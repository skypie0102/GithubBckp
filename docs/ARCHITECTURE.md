# Architecture

GithubBckp is an Android backup orchestrator. Repository data moves directly between GitHub, temporary app storage, and the user-selected destination. The app does not require an application server to handle repository contents.

## Boundaries

```text
Compose UI
   |
HomeViewModel
   |
BackupScheduler / ScheduledBackupWorker
   |
RepositoryBackupWorker
   |
BackupCoordinator
   +-- BackupEngineFactory
   |     +-- SourceArchiveBackupEngine
   |     +-- GitMirrorBackupEngine
   |            +-- GitLfsPointerScanner
   |            +-- GitLfsDownloadService
   +-- StorageRouter
   |     +-- DocumentTreeStorageProvider
   |     +-- GoogleDriveStorageProvider
   +-- BackupRetentionManager
   +-- Room history + completeness warnings

MirrorRestoreCoordinator
   |
GitMirrorRestoreService
   |
GithubMirrorRestorePublisher
   +-- GithubRepositoryRestoreGateway
   +-- GitMirrorPushService
```

### GitHub authentication and API

`GithubAuthManager` uses GitHub OAuth Device Flow. The Android package carries only an OAuth client ID and does not embed a confidential client secret. Access and refresh material returned by GitHub is encrypted with an Android Keystore-backed AES-GCM key before being placed in SharedPreferences.

`GithubGateway` owns repository discovery and authenticated source-archive transfer. Redirects from GitHub's API to archive storage are followed without forwarding the GitHub bearer token to the redirected host.

`GithubRepositoryRestoreGateway` either creates a new empty repository for the authenticated user or resolves a user-supplied `owner/repository` target. The latter supports recovery repositories created ahead of time, including organization-owned repositories the connected account can access.

### Backup engines and completeness

`BackupEngineFactory` selects an engine from `BackupType`, keeping orchestration independent of the artifact format.

`SourceArchiveBackupEngine` downloads the repository default branch as a GitHub TAR.GZ archive. This is a source snapshot only; it does not preserve arbitrary refs or full history.

`GitMirrorBackupEngine` uses JGit mirror-clone semantics: all refs are fetched into a bare repository. GitHub credentials are supplied to JGit's transport layer and are not written into the clone URL.

After the Git clone, `GitLfsPointerScanner` walks reachable mirror objects once and identifies unique standard Git LFS pointer blobs by SHA-256 OID and declared size. `GitLfsDownloadService` requests download actions from the Git LFS Batch API in groups of 100, downloads the raw object bytes using only the headers supplied for each action, verifies declared size plus SHA-256, and stores them under the standard bare-repository `lfs/objects/<2>/<2>/<oid>` layout.

GitHub credentials are sent only to the GitHub Batch API endpoint. Object-action URLs receive only their returned headers; if a download redirects to another host, `Authorization` is removed before following the redirect. Any missing, unavailable, size-mismatched, or hash-mismatched referenced object fails the backup rather than allowing a silent incomplete success.

The bare repository plus bundled LFS store is packaged as one `.mirror.zip` artifact. Both backup engines compute SHA-256 as the canonical integrity checksum plus MD5 for providers, such as Drive, that expose an independent MD5 checksum.

`BackupArtifact` can carry non-fatal warnings. Mirrors with bundled LFS objects currently record a warning that those bytes are present in the backup but are not yet uploaded during GitHub recovery publication. `BackupCoordinator` writes warnings only after the remote artifact has passed verification, so `COMPLETED` still means the stored bytes were verified while the warning describes a recovery limitation.

Room schema v3 persists the warning separately from `errorMessage`. The v2-to-v3 migration adds a nullable `warningMessage`; older rows remain null rather than receiving inferred historical completeness claims. Warning history remains even if retention later prunes the corresponding remote artifact.

Wikis, release assets, issues, and pull-request metadata remain separate completeness modules.

### Mirror restore and GitHub recovery

`GitMirrorRestoreService` rejects ZIP entries whose canonical path escapes the restore root, extracts the bare repository (including any bundled LFS object store), opens it with JGit, and verifies every advertised ref tip exists in the Git object database.

`MirrorRestoreCoordinator` imports a user-selected archive into private app storage, keeps lightweight metadata beside the restored repository, reloads valid restore records across app restarts, and exposes a validated repository directory only for a known restore ID.

`GithubMirrorRestorePublisher` can publish to either a newly created repository or an existing repository selected by full name. Both routes converge on the same `GitMirrorPushService` safety boundary.

