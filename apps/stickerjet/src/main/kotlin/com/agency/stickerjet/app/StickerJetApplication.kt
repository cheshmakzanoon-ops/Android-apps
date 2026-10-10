package com.agency.stickerjet.app

import android.app.Application
import com.agency.stickerjet.platform.ExportSharing

/**
 * Process entry point. Owns the [AppContainer], so every activity and test shares one set of adapters.
 */
class StickerJetApplication : Application() {

    /** Built on first use. Holds every adapter for the life of the process. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Share copies are temporary; clear leftovers from a previous session before the UI opens.
        ExportSharing.clearShareCopies(this)
        container.runStartupMaintenance()
    }
}
