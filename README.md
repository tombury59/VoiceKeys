# VoiceKeys — Mod vocal Fabric (mot → keybind)

Mod client Fabric qui écoute le micro et déclenche un keybind quand un mot configuré
est reconnu, comme un appui de touche. Exemple : dire « un » déclenche le keybind
Wynntils *Cast 1st Spell*. Reconnaissance 100 % hors ligne, en français, via Vosk.

**Règle de base :** un mot = un keybind = un appui. Pas de séquences ni de macros.
Côté client uniquement, rien à installer sur le serveur.

## Environnement

| Élément | Choix |
| --- | --- |
| Minecraft | 1.21.11 |
| Loader | Fabric Loader 0.20.1 + Fabric API 0.140.2 |
| Java | 21 |
| Build | Gradle 9.8.2 (wrapper) + Fabric Loom 1.14 |
| Mappings | Mojang officielles (noms `KeyMapping`, `getName()`, `clickCount`…) |
| Reconnaissance | Vosk `com.alphacephei:vosk:0.3.45`, embarqué jar-in-jar (+ JNA) |
| Modèle | `vosk-model-small-fr`, **non embarqué**, à placer dans `config/voicekeys/model/` |
| Capture audio | `javax.sound.sampled` (`TargetDataLine`), PCM 16 kHz mono 16 bits |

## Compiler

Un JDK 21 est requis. Depuis le dossier du projet :

```bash
./gradlew build
```

Le mod est produit dans `build/libs/voicekeys-1.0.0.jar` (≈ 27 Mo, Vosk + JNA inclus).
Copiez-le dans le dossier `mods/` de votre instance.

> Note : le wrapper Gradle utilise le JDK indiqué par `JAVA_HOME`. Sous Windows,
> pointez-le vers votre JDK 21, par ex. `set JAVA_HOME=C:\Users\...\.jdks\temurin-21.0.10`.

## Installer le modèle vocal

1. Téléchargez **vosk-model-small-fr** (≈ 40 Mo) sur <https://alphacephei.com/vosk/models>.
2. Décompressez-le.
3. Placez le dossier du modèle ici (dans votre instance `.minecraft`) :

   ```
   .minecraft/config/voicekeys/model/vosk-model-small-fr/
       am/  conf/  graph/  ivector/  ...
   ```

Le chemin est réglable via `modelPath` dans `config/voicekeys.json`.

## Configuration — `config/voicekeys.json`

Créé automatiquement au premier lancement avec cet exemple :

```json
{
  "modelPath": "config/voicekeys/model/vosk-model-small-fr",
  "enabledOnStart": true,
  "minConfidence": 0.7,
  "cooldownMs": 300,
  "bindings": [
    { "word": "un",     "keybind": "Cast 1st Spell" },
    { "word": "deux",   "keybind": "Cast 2nd Spell" },
    { "word": "trois",  "keybind": "Cast 3rd Spell" },
    { "word": "quatre", "keybind": "Cast 4th Spell" },
    { "word": "cheval", "keybind": "Mount Horse" },
    { "word": "inventaire", "keybind": "key.inventory" }
  ]
}
```

| Champ | Rôle |
| --- | --- |
| `modelPath` | Dossier du modèle Vosk (relatif au dossier de jeu, ou absolu) |
| `enabledOnStart` | Écoute active dès le lancement |
| `minConfidence` | Seuil de confiance (0 à 1) sous lequel le mot est ignoré |
| `cooldownMs` | Délai minimum entre deux déclenchements du **même** mot |
| `bindings[].word` | Mot à prononcer, en minuscules, présent dans le vocabulaire du modèle |
| `bindings[].keybind` | Nom interne du `KeyMapping` (ce qui suit `key_` dans `options.txt`) |

**Trouver le bon nom de keybind :** ouvrez `.minecraft/options.txt`. Chaque ligne
`key_XXX:...` donne le nom `XXX`. Pour Wynntils : `key_Cast 1st Spell` → `Cast 1st Spell`.
Pour le vanilla : `key_key.inventory` → `key.inventory`.

## Utilisation

- Touche **V** par défaut : active / coupe l'écoute (message dans l'action bar
  « Écoute : ON / OFF »). Rebindable dans Options → Commandes → catégorie VoiceKeys.
- Dites un mot configuré : le keybind associé est déclenché pour un tick.
- Rien ne se déclenche si aucun joueur n'est en jeu ou si un écran/menu est ouvert
  (chat, inventaire…).

## Comment ça marche

1. **Config** chargée au démarrage (`ConfigManager`), créée si absente.
2. **Modèle Vosk** chargé dans `VoiceRecognizer` ; en cas d'absence/erreur, message
   clair dans le log + chat, **sans crash**.
3. **Écoute micro** sur un thread dédié (`VoiceKeys-Audio`), recognizer en **mode
   grammaire** limité aux mots de la config + `[unk]` (moins de fausses détections).
4. **Mot reconnu** (confiance ≥ `minConfidence`) → ajouté à une file thread-safe.
   Aucun appel Minecraft depuis le thread audio.
5. **Déclenchement** : à chaque tick client, `KeybindController` lit la file, retrouve
   le `KeyMapping` (cache construit au 1er tick) et simule un appui d'un tick —
   `setDown(true)` + incrément de `clickCount` via mixin accessor, relâché au tick
   suivant, pour que les mods lisant `isDown()` comme `consumeClick()` réagissent.
6. **Touche on/off** : coupe/réactive l'écoute.

## Lancer un client de dev

```bash
./gradlew runClient
```

(Nécessite le modèle dans `run/config/voicekeys/model/` et un micro.)

## Dépannage

- *Modèle vocal introuvable* → vérifiez `modelPath` et que le dossier contient bien
  `am/`, `conf/`, `graph/`…
- *Micro indisponible ou occupé* → fermez les autres applis qui utilisent le micro.
- Un mot qui ne se déclenche jamais → il est peut-être absent du vocabulaire du modèle
  FR, ou la confiance est trop basse (`minConfidence`). Regardez le log (`VoiceKeys`).
- Un keybind introuvable → le nom dans `keybind` ne correspond à aucune touche ;
  comparez avec `options.txt` (un avertissement est écrit dans le log au 1er tick).

## Limites (hors MVP)

Pas d'écran de config en jeu, pas de choix du micro en jeu, pas de HUD permanent,
pas de déclenchement sur résultat partiel, pas de rechargement à chaud, FR uniquement.