Before writing any Git ref, `GitMirrorPushService` performs an authenticated `ls-remote` style advertisement check and refuses the operation if the target advertises any refs. Only an empty remote can proceed. The service then enumerates every writable ref from the validated bare repository and pushes them without force. Every attempted remote update must finish as `OK` or `UP_TO_DATE`.

The preflight empty check and non-forced refspecs intentionally protect live repositories. The empty check is not treated as permission to enable mirror-style deletion or force updates later in the call path. A separately designed non-empty recovery mode would need explicit destructive confirmation, remote-ref deletion semantics, and repository-rule handling.

GitHub owns `refs/pull/*` as a read-only namespace. Those refs may be present in a mirror clone but cannot be pushed back; they are skipped explicitly and reported separately. Restoring pull-request metadata remains a future metadata module rather than pretending those refs are writable Git state.

A restored mirror preserves bundled LFS object files locally, but `GithubMirrorRestorePublisher` currently pushes Git refs only. A follow-up LFS recovery phase must request upload/verify actions from the target repository's LFS Batch API and transfer bundled objects before recovery can be called LFS-complete.

### Storage routing

`StorageProvider` stays destination-agnostic. `StorageRouter` delegates new uploads to the persisted `StorageDestination`, while verification and deletion route according to the provider recorded on `RemoteBackup`.

`DocumentTreeStorageProvider` streams artifacts through Android SAF and then reopens the saved document to recompute size, SHA-256, and MD5. `GoogleDriveStorageProvider` uses resumable upload and verifies remote size, Drive MD5, and stored SHA-256 metadata.

### Provider-aware history and retention

Room schema v2 adds `storageProvider`, remote name/size/MD5, and `remoteDeletedAtEpochMs` to each backup row. The v1-to-v2 migration leaves these fields null for historical rows rather than guessing their origin. Schema v3 adds the nullable completeness-warning field described above.

A newly completed backup stores the full `RemoteBackup` identity before retention runs. `BackupRetentionManager` is opt-in and supports keep-all, 3, 5, or 10 artifacts per repository and backup format. It reconstructs each expired `RemoteBackup` from immutable history and calls `StorageRouter.delete`, so an artifact is deleted through the provider that created it even after the active destination changes.

Retention failures are best-effort and logged. They never retroactively fail the newly completed backup. After successful deletion, the history row remains and receives `remoteDeletedAtEpochMs` for auditability; its completeness warning is retained as historical metadata.

### Background execution

Manual backups require connected networking plus battery/storage constraints. Automatic backup settings persist disabled/daily/weekly cadence plus the selected backup format.

`ScheduledBackupWorker` is only a controller. At execution time it queries the current repository selections and fans out one `RepositoryBackupWorker` per repository. Scheduled work requires unmetered connectivity, battery-not-low, and storage-not-low constraints. This avoids turning a large GitHub account into one monolithic periodic job.

Very large transfers may eventually need Android's user-initiated/foreground transfer path rather than relying on a single long WorkManager execution window.

## Backup state machine

Source snapshot:

```text
QUEUED -> DOWNLOADING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                 \-------------------------------------------> FAILED
```

Git mirror:

```text
QUEUED -> DOWNLOADING (Git + LFS) -> PACKAGING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                 \-------------------------------------------------------------------> FAILED
```

A completed artifact may also have a non-fatal recovery/completeness warning. After `COMPLETED`, an optional retention pass may delete older verified remote artifacts without changing their historical completion state or warning metadata.

Temporary archives and mirror working directories live below the app cache directory and are removed after success or failure.

## Test coverage

Mirror restore tests create a real local repository, make a JGit mirror, package it, restore it, and assert branch/tag refs plus referenced objects survive. A separate regression test creates a malicious `../` ZIP entry and verifies it cannot write outside the restore directory.

`GitMirrorPushServiceTest` verifies writable branches/tags are pushed into an empty bare target while a synthetic `refs/pull/*` ref is skipped. A second regression creates a target that already has a commit and verifies recovery is refused before the mirror can add its branch or tag refs.

`GitLfsPointerScannerTest` creates a real repository containing an LFS pointer, mirrors it, and verifies the scanner discovers the expected unique OID and size. `GitLfsDownloadServiceTest` validates Batch API action parsing, per-object error handling, and size/SHA-256 integrity verification.

Retention tests verify keep-all selects nothing for deletion and keep-last-N selects only older artifacts. Room/KSP compilation validates the nullable v3 warning column and DAO completion query against the entity schema in CI.
