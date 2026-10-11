package com.agency.photoduel.platform

/**
 * The few user-visible strings that platform adapters must produce themselves (system dialogs, the
 * clipboard label). English only for this build. src/main/res/values belongs to the UI lane, so these
 * live in one place until PD-D1 supplies string resources; then this object is the single swap point.
 */
object PlatformTexts {
    const val PICKER_TITLE = "Add a photo"
    const val PICKER_FROM_LIBRARY = "Choose from photos"
    const val PICKER_FROM_CAMERA = "Take a photo"
    const val SHARE_SUBJECT = "Join my PhotoDuel room"
    const val CLIPBOARD_LABEL = "PhotoDuel invitation"
    const val INVITATION_COPIED = "Invitation copied"
}
