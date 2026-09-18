# GithubBckp refactor roadmap

GithubBckp is being reduced to one responsibility:

> Keep one current local `.tar.gz` Git mirror for every selected GitHub repository.

This roadmap replaces the previous recovery-oriented product plan. The refactor branch is `refactor/simple-local-mirrors` and the working draft PR is #52.

## Product boundary

The refactored app will:

- authenticate with a user-supplied GitHub personal access token;
- list repositories available to that token;
- let the user select repositories to mirror;
- store backups only in a user-selected local Android document tree;
- keep one logical backup artifact per repository;
- create a real bare Git mirror, including refs and history;
- preserve referenced Git LFS objects;
- package every completed backup as `mirror.tar.gz`;
- update an existing mirror with fetch/prune semantics rather than cloning from scratch;
- avoid extraction/recompression entirely when remote refs and repository metadata are unchanged;
- run manual, daily, or weekly updates;
- expose every active repository job through a foreground notification;
- keep a simple health dashboard.

The refactored app will not provide:

- Google Drive API storage;
- multiple backup generations or retention controls;
- restore/import/publish workflows;
- force-push or repository creation;
- release/asset backup;
- issue, pull-request, review, or discussion backup;
- wiki backup;
- Git LFS upload;
- source archive mode.

## Storage contract

Each GitHub repository ID maps to one logical archive:

```text
<selected root>/
└── GitHub Backups/
    └── <owner>--<repo>--<github-repository-id>/
        └── <owner>--<repo>.tar.gz
```

A transactional update may temporarily create:

```text
mirror.tar.gz
mirror.pending.tar.gz
```

The pending file is not a retained backup generation. It exists only so a failed write, process death, or power loss cannot destroy the previous verified mirror.

## Archive contract

`mirror.tar.gz` contains:

```text
manifest.json
repository.git/
    HEAD
    config
    refs/
    objects/
    lfs/
        objects/
```

`repository.git` is a bare Git repository.

The versioned manifest records repository identity, remote URL, default branch, privacy state, timestamps, refs digest, source HEAD, LFS presence, archive format version, and app version.

## Update algorithm

### First backup

```text
clone --mirror equivalent
→ scan reachable Git LFS pointers
→ download missing LFS objects
→ calculate refs digest
→ write manifest
→ create mirror.pending.tar.gz
→ fully verify archive and bare repository
→ persist pending archive to selected folder
→ verify persisted SHA-256
→ promote to mirror.tar.gz
```

### Existing backup

```text
read manifest.json directly from tar.gz
→ ls-remote
→ compare refs digest + repository metadata
```

If unchanged:

```text
record successful check
→ no extraction
→ no recompression
→ no archive rewrite
```

If changed:

```text
extract existing mirror to app-private working storage
→ fetch + prune refs
→ download missing LFS objects
→ Git GC
→ update manifest
→ build mirror.pending.tar.gz
→ verify
→ transactionally replace mirror.tar.gz
→ delete loose working files
```

Deleted branches and tags are pruned. Files removed from the latest repository state disappear from the latest tree, while historical blobs remain when still reachable from Git history.

---

## Phase 0 — Refactor branch and CI

Status: **complete except final merge**

- [x] Create `refactor/simple-local-mirrors`.
- [x] Open draft PR #52.
- [x] Keep existing Android CI running against every refactor commit.
- [x] Keep PR draft through cutover; the replacement pipeline is now the only active backup pipeline.

Exit criteria:

- all work happens on the dedicated branch;
- Android build, unit tests, and lint are green before merge.

---

## Phase 1 — New mirror archive foundation

Status: **complete**

- [x] Add Apache Commons Compress.
- [x] Add `TarGzArchive`.
- [x] Reject path traversal during extraction.
- [x] Reject symbolic/hard-link archive entries.
- [x] Add versioned `MirrorManifest`.
- [x] Read `manifest.json` without extracting the full archive.
- [x] Add `MirrorVerifier`.
- [x] Reopen extracted `repository.git` with JGit and require a bare repository.
- [x] Add archive and manifest unit tests.
- [x] Validate every reachable LFS object during full mirror verification.
- [x] Add corruption/truncation tests.

