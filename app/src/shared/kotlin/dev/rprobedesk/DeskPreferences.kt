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
                      val deviceDigest:String? = null, val lastModel:String? = null, val verifiedEndpoint:String? = null,
                      val wifi:String = "", val site:String = "") {
    val address get() = if (':' in ip) "[$ip]:$port" else "$ip:$port"
    val label get() = name.ifBlank { address }
}

data class SavedCommand(val id: String, val name: String, val command: String, val tag:String = "")

internal enum class DeviceGrouping(val key:String, val label:String) {
    NONE("none","不分组"), SITE("site","按场地"), WIFI("wifi","按 WiFi");

    fun groupKey(device:DeskDevice):String = when(this) {
        NONE -> ""
        SITE -> device.site.trim()
        WIFI -> device.wifi.trim()
    }

    fun sections(devices:List<DeskDevice>):Map<String,List<DeskDevice>> =
        devices.groupBy(::groupKey).toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it })
}

internal enum class DeskColorMode(val key:String, val label:String) {
    SYSTEM("system","跟随系统"), LIGHT("light","明色"), DARK("dark","暗色");

    fun isDark(systemDark:Boolean)=this==DARK || (this==SYSTEM && systemDark)
}

internal data class SyncProfile(val records: Properties, val deviceIds: Set<String>, val commandCount: Int)

/** The current device selection, saved devices and commands belong to this host. */
class DeskPreferences(private val dataDir: File) {
    private val file = File(dataDir, "desk.properties")
    private var snapshot by mutableStateOf(Properties())
    var error by mutableStateOf<String?>(null)
        private set
    val selectedId: String? get() = snapshot.getProperty("selected")
    val devices get() = devicesIn(snapshot)
    val commands get() = commandsIn(snapshot)

    init {
        try { snapshot = read() } catch (e: Exception) { error = e.localizedMessage ?: "无法读取本地配置" }
    }

    val english get() = snapshot.getProperty("language")=="en"
    fun setEnglish(value:Boolean) = change { setProperty("language",if(value) "en" else "zh") }

    internal val colorMode get()=DeskColorMode.entries.firstOrNull { it.key==snapshot.getProperty("colorMode") } ?: DeskColorMode.SYSTEM
    internal fun setColorMode(value:DeskColorMode) = change { setProperty("colorMode",value.key) }

    private fun groupingIn(p:Properties)=DeviceGrouping.entries.firstOrNull { it.key==p.getProperty("deviceGrouping") } ?: DeviceGrouping.NONE
    internal val groupCommandsByTag get()=snapshot.getProperty("groupCommandsByTag")=="true"
    internal fun setGroupCommandsByTag(value:Boolean) = change { setProperty("groupCommandsByTag",value.toString()) }
    internal val deviceGrouping get()=groupingIn(snapshot)
    internal fun setDeviceGrouping(value:DeviceGrouping) = change { setProperty("deviceGrouping",value.key) }

    internal fun isPanelGroupExpanded(group:String,defaultExpanded:Boolean) =
        snapshot.getProperty("panel.expanded.$group")?.toBooleanStrictOrNull() ?: defaultExpanded
    internal fun setPanelGroupExpanded(group:String,expanded:Boolean) = change {
        setProperty("panel.expanded.$group",expanded.toString())
    }

    val closeToTray get() = snapshot.getProperty("closeToTray","true")=="true"
    fun setCloseToTray(value:Boolean) = change { setProperty("closeToTray",value.toString()) }

    internal fun davConfig() = DavConfig(
        snapshot.getProperty("dav.server",DavConfig.DEFAULT_SERVER),snapshot.getProperty("dav.account",""),
        "",snapshot.getProperty("dav.root","r-probe-desk-sync"),snapshot.getProperty("dav.profile","default"))
    internal fun davPassword() = snapshot.getProperty("dav.secret")?.let(CloudSecret::open).orEmpty()
    internal fun saveDav(c:DavConfig) = change {
        setProperty("dav.server",c.server); setProperty("dav.account",c.account)
        setProperty("dav.root",c.root); setProperty("dav.profile",c.profile)
        if(c.password.isEmpty()) remove("dav.secret") else setProperty("dav.secret",CloudSecret.seal(c.password))
    }

