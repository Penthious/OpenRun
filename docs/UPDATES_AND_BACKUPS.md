# Updates, local builds and backups

Open **Updates & backup** at the bottom of OpenRun's sidebar. It is available
before creating a profile, which lets a fresh installation restore your data.

## Automatic downloads

OpenRun checks the latest published, stable GitHub release on startup (at most
once per 20 hours) and approximately daily while online. Android may defer
background work. Drafts and prereleases are excluded. You can check manually
or turn automatic checks/downloads off.

A newer APK downloads into OpenRun's private storage. OpenRun verifies its
SHA-256 checksum, package ID, version and signing certificate. **Update available**
appears in the sidebar. End your workout, stop the belt and keep NordicFTMS
connected, then select **Install**. Allow OpenRun to install apps if Android asks,
return to OpenRun and select Install again. Android presents its confirmation;
OpenRun never silently installs or restarts during a workout. Workout starts
are blocked while the installer is open. Normal updates preserve data and logins.

The first signed version cannot update an old debug installation in place.
Never uninstall just to bypass a signature mismatch without a verified backup.

## Back up and restore

**Save backup** exports a ZIP through Android's file picker. It includes all
profiles, workout history and samples, planned workouts, cached schedules,
pace history and imported GPX hikes. The app verifies the archive before
saving it. Keep it private: it contains health and route/location data.
Archives are limited to 64 MiB uncompressed, including at most 32 MiB of history.

Backups exclude Garmin credentials, console private keys, Android permissions
and control-verification flags. Keep your console credential ZIP separately.
Plex and Firefox/AllTrails data belong to their own apps and are unaffected.

On a fresh OpenRun installation, before adding profiles or importing hikes:

1. Open Updates & backup → Restore backup and select the ZIP.
2. Review the profile/workout/hike counts and confirm Restore.
3. Import your local console credential ZIP under Connections and restart OpenRun.
4. Regrant overlay/Bluetooth permissions and verify controls on your equipment.
5. Reconnect Garmin for each profile if wanted. Old restored workouts are not
   automatically uploaded again.

Restore validates the whole archive before installing its contents. It rejects
unknown entries, unsupported formats, corrupt routes and oversized files.
Existing histories cannot be overwritten or merged through Restore.

## Local changes after migrating to release signing

Keep using the same package ID and permanent key. A normal debug APK uses a
different key and will not replace the release installation. On the maintainer's
checkout, with ignored signing files in secrets/release/:

~~~sh
python3 scripts/build_signed.py
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n dev.digitalducktape.openrun/.MainActivity
~~~

Set JAVA_HOME to JDK 17 and configure ANDROID_HOME first. Use your usual
ADB device selector for a Wi-Fi connection. The script loads passwords privately
and runs unit tests and the release APK credential check. It never adds console
credentials to the release APK. These stay in the app's private storage.

Increment versionCode and versionName when preparing a release. The updater
only offers a release whose versionCode is greater than the installed build.
For unpublished experiments, switch automatic updates off if you want to keep
that local build. Re-enable it when ready to return to published releases.

## Maintainer migration recovery

Before changing signing identities, save and verify a backup and retain the
installed APK privately. The development console's snapshot is kept under the
ignored, owner-only backups/ directory in this checkout, with checksums. Console
credentials and permanent signing material stay under ignored secrets/.
These files are not included in a git clone. A raw debug data archive cannot
restore Android Keystore keys; Garmin requires sign-in after an uninstall.

Do not remove Plex, Firefox, NordicFTMS or any iFit/GlassOS package during this
migration. Only OpenRun's app package needs to be replaced.