Exit criteria:

- a synthetic bare repository can be packed, deleted, extracted, reopened by JGit, and verified;
- malformed/path-traversing archives fail closed.

---

## Phase 2 — Incremental Git mirror engine

Status: **complete; broader device hardening continues in Phase 11**

- [x] Add pure `GitMirrorOperations`.
- [x] Mirror clone semantics.
- [x] Fetch with `+refs/*:refs/*`.
- [x] Prune deleted remote refs.
- [x] Update symbolic HEAD to the current default branch.
- [x] Git GC after updates.
- [x] Deterministic refs digest.
- [x] Add local Git integration test covering updated and removed refs.
- [x] Add `MirrorEngine.create`.
- [x] Add `MirrorEngine.update`.
- [x] Skip extraction/rebuild when refs and repository metadata are unchanged.
- [x] Reuse read-side Git LFS scanning/download.
- [x] Add end-to-end archived mirror extract → fetch/prune → repack → verify coverage.
- [x] Test force-updated branches and deleted tags.
- [x] Test empty repositories.

Exit criteria:

- changed repositories transfer only missing Git/LFS data;
- deleted refs disappear from the mirror;
- unchanged repositories do not rewrite the archive.

---

## Phase 3 — Local-only transactional storage

Status: **complete; interruption hardening continues in Phase 11**

- [x] Add `LocalMirrorStore`.
- [x] Keep GitHub repository ID as the stable storage identity while using owner/repository names for human-readable folders/files.
- [x] Write `mirror.pending.tar.gz` before touching the stable mirror.
- [x] Re-hash persisted bytes before promotion.
- [x] Reconcile a pending archive after interruption.
- [x] Keep the stable archive untouched when candidate writing/verification fails.
- [x] Estimate expanded archive size and fail early when app-private temporary space is insufficient.
- [x] Detect unavailable/revoked SAF folder access and surface blocked health.
- [x] Reconcile pending/missing mirrors on startup.

Exit criteria:

- killing the process at any update stage cannot corrupt the last verified mirror;
- only one logical current artifact remains after reconciliation.

---

## Phase 4 — One-row-per-repository data model

Status: **complete**

- [x] Add `MirrorEntity` keyed by `repositoryId`.
- [x] Add `MirrorDao`.
- [x] Add Room migration 9 → 10.
- [x] Register the mirror table in `AppDatabase`.
- [x] Move health/state reads to `MirrorEntity`.
- [x] Stop writing new rows to the historical `backups` table.
- [x] Remove the historical backup model after migration is complete (v10 → v11 drops `backups`).

Target mirror state:

```text
repositoryId (PK)
archiveUri
archiveSizeBytes
archiveSha256
formatVersion
lastCheckedAt
lastSuccessfulSyncAt
lastChangedAt
lastAttemptAt
lastAttemptStatus
lastSourceHead
lastRefsDigest
lastError
lastWarning
```

Exit criteria:

- Room cannot represent multiple current mirrors for a repository.

---

## Phase 5 — Cut over worker/coordinator

Status: **complete**

Replace the current coordinator path with one repository sync operation:

```text
RepositoryBackupWorker
    ↓
MirrorSyncCoordinator
    ↓
MirrorEngine
    ↓
LocalMirrorStore
    ↓
MirrorDao
```

Tasks:

- [x] add `MirrorSyncCoordinator`;
- [x] copy an existing stored mirror to app-private working storage;
- [x] create when no mirror exists;
- [x] update when a mirror exists;
- [x] persist successful no-change checks without rewriting archive;
- [x] persist success/failure directly to `MirrorEntity`;
- [x] always clean app-private loose files in `finally`;
- [x] make repository work unique by GitHub repository ID.

Exit criteria:

- manual backup/update uses only the new tar.gz pipeline.

---

## Phase 6 — Scheduling and mandatory active-job notifications

Status: **complete**

Keep WorkManager with only:

- Off
- Daily
- Weekly

Tasks:

