package dev.digitalducktape.openrun
import org.junit.Assert.*
import org.junit.Test

class WarmupGuideTest {
    private fun assertTarget(expected:Target,actual:Target?) {
        assertNotNull(actual);assertEquals(expected.javaClass,actual!!.javaClass);assertEquals(expected.value,actual.value,.001)
    }

    private fun t(speed:Double=2.0,incline:Double=1.0)=Telemetry(speed,incline,0.0,1)
    @Test fun `Zone Two handoff checks HR without another ninety second warmup`() {
        val z=ZoneTwo();z.enable(0,warmedUp=true)
        assertNull(z.tick(0,t(),110,true,false))
        assertTarget(Target.Incline(1.5),z.tick(15000,t(),110,true,false))
    }
    @Test fun `stage goals never fall backwards for a slow jog`() {
        val w=WarmupGuide(3.5,140);w.resume(0)
        var last=0.0
        for(stage in 0..4) {
            w.tick(stage*60000L,t(),120,true,false,true)
            assertTrue(w.goal>=last);assertTrue(w.goal<=3.5);last=w.goal
        }
    }
    @Test fun `five minute prelude advances stages pauses and can skip without belt command`() {
        val w=WarmupGuide(6.0,168);w.resume(0)
        assertNull(w.tick(59000,t(),120,true,false,false));assertEquals(0,w.stage)
        assertTarget(Target.Speed(2.2),w.tick(65000,t(),120,true,false,false))
        w.pause();w.tick(160000,t(0.0),120,true,true,false);assertEquals(65000,w.elapsed)
        w.resume(200000);w.tick(260000,t(),null,true,false,false);assertEquals(2,w.stage)
        w.skip();assertTrue(w.finished);assertNull(w.tick(270000,t(),120,true,false,false))
    }
    @Test fun `HR loss manual override and stopped belt never trigger warmup acceleration`() {
        val w=WarmupGuide(6.0,168);w.resume(0)
        assertNull(w.tick(61000,t(),null,true,false,false))
        w.manual();assertNull(w.tick(80000,t(),120,true,false,false))
        assertNull(w.tick(90000,t(0.0),120,true,false,false));assertTrue(w.halted)
    }
    @Test fun `completion occurs after exactly five active minutes`() {
        val w=WarmupGuide(6.0,168);w.resume(0)
        w.tick(299999,t(),120,true,false,true);assertFalse(w.finished)
        assertNull(w.tick(300000,t(),120,true,false,false));assertTrue(w.finished)
    }
    @Test fun `high HR reduction waits after confirmation`() {
        val w=WarmupGuide(6.0,168);w.resume(0)
        assertTarget(Target.Incline(1.5),w.tick(10000,t(4.0,2.0),180,true,false,false))
        w.confirmed(12000,t(4.0,1.5))
        assertNull(w.tick(22000,t(4.0,1.5),180,true,false,false))
        assertTarget(Target.Incline(1.0),w.tick(42000,t(4.0,1.5),180,true,false,false))
    }
}
