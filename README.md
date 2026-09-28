# OpenRun

Native Android 9+ treadmill UI for a NordicTrack Argon2 console. Version 0.2.1 replaces the unreliable zero-speed stop with dedicated GlassOS workout Pause/Stop operations. Hardware stop behavior requires retesting.

## Included

- Runner profiles with separate history, remembered BLE chest strap, and optional Garmin account.
- NordicFTMS telemetry and controls over local DIRCON (`127.0.0.1:36866`). No commands run at launch, reconnection, or recording recovery.
- Start at 2 mph after an explicit confirmation and three-second countdown. Current incline must be 0–20%.
- Manual speed steps of 0.1 mph and incline steps of 0.5%. Commands are capped at 4 mph / 20%.
- Pause calls GlassOS WorkoutService/Pause and freezes the timer once stopped. Resume requires a tap and countdown. End calls GlassOS WorkoutService/Stop, then saves and queues Garmin upload only after stop confirmation.
- Netflix bottom overlay, collapsed HR display, Stop always accessible; expanded Pause/Resume and End controls.
- Optional Garmin mobile SSO/MFA, encrypted tokens, opt-in future workouts, and durable per-account retry queue. Running TCX contains real recorded distance, speed, timestamps and available HR. No invented GPS or altitude.
- Local history survives upgrades; interrupted sessions recover locally and are not automatically uploaded. Recording snapshots every five seconds.

## Zone 2

First verify physical start, incline and stopping behavior using the manual buttons. End the test workout, then confirm the check under Connections. This unlocks the Enable Zone 2 button; automation remains off for each new workout until tapped.

- Target 120–140 BPM; 90-second warm-up, then at least 45 seconds between commands.
- Below target: incline +0.5% first, then speed +0.1 mph once incline reaches 20%.
- Above target: incline −0.5% first, then speed −0.1 mph down to 2 mph.
- Both app and detected physical changes suspend automatic adjustments for 60 seconds.
- HR loss holds settings, recording continues, and a warning appears. Fifteen seconds of available readings are required before automatic adjustments resume.
- A stopped belt, stale/disconnected treadmill telemetry, rejected/unconfirmed commands, or readings outside limits disable automation. Reconnection never restarts the belt.
- Physical treadmill controls and safety key remain authoritative. Disable automatic adjustments whenever desired.

Stop/Pause now bypass NordicFTMS speed targets and require a successful GlassOS Result plus stopped telemetry. The previous version’s speed-zero approach failed on the physical treadmill and must not be used. Control verification is reset for this upgrade.

NordicFTMS acknowledges speed/incline commands before GlassOS applies them. OpenRun checks both the FTMS response and three distinct telemetry frames at the target. A missing confirmation reports an error and turns automation off; it does not claim the belt stopped. Use the physical Stop control if a command fails. Stop preempts pending app actions; no command is retried automatically.

## Build

