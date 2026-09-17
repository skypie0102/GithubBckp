# Scheduled mirror updates

Automatic backups are controlled by WorkManager and run daily or weekly. New scheduled work is mirror-only: each selected repository has one current logical Git mirror, and scheduled runs update that mirror rather than creating intentional generations.

## Execution model

The periodic worker is a lightweight controller. It reads the currently selected, available repositories and enqueues one repository mirror-update job per repository. WorkManager periodic execution is opportunistic rather than an exact clock alarm.

Scheduled repository jobs require:

- unmetered network connectivity;
- battery not low;
- storage not low.

Before fan-out, the controller re-checks local prerequisites. GitHub must still be connected. If Google Drive is selected, Drive must still be authorized. If a document-tree destination is selected, that tree must still be configured.

A missing prerequisite does not create a wave of repository failures. The controller finishes that cycle cleanly and checks again on the next scheduled cycle because reconnecting GitHub, Drive, or a document tree generally requires user action.

## One-current-mirror rule

Repository jobs always use `GIT_MIRROR`. The old scheduled backup-format preference is removed when settings are saved and is no longer exposed in the UI.

The mirror archive uses a stable repository-specific name. Storage providers are given the previously verified remote object when available:

- Google Drive updates that file through a resumable update session;
- document-tree storage overwrites the current document and migrates an old timestamped filename when possible.

After the replacement is uploaded and verified, older distinct artifacts left by previous versions are removed on a best-effort basis. Their history rows remain available but are marked as no longer owning a current remote artifact. A cleanup failure is recorded as a warning and does not invalidate the newly verified mirror.

## Last scheduled-run status

Each completed controller cycle persists a timestamp and one of these outcomes:

- `QUEUED` — repository jobs were enqueued successfully. This does **not** mean every repository update has completed yet.
- `SKIPPED_NO_REPOSITORIES` — there were no selected, currently available repositories to enqueue.
- `SKIPPED_NOT_READY` — local prerequisites were not ready. The block reason distinguishes GitHub disconnected, Drive disconnected, and missing document-tree configuration.

The Automatic backups card observes this state while the app is open. When a queued run has a correlation ID, the UI also observes its child backup rows and reports progress.

## Repository availability and health

Repository selection is reconciled after each successful GitHub refresh. Cached repositories that disappear from the returned inventory are retained for history but marked unavailable and excluded from scheduled fan-out. If a repository later reappears, its previous selection is restored.

Repository health is based on the latest attempt and latest current verified backup for each selected repository, not on the limited recent-activity list.

When automatic backups are enabled, a verified backup is stale after two cadence windows: 48 hours for daily schedules and 14 days for weekly schedules. The extra window allows for WorkManager's opportunistic execution and temporary device/network constraints.

A failed attempt after the latest verified backup takes priority over staleness. A later successful update clears the earlier failure state.

## Notifications

A repository worker emits a failure notification only after `BackupCoordinator` has persisted the attempt as `FAILED`. Repeated failure alerts for the same repository are rate-limited to one notification per six hours and use a stable per-repository ID.

When automatic backups are enabled, `BackupScheduler` also maintains a lightweight local health-check worker. It runs daily and groups newly overdue repositories into one notification. A repository that remains overdue does not generate a fresh alert every day; it can alert again after recovering and later becoming overdue again.

Disabling automatic backups cancels the scheduled controller and health-check worker and clears overdue-notification state.

On Android 13 and newer the app requests `POST_NOTIFICATIONS` once. If permission is unavailable, backup execution and health evaluation continue normally; only notifications are suppressed.
