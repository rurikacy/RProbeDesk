package dev.rprobedesk

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

internal val DeskReorderHandleWidth=28.dp

@Composable
internal fun <T> rememberDeskReorder(
    items:List<T>, desktop:Boolean, key:(T)->String, groupOf:(T)->String={ "" }, onDrop:(List<String>)->Unit,
):DeskReorder<T> {
    val list=rememberLazyListState()
    val source=rememberUpdatedState(items)
    val commit=rememberUpdatedState(onDrop)
    val haptics=LocalHapticFeedback.current
    val groups=items.groupBy(groupOf,key)
    val reorder=remember(list,desktop,haptics,groups) {
        DeskReorder(list,desktop,source,key,groups.values,commit) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    val edge=with(LocalDensity.current) { 48.dp.toPx() }
    val nearEdge by remember(reorder,edge) { derivedStateOf { reorder.edgeSpeed(edge)!=0f } }
    LaunchedEffect(reorder,nearEdge) {
        if(nearEdge) {
            var last=withFrameNanos { it }
            while(true) {
                val now=withFrameNanos { it }
                val seconds=((now-last)/1_000_000_000f).coerceAtMost(.05f)
                last=now
                val step=reorder.edgeSpeed(edge)*seconds
                if(step!=0f && list.scrollBy(step)!=0f) reorder.updateTarget()
            }
        }
    }
    return reorder
}

internal class DeskReorder<T>(
    val list:LazyListState, val desktop:Boolean, private val source:State<List<T>>,
    private val key:(T)->String, groups:Collection<List<String>>, private val commit:State<(List<String>)->Unit>, private val longPress:()->Unit,
) {
    private val groupById=buildMap { for(group in groups) for(id in group) put(id,group) }
    val sortable=groups.any { it.size>1 }
    fun canSort(id:String)=(groupById[id]?.size ?: 0)>1
    var active by mutableStateOf<String?>(null)
        private set
    private var preview by mutableStateOf<List<String>?>(null)
    val items:List<T> get() {
        val order=preview ?: return source.value
        val group=groupById[active] ?: return source.value
        val byId=source.value.associateBy(key)
        if(!byId.keys.containsAll(group)) return source.value
        val replacement=order.iterator()
        return source.value.map { if(groupById[key(it)]===group) byId.getValue(replacement.next()) else it }
    }
    private var finger by mutableFloatStateOf(0f)
    private var grabOffset=0f
    private var groupStartIndex=0
    private var excludedDown=-1L
    // A selectable command retains its own long press. Observing the contact consumes nothing.
    val excludeFromDrag:Modifier get()=Modifier.pointerInput(this) {
        awaitEachGesture { excludedDown=awaitFirstDown(false,PointerEventPass.Initial).uptimeMillis }
    }

    val gestures:Modifier get()=Modifier.pointerInput(this) {
        try {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                if(down.isConsumed || down.uptimeMillis==excludedDown || !sortable) return@awaitEachGesture
                if(desktop && (down.position.x>DeskReorderHandleWidth.toPx() ||
                        (down.type==PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed))) return@awaitEachGesture
                val info=list.layoutInfo
                val y=down.position.y+info.viewportStartOffset
                val row=info.visibleItemsInfo.firstOrNull { y>=it.offset && y<it.offset+it.size } ?: return@awaitEachGesture
                val id=row.key as? String ?: return@awaitEachGesture
                val group=groupById[id] ?: return@awaitEachGesture
                if(group.size<2) return@awaitEachGesture
                val start=if(desktop) down else awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                if(!desktop) longPress()
                groupStartIndex=row.index-group.indexOf(id)
                preview=group
                grabOffset=y-row.offset
                finger=start.position.y
                active=id
                start.consume()
                while(active!=null) {
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    val change=event.changes.firstOrNull { it.id==start.id }
                    if(change==null || change.isConsumed || event.changes.count { it.pressed }>1) {
                        finish(false)
                        break
                    }
                    finger=change.position.y
                    change.consume()
                    updateTarget()
                    if(!change.pressed) finish(true)
                }
            }
        } finally { finish(false) }
    }

    fun translation(id:String):Float {
        if(id!=active) return 0f
        val info=list.layoutInfo
        val row=info.visibleItemsInfo.firstOrNull { it.key==id } ?: return 0f
        val groupEndIndex=groupStartIndex+groupById.getValue(id).lastIndex
        val top=info.visibleItemsInfo.firstOrNull { it.index==groupStartIndex }?.offset
        val last=info.visibleItemsInfo.firstOrNull { it.index==groupEndIndex }
        var target=finger+info.viewportStartOffset-grabOffset
        if(top!=null) target=maxOf(target,top.toFloat())
        if(last!=null) target=minOf(target,(last.offset+last.size-row.size).toFloat())
        return target-row.offset
    }

    fun updateTarget() {
        val order=preview ?: return
        val from=order.indexOf(active)
        val info=list.layoutInfo
        val row=info.visibleItemsInfo.firstOrNull { it.key==active } ?: return
        val center=finger+info.viewportStartOffset-grabOffset+row.size/2f
        var to=from
        for(item in info.visibleItemsInfo) {
            val index=order.indexOf(item.key)
            if(index<0 || item.key==active) continue
            val midpoint=item.offset+item.size/2f
            if(item.index>row.index && index>from && center>=midpoint) to=maxOf(to,index)
            if(item.index<row.index && index<from && center<=midpoint) to=minOf(to,index)
        }
        if(to==from) return
        // Keep the viewport anchored when the first visible item's key changes position.
        if(row.index==list.firstVisibleItemIndex || info.visibleItemsInfo.firstOrNull { it.key==order[to] }?.index==list.firstVisibleItemIndex)
            list.requestScrollToItem(list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset)
        preview=order.toMutableList().apply { add(to,removeAt(from)) }
    }

    fun edgeSpeed(edge:Float):Float {
        val order=preview ?: return 0f
        val distanceFromEnd=list.layoutInfo.viewportSize.height-finger
        return when {
            finger<edge && active!=order.first() -> -(1-finger/edge).coerceIn(0f,1f)*edge*12
            distanceFromEnd<edge && active!=order.last() -> (1-distanceFromEnd/edge).coerceIn(0f,1f)*edge*12
            else -> 0f
        }
    }

    private fun finish(save:Boolean) {
        val order=preview
        val group=groupById[active]
        active=null
        preview=null
        if(save && order!=null && order!=group) commit.value(order)
    }

    fun canStep(id:String,delta:Int):Boolean {
        val group=groupById[id] ?: return false
        return active==null && group.indexOf(id)+delta in group.indices
    }
    fun step(id:String,delta:Int):Boolean {
        if(!canStep(id,delta)) return false
        val order=groupById.getValue(id).toMutableList()
        val from=order.indexOf(id)
        order.add(from+delta,order.removeAt(from))
        commit.value(order)
        return true
    }
}

