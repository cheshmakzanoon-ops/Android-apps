package com.agency.photoduel.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.agency.photoduel.R
import com.agency.photoduel.ui.common.AppEnv
import com.agency.photoduel.ui.common.PdScreen
import com.agency.photoduel.ui.common.ScrollColumn
import com.agency.photoduel.ui.common.SectionHeading
import com.agency.photoduel.ui.navigation.GuidelineTopic
import com.agency.photoduel.ui.theme.LocalReduceMotion

/**
 * Settings and privacy. Reminders are shown switched off and disabled, with the reason: the frozen
 * UiDependencies has no notification capability and this build sends no notifications. Motion follows
 * the system "Remove animations" setting. Guidelines are in-app pages; no web address is linked.
 */
@Composable
fun SettingsScreen(env: AppEnv, onBack: () -> Unit, onOpenGuideline: (GuidelineTopic) -> Unit, onOpenBlocked: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    PdScreen(title = stringResource(R.string.settings_title), onBack = onBack) { padding ->
        ScrollColumn(padding) {
            SectionHeading(stringResource(R.string.settings_preferences_header))
            val unavailable = stringResource(R.string.settings_unavailable_state)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notifications)) },
                supportingContent = { Text(stringResource(R.string.settings_notifications_unavailable)) },
                trailingContent = {
                    Switch(
                        checked = false,
                        onCheckedChange = null,
                        enabled = false,
                        modifier = Modifier.semantics { stateDescription = unavailable },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_motion)) },
                supportingContent = {
                    Text(stringResource(if (reduceMotion) R.string.settings_motion_reduced else R.string.settings_motion_full))
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            )
            if (!env.deps.serverConfigured) {
                Text(
                    text = stringResource(R.string.settings_server_not_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            SectionHeading(stringResource(R.string.settings_guidelines_header))
            LinkRow(stringResource(R.string.guideline_community)) { onOpenGuideline(GuidelineTopic.COMMUNITY) }
            LinkRow(stringResource(R.string.guideline_privacy)) { onOpenGuideline(GuidelineTopic.PRIVACY) }
            LinkRow(stringResource(R.string.guideline_voting)) { onOpenGuideline(GuidelineTopic.VOTING) }
            HorizontalDivider()
            SectionHeading(stringResource(R.string.settings_safety_header))
            LinkRow(stringResource(R.string.settings_blocked), onClick = onOpenBlocked)
        }
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/** One guideline page. The text is bundled in the app (string resources), so it works offline. */
@Composable
fun GuidelineScreen(topic: GuidelineTopic, onBack: () -> Unit) {
    val (title, body) = when (topic) {
        GuidelineTopic.COMMUNITY -> R.string.guideline_community to R.string.guideline_community_body
        GuidelineTopic.PRIVACY -> R.string.guideline_privacy to R.string.guideline_privacy_body
        GuidelineTopic.VOTING -> R.string.guideline_voting to R.string.guideline_voting_body
    }
    PdScreen(title = stringResource(title), onBack = onBack) { padding ->
        ScrollColumn(padding) {
            stringResource(body).split("\n\n").forEach { paragraph ->
                Text(text = paragraph.trim(), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
