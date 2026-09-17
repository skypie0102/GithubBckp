# Architecture

GithubBckp is an Android backup/recovery orchestrator for personal/internal use. Repository data moves directly between GitHub, temporary app storage, and the user-selected destination. The app does not require an application server to handle repository contents.

The architecture optimizes for three properties:

1. **Integrity** — a backup is not treated as successful until its bytes/checksums and supported module data are verified.
2. **Recoverability** — recovery is explicit, target-bound, resumable, and refuses ambiguous or unsafe remote state.
3. **Operator confidence** — history, audit exports, backup-health presentation, notifications, fresh stored-artifact re-verification, and guided recovery drills make silent failure increasingly difficult.

See [`ROADMAP.md`](ROADMAP.md) for the completed personal reliability roadmap, feature-freeze rule, and explicit non-goals.

## High-level boundaries

```text
Compose UI
   |
HomeViewModel
   +-- backup health presentation
   +-- normal restore orchestration
   +-- recovery-drill orchestration
   |
DisasterRecoveryDrillOverlay
   +-- validated-mirror selection
   +-- republishable vs archival-only module guidance
   +-- private-target drill setup
   |
DisasterRecoveryDrillService
   +-- GithubMirrorRestorePublisher with isolated target-specific transaction key
   +-- GitMirrorPushService.verifyPublished()
   +-- app-private drill result persistence
   |
BackupReverificationViewModel
   |
BackupReverificationService
   +-- StorageRouter -> original provider read-only download
   +-- local size / SHA-256 / MD5 recomputation
   +-- GitMirrorRestoreService.validate() for mirror backups
   +-- Room re-verification result persistence
   |
BackupScheduler
   +-- ScheduledBackupWorker
   +-- BackupHealthCheckWorker
   |
RepositoryBackupWorker
   +-- BackupProblemNotifier
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
   +-- main Git verification
   +-- LFS verification
   +-- wiki verification
   +-- release manifest/assets verification
   +-- discussion JSONL/hash validation
   |
GithubMirrorRestorePublisher
   +-- GithubAuthManager
   +-- RecoveryTransactionStore
   +-- GitLfsObjectStore / GitLfsUploadService
   +-- GithubReleaseRestoreService
   +-- GithubRepositoryRestoreGateway
   +-- GitMirrorPushService
```

## GitHub authentication and API

`GithubAuthManager` uses GitHub OAuth Device Flow. The Android package carries only an OAuth client ID. Access/refresh material and cached granted-scope metadata are encrypted with an Android Keystore-backed AES-GCM key before being placed in SharedPreferences.

New Device Flow authorizations request `repo workflow offline_access`. Backup/discovery uses ordinary repository access. Recovery uses `requireRecoveryAccessToken()`, which requires the granted `workflow` scope before any recovery repository can be created or resolved. This avoids creating a partial target that later rejects workflow-bearing refs.

For legacy installations without cached scope metadata, recovery queries GitHub's authenticated-user response, reads `X-OAuth-Scopes`, and caches the normalized set. Missing `workflow` produces a dedicated recoverable permission error and the UI offers Device Flow again.

`GithubGateway` owns repository discovery, authenticated source-archive transfer, and lightweight feature lookup. Redirects from GitHub's API to archive/object storage are followed without forwarding the bearer token off GitHub's API host.

`GithubRepositoryRestoreGateway` creates a new empty recovery repository or resolves a user-supplied existing target. Recovery drills additionally require the resolved target to be private.

## Repository inventory

Room retains repository rows so selection/history survive temporary disappearance from GitHub discovery. `RepositoryEntity.isAvailable` is reconciled only after a successful complete discovery response.

Unavailable repositories are hidden from active selection and excluded from scheduled work, health alerts, and overdue notification evaluation, but historical backup rows are retained. If a repository later reappears, its prior selection preference can be reused.

See [`REPOSITORY_RECONCILIATION.md`](REPOSITORY_RECONCILIATION.md).

## Backup engines

### Source snapshots

`SourceArchiveBackupEngine` downloads GitHub's default-branch archive. It is compact but does not represent full Git history.

### Git mirrors

`GitMirrorBackupEngine` uses JGit mirror-clone semantics to fetch main-repository refs into a bare repository. Credentials are supplied through JGit transport and never written into repository URLs.

