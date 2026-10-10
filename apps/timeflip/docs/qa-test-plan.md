# TimeFlip QA test plan

Owner: TF-D5 (Android platform and independent QA). Status date: 2026-10-10.

**Status summary.** No automated suite has run on GitHub Actions yet, and no physical device or emulator has
run any TimeFlip build. Every device, emulator and CI result in this document is **"Not run yet"** until
evidence exists. Record the commit SHA, device or API, and the CI run URL next to each result.

## 1. Checks already run locally (not CI)

These ran in the TF-D5 build sandbox. They are not Android results.

| Check | Result |
|---|---|
| `benchmarks/tools/verify_mp4.py --self-test` with ffmpeg and ffprobe 6.1.1 | Passed: 7 logic cases (no ffmpeg needed) and 5 fixture cases (encode with libx264, then ffprobe and sample-frame checks, including negative cases for wrong size, wrong duration, wrong fps and a static clip). |
| Rotation detection on a fixture with a display-matrix rotation of 90 degrees | Detected: `no_rotation` failed, `rotation_degrees` 90. |
| Kotlin compiler 2.2.21 pass over `domain/`, `data/`, `engine/`, `ui/`, `app/`, `platform/` and `src/androidTest/`, as present at this status date | No syntax errors. The unresolved symbols left are Android, Room, Compose and test libraries, which are not on the sandbox classpath, plus `TimeFlipApp`, `TimeFlipTheme` and `UserPreferences.clearAll`, which are not written yet. Every data, engine and domain symbol that `app/` uses resolved against the real files. |
| `.github/workflows/timeflip.yml` parsed as YAML | Parses. Triggers, job graph, timeouts, pinned action versions and job outputs were checked from the parsed structure. |

Not run: Gradle, lint, unit tests, assemble, the Android emulator, the instrumented tests and actionlint.

## 2. Automated suites

| Suite | Location | Runs in | Status |
|---|---|---|---|
| Domain JVM unit tests (9 files) | `src/test/.../domain/**` (TF-D2) | `validate`: `testDebugUnitTest` | Written by TF-D2. Not run on CI. |
| Engine JVM tests (alignment) | `src/test/.../engine/alignment/**` (TF-D4) | `validate` | Written by TF-D4 (`FeatureAlignerTest`, `WorkingRasterTest`). Not run on CI. `ProbeTmpTest.kt` looks temporary and should be removed before release. |
| Data tests (photo copies, repository, recovery, schema) | `src/test/.../data/**` (TF-D3) | `validate` | Written by TF-D3 (`PrivatePhotoStoreTest`, `RoomProjectRepositoryTest`, `StorageRecoveryTest`, `SchemaTest` and others). Not run on CI. |
| UI ViewModel tests (library, editor, export, pairing, alignment review, manual alignment) | `src/test/.../ui/**` (TF-D1) | `validate` | Written by TF-D1. Not run on CI. |
| Lint | `lintDebug` | `validate` | Not run. |
| Instrumented journey | `src/androidTest/.../TimeFlipJourneyTest.kt` | `instrumented` (API 34 emulator) | Written. Not run. Depends on UI labels that TF-D1 has not shipped yet. |
| Recovery and denied picker | `src/androidTest/.../PermissionAndRecoveryTest.kt` | `instrumented` | Written. Not run. |
| MP4 structure and content check | `benchmarks/tools/verify_mp4.py` | `instrumented` on the journey MP4; `mp4-verify` self-test | Self-test passed locally. CI not run. |
| Manifest permission diff | not automated | planned | Not built. The manifest as written declares no permissions (read from source). |

Instrumented-test support code lives in `src/androidTest/.../testsupport/`:
- `SyntheticPhotos.kt`: deterministic synthetic JPEGs and a `PhotoStore` double that ignores the picker reference.
- `TimeFlipTestEnvironment.kt`: per-test reset and picker stub, applied as the outer rule.
- `ComposeWaits.kt`: `waitForText`, which on timeout reports the visible screen tree.
- `UiLabels.kt`: the visible labels the tests expect. These must match TF-D1 strings.

## 3. Outline mapping: integration items 1 to 20

**Source note.** The numbered list of 20 Android integration tests comes from the TimeFlip product directive
(section 8, "Android integration tests"), not from `project-outline-android-apps.md`. The outline's §8.1 holds
only general guardrails. Items are listed in the directive's order and are mapped to the tests below.

Key: **Auto** = automated test that exists or is named here. **Device** = physical or emulator check.
Every row is "Not run yet" because nothing has run.

