# StickerJet privacy and threat model

Owner: SJ-PM with SJ-D3 (storage) and SJ-D5 (manifest and sharing). Status: v0.1 draft, not reviewed by counsel.
Every statement below is either verified by a CI check named in it or marked **unverified**.

## 1. What the app collects

Nothing leaves the device. The app has no account, no sign-in, and no analytics or crash-reporting SDK.

| Data | Where it lives | Why | Leaves the device? |
|---|---|---|---|
| The video you pick | `files/projects/<id>/source.mp4` (app-private) | Trimming, preview and export need the file after the picker grant expires | No |
| Project settings (trim, transform, caption, mask strokes, export history) | `files/projects/<id>/project.json` | Reopening and editing | No |
| Thumbnail | `files/projects/<id>/thumbnail.jpg` | Library cards | No |
| Exported stickers | `files/projects/<id>/exports/*.webp` | Library and sharing | Only when you choose Share |
| Share copies | `cacheDir/share/` | The Android share sheet needs a readable file | Only when you choose Share; cleared at each launch |
| Onboarding flag | Jetpack DataStore in app-private storage | Show the welcome screen once | No |

## 2. Permissions and network

- The merged manifest requests **no runtime permissions**. Video comes from the system Photo Picker,
  which grants a temporary read for one file. The app does not request storage, media or location access.
- `android.permission.INTERNET` is removed from the merged manifest with `tools:node="remove"`, because
  the ML Kit dependency merges it in. CI checks the merged debug manifest on every run
  (`.github/workflows/stickerjet.yml`, step "Verify the merged debug manifest requests no INTERNET permission").
- Backup is off: `android:allowBackup="false"`, and both the cloud-backup and device-transfer rules
  exclude the whole app data directory. CI checks `allowBackup` in the merged manifest.
- The FileProvider is `exported="false"` with `grantUriPermissions="true"`, and exposes only `cacheDir/share/`.
  CI checks both attributes in the merged manifest.

## 3. Segmentation

Background removal runs on the device with the ML Kit selfie segmenter (`com.google.mlkit:segmentation-selfie`,
see `assets/licenses/README.md`). The design intent is a bundled model with no network use.
**Unverified:** that the model is bundled in the APK, and that the segmenter makes no network call at
runtime. The no-INTERNET manifest means any such call would fail; the emulator journey must succeed
to show segmentation works without network. Its result is recorded in `docs/qa-test-plan.md`.

## 4. Deletion and retention

- **Delete one sticker** removes its directory: source video, thumbnail, project document and every export.
- **Delete everything** (Settings) removes every project directory.
- **Clear temporary files** (Settings) empties the import and export scratch area (`cacheDir/stickerjet-temp`).
  It does not touch the share copies, and it keeps drafts and exports.
- At launch, `StickerJetApplication.onCreate` clears the share copies (`cacheDir/share/`). Startup
  maintenance then empties the scratch area and removes project directories with no readable document
  (left by an interrupted import).
- There is **no automatic expiry**. Drafts and exports stay until you delete them.
- Uninstalling the app removes everything, since nothing is stored outside its sandbox or in shared storage.
  The one exception is the CI-only journey output, which exists only on the test emulator (see section 6).

## 5. Threats and mitigations

| Threat | Mitigation | Verified by |
|---|---|---|
| Another app reads a video or sticker | App-private storage; the FileProvider exposes only `cacheDir/share/`; no exported components besides the launcher activity | Manifest CI checks; instrumented sharing test (planned in QA plan) |
| A share copy lingers | Cleared at launch and on Clear temporary files | Unit and instrumented tests in `data/` (see QA plan) |
| The export is shared to the wrong recipient | The share sheet is the user's choice; the app never sends anything itself | By design; no automated check |
| Backup or device transfer copies private videos | `allowBackup=false` plus excluded data-extraction rules | Manifest CI check |
| Logs contain user media or paths | No logging of media contents; media-related errors show a generic message | Code review only, **unverified** by tooling |
| A malformed or huge video exhausts memory or storage | Import caps: 30 s and 150 MB source; free-space guard before copy; frames are streamed, never all held | JVM tests on the policy and the guard; instrumented import tests |
| A revoked picker grant breaks an open draft | The clip is copied into app storage on import, so later use does not need the grant | Instrumented data tests (see QA plan) |
| A corrupt project document | Atomic writes; corrupt documents are skipped and counted; orphan directories are removed at launch | JVM tests in `data/` |
| Segmentation misreads a person or misses a subject | The user can see the mask, correct it with brush strokes, and turn background removal off; the UI states the limitation | Manual QA; **no human-footage validation has been done** |

## 6. Test media and provenance

The journey test uses `assets/source/journey-clip.mp4`, a **synthetic** clip with no person (see
`assets/licenses/README.md`). It exercises the no-subject fallback only. The journey output is
published to `Download/journey-output/` on the CI emulator through MediaStore, pulled with `adb`, and
verified with Pillow. It is test output only, never shipped.

## 7. Open items before any public release

1. Counsel review of this document and of the ML Kit licence terms.
2. Confirmation of the bundled model and the no-network behaviour from the AAR and a device run.
3. A privacy policy URL for the Play listing, published by the agency. None exists yet.
4. A data-safety form that matches sections 1 to 4 exactly. None has been filed.
