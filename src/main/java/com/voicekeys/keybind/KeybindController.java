package com.voicekeys.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import com.voicekeys.VoiceKeysMod;
import com.voicekeys.config.VoiceKeysConfig;
import com.voicekeys.mixin.KeyMappingAccessor;
import com.voicekeys.mixin.KeyboardHandlerInvoker;
import com.voicekeys.voice.VoiceRecognizer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Logique côté thread client : lit la file de mots reconnus et déclenche le keybind
 * correspondant. Tous les appels Minecraft se font ici, à chaque tick.
 * <p>
 * Pour chaque déclenchement, on injecte un <b>vrai événement clavier</b>
 * ({@code KeyboardHandler.keyPress}) sur la touche physique liée au keybind, exactement
 * comme si l'utilisateur appuyait dessus. C'est le seul moyen fiable de déclencher les
 * mods (comme Wynntils) qui écoutent les événements clavier bruts et pas seulement
 * l'état {@code isDown()} du KeyMapping. Repli sur {@code setDown()} si la touche n'est
 * pas liée à une touche clavier physique.
 */
public final class KeybindController {

    /** Nombre de ticks pendant lesquels la touche reste « enfoncée » avant relâchement. */
    private static final int HOLD_TICKS = 3;

    private final VoiceKeysConfig config;
    private final VoiceRecognizer recognizer;

    /** mot -> nom interne du keybind, d'après la config. */
    private final Map<String, String> wordToKeybindName = new HashMap<>();
    /** Dernier instant (ms) où chaque mot a déclenché, pour le cooldown. */
    private final Map<String, Long> lastTrigger = new HashMap<>();

    /** nom interne -> KeyMapping, construit au premier tick puis mis en cache. */
    private Map<String, KeyMapping> keybindCache;
    /** Relâchements planifiés (compte à rebours en ticks). */
    private final List<Pending> pending = new ArrayList<>();

    public KeybindController(VoiceKeysConfig config, VoiceRecognizer recognizer) {
        this.config = config;
        this.recognizer = recognizer;
        for (VoiceKeysConfig.Binding b : config.bindings) {
            if (b.word != null && b.keybind != null && !b.word.isBlank()) {
                wordToKeybindName.put(b.word.toLowerCase().trim(), b.keybind);
            }
        }
    }

    /** Appelé à chaque fin de tick client. */
    public void onClientTick(Minecraft client) {
        // 1. Décompte et exécute les relâchements arrivés à échéance.
        if (!pending.isEmpty()) {
            pending.removeIf(p -> {
                if (--p.ticksLeft <= 0) {
                    p.release.run();
                    return true;
                }
                return false;
            });
        }

        if (!recognizer.isRunning()) {
            return;
        }

        // 2. Cache des keybinds, construit au premier tick utile.
        if (keybindCache == null) {
            buildKeybindCache(client);
        }

        // 3. Contexte : ne rien déclencher sans joueur ou avec un écran ouvert.
        boolean blocked = client.player == null || client.screen != null;

        String word;
        while ((word = recognizer.poll()) != null) {
            if (blocked) {
                continue;
            }
            trigger(client, word);
        }
    }

    private void trigger(Minecraft client, String word) {
        String keybindName = wordToKeybindName.get(word);
        if (keybindName == null) {
            VoiceKeysMod.LOGGER.info("Mot reçu '{}' : aucun binding configuré.", word);
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastTrigger.get(word);
        if (last != null && (now - last) < config.cooldownMs) {
            return; // cooldown
        }

        KeyMapping km = keybindCache.get(keybindName);
        if (km == null) {
            VoiceKeysMod.LOGGER.warn("Mot '{}' : keybind introuvable '{}'", word, keybindName);
            return;
        }

        lastTrigger.put(word, now);
        pressKeybind(client, km, word, keybindName);

        // Retour visuel dans le chat.
        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.literal("§bVoiceKeys §7» §fSort lancé : §a" + word), false);
        }
    }

    /** Appuie sur le keybind, en privilégiant un vrai événement clavier. */
    private void pressKeybind(Minecraft client, KeyMapping km, String word, String keybindName) {
        InputConstants.Key key = ((KeyMappingAccessor) (Object) km).getBoundKey();

        if (key != null && key.getType() == InputConstants.Type.KEYSYM && key.getValue() >= 0) {
            long window = client.getWindow().handle();
            int keyCode = key.getValue();
            int scancode = GLFW.glfwGetKeyScancode(keyCode);
            KeyEvent event = new KeyEvent(keyCode, scancode, 0);
            KeyboardHandlerInvoker handler = (KeyboardHandlerInvoker) (Object) client.keyboardHandler;

            handler.voicekeys$keyPress(window, GLFW.GLFW_PRESS, event);
            pending.add(new Pending(HOLD_TICKS,
                    () -> handler.voicekeys$keyPress(window, GLFW.GLFW_RELEASE, event)));

            VoiceKeysMod.LOGGER.info("Déclenchement (clavier) : '{}' -> '{}' (key {})",
                    word, keybindName, key.getName());
        } else {
            // Repli : touche non liée au clavier (souris ou non assignée).
            km.setDown(true);
            KeyMappingAccessor acc = (KeyMappingAccessor) (Object) km;
            acc.setClickCount(acc.getClickCount() + 1);
            pending.add(new Pending(HOLD_TICKS, () -> km.setDown(false)));

            VoiceKeysMod.LOGGER.info("Déclenchement (setDown) : '{}' -> '{}'", word, keybindName);
        }
    }

    private void buildKeybindCache(Minecraft client) {
        keybindCache = new HashMap<>();
        for (KeyMapping km : client.options.keyMappings) {
            keybindCache.put(km.getName(), km);
        }
        for (Map.Entry<String, String> e : wordToKeybindName.entrySet()) {
            boolean ok = keybindCache.containsKey(e.getValue());
            VoiceKeysMod.LOGGER.info("Binding '{}' -> '{}' : {}",
                    e.getKey(), e.getValue(), ok ? "OK" : "INTROUVABLE");
        }
    }

    /** Un relâchement planifié : un compte à rebours et l'action à exécuter. */
    private static final class Pending {
        int ticksLeft;
        final Runnable release;

        Pending(int ticksLeft, Runnable release) {
            this.ticksLeft = ticksLeft;
            this.release = release;
        }
    }
}
