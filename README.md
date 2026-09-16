# GithubBckp

GithubBckp is an Android app for keeping recoverable copies of repositories from a GitHub account in storage controlled by the user. The project is intended for **personal/internal use**, not public app-store distribution.

The product goal is deliberately narrow: make personal GitHub backups boring, verifiable, and recoverable. Broad GitHub feature parity is not a goal.

## Status

The core backup and recovery flows are implemented:

```text
GitHub device authorization
  -> discover/select repositories
  -> source snapshot or Git mirror
  -> mirror: Git refs/history + Git LFS objects
  -> mirror: initialized wiki history when present
  -> mirror: release metadata + verified release assets when present
  -> mirror: issue/PR metadata + comments/reviews when present
  -> checksum artifact
  -> user-selected folder or Google Drive
  -> upload + verify
  -> provider-aware history + optional keep-last-N retention

Mirror ZIP
  -> safe private import
  -> validate main Git, LFS, wiki, releases, and discussion datasets
  -> verify recovery OAuth permission
  -> bind recovery transaction to one GitHub repository ID
  -> verify target Git + release surfaces are empty
  -> verify/upload Git LFS objects
  -> verify target is still empty immediately before Git publication
  -> push writable main-repository refs without force
  -> recreate/reconcile releases and upload verified assets
  -> persist resumable recovery phases
```

Completed backups and imported mirrors expose JSON audit reports. Scheduled runs expose live child-backup progress. The Home screen also shows repository-level backup health, and the app can notify about failed attempts and overdue scheduled-backup health.

### Backup formats

- **Source snapshot** — downloads the default branch as GitHub's TAR.GZ archive. It is compact, but it is not a full-history backup.
- **Git mirror** — mirror-clones the repository, bundles detected standard Git LFS objects, optionally bundles initialized wiki history, preserves release metadata/assets, and streams issue/pull-request discussion metadata into validated JSONL datasets inside one `.mirror.zip`.

The final artifact receives SHA-256 + MD5 integrity hashes before storage. Mirror backup fails rather than silently succeeding when a referenced LFS object, release asset, or requested GitHub metadata page cannot be fetched or verified.

Main Git, Git LFS, release metadata, and release assets are recoverable to a **new or provably empty** GitHub repository. Wiki history and discussion metadata are preserved and locally verified but are intentionally archival-only for the personal-use product.

## Personal-use product boundary

The following are deliberate non-goals rather than unfinished release blockers:

- destructive recovery into arbitrary non-empty repositories;
- force-push/ref-deletion recovery workflows;
- native recreation of issues, pull requests, comments, reviews, or original authors/timestamps;
- crawling discussion timeline events and referenced attachment bytes solely for archival completeness;
- organization/team membership or permission reconstruction;
- automatic wiki publication through undocumented initialization behavior;
- exact recreation of GitHub release `latest` selection, historical publication timestamps, or immutable-release state;
- Play Store/public-distribution work.

These boundaries keep recovery behavior safe and maintenance cost appropriate for a personal tool.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) for the active reliability roadmap.

## Reliability roadmap

Completed:

