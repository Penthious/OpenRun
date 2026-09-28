package dev.digitalducktape.openrun

import android.content.Context
import android.util.Log
import io.grpc.CallOptions
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal codec for the GlassOS WorkoutService messages; no motor commands in queries. */
object WorkoutWire {
    data class Field(val number:Int,val integer:Long?=null,val bytes:ByteArray?=null)
    fun fields(data:ByteArray):List<Field> {
        var offset=0
        fun varint():Long {
            var result=0L
            for(shift in 0..63 step 7) {
                require(offset<data.size) { "Truncated GlassOS response" }
                val b=data[offset++].toInt() and 255
                result=result or ((b and 127).toLong() shl shift)
                if(b and 128==0) return result
            }
            error("Invalid GlassOS response")
        }
        val fields=mutableListOf<Field>()
        while(offset<data.size) {
            val tag=varint().toInt(); require(tag ushr 3>0)
            when(tag and 7) {
                0 -> fields+=Field(tag ushr 3,integer=varint())
                2 -> { val size=varint(); require(size>=0 && size<=data.size-offset); val end=offset+size.toInt(); fields+=Field(tag ushr 3,bytes=data.copyOfRange(offset,end)); offset=end }
                1 -> { require(offset+8<=data.size); offset+=8 }
                5 -> { require(offset+4<=data.size); offset+=4 }
                else -> error("Unsupported GlassOS response encoding")
            }
        }
        return fields
    }
    fun success(data:ByteArray):Boolean {
        val result=fields(data).lastOrNull { it.number==1 || it.number==2 }
        return result?.number==2 && result.integer==1L
    }
    fun state(data:ByteArray):Int = fields(data).lastOrNull { it.number==1 }?.integer?.toInt() ?: 0
}

/** Dedicated workout lifecycle operations, separate from speed-target changes. */
class GlassOsWorkout(private val context:Context) {
    private val channel by lazy {
        val factory=CertificateFactory.getInstance("X.509")
        fun certificate(name:String)=ByteArrayInputStream(ConsoleCredentials.read(context,name)).use { factory.generateCertificate(it) }
        val ca=certificate("glassos_ca.pem")
        val client=certificate("glassos_client_cert.pem")
        val pem=ConsoleCredentials.read(context,"glassos_client_key.pem").toString(Charsets.UTF_8)
        val encoded=pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replace(Regex("\\s"),"")
        val key=KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(android.util.Base64.decode(encoded,android.util.Base64.DEFAULT)))
        val trust=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null,null); setCertificateEntry("console",ca) }
        val identity=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null,null); setKeyEntry("client",key,charArrayOf(),arrayOf(client)) }
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(identity,charArrayOf()) }
        val tls=SSLContext.getInstance("TLS").apply { init(km.keyManagers,tm.trustManagers,null) }
        val metadata=Metadata().apply { put(Metadata.Key.of("client_id",Metadata.ASCII_STRING_MARSHALLER),"com.ifit.dev_app") }
        OkHttpChannelBuilder.forAddress("localhost",54321).sslSocketFactory(tls.socketFactory).overrideAuthority("localhost")
            .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata)).build()
    }
    private val bytes=object:MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value:ByteArray):InputStream=ByteArrayInputStream(value)
        override fun parse(stream:InputStream):ByteArray {
            val result=java.io.ByteArrayOutputStream(); val buffer=ByteArray(1024)
            while(true) { val count=stream.read(buffer); if(count<0) break; require(result.size()+count<=65536); result.write(buffer,0,count) }
            return result.toByteArray()
        }
    }
    private fun call(name:String):ByteArray {
        val method=MethodDescriptor.newBuilder<ByteArray,ByteArray>().setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("com.ifit.glassos.WorkoutService/$name").setRequestMarshaller(bytes).setResponseMarshaller(bytes).build()
        return ClientCalls.blockingUnaryCall(channel,method,CallOptions.DEFAULT.withDeadlineAfter(3,TimeUnit.SECONDS),byteArrayOf())
    }
    suspend fun state():Int=withContext(Dispatchers.IO) { WorkoutWire.state(call("GetWorkoutState")) }
    suspend fun stop(end:Boolean)=withContext(Dispatchers.IO) {
        val operation=if(end) "Stop" else "Pause"
        Log.i("OpenRunControl","Requesting dedicated workout $operation")
        val accepted=WorkoutWire.success(call(operation))
        Log.i("OpenRunControl","Workout $operation accepted=$accepted")
        check(accepted) { "GlassOS rejected workout $operation" }
    }
    suspend fun prepareToRun()=withContext(Dispatchers.IO) {
        when(val current=WorkoutWire.state(call("GetWorkoutState"))) {
            1,5 -> {
                val result=WorkoutWire.fields(call("StartNewWorkout")).firstOrNull { it.number==1 }?.bytes
                check(result!=null && WorkoutWire.success(result)) { "GlassOS rejected workout start" }
            }
            4 -> check(WorkoutWire.success(call("Resume"))) { "GlassOS rejected workout resume" }
            3 -> Unit
            else -> error("Console is not ready to run (state $current)")
        }
    }
}
