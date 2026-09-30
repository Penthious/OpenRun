package dev.digitalducktape.openrun

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkoutBuilderTest {
    private fun plan(vararg intervals:IntervalDraft)=WorkoutDraft(owner=2,intervals=intervals.toList()).workout()
    private fun telemetry(mph:Double=4.0,incline:Double=1.0)=Telemetry(mph,incline,0.0,1)
    @Test fun `time distance and HR settings survive edit and serialization`() {
        val p=plan(IntervalDraft(amount="0.5",speed="3",incline="0"),IntervalDraft(distance=true,amount="0.25",useHr=true))
        assertEquals(30,p.steps.first().seconds)
        assertEquals(402.336,p.steps.last().distanceMeters!!,.001)
        assertEquals(0,p.steps.last().seconds)
        assertEquals(120,p.steps.last().hrLow)
        assertEquals(p,WorkoutDraft.from(p).workout())
        assertEquals(p,Json.decodeFromString<SavedWorkout>(Json.encodeToString(p)))
        assertEquals(p,AdaptivePace.prepare(p,null))
        val copy=WorkoutDraft.from(p,true).workout()
        assertNotEquals(p.id,copy.id);assertEquals(p.profileId,copy.profileId);assertEquals(p.steps,copy.steps)
    }
    @Test fun `reject invalid numbers unsupported targets and empty plans`() {
        val drafts=listOf(
            WorkoutDraft(owner=1,maxSpeed="NaN"),WorkoutDraft(owner=1,maxSpeed="11"),WorkoutDraft(owner=1,name=" "),WorkoutDraft(owner=1,intervals=emptyList()),
            WorkoutDraft(owner=1,intervals=listOf(IntervalDraft(speed="7"))),WorkoutDraft(owner=1,intervals=listOf(IntervalDraft(speed="3.14"))),
            WorkoutDraft(owner=1,intervals=listOf(IntervalDraft(incline="4"))),WorkoutDraft(owner=1,intervals=listOf(IntervalDraft(amount="0"))),
            WorkoutDraft(owner=1,intervals=listOf(IntervalDraft(distance=true,amount="NaN"))),
            WorkoutDraft(owner=1,intervals=listOf(IntervalDraft(useHr=true,low="160",high="140"))),
        )
        drafts.forEach { assertThrows(IllegalArgumentException::class.java) { it.workout() } }
    }
    @Test fun `fixed intervals issue targets without HR and reduce speed before incline`() {
        val g=PlannedWorkout(plan(IntervalDraft(speed="3",incline="0"))).also { it.resume(0,0.0) }
        g.tick(0,telemetry(),null,true,false,false,0.0)
        val target=g.tick(10000,telemetry(),null,true,false,false,10.0)
        assertTrue(target is Target.Speed);assertEquals(3.8,target!!.value,.001)
        g.confirmed(11000,telemetry(3.0))
        val next=g.tick(16000,telemetry(3.0),null,true,false,false,15.0)
        assertTrue(next is Target.Incline);assertEquals(.5,next!!.value,.001)
    }
    @Test fun `distance ignores warmup and pause then carries progress across distance steps`() {
        val p=plan(IntervalDraft(distance=true,amount="0.01"),IntervalDraft(distance=true,amount="0.01"))
        val g=PlannedWorkout(p).also { it.resume(0,500.0) }
        g.tick(1000,telemetry(),null,true,false,false,510.0)
        assertEquals(10.0,g.elapsedMeters,0.0)
        g.pause();g.tick(2000,telemetry(0.0),null,true,true,false,900.0)
        assertEquals(10.0,g.elapsedMeters,0.0)
        g.resume(3000,900.0);g.tick(4000,telemetry(),null,true,false,false,910.0)
        assertEquals(1,g.index);assertEquals(20.0-16.09344,g.elapsedMeters,.001)
        g.tick(5000,telemetry(),null,true,false,false,930.0)
        assertTrue(g.complete)
    }
    @Test fun `mixed time distance steps manual hold ends at next interval`() {
        val g=PlannedWorkout(plan(IntervalDraft(amount="0.5"),IntervalDraft(distance=true,amount="0.01",speed="3"))).also { it.resume(0,0.0) }
        g.tick(0,telemetry(),null,true,false,false,0.0);g.manual()
        assertNull(g.tick(20000,telemetry(),null,true,false,false,20.0))
        g.tick(30000,telemetry(),null,true,false,false,30.0)
        assertEquals(1,g.index);assertEquals(0.0,g.elapsedMeters,0.0)
        assertTrue(g.tick(40000,telemetry(),null,true,false,false,40.0) is Target.Speed)
        g.tick(50000,telemetry(),null,true,false,false,50.0);assertTrue(g.complete)
    }
    @Test fun `custom HR interval holds settings on strap loss`() {
        val g=PlannedWorkout(plan(IntervalDraft(useHr=true))).also { it.resume(0,0.0) }
        assertNull(g.tick(10000,telemetry(2.0),null,true,false,false,1.0))
        assertTrue(g.status.contains("HR disconnected"))
        g.tick(11000,telemetry(2.0),110,true,false,false,2.0)
        assertTrue(g.tick(21000,telemetry(2.0),110,true,false,false,3.0) is Target.Speed)
    }
    @Test fun `distance rollback and invalid telemetry halt guidance`() {
        val g=PlannedWorkout(plan(IntervalDraft(distance=true,amount="1"))).also { it.resume(0,50.0) }
        assertNull(g.tick(1000,telemetry(),null,true,false,false,49.0));assertTrue(g.halted)
        val h=PlannedWorkout(plan(IntervalDraft())).also { it.resume(0,0.0) }
        assertNull(h.tick(1000,telemetry(Double.NaN),null,true,false,false,0.0));assertTrue(h.halted)
    }
    @Test fun `missing distance telemetry halts a distance interval`() {
        val g=PlannedWorkout(plan(IntervalDraft(distance=true,amount="1"))).also { it.resume(0,0.0) }
        assertNull(g.tick(1000,Telemetry(4.0,1.0,null,1),null,true,false,false,0.0));assertTrue(g.halted)
    }
    @Test fun `fixed warmup can ramp without a strap`() {
        val g=WarmupGuide(4.0,null,requiresHeartRate=false).also { it.resume(0) }
        assertTrue(g.tick(60000,telemetry(2.0),null,true,false,false) is Target.Speed)
    }
}
