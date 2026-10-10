package com.agency.timeflip.testsupport

/**
 * Visible labels the instrumented tests wait for. These are the only UI facts the tests know. They
 * must match the strings TF-D1 ships in ui/. If a label changes, change it here in the same change.
 * No label is confirmed until the instrumented job runs on an emulator.
 */
object UiLabels {
    const val WELCOME: String = "Welcome"
    const val START: String = "Start"
    const val LIBRARY_EMPTY: String = "No transformations yet"
    const val NEW_TRANSFORMATION: String = "New transformation"
    const val CHOOSE_BEFORE: String = "Choose before photo"
    const val CHOOSE_AFTER: String = "Choose after photo"
    const val OPEN_EDITOR: String = "Open editor"
    const val CROSSFADE: String = "Crossfade"
    const val EXPORT: String = "Export"
    const val SHARE: String = "Share"
}

/** Upper bounds, in milliseconds, for the slow steps on an emulator. */
object UiTimeouts {
    const val PICK_MS: Long = 10_000L
    const val ALIGNMENT_MS: Long = 90_000L
    const val EXPORT_MS: Long = 300_000L
}
