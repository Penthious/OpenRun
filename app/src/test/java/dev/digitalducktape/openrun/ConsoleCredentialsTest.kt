package dev.digitalducktape.openrun
import java.io.ByteArrayOutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.junit.Assert.*
import org.junit.Test

class ConsoleCredentialsTest {
    private fun zip(files:Map<String,ByteArray>)=ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (name,data) -> zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry() } }
    }.toByteArray().inputStream()
    private val files get()=ConsoleCredentials.names.associateWith { "fixture".toByteArray() }
    @Test fun readsOnlyExpectedNames() { assertEquals(ConsoleCredentials.names,ConsoleCredentials.parse(zip(files)).keys) }
    @Test fun rejectsMissingFiles() { assertThrows(IllegalArgumentException::class.java) { ConsoleCredentials.parse(zip(files.filterKeys { it!="glassos_client_key.pem" })) } }
    @Test fun rejectsPathTraversal() { assertThrows(IllegalArgumentException::class.java) { ConsoleCredentials.parse(zip(files+("../secret" to byteArrayOf(1)))) } }
    @Test fun rejectsOversizedExpandedContent() { assertThrows(IllegalArgumentException::class.java) { ConsoleCredentials.parse(zip(files+("glassos_ca.pem" to ByteArray(32769)))) } }
}
