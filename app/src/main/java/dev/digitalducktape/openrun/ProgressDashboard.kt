package dev.digitalducktape.openrun

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digitalducktape.openrun.core.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ProgressGreen:Color @Composable get()=MaterialTheme.colorScheme.primary
private val ProgressMuted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
private fun decimal(value:Double)=String.format(Locale.US,"%.1f",value)
private fun hours(seconds:Long)="${seconds/3600}h ${seconds%3600/60}m"
private val dayFormat=DateTimeFormatter.ofPattern("MMM d",Locale.US)

/** Uses the same completed-workout totals and profile timezone as Progress. */
@Composable internal fun HomeWeeklyProgress(profile:Profile, rides:List<Ride>, onOpen:()->Unit) {
    val zone=progressZone(profile)
    var today by remember(zone) { mutableStateOf(LocalDate.now(zone)) }
    LaunchedEffect(zone) { while(true) { today=LocalDate.now(zone); kotlinx.coroutines.delay(60_000) } }
    val week=remember(rides,profile,today) { progressWeeks(rides,profile,today).last() }
    Surface(onClick=onOpen,shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("YOUR WEEK",color=ProgressGreen,fontSize=12.sp,letterSpacing=2.sp)
                Text("View progress ›",color=ProgressGreen,fontSize=13.sp)
            }
            Text("${week.start.format(dayFormat)} – ${week.start.plusDays(6).format(dayFormat)}",fontSize=22.sp,fontWeight=FontWeight.Bold)
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                WeeklyMetric("Workouts",week.rides.size.toString(),Modifier.weight(1f))
                WeeklyMetric("Workout time",hours(week.seconds),Modifier.weight(1f))
            }
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                WeeklyMetric("Distance","${decimal(week.distance/MILE_METERS)} mi",Modifier.weight(1f))
                WeeklyMetric("Climbed","${decimal(week.ascent*3.28084)} ft",Modifier.weight(1f))
            }
            if(week.rides.isEmpty()) Text("Save a workout to start your week.",color=ProgressMuted,fontSize=13.sp)
        }
    }
}
@Composable private fun WeeklyMetric(label:String,value:String,modifier:Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,color=ProgressMuted,fontSize=13.sp)
        Text(value,fontSize=24.sp,fontWeight=FontWeight.SemiBold)
    }
}

