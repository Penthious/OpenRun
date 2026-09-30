package dev.digitalducktape.openrun

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.round

@Serializable data class PlannedStep(val name: String, val seconds: Int, val hrLow: Int? = null, val hrHigh: Int? = null, val startMph: Double? = null, val paceLowMph: Double? = null, val paceHighMph: Double? = null, val paceReason: String? = null, val distanceMeters: Double? = null, val incline: Double? = null)
@Serializable data class SavedWorkout(val id: String, val profileId: Long, val name: String, val steps: List<PlannedStep>, val maxMph: Double = 6.0, val incline: Double = 0.0, val garminSource: dev.digitalducktape.openrun.core.garmin.GarminPlannedWorkout? = null, val custom: Boolean = false)

/** Timed guide and HR decisions only. Hardware execution remains serialized by the application. */
class PlannedWorkout(val workout: SavedWorkout) {
    var index = 0; private set
    var elapsedMs = 0L; private set
    var elapsedMeters = 0.0; private set
    private var previousDistance: Double? = null
    val remainingMeters get() = ((step?.distanceMeters ?: 0.0)-elapsedMeters).coerceAtLeast(0.0)
    var complete = false; private set
    var halted = false; private set
    var status = "Starting workout"; private set
    private var previousAt: Long? = null
    private var previous: Telemetry? = null
    private var checkAfter = 0L
    private var manualStep: Int? = null
    private var rampedStep: Int? = null
    private var settleUntil = 0L
    private val readings = ArrayDeque<Pair<Long,Int>>()
    val step get() = workout.steps.getOrNull(index)
    val remainingSeconds get() = ((step?.seconds?.times(1000L) ?: 0L) - elapsedMs).coerceAtLeast(0).let { (it+999)/1000 }
    init {
        if(workout.custom) {
            require(workout.name.isNotBlank() && workout.name.length<=80 && workout.garminSource==null)
            require(workout.steps.all { it.name.isNotBlank() && it.name.length<=80 && it.startMph!=null && it.incline!=null && it.incline.isFinite() && it.incline in 0.0..3.0 })
        }
        require(workout.steps.isNotEmpty() && workout.steps.size <= 200)
        require(workout.maxMph.isFinite() && workout.maxMph in 2.0..10.0 && workout.incline.isFinite() && workout.incline in 0.0..3.0)
        require(workout.steps.all { it.startMph==null || (it.startMph.isFinite() && it.startMph in 2.0..workout.maxMph) })
        require(workout.steps.all { (it.paceLowMph==null && it.paceHighMph==null) || (it.paceLowMph!=null && it.paceHighMph!=null && it.paceLowMph.isFinite() && it.paceHighMph.isFinite() && it.paceLowMph in 0.2..10.0 && it.paceHighMph in it.paceLowMph..10.0) })
        require(workout.steps.all { (if(it.distanceMeters==null) it.seconds in 1..14400 else workout.custom && it.seconds==0 && it.distanceMeters.isFinite() && it.distanceMeters in 16.09344..80467.2) && ((it.hrLow==null && it.hrHigh==null) || (it.hrLow!=null && it.hrHigh!=null && it.hrLow in 30..240 && it.hrHigh in it.hrLow..240)) })
    }
    private fun fixedTarget(now:Long,t:Telemetry,current:PlannedStep,label:String,busy:Boolean):Target? {
        status="$label · ${current.startMph} mph · ${current.incline}%"
        if(manualStep==index) { status+=" · Manual hold until next interval"; return null }
        if(busy || now<checkAfter) return null
        checkAfter=now+5_000
        val speed=current.startMph!!
        val incline=current.incline!!
        // Reduce speed first when entering recovery; serialize every confirmed change.
        if(t.mph!!>speed+.08) return Target.Speed((round((t.mph-.2).coerceAtLeast(speed)*10)/10))
        if(abs(t.incline!!-incline)>.15) return Target.Incline(t.incline+(incline-t.incline).coerceIn(-.5,.5))
        if(t.mph<speed-.08) return Target.Speed((round((t.mph+.2).coerceAtMost(speed)*10)/10))
        return null
    }
    fun pause() { previousAt=null; readings.clear(); previous=null }
    fun resume(now: Long, distanceMeters: Double? = null) { previousDistance=distanceMeters; halted=false; previousAt=now; checkAfter=now+10_000; readings.clear(); previous=null }
    fun halt(reason: String) { halted=true; pause(); status=reason }
    fun manual() { manualStep=index; readings.clear(); status="Manual settings held until next interval" }
    fun confirmed(now: Long, t: Telemetry) { checkAfter=now+5_000; if(rampedStep==index) settleUntil=now+30_000; readings.clear(); previous=t }
    fun tick(now: Long, t: Telemetry, hr: Int?, fresh: Boolean, paused: Boolean, busy: Boolean, distanceMeters: Double = 0.0): Target? {
        if(complete || halted) return null
        if(paused) { pause(); status="Paused"; return null }
        if(!fresh || t.mph==null || !t.mph.isFinite() || t.incline==null || !t.incline.isFinite()) { halt("Treadmill connection lost · pause and resume to continue guide"); return null }
        if(step?.distanceMeters!=null && (t.meters==null || !t.meters.isFinite())) { halt("Distance unavailable · pause and resume to continue guide"); return null }
        if(t.mph<0.2) { halt("Belt stopped · pause and resume to continue guide"); return null }
        if(t.mph>workout.maxMph+0.08 || t.incline>3.15 || t.incline< -0.15) { halt("Outside running limits · pause and check settings"); return null }
        val dt=previousAt?.let { (now-it).coerceAtLeast(0) } ?: 0L
        previousAt=now
        elapsedMs+=dt
        if(workout.custom) {
            if(!distanceMeters.isFinite() || distanceMeters<0 || (previousDistance!=null && distanceMeters<previousDistance!!)) {
                halt("Distance unavailable · pause and resume to continue guide"); return null
            }
            elapsedMeters += previousDistance?.let { distanceMeters-it } ?: 0.0
            previousDistance=distanceMeters
        }
        while(step!=null && (step!!.distanceMeters?.let { elapsedMeters>=it } ?: (elapsedMs>=step!!.seconds*1000L))) {
            val oldStep=step!!
            index++
            elapsedMs=if(oldStep.distanceMeters==null && step?.distanceMeters==null) elapsedMs-oldStep.seconds*1000L else 0
            elapsedMeters=if(oldStep.distanceMeters!=null && step?.distanceMeters!=null) elapsedMeters-oldStep.distanceMeters else 0.0
            manualStep=null; rampedStep=null; settleUntil=0; previous=null; readings.clear(); checkAfter=now+10_000
        }
        if(step==null) { complete=true; status="Guide complete · stopping belt"; return null }
        val old=previous
        if(!busy && old!=null && (abs(t.mph-old.mph!!)>0.08 || abs(t.incline-old.incline!!)>0.15)) manual()
        previous=t
        val current=step!!
        val label="${index+1}/${workout.steps.size} ${current.name} · " + if(current.distanceMeters!=null) "%.2f mi left".format(remainingMeters/1609.344) else "${remainingSeconds}s"
        if(workout.custom && current.hrLow==null) return fixedTarget(now,t,current,label,busy)
        if(hr==null) { readings.clear(); checkAfter=now+10_000; status="$label · HR disconnected, holding settings"; return null }
        if(manualStep==index) { status="$label · Manual hold until next interval"; return null }
        if(busy) { readings.clear(); status="$label · Waiting for treadmill"; return null }
        readings.addLast(now to hr)
        while(readings.size>1 && readings.elementAt(1).first<=now-10_000) readings.removeFirst()
        if(now<checkAfter || now-readings.first().first<10_000) { status="$label · Checking HR for 10 seconds"; return null }
        val average=readings.map { it.second }.average()
        val low=current.hrLow
        val high=current.hrHigh
        val requested=(current.startMph ?: if(low==null) 2.0 else t.mph).coerceIn(2.0,workout.maxMph)
        fun speed(value:Double)=Target.Speed((round(value*10)/10).coerceIn(2.0,workout.maxMph))
        checkAfter=now+10_000
        status="$label · " + if(low!=null) "Target $low–$high bpm" else if(current.paceLowMph!=null) "Prescribed pace" else "Recovery walk"
        // HR above target always prevents the initial ramp from increasing load.
        val highHr=high!=null && average>high
        if(rampedStep!=index && low!=null && average in low.toDouble()..high!!.toDouble()) {
            rampedStep=index; settleUntil=now+30_000
        }
        if(rampedStep!=index && !highHr) {
            val initialIncline=if(workout.custom) current.incline!! else 1.0
            if(abs(t.incline-initialIncline)>.15 && (workout.custom || t.incline>initialIncline)) return Target.Incline(t.incline+(initialIncline-t.incline).coerceIn(-.5,.5))
            if(abs(t.mph-requested)>.08) return speed(t.mph+(requested-t.mph).coerceIn(-.2,.2))
            rampedStep=index; settleUntil=now+30_000
        }
        if(now<settleUntil) { status+=" · allowing HR to settle"; return null }
        if(low==null) return null
        val result=when {
            average>high!! && t.incline>1.05 -> Target.Incline((t.incline-.5).coerceAtLeast(1.0))
            average>high && t.mph>2.05 -> speed(t.mph-.1)
            average<low && t.incline<2.95 -> Target.Incline((t.incline+.5).coerceIn(1.0,3.0))
            average<low && t.mph<workout.maxMph-.05 -> speed(t.mph+.1)
            else -> null
        }
        if(result!=null) {
            rampedStep=index // A high-HR reduction must never bounce back to the initial pace.
            settleUntil=now+30_000
        }
        return result

    }
}
