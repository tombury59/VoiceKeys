<div align="center">

# 🎙️ VoiceKeys

**Lance tes sorts à la voix sur Wynncraft — sans quitter ta souris des yeux.**

Dis « un » → ton premier sort part. Un mod Fabric qui écoute ton micro et appuie
sur la touche à ta place, 100 % en local.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-brightgreen)
![Loader](https://img.shields.io/badge/Loader-Fabric-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![License](https://img.shields.io/github/license/tombury59/VoiceKeys)
![Release](https://img.shields.io/github/v/release/tombury59/VoiceKeys?include_prereleases)

[Télécharger](https://github.com/tombury59/VoiceKeys/releases) ·
[Signaler un bug](https://github.com/tombury59/VoiceKeys/issues) ·
[Proposer une idée](https://github.com/tombury59/VoiceKeys/issues)

</div>

---

## En bref

- **Un mot = une touche.** Tu parles, le keybind se déclenche comme une vraie frappe.
- **100 % hors ligne.** Reconnaissance vocale **française** embarquée (Vosk) : **rien** ne sort de ton PC, ça marche même sans internet.
- **Pensé pour Wynncraft.** Sorts, monture, menus… et compatible avec **n'importe quel** keybind (vanilla ou moddé, Wynntils inclus).
- **Menu en jeu** (touche **V**) : choix du micro, mots, réglages — rien à éditer à la main.
- **Discret et sûr.** Mode *push-to-talk* (micro actif seulement quand tu tiens une touche) + indicateur à l'écran.
- **Rapide ET fiable.** Détection de fin de mot + filtre anti-bruit pour déclencher vite, sans fausses détections.

## 🧐 C'est quoi ?

VoiceKeys est un mod **client** pour Minecraft (Fabric 1.21.11). Il écoute ton micro
sur un thread dédié, reconnaît les mots que **tu** as configurés, et simule l'appui de
la touche associée — exactement comme si tu la pressais au clavier.

Toute la reconnaissance se fait **localement** grâce à [Vosk](https://alphacephei.com/vosk/)
et un petit modèle français (~40 Mo). Aucun serveur, aucun cloud, aucun enregistrement :
l'audio est traité à la volée puis jeté. Rien n'est installé côté serveur Minecraft.

## Utilisation

1. En jeu, appuie sur **V** pour ouvrir le menu.
2. Choisis ton **micro** et vérifie que l'écoute est sur **ON**.
3. Dis un mot configuré — par défaut :

| Tu dis… | Il se passe… |
| --- | --- |
| « un » / « deux » / « trois » / « quatre » | Sorts 1 à 4 (Wynntils) |
| « cheval » | Monture |

Un petit **🎤 en bas à gauche** indique quand le micro écoute (vert) ou non (gris).

<!-- Astuce : ajoute une capture d'écran du menu ici, ex. ![Menu](docs/menu.png) -->

## Installation

> Pré-requis : **Minecraft 1.21.11** avec **Fabric Loader** + **Fabric API**
> (et **Wynntils** si tu veux les sorts). Côté client uniquement.

1. **Télécharge** `voicekeys-x.x.x.jar` depuis la [page Releases](https://github.com/tombury59/VoiceKeys/releases)
   et mets-le dans ton dossier **`mods/`**.
2. **Installe le modèle vocal** (non inclus, trop lourd) : télécharge
   **`vosk-model-small-fr`** sur [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models),
   décompresse-le, et place le dossier ici :
   ```
   .minecraft/config/voicekeys/model/vosk-model-small-fr/   (am/ conf/ graph/ …)
   ```
3. Lance le jeu. Le fichier `config/voicekeys.json` se crée tout seul au premier démarrage.

Modèle manquant ou micro débranché ? Pas de crash : un **message clair** s'affiche
dans le chat, tu corriges, tu relances.

## ⚙️ Configuration

Tout est éditable dans le menu **V**, mais tu peux aussi modifier `config/voicekeys.json` :

```json
{
  "micName": "",
  "enabledOnStart": true,
  "minConfidence": 0.7,
  "cooldownMs": 300,
  "triggerOnPartial": true,
  "partialConfirmations": 2,
  "pushToTalk": false,
  "bindings": [
    { "word": "un", "keybind": "wynntils.keybind.castFirstSpell" }
  ]
}
```

Le nom du keybind est celui écrit dans `options.txt` après `key_`
(ex. `key_wynntils.keybind.castFirstSpell` → `wynntils.keybind.castFirstSpell`,
`key_key.inventory` → `key.inventory`).

## 💬 Retours & contribution

Une idée, un bug, un mot mal reconnu ? **Ouvre une
[issue](https://github.com/tombury59/VoiceKeys/issues)** — toute remarque est la bienvenue.

## Compiler soi-même

Besoin de **Java 21**.

```bash
git clone https://github.com/tombury59/VoiceKeys.git
cd VoiceKeys
./gradlew build
```

Le jar est généré dans `build/libs/`. Pour tester en jeu : `./gradlew runClient`
(place d'abord le modèle dans `run/config/voicekeys/model/`).

## Pour aller plus loin

- [Vosk](https://alphacephei.com/vosk/) — le moteur de reconnaissance vocale hors ligne
- [Fabric](https://fabricmc.net/) — le mod loader
- [Wynntils](https://wynntils.com/) — le mod Wynncraft dont viennent les keybinds de sorts

## 📄 Licence

Distribué sous licence [MIT](LICENSE).
