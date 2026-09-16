# Completed backup audit reports

GithubBckp can export a JSON audit report for any completed backup in Recent backups. The report is intentionally a history/export surface: creating it does **not** download or re-verify the remote artifact.

## Format

The current report format is version `1` and uses:

```json
{
  "formatVersion": 1,
  "reportType": "github-backup-artifact-audit",
  "generatedAtEpochMs": 0,
  "repository": {},
  "backup": {},
  "storage": {},
  "integrityVerification": {},
  "limitations": []
}
```

### Repository

The immutable repository identifier available in backup history is the numeric GitHub repository ID. Owner/name, default branch, and privacy values are added when that repository is still present in the app's current repository cache. Those display fields are explicitly labeled as current-cache metadata and must not be interpreted as a backup-time snapshot.

### Backup

The backup section records the persisted backup ID, backup type, terminal status, start/completion timestamps, completeness warning, and error field.

The app currently exposes the export action only for `COMPLETED` backups.

### Storage and retention

The storage section records the provider, remote file identity/name/size, provider MD5 when available, and retention deletion timestamp. The derived `remoteState` is one of:

- `PRESENT_AT_LAST_VERIFICATION` — persisted history says the artifact existed when the backup was verified;
- `DELETED_BY_RETENTION` — the app later recorded successful provider-aware retention deletion;
- `UNKNOWN` — history does not contain enough remote identity data.

A pruned backup remains auditable even though its remote artifact no longer exists.

### Integrity verification

`artifactSha256` is the SHA-256 recorded for the locally produced artifact before upload. `providerMd5` is the persisted destination checksum when the provider exposes/uses MD5 verification.

`PERSISTED_VERIFIED_HISTORY` means the completed backup row contains its artifact SHA-256. It is deliberately **not** a claim that the artifact was re-downloaded when this JSON file was exported.

## Export behavior

The UI uses Android's system **Create Document** flow with `application/json`. The report is written only to the URI selected by the user and does not require broad storage permission.

The suggested filename includes the current known repository name when available, otherwise the immutable numeric repository ID, plus the backup history ID.