@Composable internal fun ProgressDashboard(profile:Profile, rides:List<Ride>) {
    val zone=progressZone(profile)
    var today by remember(zone) { mutableStateOf(LocalDate.now(zone)) }
    LaunchedEffect(zone) { while(true) { today=LocalDate.now(zone); kotlinx.coroutines.delay(60_000) } }
    val weeks=remember(rides,profile,today) { progressWeeks(rides,profile,today) }
    var selected by remember(profile.id) { mutableStateOf(7) }
    var metric by remember(profile.id) { mutableStateOf("Distance") }
    var band by remember(profile.id) { mutableStateOf(140) }
    val week=weeks[selected]
    val completed=remember(rides,profile.id,today) { rides.filter { it.profileId==profile.id && it.status=="complete" && Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()<=today } }
    val mile=remember(completed) { completed.mapNotNull { r -> fastestMile(r)?.let { r to it } }.minByOrNull { it.second } }
    val earlier=remember(weeks,band) { basePace(weeks.take(4).flatMap { it.rides },band) }
    val recent=remember(weeks,band) { basePace(weeks.takeLast(4).flatMap { it.rides },band) }
    ProgressPanel {
        Text("YOUR WEEK",color=ProgressGreen,fontSize=12.sp,letterSpacing=2.sp)
        Text("${week.start.format(dayFormat)} – ${week.start.plusDays(6).format(dayFormat)}${if(selected==7) " · In progress" else ""}",fontSize=25.sp,fontWeight=FontWeight.Bold)
        Text("${week.rides.size} saved workouts · Weeks start Monday · ${zone.id}",color=ProgressMuted)
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            ProgressMetric("Distance","${decimal(week.distance/MILE_METERS)} mi",Modifier.weight(1f))
            ProgressMetric("Workout time",hours(week.seconds),Modifier.weight(1f))
            ProgressMetric("Climbed","${decimal(week.ascent*3.28084)} ft",Modifier.weight(1f))
            ProgressMetric("Descended",if(week.rides.isNotEmpty() && week.descentMissing==week.rides.size) "Unknown" else "${decimal(week.descent*3.28084)} ft${if(week.descentMissing>0) " + unknown" else ""}",Modifier.weight(1f))
        }
        Text("Climbing and descent are estimated from belt distance and incline. Older workouts may lack descent.",color=ProgressMuted,fontSize=13.sp)
    }
    ProgressPanel {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Eight-week trend",fontSize=23.sp,modifier=Modifier.weight(1f))
            listOf("Distance","Time","Climbing").forEach { label -> FilterChip(colors=FilterChipDefaults.filterChipColors(selectedContainerColor=MaterialTheme.colorScheme.surfaceVariant,selectedLabelColor=Color.White),selected=metric==label,onClick={metric=label},label={Text(label)}) }
        }
        val values=weeks.map { when(metric) { "Time"->it.seconds/3600.0; "Climbing"->it.ascent*3.28084; else->it.distance/MILE_METERS } }
        val max=values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            weeks.forEachIndexed { i,w ->
                Column(Modifier.weight(1f).clickable { selected=i }.padding(6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(decimal(values[i])+when(metric) { "Time"->" h"; "Climbing"->" ft"; else->" mi" },color=if(selected==i) ProgressGreen else ProgressMuted,fontSize=13.sp)
                    Box(Modifier.fillMaxWidth().height(105.dp),contentAlignment=Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth(.65f).height((values[i]/max*100).coerceAtLeast(3.0).dp).background(if(selected==i) ProgressGreen else MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(6.dp)))
                    }
                    Text(w.start.format(dayFormat),fontSize=14.sp,color=if(selected==i) ProgressGreen else ProgressMuted)
                }
            }
        }
        Text("Tap a week to see its totals. The current week is still in progress.",fontSize=13.sp,color=ProgressMuted)
    }
    ProgressPanel {
        Text("Personal records",fontSize=23.sp)
        if(completed.isEmpty()) Text("Save your first workout to start building your progress.",color=ProgressMuted)
        else Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            val longest=completed.maxByOrNull { it.distanceMeters.takeIf(Double::isFinite) ?: 0.0 }!!
            val climbing=completed.maxByOrNull { it.ascentMeters.takeIf(Double::isFinite) ?: 0.0 }!!
            val duration=completed.maxByOrNull { it.durationSec }!!
            fun date(r:Ride)=Instant.ofEpochMilli(r.startedAt).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy",Locale.US))
            ProgressMetric("Longest workout","${decimal(longest.distanceMeters/MILE_METERS)} mi",Modifier.weight(1f),date(longest))
            ProgressMetric("Most time",hours(duration.durationSec.toLong()),Modifier.weight(1f),date(duration))
            ProgressMetric("Most climbing","${decimal(climbing.ascentMeters*3.28084)} ft",Modifier.weight(1f),date(climbing))
            ProgressMetric("Fastest continuous mile",mile?.let { val sec=kotlin.math.round(it.second).toInt(); "%d:%02d".format(sec/60,sec%60) } ?: "Not yet",Modifier.weight(1f),mile?.let { date(it.first) } ?: "Needs a full mile of continuous samples")
        }
    }
    ProgressPanel {
        Text("Base pace at a similar heart rate",fontSize=23.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { listOf(120,130,140,150,160,170).forEach { low ->
            FilterChip(colors=FilterChipDefaults.filterChipColors(selectedContainerColor=MaterialTheme.colorScheme.surfaceVariant,selectedLabelColor=Color.White),selected=band==low,onClick={band=low},label={Text("$low–${low+9}")})
        } }
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            ProgressMetric("Earlier four weeks",earlier.mph?.let { "${decimal(it)} mph" } ?: "More runs needed",Modifier.weight(1f),"${earlier.runs} qualifying runs"+ (earlier.hr?.let { " · ${decimal(it)} bpm" } ?: ""))
            ProgressMetric("Recent four weeks",recent.mph?.let { "${decimal(it)} mph" } ?: "More runs needed",Modifier.weight(1f),"${recent.runs} qualifying runs · includes this week"+(recent.hr?.let { " · ${decimal(it)} bpm" } ?: ""))
        }
        if(earlier.mph!=null && recent.mph!=null) {
            val delta=recent.mph-earlier.mph
            Text("${if(delta>=0) "+" else ""}${decimal(delta)} mph at $band–${band+9} bpm",color=ProgressGreen,fontSize=22.sp)
        }
        Text("Uses workouts named Base, at 0–1% incline, excluding warm-up and recovery. Each period needs two runs with at least three steady minutes in this HR band. Each run counts equally. This comparison does not change your workout targets.",color=ProgressMuted,fontSize=14.sp)
    }
    Text("Only completed workouts saved in OpenRun for ${profile.name} are included. Active, interrupted and discarded workouts are excluded.",color=ProgressMuted,fontSize=13.sp)
}
@Composable private fun ProgressPanel(content:@Composable ColumnScope.()->Unit) {
    Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
    }
}
@Composable private fun ProgressMetric(label:String,value:String,modifier:Modifier,detail:String?=null) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text(label,color=ProgressMuted,fontSize=14.sp)
        Text(value,fontSize=25.sp,fontWeight=FontWeight.Bold)
        detail?.let { Text(it,color=ProgressMuted,fontSize=12.sp) }
    }
}
