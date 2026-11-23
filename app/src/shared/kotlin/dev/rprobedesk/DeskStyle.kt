package dev.rprobedesk

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.*
import dev.rprobedesk.resources.*
import org.jetbrains.compose.resources.Font

@Immutable
internal class DeskPalette(
    val background:Color, val sidebar:Color, val surface:Color, val raised:Color, val border:Color,
    val ink:Color, val muted:Color, val accent:Color, val onAccent:Color, val selection:Color,
    val paleGreen:Color, val green:Color, val paleRed:Color, val error:Color,
    val paleBlue:Color, val blue:Color, val warning:Color,
)

internal val LightDeskPalette=DeskPalette(
    background=Color(0xfff5f6f8), sidebar=Color(0xfff7f7fa), surface=Color.White, raised=Color(0xfff2f2f5), border=Color(0xffdadbe2),
    ink=Color(0xff23232a), muted=Color(0xff5e606c), accent=Color(0xff7955bc), onAccent=Color.White, selection=Color(0xffefeaf8),
    paleGreen=Color(0xffdef3e6), green=Color(0xff26734e), paleRed=Color(0xffffe2e5), error=Color(0xffa0384a),
    paleBlue=Color(0xffdfedff), blue=Color(0xff285a8e), warning=Color(0xff895a10),
)
internal val DarkDeskPalette=DeskPalette(
    background=Color(0xff141414), sidebar=Color(0xff161616), surface=Color(0xff181818), raised=Color(0xff222222), border=Color(0xff39393d),
    ink=Color(0xffeeeef3), muted=Color(0xffb2b3c0), accent=Color(0xffc6a9f6), onAccent=Color(0xff261b3c), selection=Color(0xff393044),
    paleGreen=Color(0xff243c31), green=Color(0xff98d7b0), paleRed=Color(0xff482d35), error=Color(0xfff1a2ae),
    paleBlue=Color(0xff26394b), blue=Color(0xffa5caf1), warning=Color(0xffe5bc7a),
)
private val LocalDeskPalette=staticCompositionLocalOf { LightDeskPalette }
internal val DeskBackground:Color @Composable get()=LocalDeskPalette.current.background
internal val DeskSidebar:Color @Composable get()=LocalDeskPalette.current.sidebar
internal val DeskSurface:Color @Composable get()=LocalDeskPalette.current.surface
internal val DeskRaised:Color @Composable get()=LocalDeskPalette.current.raised
internal val DeskBorder:Color @Composable get()=LocalDeskPalette.current.border
internal val DeskInk:Color @Composable get()=LocalDeskPalette.current.ink
internal val DeskMuted:Color @Composable get()=LocalDeskPalette.current.muted
internal val DeskAccent:Color @Composable get()=LocalDeskPalette.current.accent
internal val DeskSelection:Color @Composable get()=LocalDeskPalette.current.selection
internal val DeskPaleGreen:Color @Composable get()=LocalDeskPalette.current.paleGreen
internal val DeskGreen:Color @Composable get()=LocalDeskPalette.current.green
internal val DeskPaleRed:Color @Composable get()=LocalDeskPalette.current.paleRed
internal val DeskError:Color @Composable get()=LocalDeskPalette.current.error
internal val DeskPaleBlue:Color @Composable get()=LocalDeskPalette.current.paleBlue
internal val DeskBlue:Color @Composable get()=LocalDeskPalette.current.blue
internal val DeskWarning:Color @Composable get()=LocalDeskPalette.current.warning
internal val DeskShape = RoundedCornerShape(4.dp)
internal val CompactDesk = staticCompositionLocalOf { false }
internal val LocalReducedMotion = compositionLocalOf { true }
private val Mono = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }
internal val DeskMono: FontFamily @Composable get() = Mono.current

@Composable
internal fun DeskTheme(reducedMotion:Boolean,dark:Boolean,content: @Composable () -> Unit) {
    val numeric = Font(Res.font.jetbrains_mono)
    val p=if(dark) DarkDeskPalette else LightDeskPalette
    val colors = Colors(primary=p.accent,primaryVariant=p.accent,secondary=p.accent,secondaryVariant=p.accent,
        background=p.background,surface=p.surface,error=p.error,onPrimary=p.onAccent,onSecondary=p.onAccent,
        onBackground=p.ink,onSurface=p.ink,onError=p.surface,isLight=!dark)
    val typography = Typography(defaultFontFamily=FontFamily.SansSerif,
        h6=TextStyle(fontSize=20.sp,lineHeight=26.sp,fontWeight=FontWeight.SemiBold),
        body1=TextStyle(fontSize=14.sp,lineHeight=20.sp), body2=TextStyle(fontSize=13.sp,lineHeight=18.sp),
        button=TextStyle(fontSize=13.sp,fontWeight=FontWeight.Medium))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDeskPalette provides p,LocalReducedMotion provides reducedMotion,Mono provides remember(numeric){FontFamily(numeric)}, CompactDesk provides (maxWidth<760.dp || maxHeight<450.dp)) {
            MaterialTheme(colors=colors,typography=typography,shapes=Shapes(DeskShape,DeskShape,DeskShape),content=content)
        }
    }
}

@Composable
internal fun Modifier.deskReveal():Modifier {
    val reduced=LocalReducedMotion.current
    val opacity=remember { Animatable(if(reduced) 1f else .84f) }
    LaunchedEffect(reduced) {
        if(reduced) opacity.snapTo(1f)
        else opacity.animateTo(1f,tween(140,easing=LinearOutSlowInEasing))
    }
    return graphicsLayer { alpha=if(reduced) 1f else opacity.value }
}

