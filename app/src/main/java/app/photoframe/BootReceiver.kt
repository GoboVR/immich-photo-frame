package app.photoframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.photoframe.core.RootShell
import kotlin.concurrent.thread

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "frame-boot") {
            try {
                // Bringing the frame to the front is what gives it microphone access, and
                // Android blocks background activity starts. With root (Magisk) we can
                // simply ask the activity manager; without it, rely on being the home app.
                val launched = RootShell.isRooted &&
                    RootShell.execBlocking("am start -n ${context.packageName}/.MainActivity").isSuccess
                if (!launched) {
                    runCatching {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { Log.w("BootReceiver", "activity start blocked: ${it.message}") }
                    FrameService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
