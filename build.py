"""Build the add-on against a local Minecraft 26.3 installation."""
import argparse
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--scholar", type=Path, required=True)
parser.add_argument("--modmenu", type=Path)
parser.add_argument("--fabric-api", type=Path)
parser.add_argument("--neoforge", type=Path, help="Minecraft directory with NeoForge 26.3.0.103 installed")
parser.add_argument("--ecj", type=Path, required=True)
parser.add_argument("--vosk", type=Path, required=True)
parser.add_argument("--vosk-linux-arm64", type=Path, required=True)
parser.add_argument("--speech-model", type=Path, required=True)
parser.add_argument("--meta", type=Path, default=Path.home() / "Library/Application Support/ModrinthApp/meta")
parser.add_argument("--version", default="26.3-0.20.1")
parser.add_argument("--output", type=Path)
parser.add_argument("--reuse-helper", type=Path, help="Keep the signed helper from an earlier build")
args = parser.parse_args()
root = Path(__file__).resolve().parent
with args.speech_model.open("rb") as model:
    if hashlib.sha256(model.read()).hexdigest() != "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498":
        parser.error("--speech-model must be the upstream vosk-model-small-en-us-0.15.zip")
if not args.neoforge and not (args.fabric_api and args.modmenu):
    parser.error("Fabric builds require --fabric-api and --modmenu")

mod_version = json.loads((root / "resources/fabric.mod.json").read_text())["version"]
loader = "neoforge" if args.neoforge else "fabric"
output = args.output or Path(f"scribes-fork-for-scholar-{loader}-{mod_version}.jar")
build = root / "build"
classes = build / "classes"
if build.exists():
    shutil.rmtree(build)
classes.mkdir(parents=True)
version = args.meta / "versions" / args.version / args.version
metadata = json.loads(Path(str(version) + ".json").read_text())
java = next((args.meta / "java_versions").glob("*/Contents/Home/bin/java"))
libraries = [args.scholar]
for entry in metadata["libraries"]:
    artifact = entry.get("downloads", {}).get("artifact", {}).get("path")
    if artifact is None:
        group, name, number = entry["name"].split(":")
        artifact = f"{group.replace('.', '/')}/{name}/{number}/{name}-{number}.jar"
    candidate = args.meta / "libraries" / artifact
    if candidate.is_file():
        libraries.append(candidate)

sources = list((root / "src").rglob("*.java"))
if args.neoforge:
    neo = args.neoforge / "libraries"
    libraries.extend(neo.glob("net/neoforged/**/minecraft-client-patched-26.3.0.103.jar"))
    libraries.extend(neo.glob("net/neoforged/**/neoforge-26.3.0.103-universal.jar"))
    libraries.extend(neo.glob("net/neoforged/**/loader-12.0.12.jar"))
    libraries.extend(neo.glob("net/neoforged/**/bus-8.0.5.jar"))
    libraries.extend(neo.glob("net/neoforged/**/mergetool-2.0.7-api.jar"))
    sources = [file for file in sources if file.name not in {"DictationClient.java", "DictationModMenu.java"}]
    sources.extend((root / "neoforge/src").rglob("*.java"))
else:
    libraries.extend([Path(str(version) + ".jar"), args.modmenu])
    with zipfile.ZipFile(args.fabric_api) as fabric:
        for filename in fabric.namelist():
            if filename.startswith(("META-INF/jars/fabric-lifecycle-events-v1-", "META-INF/jars/fabric-api-base-")):
                dependency = build / Path(filename).name
                dependency.write_bytes(fabric.read(filename))
                libraries.append(dependency)

app = build / "Scholar Dictation.app"
contents = app / "Contents"
if args.reuse_helper:
    helper_files = ("Info.plist", "MacOS/ScholarDictation", "_CodeSignature/CodeResources")
    with zipfile.ZipFile(args.reuse_helper) as previous:
        for name in helper_files:
            file = contents / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(previous.read(f"native/helper/Contents/{name}"))
    (contents / "MacOS/ScholarDictation").chmod(0o755)
else:
    (contents / "MacOS").mkdir(parents=True)
    shutil.copyfile(root / "native/Info.plist", contents / "Info.plist")
    subprocess.run(["xcrun", "swiftc", "-O", "-target", "arm64-apple-macosx26.0",
                    *map(str, sorted((root / "native").glob("*.swift"))),
                    "-o", str(contents / "MacOS/ScholarDictation")], check=True)
    subprocess.run(["codesign", "--force", "--sign", "-", str(app)], check=True)
subprocess.run(["codesign", "--verify", "--strict", str(app)], check=True)
subprocess.run([str(java), "-jar", str(args.ecj), "-25", "-proc:none",
                "-cp", ":".join(map(str, libraries)), "-d", str(classes),
                *map(str, sources)], check=True)
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as jar:
    jar.write(root / "LICENSE", "LICENSE")
    jar.write(root / "LICENSE-VOSK", "LICENSE-VOSK")
    jar.write(root / "THIRD_PARTY.md", "THIRD_PARTY.md")
    jar.write(args.speech_model, "speech/vosk-model-small-en-us-0.15.zip", compress_type=zipfile.ZIP_STORED)
    with zipfile.ZipFile(args.vosk) as vosk:
        for member in vosk.infolist():
            if not member.is_dir() and member.filename.endswith((".so", ".dll", ".dylib")):
                jar.writestr(member.filename, vosk.read(member))
    with zipfile.ZipFile(args.vosk_linux_arm64) as vosk:
        jar.writestr("linux-aarch64/libvosk.so", vosk.read("vosk-linux-aarch64-0.3.45/libvosk.so"))
    if args.neoforge:
        descriptor = (root / "neoforge/resources/META-INF/neoforge.mods.toml").read_text()
        jar.writestr("META-INF/neoforge.mods.toml", descriptor.replace("@VERSION@", mod_version))
    for folder, prefix in ((classes, ""), (root / "resources", ""), (contents, "native/helper/Contents/")):
        for file in sorted(folder.rglob("*")):
            if file.is_file() and not (args.neoforge and file.name == "fabric.mod.json"):
                jar.write(file, prefix + file.relative_to(folder).as_posix())
print(output.resolve())
