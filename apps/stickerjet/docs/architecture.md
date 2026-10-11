# StickerJet architecture and interface contract

Status: G1 contract, owned by SJ-PM. Changing a public signature below is a contract change: the
owner updates the fakes and notifies every consumer in the same PR (project-outline §3.3).

## 1. Modules

| Gradle path | Directory | Android | Contents |
|---|---|---|---|
| `:apps:stickerjet-core` | `apps/stickerjet/core/` | no | `domain` (SJ-D2), `engine` pure math and WebP container (SJ-D4), `testFixtures` fakes (SJ-D2) |
| `:apps:stickerjet` | `apps/stickerjet/` | yes | `app` composition root and integration (SJ-PM), `ui` and `res` (SJ-D1), `data` (SJ-D3), `engine` Android adapters (SJ-D4), `platform` and `androidTest` (SJ-D5) |

Why a pure JVM module: domain rules, mask math and the animated-WebP writer must be testable without
the Android SDK, and must provably not import Android types (ADR-001 in `docs/decisions.md`).

Dependency direction (arrows mean "may depend on"):

```
app (composition root) -> ui -> core/domain ports
                        -> data  -> core/domain ports
                        -> engine (Android) -> core/engine + core/domain ports
                        -> platform
core/domain, core/engine  -> nothing Android
```

No Android module may depend on another Android feature package. `ui` never imports `data` or
`engine` directly; it receives port implementations from `app.AppContainer`.

## 2. Pipeline

Import -> Trim -> (Segment + manual mask) -> Place/crop/caption -> Encode frames -> Mux animated WebP -> Share.

- Import (SJ-D3 `data.importing`): content URI -> copied into `files/projects/<id>/source.mp4`.
  Probed with `MediaMetadataRetriever`, judged by `domain.ImportPolicy`. Rejected imports leave no files.
- Frames (SJ-D4 `engine.MediaFrameSource`): `FrameSource.frames(...)` streams `ArgbFrame`s at the
  requested timestamps, rotation applied, longest side ≤ `maxSidePx`. Frames are never all held at once.
- Segmentation (SJ-D4 `engine.MlKitSubjectSegmenter`): `SubjectSegmenter.segment(frame)` returns an
  `AlphaMask` in source-frame coordinates, or `null` when no person is confident.
- Mask correction (SJ-D4 `core.engine.MaskRefiner`): applies ordered `MaskStroke`s, then edge smoothing.
- Placement (SJ-D4 `core.engine.StickerComposer`): maps the source frame onto the 512 px canvas using
  `Transform` and multiplies in the refined alpha. Pure ARGB math.
- Caption (SJ-D4 `engine.CaptionRenderer`): draws the caption with `android.graphics.Canvas` onto the Bitmap.
- Encode (SJ-D4 `engine.FrameEncoder`): `Bitmap.compress` to a static WebP per frame, quality ladder
  to stay ≤ `StickerLimits.MAX_EXPORT_BYTES`.
- Mux (SJ-D4 `core.engine.AnimatedWebpWriter`): combines the static frames into one animated WebP.
- Share (SJ-D5 `platform.ExportSharing`): `FileProvider` content URI, `ACTION_SEND` with `image/webp`.

## 3. Public contracts

### 3.1 `domain` (SJ-D2, package `com.agency.stickerjet.domain`)

Already present: `StickerLimits`, `SourceVideo`, `TrimRange`, `Transform`, `Caption`, `CaptionPosition`,
`BackgroundMode`, `StrokeMode`, `NormalizedPoint`, `MaskStroke`, `ExportRecord`, `StickerProject`,
`ProjectRepository`, `ArgbFrame`, `FrameSource`, `AlphaMask`, `SubjectSegmenter`.

