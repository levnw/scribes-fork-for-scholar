"""Build both release JARs from source and record the hashes of their inputs."""
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MOJANG = "https://piston-meta.mojang.com/v1/packages/9f67e10ed013deeaad20ac3c800652765d493408/26.3.json"
MAVEN = "https://repo.maven.apache.org/maven2/"
NEO = "https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.103/"
FABRIC = "https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/profile/json"

INPUTS = {
    "vosk.jar": (MAVEN + "com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar",
                 "9e38e96e4448a41d889bb254b2f8424945554945c6e77e1cd97a3d8633fac2ba"),
    "linux-arm64.zip": ("https://github.com/alphacep/vosk-api/releases/download/v0.3.45/vosk-linux-aarch64-0.3.45.zip",
                       "45e95d37755deb07568e79497d7feba8c03aee5a9e071df29961aa023fd94541"),
    "model.zip": ("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                  "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"),
    "neo-installer.jar": (NEO + "neoforge-26.3.0.103-installer.jar",
                          "d8a92e17cf12b5739a2518b11afd47eeec0e636a6e63f94b6400c5dbd3603e48"),
    "ecj.jar": (MAVEN + "org/eclipse/jdt/ecj/3.44.0/ecj-3.44.0.jar",
                "14c46ddc2e511c46c998da728f6a80f13a689c920f3832402f7ad150f6a6188d"),
    "scholar-fabric.jar": ("https://cdn.modrinth.com/data/fX4dIQCo/versions/43vc4YZi/scholar-fabric-26.3-1.2.12.jar",
                           "7b0ea8f20f9a35e52d1e9b1b8c4ae8e2676d6c6f7e072a72eeadc4bde92b3644"),
    "scholar-neoforge.jar": ("https://cdn.modrinth.com/data/fX4dIQCo/versions/yl4em2zo/scholar-neoforge-26.3-1.2.12.jar",
                             "58e5aab5c1f3af52c765642bd6562e6749ba25ac036714a045ddbcc4b102497a"),
}

def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={
        "User-Agent": "levnw/scribes-fork-for-scholar release build"
    }), timeout=90)

def download(url, path, expected=None, algorithm="sha256"):
    path.parent.mkdir(parents=True, exist_ok=True)
    with request(url) as response, path.open("wb") as file:
        shutil.copyfileobj(response, file)
    digest = hashlib.new(algorithm, path.read_bytes()).hexdigest()
    if expected and digest != expected:
        raise ValueError(f"Checksum mismatch for {url}: {digest}")
    return path

def modrinth_file(project, version, folder, digest):
    url = f"https://api.modrinth.com/v2/project/{project}/version"
    with request(url) as response:
        releases = json.load(response)
    for release in releases:
        if release["version_number"] == version and "26.3" in release["game_versions"]:
            for file in release["files"]:
                if file["primary"]:
                    return download(file["url"], folder / file["filename"], digest)
    raise ValueError(f"Missing Modrinth release: {project} {version}")

def build(work, output, java):
    files = {name: download(url, work / name, digest) for name, (url, digest) in INPUTS.items()}
    api = modrinth_file("fabric-api", "0.165.0+26.3", work, "18442e2ad466d6ef41c37dbb4cb68495e6ca6658b1f1ee9be76143d155ef2c88")
    menu = modrinth_file("modmenu", "21.0.0", work, "837812c6c98d66141db7b5dcae668f27fb78d557e8f4c0c89912985fc26a5e6f")
    meta = work / "minecraft"
    version = meta / "versions/26.3-0.20.1/26.3-0.20.1"
    vanilla = json.loads(download(MOJANG, work / "vanilla.json",
                                  "9f67e10ed013deeaad20ac3c800652765d493408", "sha1").read_bytes())
    with request(FABRIC) as response:
        fabric = json.load(response)
    client = vanilla["downloads"]["client"]
    jar = download(client["url"], Path(str(version) + ".jar"), client["sha1"], "sha1")
    Path(str(version) + ".json").write_text(json.dumps({
        "libraries": vanilla["libraries"] + fabric["libraries"]
    }))
    for library in vanilla["libraries"]:
        artifact = library["downloads"].get("artifact")
        if artifact and "natives-" not in artifact["path"]:
            download(artifact["url"], meta / "libraries" / artifact["path"], artifact["sha1"], "sha1")
    for library in fabric["libraries"]:
        group, name, number = library["name"].split(":")
        relative = f"{group.replace('.', '/')}/{name}/{number}/{name}-{number}.jar"
        download(library["url"] + relative, meta / "libraries" / relative, library.get("sha256"))

    neo = work / "neoforge"
    vanilla_jar = neo / "versions/26.3/26.3.jar"
    vanilla_jar.parent.mkdir(parents=True)
    shutil.copyfile(jar, vanilla_jar)
    (neo / "launcher_profiles.json").write_text('{"profiles":{}}')
    subprocess.run([java, "-jar", files["neo-installer.jar"], "--installClient", neo], check=True)

    version_number = json.loads((ROOT / "resources/fabric.mod.json").read_text())["version"]
    common = [sys.executable, ROOT / "build.py", "--java", java, "--meta", meta,
              "--ecj", files["ecj.jar"], "--vosk", files["vosk.jar"],
              "--vosk-linux-arm64", files["linux-arm64.zip"], "--speech-model", files["model.zip"]]
    for loader, args in (("fabric", ["--fabric-api", api, "--modmenu", menu]), ("neoforge", ["--neoforge", neo])):
        target = output / f"scribes-fork-for-scholar-{loader}-{version_number}.jar"
        subprocess.run([*common, "--scholar", files[f"scholar-{loader}.jar"], "--output", target, *args], check=True)
    hashes = [f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}" for p in sorted(output.glob("*.jar"))]
    (output / "SHA256SUMS").write_text("\n".join(hashes) + "\n")
    print("\n".join(hashes))

if __name__ == "__main__":
    java = shutil.which("java")
    if java is None:
        raise SystemExit("Java 25 is required.")
    output = Path(sys.argv[1]).resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="scribes-fork-ci-") as folder:
        build(Path(folder), output, java)