A mirror can additionally contain the following validated modules.

#### Git LFS

`GitLfsPointerScanner` walks reachable Git objects and finds unique standard LFS pointer OIDs/sizes. `GitLfsDownloadService` uses the Git LFS Batch API, verifies size plus SHA-256, and stores objects in the standard local layout under `lfs/objects/`.

During recovery, bundled objects are revalidated and missing target objects are uploaded through the target LFS Batch API. Target emptiness is checked before publication and immediately before main Git refs are pushed.

See [`LFS_BACKUP.md`](LFS_BACKUP.md).

#### Wiki history

If the repository reports the wiki feature enabled, `GithubWikiBackupService` attempts a mirror clone of `<owner>/<repo>.wiki.git`. An enabled but never-initialized wiki is treated as no wiki content. An initialized wiki is verified independently and stored at `github-backup/wiki.git/`.

Wiki publication is intentionally archival/manual for the personal-use product; undocumented initialization behavior is not used.

See [`WIKI_BACKUP.md`](WIKI_BACKUP.md).

#### Releases and release assets

`GithubReleaseBackupService` pages through releases/assets and writes a versioned manifest plus verified binary assets. Every asset must match GitHub's reported size and a locally computed SHA-256. When GitHub exposes a `sha256:` digest, it is independently cross-checked.

`GithubReleaseRestoreService` recreates/reconciles supported release metadata and assets after main Git publication. Existing or newly uploaded assets are verified against expected size and SHA-256; when GitHub does not expose a usable digest, verification downloads the remote asset and hashes it locally.

Exact latest-release selection, historical server timestamps, and immutable-release state are intentionally not reproduced.

See [`RELEASE_BACKUP.md`](RELEASE_BACKUP.md).

#### Issues, pull requests, comments, and reviews

`GithubDiscussionBackupService` stores raw REST response objects as versioned JSON Lines datasets under `github-backup/discussions/`. Dataset record counts and SHA-256 hashes are recorded in a manifest and revalidated during import.

Native recreation of discussions is intentionally outside the personal-use roadmap because original author/timestamp semantics cannot be reproduced safely and writes can trigger notifications/automation.

See [`DISCUSSIONS_BACKUP.md`](DISCUSSIONS_BACKUP.md).

## Storage routing and retention

`StorageRouter` routes upload, lightweight verification, read-only download, and deletion through the provider associated with the operation. Persisted provider identity lets later retention or re-verification address the same remote object even if the user changes the active destination.

`DocumentTreeStorageProvider` reopens saved files and can stream the persisted document URI back into app-private cache. `GoogleDriveStorageProvider` uses resumable upload, remote checksum metadata, and authenticated file-byte download through the Drive API.

`BackupRetentionManager` supports keep-all or keep-last-N pruning per repository and backup format. Pruning marks the historical backup row with `remoteDeletedAtEpochMs` rather than erasing history.

The backup-health model therefore ignores retention-pruned completions when deciding whether a repository still has a current verified artifact. Pruned rows also cannot be on-demand re-verified because their remote object is intentionally gone.

## Background execution

Manual backups enqueue one repository worker per selected repository. Periodic backup work uses a WorkManager controller that reads the currently selected, available repositories at execution time and fans out repository work.

Scheduled backup jobs require unmetered networking, battery-not-low, and storage-not-low constraints. WorkManager execution is opportunistic rather than exact.

Each scheduled controller run persists an outcome and, when work is queued, a correlation ID. Child backup rows persist that same ID so the UI can summarize live completion/failure/cancellation/progress for the run.

While automatic backups are enabled, `BackupScheduler` also maintains `BackupHealthCheckWorker`, a separate local periodic worker that runs every 24 hours after an initial one-hour delay. It needs no network because it evaluates persisted Room history and schedule metadata. Disabling the schedule cancels both periodic workers and clears overdue-notification state.

See [`SCHEDULED_BACKUPS.md`](SCHEDULED_BACKUPS.md).

## Backup state machine

Source snapshot:

```text
QUEUED -> DOWNLOADING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                 \-------------------------------------------> FAILED
```

Git mirror:

