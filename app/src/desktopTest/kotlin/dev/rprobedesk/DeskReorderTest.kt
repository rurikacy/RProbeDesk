@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.rprobedesk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeskReorderTest {
    private class Rows {
        var names by mutableStateOf((1..20).map(Int::toString))
        var drops=0
        var lastDrop=emptyList<String>()
        var clicks=0
        lateinit var reorder:DeskReorder<String>

        @Composable fun Show(desktop:Boolean,groupOf:(String)->String={ "" }) {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                reorder=rememberDeskReorder(names,desktop,{ it },groupOf) { order ->
                    val replacements=order.iterator()
                    names=names.map { if(it in order) replacements.next() else it }
                    lastDrop=order
                    drops++
                }
                LazyColumn(Modifier.size(300.dp,240.dp).testTag("list").then(reorder.gestures),state=reorder.list) {
                    item { Spacer(Modifier.height(30.dp)) }
                    reorder.items.groupBy(groupOf).forEach { (group,ids) ->
                        if(group.isNotEmpty()) item(key="header:$group") { Spacer(Modifier.height(20.dp)) }
                        items(ids,key={ it }) { id ->
                            DeskReorderRow(reorder,id,id) {
                                Row(Modifier.fillMaxWidth().height(40.dp)) {
                                    Text(id,Modifier.width(90.dp))
                                    Text("action",Modifier.clickable { clicks++ })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun mouseMovesOnlyFromTheGripAndSavesOnce() = runDesktopComposeUiTest {
        val rows=Rows()
        setContent { rows.Show(true) }
        val list=onNodeWithTag("list")
        list.performMouseInput { moveTo(Offset(60f,50f)); press(); moveBy(Offset(0f,88f)); release() }
        assertEquals(0,rows.drops)
        list.performMouseInput { moveTo(Offset(14f,50f)); press(); moveBy(Offset(0f,88f)); release() }
        assertEquals(listOf("2","3","1","4"),rows.names.take(4))
        assertEquals(1,rows.drops)
    }

    @Test fun touchHoldsToMoveWhileTapsAndScrollKeepWorking() = runDesktopComposeUiTest {
        val rows=Rows()
        setContent { rows.Show(false) }
        val list=onNodeWithTag("list")
        list.performTouchInput { click(Offset(105f,50f)) }
        assertEquals(1,rows.clicks)
        list.performTouchInput {
            down(Offset(40f,50f))
            advanceEventTime(700)
            moveBy(Offset(0f,88f))
            up()
        }
        assertEquals(listOf("2","3","1","4"),rows.names.take(4))
        assertEquals(1,rows.drops)
        list.performTouchInput { swipe(Offset(40f,200f),Offset(40f,50f),durationMillis=180) }
        waitForIdle()
        assertEquals(1,rows.drops)
        assertTrue(rows.reorder.list.firstVisibleItemIndex>0)
    }

    @Test fun holdingAtTheEdgeMovesBeyondTheInitialViewportBeforeDrop() = runDesktopComposeUiTest {
        val rows=Rows()
        setContent { rows.Show(true) }
        val list=onNodeWithTag("list")
        val lastVisible=rows.reorder.list.layoutInfo.visibleItemsInfo.last().key
        val lastIndex=rows.names.indexOf(lastVisible)
        mainClock.autoAdvance=false
        list.performMouseInput { moveTo(Offset(14f,50f)); press(); moveTo(Offset(14f,232f)) }
        mainClock.advanceTimeBy(900)
        val preview=rows.reorder.items
        assertTrue(preview.indexOf("1")>lastIndex,"Expected the held row to cross the initial viewport: $preview")
        assertEquals(0,rows.drops)
        list.performMouseInput { release() }
        mainClock.autoAdvance=true
        assertEquals(preview,rows.names)
        assertEquals(1,rows.drops)
    }

    @Test fun secondFingerCancelsTheDragAndDiscardsThePreview() = runDesktopComposeUiTest {
        val rows=Rows()
        val initial=rows.names
        setContent { rows.Show(false) }
        val list=onNodeWithTag("list")
        list.performTouchInput { down(Offset(40f,50f)); advanceEventTime(700); moveBy(Offset(0f,88f)) }
        assertTrue(rows.reorder.items!=initial)
        list.performTouchInput {
            down(1,Offset(60f,140f))
            up(1)
            up(0)
        }
        assertEquals(initial,rows.reorder.items)
        assertEquals(0,rows.drops)
    }

    @Test fun dragStopsAtGroupEdgeAndCommitsOnlyThatGroup() = runDesktopComposeUiTest {
        val rows=Rows().apply { names=listOf("a1","a2","b1","b2","c1") }
        setContent { rows.Show(true) { it.take(1) } }
        val list=onNodeWithTag("list")
        mainClock.autoAdvance=false
        list.performMouseInput { moveTo(Offset(14f,70f)); press(); moveTo(Offset(14f,236f)) }
        mainClock.advanceTimeBy(800)
        assertEquals(listOf("a2","a1","b1","b2","c1"),rows.reorder.items)
        assertEquals(0,rows.reorder.list.firstVisibleItemIndex)
        val row=rows.reorder.list.layoutInfo.visibleItemsInfo.first { it.key=="a1" }
        assertTrue(row.offset+row.size+rows.reorder.translation("a1")<=130f)
        list.performMouseInput { release() }
        mainClock.autoAdvance=true
        assertEquals(listOf("a2","a1"),rows.lastDrop)
        assertEquals(false,rows.reorder.canSort("c1"))
    }

    @Test fun keyboardAndAccessibilityStopAtTheSameGroupEdge() = runDesktopComposeUiTest {
        val rows=Rows().apply { names=listOf("a1","a2","b1","b2","c1") }
        setContent { rows.Show(true) { it.take(1) } }
        val first=onNodeWithContentDescription("${tr("拖动排序")} b1")
        val actions=first.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(tr("下移")),actions.map { it.label })
        first.performSemanticsAction(SemanticsActions.RequestFocus)
        first.performKeyInput { pressKey(Key.DirectionUp) }
        assertEquals(0,rows.drops)
        first.performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(listOf("b2","b1"),rows.lastDrop)
        assertEquals(listOf("a1","a2","b2","b1","c1"),rows.names)
    }
}
