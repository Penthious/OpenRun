package dev.digitalducktape.openrun

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

class MaintenanceActivity: ComponentActivity() {
    private val app get() = application as OpenRunApplication
    private var notice by mutableStateOf<String?>(null)
    private var pending by mutableStateOf<AppBackup.Snapshot?>(null)
    private val restore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            val snapshot = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use(AppBackup::read) ?: error("Cannot read backup.") }
            pending = snapshot
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) operation {
            val saved = app.store.state.value
            withContext(Dispatchers.IO) {
                val temp = File(cacheDir, "backup-check.zip")
                try {
                    AppBackup.write(temp.outputStream(), saved, File(filesDir, "hikes"))
                    temp.inputStream().use(AppBackup::read) // Verify before writing the user's destination.
                    contentResolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } } ?: error("Cannot save backup.")
                } finally { temp.delete() }
            }
            notice = "Backup saved and verified. Keep it private: it contains your workout and location history."
        }
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        app.maintenance.value = false
        notice = "Installer closed. Current version: ${BuildConfig.VERSION_NAME}."
    }
    private fun operation(block: suspend () -> Unit) {
        if (app.active.value != null || app.controlBusy.value || app.maintenance.value) { notice = "End your workout before maintenance."; return }
        app.maintenance.value = true
        // Application scope completes a restore even if Android recreates this Activity.
        app.scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "Operation failed. No existing workout history was replaced. Check the file and available storage." }
            finally { app.maintenance.value = false }
        }
    }
    private fun install() {
        val update = app.updates.state.value.ready ?: return
        if (app.active.value != null || app.controlBusy.value || app.maintenance.value) { notice = "End your workout before installing."; return }
        val telemetry = app.treadmill.telemetry.value
        if (telemetry.receivedAt == 0L || SystemClock.elapsedRealtime() - telemetry.receivedAt > 5000 || telemetry.mph == null || telemetry.mph!! > 0.05) {
            notice = "Connect NordicFTMS and stop the belt before installing the update."; return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onFailure { notice = "Allow OpenRun to install apps in Android Settings, then try again." }
            return
        }
        app.maintenance.value = true
        app.scope.launch {
            try {
                withContext(Dispatchers.IO) { app.updates.verify(app.updates.apkFile, update) }
                val current = app.treadmill.telemetry.value
                check(current.receivedAt > 0 && SystemClock.elapsedRealtime() - current.receivedAt <= 5000 && current.mph != null && current.mph!! <= 0.05)
                val uri = FileProvider.getUriForFile(this@MaintenanceActivity, "$packageName.updates", app.updates.apkFile)
                installer.launch(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true))
            } catch (_: Exception) {
                app.maintenance.value = false
                notice = "Cannot install this update. Check the connection and verify the download again."
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = openRunColors()) {
                Surface(Modifier.fillMaxSize()) {
                    val update by app.updates.state.collectAsState()
                    val active by app.active.collectAsState()
                    val controls by app.controlBusy.collectAsState()
                    val maintenance by app.maintenance.collectAsState()
                    val saved by app.store.state.collectAsState()
                    var automatic by remember { mutableStateOf(app.updates.automatic) }
                    val idle = active == null && !controls && !maintenance
                    Column(Modifier.padding(36.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { finish() }, enabled = !maintenance) { Text("← Back to OpenRun") }
                        Text("Updates & backup", fontSize = 32.sp)
                        Text("OpenRun ${BuildConfig.VERSION_NAME} · ${if (BuildConfig.DEBUG) "development" else "release"} build")
                        Text(update.message)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(onClick = { app.scope.launch { app.updates.check(force = true) } }, enabled = idle && !update.busy) { Text("Check for updates") }
                            if (update.ready != null) Button(onClick = { install() }, enabled = idle && !update.busy) { Text("Install ${update.ready!!.versionName}") }
                        }
                        Row { Switch(checked = automatic, onCheckedChange = { automatic = it; app.updates.automatic(it) }); Spacer(Modifier.width(16.dp)); Text("Check on startup and daily; download updates automatically.\nAndroid asks you to confirm installation after your workout.") }
                        HorizontalDivider()
                        Text("Your data", fontSize = 24.sp)
                        Text("Back up profiles, history, planned workouts and downloaded hikes. Garmin login, console credentials and Android permissions are separate. Plex and browser logins are unaffected.")
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(onClick = { export.launch("OpenRun-backup-${java.time.LocalDate.now()}.zip") }, enabled = idle) { Text("Save backup") }
                            OutlinedButton(onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = idle && saved.profiles.isEmpty() && saved.rides.isEmpty()) { Text("Restore backup") }
                        }
                        Text("Restore is available on a fresh installation before creating any profiles or importing hikes. Reconnect Garmin afterward; restored history is not automatically re-uploaded.")
                        if (!idle) Text("Maintenance is available when your workout has ended.")
                    }
                    pending?.let { snapshot ->
                        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Restore your backup?") }, text = { Text("${snapshot.state.profiles.size} profiles, ${snapshot.state.rides.size} workouts and ${snapshot.hikes.keys.count { it.endsWith(".gpx") }} hikes. Garmin will need a new login.") }, confirmButton = {
                            Button(onClick = { pending = null; operation { AppBackup.restore(app, snapshot); notice = "Backup restored. Return to OpenRun and reconnect Garmin under Settings." } }) { Text("Restore") }
                        }, dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
                    }
                    notice?.let { AlertDialog(onDismissRequest = { notice = null }, title = { Text("OpenRun") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } }) }
                }
            }
        }
    }
}
