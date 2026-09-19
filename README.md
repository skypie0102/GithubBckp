# GithubBckp

GithubBckp is a personal/internal Android app for keeping one current local Git mirror for each selected GitHub repository.

The product is intentionally narrow:

- connect GitHub with a personal access token;
- choose the backup repository set on a dedicated repository-selection page;
- choose a local Android document-tree folder;
- create one full Git mirror archive per repository;
- keep one separate backup of that repository's latest published GitHub Release;
- update/check both manually, daily, or weekly;
- process repository backup/update jobs strictly one at a time;
- show one ongoing foreground notification for the currently active repository job, with exact progress when measurable;
- show simple mirror health.

There is no Google Drive integration, restore/publish workflow, backup retention/history, issue/discussion/wiki backup, or multiple release generations.

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
        ├── <owner>--<repo>.tar.gz
        └── latest-release/
            └── <owner>--<repo>--<tag>--release.tar.gz
```

Example:

```text
GitHub Backups/
└── skypie0102--intake-edit--1367381284/
    ├── skypie0102--intake-edit.tar.gz
    └── latest-release/
        └── skypie0102--intake-edit--v1.2.3--release.tar.gz
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

## Latest release backup

After a successful Git mirror check/update, the same foreground repository job checks GitHub's latest **published, non-prerelease** Release.

When the latest release changed, the app stores one verified release bundle containing:

```text
release.json
source/source.tar.gz
assets/
    ...every uploaded release asset...
```

The bundle filename includes the release tag. Release ID + GitHub `updated_at` are compared before download, so an unchanged latest release is not downloaded or repackaged. When a newer release becomes latest, its verified bundle replaces the previous one. If GitHub currently reports no latest published release, an older local release bundle is retained rather than deleted.

The same fine-grained **Contents: Read-only** repository permission covers the latest-release metadata, source tarball, and release-asset downloads.

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

## Repository selection and manual updates

Repository selection is separate from backup/update execution.

The **Backup repositories** page contains the repository checkboxes, search, refresh, Select all, and Clear controls. Changing a checkbox only changes whether that repository belongs to the backup set; it does not start network or archive work.

Newly discovered repositories default to unselected. Existing choices are preserved.

On Home:

- the persistent bottom action queues **only** selected repositories currently classified as **Update available**;
- healthy selected repositories are not queued by that button;
- repositories that do not yet have a local mirror use a separate secondary **Back up ... without a mirror** action.

All repository jobs—manual and scheduled—share one WorkManager queue and execute sequentially. A repository that exhausts its retry budget records its own failure and the queue advances to the next repository.

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
