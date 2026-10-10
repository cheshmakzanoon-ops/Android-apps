# TimeFlip architecture and interface contracts

Status: MVP in progress. Contracts below are implemented in source; anything marked *planned* is not
yet implemented and must not be described as working.

## Layers and packages

`src/main/kotlin/com/agency/timeflip/`

| Package | Owner seat | Android APIs allowed | Responsibility |
|---|---|---|---|
| `app/` | TF-D5 | yes | Application class, `MainActivity`, `AppContainer` (manual composition root) |
| `ui/` | TF-D1 | Compose only | Screens, navigation, ViewModels, theme. No codecs, no SQL |
| `domain/` | TF-D2 | **none** (pure Kotlin) | Models, geometry, transitions, timeline, undo/redo, rules, ports |
| `data/` | TF-D3 | yes | Room database, private photo copies, thumbnails, preferences |
| `engine/` | TF-D4 | yes (`engine/alignment/FeatureAligner` is pure Kotlin) | Decoding, alignment, frame rendering, MP4 encoding and validation |
| `platform/` | TF-D5 | yes | Photo Picker, FileProvider sharing, lifecycle helpers |

Dependency direction: `ui → domain ports ← data/engine/platform`. The UI receives implementations
only through `AppContainer`.

## Domain contracts (implemented)

- `ProjectId`, `PhotoId` (private storage id), `PhotoSourceRef` (opaque picker reference string).
- `PixelSize`, `SourcePhoto` (oriented size, byte size, import time; **no file names**), `ImagePair`.
- `AlignmentTransform(scale, rotationDegrees, offsetX, offsetY)`. Offsets are fractions of frame
  width and height. Mapping: `p' = R(θ)·s·(p − c) + c + t`. Bounds: scale 0.5–4.0, rotation ±45°,
  offsets ±1.0. `toMatrix(frame)` produces the pixel-space `AffineMatrix`.
- `PhotoPlacement.coverBase` (cover-fit), `PhotoPlacement.placement` (cover then alignment),
  `PhotoPlacement.coverage` (fraction of the frame still covered, used to warn about empty edges).
- `TransitionDefinition(kind, TransitionParameters(durationMs, easing, reversed, wipeAngleDegrees))`.
  Kinds: `HORIZONTAL_REVEAL`, `VERTICAL_REVEAL`, `CROSSFADE`, `DIRECTIONAL_WIPE`. Duration 500–3000 ms.
- `TransitionMath.frameAt(definition, linearProgress, frame) → TransitionFrame(afterAlpha, afterClip)`.
  Preview and export **must** both call this function so they always agree.
- `TimelinePlan(transitionMs, holdBeforeMs=800, holdAfterMs=800, fps=30)`. Frame *i* is presented at
  `i · 1_000_000 / fps` µs. `frameCount` = ceil(total · fps / 1000).
- `OutputPreset` (720×1280, 1080×1920, 1080×1080, 1920×1080) with `fallback()`;
  `ExportProfile(preset, fps)` with a clamped bitrate; `ExportRequest`, `ExportedVideo`,
  `ExportProgress` (`Rendering`, `Finished`, `Failed`, `Cancelled`).
- `TimeFlipError(category, debugDetail)` with `ErrorCategory`; `TimeFlipException` wraps it.
- `EditHistory<T>` bounded undo/redo over immutable snapshots.
- `TimeFlipProject`, `ProjectSummary`, `AlignmentEstimate(transform, confidence, inlierCount, status)`,
  `AlignmentSource`, `AlignmentStatus`. `AlignmentEstimate.ACCEPT_CONFIDENCE = 0.6`.
- `ProjectRules`: pure project edits (rename, swap, replace photo, set transition/profile, apply
  estimate). Swapping or replacing a photo **clears the alignment** because the old placement no
  longer applies.

## Ports (interfaces)

- `ProjectRepository` (TF-D3 implements): observe/get/save/delete/duplicate projects, record and list
  exports, delete all data.
- `PhotoStore` (TF-D3 implements): `importPhoto(ref, slot, now)`, `originalPath(id)`,
  `thumbnailPath(id, maxEdge)`, `delete(id)`.
- `AlignmentEngine` (TF-D4 implements, pure Kotlin): `estimate(before: GrayRaster, after: GrayRaster, frame)`.
- `VideoExportEngine` (TF-D4 implements): `export(request): Flow<ExportProgress>`.

## Engine entry points consumed by UI and app (TF-D4 provides)

- `engine/image/PhotoBitmaps(photoStore)`: `suspend fun decode(id: PhotoId, maxEdge: Int): Bitmap?`
  Decodes the private, already-oriented copy with a bounded sample size.
- `engine/auto/AutoAligner(photoStore, photoBitmaps)` (package `com.agency.timeflip.engine.auto`): `suspend fun align(pair: ImagePair, frame: PixelSize): AlignmentEstimate`. It lives outside `engine/alignment` because it depends on `Bitmap`, and `engine/alignment` must stay pure Kotlin for the JVM tests.
- `engine/render/FrameRenderer.draw(canvas, frame, before, after, alignment, transition, linearProgress)`:
  the single Android drawing path used by both the Compose preview and the MP4 exporter.
- `engine/export/Mp4VideoExporter(photoStore, exportsDir, nowMs): VideoExportEngine`.

## Storage and privacy policy (TF-D3)

Photos are **copied** at import into app-private storage (`filesDir/timeflip/photos/`), downscaled so
the longest edge is at most 4096 px, JPEG-encoded and EXIF-normalized. The picker URI is never stored,
so revoked grants cannot break saved projects. Thumbnails live in `cacheDir`. Exports live in
`filesDir/timeflip/exports/` and are shared through a `FileProvider` URI. The app requests no
INTERNET, storage or camera permission. Detailed costs and recovery rules: `docs/storage-and-privacy.md`.

## Alignment method (TF-D4)

Feature-based: corner detection on a bounded working raster, normalized patch descriptors, mutual
nearest-neighbour matching, and RANSAC over a similarity transform (scale, rotation, translation).
Confidence combines inlier ratio, inlier count and geometric sanity checks (scale and rotation
limits). Below `ACCEPT_CONFIDENCE` the status is `NEEDS_MANUAL` and the user adjusts placement
manually. The app never forces an implausible warp.

## Build

- `apps/timeflip` is the Gradle module `:apps:timeflip` (`com.agency.timeflip`, minSdk 26,
  compileSdk/targetSdk 36).
- Toolchain: AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.21, KSP 2.2.21-2.0.5, Compose BOM 2026.09.00.
  Versions are pinned in `gradle/libs.versions.toml`.
