package com.example.thermohammer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.thermohammer.engine.StressEngine
import com.example.thermohammer.engine.StressEngineFactory
import com.example.thermohammer.ui.screens.MainScreen
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.systemBarsPadding

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val engineFactory = StressEngineFactory(this)
        val engine = ViewModelProvider(this, engineFactory)[StressEngine::class.java]
        lifecycle.addObserver(engine)

        setContent {
            ThermoHammerTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun ThermoHammerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0D0F14),
            surface = Color(0xFF1A1A1A),
            primary = Color(0xFF4A9EFF)
        ),
        content = {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0F14))
                    .systemBarsPadding()
            ) {
                content()
            }
        }
    )
}
