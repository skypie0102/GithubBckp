# GithubBckp

GithubBckp is a personal/internal Android app for keeping one current local Git mirror for each selected GitHub repository.

The product is intentionally narrow:

- connect GitHub with a personal access token;
- choose repositories;
- choose a local Android document-tree folder;
- create one `mirror.tar.gz` per repository;
- update that mirror manually, daily, or weekly;
- show every running repository job in a grouped ongoing notification, with exact progress when measurable;
- show simple mirror health.

There is no Google Drive integration, restore/publish workflow, backup retention/history, source-archive mode, or backup of GitHub-hosted releases/issues/discussions/wiki data.

## What is preserved

Each backup contains a real bare Git mirror:

- branches, tags, and fetched refs;
- commits, trees, and blobs reachable through those refs;
- referenced Git LFS objects, verified by size and SHA-256.

Git history is preserved. If a file disappears from the latest branch state, that deletion is reflected in the mirror while historical blobs remain when still reachable from Git history.

## Backup format

Each repository maps to one logical archive with a human-readable owner/repository name while retaining the immutable GitHub repository ID in the folder name:

```text
<selected folder>/
└── GitHub Backups/
    └── <owner>--<repo>--<github-repository-id>/
        └── <owner>--<repo>.tar.gz
```

Example:

```text
GitHub Backups/
└── skypie0102--intake-edit--1367381284/
    └── skypie0102--intake-edit.tar.gz
```

Older numeric-only folders created by early 0.3.0 test builds are migrated to the readable layout on the next successful mirror check/update without recompressing an unchanged archive.

The archive contains both a human-readable checkout and the full Git mirror:

```text
repository/       # default-branch files you can browse directly
manifest.json
repository.git/   # full Git history/refs/object database
```

Git LFS objects live in `repository.git/lfs/objects/`. LFS paths inside `repository/` stay as pointer files so large LFS payloads are not duplicated inside the same backup.

Updates are transactional. A temporary `<owner>--<repo>.pending.tar.gz` may exist while a replacement is being written and verified, but it is not retained as another backup generation.

See [docs/BACKUP_FORMAT.md](docs/BACKUP_FORMAT.md).

## Incremental updates

For an existing mirror the app first reads `manifest.json` directly from the archive and runs `ls-remote`.

If the remote refs and repository metadata are unchanged, the app records a successful check and does not extract or recompress the archive.

If the repository changed:

1. extract the current archive into app-private temporary storage;
2. fetch missing Git objects;
3. prune refs deleted upstream;
4. download newly referenced Git LFS objects;
5. regenerate the browsable default-branch `repository/` checkout;
6. update the manifest;
7. build and verify a new pending tar.gz;
8. persist and hash the pending file;
9. replace the stable archive;
10. delete loose temporary files.

## GitHub token permissions

Fine-grained personal access token is recommended.

Repository access:

- select every repository you want GithubBckp to mirror.

Repository permissions:

- **Contents: Read-only**
- **Metadata: Read-only**

No GitHub write or administration permission is required.

A classic PAT with the broader `repo` scope can be used as a compatibility fallback when fine-grained token ownership restrictions prevent one token from covering the needed private repositories.

Tokens are stored locally through the Android Keystore-backed secure store and are never embedded in the APK or repository.

## Automatic updates

Automatic updates support:

- Off
- Daily
- Weekly

WorkManager timing is opportunistic rather than an exact alarm.

Scheduled work requires a currently valid GitHub token, repository Git-readability, a configured local backup folder, network availability, and visible notifications. Repositories that lose token/org access are blocked before their repository backup worker starts.

Health becomes stale after two missed cadence windows:

- Daily: 48 hours without a successful check
- Weekly: 14 days without a successful check

A repository that was checked successfully today stays healthy even if its archive has not changed for weeks.

## Build and CI

Requirements:

- JDK 17+
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0+

Repository gate:

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

See [docs/ROADMAP.md](docs/ROADMAP.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and [docs/RELEASE.md](docs/RELEASE.md).