@Composable
internal fun <T> DeskReorderRow(
    reorder:DeskReorder<T>, id:String, label:String, modifier:Modifier=Modifier, content:@Composable ()->Unit,
) {
    if(!reorder.canSort(id)) {
        val gutter=if(reorder.desktop && reorder.sortable) Modifier.padding(start=DeskReorderHandleWidth) else Modifier
        Column(Modifier.fillMaxWidth().then(modifier).then(gutter)) { content() }
        return
    }
    val active=id==reorder.active
    var focused by remember { mutableStateOf(false) }
    val handle=Modifier.semantics {
        contentDescription="${tr("拖动排序")} $label"
        customActions=buildList {
            if(reorder.canStep(id,-1)) add(CustomAccessibilityAction(tr("上移")) { reorder.step(id,-1) })
            if(reorder.canStep(id,1)) add(CustomAccessibilityAction(tr("下移")) { reorder.step(id,1) })
        }
    }
    val outer=Modifier.fillMaxWidth().zIndex(if(active) 1f else 0f)
        .graphicsLayer { translationY=reorder.translation(id) }.then(modifier)
        .then(if(active) Modifier.border(1.dp,DeskAccent,DeskShape) else Modifier)
    Row(if(reorder.desktop) outer else outer.then(handle),verticalAlignment=Alignment.CenterVertically) {
        if(reorder.desktop) {
            Box(Modifier.width(DeskReorderHandleWidth).height(32.dp).then(handle)
                .pointerHoverIcon(PointerIcon.Hand).onFocusChanged { focused=it.isFocused }
                .onKeyEvent {
                    val step=when(it.key) { Key.DirectionUp -> -1; Key.DirectionDown -> 1; else -> return@onKeyEvent false }
                    if(it.type==KeyEventType.KeyDown) reorder.step(id,step)
                    true
                }.focusable().then(if(focused) Modifier.border(1.dp,DeskAccent,DeskShape) else Modifier),
                contentAlignment=Alignment.Center) {
                DeskIcon("拖动排序",if(active || focused) DeskAccent else DeskMuted,Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f)) { content() }
    }
}
