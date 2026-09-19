# Scheduled mirror updates

Automatic updates use WorkManager and support only:

- Off
- Daily
- Weekly

The periodic worker is a lightweight controller. It finds selected, currently available repositories and appends their repository jobs to the same global sequential WorkManager queue used by manual work.

## Readiness

Before queueing repository work, scheduled work requires:

- the stored GitHub token to still validate;
- each selected repository to remain readable over Git;
- a configured local document-tree backup folder;
- active-job notifications to be visible.

If any prerequisite is missing, the scheduled cycle records a blocked/skipped status and does not start repository backup work.

Repository work also requires network connectivity. Scheduled repository jobs currently retain conservative WorkManager battery/storage/network constraints.

## One mirror per repository

Every repository job updates the same logical artifact:

```text
GitHub Backups/<owner>--<repo>--<repository-id>/<owner>--<repo>.tar.gz
```

No scheduled run intentionally creates a historical generation.

## Unchanged repositories

The worker reads `manifest.json` directly from the current archive and compares its refs digest with `ls-remote`.

When the repository is unchanged:

- the check is recorded as successful;
- `lastCheckedAt` advances;
- `lastChangedAt` does not;
- the archive is not extracted;
- the archive is not recompressed.

## Health

A selected mirror is stale after two cadence windows without a successful check:

- Daily: 48 hours
- Weekly: 14 days

This tolerance accommodates WorkManager's opportunistic execution.

A changed archive can therefore be old while the repository remains healthy if recent scheduled checks prove that the remote repository has not changed.

## Sequential execution

Repository backup/update work is serialized globally. Only one `RepositoryBackupWorker` runs at a time, including when a scheduled cycle contains many repositories or overlaps with a manual request.

Each repository keeps its existing retry budget. If one repository ultimately fails, that failure is persisted and notified, but WorkManager is allowed to continue with the next repository instead of blocking the remaining queue.

## Notifications

Every active repository job is foreground work with one ongoing notification. Because repository jobs are serialized, the device sees one active repository notification at a time instead of a burst of simultaneous backup notifications. The final local commit reports exact bytes-written progress; stages whose total work is not knowable stay indeterminate.

If Android notification permission, app notification settings, or the active-backup notification channel prevents visibility, the repository job is blocked before mirror work starts.

The notification includes a Cancel action tied to WorkManager cancellation.
