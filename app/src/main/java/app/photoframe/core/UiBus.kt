package app.photoframe.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class ToastMessage(val title: String?, val message: String, val icon: String?, val durationSec: Int, val id: Long = System.nanoTime())

/** Transient UI signals from background components (server, HA) to the Compose layer. */
class UiBus {
    val toast = MutableStateFlow<ToastMessage?>(null)
    val pairingCode = MutableStateFlow<String?>(null)
    val touch = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}
