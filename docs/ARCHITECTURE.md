# Architecture

GithubBckp is a personal Android GitHub backup/recovery app. Repository data moves directly between GitHub, temporary app-private storage, and the user-selected destination; there is no application server handling repository contents.

The active backup product model is deliberately small: **one current, verified Git mirror per selected repository**. Manual and scheduled runs both update that logical mirror.

## Active flow

```text
Compose UI / HomeViewModel
   |
   +-- GitHub PAT setup + repository selection
   +-- Google Drive or document-tree destination
   +-- manual mirror updates
   +-- automatic mirror schedule
   +-- stored-mirror re-verification
   +-- mirror import / safe GitHub restore
   |
BackupScheduler / WorkManager
   |
RepositoryBackupWorker
   |
BackupCoordinator
   |
GitMirrorBackupEngine
   +-- JGit mirror clone
   +-- Git LFS object preservation
   +-- optional wiki history
   +-- releases/assets
   +-- issue/PR discussion metadata
   |
StorageRouter
   +-- GoogleDriveStorageProvider
   +-- DocumentTreeStorageProvider
```

## GitHub authentication

GitHub authentication is user-managed personal access token (PAT) authentication. `GithubAuthManager` validates the token against GitHub's authenticated-user endpoint, then stores it through the Android Keystore-backed encrypted `SecureStore`.

The app does not require a GitHub OAuth App, Device Flow, client ID, client secret, or build-time GitHub credential. Old OAuth state keys are cleared when a PAT is saved or disconnected.

`GithubGateway` is intentionally narrow: repository discovery and feature lookup used by mirror creation. Source-archive transfer is no longer part of the active product.

## Mirror creation

`GitMirrorBackupEngine` uses JGit mirror-clone semantics to create a bare Git repository. Credentials are passed through JGit's transport layer rather than written into the repository URL.

The engine can bundle:

- all fetched Git refs/history;
- referenced Git LFS objects, verified by size and SHA-256;
- initialized wiki history when available;
- releases and release assets;
- issue/pull-request discussion metadata supported by the app.

The output filename is stable per repository: `<owner>-<repository>.mirror.zip`. New code does not generate timestamped snapshot or mirror filenames.

`BackupType.SOURCE_ARCHIVE` remains only as a compatibility value for historical Room rows. There is no source-archive backup engine and new work always requests `GIT_MIRROR`.

## One-current-mirror storage

`BackupCoordinator` records an attempt in Room, creates the new local mirror artifact, and asks the active `StorageProvider` to store it. When a previously verified remote object is known, that object is supplied as the replacement target.

A backup is marked complete only after provider verification succeeds. Once the new/current mirror is verified, the coordinator marks the superseded history row as no longer owning a current remote artifact and removes older distinct duplicates left by previous app versions on a best-effort basis.

There is no user-facing retention/version-history policy anymore. Historical database rows remain useful for audit/activity, but remote storage is intended to contain one current mirror per repository.

### Google Drive

`GoogleDriveStorageProvider` uses resumable upload. New repositories create a Drive file; subsequent runs use a resumable `PATCH` session against the existing Drive file ID when available. Provider metadata stores the mirror checksum and repository identity for verification.

Drive authorization is handled separately by `GoogleDriveAuthManager` with Google Play services `AuthorizationClient` and the `drive.file` scope. Authorization failures expose the installed package name and certificate SHA-1 so an Android OAuth-client signing mismatch can be diagnosed instead of silently appearing to do nothing.

### Document-tree storage

`DocumentTreeStorageProvider` uses Android's Storage Access Framework. It locates the previously stored document by persisted URI or stable filename and truncates/replaces that document. It can best-effort rename a legacy timestamped file to the stable mirror name.

## Background execution

`BackupScheduler` supports manual fan-out plus daily/weekly periodic scheduling. Repository jobs do not receive a backup-format choice; `RepositoryBackupWorker` always constructs a `GIT_MIRROR` request.

Scheduled work requires unmetered networking, battery-not-low, and storage-not-low constraints. WorkManager timing is opportunistic rather than an exact alarm.

The old scheduled backup-format preference is removed when schedule settings are saved.

See [`SCHEDULED_BACKUPS.md`](SCHEDULED_BACKUPS.md).

## Backup state

The active mirror state machine is:

```text
QUEUED
  -> DOWNLOADING (Git + LFS + optional modules)
  -> PACKAGING
  -> CHECKSUM
  -> UPLOADING
  -> VERIFYING
  -> COMPLETED
```

Any failed stage persists `FAILED`. Temporary local artifacts are removed in `finally` after the attempt finishes.

## Re-verification

`BackupReverificationService` reopens a completed current remote object through its recorded storage provider, downloads the full object to app-private temporary storage, recomputes size/SHA-256/MD5, and for mirrors reuses the safe mirror validation path.

Re-verification does not create another remote object and does not publish anything to GitHub.

## Local restore and GitHub recovery

`MirrorRestoreCoordinator` and `GitMirrorRestoreService` import a `.mirror.zip` into private app storage with path-traversal protection and validate Git refs plus bundled modules before the restore is retained.

`GithubMirrorRestorePublisher` can publish a validated restore to a newly created GitHub repository or to an existing repository that is proven empty. Recovery does not provide arbitrary destructive overwrite or force-push of a live non-empty repository.

`RecoveryTransactionStore` binds a recovery transaction to a stable target identity and persists phases so retries cannot silently switch targets.

## UI structure

The main screen is a normal scrolling Compose layout. Account/tools actions live in that content; there are no competing bottom-end floating buttons. GitHub token management is opened from the GitHub card, and mirror backup/scheduling controls are presented inline.

The source-snapshot format selector, snapshot schedule selector, and retention controls have been removed.

## Build size and signing

Release and `personal` variants use R8 minification and resource shrinking. The large Material extended-icons dependency and Compose debug tooling dependency were removed from the installed dependency graph.

The normal personal APK is built locally with `assemblePersonal`: it inherits release shrinking but uses the developer's persistent local debug signing key. GitHub-hosted CI builds/tests that variant but deliberately does not distribute its runner-signed APK, because an ephemeral signing certificate would change the SHA-1 used by Google Drive Android OAuth.

See [`PERSONAL_RELEASE.md`](PERSONAL_RELEASE.md).
