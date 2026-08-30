package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.FlasherViewModel
import com.example.ui.screens.MainFlasherScreen
import com.example.ui.theme.FlashCoreTheme
import com.example.ui.theme.SpaceBackground

class MainActivity : ComponentActivity() {

    private val viewModel: FlasherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlashCoreTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SpaceBackground
                ) {
                    MainFlasherScreen(viewModel = viewModel)
                }
            }
        }
    }
}