@Composable
internal fun DeskTitle(text:String) {
    // The Latin heading's ink is centered 1.5 sp below the CJK headings on the shared baseline.
    val offset=if(text=="Shell") Modifier.offset { IntOffset(0,(-1.5).sp.roundToPx()) } else Modifier
    Text(tr(text),offset,style=MaterialTheme.typography.h6,color=DeskInk)
}

@Composable
internal fun DeskButton(label:String,enabled:Boolean=true,modifier:Modifier=Modifier,tint:Color=DeskSurface,
                        selected:Boolean?=null,localize:Boolean=true,click:()->Unit) {
    val interactions=remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val interactionPressed by interactions.collectIsPressedAsState()
    var pointerDown by remember { mutableStateOf(false) }
    val pressed=enabled && (pointerDown || interactionPressed)
    val ink=when(tint) {
        DeskPaleGreen -> DeskGreen
        DeskPaleRed -> DeskError
        DeskPaleBlue -> DeskBlue
        DeskSurface -> DeskMuted
        else -> DeskAccent
    }
    val primary=tint==DeskAccent
    val base=if(selected==false && !primary) DeskSurface else tint
    val target=when {
        !enabled -> DeskRaised
        pressed -> lerp(base,DeskInk,.18f)
        hovered -> lerp(base,DeskInk,.06f)
        else -> base
    }
    val bg=if(LocalReducedMotion.current) target else {
        val immediate=pressed || !enabled
        val animated by animateColorAsState(target,animationSpec=if(immediate) snap()
            else tween(if(selected==true || hovered) 120 else 80,easing=LinearOutSlowInEasing))
        if(immediate) target else animated
    }
    val keyboardFocus=focused && LocalInputModeManager.current.inputMode==InputMode.Keyboard
    val stroke=when {
        keyboardFocus && primary -> DeskSelection
        keyboardFocus -> DeskAccent
        !enabled || tint==DeskSurface || (selected==false && !primary) -> DeskBorder
        else -> ink
    }
    val textColor=when {
        !enabled -> DeskMuted.copy(alpha=.65f)
        primary -> MaterialTheme.colors.onPrimary
        selected==false -> DeskMuted
        else -> ink
    }
    val state=if(selected==null) modifier else modifier.semantics { this.selected=selected }
    Box(state.heightIn(min=if(CompactDesk.current) 44.dp else 32.dp).clip(DeskShape)
        .background(bg).border(if(keyboardFocus) 2.dp else 1.dp,stroke,DeskShape)
        // Scroll containers may defer Clickable's press interaction. Observe contact for paint only.
        .pointerInput(enabled) {
            if(enabled) awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                if(down.type!=PointerType.Mouse || currentEvent.buttons.isPrimaryPressed) {
                    pointerDown=true
                    try { waitForUpOrCancellation(PointerEventPass.Main) } finally { pointerDown=false }
                }
            }
        }
        .clickable(enabled=enabled,role=Role.Button,interactionSource=interactions,indication=null,onClick=click)
        .padding(horizontal=11.dp,vertical=4.dp),contentAlignment=Alignment.Center) {
        Text(if(localize) tr(label) else label,Modifier.offset { IntOffset(0,(-1).sp.roundToPx()) },textAlign=androidx.compose.ui.text.style.TextAlign.Center,fontSize=13.sp,lineHeight=17.sp,color=textColor)
    }
}

@Composable
internal fun PrimaryButton(label:String,enabled:Boolean=true,click:()->Unit) = DeskButton(label,enabled,tint=DeskAccent,click=click)

@Composable
internal fun DeskInput(value:String,label:String,change:(String)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
                       keyboard:KeyboardOptions=KeyboardOptions.Default,visual:VisualTransformation=VisualTransformation.None,
                       single:Boolean=true,maxLines:Int=if(single) 1 else 6,invalid:Boolean=false) {
    val source=remember { MutableInteractionSource() }
    val focusManager=LocalFocusManager.current
    val focused by source.collectIsFocusedAsState()
    val border=when { invalid->DeskError; focused->DeskAccent; else->DeskBorder }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(tr(label),fontSize=13.sp,color=DeskMuted)
        BasicTextField(value,change,Modifier.fillMaxWidth().semantics { contentDescription=tr(label) }
            .onPreviewKeyEvent { event ->
                if (single || event.key != Key.Tab || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) false
                else {
                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                    true
                }
            },
            enabled=enabled,singleLine=single,maxLines=if(single) maxLines else Int.MAX_VALUE,interactionSource=source,
            keyboardOptions=keyboard,visualTransformation=visual,cursorBrush=SolidColor(DeskAccent),
            textStyle=MaterialTheme.typography.body1.copy(color=DeskInk),
            decorationBox={ inner -> Box(Modifier.fillMaxWidth().heightIn(min=if(single) 36.dp else 64.dp)
                .background(if(enabled) DeskSurface else DeskRaised,DeskShape)
                .border(if(focused) 2.dp else 1.dp,border,DeskShape).padding(horizontal=9.dp,vertical=7.dp),
                contentAlignment=if(single) Alignment.CenterStart else Alignment.TopStart) {
                if(single) inner() else {
                    val limit=with(LocalDensity.current) { (MaterialTheme.typography.body1.lineHeight*maxLines).toDp() }
                    DeskScrollColumn(Modifier.fillMaxWidth().heightIn(max=limit)) { inner() }
                }
            } })
    }
}

@Composable
internal fun Modifier.deskPanel() = clip(DeskShape).background(DeskSurface).border(1.dp,DeskBorder,DeskShape)

@Composable
internal fun Modifier.initialKeyboardFocus(): Modifier {
    val requester=remember { FocusRequester() }
    val inputMode=LocalInputModeManager.current
    LaunchedEffect(Unit) { if (inputMode.inputMode == InputMode.Keyboard) requester.requestFocus() }
    return focusRequester(requester)
}
