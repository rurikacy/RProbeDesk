package dev.rprobedesk

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// The visible interval uses pixels for continuous content and fractional item indices for lazy layouts.
private data class ScrollWindow(val start:Float,val end:Float,val total:Int,val center:Float=(start+end)/2) {
    val extent get()=(end-start).coerceIn(0f,total.toFloat())
    val range get()=total-extent
    val fraction get()=if(range>0) (start/range).coerceIn(0f,1f) else 0f
}

@Composable
private fun ScrollFrame(modifier:Modifier, state:ScrollableState, reserveGutter:Boolean, window:()->ScrollWindow, seek:(Float,ScrollWindow)->Unit,
                        content:@Composable (Modifier)->Unit) {
    val enabled=state.canScrollBackward || state.canScrollForward
    val currentWindow by rememberUpdatedState(window)
    val currentSeek by rememberUpdatedState(seek)
    var heldWindow by remember { mutableStateOf<ScrollWindow?>(null) }
    var heldFraction by remember { mutableFloatStateOf(0f) }
    val held=heldWindow
    val reducedMotion=LocalReducedMotion.current
    val edges=remember { MutableSharedFlow<Float>(extraBufferCapacity=1,onBufferOverflow=BufferOverflow.DROP_OLDEST) }
    val stretch=remember { Animatable(0f) }
    LaunchedEffect(enabled,reducedMotion) {
        stretch.snapTo(0f)
        if(enabled && !reducedMotion) edges.collectLatest { direction ->
            stretch.animateTo(direction,tween(75))
            stretch.animateTo(0f,spring(dampingRatio=.78f,stiffness=360f))
        }
    }
    val boundary=remember(edges) { object:NestedScrollConnection {
        override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset {
            if(source==NestedScrollSource.UserInput && kotlin.math.abs(available.y)>.5f)
                edges.tryEmit(if(available.y>0) 1f else -1f)
            return Offset.Zero
        }
    } }
    val feedback=if(enabled && !reducedMotion) Modifier.nestedScroll(boundary).pointerInput(state) {
        // Desktop may discard a wheel tick at the limit before it reaches nested scroll.
        awaitPointerEventScope {
            while(true) {
                val event=awaitPointerEvent(PointerEventPass.Final)
                if(event.type==PointerEventType.Scroll) {
                    val delta=event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if(delta<0 && !state.canScrollBackward) edges.tryEmit(1f)
                    if(delta>0 && !state.canScrollForward) edges.tryEmit(-1f)
                }
            }
        }
    } else Modifier
    Box(modifier.clipToBounds().then(feedback)) {
        val gutter=if(enabled || reserveGutter) Modifier.padding(end=12.dp) else Modifier
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            content(gutter.graphicsLayer {
                val value=stretch.value
                transformOrigin=TransformOrigin(.5f,if(value>=0) 0f else 1f)
                scaleY=1f+kotlin.math.abs(value)*minOf(.018f,8.dp.toPx()/size.height.coerceAtLeast(1f))
            })
        }
        if(enabled) Box(Modifier.matchParentSize(),contentAlignment=Alignment.CenterEnd) {
            val ink=DeskAccent
            val track=DeskAccent.copy(alpha=.15f)
            val label=tr("滚动条")
            fun thumb(height:Float,w:ScrollWindow,min:Float)=
                (height*w.extent/w.total.coerceAtLeast(1)).coerceIn(min.coerceAtMost(height),height)
            Canvas(Modifier.fillMaxHeight().width(12.dp).semantics {
                contentDescription=label
                progressBarRangeInfo=ProgressBarRangeInfo(held?.let { heldFraction } ?: currentWindow().fraction,0f..1f)
                setProgress { currentSeek(it.coerceIn(0f,1f),currentWindow()); true }
            }.pointerInput(Unit) {
                awaitEachGesture {
                    val down=awaitFirstDown()
                    val w=currentWindow()
                    val length=thumb(size.height.toFloat(),w,24.dp.toPx())
                    val travel=size.height-length
                    if(travel<=0f) return@awaitEachGesture
                    val top=travel*w.fraction
                    val y=down.position.y
                    val grip=if(y in top..(top+length)) y-top else length/2
                    down.consume()
                    heldWindow=w
                    heldFraction=w.fraction
                    fun move(finger:Float) {
                        val position=(finger-grip)/travel
                        if(position<0f || position>1f) edges.tryEmit(if(position<0f) 1f else -1f)
                        heldFraction=position.coerceIn(0f,1f)
                        currentSeek(heldFraction,w)
                    }
                    try {
                        if(y !in top..(top+length)) move(y)
                        do {
                            val change=awaitPointerEvent().changes.firstOrNull { it.id==down.id } ?: break
                            if(change.position!=change.previousPosition) move(change.position.y)
                            change.consume()
                        } while(change.pressed)
                    } finally { heldWindow=null }
                }
            }) {
                val w=held ?: currentWindow()
                val fraction=if(held!=null) heldFraction else w.fraction
                val height=thumb(size.height,w,24.dp.toPx())
                val width=(if(held!=null) 6.dp else 4.dp).toPx()
                val x=(size.width-width)/2
                drawRoundRect(track,Offset(x,0f),Size(width,size.height),CornerRadius(width/2))
                drawRoundRect(ink,Offset(x,(size.height-height)*fraction),Size(width,height),CornerRadius(width/2))
            }
        }
    }
}

