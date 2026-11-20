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
    data class Svc(val type: String, val port: Int, val txt: Map<String, String>)
    data class Found(val name: String, val ip: String, val services: List<Svc>) {
        /** Adresse IPP annoncée (port + chemin « rp »). */
        fun ippUrl(): String? = (services.firstOrNull { it.type.startsWith("_ipp.") } ?: services.firstOrNull { it.type.startsWith("_ipps.") })
            ?.let { s -> "${if (s.type.startsWith("_ipps")) "https" else "http"}://$ip:${s.port}/${s.txt["rp"].orEmpty().trimStart('/')}" }

        /** Racine eSCL annoncée (port + chemin « rs »). */
        fun esclRoot(): String? = (services.firstOrNull { it.type.startsWith("_uscan.") } ?: services.firstOrNull { it.type.startsWith("_uscans.") })
            ?.let { s -> "${if (s.type.startsWith("_uscans")) "https" else "http"}://$ip:${s.port}/${s.txt["rs"].orEmpty().trimStart('/')}".trimEnd('/') }

        fun summary(): String = services.joinToString("\n") { s ->
            val extra = listOf("rp", "rs").mapNotNull { k -> s.txt[k]?.let { "$k=$it" } }.joinToString(" ")
            "${s.type.trimEnd('.')} : port ${s.port} $extra".trim()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun search(ctx: Context, seconds: Int = 6): List<Found> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("canonhub").apply { setReferenceCounted(false); acquire() }
        val services = mutableListOf<NsdServiceInfo>()
        val listeners = listOf("_ipp._tcp.", "_ipps._tcp.", "_uscan._tcp.", "_uscans._tcp.", "_canon-bjnp1._tcp.", "_printer._tcp.", "_pdl-datastream._tcp.").map { type ->
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
        val byIp = LinkedHashMap<String, Pair<String, MutableList<Svc>>>()
        val list = synchronized(services) { services.toList() }
        for (s in list) {
            val r = withTimeoutOrNull(3000) { resolve(nsd, s) } ?: continue
            val ip = r.host?.hostAddress ?: continue
            if (ip.contains(':')) continue // IPv6 ignorée
            val txt = runCatching { r.attributes.mapValues { (_, v) -> v?.let { String(it) }.orEmpty() } }.getOrDefault(emptyMap())
            val e = byIp.getOrPut(ip) { r.serviceName to mutableListOf() }
            e.second += Svc(s.serviceType ?: "", r.port, txt)
        }
        val result = byIp.map { (ip, e) -> Found(e.first, ip, e.second) }
        runCatching { lock.release() }
        return result
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
