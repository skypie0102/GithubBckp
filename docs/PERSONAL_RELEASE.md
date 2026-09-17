# Personal release process

This project is intended for personal/internal use rather than public app-store distribution. The release process therefore optimizes for a reproducible signed APK, real-device backup/recovery confidence, and preserving the signing key for future upgrades.

The completed personal reliability roadmap lives in [`ROADMAP.md`](ROADMAP.md). Features intentionally outside the documented product boundary are not release blockers merely because GitHub could theoretically support more state.

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
2. On Android 13+, accept the notification permission prompt and confirm the app's **Backup health** notification channel is enabled in system settings.
3. Repository discovery and selection, including at least one private repository if private backup is used.
4. Manual source-snapshot backup to the normal destination.
5. Manual Git-mirror backup containing representative history, branches/tags, and Git LFS if available.
6. Verify each completed backup appears in history with the correct destination/provenance.
7. Tap **Re-verify stored backup** for the source snapshot. Confirm the full remote artifact is read, the row records a `VERIFIED` result/timestamp, and no new remote backup object is created.
8. Re-verify the Git mirror. Confirm the result reports successful remote-byte verification plus safe local mirror/module validation; the operation must not publish anything to GitHub.
9. Export the backup audit JSON after re-verification and confirm `latestReverification` contains the recorded timestamp/result/detail. Exporting the JSON again must not itself perform another remote read.
10. If retention has pruned an older artifact, confirm that historical row remains auditable but does not offer re-verification.
11. Import the test Git mirror through the normal restore picker and confirm local mirror/module validation succeeds.
12. Open **Recovery drill**, select that validated mirror, and run the drill against a new private GitHub repository (or a private existing repository that is provably empty).
13. Confirm the drill reports `VERIFIED`, including exact Git ref verification, applicable LFS availability with representative byte re-download, and applicable release/asset verification. Confirm wiki/discussion modules are clearly reported as archival-only.
14. Export the recovery-drill audit JSON and inspect the target/module counts. Confirm the private drill target remains on GitHub for manual inspection and is not automatically deleted.
15. Configure an automatic backup, allow one scheduled run to complete, and confirm live run progress/history.
16. Exercise a recoverable failure (for example temporary network loss or unavailable destination). Confirm a failure notification is shown after the failure is persisted, tapping it opens the app, then retry and confirm the final state is correct.
17. Confirm repeated failures for the same repository/format do not create notification storms inside the six-hour rate-limit window.
18. For the backup-health dashboard, confirm a successful repository reports protected, an intentionally old scheduled backup reports stale, and a failed attempt after the latest success reports failed until a later success clears it.
19. Exercise or simulate an overdue repository and confirm the grouped overdue notification appears once, remains deduplicated on the next health check, and can notify again after the repository recovers and later becomes overdue again.
20. Disable automatic backups and confirm overdue notification state is cleared; re-enable and confirm the new schedule receives a fresh two-cadence grace window for repositories without a verified backup.
21. Reboot the device and confirm persisted authorization/settings/history/re-verification/drill results still behave as expected.

If notification permission is intentionally denied, backup execution must continue normally; record that failure/overdue alerts are unavailable until notifications are enabled in Android settings.

A failure in backup integrity, on-demand re-verification, restore validation, guided recovery-drill verification, recovery target safety, authentication, signing, notification behavior included in the candidate, or another explicitly included feature is a release blocker. Cosmetic work and features explicitly cut in [`ROADMAP.md`](ROADMAP.md) are not blockers.

## 6. Personal-use recovery promise

A known-good release is expected to preserve and recover the supported surfaces documented by the repository. It is **not** expected to recreate every server-managed GitHub behavior.

In particular, the personal product intentionally does not promise destructive overwrite of live repositories, native discussion recreation, automatic wiki publication, organization/team reconstruction, or exact historical release semantics. Keeping those operations out of the release promise reduces the risk of a recovery tool damaging live state.

A successful guided drill is the preferred evidence that a candidate release still satisfies the supported recovery promise on a real account and real device. See [`RECOVERY_DRILL.md`](RECOVERY_DRILL.md).

## 7. Tag the known-good build

Only after the real-device gate passes, tag the exact commit used to build the APK. Use an RC ref/tag until device validation is complete, then promote the exact known-good commit to the intended personal version tag.

Keep the signed APK, its SHA-256, release notes, recovery-drill audit, and signing-keystore backup together in secure storage.
