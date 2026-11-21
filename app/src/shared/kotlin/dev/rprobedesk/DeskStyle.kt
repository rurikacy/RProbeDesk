package dev.rprobedesk

import androidx.compose.foundation.*
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.*
import dev.rprobedesk.resources.*
import org.jetbrains.compose.resources.Font

internal val DeskBackground = Color(0xfff5f6f8)
internal val DeskSidebar = Color(0xfff7f7fa)
internal val DeskSurface = Color.White
internal val DeskRaised = Color(0xfff2f2f5)
internal val DeskBorder = Color(0xffdadbe2)
internal val DeskInk = Color(0xff23232a)
internal val DeskMuted = Color(0xff5e606c)
internal val DeskAccent = Color(0xff7955bc)
internal val DeskSelection = Color(0xffefeaf8)
internal val DeskPaleGreen = Color(0xffdef3e6)
internal val DeskPaleRed = Color(0xffffe2e5)
internal val DeskPaleBlue = Color(0xffdfedff)
internal val DeskError = Color(0xffa0384a)
internal val DeskGreen = Color(0xff26734e)
internal val DeskShape = RoundedCornerShape(4.dp)
internal val CompactDesk = staticCompositionLocalOf { false }
private val Mono = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }
internal val DeskMono: FontFamily @Composable get() = Mono.current

@Composable
internal fun DeskTheme(content: @Composable () -> Unit) {
    val numeric = Font(Res.font.jetbrains_mono)
    val colors = lightColors(primary=DeskAccent, secondary=DeskAccent, background=DeskBackground,
        surface=DeskSurface, onPrimary=Color.White, onSecondary=Color.White, onBackground=DeskInk, onSurface=DeskInk, error=DeskError)
    val typography = Typography(defaultFontFamily=FontFamily.SansSerif,
        h6=TextStyle(fontSize=20.sp,lineHeight=26.sp,fontWeight=FontWeight.SemiBold),
        body1=TextStyle(fontSize=14.sp,lineHeight=20.sp), body2=TextStyle(fontSize=13.sp,lineHeight=18.sp),
        button=TextStyle(fontSize=13.sp,fontWeight=FontWeight.Medium))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(Mono provides remember(numeric){FontFamily(numeric)}, CompactDesk provides (maxWidth<760.dp || maxHeight<450.dp)) {
            MaterialTheme(colors=colors,typography=typography,shapes=Shapes(DeskShape,DeskShape,DeskShape),content=content)
        }
    }
}

@Composable
internal fun DeskTitle(text:String) {
    // The Latin heading's ink is centered 1.5 sp below the CJK headings on the shared baseline.
    val offset=if(text=="Shell") Modifier.offset { IntOffset(0,(-1.5).sp.roundToPx()) } else Modifier
    Text(text,offset,style=MaterialTheme.typography.h6,color=DeskInk)
}

@Composable
internal fun DeskButton(label:String,enabled:Boolean=true,modifier:Modifier=Modifier,tint:Color=DeskSurface,click:()->Unit) =
    ActionButton(label,enabled,false,modifier,tint,click)

@Composable
internal fun PrimaryButton(label:String,enabled:Boolean=true,click:()->Unit) =
    ActionButton(label,enabled,true,Modifier,DeskAccent,click)

@Composable
private fun ActionButton(label:String,enabled:Boolean,primary:Boolean,modifier:Modifier,tint:Color,click:()->Unit) {
    val interactions=remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val bg=when { !enabled->DeskRaised; primary->DeskAccent; hovered->androidx.compose.ui.graphics.lerp(tint,DeskInk,.06f); else->tint }
    val stroke=when { focused && primary->DeskInk; focused || primary && enabled->DeskAccent; else->DeskBorder }
    Box(modifier.heightIn(min=if(CompactDesk.current) 44.dp else 32.dp).clip(DeskShape)
        .background(bg).border(if(focused) 2.dp else 1.dp,stroke,DeskShape)
        .clickable(enabled=enabled,role=Role.Button,interactionSource=interactions,indication=null,onClick=click)
        .padding(horizontal=11.dp,vertical=4.dp),contentAlignment=Alignment.Center) {
        Text(label,fontSize=13.sp,lineHeight=17.sp,color=when { !enabled->DeskMuted.copy(alpha=.65f);primary->Color.White;else->DeskInk })
    }
}

@Composable
internal fun DeskInput(value:String,label:String,change:(String)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
                       keyboard:KeyboardOptions=KeyboardOptions.Default,visual:VisualTransformation=VisualTransformation.None,
                       single:Boolean=true,maxLines:Int=if(single) 1 else 6,invalid:Boolean=false) {
    val source=remember { MutableInteractionSource() }
    val focusManager=LocalFocusManager.current
    val focused by source.collectIsFocusedAsState()
    val border=when { invalid->DeskError; focused->DeskAccent; else->DeskBorder }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,fontSize=13.sp,color=DeskMuted)
        BasicTextField(value,change,Modifier.fillMaxWidth().semantics { contentDescription=label }
            .onPreviewKeyEvent { event ->
                if (single || event.key != Key.Tab || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) false
                else {
                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                    true
                }
            },
            enabled=enabled,singleLine=single,maxLines=maxLines,interactionSource=source,
            keyboardOptions=keyboard,visualTransformation=visual,cursorBrush=SolidColor(DeskAccent),
            textStyle=MaterialTheme.typography.body1.copy(color=DeskInk),
            decorationBox={ inner -> Box(Modifier.fillMaxWidth().heightIn(min=if(single) 36.dp else 64.dp)
                .background(if(enabled) DeskSurface else DeskRaised,DeskShape)
                .border(if(focused) 2.dp else 1.dp,border,DeskShape).padding(horizontal=9.dp,vertical=7.dp),
                contentAlignment=if(single) Alignment.CenterStart else Alignment.TopStart) { inner() } })
    }
}

internal fun Modifier.deskPanel() = clip(DeskShape).background(DeskSurface).border(1.dp,DeskBorder,DeskShape)

@Composable
internal fun Modifier.initialKeyboardFocus(): Modifier {
    val requester=remember { FocusRequester() }
    val inputMode=LocalInputModeManager.current
    LaunchedEffect(Unit) { if (inputMode.inputMode == InputMode.Keyboard) requester.requestFocus() }
    return focusRequester(requester)
}
