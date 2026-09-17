# GithubBckp

GithubBckp is a personal/internal Android app for keeping recoverable copies of GitHub repositories in storage controlled by the user. Public Play Store distribution is outside scope.

The product goal is narrow: keep **one current, verified Git mirror per selected repository** and update that same logical backup on manual or automatic runs.

## What it backs up

GithubBckp is mirror-only. A backup performs mirror-style Git preservation and packages the repository into one `.mirror.zip` artifact. It preserves Git refs/history, referenced Git LFS objects, initialized wiki history when present, releases/assets, and issue/pull-request discussion metadata supported by the app.

Every completed replacement is checksummed and remotely verified before it becomes current. The previous verified mirror is not retired until the replacement is committed successfully.

Source snapshots and retention/version-copy controls have been removed. Older database rows can still describe historical backup types, but new manual and scheduled backups always use Git mirrors.

## Authentication

### GitHub

Enter a GitHub personal access token (PAT) in the app. GithubBckp validates it against GitHub and stores it encrypted through the Android Keystore-backed secure store.

The PAT must be able to read every private repository you want to back up. Recovery additionally needs the permissions required by the chosen recovery target.

The PAT is never embedded in the APK, Gradle configuration, repository, CI logs, or release metadata.

### Google Drive

Google Drive authorization is separate from GitHub authentication. The Drive adapter uses Google Play services `AuthorizationClient` with `drive.file`.

Android Google OAuth clients are bound to both the package name and APK signing certificate. Register:

```text
Package: com.skypie0102.githubbckp
SHA-1:   <persistent release signing certificate SHA-1>
```

The release workflow prints the signing SHA-1 into each GitHub release body. If Drive account selection returns without connecting, the app also reports the installed package and signing SHA-1 so the OAuth client can be checked directly.

## Backup destinations

### Backup folder

The Android Storage Access Framework can write to a user-selected document tree, including local storage, removable storage, or a compatible cloud DocumentsProvider.

Replacement mirrors are written to a sibling staging document and fully rehashed before the previous verified document is retired. An interrupted write therefore does not truncate the last known-good mirror.

### Google Drive

A replacement is uploaded as a new resumable Drive object and verified before the previous verified Drive object is retired. An interrupted upload therefore does not overwrite the only known-good mirror.

## Automatic backups

Automatic backups support disabled, daily, or weekly execution. WorkManager runs opportunistically under unmetered-network, battery-not-low, and storage-not-low constraints.

Automatic runs use the same replacement flow as manual runs. They do not intentionally create historical generations.

Scheduled health is considered overdue after two cadence windows: 48 hours for daily and 14 days for weekly. Legacy source-snapshot rows do not satisfy current mirror health.

## Recovery and verification

A validated mirror can be restored locally and published to either a new GitHub repository or an existing repository that is still empty. Recovery never force-pushes arbitrary live repositories.

Eligible completed current mirrors expose **Re-verify stored mirror**. The app reads the entire stored object again, recomputes its digests, and runs the local mirror/module validator. Re-verification never creates another remote backup object.

Completed backups and imported mirrors can export JSON audit reports.

## Build and CI

Requirements:

- JDK 17+
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0+

Repository gate:

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

Debug builds are for development only.

## Release APK

The APK release flow mirrors the Intake Edit repository:

- merge/push a new app version to `main`;
- the release workflow validates `versionName` / `versionCode`;
- it builds the minified/shrunk **release** APK;
- it verifies the APK signature;
- it publishes a GitHub release and workflow artifact;
- the install file is named `githubbckp-v<version>.apk`, not `app-debug.apk`;
- a matching `.sha256` file is published alongside it.

Unlike Intake Edit's current one-off fallback signing, GithubBckp requires a **persistent release key**. This is intentional because Google Drive Android OAuth is tied to the signing certificate SHA-1. A disposable key would make Drive authorization change on every release.

Configure these GitHub Actions secrets before the first release:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

When migrating from an older debug-signed/personal-signed APK to the new release key, Android may require one uninstall. After that, APKs signed with the same persistent release key can update in place as long as `versionCode` increases.

See [`docs/RELEASE.md`](docs/RELEASE.md).

## Security

Do not commit personal access tokens, Google credentials/tokens, keystores, or generated `local.properties`.

Git credentials are passed to JGit's transport layer rather than embedded in repository URLs. Temporary backup, verification, and restore files live in app-private/cache storage and are cleaned up after use.

## Personal-use non-goals

The app intentionally does not provide destructive overwrite of arbitrary non-empty repositories, force-push recovery workflows, native recreation of GitHub identities/timestamps for discussions, organization/team reconstruction, automatic wiki publication, or Play Store/public-distribution work.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the product boundary and implementation structure.
