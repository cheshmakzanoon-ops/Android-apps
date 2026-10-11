# StickerJet

**Status:** G1 passed on CI. G2 partly passed on an emulator with a synthetic clip. No physical device, no signed
release, no Play publication. See [docs/release-checklist.md](docs/release-checklist.md) for the evidence behind every claim.

**Promise:** turn a short video into an original animated sticker and share it as a file. No account, no network.

**Audience:** chat groups and creators.

## What the app does

1. **Import** a video from the system Photo Picker. The clip is copied into app-private storage and checked
   for duration (up to 30 s), size (up to 150 MB) and an unreadable or non-video file.
2. **Trim** a 0.5 to 3.0 s loop, with a live looping preview.
3. **Background removal** (optional, person-focused): on-device ML Kit selfie segmentation. When no person is
   found, the original background is kept and the UI says so. Corrections use a RESTORE or ERASE brush with
   undo and redo.
4. **Place and caption:** scale, drag, quarter-turn rotation, and a caption with colour and top or bottom position.
5. **Export** an animated WebP: 512 px square, 12 fps, frame durations that sum exactly to the loop, at most
   500 KB (quality is reduced to fit).
6. **Share** through the Android share sheet as a file. The app does **not** install sticker packs in any messenger.
7. **Library:** saved projects, reopen, continue editing, recent exports, delete, and clear temporary files.

## What is not in v0.1

- Camera capture. Import only.
- Segmentation of objects, pets or animals. Only people are segmented.
- Direct messenger sticker-pack import, cloud features, accounts, WebM, MP4 or GIF export.

## Verified

| Check | Where | Result |
|---|---|---|
| Core domain, WebP container, mask and composition tests (JVM) | CI job *Core JVM tests* | Passing on `2fd5421` |
| Android unit tests, lint, debug APK, release AAB | CI job *Android unit tests, lint, debug APK and release AAB* | Passing on `2fd5421` |
| Merged manifest: no INTERNET, no storage permission, backup off, private FileProvider | same job | Passing on `2fd5421` |
| Instrumented journey on an API 34 emulator (import to export, with the Compose robot tests) | CI job *Instrumented journey* | Passing on `2fd5421`. The 15-test count is from run `99d3e3f`, with identical journey code |
| Exported file decoded independently with Pillow (structure, frame durations, alpha chunks) | same job | Passing on `2fd5421` |

Test media is **synthetic**. No clip with a real person has been used, so segmentation quality on people is
**not validated**. Physical-device behaviour is **not verified**. See [docs/qa-test-plan.md](docs/qa-test-plan.md).

## Build and test

Requirements: JDK 17 and Gradle through the wrapper. The Android module needs Google Maven and the Android SDK,
so it builds on CI or on a machine with that access.

```bash
# Pure JVM core: runs on any machine with Maven Central access
./gradlew -Pstickerjet.skipAndroid=true :apps:stickerjet-core:test

# Full Android build, unit tests and lint (needs Google Maven and the Android SDK)
./gradlew :apps:stickerjet:testDebugUnitTest :apps:stickerjet:lintDebug \
          :apps:stickerjet:assembleDebug :apps:stickerjet:bundleRelease

# Instrumented journey on a connected device or emulator
./gradlew :apps:stickerjet:connectedDebugAndroidTest
```

The release bundle is unsigned unless `STICKERJET_UPLOAD_STORE_FILE`, `STICKERJET_UPLOAD_STORE_PASSWORD`,
`STICKERJET_UPLOAD_KEY_ALIAS` and `STICKERJET_UPLOAD_KEY_PASSWORD` are set by the protected release operator.
No keystore is in this repository.

## Documents

- [product.md](docs/product.md): charter, scope, acceptance and risks
- [architecture.md](docs/architecture.md): modules, pipeline and interface contract
- [decisions.md](docs/decisions.md): decision log (ADR-001 to ADR-008)
- [privacy-and-threat-model.md](docs/privacy-and-threat-model.md): data flows, permissions, deletion, threats
- [qa-test-plan.md](docs/qa-test-plan.md): test matrix with PASS, FAIL and NOT RUN per row
- [design.md](docs/design.md): design tokens and the screen-state inventory (Figma file not built, see that document)
- [release-checklist.md](docs/release-checklist.md): gate status and evidence
- [assets/licenses/README.md](assets/licenses/README.md): third-party code, model and font licences

Team: [TEAM.md](TEAM.md). Agency master plan: [project-outline-android-apps.md](../../project-outline-android-apps.md).
