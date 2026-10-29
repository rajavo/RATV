package com.ratv.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.ratv.app.data.LowLatency
import com.ratv.app.data.MatchLinks
import com.ratv.app.data.SpeedTest
import com.ratv.app.data.MatchRequest
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.RatvRoot
import com.ratv.app.ui.theme.AppTheme
import com.ratv.app.ui.theme.Palette
import com.ratv.app.ui.theme.RatvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val repository = (application as RatvApp).repository
        AppTheme.palette = Palette.of(repository.prefs.palette)
        setContent {
            CompositionLocalProvider(LocalRepo provides repository) {
                RatvTheme {
                    RatvRoot()
                }
            }
        }
        // A match opened from the schedule app (not again when the activity is only recreated).
        if (savedInstanceState == null) takeMatchLink(intent)
    }

    override fun onStart() {
        super.onStart()
        LowLatency.onShown(this)
        LowLatency.note(SpeedTest.serverOf((application as RatvApp).repository.playlist.value))
    }

    override fun onStop() {
        LowLatency.onHidden()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeMatchLink(intent)
    }

    private fun takeMatchLink(intent: Intent?) {
        MatchRequest.from(intent?.data)?.let { MatchLinks.pending.value = it }
    }
}
