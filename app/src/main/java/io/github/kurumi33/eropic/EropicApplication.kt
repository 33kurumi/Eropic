package io.github.kurumi33.eropic

import android.app.Application

class EropicApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
