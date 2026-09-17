# GithubBckp

GithubBckp is a personal/internal Android app for keeping recoverable copies of GitHub repositories in storage controlled by the user. Public distribution and Play Store work are intentionally outside scope.

The product goal is narrow: keep **one current, verified Git mirror per selected repository** and update that same logical backup on manual or automatic runs.

## What it backs up

GithubBckp is mirror-only. A backup performs mirror-style Git preservation and packages the repository into one stable `.mirror.zip` artifact. It preserves Git refs/history, referenced Git LFS objects, initialized wiki history when present, releases/assets, and issue/pull-request discussion metadata supported by the app.

Every completed artifact is checksummed before storage. Mirror backup fails instead of silently succeeding when required Git/LFS/release data cannot be fetched or verified.

Source snapshots and retention/version-copy controls have been removed. Older database rows can still describe historical backup types, but new manual and scheduled backups always use Git mirrors.

## Personal GitHub authentication

GithubBckp does **not** require a GitHub OAuth App, Device Flow, OAuth client ID, client secret, or build-time GitHub credential.

On first launch, enter a GitHub personal access token (PAT). The app validates it against GitHub and stores it encrypted through the Android Keystore-backed secure store. The token must be able to read every private repository you want to back up; recovery additionally needs the permissions required by the chosen recovery target.

The PAT is never embedded in the APK, Gradle configuration, repository, CI logs, or release metadata.

## Backup destinations

### Backup folder

The Android Storage Access Framework can write to a user-selected document tree, including local storage, removable storage, or a compatible cloud DocumentsProvider. Repeated backups update the current repository mirror instead of intentionally creating timestamped generations.

### Google Drive

Google Drive authorization is separate from GitHub authentication. The Drive adapter uses Google Play services `AuthorizationClient` with `drive.file`, resumable upload, and remote verification. Existing Drive mirrors are updated in place when possible.

Android Google OAuth clients are bound to both the package name and the APK signing certificate. Register the SHA-1 for:

```text
com.skypie0102.githubbckp
```

Get the fingerprint for the keystore that signs your personal APK with:

```bash
./gradlew :app:signingReport
```

If Drive account selection returns to the app without connecting, the app now reports the installed package and signing SHA-1 to help diagnose a mismatched Android OAuth client.

GitHub-hosted CI does **not** publish an installable APK because its generated debug signing key is not stable across runners and would make the Google OAuth SHA-1 change between builds.

## Build

Requirements:

- JDK 17+
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0+

Repository gate:

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:assemblePersonal :app:testDebugUnitTest :app:lintDebug
```

For the normal personal install, build the optimized `personal` variant locally:

```bash
./gradlew :app:clean :app:assemblePersonal
```

Expected artifact:

```text
app/build/outputs/apk/personal/app-personal.apk
```

The personal variant uses release shrinking/resource shrinking but is signed with your persistent local Android debug keystore so future installs can keep the same certificate and Google Drive OAuth registration.

See [`docs/PERSONAL_RELEASE.md`](docs/PERSONAL_RELEASE.md) for the exact personal build and Google Drive certificate setup.

## Automatic backups

Automatic backups support disabled, daily, or weekly execution. WorkManager runs opportunistically under unmetered-network, battery-not-low, and storage-not-low constraints.

Automatic runs always update the repository's one current Git mirror; they do not create intentional historical copies. After a new mirror is verified, legacy duplicate remote artifacts from older app versions are cleaned up on a best-effort basis.

Scheduled health is considered overdue after two cadence windows: 48 hours for daily and 14 days for weekly.

## Recovery and verification

A validated mirror can be restored locally and published to either a new GitHub repository or an existing repository that is still empty. Recovery never force-pushes arbitrary live repositories.

Eligible completed backups expose **Re-verify stored backup**. The app reads the entire stored object again, recomputes its digests, and for Git mirrors also runs the local mirror/module validator. Re-verification never creates another remote backup object.

Completed backups and imported mirrors can export JSON audit reports.

## Security

Do not commit personal access tokens, Google credentials/tokens, keystores, or generated `local.properties`.

Git credentials are passed to JGit's transport layer rather than embedded in repository URLs. GitHub PAT storage uses the app's Android Keystore-backed encrypted store. Temporary backup, verification, and restore files live in app-private/cache storage and are cleaned up after use.

## Personal-use non-goals

The app intentionally does not provide destructive overwrite of arbitrary non-empty repositories, force-push recovery workflows, native recreation of GitHub identities/timestamps for discussions, organization/team reconstruction, automatic wiki publication, or Play Store/public-distribution work.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the detailed product boundary and implementation structure.