| # | Item (source) | Auto | Device | Status |
|---|---|---|---|---|
| 1 | Screen reader labels on controls (§8.1 UX, §3.5 TalkBack) | UI semantics tests (TF-D1, planned); journey uses text labels | TalkBack pass over the full journey | Not run yet |
| 2 | Minimum size, 200% font, orientation (§8.1 UX, §3.5) | none yet (screenshot tests planned in `ui/`) | 200% font and rotation on the low-RAM device | Not run yet |
| 3 | Reduced motion (§8.1 UX) | `reducedMotionEnabled` is one line; no test yet | Toggle "Remove animations" and check motion is replaced | Not run yet |
| 4 | No useless buttons or abandoned states (§8.1 UX) | journey asserts each step's expected label | Manual walk of every screen | Not run yet |
| 5 | Clear alternative when permission or grant is denied (§8.1 UX) | `cancelledPickerLeavesTheWizardUsable` | Cancel the picker on a device | Not run yet |
| 6 | Idempotence and cancellation (§8.1 correctness) | engine contract: a collector that cancels leaves no partial file (`Mp4VideoExporter` doc); no test of that yet | Cancel mid-export, then export again | Not run yet |
| 7 | Domain invariants (§8.1) | domain JVM tests (TF-D2) | none | Not run yet |
| 8 | Database upgrade (§8.1) | schema test exists (TF-D3, `SchemaTest`); no upgrade-from-previous-version test yet | Install a previous build and upgrade. No previous build exists yet. | Not run yet |
| 9 | Process death (§8.1, §3.5) | ViewModel SavedStateHandle tests (TF-D1), not confirmed | Kill the process mid-editing and mid-export | Not run yet |
| 10 | Malformed and large input (§8.1, §3.5) | engine and data tests for decode failure (TF-D3, TF-D4), not confirmed | Import a large and a malformed picture | Not run yet |
| 11 | Absent network (§8.1, §3.5 offline) | manifest check (planned) | Airplane mode for the full journey | Not run yet |
| 12 | No duplicate charge or fake success (§8.1) | Not applicable (no payments). Exports report Finished only after read-back validation (`Mp4Validator`), and the `mp4-verify` gate checks the file. | none | Not run yet |
| 13 | Render and export duration, frame pacing, memory ceiling (§8.1, §3.5 low RAM) | none | Benchmark on low-RAM and modern ARM64 devices | Not run yet |
| 14 | Thermal effect (§8.1) | none | Repeated exports on low-end and modern devices | Not run yet |
| 15 | Scoped permissions (§8.1, §3.6) | manifest permission diff (planned) | Install and review the permission list | Not run yet |
| 16 | Least sharing (§8.1, §3.6) | `buildVideoShareIntent` built and checked in the journey; read-only grant | Share to a receiving app and confirm it can read the file | Not run yet |
| 17 | Deletion (§8.1, §3.6) | data tests for `deleteProject` and `deleteAllData` (TF-D3, planned). The journey's reset uses `deleteAllData`. | "Delete all data" check on a device | Not run yet |
| 18 | No unnecessary photo scanning (§8.1) | picker is the only photo entry; `PickVisualMedia` needs no permission (code read) | Confirm no permission prompt appears | Not run yet |
| 19 | Play baseline: target SDK, signing, 64-bit, data safety (§8.1, §2.1) | targetSdk 36 in build file (read); `bundleRelease` is unsigned in CI | Play Console checks | Not run yet |
| 20 | Support: crash owner, rollback, changelog (§8.1) | not a CI check | Owned by PM; see `release-checklist.md` | Not run yet |

Section 3.5 dimensions not listed above: lowest API (minSdk 26) and API 36 are not in CI (only API 34 is). The
low-RAM and ARM64 classes need physical devices. Large malformed input appears in item 10.

## 4. Export lifecycle policy (MVP)

Implemented in `platform/ExportLifecycle.kt`.

- Exports run in the app process only. There is no foreground service, WorkManager job or wake lock.
- When the process dies, the export stops. The engine deletes the partial file (`Mp4VideoExporter` cancellation
  path), and `StorageRecovery` removes leftovers at the next start. No finished MP4 is recorded, so the user
  starts again.
- While an export is running, system Back is intercepted (`ExportLeaveGuard`) and handed to the UI. The UI
  must explain the policy and offer "Stay" or an explicit "Cancel export".
- Limitation: Android may freeze or stop the process when the activity is in the background. A running export
  can stall or stop. The app makes no background export promise.

## 5. Export sharing decision

Exports are written to `filesDir/timeflip/exports/` and shared from that location. They are not copied.

- `res/xml/file_paths.xml` declares `<files-path name="timeflip_exports" path="timeflip/exports/" />`.
- The provider authority is `${applicationId}.exports`, as declared in the manifest.
- The earlier `cache-path` entry for `exports/` was replaced, because exports are not kept in the cache. The data
  layer's `StorageLayout.exportsDir(rootDir)` uses the same location.
- Only `ACTION_SEND` with `video/mp4` is built, with `FLAG_GRANT_READ_URI_PERMISSION` and a `ClipData`
  entry, inside `Intent.createChooser`. TimeFlip never names a destination app.
- If the file is outside the declared paths, or no activity can handle the chooser, `shareVideo` returns
  false and the UI shows a message. Nothing is logged.

## 6. Photo import and the test seam

- Import uses `PickVisualMedia` with `ImageOnly`. No storage or media permission is requested. The picker's
  URI is used only during import. It is never saved in a project.
- `AppContainer(context, photoStore)` accepts a custom `PhotoStore`. Production passes nothing.
- `TimeFlipApplication.replaceContainerForTests` swaps in that container before the activity starts. The
  container shares one Room database with the process container, so no second connection is opened.
