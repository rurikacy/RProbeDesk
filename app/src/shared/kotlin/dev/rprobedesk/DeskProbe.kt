package dev.rprobedesk

import java.security.MessageDigest

internal data class DeskProbe(val facts:Map<String,String>, val digest:String?) {
    val model get() = facts.getValue("型号")
    fun review(device: DeskDevice, desktop:Boolean):String? {
        val reason = when {
            device.verifiedEndpoint!=null && device.verifiedEndpoint!=device.address -> "连接地址已变更"
            desktop && device.deviceDigest!=null && device.deviceDigest!=digest -> "该地址返回的设备标识与上次不同"
            desktop && digest==null -> "当前设备未提供可供下次核对的标识"
            device.lastModel!=null && device.lastModel!=model -> "设备型号与上次不同"
            desktop && device.lastUsed>0 && device.deviceDigest==null -> "上次连接没有可核对的设备标识"
            else -> return null
        }
        return buildString {
            appendLine(reason)
            appendLine("${device.label} · ${device.address}")
            device.lastModel?.let { appendLine("上次型号  $it") }
            appendLine("当前型号  $model")
            append("Android ${facts["Android"]} · 用户 ${facts["Android 用户"]}")
        }
    }
}

internal fun probeCommand(desktop:Boolean):String {
    val fields=listOf("model" to "ro.product.model","release" to "ro.build.version.release",
        "sdk" to "ro.build.version.sdk","abi" to "ro.product.cpu.abilist",
        "brand" to "ro.product.manufacturer","soc" to "ro.soc.model","board" to "ro.board.platform",
        "patch" to "ro.build.version.security_patch")
    return buildString {
        fields.forEach { (name,property) -> appendLine("printf '$name='; getprop $property") }
        appendLine("""wm size 2>/dev/null | awk '/size:/{v=${'$'}NF} END{print "size=" v}'""")
        appendLine("""wm density 2>/dev/null | awk '/density:/{v=${'$'}NF} END{print "density=" v}'""")
        appendLine("""ip -o link show up 2>/dev/null | awk '/link\/ether/{sub(":$", "", $2); for(i=1;i<NF;i++) if(${'$'}i=="link/ether") print "mac=" $2 "  " $(i+1)}'""")
        appendLine("printf 'user='; am get-current-user 2>/dev/null || cmd activity get-current-user 2>/dev/null")
        if(desktop) {
            appendLine("printf 'serial='; getprop ro.serialno")
            appendLine("printf 'androidId='; settings --user current get secure android_id 2>/dev/null")
        }
    }
}

internal fun parseProbe(output:String):DeskProbe {
    val fields=output.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
    val model=fields["model"].orEmpty()
    val version=fields["release"].orEmpty()
    val user=fields["user"]?.toIntOrNull()
    require(model.isNotBlank() && version.isNotBlank() && user!=null && user>=0) { "无法核实目标型号、Android 版本或当前用户，请检查设备授权" }
    val info=linkedMapOf("型号" to model,"Android" to version,"Android 用户" to user.toString())
    for ((key, label) in listOf("sdk" to "SDK", "abi" to "ABI", "brand" to "制造商",
        "soc" to "SoC", "board" to "硬件平台", "patch" to "安全补丁",
        "size" to "分辨率", "density" to "显示密度 (dpi)")) {
        fields[key]?.takeIf { it.isNotBlank() }?.let { info[label] = it }
    }
    info["MAC"] = output.lineSequence().filter { it.startsWith("mac=") }
        .map { it.substringAfter('=').trim() }.filter { it.isNotEmpty() }.joinToString(" · ").ifBlank { "不可用" }
    val identifiers=listOf("serial","androidId").mapNotNull { key ->
        fields[key]?.takeIf { value -> value.matches(Regex("[A-Za-z0-9._-]{4,128}")) && value.any { it!='0' } &&
            value.lowercase() !in setOf("unknown","null","none","0123456789abcdef","9774d56d682e549c") }?.let { "$key=$it" }
    }
    val digest=identifiers.takeIf { it.isNotEmpty() }?.let {
        MessageDigest.getInstance("SHA-256").digest(it.joinToString("\n").toByteArray(Charsets.UTF_8)).joinToString("") { b -> "%02x".format(b) }
    }
    return DeskProbe(info,digest)
}
