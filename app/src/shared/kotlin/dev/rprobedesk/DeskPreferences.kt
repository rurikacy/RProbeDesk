package dev.rprobedesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

data class DeskDevice(val id: String, val name: String, val ip: String, val port: Int, val lastUsed: Long,
                      val deviceDigest:String? = null, val lastModel:String? = null, val verifiedEndpoint:String? = null) {
    val address get() = if (':' in ip) "[$ip]:$port" else "$ip:$port"
    val label get() = name.ifBlank { address }
}

data class SavedCommand(val id: String, val name: String, val command: String)

/** The current device selection, saved devices and commands belong to this host. */
class DeskPreferences(private val dataDir: File) {
    private val file = File(dataDir, "desk.properties")
    private var snapshot by mutableStateOf(Properties())
    var error by mutableStateOf<String?>(null)
        private set
    val selectedId: String? get() = snapshot.getProperty("selected")
    val devices get() = devicesIn(snapshot)
    val commands get() = ids(snapshot, "command").map { id ->
        SavedCommand(id, snapshot.getProperty("command.$id.name"), snapshot.getProperty("command.$id.text"))
    }.sortedBy { it.name }

    init {
        try { snapshot = read() } catch (e: Exception) { error = e.localizedMessage ?: "无法读取本地配置" }
    }

    fun select(id: String) = change { setProperty("selected", id) }
    fun markConnected(id: String, digest:String?=null, model:String?=null, endpoint:String?=null) = change {
        if (containsKey("device.$id.name")) {
            setProperty("device.$id.last", System.currentTimeMillis().toString())
            setProperty("selected", id)
            if(digest==null) remove("device.$id.digest") else setProperty("device.$id.digest",digest)
            if(model!=null) setProperty("device.$id.model",model)
            if(endpoint!=null) setProperty("device.$id.verifiedEndpoint",endpoint)
        }
    }

    fun saveDevice(id: String?, name: String, ip: String, port: Int): String {
        var result = id ?: UUID.randomUUID().toString()
        change {
            val duplicate = devicesIn(this).firstOrNull { it.ip == ip && it.port == port && it.id != id }
            if (duplicate != null) {
                require(id == null) { "该端点已保存为「${duplicate.label}」" }
                result = duplicate.id
            } else {
                setProperty("device.$result.name", name.trim())
                setProperty("device.$result.ip", ip)
                setProperty("device.$result.port", port.toString())
            }
            setProperty("selected", result)
        }
        return result
    }

    fun removeDevice(id: String) = change {
        keys.removeAll { it.toString().startsWith("device.$id.") }
        if (getProperty("selected") == id) remove("selected")
    }

    fun saveCommand(id: String?, name: String, command: String) = change {
        require(name.isNotBlank() && command.isNotBlank()) { "请输入名称和命令" }
        val key = id ?: UUID.randomUUID().toString()
        setProperty("command.$key.name", name.trim())
        setProperty("command.$key.text", command)
    }

    fun removeCommand(id: String) = change { keys.removeAll { it.toString().startsWith("command.$id.") } }

    private fun read() = Properties().apply {
        if (file.exists()) {
            file.inputStream().use { load(it) }
            require(getProperty("version") == "1") { "本地配置版本不兼容，请保留原文件" }
            require(ids(this,"device").all { getProperty("device.$it.ip")?.isNotBlank() == true && (getProperty("device.$it.port")?.toIntOrNull() ?: 0) in 1..65535 }
                && ids(this,"command").all { getProperty("command.$it.text") != null }) { "本地配置损坏，请保留原文件" }
        } else setProperty("version", "1")
    }

    private fun change(edit: Properties.() -> Unit) = synchronized(lock) {
        try {
            dataDir.mkdirs()
            RandomAccessFile(File(dataDir, "desk.lock"), "rw").use { stream ->
                stream.channel.lock().use {
                    val updated = read().apply(edit)
                    val temp = File.createTempFile("desk-", ".tmp", dataDir)
                    try {
                        temp.outputStream().use { output -> updated.store(output, null); output.fd.sync() }
                        try {
                            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        snapshot = updated
                        error = null
                    } finally { temp.delete() }
                }
            }
        } catch (e: Exception) {
            error = e.localizedMessage ?: "无法保存本地配置"
            throw e
        }
    }

    private fun ids(p: Properties, prefix: String) = p.stringPropertyNames().filter {
        it.startsWith("$prefix.") && it.endsWith(".name")
    }.map { it.removePrefix("$prefix.").removeSuffix(".name") }

    private fun devicesIn(p: Properties) = ids(p, "device").map { id ->
        DeskDevice(id, p.getProperty("device.$id.name"), p.getProperty("device.$id.ip"),
            p.getProperty("device.$id.port").toInt(), p.getProperty("device.$id.last")?.toLongOrNull() ?: 0L,
            p.getProperty("device.$id.digest"),p.getProperty("device.$id.model"),p.getProperty("device.$id.verifiedEndpoint"))
    }.sortedWith(compareByDescending<DeskDevice> { it.lastUsed }.thenBy { it.label })

    private companion object { val lock = Any() }
}
