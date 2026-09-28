package dev.digitalducktape.openrun
import org.junit.Assert.*
import org.junit.Test
class ControlProtocolTest {
    @Test fun `two mph converts to kph hundredths`() {
        assertArrayEquals(byteArrayOf(2,66,1),ControlProtocol.command(Target.Speed(2.0)))
        assertArrayEquals(byteArrayOf(2,73,6),ControlProtocol.command(Target.Speed(10.0)))
        assertArrayEquals(byteArrayOf(3,-56,0),ControlProtocol.command(Target.Incline(20.0)))
    }
    @Test fun `out of bounds and nonfinite targets rejected`() {
        listOf(Target.Speed(0.0),Target.Speed(10.1),Target.Speed(-.1),Target.Incline(20.1),Target.Incline(-.5),Target.Speed(Double.NaN)).forEach { target ->
            try { ControlProtocol.command(target); fail("Accepted unsafe target") } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun `requires actual FTMS control success not transport acknowledgment`() {
        assertTrue(ControlProtocol.result(Ftms.uuid(0x2ad9)+byteArrayOf(-128,2,1),2))
        assertFalse(ControlProtocol.result(Ftms.uuid(0x2ad9)+byteArrayOf(-128,2,5),2))
        try { ControlProtocol.result(Ftms.uuid(0x2ad9),2); fail() } catch(_:IllegalArgumentException) { }
        try { ControlProtocol.result(Ftms.uuid(0x2ad9)+byteArrayOf(-128,3,1),2); fail() } catch(_:IllegalArgumentException) { }
    }
    @Test fun `manual changes during ramp relinquish adjustment`() {
        val origin=Telemetry(mph=2.0,incline=1.0)
        assertFalse(Target.Incline(1.5).overridden(origin,Telemetry(mph=2.0,incline=1.2)))
        assertTrue(Target.Incline(1.5).overridden(origin,Telemetry(mph=2.1,incline=1.2)))
        assertTrue(Target.Incline(1.5).overridden(origin,Telemetry(mph=2.0,incline=2.0)))
        assertTrue(Target.Speed(2.1).overridden(origin,Telemetry(mph=2.05,incline=1.5)))
    }
    @Test fun `stopped confirmation does not accept a moving belt`() {
        assertFalse(Target.Speed(0.0).matches(Telemetry(mph=.1)))
        assertFalse(Target.Speed(0.0).matches(Telemetry()))
        assertTrue(Target.Speed(0.0).matches(Telemetry(mph=0.0)))
    }
}
