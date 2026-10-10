# StickerJet QA test plan

Owner: SJ-D5 (OS, instrumentation, device QA). Status date: 2026-10-10.

## Rules for this plan

- **PASS** only when a run produced the result and the evidence path below points to it.
- **FAIL** when a run produced a failing result. The evidence is the run.
- **NOT RUN** when nothing was executed. A written test that has not run is NOT RUN.
- A device or emulator result is claimed only from a real run. No row here claims a device run that
  did not happen.
- The Android build cannot run in the QA container (Google Maven and the Android SDK are blocked).
  Android build and lint evidence comes from GitHub Actions. Local evidence is limited to the pure
  JVM core, the Python verifier, and static checks of text files.

## 1. Environment matrix

| Environment | How it runs | Status | Evidence |
|---|---|---|---|
| QA container, JDK 17, Gradle 8.14.3 offline, pure JVM core | `./gradlew -Pstickerjet.skipAndroid=true :apps:stickerjet-core:test --offline` | PASS: 110 tests, 0 failures, 0 errors, 0 skipped | `apps/stickerjet/core/build/test-results/test/` (local build output, not durable) |
| QA container, Python 3, Pillow 12.3.0 | `verify_animated_webp.py` self-test (section 6) | PASS: exit 0 on a valid animated WebP, exit 1 on a non-WebP file | Command output recorded in section 6 |
| GitHub `ubuntu-latest`, job `android`: unit tests, lint, debug APK, release AAB | Workflow run 38071473415 | Build and lint reported green. The INTERNET check FAILED on that run. | Run 38071473415, reported by SJ-PM. SJ-D5 has not opened the logs. |
| GitHub `ubuntu-latest`, job `instrumented`: API 34 `google_apis` `x86_64` emulator with KVM | Added in this change, `continue-on-error: true` | NOT RUN | No run yet. Blocked: see section 8. |
| Emulator API 26 (minSdk) | none configured | NOT RUN | none |
| Emulator API 36 (targetSdk, compileSdk) | none configured | NOT RUN | none |
| Physical device: Android 14 or newer, system Photo Picker available | manual | NOT RUN | none |
| Physical device: Android 11 to 12, no system Photo Picker (GetContent fallback path) | manual | NOT RUN | none |
| Physical device: Android 8.0 (minSdk 26) | manual | NOT RUN | none |
| Physical device: no H.264 decoder (expected `H264DecodeCapability.NotSupported`) | manual, device to be sourced | NOT RUN | none |
| Physical device: low free storage (under 500 MB) | manual | NOT RUN | none |

## 2. Scenario matrix (product QA list)

Status applies to the scenario as written. A unit-level result alone does not make a scenario PASS.

| # | Scenario | Automated coverage | Status | Evidence and notes |
|---|---|---|---|---|
| 1 | Fresh install | none | NOT RUN | Clean install of the debug APK on an API 34 emulator. Expect the launcher screen and no permission prompt. |
| 2 | No account | none. No account code exists in the tree. | NOT RUN | Expect no sign-in screen and no account prompt on first run. |
| 3 | Import (Photo Picker, copy, probe) | `StickerJourneyTest.importTrimExportAndShareOneStickerEndToEnd`, `unsupportedFileIsRejectedAndLeavesNoProjectFiles` | NOT RUN | Tests written, not run. The picker UI itself is manual. |
| 4 | Trim (default 2.0 s window) | Journey asserts the default trim. Core `TrimPolicyTest`: 18 tests. | NOT RUN | Core rule tests PASS (section 1). Journey not run. |
| 5 | Segmentation (person found, or fallback when no person) | Journey asserts alpha only when `subjectFrames > 0`. | NOT RUN | Needs a fixture with a visible person to exercise the mask path. |
| 6 | Export (512 px, 12 fps, at most 500 KB) | Journey asserts frame count, durations, canvas, loop count and size. Core `ExportPlanTest`: 10 tests. | NOT RUN | Core rule tests PASS. Journey not run. |
| 7 | Independent decode of the exported file | `tools/verify_animated_webp.py` in workflow job `instrumented` | NOT RUN | The tool works (section 6). It has not checked a file from the app. |
| 8 | Share (FileProvider, grants, chooser) | Journey asserts the URI, grants, bytes served and chooser resolution. | NOT RUN | Recipient apps are not covered (section 7). |
| 9 | Save and reopen | Journey reopens the project from disk in the same process. | NOT RUN | No cold-start test. |
| 10 | Cancel during export (temp file removed) | none | NOT RUN | Manual: cancel at 50 percent, check that no partial file remains in `exports/`. |
| 11 | Revoked permission (picked URI revoked before the copy) | Journey's missing-file test is a proxy only. It is not a true revocation. | NOT RUN | Manual: pick, revoke the grant, import. Expect `Failed(ACCESS_REVOKED)` and no project folder. |
| 12 | Invalid input (not a video, unreadable) | Journey rejection test. Core `ImportPolicyTest`: 17 tests. | NOT RUN | Core rule tests PASS. Journey not run. |
| 13 | Missing original (source deleted before export) | Journey `exportWithDeletedOriginalFailsAndKeepsTheDraft` | NOT RUN | Expect `SOURCE_MISSING`, the draft kept, no partial export. |
| 14 | Low storage | none. `StorageGuard` has no test yet. | NOT RUN | Manual on a low-storage device. |
| 15 | Offline use | none | NOT RUN | Source manifest has no INTERNET (section 5). Merged manifest FAILED on run 38071473415. Fix in tree, not yet verified. Airplane-mode run not done. |
| 16 | Process death during import | `PendingImportHolder` has no test yet | NOT RUN | Manual: start import, `adb shell am kill`, relaunch, expect retry or ACCESS_REVOKED. |
| 17 | Upgrade (0.1.0 to the next version) | none | NOT RUN | Needs two builds. Expect the library and the exports to survive. |
| 18 | Unit tests and integration tests | Core JVM: 110 tests. Android unit tests: CI job `android`. Journey: CI job `instrumented`. | Core: PASS. Journey: NOT RUN. | Core evidence in section 1. Android unit tests: reported green in run 38071473415 (SJ-PM report). |
| 19 | Debug APK | CI job `android`, `assembleDebug` | PASS (reported) | Run 38071473415, reported by SJ-PM. The APK has not been installed or run by SJ-D5. |
| 20 | Release AAB | CI job `android`, `bundleRelease` | PASS for the unsigned bundle (reported). Signed upload NOT RUN. | Run 38071473415. CI has no keystore by design. Signed upload is a protected release step. |
| 21 | Merged manifest: no INTERNET, no storage permission, backup off, private FileProvider | CI step "Verify the merged manifest policy" and the INTERNET step | FAIL on run 38071473415. Fix applied, not yet verified. | `tools:node="remove"` for INTERNET added. Re-run needed. |

