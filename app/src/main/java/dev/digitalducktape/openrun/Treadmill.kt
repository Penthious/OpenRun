package dev.digitalducktape.openrun

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID

/** FTMS telemetry only. This build never requests control or writes a control point. */
data class Telemetry(val mph: Double? = null, val incline: Double? = null, val meters: Double? = null, val receivedAt: Long = 0)
object Ftms {
    fun parse(bytes: ByteArray): Telemetry {
        var p = 0
        fun u8(): Int { require(p < bytes.size); return bytes[p++].toInt() and 255 }
        fun u16() = u8() or (u8() shl 8)
        val flags = u16()
        val speed = if (flags and 1 == 0) u16() / 100.0 / 1.609344 else null
        if (flags and 2 != 0) u16()
        val meters = if (flags and 4 != 0) (u8() or (u8() shl 8) or (u8() shl 16)).toDouble() else null
        val incline = if (flags and 8 != 0) { val n = u16().toShort() / 10.0; u16(); n } else null
        return Telemetry(speed, incline, meters)
    }
    fun uuid(id: Int): ByteArray { val u = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(id)); return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array() }
    fun keepAlive(sequence: Int): ByteArray = byteArrayOf(1,1,sequence.toByte(),0,0,0) // Discover services, never control.
    fun subscription(): ByteArray { val payload = uuid(0x2acd) + byteArrayOf(1); return byteArrayOf(1,5,1,0,0,payload.size.toByte()) + payload }
}
class Treadmill(private val scope: CoroutineScope) {
    val telemetry = MutableStateFlow(Telemetry())
    val status = MutableStateFlow("Connecting to NordicFTMS…")
    private var job: Job? = null
    private var socket: Socket? = null
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    Socket().use { s ->
                        socket = s; s.connect(InetSocketAddress("127.0.0.1",36866),3000); s.soTimeout = 5000
                        s.getOutputStream().write(Ftms.subscription())
                        val input = DataInputStream(s.getInputStream())
                        var lastKeepAlive = System.nanoTime()
                        var sequence = 1
                        while (isActive) {
                            if (System.nanoTime() - lastKeepAlive > 10_000_000_000L) {
                                sequence = (sequence + 1) and 255
                                s.getOutputStream().write(Ftms.keepAlive(sequence))
                                lastKeepAlive = System.nanoTime()
                            }
                            val header = ByteArray(6); input.readFully(header)
                            require(header[0].toInt() == 1)
                            val size = ((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
                            require(size <= 4096)
                            val payload = ByteArray(size); input.readFully(payload)
                            if (header[3].toInt() != 0) error("NordicFTMS rejected subscription")
                            if (header[1].toInt() == 6 && size >= 18 && payload.copyOfRange(0,16).contentEquals(Ftms.uuid(0x2acd))) {
                                val reading = Ftms.parse(payload.copyOfRange(16,size))
                                telemetry.value = reading.copy(receivedAt = android.os.SystemClock.elapsedRealtime())
                                status.value = "NordicFTMS connected · verify against console"
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { telemetry.value = Telemetry(); status.value = "No treadmill data · open NordicFTMS and enable DIRCON" }
                finally { socket = null }
                delay(3000)
            }
        }
    }
    fun stop() { job?.cancel(); runCatching { socket?.close() }; telemetry.value = Telemetry() }
}
