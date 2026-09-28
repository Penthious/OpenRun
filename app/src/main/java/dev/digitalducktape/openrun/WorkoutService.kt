package dev.digitalducktape.openrun

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.*
import java.util.Locale
import dev.digitalducktape.openrun.core.garmin.GarminScheduleRepository
import dev.digitalducktape.openrun.core.garmin.ScheduleEntry

class WorkoutService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var window: WindowManager
    private var panel: LinearLayout?=null
    private var stats: TextView?=null
    private var idleCollapsed=false
    private var idlePanel=false
    private var idleSignature=""
    private var pendingStart: Pair<String, () -> Unit>?=null
    private var idleStatus: TextView?=null
    private var collapsed=false
    private var completed=false
    private var saved=true
    private var choosingEnd=false
    private var pauseButton: Button?=null
    private var endButton: Button?=null
    private var skipButton: Button?=null
    private val app get()=application as OpenRunApplication
    override fun onCreate() {
        super.onCreate()
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("workout","Workout recording",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1,Notification.Builder(this,"workout").setContentTitle("OpenRun").setContentText("Tap to return to OpenRun").setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(open).build())
        window=getSystemService(WindowManager::class.java)
        scope.launch {
            var hadHr=false
            while(isActive) {
                val hr=app.heart.bpm.value
                if(hadHr && hr==null && app.active.value!=null) Toast.makeText(this@WorkoutService,"HR monitor disconnected. Keep using your current settings.",Toast.LENGTH_LONG).show()
                hadHr=hr!=null
                updateText(); delay(1000)
            }
        }
    }
    override fun onStartCommand(intent: Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            "show" -> { completed=false; choosingEnd=false; pendingStart=null; if(Settings.canDrawOverlays(this)) showPanel() }
            "record" -> { completed=false; choosingEnd=false; pendingStart=null; collapsed=false; if(panel!=null) showPanel() }
            "complete" -> {
                completed=true; idleCollapsed=false; pendingStart=null
                saved=intent.getBooleanExtra("saved",true); choosingEnd=false
                if(panel!=null) showPanel() else stopSelf()
            }
            "hide" -> removePanel()
            "stop" -> { removePanel(); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun returnToOpenRun() {
        startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        removePanel()
        if(app.active.value==null) stopSelf()
    }
    private fun showPanel() {
        removePanel()
        if(app.active.value==null) {
            showIdlePanel()
            return
        }
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(22,10,22,10); setBackgroundColor(Color.rgb(20,30,25)) }
        val label=TextView(this).apply { setTextColor(Color.WHITE); textSize=if(collapsed) 22f else 24f; setPadding(12,8,12,8) }
        stats=label
        row.addView(label,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        fun button(title:String,action:()->Unit) { row.addView(Button(this).apply { text=title; setOnClickListener { action() } }) }
        button("Stop") { app.pauseWorkout() }
        if(choosingEnd) {
            button("Stop & save") { choosingEnd=false; app.endWorkout(); showPanel() }
            button("End without saving") { choosingEnd=false; app.endWorkout(save=false); showPanel() }
            button("Keep going") { choosingEnd=false; showPanel() }
        } else if(!collapsed) {
            val pause=Button(this).apply { text="Pause"; setOnClickListener { if(app.paused.value) app.resumeWorkout() else app.pauseWorkout() } }
            pauseButton=pause; row.addView(pause)
            val skip=Button(this).apply { text="Skip warm-up"; setOnClickListener { app.skipWarmup() } }
            skipButton=skip; row.addView(skip)
            val end=Button(this).apply { text="End"; setOnClickListener { choosingEnd=true; showPanel() } }
            endButton=end; row.addView(end)
        }
        if(!collapsed) button("OpenRun") { returnToOpenRun() }
        if(!choosingEnd) button(if(collapsed) "Expand" else "Collapse") { collapsed=!collapsed; showPanel() }
        val params=WindowManager.LayoutParams(if(collapsed) 480 else WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.END }
        try { window.addView(row,params); panel=row; updateText() } catch(_:Exception) { app.message.value="Overlay permission is required." }
    }
    private fun todayWorkouts(): List<ScheduleEntry> {
        val state=app.store.state.value
        val profile=state.profiles.firstOrNull { it.id==state.selectedId } ?: return emptyList()
        val account=app.garmin.state.value.accounts.firstOrNull { it.profileId==profile.id } ?: return emptyList()
        val owner=GarminScheduleRepository.ownerKey(account.email)
        val today=java.time.LocalDate.now(java.time.ZoneId.of(profile.scheduleTimeZone)).toString()
        return state.schedules.firstOrNull { it.profileId==profile.id && it.accountKey==owner }?.entries.orEmpty()
            .filter { it.source.date==today && it.preview.executionIssue==null && it.preview.executableSteps.isNotEmpty() }
    }
    private fun currentIdleSignature(): String = listOf(
        app.store.state.value.selectedId, app.store.state.value.profiles, todayWorkouts(),
        app.heart.bpm.value!=null, app.controlsVerified.value, app.controlBusy.value
    ).toString()

    private fun showIdlePanel() {
        idlePanel=true
        idleSignature=currentIdleSignature()
        val profile=app.store.state.value.profiles.firstOrNull { it.id==app.store.state.value.selectedId }
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(12,6,12,6); setBackgroundColor(Color.rgb(20,30,25))
        }
        fun label(text:String)=TextView(this).apply {
            this.text=text; setTextColor(Color.WHITE); textSize=16f; setPadding(10,6,10,6)
        }
        val buttons=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        fun button(title:String, enabled:Boolean=true, action:()->Unit) {
            buttons.addView(Button(this).apply { text=title; isEnabled=enabled; setOnClickListener { action() } })
        }
        val busy=app.controlBusy.value
        fun choose(title:String, action:()->Unit) {
            pendingStart=title to action
            showPanel()
        }
        if(idleCollapsed && pendingStart==null) {
            button("Workouts · OpenRun") { idleCollapsed=false; showPanel() }
        } else {
            root.addView(label(if(completed) {
                (if(saved) "Workout saved" else "Workout discarded")+" · "+(profile?.name ?: "Choose a runner in OpenRun")
            } else profile?.name ?: "Choose a runner in OpenRun"))
            val pending=pendingStart
            if(pending!=null) {
                root.addView(label("${pending.first} · Belt starts at 2 mph after a 3-second countdown."))
                button("Start belt & workout",!busy && profile!=null) {
                    pendingStart=null
                    pending.second()
                    if(app.active.value==null) Toast.makeText(this,app.message.value ?: "Unable to start. Check your runner and connections in OpenRun.",Toast.LENGTH_LONG).show()
                    showPanel()
                }
                button("Cancel") { pendingStart=null; showPanel() }
            } else {
                button("Start workout",!busy && profile!=null) { choose("Manual workout") { app.startWorkout() } }
                button("Zone 2",!busy && profile!=null && app.controlsVerified.value && app.heart.bpm.value!=null) {
                    choose("Zone 2") { app.startWorkout(withZoneTwo=true) }
                }
                todayWorkouts().forEach { entry ->
                    val profileId=profile?.id ?: return@forEach
                    button("Today · ${entry.preview.name}",!busy && app.controlsVerified.value && app.heart.bpm.value!=null) {
                        choose(entry.preview.name) { app.startScheduledWorkout(profileId,entry) }
                    }
                }
                button("OpenRun") { returnToOpenRun() }
                button("Collapse") { idleCollapsed=true; showPanel() }
            }
            if(profile!=null) root.addView(CheckBox(this).apply {
                text="5-minute warm-up"; setTextColor(Color.WHITE); isChecked=profile.warmupEnabled; isEnabled=!busy
                setOnCheckedChangeListener { _, checked -> app.setWarmup(checked); idleSignature=currentIdleSignature() }
            })
            idleStatus=label(when {
                busy -> app.controlStatus.value
                app.heart.bpm.value==null -> "Connect your chest strap for Zone 2 and guided workouts."
                !app.controlsVerified.value -> "Verify controls in OpenRun → Connections for guided workouts."
                else -> "Choose your show, then start when ready."
            }).also { root.addView(it) }
        }
        // Horizontal scrolling keeps every scheduled option reachable on smaller screens.
        root.addView(HorizontalScrollView(this).apply { addView(buttons) })
        val params=WindowManager.LayoutParams(
            if(idleCollapsed && pendingStart==null) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT
        ).apply { gravity=Gravity.BOTTOM or Gravity.END }
        try { window.addView(root,params); panel=root }
        catch(_:Exception) { app.message.value="Overlay permission is required."; stopSelf() }
    }
    private fun updateText() {
        if(idlePanel) {
            if(app.active.value!=null || idleSignature!=currentIdleSignature()) {
                // Never keep a start confirmation when its runner or available options changed.
                pendingStart=null
                showPanel()
            }
            if(app.controlBusy.value) idleStatus?.text=app.controlStatus.value
            return
        }
        skipButton?.visibility=if(app.warmingUp.value) android.view.View.VISIBLE else android.view.View.GONE
        skipButton?.isEnabled=!app.controlBusy.value && !app.paused.value
        pauseButton?.text=if(app.paused.value) "Resume" else "Pause"
        pauseButton?.isEnabled=app.active.value!=null && (!app.paused.value || !app.controlBusy.value)
        endButton?.isEnabled=app.active.value!=null
        val hr=app.heart.bpm.value?.toString() ?: "—"
        if(collapsed) { stats?.text="♥ $hr BPM"; return }
        val t=app.treadmill.telemetry.value
        val fresh=t.receivedAt>0 && android.os.SystemClock.elapsedRealtime()-t.receivedAt<5000
        val speed=if(fresh) t.mph?.let { String.format(Locale.US,"%.1f",it) } ?: "—" else "—"
        val incline=if(fresh) t.incline?.let { String.format(Locale.US,"%.1f",it) } ?: "—" else "—"
        val run=app.active.value
        val time=run?.durationSec ?: 0
        stats?.text=String.format(Locale.US,"%s mph    %s%% incline    %.2f mi    ♥ %s    %d:%02d%s",speed,incline,(run?.distanceMeters ?: 0.0)/1609.344,hr,time/60,time%60,if(app.paused.value) " · PAUSED" else "") + "\n" + if(app.controlBusy.value) app.controlStatus.value else app.warmupStatus.value ?: app.plannedStatus.value ?: app.zoneStatus.value
    }
    private fun removePanel() { panel?.let { runCatching { window.removeView(it) } }; panel=null; idlePanel=false; idleStatus=null; stats=null; pauseButton=null; endButton=null; skipButton=null }
    override fun onDestroy() { scope.cancel(); removePanel(); super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null
}
