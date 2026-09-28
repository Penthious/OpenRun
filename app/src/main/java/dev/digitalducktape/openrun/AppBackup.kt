package dev.digitalducktape.openrun

import dev.digitalducktape.openrun.core.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.util.zip.*

/** Portable data only. Garmin tokens, console keys and device permissions stay out. */
object AppBackup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val MAX_TOTAL = 64 * 1024 * 1024
    data class Snapshot(val state: SavedState, val hikes: Map<String, ByteArray>)
    fun read(input: InputStream): Snapshot {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && files.size < 1002 && entry.name !in files) { "Invalid backup entries." }
                val limit = when {
                    entry.name == "format.txt" -> 64
                    entry.name == "workouts.json" -> 32 * 1024 * 1024
                    entry.name.matches(Regex("hikes/[a-f0-9]{64}\\.gpx")) -> TrailGpx.MAX_BYTES
                    entry.name.matches(Regex("hikes/[a-f0-9]{64}\\.txt")) -> 1024
                    else -> error("Unexpected file in backup.")
                }
                val bytes = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = zip.read(chunk); if (n < 0) break
                    total += n
                    require(total <= MAX_TOTAL && bytes.size() + n <= limit) { "Backup is too large." }
                    bytes.write(chunk, 0, n)
                }
                files[entry.name] = bytes.toByteArray()
            }
        }
        require(files.remove("format.txt")?.toString(Charsets.UTF_8) == "openrun-backup-1") { "Unsupported backup format." }
        val state = json.decodeFromString<SavedState>(files.remove("workouts.json")?.toString(Charsets.UTF_8) ?: error("Missing workout history."))
        val ids = state.profiles.map { it.id }.toSet()
        require(ids.size == state.profiles.size && state.profiles.all { it.id > 0 && it.name.isNotBlank() }) { "Invalid profiles." }
        require(state.selectedId == null || state.selectedId in ids)
        require(state.rides.map { it.id }.distinct().size == state.rides.size && state.rides.all { it.profileId in ids }) { "Invalid workout ownership." }
        require(state.workouts.all { it.profileId in ids } && state.schedules.all { it.profileId in ids } && state.paceHistory.all { it.profileId in ids })
        files.forEach { (name, bytes) ->
            if (name.endsWith(".gpx")) {
                require(sha256(bytes) == name.substringAfter('/').substringBefore('.')) { "Hike checksum mismatch." }
                TrailGpx.parse(bytes.inputStream())
            } else require(files.containsKey(name.removeSuffix(".txt") + ".gpx")) { "Missing hike route." }
        }
        return Snapshot(state.copy(rides = state.rides.map { if (it.status == "recording") it.copy(status = "interrupted") else it }), files)
    }
    fun write(output: OutputStream, state: SavedState, hikeDirectory: File) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("format.txt", "openrun-backup-1".toByteArray())
            entry("workouts.json", json.encodeToString(state).toByteArray())
            hikeDirectory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.(gpx|txt)")) }.sortedBy { it.name }.forEach { entry("hikes/${it.name}", it.readBytes()) }
        }
    }
    /** Restore only into a fresh install: no merging or overwriting existing histories. */
    fun restore(app: OpenRunApplication, snapshot: Snapshot) {
        check(app.active.value == null && !app.controlBusy.value)
        check(app.store.state.value.profiles.isEmpty() && app.store.state.value.rides.isEmpty()) { "Restore requires a fresh OpenRun installation." }
        val destination = File(app.filesDir, "hikes")
        check(destination.listFiles().isNullOrEmpty()) { "Restore requires an empty hike library." }
        val staging = File(app.filesDir, "restore-hikes")
        staging.deleteRecursively(); check(staging.mkdirs())
        try {
            snapshot.hikes.forEach { (name, data) -> File(staging, name.substringAfter('/')).writeBytes(data) }
            destination.delete()
            check(staging.renameTo(destination)) { "Cannot restore hike library." }
            try { app.store.update { snapshot.state } }
            catch (e: Exception) { destination.deleteRecursively(); throw e }
        } finally { staging.deleteRecursively() }
        app.pendingHike.value = null
    }
}

internal fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
