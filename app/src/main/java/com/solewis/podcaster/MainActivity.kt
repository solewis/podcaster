package com.solewis.podcaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.solewis.podcaster.ui.PodcasterRoot
import com.solewis.podcaster.ui.theme.PodcasterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as PodcasterApp).container
        setContent {
            PodcasterTheme {
                PodcasterRoot(container = container)
            }
        }
    }
}
