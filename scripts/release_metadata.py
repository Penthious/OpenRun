#!/usr/bin/env python3
"""Create the bounded, versioned manifest consumed by OpenRun's updater."""
import hashlib
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
config = (root / 'app/build.gradle.kts').read_text()
version = re.search(r'versionName = "([^"]+)"', config)[1]
code = int(re.search(r'versionCode = (\d+)', config)[1])
apk = Path(sys.argv[1])
assert apk.name == f'OpenRun-v{version}.apk', 'APK name must match app version'
manifest = dict(versionCode=code, versionName=version, apk=apk.name,
                sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), minSdk=28)
(apk.parent / 'update.json').write_text(json.dumps(manifest, indent=2) + '\n')
