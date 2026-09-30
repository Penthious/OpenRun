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

private val Green:Color @Composable get()=MaterialTheme.colorScheme.primary
private val Background:Color @Composable get()=MaterialTheme.colorScheme.background
private val Surface:Color @Composable get()=MaterialTheme.colorScheme.surface
private val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
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
        setContent { MaterialTheme(colorScheme=openRunColors()) { Surface(color=Background,contentColor=Color.White) {
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
            Text("WATCH & UNWIND",color=Muted,fontSize=12.sp,letterSpacing=2.sp)
            Text("Watch while you move.",fontSize=25.sp,fontWeight=FontWeight.Bold)
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
        val pausedSession by app.paused.collectAsState()
        var tab by remember { mutableStateOf("Home") }
        var progressHistory by remember { mutableStateOf(false) }
        var switching by remember { mutableStateOf(false) }
        var ending by remember { mutableStateOf(false) }
        var adding by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        val profile=saved.profiles.firstOrNull { it.id==saved.selectedId }
        LaunchedEffect(resumeGeneration.value) { if(app.hike!=null && app.active.value!=null) tab="Home" }
        Row(Modifier.fillMaxSize().background(Background).padding(24.dp),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(230.dp).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("OPENRUN",color=Green,fontSize=27.sp,fontWeight=FontWeight.Black,letterSpacing=3.sp,modifier=Modifier.padding(vertical=14.dp))
                Surface(shape=RoundedCornerShape(18.dp),color=Surface,modifier=Modifier.fillMaxWidth().clickable(enabled=active==null && !busy) { switching=true }) {
                    Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("YOUR PROFILE",fontSize=10.sp,color=Muted,letterSpacing=1.sp); Text(profile?.name ?: "Choose runner",fontSize=21.sp,fontWeight=FontWeight.Bold) }
                        Text("⌄",color=Green,fontSize=24.sp)
                    }
                }
                if(active!=null) Text("Profile locked during workout",color=Muted,fontSize=12.sp)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    listOf("Home" to "Your next session","Workouts" to "Plans & intervals","Trails" to "Explore your routes","Progress" to "Trends & history","Settings" to "Devices & accounts").forEach { (label,description) ->
                        val selected=tab==label
                        Surface(color=if(selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().clickable {
                            if(label=="Trails") startActivity(Intent(this@MainActivity,OutdoorActivity::class.java)) else tab=label
                        }) {
                            Row(Modifier.padding(horizontal=12.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.width(4.dp).height(38.dp).background(if(selected) Green else Color.Transparent,RoundedCornerShape(2.dp)))
                                Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text(if(label=="Home" && active!=null) "Live workout" else label,fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=Color.White)
                                Text(description,fontSize=12.sp,color=Muted)
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick={tab="Watch"},modifier=Modifier.fillMaxWidth().height(52.dp)) { Text("Watch Plex & more",fontSize=16.sp) }
                if(active!=null) {
                    Button(onClick={if(pausedSession) app.resumeWorkout() else app.pauseWorkout()},enabled=!pausedSession || !busy,modifier=Modifier.fillMaxWidth().height(58.dp)) { Text(if(pausedSession) "Resume workout" else "Pause workout",fontSize=17.sp) }
                    OutlinedButton(onClick={ending=true},modifier=Modifier.fillMaxWidth().height(54.dp)) { Text("End workout",fontSize=17.sp) }
                }
                Button(onClick={app.pauseWorkout()},modifier=Modifier.fillMaxWidth().height(64.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB64236),contentColor=Color.White)) { Text("STOP BELT",fontSize=18.sp,fontWeight=FontWeight.Bold) }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick=onSleep,enabled=active==null || pausedSession) { Text("Sleep",color=Muted) }
                    TextButton(onClick={startActivity(Intent(this@MainActivity,MaintenanceActivity::class.java))}) { Text(BuildConfig.VERSION_NAME,color=Muted,fontSize=12.sp) }
                }
            }
            key(tab,profile?.id) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                    Row(Modifier.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(when(tab) { "Home"->if(active!=null) "Make this one yours." else "Ready, ${profile?.name ?: "runner"}?"; "Workouts"->"Find your next workout."; "Progress"->"See how far you’ve come."; "Settings"->"Everything connected."; else->"A good show. A great workout." },fontSize=34.sp,fontWeight=FontWeight.Bold)
                            Text(when(tab) { "Home"->if(active!=null) "Your session is in progress. Controls are always on the left." else "Choose your pace, follow a plan, or put on your favorite show."; "Workouts"->"Your saved plans, ready when you are."; "Progress"->"${profile?.name ?: "Your"}’s progress, one session at a time."; "Settings"->"Manage your chest strap, Garmin account and treadmill."; else->"Your workout controls follow you into the player." },color=Muted,fontSize=16.sp)
                        }
                        if(active!=null && tab!="Home") Button(onClick={tab="Home"}) { Text("Return to workout") }
                    }
                    if(profile==null) Panel { Text("Start with a profile",fontSize=24.sp); Text("Your workouts, progress and Garmin account, together.",color=Muted); Button(onClick={adding=true}) { Text("Create profile") } }
                    else when(tab) {
                        "Home"->{
                            Workout(profile,onSleep)
                            if(active==null) Row(horizontalArrangement=Arrangement.spacedBy(22.dp),verticalAlignment=Alignment.Top) {
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(22.dp)) { HomeSchedule(profile,onConnections={tab="Settings"}); RecentTrails() }
                                Column(Modifier.width(350.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                                    Entertainment()
                                    HomeWeeklyProgress(profile,saved.rides,onOpen={progressHistory=false;tab="Progress"})
                                }
                            }
                        }
                        "Workouts"->PlannedWorkouts(profile,onStarted={tab="Home"})
                        "Progress"->{
                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                FilterChip(selected=!progressHistory,onClick={progressHistory=false},label={Text("Overview",fontSize=17.sp)})
                                FilterChip(selected=progressHistory,onClick={progressHistory=true},label={Text("Workout history",fontSize=17.sp)})
                            }
                            key(progressHistory) { if(progressHistory) History(profile) else ProgressDashboard(profile,saved.rides) }
                        }
                        "Settings"->{
                            Panel {
                                Text("Color scheme",fontSize=24.sp,fontWeight=FontWeight.Bold)
                                Text("Saved automatically for ${profile.name}. Each runner can choose their own look.",color=Muted)
                                Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                    runPalettes.forEach { option ->
                                        val chosen=runPalette(profile.colorScheme).id==option.id
                                        Surface(color=Color(option.surface),shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(if(chosen) 3.dp else 1.dp,if(chosen) Color(option.accent) else Color(0xFF657078)),modifier=Modifier.weight(1f).clickable { app.setColorScheme(option.id) }) {
                                            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                                    listOf(option.background,option.surface,option.accent).forEach { value -> Box(Modifier.size(26.dp).background(Color(value),RoundedCornerShape(13.dp))) }
                                                }
                                                Text(option.name,color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
                                                Text(if(chosen) "Selected ✓" else "Tap to use",color=Color(option.accent),fontSize=14.sp)
                                            }
                                        }
                                    }
                                }
                            }
                            Panel {
                                Text("Workout preferences",fontSize=24.sp,fontWeight=FontWeight.Bold)
                                WarmupOption(profile,enabled=active==null && !busy)
                                val update by app.updates.state.collectAsState()
                                OutlinedButton(onClick={startActivity(Intent(this@MainActivity,MaintenanceActivity::class.java))}) { Text(if(update.ready!=null) "Update available · Manage app" else "Updates & backup") }
                            }
                            Connections(profile)
                        }
                        else->Row { Column(Modifier.width(520.dp)) { Entertainment() } }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if(switching) AlertDialog(containerColor=Surface,onDismissRequest={switching=false},title={Text("Who’s working out?")},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                saved.profiles.forEach { p ->
                    OutlinedButton(onClick={app.selectProfile(p.id);switching=false},enabled=active==null && !busy,modifier=Modifier.fillMaxWidth().height(56.dp)) { Text(p.name+if(p.id==profile?.id) "  •" else "",fontSize=20.sp) }
                }
            }
        },confirmButton={TextButton(onClick={switching=false;adding=true},enabled=active==null && !busy) { Text("+ Add profile") }},dismissButton={TextButton(onClick={switching=false}) { Text("Close") }})
        if(ending && active!=null) AlertDialog(containerColor=Surface,onDismissRequest={ending=false},title={Text("End this workout?")},text={Text("Stop the belt and save to ${profile?.name ?: "your profile"}’s history and Garmin if connected, or discard without saving or uploading.")},confirmButton={Button(onClick={app.endWorkout();ending=false}) { Text("Stop & save") }},dismissButton={Row {
            TextButton(onClick={app.endWorkout(save=false);ending=false}) { Text("End without saving") }
            TextButton(onClick={ending=false}) { Text("Keep going") }
        }})
        if(adding) AlertDialog(containerColor=Surface,onDismissRequest={adding=false},title={Text("New runner")},text={OutlinedTextField(value=name,onValueChange={if(it.length<=40) name=it},label={Text("Name")},singleLine=true)},confirmButton={Button(onClick={app.store.addProfile(name); app.selectProfile(app.store.state.value.selectedId!!); name=""; adding=false},enabled=name.trim().isNotEmpty()){Text("Create profile")}},dismissButton={TextButton(onClick={adding=false}){Text("Cancel")}})
        notice?.let { AlertDialog(containerColor=Surface,onDismissRequest={app.message.value=null},title={Text("OpenRun")},text={Text(it)},confirmButton={TextButton(onClick={app.message.value=null}){Text("OK")}}) }
    }
    @Composable private fun RecentTrails() {
        val busy by app.controlBusy.collectAsState()
        val active by app.active.collectAsState()
        var trails by remember { mutableStateOf<List<SavedHike>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(resumeGeneration.value) {
            loading=true
            try {
                trails=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { HikeLibrary(this@MainActivity).recent(5) }
                failed=false
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(_:Exception) { failed=true }
            finally { loading=false }
        }
        Panel {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("RECENT TRAILS",color=Green,fontSize=12.sp,letterSpacing=2.sp)
                    Text("Your next escape.",fontSize=24.sp,fontWeight=FontWeight.Bold)
                }
                TextButton(onClick={startActivity(Intent(this@MainActivity,OutdoorActivity::class.java))}) { Text("All trails & import") }
            }
            Text("Start after a 3-second countdown at 2 mph, or queue without moving the belt. Terrain limits: −6% to 40%. Your warm-up setting applies.",color=Muted,fontSize=14.sp)
            when {
                loading -> Text("Loading trails…",color=Muted)
                failed -> Text("Could not load recent trails. Open All trails to try again.",color=Muted)
                trails.isEmpty() -> Text("Download your first GPX in All trails to see it here.",color=Muted)
                else -> trails.forEach { trail ->
                    Surface(color=Background,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                            TrailThumbnail(trail.route,Modifier.width(100.dp).height(66.dp))
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(trail.name,fontSize=19.sp,fontWeight=FontWeight.Bold)
                                Text("${fmt(trail.route.distanceMeters/1609.344,2)} mi"+if(trail.route.hasElevation) " · Terrain following" else " · Missing elevation",color=Muted,fontSize=14.sp)
                            }
                            Button(enabled=active==null && !busy && trail.route.hasElevation,onClick={
                                if(app.queueHike(trail,HikeLimits.MAX_INCLINE)) app.startHike()
                            }) { Text("Start hike") }
                            OutlinedButton(enabled=active==null && !busy && trail.route.hasElevation,onClick={
                                if(app.queueHike(trail,HikeLimits.MAX_INCLINE)) {
                                    val pkg=if(packageManager.getLaunchIntentForPackage("com.plexapp.android")!=null) "com.plexapp.android" else "com.netflix.mediaclient"
                                    openEntertainment(pkg,"Plex")
                                }
                            }) { Text("Queue for Plex") }
                        }
                    }
                }
            }
        }
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
        }
        if(scheduleSettings) AlertDialog(containerColor=Surface,onDismissRequest={scheduleSettings=false},title={Text("Schedule & pace")},text={
            Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(cache?.let { "Last synced ${SimpleDateFormat("MMM d, h:mm a",Locale.getDefault()).apply { timeZone=java.util.TimeZone.getTimeZone(zone) }.format(Date(it.syncedAt))}" } ?: "No saved schedule",color=Muted)
                refresh.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Text(paceMessages[profile.id] ?: "Pace adapts as comparable runs become available.")
                Text("Workouts are available offline after syncing. You can adjust the speed limit in each workout preview.",color=Muted)
                TextButton(enabled=!refresh.loading,onClick={editingZone=true}) { Text("Time zone · ${profile.scheduleTimeZone}") }
                Button(enabled=!refresh.loading,onClick={refreshSchedule(true)}) { Text(if(refresh.loading) "Syncing…" else "Refresh Garmin") }
            }
        },confirmButton={TextButton(onClick={scheduleSettings=false}) { Text("Done") }})
        if(editingZone) AlertDialog(containerColor=Surface,onDismissRequest={editingZone=false},title={Text("Schedule time zone")},
            text={ Column { Text("Use a region such as America/Denver, America/New_York, or Europe/London.")
                OutlinedTextField(value=zoneInput,onValueChange={zoneInput=it},singleLine=true,label={Text("Time zone")}) } },
            confirmButton={Button(enabled=runCatching { java.time.ZoneId.of(zoneInput.trim()) }.isSuccess,onClick={
                app.store.update { state -> state.copy(profiles=state.profiles.map { if(it.id==profile.id) it.copy(scheduleTimeZone=zoneInput.trim()) else it },schedules=state.schedules.filterNot { it.profileId==profile.id }) }
                editingZone=false
            }) { Text("Save") }},dismissButton={TextButton(onClick={editingZone=false}) { Text("Cancel") }})
        selected?.let { date ->
            AlertDialog(containerColor=Surface,onDismissRequest={selected=null},title={Text(date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d")))},
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
                    Text("Start moving.",fontSize=24.sp,fontWeight=FontWeight.Bold)
                    Text("${if(t.receivedAt>0 && android.os.SystemClock.elapsedRealtime()-t.receivedAt<5000) "Treadmill connected" else "Waiting for treadmill"} · ${hr?.let { "♥ $it bpm" } ?: "Chest strap not connected"}",color=Muted)
                }
                Column(Modifier.width(444.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.Top) {
                        OutlinedButton(onClick={startZoneTwo=false;start=true},enabled=!busy,modifier=Modifier.width(200.dp).height(64.dp)) { Text("Quick start",fontSize=19.sp) }
                        Button(onClick={startZoneTwo=true;start=true},enabled=verified && !busy && hr!=null,modifier=Modifier.width(220.dp).height(64.dp)) { Text("Zone 2",fontSize=19.sp) }
                    }
                    val unavailable=when { busy->"Wait for controls to finish"; !verified->"Verify controls in Settings"; hr==null->"Connect chest strap"; else->null }
                    Text(unavailable.orEmpty(),color=Muted,fontSize=13.sp,modifier=Modifier.width(220.dp).align(Alignment.End))
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                WarmupOption(profile,enabled=!busy)
                Text("Zone 2 · 120–140 bpm · 2–4 mph",color=Muted,fontSize=14.sp)
            }
            if(busy) Text("Quick start and warm-up settings are unavailable while controls are updating.",color=Muted,fontSize=13.sp)
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
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick={app.adjustManual(true,-0.1)},enabled=canAdjust && (desiredSpeed ?: 0.0)>0.2) { Text("− Speed") }
                OutlinedButton(onClick={app.adjustManual(true,0.1)},enabled=canAdjust && (desiredSpeed ?: app.manualMaxMph)<app.manualMaxMph) { Text("+ Speed") }
                OutlinedButton(onClick={app.adjustManual(false,-0.5)},enabled=canAdjust && (desiredIncline ?: app.manualMinIncline)>app.manualMinIncline) { Text("− Incline") }
                OutlinedButton(onClick={app.adjustManual(false,0.5)},enabled=canAdjust && (desiredIncline ?: app.manualMaxIncline)<app.manualMaxIncline) { Text("+ Incline") }
            }
            if(zoneEnabled) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(zoneStatus,color=Green,modifier=Modifier.weight(1f))
                TextButton(onClick={app.disableZone()}) { Text("Turn off auto adjustments") }
            }
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

            Text(if(app.hike!=null) "Speed stays manual. Only incline changes hold terrain following for 60 seconds." else if(plannedName!=null) "Manual changes hold until the next interval. Incline capped at 3%." else "Manual changes hold for 60 seconds. Physical controls remain available.",fontSize=13.sp,color=Muted)
        }
        if(plannedName!=null) Panel {
            Text(plannedName!!,fontSize=26.sp,fontWeight=FontWeight.Bold)
            Text(plannedStatus.orEmpty(),color=Green,fontSize=20.sp)
            Text("HR checks every 10 seconds · 30 seconds to settle after adjustments · ${fmt(app.manualMaxMph,1)} mph maximum · incline 1–3% before speed",color=Muted)
        }

        }
        if(start) AlertDialog(containerColor=Surface,onDismissRequest={start=false},title={Text(if(startZoneTwo) "Start Zone 2 at 2 mph?" else "Start walking at 2 mph?")},text={Text("The belt starts after a 3-second countdown. Warm-up is ${if(profile.warmupEnabled) "on" else "off"}. " + if(startZoneTwo) "Zone 2 enables after the optional five-minute warm-up." else "You control speed and incline throughout this workout.")},confirmButton={Button(onClick={start=false; app.startWorkout(withZoneTwo=startZoneTwo)}){Text("Start belt & workout")}},dismissButton={TextButton(onClick={start=false}){Text("Cancel")}})

    }
    @Composable private fun History(profile:Profile) {
        val saved by app.store.state.collectAsState()
        val garmin by app.garmin.state.collectAsState()
        var selectedRide by remember(profile.id) { mutableStateOf<Long?>(null) }
        val rides=saved.rides.filter { it.profileId==profile.id && it.status!="recording" }.sortedByDescending { it.id }
        if(rides.isEmpty()) Panel { Text("Your first workout starts here.",fontSize=23.sp); Text("Saved sessions for ${profile.name} will appear here.",color=Muted) }
        rides.filter { selectedRide==null || it.id==selectedRide }.forEach { r ->
            val title=r.hikeName ?: r.plannedWorkout?.name ?: "Free workout"
            val date=java.time.Instant.ofEpochMilli(r.startedAt).atOffset(java.time.ZoneOffset.ofTotalSeconds(r.utcOffsetSeconds)).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a",Locale.US))
            val upload=if(r.status=="interrupted") "Interrupted · recovered locally" else garmin.uploads.firstOrNull { it.profileId==profile.id && it.rideId==r.id }?.let { "Garmin: ${it.status}" } ?: "Saved locally"
            if(selectedRide==null) Surface(color=Surface,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().clickable { selectedRide=r.id }) {
                Row(Modifier.padding(22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(title,fontSize=22.sp,fontWeight=FontWeight.Bold)
                        Text("$date · $upload",color=Muted,fontSize=14.sp)
                    }
                    Text("${fmt(r.distanceMeters/1609.344,2)} mi",fontSize=24.sp,fontWeight=FontWeight.SemiBold)
                    Text("${r.durationSec/60}:${"%02d".format(r.durationSec%60)}",fontSize=24.sp,modifier=Modifier.width(100.dp))
                    Text("View ›",color=Green,fontSize=17.sp)
                }
            } else Panel {
                TextButton(onClick={selectedRide=null}) { Text("← All workouts") }
                Text(title,fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text(date,color=Muted,fontSize=17.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Metric("DURATION","${r.durationSec/60}:${"%02d".format(r.durationSec%60)}","min:sec")
                    Metric("DISTANCE",fmt(r.distanceMeters/1609.344,2),"mi")
                    Metric("AVG HR",historySummary(r).averageHr?.toInt()?.toString() ?: "—","bpm")
                    Metric("ASCENT",fmt(r.ascentMeters*3.28084,0),"ft estimated")
                    Metric("DESCENT",r.descentMeters?.let { fmt(it*3.28084,0) } ?: "—","ft estimated")
                }
                Text(upload+ (garmin.uploads.firstOrNull { it.profileId==profile.id && it.rideId==r.id }?.message?.let { " · $it" } ?: ""),color=Muted)
                WorkoutHistoryDetails(r)
            }
        }
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
        var deleting by remember { mutableStateOf<SavedWorkout?>(null) }
        var preview by remember { mutableStateOf<SavedWorkout?>(null) }
        var customLibrary by remember { mutableStateOf(true) }
        Panel {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("${profile.name}’s saved workouts",fontSize=24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                Button(enabled=active==null && !controlBusy,onClick={startActivity(Intent(this@MainActivity,WorkoutBuilderActivity::class.java))}) { Text("+ Create workout") }
            }
            WarmupOption(profile,enabled=active==null && !controlBusy)
            if(active!=null || controlBusy) Text(if(active!=null) "End your current workout to create, edit or start another." else "Wait for treadmill controls to finish before changing workouts.",color=Muted)
            Text("Build your own intervals or import from Garmin. Optional five-minute warm-up comes first. At the end, the belt stops and you choose save or discard.",color=Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(selected=customLibrary,onClick={customLibrary=true},label={Text("Custom workouts")})
                FilterChip(selected=!customLibrary,onClick={customLibrary=false},label={Text("Garmin imports")})
            }
            val plans=saved.workouts.filter { it.profileId==profile.id && it.custom==customLibrary }
            if(plans.isEmpty()) Text(if(customLibrary) "No custom workouts yet. Create your first interval plan above." else "No saved Garmin workouts. Import one below.",color=Muted)
            plans.forEach { plan ->
                Surface(color=Background,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("${plan.garminSource?.date?.let { "$it · " }.orEmpty()}${plan.name}",fontSize=21.sp,fontWeight=FontWeight.Bold)
                    Text("${if(plan.custom) "Custom" else "Garmin"} · ${plan.durationLabel()} · ${plan.steps.size} intervals · Max ${fmt(plan.maxMph,1)} mph",color=Muted)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Button(enabled=active==null && !controlBusy,onClick={preview=plan}) { Text("View workout") }
                        if(plan.custom) {
                            OutlinedButton(enabled=active==null && !controlBusy,onClick={startActivity(Intent(this@MainActivity,WorkoutBuilderActivity::class.java).putExtra("workout",plan.id))}) { Text("Edit") }
                            OutlinedButton(enabled=active==null && !controlBusy,onClick={startActivity(Intent(this@MainActivity,WorkoutBuilderActivity::class.java).putExtra("workout",plan.id).putExtra("copy",true))}) { Text("Duplicate") }
                            TextButton(enabled=active==null && !controlBusy,onClick={deleting=plan}) { Text("Delete") }
                        } else {
                            OutlinedButton(enabled=active==null && !controlBusy && plan.maxMph>2.0,onClick={app.setPlannedMaxSpeed(plan.id,(plan.maxMph-0.5).coerceAtLeast(2.0))}) { Text("− Max") }
                            OutlinedButton(enabled=active==null && !controlBusy && plan.maxMph<10.0,onClick={app.setPlannedMaxSpeed(plan.id,(plan.maxMph+0.5).coerceAtMost(10.0))}) { Text("+ Max") }
                        }
                    }
                } }
            }
        }
        preview?.let { plan -> AlertDialog(containerColor=Surface,onDismissRequest={preview=null},title={Text(plan.name)},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("${plan.durationLabel()} · ${plan.steps.size} intervals · Max ${fmt(plan.maxMph,1)} mph",color=Green)
                WarmupOption(profile,enabled=active==null && !controlBusy)
                plan.steps.forEachIndexed { index,step ->
                    Text("${index+1}. ${step.name}",fontWeight=FontWeight.Bold)
                    Text((step.distanceMeters?.let { "${fmt(it/1609.344,2)} mi" } ?: "${step.seconds/60}m ${step.seconds%60}s")+
                        (step.startMph?.let { " · ${fmt(it,1)} mph" } ?: "")+
                        " · ${fmt(step.incline ?: plan.incline,1)}% incline"+
                        (step.hrLow?.let { " · $it–${step.hrHigh} bpm" } ?: ""),color=Muted)
                }
                Text("The belt starts after a 3-second countdown.",color=Muted)
            }
        },confirmButton={Button(enabled=active==null && !controlBusy,onClick={preview=null;app.startPlannedWorkout(plan);if(app.active.value!=null) onStarted()}) { Text("Start workout") }},dismissButton={TextButton(onClick={preview=null}) { Text("Back") }}) }
        deleting?.let { plan -> AlertDialog(containerColor=Surface,onDismissRequest={deleting=null},title={Text("Delete ${plan.name}?")},text={Text("This removes the saved template. Completed workout history stays intact.")},confirmButton={Button(onClick={app.deleteCustomWorkout(plan.id);deleting=null}) { Text("Delete workout") }},dismissButton={TextButton(onClick={deleting=null}) { Text("Cancel") }}) }
        val state by app.garmin.state.collectAsState()
        val account=state.accounts.firstOrNull { it.profileId==profile.id }
        if(!customLibrary) key(account?.email) {
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
                if(account==null || account.needsLogin) Text("Connect Garmin for this runner in Settings first.",color=Muted)
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
                Button(enabled=workout.executionIssue==null && workout.executableSteps.isNotEmpty(),onClick={app.savePlannedWorkout(workout)}) { Text("Save to Garmin imports") }
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
