package dev.digitalducktape.openrun

import dev.digitalducktape.openrun.core.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ProgressStatsTest {
    private val profile=Profile(1,"Runner")
    private fun ride(id:Long=1,date:String="2026-09-28T06:00:00Z",status:String="complete",owner:Long=1)=
        Ride(id,owner,Instant.parse(date).toEpochMilli(),600,1609.344,25.0,status,descentMeters=10.0)
    @Test fun weeksUseProfileTimezoneMondayAndFilterOwnersAndUnfinishedSessions() {
        val rides=listOf(ride(),ride(2,"2026-09-28T05:59:59Z"),ride(3,status="recording"),ride(4,status="interrupted"),ride(5,owner=2))
        val weeks=progressWeeks(rides,profile,LocalDate.parse("2026-09-28"))
        assertEquals(8,weeks.size)
        assertEquals(LocalDate.parse("2026-09-28"),weeks.last().start)
        assertEquals(listOf(1L),weeks.last().rides.map { it.id })
        assertEquals(listOf(2L),weeks[6].rides.map { it.id })
        assertEquals(1609.344,weeks.last().distance,.001)
    }
    @Test fun dstWeeksUseCalendarDates() {
        val weeks=progressWeeks(listOf(ride(date="2026-03-09T05:59:59Z"),ride(2,"2026-03-09T06:00:00Z")),profile,LocalDate.parse("2026-03-09"))
        assertEquals(1,weeks[6].rides.size);assertEquals(1,weeks.last().rides.size)
    }
    @Test fun unknownDescentStaysUnknownAndEmptyWeeksRemainEmpty() {
        val weeks=progressWeeks(listOf(ride().copy(descentMeters=null),ride(2)),profile,LocalDate.parse("2026-09-28"))
        assertEquals(1,weeks.last().descentMissing);assertEquals(10.0,weeks.last().descent,.001)
        assertEquals(0,weeks.first().rides.size)
    }
    private fun samples(speed:Double=3.0)= (0..700).map { sec -> RideSample(sec*1000L,sec,sec*speed,speed,0.0,145,0.0) }
    @Test fun mileInterpolatesMeasuredDistanceInsteadOfWholeWorkoutAverage() {
        assertEquals(MILE_METERS/3.0,fastestMile(ride().copy(samples=samples()))!!,.001)
        assertNull(fastestMile(ride().copy(samples=samples().take(100))))
    }
    @Test fun fastestMileCannotBridgePauseGapOrCounterReset() {
        val input=samples()
        val paused=input.map { if(it.elapsedSec>=350) it.copy(timestamp=it.timestamp+60_000) else it }
        assertNull(fastestMile(ride().copy(samples=paused)))
        assertNull(fastestMile(ride().copy(samples=input.filter { it.elapsedSec !in 300..400 })))
        assertNull(fastestMile(ride().copy(samples=input.map { if(it.elapsedSec>=350) it.copy(distanceMeters=it.distanceMeters-1050) else it })))
    }
    @Test fun stoppedOrInvalidSpeedBreaksContinuousMile() {
        assertNull(fastestMile(ride().copy(samples=samples().map { if(it.elapsedSec==350) it.copy(speedMps=null) else it })))
    }
    private fun base(id:Long,mph:Double=5.0,hr:Int=145,incline:Double=0.0)=ride(id).copy(
        plannedWorkout=SavedWorkout("base",1,"Base run",listOf(PlannedStep("Run",1000,140,150))),
        samples=(0..700).map { sec -> RideSample(sec*1000L,sec,sec*mph*.44704,mph*.44704,incline,hr,0.0,0) })
    @Test fun paceNeedsTwoSteadyBaseRunsAndExcludesHikesAndOtherHrBands() {
        assertNull(basePace(listOf(base(1)),140).mph)
        val result=basePace(listOf(base(1),base(2,6.0),base(3,9.0,165),base(4,8.0).copy(hikeName="Trail"),base(5,8.0,incline=3.0)),140)
        assertEquals(2,result.runs);assertEquals(5.5,result.mph!!,.001);assertEquals(145.0,result.hr!!,.001)
    }
    @Test fun warmupAndRecoveryDoNotContributeToBaseComparison() {
        val warm=base(1).let { r -> r.copy(samples=r.samples.map { it.copy(warmup=true) }) }
        val recovery=base(2).let { r -> r.copy(plannedWorkout=r.plannedWorkout!!.copy(steps=listOf(PlannedStep("Recovery",1000,140,150)))) }
        assertEquals(0,basePace(listOf(warm,recovery),140).runs)
    }
}
