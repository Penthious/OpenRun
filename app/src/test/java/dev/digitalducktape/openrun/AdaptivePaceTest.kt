package dev.digitalducktape.openrun
import org.junit.Assert.*
import org.junit.Test

class AdaptivePaceTest {
    private val now=1_800_000_000_000L
    private fun segments(mph:Double,indoor:Boolean=true)=listOf("a","b").flatMap { id -> (1..4).map { PaceSegment(id,now-it*1000,mph,154.0,indoor) } }
    @Test fun `steady running used while warmup walking HR gaps hills and pauses excluded`() {
        fun points()=(0..180).map { PacePoint(now+it*1000,5.0,150,1.0) }
        assertEquals(2,AdaptivePace.segments("x",true,points()).size)
        assertTrue(AdaptivePace.segments("x",true,points().map { it.copy(eligible=false) }).isEmpty())
        assertTrue(AdaptivePace.segments("x",true,points().map { it.copy(mph=2.0) }).isEmpty())
        assertTrue(AdaptivePace.segments("x",true,points().map { it.copy(hr=null) }).isEmpty())
        assertTrue(AdaptivePace.segments("x",false,points().map { it.copy(incline=4.0) }).isEmpty())
        assertTrue(AdaptivePace.segments("x",true,points().filterIndexed { i,_ -> i%30<10 }).isEmpty())
    }
    @Test fun `requires two runs prefers treadmill and limits each learned change`() {
        assertNull(AdaptivePace.learn(140,168,segments(5.0).filter { it.activity=="a" },null,now))
        val initial=AdaptivePace.learn(140,168,segments(5.0)+segments(8.0,false).map { it.copy(activity="outdoor-${it.activity}") },null,now)!!
        assertEquals(5.0,initial.mph,.001)
        val next=AdaptivePace.learn(140,168,segments(6.0),initial,now)!!
        assertEquals(5.3,next.mph,.001)
        assertNull(AdaptivePace.learn(140,168,segments(5.0).map { it.copy(timestamp=now-AdaptivePace.AGE_MS-1) },initial,now))
    }
    @Test fun `explicit pace wins over learned pace and selected cap never grows`() {
        val h=PaceHistory(1,"a",learned=listOf(LearnedPace(140,168,5.2,154.0,3)))
        val plan=SavedWorkout("x",1,"Base",listOf(PlannedStep("run",1440,140,168)),maxMph=6.0)
        assertEquals(5.2,AdaptivePace.prepare(plan,h).steps.single().startMph!!,.001)
        assertEquals(6.0,AdaptivePace.prepare(plan,null).steps.single().startMph!!,.001)
        val pace=plan.copy(steps=listOf(PlannedStep("run",1440,paceLowMph=7.0,paceHighMph=8.0)))
        assertEquals(6.0,AdaptivePace.prepare(pace,h).steps.single().startMph!!,.001)
        assertEquals(6.0,AdaptivePace.prepare(pace,h).maxMph,.001)
    }
}
