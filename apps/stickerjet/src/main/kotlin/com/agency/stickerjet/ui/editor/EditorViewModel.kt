package com.agency.stickerjet.ui.editor

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.BackgroundMode
import com.agency.stickerjet.domain.CaptionPosition
import com.agency.stickerjet.domain.EditHistory
import com.agency.stickerjet.domain.ExportPlan
import com.agency.stickerjet.domain.FrameSource
import com.agency.stickerjet.domain.MaskStroke
import com.agency.stickerjet.domain.NormalizedPoint
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.StrokeMode
import com.agency.stickerjet.domain.SubjectSegmenter
import com.agency.stickerjet.domain.TrimPolicy
import com.agency.stickerjet.domain.Transform
import com.agency.stickerjet.domain.withBackground
import com.agency.stickerjet.domain.withCaption
import com.agency.stickerjet.domain.withStroke
import com.agency.stickerjet.domain.withTransform
import com.agency.stickerjet.domain.withTrim
import com.agency.stickerjet.ui.navigation.Routes
import com.agency.stickerjet.ui.ports.ExportFailureKind
import com.agency.stickerjet.ui.ports.ExportPort
import com.agency.stickerjet.ui.ports.ExportResult
import com.agency.stickerjet.ui.ports.MaskRefinementPort
import com.agency.stickerjet.ui.ports.PreviewPort
import com.agency.stickerjet.ui.ports.ProjectFilesPort
import com.agency.stickerjet.ui.ports.StoragePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor state machine for one project. The project id comes from [SavedStateHandle].
 *
 * Drags update the draft only. A finished gesture commits the draft, which records one undo step and
 * saves the project. A commit that changes nothing is skipped. Public functions launch on
 * [viewModelScope]; the internal suspend functions hold the logic and are what the JVM tests call.
 */
