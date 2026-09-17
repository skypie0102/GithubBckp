# Personal APK deployment and device validation

This project is intended for personal/internal use rather than public app-store distribution. The normal personal artifact is the Android **debug APK** produced by Gradle. Android still requires every installed APK to carry a certificate, but the debug build is signed automatically by the Android debug keystore; there is no custom release-keystore workflow and no manual signing step for this project.

The reliability roadmap lives in [`ROADMAP.md`](ROADMAP.md). The planned personal reliability feature set is complete; future feature work should be driven by concrete recurring personal-use problems rather than theoretical GitHub parity.

## 1. Freeze and verify the code

Before producing the APK, run the normal repository gate:

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

`assembleRelease` remains part of CI only as a release-variant compile check. It is not the personal install artifact and the project does not configure a custom release signing key.

Do not treat a candidate as known-good if this gate is failing.

## 2. Build the personal APK

No GitHub OAuth App, client ID, PAT, or other GitHub credential is required at build time.

Build the debug APK on the computer you will use for personal deployments:

```bash
./gradlew :app:clean :app:assembleDebug
```

Expected artifact:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Gradle signs this APK automatically with the Android debug keystore, normally stored outside the repository at:

```text
~/.android/debug.keystore
```

There is no `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, or `GITHUB_CLIENT_ID` configuration.

If you want future APKs to install over the existing app without uninstalling it, keep using the same debug keystore. Backing up that keystore is optional for disaster recovery of the app installation itself, but useful if you want seamless in-place upgrades after moving to another development computer. Losing it does not affect the GitHub backup artifacts; it only means a future APK signed by a different key will require uninstall/reinstall.

Record the APK SHA-256 if you want to preserve the exact tested build:

```bash
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

## 3. Install and connect GitHub

Install by opening `app-debug.apk` on the Android device and allowing installation from that source, or with ADB:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

For an update signed by the same debug keystore:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch the app opens a masked **GitHub token** panel. Enter a GitHub personal access token. The app validates it against GitHub before persisting it, then stores it through Android Keystore-backed encrypted app storage. The token is never baked into the APK.

For the simplest full personal backup/recovery setup, use a classic PAT with:

```text
repo
workflow
```

`repo` provides access to private repositories covered by the token. `workflow` is required for complete recovery when restored history includes GitHub Actions workflow files. A valid token without `workflow` can still perform backups that its permissions allow, but full recovery remains guarded.

Use the in-app **GitHub token** control to replace the stored token. A replacement is validated before the old credential is overwritten.

If Android reports an APK signature mismatch during installation, the installed copy was signed with a different key. Uninstalling the old copy permits a fresh install, but Android app-local data—including the encrypted GitHub token and local app settings—will be removed.

## 4. Google Drive setup for the debug APK

Google Drive authentication is separate from GitHub authentication.

Google Drive support uses an Android OAuth client bound to both the package name and the certificate SHA-1. For the personal debug APK, register the **debug signing certificate SHA-1** for:

```text
com.skypie0102.githubbckp
```

Get the fingerprint with:

```bash
./gradlew :app:signingReport
```

Find the `debug` variant and copy its `SHA1` value into the Android OAuth client configuration used for Google Drive.

The SHA-1 belongs to the debug keystore on the machine that built the APK. If the debug keystore changes, the APK will have a different certificate fingerprint and the Google Android OAuth client must be updated with the new SHA-1 before Drive authorization will work for that build.

The app uses Google Play services authorization at runtime and does not persist Google access tokens.

## 5. Real-device validation gate

Run these checks on the exact debug APK you intend to keep as the known-good personal build:

