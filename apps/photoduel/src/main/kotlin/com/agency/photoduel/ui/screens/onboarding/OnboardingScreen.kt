package com.agency.photoduel.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agency.photoduel.R
import com.agency.photoduel.ui.common.PdButton
import com.agency.photoduel.ui.common.PdButtonStyle
import com.agency.photoduel.ui.state.OnboardingState
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.LocalReduceMotion
import com.agency.photoduel.ui.theme.PdMotion
import com.agency.photoduel.ui.theme.PdSpace

private class OnboardingPage(val icon: ImageVector, val title: Int, val body: Int)

private val PAGES = listOf(
    OnboardingPage(Icons.Filled.Lock, R.string.onboarding_1_title, R.string.onboarding_1_body),
    OnboardingPage(Icons.Filled.Star, R.string.onboarding_2_title, R.string.onboarding_2_body),
    OnboardingPage(Icons.Filled.CheckCircle, R.string.onboarding_3_title, R.string.onboarding_3_body),
)

/**
 * Three-page welcome. [onFinished] runs once when the user finishes or skips; the caller stores the
 * "onboarding completed" flag there.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    val state = remember { OnboardingState(pageCount = PAGES.size, onFinished = { finish() }) }
    val ui by state.state.collectAsStateWithLifecycle()
    val reduceMotion = LocalReduceMotion.current
    val pageText = stringResource(R.string.onboarding_page_indicator, ui.page + 1, ui.pageCount)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = PdSpace.gutter, vertical = PdSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!ui.isLast) {
                TextButton(onClick = state::skip) { Text(stringResource(R.string.onboarding_skip)) }
            } else {
                Spacer(Modifier.height(PdSpace.touchTarget))
            }
        }
        AnimatedContent(
            targetState = ui.page,
            transitionSpec = {
                val duration = if (reduceMotion) 0 else PdMotion.MEDIUM_MS
                fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))
            },
            modifier = Modifier.weight(1f),
            label = "onboarding-page",
        ) { page ->
            OnboardingPageContent(PAGES[page])
        }
        PageDots(current = ui.page, count = ui.pageCount, description = pageText)
        Spacer(Modifier.height(PdSpace.lg))
        Row(
            modifier = Modifier.widthIn(max = PdSpace.contentMaxWidth).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PdSpace.sm),
        ) {
            if (!ui.isFirst) {
                PdButton(
                    text = stringResource(R.string.onboarding_back),
                    onClick = state::previous,
                    style = PdButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f),
                )
            }
            PdButton(
                text = stringResource(if (ui.isLast) R.string.onboarding_start else R.string.onboarding_next),
                onClick = state::next,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // A plain photo-frame motif: the app is about photos, so the illustration is an empty frame.
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(LocalPdPalette.current.bgSunken, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            Icon(page.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(PdSpace.xl))
        Text(
            text = stringResource(page.title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(PdSpace.sm))
        Text(
            text = stringResource(page.body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

@Composable
private fun PageDots(current: Int, count: Int, description: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(PdSpace.xs),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == current) 10.dp else 8.dp)
                    .background(
                        if (index == current) MaterialTheme.colorScheme.primary else LocalPdPalette.current.borderStrong,
                        CircleShape,
                    ),
            )
        }
    }
}
