package dev.rprobedesk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal typealias DiscoverySource = suspend ((List<DiscoveredDevice>) -> Unit) -> List<DiscoveredDevice>

internal fun mergeDiscovery(sources: Collection<List<DiscoveredDevice>>): List<DiscoveredDevice> {
    val all = sources.flatten().distinctBy { Triple(it.name.lowercase(java.util.Locale.ROOT), it.service, it.address) }
    val named = all.filter { it.service.isNotEmpty() && !it.pairing }.mapTo(HashSet()) { it.address }
    return all.filter { it.service.isNotEmpty() || it.address !in named }
}

/** Requests share one scan. Its last subscriber cancels every platform listener. */
internal class DiscoveryAggregator(private vararg val sources: DiscoverySource) : LiveDiscovery {
    private class Scan {
        val updates = MutableStateFlow<List<DiscoveredDevice>?>(null)
        lateinit var result: Deferred<List<DiscoveredDevice>>
        var users = 0
    }
    private val lifecycle = Mutex()
    private var active: Scan? = null

    private suspend fun collect(scan: Scan): List<DiscoveredDevice> = coroutineScope {
        val parts = mutableMapOf<Int, List<DiscoveredDevice>>()
        fun publish(id: Int, rows: List<DiscoveredDevice>) = synchronized(parts) {
            parts[id] = rows.take(1024)
            scan.updates.value = mergeDiscovery(parts.toSortedMap().values)
        }
        val errors = sources.mapIndexed { id, source -> async {
            try {
                withTimeout(6000) { publish(id, source { publish(id, it) }) }
                null
            } catch (e: TimeoutCancellationException) { e }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { e }
        } }.awaitAll()
        scan.updates.value ?: throw (errors.filterNotNull().firstOrNull() ?: java.io.IOException("设备发现失败"))
    }

    override suspend fun discover(onUpdate: (List<DiscoveredDevice>) -> Unit): List<DiscoveredDevice> = coroutineScope {
        val scan = synchronized(this@DiscoveryAggregator) {
            (active ?: Scan().also { new ->
                new.result = CoroutineScope(Dispatchers.IO).async { lifecycle.withLock { collect(new) } }
                active = new
            }).also { it.users++ }
        }
        val events = launch(start = CoroutineStart.UNDISPATCHED) { scan.updates.filterNotNull().collect(onUpdate) }
        try { scan.result.await().also(onUpdate) }
        finally {
            events.cancel()
            synchronized(this@DiscoveryAggregator) {
                if (--scan.users == 0 && active === scan) { active = null; scan.result.cancel() }
            }
        }
    }
    fun stop() = synchronized(this) { active?.result?.cancel(); active = null }
}