1. Fresh-install the APK and enter a valid GitHub PAT in the masked GitHub token panel.
2. Confirm repository discovery works, including at least one private repository if private backup is used.
3. If full recovery is part of the test, confirm the PAT exposes `workflow` permission (a classic PAT with `repo` + `workflow` is the simplest supported setup).
4. If Google Drive is used, confirm the debug SHA-1 is registered for the Android OAuth client and complete Drive authorization/upload successfully.
5. On Android 13+, accept the notification permission prompt if you want backup-health alerts and confirm the app's **Backup health** notification channel is enabled.
6. Manual source-snapshot backup to the normal destination.
7. Manual Git-mirror backup containing representative history, branches/tags, and Git LFS if available.
8. Verify each completed backup appears in history with the correct destination/provenance.
9. Tap **Re-verify stored backup** for the source snapshot. Confirm the full remote artifact is read, the row records a `VERIFIED` result/timestamp, and no new remote backup object is created.
10. Re-verify the Git mirror. Confirm the result reports successful remote-byte verification plus safe local mirror/module validation; the operation must not publish anything to GitHub.
11. Export the backup audit JSON after re-verification and confirm `latestReverification` contains the recorded timestamp/result/detail. Exporting the JSON again must not itself perform another remote read.
12. If retention has pruned an older artifact, confirm that historical row remains auditable but does not offer re-verification.
13. Import a representative mirror, open **Recovery drill**, and confirm the UI distinguishes automatically republished modules from archival-only modules before publication.
14. Run the drill to a new private repository. Confirm exact post-publication Git-ref verification, all applicable LFS objects are advertised for download, the bounded representative LFS sample is re-downloaded/re-hashed, and release/release-asset verification counts are reported.
15. Confirm the drill target remains present for manual review and is not automatically deleted.
16. Confirm the successful drill result remains visible after reopening the app. If practical, retry the same drill target to exercise resumable drill state; ordinary recovery for the same restored mirror must remain independently available because drill transactions use separate target-specific bindings.
17. Configure an automatic backup, allow one scheduled run to complete, and confirm live run progress/history.
18. Exercise a recoverable failure such as temporary network loss or an unavailable destination. Confirm a failure notification is shown after the failure is persisted, tapping it opens the app, then retry and confirm the final state is correct.
19. Confirm repeated failures for the same repository/format do not create notification storms inside the six-hour rate-limit window.
20. For the backup-health dashboard, confirm a successful repository reports protected, an intentionally old scheduled backup reports stale, and a failed attempt after the latest success reports failed until a later success clears it.
21. Exercise or simulate an overdue repository and confirm the grouped overdue notification appears once, remains deduplicated on the next health check, and can notify again after the repository recovers and later becomes overdue again.
22. Disable automatic backups and confirm overdue notification state is cleared; re-enable and confirm the new schedule receives a fresh two-cadence grace window for repositories without a verified backup.
23. Replace the GitHub PAT in the token panel and confirm an invalid replacement is rejected without destroying the currently stored working credential; confirm a valid replacement refreshes repository discovery.
24. Reboot the device and confirm the encrypted GitHub token, settings, history, re-verification results, and drill results still behave as expected.

If notification permission is intentionally denied, backup execution must continue normally; only the alert surface is unavailable.

A failure in backup integrity, on-demand re-verification, restore validation, recovery-drill verification, recovery target safety, GitHub PAT validation/storage, Google Drive authorization when Drive is part of your setup, scheduled execution, persistence behavior, or another explicitly included feature is a personal-device blocker. Cosmetic work and features explicitly cut in [`ROADMAP.md`](ROADMAP.md) are not blockers.

## 6. Personal-use recovery promise

A known-good personal APK is expected to preserve and recover the supported surfaces documented by the repository. It is **not** expected to recreate every server-managed GitHub behavior.

In particular, the personal product intentionally does not promise destructive overwrite of live repositories, native discussion recreation, automatic wiki publication, organization/team reconstruction, or exact historical release semantics. Keeping those operations out of the product boundary reduces the risk of a recovery tool damaging live state.

## 7. Preserve the known-good build

After the device gate passes, keep the exact tested `app-debug.apk`, its SHA-256, the source commit/ref used to build it, and the latest successful recovery-drill result together in secure personal storage.

A Git tag is optional for this personal workflow. If you do tag a known-good build, tag the exact source commit used to produce the tested APK.
