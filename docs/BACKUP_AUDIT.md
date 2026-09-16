# Completed backup audit reports

GithubBckp can export a JSON audit report for any completed backup in Recent backups. Exporting the JSON is a history/export operation and does **not** itself access the remote artifact. Separately, eligible completed backups expose **Re-verify stored backup**, which performs a fresh read-only remote verification and records the latest result in the same history row.

## Format

The current report format is version `5` and uses:

```json
{
  "formatVersion": 5,
  "reportType": "github-backup-artifact-audit",
  "generatedAtEpochMs": 0,
  "repository": {},
  "backup": {},
  "storage": {},
  "integrityVerification": {},
  "latestReverification": {},
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

The backup section records the persisted backup ID, backup type, origin, scheduled-run correlation ID, terminal status, start/completion timestamps, completeness warning, and error field.

Database schema v6 records `origin` before backup network work begins. New WorkManager requests use:

- `MANUAL` — explicitly queued from the app's manual backup action;
- `SCHEDULED` — queued by the periodic automatic-backup controller.

Rows migrated from schema v1-v5, and one-time work that was already pending before origin metadata was introduced, retain `origin: null`. The audit report describes that case as unknown rather than guessing whether a historical attempt was manual or scheduled.

Database schema v7 adds nullable `scheduledRunId`. Every new periodic controller execution generates one UUID and passes it to each scheduled repository WorkRequest it creates. Backup rows that execute from that fan-out persist the same ID, allowing multiple repository outcomes from one automatic run to be correlated in exported audit reports. Manual backups intentionally keep `scheduledRunId: null`.

Rows migrated from schema v1-v6, and scheduled one-time work already pending before correlation was introduced, retain `scheduledRunId: null`. The report marks a scheduled row with no run ID as legacy rather than fabricating an identifier.

Scheduled repository work continues to use WorkManager unique-work `KEEP` semantics. If a controller run requests a repository/backup-format pair while older unique work for that same pair is still active, WorkManager keeps the older work and the newer controller run does not create a second child backup row. A run ID therefore correlates child work that actually executes; it is not a claim that every requested fan-out entry produced a distinct backup attempt.

The app exposes audit export only for `COMPLETED` backups.

### Storage and retention

The storage section records the provider, remote file identity/name/size, provider MD5 when available, and retention deletion timestamp. The derived `remoteState` is one of:

- `PRESENT_AT_LAST_VERIFICATION` — persisted history says the artifact existed when the backup was verified;
- `DELETED_BY_RETENTION` — the app later recorded successful provider-aware retention deletion;
- `UNKNOWN` — history does not contain enough remote identity data.

A pruned backup remains auditable even though its remote artifact no longer exists. Pruned rows do not expose re-verification.

### Original integrity verification

`integrityVerification.artifactSha256` is the SHA-256 recorded for the locally produced artifact before upload. `providerMd5` is the persisted destination checksum when the provider exposes/uses MD5 verification.

`PERSISTED_VERIFIED_HISTORY` means the completed backup row contains the integrity result captured when the backup was created. It is deliberately **not** a claim that the artifact was freshly read when the JSON file was exported.

### Latest on-demand re-verification

Database schema v9 adds nullable `lastReverifiedAtEpochMs`, `lastReverificationStatus`, and `lastReverificationMessage` fields to each backup row. Existing rows migrate with all three fields null.

For an eligible completed, non-pruned backup, **Re-verify stored backup**:

1. resolves the provider that originally stored the artifact rather than using the currently selected destination;
2. downloads/reads the complete remote artifact into a temporary app-cache file without modifying the remote object;
3. recomputes byte size, SHA-256, and MD5 locally and compares all three with the persisted verified metadata;
4. for Git-mirror backups, runs the same safe local mirror/module validator used by restore, including Git ref-tip object checks plus applicable LFS, wiki, release, and discussion validation;
5. deletes the temporary local copy;
6. records `VERIFIED` or `FAILED`, a timestamp, and a bounded human-readable detail string in the backup-history row.

A failed re-verification does **not** rewrite the original backup status from `COMPLETED` to `FAILED`. The original completion record answers whether creation/upload verification succeeded at that time; the re-verification fields answer whether the stored artifact could be freshly read and validated later.

`latestReverification.state` is `NOT_RUN` when no result exists and `RECORDED` once either a success or failure has been persisted.

## Provider behavior

Document-tree re-verification opens the persisted document URI and streams the full object to app cache. Google Drive re-verification downloads the file bytes through the Drive API using the persisted file ID. Both paths then use the same local digest comparison; Drive metadata alone is not treated as sufficient for this operation.

If authorization or persisted document access is no longer valid, re-verification records a failed result and leaves the remote object untouched.

## Export behavior

The UI uses Android's system **Create Document** flow with `application/json`. The report is written only to the URI selected by the user and does not require broad storage permission.

The suggested filename uses the backup-time repository name for schema-v4+ rows. Legacy rows use the current known repository name when available, otherwise the immutable numeric repository ID, plus the backup history ID.

Exporting the report after a re-verification includes the latest recorded result, but export itself never causes another network/storage read.

## Relation to the personal reliability roadmap

Roadmap issue #35 implements the fresh-proof path that was intentionally separate from backup-health classification and audit export. It remains read-only with respect to the stored artifact and reuses the existing restore validator for Git mirrors instead of maintaining a second mirror-validation implementation.

See [`ROADMAP.md`](ROADMAP.md).
