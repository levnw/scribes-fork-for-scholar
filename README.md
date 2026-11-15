# Scribe’s Fork for Scholar 0.3.0-beta.1

A client-side add-on for Minecraft 26.3 on Fabric or NeoForge and Scholar 1.2.12 with live, local dictation. This beta targets Windows x64, Linux x64 and ARM64, and Intel and Apple silicon Macs.

## Use

Install the Scribe’s Fork JAR and Scholar 1.2.12 build for your loader in the same client profile. On Fabric, also install Fabric API and Forge Config API Port. Restart Minecraft. Open a writable book and click the tuning fork beside the spread. On macOS, Control+V starts dictation and Command+V pastes. On Windows and Linux, Control+Shift+D starts dictation and Control+V pastes. Click a page first to dictate at a specific cursor position. Without a selection, dictation appends to the last written page in the spread or starts on the left page of an empty spread. On Apple silicon with macOS 26 or newer, grant microphone access to Scholar Dictation when prompted. With the portable backend, check microphone permissions for your launcher or Java if prompted. Wait for the Listening tooltip before speaking. The fork lights up when clicked and stays lit until dictation finishes. The page shows the draft as you speak and revises it as the recognizer receives more audio. Click again to commit the final text. Choose a microphone from Scribe’s Fork for Scholar in the Mods screen. On Fabric, the settings screen requires Mod Menu. On NeoForge, use the built-in Mods screen and select Config. System Default uses the operating system’s default input. Hover for status or errors.

Recording also stops after 45 seconds. Editing or leaving the target page cancels the recording. A transcript that exceeds the remaining page space is rejected with a tooltip. On Apple silicon with macOS 26 or newer, recognition uses the Mac’s preferred language and requires Apple on-device recognition support for that language. macOS can download the required language model on first use. The add-on starts a bundled native helper through Launch Services. It captures audio in memory and exchanges only status and text with the Minecraft process through a temporary directory. On other supported systems, Vosk uses the bundled US English model and a Java Sound microphone. Audio is processed locally.

## Build

Use Python 3, Apple Command Line Tools, and a Modrinth installation of Minecraft 26.3 with Java 25 and Fabric Loader 0.20.1. Supply a local copy of Scholar 1.2.12 and Eclipse ECJ 3.44.0. Download [Vosk 0.3.45](https://repo.maven.apache.org/maven2/com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar), the [Vosk Linux ARM64 archive](https://github.com/alphacep/vosk-api/releases/download/v0.3.45/vosk-linux-aarch64-0.3.45.zip), and the [small US English model](https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip).

```sh
python3 build.py --scholar /path/to/scholar-fabric-26.3-1.2.12.jar --ecj /path/to/ecj-3.44.0.jar --fabric-api /path/to/fabric-api.jar --modmenu /path/to/modmenu-21.0.0.jar --vosk /path/to/vosk-0.3.45.jar --vosk-linux-arm64 /path/to/vosk-linux-aarch64-0.3.45.zip --speech-model /path/to/vosk-model-small-en-us-0.15.zip
```

To build the NeoForge variant, install NeoForge 26.3.0.103 in a Minecraft directory with the official installer. Pass that directory as `--neoforge` and pass the NeoForge Scholar 1.2.12 JAR to `--scholar`. Omit `--fabric-api` and `--modmenu` for that build. Use separate output filenames for Fabric and NeoForge.

The build compiles and ad-hoc signs the macOS helper and packages it with the mod. The button sprites are in `resources/assets/scholar_dictation/textures/gui/sprites/book/`. Replace any state with an 18 × 18 transparent PNG using the same filename and rebuild.
