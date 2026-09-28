package dev.digitalducktape.openrun

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.round

@Serializable data class PlannedStep(val name: String, val seconds: Int, val hrLow: Int? = null, val hrHigh: Int? = null, val startMph: Double? = null, val paceLowMph: Double? = null, val paceHighMph: Double? = null, val paceReason: String? = null)
@Serializable data class SavedWorkout(val id: String, val profileId: Long, val name: String, val steps: List<PlannedStep>, val maxMph: Double = 6.0, val incline: Double = 0.0, val garminSource: dev.digitalducktape.openrun.core.garmin.GarminPlannedWorkout? = null)

/** Timed guide and HR decisions only. Hardware execution remains serialized by the application. */
class PlannedWorkout(val workout: SavedWorkout) {
    var index = 0; private set
    var elapsedMs = 0L; private set
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
        require(workout.steps.isNotEmpty() && workout.steps.size <= 200)
        require(workout.maxMph.isFinite() && workout.maxMph in 2.0..10.0 && workout.incline.isFinite() && workout.incline in 0.0..3.0)
        require(workout.steps.all { it.startMph==null || (it.startMph.isFinite() && it.startMph in 2.0..workout.maxMph) })
        require(workout.steps.all { (it.paceLowMph==null && it.paceHighMph==null) || (it.paceLowMph!=null && it.paceHighMph!=null && it.paceLowMph.isFinite() && it.paceHighMph.isFinite() && it.paceLowMph in 0.2..10.0 && it.paceHighMph in it.paceLowMph..10.0) })
        require(workout.steps.all { it.seconds in 1..14400 && ((it.hrLow==null && it.hrHigh==null) || (it.hrLow!=null && it.hrHigh!=null && it.hrLow in 30..240 && it.hrHigh in it.hrLow..240)) })
    }
    fun pause() { previousAt=null; readings.clear(); previous=null }
    fun resume(now: Long) { halted=false; previousAt=now; checkAfter=now+10_000; readings.clear(); previous=null }
    fun halt(reason: String) { halted=true; pause(); status=reason }
    fun manual() { manualStep=index; readings.clear(); status="Manual settings held until next interval" }
    fun confirmed(now: Long, t: Telemetry) { checkAfter=now+5_000; if(rampedStep==index) settleUntil=now+30_000; readings.clear(); previous=t }
    fun tick(now: Long, t: Telemetry, hr: Int?, fresh: Boolean, paused: Boolean, busy: Boolean): Target? {
        if(complete || halted) return null
        if(paused) { pause(); status="Paused"; return null }
        if(!fresh || t.mph==null || t.incline==null) { halt("Treadmill connection lost · pause and resume to continue guide"); return null }
        if(t.mph<0.2) { halt("Belt stopped · pause and resume to continue guide"); return null }
        if(t.mph>workout.maxMph+0.08 || t.incline>3.15 || t.incline< -0.15) { halt("Outside running limits · pause and check settings"); return null }
        val dt=previousAt?.let { (now-it).coerceAtLeast(0) } ?: 0L
        previousAt=now
        elapsedMs+=dt
        while(step!=null && elapsedMs>=step!!.seconds*1000L) {
            elapsedMs-=step!!.seconds*1000L; index++; manualStep=null; readings.clear(); checkAfter=now+10_000
        }
        if(step==null) { complete=true; status="Guide complete · stopping belt"; return null }
        val old=previous
        if(!busy && old!=null && (abs(t.mph-old.mph!!)>0.08 || abs(t.incline-old.incline!!)>0.15)) manual()
        previous=t
        val current=step!!
        val label="${index+1}/${workout.steps.size} ${current.name} · ${remainingSeconds}s"
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
            if(t.incline>1.05) return Target.Incline((t.incline-.5).coerceAtLeast(1.0))
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