## 3. Automated tests added in this change

| Test | Kind | Status | Notes |
|---|---|---|---|
| `StickerJourneyTest.importTrimExportAndShareOneStickerEndToEnd` | instrumented, real adapters | NOT RUN | Depends on engine adapters and a fixture not in the tree (section 8). |
| `StickerJourneyTest.unsupportedFileIsRejectedAndLeavesNoProjectFiles` | instrumented, negative | NOT RUN | Accepts `NOT_A_VIDEO` or `UNREADABLE`. |
| `StickerJourneyTest.missingSourceFailsCleanlyWithoutProjectFiles` | instrumented, negative | NOT RUN | Accepts `Failed(ACCESS_REVOKED or IO_ERROR)` or `Rejected(UNREADABLE)`. |
| `StickerJourneyTest.exportWithDeletedOriginalFailsAndKeepsTheDraft` | instrumented, negative | NOT RUN | Expects `Failed(SOURCE_MISSING)`. |

Each journey test is skipped (Assume), not passed, on a device without an H.264 decoder.

## 4. Platform code status

| Item | Status | Evidence |
|---|---|---|
| `ExportSharing.shareIntent`, `clearShareCopies` | Written. Not run. | Journey covers it. Syntax-checked (section 6). |
| `rememberVideoPicker` (Photo Picker, GetContent fallback) | Written. Not run. | Manual scenario 3. |
| `PendingImportHolder` (commit-based, process-death safe) | Written. Not run. | Scenario 16. |
| `H264Capability.probe()` | Written. Not run. | Used by the journey Assume. |

Syntax check: the four platform files and the journey test were parsed with the Kotlin 2.2.21
compiler from the Gradle cache. No parser errors remain. The only remaining errors are unresolved
Android, AndroidX and engine symbols that are not on this machine. This is not a compile check. CI
`assembleDebug` is the authority.

## 5. Security and policy checks

Run from the repository root. The source-level checks ran on 2026-10-10 and passed. The merged
checks run in CI after a build.

| Check | Command | Result |
|---|---|---|
| Source manifest: INTERNET only as a removal marker | `grep -n 'android.permission' apps/stickerjet/src/main/AndroidManifest.xml` | PASS. Only line 14 matches, `INTERNET tools:node="remove"`. |
| Source manifest: no storage permission | same grep | PASS. No `*_EXTERNAL_STORAGE` or `READ_MEDIA_*` entries. |
| `android:allowBackup="false"` | `grep -n 'allowBackup="false"' apps/stickerjet/src/main/AndroidManifest.xml` | PASS. Line 18. |
| FileProvider not exported, grants enabled | `grep -n -A3 'FileProvider' apps/stickerjet/src/main/AndroidManifest.xml` | PASS. `exported="false"`, `grantUriPermissions="true"`. |
| Share path is the only exposed path | `cat apps/stickerjet/src/main/res/xml/file_paths.xml` | PASS. Only `cache-path name="share" path="share/"`. |
| Merged manifest policy (INTERNET, storage, backup, provider) | CI step "Verify the merged manifest policy" in job `android` | FAIL on run 38071473415 (INTERNET). Not yet re-run. |
| No keystore, password or key in the tree | `git ls-files --cached --others --exclude-standard \| grep -iE '\.(jks\|keystore\|p12\|pfx\|pem\|key)$\|keystore\|google-services\|secrets?\.\|\.env($\|\.)\|service-account' \|\| echo none` | PASS. `none`. |
| No key material or literal passwords in sources | `grep -rIlE -- '-----BEGIN (RSA \|EC \|OPENSSH \|)PRIVATE KEY-----\|AKIA[0-9A-Z]{16}\|AIza[0-9A-Za-z_-]{35}' apps .github gradle build.gradle.kts settings.gradle.kts gradle.properties \|\| echo none` | PASS. `none`. |
| Release signing comes only from the environment | `grep -n 'STICKERJET_UPLOAD' apps/stickerjet/build.gradle.kts` | PASS. Four environment reads, no literals. |

