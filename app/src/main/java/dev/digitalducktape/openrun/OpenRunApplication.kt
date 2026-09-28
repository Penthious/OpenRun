package dev.digitalducktape.openrun

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import dev.digitalducktape.openrun.core.data.*
import dev.digitalducktape.openrun.core.garmin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OpenRunApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val maintenance = MutableStateFlow(false)
    lateinit var updates: AppUpdates
    lateinit var store: RunStore
    lateinit var garmin: GarminSyncManager
    lateinit var schedule: GarminScheduleRepository
    lateinit var treadmill: Treadmill
    lateinit var heart: HeartRate
    val pendingHike = MutableStateFlow<PendingHike?>(null)
    val hikeStatus = MutableStateFlow<String?>(null)
    var hike: HikeGuide? = null; private set
    private var hikeDistanceOffset=0.0
    val hikeDistance get()=if(warmup!=null) 0.0 else ((active.value?.distanceMeters ?: 0.0)-hikeDistanceOffset).coerceAtLeast(0.0)
    val active = MutableStateFlow<Ride?>(null)
    val paused = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private val glass by lazy { GlassOsWorkout(this) }
    val controls = TreadmillControl(stopWorkout={ end -> glass.stop(end) })
    val consoleStatus = MutableStateFlow("Checking console connection…")
    val zone = ZoneTwo()
    var planned: PlannedWorkout? = null; private set
    val plannedStatus = MutableStateFlow<String?>(null)
    val plannedName = MutableStateFlow<String?>(null)
    private var guideFinishHandled = false
    private var warmup: WarmupGuide? = null
    val warmupStatus = MutableStateFlow<String?>(null)
    val warmingUp = MutableStateFlow(false)
    private var zoneAfterWarmup=false
    private val paceLock=Mutex()
    val paceStatus=MutableStateFlow<Map<Long,String>>(emptyMap())

    val manualMaxMph get() = planned?.workout?.maxMph ?: 4.0
    val manualMinIncline get() = if(hike!=null) HikeLimits.MIN_INCLINE else 0.0
    val manualMaxIncline get() = hike?.selection?.maxIncline ?: if(planned!=null) 3.0 else 20.0
    val controlBusy = MutableStateFlow(false)
    val manualAdjusting = MutableStateFlow(false)
    val manualTargets = MutableStateFlow<List<Target>>(emptyList())
    val controlStatus = MutableStateFlow("Ready · tap a control to move the treadmill")
    val zoneStatus = MutableStateFlow("Automatic adjustments off")
    val zoneEnabled = MutableStateFlow(false)
    val controlsVerified = MutableStateFlow(false)
    private var controlJob: Job? = null
    private var controlGeneration = 0L
    private var resumeMph = 2.0
    private var resumeZone = false
    private var recording: Job? = null
    private var lastMeter: Double? = null
    override fun onCreate() {
        super.onCreate()
        SettingsAccessService.startIfAuthorized(this)
        store = RunStore(this)
        store.update { s -> s.copy(rides = s.rides.map { if (it.status == "recording") it.copy(status = "interrupted") else it }) }
        val pacePrefs=getSharedPreferences("controls",MODE_PRIVATE)
        if(!pacePrefs.getBoolean("base_cap_v2",false)) {
            store.update { state -> state.copy(workouts=state.workouts.map { if(it.name.contains("base",true) && it.maxMph==4.0) it.copy(maxMph=6.0) else it }) }
            pacePrefs.edit().putBoolean("base_cap_v2",true).apply()
        }
        treadmill = Treadmill(scope); heart = HeartRate(this,scope)
        garmin = GarminSyncManager(GarminStore(this), object : GarminRideSource {
            override suspend fun rides() = store.state.value.rides.filter { it.status == "complete" }
            override suspend fun samples(rideId: Long) = store.state.value.rides.firstOrNull { it.id == rideId }?.samples.orEmpty()
        }, GarminApi(), { GarminSyncWorker.enqueue(this) })
        schedule = GarminScheduleRepository(
            accountKey = { id -> garmin.state.value.accounts.firstOrNull { it.profileId==id }?.let { GarminScheduleRepository.ownerKey(it.email) } },
            cached = { store.state.value.schedules },
            save = { cache -> store.update { state -> state.copy(schedules=state.schedules.filterNot { it.profileId==cache.profileId } + cache) } },
            list = { id -> garmin.plannedWorkouts(id, java.time.LocalDate.now(java.time.ZoneId.of(store.state.value.profiles.first { it.id==id }.scheduleTimeZone))) }, preview = { id, workout -> garmin.previewWorkout(id,workout) },
        )
        GarminSyncWorker.scheduleRecovery(this)
        GarminHistoryWorker.schedule(this)
        updates = AppUpdates(this)
        AppUpdateWorker.schedule(this)
        treadmill.start()
        scope.launch {
            try { val state=glass.state(); consoleStatus.value="Console connected · workout state $state"; android.util.Log.i("OpenRunControl","Read-only console connection verified; workout state=$state") }
            catch(_:Exception) { consoleStatus.value="Console workout connection unavailable"; android.util.Log.w("OpenRunControl","Read-only console connection failed") }
        }
        controlsVerified.value = getSharedPreferences("controls", MODE_PRIVATE).getBoolean("verified_workout_stop_v2",false)
        scope.launch {
            while(isActive) {
                delay(1000)
                if(active.value!=null && warmup!=null) {
                    val guide=warmup!!; val t=treadmill.telemetry.value
                    val target=guide.tick(SystemClock.elapsedRealtime(),t,heart.bpm.value,isFresh(t),paused.value,controlBusy.value)
                    warmupStatus.value=guide.status
                    if(guide.finished && !controlBusy.value) finishWarmup()
                    else if(target!=null && !controlBusy.value) performControl {
                        if(applyTarget(target)) guide.confirmed(SystemClock.elapsedRealtime(),treadmill.telemetry.value)
                    }
                } else if(active.value!=null && hike!=null) {
                    val guide=hike!!;val t=treadmill.telemetry.value
                    val target=guide.tick(SystemClock.elapsedRealtime(),(active.value!!.distanceMeters-hikeDistanceOffset).coerceAtLeast(0.0),t,isFresh(t),paused.value,controlBusy.value)
                    hikeStatus.value=guide.status
                    if(guide.complete && !guideFinishHandled) {
                        guideFinishHandled=true;pauseWorkout()
                        message.value="Hike complete. Belt stopping; choose End to save or discard."
                    } else if(target!=null) performControl {
                        if(applyTarget(target)) guide.confirmed(SystemClock.elapsedRealtime(),treadmill.telemetry.value.incline)
                    }
                } else if(active.value!=null && planned!=null) {
                    val guide=planned!!
                    val t=treadmill.telemetry.value
                    val target=guide.tick(SystemClock.elapsedRealtime(),t,heart.bpm.value,isFresh(t),paused.value,controlBusy.value)
                    plannedStatus.value=guide.status
                    if(guide.complete && !guideFinishHandled) {
                        guideFinishHandled=true
                        pauseWorkout()
                        message.value="Workout guide complete. The belt is stopping. Choose End workout to save or discard."
                    } else if(target!=null && !controlBusy.value) performControl {
                        if(applyTarget(target)) guide.confirmed(SystemClock.elapsedRealtime(),treadmill.telemetry.value)
                    }
                } else if(active.value!=null && !controlBusy.value) {
                    val t=treadmill.telemetry.value
                    val target=zone.tick(SystemClock.elapsedRealtime(),t,heart.bpm.value,isFresh(t),paused.value)
                    zoneStatus.value=zone.status; zoneEnabled.value=zone.enabled
                    if(target!=null) performControl { applyTarget(target) }
                }
            }
        }
    }
    fun selectProfile(id: Long) {
        if (active.value != null || controlBusy.value) return
        val profile = store.state.value.profiles.firstOrNull { it.id == id } ?: return
        pendingHike.value=null
        store.update { it.copy(selectedId = id) }; heart.connect(profile.strapAddress)
    }
    fun pair(address: String, name: String) {
        if(active.value!=null || controlBusy.value) return
        val id = store.state.value.selectedId ?: return
        store.update { s -> s.copy(profiles = s.profiles.map { if(it.id == id) it.copy(strapAddress = address, strapName = name) else it }) }
        heart.connect(address)
    }
    fun startRecording(workout: SavedWorkout? = null, hikeName:String? = null) {
        if (active.value != null || maintenance.value) return
        val id = store.state.value.selectedId ?: return
        val t = treadmill.telemetry.value
        if (t.receivedAt == 0L || SystemClock.elapsedRealtime()-t.receivedAt > 5000) { message.value = "Connect NordicFTMS before recording."; return }
        val now = System.currentTimeMillis()
        val ride = Ride(maxOf(now, (store.state.value.rides.maxOfOrNull { it.id } ?: 0) + 1),id,now,descentMeters=0.0,hikeName=hikeName,plannedWorkout=workout,utcOffsetSeconds=java.time.ZoneId.of(store.state.value.profiles.first { it.id==id }.scheduleTimeZone).rules.getOffset(java.time.Instant.ofEpochMilli(now)).totalSeconds)
        store.save(ride); active.value = ride; paused.value = false; lastMeter = t.meters
        recording = scope.launch {
            var activeMillis = 0L
            var previous = SystemClock.elapsedRealtime()
            while(isActive) {
                delay(1000)
                val tick = SystemClock.elapsedRealtime(); val dt = tick-previous; previous=tick
                val current = active.value ?: break
                val raw = treadmill.telemetry.value
                val fresh = raw.receivedAt > 0 && tick-raw.receivedAt <= 5000
                val measured = raw.meters.takeIf { fresh }
                val delta = sessionDistanceDelta(lastMeter,measured,dt)
                lastMeter = measured
                if (paused.value) continue
                activeMillis += dt
                val distance = current.distanceMeters + delta
                val ascent = current.ascentMeters + delta * ((raw.incline.takeIf { fresh } ?: 0.0).coerceAtLeast(0.0) / 100.0)
                val descent = (current.descentMeters ?: 0.0) + delta * (-(raw.incline.takeIf { fresh } ?: 0.0)).coerceAtLeast(0.0) / 100.0
                val elapsed = (activeMillis / 1000).toInt()
                val sample = RideSample(System.currentTimeMillis(),elapsed,distance,raw.mph?.takeIf { fresh }?.times(0.44704),raw.incline.takeIf { fresh },heart.bpm.value,ascent,planned?.index?.takeIf { planned?.complete == false && warmup==null },warmup!=null,descentMeters=descent)
                val next = current.copy(durationSec=elapsed,distanceMeters=distance,ascentMeters=ascent,descentMeters=descent,samples=current.samples+sample)
                active.value=next
                if (elapsed % 5 == 0) withContext(Dispatchers.IO) { store.save(next) }
            }
        }
    }
    private fun isFresh(t: Telemetry) = t.receivedAt>0 && SystemClock.elapsedRealtime()-t.receivedAt<=5000
    fun verifyControls(verified: Boolean) {
        if(active.value!=null || controlBusy.value) return
        controlsVerified.value=verified
        getSharedPreferences("controls",MODE_PRIVATE).edit().putBoolean("verified_workout_stop_v2",verified).apply()
        if(!verified) disableZone()
    }
    fun disableZone() { zone.disable(); zoneEnabled.value=false; zoneStatus.value=zone.status; resumeZone=false }
    fun enableZone() {
        if(controlBusy.value || warmup!=null) return
        enableZoneWhenReady()
    }
    private fun enableZoneWhenReady(warmedUp:Boolean=false) {
        if(planned!=null || hike!=null) { message.value="End the guided workout before enabling Zone 2."; return }
        if(!controlsVerified.value || active.value==null || paused.value) return
        val t=treadmill.telemetry.value
        if(!isFresh(t) || t.mph==null || t.mph<0.2 || t.mph>4.05 || t.incline==null || t.incline !in 0.0..20.0) {
            message.value="Start walking within 2–4 mph and 0–20% incline before enabling Zone 2."; return
        }
        if(heart.bpm.value==null) { message.value="Connect your chest strap before enabling Zone 2."; return }
        zone.enable(SystemClock.elapsedRealtime(),warmedUp); zoneEnabled.value=true; zoneStatus.value=zone.status
    }
    private fun performControl(preempt: Boolean=false, block: suspend ()->Unit) {
        if((controlBusy.value || maintenance.value) && !preempt) return
        if(preempt) {
            manualTargets.value=emptyList(); manualAdjusting.value=false
            controlJob?.cancel()
        }
        val generation=++controlGeneration
        controlBusy.value=true
        controlJob=scope.launch {
            try { block() }
            catch(e: TimeoutCancellationException) { controlFailed() }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { controlFailed() }
            finally { if(generation==controlGeneration) controlBusy.value=false }
        }
    }
    private fun controlFailed() {
                hike?.halt();hikeStatus.value=hike?.status
                warmup?.halt(); warmupStatus.value=warmup?.status
                planned?.halt("Command failed · guide paused; use physical controls")
                plannedStatus.value=planned?.status
                disableZone()
                controlStatus.value="Command not confirmed · use physical controls"
                message.value="The treadmill did not confirm the requested setting. Automatic adjustments are off. Use the physical Stop button if the belt is moving."
    }
    private suspend fun applyTarget(target: Target, endWorkout: Boolean=false): Boolean {
        val now=SystemClock.elapsedRealtime()
        val origin=treadmill.telemetry.value
        if(planned!=null && !(target is Target.Speed && target.value==0.0)) {
            check(isFresh(origin) && origin.incline!=null && origin.incline in 0.0..3.0) { "Running incline or connection is outside limits" }
            check(target.value.isFinite() && if(target is Target.Speed) target.value in 0.2..planned!!.workout.maxMph else target.value in 0.0..3.0)
        }
        controlStatus.value=when(target) { is Target.Speed -> if(target.value==0.0) "Stopping belt…" else "Setting speed to ${target.value} mph…"; is Target.Incline -> "Setting incline to ${target.value}%…" }
        check(target.value.isFinite() && when(target) {
            is Target.Speed -> target.value == 0.0 || target.value in 0.2..manualMaxMph
            is Target.Incline -> target.value in manualMinIncline..manualMaxIncline
        })
        controls.send(target,endWorkout)
        // A protocol acknowledgment is not proof that GlassOS applied the command.
        val reached=withTimeout(if(target is Target.Incline) 60_000L else 25_000L) {
            var matches=0
            var previousFrame=now
            while(matches<3) {
                delay(500)
                val t=treadmill.telemetry.value
                if(isFresh(t) && !(target is Target.Speed && target.value==0.0) && target.overridden(origin,t,ignoreRunningSpeedChange=hike!=null && warmup==null && target is Target.Incline)) {
                    if((t.mph ?: 0.0)<0.2) disableZone() else zone.manual(SystemClock.elapsedRealtime())
                    zoneStatus.value=zone.status
                    hike?.manual(target,SystemClock.elapsedRealtime()); if(warmup==null) planned?.manual(); warmup?.manual()
                    if((t.mph ?: 0.0)<0.2) { warmup?.halt() }; if((t.mph ?: 0.0)<0.2) planned?.halt("Belt stopped · pause and resume to continue guide")
                    return@withTimeout false
                }
                if(!isFresh(t) || !target.matches(t)) matches=0
                else if(t.receivedAt>previousFrame) matches++
                previousFrame=maxOf(previousFrame,t.receivedAt)
            }
            true
        }
        if(!reached) { controlStatus.value="Manual change detected · holding settings"; return false }
        controlStatus.value=if(target is Target.Speed && target.value==0.0) "Belt stopped" else "Setting confirmed by treadmill"
        return true
    }
    fun adjustManual(speed: Boolean, delta: Double) {
        val observed=if(speed) treadmill.telemetry.value.mph else treadmill.telemetry.value.incline
        val base=manualTargets.value.lastOrNull { (it is Target.Speed)==speed }?.value ?: observed ?: return
        val next=kotlin.math.round((base+delta)*10)/10
        manualTarget(if(speed) Target.Speed(next.coerceIn(0.0,manualMaxMph)) else Target.Incline(next.coerceIn(manualMinIncline,manualMaxIncline)))
    }
    fun manualTarget(target: Target) {
        if(target is Target.Speed && target.value==0.0) { pauseWorkout(); return }
        if((controlBusy.value && !manualAdjusting.value) || paused.value) return
        if(!target.value.isFinite() || (target is Target.Incline && target.value !in manualMinIncline..manualMaxIncline) || (target is Target.Speed && target.value !in 0.2..manualMaxMph)) return
        hike?.manual(target,SystemClock.elapsedRealtime()); if(warmup==null) planned?.manual(); warmup?.manual()
        if(!isFresh(treadmill.telemetry.value)) { message.value="Wait for the treadmill connection before adjusting."; return }
        if(target is Target.Speed && (treadmill.telemetry.value.mph ?: 0.0)<0.2) {
            message.value="Use Start workout or Resume to start the belt."; return
        }
        zone.manual(SystemClock.elapsedRealtime())
        zoneStatus.value=zone.status
        manualTargets.value=coalesceManualTarget(manualTargets.value,target)
        if(manualAdjusting.value) return
        manualAdjusting.value=true
        performControl {
            val generation=controlGeneration
            try {
                while(manualTargets.value.isNotEmpty()) {
                    val next=manualTargets.value.first()
                    val current=treadmill.telemetry.value
                    check(isFresh(current) && !paused.value && (current.mph ?: 0.0)>=0.2) { "Manual adjustment no longer safe" }
                    zone.manual(SystemClock.elapsedRealtime())
                    zone.commanded(next,current,SystemClock.elapsedRealtime())
                    if(!applyTarget(next)) break
                    // Keep a newer target for this axis if the user tapped during confirmation.
                    manualTargets.value=manualTargets.value.filterNot { it === next }
                }
            } finally {
                if(generation==controlGeneration) {
                    manualTargets.value=emptyList(); manualAdjusting.value=false
                }
            }
        }
    }
    fun startWorkout(withZoneTwo: Boolean = false) {
        if(active.value!=null || controlBusy.value) return
        if(withZoneTwo && !controlsVerified.value) { message.value="Verify treadmill controls under Connections first."; return }
        if(withZoneTwo && heart.bpm.value==null) { message.value="Connect your chest strap before starting Zone 2."; return }
        val t=treadmill.telemetry.value
        if(!isFresh(t) || t.incline==null || t.incline !in 0.0..20.0) { message.value="Connect the treadmill and set incline between 0% and 20% first."; return }
        val addWarmup=store.state.value.profiles.firstOrNull { it.id==store.state.value.selectedId }?.warmupEnabled==true
        if(addWarmup && t.incline>3.0) { message.value="Set incline to 0–3% for the warm-up, or turn warm-up off."; return }
        // Record before the belt moves; a failed start remains available to end/save.
        startRecording()
        if(active.value==null) return
        startForegroundService(Intent(this,WorkoutService::class.java).setAction("record"))
        disableZone()
        paused.value=true
        if(addWarmup) beginWarmup(3.5,if(withZoneTwo) 140 else null,withZoneTwo)
        performControl {
            controlStatus.value="Starting at 2 mph in 3 seconds…"; delay(3000)
            glass.prepareToRun()
            if(!applyTarget(Target.Speed(2.0))) { warmup?.halt(); return@performControl }
            paused.value=false
            warmup?.resume(SystemClock.elapsedRealtime())
            if(withZoneTwo && warmup==null) enableZoneWhenReady(warmedUp=true)
        }
    }
    private fun paceOwner(profileId:Long)=garmin.state.value.accounts.firstOrNull { it.profileId==profileId }?.let { GarminScheduleRepository.ownerKey(it.email) } ?: "local"
    fun preparePlan(plan:SavedWorkout):SavedWorkout = AdaptivePace.prepare(plan,store.state.value.paceHistory.firstOrNull { it.profileId==plan.profileId && it.accountKey==paceOwner(plan.profileId) })
    fun scheduledPlan(profileId: Long, entry: ScheduleEntry): SavedWorkout {
        val existing=store.state.value.workouts.firstOrNull { it.profileId==profileId && it.garminSource==entry.preview.source }
        return preparePlan(existing?.copy(name=entry.preview.name,steps=entry.preview.executableSteps)
            ?: SavedWorkout(java.util.UUID.randomUUID().toString(),profileId,entry.preview.name,entry.preview.executableSteps,
                maxMph=if(entry.preview.name.contains("tempo",ignoreCase=true)) 10.0 else 6.0,garminSource=entry.preview.source))
    }
    suspend fun refreshPaces(profileId:Long, localOnly:Boolean=false):Boolean = withContext(Dispatchers.IO) {
        paceLock.withLock {
            val owner=paceOwner(profileId)
            val now=System.currentTimeMillis()
            val old=store.state.value.paceHistory.firstOrNull { it.profileId==profileId && it.accountKey==owner }
            var remote=old?.segments.orEmpty().filter { it.activity.startsWith("garmin:") }
            var fetched=old?.fetchedAt ?: 0L
            var error:String?=null
            if(!localOnly && owner!="local" && now-fetched>=7L*24*60*60*1000) {
                paceStatus.value=paceStatus.value+(profileId to "Checking recent Garmin running history…")
                try { remote=garmin.history(profileId); fetched=now }
                catch(e:CancellationException) { throw e }
                catch(e:Exception) { error=(e as? GarminFailure)?.userMessage ?: "History refresh failed; keeping the previous pace model." }
            }
            if(paceOwner(profileId)!=owner) return@withLock false
            val state=store.state.value
            val rides=state.rides.filter { it.profileId==profileId && it.status=="complete" && it.hikeName==null && it.startedAt>now-AdaptivePace.AGE_MS }
            remote=remote.filterNot { segment -> rides.any { segment.timestamp in it.startedAt..(it.samples.lastOrNull()?.timestamp ?: it.startedAt) } }
            val merged=(remote+rides.flatMap { AdaptivePace.local(it) }).filter { it.timestamp>=now-AdaptivePace.AGE_MS }.sortedBy { it.timestamp }
            val ranges=(state.workouts.filter { it.profileId==profileId }.flatMap { it.steps }+
                state.schedules.filter { it.profileId==profileId && it.accountKey==owner }.flatMap { it.entries }.flatMap { it.preview.executableSteps })
                .mapNotNull { step -> step.hrLow?.let { it to step.hrHigh!! } }.distinct()
            val changed=merged!=old?.segments
            val learned=ranges.mapNotNull { (low,high) ->
                val previous=old?.learned?.firstOrNull { it.low==low && it.high==high }?.takeIf { now-old.updatedAt<AdaptivePace.AGE_MS }
                if(!changed && previous!=null) previous else AdaptivePace.learn(low,high,merged,previous,now)
            }
            val next=PaceHistory(profileId,owner,fetched,merged,learned,if(changed) now else old!!.updatedAt)
            store.update { it.copy(paceHistory=it.paceHistory.filterNot { h -> h.profileId==profileId }+next) }
            paceStatus.value=paceStatus.value+(profileId to (error ?: if(learned.isEmpty()) "Learning pace · need steady segments from at least two comparable runs" else "Adaptive pace ready · ${learned.maxOf { it.runs }} comparable runs · weekly Garmin refresh"))
            error==null
        }
    }
    fun setWarmup(enabled:Boolean) {
        if(active.value!=null || controlBusy.value) return
        val owner=store.state.value.selectedId ?: return
        store.update { s -> s.copy(profiles=s.profiles.map { if(it.id==owner) it.copy(warmupEnabled=enabled) else it }) }
    }
    private fun beginWarmup(pace:Double,ceiling:Int?,zoneAfter:Boolean=false) {
        warmup=WarmupGuide(pace,ceiling); warmingUp.value=true; warmupStatus.value="Warm-up · 5:00"; zoneAfterWarmup=zoneAfter
    }
    fun skipWarmup() {
        if(warmup==null || controlBusy.value || paused.value) return
        if(warmup?.halted==true) { message.value="Pause and resume after checking the connection before skipping warm-up."; return }
        warmup?.skip(); finishWarmup()
    }
    private fun finishWarmup() {
        warmup=null; warmingUp.value=false; warmupStatus.value=null
        hikeDistanceOffset=active.value?.distanceMeters ?: 0.0
        hike?.resume(SystemClock.elapsedRealtime())
        planned?.resume(SystemClock.elapsedRealtime())
        if(zoneAfterWarmup) { zoneAfterWarmup=false; enableZoneWhenReady(warmedUp=true) }
    }
    fun setScheduledMax(profileId:Long,entry:ScheduleEntry,max:Double) {
        if(active.value!=null || controlBusy.value || profileId!=store.state.value.selectedId || !max.isFinite() || max !in 2.0..10.0) return
        val plan=scheduledPlan(profileId,entry).copy(maxMph=max)
        store.update { s -> s.copy(workouts=s.workouts.filterNot { it.profileId==profileId && it.garminSource==plan.garminSource }+plan) }
    }
    fun startScheduledWorkout(profileId: Long, entry: ScheduleEntry) {
        if(store.state.value.selectedId!=profileId) return
        val account=garmin.state.value.accounts.firstOrNull { it.profileId==profileId } ?: return
        val cache=store.state.value.schedules.firstOrNull { it.profileId==profileId && it.accountKey==GarminScheduleRepository.ownerKey(account.email) } ?: return
        if(entry !in cache.entries || entry.preview.executionIssue!=null || entry.preview.executableSteps.isEmpty()) return
        startPlannedWorkout(scheduledPlan(profileId,entry))
    }
    fun savePlannedWorkout(preview: GarminWorkoutPreview) {
        val owner=store.state.value.selectedId ?: return
        if(preview.executionIssue!=null || preview.executableSteps.isEmpty()) return
        val saved=SavedWorkout(java.util.UUID.randomUUID().toString(),owner,preview.name,preview.executableSteps,maxMph=if(preview.name.contains("tempo",ignoreCase=true)) 10.0 else 6.0,garminSource=preview.source)
        PlannedWorkout(saved) // Validate before persistence.
        store.update { state ->
            val existing=state.workouts.firstOrNull { it.profileId==owner &&
                (if(saved.garminSource!=null) it.garminSource==saved.garminSource else it.name==saved.name && it.steps==saved.steps && it.garminSource==null) }
            if(existing==null) state.copy(workouts=state.workouts+saved)
            else state.copy(workouts=state.workouts.map { if(it.id==existing.id) saved.copy(id=it.id,maxMph=it.maxMph) else it })
        }
        message.value="Saved ${preview.name} to this runner’s planned workouts. Start it when ready."
    }
    fun setPlannedMaxSpeed(id: String, maxMph: Double) {
        if(active.value!=null || controlBusy.value || !maxMph.isFinite() || maxMph !in 2.0..10.0) return
        val owner=store.state.value.selectedId ?: return
        store.update { state -> state.copy(workouts=state.workouts.map {
            if(it.id==id && it.profileId==owner) it.copy(maxMph=maxMph) else it
        }) }
    }
    fun startPlannedWorkout(original: SavedWorkout) {
        val saved=preparePlan(original)
        if(active.value!=null || controlBusy.value || saved.profileId!=store.state.value.selectedId) return
        if(!controlsVerified.value) { message.value="Verify treadmill controls under Connections first."; return }
        if(heart.bpm.value==null) { message.value="Connect your chest strap before starting the guide."; return }
        val t=treadmill.telemetry.value
        if(!isFresh(t) || t.mph==null || t.mph>0.05 || t.incline==null || t.incline !in 0.0..3.0) {
            message.value="Stop the belt and set incline between 0% and 3% before starting this running workout."; return
        }
        val guide=runCatching { PlannedWorkout(saved) }.getOrElse { message.value="This saved workout has unsupported settings."; return }
        startRecording(saved)
        if(active.value==null) return
        paused.value=true // Countdown and startup must not consume an interval.
        disableZone(); resumeMph=2.0; planned=guide; plannedName.value=saved.name; guideFinishHandled=false
        plannedStatus.value="Starting ${saved.name}"
        if(store.state.value.profiles.firstOrNull { it.id==saved.profileId }?.warmupEnabled==true) {
            val first=saved.steps.first()
            beginWarmup(first.startMph ?: 6.0,first.hrHigh)
        }
        startForegroundService(Intent(this,WorkoutService::class.java).setAction("record"))
        performControl {
            controlStatus.value="Starting ${saved.name} at 2 mph in 3 seconds…"; delay(3000)
            glass.prepareToRun()
            if(!applyTarget(Target.Speed(2.0))) { guide.halt("Start interrupted · pause and resume to continue"); return@performControl }
            if(warmup!=null) warmup?.resume(SystemClock.elapsedRealtime()) else guide.resume(SystemClock.elapsedRealtime()); paused.value=false
        }
    }
    fun queueHike(saved:SavedHike,maxIncline:Double):Boolean {
        if(active.value!=null || controlBusy.value) { message.value="End the current workout before choosing a hike.";return false }
        val owner=store.state.value.selectedId ?: run { message.value="Choose a runner in OpenRun first.";return false }
        if(!saved.route.hasElevation || !maxIncline.isFinite() || maxIncline !in 0.0..HikeLimits.MAX_INCLINE) return false
        pendingHike.value=PendingHike(saved,owner,maxIncline);return true
    }
    fun startHike() {
        val selected=pendingHike.value ?: return
        if(active.value!=null || controlBusy.value || selected.profileId!=store.state.value.selectedId) return
        if(!controlsVerified.value) { message.value="Verify treadmill controls under Connections first.";return }
        val t=treadmill.telemetry.value
        if(!isFresh(t) || t.mph==null || t.mph>0.05 || t.incline==null || t.incline !in HikeLimits.MIN_INCLINE..selected.maxIncline) {
            message.value="Stop the belt and set incline within this hike’s limit before starting.";return
        }
        val addWarmup=store.state.value.profiles.first { it.id==selected.profileId }.warmupEnabled
        if(addWarmup && t.incline !in 0.0..3.0) { message.value="Set incline to 0–3% before warm-up.";return }
        val guide=HikeGuide(selected)
        startRecording(hikeName=selected.hike.name)
        if(active.value==null) return
        hike=guide;hikeDistanceOffset=0.0;guideFinishHandled=false;pendingHike.value=null
        disableZone();paused.value=true;resumeMph=2.0
        if(addWarmup) beginWarmup(2.0,null)
        startForegroundService(Intent(this,WorkoutService::class.java).setAction("record"))
        performControl {
            controlStatus.value="Preparing hike in 3 seconds…";delay(3000)
            glass.prepareToRun()
            // Align the route before starting the belt; a failed incline command must
            // never fall through to a speed command. Warm-up retains its gentle grade.
            if(warmup==null) {
                val initial=Target.Incline(guide.startingIncline)
                if(!initial.matches(treadmill.telemetry.value) && !applyTarget(initial)) {
                    guide.halt();return@performControl
                }
                guide.confirmed(SystemClock.elapsedRealtime(),treadmill.telemetry.value.incline)
            }
            if(!applyTarget(Target.Speed(2.0))) { guide.halt();return@performControl }
            paused.value=false
            if(warmup!=null) warmup?.resume(SystemClock.elapsedRealtime()) else guide.resume(SystemClock.elapsedRealtime())
        }
    }
    fun pauseWorkout() {
        warmup?.pause()
        planned?.pause()
        if(active.value!=null) paused.value=true
        resumeZone=zone.enabled
        val observed=treadmill.telemetry.value.mph
        if(observed!=null && observed>=0.2) resumeMph=observed.coerceIn(2.0,manualMaxMph)
        zone.disable("Paused · belt stop requested"); zoneEnabled.value=false; zoneStatus.value=zone.status
        performControl(preempt=true) {
            applyTarget(Target.Speed(0.0))
            if(active.value!=null) { paused.value=true; active.value?.let(store::save) }
        }
    }
    fun resumeWorkout() {
        if(active.value==null || !paused.value || controlBusy.value) return
        if(planned?.complete==true || hike?.complete==true) { message.value="Guide complete. Use End workout to save or discard."; return }
        val t=treadmill.telemetry.value
        if(!isFresh(t) || t.incline==null || t.incline !in manualMinIncline..manualMaxIncline) { message.value="Check the treadmill connection and incline before resuming."; return }
        if(warmup!=null && t.incline !in 0.0..3.0) { message.value="Set incline to 0–3% before resuming warm-up.";return }
        performControl {
            controlStatus.value="Resuming in 3 seconds…"; delay(3000)
            glass.prepareToRun()
            if(!applyTarget(Target.Speed(resumeMph))) return@performControl
            if(warmup!=null) warmup?.resume(SystemClock.elapsedRealtime()) else planned?.resume(SystemClock.elapsedRealtime())
            hike?.resume(SystemClock.elapsedRealtime())
            paused.value=false
            if(resumeZone && heart.bpm.value!=null) { zone.enable(SystemClock.elapsedRealtime()); zoneEnabled.value=true; zoneStatus.value=zone.status }
            resumeZone=false
        }
    }
    fun endWorkout(save: Boolean = true) {
        if(active.value==null) { pauseWorkout(); return }
        disableZone()
        planned?.pause(); warmup?.pause()
        paused.value=true
        performControl(preempt=true) {
            applyTarget(Target.Speed(0.0),endWorkout=true)
            finishRecording(save)
            startService(Intent(this,WorkoutService::class.java).setAction("complete").putExtra("saved",save))
        }
    }
    private suspend fun finishRecording(save: Boolean) {
        // Wait for any in-flight autosave before saving or deleting the final session.
        recording?.cancelAndJoin(); recording=null
        val ride=active.value ?: return
        if(save) store.save(ride.copy(status="complete",guideCompleted=planned?.complete == true || hike?.complete == true))
        else store.update { state -> state.copy(rides=state.rides.filterNot { it.id==ride.id }) }
        active.value=null; paused.value=false
        warmup=null; warmingUp.value=false; warmupStatus.value=null; zoneAfterWarmup=false
        if(save) scope.launch(Dispatchers.IO) { refreshPaces(ride.profileId,localOnly=true) }
        hike=null;hikeStatus.value=null
        planned=null; plannedName.value=null; plannedStatus.value=null
        if(save) GarminSyncWorker.enqueue(this)
        message.value=if(save) "Belt stopped. Workout saved to this profile." else "Belt stopped. Workout discarded; nothing saved or uploaded."
    }

}
/** Ignore counter resets and discontinuities instead of attributing another session's distance. */
fun sessionDistanceDelta(previous: Double?, current: Double?, elapsedMs: Long): Double {
    if(previous==null || current==null) return 0.0
    val delta=current-previous
    return if(delta >= 0 && delta <= elapsedMs / 1000.0 * 15 + 2) delta else 0.0
}
