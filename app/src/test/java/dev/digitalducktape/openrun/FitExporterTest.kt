package dev.digitalducktape.openrun

import com.garmin.fit.*
import dev.digitalducktape.openrun.core.data.*
import dev.digitalducktape.openrun.core.export.FitExporter
import dev.digitalducktape.openrun.core.garmin.GarminPlannedWorkout
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class FitExporterTest {
    private val start = 1_790_000_000_000L
    private val source = GarminPlannedWorkout(0,null,"2026-09-28","Tempo","aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val plan = SavedWorkout("local",1,"Tempo",listOf(PlannedStep("warmup",2,120,140),PlannedStep("interval",8,160,175),PlannedStep("cooldown",10)),garminSource=source)
    private val samples = listOf(
        RideSample(start+1000,1,1.0,1.0,2.0,125,0.02,0),
        RideSample(start+2000,2,2.0,1.0,2.0,130,0.04,0),
        RideSample(start+13000,3,3.0,1.0,2.0,135,0.06,1),
        RideSample(start+14000,4,4.0,1.0,2.0,null,0.08,1))
    private val ride = Ride(1,1,start,4,4.0,0.08,"complete",samples,plan,false,-21600)
    private fun decode(bytes: ByteArray): List<Mesg> {
        val messages=mutableListOf<Mesg>()
        assertTrue(Decode().read(bytes.inputStream(),MesgListener { messages.add(it) }))
        return messages
    }
    @Test fun `warmup lap is separate and explicit pace survives FIT round trip`() {
        val modified=ride.copy(plannedWorkout=plan.copy(steps=listOf(PlannedStep("run",60,paceLowMph=6.0,paceHighMph=6.5))))
        val points=samples.mapIndexed { i,p -> if(i<2) p.copy(warmup=true,workoutStepIndex=null) else p.copy(workoutStepIndex=0) }
        val messages=decode(FitExporter.export(modified,points))
        val laps=messages.filter { it.num==MesgNum.LAP }.map(::LapMesg)
        assertEquals(Intensity.WARMUP,laps.first().intensity)
        assertNull(laps.first().wktStepIndex)
        assertEquals(0,laps.last().wktStepIndex)
        val step=WorkoutStepMesg(messages.single { it.num==MesgNum.WORKOUT_STEP })
        assertEquals(WktStepTarget.SPEED,step.targetType)
        assertEquals(2.682,step.customTargetSpeedLow.toDouble(),.002)
    }

    @Test fun `FIT round trip retains measured intervals pause time and workout provenance`() {
        val bytes=FitExporter.export(ride,samples)
        assertArrayEquals(bytes,FitExporter.export(ride,samples))
        val messages=decode(bytes)
        val workout=WorkoutMesg(messages.single { it.num==MesgNum.WORKOUT })
        assertTrue(workout.wktDescription.contains(source.adaptiveUuid!!))
        assertTrue(workout.wktDescription.contains("guideCompleted=false"))
        val steps=messages.filter { it.num==MesgNum.WORKOUT_STEP }.map(::WorkoutStepMesg)
        assertEquals(3,steps.size)
        assertEquals(220L,steps.first().customTargetHeartRateLow)
        val laps=messages.filter { it.num==MesgNum.LAP }.map(::LapMesg)
        assertEquals(listOf(0,1),laps.map { it.wktStepIndex })
        assertEquals(4.0,laps.sumOf { it.totalTimerTime.toDouble() },0.001)
        assertEquals(14.0,laps.sumOf { it.totalElapsedTime.toDouble() },0.001)
        assertEquals(4.0,laps.sumOf { it.totalDistance.toDouble() },0.001)
        val records=messages.filter { it.num==MesgNum.RECORD }.map(::RecordMesg)
        assertNull(records.last().heartRate)
        assertNull(records.first().positionLat)
        assertEquals(125.toShort(),records.first().heartRate)
        val events=messages.filter { it.num==MesgNum.EVENT }.map(::EventMesg)
        assertEquals(listOf(EventType.START,EventType.STOP_ALL,EventType.START,EventType.STOP_ALL),events.map { it.eventType })
        val activity=ActivityMesg(messages.single { it.num==MesgNum.ACTIVITY })
        assertEquals(activity.timestamp.timestamp-21600,activity.localTimestamp)
        assertEquals(Manufacturer.DEVELOPMENT,FileIdMesg(messages.first()).manufacturer)
    }
    @Test fun `hike FIT includes ascent and descent as session fields`() {
        val hike=ride.copy(plannedWorkout=null,hikeName="Trail",ascentMeters=125.0,descentMeters=84.0)
        val messages=decode(FitExporter.export(hike,samples))
        val session=SessionMesg(messages.single { it.num==MesgNum.SESSION })
        assertEquals(125,session.totalAscent)
        assertEquals(84,session.totalDescent)
        assertEquals(SubSport.TREADMILL,session.subSport)
        assertFalse(messages.any { it.num==MesgNum.WORKOUT })
        assertEquals(hike,Json.decodeFromString<Ride>(Json.encodeToString(hike)))
    }
    @Test fun `legacy rides do not claim a measured zero descent`() {
        val old=Json.decodeFromString<Ride>("""{"id":1,"profileId":1,"startedAt":1000}""")
        assertNull(old.descentMeters)
        val session=SessionMesg(decode(FitExporter.export(ride,samples)).single { it.num==MesgNum.SESSION })
        assertNull(session.totalDescent)
    }
    @Test fun `custom distance intervals retain FIT duration and prescribed speed`() {
        val custom=WorkoutDraft(owner=1,intervals=listOf(IntervalDraft(distance=true,amount="0.25"))).workout()
        val messages=decode(FitExporter.export(ride.copy(plannedWorkout=custom),samples))
        val step=WorkoutStepMesg(messages.single { it.num==MesgNum.WORKOUT_STEP })
        assertEquals(WktStepDuration.DISTANCE,step.durationType)
        assertEquals(402.336,step.durationDistance.toDouble(),.01)
        assertEquals(WktStepTarget.SPEED,step.targetType)
        assertEquals(4*.44704,step.customTargetSpeedLow.toDouble(),.002)
    }
    @Test fun `old history deserializes and new snapshots survive restart`() {
        val old=Json.decodeFromString<Ride>("""{"id":1,"profileId":1,"startedAt":1000}""")
        assertNull(old.plannedWorkout)
        assertEquals(ride,Json.decodeFromString<Ride>(Json.encodeToString(ride)))
    }
}