```text
QUEUED -> DOWNLOADING (Git + LFS + wiki + releases + discussions)
       -> PACKAGING -> CHECKSUM -> UPLOADING -> VERIFYING -> COMPLETED
                 \--------------------------------------------> FAILED
```

After `COMPLETED`, retention may prune an older verified remote artifact without changing its historical completion state.

## Backup-health presentation

`BackupDao.observeBackupHealthHistory()` returns at most the rows needed to classify each repository: the latest attempt and the latest non-pruned verified completion. `BackupHealthPresentation` combines those rows with the currently selected repository inventory.

Health states are:

- `PROTECTED` — a verified artifact exists and no higher-priority problem applies;
- `WARNING` — the latest verified artifact has a completeness warning, or a newer attempt was cancelled;
- `FAILED` — a failed attempt occurred after the latest verified backup;
- `STALE` — automatic backups are enabled and the latest verified backup is older than two cadence windows;
- `NEVER_BACKED_UP` — no current non-pruned verified artifact exists.

The calculation is intentionally pure/testable and does not depend on the fixed-size Recent backups UI list. A later successful backup clears an earlier failure.

## Backup problem notifications

`RepositoryBackupWorker` receives the boolean result from `BackupCoordinator`. When the coordinator has already persisted a `FAILED` row, the worker resolves the repository again and only notifies if the repository is still available and selected. `BackupProblemNotifier` rate-limits repeated failure notifications for the same repository/backup format to one every six hours. Notification taps open `MainActivity`, where the Home health card and backup history show the actionable state.

Overdue alerts are evaluated separately by `BackupHealthCheckWorker`. Its pure notification policy uses the same two-cadence freshness window as the Home health model. For repositories with no successful backup, `BackupSchedulePreferences` persists the schedule-enable timestamp and uses it as the start of the same grace window.

The notifier stores the current overdue repository-ID set in app-private SharedPreferences. It emits one grouped alert only when at least one repository newly enters that set. Repositories that recover are removed on the next health check, allowing a later independent overdue episode to notify again. Turning automatic backups off explicitly clears this state.

Android 13+ notification delivery is gated by `POST_NOTIFICATIONS`. `MainActivity` asks once; the notification layer also checks runtime permission and OS notification enablement before posting. Lack of permission never changes backup execution or persisted health state.

## On-demand stored-artifact re-verification

Database schema v9 adds three nullable fields to each backup row: latest re-verification timestamp, status, and detail. Existing rows migrate with all fields unset.

`BackupReverificationService` accepts only completed, non-pruned rows that still contain the provider identity, remote object identity, byte size, SHA-256, and MD5 persisted at creation time. The current destination is irrelevant; `StorageRouter` routes the read to the provider recorded on that backup row.

The provider downloads the complete object to a temporary file below app cache. `BackupReverificationService` recomputes size, SHA-256, and MD5 locally and requires all three to match the persisted verified values. Google Drive metadata alone is therefore insufficient for a successful on-demand check.

For `GIT_MIRROR` rows, the service then calls `GitMirrorRestoreService.validate()` against the temporary archive. This deliberately reuses the same safe extraction and module-validation path as restore instead of creating a second Git/LFS/wiki/release/discussion validator. No GitHub publication path is reachable from re-verification.

The temporary download and validation scratch directory are deleted in `finally`. The remote artifact is never updated or replaced. Success and failure both persist as separate re-verification state; the historical backup status remains `COMPLETED` because it describes creation-time success, not present-day readability.

`BackupReverificationViewModel` provides a narrow UI action state so only one re-verification is launched at a time. The normal Recent backups Room flow refreshes the row after the result is persisted.

See [`BACKUP_AUDIT.md`](BACKUP_AUDIT.md).

## Local mirror restore

`GitMirrorRestoreService` rejects ZIP entries escaping the restore root and validates every bundled module before the restored copy is retained:

- main bare Git repository and advertised ref tips;
- referenced Git LFS objects;
- optional wiki mirror;
- optional release manifest/assets;
- optional discussion datasets and counts.

`MirrorRestoreCoordinator` stores validated restores in private app storage and reloads valid records across restarts.

Restore audit export summarizes the validation result without duplicating the recovery artifact. See [`RESTORE_AUDIT.md`](RESTORE_AUDIT.md).

