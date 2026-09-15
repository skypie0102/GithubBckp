# Architecture

GithubBckp is an Android backup orchestrator. It should not require a server to handle repository data; a small auth broker may be introduced later only where an OAuth provider requires a confidential client secret.

## Boundaries

```text
Compose UI
   |
ViewModels / use cases
   |
Backup coordinator
   +-- GithubGateway
   +-- BackupEngine
   +-- StorageProvider
   +-- Room history
   +-- WorkManager scheduler
```

### GitHub

`GithubGateway` owns repository discovery and GitHub API calls. OAuth tokens must be stored using Android Keystore-backed storage. A production GitHub App/native OAuth flow should use PKCE.

### Backup engines

Start with a source archive engine for the MVP. Add a mirror engine behind the same `BackupEngine` interface for full branch/tag/history backups. Git LFS must be handled separately before a backup is described as complete for LFS repositories.

### Storage

`StorageProvider` makes cloud destinations replaceable. The first implementation is Google Drive with the `drive.file` scope and resumable uploads. Future providers can include OneDrive, Dropbox, and S3-compatible object storage.

### Background execution

A scheduler enqueues one repository per unit of work. Scheduled jobs should default to unmetered connectivity and avoid low battery/storage states. User-initiated "Back up now" transfers may need a foreground/user-initiated transfer path rather than relying on one long WorkManager job.

## Backup state machine

```text
QUEUED -> DOWNLOADING -> PACKAGING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                                     \-------------------------------------> FAILED
```

Persist transitions so process death can be distinguished from a completed upload.
