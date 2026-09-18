# Architecture

GithubBckp has one active data path:

```text
Compose UI
    ↓
HomeViewModel
    ↓
WorkManager / RepositoryBackupWorker
    ├── MirrorSyncCoordinator
    │      ↓
    │   MirrorEngine → LocalMirrorStore → repository mirror tar.gz
    │
    └── LatestReleaseSyncCoordinator
           ↓
        GitHubLatestReleaseService
           ↓
        LocalLatestReleaseStore → latest-release tar.gz
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
→ export default-branch repository/
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
→ regenerate default-branch repository/
→ rewrite manifest
→ tar.gz
→ verify
```

The engine never modifies the durable archive directly. It only emits a verified pending archive.

### LocalMirrorStore

Uses Android's Storage Access Framework and one human-readable directory per repository while retaining the immutable GitHub repository ID as the stable identity suffix:

```text
GitHub Backups/<owner>--<repo>--<repository-id>/<owner>--<repo>.tar.gz
```

Replacement writes use `<owner>--<repo>.pending.tar.gz`. Persisted bytes are hashed before the previous stable mirror is retired. Startup reconciliation can promote a verified pending archive after interruption. Numeric-only folders from early refactor test builds are migrated only after a readable replacement copy is checksum-verified.

### MirrorEntity

Room stores one row per repository. `repositoryId` is the primary key, so the database cannot represent multiple current mirror records for one repository.

Important timestamps are separate:

- `lastCheckedAtEpochMs`: successful remote comparison/update check;
- `lastChangedAtEpochMs`: last time the archive was actually rebuilt;
- `lastSuccessfulSyncAtEpochMs`: last successful mirror operation.

This keeps unchanged but regularly checked repositories healthy.

### LatestReleaseSyncCoordinator

Runs after a successful mirror operation for each selected repository.

It calls GitHub's latest published Release endpoint independently of Git ref comparison. This is necessary because release notes/assets can change without changing the release tag ref.

For a changed latest release it:

```text
check release ID + updated_at
→ download tag source tarball
→ stream all uploaded release assets
→ hash every downloaded file
→ write release.json
→ tar.gz
→ extract + verify every recorded size/SHA-256
→ persist pending SAF archive
→ verify persisted archive SHA-256
→ replace previous latest-release bundle
```

The release state has its own Room row and failure status; a release-backup failure does not invalidate an already-verified Git mirror.

### LocalLatestReleaseStore

Stores one latest-release bundle under the repository's existing storage directory:

```text
GitHub Backups/<owner>--<repo>--<repository-id>/latest-release/<owner>--<repo>--<tag>--release.tar.gz
```

Its pending/promote/recovery rules mirror the durable mirror store.

### WorkManager

`RepositoryBackupWorker` is unique per repository and runs as foreground work.

The active notification follows actual mirror stages and simultaneous repository jobs share one notification group:

- checking GitHub;
- extracting mirror;
- creating/fetching mirror;
- downloading LFS;
- optimizing;
- compressing;
- verifying.

Backups do not start when notifications cannot be shown. Scheduled fan-out also revalidates the token and repository Git readability before enqueueing repository jobs.

## Database

Current schema:

```text
repositories
mirrors
latest_releases
```

The old historical `backups` table is dropped by migration 10 → 11.

## Non-goals

The architecture intentionally excludes:

- Google Drive or other app-managed cloud destinations;
- restore/publish workflows;
- multiple backup generations;
- multiple retained release generations;
- issue/PR/discussion backup;
- wiki backup;
- Git LFS upload;
- arbitrary destructive Git operations.


### Browsable checkout

Every 0.3.3+ archive contains `repository/`, a materialized snapshot of the repository's configured default branch. This is for human inspection.

The authoritative backup remains `repository.git/`, a bare mirror. The working-tree snapshot is regenerated from that mirror after every changed update, so deleted/changed source files are reflected without changing the full-history backup model.
