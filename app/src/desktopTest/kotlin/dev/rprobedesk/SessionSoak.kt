package dev.rprobedesk

import java.awt.Frame
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*

/** Run against the packaged application jars and DLL for the requested elapsed minutes. */
fun main(args: Array<String>) = runBlocking(Dispatchers.Main) {
    require(args.size in 4..5) { "IP PORT EXPECTED_MODEL QA_LOCALAPPDATA [MINUTES]" }
    val minutes = args.getOrNull(4)?.toLong() ?: 1440L
    require(minutes in 1..1440)
    val profile = File(args[3]).canonicalFile
    require(profile == File(System.getenv("LOCALAPPDATA")).canonicalFile && !File(profile, "RProbeDesk/desk.properties").exists()) {
        "Launch with a new isolated QA profile"
    }
    val frame = Frame()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val host = WindowsHost(frame)
    val prefs = DeskPreferences(host.settingsDir)
    val repo = DeviceSessionRepository(host, prefs, scope)
    val id = prefs.saveDevice(null, "soak", args[0], args[1].toInt())
    try {
        repo.select(prefs.devices.single { it.id == id })
        val desk = repo.selected
        withTimeout(40000) { while (desk.session == null) { check(desk.review == null); delay(200) } }
        desk.workspace.startLogs()
        val start = System.nanoTime()
        var lastGood = start
        var nextSample = start
        var count = 0
        var connections = 0
        var previous: DeviceSession? = null
        var watcher: Job? = null
        var sampler: Job? = null
        while (true) {
            val now = System.nanoTime()
            check(now-lastGood <= 120_000_000_000L) { "No verified working sample for two minutes: ${desk.message} / ${desk.samplingError}" }
            val session = desk.session
            if (session !== previous) {
                watcher?.cancel()
                sampler?.cancel()
                previous = session
                if (session != null) {
                    ++connections
                    watcher = scope.launch { desk.watch(session) }
                    sampler = scope.launch { desk.sample(session) }
                }
                println("${Instant.now()} transport=${(session as? WindowsSession)?.transportId} connections=$connections status=${desk.message}")
            }
            if (now >= nextSample && session != null) {
                val response = try {
                    withTimeout(15000) { session.shell("getprop ro.product.model; cat /proc/uptime") }
                } catch (e: TimeoutCancellationException) { println("${Instant.now()} heartbeat timeout"); null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { println("${Instant.now()} heartbeat failed: ${e.message}"); null }
                if (response != null) {
                    val lines = response.stdout.trim().lines()
                    check(response.exitCode == 0 && lines.size == 2 && lines[0] == args[2] &&
                        lines[1].matches(Regex("[0-9.]+ [0-9.]+"))) { "Unexpected device identity or uptime" }
                    val metric = desk.metrics
                    if (metric?.memoryPercent != null && System.currentTimeMillis() - metric.captured in 0..20000 &&
                        desk.workspace.logRunning && desk.session === session) {
                        lastGood = System.nanoTime()
                        val elapsed = (lastGood-start) / 1_000_000_000
                        val runtime = Runtime.getRuntime()
                        println("${Instant.now()} elapsed_s=$elapsed sample=${++count} connections=$connections " +
                            "heap_bytes=${runtime.totalMemory()-runtime.freeMemory()} log_rows=${desk.workspace.logs.size} evicted=${desk.workspace.dropped}")
                        check(desk.workspace.logs.size <= 1500)
                        if (elapsed >= minutes * 60) break
                        nextSample = lastGood + 60_000_000_000L
                    }
                }
            }
            delay(1000)
        }
        println("PASS ${minutes}m continuous workspace with verified shell, monitoring and Logcat")
    } finally {
        scope.cancel()
        repo.close()
        frame.dispose()
    }
}
