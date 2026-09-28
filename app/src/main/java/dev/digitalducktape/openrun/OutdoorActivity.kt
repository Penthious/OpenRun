package dev.digitalducktape.openrun

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.*
import dev.digitalducktape.openrun.TrailGpx.readBytesLimited

class OutdoorActivity: ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val app get()=application as OpenRunApplication
    private val library by lazy { HikeLibrary(this) }
    private var hikes by mutableStateOf<List<SavedHike>>(emptyList())
    private var selected by mutableStateOf<SavedHike?>(null)
    private var maxIncline by mutableStateOf(HikeLimits.MAX_INCLINE)
    private var showImportHelp by mutableStateOf(false)
    private var confirmStart by mutableStateOf(false)
    private var scanner:Job?=null
    private val scanned=mutableMapOf<String,Pair<Long,Long>>()
    private var scanEnabled by mutableStateOf(false)
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanEnabled=granted
        if(granted) refreshLibrary() else message="Downloads access was not allowed. You can still import with the file picker."
    }
    private var preview by mutableStateOf<TrailPreview?>(null)
    private var message by mutableStateOf("Download a GPX Track from AllTrails, then choose it from Downloads.")
    private var busy by mutableStateOf(false)
    private val stored get() = File(filesDir,"outdoor-preview.gpx")
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importRoute(it) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val active by app.active.collectAsState()
            val controlling by app.controlBusy.collectAsState()
            val pending by app.pendingHike.collectAsState()
            val status by app.hikeStatus.collectAsState()
            val notice by app.message.collectAsState()
            val saved by app.store.state.collectAsState()
            val profile=saved.profiles.firstOrNull { it.id==saved.selectedId }

            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFB7EF79))) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(32.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                            Text("Outdoor Trails",fontSize=30.sp)
                            TextButton(onClick={startActivity(Intent(this@OutdoorActivity,MainActivity::class.java));finish()}) { Text("Back to OpenRun") }
                        }
                        Text("Choose a hike now, or queue it and start from the floating menu over Plex.")
                        if(active!=null) {
                            Text(status ?: "Workout active",fontSize=20.sp)
                            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                Button(onClick={app.pauseWorkout()}) { Text("Stop / pause") }
                                Button(onClick={openPlex()}) { Text("Watch Plex") }
                            }
                        }
                        notice?.let { Text(it) }
                        if(confirmStart) AlertDialog(onDismissRequest={confirmStart=false},title={Text("Start ${selected?.name}?")},
                            text={Text("After the 3-second countdown, incline is set to the trail’s starting grade before the belt starts at 2 mph (unless warming up). Incline follows terrain up to ${maxIncline.toInt()}%. Downhill sections follow terrain down to −6%. You control speed.")},
                            confirmButton={TextButton(onClick={confirmStart=false;selected?.let { if(app.queueHike(it,maxIncline)) { app.startHike();if(app.active.value!=null) { startActivity(Intent(this@OutdoorActivity,MainActivity::class.java));finish() } } }}) { Text("Start belt & hike") }},
                            dismissButton={TextButton(onClick={confirmStart=false}) { Text("Cancel") }})
                        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            Button(onClick={browse()}) { Text("Browse AllTrails") }
                            Button(enabled=!busy,onClick={picker.launch("*/*")}) { Text("Import file") }
                            if(Build.VERSION.SDK_INT<=28 && !scanEnabled) Button(onClick={permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)}) { Text("Enable Downloads scan") }
                            TextButton(enabled=!busy,onClick={scanned.clear();refreshLibrary()}) { Text("Scan now") }
                        }
                        Text(message)
                        TextButton(onClick={showImportHelp=!showImportHelp}) { Text(if(showImportHelp) "Hide import help" else "How to add hikes") }
                        if(showImportHelp) Text("In AllTrails: sign in → choose a trail → Hit the trail → Export map file → GPX Track. You can also open a downloaded GPX with OpenRun.")
                        Text(if(scanEnabled) "Downloads are checked automatically while this view is open and when you return." else "Use Import file to add GPX hikes.")
                        pending?.let { Text("Ready for ${profile?.name ?: "runner"}: ${it.hike.name} · start from the Plex overlay",color=Color(0xFFB7EF79)) }
                        hikes.forEach { savedHike ->
                            OutlinedButton(onClick={selected=savedHike;preview=savedHike.route},modifier=Modifier.fillMaxWidth()) {
                                Text("${savedHike.name} · %.2f mi%s".format(savedHike.route.distanceMeters/1609.344,if(savedHike.route.hasElevation) "" else " · missing elevation"))
                            }
                        }
                        if(hikes.isEmpty()) Text("No hikes yet. Download a GPX Track from AllTrails to get started.")
                        selected?.let { chosen ->
                            if(active==null) {
                                Text("Maximum incline: ${maxIncline.toInt()}% · decline down to −6% · start speed 2 mph")
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    listOf(3.0,5.0,10.0,15.0,20.0,30.0,40.0).forEach { cap ->
                                        FilterChip(selected=maxIncline==cap,onClick={maxIncline=cap},label={Text("${cap.toInt()}%")})
                                    }
                                }
                                Row {
                                    Checkbox(checked=profile?.warmupEnabled==true,onCheckedChange={app.setWarmup(it)},enabled=profile!=null && !controlling)
                                    Text("Optional 5-minute warm-up (does not count toward trail progress)")
                                }
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Button(enabled=chosen.route.hasElevation && !controlling,onClick={confirmStart=true}) { Text("Start hike") }
                                    Button(enabled=chosen.route.hasElevation && !controlling,onClick={if(app.queueHike(chosen,maxIncline)) openPlex()}) { Text("Queue hike & open Plex") }
                                }
                            }
                        }
                        preview?.let { route ->
                            if(!route.hasElevation) Text("Elevation is missing. This file cannot drive automatic incline.")
                            selected?.let { chosen ->
                                HikeMap(chosen,if(app.hike?.selection?.hike?.id==chosen.id) app.hikeDistance else 0.0,app.paused.collectAsState().value,app.warmingUp.collectAsState().value)
                            }

                        }
                    }
                }
            }
        }
        if(intent.action==Intent.ACTION_VIEW && intent.data!=null) importRoute(intent.data!!)
    }
    override fun onResume() {
        super.onResume()
        scanEnabled=Build.VERSION.SDK_INT<=28 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
        scanner?.cancel()
        scanner=scope.launch { while(isActive) { refreshLibrary();delay(5000) } }
    }
    override fun onPause() { scanner?.cancel();super.onPause() }
    private fun refreshLibrary() {
        if(busy) return
        busy=true
        scope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                val errors=mutableListOf<String>()
                if(stored.exists()) {
                    library.import(stored.inputStream().use { it.readBytesLimited() },"Previously imported hike")
                    stored.delete()
                }
                if(scanEnabled) {
                    val downloads=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    downloads.listFiles().orEmpty().filter { it.isFile && it.extension.equals("gpx",true) }.forEach { file ->
                        val stamp=file.lastModified() to file.length()
                        if(scanned[file.absolutePath]!=stamp) {
                            runCatching { library.import(file.inputStream().use { it.readBytesLimited() },file.name) }
                                .onSuccess { scanned[file.absolutePath]=stamp }
                                .onFailure { errors.add("${file.name}: ${it.message}") }
                        }
                    }
                }
                library.list() to errors
            } }
            result.onSuccess { (found,errors) ->
                hikes=found
                if(selected==null) { selected=app.hike?.selection?.hike ?: app.pendingHike.value?.hike ?: found.firstOrNull();preview=selected?.route }
                message=if(errors.isEmpty()) "${found.size} saved hikes" else "${found.size} saved hikes. Could not read: ${errors.joinToString().take(300)}"
            }.onFailure { message="Could not scan hikes: ${it.message}" }
            busy=false
        }
    }

    private fun importRoute(uri:Uri) {
        if(busy) return
        if(uri.scheme !in listOf("content","file")) { message="Choose a downloaded GPX file."; return }
        busy=true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val data=contentResolver.openInputStream(uri)?.use { it.readBytesLimited() } ?: error("Could not open file.")
                    val name=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                        if(cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "Imported hike.gpx"
                    library.import(data,name)
                }
            }.onSuccess { selected=it;preview=it.route;hikes=withContext(Dispatchers.IO) { library.list() };message="Hike saved to your library." }
             .onFailure { message="Import failed: ${it.message ?: "Choose a valid GPX Track."}" }
            busy=false
        }
    }
    private fun openPlex() {
        if(!Settings.canDrawOverlays(this)) {
            message="Enable display over other apps to start the selected hike from Plex."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")));return
        }
        val launch=packageManager.getLaunchIntentForPackage("com.plexapp.android")
            ?: packageManager.getLaunchIntentForPackage("com.netflix.mediaclient")
        if(launch==null) { message="Plex is not installed. The hike is queued in the OpenRun overlay.";return }
        startForegroundService(Intent(this,WorkoutService::class.java).setAction("show"))
        startActivity(launch)
    }
    private fun browse() {
        if(!Settings.canDrawOverlays(this)) {
            message="Enable OpenRun’s display-over-other-apps permission to keep a return shortcut in the browser."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            return
        }
        val launch=Intent(Intent.ACTION_VIEW,Uri.parse("https://www.alltrails.com"))
        val browser = listOf("org.mozilla.firefox", "net.slions.fulguris.full.fdroid")
            .firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
        browser?.let { launch.setPackage(it) }
        try {
            startForegroundService(Intent(this,WorkoutService::class.java).setAction("show"))
            startActivity(launch)
        } catch(e:android.content.ActivityNotFoundException) { message="No compatible browser installed." }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
