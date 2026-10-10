package com.agency.stickerjet.ui.editor

import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.MaskStroke
import com.agency.stickerjet.domain.NormalizedPoint
import com.agency.stickerjet.domain.StrokeMode
import com.agency.stickerjet.domain.withStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Returns the still frame at the trim start, decoding it once. Null when it cannot be read. */
internal suspend fun EditorSession.ensureStill(): ArgbFrame? {
    state.value.still?.let { return it }
    val current = project ?: return null
    if (state.value.sourceMissing) return null
    val still = try {
        frames.frames(current.id, listOf(current.trim.startMs), EditorFrameSizes.STILL_MAX_SIDE_PX).first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    if (still == null) {
        state.update { it.copy(stillFailed = true) }
        return null
    }
    state.update { it.copy(still = still, stillFailed = false) }
    return still
}

/** Runs person detection on the still frame. A null result means no confident person: the original background is kept. */
internal suspend fun EditorSession.detectSubjectNow() {
    val still = ensureStill()
    if (still == null) {
        state.update { it.copy(detection = SubjectDetection.FAILED) }
        return
    }
    state.update { it.copy(detection = SubjectDetection.RUNNING) }
    val mask = try {
        segmenter.segment(still)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        state.update { it.copy(detection = SubjectDetection.FAILED) }
        return
    }
    baseMask = mask
    state.update {
        it.copy(detection = if (mask == null) SubjectDetection.NO_PERSON else SubjectDetection.FOUND)
    }
    recomputeMatte()
}

/**
 * Adds one brush stroke. Points are clamped into the frame, and non-finite points are dropped. Both
 * the radius and the point list are checked here, so the domain's require() checks never fire from UI input.
 */
internal suspend fun EditorSession.addStrokeNow(mode: StrokeMode, points: List<NormalizedPoint>, radius: Float) {
    if (!radius.isFinite() || radius <= 0f) return
    val clean = points
        .filter { it.x.isFinite() && it.y.isFinite() }
        .map { NormalizedPoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
    if (clean.isEmpty()) return
    commitIfPresent { p -> p.withStroke(MaskStroke(mode, clean, radius), now) }
}

/** Removes every manual correction. The domain has no clear helper, so only the list is replaced. */
internal suspend fun EditorSession.resetMaskNow() {
    val current = project ?: return
    if (current.maskStrokes.isEmpty()) return
    commit(current.copy(maskStrokes = emptyList(), updatedAtMs = now))
}

/** Rebuilds the matte preview from the base mask and the ordered strokes. Runs off the main thread. */
internal suspend fun EditorSession.recomputeMatte() {
    val current = project ?: return
    val still = state.value.still
    if (still == null) {
        state.update { it.copy(matte = null) }
        return
    }
    val mask = baseMask
    val matte = withContext(Dispatchers.Default) {
        val refined = masks.refine(mask, still.width, still.height, current.maskStrokes)
        ArgbFrame(still.width, still.height, SubjectMatte.compose(still, refined), still.timestampMs)
    }
    state.update { it.copy(matte = matte) }
}
