package com.ratv.kora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshJob.schedule(this)
        setContent {
            // The whole app reads right to left, whatever the language of the TV.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                KoraApp()
            }
        }
    }
}
