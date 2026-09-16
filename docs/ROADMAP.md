# Personal-use roadmap

GithubBckp is a personal/internal disaster-recovery tool. The roadmap optimizes for **backup reliability, recoverability, and operator confidence**, not broad GitHub feature parity or public distribution.

## Current product boundary

The supported recovery promise is intentionally narrow:

- source snapshots and full Git mirrors can be backed up to the configured personal destination;
- Git mirrors preserve main Git history/refs, Git LFS, initialized wiki history, releases/assets, and issue/PR discussion metadata;
- imported mirrors are validated locally before they are retained;
- main Git, Git LFS, and releases/assets can be recovered to a new or provably empty GitHub repository;
- wiki and discussion data are preservation/local-validation surfaces rather than automated GitHub publication surfaces;
- recovery never force-pushes or destructively rewrites an arbitrary non-empty repository.

## Active roadmap

### P0 — Backup health dashboard

Goal: make silent backup failure obvious without reading raw history.

Acceptance criteria:

- show health for every currently selected repository;
- distinguish protected, warning, failed-after-last-success, stale, and never-backed-up states;
- use the latest verified backup and latest attempt rather than a fixed-size recent-history window;
- when automatic backups are enabled, flag repositories whose last verified backup is older than a cadence-aware freshness window;
- surface a compact top-level summary and the repositories that need attention;
- keep the health calculation pure and unit tested.

### P0 — Failure and overdue-backup notifications

Goal: make the app useful even when it is not opened regularly.

Acceptance criteria:

- notify when a backup attempt reaches `FAILED`;
- notify when scheduled backup health becomes overdue for selected repositories;
- avoid notification storms by grouping/rate-limiting repeated failures;
- deep-link or otherwise lead the user to actionable in-app state;
- do not notify for intentionally unavailable/unselected repositories.

### P1 — On-demand backup re-verification

Goal: prove that an older stored artifact is still present and readable, instead of relying only on verification performed when it was created.

Acceptance criteria:

- select a completed, non-pruned backup from history;
- re-open or re-download the stored artifact through its original provider;
- verify persisted size/checksum data;
- for Git mirrors, optionally run the same safe local mirror/module validation used by restore without publishing anything;
- persist/display the latest re-verification result and timestamp;
- never mutate the remote backup while verifying it.

### P1 — Guided disaster-recovery drill

Goal: make recovery testing a routine operation rather than something first attempted during an emergency.

Acceptance criteria:

- guide the user from a selected verified mirror through safe import and recovery;
- require a new or provably empty private GitHub target;
- show which modules can be republished automatically and which are archival only;
- verify representative Git refs plus applicable LFS and release assets after publication;
- produce a concise drill result/audit record;
- do not automatically delete repositories or bypass existing recovery safety checks.

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

After the four active roadmap items are complete, feature development should stop by default. New features require a concrete problem observed during personal use. Reliability fixes, compatibility fixes, security fixes, and recovery-safety improvements remain in scope at any time.
