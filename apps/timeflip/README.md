# TimeFlip — before and after video creator

**Status:** MVP implementation in progress. Source code, unit tests and CI are being added; the
build has **not** yet passed on GitHub Actions at the time of this commit (see
[docs/release-checklist.md](docs/release-checklist.md) and [docs/qa-test-plan.md](docs/qa-test-plan.md)
for the evidence actually obtained). No device or emulator result is claimed here.

**Promise:** turn two photos into a smooth, correctly oriented before/after MP4 that you can share
through Android's share sheet. No account, no upload, and no network permission.

**Design:** Figma file [TimeFlip — Android Design System & Product UI](https://www.figma.com/design/01ffCnUIwnQsd4WjgjJHka)
(see [docs/design.md](docs/design.md) for what is designed and what is implemented).

## What the code does today
- Domain (pure Kotlin, JVM-tested): image pairs, alignment transforms with bounds, cover-fit placement and
  coverage, crop geometry, transition geometry shared by preview and export, timeline and frame timestamps,
  export presets, undo/redo history, and pure project rules.
- Data, engine, UI and platform layers are described in [docs/architecture.md](docs/architecture.md).

## Build and test
From the repository root (requires JDK 17; Android SDK 36 via Android Studio or the CI runner):

```sh
./gradlew :apps:timeflip:testDebugUnitTest      # JVM unit tests
./gradlew :apps:timeflip:lintDebug              # Android lint
./gradlew :apps:timeflip:assembleDebug          # debug APK
./gradlew :apps:timeflip:bundleRelease          # unsigned release bundle (no signing credentials in Git)
./gradlew :apps:timeflip:connectedDebugAndroidTest  # needs an emulator or device
```

Local machines without the Android SDK can still run the pure-Kotlin domain tests with any Gradle
JVM project that compiles `src/main/kotlin/com/agency/timeflip/domain`.

## Documents
- [Product scope and acceptance](docs/product.md)
- [Architecture and contracts](docs/architecture.md)
- [Decision log](docs/decisions.md)
- [Storage and privacy](docs/storage-and-privacy.md)
- [QA test plan](docs/qa-test-plan.md)
- [Release checklist](docs/release-checklist.md)
- [Team seats](TEAM.md)
- Agency master plan: [../../project-outline-android-apps.md](../../project-outline-android-apps.md)
