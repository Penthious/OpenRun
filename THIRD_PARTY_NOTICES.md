# Acknowledgments and third-party software

OpenRun's own source is licensed under [Apache-2.0](LICENSE). Dependencies
and tools retain their own licenses; OpenRun's license does not relicense
those components.

## Reference projects

- [OpenRide](https://github.com/Penthious/OpenRide) was the base example and
  inspiration. No OpenRide application source code was copied into OpenRun.
  See [OPENRIDE_NOTICE](OPENRIDE_NOTICE).
- [NordicFTMS](https://github.com/mikepugh/NordicFTMS) is separately installed.
  OpenRun implements the client-side protocol; NordicFTMS source and binaries
  are not included.

## Dependencies and tools

Direct dependencies actually used are declared in
[app/build.gradle.kts](app/build.gradle.kts). Versions also appear in
[gradle/libs.versions.toml](gradle/libs.versions.toml); catalog entries alone
do not imply that a dependency is packaged.

- AndroidX, Compose, Material 3 and WorkManager:
  [license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt).
- Kotlin, coroutines and serialization:
  [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt),
  [coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt),
  [serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt).
- gRPC Java:
  [license](https://github.com/grpc/grpc-java/blob/master/LICENSE).
- Garmin FIT Java SDK (com.garmin:fit:21.176.0):
  [FIT Protocol License Agreement](https://github.com/garmin/fit-java-sdk/blob/main/LICENSE.txt).
  This has its own license, not OpenRun's Apache license.
- JUnit 4 (unit tests only):
  [license](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt).
- Gradle wrapper and generated launch scripts:
  [license](https://github.com/gradle/gradle/blob/master/LICENSE).
  Existing script copyright/license headers are retained.
- Android Gradle plugin:
  [license](https://android.googlesource.com/platform/tools/base/+/mirror-goog-studio-main/LICENSE).

For binary distribution, inspect the licenses/notices shipped with the exact
resolved versions, including transitive dependencies. This source repository
is not a cleared binary release.

## Local-only components

GlassOS/iFit applications and certificate/private-key assets are not
distributed in this source repository. Local credentials are ignored by
Git but packaged by Android into local APKs. Do not publish those APKs.

Plex, Netflix and browser applications are launched when installed, not
bundled. No streaming video content is included.

OpenRun is independent and is not affiliated with or endorsed by iFIT,
NordicTrack, Garmin, Plex or Netflix. Product names identify services or
hardware with which the application interoperates.
