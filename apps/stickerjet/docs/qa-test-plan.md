# StickerJet QA test plan and results

Owner: SJ-D5 (platform and QA), updated by SJ-PM. Status date: 2026-10-11, against commit `2fd5421`.

## Rules

- **PASS** only when a run produced the result, and the evidence column names that run.
- **FAIL** when a run produced a failing result.
- **NOT RUN** when nothing was executed. Written code that has not run is NOT RUN.
- A device or emulator result is claimed only from a real run. No row claims a device run that did not happen.

## 1. Environment matrix

| Environment | Status | Evidence |
|---|---|---|
| Local container, pure JVM core | Reported by lanes, not re-run by PM | SJ-D2 and SJ-D4 each reported 159 test cases with 0 failures. Local Maven Central throttling makes this run intermittent |
| GitHub `ubuntu-latest`, JDK 17, Gradle: core JVM tests | PASS | Run [38152270493](https://github.com/cheshmakzanoon-ops/Android-apps/actions/runs/38152270493), job *Core JVM tests* |
| GitHub `ubuntu-latest`: Android unit tests, lint, debug APK, release AAB, manifest policy | PASS | Run 38152270493, job *Android unit tests, lint, debug APK and release AAB* |
| GitHub `ubuntu-latest`: API 34 `google_apis` `x86_64` emulator with KVM | PASS | Run 38152270493, job *Instrumented journey on an API 34 emulator*. The 15-test count comes from run 38151440072 (commit `99d3e3f`), whose journey code is identical |
| Emulator API 26 (minSdk) | NOT RUN | No job configured |
| Emulator API 36 (targetSdk and compileSdk) | NOT RUN | No job configured |
| Physical phone, Android 14 or newer, system Photo Picker | NOT RUN | No device available to this session |
| Physical phone, Android 11 to 12, GetContent fallback | NOT RUN | No device available |
| Physical phone, Android 8.0 (minSdk 26) | NOT RUN | No device available |
| Physical phone without an H.264 decoder | NOT RUN | No device available |
| Physical phone with low free storage | NOT RUN | No device available |

## 2. Scenarios from the product QA list

| # | Scenario | Status | Evidence and limit |
|---|---|---|---|
| 1 | Fresh install, no account | NOT RUN on a device | No cold-start test runs the installed app. The journey drives the real adapters without the UI |
| 2 | Open without registration | PASS (emulator, fake ports) | Compose robot test starts the UI host with fake ports and no account. Not a cold start of the real app |
| 3 | Import a supported video | PASS (emulator) | Journey imports a bundled synthetic clip through `ImportService` |
| 4 | Trim | PASS (JVM) | `TrimPolicy` rules are unit tested. The journey uses the default trim; it does not drive a user trim |
| 5 | Foreground segmentation on a supported sample | **NOT VALIDATED** | The only clip is synthetic with no person. The journey asserts the no-subject fallback path, not segmentation quality |
| 6 | Correct or keep the mask | PASS (JVM) | `MaskRefiner` tests for RESTORE, ERASE, order and bounds. Not exercised on a device |
| 7 | Preview a looping sticker | PASS (emulator, fake ports) | The import-to-share robot test reaches the preview with fake renderers. The real preview loop is not checked on a device |
| 8 | Export a real animated file | PASS (emulator) | Journey exports through `StickerExporter` and `AnimatedWebpWriter` |
| 9 | Decode and inspect the exported file independently | PASS (emulator) | Pillow 12.3.0 via `tools/verify_animated_webp.py`: valid structure, per-frame 512×512, durations 83 and 84 ms, ALPH and VP8 chunks, no problems reported |
| 10 | Share through Android | PASS (emulator, partial) | The share intent is built with a FileProvider URI and resolves in the journey. No recipient app was driven |
| 11 | Save and reopen a project | PASS (JVM, emulator data tests) | `FileProjectRepository` round trips and restart tests; instrumented data tests on the emulator |
| 12 | Cancel an operation | PASS (JVM) | Export cancellation deletes its temporary file; covered by engine tests. Not on a device |
| 13 | Unavailable permission | PASS (emulator, partial) | The app requests no permission. Revoked picker access is simulated with an exception in the data tests, not a real revocation |
| 14 | Invalid input video | PASS (JVM, emulator) | `ImportPolicy` rejects non-video, too short, too long and too large; the journey covers a negative case |
| 15 | Missing original file | NOT VERIFIED | The code maps a missing source to `SOURCE_MISSING`, but no test run shows it |
| 16 | Low storage | PASS (CI unit tests) | `StorageGuard` tests with an injected free-space value. Not on a device |
| 17 | Offline, core features | PASS (emulator, partial) | The app has no INTERNET permission, so it cannot use the network. Offline operation on a device is NOT RUN |
| 18 | Low-memory and modern devices | NOT RUN | No device |
| 19 | Unit and relevant integration tests | PASS | Core JVM tests and Android unit tests on run 38152270493 |
| 20 | Debug APK and release AAB | PASS (build) | Built on run 38152270493. The AAB is unsigned: no upload key exists |

## 3. Security and policy checks

| Check | Status | How |
|---|---|---|
| No INTERNET permission in the merged debug manifest | PASS | CI step in the Android job |
| No storage or media permission in the merged manifest | PASS | CI step, which also rejects `READ_MEDIA_*` and `MANAGE_EXTERNAL_STORAGE` |
| `allowBackup` is false | PASS | CI step |
| FileProvider is `exported="false"` with `grantUriPermissions="true"` | PASS | CI step |
| No keystore or secret committed | PASS | `.gitignore` covers keystores and service-account files; the release signing configuration reads environment variables only |
| Logs contain no media | NOT VERIFIED | Code review only; no tooling check exists yet |

## 4. Known gaps

- No physical device has run any test. Every device row is NOT RUN.
- No real human footage is available in the repository. Segmentation quality is unmeasured.
- The emulator journey uses synthetic media and checks the fallback path for segmentation.
- The journey export reaches the decoder through MediaStore and `adb pull`, so the test is verified only as CI
  publishes it. A user never sees that path; it exists only for the test.
- The emulator job is required. A fresh emulator boot can be slow (about 5 to 7 minutes), so its timeout is 60 minutes.

## 5. Reproducing the results

```bash
./gradlew -Pstickerjet.skipAndroid=true :apps:stickerjet-core:test          # pure JVM core
./gradlew :apps:stickerjet:testDebugUnitTest :apps:stickerjet:lintDebug \
          :apps:stickerjet:assembleDebug :apps:stickerjet:bundleRelease     # Android build
./gradlew :apps:stickerjet:connectedDebugAndroidTest                        # emulator or device
python3 -I apps/stickerjet/tools/verify_animated_webp.py path/to/sticker.webp
```