## Resumable GitHub recovery

`GithubMirrorRestorePublisher` obtains a recovery-validated OAuth token **before** target creation/resolution. First-time recovery is restricted to a new or provably empty Git/release target.

`RecoveryTransactionStore` persists a caller-supplied transaction key and binds that transaction to one stable GitHub repository ID/full name. Ordinary recovery uses the restored mirror ID as that key and advances monotonically through:

```text
TARGET_BOUND -> LFS_PUBLISHED -> GIT_PUBLISHED -> RELEASES_PUBLISHED
```

Retries cannot silently switch targets. A crash after Git publication but before the phase is persisted is reconciled by comparing every writable target ref name/object ID with the local mirror; only an exact match is accepted as already published.

Release retries similarly reconcile only matching releases/assets. Conflicting or ambiguous remote state fails safely.

The transaction mechanism deliberately does **not** authorize arbitrary non-empty overwrite, force-push, or destructive ref deletion.

## Guided disaster-recovery drill

`DisasterRecoveryDrillOverlay` exposes a dedicated rehearsal surface without replacing ordinary recovery. It lists validated restored mirrors, shows which modules are automatically republishable versus archival-only, and requires either a newly created private target or an existing private target that is still empty.

`DisasterRecoveryDrillService` deliberately reuses `GithubMirrorRestorePublisher` rather than implementing a second publication engine. The publisher receives a deterministic drill transaction key derived from the restore ID, target kind, and normalized target identity. That key is valid for `RecoveryTransactionStore`, is stable across retries to the same drill target, differs across drill targets, and never equals the normal restore ID. A rehearsal therefore cannot consume the ordinary recovery binding.

After the normal publisher reaches `RELEASES_PUBLISHED`, `GitMirrorPushService.verifyPublished()` performs an additional read-only exact comparison of every writable local ref name/object ID with the remote advertised refs. LFS counts are only returned after local object validation and the LFS upload protocol succeeds; release assets are only counted after remote size/SHA-256 verification succeeds.

A successful `DisasterRecoveryDrillResult` is stored as versioned JSON below app-private files storage and reloaded with the restored-mirror list. It records the target, completion time, verified Git/LFS/release counts, and the automatic/archival module plan. The app never automatically deletes the drill target; cleanup is an explicit operator decision after reviewing the result.

## Audit reporting

Completed backup rows can export JSON audit/history reports. Exporting a backup audit never initiates a remote read; format v5 reports creation-time integrity/provenance plus the latest separately recorded on-demand re-verification result, if one exists.

Restore audit exports remain separate from recovery-drill results. A drill result is an operational rehearsal record tied to the locally restored mirror and latest successful drill target.

See [`BACKUP_AUDIT.md`](BACKUP_AUDIT.md).

## Personal-use scope boundary

The following are architectural non-goals unless the product assumptions change:

- native discussion reconstruction;
- discussion timeline/attachment crawling solely for archival completeness;
- destructive recovery into arbitrary non-empty repositories;
- organization/team identity or permission reconstruction;
- automatic wiki publication through undocumented behavior;
- perfect recreation of GitHub's server-managed release semantics;
- Play Store/public-distribution infrastructure.

The guiding rule is to prefer a smaller, auditable recovery system over a broader system that can silently mutate live GitHub state.

## Test coverage

The JVM suite covers, among other things:

- mirror restore with real Git repositories and optional modules;
- ZIP path traversal rejection;
- LFS pointer scanning, Batch API parsing, upload/download integrity, and already-present objects;
- release backup/restore validation and reconciliation;
- discussion dataset validation/tamper/duplicate rejection;
- recovery transaction persistence and target binding;
- empty-target push behavior and exact post-push reconciliation;
- recovery-drill module capability classification and isolated deterministic transaction keys;
- OAuth scope normalization and legacy scope discovery;
- retention selection;
- backup-health state classification;
- overdue-notification policy and failure rate-limit boundaries;
- re-verification eligibility and size/SHA-256/MD5 mismatch detection;
- scheduled-run readiness/progress presentation.

Room/KSP compilation validates database schema/query consistency in CI. The repository CI gate builds debug and release variants, runs JVM tests, and runs Android lint.
