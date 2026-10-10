package com.agency.timeflip.ui.screens.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agency.timeflip.R
import com.agency.timeflip.ui.TimeFlipTheme
import com.agency.timeflip.ui.UiDependencies
import com.agency.timeflip.ui.components.BottomActionBar
import com.agency.timeflip.ui.components.PrimaryButton
import com.agency.timeflip.ui.navigation.welcomeFactory
import com.agency.timeflip.ui.theme.TimeFlipColor
import com.agency.timeflip.ui.theme.TimeFlipRadius
import com.agency.timeflip.ui.theme.TimeFlipSize
import com.agency.timeflip.ui.theme.TimeFlipSpacing

@Composable
fun WelcomeRoute(deps: UiDependencies, onStarted: () -> Unit) {
    val vm: WelcomeViewModel = viewModel<WelcomeViewModel>(factory = welcomeFactory(deps.onboarding))
    val state by vm.state.collectAsStateWithLifecycle()
    WelcomeContent(starting = state.starting, onStart = { vm.start(onStarted) })
}

/** Stateless welcome content: what the app does, that nothing leaves the device, and the Start button. */
@Composable
fun WelcomeContent(starting: Boolean, onStart: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = TimeFlipSpacing.Space16)
                .padding(top = TimeFlipSpacing.Space24),
        ) {
            SplitIllustration()
            Spacer(Modifier.height(TimeFlipSpacing.Space24))
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineSmall,
                color = TimeFlipColor.OnBackground,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(TimeFlipSpacing.Space8))
            Text(
                text = stringResource(R.string.welcome_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = TimeFlipColor.OnBackground,
            )
            Spacer(Modifier.height(TimeFlipSpacing.Space24))
            WelcomePoint(stringResource(R.string.welcome_point_device))
            Spacer(Modifier.height(TimeFlipSpacing.Space12))
            WelcomePoint(stringResource(R.string.welcome_point_account))
            Spacer(Modifier.height(TimeFlipSpacing.Space12))
            WelcomePoint(stringResource(R.string.welcome_point_permissions))
            Spacer(Modifier.height(TimeFlipSpacing.Space24))
        }
        BottomActionBar {
            PrimaryButton(
                text = stringResource(R.string.welcome_start),
                onClick = onStart,
                enabled = !starting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Two flat blocks, one for each photo: the same split as the launcher mark. No gradients. */
@Composable
private fun SplitIllustration() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(TimeFlipRadius.Medium)),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(TimeFlipColor.SurfaceRaised)
                .padding(TimeFlipSpacing.Space12),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                text = stringResource(R.string.welcome_before_label),
                style = MaterialTheme.typography.labelLarge,
                color = TimeFlipColor.OnBackground,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(TimeFlipColor.AccentContainer)
                .padding(TimeFlipSpacing.Space12),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                text = stringResource(R.string.welcome_after_label),
                style = MaterialTheme.typography.labelLarge,
                color = TimeFlipColor.OnAccentContainer,
            )
        }
    }
}

@Composable
private fun WelcomePoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        // Decorative: the sentence itself carries the meaning.
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = TimeFlipColor.Accent,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(TimeFlipSpacing.Space12))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TimeFlipColor.OnSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(name = "Welcome", widthDp = 390, heightDp = 844)
@Composable
private fun WelcomePreview() {
    TimeFlipTheme { WelcomeContent(starting = false, onStart = {}) }
}
