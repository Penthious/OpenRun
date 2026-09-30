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
    private var guidance: TextView?=null
    private val adjustButtons=mutableListOf<Triple<Button,Boolean,Double>>()
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private val palette get()=runPalette(app.store.state.value.let { s -> s.profiles.firstOrNull { it.id==s.selectedId }?.colorScheme })
    private var displayedPalette=""
    private fun backdrop()=android.graphics.drawable.GradientDrawable().apply {
        setColor(palette.surface.toInt()); cornerRadius=dp(18).toFloat()
        setStroke(dp(1),Color.rgb(65,71,77))
    }
    private fun styledButton(title:String,danger:Boolean=false,action:()->Unit)=Button(this).apply {
        text=title; isAllCaps=false; textSize=16f; minWidth=dp(88); minimumHeight=dp(50)
        setPadding(dp(16),0,dp(16),0)
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.rgb(150,158,165),Color.WHITE)))
        backgroundTintList=android.content.res.ColorStateList.valueOf(if(danger) Color.rgb(174,57,47) else palette.selected.toInt())
        setOnClickListener { action() }
    }
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
                completed=true; idleCollapsed=true; pendingStart=null
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
        displayedPalette=palette.id
        if(app.active.value==null) stopSelf()
    }
    private fun showPanel() {
        removePanel()
        if(app.active.value==null) {
            showIdlePanel()
            return
        }
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(if(collapsed) 6 else 14),dp(if(collapsed) 2 else 8),dp(if(collapsed) 6 else 14),dp(if(collapsed) 2 else 8)); background=backdrop()
        }
        val top=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        stats=TextView(this).apply { setTextColor(Color.WHITE); textSize=if(collapsed) 19f else 24f; setPadding(dp(8),dp(8),dp(8),dp(8)) }.also {
            top.addView(it,if(collapsed) LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT) else LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        }
        root.addView(top)
        fun button(row:LinearLayout,title:String,danger:Boolean=false,action:()->Unit):Button = styledButton(title,danger,action).also {
            row.addView(it,LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(56)).apply { marginStart=dp(8) })
        }
        if(collapsed) {
            button(top,"Stop",true) { app.pauseWorkout() }
            button(top,"Expand") { collapsed=false; showPanel() }
        } else {
            button(top,"OpenRun") { returnToOpenRun() }
            button(top,"Collapse") { collapsed=true; choosingEnd=false; showPanel() }
            guidance=TextView(this).apply {
                setTextColor(palette.accent.toInt()); textSize=16f; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
                setPadding(dp(8),dp(2),dp(8),dp(6))
            }.also { root.addView(it) }
            val controls=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            if(choosingEnd) {
                controls.addView(TextView(this).apply { text="Finish workout?"; textSize=18f; setTextColor(Color.WHITE); setPadding(dp(8),0,dp(16),0) })
                button(controls,"Stop & save") { choosingEnd=false; app.endWorkout(); showPanel() }
                button(controls,"Discard") { choosingEnd=false; app.endWorkout(save=false); showPanel() }
                button(controls,"Keep going") { choosingEnd=false; showPanel() }
            } else {
                listOf(Triple("− Speed",true,-.1),Triple("+ Speed",true,.1),Triple("− Incline",false,-.5),Triple("+ Incline",false,.5)).forEach { (title,speed,delta) ->
                    val control=button(controls,title) { app.adjustManual(speed,delta) }
                    adjustButtons.add(Triple(control,speed,delta))
                }
                pauseButton=button(controls,"Pause") { if(app.paused.value) app.resumeWorkout() else app.pauseWorkout() }
                skipButton=button(controls,"Skip warm-up") { app.skipWarmup() }
                endButton=button(controls,"End") { choosingEnd=true; showPanel() }
            }
            // Stop stays fixed even when other controls need horizontal scrolling.
            val bottom=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            bottom.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=true; addView(controls) },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
            button(bottom,"STOP BELT",true) { app.pauseWorkout() }
            root.addView(bottom)
        }
        val params=WindowManager.LayoutParams(if(collapsed) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.END }
        try { window.addView(root,params); panel=root; updateText() } catch(_:Exception) { app.message.value="Overlay permission is required." }
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
        app.pendingHike.value?.let { listOf(it.hike.id,it.profileId,it.maxIncline) }, app.heart.bpm.value!=null, app.controlsVerified.value, app.controlBusy.value
    ).toString()

    private fun showIdlePanel() {
        idlePanel=true
        idleSignature=currentIdleSignature()
        val profile=app.store.state.value.profiles.firstOrNull { it.id==app.store.state.value.selectedId }
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(8),dp(14),dp(8)); background=backdrop()
        }
        fun label(text:String)=TextView(this).apply {
            this.text=text; setTextColor(Color.WHITE); textSize=16f; setPadding(10,6,10,6)
        }
        val buttons=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        fun button(title:String, enabled:Boolean=true, action:()->Unit) {
            buttons.addView(styledButton(title,action=action).apply { isEnabled=enabled },LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,dp(56)).apply { marginEnd=dp(8) })
        }
        val busy=app.controlBusy.value
        fun choose(title:String, action:()->Unit) {
            pendingStart=title to action
            showPanel()
        }
        if(idleCollapsed && pendingStart==null) {
            button("OpenRun") { returnToOpenRun() }
            button(if(completed) (if(saved) "Saved · Workouts" else "Discarded · Workouts") else "Workouts") { idleCollapsed=false; showPanel() }
        } else {
            root.addView(label(if(completed) {
                (if(saved) "Workout saved" else "Workout discarded")+" · "+(profile?.name ?: "Choose a runner in OpenRun")
            } else profile?.name ?: "Choose a runner in OpenRun"))
            val pending=pendingStart
            if(pending!=null) {
                root.addView(label("${pending.first} · 3-second countdown, then belt starts at 2 mph. Hikes set starting incline first unless warming up."))
                button("Start belt & workout",!busy && profile!=null) {
                    pendingStart=null
                    pending.second()
                    if(app.active.value==null) Toast.makeText(this,app.message.value ?: "Unable to start. Check your runner and connections in OpenRun.",Toast.LENGTH_LONG).show()
                    showPanel()
                }
                button("Cancel") { pendingStart=null; showPanel() }
            } else {
                app.pendingHike.value?.takeIf { it.profileId==profile?.id }?.let { selected ->
                    button("Start hike · ${selected.hike.name}",!busy && app.controlsVerified.value) {
                        choose(selected.hike.name) { app.startHike() }
                    }
                    button("Clear hike",!busy) { app.pendingHike.value=null;showPanel() }
                }
                button("Hikes",!busy) {
                    startActivity(Intent(this,OutdoorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    removePanel();if(app.active.value==null) stopSelf()
                }
                button("Quick start",!busy && profile!=null) { choose("Manual workout") { app.startWorkout() } }
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
                app.pendingHike.value!=null -> "Hike ready · speed stays manual; incline follows the trail. HR strap optional."
                app.heart.bpm.value==null -> "Connect your chest strap for Zone 2 and Garmin guided workouts."
                !app.controlsVerified.value -> "Verify controls in OpenRun → Settings for guided workouts."
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
        if(panel!=null && displayedPalette!=palette.id) { showPanel(); return }
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
        val elapsed=app.active.value?.durationSec ?: 0
        if(collapsed) { stats?.text=String.format(Locale.US,"♥ %s   ·   %d:%02d%s",hr,elapsed/60,elapsed%60,if(app.paused.value) " · Paused" else ""); return }
        val t=app.treadmill.telemetry.value
        val fresh=t.receivedAt>0 && android.os.SystemClock.elapsedRealtime()-t.receivedAt<5000
        val speed=if(fresh) t.mph?.let { String.format(Locale.US,"%.1f",it) } ?: "—" else "—"
        val incline=if(fresh) t.incline?.let { String.format(Locale.US,"%.1f",it) } ?: "—" else "—"
        val run=app.active.value
        val time=run?.durationSec ?: 0
        val targets=app.manualTargets.value
        val desiredSpeed=targets.lastOrNull { it is Target.Speed }?.value ?: t.mph
        val desiredIncline=targets.lastOrNull { it is Target.Incline }?.value ?: t.incline
        val canAdjust=fresh && run!=null && (!app.controlBusy.value || app.manualAdjusting.value) && !app.paused.value && (t.mph ?: 0.0)>=.2
        adjustButtons.forEach { (button,speedControl,delta) ->
            button.isEnabled=canAdjust && if(speedControl) {
                if(delta<0) (desiredSpeed ?: 0.0)>.2 else (desiredSpeed ?: app.manualMaxMph)<app.manualMaxMph
            } else {
                if(delta<0) (desiredIncline ?: app.manualMinIncline)>app.manualMinIncline else (desiredIncline ?: app.manualMaxIncline)<app.manualMaxIncline
            }
        }
        stats?.text=String.format(Locale.US,"%s mph    %s%% incline    %.2f mi    ♥ %s    %d:%02d%s",speed,incline,(run?.distanceMeters ?: 0.0)/1609.344,hr,time/60,time%60,if(app.paused.value) " · PAUSED" else "")
        guidance?.text=if(app.controlBusy.value) app.controlStatus.value else app.warmupStatus.value ?: app.hikeStatus.value ?: app.plannedStatus.value ?: app.zoneStatus.value
    }
    private fun removePanel() { panel?.let { runCatching { window.removeView(it) } }; panel=null; idlePanel=false; idleStatus=null; stats=null; guidance=null; adjustButtons.clear(); pauseButton=null; endButton=null; skipButton=null }
    override fun onDestroy() { scope.cancel(); removePanel(); super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null
}