Merged manifest, run locally after a build with the Android SDK (not available in the QA
container):

```
./gradlew :apps:stickerjet:assembleDebug
apkanalyzer manifest print apps/stickerjet/build/outputs/apk/debug/stickerjet-debug.apk
```

Expect no `uses-permission` for INTERNET, `*_EXTERNAL_STORAGE`, `READ_MEDIA_*` or
`MANAGE_EXTERNAL_STORAGE`. Expect `allowBackup="false"` and the FileProvider with
`exported="false"`.

## 6. Commands and results

```
# Pure JVM core unit tests (no Android SDK)
./gradlew -Pstickerjet.skipAndroid=true :apps:stickerjet-core:test --offline
# Result 2026-10-10: BUILD SUCCESSFUL, 110 tests, 0 failures.

# Independent WebP verifier self-test
python3 -I apps/stickerjet/tools/verify_animated_webp.py apps/stickerjet/core/src/test/resources/webp/pil_animated_3f.webp
# Result 2026-10-10: exit 0, 3 frames, durations 100/200/300 ms, structure ok.
python3 -I apps/stickerjet/tools/verify_animated_webp.py <any non-WebP file>
# Result 2026-10-10: exit 1, UnidentifiedImageError.

# Workflow static checks (actionlint 1.7.12 with shellcheck, from pip packages actionlint-py and shellcheck-py)
actionlint -no-color .github/workflows/stickerjet.yml
# Result 2026-10-10: exit 0.

# Instrumented journey (needs a booted emulator or device with an H.264 decoder, and the fixture)
./gradlew :apps:stickerjet:connectedDebugAndroidTest --console=plain
# NOT RUN. Blocked: section 8.
```

## 7. What the journey test does not cover

- Recipient apps and messengers: the chooser is resolved, but no receiver is driven. StickerJet
  never claims that a messenger installs the sticker.
- The Photo Picker UI. It is manual scenario 3.
- A true revoked-permission path. Scenario 11 is manual.
- Low storage, cancel, process death, offline use and upgrade. Those are manual scenarios 10, 14,
  15, 16 and 17.
- Matte quality and appearance. The test checks structure, timing and size.
- Physical devices. Section 1 lists them as NOT RUN.

## 8. Blockers and integration needs

| Owner | Need | Blocks |
|---|---|---|
| SJ-D4 | Engine Android adapters in `apps/stickerjet/src/main/kotlin/com/agency/stickerjet/engine/`: `StickerExporter` (with `ExportOutcome`, `ExportFailure`), `MediaFrameSource`, `MlKitSubjectSegmenter`, `FrameEncoder`. They are not in the tree, so the journey does not compile. | Journey tests |
| SJ-PM | Fixture `apps/stickerjet/assets/source/journey-clip.mp4`: H.264 MP4, 3 to 30 s, at most 150 MB, one clearly visible person. It is not in the tree. The build script already maps `assets/source` into androidTest assets. A placeholder with no person can be made with `ffmpeg -f lavfi -i testsrc2=size=640x360:rate=30 -t 4 -c:v libx264 -pix_fmt yuv420p journey-clip.mp4`. That placeholder exercises the fallback path, not segmentation. | Journey tests |
| SJ-PM | `ExportSharing.clearShareCopies(context)` called from `StickerJetApplication.onCreate`. It is not called yet. | Share cleanup at launch |
| SJ-PM | The committed `PhotoPicking.kt` (commit c24309c) still contains `"video/*"` inside a KDoc. Kotlin block comments nest, so that breaks the main compile. The working tree has the fix. The fix must be in the next commit. | Main compile |
| SJ-PM | Re-run the `android` job with the merged-manifest fix. Confirm the INTERNET step is green. | Scenario 15, 21 |
| SJ-D1 | Use `rememberVideoPicker`, and `PendingImportHolder` (save before `ImportService.import`, clear after any outcome, load on cold start). | Scenarios 3, 11, 16 |
| SJ-D4 | Confirm that segmentation needs no network. The selfie model is bundled, so it should not. The journey run on the emulator is the check. | INTERNET removal |