class EditorViewModel(
    savedState: SavedStateHandle,
    private val projects: ProjectRepository,
    private val files: ProjectFilesPort,
    private val frames: FrameSource,
    private val segmenter: SubjectSegmenter,
    private val masks: MaskRefinementPort,
    private val exporter: ExportPort,
    private val preview: PreviewPort,
    private val storage: StoragePort,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val projectId: String = requireNotNull(savedState.get<String>(Routes.PROJECT_ID_ARG)) {
        "The editor needs a project id"
    }

    private val _state = MutableStateFlow(EditorUiState())

    /** Everything the editor draws. */
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var history: EditHistory<StickerProject>? = null
    private var baseMask: AlphaMask? = null
    private var previewStale = true
    private var exportJob: Job? = null

    private val project: StickerProject? get() = _state.value.project
    private val now: Long get() = clock()

    /** Loads the project. Called once when the screen first appears. */
    fun start() {
        viewModelScope.launch { load() }
    }

    /** Switches tab and prepares whatever that tab needs. */
    fun selectTab(tab: EditorTab) {
        _state.update { it.copy(tab = tab) }
        viewModelScope.launch { enterTab(tab) }
    }

    /** Moves the start handle during a drag. Not committed until [onTrimFinished]. */
    fun onTrimStartDrag(ms: Float) {
        if (ms.isFinite()) draftTrimStart(ms.toLong())
    }

    /** Moves the end handle during a drag. Not committed until [onTrimFinished]. */
    fun onTrimEndDrag(ms: Float) {
        if (ms.isFinite()) draftTrimEnd(ms.toLong())
    }

    /** Ends a trim gesture: commits the draft and refreshes the loop preview. */
    fun onTrimFinished() {
        viewModelScope.launch { commitDraft() }
    }

    /** Sets placement during a drag. Not committed until [onPlacementFinished]. */
    fun onPlacementDrag(scale: Float, offsetX: Float, offsetY: Float) {
        draftPlacement(scale, offsetX, offsetY)
    }

    /** Ends a placement gesture: commits the draft. */
    fun onPlacementFinished() {
        viewModelScope.launch { commitDraft() }
    }

    /** Turns background removal on or off. */
    fun setBackground(mode: BackgroundMode) {
        viewModelScope.launch { setBackgroundMode(mode) }
    }

    /** Runs person detection again, after a failure or for a changed frame. */
    fun detectSubject() {
        viewModelScope.launch { detectSubjectNow() }
    }

    /** Adds one brush stroke from a finished drag. */
    fun addStroke(mode: StrokeMode, points: List<NormalizedPoint>, radius: Float) {
        viewModelScope.launch { addStrokeNow(mode, points, radius) }
    }

    /** Removes every manual correction. */
    fun resetMask() {
        viewModelScope.launch { resetMaskNow() }
    }

    /** Steps back one edit. */
    fun undo() {
        viewModelScope.launch { step { it.undo() } }
    }

    /** Steps forward one edit. */
    fun redo() {
        viewModelScope.launch { step { it.redo() } }
    }

    /** Rotates the sticker by a quarter turn. */
    fun rotate() {
        viewModelScope.launch { rotateNow() }
    }

    /** Returns scale, position and rotation to their defaults. */
    fun resetPlacement() {
        viewModelScope.launch { commitIfPresent { p -> p.withTransform(Transform(), now) } }
    }

    /** Commits the caption text when the field loses focus or Done is pressed. Normalized by the domain. */
    fun commitCaption(text: String) {
        viewModelScope.launch { commitCaptionNow(text) }
    }

    /** Changes the caption position. */
    fun setCaptionPosition(position: CaptionPosition) {
        viewModelScope.launch {
            commitIfPresent { p -> p.withCaption(p.caption.copy(position = position), now) }
        }
    }

    /** Changes the caption colour. [argb] is 0xAARRGGBB; out-of-range values are ignored. */
    fun setCaptionColor(argb: Long) {
        if (argb !in 0L..MAX_ARGB) return
        viewModelScope.launch {
            commitIfPresent { p -> p.withCaption(p.caption.copy(argb = argb), now) }
        }
    }

    /** Renders the loop again, for example after a failed preview. */
    fun refreshPreview() {
        previewStale = true
        viewModelScope.launch { ensurePreview() }
    }

    /** Starts an export. The draft is kept whatever the outcome. */
    fun startExport() {
        exportJob?.cancel()
        exportJob = viewModelScope.launch { exportSticker() }
    }

    /** Cancels a running export and removes its temporary file. */
    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _state.update { it.copy(export = ExportState.Idle) }
    }

    /** Closes the export result or failure, so the user can keep editing. */
    fun dismissExport() {
        _state.update { it.copy(export = ExportState.Idle) }
    }

    internal suspend fun enterTab(tab: EditorTab) {
        when (tab) {
            EditorTab.TRIM -> {
                if (_state.value.trimLoop !is LoopState.Ready) refreshTrimLoop()
            }
            EditorTab.SUBJECT -> {
                if (_state.value.detection == SubjectDetection.IDLE) detectSubjectNow()
            }
            EditorTab.STYLE -> {
                ensureStill()
            }
            EditorTab.PREVIEW -> {
                ensurePreview()
            }
        }
    }

    internal suspend fun load() {
        _state.update { it.copy(loading = true, loadFailed = false, notFound = false) }
        val stored = try {
            projects.load(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        if (stored == null) {
            _state.update { it.copy(loading = false, notFound = true) }
            return
        }
        val loaded = if (TrimPolicy.isValid(stored.trim, stored.source.durationMs)) {
            stored
        } else {
            stored.withTrim(stored.trim, now)
        }
        history = EditHistory(loaded)
        val missing = withContext(Dispatchers.IO) { !files.sourceFile(projectId).isFile }
        _state.update {
            it.copy(
                loading = false,
                project = loaded,
                canUndo = false,
                canRedo = false,
                sourceMissing = missing,
            )
        }
        if (!missing) refreshTrimLoop()
    }

    internal suspend fun refreshTrimLoop() {
        val current = project ?: return
        if (_state.value.sourceMissing) return
        _state.update { it.copy(trimLoop = LoopState.Loading) }
        val result: LoopState<ArgbFrame> = try {
            val timestamps = ExportPlan.frameTimestampsMs(current.trim)
            val durations = ExportPlan.frameDurationsMs(current.trim).toList()
            val decoded = frames.frames(current.source.fileName, timestamps, TRIM_LOOP_MAX_SIDE_PX).toList()
            LoopState.Ready(decoded, durations, subjectFrames = 0, totalFrames = decoded.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoopState.Failed
        }
        _state.update { it.copy(trimLoop = result) }
    }

    internal fun draftTrimStart(requestedMs: Long) {
        val current = project ?: return
        val sourceMs = current.source.durationMs
        if (sourceMs < StickerLimits.MIN_STICKER_MS) return
        val trim = TrimEditor.moveStart(current.trim, requestedMs, sourceMs)
        _state.update { it.copy(project = current.withTrim(trim, now)) }
    }

    internal fun draftTrimEnd(requestedMs: Long) {
        val current = project ?: return
        val sourceMs = current.source.durationMs
        if (sourceMs < StickerLimits.MIN_STICKER_MS) return
        val trim = TrimEditor.moveEnd(current.trim, requestedMs, sourceMs)
        _state.update { it.copy(project = current.withTrim(trim, now)) }
    }

    internal fun draftPlacement(scale: Float, offsetX: Float, offsetY: Float) {
        val current = project ?: return
        if (!scale.isFinite() || !offsetX.isFinite() || !offsetY.isFinite()) return
        val transform = current.transform.copy(
            scale = scale.coerceIn(PlacementLimits.MIN_SCALE, PlacementLimits.MAX_SCALE),
            offsetX = offsetX.coerceIn(-PlacementLimits.MAX_OFFSET, PlacementLimits.MAX_OFFSET),
            offsetY = offsetY.coerceIn(-PlacementLimits.MAX_OFFSET, PlacementLimits.MAX_OFFSET),
        )
        _state.update { it.copy(project = current.withTransform(transform, now)) }
    }

    /** Records the draft as one undo step, if it differs from the last committed state. */
    internal suspend fun commitDraft() {
        val draft = project ?: return
        commit(draft)
    }

    internal suspend fun setBackgroundMode(mode: BackgroundMode) {
        commitIfPresent { p -> p.withBackground(mode, now) }
    }

    internal suspend fun detectSubjectNow() {
        val still = ensureStill()
        if (still == null) {
            _state.update { it.copy(detection = SubjectDetection.FAILED) }
            return
        }
        _state.update { it.copy(detection = SubjectDetection.RUNNING) }
        val mask = try {
            segmenter.segment(still)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(detection = SubjectDetection.FAILED) }
            return
        }
        baseMask = mask
        _state.update {
            it.copy(detection = if (mask == null) SubjectDetection.NO_PERSON else SubjectDetection.FOUND)
        }
        recomputeMatte()
    }

    internal suspend fun addStrokeNow(mode: StrokeMode, points: List<NormalizedPoint>, radius: Float) {
        if (!radius.isFinite() || radius <= 0f) return
        val clean = points
            .filter { it.x.isFinite() && it.y.isFinite() }
            .map { NormalizedPoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
        if (clean.isEmpty()) return
        commitIfPresent { p -> p.withStroke(MaskStroke(mode, clean, radius), now) }
    }

    internal suspend fun resetMaskNow() {
        val current = project ?: return
        if (current.maskStrokes.isEmpty()) return
        // The domain has no clear helper; copy() only drops the list and keeps every other rule.
        commit(current.copy(maskStrokes = emptyList(), updatedAtMs = now))
    }

    internal suspend fun rotateNow() {
        commitIfPresent { p ->
            p.withTransform(p.transform.copy(quarterTurns = (p.transform.quarterTurns + 1) % QUARTER_TURNS), now)
        }
    }

    internal suspend fun commitCaptionNow(text: String) {
        commitIfPresent { p -> p.withCaption(p.caption.copy(text = text), now) }
    }

    internal suspend fun ensurePreview() {
        val current = project ?: return
        if (!previewStale && _state.value.previewLoop is LoopState.Ready) return
        previewStale = false
        _state.update { it.copy(previewLoop = LoopState.Loading) }
        val result: LoopState<Bitmap> = try {
            val rendered = preview.render(current, PREVIEW_CANVAS_PX)
            LoopState.Ready(
                frames = rendered.frames,
                durationsMs = rendered.durationsMs,
                subjectFrames = rendered.subjectFrames,
                totalFrames = rendered.totalFrames,
            )
        } catch (e: CancellationException) {
            previewStale = true
            throw e
        } catch (e: Exception) {
            previewStale = true
            LoopState.Failed
        }
        _state.update { it.copy(previewLoop = result) }
    }

    internal suspend fun exportSticker() {
        val current = project ?: return
        if (_state.value.sourceMissing) {
            _state.update { it.copy(export = ExportState.Failed(ExportFailureKind.SOURCE_MISSING)) }
            return
        }
        if (!storage.hasRoomFor(StickerLimits.MAX_EXPORT_BYTES)) {
            _state.update { it.copy(export = ExportState.Failed(ExportFailureKind.LOW_STORAGE)) }
            return
        }
        _state.update { it.copy(export = ExportState.Running(0f)) }
        try {
            when (val result = exporter.export(current) { fraction ->
                _state.update { it.copy(export = ExportState.Running(fraction.coerceIn(0f, 1f))) }
            }) {
                is ExportResult.Success -> {
                    // Reload so the new export record is part of the draft; the exporter wrote it.
                    val reloaded = projects.load(projectId) ?: current
                    history = EditHistory(reloaded)
                    _state.update {
                        it.copy(
                            project = reloaded,
                            canUndo = false,
                            canRedo = false,
                            export = ExportState.Done(result.file, result.record, result.subjectFrames, result.totalFrames),
                        )
                    }
                }
                is ExportResult.Failed -> _state.update { it.copy(export = ExportState.Failed(result.reason)) }
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(export = ExportState.Idle) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(export = ExportState.Failed(ExportFailureKind.INTERNAL)) }
        }
    }

    private suspend fun ensureStill(): ArgbFrame? {
        _state.value.still?.let { return it }
        val current = project ?: return null
        if (_state.value.sourceMissing) return null
        val still = try {
            frames.frames(current.source.fileName, listOf(current.trim.startMs), STILL_MAX_SIDE_PX).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (still == null) {
            _state.update { it.copy(stillFailed = true) }
            return null
        }
        _state.update { it.copy(still = still, stillFailed = false) }
        return still
    }

    private suspend fun recomputeMatte() {
        val current = project ?: return
        val still = _state.value.still
        if (still == null) {
            _state.update { it.copy(matte = null) }
            return
        }
        val mask = baseMask
        val matte = withContext(Dispatchers.Default) {
            val refined = masks.refine(mask, still.width, still.height, current.maskStrokes)
            ArgbFrame(still.width, still.height, SubjectMatte.compose(still, refined), still.timestampMs)
        }
        _state.update { it.copy(matte = matte) }
    }

    /** Applies [edit] to the draft and commits it, unless the project is not loaded. */
    private suspend fun commitIfPresent(edit: (StickerProject) -> StickerProject) {
        val current = project ?: return
        commit(edit(current))
    }

    private suspend fun commit(next: StickerProject) {
        val h = history ?: return
        val before = h.current
        if (next == before) {
            _state.update { it.copy(project = next) }
            return
        }
        h.commit(next)
        previewStale = true
        _state.update { it.copy(project = next, canUndo = h.canUndo, canRedo = h.canRedo) }
        persist(next)
        applyEffects(before, next)
    }

    private suspend fun step(move: (EditHistory<StickerProject>) -> StickerProject?) {
        val h = history ?: return
        val before = h.current
        val next = move(h) ?: return
        previewStale = true
        _state.update { it.copy(project = next, canUndo = h.canUndo, canRedo = h.canRedo) }
        persist(next)
        applyEffects(before, next)
    }

    /** Work that follows a change: a new trim invalidates the still frame; new strokes rebuild the matte. */
    private suspend fun applyEffects(before: StickerProject, after: StickerProject) {
        if (after.trim != before.trim) {
            baseMask = null
            _state.update {
                it.copy(still = null, stillFailed = false, matte = null, detection = SubjectDetection.IDLE)
            }
            refreshTrimLoop()
        } else if (after.maskStrokes != before.maskStrokes) {
            recomputeMatte()
        }
    }

    private suspend fun persist(saved: StickerProject) {
        val ok = try {
            projects.save(saved)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        _state.update { it.copy(saveFailed = !ok) }
    }

    companion object {
        /** Longest side of the still frame used by the Subject and Style tabs. */
        const val STILL_MAX_SIDE_PX: Int = 384

        /** Longest side of each frame in the trim loop. */
        const val TRIM_LOOP_MAX_SIDE_PX: Int = 256

        /** Canvas size of the editor's rendered preview. Export always uses the full 512 px canvas. */
        const val PREVIEW_CANVAS_PX: Int = 256

        private const val QUARTER_TURNS: Int = 4
        private const val MAX_ARGB: Long = 0xFFFF_FFFFL
    }
}
