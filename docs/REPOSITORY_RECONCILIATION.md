# Repository inventory reconciliation

GithubBckp keeps a local repository inventory so users can select repositories for manual and scheduled backup. The inventory must not continue scheduling repositories that have been deleted, transferred out of access, or otherwise stopped appearing in GitHub discovery.

## Availability model

Room schema version 5 adds `RepositoryEntity.isAvailable`.

- Existing rows migrate with `isAvailable = true`.
- A repository returned by the latest successful GitHub discovery is stored with `isAvailable = true`.
- A cached repository omitted from the latest successful discovery is retained but marked `isAvailable = false`.
- Unavailable rows are hidden from the active repository list and excluded from scheduled backups.
- Backup history is not deleted when repository availability changes.

Rows are retained rather than deleted so a repository that becomes accessible again can keep its prior `selectedForBackup` preference and cached identity history.

## Transaction boundary

A refresh first obtains the complete remote repository list from `GithubGateway.listRepositories()`. Only after that call succeeds does Room enter the reconciliation transaction:

1. mark every cached repository unavailable;
2. deduplicate the returned repository IDs;
3. upsert every returned repository as available.

If GitHub discovery fails before step 1, the existing inventory is left unchanged. A valid successful response containing zero repositories therefore intentionally marks every cached repository unavailable.

The ViewModel reads all cached rows before discovery so existing selection preferences are retained when building the upsert set.

## Backup scheduling

`ScheduledBackupWorker` queries only available repositories and then applies `selectedForBackup`.

`RepositoryBackupWorker` also resolves only available repositories. If work was queued while a repository was available but a later successful refresh marks it unavailable before execution, the worker exits successfully without creating a failed backup record. This treats the queued work as obsolete rather than as a retryable backup failure.

A repository that later reappears in discovery is reactivated automatically and keeps its previous selection preference.

## Safety boundary

Availability is based on the most recent **successful full discovery response**. A transient network/API error does not deactivate repositories because reconciliation does not begin until discovery has completed successfully.
