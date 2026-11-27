package dev.rprobedesk

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope

/** Keeps a verified workspace alive when the user views another saved device. */
internal class DeviceSessionRepository(
    private val host: HostBridge, private val prefs: DeskPreferences, private val scope: CoroutineScope
) {
    val devices = mutableStateMapOf<String, DeskController>()
    var selected by mutableStateOf(DeskController(host, prefs, scope))
        private set

    fun select(device: DeskDevice) {
        val next = devices[device.id] ?: (
            selected.takeIf { it !in devices.values } ?: DeskController(host, prefs, scope)
        ).also { devices[device.id] = it }
        if (selected !== next) {
            selected.leaveWorkspace()
            selected.selectedInWorkbench = false
            selected = next
        }
        next.selectedInWorkbench = true
        if (next.connecting?.id != device.id) next.connect(device)
        if (next.session != null && prefs.selectedId != device.id) prefs.select(device.id)
    }

    fun remove(id: String) {
        val removed = devices.remove(id) ?: return
        if (selected === removed) selected = DeskController(host, prefs, scope)
        removed.close()
    }

    fun close() {
        (devices.values + selected).distinct().forEach { it.close() }
        devices.clear()
    }
}
