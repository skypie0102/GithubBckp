# GithubBckp

Android app for backing up repositories from a GitHub account to external storage.

## Status

The core backup, scheduling, retention, local restore, and safe GitHub recovery flows are implemented:

```text
GitHub device authorization
  -> discover/select repositories
  -> source snapshot or Git mirror
  -> mirror: Git refs/history + Git LFS objects
  -> mirror: initialized wiki history when present
  -> mirror: release metadata + verified release assets when present
  -> checksum artifact
  -> user-selected folder or Google Drive
  -> upload + verify
  -> provider-aware history + optional keep-last-N retention

Mirror ZIP
  -> safe private import
  -> validate main Git, LFS, wiki, and release data
  -> create a new GitHub repository OR select an existing empty repository
  -> verify/upload Git LFS objects
  -> verify target advertises no Git refs
  -> push writable main-repository refs without force
```

### Backup formats

- **Source snapshot** — downloads the default branch as GitHub's TAR.GZ archive. It is compact, but not a full-history backup.
- **Git mirror** — mirror-clones the repository, bundles all detected standard Git LFS objects, optionally bundles initialized wiki history, and preserves release metadata/assets inside one `.mirror.zip`. The artifact receives SHA-256 + MD5 integrity hashes before storage.

Git mirror backup fails rather than silently succeeding when a referenced LFS object or release asset cannot be fetched or verified. Main Git/LFS recovery is publishable to a new or provably empty GitHub repository. Wiki history and releases are preserved and locally verified, but automatic GitHub publication for those two modules is intentionally deferred and recorded as non-fatal completeness warnings.

Issues and pull-request metadata remain future completeness modules.

## Build

Requirements:

- JDK 17+
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0+

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## GitHub OAuth setup

The app uses GitHub Device Flow so the Android package never ships an OAuth client secret. Create a GitHub OAuth App, enable **Device Flow**, then provide only its client ID locally:

```bash
./gradlew :app:assembleDebug -PGITHUB_CLIENT_ID=your_client_id
```

Or export `GITHUB_CLIENT_ID`. Runtime token material is encrypted with Android Keystore-backed AES-GCM storage.

## Backup destinations

### Backup folder — recommended

The Android Storage Access Framework lets the user choose a writable document tree. The persisted URI grant can point at local storage, removable media, or a cloud app exposing a DocumentsProvider, without broad storage permission.

Artifacts are written below:

```text
GitHub Backups/<owner>/<repository>/<artifact>
```

The app reopens each saved document and verifies byte count, SHA-256, and MD5 before marking the backup `COMPLETED`.

### Google Drive

The Drive adapter uses Google Play services `AuthorizationClient` with `drive.file`, resumable upload, and remote checksum verification. Configure an Android OAuth client for package `com.skypie0102.githubbckp` and register the signing certificate SHA-1. The app does not persist Google access tokens.

Because Google's Workspace API policy restricts generic backup-to-Drive use for public apps, treat this adapter as personal/internal unless written permission or current policy guidance says otherwise.

## Automatic backups and retention

Automatic backup settings support disabled, daily, or weekly execution and source-snapshot or Git-mirror format. WorkManager execution is opportunistic rather than an exact alarm. Scheduled work requires unmetered connectivity, battery-not-low, and storage-not-low constraints.

Retention defaults to **Keep all**. Users can instead keep the newest 3, 5, or 10 verified artifacts per repository and backup format. Provider identity is persisted with every new completed backup, so deletion is routed through the storage provider that originally created the remote artifact even if the active destination later changes.

## Git LFS backup and recovery

Mirror mode scans reachable Git blobs for standard LFS pointers. Unique OIDs are requested from the Git LFS Batch API in groups of 100. Every object must match its declared size and SHA-256 before it is placed in the standard local store:

```text
lfs/objects/<first 2 hex>/<next 2 hex>/<full sha256 oid>
```

During GitHub recovery, bundled objects are revalidated locally, the target LFS Batch API is queried for upload actions, missing objects are uploaded, and optional server verify actions are executed. Only after LFS coverage succeeds does the app publish Git refs.

See [`docs/LFS_BACKUP.md`](docs/LFS_BACKUP.md).

