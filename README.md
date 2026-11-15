# Scribe’s Fork for Scholar 0.2.0

A client-side add-on for Minecraft 26.3 Fabric and Scholar 1.2.12 on an Apple silicon Mac running macOS 26 or newer.

## Use

Place `scholar-dictation-0.2.0.jar` in the same client profile as Scholar, Fabric API, and Forge Config API Port. Restart Minecraft. Open a writable book and click the tuning fork beside the spread. Control+V also starts dictation. Use Command+V to paste into the book. Click a page first to dictate at a specific cursor position. Without a selection, dictation appends to the last written page in the spread or starts on the left page of an empty spread. On first use, grant macOS microphone access to Scholar Dictation. Wait for the Listening tooltip before speaking. The fork lights up when clicked and stays lit until dictation finishes. The page shows the draft as you speak and revises it as the recognizer receives more audio. Click again to commit the final text. Choose an input under Mods → Scribe’s Fork for Scholar → Configure. System Default follows macOS. Mod Menu is optional for dictation. Hover for status or errors.

Recording also stops after 45 seconds. Editing or leaving the target page cancels the recording. A transcript that exceeds the remaining page space is rejected with a tooltip. Recognition uses the Mac’s preferred language and requires Apple on-device recognition support for that language. macOS can download the required language model on first use. The add-on starts a bundled native helper through Launch Services. It captures audio in memory and exchanges only status and text with the Minecraft process through a temporary directory.

## Build

Use Python 3, Apple Command Line Tools, and a Modrinth installation of Minecraft 26.3 with Java 25 and Fabric Loader 0.20.1. Supply a local copy of Scholar 1.2.12 and Eclipse ECJ 3.44.0.

```sh
python3 build.py --scholar /path/to/scholar-fabric-26.3-1.2.12.jar --ecj /path/to/ecj-3.44.0.jar --fabric-api /path/to/fabric-api.jar --modmenu /path/to/modmenu-21.0.0.jar
```

The build compiles and ad-hoc signs the macOS helper and packages it with the Fabric mod. The button sprites are in `resources/assets/scholar_dictation/textures/gui/sprites/book/`. Replace any state with an 18 × 18 transparent PNG using the same filename and rebuild.
