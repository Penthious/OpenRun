## 0.2.47

- Home now shows the selected runner’s weekly saved workout count, time, distance and climbing below entertainment. Tap the card to open Progress.

OpenRun 0.2.46 adds four color schemes under Settings, saved independently
for each runner: Charcoal & lime, Midnight blue, Forest and Plum. Selections
apply immediately across app screens and the entertainment overlay. Existing
profiles keep Charcoal by default; Stop controls remain red in every scheme.

OpenRun 0.2.45 keeps Quick Start and Zone 2 aligned in one button row.
The connection hint occupies its own line below Zone 2, including when blank.

OpenRun 0.2.44 refines the theme with charcoal backgrounds, neutral cards,
brighter secondary text and a subtle selected-navigation marker. Workout,
trail, progress, builder, maintenance and overlay surfaces share the palette.
Plex branding, chart colors and red Stop controls retain their distinct colors.

OpenRun 0.2.43 puts Start hike and Queue for Plex directly on Home trail cards.
Start uses the existing countdown, profile warm-up and −6% / 40% terrain limits;
queue opens Plex without moving the belt. The collapsed workout overlay now
sizes to its contents instead of reserving a fixed-width blank area.

OpenRun 0.2.42 removes the Zone 2 setup panel from live sessions. Existing
Zone 2 sessions retain compact status and an option to turn off automatic
adjustments. Home now shows the five most recently imported trails beneath
the Garmin schedule; a trail opens directly to its Start / Queue options.

OpenRun 0.2.41 hides import and scanning tools while viewing a selected trail.
Return to All trails to access them; background scanning continues. Unavailable
Zone 2, workout-library and hike actions now explain their requirements nearby.

OpenRun 0.2.40 makes workout history easier to scan with compact rows and
separate detail views. The workout library separates custom plans from Garmin
imports. Trail cards include an offline route preview, distance and GPX-based
ascent/descent estimates, with distinct Start hike and Queue for Plex actions.
Automatic download scanning leaves the library selection unchanged.

OpenRun 0.2.39 refreshes the entertainment overlay with rounded controls,
HR and elapsed time in the compact view, speed/incline adjustment buttons,
a single guidance line and separate Pause, End and Stop controls. Completing
or discarding a workout leaves a small OpenRun / Workouts shortcut over the
player. Belt controls still use the existing guarded control path.

OpenRun 0.2.38 refreshes the console layout with a compact profile picker, five
main navigation destinations and prominent Quick Start / Zone 2 controls.
Workout history lives under Progress; connections and app maintenance live
under Settings. Saved workouts have an interval preview before starting.
Pause, End and Stop Belt remain fixed during sessions, and speed/incline
controls appear above the track or trail map.

OpenRun 0.2.37 adds a per-profile Progress dashboard: eight weeks of selectable
weekly totals, distance/time/climbing trends, personal records, and base-run
pace comparisons at similar heart rates. Missing historical descent remains
unknown; fastest-mile records exclude pauses and telemetry gaps. Analytics use
completed local workouts only and never change treadmill controls or targets.

OpenRun 0.2.36 adds a per-profile workout builder. Create, edit, duplicate and
delete saved workouts with timed or distance-based intervals, speed/incline
targets and optional heart-rate guidance. Fixed-target workouts and their
warm-ups can run without a chest strap. Distance and speed prescriptions are
retained in Garmin FIT exports.

See [custom workouts](https://github.com/Penthious/OpenRun/blob/main/docs/CUSTOM_WORKOUTS.md)
for limits, ramp behavior and controls.

This APK excludes GlassOS credentials. Install NordicFTMS separately and import
your local console credential ZIP under OpenRun → Settings before using
treadmill controls. See the installation guide in the repository.

Verify SHA256SUMS.txt before installing. Install with the belt stopped and no
active workout. Upgrades require the same signing key; do not uninstall an
existing app to resolve a signature mismatch unless you accept losing its data.

Garmin Coach credit remains unverified. This is an experimental console
integration; verify physical Start/Pause/Stop behavior on your own equipment.

The FIT SDK APK-bundling question has been resolved using Garmin's direct
clarification. The SDK retains its own license; see the
[distribution review](https://github.com/Penthious/OpenRun/blob/main/docs/GARMIN_FIT_LICENSE_REVIEW.md).
