package dev.digitalducktape.openrun

import org.junit.Assert.*
import org.junit.Test
import dev.digitalducktape.openrun.core.data.*
import dev.digitalducktape.openrun.core.export.TcxExporter
import javax.xml.parsers.DocumentBuilderFactory

class TelemetryTest {
    @Test fun `decodes nordic treadmill speed distance and incline`() {
        val t=Ftms.parse(byteArrayOf(12,0,66,1,16,39,0,100,0,-1,127))
        assertEquals(2.00083,t.mph!!,0.002)
        assertEquals(10000.0,t.meters!!,0.0)
        assertEquals(10.0,t.incline!!,0.0)
    }
    @Test fun `missing and fragmented speed is not zero speed`() {
        assertNull(Ftms.parse(byteArrayOf(1,0)).mph)
        assertNull(Ftms.parse(byteArrayOf(0,0,0,0)).incline)
    }
    @Test(expected=IllegalArgumentException::class) fun `truncated telemetry is rejected`() { Ftms.parse(byteArrayOf(12,0,66,1)) }
    @Test fun `signed decline is preserved`() { assertEquals(-1.0,Ftms.parse(byteArrayOf(8,0,0,0,-10,-1,0,0)).incline!!,0.0) }
    @Test fun `counter reset and reconnect gap do not inflate session`() {
        assertEquals(0.0,sessionDistanceDelta(100.0,0.0,1000),0.0)
        assertEquals(0.0,sessionDistanceDelta(null,100.0,1000),0.0)
        assertEquals(0.0,sessionDistanceDelta(5.0,1000.0,1000),0.0)
        assertEquals(1.0,sessionDistanceDelta(100.0,101.0,1000),0.0)
    }
    @Test fun `heart rate honors contact and uint16 flags`() {
        assertEquals(130,decodeHeartRate(byteArrayOf(0,130.toByte())))
        assertEquals(140,decodeHeartRate(byteArrayOf(1,140.toByte(),0)))
        assertNull(decodeHeartRate(byteArrayOf(4,130.toByte())))
        assertNull(decodeHeartRate(byteArrayOf(1,10)))
    }
    @Test fun `keepalive only discovers services`() { assertArrayEquals(byteArrayOf(1,1,2,0,0,0),Ftms.keepAlive(2)) }
    @Test fun `read only subscription does not request control`() {
        val packet=Ftms.subscription()
        assertEquals(5,packet[1].toInt()); assertEquals(17,packet[5].toInt())
        assertArrayEquals(Ftms.uuid(0x2acd),packet.copyOfRange(6,22))
    }
    @Test fun `running TCX includes real distance HR and speed without fake GPS`() {
        val r=Ride(1,2,1700000000000,60,50.0,1.0,"complete")
        val xml=TcxExporter.export(r,listOf(RideSample(1700000001000,1,1.0,0.9,2.0,130,0.02)))
        val doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("Running",doc.getElementsByTagName("Activity").item(0).attributes.getNamedItem("Sport").nodeValue)
        assertEquals("130",doc.getElementsByTagName("Value").item(0).textContent)
        assertFalse(xml.contains("Position")); assertFalse(xml.contains("AltitudeMeters"))
    }
}
