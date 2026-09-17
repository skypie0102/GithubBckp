# Restore audit reports

Every newly imported Git mirror keeps a compact, app-private restore record describing what the local restore actually verified. The restored-mirror UI shows that summary and can export a versioned JSON audit report through Android's system **Create document** flow.

## Verification recorded for new restores

Metadata version 2 records:

- main Git ref count and number of distinct advertised ref-tip objects verified in the object database;
- number of Git LFS objects referenced by reachable history and successfully verified by size plus SHA-256 during local import;
- wiki ref count and verified wiki ref-tip object count;
- release count and verified release-asset count;
- issue count;
- pull-request count;
- issue/PR conversation-comment count;
- pull-request review-comment count;
- pull-request review count.

This metadata is written only after `GitMirrorRestoreService` has completed the corresponding module validation. A corrupt or missing referenced LFS object fails local import instead of waiting until GitHub publication to expose the problem.

## Backward compatibility

Restore records created by older app versions contain only main Git ref/object counts. They remain readable. The app marks them as lacking detailed module metadata and does **not** interpret zero optional-module counts as proof that LFS/wiki/release/discussion data was absent.

The JSON export carries `moduleDetailsAvailable=false` for those records and includes an explanatory note.

## JSON export

The report is generated locally from the retained restore record and is written only to the destination URI selected by the user. No report is uploaded automatically.

Top-level fields include:

- `formatVersion`;
- generation/import timestamps;
- restore ID and source archive name;
- `moduleDetailsAvailable`;
- module sections for `mainGit`, `gitLfs`, `wiki`, `releases`, and `discussions`.

Each section records verified counts and whether GitHub publication is currently supported. The report also states known semantic limitations, including:

- wiki publication is intentionally archival/manual;
- release recovery does not recreate source latest-release selection, source publication timestamps, or immutable-release state;
- discussion publication is intentionally archival-only and the backup does not include timeline-event or referenced-attachment bytes.

The report is an operator/audit summary, not a second backup artifact. The `.mirror.zip` remains the recovery artifact and retains its own SHA-256/MD5 plus module-level integrity checks.

## Privacy

The audit JSON contains counts, restore identifiers, the local archive display name, and capability/limitation statements. It does not copy issue bodies, comments, tokens, Git objects, release binaries, or LFS object bytes. Discussion content remains inside the mirror artifact's validated JSONL datasets.

## Recovery relationship

A successful local restore audit is the evidence used before the user starts GitHub publication. `GithubMirrorRestorePublisher` then applies the separate target-safety rules for a new or provably empty repository and uses resumable target-bound recovery phases.

The current product does not expose a separate guided recovery-drill workflow. Local validation plus the normal safe recovery path are the supported recovery surfaces.

See [`ROADMAP.md`](ROADMAP.md) and [`ARCHITECTURE.md`](ARCHITECTURE.md).
