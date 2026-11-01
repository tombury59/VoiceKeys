package com.voicekeys.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.voicekeys.VoiceKeysMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chargement et création de {@code config/voicekeys.json}.
 * Si le fichier est absent, une configuration d'exemple est écrite.
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "voicekeys.json";

    private ConfigManager() {
    }

    /** Écrit la config sur le disque (dossier de config du jeu). */
    public static void save(VoiceKeysConfig config) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config));
            VoiceKeysMod.LOGGER.info("Config enregistrée dans {}", path);
        } catch (IOException e) {
            VoiceKeysMod.LOGGER.error("Impossible d'enregistrer la config dans {}", path, e);
        }
    }

    /**
     * Charge la config depuis le dossier de config du jeu, en la créant si besoin.
     * Ne lève jamais d'exception : en cas d'échec, renvoie une config d'exemple.
     */
    public static VoiceKeysConfig loadOrCreate() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

        if (Files.notExists(path)) {
            VoiceKeysConfig def = VoiceKeysConfig.createDefault();
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(def));
                VoiceKeysMod.LOGGER.info("Config absente : exemple créé dans {}", path);
            } catch (IOException e) {
                VoiceKeysMod.LOGGER.error("Impossible d'écrire la config d'exemple dans {}", path, e);
            }
            return def;
        }

        try {
            String json = Files.readString(path);
            VoiceKeysConfig cfg = GSON.fromJson(json, VoiceKeysConfig.class);
            if (cfg == null) {
                VoiceKeysMod.LOGGER.error("Config vide ou invalide dans {}, utilisation des valeurs par défaut", path);
                return VoiceKeysConfig.createDefault();
            }
            if (cfg.bindings == null) {
                cfg.bindings = VoiceKeysConfig.createDefault().bindings;
            }
            VoiceKeysMod.LOGGER.info("Config chargée depuis {} ({} bindings)", path, cfg.bindings.size());
            return cfg;
        } catch (Exception e) {
            VoiceKeysMod.LOGGER.error("Erreur de lecture de la config {}, utilisation des valeurs par défaut", path, e);
            return VoiceKeysConfig.createDefault();
        }
    }
}
