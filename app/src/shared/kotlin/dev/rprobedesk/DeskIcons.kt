package dev.rprobedesk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.rprobedesk.resources.Res
import dev.rprobedesk.resources.rprobe_brand
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun DeskIcon(name:String, tint:Color, modifier:Modifier=Modifier.size(18.dp)) {
    Canvas(modifier) {
        val u=size.minDimension/24f
        val stroke=Stroke(1.65f*u)
        fun line(x:Int,y:Int,xx:Int,yy:Int) = drawLine(tint,Offset(x*u,y*u),Offset(xx*u,yy*u),stroke.width,StrokeCap.Round)
        fun box(x:Int,y:Int,w:Int,h:Int,r:Int=2) =
            drawRoundRect(tint,Offset(x*u,y*u),Size(w*u,h*u),CornerRadius(r*u),style=stroke)
        fun dot(x:Int,y:Int,r:Float) = drawCircle(tint,r*u,Offset(x*u,y*u))
        when(name) {
            "设备"->{ box(6,2,12,20); line(10,5,14,5); dot(12,19,.95f) }
            "面板"->{ box(2,4,20,16);line(4,13,8,13);line(8,13,10,8);line(10,8,13,16);line(13,16,16,11);line(16,11,20,11) }
            "应用"->{ for(x in listOf(3,13)) for(y in listOf(3,13)) box(x,y,7,7,1) }
            "文件"->{ val p=Path().apply { moveTo(2*u,6*u);lineTo(9*u,6*u);lineTo(12*u,9*u);lineTo(22*u,9*u);lineTo(22*u,19*u);lineTo(2*u,19*u);close() };drawPath(p,tint,style=stroke) }
            "Shell"->{ line(3,6,9,12);line(9,12,3,18);line(12,18,21,18) }
            "镜像"->{ box(2,3,20,14);line(12,17,12,21);line(7,21,17,21) }
            "日志"->{ box(4,2,16,20);for(y in listOf(7,11,15)) line(8,y,16,y) }
            "搜索"->{ drawCircle(tint,6*u,Offset(10*u,10*u),style=stroke);line(15,15,21,21) }
            "收藏"->{ val p=Path().apply { moveTo(5*u,3*u);lineTo(19*u,3*u);lineTo(19*u,21*u);lineTo(12*u,17*u);lineTo(5*u,21*u);close() }; drawPath(p,tint,style=stroke) }
            "在线"->{ drawCircle(tint,8*u,Offset(12*u,12*u),style=stroke);dot(12,12,3f) }
            "离线"->{ drawCircle(tint,8*u,Offset(12*u,12*u),style=stroke);line(8,12,16,12) }
            else->{ box(3,3,18,18) }
        }
    }
}

@Composable
internal fun DeskBrand() {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        Image(painterResource(Res.drawable.rprobe_brand),contentDescription=null,modifier=Modifier.size(28.dp))
        Row { Text("RProbe",fontSize=16.sp,fontWeight=FontWeight.Bold,color=DeskInk); Text("Desk",fontSize=16.sp,color=DeskAccent) }
    }
}
