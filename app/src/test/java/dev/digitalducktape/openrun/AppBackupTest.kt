package dev.digitalducktape.openrun

import dev.digitalducktape.openrun.core.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.zip.*

class AppBackupTest {
    private val json = Json { encodeDefaults = true }
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
    }.toByteArray()
    private val format = "format.txt" to "openrun-backup-1".toByteArray()
    private fun state(s: SavedState) = "workouts.json" to json.encodeToString(s).toByteArray()
    @Test fun roundTripRetainsProfilesHistoryAndRoutesButInterruptsRecording() {
        val directory = Files.createTempDirectory("hikes").toFile()
        try {
            val gpx = """<gpx><trk><trkseg><trkpt lat="39" lon="-105"><ele>100</ele></trkpt><trkpt lat="39.001" lon="-105"><ele>101</ele></trkpt></trkseg></trk></gpx>""".toByteArray()
            val id = sha256(gpx)
            File(directory, "$id.gpx").writeBytes(gpx); File(directory, "$id.txt").writeText("Test trail")
            val original = SavedState(profiles = listOf(Profile(1, "Runner")), selectedId = 1, rides = listOf(Ride(10, 1, 10, descentMeters = 3.5)))
            val buffer = ByteArrayOutputStream(); AppBackup.write(buffer, original, directory)
            val restored = AppBackup.read(buffer.toByteArray().inputStream())
            assertEquals(original.profiles, restored.state.profiles)
            assertEquals(original.selectedId, restored.state.selectedId)
            assertEquals("interrupted", restored.state.rides.single().status)
            assertEquals(3.5, restored.state.rides.single().descentMeters!!, 0.0)
            assertArrayEquals(gpx, restored.hikes.getValue("hikes/$id.gpx"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun rejectsPathTraversalAndSecrets() {
        for (name in listOf("../workouts.json", "garmin-sync.bin", "console-credentials.zip", "/workouts.json")) {
            assertThrows(IllegalStateException::class.java) { AppBackup.read(archive(format, state(SavedState()), name to byteArrayOf(1)).inputStream()) }
        }
    }
    @Test fun rejectsUnownedHistory() {
        assertThrows(IllegalArgumentException::class.java) { AppBackup.read(archive(format, state(SavedState(rides = listOf(Ride(1, 99, 0))))).inputStream()) }
    }
    @Test fun rejectsUnsupportedVersionAndOversizeEntry() {
        assertThrows(IllegalArgumentException::class.java) { AppBackup.read(archive("format.txt" to "openrun-backup-2".toByteArray(), state(SavedState())).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { AppBackup.read(archive("format.txt" to ByteArray(1024)).inputStream()) }
    }
    @Test fun rejectsTamperedHike() {
        assertThrows(IllegalArgumentException::class.java) { AppBackup.read(archive(format, state(SavedState()), "hikes/${"0".repeat(64)}.gpx" to "changed".toByteArray()).inputStream()) }
    }
}
