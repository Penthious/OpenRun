package dev.digitalducktape.openrun

import dev.digitalducktape.openrun.core.data.Ride
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.round

@Serializable data class PaceSegment(val activity: String, val timestamp: Long, val mph: Double, val hr: Double, val treadmill: Boolean)
@Serializable data class LearnedPace(val low: Int, val high: Int, val mph: Double, val averageHr: Double, val runs: Int)
@Serializable data class PaceHistory(val profileId: Long, val accountKey: String, val fetchedAt: Long = 0,
    val segments: List<PaceSegment> = emptyList(), val learned: List<LearnedPace> = emptyList(), val updatedAt: Long = 0)
data class PacePoint(val timestamp: Long, val mph: Double?, val hr: Int?, val incline: Double?, val eligible: Boolean = true)

object AdaptivePace {
    const val AGE_MS = 56L*24*60*60*1000
    /** One vote per steady minute; no warm-up, pauses, HR gaps, steep hills, or recovery walks. */
    fun segments(activity: String, treadmill: Boolean, points: List<PacePoint>): List<PaceSegment> {
        val result=mutableListOf<PaceSegment>()
        var window=mutableListOf<PacePoint>()
        for(p in points.sortedBy { it.timestamp }) {
            val previous=window.lastOrNull()
            val good=p.eligible && p.mph!=null && p.mph.isFinite() && p.mph in 3.5..10.0 && p.hr!=null && p.hr in 60..220 &&
                p.incline!=null && p.incline.isFinite() && abs(p.incline)<=if(treadmill) 3.0 else 1.0
            if(!good || (previous!=null && p.timestamp-previous.timestamp !in 500..15_000)) window.clear()
            if(!good) continue
            window.add(p)
            if(p.timestamp-window.first().timestamp>=60_000) {
                val speeds=window.map { it.mph!! }; val hearts=window.map { it.hr!! }
                if(speeds.max()-speeds.min()<=.4 && hearts.max()-hearts.min()<=12 && window.size>=6) {
                    result+=PaceSegment(activity,p.timestamp,speeds.average(),hearts.average(),treadmill)
                }
                window=mutableListOf()
            }
        }
        return result
    }
    fun local(ride: Ride):List<PaceSegment> {
        if(ride.status!="complete") return emptyList()
        return segments("local:${ride.id}",true,ride.samples.map { p ->
            val step=p.workoutStepIndex?.let { ride.plannedWorkout?.steps?.getOrNull(it) }
            val excluded=step?.name?.lowercase() in setOf("warmup","cooldown","rest","recovery")
            PacePoint(p.timestamp,p.speedMps?.div(.44704),p.heartRate,p.incline,
                !p.warmup && p.elapsedSec>=300 && !excluded)
        })
    }
    fun learn(low:Int,high:Int,segments:List<PaceSegment>,previous:LearnedPace?,now:Long):LearnedPace? {
        val middle=(low+high)/2.0
        val candidates=segments.filter { it.timestamp in (now-AGE_MS)..now && it.hr in low.toDouble()..high.toDouble() && abs(it.hr-middle)<=10 }
        val indoor=candidates.filter { it.treadmill }
        val use=if(indoor.map { it.activity }.distinct().size>=2) indoor else candidates
        // Each run has equal influence, preventing one long run dominating calibration.
        val runs=use.groupBy { it.activity }.values.filter { it.size>=3 }
        if(runs.size<2) return null
        fun median(values:List<Double>):Double { val s=values.sorted(); return (s[(s.size-1)/2]+s[s.size/2])/2 }
        val estimate=median(runs.map { run -> median(run.map { it.mph }) })
        val limited=previous?.let { estimate.coerceIn(it.mph-.3,it.mph+.3) } ?: estimate
        return LearnedPace(low,high,round(limited*10)/10,runs.flatMap { it }.map { it.hr }.average(),runs.size)
    }
    fun prepare(plan:SavedWorkout,history:PaceHistory?):SavedWorkout = if(plan.custom) plan else plan.copy(steps=plan.steps.map { step ->
        val learned=history?.learned?.firstOrNull { it.low==step.hrLow && it.high==step.hrHigh }
        val pace=when {
            step.paceLowMph!=null && step.paceHighMph!=null -> (step.paceLowMph+step.paceHighMph)/2
            learned!=null -> learned.mph
            step.hrLow==null -> 2.0
            step.name.lowercase() in setOf("warmup","cooldown","rest","recovery") -> 3.5
            else -> 6.0 // User-selected running baseline, not a distance estimate.
        }.coerceIn(2.0,plan.maxMph)
        step.copy(startMph=pace,paceReason=when {
            step.paceLowMph!=null -> "Garmin pace target"
            learned!=null -> "Learned from ${learned.runs} runs · ${learned.averageHr.toInt()} bpm"
            step.hrLow==null -> "Recovery walk"
            else -> "Default pace · learning from your runs"
        })
    })
}
