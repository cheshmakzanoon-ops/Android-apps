# StickerJet — product charter (MVP v0.1)

Owner: SJ-PM. Source of scope: project-outline-android-apps.md §5.1.

## Promise

Turn a short personal video into an original animated sticker and share it as a file, without
creating an account or uploading anything.

## Audience

Chat groups and creators who make short reaction clips.

## Core journey (must work end to end on a real device)

Import a video → trim to a 0.5–3.0 s loop → optionally remove the background (person-focused) and
correct the mask by hand → place, scale, rotate and caption → preview the loop → export an animated
WebP ≤ 500 KB at 512 × 512 → share through Android.

## MVP scope

| Area | In v0.1 | Status is tracked in docs/release-checklist.md |
|---|---|---|
| Import | Android Photo Picker, video only; duration, size and orientation validated; rejected imports leave nothing behind | implementation by SJ-D3/SJ-D5 |
| Capture | **Not included.** Camera recording is deferred to v0.2 so it cannot delay the import-first journey | — |
| Trim | Start and end controls, 0.5–3.0 s, live loop preview | SJ-D1/SJ-D2 |
| Background | Person-focused on-device segmentation (ML Kit selfie); conservative fallback to the original background; manual RESTORE/ERASE brush with undo | SJ-D4 |
| Style | Scale, drag position, quarter-turn rotation, caption with colour and top/bottom position | SJ-D1/SJ-D4 |
| Preview | Loop on light and dark backgrounds, frame count and duration | SJ-D1/SJ-D4 |
| Export | Animated WebP, 512 px canvas, 12 fps, frame durations summing exactly to the loop, quality ladder to ≤ 500 KB | SJ-D4 |
| Share | FileProvider content URI through the Android share sheet | SJ-D5 |
| Library | Saved projects, reopen, continue editing, recent exports, delete, remove cached files, restart recovery | SJ-D3/SJ-D1 |
| Privacy | No account, no INTERNET permission, no analytics SDK, delete all data | SJ-D5 verifies |

## Explicitly excluded in v0.1

- Direct import into any messenger's sticker library. Sharing is a file share only. No claim is made
  that a messenger installs a pack.
- Object, animal or pet segmentation. The ML model is person-focused; other subjects fall back to the
  original background, and the UI says so.
- Cloud AI, cloud sync, accounts, public galleries, social features.
- WebM, MP4 or GIF export.
- Play Store publication. See docs/release-checklist.md for the path that remains.

## Non-negotiable acceptance (from the agency plan)

1. Imported clips can be corrected, exported and shared without creating an account.
2. A failure never destroys the draft; the user can retry or fix the input.
3. Claimed integrations are validated on recipient apps. v0.1 claims only the file share, so the
   recipient check is limited to what the test plan lists in docs/qa-test-plan.md.

## Known product risks

| Risk | Mitigation in v0.1 | Owner |
|---|---|---|
| Person segmentation misses subjects, halos or flickers | Manual RESTORE/ERASE correction; conservative fallback; limitations shown in the UI | SJ-D4 |
| Messenger sticker limits change | Output follows the documented 512 px / 500 KB limits (see decisions.md ADR-003); no integration claimed | SJ-PM |
| Animated WebP encoder differences across devices | Static frames from the platform encoder; container written by our tested writer; independent decode checks | SJ-D4/SJ-D5 |
| Large or long inputs exhaust memory | 30 s / 150 MB source caps; frames streamed one at a time; bounded bitmaps | SJ-D2/SJ-D4 |
| Private videos leak through backup or sharing | Backup disabled; app-private storage; share copies in cache, cleared at launch | SJ-D3/SJ-D5 |

## Metrics

No telemetry is implemented in v0.1. The agency plan proposes the event names `import_started`,
`trim_applied`, `render_success`, `share_invoked` and `export_error`. They are not wired up, because
adding any analytics requires a separate privacy review and a data-safety update.
