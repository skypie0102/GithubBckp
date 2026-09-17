# Personal-use roadmap

GithubBckp is a personal/internal disaster-recovery tool. The roadmap optimizes for **backup reliability, recoverability, and operator confidence**, not broad GitHub feature parity or public distribution.

## Current product boundary

The active product is intentionally small:

- each selected repository has one current Git mirror at the configured personal destination;
- manual and automatic runs replace that logical mirror instead of creating intentional timestamped generations;
- a replacement is verified and committed before the previous verified remote object is retired;
- mirrors preserve Git refs/history, referenced Git LFS objects, initialized wiki history when available, releases/assets, and supported issue/PR discussion metadata;
- imported mirrors are validated locally before they are retained;
- main Git, Git LFS, and releases/assets can be recovered to a new or provably empty GitHub repository;
- wiki and discussion data are preservation/local-validation surfaces rather than automated GitHub publication surfaces;
- recovery never force-pushes or destructively rewrites an arbitrary non-empty repository;
- GitHub authentication uses a user-supplied PAT stored through Android Keystore-backed encrypted storage;
- backup destinations are Google Drive or a user-selected Android document tree.

Source snapshots, configurable retention/version-history controls, GitHub Device Flow, the guided recovery-drill UI, and the extra `personal` Android build variant are no longer part of the active product.

## Completed reliability work

### Backup health dashboard — complete

The Home screen classifies every currently selected, available repository as protected, warning, failed-after-last-success, stale, or never backed up. Health uses the latest Git-mirror attempt and latest completed Git-mirror row that still represents a current remote object rather than the fixed-size Recent backup activity list.

Legacy source-snapshot rows remain historical data but do not satisfy current mirror health. Automatic-backup staleness uses two cadence windows to account for WorkManager's opportunistic execution.

### Failure and overdue-backup notifications — complete

The app can surface backup trouble without remaining open:

- a failed mirror update can notify only after the failure is persisted;
- repeated failures for the same repository/mirror are rate-limited;
- automatic-backup health is checked locally while scheduling is enabled;
- repositories become overdue after two cadence windows;
- legacy source snapshots do not suppress mirror overdue alerts;
- overdue repositories are grouped and deduplicated;
- unavailable and unselected repositories are excluded;
- disabling automatic backups clears overdue-notification state;
- tapping an alert opens the app's Home/backup-health surface.

Notification permission affects the alert surface only, not backup execution.

### On-demand stored-mirror re-verification — complete

Eligible completed current mirrors can be freshly re-verified from backup activity:

- the app reads the artifact through the provider that originally stored it;
- the complete object is streamed into temporary app-cache storage;
- byte size, SHA-256, and MD5 are recomputed locally;
- Git mirrors run through the existing safe local mirror/module validator without publishing anything to GitHub;
- temporary downloaded files are removed after the check;
- the latest `VERIFIED` or `FAILED` result is stored separately from creation-time completion state;
- audit export includes the latest recorded re-verification result;
- re-verification never mutates the remote object.

### One-current-mirror cleanup — complete

The active backup path has been simplified around one current mirror per repository:

- new manual and scheduled work always requests `GIT_MIRROR`;
- source-archive creation paths and format selectors are removed;
- local mirror artifact names are stable instead of timestamped;
- Google Drive uploads a distinct resumable replacement so the previous verified file remains untouched until the new object verifies and is committed;
- document-tree storage writes and fully rehashes a sibling staging document before the previous verified document is retired;
- `BackupCoordinator` records the verified replacement as `COMPLETED` before cleanup begins;
- failed replacement candidates are removed best-effort;
- older duplicate rows are retired from the logical current set after replacement commit, while physical cleanup failure is surfaced as a warning;
- configurable keep-last-N retention has been removed;
- historical Room rows remain available for activity/audit compatibility.

### Release pipeline cleanup — complete

The installable artifact is now the signed, minified/resource-shrunk `release` APK. The extra `personal` build type has been removed.

The GitHub Actions release flow mirrors Intake Edit's release publishing shape: version validation, signed APK build, signature verification, versioned filename, SHA-256 sidecar, and GitHub Release publication.

GithubBckp intentionally requires a persistent signing key instead of Intake Edit's current disposable fallback key because Google Drive Android OAuth is bound to the APK signing certificate SHA-1.

## Active roadmap

There are currently **no active product-feature roadmap items**.

The next work should come from concrete problems observed in personal use, especially failures involving mirror integrity, storage replacement, authentication, scheduling, or recovery.

## Architecture cleanup status

The obsolete backup modes and duplicate-generation machinery are removed from active execution. The remaining larger subsystems are not compatibility trash; they implement currently retained features:

- Git LFS preservation;
- wiki preservation;
- releases/assets preservation and restore;
- issue/pull-request discussion preservation;
- stored-mirror re-verification and audit;
- safe restore/recovery;
- automatic scheduling and health notifications.

If the product is later narrowed to **Git repository data only**, those optional preservation/recovery surfaces can be removed in a separate scope-reduction pass. Until that product decision is made, deleting them would remove working backup coverage rather than merely clean dead code.

## Explicitly cut / non-goals

The following are intentionally not planned unless the operating assumptions change:

- source-snapshot backups as a second active format;
- user-managed historical backup generations or retention policies;
- GitHub Device Flow;
- guided disaster-recovery drills as a separate product surface;
- native recreation of issues, pull requests, comments, reviews, or their original authors/timestamps;
- crawling timeline-event and referenced discussion-attachment bytes solely for archival completeness;
- destructive recovery into arbitrary non-empty repositories, including force-push/ref-deletion workflows;
- organization/team membership, permission, or identity reconstruction;
- automatic wiki publication through undocumented or unsafe initialization behavior;
- exact recreation of GitHub release `latest` selection, historical server timestamps, or immutable-release state;
- Play Store/public-distribution work.

## Priority rule

Feature development is frozen by default. Reliability fixes, compatibility fixes, security fixes, storage-integrity fixes, and recovery-safety improvements remain in scope at any time.
