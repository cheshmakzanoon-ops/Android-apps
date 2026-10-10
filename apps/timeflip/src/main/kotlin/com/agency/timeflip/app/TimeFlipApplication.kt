package com.agency.timeflip.app

import android.app.Application

/**
 * Process entry point. Creates the single [AppContainer] and holds no Activity references.
 */
class TimeFlipApplication : Application() {

    /** Set in [onCreate], before any Activity can read it. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * TEST SEAM. Instrumented tests call this before the Activity under test is created, to inject a
     * fake photo store. Production code never calls it.
     */
    fun replaceContainerForTests(replacement: AppContainer) {
        container = replacement
    }
}
