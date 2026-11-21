package dev.rprobedesk

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64

internal object CloudSecret {
    fun seal(text:String):String = Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(text.toByteArray(Charsets.UTF_8)))
    fun open(text:String):String = String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(text)),Charsets.UTF_8)
}
