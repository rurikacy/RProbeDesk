package dev.rprobedesk

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** A second process or a new device session may recover a task only after its owner releases the file lock. */
internal class WorkTicket private constructor(
    private val record: File, private val handle: RandomAccessFile, val data: Properties
) {
    val id = record.name.substringAfterLast('_').removeSuffix(".lease")
    val localFile: File get() = File(record.parentFile, "rprobe-$id.${data.getProperty("suffix")}")

    fun release(finished: Boolean) = guarded(requireNotNull(record.parentFile)) {
        handle.close()
        if (finished) record.delete()
        Unit
    }

    companion object {
        private val mutex = Any()
        private fun <T> guarded(directory: File, work: () -> T): T = synchronized(mutex) {
            directory.mkdirs()
            RandomAccessFile(File(directory, ".rprobe-tasks.lock"), "rw").use { lock ->
                lock.channel.lock().use { work() }
            }
        }

        fun create(directory: File, kind: String, fields: Map<String, String>): WorkTicket = guarded(directory) {
            val record = File(directory, ".rprobe_${kind}_${UUID.randomUUID()}.lease")
            val data = Properties().apply { putAll(fields) }
            val handle = RandomAccessFile(record, "rw")
            try {
                handle.channel.lock()
                val bytes = java.io.ByteArrayOutputStream().also { data.store(it, null) }.toByteArray()
                handle.write(bytes)
                handle.fd.sync()
                WorkTicket(record, handle, data)
            } catch (e: Throwable) { handle.close(); record.delete(); throw e }
        }

        fun records(directory: File, kind: String): List<File> = directory.listFiles { _, name ->
            name.matches(Regex("""\.rprobe_${kind}_[0-9a-f-]{36}\.lease"""))
        }?.toList().orEmpty()

        fun claim(record: File): WorkTicket? = guarded(requireNotNull(record.parentFile)) {
            if (!record.isFile) return@guarded null
            val handle = RandomAccessFile(record, "rw")
            var keep = false
            try {
                val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null || handle.length() !in 1..65536) return@guarded null
                val bytes = ByteArray(handle.length().toInt()).also(handle::readFully)
                val data = Properties().apply { bytes.inputStream().use { load(it) } }
                WorkTicket(record, handle, data).also { keep = true }
            } finally { if (!keep) handle.close() }
        }
    }
}

internal fun recoverLocalStages(cache: File) {
    for (record in WorkTicket.records(cache, "local")) {
        val ticket = WorkTicket.claim(record) ?: continue
        var done = false
        try {
            if (ticket.data.getProperty("suffix") in setOf("apk", "part", "txt")) {
                done = !ticket.localFile.exists() || ticket.localFile.delete()
            }
        } finally { ticket.release(done) }
    }
}

internal fun remoteStage(ticket: WorkTicket) =
    remotePath(ticket.data.getProperty("directory"), ".rprobedesk-${ticket.id}")

internal suspend fun DeviceSession.cleanUpload(ticket: WorkTicket, sameSession: Boolean = false) {
    val dir = remoteArg(remoteStage(ticket))
    val nonce = remoteArg(ticket.data.getProperty("marker"))
    // Only a directory created by this particular transfer on the original target contains this marker.
    val missing = if (sameSession) "[ -e $dir ] || [ -L $dir ] || exit 0\n" else ""
    checked(missing + "[ ! -L $dir ] && cd $dir && [ -f owner ] && [ ! -L owner ] && [ \"\$(cat owner)\" = $nonce ] " +
        "|| { echo '暂存目录的归属待核实'; exit 1; }\n" +
        "rm -f -- payload && rm -- owner && cd / && rmdir -- $dir")
}

internal suspend fun recoverUploads(settings: File, profile: String, device: DeviceSession): List<String> {
    val pending = mutableListOf<String>()
    for (record in WorkTicket.records(settings, "upload")) {
        val ticket = WorkTicket.claim(record) ?: continue
        var done = false
        try {
            if (ticket.data.getProperty("profile") == profile) {
                device.cleanUpload(ticket)
                done = true
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { pending.add(remoteStage(ticket)) }
        finally { ticket.release(done) }
    }
    return pending
}

