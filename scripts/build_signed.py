#!/usr/bin/env python3
"""Build locally with the same permanent key used by GitHub releases. Never prints secrets."""
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
secret = root / 'secrets/release/signing.json'
if not secret.exists():
    raise SystemExit('Missing ignored secrets/release/signing.json; see docs/RELEASING.md.')
settings = json.loads(secret.read_text())
env = dict(os.environ)
env.update(OPENRUN_KEYSTORE=str(root / 'secrets/release/openrun-release.p12'),
           OPENRUN_STORE_PASSWORD=settings['store_password'],
           OPENRUN_KEY_ALIAS=settings['alias'], OPENRUN_KEY_PASSWORD=settings['key_password'])
subprocess.run([str(root / 'gradlew'), ':app:testDebugUnitTest', ':app:assembleRelease'], cwd=root, env=env, check=True)
subprocess.run(['python3', 'scripts/check_apk.py', 'app/build/outputs/apk/release/app-release.apk'], cwd=root, check=True)
