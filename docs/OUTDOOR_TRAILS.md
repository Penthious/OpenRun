# Outdoor Trails

Open Entertainment → Outdoor Trails. On Android 9, select **Enable Downloads
scan** and allow file access once. The library checks Downloads every five
seconds while open, and on return from the browser. Only GPX files are read;
valid routes are copied into OpenRun's private library and deduplicated by file
content. Saved hikes remain available after restart or removal of the download.
On newer Android versions use **Import file** through the system picker.

**Browse AllTrails** opens Firefox when installed, with OpenRun's return overlay.
Sign in, select a trail, and export **GPX Track**. If Google login fails to finish,
a site-specific Firefox tracking-protection exception for AllTrails resolved it
on the test console. The original WebView 83 rendered pages but failed to handle
some interactions. Firefox does not replace the system WebView.

Return using **Hikes** in the overlay. Select a library entry and an incline cap
(3, 5, 10, 15, 20, 30, or 40%; defaults to 40%). Choose **Start hike**, or **Queue hike & open Plex**. Queuing
does not move the belt. In Plex, expand the floating menu and select **Start hike**,
then confirm. A selected runner and verified controls are required. The queued
selection is cleared when changing runner and is not restored after app restart.

Start uses the existing three-second countdown. Without warm-up, OpenRun sets and confirms the first route grade before commanding the belt to 2 mph; a failure prevents that speed command. With warm-up, terrain following begins as soon as warm-up finishes. Speed remains
manual (up to 4 mph). Terrain controls incline only: the grade is smoothed over
30 meters, downhill follows terrain down to −6%, and changes are limited to 1 percentage point
without an extra delay after telemetry confirms the previous setting. The optional five-minute warm-up
can be skipped; its distance is excluded from trail progress. Manual incline adjustments
hold terrain following for 60 seconds. Manual speed changes leave terrain following
enabled, including physical speed changes during an incline command. A command failure halts automatic changes
until an explicit pause/resume. Missing telemetry holds incline. Out-of-limit
incline or a stopped belt also halts the guide.

Completing the route requests a belt stop. End the workout to save or discard
using the existing controls. History records the hike name and actual treadmill
metrics, and uses the existing Garmin upload path (indoor workout, no outdoor GPS
track). Local hikes are excluded from the adaptive running pace model.

Files require valid coordinates and elevation for terrain control. Missing
elevation permits a preview only. Multiple track segments are rejected to avoid
joining disconnected sections. Limits: 4 MB per GPX and 50,000 points. Only UTF-8
GPX is accepted; DTDs and external XML references are blocked. Existing test-preview
routes are migrated into the library automatically.

During a hike the main Workout screen shows its offline GPX trail map instead of
the running track, with an interpolated position marker, completed route and
remaining distance. Outdoor Trails is always available in the sidebar. Starting
a hike directly opens this workout screen; Watch Plex restores the live overlay.
Terrain targets round to whole percentages, with changes up to 1% at a time.

The live map and library preview include a distance-scaled elevation profile with
a synchronized position marker and highlighted completed section. Estimated GPX
elevation gain and elevation range are shown in feet; these describe the route,
not the treadmill’s measured ascent (which can differ due to incline limits).

Live Stop, Pause/Resume and End controls stay fixed in the main sidebar. The
workout view puts metrics first and uses compact trail and elevation charts.
New sessions record estimated descent from distance and measured negative incline;
history shows ascent and descent separately. Future hike uploads use FIT session
ascent/descent fields. Older sessions show unavailable descent; previous Garmin
uploads are not changed. GPX gain remains a route estimate, not a workout total.
