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

@Suppress("DEPRECATION")
internal suspend fun discoverAndroidDevices(context:Context):List<DiscoveredDevice> = coroutineScope {
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    val multicast = wifi?.createMulticastLock("RProbeDesk.discovery")
    val native = try {
        multicast?.acquire()
        nativeDiscovery()
    } finally { if (multicast?.isHeld == true) multicast.release() }
    if (native.isNotEmpty()) return@coroutineScope native
    // Android NSD remains available on networks where applications cannot receive multicast.
    val manager=context.getSystemService(NsdManager::class.java)
    val capacity = 128
    val services=Channel<Pair<String,NsdServiceInfo>>(capacity)
    val present=ConcurrentHashMap.newKeySet<Pair<String,String>>()
    val failed=ConcurrentHashMap.newKeySet<String>()
    val results=ConcurrentHashMap<Pair<String,String>,DiscoveredDevice>()
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
            val key = type to service.serviceName
            discoveredDevice(service.serviceName,ip,info.port,type)?.let { device ->
                synchronized(present) { if (key in present) results[key] = device }
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
                    val key = type to info.serviceName
                    synchronized(present) {
                        if (present.size < capacity && present.add(key) && services.trySend(type to info).isFailure)
                            present.remove(key)
                    }
                }
                override fun onServiceLost(info:NsdServiceInfo) {
                    val key = type to info.serviceName
                    synchronized(present) { present.remove(key); results.remove(key) }
                }
            }
            manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener)
            listeners.add(listener)
        }
        delay(4500)
        resolver.cancelAndJoin()
        check(failed.size<adbServiceTypes.size) { "设备发现失败" }
        results.values.toList()
    } finally {
        for(listener in listeners) try { manager.stopServiceDiscovery(listener) } catch(_:IllegalArgumentException) { }
        services.close()
        resolver.cancel()
    }
}
