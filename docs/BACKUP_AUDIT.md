# Completed backup audit reports

GithubBckp can export a JSON audit report for any completed backup in Recent backups. The report is intentionally a history/export surface: creating it does **not** download or re-verify the remote artifact.

## Format

The current report format is version `3` and uses:

```json
{
  "formatVersion": 3,
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

Every new schema-v4 backup row snapshots these values before backup network work begins:

- numeric GitHub repository ID;
- owner and repository name;
- default branch;
- private/public state.

Audit reports expose those values with `metadataSource: "backup-time-snapshot"`. A later repository rename, default-branch change, or privacy change therefore does not rewrite the historical audit identity.

Rows migrated from database schema v1-v3 cannot be assigned historical values safely. Their snapshot columns remain null. Audit export falls back to the current local repository cache when available and reports `metadataSource: "current-local-repository-cache"`; otherwise it uses `metadataSource: "unavailable"`. Both legacy cases are also called out in the report's `limitations` array.

### Backup

The backup section records the persisted backup ID, backup type, origin, terminal status, start/completion timestamps, completeness warning, and error field.

Database schema v6 records `origin` before backup network work begins. New WorkManager requests use:

- `MANUAL` — explicitly queued from the app's manual backup action;
- `SCHEDULED` — queued by the periodic automatic-backup controller.

Rows migrated from schema v1-v5, and one-time work that was already pending before origin metadata was introduced, retain `origin: null`. The audit report describes that case as unknown rather than guessing whether a historical attempt was manual or scheduled.

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

The suggested filename uses the backup-time repository name for schema-v4+ rows. Legacy rows use the current known repository name when available, otherwise the immutable numeric repository ID, plus the backup history ID.
