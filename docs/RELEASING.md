# Maintainer release setup

## Workflows

- Android CI runs tests, builds an unsigned release and checks its contents
  on main pushes and pull requests. It uses no signing or console secrets.
- Release APK runs on a version tag such as v0.2.24. The tag must match
  versionName. It tests, builds/signs the release, checks for credential
  assets/private keys, verifies the APK signature, and creates a **draft**
  GitHub release containing the APK, SHA-256 checksum and notices.

Drafts allow an installation/control smoke test and dependency-notice review
before publishing a hardware-control application. A failed check prevents
release creation. No workflow installs anything on a treadmill.

## Signing secrets

Store the permanent release signing key and its private backup metadata under
the ignored secrets/release/ directory in this checkout. Do not
reuse Android's development debug key for public releases. Never upload
GlassOS credentials to Actions.

Create a GitHub environment named **release**, restrict its deployment
branches/tags as appropriate, and configure these environment secrets:

- OPENRUN_KEYSTORE_BASE64: base64 of your release keystore
- OPENRUN_STORE_PASSWORD
- OPENRUN_KEY_ALIAS
- OPENRUN_KEY_PASSWORD

Use the repository's Settings → Environments → release, or GitHub's
[secrets setup](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
Do not paste secrets into workflow YAML or commit them.

Generate a key interactively inside the ignored secrets/release/ directory using Android Studio's
Generate Signed APK flow or keytool; follow
[Android signing guidance](https://developer.android.com/studio/publish/app-signing).
Retain the same key for all future releases. An existing debug-signed
installation will reject this new identity as an in-place update. Preserve
that installation until its data can be migrated.

## Cut a release

1. Finish validation, increment versionCode and versionName together.
2. Commit the tested changes to main and push.
3. Push the matching version tag, for example:

~~~sh
git tag v0.2.24
git push origin v0.2.24
~~~

4. Inspect the Actions run and draft release. Test the signed APK on a
   suitable device with locally imported credentials; verify Pause/Stop.
5. Review and supply the exact resolved dependencies' required license and
   notice materials before publishing; the source-level notice inventory
   alone does not complete binary redistribution review.
6. Publish the draft when ready.

Do not move an already released tag. Correct a failed release using the
existing run when possible; use a new version for changed source. A duplicate
release fails creation rather than silently replacing downloadable APKs.


## Private signing files in this checkout

The local setup uses secrets/release/openrun-release.p12, signing.json
(passwords and alias), and certificate-sha256.txt (public fingerprint).
The folder is owner-only and all contents are ignored. Never print the
password file into logs or attach it to issues.

The pre-commit check rejects secrets/ even when force-staged. Enable it in
each clone with:

~~~sh
git config core.hooksPath .githooks
~~~

Hooks and ignore rules can be deliberately bypassed; they are safeguards,
not an absolute filesystem boundary. CI also runs the source checker.
Ignored files are not copied by cloning the repository. Losing this local
directory means losing this backup; protect the containing disk accordingly.