- [x] remove obsolete backup-format preferences;
- [x] retain unique repository work;
- [x] foreground every active repository job;
- [x] expose stages: checking, extracting, fetching, LFS, optimizing, compressing, verifying;
- [x] show exact byte progress during final archive commit; keep non-measurable Git/network stages indeterminate;
- [x] add cancel action;
- [x] group simultaneous repository notifications under one active-mirror group;
- [x] block job start when Android notification visibility is unavailable;
- [x] show notification-disabled state as a readiness/health problem.

Exit criteria:

- every running mirror job has an ongoing device notification for its complete lifetime.

---

## Phase 7 — Authentication and onboarding

Status: **complete**

- [x] Replace recovery/write permission wording.
- [x] State exact recommended fine-grained PAT permissions:
  - Contents: Read-only
  - Metadata: Read-only
- [x] State that no write/admin permission is required.
- [x] Explain classic `repo` scope only as a broader compatibility fallback.
- [x] validate repository Git readability with bounded-concurrency `ls-remote` checks during refresh;
- [x] move notification permission/settings handling into explained onboarding;
- [x] enforce onboarding order: token → folder → notifications → repository selection.

Exit criteria:

- users know exactly which permissions are required before creating a token;
- inaccessible private repositories fail during readiness validation rather than during a scheduled backup.

---

## Phase 8 — Delete legacy product code

Status: **complete**

Delete after the new worker path is operational so the branch stays buildable during migration.

Remove:

- [x] `GoogleDriveAuthManager`;
- [x] `GoogleDriveStorageProvider`;
- [x] `StorageRouter`;
- [x] Google Play Services Auth dependency;
- [x] storage destination selector/state;
- [x] `GitMirrorRestoreService`;
- [x] `MirrorRestoreCoordinator`;
- [x] `GithubMirrorRestorePublisher`;
- [x] `GithubRepositoryRestoreGateway`;
- [x] `GitMirrorPushService`;
- [x] `GithubReleaseRestoreService`;
- [x] `RecoveryTransactionStore`;
- [x] `RestoreAuditReport`;
- [x] `GitLfsUploadService`;
- [x] `GithubReleaseBackupService`;
- [x] `GithubDiscussionBackupService`;
- [x] `GithubWikiBackupService`;
- [x] related UI, tests, Room history table, and obsolete documentation.

Exit criteria:

- no Google authorization code remains;
- no restore/publish code remains;
- no GitHub-hosted metadata backup modules remain;
- the APK has no dependency used only by those features.

---

## Phase 9 — UI and health dashboard rebuild

Status: **complete, with device-feedback UX polish applied**

Target Home screen:

```text
GitHub account
Backup folder
Automatic updates
Health summary
Repository list
```

Repository health states:

- HEALTHY
- UPDATING
- STALE
- FAILED
- MISSING
- BLOCKED
- NEVER_BACKED_UP

Tasks:

- [x] distinguish last successful check from last archive change;
- [x] show archive size;
- [x] show source HEAD;
- [x] show last failure;
- [x] show schedule;
- [x] remove restore/import/reverify/audit/provider controls;
- [x] reduce `HomeViewModel` and `HomeScreen` rather than layering more state onto them;
- [x] paint a consistent app background matching the active Material theme;
- [x] consume system-bar insets through Scaffold/TopAppBar so content does not collide with the status bar;
- [x] collapse completed setup into a compact summary;
- [x] make repository rows and scheduling controls materially more compact;
- [x] keep the primary backup action reachable in a persistent bottom action area;
- [x] use human-readable backup folder/archive names while preserving repository-ID identity.

Freshness tolerance:

- daily schedule: stale after 48 hours without a successful check;
- weekly schedule: stale after 14 days without a successful check.

Exit criteria:

- the complete application workflow is understandable from the Home screen.

---

## Phase 10 — Legacy archive migration

Status: **implemented for app-owned local ZIP mirrors**

Existing `.mirror.zip` backups must not be silently destroyed.

Policy:

1. detect old ZIP mirrors as legacy;
2. do not mutate them in place;
3. create a new vNext `.tar.gz` mirror;
4. fully verify the new mirror;
5. only then remove app-owned legacy ZIPs matching the old exact naming convention for that repository.

Google-Drive-only installations must select a local folder before any new work starts.

Exit criteria:

- an upgrade cannot leave a repository with no verified backup due to migration failure.

