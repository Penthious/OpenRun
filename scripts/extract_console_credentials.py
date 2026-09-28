#!/usr/bin/env python3
"""Read credential assets from the owner's installed NordicFTMS APK. No device writes."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NAMES = ("glassos_ca.pem", "glassos_client_cert.pem", "glassos_client_key.pem")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB device serial, such as TREADMILL_IP:5555")
    parser.add_argument("--adb-port", type=int, help="Optional local ADB server port")
    parser.add_argument("--apk", type=Path, help="Use an already downloaded NordicFTMS APK instead")
    args = parser.parse_args()
    os.umask(0o077)
    folder = ROOT / "secrets" / "glassos"
    output = folder / "console-credentials.zip"
    check = subprocess.run(["git", "-C", str(ROOT), "check-ignore", "-q", str(output)])
    if check.returncode:
        raise SystemExit("Refusing to write credentials: output is not ignored by Git.")
    if output.exists():
        raise SystemExit("Private bundle already exists; left unchanged: " + str(output))
    folder.mkdir(parents=True, exist_ok=True)
    folder.chmod(0o700)
    with tempfile.TemporaryDirectory(dir=folder) as temp:
        if args.apk:
            apks = [args.apk]
        else:
            if not args.serial:
                parser.error("--serial is required unless --apk is provided")
            adb = ["adb"]
            if args.adb_port:
                adb += ["-P", str(args.adb_port)]
            adb += ["-s", args.serial]
            result = subprocess.run(adb + ["shell", "pm", "path", "com.nordicftms.app"],
                                    check=True, capture_output=True, text=True)
            paths = [line.removeprefix("package:").strip() for line in result.stdout.splitlines()
                     if line.startswith("package:")]
            if not paths:
                raise SystemExit("NordicFTMS is not installed on the selected device.")
            apks = []
            for index, remote in enumerate(paths):
                path = Path(temp) / f"companion-{index}.apk"
                subprocess.run(adb + ["pull", remote, str(path)], check=True, capture_output=True)
                apks.append(path)
        found = {}
        for apk in apks:
            with zipfile.ZipFile(apk) as archive:
                for name in NAMES:
                    member = "assets/certs/" + name
                    if member not in archive.namelist():
                        continue
                    info = archive.getinfo(member)
                    if not 0 < info.file_size <= 32768:
                        raise SystemExit("Unexpected credential asset size; no bundle created.")
                    data = archive.read(member)
                    if name in found and found[name] != data:
                        raise SystemExit("Conflicting credentials across APKs; no bundle created.")
                    found[name] = data
        if set(found) != set(NAMES):
            raise SystemExit("This companion version does not contain the expected assets. No bundle created.")
        staged = Path(temp) / "bundle.zip"
        with zipfile.ZipFile(staged, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
            for name in NAMES:
                bundle.writestr(name, found[name])
        # Exclusive creation prevents accidentally replacing an existing bundle.
        with output.open("xb") as destination:
            destination.write(staged.read_bytes())
    print("Created private import bundle: " + str(output))
    print("No treadmill settings or controls were changed. Keep this ZIP out of GitHub.")


if __name__ == "__main__":
    main()
