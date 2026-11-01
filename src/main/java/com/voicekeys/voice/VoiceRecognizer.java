package com.voicekeys.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.voicekeys.VoiceKeysMod;
import com.voicekeys.config.VoiceKeysConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Capture micro + reconnaissance Vosk, entièrement hors du thread de rendu.
 * <p>
 * Le seul point de contact avec le reste du mod est {@link #poll()} : le thread
 * client y lit les mots reconnus. Aucun appel Minecraft n'est fait ici.
 */
public final class VoiceRecognizer {

    /** PCM 16 kHz, mono, 16 bits signés, little-endian — le format attendu par Vosk. */
    private static final float SAMPLE_RATE = 16_000f;

    /**
     * Nombre de morceaux de silence (~40 ms chacun) après la parole avant de forcer la
     * finalisation. 4 ≈ 160 ms : assez court pour être réactif, assez long pour ne pas
     * couper entre deux syllabes.
     */
    private static final int SILENCE_BUFFERS = 4;
    private static final AudioFormat FORMAT =
            new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    private final double minConfidence;
    private final boolean triggerOnPartial;
    private final int partialConfirmations;
    private final String micName;
    private final Set<String> vocabulary;
    private final String grammarJson;

    /**
     * Mots déjà déclenchés dans la phrase (utterance) en cours, pour n'envoyer un mot
     * qu'une seule fois entre deux silences. Accédé uniquement par le thread audio.
     */
    private final Set<String> firedThisUtterance = new HashSet<>();

    /**
     * Compteur de stabilité en mode partiel : nombre de relevés consécutifs où chaque
     * mot est resté présent. On ne déclenche qu'au-delà du seuil, pour filtrer le bruit.
     */
    private final Map<String, Integer> partialSeen = new HashMap<>();

    /** File thread-safe : écrite par le thread audio, lue par le thread client. */
    private final Queue<String> recognizedWords = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;
    private volatile boolean wantListening = false;

    private Thread thread;
    private Model model;
    private Recognizer recognizer;
    private TargetDataLine line;

    public VoiceRecognizer(VoiceKeysConfig config) {
        this.minConfidence = config.minConfidence;
        this.triggerOnPartial = config.triggerOnPartial;
        this.partialConfirmations = Math.max(1, config.partialConfirmations);
        this.micName = config.micName == null ? "" : config.micName.trim();
        this.vocabulary = new LinkedHashSet<>();
        for (VoiceKeysConfig.Binding b : config.bindings) {
            if (b.word != null && !b.word.isBlank()) {
                vocabulary.add(b.word.toLowerCase().trim());
            }
        }
        this.grammarJson = buildGrammar(vocabulary);
    }

    /**
     * Grammaire Vosk : une liste JSON des mots du vocabulaire plus {@code [unk]}.
     * Limiter le vocabulaire réduit fortement les fausses détections et la latence.
     */
    private static String buildGrammar(Set<String> words) {
        JsonArray arr = new JsonArray();
        for (String w : words) {
            arr.add(w);
        }
        arr.add("[unk]");
        return arr.toString();
    }

    /**
     * Charge le modèle, ouvre le micro et démarre le thread d'écoute.
     *
     * @return {@code true} si tout est prêt ; {@code false} en cas d'erreur (message déjà loggé).
     *         Le jeu ne doit jamais planter : toute erreur est capturée ici.
     */
    public boolean start(String modelPath, boolean listenImmediately) {
        if (running) {
            return true;
        }

        // --- Modèle Vosk ---
        Path resolved = resolveModelPath(modelPath);
        if (!Files.isDirectory(resolved)) {
            VoiceKeysMod.LOGGER.error("Modèle Vosk introuvable : {} (dossier inexistant)", resolved);
            VoiceKeysMod.reportError("Modèle vocal introuvable : " + resolved
                    + ". Placez le modèle vosk-model-small-fr dans ce dossier.");
            return false;
        }

        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(resolved.toString());
            recognizer = new Recognizer(model, SAMPLE_RATE, grammarJson);
            recognizer.setWords(true);
        } catch (Throwable t) {
            VoiceKeysMod.LOGGER.error("Échec du chargement du modèle Vosk ({})", resolved, t);
            VoiceKeysMod.reportError("Impossible de charger le modèle vocal : " + t.getMessage());
            closeQuietly();
            return false;
        }

        // Signale dans le log tout mot absent du vocabulaire du modèle (non bloquant).
        warnUnknownWords();

        // --- Micro ---
        try {
            line = openLine(micName);
            line.open(FORMAT);
        } catch (Throwable t) {
            VoiceKeysMod.LOGGER.error("Micro indisponible ou occupé", t);
            VoiceKeysMod.reportError("Micro indisponible ou occupé : " + t.getMessage());
            closeQuietly();
            return false;
        }

        running = true;
        wantListening = listenImmediately;

        thread = new Thread(this::loop, "VoiceKeys-Audio");
        thread.setDaemon(true);
        // Priorité un cran au-dessus : l'audio est traité sans attendre derrière le reste.
        try {
            thread.setPriority(Thread.NORM_PRIORITY + 2);
        } catch (Throwable ignored) {
        }
        thread.start();

        VoiceKeysMod.LOGGER.info("Reconnaissance vocale démarrée (vocabulaire : {})", vocabulary);
        return true;
    }

    /** Boucle d'écoute, exécutée sur le thread audio dédié. */
    private void loop() {
        // Buffer très court (~40 ms à 16 kHz) : on traite l'audio par petits morceaux
        // pour réagir au plus vite, au lieu d'attendre un gros bloc.
        byte[] buffer = new byte[1280];
        boolean active = false;

        // Détection d'activité vocale (VAD) : on repère quand la parole commence puis
        // s'arrête, pour forcer Vosk à finaliser tout de suite (résultat AVEC confiance,
        // sans attendre le long silence par défaut). On filtre aussi le bruit de fond.
        double noiseFloor = 300.0;
        boolean speechActive = false;
        int silenceRun = 0;

        while (running) {
            // Applique les transitions ON/OFF ici, pour que toutes les opérations
            // sur la ligne audio restent sur ce seul thread.
            if (wantListening && !active) {
                line.flush();
                try {
                    recognizer.reset();
                } catch (Throwable ignored) {
                    // reset absent sur certaines versions : sans gravité.
                }
                firedThisUtterance.clear();
                partialSeen.clear();
                speechActive = false;
                silenceRun = 0;
                line.start();
                active = true;
            } else if (!wantListening && active) {
                line.stop();
                line.flush();
                active = false;
            }

            if (!active) {
                sleep(50);
                continue;
            }

            int n = line.read(buffer, 0, buffer.length);
            if (n <= 0) {
                continue;
            }

            // --- VAD : énergie moyenne de ce morceau ---
            double amp = meanAbsAmplitude(buffer, n);
            double enterThreshold = noiseFloor * 2.5 + 200.0;
            double exitThreshold = noiseFloor * 1.6 + 120.0;
            if (!speechActive) {
                if (amp > enterThreshold) {
                    speechActive = true;
                    silenceRun = 0;
                } else {
                    // Silence : on ajuste lentement le niveau de bruit ambiant.
                    noiseFloor = noiseFloor * 0.97 + amp * 0.03;
                }
            } else {
                silenceRun = (amp < exitThreshold) ? silenceRun + 1 : 0;
            }

            try {
                if (recognizer.acceptWaveForm(buffer, n)) {
                    // Vosk a détecté lui-même une fin de phrase.
                    handleFinal(recognizer.getResult());
                    endUtterance();
                    speechActive = false;
                    silenceRun = 0;
                } else {
                    // En réaction rapide : on tente le déclenchement immédiat, mais
                    // uniquement pendant qu'il y a vraiment de la parole (anti-bruit).
                    if (triggerOnPartial && speechActive) {
                        handlePartial(recognizer.getPartialResult());
                    }
                    // Fin de mot détectée par notre VAD (~SILENCE_BUFFERS x 40 ms) :
                    // on force la finalisation immédiate -> résultat avec confiance, vite.
                    if (speechActive && silenceRun >= SILENCE_BUFFERS) {
                        handleFinal(recognizer.getFinalResult());
                        try {
                            recognizer.reset();
                        } catch (Throwable ignored) {
                        }
                        endUtterance();
                        speechActive = false;
                        silenceRun = 0;
                    }
                }
            } catch (Throwable t) {
                VoiceKeysMod.LOGGER.error("Erreur pendant la reconnaissance", t);
            }
        }
    }

    /** Réinitialise l'état « phrase en cours » après un résultat final. */
    private void endUtterance() {
        firedThisUtterance.clear();
        partialSeen.clear();
    }

    /** Amplitude moyenne absolue (0..32767) d'un buffer PCM 16 bits little-endian. */
    private static double meanAbsAmplitude(byte[] buffer, int length) {
        int samples = length / 2;
        if (samples == 0) {
            return 0.0;
        }
        long sum = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short s = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            sum += Math.abs((int) s);
        }
        return (double) sum / samples;
    }

    /**
     * Résultat final (fin de phrase). Avec {@code setWords(true)}, chaque mot possède
     * une confiance ({@code conf}). On n'enfile que les mots du vocabulaire dont la
     * confiance atteint le seuil, et qui n'ont pas déjà été envoyés en partiel.
     */
    private void handleFinal(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        JsonObject obj;
        try {
            obj = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return;
        }

        if (obj.has("result") && obj.get("result").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("result")) {
                JsonObject wordObj = el.getAsJsonObject();
                String word = wordObj.has("word") ? wordObj.get("word").getAsString() : null;
                double conf = wordObj.has("conf") ? wordObj.get("conf").getAsDouble() : 0.0;
                if (word == null) {
                    continue;
                }
                word = word.toLowerCase().trim();
                if (!vocabulary.contains(word) || firedThisUtterance.contains(word)) {
                    continue;
                }
                if (conf < minConfidence) {
                    VoiceKeysMod.LOGGER.debug("Mot '{}' ignoré (confiance {} < {})", word, conf, minConfidence);
                    continue;
                }
                recognizedWords.add(word);
                firedThisUtterance.add(word);
                VoiceKeysMod.LOGGER.debug("Mot reconnu (final) : '{}' (confiance {})", word, conf);
            }
        }
    }

    /**
     * Résultat partiel (phrase en cours). Format Vosk : {@code {"partial":"mot mot"}}.
     * Pas de confiance disponible, donc on utilise la <b>stabilité</b> comme filtre :
     * un mot n'est déclenché que s'il reste présent sur {@code partialConfirmations}
     * relevés <b>consécutifs</b>. Le bruit produit des mots instables (qui clignotent)
     * et se fait filtrer, tandis que la vraie parole est stable et part quasi aussitôt.
     */
    private void handlePartial(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        JsonObject obj;
        try {
            obj = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        if (!obj.has("partial")) {
            return;
        }
        String partial = obj.get("partial").getAsString();

        // Mots du vocabulaire présents dans ce relevé.
        Set<String> present = new HashSet<>();
        if (partial != null && !partial.isBlank()) {
            for (String token : partial.toLowerCase().trim().split("\\s+")) {
                if (vocabulary.contains(token)) {
                    present.add(token);
                }
            }
        }

        // Les mots absents de ce relevé perdent leur compteur (exigence de consécutivité).
        partialSeen.keySet().retainAll(present);

        for (String token : present) {
            if (firedThisUtterance.contains(token)) {
                continue;
            }
            int count = partialSeen.merge(token, 1, Integer::sum);
            if (count >= partialConfirmations) {
                recognizedWords.add(token);
                firedThisUtterance.add(token);
                VoiceKeysMod.LOGGER.debug("Mot reconnu (partiel, stable x{}) : '{}'", count, token);
            }
        }
    }

    private void warnUnknownWords() {
        if (recognizer == null) {
            return;
        }
        // Vosk ne fournit pas de liste du vocabulaire du modèle via l'API Java ;
        // on se contente de rappeler les mots attendus pour faciliter le diagnostic.
        VoiceKeysMod.LOGGER.info("Mots attendus (doivent exister dans le modèle FR) : {}", vocabulary);
    }

    /** Lu par le thread client à chaque tick. {@code null} si la file est vide. */
    public String poll() {
        return recognizedWords.poll();
    }

    public void setListening(boolean listening) {
        this.wantListening = listening;
    }

    public boolean isListening() {
        return wantListening;
    }

    public boolean isRunning() {
        return running;
    }

    /** Arrête le thread, libère le micro et le recognizer. À appeler à la fermeture du jeu. */
    public void stop() {
        running = false;
        wantListening = false;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        closeQuietly();
        recognizedWords.clear();
        VoiceKeysMod.LOGGER.info("Reconnaissance vocale arrêtée, ressources libérées.");
    }

    private void closeQuietly() {
        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (Throwable ignored) {
            }
            line = null;
        }
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Throwable ignored) {
            }
            recognizer = null;
        }
        if (model != null) {
            try {
                model.close();
            } catch (Throwable ignored) {
            }
            model = null;
        }
    }

    /**
     * Ouvre une ligne d'entrée. Si {@code micName} est renseigné, cherche le mixeur
     * correspondant ; sinon utilise le micro par défaut du système.
     */
    private static TargetDataLine openLine(String micName) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);

        if (micName != null && !micName.isBlank()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().equals(micName)) {
                    Mixer mixer = AudioSystem.getMixer(mi);
                    if (mixer.isLineSupported(info)) {
                        VoiceKeysMod.LOGGER.info("Micro sélectionné : {}", micName);
                        return (TargetDataLine) mixer.getLine(info);
                    }
                }
            }
            VoiceKeysMod.LOGGER.warn("Micro '{}' introuvable ou incompatible, retour au micro par défaut", micName);
        }

        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Format audio non supporté par le micro par défaut");
        }
        return (TargetDataLine) AudioSystem.getLine(info);
    }

    /**
     * Liste les noms des périphériques d'entrée compatibles (pour le menu de config).
     * Utilisable sans démarrer la reconnaissance.
     */
    public static List<String> availableMics() {
        List<String> names = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mi);
                if (mixer.isLineSupported(info) && !names.contains(mi.getName())) {
                    names.add(mi.getName());
                }
            } catch (Throwable ignored) {
                // certains mixeurs lèvent une exception : on les ignore.
            }
        }
        return names;
    }

    private static Path resolveModelPath(String modelPath) {
        Path p = Path.of(modelPath);
        if (p.isAbsolute()) {
            return p;
        }
        return FabricLoader.getInstance().getGameDir().resolve(modelPath).normalize();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
