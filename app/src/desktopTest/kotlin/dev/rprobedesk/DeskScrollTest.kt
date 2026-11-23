@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.rprobedesk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeskScrollTest {
    @Test fun draggingAcrossExpandedAndCollapsedRowsNeverReversesOrMovesWhileHeld() = runDesktopComposeUiTest {
        lateinit var state:LazyListState
        val expanded=mutableStateOf(false)
        setContent {
            state=rememberLazyListState()
            DeskLazyColumn(Modifier.size(320.dp,240.dp),state=state) {
                item { Spacer(Modifier.height(120.dp)) }
                if(expanded.value) item { Spacer(Modifier.height(520.dp)) }
                items(32) { i -> Spacer(Modifier.height(if(i%5==0) 280.dp else 26.dp)) }
            }
        }
        val bar=onNodeWithContentDescription("滚动条")
        for(open in listOf(false,true,false)) {
            runOnIdle { expanded.value=open; state.requestScrollToItem(0) }
            bar.performMouseInput { moveTo(Offset(center.x,2f)); press() }
            var last=0 to 0
            for(step in 1..24) {
                bar.performMouseInput { moveTo(Offset(center.x,(height-1)*step/24f)) }
                waitForIdle()
                fun position()=state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
                val current=position()
                assertTrue(current.first>last.first || current.first==last.first && current.second>=last.second,
                    "Drag reversed from $last to $current at step $step")
                mainClock.advanceTimeBy(80)
                assertEquals(current,position())
                last=current
            }
            bar.performMouseInput { release() }
            assertTrue(!state.canScrollForward)
        }
    }

    @Test fun wheelBeyondTopStretchesAndSettlesWithoutMovingTheList() = runDesktopComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                DeskLazyColumn(Modifier.size(300.dp,220.dp).testTag("list")) {
                    items(20) { i -> Spacer(Modifier.fillMaxWidth().height(64.dp).testTag("row$i")) }
                }
            }
        }
        fun boundary()=onNodeWithTag("row2").fetchSemanticsNode().boundsInRoot.top
        val initial=boundary()
        mainClock.autoAdvance=false
        onNodeWithTag("list").performMouseInput { moveTo(center); scroll(-3f) }
        mainClock.advanceTimeBy(80)
        assertTrue(boundary()>initial,"Expected an outward wheel tick at the top to stretch the rows")
        mainClock.advanceTimeBy(1200)
        assertEquals(initial,boundary(),.1f)
        mainClock.autoAdvance=true
    }
}
