# TimeFlip decision log

## ADR-001 — Android Gradle Plugin 8.13.2 instead of 9.x (2026-10-10)
AGP 9.4 requires Gradle 9.6, uses built-in Kotlin and changes several DSL defaults. Android artifacts
cannot be downloaded from this build sandbox (Google Maven and the SDK host are blocked), so every
Android build runs on GitHub Actions. The 8.13 line is the newest line whose APIs and Kotlin
integration we can verify with confidence and still supports compileSdk/targetSdk 36. Revisit at
the next toolchain review.

## ADR-002 — Pure-Kotlin domain and alignment, verified on the JVM (2026-10-10)
Domain models, geometry, transitions, timeline, undo/redo and the alignment solver contain no
Android imports, so they can be unit-tested without an emulator. Android-only code (Bitmap, Canvas,
MediaCodec, Room) is kept in `data/`, `engine/` and `platform/`.

## ADR-003 — Copy photos into private storage instead of persisting picker URIs (2026-10-10)
Trade-off: extra storage (≤4096 px JPEG copies) versus reliability. Picker grants can be revoked and
originals can be deleted, which would break a saved project. Copies also keep the app offline and
keep user media out of any shared storage. See `docs/storage-and-privacy.md`.

## ADR-004 — Transition geometry defined once in the domain (2026-10-10)
`TransitionMath.frameAt` returns opacity and clip shape. The Compose preview and the MP4 exporter both
render through `FrameRenderer`, so preview and output cannot drift apart.

## ADR-005 — Silent video for the MVP (2026-10-10)
No music or audio track is bundled or generated. Music licensing is out of scope.

## ADR-006 — Single application module with package layers (2026-10-10)
The outline allows modules only when justified. One `:apps:timeflip` module with enforced package
boundaries keeps the build small. Revisit if a second app needs the domain or engine code.
