package app.photoframe.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Tiny helper around `su` (Magisk). Every call is a fresh process; fine for our low call rate. */
object RootShell {
    @Volatile private var cached: Boolean? = null

    val isRooted: Boolean
        get() = cached ?: probe().also { cached = it }

    private fun probe(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 &&
            p.inputStream.bufferedReader().readText().contains("uid=0")
        if (!ok) p.destroy()
        ok
    }.getOrDefault(false)

    fun execBlocking(cmd: String): Result<String> = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroy(); error("timeout: $cmd") }
        val out = p.inputStream.bufferedReader().readText()
        if (p.exitValue() != 0) error("exit ${p.exitValue()}: $out")
        out
    }.onFailure { Log.w("RootShell", "failed: $cmd", it) }

    suspend fun exec(cmd: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isRooted) Result.failure(IllegalStateException("not rooted")) else execBlocking(cmd)
    }
}
