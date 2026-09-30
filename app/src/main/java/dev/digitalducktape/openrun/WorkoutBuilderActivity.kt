package dev.digitalducktape.openrun

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkoutBuilderActivity:ComponentActivity() {
    private val app get()=application as OpenRunApplication
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val owner=app.store.state.value.selectedId ?: run { finish();return }
        val id=intent.getStringExtra("workout")
        val plan=id?.let { key -> app.store.state.value.workouts.firstOrNull { it.id==key && it.profileId==owner && it.custom } }
        if(id!=null && plan==null) { finish();return }
        val initial=plan?.let { WorkoutDraft.from(it,intent.getBooleanExtra("copy",false)) } ?: WorkoutDraft(owner=owner)
        val initialJson=Json.encodeToString(initial)
        setContent {
            MaterialTheme(colorScheme=openRunColors()) {
                var encoded by rememberSaveable { mutableStateOf(initialJson) }
                val draft=remember(encoded) { Json.decodeFromString<WorkoutDraft>(encoded) }
                var selected by rememberSaveable { mutableIntStateOf(0) }
                var error by remember { mutableStateOf<String?>(null) }
                var leaving by remember { mutableStateOf(false) }
                val active by app.active.collectAsState();val busy by app.controlBusy.collectAsState();val maintenance by app.maintenance.collectAsState()
                val enabled=active==null && !busy && !maintenance
                fun change(next:WorkoutDraft) { encoded=Json.encodeToString(next);error=null }
                fun interval(next:IntervalDraft) { change(draft.copy(intervals=draft.intervals.mapIndexed { i,s -> if(i==selected) next else s })) }
                fun back() { if(encoded!=initialJson) leaving=true else finish() }
                BackHandler { back() }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            TextButton(onClick={back()}) { Text("← Back") }
                            Text("${app.store.state.value.profiles.firstOrNull { it.id==owner }?.name}’s workout builder",fontSize=28.sp,modifier=Modifier.weight(1f))
                            Button(enabled=enabled,onClick={
                                try { app.saveCustomWorkout(draft.workout());finish() }
                                catch(e:IllegalArgumentException) { error=e.message ?: "Check your intervals." }
                                catch(e:IllegalStateException) { error=e.message ?: "Cannot save right now." }
                                catch(_:Exception) { error="Could not save. Your existing workouts are unchanged." }
                            }) { Text("Save workout") }
                        }
                        Text("Save now, start from Workouts when ready. Optional warm-up follows your profile setting.")
                        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        if(!enabled) Text("End your current workout before saving changes.")
                        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.width(350.dp).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                draft.intervals.forEachIndexed { i,s ->
                                    FilledTonalButton(onClick={selected=i},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(i==selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.fillMaxWidth()) { Text("${i+1}. ${s.name}");Text("${s.amount} ${if(s.distance) "mi" else "min"} · ${s.speed} mph · ${s.incline}%") }
                                    }
                                }
                                OutlinedButton(enabled=draft.intervals.size<40,onClick={change(draft.copy(intervals=draft.intervals+IntervalDraft()));selected=draft.intervals.lastIndex+1}) { Text("+ Add interval") }
                            }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                OutlinedTextField(draft.name,{change(draft.copy(name=it.take(80)))},label={Text("Workout name")},singleLine=true,modifier=Modifier.fillMaxWidth())
                                NumberField("Maximum speed (mph)",draft.maxSpeed,{change(draft.copy(maxSpeed=it))})
                                HorizontalDivider()
                                val s=draft.intervals[selected.coerceIn(draft.intervals.indices)]
                                Text("Interval ${selected+1}",fontSize=24.sp)
                                OutlinedTextField(s.name,{interval(s.copy(name=it.take(80)))},label={Text("Interval name")},singleLine=true,modifier=Modifier.fillMaxWidth())
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    FilterChip(selected=!s.distance,onClick={if(s.distance) interval(s.copy(distance=false,amount="5"))},label={Text("Time")})
                                    FilterChip(selected=s.distance,onClick={if(!s.distance) interval(s.copy(distance=true,amount="0.25"))},label={Text("Distance")})
                                    NumberField(if(s.distance) "Miles" else "Minutes",s.amount,{interval(s.copy(amount=it))})
                                }
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    NumberField(if(s.useHr) "Starting speed (mph)" else "Speed (mph)",s.speed,{interval(s.copy(speed=it))})
                                    NumberField(if(s.useHr) "Starting incline (%)" else "Incline (%)",s.incline,{interval(s.copy(incline=it))})
                                }
                                Row { Switch(s.useHr,{interval(s.copy(useHr=it))});Spacer(Modifier.width(12.dp));Text("Heart-rate guidance (optional)") }
                                if(s.useHr) {
                                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                        NumberField("Low HR (bpm)",s.low,{interval(s.copy(low=it))})
                                        NumberField("High HR (bpm)",s.high,{interval(s.copy(high=it))})
                                    }
                                    Text("A chest strap is required. HR guidance may change your starting targets: incline first, then speed up to your maximum. HR loss holds the current settings.")
                                } else Text("Runs at your speed and incline targets without a chest strap.")
                                Text("Targets ramp gradually; interval time includes the ramp. Manual changes hold until the next interval. Running limits: 2–10 mph and 0–3% incline.")
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(enabled=selected>0,onClick={val l=draft.intervals.toMutableList();java.util.Collections.swap(l,selected,selected-1);change(draft.copy(intervals=l));selected--}) { Text("Move up") }
                                    OutlinedButton(enabled=selected<draft.intervals.lastIndex,onClick={val l=draft.intervals.toMutableList();java.util.Collections.swap(l,selected,selected+1);change(draft.copy(intervals=l));selected++}) { Text("Move down") }
                                    OutlinedButton(enabled=draft.intervals.size<40,onClick={val l=draft.intervals.toMutableList();l.add(selected+1,s);change(draft.copy(intervals=l));selected++}) { Text("Duplicate interval") }
                                    TextButton(enabled=draft.intervals.size>1,onClick={val l=draft.intervals.toMutableList();l.removeAt(selected);change(draft.copy(intervals=l));selected=selected.coerceAtMost(l.lastIndex)}) { Text("Remove") }
                                }
                                Spacer(Modifier.height(48.dp))
                            }
                        }
                    }
                    if(leaving) AlertDialog(onDismissRequest={leaving=false},title={Text("Discard unsaved changes?")},text={Text("Your saved workout will stay unchanged.")},confirmButton={Button(onClick={finish()}) { Text("Discard changes") }},dismissButton={TextButton(onClick={leaving=false}) { Text("Keep editing") }})
                }
            }
        }
    }
}
@Composable private fun NumberField(label:String,value:String,change:(String)->Unit) {
    OutlinedTextField(value,{if(it.length<=10) change(it)},label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.width(240.dp))
}
