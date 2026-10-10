package com.agency.timeflip.platform

import android.content.Context
import android.provider.Settings

/**
 * True when the system animator duration scale is 0, which is what the "Remove animations"
 * accessibility setting and the developer option "Animator duration scale = off" both write.
 *
 * Screens use this to replace motion (slider sweeps, progress pulses) with static frames or
 * cross-fades. It reads only a global setting; nothing about the user is stored or logged.
 */
fun reducedMotionEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
