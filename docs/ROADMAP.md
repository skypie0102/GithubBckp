# Personal-use roadmap

GithubBckp is a personal/internal disaster-recovery tool. The roadmap optimizes for **backup reliability, recoverability, and operator confidence**, not broad GitHub feature parity or public distribution.

## Current product boundary

The supported recovery promise is intentionally narrow:

- source snapshots and full Git mirrors can be backed up to the configured personal destination;
- Git mirrors preserve main Git history/refs, Git LFS, initialized wiki history, releases/assets, and issue/PR discussion metadata;
- imported mirrors are validated locally before they are retained;
- main Git, Git LFS, and releases/assets can be recovered to a new or provably empty GitHub repository;
- wiki and discussion data are preservation/local-validation surfaces rather than automated GitHub publication surfaces;
- recovery never force-pushes or destructively rewrites an arbitrary non-empty repository;
- GitHub authentication is supplied at runtime with a locally encrypted personal access token; no GitHub OAuth application identity is built into the APK.

## Completed reliability work

### P0 — Backup health dashboard — #33 — complete

The Home screen classifies every currently selected, available repository as protected, warning, failed-after-last-success, stale, or never backed up. Health uses the latest attempt and latest non-pruned verified backup rather than the fixed-size Recent backups list. Automatic-backup staleness uses two cadence windows to account for WorkManager's opportunistic execution.

### P0 — Failure and overdue-backup notifications — #34 — complete

The app requests notification permission on supported Android versions and surfaces backup trouble without requiring the app to remain open.

Implemented behavior:

- a failed backup can produce a notification only after the failure is durably recorded;
- repeated failures for the same repository/backup format are rate-limited to one alert per six hours;
- automatic-backup health is checked locally every 24 hours while scheduling is enabled;
- repositories become overdue after two cadence windows; repositories with no successful backup receive the same grace window starting when scheduling is enabled;
- overdue repositories are grouped into one notification and are re-notified only when a repository newly enters the overdue set;
- unavailable and unselected repositories are excluded;
- disabling automatic backups clears overdue-notification state;
- tapping an alert opens the app's Home/backup-health surface.

### P1 — On-demand backup re-verification — #35 — complete

Completed, non-pruned backups with persisted provider/checksum metadata can be freshly re-verified from Recent backups.

Implemented behavior:

- the app reads the artifact through the provider that originally stored it rather than the currently selected destination;
- document-tree and Google Drive artifacts are fully streamed into temporary app-cache storage;
- byte size, SHA-256, and MD5 are recomputed locally and compared with the persisted verified backup metadata;
- Git mirrors additionally run through the existing safe local mirror/module validator without publishing anything to GitHub;
- the temporary downloaded artifact is deleted after the check;
- the latest `VERIFIED` or `FAILED` result, timestamp, and detail are persisted in schema v9 and shown in Recent backups;
- exported backup-audit JSON includes the latest recorded re-verification result;
- re-verification never changes the historical `COMPLETED` backup result and never mutates the remote artifact.

### P1 — Guided disaster-recovery drill — #36 — complete

Validated restored mirrors can be used for a guided recovery rehearsal without weakening the normal recovery safety model.

Implemented behavior:

- the drill starts from a locally validated imported mirror;
- the UI identifies Git/LFS/releases as automatically republishable when present and wiki/discussion datasets as archival-only;
- drills require either a newly created private repository or an existing private repository that is still provably empty;
- the normal recovery publisher remains responsible for PAT permission checks, empty-target checks, LFS publication, non-force Git publication, release/asset publication, and resumable phases;
- drill transactions use isolated target-specific transaction keys, so a rehearsal cannot consume the restored mirror's normal recovery binding and retries of the same rehearsal remain resumable;
- after publication, every writable Git ref is compared with the remote advertised ref/object ID; LFS and release-asset counts are reported only after their existing remote verification succeeds;
- the latest successful drill result is stored in app-private JSON and shown with the restored mirror;
- the app never automatically deletes a drill repository.

## Active roadmap

There are currently **no active product-feature roadmap items**. The personal reliability roadmap is complete.

## Later only if personal use creates a real need

These are not active roadmap commitments:

- storage-usage visibility and per-repository artifact-size summaries;
- export/import of non-secret app configuration such as repository selection, schedule, and retention preferences.

Implement these only when actual usage demonstrates that they remove recurring friction.

## Explicitly cut / non-goals

The following are intentionally **not planned** for the personal-use product unless the operating assumptions change:

- native recreation of issues, pull requests, comments, reviews, or their original authors/timestamps;
- crawling timeline-event and referenced discussion-attachment bytes solely for archival completeness;
- destructive recovery into arbitrary non-empty repositories, including force-push/ref-deletion workflows;
- organization/team membership, permission, or identity reconstruction;
- automatic wiki publication through undocumented or unsafe initialization behavior;
- exact recreation of GitHub release `latest` selection, historical server timestamps, or immutable-release state;
- Play Store/public-distribution work.

These cuts are deliberate safety/maintenance decisions, not release blockers.

## Priority rule

Feature development is now frozen by default. New features require a concrete recurring problem observed during personal use. Reliability fixes, compatibility fixes, security fixes, and recovery-safety improvements remain in scope at any time.
