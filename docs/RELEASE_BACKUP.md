# GitHub release backup

Git mirror backups preserve GitHub release metadata and binary release assets inside the same `.mirror.zip` artifact.

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

## GitHub publication boundary

Automatic recreation of releases and upload of their assets to a target GitHub repository is intentionally not implemented in this slice. Git refs need to exist before releases can be faithfully recreated, but a release-publication failure after the Git push would leave a non-empty partially recovered target. The current recovery safety model deliberately refuses non-empty targets, so retry/resume semantics must be designed before release publication is enabled.

Backups that contain release metadata therefore persist a non-fatal completeness warning. Main Git refs and Git LFS recovery remain supported, while the release manifest/assets stay preserved and locally verified for a future idempotent publication phase.
