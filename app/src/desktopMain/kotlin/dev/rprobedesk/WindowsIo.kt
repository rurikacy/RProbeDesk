package dev.rprobedesk

import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Long-lived readers never use the workers that open connections or check health. */
internal object WindowsIo {
    val rejected = AtomicLong()
    val sessions = AtomicInteger()
    val channels = AtomicInteger()
    val children = AtomicInteger()

    class Lane(val name: String, val limit: Int) {
        private val pool = ThreadPoolExecutor(0, limit, 10, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
            { job -> Thread(job, "rprobe-io-$name").apply { isDaemon = true } })
        val active get() = pool.activeCount
        val threads get() = pool.poolSize
        val waiting get() = pool.queue.size
        suspend fun <T> run(block: () -> T): T = kotlinx.coroutines.suspendCancellableCoroutine { pending ->
            val future = submit {
                if (pending.isActive) {
                    val result = runCatching(block)
                    if (pending.isActive) pending.resumeWith(result)
                }
            }
            pending.invokeOnCancellation { future.cancel(true) }
        }
        fun execute(job: Runnable) = admit { pool.execute(job) }
        fun <T> submit(job: Callable<T>): Future<T> = admit { pool.submit(job) }
        private inline fun <T> admit(start: () -> T): T = try { start() }
        catch (e: RejectedExecutionException) { exhausted(name, e) }
        fun close() = pool.shutdownNow()
    }

    val processes = Lane("process", 8)
    val pipes = Lane("pipe", 2 * processes.limit)
    val short = Lane("command", 12)
    val streams = Lane("stream", 8)
    val control = Lane("control", 6)
    val trackers = Lane("tracker", 32)
    val mirrors = Lane("mirror", 2)
    private val lanes = listOf(processes, pipes, short, streams, control, trackers, mirrors)

    fun exhausted(resource: String, cause: Throwable? = null): Nothing {
        rejected.incrementAndGet()
        throw TransportFailure(TransportError.Exhausted.code, resource, cause)
    }
    fun trackChild(process: Process): Process {
        children.incrementAndGet()
        process.onExit().thenRun { children.decrementAndGet() }
        return process
    }
    fun snapshot() = mapOf("threads" to lanes.sumOf { it.threads }.toLong(),
        "waiting" to lanes.sumOf { it.waiting }.toLong(), "sessions" to sessions.get().toLong(),
        "streams" to streams.active.toLong(), "processes" to children.get().toLong(),
        "channels" to channels.get().toLong(), "rejected" to rejected.get())
    init { Runtime.getRuntime().addShutdownHook(Thread { lanes.forEach { it.close() } }) }
}
