# Scheduled mirror updates

Automatic updates use WorkManager and support only:

- Off
- Daily
- Weekly

The periodic worker is a lightweight controller. It finds selected, currently available repositories and enqueues one unique repository job for each.

## Readiness

Before fan-out, scheduled work requires:

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

## Notifications

Every repository job is foreground work with one ongoing notification. Simultaneous repository notifications share the same Android notification group. The final local commit reports exact bytes-written progress; stages whose total work is not knowable stay indeterminate.

If Android notification permission, app notification settings, or the active-backup notification channel prevents visibility, the repository job is blocked before mirror work starts.

The notification includes a Cancel action tied to WorkManager cancellation.
