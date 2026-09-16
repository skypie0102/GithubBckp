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

The personal-use reliability roadmap adds repository-level health on top of the scheduling machinery. Health is based on the latest backup attempt and the latest non-pruned verified backup for each currently selected repository, not on the limited Recent backups list.

When automatic backups are enabled, the initial health model treats a verified backup as stale after two cadence windows (48 hours for daily schedules and 14 days for weekly schedules). The extra window intentionally allows for WorkManager's opportunistic execution and temporary device/network constraints before declaring the repository overdue.

A failed attempt after the latest verified backup is higher priority than staleness. A later successful backup clears the earlier failure. Completeness warnings remain visible without reclassifying a verified artifact as missing.

Roadmap issue #34 will add grouped/rate-limited notifications for failed attempts and overdue selected repositories so the app does not need to be opened regularly to detect backup trouble.

See [`ROADMAP.md`](ROADMAP.md).
