package app.photoframe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.photoframe.ui.FrameRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        FrameService.start(this) // restart with mic FGS type if newly granted
        FrameApp.graph.assist.refreshWakeWord()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        immersive()

        FrameService.start(this)
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())

        // Apply brightness from the screen controller to the window.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FrameApp.graph.screen.state.collect { s ->
                    window.attributes = window.attributes.apply {
                        screenBrightness = if (s.softOff) 0.0f else s.effectiveBrightness
                    }
                }
            }
        }

        setContent { FrameRoot(FrameApp.graph) }
    }

    override fun onResume() {
        super.onResume()
        immersive() // re-hide system bars (e.g. after being launched at boot)
        // Now that we're visible, (re)promote the service with microphone access.
        FrameService.start(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun immersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
