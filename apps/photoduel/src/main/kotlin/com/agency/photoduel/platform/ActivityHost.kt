package com.agency.photoduel.platform

import androidx.activity.ComponentActivity
import java.lang.ref.WeakReference

/**
 * Remembers which activity is on screen so that application scoped adapters (picker, share sheet,
 * permission prompt) can launch system UI without holding a Context that outlives it. Main thread only.
 */
class ActivityHost {
    private var reference: WeakReference<ComponentActivity>? = null

    fun attach(activity: ComponentActivity) {
        reference = WeakReference(activity)
    }

    fun detach(activity: ComponentActivity) {
        if (reference?.get() === activity) reference = null
    }

    /** The visible activity, or null when none is attached or it is already finishing or destroyed. */
    fun current(): ComponentActivity? = reference?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
