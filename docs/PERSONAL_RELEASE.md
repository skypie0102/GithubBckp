# Personal release process

This project is intended for personal/internal use rather than public app-store distribution. The release process therefore optimizes for a reproducible signed APK, real-device backup/recovery confidence, and preserving the signing key for future upgrades.

The active reliability roadmap lives in [`ROADMAP.md`](ROADMAP.md). Roadmap items are prioritized product improvements; a feature that is intentionally outside scope is not a release blocker merely because GitHub could theoretically support more state.

## 1. Freeze and verify the code

Before producing a release APK:

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

CI runs the same build/test gate. `assembleRelease` is expected to work without signing configuration so CI can verify the release variant without holding private keys.

Do not tag a known-good build from a commit whose CI gate is failing.

## 2. Create and protect a signing key

Create the key once and keep multiple secure backups. Do not commit the keystore or any passwords to Git.

Example:

```bash
keytool -genkeypair \
  -keystore githubbckp-release.jks \
  -alias githubbckp \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

The same signing key must be retained to install future upgrades over an existing release build.

## 3. Configure release signing locally

Set all four values together, either as Gradle properties or environment variables:

```bash
export RELEASE_STORE_FILE=/absolute/path/to/githubbckp-release.jks
export RELEASE_STORE_PASSWORD='...'
export RELEASE_KEY_ALIAS=githubbckp
export RELEASE_KEY_PASSWORD='...'
```

Do not place secrets in tracked files or shell history. A partially configured signing setup intentionally fails the Gradle configuration step rather than silently creating the wrong artifact.

The GitHub OAuth client ID is supplied independently:

```bash
export GITHUB_CLIENT_ID='...'
```

## 4. Build the signed APK

```bash
./gradlew :app:clean :app:assembleRelease
```

Expected artifact:

```text
app/build/outputs/apk/release/app-release.apk
```

Verify the package is signed before installing:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Record the SHA-256 of the APK alongside the release notes:

```bash
sha256sum app/build/outputs/apk/release/app-release.apk
```

## 5. Real-device release gate

Run these checks on the signed release build before tagging it as known-good:

1. Fresh install and GitHub Device Flow authorization.
2. Repository discovery and selection, including at least one private repository if private backup is used.
3. Manual source-snapshot backup to the normal destination.
4. Manual Git-mirror backup containing representative history, branches/tags, and Git LFS if available.
5. Verify the completed backup appears in history with the correct destination/provenance.
6. Import a mirror and complete a recovery to a new or provably empty GitHub repository.
7. Confirm restored refs and representative content; for applicable test repositories also confirm LFS objects and release assets.
8. Configure an automatic backup, allow one scheduled run to complete, and confirm live run progress/history.
9. Exercise a recoverable failure (for example temporary network loss or unavailable destination), then retry and confirm the final state is correct.
10. Reboot the device and confirm persisted authorization/settings/history still behave as expected.
11. If the candidate contains the backup-health dashboard, confirm a successful repository reports protected, an intentionally old scheduled backup reports stale, and a failed attempt after the latest success reports failed until a later success clears it.

A failure in backup integrity, restore validation, recovery target safety, authentication, signing, or a feature explicitly included in the candidate is a release blocker. Cosmetic work and features explicitly cut in [`ROADMAP.md`](ROADMAP.md) are not blockers.

## 6. Personal-use recovery promise

A known-good release is expected to preserve and recover the supported surfaces documented by the repository. It is **not** expected to recreate every server-managed GitHub behavior.

In particular, the personal product intentionally does not promise destructive overwrite of live repositories, native discussion recreation, automatic wiki publication, organization/team reconstruction, or exact historical release semantics. Keeping those operations out of the release promise reduces the risk of a recovery tool damaging live state.

## 7. Tag the known-good build

Only after the real-device gate passes, tag the exact commit used to build the APK. Use an RC ref/tag until device validation is complete, then promote the exact known-good commit to the intended personal version tag.

Keep the signed APK, its SHA-256, release notes, and signing-keystore backup together in secure storage.
