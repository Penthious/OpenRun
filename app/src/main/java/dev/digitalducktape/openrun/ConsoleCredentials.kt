package dev.digitalducktape.openrun

import android.content.Context
import android.util.AtomicFile
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/** Credentials stay on this console, outside Android backup and the distributed APK. */
object ConsoleCredentials {
    val names=setOf("glassos_ca.pem","glassos_client_cert.pem","glassos_client_key.pem")
    private fun file(context:Context)=AtomicFile(File(context.noBackupFilesDir,"console-credentials.zip"))
    fun installed(context:Context)=file(context).baseFile.exists()
    fun read(context:Context,name:String):ByteArray {
        require(name in names)
        val stored=file(context)
        if(stored.baseFile.exists()) return stored.openRead().use { parse(it).getValue(name) }
        if(BuildConfig.DEBUG) return context.assets.open("certs/$name").use { it.readBytes() }
        error("Import console credentials in Settings before using treadmill controls.")
    }
    internal fun parse(input:InputStream):Map<String,ByteArray> {
        val found=mutableMapOf<String,ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry=zip.nextEntry
            while(entry!=null) {
                require(!entry.isDirectory && entry.name in names && entry.name !in found) { "Archive must contain only the three named PEM files." }
                val buffer=ByteArrayOutputStream()
                val chunk=ByteArray(4096)
                while(true) {
                    val count=zip.read(chunk)
                    if(count<0) break
                    require(buffer.size()+count<=32*1024) { "Credential file too large." }
                    buffer.write(chunk,0,count)
                }
                val data=buffer.toByteArray()
                require(data.size<=32*1024) { "Credential file too large." }
                found[entry.name]=data
                entry=zip.nextEntry
            }
        }
        require(found.keys==names) { "All three console credential files are required." }
        return found
    }
    fun install(context:Context,input:InputStream) {
        val files=parse(input)
        val factory=CertificateFactory.getInstance("X.509")
        factory.generateCertificate(ByteArrayInputStream(files.getValue("glassos_ca.pem")))
        val client=factory.generateCertificate(ByteArrayInputStream(files.getValue("glassos_client_cert.pem")))
        val pem=files.getValue("glassos_client_key.pem").toString(Charsets.UTF_8)
        val encoded=pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replace(Regex("\\s"),"")
        val key=KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(android.util.Base64.decode(encoded,android.util.Base64.DEFAULT)))
        val challenge=ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val signature=Signature.getInstance("SHA256withRSA").run { initSign(key);update(challenge);sign() }
        require(Signature.getInstance("SHA256withRSA").run { initVerify(client.publicKey);update(challenge);verify(signature) }) { "Certificate and key do not match." }
        val encodedZip=ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip -> files.forEach { (name,data) -> zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry() } }
        }.toByteArray()
        val store=file(context); val output=store.startWrite()
        try { output.write(encodedZip);store.finishWrite(output) } catch(e:Exception) { store.failWrite(output);throw e }
    }
}
