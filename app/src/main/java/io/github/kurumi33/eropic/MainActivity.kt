package io.github.kurumi33.eropic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kurumi33.eropic.ui.EropicRoot
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.theme.EropicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EropicApplication).container
        setContent {
            EropicTheme {
                val viewModel: EropicViewModel = viewModel(factory = EropicViewModel.factory(container))
                EropicRoot(viewModel)
            }
        }
    }
}
