#!/usr/bin/env python3
"""Check source commit candidates without printing credential values."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


paths = set(git("ls-files", "-z", "--cached", "--others", "--exclude-standard").decode().split("\0")) - {""}
blocked_suffixes = {
    ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".der", ".crt", ".cer",
    ".apk", ".aab", ".apks", ".xapk", ".zip", ".fit", ".tcx", ".db", ".log",
}
patterns = [
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/]{32,}"),
    re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[A-Z0-9]{16})\b"),
]
problems = set()
for name in sorted(paths):
    path = Path(name)
    if (path.suffix.lower() in blocked_suffixes
        or any(part in {"certs", "credentials", "secrets", "local-data", "backups", "build", ".gradle", ".kotlin"} for part in path.parts)
        or (path.name.startswith(".env") and path.name != ".env.example")
        or path.name == "local.properties"):
        problems.add((name, "local credential, data or generated artifact"))
    sources = []
    local = ROOT / path
    if local.is_symlink():
        problems.add((name, "symlink needs manual review"))
    elif local.is_file():
        sources.append(("working tree", local.read_bytes()))
    staged = subprocess.run(["git", "-C", str(ROOT), "show", ":" + name], capture_output=True)
    if staged.returncode == 0:
        sources.append(("index", staged.stdout))
    for origin, content in sources:
        if any(pattern.search(content) for pattern in patterns):
            problems.add((name, "possible secret in " + origin))

if problems:
    for name, reason in sorted(problems):
        print(f"BLOCKED: {name}: {reason}")
    sys.exit(1)
print(f"PASS: checked {len(paths)} tracked/non-ignored source candidates and available staged content.")
print("Local debug APKs may embed supplied credential assets; keep them private.")
