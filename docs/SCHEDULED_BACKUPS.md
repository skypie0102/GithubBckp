# Scheduled backups

Automatic backups are controlled by WorkManager and can run daily or weekly using either source snapshots or Git mirrors.

## Execution model

The periodic worker is a lightweight controller. It reads the currently selected, available repositories and enqueues one repository backup job per repository. WorkManager periodic execution is opportunistic rather than an exact clock alarm.

Scheduled repository jobs require:

- unmetered network connectivity;
- battery not low;
- storage not low.

Before the controller fans out repository jobs, it also re-checks locally known app prerequisites. GitHub must still be connected. If Google Drive is selected, Drive must still be authorized. If a document-tree destination is selected, that tree must still be configured.

A missing prerequisite does not create a wave of repository failures. The controller finishes that cycle cleanly and checks again on the next scheduled cycle, because reconnecting GitHub, Drive, or a document tree generally requires user action.

## Last scheduled-run status

Each completed controller cycle persists a timestamp and one of these outcomes in the schedule preferences:

- `QUEUED` — repository jobs were enqueued successfully. This does **not** mean the repository backups have completed yet.
- `SKIPPED_NO_REPOSITORIES` — there were no selected, currently available repositories to enqueue.
- `SKIPPED_NOT_READY` — local prerequisites were not ready. The persisted block reason distinguishes GitHub disconnected, Drive disconnected, and missing document-tree configuration.

The Automatic backups card observes this state while the app is open and displays the last controller timestamp and outcome. When a queued run has a correlation ID, the UI also observes its child backup rows and shows completed/failed/cancelled/active/not-started-or-deduplicated progress.

If enqueue itself throws, the controller does not write a misleading `QUEUED` result.

## Repository availability

Repository selection is reconciled after each successful full GitHub refresh. Cached repositories that disappear from the returned inventory are retained for history but marked unavailable; they are excluded from scheduled fan-out. If a repository later reappears, its previous selection is restored.

## Backup-health interpretation

Repository health is based on the latest backup attempt and the latest non-pruned verified backup for each currently selected repository, not on the limited Recent backups list.

When automatic backups are enabled, a verified backup is stale after two cadence windows (48 hours for daily schedules and 14 days for weekly schedules). The extra window intentionally allows for WorkManager's opportunistic execution and temporary device/network constraints before declaring the repository overdue.

A failed attempt after the latest verified backup is higher priority than staleness. A later successful backup clears the earlier failure. Completeness warnings remain visible without reclassifying a verified artifact as missing.

## Failure notifications

A repository worker emits a failure notification only after `BackupCoordinator` has persisted the backup row as `FAILED`. Before notifying, the worker resolves the repository again and requires it to still be available and selected for backup.

Repeated failure alerts for the same repository and backup format are rate-limited to one notification per six hours. The notification uses a stable per-repository ID so later failure state replaces the existing alert instead of building an unbounded stack. Tapping the notification opens the app, where the Backup health card and Recent backups provide the actionable state.

## Overdue notifications

When automatic backups are enabled, `BackupScheduler` also maintains a lightweight local `BackupHealthCheckWorker`. It runs every 24 hours, with an initial one-hour delay, and does not require network access because it evaluates persisted repository/backup history.

The overdue check:

1. reads currently available repositories and keeps only those still selected for backup;
2. reads the latest attempt/latest verified health history from Room;
3. applies the same two-cadence freshness window used by the Home health model;
4. for repositories that have never completed a backup, uses the schedule enable timestamp as the start of the same grace window;
5. groups overdue repositories into one notification.

The notifier persists the current overdue repository-ID set. A repository that remains overdue does not create a new alert every day. A new alert is emitted only when at least one repository newly enters the overdue set. Once a repository becomes healthy it leaves that set, so a later independent overdue episode can notify again.

Disabling automatic backups cancels both the scheduled controller and the local health-check worker and clears overdue-notification state.

## Android notification permission

On Android 13 and newer the app requests `POST_NOTIFICATIONS` once from the main activity. If notification permission is unavailable or notifications are disabled at the OS level, backup execution and health evaluation continue normally; only the alert surface is suppressed. The permission can be changed later in Android's app notification settings.

See [`ROADMAP.md`](ROADMAP.md).
