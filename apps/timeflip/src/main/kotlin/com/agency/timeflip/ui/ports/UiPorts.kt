package com.agency.timeflip.ui.ports

import com.agency.timeflip.domain.model.AlignmentEstimate
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.PixelSize
import kotlinx.coroutines.flow.Flow

/**
 * UI-side view of the automatic aligner. The composition root adapts `AutoAligner` to this type,
 * so ViewModels can be tested with the domain fakes and never reference the engine directly.
 */
fun interface AlignmentRunner {
    suspend fun align(pair: ImagePair, frame: PixelSize): AlignmentEstimate
}

/** First-run flag. The composition root implements it on top of `UserPreferences`. */
interface OnboardingStore {
    fun observeCompleted(): Flow<Boolean>

    suspend fun markCompleted()
}

/** Screen shown on launch, decided from the onboarding flag. */
enum class StartScreen { WELCOME, LIBRARY }
