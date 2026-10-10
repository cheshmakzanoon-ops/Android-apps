package com.agency.stickerjet.data

import com.agency.stickerjet.domain.ImportRejection
import com.agency.stickerjet.domain.StickerProject

/** Result of [ImportService.import]. Only [Imported] leaves files behind. */
sealed interface ImportOutcome {
    /** The project was saved. Its source clip and, when possible, its thumbnail are in the project folder. */
    data class Imported(val project: StickerProject) : ImportOutcome

    /** The clip was read and refused for a reason the user can fix. Nothing was kept. */
    data class Rejected(val reason: ImportRejection) : ImportOutcome

    /** The import could not finish for a reason outside the clip. Nothing was kept. */
    data class Failed(val cause: ImportFailure) : ImportOutcome
}

/** Why an import failed, for the user-visible message. */
enum class ImportFailure {
    /** The app can no longer read the picked file: permission was revoked, or the file is gone. */
    ACCESS_REVOKED,

    /** Not enough free space for the copy, counting the safety margin. */
    LOW_STORAGE,

    /** Any other read or write error. */
    IO_ERROR,
}
