#!/usr/bin/env python3
"""Reject APKs carrying local credentials before artifact/release upload."""
import re
import sys
import zipfile
from pathlib import PurePosixPath

with zipfile.ZipFile(sys.argv[1]) as apk:
    for entry in apk.infolist():
        name=PurePosixPath(entry.filename)
        if "certs" in name.parts or name.suffix.lower() in {".pem",".key",".p12",".pfx",".jks",".keystore",".crt",".cer"}:
            raise SystemExit("BLOCKED: credential-like APK entry: "+entry.filename)
        if re.search(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/]{32,}",apk.read(entry)):
            raise SystemExit("BLOCKED: private key material in APK entry: "+entry.filename)
print("PASS: APK contains no credential paths or PEM private-key material.")
