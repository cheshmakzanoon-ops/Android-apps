# StickerJet release checklist

Owner: SJ-PM. Source of gates: project-outline-android-apps.md §4 and §4.1. Status date: 2026-10-11.

Evidence is named by CI run and commit. "Passed" means that run shows the result. Nothing here is
a device claim unless a device ran it.

## Gate status

| Gate | Status | Evidence |
|---|---|---|
| **G0** Product discovery: scope, non-goals, risks, data rights | Passed (charter v0.1) | [product.md](product.md), [privacy-and-threat-model.md](privacy-and-threat-model.md) |
| **G1** Buildable framework: wrapper, compiling module, contracts with fakes, CI green | **Passed** | CI run [38152270493](https://github.com/cheshmakzanoon-ops/Android-apps/actions/runs/38152270493) on commit `2fd5421`: core tests, Android unit tests, lint, debug APK, release AAB, manifest policy |
| **G2** Vertical slice on a real device: import, trim, segmentation, export, share | **Partial** | Emulator journey (API 34) passed on `2fd5421`, using a synthetic clip with no person. **No physical device has been used.** |
| **G3** MVP feature complete | Not started | Camera capture is out of scope for v0.1. Other MVP items are implemented but not yet verified on a physical device. |
| **G4** Release candidate: tagged SHA, reports, blockers closed | Not started | |
| **G5** Play internal or closed test | Not started | No developer account, signing key or Play Console access is available to this session. |
| **G6** Staged production | Not started | |
| **G7** Operate | Not started | |

## What the CI evidence covers (commit `2fd5421`)

| Check | Job | Result |
|---|---|---|
| Core domain, WebP container, mask and composition tests | Core JVM tests | Passed |
| Android unit tests, lint, debug APK, release AAB | Android unit tests, lint, debug APK and release AAB | Passed |
| Merged manifest: no INTERNET permission | same job | Passed |
| Merged manifest: no storage permissions, `allowBackup=false`, FileProvider not exported | same job | Passed |
| Instrumented journey on API 34 `google_apis` `x86_64` emulator (15 tests in run `99d3e3f`) | Instrumented journey on an API 34 emulator | Passed on `2fd5421` |
| Exported sticker decoded by Pillow 12.3.0 (structure, per-frame durations, alpha chunks) | same job | Passed |

The emulator journey was earlier run as run 38152215480 (commit `af10ade`) and cancelled by a later push. Its
export step is the one that verified the file. The current head is `2fd5421`, whose test code is the same as
`99d3e3f` for the journey.

## Defects found and fixed during G1 and G2

| Commit | Defect | Fix |
|---|---|---|
| `422e4d8` | Root build declared Kotlin plugins with `apply false` | Reverted in `ad6ff56`: every module failed with a classloader error |
| `7ee4db8` | Eight UI compile errors (missing imports, a composable read inside a non-composable lambda) | Fixed |
| `3c89d43` | Four compile errors in the Compose instrumented tests | Fixed |
| `99d3e3f` | Export copy kept an error message as a 47-byte "export" | Copy now requires the RIFF header |
| `af10ade` | Gradle uninstalls the app after tests, so `run-as` found no file; the verifier also passed on a missing file | Export published through MediaStore and pulled with `adb`; verifier fails when the export is missing |

## Open items that block G3 and later

1. **Physical device.** Run the QA matrix in [qa-test-plan.md](qa-test-plan.md) on at least one Android 14 or newer phone and one older phone.
2. **Real human footage.** Segmentation has never run on a real person clip in this repository. Source a licensed clip and record the result.
3. **ML Kit facts.** Confirm from the AAR that the selfie model is bundled and needs no network. See `assets/licenses/README.md`.
4. **Licence and privacy review.** Counsel review of the ML Kit terms and of the privacy document. No privacy policy URL exists yet.
5. **Figma.** The design file exists but is empty. The Starter plan tool-call limit blocked every write. See [design.md](design.md).
6. **Node 20 deprecation.** GitHub reports that the actions in the workflow run on Node 24; upgrade `checkout`, `setup-java`, `setup-python`, `upload-artifact` and `setup-gradle` to their current majors before G4.
7. **Signing.** No upload key exists. Release signing is wired to environment variables and has not been exercised.

## Release decision

**Not a release candidate.** StickerJet has passed G1 and partly G2 on CI. It must not be described to users or
to the store as a finished app, a messenger-compatible sticker pack, or a tested-on-device product.
