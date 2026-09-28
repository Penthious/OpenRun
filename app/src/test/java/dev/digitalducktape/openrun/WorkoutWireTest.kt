package dev.digitalducktape.openrun
import org.junit.Assert.*
import org.junit.Test
class WorkoutWireTest {
    @Test fun `only explicit Result success is accepted`() {
        assertTrue(WorkoutWire.success(byteArrayOf(16,1)))
        assertFalse(WorkoutWire.success(byteArrayOf()))
        assertFalse(WorkoutWire.success(byteArrayOf(16,0)))
        assertFalse(WorkoutWire.success(byteArrayOf(10,0)))
        assertFalse(WorkoutWire.success(byteArrayOf(16,1,10,0)))
    }
    @Test fun `paused and idle state decoded without mutation`() {
        assertEquals(4,WorkoutWire.state(byteArrayOf(8,4)))
        assertEquals(1,WorkoutWire.state(byteArrayOf(8,1)))
        assertEquals(0,WorkoutWire.state(byteArrayOf()))
    }
    @Test fun `nested start result is decoded`() {
        val fields=WorkoutWire.fields(byteArrayOf(10,2,16,1,18,1,65))
        assertTrue(WorkoutWire.success(fields.first().bytes!!))
    }
    @Test(expected=IllegalArgumentException::class) fun `truncated result rejected`() { WorkoutWire.fields(byteArrayOf(10,10,1)) }
}
