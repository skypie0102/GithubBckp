# GithubBckp

GithubBckp is a personal/internal Android app for keeping recoverable copies of GitHub repositories in storage controlled by the user. Public distribution and Play Store work are intentionally outside scope.

The product goal is narrow: make personal GitHub backups boring, verifiable, and recoverable.

## What it backs up

Two backup formats are supported:

- **Source snapshot** — downloads GitHub's TAR.GZ archive for the default branch. It is compact but does not preserve full Git history.
- **Git mirror** — performs mirror-style Git preservation, bundles referenced Git LFS objects, initialized wiki history when present, releases/assets, and issue/pull-request discussion metadata into one verified `.mirror.zip` artifact.

Every completed artifact is checksummed before storage. Mirror backup fails instead of silently succeeding when a referenced LFS object, release asset, or requested metadata page cannot be fetched or verified.

Supported recovery surfaces are main Git refs/history, Git LFS, release metadata, and release assets. Wiki history and discussion datasets are preserved and locally validated but intentionally remain archival-only.

## Personal GitHub authentication

GithubBckp does **not** require a GitHub OAuth App, Device Flow, OAuth client ID, client secret, or build-time GitHub credential.

On first launch, enter a GitHub personal access token (PAT). The app validates it against GitHub, then stores it encrypted through the existing Android Keystore-backed secure store.

The token must be able to read every private repository you want to back up. Recovery additionally requires the permissions needed to create or update the chosen target repository and its Git/LFS/release surfaces. Classic and fine-grained PATs are both supported; GithubBckp does not rely on OAuth scope strings to decide whether recovery is possible.

The PAT is never embedded in the APK, Gradle configuration, repository, CI logs, or release metadata.

## Backup destinations

### Backup folder

The Android Storage Access Framework can write to a user-selected document tree, including local storage, removable storage, or a compatible cloud DocumentsProvider. Completed documents are reopened and verified by byte count, SHA-256, and MD5 before the backup is marked complete.

### Google Drive

Google Drive authorization is separate from GitHub authentication. The Drive adapter uses Google Play services `AuthorizationClient` with `drive.file`, resumable upload, and remote verification.

For the normal personal debug APK, register the debug signing certificate SHA-1 for package:

```text
com.skypie0102.githubbckp
```

Get that fingerprint with:

```bash
./gradlew :app:signingReport
```

The app does not persist Google access tokens.

## Reliability features

The personal reliability roadmap is complete:

1. **Backup health dashboard (#33)** — selected repositories are classified as protected, warning, failed, stale, or never backed up.
2. **Failure and overdue notifications (#34)** — failures are rate-limited and overdue repositories are grouped into deduplicated alerts.
3. **On-demand re-verification (#35)** — completed, non-pruned artifacts can be reopened through their original storage provider and rehashed from the stored bytes.
4. **Guided disaster-recovery drill (#36)** — validated mirrors can be rehearsed against a private new/provably empty target with independent post-publication Git/LFS/release verification.

There are no active product-feature roadmap items. Future feature work is frozen by default unless personal use exposes a concrete recurring problem. Reliability, compatibility, security, and recovery-safety fixes remain in scope.

## Build

Requirements:

- JDK 17+
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0+

Repository gate:

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

Personal install artifact:

```bash
./gradlew :app:assembleDebug
```

```text
app/build/outputs/apk/debug/app-debug.apk
```

Gradle/Android tooling automatically signs the debug APK with the local Android debug keystore. GithubBckp has no custom release-keystore workflow. The release variant remains in CI only as a compile check.

See [`docs/PERSONAL_RELEASE.md`](docs/PERSONAL_RELEASE.md) for the exact personal build, Google Drive certificate setup, installation, and device-validation process.

## Automatic backups and retention

Automatic backup settings support disabled, daily, or weekly execution and either source-snapshot or Git-mirror format. WorkManager runs opportunistically under unmetered-network, battery-not-low, and storage-not-low constraints.

Scheduled health is considered overdue after two cadence windows: 48 hours for daily and 14 days for weekly. Retention defaults to **Keep all**, with optional newest 3/5/10 pruning per repository and backup format.

See [`docs/SCHEDULED_BACKUPS.md`](docs/SCHEDULED_BACKUPS.md).

## Recovery safety

A validated mirror can be published to either a **new** GitHub repository or an **existing repository that is still empty**. Recovery never force-pushes or performs arbitrary destructive overwrite.

Recovery transactions bind to an exact target repository identity and persist phases so retries cannot silently switch targets. Recovery drills use separate target-specific transaction keys, so a rehearsal does not consume or change the ordinary recovery binding.

The guided drill independently verifies published Git refs, checks every referenced LFS object is advertised for download and re-downloads/re-hashes a deterministic sample, and re-reads releases/assets after publication.

See:

- [`docs/RECOVERY_DRILL.md`](docs/RECOVERY_DRILL.md)
- [`docs/LFS_BACKUP.md`](docs/LFS_BACKUP.md)
- [`docs/RELEASE_BACKUP.md`](docs/RELEASE_BACKUP.md)
- [`docs/WIKI_BACKUP.md`](docs/WIKI_BACKUP.md)
- [`docs/DISCUSSIONS_BACKUP.md`](docs/DISCUSSIONS_BACKUP.md)

## Re-verification and audit

Eligible completed backups expose **Re-verify stored backup**. The app reads the entire remote object again, recomputes size/SHA-256/MD5 locally, and for Git mirrors also runs the safe local mirror/module validator. The remote artifact is never mutated.

Completed backups and imported mirrors expose JSON audit reports. The latest on-demand re-verification result is stored separately from the original backup completion state.

See [`docs/BACKUP_AUDIT.md`](docs/BACKUP_AUDIT.md) and [`docs/RESTORE_AUDIT.md`](docs/RESTORE_AUDIT.md).

## Security

Do not commit personal access tokens, Google credentials/tokens, keystores, or generated `local.properties`.

Git credentials are passed to JGit's transport layer rather than embedded in repository URLs. GitHub PAT storage uses the app's Android Keystore-backed encrypted store. Temporary backup, re-verification, restore, and drill-verification files live in app-private/cache storage and are cleaned up after use.

## Personal-use non-goals

The app intentionally does not provide:

- destructive recovery into arbitrary non-empty repositories;
- force-push/ref-deletion recovery workflows;
- native recreation of issues, pull requests, comments, reviews, or original identities/timestamps;
- organization/team membership or permission reconstruction;
- automatic wiki publication through undocumented initialization behavior;
- exact recreation of GitHub release `latest` selection, historical publication timestamps, or immutable-release state;
- Play Store/public-distribution work.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the detailed product boundary and implementation structure.
