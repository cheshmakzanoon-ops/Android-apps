package com.agency.timeflip.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/*
 * MVP export lifecycle policy (documented in docs/qa-test-plan.md, known limitations).
 *
 * - An export runs in the app process only. There is no foreground service, WorkManager job or
 *   wake lock in this MVP.
 * - When the process dies, the export stops. The engine deletes its partial file in a finally block,
 *   and StorageRecovery removes any leftovers on the next start. No finished MP4 is recorded, so the
 *   user starts the export again.
 * - Android may freeze or stop a process whose activity is in the background. A running export can
 *   then stall or stop. This is not a background export guarantee.
 */

/**
 * While [exporting] is true, the system Back gesture is handled here instead of leaving the screen.
 * [onLeaveAttempt] lets the UI explain the policy and offer "Stay" or an explicit "Cancel export".
 * The UI must act on that choice. This composable never cancels the export or leaves the screen on
 * its own.
 */
@Composable
fun ExportLeaveGuard(exporting: Boolean, onLeaveAttempt: () -> Unit) {
    BackHandler(enabled = exporting, onBack = onLeaveAttempt)
}
