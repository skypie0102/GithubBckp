# GitHub release backup and recovery

Git mirror backups preserve GitHub release metadata and binary release assets inside the same `.mirror.zip` artifact, and the target-bound recovery transaction can later recreate/reconcile those releases after Git refs exist.

## Discovery and metadata

`GithubReleaseBackupService` lists releases through GitHub's REST API with 100-item pagination. Assets are listed separately for every release so a release with more than one API page of assets is still complete.

The versioned manifest is stored at:

```text
github-backup/releases/manifest.json
```

For each release it records the source release ID, tag name, target commitish, name, body, draft/prerelease flags, immutable flag, source timestamps, and a list of assets. Releases with zero assets are still represented in the manifest.

## Asset bytes and integrity

Binary assets are downloaded through GitHub's release-asset API using `Accept: application/octet-stream`. The API may stream the bytes directly or redirect to object storage. The GitHub bearer token and API-version header are attached only while the request host is `api.github.com`; redirected object-storage hosts receive no GitHub bearer token.

Assets are stored below:

```text
github-backup/releases/assets/release-<source release id>/<source asset id>-<safe filename>
```

Every downloaded file must match the size reported by GitHub. The app also computes SHA-256 for every asset and records it in the manifest. When GitHub supplies the release asset `digest` as `sha256:<hex>`, the computed digest must match that independent server value or the backup fails.

If any release page, asset listing, asset download, size check, or supported GitHub digest check fails, the whole Git mirror backup fails instead of silently producing an incomplete successful release backup.

## Local restore verification

After a mirror ZIP is imported, release data is revalidated before the restored copy is retained:

1. The manifest version must be supported.
2. Every release must have a tag name.
3. Every asset path must stay inside the bundled releases directory.
4. Every asset file must exist and match its recorded size.
5. Every asset must reproduce its recorded SHA-256.

This makes release assets part of local disaster-recovery validation rather than unverified opaque files.

## GitHub publication

Release publication is a post-Git recovery phase. The recovery transaction must first reach `GIT_PUBLISHED`, which guarantees the release tags/commits are already available in the bound target repository.

On the first recovery attempt, the target's release surface must be empty before any LFS/Git write starts. After Git publication, `GithubReleaseRestoreService` processes the bundled manifest:

- a missing release is created from the backed-up tag, target commitish, name, body, draft flag, and prerelease flag;
- an existing release with the same tag can be reused only when its name/body/draft/prerelease metadata matches;
- any unexpected target release tag is treated as conflicting state and fails recovery.

Creation deliberately sets `make_latest=false`, so restoring older releases does not unexpectedly change the repository's current latest-release selection.

## Idempotent asset recovery

Asset publication is designed for interrupted/retried recovery:

1. If an uploaded asset with the expected name exists, its size and SHA-256 must match before it is reused.
2. If GitHub normalized/renamed the filename, one unique uploaded asset with the expected size and SHA-256 digest can be reconciled to the bundled asset.
3. Multiple possible digest matches are ambiguous and fail safely.
4. A GitHub `starter` asset left by a failed/interrupted upload is deleted and the upload is retried.
5. A conflicting fully uploaded asset is never silently deleted or overwritten.
6. After all expected assets are accounted for, any unexpected leftover target asset fails recovery.

When a reusable remote asset exposes a `sha256:` digest, that digest is compared directly. When no digest is available, the asset is downloaded again and SHA-256 is recomputed before the app accepts it as restored.

After every bundled release and asset has been reconciled, `RecoveryTransactionStore` advances the restore to `RELEASES_PUBLISHED` and persists release/asset counts. A crash before that phase is written simply resumes the reconciliation process on the same repository ID.

## Personal-use recovery boundary

The backup preserves source timestamps and immutable-state metadata for audit/completeness reporting, but exact server-side release semantics are intentionally outside the personal-use roadmap. The app does not restore:

- the original latest-release selection;
- original creation/publication timestamps;
- immutable release state.

These are accepted completeness limitations rather than pending feature work. Release metadata and binary assets remain preserved, verified, and replayed through the transaction-backed recovery flow.

See [`ROADMAP.md`](ROADMAP.md) for the current scope decisions.
