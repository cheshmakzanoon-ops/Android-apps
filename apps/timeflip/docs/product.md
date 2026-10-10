# TimeFlip product scope (PM-owned)

## Promise
Turn two photographs of the same subject into a smooth, correctly oriented before/after MP4 that can
be shared through the Android share sheet. No account, no upload, works offline after import.

## Target user and first session
Artists, renovators and progress diarists who already have a "before" and an "after" photo on their
phone. First session goal: a playable MP4 with no sign-up.

## MVP scope (in)
- Import exactly two photographs with the system Photo Picker; swap, replace or clear them.
- Copy each photo into app-private storage with EXIF orientation applied.
- Automatic feature-based alignment with a confidence score; a clear manual fallback when confidence is low.
- Manual alignment: comparison slider, overlay opacity, drag, pinch zoom, rotation, numeric sliders,
  reset, fit, undo and redo, and an empty-edge (coverage) warning.
- Four transitions: horizontal reveal, vertical reveal, crossfade, directional wipe, with duration,
  easing and reverse controls.
- Live preview that uses the same transition geometry as the export.
- Export presets: 720×1280, 1080×1920, 1080×1080 and 1920×1080 at 30 fps, silent H.264 MP4.
- Cancel, failure recovery and retry for export; validation of every finished MP4 before it is reported as done.
- Share through `FileProvider` and the Android share sheet.
- Local project library: reopen, rename, duplicate, delete, list previous exports.
- Delete all data.

## Explicitly out of scope for the MVP
- Music, audio tracks and any licensed catalogue.
- Cloud galleries, accounts, sync and any server.
- Photogrammetry, generative or subject-aware morphing.
- Direct publishing to Instagram, TikTok or YouTube (not integrated, not claimed).
- Background export after the process is killed.

## Acceptance criteria (evidence required)
1. First launch reaches a playable correctly oriented MP4 without sign-up. *Evidence: CI instrumented journey plus MP4 verification.*
2. Automatic alignment has a usable manual fallback. *Evidence: unit tests for NEEDS_MANUAL paths and UI test.*
3. Exports play in common media players. *Evidence: ffprobe validation in CI; device playback still pending.*
4. Local projects survive a restart with identical output for the same inputs. *Evidence: data tests; device restart test pending.*
5. Permissions and missing media are handled without crashes. *Evidence: data tests; device test pending.*
6. Accessibility: 48 dp targets, font scale tests, no colour-only status. *Evidence: UI tests written; device/TalkBack pending.*

## Privacy claims allowed in store copy
- Photos and exports stay on the device.
- The app does not request network, storage or camera permission.
- No analytics are implemented in this MVP.

## Known limitations at the time of writing
See `docs/qa-test-plan.md`. In particular: no physical device has been tested; exports stop if the
process is killed; the photo copy is downscaled to at most 4096 px on the longest edge.