    private val portableKey=Regex("""(device\.[^.]+\.(name|ip|port|wifi|site)|command\.[^.]+\.(name|text|tag))""")
    private val deviceOrderKey=Regex("""device\.[^.]+\.order""")
    private fun portable(source:Properties) = Properties().apply {
        setProperty("format","RProbeDesk/2")
        setProperty("deviceGrouping",groupingIn(source).key)
        for(key in source.stringPropertyNames()) if(portableKey.matches(key)) setProperty(key,source.getProperty(key))
        // A host with no manual ranks may still display devices in recent-connection order.
        devicesIn(source).forEachIndexed { index,device -> setProperty("device.${device.id}.order",index.toString()) }
    }
    internal fun exportSync() = portable(snapshot)
    internal fun inspectSync(remote:Properties):SyncProfile {
        val format=remote.getProperty("format")
        val hasLayout=format=="RProbeDesk/2"
        require(hasLayout || format=="RProbeDesk/1") { "同步文件格式无效" }
        val keys=remote.stringPropertyNames().filterNot { it=="format" || (hasLayout && it=="deviceGrouping") }
        require(keys.all { portableKey.matches(it) || (hasLayout && deviceOrderKey.matches(it)) }) { "同步文件格式无效" }
        val devices=ids(remote,"device").toSet()
        val commands=ids(remote,"command").toSet()
        require(keys.all {
            it.split('.')[1] in (if(it.startsWith("device.")) devices else commands)
        }) { "同步记录不完整" }
        if(hasLayout) {
            require(DeviceGrouping.entries.any { it.key==remote.getProperty("deviceGrouping") }) { "同步文件中的分组方式无效" }
            val ranks=devices.map { remote.getProperty("device.$it.order")?.toIntOrNull() }
            require(ranks.toSet()==devices.indices.toSet()) { "同步文件中的设备顺序无效" }
        }
        val records=Properties().apply { putAll(remote) }
        val endpoints=devices.map { id ->
            val ip=normalizedIp(remote.getProperty("device.$id.ip").orEmpty())
            val port=parsePort(remote.getProperty("device.$id.port").orEmpty())
            records.setProperty("device.$id.ip",ip)
            records.setProperty("device.$id.port",port.toString())
            ip to port
        }
        require(endpoints.distinct().size==devices.size && commands.all {
            !remote.getProperty("command.$it.name").isNullOrBlank() && !remote.getProperty("command.$it.text").isNullOrBlank()
        }) { "同步文件包含重复端点或无效收藏" }
        return SyncProfile(records,devices,commands.size)
    }

