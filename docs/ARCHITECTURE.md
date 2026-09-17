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

The local output filename is stable per repository: `<owner>-<repository>.mirror.zip`. New code does not generate timestamped snapshot or mirror generations.

`BackupType.SOURCE_ARCHIVE` remains only as a compatibility value for historical Room rows. There is no source-archive backup engine and new work always requests `GIT_MIRROR`.

## Verified-before-retire storage model

`BackupCoordinator` records an attempt in Room, creates the new local mirror artifact, and asks the active `StorageProvider` to store a replacement candidate.

The replacement is independently verified before it becomes authoritative. After remote verification succeeds, the coordinator first persists the new row as `COMPLETED`; only then does it retire superseded remote objects and mark their historical rows as no longer owning a current object. Cleanup is best-effort and cannot downgrade the already verified replacement. Cleanup failures are attached as warnings when possible.

This ordering is deliberate: a crash during cleanup can temporarily leave more than one remote object, but it must not leave the database claiming there is no verified current mirror. There is no user-facing retention/version-history policy; extra objects are cleanup residue, not intentional generations.

Historical database rows remain useful for audit/activity. A row with `remoteDeletedAtEpochMs` no longer represents a current remote object and is excluded from current mirror health and on-demand re-verification.

### Google Drive

`GoogleDriveStorageProvider` uses resumable upload to create each replacement as a distinct Drive object. It does **not** overwrite the previous verified file in place. The previous object remains intact while the replacement uploads; after the new object verifies and is committed in Room, coordinator cleanup removes the older Drive object.

This means a failed or interrupted Drive upload cannot corrupt the only known-good mirror. If the user has changed Google accounts and an older file ID is no longer visible, cleanup can warn while the newly verified object in the current account remains authoritative.

Provider metadata stores the mirror checksum and repository identity for verification. Drive authorization is handled separately by `GoogleDriveAuthManager` with Google Play services `AuthorizationClient` and the `drive.file` scope. Authorization failures expose the installed package name and certificate SHA-1 so an Android OAuth-client signing mismatch can be diagnosed instead of silently appearing to do nothing.

### Document-tree storage

`DocumentTreeStorageProvider` uses Android's Storage Access Framework. It resolves prior mirror documents only inside the **currently selected** repository folder, so changing the backup folder moves future backups to the new tree rather than continuing to write through an old persisted URI.

A replacement is written to a sibling staging document and the complete staged bytes are checked against the artifact size, SHA-256, and MD5 before the provider returns it. The previous verified document remains untouched while this staging write and local verification occur. `BackupCoordinator` then performs its normal provider verification, commits the new row, and only afterward deletes the superseded document.

If the stable mirror filename is free, the staged document is renamed to it. When the previous stable-name object still exists during staging, the provider may temporarily persist the verified replacement under its staging display name; this is a storage implementation detail, not a historical generation. A later successful update can normalize the display name once the old object has been removed.

Document-tree deletion is idempotent so cleanup can safely retry a document that a provider has already removed or that disappeared externally.

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

Any failure before the verified replacement is committed persists `FAILED`. Temporary local artifacts are removed in `finally` after the attempt finishes. Duplicate cleanup happens after `COMPLETED` and is therefore warning-only.

## Backup health

Backup health is mirror-only. Legacy `SOURCE_ARCHIVE` rows remain visible as historical data but do not count as current protection, do not suppress overdue alerts, and do not override the status of a newer/current Git mirror.

For each selected available repository, health uses the latest Git-mirror attempt and latest completed Git-mirror row that still owns a current remote object. Automatic-backup freshness uses two cadence windows.

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

## Build and release

There are only two normal Android build types: `debug` for development and `release` for the installable APK.

The release build uses R8 minification and resource shrinking. The large Material extended-icons dependency and Compose debug tooling dependency were removed from the installed dependency graph.

The GitHub release workflow follows the Intake Edit release shape: it validates the app version, builds a signed release APK, verifies the signature, renames the artifact to `githubbckp-v<version>.apk`, creates a SHA-256 sidecar, and publishes both files to a GitHub Release.

Release signing mirrors Intake Edit: the workflow uses configured persistent signing secrets when available and otherwise generates a one-off fallback key. Google Drive Android OAuth is bound to the signing certificate SHA-1, so one-off releases require the corresponding release SHA-1 to be registered before Drive authorization will work.

See [`RELEASE.md`](RELEASE.md).
