package dev.digitalducktape.openrun

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.*
import android.provider.Settings
import android.util.Log

internal const val BLOCKING_NAVIGATION_SERVICE = "com.ifit.glassos_service/com.ifit.glassos_appnavigation_service.service.AccessibilityServiceImpl"

internal fun withoutSettingsBlocker(services: String): String = services.split(':')
    .filterNot { it == BLOCKING_NAVIGATION_SERVICE }.joinToString(":")

/** Android 9 console workaround; deliberately preserves every other accessibility service. */
class SettingsAccessService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val repair = Runnable { restoreSettingsAccess() }
    private var bootLaunchScheduled = false
    private val bootHome = Runnable {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val selected = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        if (selected?.activityInfo?.packageName == packageName) {
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.i("OpenRunSettings", "Opened OpenRun Home after boot")
            } catch (e: RuntimeException) {
                Log.w("OpenRunSettings", "Unable to open Home after boot", e)
            }
        }
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(repair)
            handler.postDelayed(repair, 250)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("settings_access", "Settings access", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 20, Intent(Settings.ACTION_SETTINGS), PendingIntent.FLAG_IMMUTABLE)
        startForeground(20, Notification.Builder(this, "settings_access")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentTitle("OpenRun · Settings access")
            .setContentText("Keeping Android Settings accessible")
            .setContentIntent(open).setOngoing(true).build())
        if (!authorized(this)) { stopSelf(); return }
        contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        restoreSettingsAccess()
    }

    private fun restoreSettingsAccess() {
        if (!authorized(this)) { stopSelf(); return }
        try {
            val old = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return
            val updated = withoutSettingsBlocker(old)
            if (old != updated) {
                if (Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)) {
                    Log.i("OpenRunSettings", "Removed Settings-blocking navigation hook")
                } else {
                    Log.w("OpenRunSettings", "Settings access write failed")
                }
            }
        } catch (e: SecurityException) {
            Log.w("OpenRunSettings", "Settings access permission unavailable", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && !bootLaunchScheduled) {
            bootLaunchScheduled = true
            // The console explicitly starts iFit before BOOT_COMPLETED. Allow its
            // account-selection startup to settle, then open the chosen Home once.
            handler.postDelayed(bootHome, 20_000)
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacks(repair)
        handler.removeCallbacks(bootHome)
        super.onDestroy()
    }

    companion object {
        private fun authorized(context: Context) = Build.VERSION.SDK_INT == 28 &&
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        fun startIfAuthorized(context: Context, afterBoot: Boolean = false) {
            if (authorized(context)) context.startForegroundService(Intent(context, SettingsAccessService::class.java).apply {
                if (afterBoot) action = Intent.ACTION_BOOT_COMPLETED
            })
        }
    }
}

class SettingsAccessBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) SettingsAccessService.startIfAuthorized(context, afterBoot = true)
    }
}
