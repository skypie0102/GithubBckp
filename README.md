# GithubBckp

Android app for backing up repositories from a GitHub account to cloud storage, starting with Google Drive.

## Status

This repository contains the initial Android scaffold. Authentication and remote backup operations are intentionally not wired to credentials yet.

### Included

- Kotlin + Jetpack Compose application shell
- Android Gradle Plugin 9.4 / compileSdk 37
- Hilt dependency injection
- Room entities/DAO for repositories and backup history
- WorkManager scheduling constraints and per-repository worker boundary
- `GithubGateway`, `BackupEngine`, and `StorageProvider` interfaces
- Google Drive provider placeholder with security notes
- Architecture notes in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

## Product direction

1. Connect a GitHub account.
2. Discover owned/accessible repositories.
3. Connect Google Drive with a narrow `drive.file` scope.
4. Back up one selected repository as a source TAR archive.
5. SHA-256 the artifact, upload with a resumable Drive session, verify the result, and persist history.
6. Add scheduled multi-repository backups.
7. Add Git mirror backups for full history, then LFS/wiki/releases/issues/PR metadata as separate modules.

A GitHub source archive is a snapshot, not a complete Git-history backup. The long-term `GIT_MIRROR` mode is the recoverable repository backup format.

## Build

Requirements:

- JDK 17+
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0+

```bash
./gradlew :app:assembleDebug
```

Open the project in a current stable Android Studio release and let Gradle sync.

## Secrets

Do **not** commit OAuth client secrets, access tokens, refresh tokens, signing keys, or generated `local.properties` files. Local secret configuration belongs outside version control, and runtime tokens should use Keystore-backed storage.

## Next implementation slice

The first end-to-end milestone should be:

```text
GitHub OAuth
  -> list repositories
  -> choose one repository
  -> download TAR archive
  -> calculate SHA-256
  -> Google Drive resumable upload
  -> verify remote artifact
  -> record COMPLETED/FAILED in Room
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for component boundaries.