To implement (signatures are binding; bodies and tests are SJ-D2's):

```kotlin
object TrimPolicy {
    fun defaultTrim(sourceMs: Long): TrimRange                 // first DEFAULT_STICKER_MS, clamped
    fun clamp(trim: TrimRange, sourceMs: Long): TrimRange      // inside source, length in [MIN, MAX]
    fun isValid(trim: TrimRange, sourceMs: Long): Boolean
}

data class ProbeResult(
    val durationMs: Long?, val width: Int?, val height: Int?,
    val rotationDegrees: Int, val hasVideoTrack: Boolean, val sizeBytes: Long,
)
enum class ImportRejection { NOT_A_VIDEO, UNREADABLE, TOO_SHORT, TOO_LONG, TOO_LARGE }
sealed interface ImportDecision {
    data class Accepted(val durationMs: Long, val orientedWidth: Int, val orientedHeight: Int, val rotationDegrees: Int) : ImportDecision
    data class Rejected(val reason: ImportRejection) : ImportDecision
}
object ImportPolicy { fun evaluate(probe: ProbeResult): ImportDecision }   // oriented size swaps for 90/270

object ExportPlan {
    /** Frame count: max(1, round(durationMs * FPS / 1000)). */
    fun frameCount(trim: TrimRange): Int
    /** Timestamps of the frames in the loop: trim.startMs + i * 1000 / FPS, i in 0 until frameCount. */
    fun frameTimestampsMs(trim: TrimRange): List<Long>
    /** Integer durations that sum exactly to the loop length (83/84 ms at 12 fps). */
    fun frameDurationsMs(trim: TrimRange): IntArray
}

/** Edit rules, each returning a new project. Trim is clamped; the others reject invalid values. */
fun StickerProject.withTrim(trim: TrimRange, nowMs: Long): StickerProject
fun StickerProject.withTransform(transform: Transform, nowMs: Long): StickerProject
fun StickerProject.withCaption(caption: Caption, nowMs: Long): StickerProject
fun StickerProject.withBackground(background: BackgroundMode, nowMs: Long): StickerProject
fun StickerProject.withStroke(stroke: MaskStroke, nowMs: Long): StickerProject
fun StickerProject.withTitle(title: String, nowMs: Long): StickerProject

class EditHistory<T>(initial: T, maxDepth: Int = 50) {
    val current: T; val canUndo: Boolean; val canRedo: Boolean
    fun commit(next: T); fun undo(): T?; fun redo(): T?
}

object CaptionPolicy { fun normalize(text: String): String }       // trim, collapse spaces, ≤ MAX_CAPTION_CHARS

object StickerFileNamer {
    /** "stickerjet-<ascii-slug>-<yyyyMMdd-HHmmss>.webp" in UTC; suffix "-2", "-3" when names collide. */
    fun fileName(title: String, createdAtMs: Long, existing: Set<String>): String
}

object StickerProjects {
    const val FALLBACK_TITLE: String = "Sticker"
    /** Default trim; title from displayName (picked file's OpenableColumns.DISPLAY_NAME, extension dropped). */
    fun create(id: String, source: SourceVideo, displayName: String, nowMs: Long): StickerProject
    /** Trims whitespace, collapses runs of spaces, and falls back to FALLBACK_TITLE when empty. */
    fun normalizeTitle(raw: String): String
}

// Editing helpers, domain/StickerEdits.kt. Each returns a new project and sets updatedAtMs = nowMs.
fun StickerProject.withTrim(trim: TrimRange, nowMs: Long): StickerProject             // clamps into the source (TrimPolicy.clamp)
fun StickerProject.withTransform(transform: Transform, nowMs: Long): StickerProject   // throws on NaN or scale <= 0; quarterTurns mod 4
fun StickerProject.withCaption(caption: Caption, nowMs: Long): StickerProject         // text via CaptionPolicy.normalize; argb in 32 bits
fun StickerProject.withBackground(mode: BackgroundMode, nowMs: Long): StickerProject  // keeps strokes
fun StickerProject.withStroke(stroke: MaskStroke, nowMs: Long): StickerProject        // appends; validated
fun StickerProject.withoutStrokes(nowMs: Long): StickerProject                        // clears maskStrokes only
fun StickerProject.withTitle(title: String, nowMs: Long): StickerProject              // via StickerProjects.normalizeTitle
```

### 3.2 `engine` pure (SJ-D4, package `com.agency.stickerjet.engine`, module `core`)

```kotlin
object StickerComposer {
    /** Fills [canvasPx]² ARGB pixels in [out]. Pixels outside the source are fully transparent. */
    fun compose(source: ArgbFrame, alpha: AlphaMask?, transform: Transform, canvasPx: Int, out: IntArray)
}

object MaskRefiner {
    /** Applies strokes in order to [base] (null = fully opaque), then smooths the edge. Output is source-sized. */
    fun refine(base: AlphaMask?, width: Int, height: Int, strokes: List<MaskStroke>): AlphaMask
}

data class WebpFrame(val staticWebp: ByteArray, val durationMs: Int)  // one complete single-image WebP
class AnimatedWebpWriter(private val canvasPx: Int) {
    fun write(frames: List<WebpFrame>, loopCount: Int = 0): ByteArray
}
object WebpInspector {
    fun inspect(bytes: ByteArray): WebpInfo
}
data class WebpInfo(
    val canvasWidth: Int, val canvasHeight: Int, val frameCount: Int,
    val frameDurationsMs: List<Int>, val hasAlphaChunks: Boolean, val loopCount: Int,
)
```

### 3.3 `data` (SJ-D3, package `com.agency.stickerjet.data`)

```kotlin
class ProjectFiles(root: File) {               // root = context.filesDir/projects
    fun dir(projectId: String): File
    fun sourceFile(projectId: String): File    // source.mp4
    fun thumbnailFile(projectId: String): File // thumbnail.jpg
    fun exportsDir(projectId: String): File    // exports/
    fun tempDir(): File                        // cache-backed, cleared at launch
}
class FileProjectRepository(files: ProjectFiles, json: Json, clock: () -> Long) : ProjectRepository
class ImportService(resolver: ContentResolver, files: ProjectFiles, clock: () -> Long) {
    suspend fun import(uri: Uri, projectId: String): ImportOutcome
}
// ImportOutcome and ImportFailure live in `domain` (com.agency.stickerjet.domain, file domain/ImportOutcome.kt),
// not in `data`, so `ui` can match on them without importing `data`. Same names and shapes as the domain file:
// sealed interface ImportOutcome { Imported(project), Rejected(reason: ImportRejection), Failed(cause: ImportFailure) }
// enum class ImportFailure { ACCESS_REVOKED, LOW_STORAGE, IO_ERROR }
class StorageGuard(root: File) { fun hasRoomFor(bytes: Long): Boolean }
class LibraryCleaner(files: ProjectFiles, repository: ProjectRepository) {
    suspend fun clearTemporaryFiles()
    suspend fun clearThumbnails()
    /** Removes project directories without a readable project document (interrupted imports). */
    suspend fun removeOrphans(): Int
    suspend fun deleteEverything()
}
/** First-run flag, stored with Jetpack DataStore. */
class OnboardingStore(context: Context) {
    val completed: Flow<Boolean>
    suspend fun markCompleted()
}
```

### 3.4 `engine` Android (SJ-D4, package `com.agency.stickerjet.engine`, module `app`)

```kotlin
class MediaFrameSource(sourceFor: (projectId: String) -> File) : FrameSource
class MlKitSubjectSegmenter : SubjectSegmenter        // selfie segmentation, single-image mode, raw size mask
class FrameEncoder(private val budgetBytes: Long = StickerLimits.MAX_EXPORT_BYTES) {
    fun encode(frame: Bitmap, quality: Int): ByteArray   // static WebP
}
class CaptionRenderer { fun draw(canvas: android.graphics.Canvas, caption: Caption, sizePx: Int) }

/** Renders the animation as bitmaps for the editor's live preview. Same pipeline as export, smaller canvas. */
class StickerPreviewRenderer(
    frames: FrameSource, segmenter: SubjectSegmenter, encoder: FrameEncoder,
) {
    suspend fun render(project: StickerProject, canvasPx: Int): PreviewResult
}
data class PreviewResult(
    val frames: List<Bitmap>, val durationsMs: IntArray, val subjectFrames: Int, val totalFrames: Int,
)
class StickerExporter(
    frames: FrameSource, segmenter: SubjectSegmenter, encoder: FrameEncoder,
    repository: ProjectRepository, files: ProjectFiles, clock: () -> Long,
) {
    /** Emits progress 0..1. Cancellation via coroutine cancel removes the temp file. */
    suspend fun export(project: StickerProject, onProgress: (Float) -> Unit): ExportOutcome
}
sealed interface ExportOutcome {
    data class Success(val file: File, val record: ExportRecord, val subjectFrames: Int, val totalFrames: Int) : ExportOutcome
    data class Failed(val reason: ExportFailure) : ExportOutcome
}
enum class ExportFailure { SOURCE_MISSING, TOO_LARGE, LOW_STORAGE, SEGMENTATION_FAILED, INTERNAL }
```

### 3.5 `platform` (SJ-D5, package `com.agency.stickerjet.platform`)

```kotlin
object ExportSharing {
    fun shareIntent(context: Context, file: File): Intent     // FileProvider URI + FLAG_GRANT_READ_URI_PERMISSION
}
@Composable fun rememberVideoPicker(onPicked: (Uri?) -> Unit): () -> Unit   // Photo Picker, video only
```

### 3.6 `app` composition root and `ui` (SJ-PM wires; SJ-D1 consumes)

`app.AppContainer` (PM) holds: `ProjectRepository`, `ImportService`, `FrameSource`, `SubjectSegmenter`,
`StickerExporter`, `StickerPreviewRenderer`, `LibraryCleaner`, `StorageGuard`, `OnboardingStore`.
`MainActivity` (PM) calls `ui.navigation.StickerJetRoot(container: AppContainer)`, which SJ-D1 owns.
`ui` ViewModels receive ports through constructor parameters only, and screens never construct data or
engine classes. SJ-D1 defines `ui` interfaces for anything it needs that is not in this list.

## 4. Product rules every lane implements

- Source clip ≤ 30 s and ≤ 150 MB; sticker 0.5–3.0 s, default 2.0 s; canvas 512 px; 12 fps.
- Export ≤ 500 KB. A quality ladder is tried before reporting `TOO_LARGE`. The draft is never deleted on failure.
- Background removal is person-focused. The UI must say so and show the fallback when no person is found.
- No account, no network, no analytics identifiers. Original video stays inside app-private storage.
- Every user-visible failure has a message and a retry or cancel path. Nothing claims a messenger
  "installs" the sticker: the output is shared as a file.
