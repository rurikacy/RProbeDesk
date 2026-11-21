package dev.rprobedesk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

internal fun actionTint(action:String) = when(action) {
    "启动","解冻" -> DeskPaleGreen
    "停止" -> DeskPaleBlue
    "卸载","删除收藏","清屏","移除" -> DeskPaleRed
    else -> DeskAccent
}

@Composable
internal fun DeskDialog(onDismissRequest:()->Unit, title:@Composable ()->Unit, text:@Composable ()->Unit,
                        confirmButton:@Composable ()->Unit, dismissButton:@Composable ()->Unit) {
    AlertDialog(onDismissRequest=onDismissRequest,title=title,text=text,buttons={
        Box(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=8.dp,bottom=16.dp),
            contentAlignment=androidx.compose.ui.Alignment.CenterEnd) {
            Tools { dismissButton(); confirmButton() }
        }
    })
}

@Composable
internal fun DeskConfirm(title: String, details: String, action: String, dismiss:()->Unit, confirm:()->Unit) {
    DeskDialog(onDismissRequest=dismiss, title={ Text(uiMessage(title),style=MaterialTheme.typography.h6) },
        text={Text(if(action in listOf("移除","删除收藏")) details else uiMessage(details))}, confirmButton={DeskButton(action,tint=actionTint(action),click=confirm)}, dismissButton={
            DeskButton("取消",modifier=Modifier.initialKeyboardFocus(),click=dismiss) })
}

@Composable
internal fun DeviceForm(device: DeskDevice?, prefs: DeskPreferences, onDismiss:()->Unit, save:(String?,String,String,Int,String,String)->Unit) {
    var wifi by remember { mutableStateOf(device?.wifi.orEmpty()) }
    var site by remember { mutableStateOf(device?.site.orEmpty()) }
    var name by remember { mutableStateOf(device?.name.orEmpty()) }
    var ip by remember { mutableStateOf(device?.ip.orEmpty()) }
    var port by remember { mutableStateOf(device?.port?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var invalid by remember { mutableStateOf<String?>(null) }
    DeskDialog(onDismissRequest=onDismiss, title={ Text(tr(if(device==null) "添加设备" else "编辑设备"),style=MaterialTheme.typography.h6) },
        text={
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                DeskInput(name,"设备备注",{name=it},Modifier.initialKeyboardFocus())
                DeskInput(wifi,"WiFi 名称",{wifi=it})
                DeskInput(site,"场地",{site=it})
                DeskInput(ip,"IP 地址",{ip=it; invalid=null; error=null},invalid=invalid=="ip")
                DeskInput(port,"连接端口",{port=it; invalid=null; error=null},keyboard=KeyboardOptions(keyboardType=KeyboardType.Number),invalid=invalid=="port")
                (error ?: prefs.error)?.let { Text(uiMessage(it),color=MaterialTheme.colors.onSurface) }
            }
        }, confirmButton={ DeskButton("保存",tint=DeskAccent) {
            try {
                invalid="ip"
                val address=normalizedIp(ip)
                invalid="port"
                val number=parsePort(port)
                invalid=null
                save(device?.id,name,address,number,wifi,site)
            }
            catch(e:Exception) { error=e.localizedMessage ?: "无法保存" }
        } }, dismissButton={DeskButton("取消",click=onDismiss)})
}

@Composable
internal fun PairForm(desk: DeskController, direct:(String, Int)->Unit, dismiss:()->Unit) {
    var ip by remember { mutableStateOf(desk.chosen?.ip.orEmpty()) }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var invalid by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    DeskDialog(onDismissRequest=dismiss,title={Text(tr("无线配对"),style=MaterialTheme.typography.h6)},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(tr("配对码为空则不使用配对码，请填写连接端口。使用配对码时，请填写设备配对窗口中的端口和六位数字。"),style=MaterialTheme.typography.body2)
            DeskInput(ip,"IP 地址",{ip=it; invalid=null; error=null},Modifier.initialKeyboardFocus(),enabled=!working,invalid=invalid=="ip")
            DeskInput(port,if(code.isEmpty()) "连接端口" else "配对端口",{port=it; invalid=null; error=null},enabled=!working,
                keyboard=KeyboardOptions(keyboardType=KeyboardType.Number),invalid=invalid=="port")
            DeskInput(code,"配对码（可选）",{code=it; invalid=null; error=null},enabled=!working,visual=PasswordVisualTransformation(),
                keyboard=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),invalid=invalid=="code")
            error?.let { Text(uiMessage(it)) }
        }
    },confirmButton={ DeskButton(if(working) "配对中" else if(code.isEmpty()) "保存并连接" else "配对",!working,tint=DeskAccent){
        scope.launch {
            try {
                invalid="ip"
                val address=normalizedIp(ip)
                invalid="port"
                val pairPort=parsePort(port)
                if (code.isEmpty()) {
                    direct(address, pairPort)
                    dismiss()
                    return@launch
                }
                invalid="code"
                require(code.matches(Regex("[0-9]{6}"))) { "配对码应为六位数字" }
                invalid=null
                working=true
                val submitted=code
                code=""
                desk.host.pair(address,pairPort,submitted)
                desk.message="配对成功，请使用无线调试主页的连接端口添加设备"
                dismiss()
            } catch (e: TimeoutCancellationException) { error = "配对超时，请重新打开目标设备的配对码窗口" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error=e.localizedMessage ?: "配对失败" }
            finally { working=false }
        }
    } },dismissButton={DeskButton("取消",click=dismiss)})
}
