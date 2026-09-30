package dev.digitalducktape.openrun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.floor

internal data class TrackProgress(val completedLaps:Long,val metersIntoLap:Double) {
    val fraction get()=(metersIntoLap/400.0).toFloat()
}
internal fun trackProgress(distanceMeters:Double):TrackProgress {
    val distance=distanceMeters.takeIf { it.isFinite() && it>=0 } ?: 0.0
    return TrackProgress(floor(distance/400.0).toLong(),distance%400.0)
}

/** Position comes exclusively from recorded distance; pauses and telemetry loss cannot advance it. */
@Composable internal fun WorkoutTrack(distanceMeters:Double,paused:Boolean,title:String) {
    val progress=trackProgress(distanceMeters)
    val palette=MaterialTheme.colorScheme
    val green=palette.primary
    val muted=palette.onSurfaceVariant
    Row(Modifier.fillMaxWidth().background(palette.background,RoundedCornerShape(18.dp)).padding(20.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(28.dp)) {
        Box(Modifier.weight(1f).height(240.dp),contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize().semantics {
                contentDescription="400 meter track. ${progress.completedLaps} laps completed. ${progress.metersIntoLap.toInt()} meters into current lap."
            }) {
                val inset=26.dp.toPx()
                val top=inset;val bottom=size.height-inset
                val radius=(bottom-top)/2
                val left=inset+radius;val right=size.width-inset-radius
                val center=size.width/2
                // Start at the middle of the bottom straight, travelling counterclockwise.
                val path=Path().apply {
                    moveTo(center,bottom);lineTo(right,bottom)
                    arcTo(Rect(right-radius,top,right+radius,bottom),90f,-180f,false)
                    lineTo(left,top)
                    arcTo(Rect(left-radius,top,left+radius,bottom),-90f,-180f,false)
                    lineTo(center,bottom);close()
                }
                drawPath(path,Color(0xFF33433A),style=Stroke(30.dp.toPx()))
                drawPath(path,muted.copy(alpha=.3f),style=Stroke(1.dp.toPx()))
                val measure=PathMeasure().apply { setPath(path,false) }
                val covered=Path()
                measure.getSegment(0f,measure.length*progress.fraction,covered,true)
                drawPath(covered,green.copy(alpha=.6f),style=Stroke(10.dp.toPx()))
                drawLine(Color.White,Offset(center,bottom-16.dp.toPx()),Offset(center,bottom+16.dp.toPx()),3.dp.toPx())
                val position=measure.getPosition(measure.length*progress.fraction)
                drawCircle(palette.background,12.dp.toPx(),position)
                drawCircle(if(paused) muted else green,8.dp.toPx(),position)
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text(if(paused) "PAUSED" else title,color=green,fontSize=15.sp)
                Text("Lap ${progress.completedLaps+1}",fontSize=36.sp,fontWeight=FontWeight.Bold)
                Text("400 m per lap",color=muted,fontSize=14.sp)
            }
        }
        Column(Modifier.width(215.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("LAPS COMPLETED",color=muted,fontSize=12.sp,letterSpacing=2.sp)
            Text(progress.completedLaps.toString(),fontSize=42.sp,fontWeight=FontWeight.Bold)
            Text("${progress.metersIntoLap.toInt()} / 400 m",color=green,fontSize=23.sp)
            Text("${kotlin.math.ceil(400-progress.metersIntoLap).toInt()} m to next lap",color=muted)
            Text(String.format(Locale.US,"%.2f miles total",distanceMeters.takeIf { it.isFinite() && it>=0 }?.div(1609.344) ?: 0.0),color=muted)
        }
    }
}
