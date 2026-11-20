package be.canonhub

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Recherche des imprimantes du Wi-Fi via mDNS (services IPP et Canon BJNP). */
object Discovery {
    data class Found(val name: String, val ip: String)

    @Suppress("DEPRECATION")
    suspend fun search(ctx: Context, seconds: Int = 6): List<Found> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("canonhub").apply { setReferenceCounted(false); acquire() }
        val services = mutableListOf<NsdServiceInfo>()
        val listeners = listOf("_ipp._tcp.", "_canon-bjnp1._tcp.", "_printer._tcp.").map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(s: NsdServiceInfo) { synchronized(services) { services += s } }
                override fun onServiceLost(s: NsdServiceInfo) {}
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
            }.also { runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) } }
        }
        try {
            delay(seconds * 1000L)
        } finally {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        }
        val result = LinkedHashMap<String, Found>()
        val list = synchronized(services) { services.toList() }
        for (s in list) {
            val r = withTimeoutOrNull(3000) { resolve(nsd, s) } ?: continue
            val ip = r.host?.hostAddress ?: continue
            if (ip.contains(':')) continue // IPv6 ignorée
            result.putIfAbsent(ip, Found(r.serviceName, ip))
        }
        runCatching { lock.release() }
        return result.values.toList()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, s: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { c ->
            try {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) { if (c.isActive) c.resume(null) }
                    override fun onServiceResolved(i: NsdServiceInfo) { if (c.isActive) c.resume(i) }
                })
            } catch (e: Exception) {
                if (c.isActive) c.resume(null)
            }
        }
}
