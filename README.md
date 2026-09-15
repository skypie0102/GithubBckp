# GithubBckp

Android app for backing up repositories from a GitHub account to external storage, with source-archive backups and a Google Drive adapter.

## Status

The first end-to-end backup slice is implemented:

```text
GitHub device authorization
  -> discover repositories
  -> select repositories
  -> download GitHub TAR archive
  -> calculate SHA-256 + MD5
  -> Google Drive resumable upload
  -> verify Drive size/checksums
  -> persist COMPLETED/FAILED history in Room
```

Each selected repository is queued as its own constrained WorkManager job. `GIT_MIRROR` remains the next backup format to implement; a GitHub source archive is only a source snapshot and does not preserve full Git history.

## Build

Requirements:

- JDK 17+
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0+

```bash
./gradlew :app:assembleDebug
```

## GitHub OAuth setup

The app uses GitHub's device authorization flow so the Android package never ships an OAuth client secret. Create a GitHub OAuth App, enable **Device Flow**, then provide only its client ID locally:

```bash
./gradlew :app:assembleDebug -PGITHUB_CLIENT_ID=your_client_id
```

Or export `GITHUB_CLIENT_ID` in your local/CI environment. Do not commit it if you prefer to keep application identifiers private; no GitHub client secret is required by this implementation.

The app requests `repo` plus `offline_access`, stores GitHub access/refresh material encrypted by an Android Keystore AES-GCM key, and refreshes expiring device-flow tokens when possible.

## Google Drive setup

Google Drive authorization uses Google Play services AuthorizationClient with the narrow `drive.file` scope. Configure an Android OAuth client in the Google Cloud project for package:

```text
com.skypie0102.githubbckp
```

and register the signing certificate SHA-1 used for your build. The app does not embed a Google client secret and does not persist Google access tokens; Play services refreshes authorization when possible.

### Important Drive policy note

Google's Workspace API user-data policy currently lists using Drive as a generic backup destination for app/user content as a disallowed use case for public applications. The Drive implementation here is therefore best treated as a personal/internal adapter. `StorageProvider` remains isolated so a user-selected Storage Access Framework destination or another storage backend can replace Drive without changing the backup engine.

## Security

Do **not** commit OAuth client secrets, access tokens, refresh tokens, signing keys, or generated `local.properties` files. GitHub runtime tokens are encrypted with Android Keystore-backed AES-GCM storage. Temporary archives are created under the app cache directory and removed after each backup attempt.

## Architecture

Key boundaries:

- `GithubAuthManager` — GitHub device flow, encrypted token persistence and refresh
- `GithubGateway` / `GithubRestGateway` — repository discovery and source archive transfer
- `BackupEngine` / `SourceArchiveBackupEngine` — artifact creation and hashing
- `StorageProvider` / `GoogleDriveStorageProvider` — remote upload and verification
- `BackupCoordinator` — durable Room state transitions around one backup attempt
- `RepositoryBackupWorker` — one repository per WorkManager job
- `HomeViewModel` — connection, repository selection and manual queueing UI state

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the original component plan.

## Next implementation slice

1. Implement true `git --mirror`-equivalent backup/restoration for complete history.
2. Add a Storage Access Framework provider for policy-safe user-selected folders.
3. Add scheduled cadence/settings and retention rules.
4. Add LFS, wiki, releases, issues, and pull-request metadata as optional modules.
