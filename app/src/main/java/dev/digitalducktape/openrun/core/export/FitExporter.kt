package dev.digitalducktape.openrun.core.export

import com.garmin.fit.*
import dev.digitalducktape.openrun.core.data.Ride
import dev.digitalducktape.openrun.core.data.RideSample
import java.util.Date

/** Sensor data and executed intervals, without impersonating a Garmin recording device.
 * Source IDs in the description are provenance, not a documented Coach-link protocol.
 */
object FitExporter {
    fun export(ride: Ride, samples: List<RideSample>): ByteArray {
        require(ride.durationSec > 0 && ride.distanceMeters.isFinite() && ride.distanceMeters >= 0)
        val points = samples.sortedBy { it.timestamp }
        require(points.zipWithNext().all { (a,b) -> b.elapsedSec >= a.elapsedSec && b.distanceMeters >= a.distanceMeters })
        val end = maxOf(ride.startedAt + ride.durationSec * 1000L, points.lastOrNull()?.timestamp ?: ride.startedAt)
        fun time(ms: Long) = DateTime(Date(ms))
        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0).apply { open() }
        encoder.write(FileIdMesg().apply {
            type = com.garmin.fit.File.ACTIVITY; manufacturer = Manufacturer.DEVELOPMENT
            product = 1; productName = "OpenRun"; timeCreated = time(ride.startedAt)
            serialNumber = ride.profileId.and(0xffffffffL)
        })
        val plan = ride.plannedWorkout
        if (plan != null) {
            encoder.write(WorkoutMesg().apply {
                sport = Sport.RUNNING; subSport = SubSport.TREADMILL; wktName = plan.name
                numValidSteps = plan.steps.size
                wktDescription = buildString {
                    append("OpenRun; guideCompleted=${ride.guideCompleted}")
                    plan.garminSource?.let {
                        append("; scheduledDate=${it.date}")
                        it.workoutId?.let { id -> append("; workoutId=$id") }
                        if (it.scheduleId > 0) append("; scheduleId=${it.scheduleId}")
                        it.adaptiveUuid?.let { uuid -> append("; workoutUuid=$uuid") }
                    }
                }
            })
            plan.steps.forEachIndexed { i, step ->
                encoder.write(WorkoutStepMesg().apply {
                    messageIndex = i; wktStepName = step.name
                    durationType = WktStepDuration.TIME; durationTime = step.seconds.toFloat()
                    intensity = when(step.name.lowercase()) {
                        "warmup" -> Intensity.WARMUP
                        "cooldown" -> Intensity.COOLDOWN
                        "rest" -> Intensity.REST
                        "recovery" -> Intensity.RECOVERY
                        else -> Intensity.ACTIVE
                    }
                    if(step.paceLowMph!=null && step.paceHighMph!=null) {
                        targetType = WktStepTarget.SPEED; targetSpeedZone = 0L
                        customTargetSpeedLow = (step.paceLowMph*.44704).toFloat()
                        customTargetSpeedHigh = (step.paceHighMph*.44704).toFloat()
                    } else if (step.hrLow != null && step.hrHigh != null) {
                        targetType = WktStepTarget.HEART_RATE; targetHrZone = 0L
                        customTargetHeartRateLow = step.hrLow + WorkoutHr.BPM_OFFSET
                        customTargetHeartRateHigh = step.hrHigh + WorkoutHr.BPM_OFFSET
                    } else { targetType = WktStepTarget.OPEN; targetValue = 0L }
                })
            }
        }
        fun timer(at: Long, kind: EventType) = encoder.write(EventMesg().apply {
            timestamp = time(at); event = Event.TIMER; eventType = kind
        })
        // The elapsed counter excludes pauses. Reconstruct timer gaps from the recorded samples.
        var previousAt = ride.startedAt
        var previousElapsed = 0
        var running = false
        points.forEach { p ->
            val elapsedDelta = p.elapsedSec - previousElapsed
            val gap = p.timestamp - previousAt - elapsedDelta * 1000L
            if (!running || gap > 1500) {
                if (running) timer(previousAt, EventType.STOP_ALL)
                timer(maxOf(previousAt, p.timestamp - elapsedDelta * 1000L), EventType.START)
                running = true
            }
            encoder.write(RecordMesg().apply {
                timestamp = time(p.timestamp); distance = p.distanceMeters.toFloat()
                p.speedMps?.takeIf { it.isFinite() && it >= 0 }?.let { speed = it.toFloat() }
                p.heartRate?.takeIf { it in 1..254 }?.let { heartRate = it.toShort() }
                p.incline?.takeIf { it.isFinite() }?.let { grade = it.toFloat() }
            })
            previousAt = p.timestamp; previousElapsed = p.elapsedSec
        }
        if (running) timer(end, EventType.STOP_ALL)
        // A lap ends at the last measured point in each actually executed step. Never emit
        // laps for unvisited steps or claim the planned duration when the user ends early.
        val groups = mutableListOf<MutableList<RideSample>>()
        points.forEach { p ->
            if (groups.isEmpty() || groups.last().last().workoutStepIndex != p.workoutStepIndex || groups.last().last().warmup != p.warmup) groups.add(mutableListOf())
            groups.last().add(p)
        }
        if (groups.isEmpty()) groups.add(mutableListOf())
        var lapAt = ride.startedAt; var lapElapsed = 0; var lapDistance = 0.0
        groups.forEachIndexed { i, group ->
            val last = i == groups.lastIndex
            val finish = if (last) end else group.last().timestamp
            val elapsed = if (last) ride.durationSec else group.last().elapsedSec
            val distance = if (last) ride.distanceMeters else group.last().distanceMeters
            encoder.write(LapMesg().apply {
                messageIndex = i; startTime = time(lapAt); timestamp = time(finish)
                totalElapsedTime = (finish - lapAt) / 1000f
                totalTimerTime = (elapsed - lapElapsed).toFloat(); totalDistance = (distance - lapDistance).toFloat()
                sport = Sport.RUNNING; subSport = SubSport.TREADMILL
                event = Event.LAP; eventType = EventType.STOP
                if(group.firstOrNull()?.warmup==true) intensity=Intensity.WARMUP
                group.firstOrNull()?.workoutStepIndex?.takeIf { plan != null && it in plan.steps.indices }?.let { wktStepIndex = it }
            })
            lapAt = finish; lapElapsed = elapsed; lapDistance = distance
        }
        encoder.write(SessionMesg().apply {
            messageIndex = 0; startTime = time(ride.startedAt); timestamp = time(end)
            sport = Sport.RUNNING; subSport = SubSport.TREADMILL
            totalElapsedTime = (end - ride.startedAt) / 1000f; totalTimerTime = ride.durationSec.toFloat()
            totalAscent = ride.ascentMeters.coerceIn(0.0,65534.0).toInt()
            ride.descentMeters?.takeIf { it.isFinite() }?.let { totalDescent = it.coerceIn(0.0,65534.0).toInt() }
            totalDistance = ride.distanceMeters.toFloat(); firstLapIndex = 0; numLaps = groups.size
            event = Event.SESSION; eventType = EventType.STOP
        })
        encoder.write(ActivityMesg().apply {
            timestamp = time(end); localTimestamp = time(end).timestamp + ride.utcOffsetSeconds
            totalTimerTime = ride.durationSec.toFloat(); numSessions = 1; type = Activity.MANUAL
            event = Event.ACTIVITY; eventType = EventType.STOP
        })
        return encoder.close()
    }
}