---

## Phase 11 — Hardening matrix

Status: **automated hardening complete; device/live-provider validation remains**

Required tests include:

- [x] revoked/expired PAT response policy and scheduled token revalidation;
- [x] missing fine-grained repository access via unreadable-repository preflight;
- organization token approval pending; same unreadable-repository path is implemented, with live org-approval validation tracked in issue #53;
- [x] repository deleted/unreadable remote;
- [x] repository renamed;
- [x] repository privacy changed;
- [x] default branch changed;
- [x] branch force-pushed;
- [x] branch deleted;
- [x] tag deleted;
- [x] empty repository;
- [x] synthetic large-repository stress (~12 MiB incompressible payload plus many refs); real device-scale stress remains a manual release gate;
- [x] Unicode paths;
- [x] Git LFS pointers and missing/corrupt LFS object;
- [x] corrupt/truncated tar.gz;
- [x] archive manually deleted;
- [x] selected folder moved/unavailable;
- [x] SAF permission revoked/unavailable;
- [x] notification permission/app/channel unavailable;
- [x] preflight temporary-space estimate before extraction;
- [x] simulated storage exhaustion/write failure during compression;
- process death during fetch; automated failed-fetch coverage proves the stable archive is untouched, with device-level process-kill validation tracked in issue #53;
- process death during compression; automated truncated-candidate coverage proves the stable archive is untouched, with device-level process-kill validation tracked in issue #53;
- process death before/after promotion; recovery state-machine coverage is implemented, with device-level process-kill validation tracked in issue #53;
- [x] bounded WorkManager retry policy;
- [x] multiple simultaneous selected repositories use independent unique work plans.

Exit criteria:

- interruption testing demonstrates the previous verified mirror survives every failed update stage.

### Remaining manual gates

The automated test matrix is green. The following remain intentionally manual and are mirrored in `docs/RELEASE.md`:

- live GitHub organization approval/revocation behavior;
- upgrade migration from a signed pre-refactor installation;
- real device-scale large repository;
- Android process kill during fetch;
- Android process kill during compression;
- Android process kill immediately before/after archive promotion;
- real SAF folder move/revocation and recovery;
- real notification permission/channel disablement;
- several simultaneous real repository jobs.

---

## Phase 12 — Documentation and release cutover

Status: **automated release preparation complete; manual device gates and merge remain**

Keep only documentation that serves the new product:

- `README.md`
- `docs/ARCHITECTURE.md`
- `docs/BACKUP_FORMAT.md`
- `docs/SCHEDULED_BACKUPS.md`
- `docs/ROADMAP.md`
- `docs/RELEASE.md`

Delete obsolete recovery/Drive/metadata-backup documents.

Version target: `0.3.0` (version code `4`), prepared on the refactor branch and published only after merge to `main`.

The final architecture should reduce to:

```text
Compose UI
    ↓
HomeViewModel
    ↓
WorkManager / MirrorSyncCoordinator
    ↓
MirrorEngine
   ↙       ↘
GitHub     LocalMirrorStore
              ↓
          mirror.tar.gz
```

## Definition of done

The refactor is complete when:

1. one repository maps to one local logical `mirror.tar.gz`;
2. new code never creates `.mirror.zip`;
3. initial backup creates a bare Git mirror;
4. unchanged repositories are checked without recompression;
5. changed repositories fetch only missing Git/LFS data;
6. deleted upstream refs are pruned;
7. every changed update ends as a verified `.tar.gz`;
8. failed updates never damage the previous verified mirror;
9. temporary loose files are cleaned after success, failure, cancellation, and restart;
10. Google Drive code and dependencies are gone;
11. restore/recovery/publish code is gone;
12. release/discussion/wiki backup code is gone;
13. daily and weekly are the only automatic cadences;
14. every running job has an ongoing foreground notification;
15. jobs do not start when required notification visibility is unavailable;
16. onboarding states exact read-only PAT permissions;
17. health distinguishes last check from last actual source change;
18. Room cannot represent multiple current mirrors for one repository;
19. legacy ZIP migration cannot delete the only verified backup;
20. CI build, tests, and lint are green.
