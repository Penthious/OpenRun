package dev.digitalducktape.openrun

import dev.digitalducktape.openrun.core.data.*
import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

internal const val MILE_METERS = 1609.344
internal data class ProgressWeek(val start: LocalDate, val rides: List<Ride>) {
    val distance = rides.sumOf { it.distanceMeters.validTotal() }
    val seconds = rides.sumOf { it.durationSec.coerceAtLeast(0).toLong() }
    val ascent = rides.sumOf { it.ascentMeters.validTotal() }
    val descent = rides.mapNotNull { it.descentMeters?.takeIf { v -> v.isFinite() && v>=0 } }.sum()
    val descentMissing = rides.count { it.descentMeters==null || !it.descentMeters.isFinite() || it.descentMeters<0 }
}
private fun Double.validTotal() = takeIf { isFinite() && this>=0 } ?: 0.0
internal fun progressZone(profile: Profile): ZoneId = runCatching { ZoneId.of(profile.scheduleTimeZone) }.getOrDefault(ZoneId.of("America/Denver"))
internal fun progressWeeks(rides: List<Ride>, profile: Profile, today: LocalDate): List<ProgressWeek> {
    val monday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val eligible=rides.filter { it.profileId==profile.id && it.status=="complete" }
    val zone=progressZone(profile)
    return (7 downTo 0).map { ago ->
        val start=monday.minusWeeks(ago.toLong())
        ProgressWeek(start,eligible.filter { val date=Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate(); date>=start && date<start.plusWeeks(1) && date<=today })
    }
}

/** Fastest measured mile, interpolated at sample boundaries; never bridges pauses or missing telemetry. */
internal fun fastestMile(ride: Ride): Double? {
    var best: Double?=null
    var segment=mutableListOf<RideSample>()
    fun evaluate() {
        if(segment.size<2) return
        fun elapsedAt(distance: Double): Double {
            val index=segment.binarySearchBy(distance) { it.distanceMeters }
            if(index>=0) return segment[index].elapsedSec.toDouble()
            val right=-index-1
            val a=segment[right-1]; val b=segment[right]
            return a.elapsedSec+(distance-a.distanceMeters)/(b.distanceMeters-a.distanceMeters)*(b.elapsedSec-a.elapsedSec)
        }
        // An optimum starts or ends at a sample boundary in the piecewise-linear trace.
        val ends=segment.flatMap { listOf(it.distanceMeters,it.distanceMeters+MILE_METERS) }
        for(end in ends) {
            val start=end-MILE_METERS
            if(start<segment.first().distanceMeters || end>segment.last().distanceMeters) continue
            val duration=elapsedAt(end)-elapsedAt(start)
            if(duration>0 && (best==null || duration<best!!)) best=duration
        }
    }
    for(s in ride.samples) {
        val valid=s.distanceMeters.isFinite() && s.distanceMeters>=0 && s.elapsedSec>=0 && s.speedMps?.let { it.isFinite() && it>0 }==true
        val p=segment.lastOrNull()
        val continuous=p==null || (s.timestamp-p.timestamp in 1..15_000 && s.elapsedSec-p.elapsedSec in 1..15 &&
            abs((s.timestamp-p.timestamp)/1000.0-(s.elapsedSec-p.elapsedSec))<=1.0 &&
            s.distanceMeters>p.distanceMeters && (s.distanceMeters-p.distanceMeters)/(s.elapsedSec-p.elapsedSec)<=12.0)
        if(!valid || !continuous) { evaluate(); segment=mutableListOf() }
        if(valid) segment.add(s)
    }
    evaluate()
    return best
}
internal data class PacePeriod(val mph: Double?, val hr: Double?, val runs: Int)
internal fun basePace(rides: List<Ride>, low: Int): PacePeriod {
    val averages=rides.filter { it.hikeName==null && it.plannedWorkout?.name?.contains("base",ignoreCase=true)==true }.mapNotNull { ride ->
        val segments=AdaptivePace.segments(ride.id.toString(),true,ride.samples.map { s ->
            val name=s.workoutStepIndex?.let { ride.plannedWorkout?.steps?.getOrNull(it)?.name }.orEmpty()
            PacePoint(s.timestamp,s.speedMps?.div(.44704),s.heartRate,s.incline,
                !s.warmup && s.elapsedSec>=300 && s.incline?.let { it in 0.0..1.0 }==true &&
                    !Regex("warm|cool|recover|rest",RegexOption.IGNORE_CASE).containsMatchIn(name))
        }).filter { it.hr>=low && it.hr<low+10 }
        if(segments.size<3) null else segments.map { it.mph }.average() to segments.map { it.hr }.average()
    }
    return PacePeriod(if(averages.size<2) null else averages.map { it.first }.average(),
        if(averages.size<2) null else averages.map { it.second }.average(),averages.size)
}
