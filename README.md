# GithubBckp

Android app for backing up repositories from a GitHub account to external storage.

## Status

Two backup formats and two destination types are now implemented:

```text
GitHub device authorization
  -> discover repositories
  -> select repositories
  -> choose source snapshot or Git mirror
  -> create + checksum artifact
  -> choose user-selected folder or Google Drive
  -> upload + verify saved bytes
  -> persist COMPLETED/FAILED history in Room
```

### Backup formats

- **Source snapshot** — downloads the repository default branch as GitHub's TAR.GZ archive. It is compact, but is not a full Git-history backup.
- **Git mirror** — uses JGit mirror-clone semantics to fetch all Git refs into a bare repository, packages that repository as a `.mirror.zip`, and computes SHA-256 + MD5. This preserves Git refs/history for later restoration.

Git LFS objects are **not included yet** in mirror mode. Wikis, release assets, issues, and pull-request metadata are also separate future completeness modules.

### Destinations

- **Backup folder (recommended)** — Android Storage Access Framework (SAF). The user chooses a folder with the system picker; the app persists the URI grant and can write to local storage, SD cards, or cloud apps that expose an Android DocumentsProvider. No broad storage permission is required.
- **Google Drive** — direct Drive API adapter with `drive.file`, resumable upload, and remote checksum verification. Because Google's current Workspace API policy restricts generic backup-to-Drive use for public apps, this adapter should be treated as personal/internal unless written permission or policy guidance changes.

Each selected repository is queued as its own constrained WorkManager job.

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

## Google Drive setup

Google Drive authorization uses Google Play services `AuthorizationClient` with the narrow `drive.file` scope. Configure an Android OAuth client in the Google Cloud project for package:

```text
com.skypie0102.githubbckp
```

and register the signing certificate SHA-1 used for your build. The app does not embed a Google client secret and does not persist Google access tokens; Play services refreshes authorization when possible.

## Storage Access Framework setup

No cloud API credentials are needed. Tap **Choose backup folder** in the app and pick a writable location from Android's system document picker. The app keeps only the persisted document-tree URI grant.

Artifacts are written under:

```text
GitHub Backups/
  <owner>/
    <repository>/
      <artifact>
```

After writing, the app opens the saved document again and verifies its byte count, SHA-256, and MD5 against the local artifact before recording `COMPLETED`.

## Mirror restoration

A Git mirror artifact is a ZIP containing a bare Git repository. `GitMirrorRestoreService` provides the restore/validation primitive:

1. Reject unsafe ZIP entries that escape the restore directory.
2. Extract the bare repository.
3. Open it through JGit.
4. Verify every advertised ref tip is present in the object database.

A future user-facing restore flow can use the restored bare repository as the source for a mirror push to a new GitHub repository.

## Security

Do **not** commit OAuth client secrets, access tokens, refresh tokens, signing keys, or generated `local.properties` files. GitHub runtime tokens are encrypted with Android Keystore-backed AES-GCM storage. Temporary source/mirror artifacts live below the app cache directory and are removed after each backup attempt. Git credentials are passed to JGit's transport layer rather than embedded in repository URLs.

## Architecture

Key boundaries:

- `GithubAuthManager` — GitHub device flow, encrypted token persistence and refresh
- `GithubGateway` / `GithubRestGateway` — repository discovery and source archive transfer
- `BackupEngineFactory` — selects source snapshot or Git mirror engine
- `SourceArchiveBackupEngine` — GitHub default-branch TAR.GZ artifact
- `GitMirrorBackupEngine` — JGit mirror clone and portable bare-repository ZIP artifact
- `GitMirrorRestoreService` — safe extraction and ref/object validation
- `StorageRouter` — selects the persisted destination
- `DocumentTreeStorageProvider` — SAF streaming upload/readback verification
- `GoogleDriveStorageProvider` — Drive resumable upload/remote verification
- `BackupCoordinator` — durable Room state transitions around one backup attempt
- `RepositoryBackupWorker` — one repository per WorkManager job
- `HomeViewModel` — connection, destination, format, repository selection, and queueing state

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for component details.

## Next implementation slice

1. Add scheduled cadence/settings and retention pruning.
2. Add a user-facing restore workflow that can create/select a destination repository and mirror-push into it.
3. Add Git LFS object backup/restore and clearly report repository completeness.
4. Add optional wiki, release assets, issues, and pull-request metadata modules.
