package com.agency.photoduel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.agency.photoduel.R
import com.agency.photoduel.domain.port.ServerClock
import com.agency.photoduel.ui.state.Countdown
import com.agency.photoduel.ui.state.CountdownKind
import com.agency.photoduel.ui.state.ServerTimeAnchor
import com.agency.photoduel.ui.theme.LocalPdPalette
import com.agency.photoduel.ui.theme.PdSpace
import com.agency.photoduel.ui.theme.PdType
import kotlinx.coroutines.delay

/**
 * Server time for display, refreshed twice a second while [live]. Derived from [anchor] and the frozen
 * [ServerClock]; never from the device clock alone. With [live] false it is computed once.
 */
@Composable
fun rememberServerNow(anchor: ServerTimeAnchor, clock: ServerClock, live: Boolean): Long {
    val now by produceState(initialValue = anchor.serverNow(clock.nowEpochMillis()), anchor, clock, live) {
        value = anchor.serverNow(clock.nowEpochMillis())
        if (!live) return@produceState
        while (true) {
            delay(500)
            value = anchor.serverNow(clock.nowEpochMillis())
        }
    }
    return now
}

enum class CountdownLabel { SUBMISSIONS, VOTING }

/**
 * Time left until [deadlineEpochMillis]. Turns to the error colour in the last ten minutes. TalkBack reads
 * a rounded duration ("Voting closes in 12 minutes"), never the ticking seconds. When the time is up it
 * says the app is waiting for the server and calls [onDeadlineReached] once: the server, not the phone,
 * moves the challenge on.
 */
@Composable
fun CountdownChip(
    deadlineEpochMillis: Long,
    serverNowMillis: Long,
    label: CountdownLabel,
    modifier: Modifier = Modifier,
    onDeadlineReached: () -> Unit = {},
) {
    val parts = Countdown.compute(deadlineEpochMillis, serverNowMillis)
    val palette = LocalPdPalette.current
    val ended = parts.kind == CountdownKind.ENDED
    val currentOnReached by rememberUpdatedState(onDeadlineReached)
    LaunchedEffect(ended) {
        if (ended) currentOnReached()
    }
    val (background, foreground) = when (parts.kind) {
        CountdownKind.OPEN -> palette.bgSunken to palette.textPrimary
        CountdownKind.URGENT -> palette.errorContainer to palette.onErrorContainer
        CountdownKind.ENDED -> palette.bgSunken to palette.textSecondary
    }
    val title = stringResource(if (label == CountdownLabel.SUBMISSIONS) R.string.countdown_submissions else R.string.countdown_voting)
    val spoken = if (ended) {
        stringResource(R.string.countdown_ended)
    } else {
        val duration = spokenDuration(parts)
        stringResource(if (label == CountdownLabel.SUBMISSIONS) R.string.countdown_a11y_submission else R.string.countdown_a11y_voting, duration)
    }
    Column(
        modifier = modifier
            .background(background, MaterialTheme.shapes.medium)
            .padding(horizontal = PdSpace.md, vertical = PdSpace.sm)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(PdSpace.xxs),
    ) {
        Text(text = if (ended) stringResource(R.string.countdown_ended) else title, style = MaterialTheme.typography.labelLarge, color = foreground)
        if (!ended) {
            Text(text = Countdown.clockText(parts), style = PdType.countdown, color = foreground)
        }
    }
}
