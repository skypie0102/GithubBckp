# Architecture

GithubBckp now has one job: maintain one local `.tar.gz` Git mirror for each selected repository.

## Flow

```text
GitHub PAT
   |
GithubRestGateway -> repository inventory
   |
HomeViewModel / HomeScreen
   |
BackupScheduler
   |
RepositoryBackupWorker
   |  foreground notification
   v
MirrorBackupCoordinator
   |
MirrorBackupEngine
   |-- first run: JGit clone
   |-- later run: extract -> JGit fetch/prune -> hard reset
   |
TarGzArchive
   |
LocalArchiveStore
   |
Android document tree / GitHub Mirrors/*.tar.gz
```

## State

Room stores only two kinds of records:

- `RepositoryEntity`: current GitHub repository inventory and selection state;
- `MirrorEntity`: one current status row per repository.

There is no append-only backup history table and no retention policy.

The database uses a new filename, `github-backup-simple.db`, rather than migrating the previous multi-feature schema.

## Git mirror semantics

The stored archive contains a normal Git working repository, including its `.git` directory.

Initial backup uses a normal JGit clone with all branches fetched.

An update extracts the existing archive and performs an incremental fetch using explicit branch/tag refspecs with deleted-ref pruning. The local default branch is then force-checked out and hard-reset to `origin/<default branch>`, and untracked temporary files are cleaned before recompression.

This means Git object transfer is incremental. The compressed archive is still fully recreated each successful run.

## Local archive replacement

`LocalArchiveStore` uses Android's Storage Access Framework. The final stable name is:

```text
owner--repository.tar.gz
```

A replacement is first written to a sibling pending document and size-checked before the previous stable document is retired.

The final product state is one archive per repository; pending documents are implementation staging, not retained backup versions.

## Scheduling

Manual and scheduled runs both use `RepositoryBackupWorker`.

Every repository worker enters WorkManager foreground/data-sync mode and posts an ongoing notification. Manual runs require connectivity. Periodic runs additionally use battery-not-low and storage-not-low constraints.

The only schedule choices are daily and weekly.

## Removed subsystems

The refactor removes:

- Google Drive authentication/storage;
- restore-to-GitHub and imported-mirror restore;
- release/wiki/discussion metadata backup;
- Git LFS-specific download/upload machinery;
- recovery transactions and drills;
- audit exports and re-verification;
- backup-format abstractions;
- retention/version-history logic;
- historical backup activity infrastructure;
- failure/overdue notification subsystems separate from the health dashboard.

The remaining architecture is intentionally centered on Git + local storage + scheduling.
