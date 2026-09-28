# Garmin FIT SDK distribution review

Reviewed September 28, 2026 for OpenRun 0.2.35, using com.garmin:fit:21.176.0.

## APK bundling clarification

The distribution hold concerned whether section 2(c) of the FIT SDK license
prohibited bundling its compiled Java code inside OpenRun's APK.

In a direct response to that exact Android packaging question, Ben FIT on
Garmin's FIT SDK forum explains that APK bundling is permitted: section 1
allows the SDK to be used in the licensee's software, and section 2(c) does
not prohibit uses expressly permitted elsewhere in the agreement.

- [Direct Android APK clarification](https://forums.garmin.com/developer/fit-sdk/f/discussion/441419/can-a-commercial-android-app-bundle-the-fit-java-sdk-com-garmin-fit-for-distribution/2051563)
- [Related Java/Maven clarification](https://forums.garmin.com/developer/fit-sdk/f/discussion/439800/redistribution-of-the-garmin-fit-java-library/2045668)
- [Exact SDK version's license](https://github.com/garmin/fit-java-sdk/blob/21.176.0/LICENSE.txt)

This resolves the APK-bundling concern; the full license continues to apply.
It is not permission to redistribute the SDK as a separate developer product
or to relicense it under OpenRun's Apache-2.0 license.

## OpenRun's use

OpenRun uses the unmodified Maven dependency internally to encode saved
activity data for Garmin upload. Its SDK imports are confined to FitExporter;
treadmill control and heart-rate guidance do not depend on the SDK. The SDK
is not exposed as a separate developer tool or bundled as a standalone JAR.

The APK and release notice archive include the original FIT license text.
THIRD_PARTY_NOTICES.md identifies the SDK's separate license. The existing
signed 0.2.35 APK can therefore be published without replacing the exporter
or changing the APK, version tag, signing key or stored workout data.

This review addresses SDK packaging. It does not establish Garmin endorsement,
Garmin Coach completion credit, or approval of the separate Garmin Connect
account/upload integration. Recheck licensing when changing SDK versions or use.