    internal fun applySync(profile:SyncProfile,expected:Properties) {
        val remote=profile.records
        val devices=profile.deviceIds
        change {
            check(portable(this)==expected) { "本地资料已变化，请重新同步" }
            // Only keep local identity checks for records that still refer to the same endpoint.
            for(id in ids(this,"device")) {
                if(id !in devices || listOf("ip","port").any { getProperty("device.$id.$it")!=remote.getProperty("device.$id.$it") }) {
                    keys.removeAll { it.toString().startsWith("device.$id.") && (id !in devices || it!="device.$id.order") }
                }
            }
            for(id in ids(this,"command")) if(!remote.containsKey("command.$id.name")) remove("command.$id.order")
            keys.removeAll { portableKey.matches(it.toString()) }
            for(key in remote.stringPropertyNames()) if(key!="format") setProperty(key,remote.getProperty(key))
            if(getProperty("selected") !in devices) remove("selected")
            appendUnranked("device", devicesIn(this).map { it.id })
            appendUnranked("command", commandsIn(this).map { it.id })
        }
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

    fun saveDevice(id: String?, name: String, ip: String, port: Int, wifi:String = "", site:String = ""): String {
        var result = id ?: UUID.randomUUID().toString()
        change {
            val duplicate = devicesIn(this).firstOrNull { it.ip == ip && it.port == port && it.id != id }
            if (duplicate != null) {
                require(id == null) { "该端点已保存为「${duplicate.label}」" }
                result = duplicate.id
            } else {
                setProperty("device.$result.name", name.trim())
                setProperty("device.$result.wifi",wifi.trim())
                setProperty("device.$result.site",site.trim())
                setProperty("device.$result.ip", ip)
                setProperty("device.$result.port", port.toString())
                appendUnranked("device", devicesIn(this).map { it.id })
            }
            setProperty("selected", result)
        }
        return result
    }

    fun removeDevice(id: String) = change {
        keys.removeAll { it.toString().startsWith("device.$id.") }
        if (getProperty("selected") == id) remove("selected")
    }

    fun saveCommand(id: String?, name: String, command: String, tag:String = "") = change {
        require(name.isNotBlank() && command.isNotBlank()) { "请输入名称和命令" }
        val key = id ?: UUID.randomUUID().toString()
        setProperty("command.$key.name", name.trim())
        setProperty("command.$key.text", command)
        if(tag.isBlank()) remove("command.$key.tag") else setProperty("command.$key.tag",tag.trim())
        appendUnranked("command", commandsIn(this).map { it.id })
    }

    fun removeCommand(id: String) = change { keys.removeAll { it.toString().startsWith("command.$id.") } }

    fun reorderDevices(orderedIds: List<String>) = reorderDevices(orderedIds, DeviceGrouping.NONE, "")

    internal fun reorderDevices(orderedIds:List<String>, grouping:DeviceGrouping, groupKey:String) = change {
        if(groupingIn(this)!=grouping) return@change
        val devices=devicesIn(this)
        val members=devices.filter { grouping.groupKey(it)==groupKey }.mapTo(HashSet()) { it.id }
        reorder("device", devices.map { it.id }, orderedIds.filter { it in members })
    }

    fun reorderCommands(orderedIds: List<String>, tag:String?=null) = change {
        val commands=commandsIn(this)
        val members=commands.filter { tag==null || it.tag==tag }.mapTo(HashSet()) { it.id }
        reorder("command", commands.map { it.id }, orderedIds.filter { it in members })
    }

    private fun Properties.reorder(prefix: String, current: List<String>, orderedIds: List<String>) {
        val existing = current.toHashSet()
        val ordered = orderedIds.filter { it in existing }.distinct()
        val visible = ordered.toHashSet()
        if (current.filter { it in visible } == ordered) return
        // Hidden rows and rows added by another window keep their positions.
        val replacements = ordered.iterator()
        current.forEachIndexed { index, id ->
            val placed = if (id in visible) replacements.next() else id
            setProperty("$prefix.$placed.order", index.toString())
        }
    }

    private fun Properties.appendUnranked(prefix: String, ids: List<String>) {
        var last = ids.mapNotNull { getProperty("$prefix.$it.order")?.toIntOrNull() }.maxOrNull() ?: return
        for (id in ids) if (!containsKey("$prefix.$id.order")) setProperty("$prefix.$id.order", (++last).toString())
    }

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
            p.getProperty("device.$id.digest"),p.getProperty("device.$id.model"),p.getProperty("device.$id.verifiedEndpoint"), p.getProperty("device.$id.wifi",""), p.getProperty("device.$id.site",""))
    }.sortedWith(compareBy<DeskDevice> { p.order("device", it.id) }.thenByDescending { it.lastUsed }.thenBy { it.label })

    private fun commandsIn(p: Properties) = ids(p, "command").map { id ->
        SavedCommand(id, p.getProperty("command.$id.name"), p.getProperty("command.$id.text"), p.getProperty("command.$id.tag",""))
    }.sortedWith(compareBy<SavedCommand> { p.order("command", it.id) }.thenBy { it.name })

    private fun Properties.order(prefix: String, id: String) = getProperty("$prefix.$id.order")?.toIntOrNull() ?: Int.MAX_VALUE

    private companion object { val lock = Any() }
}
