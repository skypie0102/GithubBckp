# GithubBckp

GithubBckp is a small personal Android app that keeps one local backup mirror for each selected GitHub repository.

The app deliberately does less now:

- GitHub is read-only.
- Backups go only to a user-selected local/document-tree folder.
- Each repository has exactly one final backup archive.
- The archive format is `.tar.gz`.
- There is no Google Drive connector.
- There is no restore/publish workflow.
- There is no backup history/retention system.
- Automatic updates are daily or weekly.
- Every actual repository backup runs as foreground work with an ongoing Android notification.

## Backup model

For the first backup of a repository:

1. clone the Git repository over HTTPS;
2. include the working tree and its `.git` directory;
3. compress the repository to `owner--repository.tar.gz`;
4. write that archive into the selected folder;
5. remove the loose temporary clone from app cache.

For later backups:

1. copy and extract the existing `.tar.gz` into app-private temporary storage;
2. fetch new Git objects and refs from GitHub;
3. prune remote refs that were deleted on GitHub;
4. hard-reset the default-branch working tree so changed and deleted files match GitHub;
5. recompress the repository;
6. replace the previous archive;
7. remove all loose temporary files.

Git fetch is incremental: it transfers Git objects that are not already present in the extracted repository. The final `.tar.gz` itself is recreated after every successful update because compressed archives are not safely patchable in place.

## GitHub token permissions

The token screen shows the required permissions before a token can be saved.

For a **fine-grained personal access token**:

- Repository access: every repository you want GithubBckp to back up.
- Repository permissions:
  - **Contents: Read-only**
  - **Metadata: Read-only**
- No write, administration, issues, pull requests, actions, or package permissions are required.

For a **classic personal access token**:

- public repositories only: `public_repo`;
- any private repository: `repo`.

The token is encrypted locally using Android Keystore-backed storage.

## Local storage

Use **Choose folder** in the app and select a writable Android document tree. GithubBckp creates:

```text
<selected folder>/
  GitHub Mirrors/
    owner--repository.tar.gz
```

There is one stable archive name per repository.

## Automatic backups

Automatic updates can be disabled, daily, or weekly. WorkManager schedules periodic checks opportunistically; Android does not guarantee an exact wall-clock execution time.

Actual repository backups require network connectivity and run as foreground data-sync work with an ongoing notification.

## Health dashboard

The dashboard summarizes selected repositories as healthy, needing a first backup, stale, failed, or currently running.

When automatic backups are enabled, a mirror is considered stale after two missed cadence windows.

## Build

Requirements:

- JDK 17+
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0+

Repository gate:

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

The release build uses R8/resource shrinking while keeping JGit intact because aggressive rewriting previously caused runtime failures.

See `docs/ARCHITECTURE.md` for the implementation structure.