## GitHub wiki backup

GitHub wikis are separate Git repositories. When the wiki feature is enabled, mirror mode attempts to clone `<owner>/<repository>.wiki.git` with mirror semantics. An enabled wiki that has never had an initial page is treated as having no wiki content.

Initialized wiki history is stored at:

```text
github-backup/wiki.git/
```

The wiki mirror is verified before packaging and again during local restore. Automatic wiki publication remains intentionally unavailable because GitHub does not expose a documented wiki-page initialization API and the app does not rely on undocumented overwrite behavior.

See [`docs/WIKI_BACKUP.md`](docs/WIKI_BACKUP.md).

## GitHub release backup

Mirror mode also lists GitHub releases and their assets. Release metadata is stored in a versioned manifest:

```text
github-backup/releases/manifest.json
```

Binary assets are stored below:

```text
github-backup/releases/assets/release-<release id>/<asset id>-<filename>
```

Every asset must match GitHub's reported size and a locally computed SHA-256. When GitHub supplies a `sha256:` release-asset digest, that server digest is cross-checked too. Redirected asset hosts never receive the GitHub bearer token.

Local mirror restore revalidates the manifest, safe relative paths, file sizes, and SHA-256 values. Releases with no binary assets are still preserved in the manifest.

Automatic release recreation/upload is intentionally deferred. Releases depend on Git refs already existing; a failure after the Git push would leave a partially recovered non-empty target. A resumable, target-bound recovery transaction is required before enabling that publication path.

See [`docs/RELEASE_BACKUP.md`](docs/RELEASE_BACKUP.md).

## Mirror restoration

The app imports a Git mirror ZIP through Android's document picker and validates it before keeping a private restored copy:

1. Reject ZIP entries escaping the restore root.
2. Verify the main bare Git repository and every advertised ref tip.
3. Revalidate bundled Git LFS objects during GitHub publication.
4. Verify any bundled wiki mirror independently.
5. Verify any bundled release manifest and every asset's size/SHA-256.

A validated main mirror can be published to either a **new** GitHub repository or an **existing repository that is still empty**. Git LFS is uploaded before Git refs. Immediately before the Git write, the app checks that the target still advertises no refs. Main refs are then pushed without force. GitHub's read-only `refs/pull/*` namespace is skipped and reported.

Destructive overwrite of an arbitrary non-empty repository remains intentionally unavailable.

## Security

Do **not** commit OAuth client secrets, access/refresh tokens, signing keys, or generated `local.properties`. Git credentials are passed to JGit's transport layer rather than embedded in repository URLs. Temporary source/mirror artifacts live below app cache and are removed after each backup attempt.

For redirected GitHub archive, LFS, and release-asset downloads, authentication is deliberately not forwarded to unrelated hosts.

## Architecture

Key boundaries:

- `GithubAuthManager` — GitHub Device Flow and encrypted token persistence
- `GithubGateway` / `GithubRestGateway` — repository discovery, feature lookup, source archive transfer
- `BackupEngineFactory` — source snapshot vs Git mirror
- `GitLfsPointerScanner` / `GitLfsDownloadService` — LFS backup
- `GitLfsObjectStore` / `GitLfsUploadService` — LFS recovery
- `GithubWikiBackupService` — wiki mirror preservation/validation
- `GithubReleaseBackupService` — release manifest/asset preservation/validation
- `GitMirrorRestoreService` / `MirrorRestoreCoordinator` — safe local restore
- `GitMirrorPushService` / `GithubMirrorRestorePublisher` — safe main-repository publication
- `StorageRouter` — provider-aware upload, verification, deletion
- `BackupScheduler` / `ScheduledBackupWorker` — manual and periodic scheduling
- `BackupRetentionManager` — keep-last-N pruning
- `BackupCoordinator` — durable Room state transitions

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Next implementation slice

1. Add a resumable recovery transaction so release recreation/asset upload can safely continue after the main Git push.
2. Design separately confirmed destructive recovery for non-empty repositories with explicit ref-deletion and repository-rule handling.
3. Add issues and pull-request metadata backup/recovery modules.
4. Add richer backup/restore audit and export reporting.
