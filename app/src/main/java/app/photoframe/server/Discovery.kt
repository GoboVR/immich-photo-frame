package app.photoframe.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/** Advertises `_photoframe._tcp` so Home Assistant discovers the frame via zeroconf. */
class Discovery(private val context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    fun register(name: String, port: Int, id: String) {
        unregister()
        if (multicastLock == null) {
            multicastLock = context.applicationContext.getSystemService(WifiManager::class.java)
                .createMulticastLock("photoframe-mdns").apply { setReferenceCounted(false); acquire() }
        }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_photoframe._tcp."
            setPort(port)
            setAttribute("id", id)
            setAttribute("name", name)
            setAttribute("version", "1")
            setAttribute("model", Build.MODEL)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "registered ${s.serviceName}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.w(TAG, "registration failed $code") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { Log.w(TAG, "register", it) }
    }

    @Synchronized
    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object { private const val TAG = "Discovery" }
}
