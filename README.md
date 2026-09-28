# OpenRun

An independent Android treadmill interface for running, walking and watching
your own entertainment. Developed on a NordicTrack console with a Malata
Argon2 tablet running Android 9. Compatibility with other consoles requires
testing.

OpenRun uses a separately installed [NordicFTMS](https://github.com/mikepugh/NordicFTMS)
companion for telemetry and speed/incline commands, plus the console's
existing GlassOS service for workout preparation and Pause/Stop operations.

## Features

- **Runner profiles:** separate history, remembered Bluetooth chest strap
  and optional Garmin account per person.
- **Live track:** a 400-meter oval with your position, current lap, completed
  laps and distance to the next lap, driven by recorded workout distance.
- **Manual and Zone 2 workouts:** live speed, incline, distance, time and HR,
  with on-screen controls and a fixed sidebar Stop button.
- **Entertainment overlay:** choose your show first, then start manual,
  Zone 2 or today's available Garmin workout from a collapsible menu.
  During exercise it shows metrics and controls; finishing returns to the
  menu without leaving the player.
- **Optional warm-up:** five minutes progressing from walking toward running,
  with a skip button and a saved per-runner preference.
- **Garmin schedule:** today's workout and a seven-day calendar, cached per
  runner/account, with previews and configurable maximum running speed.
- **Adaptive starting pace:** weekly checks of recent Garmin runs and
  learning from saved local workouts when enough stable HR/speed data exists.
- **Workout history:** tappable HR, speed and incline charts, peak metrics,
  guided interval breakdowns and recorded prescribed HR target bands.
- **Save or discard:** discarded workouts do not enter history or upload.
  Saved workouts can upload to the runner's connected Garmin account.
- **Sleep screen:** manual sleep and an idle timer, with tap to wake.

The current version is defined in [app/build.gradle.kts](app/build.gradle.kts).

## Installation and releases

Follow the [console installation guide](docs/INSTALL.md) for first-time
privileged mode, Android debugging, OpenPelo installation and local credential
import. The [release guide](docs/RELEASING.md) explains GitHub Actions and signing.

Release builds exclude console credentials even when local debug assets exist.
Import your own credential ZIP under Connections before using hardware controls.
Debug builds may still package local developer credentials and must stay private.

## Requirements

- Android 9 / API 28 or newer. Development hardware has a 1920×1080 display;
  this is not a universal treadmill driver.
- NordicFTMS running with local DIRCON available at 127.0.0.1:36866.
- Existing GlassOS/iFit system services retained on the console.
- Locally supplied GlassOS client credentials for the device integration.
  They are deliberately **not included** in this repository.
- A Bluetooth HR chest strap for HR-controlled workouts.
- JDK 17 and Android SDK 34 to build; ADB to install.
- Internet access and an optional Garmin account for schedule/history sync
  and uploads. Manual workouts and local history do not require Garmin.

## Build and install locally

Configure JAVA_HOME and your Android SDK path through ANDROID_HOME or an
ignored local.properties file. Use the Gradle wrapper:

~~~sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
~~~

A clean checkout can compile and run unit tests without local credentials,
but GlassOS-dependent hardware operations require these files in
app/src/main/assets/certs/:

- glassos_ca.pem
- glassos_client_cert.pem
- glassos_client_key.pem

Supply credentials locally for equipment you are authorized to access.
Do not commit them, paste them into issues or include them in logs.

With the belt stopped and no workout active, install your local build:

~~~sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.digitalducktape.openrun/.MainActivity
~~~

Use the ADB device selector if more than one device is connected. Installing
an update restarts OpenRun. Updating with the reinstall flag preserves app
data; uninstalling deletes local data.

**Keep locally built debug APKs private.** Android packages the credential assets
inside debug APKs even though Git ignores them. Release builds exclude those assets. Do not attach such APKs to
GitHub releases, issues or CI artifacts.

## First run

1. Create a runner profile and pair a chest strap under **Connections**.
   Android 9 requires Bluetooth/location permissions and Location enabled
   for BLE scanning.
2. Confirm NordicFTMS is connected. Grant **Display over other apps** for
   the entertainment overlay.
3. At the treadmill, verify Start, small speed/incline adjustments, Pause,
   Resume, End and the physical Stop/safety key. Confirm control verification
   under Connections before enabling guided HR workouts.
4. Optionally connect Garmin and confirm the runner's schedule time zone.
   Schedule dates use this setting rather than the console time zone.
5. Choose a workout in OpenRun, or open a player and use the floating menu.
   Starting requires confirmation and a three-second countdown at 2 mph.

Installed Plex, Netflix and the Fulguris browser have entertainment tiles.
A locally installed Plex wrapper using Netflix's package name is labeled
Plex when its launch activity identifies Plex. The wrapper is not included.

## Workout behavior

### Manual and Zone 2

Manual/Zone 2 controls use a 4 mph speed ceiling and 20% incline ceiling.
Zone 2 targets 120–140 bpm, adjusting incline before speed. Manual overrides
hold automatic adjustments for 60 seconds. With the optional warm-up,
automation follows the warm-up; without it, the Zone 2 controller retains
its initial settling period.

HR loss warns and holds the last settings while recording continues.
Automatic adjustments resume only after enough readings are available.

### Scheduled running workouts

Scheduled runs follow imported steps with an editable speed limit. Running
guides cap incline at 3%; HR adjustments work toward 1–3% incline before
changing speed at those bounds. HR checks occur over ten-second windows,
with thirty seconds to settle after confirmed HR-driven changes. Manual
changes hold until the next interval.

Default running pace is 6 mph unless an explicit target or reliable learned
pace takes precedence, bounded by the workout's maximum. Tempo defaults to
a 10 mph maximum. Starts remain at 2 mph with a ramp; a target pace is not an
immediate belt-speed jump.

Pace learning requires stable measurements from multiple workouts. It
excludes warm-up/recovery, missing HR, unstable segments and other unsuitable
data. Later learned changes are bounded; missing data is not invented.

### Stopping and recovery

Pause/Stop use dedicated GlassOS operations and confirm stopped telemetry.
Speed/incline commands require protocol responses and matching telemetry.
Failed confirmation reports an error rather than claiming success. Stop
preempts pending commands.

No workout is automatically restarted after launch, reconnection or
recording recovery. Interrupted sessions are kept locally and not
automatically uploaded. Physical Stop and the safety key remain essential;
verify behavior on your hardware before relying on automation. Automated
tests cannot validate physical stopping.

## Garmin integration and storage

Garmin is optional and uses unofficial endpoints that may change. Login
supports MFA; tokens and upload jobs are encrypted with Android Keystore.
Accounts and queues are isolated per runner.

Planned runs use FIT uploads with recorded sensor data and interval
information. Non-planned runs and older queued jobs may use TCX. Uploads
do not fabricate GPS, watch identity, training effect or other unmeasured
metrics. Retry jobs retain their original payload identity.

**Garmin Coach credit remains unverified.** Successful activity upload
does not establish that Garmin marked the prescribed Coach workout
complete. Training load and recovery effects are not guaranteed.

Schedules are cached for offline starts and refreshed with throttling.
Pace history is checked weekly when network access is available.
Chart targets depend on metadata recorded with the workout; older sessions
may lack guided interval details.

Profiles/history are stored in app-private JSON; Android backup is disabled.
Climbing is estimated from distance and positive incline. Profile editing
and general history export/deletion UI are not implemented.

## Publishing the source

Commit source, tests, documentation and Gradle configuration. The
[ignore rules](.gitignore) exclude local credentials, environment files,
signing keys, builds/caches, APKs and private workout exports. Keep personal
backups under an ignored local-data/ or backups/ directory.

Enable the repository’s pre-commit source check in each clone:

~~~sh
git config core.hooksPath .githooks
~~~

Private release signing material lives under the ignored secrets/release/
directory. The [credential extraction example](docs/INSTALL.md#example-extract-from-your-installed-nordicftms-companion) writes only to secrets/glassos/.

Before committing:

~~~sh
python3 scripts/check_source.py
git add .
python3 scripts/check_source.py
git diff --cached --stat
git diff --cached --check
~~~

The source check inspects tracked and non-ignored candidate files,
including staged content, for prohibited artifacts and common secret
patterns. It reports paths, not secret values. It is a focused check, not
a guarantee against every credential format. Do not force-add local assets.
Review staged changes before committing to your chosen branch.

## Credits and license

[OpenRide](https://github.com/Penthious/OpenRide) was the **base example and
inspiration** for OpenRun's on-device fitness experience, profiles,
entertainment workflow and optional Garmin integration. OpenRun was
implemented independently: **no OpenRide application source code was
copied into this repository**. See [OPENRIDE_NOTICE](OPENRIDE_NOTICE).

[NordicFTMS](https://github.com/mikepugh/NordicFTMS) provides the separately
installed companion. No NordicFTMS source or binary is bundled.

OpenRun's source is licensed under [Apache-2.0](LICENSE). Dependencies retain
their own licenses, including the Garmin FIT SDK's
[FIT Protocol License Agreement](https://github.com/garmin/fit-java-sdk/blob/main/LICENSE.txt).
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency and tooling
references. OpenRun is not affiliated with or endorsed by iFIT, NordicTrack,
Garmin, Plex or Netflix.

### Outdoor hikes

Download GPX tracks on the treadmill and use **Entertainment → Outdoor Trails**.
The Android 9 Downloads scanner keeps a local hike library. Start a hike directly
or queue it, open Plex, and start from the floating menu. Terrain adjusts incline
within your chosen cap while speed stays manual. See [Outdoor Trails](docs/OUTDOOR_TRAILS.md)
for setup and control behavior.
