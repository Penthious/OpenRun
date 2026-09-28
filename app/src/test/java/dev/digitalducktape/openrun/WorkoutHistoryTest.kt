package dev.digitalducktape.openrun

import dev.digitalducktape.openrun.core.data.*
import org.junit.Assert.*
import org.junit.Test

class WorkoutHistoryTest {
    private fun sample(hr:Int?,speed:Double?=null,step:Int?=null,warmup:Boolean=false)=
        RideSample(0,1,0.0,speed,null,hr,0.0,step,warmup)
    @Test fun missingReadingsDoNotBecomeZero() {
        val result=historySummary(Ride(1,1,0,samples=listOf(sample(null),sample(120,0.89408),sample(0,Double.NaN))))
        assertEquals(120.0,result.averageHr!!,.001)
        assertEquals(2.0,result.averageMph!!,.001)
        assertEquals(0,result.targetReadings)
    }
    @Test fun targetComplianceUsesActualStepAndExcludesWarmupAndMissingHr() {
        val plan=SavedWorkout("x",1,"Intervals",listOf(PlannedStep("Easy",60,120,140),PlannedStep("Tempo",60,150,165)))
        val ride=Ride(1,1,0,plannedWorkout=plan,samples=listOf(
            sample(120,step=0),sample(140,step=0),sample(145,step=1),
            sample(160,step=1),sample(null,step=1),sample(100,step=0,warmup=true),
            sample(160),sample(160,step=8)
        ))
        val result=historySummary(ride)
        assertEquals(4,result.targetReadings)
        assertEquals(3,result.inTargetReadings)
    }
    @Test fun emptySessionHasNoInventedMetrics() {
        val result=historySummary(Ride(1,1,0))
        assertNull(result.averageHr);assertNull(result.maxMph);assertNull(result.maxIncline)
        assertEquals(0,result.targetReadings)
    }
}
