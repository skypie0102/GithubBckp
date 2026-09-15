# GithubBckp

Android app for backing up repositories from a GitHub account to external storage.

## Status

The core backup, scheduling, retention, local restore, and safe GitHub recovery flows are implemented:

```text
GitHub device authorization
  -> discover/select repositories
  -> source snapshot or Git mirror
  -> checksum artifact
  -> user-selected folder or Google Drive
  -> upload + verify
  -> persist provider-aware backup history
  -> optional keep-last-N retention

Periodic WorkManager controller
  -> read repositories selected at execution time
  -> fan out one constrained backup job per repository

Mirror ZIP
  -> Android document picker
  -> safe private import
  -> validate bare Git repository refs/objects
  -> create a new GitHub repository OR select an existing empty repository
  -> verify target advertises no Git refs
  -> push all writable refs without force
```

### Backup formats

- **Source snapshot** — downloads the repository default branch as GitHub's TAR.GZ archive. It is compact, but is not a full Git-history backup.
- **Git mirror** — uses JGit mirror-clone semantics to fetch all Git refs into a bare repository, packages that repository as a `.mirror.zip`, and computes SHA-256 + MD5. This preserves Git refs/history for restoration.

Git LFS objects are **not included yet** in mirror mode. Wikis, release assets, issues, and pull-request metadata are separate future completeness modules.

### Destinations

- **Backup folder (recommended)** — Android Storage Access Framework (SAF). The user chooses a folder with the system picker; the app persists the URI grant and can write to local storage, SD cards, or cloud apps that expose an Android DocumentsProvider. No broad storage permission is required.
- **Google Drive** — direct Drive API adapter with `drive.file`, resumable upload, and remote checksum verification. Because Google's Workspace API policy restricts generic backup-to-Drive use for public apps, this adapter should be treated as personal/internal unless written permission or policy guidance changes.

Each repository runs as its own WorkManager job.

## Build

Requirements:

- JDK 17+
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0+

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## GitHub OAuth setup

The app uses GitHub's device authorization flow so the Android package never ships an OAuth client secret. Create a GitHub OAuth App, enable **Device Flow**, then provide only its client ID locally:

```bash
./gradlew :app:assembleDebug -PGITHUB_CLIENT_ID=your_client_id
```

Or export `GITHUB_CLIENT_ID` in your local/CI environment. No GitHub client secret is required by this implementation.

The app requests `repo` plus `offline_access`, stores GitHub access/refresh material encrypted by an Android Keystore AES-GCM key, and refreshes expiring device-flow tokens when possible.

## Storage Access Framework

Tap **Choose backup folder** and select a writable location using Android's system document picker. Artifacts are written below:

```text
GitHub Backups/<owner>/<repository>/<artifact>
```

After writing, the app reopens the saved document and verifies byte count, SHA-256, and MD5 before recording `COMPLETED`.

## Google Drive

Google Drive authorization uses Google Play services `AuthorizationClient` with the narrow `drive.file` scope. Configure an Android OAuth client for package `com.skypie0102.githubbckp` and register the signing certificate SHA-1. The app does not persist Google access tokens.

## Automatic backups

Automatic backup settings support disabled, daily, or weekly execution, source snapshot or Git mirror format, and persisted settings across app restarts.

WorkManager periodic execution is opportunistic rather than an exact alarm. The periodic controller reads the repositories selected when it runs and fans out one backup job per repository. Scheduled work requires unmetered connectivity, battery-not-low, and storage-not-low constraints.

## Retention

Retention is opt-in. The default is **Keep all**. Users can instead keep the newest 3, 5, or 10 verified artifacts per repository and backup format.

Room schema v2 persists the provider plus immutable remote artifact metadata required to reconstruct deletion requests. Deletion is routed through the provider that originally created the artifact, even if the user later switches destinations. Successful deletion records `remoteDeletedAtEpochMs` while keeping local history.

Rows migrated from schema v1 have unknown provider metadata and are deliberately excluded from automatic deletion. Retention is best-effort: a pruning failure is logged and never changes a newly verified backup from `COMPLETED` to `FAILED`.

## Mirror restoration

The app can import a Git mirror ZIP through Android's document picker. The archive is copied into private app storage and restored with these checks:

1. Reject ZIP entries that escape the restore directory.
2. Extract into a bare-repository directory.
3. Open it with JGit.
4. Verify every advertised ref tip exists in the object database.
5. Persist lightweight restore metadata so valid restores survive app restarts.

A validated restore can then be published either into a **new** GitHub repository or an **existing repository that is still empty**. New repositories default to private. For an existing target, the user enters `owner/repository`, which also supports organization-owned recovery repositories the connected account can access.

Before any Git write, `GitMirrorPushService` runs an authenticated remote-ref advertisement check. If the target advertises any Git refs, restoration is refused. Writable refs are then pushed without force and every remote update result must be `OK` or `UP_TO_DATE`. GitHub's read-only `refs/pull/*` namespace is intentionally skipped and reported to the user; pull-request objects/metadata are not part of Git restoration.

Destructive overwrite of a non-empty repository remains intentionally unavailable. Exact mirror overwrite can delete branches/tags and may conflict with repository rules or GitHub push policies, so that requires a separate confirmation/rules-aware design.

## Security

Do **not** commit OAuth client secrets, access tokens, refresh tokens, signing keys, or generated `local.properties` files. GitHub runtime tokens are encrypted with Android Keystore-backed AES-GCM storage. Temporary source/mirror artifacts live below the app cache directory and are removed after each backup attempt. Git credentials are passed to JGit's transport layer rather than embedded in repository URLs.

## Architecture

Key boundaries:

- `GithubAuthManager` — GitHub device flow, encrypted token persistence and refresh
- `GithubGateway` / `GithubRestGateway` — repository discovery and source archive transfer
- `GithubRepositoryRestoreGateway` — create or resolve a GitHub recovery target
- `BackupEngineFactory` — selects source snapshot or Git mirror engine
- `GitMirrorRestoreService` / `MirrorRestoreCoordinator` — safe mirror import and persistent local restores
- `GitMirrorPushService` / `GithubMirrorRestorePublisher` — empty-target validation and non-forced Git recovery publication
- `StorageRouter` — provider-aware upload, verification, and deletion routing
- `DocumentTreeStorageProvider` — SAF streaming upload/readback verification
- `GoogleDriveStorageProvider` — Drive resumable upload/remote verification
- `BackupScheduler` / `ScheduledBackupWorker` — manual and periodic fan-out scheduling
- `BackupRetentionManager` — opt-in keep-last-N pruning
- `BackupCoordinator` — durable Room state transitions around one backup attempt

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for component details.

## Next implementation slice

1. Design a separately confirmed non-empty repository recovery flow with explicit remote-ref deletion semantics and repository-rule checks.
2. Add Git LFS object backup/restore and clearly report repository completeness.
3. Add optional wiki, release assets, issues, and pull-request metadata modules.
4. Add richer backup/restore audit and export reporting.
