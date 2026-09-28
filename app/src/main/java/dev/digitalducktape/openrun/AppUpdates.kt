package dev.digitalducktape.openrun

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

@Serializable data class UpdateManifest(val versionCode: Long, val versionName: String, val apk: String, val sha256: String, val minSdk: Int = 28) {
    fun validate(tag: String) {
        require(versionCode > 0 && versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) && tag == "v$versionName") { "Invalid release version." }
        require(apk == "OpenRun-$tag.apk" && sha256.matches(Regex("[a-f0-9]{64}")) && minSdk >= 28) { "Invalid release metadata." }
    }
}
data class UpdateStatus(val message: String = "Automatic update checks are on.", val busy: Boolean = false, val ready: UpdateManifest? = null)

class AppUpdates(private val app: OpenRunApplication) {
    val state = MutableStateFlow(UpdateStatus())
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val directory = File(app.filesDir, "updates").apply { mkdirs() }
    val apkFile = File(directory, "update.apk")
    private val manifestFile = File(directory, "ready.json")
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    val automatic get() = prefs.getBoolean("automatic", true)
    fun automatic(enabled: Boolean) { prefs.edit().putBoolean("automatic", enabled).apply() }
    init {
        val saved = runCatching { json.decodeFromString<UpdateManifest>(manifestFile.readText()) }.getOrNull()
        if (saved != null && saved.versionCode > BuildConfig.VERSION_CODE && apkFile.exists()) state.value = UpdateStatus("Update ${saved.versionName} downloaded. Install after your workout.", ready = saved)
        else { apkFile.delete(); manifestFile.delete() }
    }
    suspend fun check(force: Boolean = false) = mutex.withLock {
        if (!force && (!automatic || System.currentTimeMillis() - prefs.getLong("checked", 0) in 0 until TimeUnit.HOURS.toMillis(20))) return@withLock
        if (app.active.value != null || app.controlBusy.value || app.maintenance.value) return@withLock
        val ready = state.value.ready
        state.value = UpdateStatus("Checking GitHub releases…", true, ready)
        try {
            withContext(Dispatchers.IO) {
                val release = json.parseToJsonElement(fetch("https://api.github.com/repos/Penthious/OpenRun/releases/latest", 1024 * 1024).toString(Charsets.UTF_8)).jsonObject
                require(release["draft"]?.jsonPrimitive?.boolean == false && release["prerelease"]?.jsonPrimitive?.boolean == false)
                val tag = release.getValue("tag_name").jsonPrimitive.content
                require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))
                val assets = release.getValue("assets").jsonArray.map { it.jsonObject }
                fun asset(name: String): String {
                    val url = assets.single { it["name"]?.jsonPrimitive?.content == name }.getValue("browser_download_url").jsonPrimitive.content
                    require(url == "https://github.com/Penthious/OpenRun/releases/download/$tag/$name") { "Unexpected release asset URL." }
                    return url
                }
                val manifest = json.decodeFromString<UpdateManifest>(fetch(asset("update.json"), 8192).toString(Charsets.UTF_8))
                manifest.validate(tag)
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                    state.value = UpdateStatus("OpenRun ${BuildConfig.VERSION_NAME} is up to date.")
                } else {
                    require(manifest.minSdk <= Build.VERSION.SDK_INT) { "This release needs a newer Android version." }
                    if (app.active.value != null || app.maintenance.value) return@withContext
                    state.value = UpdateStatus("Downloading OpenRun ${manifest.versionName}…", true, ready)
                    val partial = File(directory, "download.part")
                    try {
                        if (state.value.ready != manifest || !apkFile.exists()) {
                            download(asset(manifest.apk), partial, 100 * 1024 * 1024)
                            verify(partial, manifest)
                            check(partial.renameTo(apkFile)) { "Cannot save the downloaded update." }
                            val atomic = android.util.AtomicFile(manifestFile)
                            val out = atomic.startWrite()
                            try { out.write(json.encodeToString(manifest).toByteArray()); atomic.finishWrite(out) }
                            catch (e: Exception) { atomic.failWrite(out); throw e }
                        } else verify(apkFile, manifest)
                        state.value = UpdateStatus("OpenRun ${manifest.versionName} is ready. Install after your workout.", ready = manifest)
                    } finally { partial.delete() }
                }
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.value = UpdateStatus(if (e is UpdateFailure) e.message!! else "Could not check or verify the release. Try again when online.", ready = ready) }
        finally { if (state.value.busy) state.value = state.value.copy(busy = false) }
    }
    fun verify(file: File, manifest: UpdateManifest) {
        manifest.validate("v${manifest.versionName}")
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(16384); while (true) { val n = input.read(b); if (n < 0) break; hash.update(b, 0, n) } }
        require(hash.digest().joinToString("") { "%02x".format(it) } == manifest.sha256) { "APK checksum mismatch." }
        val pm = app.packageManager
        val apk = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Unreadable APK.")
        require(apk.applicationInfo != null && apk.applicationInfo!!.minSdkVersion <= Build.VERSION.SDK_INT) { "Unsupported Android version." }
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(apk.packageName == app.packageName && apk.longVersionCode == manifest.versionCode && apk.versionName == manifest.versionName && apk.longVersionCode > installed.longVersionCode) { "APK identity or version mismatch." }
        fun certificates(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        val expected = certificates(installed)
        if (expected.isEmpty() || certificates(apk) != expected) throw UpdateFailure("Different signing key. This installation needs a backed-up migration; it cannot update in place.")
    }
    private class UpdateFailure(message: String): Exception(message)
    private fun fetch(url: String, max: Int): ByteArray = connection(url).let { c ->
        try { c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192)
            while (true) { val n = input.read(b); if (n < 0) break; require(out.size() + n <= max); out.write(b, 0, n) }; out.toByteArray()
        } } finally { c.disconnect() }
    }
    private fun download(url: String, file: File, max: Int) {
        val c = connection(url)
        try { c.inputStream.use { input -> file.outputStream().use { out ->
            var total = 0; val b = ByteArray(16384)
            while (true) { val n = input.read(b); if (n < 0) break; total += n; require(total <= max); out.write(b, 0, n) }
        } } } finally { c.disconnect() }
    }
    private fun connection(start: String): HttpsURLConnection {
        var url = URL(start)
        repeat(6) {
            require(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) && url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            val c = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "OpenRun/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/octet-stream, application/vnd.github+json")
            }
            if (c.responseCode in listOf(301, 302, 303, 307, 308)) {
                val location = c.getHeaderField("Location"); c.disconnect(); require(location != null); url = URL(url, location)
            } else {
                if (c.responseCode == 404) { c.disconnect(); throw UpdateFailure("No published release is available yet.") }
                if (c.responseCode != 200) { c.disconnect(); error("Release request failed.") }
                return c
            }
        }
        error("Too many redirects.")
    }
}
class AppUpdateWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as OpenRunApplication).updates.check()
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork("app-updates-daily", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS).setConstraints(network).build())
            manager.enqueueUniqueWork("app-updates-startup", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AppUpdateWorker>().setConstraints(network).build())
        }
    }
}
