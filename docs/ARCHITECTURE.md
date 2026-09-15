# Architecture

GithubBckp is an Android backup orchestrator. Repository data moves directly between GitHub, temporary app storage, and the user-selected destination. The app does not require an application server to handle repository contents.

## Boundaries

```text
Compose UI
   |
HomeViewModel
   |
WorkManager scheduler
   |
BackupCoordinator
   +-- BackupEngineFactory
   |     +-- SourceArchiveBackupEngine
   |     +-- GitMirrorBackupEngine
   +-- StorageRouter
   |     +-- DocumentTreeStorageProvider
   |     +-- GoogleDriveStorageProvider
   +-- Room history
```

### GitHub authentication and API

`GithubAuthManager` uses GitHub OAuth Device Flow. This is a deliberate native-client tradeoff: the Android package carries only an OAuth client ID and does not embed a confidential client secret. Access and refresh material returned by GitHub is encrypted with an Android Keystore-backed AES-GCM key before being placed in SharedPreferences.

`GithubGateway` owns repository discovery and authenticated source-archive transfer. Redirects from GitHub's API to archive storage are followed without forwarding the GitHub bearer token to the redirected host.

A future GitHub App/PKCE architecture may still be preferable if the product gains a backend that can safely hold confidential credentials.

### Backup engines

`BackupEngineFactory` selects an engine from `BackupType`, keeping orchestration independent of the artifact format.

`SourceArchiveBackupEngine` downloads the repository default branch as a GitHub TAR.GZ archive. This is a source snapshot only; it does not preserve arbitrary refs or full history.

`GitMirrorBackupEngine` uses JGit mirror-clone semantics, equivalent in intent to `git clone --mirror`: all refs are fetched into a bare repository. GitHub credentials are supplied to JGit's transport layer and are not written into the clone URL. The bare repository is packaged as one `.mirror.zip` artifact for hashing, storage, and restore portability.

Both engines compute SHA-256 as the canonical integrity checksum plus MD5 for providers, such as Drive, that expose an independent MD5 checksum.

Git LFS objects are not part of normal Git object storage and are **not included yet**. Wikis, release assets, issues, and pull-request metadata remain separate completeness modules.

### Mirror restore validation

`GitMirrorRestoreService` is the low-level restore primitive. It:

- rejects ZIP entries whose canonical path escapes the restore root;
- extracts the artifact into a bare-repository directory;
- opens that repository with JGit; and
- verifies every ref tip advertised under `refs/` exists in the object database.

A future restore UI can build on this primitive to push the restored bare repository to a destination with mirror semantics.

### Storage routing

`StorageProvider` stays destination-agnostic. `StorageRouter` delegates new uploads to the persisted `StorageDestination`, while verification/deletion route according to the provider recorded on `RemoteBackup`. This avoids a destination switch during a running operation causing verification to hit the wrong backend.

#### Storage Access Framework

`DocumentTreeStorageProvider` is the preferred public-app destination. The user grants access through Android's `ACTION_OPEN_DOCUMENT_TREE` flow. `StoragePreferences` persists the URI grant with `takePersistableUriPermission`, so no broad filesystem permission is required.

The provider creates:

```text
GitHub Backups/<owner>/<repository>/<artifact>
```

It streams the artifact through `ContentResolver`, then reopens the resulting document and recomputes size, SHA-256, and MD5. `COMPLETED` is recorded only after that readback matches the local artifact.

Because SAF works through Android `DocumentsProvider`s, the selected tree can live on local storage, removable storage, or a compatible third-party cloud provider.

#### Google Drive

`GoogleDriveStorageProvider` uses the narrow `drive.file` scope, a resumable upload session, and a post-upload metadata read that verifies remote size, Drive's MD5, and the SHA-256 stored as a Drive app property.

Google's current Workspace API user-data policy restricts generic backup of app/user content to Drive for public applications. Treat this adapter as personal/internal unless written permission or policy guidance changes.

### Background execution

One repository is one WorkManager job. A manual backup requires a connected network plus adequate battery/storage. Future scheduled backups should use `BackupScheduler.scheduledConstraints()`, which defaults to unmetered connectivity plus the same battery/storage constraints.

Very large transfers may eventually need Android's user-initiated/foreground transfer path rather than relying on a single long WorkManager execution window.

## Backup state machine

Source snapshot:

```text
QUEUED -> DOWNLOADING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                 \-------------------------------------------> FAILED
```

Git mirror:

```text
QUEUED -> DOWNLOADING -> PACKAGING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                 \------------------------------------------------------> FAILED
```

Every state transition is persisted in Room. Temporary archives and mirror working directories live below the app cache directory and are removed after success or failure.

## Test coverage

The mirror restore tests create a real local repository, make a JGit mirror, package it, restore it, and assert branch/tag refs plus referenced objects survive. A separate regression test creates a malicious `../` ZIP entry and verifies it cannot write outside the restore directory.
