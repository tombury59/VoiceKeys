package com.voicekeys;

import com.mojang.blaze3d.platform.InputConstants;
import com.voicekeys.config.ConfigManager;
import com.voicekeys.config.VoiceKeysConfig;
import com.voicekeys.gui.VoiceKeysConfigScreen;
import com.voicekeys.keybind.KeybindController;
import com.voicekeys.voice.VoiceRecognizer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Point d'entrée client du mod VoiceKeys.
 * Ordre de traitement : config -> modèle Vosk -> écoute micro -> file de mots ->
 * déclenchement du keybind au tick -> touche on/off.
 */
public class VoiceKeysMod implements ClientModInitializer {

    public static final String MOD_ID = "voicekeys";
    public static final Logger LOGGER = LoggerFactory.getLogger("VoiceKeys");

    /** Messages d'erreur à afficher dans le chat dès qu'un joueur est présent. */
    private static final Queue<String> PENDING_MESSAGES = new ConcurrentLinkedQueue<>();

    /** Instance unique, pour que l'écran de config puisse appliquer les changements. */
    private static VoiceKeysMod instance;

    private VoiceKeysConfig config;
    private VoiceRecognizer recognizer;
    private KeybindController controller;
    private KeyMapping configKey;
    private KeyMapping pttKey;

    public static VoiceKeysMod getInstance() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;

        // 1. Config
        config = ConfigManager.loadOrCreate();

        // 2. + 3. Recognizer (chargement modèle + micro gérés dans start()).
        recognizer = new VoiceRecognizer(config);
        controller = new KeybindController(config, recognizer);

        // Touche d'ouverture du menu (V par défaut, rebindable). Le marche/arrêt de
        // l'écoute se fait désormais depuis le menu lui-même.
        // Depuis 1.21.11, le constructeur de KeyMapping attend une KeyMapping.Category
        // (enregistrée via un Identifier) au lieu d'une simple chaîne de catégorie.
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        configKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.voicekeys.config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                category
        ));

        // Touche "push-to-talk" : non assignée par défaut (à définir dans Options > Commandes).
        pttKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.voicekeys.ptt",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                category
        ));

        // Démarrage de l'écoute (après l'init du client pour éviter de bloquer le chargement).
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            boolean ok = recognizer.start(config.modelPath, config.enabledOnStart);
            if (ok && config.enabledOnStart) {
                LOGGER.info("Écoute active au démarrage.");
            }
        });

        // Libération propre à la fermeture.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (recognizer != null) {
                recognizer.stop();
            }
        });

        // Boucle de tick client.
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // Indicateur micro en bas à gauche de l'écran.
        HudRenderCallback.EVENT.register((graphics, tick) -> renderMicIndicator(graphics));

        LOGGER.info("VoiceKeys initialisé.");
    }

    private void onClientTick(Minecraft client) {
        // Ouverture du menu (seulement si aucun écran n'est déjà ouvert).
        while (configKey.consumeClick()) {
            if (client.screen == null) {
                client.setScreen(new VoiceKeysConfigScreen(null, config));
            }
        }

        // Mode push-to-talk : l'écoute suit l'état de la touche (tenue = écoute).
        if (config.pushToTalk && recognizer != null && recognizer.isRunning()) {
            recognizer.setListening(pttKey.isDown());
        }

        // Affiche les erreurs en attente dès qu'un joueur est là.
        if (client.player != null) {
            String msg;
            while ((msg = PENDING_MESSAGES.poll()) != null) {
                client.player.displayClientMessage(Component.literal("[VoiceKeys] " + msg), false);
            }
        }

        // 5. Déclenchement des keybinds.
        controller.onClientTick(client);
    }

    /** État courant de l'écoute (pour l'option du menu). */
    public boolean isListening() {
        return recognizer != null && recognizer.isRunning() && recognizer.isListening();
    }

    /** Vrai si la reconnaissance a bien démarré (modèle + micro OK). */
    public boolean isRunning() {
        return recognizer != null && recognizer.isRunning();
    }

    /** Active ou coupe l'écoute en direct, depuis le menu. */
    public void setListening(boolean listening) {
        if (recognizer == null || !recognizer.isRunning()) {
            reportError("Reconnaissance vocale non démarrée (voir le log).");
            return;
        }
        recognizer.setListening(listening);
        LOGGER.info("Écoute : {}", listening ? "ON" : "OFF");
    }

    public VoiceKeysConfig getConfig() {
        return config;
    }

    /**
     * Applique une nouvelle configuration à chaud : enregistre sur disque, arrête
     * l'ancienne reconnaissance et en redémarre une avec les nouveaux réglages (micro,
     * mots, seuils…). Appelé par l'écran de config au clic sur « Enregistrer ».
     */
    public void applyConfig(VoiceKeysConfig newConfig) {
        ConfigManager.save(newConfig);
        this.config = newConfig;

        boolean wasRunning = recognizer != null && recognizer.isRunning();
        boolean wasListening = wasRunning && recognizer.isListening();
        if (recognizer != null) {
            recognizer.stop();
        }
        recognizer = new VoiceRecognizer(newConfig);
        controller = new KeybindController(newConfig, recognizer);

        // Conserve l'état d'écoute choisi par l'utilisateur (si on tournait déjà) :
        // couper le micro puis Enregistrer ne doit pas le rallumer.
        // Sinon (premier démarrage réel), on suit « Écoute au démarrage ».
        boolean listenNow = wasRunning ? wasListening : newConfig.enabledOnStart;
        boolean ok = recognizer.start(newConfig.modelPath, listenNow);
        if (ok) {
            LOGGER.info("Configuration appliquée à chaud.");
            reportError("Configuration appliquée.");
        }
    }

    /**
     * Dessine un petit micro en bas à gauche : vert quand ça écoute, gris quand c'est
     * en pause. Masqué si la reconnaissance n'est pas démarrée ou si un écran est ouvert.
     */
    private void renderMicIndicator(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        if (recognizer == null || !recognizer.isRunning()) {
            return;
        }
        boolean listening = recognizer.isListening();

        int color = listening ? 0xFF4CD964 : 0xFF9A9A9A; // vert / gris
        int x = 4;
        int y = mc.getWindow().getGuiScaledHeight() - 13;

        // Micro stylisé (corps + pied + socle) en petits rectangles.
        g.fill(x + 2, y, x + 7, y + 6, color);        // corps (capsule)
        g.fill(x + 1, y + 1, x + 8, y + 5, color);    // léger arrondi
        g.fill(x + 4, y + 6, x + 5, y + 8, color);    // tige
        g.fill(x + 2, y + 8, x + 7, y + 9, color);    // socle

        // Texte à côté.
        String label = listening ? "ON" : "OFF";
        g.drawString(mc.font, Component.literal(label), x + 12, y, color);
    }

    /** Thread-safe : empile un message d'erreur affiché ensuite dans le chat. */
    public static void reportError(String message) {
        PENDING_MESSAGES.add(message);
    }
}
