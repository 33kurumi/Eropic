package io.github.kurumi33.eropic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kurumi33.eropic.core.settings.AppSettings
import io.github.kurumi33.eropic.core.settings.ThemeMode
import io.github.kurumi33.eropic.ui.EropicRoot
import io.github.kurumi33.eropic.ui.EropicViewModel
import io.github.kurumi33.eropic.ui.theme.EropicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EropicApplication).container
        setContent {
            val settings by container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            EropicTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
                val viewModel: EropicViewModel = viewModel(factory = EropicViewModel.factory(container))
                EropicRoot(viewModel)
            }
        }
    }
}
