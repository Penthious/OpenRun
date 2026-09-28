package dev.digitalducktape.openrun

import org.junit.Assert.*
import org.junit.Test

class ZoneTwoTest {
    private fun t(speed: Double=2.0,incline: Double=0.0)=Telemetry(speed,incline,0.0,1)
    private fun ready(t: Telemetry=t()): ZoneTwo = ZoneTwo().also { it.enable(0); it.tick(0,t,130,true,false) }
    @Test fun `starts disabled and warms up before adjustments`() {
        val z=ZoneTwo(); assertNull(z.tick(100000,t(),110,true,false))
        z.enable(100000); assertNull(z.tick(100000,t(),110,true,false)); assertNull(z.tick(189999,t(),110,true,false))
        assertTrue(z.tick(190000,t(),110,true,false) is Target.Incline)
    }
    @Test fun `low HR raises incline first then speed only at incline cap`() {
        val a=ready().tick(90000,t(),110,true,false)!!
        assertTrue(a is Target.Incline); assertEquals(0.5,a.value,0.0)
        val b=ready(t(2.0,20.0)).tick(90000,t(2.0,20.0),110,true,false)!!
        assertTrue(b is Target.Speed); assertEquals(2.1,b.value,0.0001)
    }
    @Test fun `high HR lowers incline first then speed`() {
        val a=ready(t(3.0,10.0)).tick(90000,t(3.0,10.0),150,true,false)!!
        assertTrue(a is Target.Incline); assertEquals(9.5,a.value,0.0)
        val b=ready(t(3.0,0.0)).tick(90000,t(3.0,0.0),150,true,false)!!
        assertTrue(b is Target.Speed); assertEquals(2.9,b.value,0.0001)
    }
    @Test fun `target range and limits hold without commands`() {
        for(hr in listOf(120,130,140)) assertNull(ready().tick(90000,t(),hr,true,false))
        assertNull(ready(t(4.0,20.0)).tick(90000,t(4.0,20.0),110,true,false))
        assertNull(ready().tick(90000,t(),150,true,false))
    }
    @Test fun `manual adjustment holds for full sixty seconds`() {
        val z=ready(); z.manual(100000)
        assertNull(z.tick(159999,t(),110,true,false))
        assertTrue(z.tick(160000,t(),110,true,false) is Target.Incline)
    }
    @Test fun `physical tenth mph change triggers override`() {
        val z=ready(); assertNull(z.tick(100000,t(2.1),110,true,false))
        assertTrue(z.status.contains("Manual override"))
        assertNull(z.tick(159999,t(2.1),110,true,false))
        assertTrue(z.tick(160000,t(2.1),110,true,false) is Target.Incline)
    }
    @Test fun `HR loss holds and waits for reliable signal after reconnect`() {
        val z=ready()
        assertNull(z.tick(90000,t(),null,true,false)); assertTrue(z.enabled)
        assertNull(z.tick(200000,t(),110,true,false))
        assertNull(z.tick(214999,t(),110,true,false))
        assertTrue(z.tick(215000,t(),110,true,false) is Target.Incline)
    }
    @Test fun `physical stop and disconnected treadmill require explicit reenable`() {
        val z=ready(); assertNull(z.tick(90000,t(0.0),110,true,false)); assertFalse(z.enabled)
        assertNull(z.tick(100000,t(),110,true,false))
        val lost=ready(); assertNull(lost.tick(90000,t(),110,false,false)); assertFalse(lost.enabled)
    }
    @Test fun `ramp toward own target is not manual override and waits forty five seconds`() {
        val z=ready(); assertNotNull(z.tick(90000,t(),110,true,false))
        assertNull(z.tick(91000,t(incline=.2),110,true,false))
        assertNull(z.tick(92000,t(incline=.5),110,true,false))
        assertFalse(z.status.contains("Manual override"))
        assertNull(z.tick(134999,t(incline=.5),110,true,false))
        assertEquals(1.0,z.tick(135000,t(incline=.5),110,true,false)!!.value,0.0)
    }
    @Test fun `paused and out of bounds cannot increase settings`() {
        assertNull(ready().tick(90000,t(),110,true,true))
        val z=ready(); assertNull(z.tick(90000,t(incline=25.0),110,true,false)); assertFalse(z.enabled)
    }
    @Test fun `unreached target disables automation instead of repeated commands`() {
        val z=ready(); z.tick(90000,t(),110,true,false)
        assertNull(z.tick(150000,t(),110,true,false)); assertFalse(z.enabled)
    }
}
