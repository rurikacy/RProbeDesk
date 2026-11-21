package dev.rprobedesk

import android.content.Context
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

@Suppress("DEPRECATION")
internal suspend fun discoverAndroidDevices(context:Context):List<DiscoveredDevice> = coroutineScope {
    val manager=context.getSystemService(NsdManager::class.java)
    val services=Channel<Pair<String,NsdServiceInfo>>(Channel.UNLIMITED)
    val present=ConcurrentHashMap.newKeySet<Pair<String,String>>()
    val failed=ConcurrentHashMap.newKeySet<String>()
    val results=mutableListOf<DiscoveredDevice>()
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
            val ip=info.host?.hostAddress ?: continue
            discoveredDevice(service.serviceName,ip,info.port,type)?.let(results::add)
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
                    if(present.add(type to info.serviceName)) services.trySend(type to info)
                }
                override fun onServiceLost(info:NsdServiceInfo) { present.remove(type to info.serviceName) }
            }
            manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener)
            listeners.add(listener)
        }
        delay(4500)
        resolver.cancelAndJoin()
        check(failed.size<adbServiceTypes.size) { "设备发现失败" }
        results.filter { it.service to it.name in present }
    } finally {
        for(listener in listeners) try { manager.stopServiceDiscovery(listener) } catch(_:IllegalArgumentException) { }
        services.close()
        resolver.cancel()
    }
}
