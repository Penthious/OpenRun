package dev.digitalducktape.openrun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

sealed class Target(val value: Double) {
    class Speed(mph: Double): Target(mph)
    class Incline(percent: Double): Target(percent)
    fun overridden(origin: Telemetry, current: Telemetry): Boolean {
        val start = if(this is Speed) origin.mph else origin.incline
        val observed = if(this is Speed) current.mph else current.incline
        if(start==null || observed==null) return false
        val otherChanged=if(this is Speed) origin.incline!=null && current.incline!=null && kotlin.math.abs(origin.incline-current.incline)>0.08
            else origin.mph!=null && current.mph!=null && kotlin.math.abs(origin.mph-current.mph)>0.05
        val tolerance=if(this is Speed) 0.08 else 0.15
        return otherChanged || observed<minOf(start,value)-tolerance || observed>maxOf(start,value)+tolerance
    }
    fun matches(t: Telemetry): Boolean = when(this) {
        is Speed -> t.mph?.let { kotlin.math.abs(it-value) <= if(value==0.0) 0.05 else 0.08 } ?: false
        is Incline -> t.incline?.let { kotlin.math.abs(it-value) <= 0.15 } ?: false
    }
}
object ControlProtocol {
    fun command(target: Target): ByteArray {
        require(target.value.isFinite())
        val raw=when(target) {
            is Target.Speed -> { require(target.value > 0.0 && target.value <= 10.0); (target.value*1.609344*100).roundToInt() }
            is Target.Incline -> { require(target.value in 0.0..20.0); (target.value*10).roundToInt() }
        }
        return byteArrayOf(if(target is Target.Speed) 2 else 3,raw.toByte(),(raw shr 8).toByte())
    }
    fun packet(type: Int, sequence: Int, data: ByteArray) = byteArrayOf(1,type.toByte(),sequence.toByte(),0,(data.size shr 8).toByte(),data.size.toByte())+data
    fun result(payload: ByteArray, opcode: Int): Boolean {
        require(payload.size==19 && payload.copyOfRange(0,16).contentEquals(Ftms.uuid(0x2ad9))) { "Malformed treadmill acknowledgment" }
        require((payload[16].toInt() and 255)==0x80 && (payload[17].toInt() and 255)==opcode) { "Unexpected treadmill acknowledgment" }
        return payload[18].toInt()==1
    }
}
/** Commands use a short-lived, serialized connection. No replay after reconnect or timeout. */
class TreadmillControl(private val host: String="127.0.0.1", private val port: Int=36866, private val stopWorkout: suspend (Boolean)->Unit = { error("Dedicated workout stop is unavailable") }) {
    private val mutex=Mutex()
    suspend fun send(target: Target, endWorkout: Boolean=false) = mutex.withLock {
        if(target is Target.Speed && target.value==0.0) {
            stopWorkout(endWorkout)
            return@withLock
        }
        val command=ControlProtocol.command(target)
        withContext(Dispatchers.IO) {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host,port),2000)
                socket.soTimeout=3000
                val input=DataInputStream(socket.getInputStream())
                val output=socket.getOutputStream()
                val uuid=Ftms.uuid(0x2ad9)
                output.write(ControlProtocol.packet(5,1,uuid+byteArrayOf(1)))
                val subscribed=readPacket(input)
                check(subscribed.first[1].toInt()==5 && subscribed.first[2].toInt()==1) { "Control subscription failed" }
                exchange(output,input,2,byteArrayOf(0))
                currentCoroutineContext().ensureActive()
                exchange(output,input,3,command)
            }
        }
    }
    private fun exchange(output: OutputStream,input: DataInputStream,seq: Int,command: ByteArray) {
        output.write(ControlProtocol.packet(4,seq,Ftms.uuid(0x2ad9)+command))
        var acknowledged=false
        repeat(8) {
            val (header,payload)=readPacket(input)
            if(header[1].toInt()==4 && (header[2].toInt() and 255)==seq) acknowledged=true
            if(header[1].toInt()==6 && payload.size>=16 && payload.copyOfRange(0,16).contentEquals(Ftms.uuid(0x2ad9))) {
                check(acknowledged && ControlProtocol.result(payload,command[0].toInt())) { "Treadmill rejected the command" }
                return
            }
        }
        error("Treadmill did not acknowledge the command")
    }
    private fun readPacket(input: DataInputStream): Pair<ByteArray,ByteArray> {
        val header=ByteArray(6); input.readFully(header)
        check(header[0].toInt()==1 && header[3].toInt()==0) { "Treadmill connection rejected the request" }
        val size=((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
        require(size<=4096)
        val data=ByteArray(size); input.readFully(data)
        return header to data
    }
}
