package com.agency.stickerjet.app

import android.app.Application

/**
 * Composition root. Dependencies are wired here by hand, with no DI framework, so the object graph
 * stays readable. Each squad lane adds its adapters to [container] as the lane integrates.
 */
class StickerJetApplication : Application()