1. **Backup health dashboard (#33)** — every selected/available repository is classified as protected, warning, failed, stale, or never backed up using the latest attempt and latest non-pruned verified backup.
2. **Failure and overdue-backup notifications (#34)** — failed backups can notify after durable failure recording; repeat failures are rate-limited; overdue selected repositories are checked locally and grouped into deduplicated alerts.

Remaining planned product work:

3. **On-demand backup re-verification (#35)** — re-open/re-download an existing stored artifact and prove it is still intact.
4. **Guided disaster-recovery drill (#36)** — make a safe recovery test routine rather than something first attempted during an emergency.

After the remaining two items, feature development should stop by default unless personal usage exposes a concrete recurring problem. Reliability, compatibility, security, and recovery-safety fixes remain in scope.

## Build

Requirements:

- JDK 17+
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0+

The normal repository gate verifies debug + release variants, JVM tests, and lint:

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

For personal release signing and real-device validation, see [`docs/PERSONAL_RELEASE.md`](docs/PERSONAL_RELEASE.md).

## GitHub OAuth setup

The app uses GitHub Device Flow so the Android package never ships an OAuth client secret. Create a GitHub OAuth App, enable **Device Flow**, then provide only its client ID locally:

```bash
./gradlew :app:assembleDebug -PGITHUB_CLIENT_ID=your_client_id
```

Or export `GITHUB_CLIENT_ID`. Runtime token material and cached granted-scope metadata are encrypted with Android Keystore-backed AES-GCM storage.

New authorizations request:

```text
repo workflow offline_access
```

`repo` covers private-repository access. `workflow` is required for recovery when restored Git history contains GitHub Actions workflow files. `offline_access` is requested for renewable authorization when GitHub returns expiring user tokens.

Older tokens without cached scope metadata remain usable for discovery and backup. Recovery checks the granted scopes before creating or modifying a target and asks for Device Flow authorization again if `workflow` is missing.

## Backup destinations

### Backup folder — recommended

The Android Storage Access Framework lets the user choose a writable document tree. The persisted URI grant can point at local storage, removable media, or a cloud app exposing a DocumentsProvider, without broad storage permission.

Artifacts are written below:

```text
GitHub Backups/<owner>/<repository>/<artifact>
```

The app reopens each saved document and verifies byte count, SHA-256, and MD5 before marking a backup `COMPLETED`.

### Google Drive

The Drive adapter uses Google Play services `AuthorizationClient` with `drive.file`, resumable upload, and remote checksum verification. Configure an Android OAuth client for package `com.skypie0102.githubbckp` and register the signing certificate SHA-1. The app does not persist Google access tokens.

This adapter is intentionally treated as personal/internal functionality.

## Automatic backups, health, notifications, and retention

Automatic backup settings support disabled, daily, or weekly execution and source-snapshot or Git-mirror format. WorkManager execution is opportunistic rather than an exact alarm. Scheduled backup jobs require unmetered connectivity, battery-not-low, and storage-not-low constraints.

The backup-health model considers a scheduled repository overdue after two cadence windows: 48 hours for a daily schedule and 14 days for a weekly schedule. A lightweight local health worker checks once per day while automatic backups are enabled. It evaluates only currently selected/available repositories and requires no network access.

Failed attempts can notify after the failed backup row has been written. Repeated failures for the same repository and backup format are rate-limited to one alert per six hours. Overdue repositories are grouped into one notification and remain deduplicated until their health recovers or a different repository newly becomes overdue. Tapping an alert opens the Home screen and its Backup health surface.

Android 13+ requires notification permission. The app requests it once; denying or disabling notifications does not affect backup execution, only the alert surface.

Retention defaults to **Keep all**. Users can instead keep the newest 3, 5, or 10 verified artifacts per repository and backup format. Provider identity is persisted with every new completed backup, so deletion is routed through the provider that originally created the remote artifact even if the active destination later changes.

See [`docs/SCHEDULED_BACKUPS.md`](docs/SCHEDULED_BACKUPS.md).

## Git LFS backup and recovery

Mirror mode scans reachable Git blobs for standard LFS pointers. Unique OIDs are requested from the Git LFS Batch API in groups of 100. Every object must match its declared size and SHA-256 before it is placed in the standard local store:

```text
lfs/objects/<first 2 hex>/<next 2 hex>/<full sha256 oid>
```

During GitHub recovery, bundled objects are revalidated locally and the target LFS Batch API is queried for upload actions. The target is checked for emptiness before LFS publication and again immediately before Git refs are published.

See [`docs/LFS_BACKUP.md`](docs/LFS_BACKUP.md).

## GitHub wiki backup

GitHub wikis are separate Git repositories. When the wiki feature is enabled, mirror mode attempts to clone `<owner>/<repository>.wiki.git` with mirror semantics. An enabled wiki that has never had an initial page is treated as having no wiki content.

Initialized wiki history is stored at:

```text
github-backup/wiki.git/
```

The wiki mirror is verified before packaging and again during local restore. Automatic publication is intentionally outside the personal-use roadmap.

See [`docs/WIKI_BACKUP.md`](docs/WIKI_BACKUP.md).

## GitHub release backup and recovery

Mirror mode lists GitHub releases and their assets. Release metadata is stored in a versioned manifest and binary assets are stored under the mirror backup. Asset size and SHA-256 are verified, and GitHub-provided `sha256:` digests are cross-checked when available.

Local restore revalidates the manifest, safe relative paths, file sizes, and hashes. Recovery recreates/reconciles releases and verified assets after Git refs are published.

GitHub's original latest-release selection, historical publication timestamps, and immutable-release state are intentionally not recreated.

See [`docs/RELEASE_BACKUP.md`](docs/RELEASE_BACKUP.md).

## GitHub issue and pull request metadata backup

Mirror mode preserves issue, pull-request, comment, review-comment, and review data as validated JSON Lines datasets under:

```text
github-backup/discussions/
```

The raw REST response objects are retained so GitHub-provided metadata remains available when returned by the API. Each dataset has an independent SHA-256 and record count in the versioned manifest.

Local mirror restore validates hashes, JSON records, identities, duplicate detection, and counts. Native recreation on GitHub is intentionally outside the personal-use roadmap because original identity/timestamp semantics cannot be reproduced safely and writes may trigger notifications or automation.

See [`docs/DISCUSSIONS_BACKUP.md`](docs/DISCUSSIONS_BACKUP.md).

## Resumable GitHub recovery

Every recovery is bound to a private app-side transaction keyed by the restored mirror ID. It records the exact target repository identity plus the recovery phase:

```text
TARGET_BOUND -> LFS_PUBLISHED -> GIT_PUBLISHED -> RELEASES_PUBLISHED
```

Retries cannot silently switch to another target. Recovery reuses or reconciles only state belonging to the same transaction. First-time recovery requires an empty Git target and no pre-existing releases; arbitrary non-empty overwrite is intentionally unavailable.

## Mirror restoration

The app imports a Git mirror ZIP through Android's document picker and validates it before keeping a private restored copy:

1. reject ZIP entries escaping the restore root;
2. verify the main bare Git repository and advertised ref tips;
3. verify referenced Git LFS objects;
4. verify any bundled wiki mirror independently;
5. verify any bundled release manifest and assets;
6. verify every bundled discussion dataset's SHA-256, JSON records, identities, and counts.

A validated mirror can then be published to either a **new** GitHub repository or an **existing repository that is still empty**. GitHub-owned `refs/pull/*` refs are skipped and reported. Main refs are never force-pushed.

## Audit reporting

Completed backups can export JSON history/audit reports without re-downloading the remote artifact. Imported mirrors can export module-level restore audit reports including Git, LFS, wiki, release, and discussion counts.

See [`docs/BACKUP_AUDIT.md`](docs/BACKUP_AUDIT.md) and [`docs/RESTORE_AUDIT.md`](docs/RESTORE_AUDIT.md).

## Security

Do **not** commit OAuth client secrets, access/refresh tokens, signing keys, or generated `local.properties`.

Git credentials are passed to JGit's transport layer rather than embedded in repository URLs. Temporary source/mirror artifacts live below app cache and are removed after each backup attempt. Redirected archive/LFS/release downloads do not forward GitHub authentication to unrelated hosts. Recovery transaction files live in app-private storage and bind a restore to a stable GitHub repository ID.

## Architecture

Key boundaries include:

- `GithubAuthManager` — Device Flow, encrypted token/scope persistence, recovery-scope enforcement
- `GithubGateway` / `GithubRestGateway` — repository discovery and GitHub API access
- `BackupEngineFactory` — source snapshot vs Git mirror
- `GitLfsPointerScanner` / `GitLfsDownloadService` / `GitLfsUploadService` — LFS preservation and recovery
- `GithubWikiBackupService` — wiki preservation/validation
- `GithubReleaseBackupService` / `GithubReleaseRestoreService` — release preservation/recovery
- `GithubDiscussionBackupService` — issue/PR metadata preservation/validation
- `GitMirrorRestoreService` / `MirrorRestoreCoordinator` — safe local restore
- `RecoveryTransactionStore` / `GithubMirrorRestorePublisher` — resumable safe publication
- `StorageRouter` — provider-aware upload, verification, deletion
- `BackupScheduler` / `ScheduledBackupWorker` / `BackupHealthCheckWorker` — backup scheduling and local health monitoring
- `BackupProblemNotifier` — rate-limited failure and deduplicated overdue alerts
- `BackupRetentionManager` — keep-last-N pruning
- `BackupCoordinator` — durable Room state transitions

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
