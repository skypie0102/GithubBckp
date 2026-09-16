# GitHub issue and pull request metadata backup

Git mirror backups preserve GitHub discussion metadata that is not part of the Git object database. For the personal-use product this module is intentionally **preservation and local validation only**; native discussion recreation on a target repository is not planned.

## Bundled data

The backup reads GitHub's REST API and stores the raw response objects for:

- repository issues, including the issue-shaped records GitHub returns for pull requests;
- issue comments, which also include ordinary pull-request conversation comments;
- pull requests;
- pull-request review comments attached to diffs;
- pull-request reviews, including review state and body when returned by GitHub.

Using the raw REST objects preserves fields such as titles, Markdown bodies, author identities, labels, milestones, timestamps, refs, review state, and association metadata when GitHub includes them. The app adds only `backupIssueNumber` or `backupPullRequestNumber` to global comment/review datasets where the parent number otherwise comes from endpoint context or a URL.

## Streaming artifact layout

Discussion histories can be much larger than the Git repository itself, so backup does not build one giant in-memory JSON document. Records stream directly to JSON Lines files:

```text
github-backup/discussions/
  manifest.json
  issues.jsonl
  issue-comments.jsonl
  pull-requests.jsonl
  review-comments.jsonl
  reviews.jsonl
```

Each line is one complete JSON object. The versioned `manifest.json` records the repository name, per-dataset record counts, and a SHA-256 digest for each JSONL file.

Repository issue and pull-request lists use `state=all` and 100-item pagination. Repository-wide issue comments and review comments are paginated independently. Pull-request reviews do not have a repository-wide list endpoint, so the backup pages reviews for each discovered pull request while retaining only the pull numbers in memory.

## Validation

Before a restored mirror is retained, `GithubDiscussionBackupService` validates the discussion directory independently:

1. The manifest format version must be supported.
2. All five required datasets must be present under their fixed file names.
3. Dataset paths must remain inside the discussion directory.
4. Every JSONL file must reproduce its manifest SHA-256.
5. Every line must contain valid JSON.
6. GitHub IDs and issue/pull numbers must be positive where required.
7. Duplicate issue numbers, pull-request numbers, or record IDs are rejected within their datasets.
8. Manifest record counts and top-level discussion counts must match the validated files.

The whole `.mirror.zip` still receives the normal artifact SHA-256 and MD5 checksums, so discussion datasets have both module-level integrity and artifact-level integrity.

## Recovery boundary

Native discussion recreation is deliberately outside the personal-use roadmap. GitHub APIs can create new issues/comments/reviews, but recreated objects cannot faithfully assume another user's identity or original server timestamp, and writes may trigger notifications or automation. The maintenance and safety cost is not justified for the intended use of the app.

Timeline events and referenced attachment bytes are also intentionally outside scope. The backup preserves the discussion records returned by the supported REST surfaces and records the limitation rather than pretending to provide perfect GitHub-state cloning.

A mirror containing discussion metadata therefore carries a non-fatal completeness warning: the metadata is preserved and locally verified, but it is not automatically republished to GitHub.

See [`ROADMAP.md`](ROADMAP.md) for the current personal-use scope decisions.