Requires JDK 17 and Android SDK 34. Local GlassOS mTLS assets are required in `app/src/main/assets/certs/` (CA certificate, client certificate and key from the installed companion). These are excluded from source control; do not publish them or include them in logs.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.digitalducktape.openrun/.MainActivity
```

## Device validation still required

1. Keep NordicFTMS running with DIRCON enabled and retain GlassOS/iFit system services.
2. Pair HR under Connections. Android 9 needs Bluetooth/location permission and Location enabled for scanning.
3. Verify actual speed/incline against displayed values. FTMS packets alone cannot prove GlassOS freshness: NordicFTMS can repeat cached readings.
4. While at the treadmill, test Start, small incline changes, Pause, Resume, and End. Confirm physical overrides and the safety key. Automated tests use a fake local bridge, not hardware.
5. Verify Netflix playback, overlay controls, HR loss/reconnection, history ownership, and a real Garmin upload. Garmin behavior uses unofficial endpoints and may require maintenance.
6. After these checks, enable Zone 2 and observe its initial adjustments in person.

## Storage and limitations

Profiles/history use app-private atomic JSON. Garmin tokens/outbox are AES-GCM encrypted with Android Keystore and excluded from backups. Android backup is disabled. Local history requires no cloud account. History export/deletion and profile-edit UI are not yet implemented. Upgrade with `adb install -r`; uninstalling deletes local data.

Approximate climbing is computed from measured distance and positive incline, shown in feet and added to TCX notes. Garmin training load/recovery effects are not guaranteed by a third-party activity import.

## Attribution

Garmin integration adapted from local OpenRide (Apache-2.0); see LICENSE and OPENRIDE_NOTICE. Protocol implementation was written for OpenRun after inspecting NordicFTMS's DIRCON framing and FTMS encoding; no NordicFTMS source is bundled. NordicFTMS is separately installed software: https://github.com/mikepugh/NordicFTMS.


### Planned Garmin workouts and FIT uploads (0.2.16)

New planned runs retain a snapshot of the imported workout, its scheduled date and source identifiers, and the measured interval index on each sample. Re-import previously saved sessions to retain their Garmin source identifiers; the old sessions cannot be matched safely by name alone. Saved cards display the schedule date.

Planned runs upload as FIT with real sensor records, executed interval laps, timer gaps, and structured HR targets using Garmin's FIT Java SDK. Ending early records only the intervals visited. Source identifiers are retained in the workout description as provenance; this is **not a documented Garmin Coach association protocol** and Coach completion remains unverified. No Garmin watch identity, training effect, or other unmeasured metrics are fabricated. The upload status reports that Coach credit is unverified.

Older queued jobs and non-planned runs retain TCX. Each outbox job pins its format and payload fingerprint for retries, and already uploaded rides are not re-uploaded. Discarded workouts remain excluded from history and uploads. No test activities are uploaded during local verification.


### Home schedule (0.2.17)

The idle Workout home screen shows today's Garmin workouts and an upcoming seven-day strip. Selecting a day opens a preview; only Start workout initiates the existing three-second countdown and safety checks. Rest days preview the next scheduled day. Manual and Zone 2 controls remain below the schedule, and the schedule hides during active workouts.

Schedules refresh on profile selection or returning home (five-minute throttle, with an explicit Refresh button). Imported steps are cached atomically per runner and Garmin account for offline starts. Failed refreshes preserve the last good snapshot and show an error with the last-sync time. Changing accounts cannot display or commit the previous account's schedule. Local completion badges do not claim Garmin Coach credit.

Schedule days use the profile’s editable time zone (America/Denver by default for this installation), independently of the console system clock. The chosen local date also controls Garmin calendar queries at month boundaries.


### Warm-up and adaptive pace (0.2.18)

Each runner has a default-on, optional five-minute prelude for manual, Zone 2 and scheduled workouts. Five one-minute stages progress from a 2 mph walk toward the chosen pace. Skip remaining warm-up is available on the workout screen and expanded overlay. Pause freezes the prelude. Skipping does not skip Garmin-prescribed warm-up steps. Warm-up samples are separate laps and excluded from pace learning.

Scheduled HR workouts use a 6 mph default running target, superseded by reliable learned pace or explicit Garmin pace targets, and always bounded by the editable maximum. Start remains at 2 mph followed by confirmed 0.2 mph ramp steps. In-range HR stops further ramp increases. HR control checks ten-second windows, changes incline by 0.5% up to 3% or down to 1%, then changes speed by 0.1 mph at those bounds. Confirmed HR-driven changes wait thirty seconds for settling. HR loss holds settings; manual overrides last until the next prescribed interval. The old hardcoded 4 mph Base cap is migrated once to 6 mph; Tempo defaults to a 10 mph cap. Distance estimates do not become speed prescriptions.

A network-constrained Android WorkManager job refreshes Garmin history weekly, with a due check on returning home. Updates also learn from each saved local workout. Learning examines up to twelve recent running sessions within eight weeks, excludes first-five-minute/warm-up/recovery, walking, HR gaps, pauses, unstable speed/HR and steep grade, and requires at least three stable minutes in each of two runs. Treadmill segments are preferred when sufficient; missing/unknown grade or units are excluded, not guessed. Each run has equal influence; later learned pace changes are limited to 0.3 mph per model update. Account changes cannot mix Garmin history. No watch device metrics are fabricated; Coach credit remains unverified.


### Home and entertainment layout (0.2.19)

The idle home screen now pairs a compact today/week workout card with a separate entertainment section and a small manual/Zone 2 chooser. Empty live metrics are hidden until recording starts. Schedule settings contain sync status, pace-learning explanations and time zone; speed-limit editing stays in workout previews. A fixed sidebar Stop belt button remains available on every OpenRun page. Sleep is also in the sidebar.

Entertainment is accessible on home and through its own sidebar destination. Installed streaming apps open from individual tiles. The existing console Plex installation uses the Netflix package name; its tile is labeled Plex based on the launch activity. The installed Fulguris browser appears as Web player. Active workouts open the floating controls before launching a player; idle launches show a compact Back to OpenRun shortcut without starting or recording a workout (0.2.20). Tapping it returns home and removes the shortcut.

### Workouts over entertainment (0.2.21)

Opening a player while idle shows a bottom menu for the selected runner: Start workout (manual), Zone 2, and executable Garmin workouts scheduled for today in that runner's time zone and account. The menu can collapse while choosing a show. A warm-up checkbox uses the runner's existing preference; selecting a workout shows a start confirmation before the existing three-second countdown. Zone 2 and Garmin guides require a connected chest strap and verified controls. Existing start validation remains in force, with failures shown over the player. Recording switches the menu to live metrics and controls; saving or discarding returns to the idle menu without leaving the player.

### Workout history (0.2.22)

Each runner's saved sessions open into a detail view with tappable heart-rate, speed and incline charts, average/peak metrics and recorded Garmin upload status. Guided runs show prescribed HR bands and per-step average HR; target compliance is the share of valid recorded guided HR readings inside each step's target, excluding optional warm-up and missing data. Gaps stay blank, and the time axis follows active workout time rather than pauses. Older sessions without guide metadata still show their available measurements. Session dates use the recorded UTC offset.

### Live track (0.2.23)

Active workouts on the Workout screen show a 400-meter oval, position marker, current lap, completed laps and meters remaining to the next lap. Position uses recorded session distance, including warm-up, rather than simulated speed or elapsed time; pauses and missing distance telemetry cannot advance the marker. One mile is slightly more than four laps. Existing start/countdown, manual controls, Pause/End and entertainment overlays are unchanged.
