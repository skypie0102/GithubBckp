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
   |            +-- GithubDiscussionBackupService
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
   +-- GithubDiscussionBackupService (JSONL/hash validation)
   |
GithubMirrorRestorePublisher
   +-- GithubAuthManager (recovery scope enforcement)
   +-- RecoveryTransactionStore
   +-- GitLfsObjectStore / GitLfsUploadService
   +-- GithubReleaseRestoreService
   +-- GithubRepositoryRestoreGateway
   +-- GitMirrorPushService
```

## GitHub authentication and API

`GithubAuthManager` uses GitHub OAuth Device Flow. The Android package carries only an OAuth client ID. Access/refresh material and cached granted-scope metadata are encrypted with an Android Keystore-backed AES-GCM key before being placed in SharedPreferences.

New Device Flow authorizations request `repo workflow offline_access`. Backup/discovery continues to use the ordinary access token path. Recovery uses `requireRecoveryAccessToken()`, which requires the granted `workflow` scope before any recovery repository can be created or resolved. This avoids leaving a partially created target when GitHub later rejects a workflow-bearing ref update.

For new authorizations, the token response's scope set is cached when GitHub returns it. Older installations can have a valid token but no cached scope metadata because previous app versions did not persist scopes. In that legacy case, recovery queries GitHub's authenticated-user endpoint and reads `X-OAuth-Scopes`, then caches the normalized set. Missing `workflow` produces a dedicated recoverable permission error and the UI offers Device Flow again as **Update GitHub permissions**. Backup and repository discovery do not require the user to reauthorize merely because recovery permission is absent.

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

### Issues, pull requests, comments, and reviews

`GithubDiscussionBackupService` preserves GitHub discussion data outside the Git object database. It reads repository issues with `state=all`, repository pull requests with `state=all`, repository-wide issue comments, repository-wide pull-request review comments, and the review list for each discovered pull request. API requests use 100-item pagination.

The service intentionally stores the raw REST response objects rather than mapping them into a narrow app model. That preserves GitHub-provided metadata fields without requiring an app schema migration whenever GitHub adds response fields. For global comment/review datasets the app adds only a stable parent-number field when the parent is otherwise represented by endpoint context or an API URL.

To bound memory use, discussion records stream directly into JSON Lines files under `github-backup/discussions/`. Only discovered pull-request numbers are retained in memory for the per-PR review pass. A versioned `manifest.json` records per-dataset counts and SHA-256 hashes for `issues.jsonl`, `issue-comments.jsonl`, `pull-requests.jsonl`, `review-comments.jsonl`, and `reviews.jsonl`.

The main bare repository, LFS store, optional wiki mirror, optional release directory, and optional discussion directory are packaged as one `.mirror.zip`. The final artifact receives SHA-256 plus MD5 for destination-level verification.

`BackupArtifact` can carry non-fatal completeness warnings. Git/LFS and release data are recoverable through the supported empty-target transaction flow. Wiki and discussion data are preservation/local-validation only. Release backups retain a warning because source latest-release selection, source publication timestamps, and immutable-release state are not recreated. Discussion backups retain a warning because automatic recreation, timeline events, and referenced attachment bytes are not implemented.

## Local mirror restore

`GitMirrorRestoreService` rejects ZIP entries escaping the restore root and then validates every bundled module before the restored copy is retained:

- main bare Git repository: advertised ref tips must exist in the object database;
- optional wiki mirror: opened independently as a bare repository and its ref tips verified;
- optional releases: supported manifest version, safe asset paths, file existence, size, and SHA-256 all verified;
- optional discussions: fixed dataset names, safe paths, per-file SHA-256, JSONL parsing, positive GitHub identities/numbers, duplicate identities, and manifest counts all verified.

`MirrorRestoreCoordinator` keeps validated restores in private app storage with lightweight metadata and reloads valid records across app restarts.

## Resumable GitHub recovery

`GithubMirrorRestorePublisher` obtains a recovery-validated OAuth token **before** target creation/resolution, then publishes the main repository to either a newly created repository or an existing repository selected by full name. First-time recovery remains restricted to an empty Git surface and an empty release surface.

`RecoveryTransactionStore` persists one app-private transaction per restore. A transaction binds that restore to the stable GitHub repository ID/full name and advances monotonically through:

```text
TARGET_BOUND -> LFS_PUBLISHED -> GIT_PUBLISHED -> RELEASES_PUBLISHED
```

The binding is immutable. A retry for a newly created target resolves and reuses the previously created repository instead of creating another one. A retry for an existing target must supply the same full name and the resolved GitHub repository ID must still match the transaction.

While the transaction is `TARGET_BOUND`, `GitMirrorPushService.requireRemoteEmpty` and `GithubReleaseRestoreService.requireNoExistingReleases` run before any LFS upload. Only a target with no advertised Git refs and no GitHub releases can proceed. Immediately before Git refs are published, `GitMirrorPushService.push` checks Git emptiness again to protect against a concurrent writer between the LFS and Git phases.

A crash after GitHub accepts the Git push but before `GIT_PUBLISHED` is persisted is handled by the resume-only `pushOrReconcilePublished` path. It compares the target's advertised writable ref names and object IDs with the local mirror. Only an exact match is accepted as an already-completed Git phase. Extra refs, missing refs, mismatched object IDs, and GitHub-owned `refs/pull/*` refs on the target cause a hard failure.

### Release publication and retry reconciliation

After `GIT_PUBLISHED`, `GithubReleaseRestoreService` validates the bundled release directory again and publishes each manifest entry. Missing releases are created; an existing release with the same tag is reusable only when name, body, draft, and prerelease metadata match the backup. Unexpected target release tags cause a hard failure.

Asset recovery is also idempotent. An existing uploaded asset with the expected name is reused only after size and SHA-256 verification. If GitHub normalized/renamed the filename, one unique uploaded asset with the same size and SHA-256 digest can be reconciled to the bundled asset. Ambiguous digest matches are refused. A `starter` asset left by an interrupted/failed GitHub upload is deleted before the app retries that asset. Conflicting uploaded assets are never silently replaced.

When GitHub exposes a SHA-256 release-asset digest, the app verifies it directly. If a reusable asset lacks that digest, its bytes are downloaded through the release-asset endpoint and SHA-256 is recomputed before reuse. Redirected download hosts do not receive the GitHub bearer token.

Release creation uses the backed-up tag/name/body/draft/prerelease fields and deliberately does not take over GitHub's latest-release selection. Original source creation/publication timestamps and immutable-release state are not reproducible through this flow. After all backed-up releases/assets reconcile successfully, the transaction records `RELEASES_PUBLISHED` plus release/asset counts.

GitHub-owned `refs/pull/*` refs from the source mirror are skipped and reported rather than treated as writable Git state.

The transaction mechanism is deliberately narrow: it makes retries idempotent for the target this restore already owns; it does not authorize arbitrary overwrite or force-push into a live repository.

Wiki publication is not automated because GitHub does not expose a documented wiki-page initialization API. Discussion publication is not automated because native recreation cannot faithfully assume original authors/timestamps and can trigger notifications/automation. Arbitrary destructive recovery into a non-empty repository remains unavailable.

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
QUEUED -> DOWNLOADING (Git + LFS + wiki + releases + discussions) -> PACKAGING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                 \------------------------------------------------------------------------------------------------------> FAILED
```

After `COMPLETED`, retention may prune older verified remote artifacts without changing historical completion state.

## Test coverage

- Mirror restore tests create and restore real main and wiki Git repositories and also include bundled release data.
- ZIP path traversal is rejected.
- LFS scanner tests discover real pointer blobs; download/upload tests cover Batch API parsing, errors, already-present objects, and integrity checks.
- `GithubReleaseBackupServiceTest` verifies valid assets, corrupt-asset rejection, and manifest path-traversal rejection.
- `GithubReleaseRestoreServiceTest` verifies manifest parsing, renamed-asset SHA-256 reconciliation, starter cleanup planning, conflicting uploaded-asset rejection, and ambiguous digest rejection.
- `GithubDiscussionBackupServiceTest` verifies valid mixed issue/PR datasets, SHA-256 tamper rejection, and duplicate-identity rejection.
- `RecoveryTransactionStoreTest` covers persistence, phase advancement through `RELEASES_PUBLISHED`, immutable target binding, ordering, and rebound-state rejection.
- `GitMirrorPushServiceTest` validates empty-target push behavior, explicit preflight refusal for non-empty targets, exact post-push reconciliation, and rejection of unexpected extra refs.
- `GithubAuthManagerTest` covers OAuth scope normalization, workflow-scope detection, and the legacy no-cache case.
- Retention tests validate keep-all and keep-last-N selection.
- Room/KSP compilation validates database schema/query consistency in CI.
