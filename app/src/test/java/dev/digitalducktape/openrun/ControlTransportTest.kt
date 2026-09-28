package dev.digitalducktape.openrun

import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Local fake bridge only: never connects to treadmill hardware. */
class ControlTransportTest {
    private fun fixture(result: Int, task: (TreadmillControl)->Unit): List<Int> {
        val seen=mutableListOf<Int>()
        ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout=5000
            val executor=Executors.newSingleThreadExecutor()
            val future=executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout=5000
                    val input=DataInputStream(socket.getInputStream()); val out=socket.getOutputStream()
                    repeat(3) { index ->
                        val header=ByteArray(6); input.readFully(header)
                        val size=((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
                        val payload=ByteArray(size); input.readFully(payload)
                        assertArrayEquals(Ftms.uuid(0x2ad9),payload.copyOfRange(0,16))
                        if(index==0) {
                            assertEquals(5,header[1].toInt())
                            out.write(ControlProtocol.packet(5,1,Ftms.uuid(0x2ad9)))
                        } else {
                            assertEquals(4,header[1].toInt())
                            val op=payload[16].toInt(); seen+=op
                            out.write(ControlProtocol.packet(4,index+1,Ftms.uuid(0x2ad9)))
                            out.write(ControlProtocol.packet(6,0,Ftms.uuid(0x2ad9)+byteArrayOf(-128,op.toByte(),if(index==1) 1 else result.toByte())))
                        }
                    }
                }
            }
            try { task(TreadmillControl(port=server.localPort)); future.get(8,TimeUnit.SECONDS) }
            finally { executor.shutdownNow() }
        }
        return seen
    }
    @Test fun `pause and end use dedicated workout API without FTMS speed zero`() = runBlocking {
        val calls=mutableListOf<Boolean>()
        val control=TreadmillControl(port=1,stopWorkout={ calls+=it })
        control.send(Target.Speed(0.0))
        control.send(Target.Speed(0.0),endWorkout=true)
        assertEquals(listOf(false,true),calls)
    }
    @Test fun `dedicated stop rejection propagates without speed fallback`() = runBlocking {
        val control=TreadmillControl(port=1,stopWorkout={ error("rejected") })
        try { control.send(Target.Speed(0.0)); fail("Stop rejection swallowed") } catch(_:IllegalStateException) { }
    }
    @Test fun `subscribes requests control then sends target exactly once`() {
        val seen=fixture(1) { runBlocking { it.send(Target.Speed(2.0)) } }
        assertEquals(listOf(0,2),seen)
    }
    @Test fun `reject is surfaced without replaying movement command`() {
        val seen=fixture(5) {
            try { runBlocking { it.send(Target.Speed(2.0)) }; fail("Rejected movement reported successful") }
            catch(_:IllegalStateException) { }
        }
        assertEquals(listOf(0,2),seen)
    }
}
