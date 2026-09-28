package dev.digitalducktape.openrun

import org.junit.Assert.*
import org.junit.Test

class PlannedWorkoutTest {
    private fun assertTarget(expected:Target,actual:Target?) {
        assertNotNull(actual);assertEquals(expected.javaClass,actual!!.javaClass);assertEquals(expected.value,actual.value,.001)
    }

    private fun t(speed:Double=6.0,incline:Double=1.0)=Telemetry(speed,incline,0.0,1)
    private fun guide(steps:List<PlannedStep> = listOf(PlannedStep("Base",600,140,168,startMph=6.0)),max:Double=6.0) = PlannedWorkout(SavedWorkout("x",1,"Base",steps,maxMph=max)).also { it.resume(0) }
    private fun tick(g:PlannedWorkout,now:Long,hr:Int?=130,speed:Double=6.0,incline:Double=1.0,busy:Boolean=false)=g.tick(now,t(speed,incline),hr,true,false,busy)
    @Test fun `low HR uses incline first then speed at three percent and observes settling`() {
        val g=guide(max=10.0)
        tick(g,0);assertNull(tick(g,10000));assertNull(tick(g,30000))
        val target=tick(g,40000)
        assertTrue(target is Target.Incline);assertEquals(1.5,target!!.value,.001)
        g.confirmed(45000,t(6.0,1.5));tick(g,45000,incline=1.5)
        assertNull(tick(g,55000,incline=1.5));assertNull(tick(g,65000,incline=1.5))
        assertEquals(2.0,tick(g,75000,incline=1.5)!!.value,.001)
        g.confirmed(80000,t(6.0,3.0));tick(g,80000,incline=3.0)
        assertEquals(6.1,tick(g,110000,incline=3.0)!!.value,.001)
    }
    @Test fun `high HR reduces incline before speed and never ramps back to initial pace`() {
        val g=guide();tick(g,0,180,incline=2.0)
        assertTarget(Target.Incline(1.5),tick(g,10000,180,incline=2.0))
        g.confirmed(15000,t(6.0,1.0));tick(g,15000,180)
        assertTarget(Target.Speed(5.9),tick(g,45000,180))
        g.confirmed(50000,t(5.9,1.0));tick(g,50000,154,5.9)
        assertNull(tick(g,90000,154,5.9))
    }
    @Test fun `initial ramp is gradual and in-range HR stops further increases`() {
        val g=guide();tick(g,0,speed=2.0)
        assertTarget(Target.Speed(2.2),tick(g,10000,speed=2.0))
        g.confirmed(12000,t(2.2));tick(g,12000,154,2.2)
        assertNull(tick(g,22000,154,2.2))
        assertNull(tick(g,60000,154,2.2))
    }
    @Test fun `cap and three percent incline are never exceeded`() {
        val g=guide();tick(g,0);tick(g,10000)
        g.confirmed(20000,t(6.0,3.0));tick(g,20000,incline=3.0)
        assertNull(tick(g,60000,incline=3.0))
        assertFalse(g.halted)
    }
    @Test(expected=IllegalArgumentException::class) fun `saved speed beyond user ceiling is rejected`() {
        PlannedWorkout(SavedWorkout("x",1,"Tempo",listOf(PlannedStep("Run",300,168,178)),maxMph=10.1))
    }
    @Test fun `HR loss holds ramp and requires fresh ten second window`() {
        val g=guide();tick(g,0,speed=2.0);assertNull(tick(g,10000,null,2.0));assertNull(tick(g,11000,speed=2.0));assertNull(tick(g,20000,speed=2.0))
        assertTarget(Target.Speed(2.2),tick(g,21000,speed=2.0))
    }
    @Test fun `pause freezes interval and completion never starts another step`() {
        val g=guide(listOf(PlannedStep("Run",20,120,140),PlannedStep("Cool",10,120,140)))
        tick(g,0,130);tick(g,9000,130);g.pause()
        g.tick(50000,t(0.0),130,true,true,false)
        assertEquals(9000,g.elapsedMs)
        g.resume(60000);tick(g,71000,130);assertEquals(1,g.index)
        tick(g,81000,130);assertTrue(g.complete);assertNull(tick(g,90000))
    }
    @Test fun `manual hold expires only on next prescribed interval`() {
        val g=guide(listOf(PlannedStep("Run",20,140,168,startMph=6.0),PlannedStep("Run",60,140,168,startMph=6.0)))
        tick(g,0,speed=2.0);g.manual();assertNull(tick(g,15000,speed=2.0));assertNull(tick(g,20000,speed=2.0))
        assertTarget(Target.Speed(2.2),tick(g,30000,speed=2.0))
    }
    @Test fun `stopped belt lost telemetry and over limit incline latch automation off`() {
        for((telemetry,fresh) in listOf(t(0.0) to true,t() to false,t(2.0,4.0) to true)) {
            val g=guide();assertNull(g.tick(0,telemetry,120,fresh,false,false));assertTrue(g.halted)
            assertNull(tick(g,30000));assertEquals(0,g.elapsedMs)
        }
    }
    @Test fun `busy command never issues another command`() {
        val g=guide();tick(g,0);assertNull(tick(g,10000,busy=true));assertNull(tick(g,11000))
    }
}
