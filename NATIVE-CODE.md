# Native code and release verification

The macOS speech helper is built from [the Swift sources in this repository](native/)
by [build.py](build.py). The release workflow builds and ad-hoc signs it from
source. It uses the system Speech and AVFoundation frameworks.

| Bundled component | Exact upstream input |
| --- | --- |
| Vosk 0.3.45 for macOS, Windows and Linux x86-64 | [Maven Central JAR](https://repo.maven.apache.org/maven2/com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar) and [upstream source tag](https://github.com/alphacep/vosk-api/tree/v0.3.45) |
| Vosk 0.3.45 for Linux ARM64 | [Upstream release archive](https://github.com/alphacep/vosk-api/releases/download/v0.3.45/vosk-linux-aarch64-0.3.45.zip) |
| Vosk small English model 0.15 | [Upstream model archive](https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip) and [model list](https://alphacephei.com/vosk/models) |

The Vosk libraries and model are copied from those inputs. The
[release script](ci/build-release.py) checks their SHA-256 digests before
packaging them.

The [GitHub Actions workflow](.github/workflows/release-build.yml) builds
the Fabric and NeoForge JARs from the tagged commit and uploads them with
`SHA256SUMS`. Download the JARs from that run or its linked GitHub Release
and compare their SHA-256 digests with the files on Modrinth.

The 0.2.0 alpha and 0.3.0-beta.1 packages predate this workflow and were
built locally. The beta packages reused the alpha's macOS helper.
