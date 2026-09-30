package dev.digitalducktape.openrun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digitalducktape.openrun.core.data.Ride
import dev.digitalducktape.openrun.core.data.RideSample
import java.util.Locale

internal data class HistorySummary(val averageHr: Double?, val maxHr: Int?, val averageMph: Double?, val maxMph: Double?, val maxIncline: Double?, val targetReadings: Int, val inTargetReadings: Int)
internal fun historySummary(ride: Ride): HistorySummary {
    val hr=ride.samples.mapNotNull { it.heartRate?.takeIf { n -> n>0 } }
    val speed=ride.samples.mapNotNull { it.speedMps?.takeIf { n -> n.isFinite() && n>=0 }?.div(0.44704) }
    val incline=ride.samples.mapNotNull { it.incline?.takeIf(Double::isFinite) }
    val targeted=ride.samples.mapNotNull { sample ->
        if(sample.warmup) return@mapNotNull null
        val step=sample.workoutStepIndex?.let { ride.plannedWorkout?.steps?.getOrNull(it) } ?: return@mapNotNull null
        val value=sample.heartRate?.takeIf { it>0 } ?: return@mapNotNull null
        val low=step.hrLow ?: return@mapNotNull null
        val high=step.hrHigh ?: return@mapNotNull null
        value in low..high
    }
    return HistorySummary(hr.takeIf { it.isNotEmpty() }?.average(),hr.maxOrNull(),speed.takeIf { it.isNotEmpty() }?.average(),speed.maxOrNull(),incline.maxOrNull(),targeted.size,targeted.count { it })
}
private fun number(value:Double?,digits:Int=1)=value?.let { String.format(Locale.US,"%.${digits}f",it) } ?: "—"
private fun time(seconds:Int)="${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"
private val ChartMuted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant

