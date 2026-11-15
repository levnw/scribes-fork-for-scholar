"""Build the Fabric add-on against a local Modrinth 26.3 installation."""
import argparse
import json
import shutil
import subprocess
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--scholar", type=Path, required=True)
parser.add_argument("--modmenu", type=Path, required=True)
parser.add_argument("--fabric-api", type=Path, required=True)
parser.add_argument("--ecj", type=Path, required=True)
parser.add_argument("--meta", type=Path, default=Path.home() / "Library/Application Support/ModrinthApp/meta")
parser.add_argument("--version", default="26.3-0.20.1")
parser.add_argument("--output", type=Path)
parser.add_argument("--reuse-helper", type=Path, help="Keep the signed helper from an earlier build")
args = parser.parse_args()
root = Path(__file__).resolve().parent
mod_version = json.loads((root / "resources/fabric.mod.json").read_text())["version"]
output = args.output or Path(f"scholar-dictation-{mod_version}.jar")
build = root / "build"
classes = build / "classes"
if build.exists():
    shutil.rmtree(build)
classes.mkdir(parents=True)
version = args.meta / "versions" / args.version / args.version
metadata = json.loads(Path(str(version) + ".json").read_text())
java = next((args.meta / "java_versions").glob("*/Contents/Home/bin/java"))
libraries = [Path(str(version) + ".jar"), args.scholar, args.modmenu]
for entry in metadata["libraries"]:
    artifact = entry.get("downloads", {}).get("artifact", {}).get("path")
    if artifact is None:
        group, name, number = entry["name"].split(":")
        artifact = f"{group.replace('.', '/')}/{name}/{number}/{name}-{number}.jar"
    candidate = args.meta / "libraries" / artifact
    if candidate.is_file():
        libraries.append(candidate)

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
                *map(str, (root / "src").rglob("*.java"))], check=True)
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as jar:
    for folder, prefix in ((classes, ""), (root / "resources", ""), (contents, "native/helper/Contents/")):
        for file in sorted(folder.rglob("*")):
            if file.is_file():
                jar.write(file, prefix + file.relative_to(folder).as_posix())
print(output.resolve())
