package dev.rprobedesk

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

internal class DiscoveryResult(private val job: Job, private val update: ((List<DiscoveredDevice>) -> Unit)? = null) {
    val devices = mutableListOf<DiscoveredDevice>()
    fun begin() = devices.clear()
    fun complete() { update?.invoke(devices.toList()) }
    fun active() = job.isActive
    fun found(name: String, ip: String, port: Int, service: String) {
        discoveredDevice(name, ip, port, service)?.let(devices::add)
    }
}

internal suspend fun nativeDiscovery(update: ((List<DiscoveredDevice>) -> Unit)? = null): List<DiscoveredDevice> =
    withContext(Dispatchers.IO) {
        val result = DiscoveryResult(currentCoroutineContext().job, update)
        try { NativeCore.discover(result) } catch (e: IOException) { if (result.devices.isEmpty()) throw e }
        mergeDiscovery(listOf(result.devices))
    }


internal interface LiveDiscovery {
    suspend fun discover(onUpdate: (List<DiscoveredDevice>) -> Unit): List<DiscoveredDevice>
}