- The system picker is intercepted in tests with espresso-intents. Injecting a `PhotoStore` alone does not
  stop the picker from opening, because the UI launches it before `importPhoto` runs. This needs the
  dependency listed in the report to the PM.

## 7. Known limitations

- No physical device has run TimeFlip. Nothing about device behavior is verified.
- No CI run has happened. Every workflow step is unverified until the first GitHub Actions run.
- Exports stop when the process is killed (section 4).
- Automatic alignment is limited. Large inputs and heavily changed scenes give poor estimates, and manual
  correction is required (outline §5.2 risk gate). Synthetic test photos do not represent that difficulty.
- Test photos are synthetic. No user media is used in tests or stored in CI.
- Video is silent (ADR-005). The verifier fails any audio stream.
- The emulator job uses API 34 only. Minimum API 26 and API 36 are follow-up items. API 36 needs a system image
  on the hosted runner.
- The UI tests depend on the labels in `UiLabels.kt`. Those labels are unconfirmed until TF-D1 ships `ui/`
  screens and the `TimeFlipApp` root.
- `verify_mp4.py` checks structure and a content change. It does not judge visual quality, smoothness or
  playback in popular players. Those need device and player checks.

## 8. Device matrix

Every row is "Not run yet". Add evidence as links to CI runs or device logs. Do not change a row to passed
without a link.

| Device or emulator | Android API | Class | Scenario | Result |
|---|---|---|---|---|
| Emulator pixel_6 google_apis x86_64 (CI) | 34 | modern | Journey, recovery and denied-picker instrumented tests, MP4 check | Not run yet |
| Emulator (follow-up) | 36 | modern | Same instrumented tests | Not run yet |
| Physical low-RAM ARM64 phone | 26 (minSdk) | low RAM | Journey and export; memory and render timing | Not run yet |
| Physical modern ARM64 phone | 36 (targetSdk) | modern | Journey, export and share; thermal check | Not run yet |
| Any device | any | any | TalkBack over the full journey | Not run yet |
| Any device | any | any | 200% font scale and both orientations | Not run yet |
| Any device | any | any | Picker cancelled (no grant) | Not run yet |
| Any device | any | any | Airplane mode for the full journey | Not run yet |
| Any device | any | any | Kill the process during an export | Not run yet |
| Any device | any | any | Upgrade from a previous build | Not run yet (no previous build) |
| Any device | any | any | Large and malformed input picture | Not run yet |
| Low-end and modern devices | any | any | Thermal and battery over repeated exports | Not run yet |

## 9. Integration contracts this suite depends on

Checked against the files present at the status date. "Exists" means the declaration is in the tree, not that
it has been tested.

| Symbol | Status | Owner | Used by |
|---|---|---|---|
| `data.db.TimeFlipDatabase.create(context)` | Exists. Not a singleton, so `AppContainer` shares one instance per process. | TF-D3 | `AppContainer` |
| `data.RoomProjectRepository(db, rootDir, cacheDir)` | Exists | TF-D3 | `AppContainer` |
| `data.PrivatePhotoStore(context, rootDir)` | Exists. Reads its source through `ContentResolver`, so `file://` URIs work. | TF-D3 | `AppContainer`, recovery test |
| `data.prefs.UserPreferences(context)` | Exists | TF-D3 | `AppContainer` |
| `data.prefs.UserPreferences.clearAll()` | **Missing.** The test reset needs it to show Welcome on each fresh launch. It is also needed for "Delete all data". | TF-D3 | `TimeFlipTestEnvironment` |
| `data.StorageRecovery(rootDir, cacheDir, db).recover(): RecoveryReport` with `missingPhotos` (`photoId`) | Exists | TF-D3 | `AppContainer`, recovery test |
| `engine.image.PhotoBitmaps(photoStore)` | Exists | TF-D4 | `AppContainer` |
| `engine.auto.AutoAligner(photoStore, photoBitmaps).align(pair, frame)` | Exists in package `engine.auto`. The architecture document names `engine/alignment`. | TF-D4 | `AppContainer` |
| `engine.export.Mp4VideoExporter(photoStore, exportsDir, nowMs = default)` | Exists | TF-D4 | `AppContainer` |
| `ui.ports.OnboardingStore`, `ui.ports.AlignmentRunner` | Interfaces exist. `AppContainer` provides the adapters (`PreferencesOnboardingStore` and an object expression). | TF-D1 (ports), TF-D5 (adapters) | `AppContainer`, `TimeFlipApp` |
| `ui.TimeFlipTheme { }` and `ui.TimeFlipApp(container: AppContainer)` | **Missing.** `ui/` has no root file yet. | TF-D1 | `MainActivity` |
| UI labels in `UiLabels.kt` | Not confirmed | TF-D1 | instrumented tests |

## 10. Evidence rules

- Record the SHA, the device or emulator with its API level, and the CI run URL.
- Never mark a result as passed without the run that produced it.
- Do not commit APK, AAB or MP4 files, or any user media.
