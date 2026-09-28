package dev.digitalducktape.openrun

import org.junit.Assert.*
import org.junit.Test

class ManualTargetsTest {
    @Test fun rapidTapsKeepOnlyLatestTargetPerAxis() {
        var pending=listOf<Target>(Target.Speed(2.1),Target.Incline(1.0))
        repeat(10) { pending=coalesceManualTarget(pending,Target.Speed(2.2+it*0.1)) }
        assertEquals(2,pending.size)
        assertEquals(3.1,pending[0].value,0.00001)
        assertEquals(1.0,pending[1].value,0.00001)
    }
    @Test fun confirmingOldCommandPreservesNewerRequest() {
        val sent=Target.Speed(2.1)
        val newer=Target.Speed(2.2)
        val pending=coalesceManualTarget(listOf(sent),newer).filterNot { it === sent }
        assertSame(newer,pending.single())
    }
}
