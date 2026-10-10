package com.agency.timeflip.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.agency.timeflip.ui.TimeFlipApp

/**
 * The only Activity. It keeps no state of its own: rotation and process death are handled by the
 * ViewModels through SavedStateHandle. Nothing is saved in onSaveInstanceState.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as TimeFlipApplication).container
        setContent {
            TimeFlipApp(container)
        }
    }
}