@Composable
internal fun DeskScrollColumn(modifier:Modifier=Modifier,verticalArrangement:Arrangement.Vertical=Arrangement.Top,
                              content:@Composable ColumnScope.()->Unit) {
    val state=rememberScrollState()
    val scope=rememberCoroutineScope()
    ScrollFrame(modifier,state,false,{
        ScrollWindow(state.value.toFloat(),(state.value+state.viewportSize).toFloat(),state.maxValue+state.viewportSize)
    },{ fraction,_ -> scope.launch { state.scrollTo((state.maxValue*fraction).roundToInt()) } }) { gutter ->
        Column(gutter.verticalScroll(state),verticalArrangement=verticalArrangement,content=content)
    }
}

private fun visibleWindow(count:Int, first:Int, last:Int, top:Int, bottom:Int, firstHeight:Int, lastHeight:Int,
                          viewStart:Int, viewEnd:Int):ScrollWindow {
    val before=((viewStart-top).toFloat()/firstHeight.coerceAtLeast(1)).coerceIn(0f,1f)
    val after=((bottom-viewEnd).toFloat()/lastHeight.coerceAtLeast(1)).coerceIn(0f,1f)
    return ScrollWindow(first+before,last+1-after,count)
}

@Composable
internal fun DeskLazyColumn(modifier:Modifier=Modifier,state:LazyListState=rememberLazyListState(),
                           contentPadding:PaddingValues=PaddingValues(0.dp),verticalArrangement:Arrangement.Vertical=Arrangement.Top,
                           onBarScroll:()->Unit={},content:LazyListScope.()->Unit) {
    fun window():ScrollWindow {
        val layout=state.layoutInfo
        val items=layout.visibleItemsInfo
        val first=items.firstOrNull() ?: return ScrollWindow(0f,0f,0)
        val last=items.last()
        val middle=(layout.viewportStartOffset+layout.viewportEndOffset)/2
        val row=items.lastOrNull { it.offset<=middle } ?: first
        val center=row.index+((middle-row.offset).toFloat()/(row.size+layout.mainAxisItemSpacing).coerceAtLeast(1)).coerceIn(0f,1f)
        return visibleWindow(layout.totalItemsCount,first.index,last.index,first.offset,last.offset+last.size,first.size,last.size,
            layout.viewportStartOffset,layout.viewportEndOffset).copy(center=center)
    }
    val scope=rememberCoroutineScope()
    ScrollFrame(modifier,state,true,::window,{ fraction,initial ->
        onBarScroll()
        val count=state.layoutInfo.totalItemsCount
        if(count>0) scope.launch {
            if(fraction<=0f) state.scrollToItem(0)
            else if(fraction>=1f) state.scrollToItem(count-1,Int.MAX_VALUE/2)
            else {
                // Freeze the mapping on press. The height of the row under the pointer can change by a whole panel.
                val start=initial.fraction
                val target=if(fraction<=start) initial.center*fraction/start
                    else initial.center+(initial.total-initial.center)*(fraction-start)/(1f-start)
                val index=target.toInt().coerceIn(0,count-1)
                var layout=state.layoutInfo
                if(layout.visibleItemsInfo.none { it.index==index }) {
                    state.scrollToItem(index)
                    layout=state.layoutInfo
                }
                layout.visibleItemsInfo.firstOrNull { it.index==index }?.let { row ->
                    val within=(target-index).coerceIn(0f,1f)*(row.size+layout.mainAxisItemSpacing)
                    state.scrollToItem(index,(within-layout.viewportSize.height/2f).roundToInt())
                }
            }
        }
    }) { gutter -> LazyColumn(gutter.fillMaxSize(),state=state,contentPadding=contentPadding,verticalArrangement=verticalArrangement,content=content) }
}

@Composable
internal fun DeskLazyGrid(columns:GridCells,modifier:Modifier=Modifier,state:LazyGridState=rememberLazyGridState(),
                         horizontalArrangement:Arrangement.Horizontal,verticalArrangement:Arrangement.Vertical,content:LazyGridScope.()->Unit) {
    fun window():ScrollWindow {
        val layout=state.layoutInfo
        val items=layout.visibleItemsInfo
        val first=items.firstOrNull() ?: return ScrollWindow(0f,0f,0)
        val last=items.last()
        return visibleWindow(layout.totalItemsCount,first.index,last.index,first.offset.y,last.offset.y+last.size.height,
            first.size.height,last.size.height,layout.viewportStartOffset,layout.viewportEndOffset)
    }
    ScrollFrame(modifier,state,true,::window,{ fraction,w ->
        if(w.total>0) state.requestScrollToItem(if(fraction==1f) w.total-1 else (w.range*fraction).roundToInt().coerceIn(0,w.total-1))
    }) { gutter -> LazyVerticalGrid(columns,gutter.fillMaxSize(),state=state,horizontalArrangement=horizontalArrangement,
        verticalArrangement=verticalArrangement,content=content) }
}
