package dev.digitalducktape.openrun

import org.junit.Assert.*
import org.junit.Test

class HikeGuideTest {
    private fun guide(rise:Double=100.0,cap:Double=10.0)=HikeGuide(PendingHike(SavedHike("test","Test hike",TrailPreview(listOf(TrailPoint(0.0,0.0,100.0),TrailPoint(0.0,0.01,100.0+rise)))),1,cap))
    private val walking=Telemetry(mph=2.0,incline=0.0,meters=0.0,receivedAt=1)
    @Test fun followsDistanceAndClampsGrade() { assertEquals(5.0,guide(cap=5.0).grade(500.0),0.001);assertEquals(-6.0,guide(-100.0).grade(500.0),0.001) }
    @Test fun rampsOnePercentAsSoonAsPreviousCommandIsConfirmed() {
        val g=guide();g.resume(0)
        assertEquals(1.0,g.tick(0,0.0,walking,true,false,false)!!.value,0.0)
        assertNull(g.tick(1000,1.0,walking.copy(incline=0.5),true,false,true))
        g.confirmed(8000,1.0)
        assertEquals(2.0,g.tick(8000,30.0,walking.copy(incline=1.0),true,false,false)!!.value,0.0)
    }
    @Test fun initialInclineUsesFirstRouteGradeAndLimits() {
        assertEquals(-3.0,guide(-33.36).startingIncline,0.0)
        assertEquals(-6.0,guide(-100.0).startingIncline,0.0)
        assertEquals(5.0,guide(100.0,5.0).startingIncline,0.0)
    }
    @Test fun neverAdjustsWhilePausedBusyOrStale() {
        val g=guide()
        assertNull(g.tick(10000,100.0,walking,false,false,false))
        assertNull(g.tick(20000,100.0,walking,true,true,false))
        assertNull(g.tick(30000,100.0,walking,true,false,true))
    }
    @Test fun manualOverrideHoldsForSixtySeconds() {
        val g=guide();g.manual(10000)
        assertNull(g.tick(69999,100.0,walking,true,false,false))
        assertNotNull(g.tick(70000,100.0,walking,true,false,false))
    }
    @Test fun manualSpeedDoesNotHoldTerrain() {
        val g=guide();g.confirmed(1000,0.0)
        g.manual(Target.Speed(3.0),2000)
        assertNotNull(g.tick(3000,100.0,walking.copy(mph=3.0),true,false,false))
    }
    @Test fun manualInclineStillHoldsTerrain() {
        val g=guide();g.manual(Target.Incline(2.0),2000)
        assertNull(g.tick(3000,100.0,walking.copy(incline=2.0),true,false,false))
    }
    @Test fun speedChangeDuringInclineCommandDoesNotCancelItButStopDoes() {
        val target=Target.Incline(3.0)
        val origin=walking.copy(incline=1.0)
        assertFalse(target.overridden(origin,origin.copy(mph=3.0,incline=2.0),ignoreRunningSpeedChange=true))
        assertTrue(target.overridden(origin,origin.copy(mph=0.0,incline=2.0),ignoreRunningSpeedChange=true))
        assertTrue(target.overridden(origin,origin.copy(mph=3.0,incline=4.0),ignoreRunningSpeedChange=true))
        assertTrue(target.overridden(origin,origin.copy(mph=3.0,incline=2.0)))
    }
    @Test fun physicalInclineChangeTriggersHold() {
        val g=guide();g.confirmed(0,1.0)
        assertNull(g.tick(10000,100.0,walking.copy(incline=2.0),true,false,false))
        assertTrue(g.status.contains("Manual override"))
    }
    @Test fun finishIsDistanceBasedAndCannotRestart() {
        val g=guide();assertNull(g.tick(10000,g.length,walking,true,false,false));assertTrue(g.complete)
        g.resume(20000);assertNull(g.tick(30000,0.0,walking,true,false,false))
    }
    @Test fun stoppedBeltAndFailuresRequireExplicitResume() {
        val g=guide();assertNull(g.tick(10000,1.0,walking.copy(mph=0.0),true,false,false))
        assertNull(g.tick(20000,1.0,walking,true,false,false))
        g.resume(20000);assertNotNull(g.tick(30000,1.0,walking,true,false,false))
        g.halt();assertNull(g.tick(40000,1.0,walking,true,false,false))
    }
    @Test fun inclineOutsideChosenCapHaltsInsteadOfJumping() {
        val g=guide(cap=5.0)
        assertNull(g.tick(10000,100.0,walking.copy(incline=10.0),true,false,false))
        assertNull(g.tick(20000,100.0,walking,true,false,false))
    }
    @Test fun downhillRampsThroughZero() {
        val g=guide(-100.0,40.0)
        assertEquals(-1.0,g.tick(10000,100.0,walking,true,false,false)!!.value,0.0)
        g.confirmed(11000,-1.0)
        assertEquals(-2.0,g.tick(21000,100.0,walking.copy(incline=-1.0),true,false,false)!!.value,0.0)
    }
    @Test fun fullMachineRangeRespected() {
        assertEquals(40.0,guide(1000.0,40.0).grade(500.0),0.0)
        assertNull(guide(-100.0,40.0).tick(10000,100.0,walking.copy(incline=-6.0),true,false,false))
        assertNull(guide(1000.0,40.0).tick(10000,100.0,walking.copy(incline=40.0),true,false,false))
        assertNull(guide().tick(10000,100.0,walking.copy(incline=-6.1),true,false,false))
    }
    @Test fun invalidTelemetryCannotChangeIncline() { assertNull(guide().tick(10000,1.0,walking.copy(incline=Double.NaN),true,false,false)) }
    @Test(expected=IllegalArgumentException::class) fun missingElevationRejected() { HikeGuide(PendingHike(SavedHike("x","x",TrailPreview(listOf(TrailPoint(0.0,0.0,null),TrailPoint(0.0,0.1,null)))),1,10.0)) }
}
