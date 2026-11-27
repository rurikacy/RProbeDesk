package dev.rprobedesk

import android.content.Context
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

internal fun androidDiscovery(context: Context) = DiscoveryAggregator(
    { update ->
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("RProbeDesk.discovery")
        try { lock?.acquire(); nativeDiscovery(update) }
        finally { if (lock?.isHeld == true) lock.release() }
    },
    { update -> discoverNsd(context, update) },
)

@Suppress("DEPRECATION")
private suspend fun discoverNsd(context: Context, update: (List<DiscoveredDevice>) -> Unit): List<DiscoveredDevice> = coroutineScope {
    val manager=context.getSystemService(NsdManager::class.java)
    val capacity = 128
    val services=Channel<Pair<String,NsdServiceInfo>>(capacity)
    fun key(type: String, service: NsdServiceInfo) = Triple(type, service.serviceName,
        if (android.os.Build.VERSION.SDK_INT >= 33) service.network else null)
    val present=mutableMapOf<Triple<String,String,android.net.Network?>, NsdServiceInfo>()
    val failed=ConcurrentHashMap.newKeySet<String>()
    val results=mutableMapOf<Triple<String,String,android.net.Network?>, List<DiscoveredDevice>>()
    val listeners=mutableListOf<NsdManager.DiscoveryListener>()
    // Older Android versions accept only one outstanding resolve request per process.
    val resolver=launch {
        for((type,service) in services) {
            val info=suspendCancellableCoroutine<NsdServiceInfo?> { pending ->
                manager.resolveService(service,object:NsdManager.ResolveListener {
                    override fun onResolveFailed(service:NsdServiceInfo,code:Int) { pending.resume(null) }
                    override fun onServiceResolved(service:NsdServiceInfo) { pending.resume(service) }
                })
            } ?: continue
            val addresses = if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
            val key = key(type,service)
            synchronized(present) {
                if (present[key] === service) {
                    results[key] = addresses.take(8).mapNotNull { address ->
                        address.hostAddress?.let { discoveredDevice(service.serviceName,it,info.port,type) }
                    }
                    update(results.values.flatten())
                }
            }
        }
    }
    try {
        for(type in adbServiceTypes) {
            val listener=object:NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t:String) {}
                override fun onDiscoveryStopped(t:String) {}
                override fun onStartDiscoveryFailed(t:String,code:Int) { failed.add(type) }
                override fun onStopDiscoveryFailed(t:String,code:Int) {}
                override fun onServiceFound(info:NsdServiceInfo) {
                    val key = key(type,info)
                    synchronized(present) {
                        if ((key in present || present.size < capacity) && services.trySend(type to info).isSuccess) present[key] = info
                    }
                }
                override fun onServiceLost(info:NsdServiceInfo) {
                    val key = key(type,info)
                    synchronized(present) { present.remove(key); results.remove(key); update(results.values.flatten()) }
                }
            }
            manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener)
            listeners.add(listener)
        }
        delay(4500)
        resolver.cancelAndJoin()
        check(failed.size<adbServiceTypes.size) { "设备发现失败" }
        synchronized(present) { results.values.flatten() }
    } finally {
        for(listener in listeners) try { manager.stopServiceDiscovery(listener) } catch(_:IllegalArgumentException) { }
        services.close()
        resolver.cancel()
    }
}
