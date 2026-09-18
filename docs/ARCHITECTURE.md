# Architecture

GithubBckp has one active data path:

```text
Compose UI
    ↓
HomeViewModel
    ↓
WorkManager / RepositoryBackupWorker
    ↓
MirrorSyncCoordinator
    ↓
MirrorEngine
   ↙       ↘
GitHub     LocalMirrorStore
              ↓
          mirror.tar.gz
```

There is no application server and no cloud-storage adapter.

## Main components

### GithubAuthManager

Stores the user-supplied GitHub PAT through the Android Keystore-backed secure store.

### GithubGateway

Lists repositories visible to the token. Git contents themselves are transferred through JGit and Git LFS endpoints, not through GitHub REST file APIs.

### MirrorEngine

Owns mirror creation and update logic.

First backup:

```text
clone mirror
→ scan LFS pointers
→ download LFS objects
→ write manifest
→ tar.gz
→ verify
```

Update:

```text
read manifest
→ ls-remote
→ unchanged? stop
→ extract
→ fetch + prune
→ LFS delta
→ GC
→ rewrite manifest
→ tar.gz
→ verify
```

The engine never modifies the durable archive directly. It only emits a verified pending archive.

### LocalMirrorStore

Uses Android's Storage Access Framework and one directory per GitHub repository ID:

```text
GitHub Backups/<repository-id>/mirror.tar.gz
```

Replacement writes use `mirror.pending.tar.gz`. Persisted bytes are hashed before the previous stable mirror is retired. Startup reconciliation can promote a verified pending archive after interruption.

### MirrorEntity

Room stores one row per repository. `repositoryId` is the primary key, so the database cannot represent multiple current mirror records for one repository.

Important timestamps are separate:

- `lastCheckedAtEpochMs`: successful remote comparison/update check;
- `lastChangedAtEpochMs`: last time the archive was actually rebuilt;
- `lastSuccessfulSyncAtEpochMs`: last successful mirror operation.

This keeps unchanged but regularly checked repositories healthy.

### WorkManager

`RepositoryBackupWorker` is unique per repository and runs as foreground work.

The active notification follows actual mirror stages:

- checking GitHub;
- extracting mirror;
- creating/fetching mirror;
- downloading LFS;
- optimizing;
- compressing;
- verifying.

Backups do not start when notifications cannot be shown.

## Database

Current schema:

```text
repositories
mirrors
```

The old historical `backups` table is dropped by migration 10 → 11.

## Non-goals

The architecture intentionally excludes:

- Google Drive or other app-managed cloud destinations;
- restore/publish workflows;
- multiple backup generations;
- release/asset backup;
- issue/PR/discussion backup;
- wiki backup;
- Git LFS upload;
- arbitrary destructive Git operations.
