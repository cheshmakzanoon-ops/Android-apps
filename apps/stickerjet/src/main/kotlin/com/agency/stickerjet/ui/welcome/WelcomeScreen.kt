package com.agency.stickerjet.ui.welcome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.agency.stickerjet.R
import com.agency.stickerjet.ui.components.StickerPrimaryButton
import com.agency.stickerjet.ui.theme.Spacing
import com.agency.stickerjet.ui.theme.StickerPalette
import com.agency.stickerjet.ui.theme.StickerShapes

/**
 * First-run welcome. It says what the app does, states the privacy promise in plain words, and
 * continues once. It is shown only until [onContinue] marks onboarding complete.
 */
@Composable
fun WelcomeScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        BrandMark()
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StepLine(number = 1, text = stringResource(R.string.welcome_step_import))
            StepLine(number = 2, text = stringResource(R.string.welcome_step_edit))
            StepLine(number = 3, text = stringResource(R.string.welcome_step_share))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(StickerShapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.welcome_privacy_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.welcome_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StickerPrimaryButton(
            text = stringResource(R.string.welcome_cta),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One numbered step. The number is part of the text, so it is read aloud in order. */
@Composable
private fun StepLine(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(R.string.welcome_step_number, number),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Decorative mark drawn from the palette: a Jet tile with a Paper and a Sun sticker. Not announced. */
@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(MARK_SIZE)) {
        drawRoundRect(
            color = StickerPalette.Jet,
            cornerRadius = CornerRadius(size.minDimension * 0.28f),
        )
        drawCircle(
            color = StickerPalette.Paper,
            radius = size.minDimension * 0.24f,
            center = Offset(size.width * 0.40f, size.height * 0.44f),
        )
        drawCircle(
            color = StickerPalette.Sun,
            radius = size.minDimension * 0.14f,
            center = Offset(size.width * 0.70f, size.height * 0.68f),
        )
    }
}

private val MARK_SIZE = 96.dp
