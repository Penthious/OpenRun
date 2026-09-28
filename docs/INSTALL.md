# Install OpenRun on a NordicTrack console

These instructions target the Android 9 Argon2 console used for development.
Menus and access methods vary by firmware. Keep the belt stopped during
installation. Retain iFit/GlassOS system packages. A factory reset, rooting
or firmware flashing is not part of this guide.

## 1. Enable privileged mode and Android debugging

Start from the iFit dashboard with the belt stopped. If you already have
access to Android Settings and USB debugging is enabled, skip to the next
section.

1. Find a blank area of the dashboard that does not activate a button.
   Tap it **ten times**, wait **seven seconds**, then tap the same area
   **ten more times**.
2. If the console displays an activation challenge, open
   [getresponsecode.com](https://getresponsecode.com/) on your phone or
   computer. Enter the **six-digit challenge code shown on the treadmill**
   and submit it. Type the returned response code into the treadmill's
   activation screen.
3. Wait for confirmation that privileged mode is enabled. Open the Android
   app drawer and select **Settings**.
4. Navigate to **System → About tablet**, or **System → Advanced → About
   tablet**, depending on the console. Tap **Build number seven times** to
   unlock Developer options.
5. Go back to **Developer options** and turn on **USB debugging**. Confirm
   the prompt. When you connect your computer, accept the console's
   **Allow USB debugging** authorization prompt.

Privileged mode provides access to Android; USB debugging lets your computer
connect through ADB. Menu names and activation screens vary by firmware.
If privileged mode is already enabled, do not repeat the tap sequence—it
may turn the mode off.

## 2. Connect OpenPelo

Download [OpenPelo](https://github.com/doudar/OpenPelo/releases) for your
computer. On the tested NordicTrack Android 9 console, enabling USB debugging
also exposes **ADB over Wi-Fi at the treadmill's IP address, port 5555**.
You can connect directly without first attaching a USB cable.

1. Connect your computer and treadmill to the same trusted local network.
2. Find the treadmill's IP address in Android **Settings → Wi-Fi → connected
   network details** (the exact menu varies by firmware).
3. In OpenPelo's Wi-Fi connection flow, use the console's **IP:PORT**, for
   example **192.168.1.105:5555**. Replace the example IP with your own.
4. Accept any debugging authorization prompt on the treadmill, then select
   it as OpenPelo's **Target Device**.

Android 9 uses this direct ADB connection, not Android 11's wireless
pairing-code flow. The command-line equivalent is:

~~~sh
adb connect TREADMILL_IP:5555
adb devices
~~~

Availability can vary by NordicTrack firmware. If the connection fails,
confirm debugging is enabled, recheck the IP address, and make sure your
network allows the two devices to communicate. USB is an alternative if
your console supports it. See
[OpenPelo's connection guide](https://github.com/doudar/OpenPelo#connect-a-device).

## 3. Install the applications

Obtain NordicFTMS separately from its
[project](https://github.com/mikepugh/NordicFTMS) and enable local DIRCON.
Keep the existing GlassOS services installed.

Download the APK and SHA256SUMS.txt from a **published**
[OpenRun release](https://github.com/Penthious/OpenRun/releases).
If none is published, use a local build; a draft is not a public download.

Check the APK against SHA256SUMS.txt. In OpenPelo, choose **Install local APK**
and select the file. Launch OpenRun from Installed App Manager.
See [OpenPelo's app instructions](https://github.com/doudar/OpenPelo#2-install-apps).

**Signature mismatch:** cancel rather than choosing Uninstall & Reinstall
if you want to preserve history. Local debug builds and official release
builds normally have different signing identities. Existing debug users
must [back up and migrate their data](UPDATES_AND_BACKUPS.md) before changing
identities. Versions 0.2.35 and later include an Updates & backup screen.

## 4. Import your local console credentials

Public release APKs contain no GlassOS credentials. You must already have
authorized access to the appropriate CA certificate, client certificate and
PKCS#8 RSA private key for your console integration. Neither OpenRun releases
nor GitHub Actions supply these files. If you do not have them, stop before
using treadmill controls; this remains a prerequisite, not an automatic
first-install setup.

### Example: extract from your installed NordicFTMS companion

On the development console, the installed NordicFTMS APK contains the
required files under assets/certs/. The following helper copies that APK
over ADB and creates a private import ZIP; it does not write anything to
the treadmill, change settings or send motor commands.

From the OpenRun repository, with your own console already connected:

~~~sh
python3 scripts/extract_console_credentials.py --serial TREADMILL_IP:5555
~~~

If your local ADB server uses a custom port, add, for example,
--adb-port 5038. An already downloaded companion can also be used:

~~~sh
python3 scripts/extract_console_credentials.py --apk /path/to/NordicFTMS.apk
~~~

The result is secrets/glassos/console-credentials.zip, ignored by Git.
The helper fails if the expected assets are absent and never overwrites an
existing bundle. This is extraction from the installed companion, not from
Android Keystore or your Garmin account; other companion versions may differ.

Create a ZIP with these three files at its root (no containing directory):

- glassos_ca.pem
- glassos_client_cert.pem
- glassos_client_key.pem

For example, from the local directory containing your files:

~~~sh
zip console-credentials.zip glassos_ca.pem glassos_client_cert.pem glassos_client_key.pem
~~~

Transfer it to the console's Download directory using OpenPelo File Manager.
In OpenRun → Connections, select **Import console credentials** and choose
the ZIP. The importer validates the certificate/key pairing and stores the
bundle in private, non-backup app storage. It does not test hardware control.

Force stop OpenRun using Installed App Manager, then launch it again.
Delete the ZIP from shared Downloads and any unnecessary transfer copies.
Keep your original credentials private. Never attach them to a GitHub issue.

## 5. Finish setup

Create a runner, pair the chest strap and grant **Display over other apps**.
Verify Start, small speed/incline changes, Pause, Resume, End and physical
Stop/safety-key behavior in person. Only then confirm control verification
under Connections. Garmin is optional.

If Android Settings immediately closes on the tested Android 9 console,
OpenRun has a narrowly scoped Settings-access helper. This optional ADB grant
allows it to remove the known Settings-blocking accessibility hook:

~~~sh
adb shell pm grant dev.digitalducktape.openrun android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n dev.digitalducktape.openrun/.MainActivity
~~~

This is not universal privileged-mode persistence. It does not enable ADB
from scratch. To revoke the permission:

~~~sh
adb shell pm revoke dev.digitalducktape.openrun android.permission.WRITE_SECURE_SETTINGS
~~~

OpenPelo's Set Default Launcher can select OpenRun as Home. Firmware may
still start iFit before OpenRun at boot; Home selection alone cannot stop
that behavior. Verify access again after reboot before relying on it.