@Composable internal fun WorkoutHistoryDetails(ride:Ride) {
    val summary=remember(ride) { historySummary(ride) }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(32.dp)) {
        Text("Avg speed  ${number(summary.averageMph)} mph")
        Text("Peak speed  ${number(summary.maxMph)} mph")
        Text("Peak HR  ${summary.maxHr ?: "—"} bpm")
        Text("Peak incline  ${number(summary.maxIncline)}%")
    }
    Text("Tap a chart to inspect a reading. Gaps indicate missing sensor data. Time excludes pauses.",color=ChartMuted,fontSize=14.sp)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        HistoryChart(ride,"Heart rate","bpm",Color(0xFFFF9C91),Modifier.weight(1f),true) { it.heartRate?.takeIf { hr -> hr>0 }?.toDouble() }
        HistoryChart(ride,"Speed","mph",Color(0xFFB7EF79),Modifier.weight(1f)) { it.speedMps?.takeIf { speed -> speed>=0 }?.div(0.44704) }
        HistoryChart(ride,"Incline","%",Color(0xFF8EC9FF),Modifier.weight(1f)) { it.incline }
    }
    if(ride.samples.isEmpty()) Text("This session has no recorded sensor samples.",color=ChartMuted)
    if(summary.targetReadings>0) Text("${number(100.0*summary.inTargetReadings/summary.targetReadings,0)}% of recorded guided HR readings were within their prescribed target. Optional warm-up and missing HR excluded.",color=ChartMuted)
    ride.plannedWorkout?.let { plan ->
        Text("Workout guide · ${if(ride.guideCompleted) "Completed" else "Not completed"}",fontSize=20.sp)
        if(ride.samples.any { it.warmup }) Text("Optional warm-up is included in the charts, before the guided intervals.",color=ChartMuted)
        plan.steps.forEachIndexed { index,step ->
            val readings=ride.samples.filter { !it.warmup && it.workoutStepIndex==index }
            val hrs=readings.mapNotNull { it.heartRate?.takeIf { hr -> hr>0 } }
            val target=if(step.hrLow!=null && step.hrHigh!=null) "${step.hrLow}–${step.hrHigh} bpm" else if(step.paceLowMph!=null && step.paceHighMph!=null) "${number(step.paceLowMph)}–${number(step.paceHighMph)} mph" else "No target"
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("${index+1}. ${step.name}",Modifier.weight(1f))
                Text("${time(step.seconds)} planned",Modifier.weight(1f),color=ChartMuted)
                Text(target,Modifier.weight(1f),color=ChartMuted)
                Text(if(readings.isEmpty()) "No recorded data" else "Avg HR ${number(hrs.takeIf { it.isNotEmpty() }?.average(),0)} bpm",Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun HistoryChart(ride:Ride,title:String,unit:String,color:Color,modifier:Modifier,hrTarget:Boolean=false,value:(RideSample)->Double?) {
    val samples=remember(ride) { ride.samples.sortedBy { it.elapsedSec } }
    val values=samples.map { value(it)?.takeIf(Double::isFinite) }
    val targets=samples.map { s -> if(hrTarget && !s.warmup) s.workoutStepIndex?.let { ride.plannedWorkout?.steps?.getOrNull(it) } else null }
    val extent=values.filterNotNull()+targets.flatMap { listOfNotNull(it?.hrLow?.toDouble(),it?.hrHigh?.toDouble()) }
    val low=if(hrTarget) ((extent.minOrNull() ?: 60.0)-10).coerceAtLeast(0.0) else minOf(0.0,extent.minOrNull() ?: 0.0)
    val high=maxOf(low+1.0,(extent.maxOrNull() ?: 1.0)+(if(hrTarget) 10.0 else 0.5))
    val duration=maxOf(ride.durationSec,samples.lastOrNull()?.elapsedSec ?: 0,1)
    var selected by remember(ride.id) { mutableStateOf<Int?>(null) }
    val gridColor=ChartMuted.copy(alpha=.16f)
    Column(modifier.background(MaterialTheme.colorScheme.background,RoundedCornerShape(12.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,fontSize=20.sp,color=color)
        val index=selected?.takeIf { it in samples.indices }
        Text(if(index!=null) "${time(samples[index].elapsedSec)} · ${number(values[index],if(hrTarget) 0 else 1)} $unit" else "$unit · tap to inspect",color=ChartMuted,fontSize=14.sp)
        Text(number(high,if(hrTarget) 0 else 1),color=ChartMuted,fontSize=12.sp)
        Canvas(Modifier.fillMaxWidth().height(150.dp).pointerInput(samples) {
            detectTapGestures { point -> selected=samples.indices.minByOrNull { kotlin.math.abs(samples[it].elapsedSec-point.x/size.width*duration) } }
        }) {
            fun x(sec:Int)=sec.toFloat()/duration*size.width
            fun y(v:Double)=size.height-((v-low)/(high-low)*size.height).toFloat()
            repeat(4) { n -> val yy=size.height*n/3; drawLine(gridColor,Offset(0f,yy),Offset(size.width,yy)) }
            samples.forEachIndexed { i,s ->
                val target=targets[i]
                val end=samples.getOrNull(i+1)
                if(target?.hrLow!=null && target.hrHigh!=null && end!=null && end.elapsedSec-s.elapsedSec in 1..5) {
                    drawRect(color.copy(alpha=.12f),Offset(x(s.elapsedSec),y(target.hrHigh.toDouble())),Size(x(end.elapsedSec)-x(s.elapsedSec),y(target.hrLow.toDouble())-y(target.hrHigh.toDouble())))
                }
            }
            val path=Path()
            var previous:Int?=null
            samples.forEachIndexed { i,s ->
                val v=values[i]
                if(v==null) { previous=null } else {
                    val xx=x(s.elapsedSec);val yy=y(v)
                    if(previous==null || s.elapsedSec-samples[previous!!].elapsedSec>5) path.moveTo(xx,yy) else path.lineTo(xx,yy)
                    drawCircle(color,1.5f,Offset(xx,yy))
                    previous=i
                }
            }
            drawPath(path,color,style=Stroke(width=2f))
            index?.let { i -> drawLine(Color.White.copy(alpha=.7f),Offset(x(samples[i].elapsedSec),0f),Offset(x(samples[i].elapsedSec),size.height)) }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("${number(low,if(hrTarget) 0 else 1)} · 0:00",fontSize=12.sp,color=ChartMuted);Text(time(duration),fontSize=12.sp,color=ChartMuted) }
        if(values.all { it==null }) Text("No recorded $title data",color=ChartMuted,fontSize=13.sp)
        if(hrTarget && targets.any { it?.hrLow!=null }) Text("Shaded band: prescribed HR target",color=ChartMuted,fontSize=12.sp)
    }
}
