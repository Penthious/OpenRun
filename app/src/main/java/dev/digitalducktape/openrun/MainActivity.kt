package dev.digitalducktape.openrun

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digitalducktape.openrun.core.data.Profile
import dev.digitalducktape.openrun.core.data.Ride
import dev.digitalducktape.openrun.core.garmin.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green=Color(0xFFB7EF79)
private val Background=Color(0xFF101713)
private val Surface=Color(0xFF1D2821)
private val Muted=Color(0xFF9EAEA1)
class MainActivity: ComponentActivity() {
    private val app get()=application as OpenRunApplication
    private val sleeping = mutableStateOf(false)
    private val resumeGeneration = mutableStateOf(0)
    private val sleepHandler = Handler(Looper.getMainLooper())
    private val sleepAfterInactivity = Runnable {
        if (app.active.value == null || app.paused.value) {
            sleeping.value = true
            setScreenSleep(true)
        }
    }
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result.values.all { it }) { app.heart.connect(app.store.state.value.profiles.firstOrNull { it.id==app.store.state.value.selectedId }?.strapAddress); app.heart.scan() }
        else app.message.value="Allow Bluetooth/location permissions to pair your chest strap."
    }
    private val importCredentials=registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null && app.active.value==null && !app.controlBusy.value) {
            app.scope.launch {
                val result=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { contentResolver.openInputStream(uri)!!.use { ConsoleCredentials.install(this@MainActivity,it) } }
                }
                app.message.value=if(result.isSuccess) "Console credentials saved. Force stop and relaunch OpenRun before exercising. Delete the transferred ZIP from Downloads."
                    else "Could not import credentials. Choose a ZIP with the three matching console PEM files."
            }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val blePermission=if(Build.VERSION.SDK_INT>=31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION
        if(checkSelfPermission(blePermission)==android.content.pm.PackageManager.PERMISSION_GRANTED) {
            app.heart.connect(app.store.state.value.profiles.firstOrNull { it.id==app.store.state.value.selectedId }?.strapAddress)
        }
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Green,onPrimary=Background,background=Background,surface=Surface,onSurface=Color.White)) { Surface(color=Background,contentColor=Color.White) {
            Box(Modifier.fillMaxSize()) {
                AppScreen(onSleep={ enterSleep() })
                if(sleeping.value) Box(Modifier.fillMaxSize().background(Color.Black).clickable {
                    exitSleep()
                }, contentAlignment=Alignment.Center) {
                    Text("Tap to wake", color=Color.DarkGray, fontSize=14.sp)
                }
            }
        } } }
        scheduleSleep()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && sleeping.value) exitSleep()
        else if (event.actionMasked == MotionEvent.ACTION_UP) scheduleSleep()
        return super.dispatchTouchEvent(event)
    }
    override fun onResume() { super.onResume(); resumeGeneration.value++; scheduleSleep() }
    override fun onPause() { sleepHandler.removeCallbacks(sleepAfterInactivity); super.onPause() }
    override fun onDestroy() { sleepHandler.removeCallbacks(sleepAfterInactivity); super.onDestroy() }
    private fun scheduleSleep() {
        sleepHandler.removeCallbacks(sleepAfterInactivity)
        if (!sleeping.value && (app.active.value == null || app.paused.value)) {
            sleepHandler.postDelayed(sleepAfterInactivity, 5 * 60 * 1000L)
        }
    }
    private fun enterSleep() { sleepHandler.removeCallbacks(sleepAfterInactivity); sleeping.value=true; setScreenSleep(true) }
    private fun exitSleep() { sleeping.value=false; setScreenSleep(false); scheduleSleep() }
    private fun setScreenSleep(sleep: Boolean) {
        window.attributes = window.attributes.apply { screenBrightness = if(sleep) 0.01f else -1f }
    }
    private fun requestBle() {
        permissions.launch(if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }
    private fun service(action:String) { val i=Intent(this,WorkoutService::class.java).setAction(action); if(action=="show" || action=="record") startForegroundService(i) else startService(i) }
    private fun openEntertainment(packageName:String,label:String) {
        if(!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${this.packageName}")))
            app.message.value="Enable ‘Display over other apps’ to keep an OpenRun shortcut over $label."
            return
        }
        val launch=packageManager.getLaunchIntentForPackage(packageName)
        if(launch==null) { app.message.value="$label is not installed."; return }
        service("show")
        startActivity(launch)
    }
    @Composable private fun Entertainment() {
        val active by app.active.collectAsState()
        Panel {
            Button(onClick={startActivity(Intent(this@MainActivity,OutdoorActivity::class.java))}) { Text("Outdoor Trails") }
            Text("WATCH & UNWIND",color=Muted,fontSize=12.sp,letterSpacing=2.sp)
            Text("Something good\nto move to.",fontSize=25.sp,fontWeight=FontWeight.Bold)
            // Some consoles host Plex under the Netflix package for launcher compatibility.
            val netflixIsPlex=packageManager.getLaunchIntentForPackage("com.netflix.mediaclient")?.component?.className?.contains("plex",ignoreCase=true)==true
            val apps=listOf(
                Triple(if(netflixIsPlex) "Plex" else "Netflix","com.netflix.mediaclient",if(netflixIsPlex) "P" else "N"),
                Triple("Plex","com.plexapp.android","P"),
                Triple("Web player","net.slions.fulguris.full.fdroid","↗"))
            apps.filter { packageManager.getLaunchIntentForPackage(it.second)!=null }.forEach { (label,pkg,mark) ->
                Surface(color=Background,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().clickable { openEntertainment(pkg,label) }) {
                    Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(mark,fontSize=30.sp,fontWeight=FontWeight.Black,color=when(label) { "Netflix"->Color(0xFFE50914); "Plex"->Color(0xFFE5A00D); else->Green })
                        Column { Text(label,fontSize=19.sp,fontWeight=FontWeight.Bold); Text(if(label=="Web player") "Plex & streaming sites" else "Open app",fontSize=12.sp,color=Muted) }
                    }
                }
            }
            Text(if(active!=null) "Your workout controls stay on screen while you watch." else "Pick your show, then start a workout from the floating menu.",color=Muted,fontSize=14.sp)
            if(active!=null) TextButton(onClick={service("hide")}) { Text("Hide floating controls") }
        }
    }
    @Composable private fun AppScreen(onSleep:()->Unit) {
        val saved by app.store.state.collectAsState()
        val active by app.active.collectAsState()
        val busy by app.controlBusy.collectAsState()
        val notice by app.message.collectAsState()
        var tab by remember { mutableStateOf("Workout") }
        LaunchedEffect(resumeGeneration.value) { if(app.hike!=null && app.active.value!=null) tab="Workout" }
        var ending by remember { mutableStateOf(false) }
        val pausedSession by app.paused.collectAsState()
        var adding by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        val profile=saved.profiles.firstOrNull { it.id==saved.selectedId }
        Row(Modifier.fillMaxSize().background(Background).padding(28.dp),horizontalArrangement=Arrangement.spacedBy(30.dp)) {
            Column(Modifier.width(230.dp).fillMaxHeight().padding(bottom=48.dp)) {
                Text("OPENRUN",color=Green,fontSize=27.sp,fontWeight=FontWeight.Black,letterSpacing=3.sp)
                Text("YOUR PACE. YOUR SPACE.",fontSize=10.sp,color=Muted,letterSpacing=1.sp)
                Spacer(Modifier.height(42.dp))
                Text("RUNNER",fontSize=11.sp,color=Muted,letterSpacing=2.sp)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    saved.profiles.forEach { p ->
                        Surface(shape=RoundedCornerShape(12.dp),color=if(p.id==profile?.id) Green else Surface,modifier=Modifier.fillMaxWidth().clickable(enabled=active==null && !busy) { app.selectProfile(p.id) }) {
                            Text(p.name,Modifier.padding(16.dp),color=if(p.id==profile?.id) Background else Color.White,fontWeight=FontWeight.Bold)
                        }
                    }
                    TextButton(onClick={adding=true},enabled=active==null && !busy) { Text("+ Add profile") }
                    if(active!=null) Text("End recording to switch runners.",fontSize=12.sp,color=Muted)
                    Spacer(Modifier.height(24.dp))
                    listOf("Workout","Outdoor Trails","Entertainment","Planned workouts","History","Connections").forEach { label ->
                        TextButton(onClick={if(label=="Outdoor Trails") startActivity(Intent(this@MainActivity,OutdoorActivity::class.java)) else tab=label},modifier=Modifier.fillMaxWidth()) { Text(label,color=if(tab==label) Green else Muted,fontSize=18.sp) }
                    }
                }
                Button(onClick={app.pauseWorkout()},modifier=Modifier.fillMaxWidth().height(60.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB64236),contentColor=Color.White)) { Text("STOP BELT",fontSize=17.sp,fontWeight=FontWeight.Bold) }
                if(active!=null) {
                    OutlinedButton(onClick={if(pausedSession) app.resumeWorkout() else app.pauseWorkout()},enabled=!pausedSession || !busy,modifier=Modifier.fillMaxWidth().height(54.dp)) { Text(if(pausedSession) "Resume workout" else "Pause workout") }
                    Button(onClick={ending=true},modifier=Modifier.fillMaxWidth().height(54.dp)) { Text("End workout") }
                }
                TextButton(onClick=onSleep,enabled=active==null || app.paused.collectAsState().value,modifier=Modifier.fillMaxWidth()) { Text("Sleep screen",color=Muted) }
                val update by app.updates.state.collectAsState()
                TextButton(onClick={startActivity(Intent(this@MainActivity,MaintenanceActivity::class.java))}) { Text(if(update.ready!=null) "Update available" else "${BuildConfig.VERSION_NAME} · Updates & backup",fontSize=12.sp,color=if(update.ready!=null) Green else Muted) }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(if(profile==null) "Make room for your next run." else when(tab) { "History"->"${profile.name}’s workouts"; "Connections"->"Connected to you."; "Entertainment"->"Make time for a good show."; else->if(active!=null) "${profile.name}’s session" else "Ready when you are, ${profile.name}." },fontSize=32.sp,fontWeight=FontWeight.Bold); Text("A little movement. A good show. Your own space.",color=Muted,fontSize=16.sp) }
                    Text("OPENRUN / 01",color=Muted,fontSize=12.sp)
                }
                if(profile==null) {
                    Panel { Text("Start with a profile",fontSize=24.sp); Text("Keep each person’s workouts, chest strap, and Garmin account together.",color=Muted); Button(onClick={adding=true}) { Text("Create your profile") } }
                } else when(tab) {
                    "Workout"->{
                        if(active==null) {
                            Row(horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.Top) {
                                Column(Modifier.weight(1f)) { key(profile.id) { HomeSchedule(profile,onConnections={tab="Connections"}) } }
                                Column(Modifier.width(310.dp)) { Entertainment() }
                            }
                        }
                        Workout(profile, onSleep)
                    }
                    "Entertainment"->Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) { Column(Modifier.width(420.dp)) { Entertainment() }; Column(Modifier.weight(1f)) { Panel { Text("Keep your run in view",fontSize=24.sp); Text("Choose your show first. The floating menu offers manual, Zone 2 and today’s Garmin workouts. Once started, your live metrics and controls follow you into the player.",color=Muted) } } }
                    "History"->History(profile)
                    "Planned workouts"->key(profile.id) { PlannedWorkouts(profile,onStarted={tab="Workout"}) }
                    else->Connections(profile)
                }
            }
        }
        if(ending && active!=null) AlertDialog(onDismissRequest={ending=false},title={Text("End this workout?")},text={Text("Stop the belt and save to ${profile?.name ?: "your profile"}’s history and Garmin if connected, or discard without saving or uploading.")},confirmButton={Button(onClick={app.endWorkout();ending=false}) { Text("Stop & save") }},dismissButton={Row {
            TextButton(onClick={app.endWorkout(save=false);ending=false}) { Text("End without saving") }
            TextButton(onClick={ending=false}) { Text("Keep going") }
        }})
        if(adding) AlertDialog(onDismissRequest={adding=false},title={Text("New runner")},text={OutlinedTextField(value=name,onValueChange={if(it.length<=40) name=it},label={Text("Name")},singleLine=true)},confirmButton={Button(onClick={app.store.addProfile(name); app.selectProfile(app.store.state.value.selectedId!!); name=""; adding=false},enabled=name.trim().isNotEmpty()){Text("Create profile")}},dismissButton={TextButton(onClick={adding=false}){Text("Cancel")}})
        notice?.let { AlertDialog(onDismissRequest={app.message.value=null},title={Text("OpenRun")},text={Text(it)},confirmButton={TextButton(onClick={app.message.value=null}){Text("OK")}}) }
    }
    @Composable private fun WarmupOption(profile:Profile,enabled:Boolean) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=profile.warmupEnabled,onCheckedChange={app.setWarmup(it)},enabled=enabled)
            Text("5-minute warm-up",color=Muted)
        }
    }
    @Composable private fun HomeSchedule(profile: Profile, onConnections: () -> Unit) {
        val saved by app.store.state.collectAsState()
        val accounts by app.garmin.state.collectAsState()
        val refreshes by app.schedule.status.collectAsState()
        val paceMessages by app.paceStatus.collectAsState()
        val busy by app.controlBusy.collectAsState()
        val account=accounts.accounts.firstOrNull { it.profileId==profile.id }
        val owner=account?.let { GarminScheduleRepository.ownerKey(it.email) }
        val cache=saved.schedules.firstOrNull { it.profileId==profile.id && it.accountKey==owner }
        val refresh=refreshes[profile.id] ?: ScheduleRefresh()
        val scope=rememberCoroutineScope()
        val zone=java.time.ZoneId.of(profile.scheduleTimeZone)
        var today by remember(zone) { mutableStateOf(java.time.LocalDate.now(zone)) }
        var editingZone by remember { mutableStateOf(false) }
        var scheduleSettings by remember { mutableStateOf(false) }
        var zoneInput by remember(profile.scheduleTimeZone) { mutableStateOf(profile.scheduleTimeZone) }
        var selected by remember(owner) { mutableStateOf<java.time.LocalDate?>(null) }
        fun refreshSchedule(force: Boolean = false) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { app.schedule.refresh(profile.id,java.time.LocalDate.now(zone),force); app.refreshPaces(profile.id) }
        }
        LaunchedEffect(profile.id,owner,resumeGeneration.value,today,zone) {
            today=java.time.LocalDate.now(zone)
            if(owner!=null) refreshSchedule()
        }
        LaunchedEffect(zone) {
            while(true) { kotlinx.coroutines.delay(60_000); today=java.time.LocalDate.now(zone) }
        }
        fun entries(date: java.time.LocalDate)=cache?.entries.orEmpty().filter { it.source.date==date.toString() }
        fun completed(entry: ScheduleEntry)=saved.rides.any { it.profileId==profile.id && it.status=="complete" &&
            it.guideCompleted && it.plannedWorkout?.garminSource==entry.preview.source }
        @Composable fun Entry(entry: ScheduleEntry, detailed: Boolean) {
            val preview=entry.preview
            val plan=app.scheduledPlan(profile.id,entry)
            val bounds=preview.executableSteps.mapNotNull { step -> step.hrLow?.let { it to step.hrHigh!! } }
            Text(preview.name,fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text(if(preview.executableSteps.isEmpty()) "Preview only" else
                "${preview.executableSteps.sumOf { it.seconds }/60} min · ${if(bounds.isEmpty()) "No HR target" else "${bounds.minOf { it.first }}–${bounds.maxOf { it.second }} bpm across intervals"} · Max ${fmt(plan.maxMph,1)} mph",color=Muted)
            Text("Running pace ${fmt(plan.steps.firstOrNull()?.startMph ?: 2.0,1)} mph" + if(detailed) " · ${plan.steps.firstOrNull()?.paceReason.orEmpty()}" else "",color=Green,fontSize=14.sp)
            if(detailed) Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled=!busy && plan.maxMph>2,onClick={app.setScheduledMax(profile.id,entry,(plan.maxMph-.5).coerceAtLeast(2.0))}) { Text("− Max") }
                OutlinedButton(enabled=!busy && plan.maxMph<10,onClick={app.setScheduledMax(profile.id,entry,(plan.maxMph+.5).coerceAtMost(10.0))}) { Text("+ Max") }
            }
            if(completed(entry)) Text("Completed in OpenRun · Garmin Coach credit unverified",color=Green)
            if(detailed) preview.steps.forEach { Text(it,fontSize=15.sp,color=Muted) }
            preview.executionIssue?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(enabled=!busy && preview.executionIssue==null && preview.executableSteps.isNotEmpty(),onClick={
                    app.startScheduledWorkout(profile.id,entry)
                    if(app.active.value!=null) selected=null
                }) { Text(if(completed(entry)) "Start again · 3 sec countdown" else "Start workout · 3 sec countdown") }
                if(!detailed) OutlinedButton(onClick={selected=java.time.LocalDate.parse(entry.source.date)}) { Text("View intervals") }
            }
        }
        Panel {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("TODAY’S WORKOUT",color=Green,fontSize=12.sp,letterSpacing=2.sp,modifier=Modifier.weight(1f))
                if(owner!=null) TextButton(onClick={scheduleSettings=true}) { Text(if(refresh.loading) "Syncing…" else "Schedule settings",color=Muted) }
            }
            if(owner==null) {
                Text("Your next run starts here.",fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text("Connect Garmin to bring your training plan onto the treadmill.",color=Muted)
                Button(onClick=onConnections) { Text("Connect Garmin") }
            } else {
                refresh.error?.let { Text("Using saved schedule · tap Schedule settings for details",color=MaterialTheme.colorScheme.error,fontSize=13.sp) }
                if(cache==null) Text(if(refresh.loading) "Loading your Garmin week…" else "Your schedule is not available yet. Try Refresh in Schedule settings.",color=Muted)
                else {
                    val todays=entries(today)
                    if(todays.isNotEmpty()) todays.forEach { Entry(it,false) }
                    else {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(if(cache.fromDate==today.toString()) "A day to recharge." else "No saved workout today",fontSize=28.sp,fontWeight=FontWeight.Bold)
                                cache.entries.firstOrNull { it.source.date>today.toString() }?.let { Text("Up next · ${it.source.name}",color=Muted,fontSize=17.sp) }
                            }
                            cache.entries.firstOrNull { it.source.date>today.toString() }?.let { next ->
                                OutlinedButton(onClick={selected=java.time.LocalDate.parse(next.source.date)}) { Text("Preview next") }
                            }
                        }
                    }
                    HorizontalDivider(color=Muted.copy(alpha=.15f))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        repeat(7) { offset ->
                            val date=today.plusDays(offset.toLong()); val dayEntries=entries(date)
                            Surface(color=if(offset==0) Green.copy(alpha=.14f) else Background,shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f).height(88.dp).clickable { selected=date }) {
                                Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                                    Text(date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d")),fontSize=13.sp,color=if(offset==0) Green else Muted,fontWeight=FontWeight.Bold)
                                    Text(if(dayEntries.isEmpty()) { if(date.toString()<=java.time.LocalDate.parse(cache.fromDate).plusDays(6).toString()) "Rest" else "Not synced" } else dayEntries.joinToString(" / ") { it.source.name },fontSize=13.sp,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            WarmupOption(profile,enabled=!busy)
        }
        if(scheduleSettings) AlertDialog(onDismissRequest={scheduleSettings=false},title={Text("Schedule & pace")},text={
            Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(cache?.let { "Last synced ${SimpleDateFormat("MMM d, h:mm a",Locale.getDefault()).apply { timeZone=java.util.TimeZone.getTimeZone(zone) }.format(Date(it.syncedAt))}" } ?: "No saved schedule",color=Muted)
                refresh.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Text(paceMessages[profile.id] ?: "Pace adapts as comparable runs become available.")
                Text("Workouts are available offline after syncing. You can adjust the speed limit in each workout preview.",color=Muted)
                TextButton(enabled=!refresh.loading,onClick={editingZone=true}) { Text("Time zone · ${profile.scheduleTimeZone}") }
                Button(enabled=!refresh.loading,onClick={refreshSchedule(true)}) { Text(if(refresh.loading) "Syncing…" else "Refresh Garmin") }
            }
        },confirmButton={TextButton(onClick={scheduleSettings=false}) { Text("Done") }})
        if(editingZone) AlertDialog(onDismissRequest={editingZone=false},title={Text("Schedule time zone")},
            text={ Column { Text("Use a region such as America/Denver, America/New_York, or Europe/London.")
                OutlinedTextField(value=zoneInput,onValueChange={zoneInput=it},singleLine=true,label={Text("Time zone")}) } },
            confirmButton={Button(enabled=runCatching { java.time.ZoneId.of(zoneInput.trim()) }.isSuccess,onClick={
                app.store.update { state -> state.copy(profiles=state.profiles.map { if(it.id==profile.id) it.copy(scheduleTimeZone=zoneInput.trim()) else it },schedules=state.schedules.filterNot { it.profileId==profile.id }) }
                editingZone=false
            }) { Text("Save") }},dismissButton={TextButton(onClick={editingZone=false}) { Text("Cancel") }})
        selected?.let { date ->
            AlertDialog(onDismissRequest={selected=null},title={Text(date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d")))},
                text={ Column(Modifier.heightIn(max=580.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    val dayEntries=entries(date)
                    if(dayEntries.isEmpty()) Text("No workout in the saved schedule for this day. Manual and Zone 2 workouts are available on the home screen.")
                    dayEntries.forEach { Entry(it,true) }
                } },confirmButton={TextButton(onClick={selected=null}) { Text("Close") }})
        }
    }
    @Composable private fun Workout(profile:Profile, onSleep:()->Unit) {
        val t by app.treadmill.telemetry.collectAsState()
        val status by app.treadmill.status.collectAsState()
        val hr by app.heart.bpm.collectAsState()
        val run by app.active.collectAsState()
        val paused by app.paused.collectAsState()
        val busy by app.controlBusy.collectAsState()
        val controlStatus by app.controlStatus.collectAsState()
        val manualAdjusting by app.manualAdjusting.collectAsState()
        val manualTargets by app.manualTargets.collectAsState()
        val desiredSpeed=manualTargets.lastOrNull { it is Target.Speed }?.value ?: t.mph
        val desiredIncline=manualTargets.lastOrNull { it is Target.Incline }?.value ?: t.incline
        val canAdjust=(!busy || manualAdjusting) && !paused && (t.mph ?: 0.0)>=0.2
        val zoneStatus by app.zoneStatus.collectAsState()
        val plannedStatus by app.plannedStatus.collectAsState()
        val plannedName by app.plannedName.collectAsState()
        val zoneEnabled by app.zoneEnabled.collectAsState()
        val verified by app.controlsVerified.collectAsState()
        var start by remember { mutableStateOf(false) }
        var startZoneTwo by remember { mutableStateOf(false) }
        val warming by app.warmingUp.collectAsState()
        val warmupText by app.warmupStatus.collectAsState()
        if(run==null) Panel {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Or go at your own pace.",fontSize=24.sp,fontWeight=FontWeight.Bold)
                    Text("${if(t.receivedAt>0 && android.os.SystemClock.elapsedRealtime()-t.receivedAt<5000) "Treadmill connected" else "Waiting for treadmill"} · ${hr?.let { "♥ $it bpm" } ?: "Chest strap not connected"}",color=Muted)
                }
                OutlinedButton(onClick={startZoneTwo=false;start=true},enabled=!busy,modifier=Modifier.height(56.dp)) { Text("Manual workout") }
                Button(onClick={startZoneTwo=true;start=true},enabled=verified && !busy && hr!=null,modifier=Modifier.height(56.dp)) { Text("Zone 2 · Watch & walk") }
            }
            Text("Zone 2: 120–140 bpm · 2–4 mph. Manual workouts follow your controls.",color=Muted,fontSize=13.sp)
            if(!verified) Text("Verify treadmill controls in Connections to unlock Zone 2.",color=Muted,fontSize=13.sp)
        }
        if(run!=null) {
        Panel {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("LIVE SESSION",color=Green,fontSize=12.sp,letterSpacing=2.sp)
                Text(if(run==null) "READY TO RECORD" else if(paused) "RECORDING PAUSED" else "RECORDING · ${profile.name}",color=Muted,fontSize=12.sp)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Metric("SPEED",t.mph?.let { fmt(it,1) } ?: "—","mph")
                Metric("INCLINE",t.incline?.let { fmt(it,1) } ?: "—","%")
                Metric("DISTANCE",fmt((run?.distanceMeters ?: 0.0)/1609.344,2),"mi")
                Metric("HEART RATE",hr?.toString() ?: "—","bpm")
                val seconds=run?.durationSec ?: 0
                Metric("TIME","%d:%02d".format(seconds/60,seconds%60),"elapsed")
            }
            Text("Estimated ascent  ${fmt((run?.ascentMeters ?: 0.0)*3.28084,0)} ft     ·     Descent  ${run?.descentMeters?.let { fmt(it*3.28084,0) } ?: "—"} ft",color=Muted,fontSize=14.sp)
            val activeHike=app.hike
            val hikeStatus by app.hikeStatus.collectAsState()
            if(activeHike!=null) {
                HikeMap(activeHike.selection.hike,app.hikeDistance,paused,warming,compact=true)
                hikeStatus?.let { Text(it,color=Green) }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick={startActivity(Intent(this@MainActivity,OutdoorActivity::class.java))}) { Text("Hike details & library") }
                    TextButton(onClick={
                        val pkg=if(packageManager.getLaunchIntentForPackage("com.plexapp.android")!=null) "com.plexapp.android" else "com.netflix.mediaclient"
                        openEntertainment(pkg,"Plex")
                    }) { Text("Watch Plex") }
                }
            } else WorkoutTrack(run?.distanceMeters ?: 0.0,paused,if(warming) "WARM-UP" else if(plannedName!=null) "GUIDED RUN" else if(zoneEnabled) "ZONE 2" else "YOUR RUN")
            if(warming) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(warmupText.orEmpty(),fontSize=18.sp,color=Green)
                TextButton(onClick={app.skipWarmup()},enabled=!busy && !paused) { Text("Skip warm-up") }
            }
            HorizontalDivider(color=Muted.copy(alpha=.2f))
            Text(controlStatus,fontSize=15.sp,color=Green)
            if(manualTargets.isNotEmpty()) Text("Requested: " + manualTargets.joinToString(" · ") {
                if(it is Target.Speed) "${fmt(it.value,1)} mph" else "${fmt(it.value,1)}% incline"
            },color=Green)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick={app.adjustManual(true,-0.1)},enabled=canAdjust && (desiredSpeed ?: 0.0)>0.2) { Text("− Speed") }
                OutlinedButton(onClick={app.adjustManual(true,0.1)},enabled=canAdjust && (desiredSpeed ?: app.manualMaxMph)<app.manualMaxMph) { Text("+ Speed") }
                OutlinedButton(onClick={app.adjustManual(false,-0.5)},enabled=canAdjust && (desiredIncline ?: app.manualMinIncline)>app.manualMinIncline) { Text("− Incline") }
                OutlinedButton(onClick={app.adjustManual(false,0.5)},enabled=canAdjust && (desiredIncline ?: app.manualMaxIncline)<app.manualMaxIncline) { Text("+ Incline") }
            }
            Text(if(app.hike!=null) "Speed stays manual. Only incline changes hold terrain following for 60 seconds." else if(plannedName!=null) "Manual changes hold until the next interval. Incline capped at 3%." else "Manual changes hold for 60 seconds. Physical controls remain available.",fontSize=13.sp,color=Muted)
        }
        if(plannedName!=null) Panel {
            Text(plannedName!!,fontSize=26.sp,fontWeight=FontWeight.Bold)
            Text(plannedStatus.orEmpty(),color=Green,fontSize=20.sp)
            Text("HR checks every 10 seconds · 30 seconds to settle after adjustments · ${fmt(app.manualMaxMph,1)} mph maximum · incline 1–3% before speed",color=Muted)
        }
        if(plannedName==null && app.hike==null) Panel {
            Text("AUTOMATIC HR CONTROL",color=Green,fontSize=12.sp,letterSpacing=2.sp)
            Text("Zone 2 · Watch & walk",fontSize=27.sp,fontWeight=FontWeight.Bold)
            Text("120–140 BPM    /    2–4 mph    /    up to 20% incline",fontSize=18.sp)
            Text("Incline adjusts first in either direction. HR loss holds your settings while the workout continues.",color=Muted,fontSize=15.sp)
            Text(zoneStatus,color=Green,fontSize=18.sp)
            if(!verified) Text("Before enabling Zone 2, test Start, incline +/−, and Stop belt. Confirm the results under Connections.",color=Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(onClick={
                    if(zoneEnabled) app.disableZone()
                    else if(run==null) { startZoneTwo=true; start=true }
                    else app.enableZone()
                },enabled=zoneEnabled || (verified && !paused && !busy && hr!=null)) {
                    Text(if(zoneEnabled) "Turn automatic adjustments off" else if(run==null) "Start Zone 2 workout" else "Enable Zone 2")
                }
                if(!zoneEnabled && hr==null) Text("Connect your chest strap to start Zone 2.",color=Muted)
            }
        }
        }
        if(start) AlertDialog(onDismissRequest={start=false},title={Text(if(startZoneTwo) "Start Zone 2 at 2 mph?" else "Start walking at 2 mph?")},text={Text("The belt starts after a 3-second countdown. Warm-up is ${if(profile.warmupEnabled) "on" else "off"}. " + if(startZoneTwo) "Zone 2 enables after the optional five-minute warm-up." else "Automatic HR adjustments stay off until you enable Zone 2.")},confirmButton={Button(onClick={start=false; app.startWorkout(withZoneTwo=startZoneTwo)}){Text("Start belt & workout")}},dismissButton={TextButton(onClick={start=false}){Text("Cancel")}})

    }
    @Composable private fun History(profile:Profile) {
        val saved by app.store.state.collectAsState()
        val garmin by app.garmin.state.collectAsState()
        var selectedRide by remember(profile.id) { mutableStateOf<Long?>(null) }
        val rides=saved.rides.filter { it.profileId==profile.id && it.status!="recording" }.sortedByDescending { it.id }
        if(rides.isEmpty()) Panel { Text("Your first workout starts here.",fontSize=23.sp); Text("Saved sessions for ${profile.name} will appear here.",color=Muted) }
        rides.filter { selectedRide==null || it.id==selectedRide }.forEach { r -> Panel {
            if(selectedRide!=null) TextButton(onClick={selectedRide=null}) { Text("← All workouts") }
            Text(r.hikeName ?: r.plannedWorkout?.name ?: "Free workout",fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text(java.time.Instant.ofEpochMilli(r.startedAt).atOffset(java.time.ZoneOffset.ofTotalSeconds(r.utcOffsetSeconds)).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a",Locale.US)),fontSize=18.sp,color=Muted)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Metric("DURATION","${r.durationSec/60}:${"%02d".format(r.durationSec%60)}","min:sec"); Metric("DISTANCE",fmt(r.distanceMeters/1609.344,2),"mi"); Metric("AVG HR",r.samples.mapNotNull{it.heartRate}.takeIf{it.isNotEmpty()}?.average()?.toInt()?.toString() ?: "—","bpm"); Metric("ASCENT",fmt(r.ascentMeters*3.28084,0),"ft estimated"); Metric("DESCENT",r.descentMeters?.let { fmt(it*3.28084,0) } ?: "—","ft estimated") }
            Text(if(r.status=="interrupted") "Interrupted session · recovered locally; not auto-uploaded" else garmin.uploads.firstOrNull{it.profileId==profile.id && it.rideId==r.id}?.let { "Garmin: ${it.status} · ${it.message.orEmpty()}" } ?: "Saved on this treadmill",color=Muted)
            if(selectedRide==r.id) WorkoutHistoryDetails(r)
            else OutlinedButton(onClick={selectedRide=r.id}) { Text("View workout") }
        } }
    }
    @Composable private fun Connections(profile:Profile) {
        val hrStatus by app.heart.status.collectAsState()
        val devices by app.heart.devices.collectAsState()
        val consoleStatus by app.consoleStatus.collectAsState()
        val verified by app.controlsVerified.collectAsState()
        val active by app.active.collectAsState()
        val busy by app.controlBusy.collectAsState()
        Panel {
            Text("Chest strap",fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text(profile.strapName ?: "Pair a heart-rate monitor for ${profile.name}",color=Muted)
            Text(hrStatus,color=Green)
            Button(onClick={requestBle()}) { Text("Find chest strap") }
            devices.forEach { d -> OutlinedButton(onClick={app.pair(d.address,d.name)},enabled=app.active.value==null) { Text("${d.name} · ${d.address.takeLast(5)}") } }
            Text("Wear your Garmin strap while pairing. Android 9 also needs Location enabled for Bluetooth scanning.",fontSize=13.sp,color=Muted)
        }
        key(profile.id) { GarminPanel(profile) }
        Panel {
            Text("Console credentials",fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text(if(ConsoleCredentials.installed(this@MainActivity)) "Local credentials installed." else if(BuildConfig.DEBUG) "Local developer assets may be used." else "Import credentials from your console before using treadmill controls.",color=Muted)
            OutlinedButton(onClick={importCredentials.launch("application/zip")},enabled=active==null && !busy) { Text("Import console credentials") }
            Text("Select a local ZIP containing the CA, client certificate and private key. Force stop and relaunch OpenRun after importing.",color=Muted)
        }
        Panel {
            Text("Treadmill connection",fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text(consoleStatus,color=Green)
            Text("Keep NordicFTMS running. Confirm on the treadmill that Start sets 2 mph, incline buttons move the deck, and Stop belt fully stops it.",color=Muted)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=verified,onCheckedChange={app.verifyControls(it)},enabled=active==null && !busy)
                Text("I verified start, incline changes, and a complete belt stop.")
            }
            Text("This unlocks Zone 2. End any test workout before checking this box.",color=Muted,fontSize=13.sp)
            OutlinedButton(onClick={val i=packageManager.getLaunchIntentForPackage("com.nordicftms.app"); if(i!=null) startActivity(i) else app.message.value="Install NordicFTMS first."}) { Text("Open NordicFTMS") }
        }
    }
    @Composable private fun PlannedWorkouts(profile:Profile,onStarted:()->Unit) {
        val saved by app.store.state.collectAsState()
        val active by app.active.collectAsState()
        val controlBusy by app.controlBusy.collectAsState()
        Panel {
            Text("${profile.name}’s saved workouts",fontSize=24.sp,fontWeight=FontWeight.Bold)
            WarmupOption(profile,enabled=active==null && !controlBusy)
            Text("Optional five-minute warm-up, then adaptive running pace. HR adjusts incline 1–3% first, then speed within your chosen limit. Garmin’s prescribed warm-ups remain part of the workout. The belt stops at the end; you choose save or discard.",color=Muted)
            Text("Re-import older saved sessions to retain their Garmin schedule details. Uploading a run does not yet confirm Garmin Coach credit.",color=Muted,fontSize=13.sp)
            val plans=saved.workouts.filter { it.profileId==profile.id }
            if(plans.isEmpty()) Text("Import a workout below, preview its steps, then save it here.",color=Muted)
            plans.forEach { plan ->
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("${plan.garminSource?.date?.let { "$it · " }.orEmpty()}${plan.name} · ${plan.steps.sumOf { it.seconds }/60} min · ${plan.steps.size} steps",Modifier.weight(1f))
                    Text("Max ${fmt(plan.maxMph,1)} mph")
                    OutlinedButton(enabled=active==null && !controlBusy && plan.maxMph>2.0,onClick={app.setPlannedMaxSpeed(plan.id,(plan.maxMph-0.5).coerceAtLeast(2.0))}) { Text("− Max") }
                    OutlinedButton(enabled=active==null && !controlBusy && plan.maxMph<10.0,onClick={app.setPlannedMaxSpeed(plan.id,(plan.maxMph+0.5).coerceAtMost(10.0))}) { Text("+ Max") }
                    Button(enabled=active==null && !controlBusy,onClick={app.startPlannedWorkout(plan); if(app.active.value!=null) onStarted()}) { Text("Start workout") }
                }
            }
        }
        val state by app.garmin.state.collectAsState()
        val account=state.accounts.firstOrNull { it.profileId==profile.id }
        key(account?.email) {
            val scope=rememberCoroutineScope()
            var workouts by remember { mutableStateOf<List<GarminPlannedWorkout>?>(null) }
            var preview by remember { mutableStateOf<GarminWorkoutPreview?>(null) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            fun load(block:suspend ()->Unit) {
                busy=true; error=null
                scope.launch {
                    try { block() }
                    catch(e:kotlinx.coroutines.CancellationException) { throw e }
                    catch(e:Exception) { error=(e as? GarminFailure)?.userMessage ?: "Could not load workouts. Check your connection and try again." }
                    finally { busy=false }
                }
            }
            Panel {
                Text("Garmin planned workouts",fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text("Preview upcoming workouts for ${profile.name}. This checks the current and next calendar month. Some adaptive Coach sessions may not be exposed by Garmin.",color=Muted)
                Text("Import and save here; the belt only moves when you tap Start workout.",color=Green)
                if(account==null || account.needsLogin) Text("Connect Garmin for this runner in Connections first.",color=Muted)
                Button(enabled=!busy && account!=null && !account.needsLogin,onClick={load {
                    preview=null; workouts=null
                    workouts=app.garmin.plannedWorkouts(profile.id)
                }}) { Text(if(busy) "Loading…" else "Import from Garmin") }
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                if(workouts?.isEmpty()==true) Text("No upcoming workouts were exposed by Garmin. Check your Garmin Connect calendar; a Coach session shown only on your watch may not be available here.",color=Muted)
                workouts?.forEach { workout ->
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("${workout.date} · ${workout.name}",Modifier.weight(1f))
                        OutlinedButton(enabled=!busy,onClick={load { preview=null; preview=app.garmin.previewWorkout(profile.id,workout) }}) { Text("Preview steps") }
                    }
                }
            }
            preview?.let { workout -> Panel {
                Text(workout.name,fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text("${workout.sport} · Garmin preview",color=Muted)
                workout.steps.forEach { Text(it,fontSize=18.sp) }
                workout.executionIssue?.let { Text(it,color=Muted) }
                Button(enabled=workout.executionIssue==null && workout.executableSteps.isNotEmpty(),onClick={app.savePlannedWorkout(workout)}) { Text("Save as custom workout") }
            } }
        }
    }
    @Composable private fun GarminPanel(profile:Profile) {
        val state by app.garmin.state.collectAsState()
        val account=state.accounts.firstOrNull { it.profileId==profile.id }
        val scope=rememberCoroutineScope()
        val api=remember { GarminApi() }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var verification by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        Panel {
            Text("Garmin Connect · optional",fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text("Only ${profile.name}’s completed workouts go to this account. Login uses the same unofficial integration as OpenRide.",color=Muted)
            app.garmin.storageError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(account!=null) {
                Text(account.email,color=Green)
                Row(verticalAlignment=Alignment.CenterVertically) { Switch(checked=account.enabled,onCheckedChange={ enabled -> scope.launch { runCatching { app.garmin.setEnabled(profile.id,enabled) }.onFailure { error="Could not save sync setting." } } }); Text("Automatically upload future workouts",Modifier.padding(start=12.dp)) }
                Text(account.message ?: "Connected",color=Muted)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick={scope.launch { app.garmin.retry(profile.id) }}) { Text("Retry uploads") }
                    OutlinedButton(onClick={scope.launch { app.garmin.disconnect(profile.id) }}) { Text("Disconnect Garmin") }
                }
            }
            if(account==null || account.needsLogin) {
                if(!verification) {
                    OutlinedTextField(value=email,onValueChange={email=it},label={Text("Garmin email")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=password,onValueChange={password=it},label={Text("Password")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth())
                } else OutlinedTextField(value=code,onValueChange={code=it},label={Text("Verification code")},singleLine=true,enabled=!busy)
                Button(enabled=!busy && (if(verification) code.isNotBlank() else email.isNotBlank() && password.isNotEmpty()),onClick={
                    busy=true; error=null
                    val owner=profile.id; val loginEmail=email
                    scope.launch {
                        try {
                            val result=if(verification) api.verify(code) else api.login(loginEmail,password)
                            password=""
                            when(result) {
                                is GarminLoginResult.VerificationRequired -> verification=true
                                is GarminLoginResult.Connected -> { app.garmin.connect(owner,loginEmail,result.tokens); verification=false; code="" }
                            }
                        } catch(e:kotlinx.coroutines.CancellationException) { throw e }
                        catch(e:Exception) { error=(e as? GarminFailure)?.userMessage ?: "Could not connect. Check your network and try again." }
                        finally { busy=false; password="" }
                    }
                }) { Text(if(busy) "Connecting…" else if(verification) "Verify" else "Connect Garmin") }
                if(verification) TextButton(onClick={verification=false; code=""},enabled=!busy) { Text("Start sign-in again") }
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    }
}
@Composable private fun Panel(content:@Composable ColumnScope.()->Unit) {
    Surface(color=Surface,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(26.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content) }
}
@Composable private fun Metric(label:String,value:String,unit:String) { Column(verticalArrangement=Arrangement.spacedBy(5.dp)) { Text(label,color=Muted,fontSize=11.sp,letterSpacing=1.sp); Text(value,fontSize=38.sp,fontWeight=FontWeight.Medium); Text(unit,color=Muted,fontSize=13.sp) } }
private fun fmt(value:Double,places:Int)=String.format(Locale.US,"%.${places}f",value)
