package com.voicekeys.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Modèle de la configuration, calqué sur {@code config/voicekeys.json}.
 * Les noms de champs correspondent exactement aux clés JSON (dé-sérialisation Gson directe).
 */
public class VoiceKeysConfig {

    /** Dossier du modèle Vosk, relatif au dossier de jeu. */
    public String modelPath = "config/voicekeys/model/vosk-model-small-fr";

    /**
     * Nom du périphérique micro à utiliser (tel qu'affiché par le système).
     * Vide = micro par défaut du système.
     */
    public String micName = "";

    /** Écoute active dès le lancement. */
    public boolean enabledOnStart = true;

    /** Seuil de confiance (0 à 1) sous lequel un mot reconnu est ignoré. */
    public double minConfidence = 0.7;

    /** Délai minimum, en millisecondes, entre deux déclenchements du même mot. */
    public long cooldownMs = 300;

    /**
     * Déclenche dès le résultat partiel (sans attendre la fin de phrase), pour réduire
     * fortement la latence. Les partiels n'ont pas de score de confiance : {@code minConfidence}
     * ne s'applique alors qu'aux résultats finaux. Mettre à {@code false} pour ne déclencher
     * qu'en fin de phrase (plus de latence, filtre de confiance appliqué partout).
     */
    public boolean triggerOnPartial = true;

    /**
     * Mode « push-to-talk » : le micro n'écoute que pendant qu'on tient la touche dédiée
     * (à assigner dans Options &gt; Commandes &gt; VoiceKeys). Pratique et plus discret :
     * aucune écoute hors appui.
     */
    public boolean pushToTalk = false;

    /**
     * Mode « Réaction rapide » : nombre de relevés partiels consécutifs où un mot doit
     * rester présent avant d'être déclenché. 1 = le plus rapide (plus de faux positifs),
     * 2 = équilibré, 3 = plus sûr. Compense l'absence de score de confiance sur les partiels.
     */
    public int partialConfirmations = 2;

    /** Associations mot -> keybind. */
    public List<Binding> bindings = new ArrayList<>();

    /** Une association d'un mot prononcé vers un keybind. */
    public static class Binding {
        /** Mot à prononcer, en minuscules, présent dans le vocabulaire du modèle. */
        public String word = "";
        /** Nom interne du KeyMapping (ce qui suit {@code key_} dans options.txt). */
        public String keybind = "";

        public Binding() {
        }

        public Binding(String word, String keybind) {
            this.word = word;
            this.keybind = keybind;
        }
    }

    /** Copie profonde, pour éditer sans toucher la config active (bouton Annuler). */
    public VoiceKeysConfig copy() {
        VoiceKeysConfig c = new VoiceKeysConfig();
        c.modelPath = this.modelPath;
        c.micName = this.micName;
        c.enabledOnStart = this.enabledOnStart;
        c.minConfidence = this.minConfidence;
        c.cooldownMs = this.cooldownMs;
        c.triggerOnPartial = this.triggerOnPartial;
        c.pushToTalk = this.pushToTalk;
        c.partialConfirmations = this.partialConfirmations;
        c.bindings = new ArrayList<>();
        for (Binding b : this.bindings) {
            c.bindings.add(new Binding(b.word, b.keybind));
        }
        return c;
    }

    /** Construit la configuration d'exemple écrite au premier lancement. */
    public static VoiceKeysConfig createDefault() {
        VoiceKeysConfig cfg = new VoiceKeysConfig();
        // Noms internes réels des keybinds Wynntils (voir options.txt après "key_").
        cfg.bindings.add(new Binding("un", "wynntils.keybind.castFirstSpell"));
        cfg.bindings.add(new Binding("deux", "wynntils.keybind.castSecondSpell"));
        cfg.bindings.add(new Binding("trois", "wynntils.keybind.castThirdSpell"));
        cfg.bindings.add(new Binding("quatre", "wynntils.keybind.castFourthSpell"));
        cfg.bindings.add(new Binding("cheval", "wynntils.keybind.mountHorse"));
        cfg.bindings.add(new Binding("inventaire", "key.inventory"));
        return cfg;
    }
}
