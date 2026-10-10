package com.agency.stickerjet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.agency.stickerjet.ui.navigation.StickerJetRoot
import com.agency.stickerjet.ui.theme.StickerJetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as StickerJetApplication).container
        setContent {
            StickerJetTheme {
                StickerJetRoot(container = container)
            }
        }
    }
}
