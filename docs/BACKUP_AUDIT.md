# Completed backup audit reports

GithubBckp can export a JSON audit report for a completed backup activity row. Exporting the JSON is a history/export operation and does **not** itself access the remote artifact. Separately, eligible completed current mirrors expose **Re-verify stored mirror**, which performs a fresh read-only verification and records the latest result in the same history row.

## Format

The current report format is version `6`:

```json
{
  "formatVersion": 6,
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

## Repository identity

Newer backup rows snapshot the repository's numeric GitHub ID, owner/name, default branch, and private/public state before backup network work begins. Audit reports expose those values with `metadataSource: "backup-time-snapshot"` so a later rename or settings change does not rewrite history.

Older migrated rows may lack that immutable snapshot. In that case export can use the current local repository cache and reports `metadataSource: "current-local-repository-cache"`, or `"unavailable"` if no safe display metadata exists. The limitation is stated explicitly rather than inventing historical identity.

## Backup activity

The backup section records the persisted row ID, backup type, origin, scheduled-run correlation ID, terminal status, start/completion timestamps, completeness warning, and error field.

Current GithubBckp versions create only `GIT_MIRROR` backups. `SOURCE_ARCHIVE` can still appear in exported history for rows created by older versions; format version 6 adds an explicit limitation when a row describes such a legacy backup type.

Origins are:

- `MANUAL` — explicitly queued from the app;
- `SCHEDULED` — queued by the periodic automatic-backup controller;
- `null` — legacy history where the app cannot safely infer the origin.

Scheduled rows can include `scheduledRunId`, allowing child repository work from one controller execution to be correlated. WorkManager unique-work `KEEP` semantics mean a controller can request a repository while older work for that repository is still active; in that case the newer controller run does not necessarily create a second child row.

## Storage state

The storage section records provider, remote object identity/name/size, persisted MD5, and `remoteDeletedAtEpochMs` when the history row no longer owns a current remote object.

The derived `remoteState` is one of:

- `PRESENT_AT_LAST_VERIFICATION` — persisted history still points at a remote object that was current when last verified;
- `REMOTE_OBJECT_REMOVED` — the row's remote object was later replaced/removed or, for legacy history, deleted by an older retention policy;
- `UNKNOWN` — history lacks enough remote identity data.

The neutral `REMOTE_OBJECT_REMOVED` name is intentional. The same historical timestamp field can represent both the current one-mirror replacement model and deletion performed by older app versions, so export does not claim a reason it cannot prove.

A historical row whose remote object has been removed remains auditable but cannot be freshly re-verified.

## Original integrity verification

`integrityVerification.artifactSha256` is the SHA-256 recorded for the locally produced artifact before upload. `providerMd5` is the persisted destination checksum used by the provider path.

`PERSISTED_VERIFIED_HISTORY` means the completed row contains the integrity result captured when that backup was created and stored. It is deliberately **not** a claim that the artifact was freshly read when the JSON file was exported.

## Latest on-demand re-verification

For an eligible completed current mirror, **Re-verify stored mirror**:

1. resolves the provider that originally stored the object rather than using the currently selected destination;
2. reads/downloads the complete remote object into temporary app-cache storage without modifying it;
3. recomputes byte size, SHA-256, and MD5 locally and compares all three with the persisted verified metadata;
4. runs the same safe local Git-mirror/module validator used by restore;
5. deletes the temporary local copy;
6. records `VERIFIED` or `FAILED`, a timestamp, and a bounded detail string in the history row.

A failed re-verification does not rewrite the original backup status from `COMPLETED` to `FAILED`. Creation-time completion and later storage readability are separate facts.

`latestReverification.state` is `NOT_RUN` when no result exists and `RECORDED` after either a success or failure has been persisted.

## Provider behavior

Document-tree re-verification opens the persisted document URI and streams the full object to app cache. Google Drive re-verification downloads the file bytes through the Drive API using the persisted file ID. Both paths use the same local digest comparison; Drive metadata alone is not considered sufficient proof for on-demand re-verification.

If authorization or persisted document access is no longer valid, re-verification records a failed result and leaves the remote object untouched.

## Export behavior

The UI uses Android's system **Create Document** flow with `application/json`. The report is written only to the URI selected by the user and does not require broad storage permission.

The suggested filename uses backup-time repository identity when available. Legacy rows fall back to current known repository name or the immutable numeric repository ID.

Exporting the report after a re-verification includes the latest recorded result, but export itself never causes another network or storage read.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the one-current-mirror storage model.
