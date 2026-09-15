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
   |            +-- GitLfsPointerScanner / GitLfsDownloadService
   |            +-- GithubWikiBackupService
   |            +-- GithubReleaseBackupService
   +-- StorageRouter
   |     +-- DocumentTreeStorageProvider
   |     +-- GoogleDriveStorageProvider
   +-- BackupRetentionManager
   +-- Room history + completeness warnings

MirrorRestoreCoordinator
   |
GitMirrorRestoreService
   +-- GithubWikiBackupService (validation)
   +-- GithubReleaseBackupService (manifest/assets validation)
   |
GithubMirrorRestorePublisher
   +-- GitLfsObjectStore / GitLfsUploadService
   +-- GithubRepositoryRestoreGateway
   +-- GitMirrorPushService
```

## GitHub authentication and API

`GithubAuthManager` uses GitHub OAuth Device Flow. The Android package carries only an OAuth client ID. Access/refresh material is encrypted with an Android Keystore-backed AES-GCM key before being placed in SharedPreferences.

`GithubGateway` owns repository discovery, authenticated source-archive transfer, and lightweight repository feature lookup. Redirects from GitHub's API to archive storage are followed without forwarding the bearer token off GitHub's API host.

`GithubRepositoryRestoreGateway` creates a new empty recovery repository or resolves a user-supplied `owner/repository` target.

## Git mirror backup completeness

`GitMirrorBackupEngine` uses JGit mirror-clone semantics to fetch all main-repository refs into a bare repository. Credentials are supplied through JGit transport and never written into repository URLs.

### Git LFS

`GitLfsPointerScanner` walks reachable Git objects and finds unique standard LFS pointer OIDs/sizes. `GitLfsDownloadService` uses the Git LFS Batch API in groups of 100, downloads action URLs using only the returned headers, verifies size plus SHA-256, and stores verified objects in `lfs/objects/<2>/<2>/<oid>`. Cross-host redirects never inherit GitHub bearer credentials.

### Wiki history

If repository metadata reports the wiki feature enabled, `GithubWikiBackupService` attempts a mirror clone of `<owner>/<repo>.wiki.git`. An enabled but never-initialized wiki is treated as no wiki content. An initialized wiki is verified independently and stored at `github-backup/wiki.git/`.

### Releases and release assets

`GithubReleaseBackupService` pages through releases and then pages through assets for each release. It writes a versioned manifest at `github-backup/releases/manifest.json` containing release metadata and asset metadata. Releases with zero assets are still represented.

Asset bytes are downloaded from GitHub's release-asset endpoint using `Accept: application/octet-stream`. The app manually follows redirects, requires HTTPS, and only sends the GitHub bearer token while the request host is `api.github.com`. Every asset must match GitHub's reported size and a locally computed SHA-256. When the API supplies a `sha256:` asset digest, it is independently cross-checked.

The main bare repository, LFS store, optional wiki mirror, and optional release directory are packaged as one `.mirror.zip`. The final artifact receives SHA-256 plus MD5 for destination-level verification.

`BackupArtifact` can carry non-fatal completeness warnings. Git/LFS recovery is complete for the supported empty-target flow, while mirrors containing wiki or release data currently warn that those modules are preserved/verified but not automatically republished to GitHub.

## Local mirror restore

`GitMirrorRestoreService` rejects ZIP entries escaping the restore root and then validates every bundled module before the restored copy is retained:

- main bare Git repository: advertised ref tips must exist in the object database;
- optional wiki mirror: opened independently as a bare repository and its ref tips verified;
- optional releases: supported manifest version, safe asset paths, file existence, size, and SHA-256 all verified.

`MirrorRestoreCoordinator` keeps validated restores in private app storage with lightweight metadata and reloads valid records across app restarts.

## GitHub recovery

`GithubMirrorRestorePublisher` can publish the main repository to either a newly created repository or an existing repository selected by full name. The current recovery path is deliberately restricted to an empty target.

Recovery is LFS-first. `GitLfsObjectStore` verifies every referenced bundled object locally, `GitLfsUploadService` uploads missing objects and performs optional server verify actions, and only then does `GitMirrorPushService` perform its authenticated empty-remote preflight and non-forced ref push.

GitHub-owned `refs/pull/*` refs are skipped and reported rather than treated as writable Git state.

Wiki publication is not automated because GitHub does not expose a documented wiki-page initialization API. Release publication is also deliberately deferred: releases depend on Git refs already existing, and a publication failure after the main push would leave a non-empty partial target that the current safe retry guard intentionally refuses. A target-bound resumable recovery transaction is required before release recreation can be enabled safely.

Arbitrary destructive recovery into a non-empty repository remains unavailable.

## Storage routing and retention

`StorageRouter` sends new artifacts to the configured destination and uses provider metadata recorded with each completed backup for later verification/deletion. `DocumentTreeStorageProvider` reopens saved files and recomputes size/SHA-256/MD5. `GoogleDriveStorageProvider` uses resumable upload and remote checksum metadata.

Room schema v2 added provider-aware remote identity and deletion timestamps. Schema v3 added nullable completeness warnings. Historical rows are never assigned fabricated metadata.

`BackupRetentionManager` supports keep-all, 3, 5, or 10 artifacts per repository and format. Deletion failures are best-effort and never retroactively turn a newly verified backup into `FAILED`.

## Background execution

Manual backups require connected networking plus battery/storage constraints. Scheduled backups use WorkManager daily/weekly controllers, query currently selected repositories at execution time, and fan out one repository worker per backup. Scheduled jobs require unmetered networking, battery-not-low, and storage-not-low constraints.

## Backup state machine

Source snapshot:

```text
QUEUED -> DOWNLOADING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                 \-------------------------------------------> FAILED
```

Git mirror:

```text
QUEUED -> DOWNLOADING (Git + LFS + wiki + releases) -> PACKAGING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                 \-----------------------------------------------------------------------------------------> FAILED
```

After `COMPLETED`, retention may prune older verified remote artifacts without changing historical completion state.

## Test coverage

- Mirror restore tests create and restore real main and wiki Git repositories and also include bundled release data.
- ZIP path traversal is rejected.
- LFS scanner tests discover real pointer blobs; download/upload tests cover Batch API parsing, errors, already-present objects, and integrity checks.
- `GithubReleaseBackupServiceTest` verifies valid assets, corrupt-asset rejection, and manifest path-traversal rejection.
- `GitMirrorPushServiceTest` validates empty-target push behavior and refusal of non-empty targets.
- Retention tests validate keep-all and keep-last-N selection.
- Room/KSP compilation validates database schema/query consistency in CI.
