# Architecture

GithubBckp is an Android backup orchestrator. Repository archives are transferred directly from GitHub to a storage provider; the app does not require an application server to handle repository data.

## Boundaries

```text
Compose UI
   |
HomeViewModel
   |
WorkManager scheduler
   |
BackupCoordinator
   +-- GithubGateway
   +-- BackupEngine
   +-- StorageProvider
   +-- Room history
```

### GitHub authentication and API

`GithubAuthManager` uses GitHub OAuth Device Flow. This is a deliberate native-client tradeoff: the Android package carries only an OAuth client ID and does not embed a confidential client secret. Access and refresh material returned by GitHub is encrypted with an Android Keystore-backed AES-GCM key before being placed in SharedPreferences.

`GithubGateway` owns repository discovery and authenticated source-archive transfer. Redirects from GitHub's API to archive storage are followed without forwarding the GitHub bearer token to the redirected host.

A future GitHub App/PKCE architecture may still be preferable if the product gains a backend that can safely hold confidential credentials.

### Backup engines

`SourceArchiveBackupEngine` downloads the repository default branch as a GitHub TAR archive and computes SHA-256 plus MD5 locally. SHA-256 is the canonical app checksum; MD5 is retained because Google Drive exposes an `md5Checksum` that can be used for independent post-upload verification.

A source archive is not a complete Git backup. `GIT_MIRROR` remains behind the same `BackupEngine` boundary and should preserve refs, branches, tags, and full history. Git LFS, wikis, releases, issues, and pull-request metadata remain separate completeness modules.

### Storage

`StorageProvider` keeps destinations replaceable. `GoogleDriveStorageProvider` currently uses the narrow `drive.file` scope, a resumable upload session, and a post-upload read that verifies remote size, Drive's MD5, and the SHA-256 stored as a Drive app property.

Google's current Workspace API user-data policy lists generic backup of app/user content to Drive as a disallowed use case for public applications. The Drive provider should therefore be treated as a personal/internal adapter unless policy guidance changes. A Storage Access Framework provider for a user-selected document-tree destination is the preferred policy-safe next provider.

### Background execution

One repository is one WorkManager job. A manual backup requires a connected network plus adequate battery/storage. Future scheduled backups should use `BackupScheduler.scheduledConstraints()`, which defaults to unmetered connectivity plus the same battery/storage constraints.

Very large transfers may eventually need Android's user-initiated/foreground transfer path rather than relying on a single long WorkManager execution window.

## Backup state machine

```text
QUEUED -> DOWNLOADING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                 \-------------------------------------------> FAILED
```

Every state transition is persisted in Room. Temporary archives live below the app cache directory and are deleted after success or failure.
