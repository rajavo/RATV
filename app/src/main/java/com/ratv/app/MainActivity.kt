package com.ratv.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.RatvRoot
import com.ratv.app.ui.theme.RatvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val repository = (application as RatvApp).repository
        setContent {
            CompositionLocalProvider(LocalRepo provides repository) {
                RatvTheme {
                    RatvRoot()
                }
            }
        }
    }
}
