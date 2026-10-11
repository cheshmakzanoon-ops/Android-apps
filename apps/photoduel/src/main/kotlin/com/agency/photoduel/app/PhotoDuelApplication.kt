package com.agency.photoduel.app

import android.app.Application

/** Builds the single [AppContainer] for the process. */
class PhotoDuelApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
