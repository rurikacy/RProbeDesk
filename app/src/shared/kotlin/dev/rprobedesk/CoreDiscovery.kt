package dev.rprobedesk

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DiscoveryResult(private val job: Job) {
    val devices = mutableListOf<DiscoveredDevice>()
    fun active() = job.isActive
    fun found(name: String, ip: String, port: Int, service: String) {
        discoveredDevice(name, ip, port, service)?.let(devices::add)
    }
}

private val discoveryLock = Mutex()

internal suspend fun nativeDiscovery(): List<DiscoveredDevice> = discoveryLock.withLock {
    withContext(Dispatchers.IO) {
        val result = DiscoveryResult(currentCoroutineContext().job)
        try { NativeCore.discover(result) } catch (_: IOException) { return@withContext emptyList() }
        result.devices.distinctBy { it.address to it.pairing }
    }
}
