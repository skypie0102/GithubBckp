# GitHub wiki backup

GitHub wikis are separate Git repositories. When the main repository reports the wiki feature as enabled, Git mirror backup attempts to mirror-clone:

```text
https://github.com/<owner>/<repository>.wiki.git
```

An enabled wiki that has never had an initial page may not have a cloneable Git repository yet. That case is treated as "no wiki content" rather than as a failed repository backup.

## Artifact layout

Initialized wiki history is stored inside the existing `.mirror.zip` under:

```text
github-backup/wiki.git/
```

The directory is a bare Git mirror. Before the backup is packaged, the app verifies that it is bare, that it contains refs, and that every advertised ref tip exists in its object database.

The same checks run again after a mirror ZIP is imported through the local restore flow. A malformed or incomplete bundled wiki therefore makes local restore fail instead of silently accepting damaged wiki history.

## GitHub publication boundary

Automatic publication of bundled wiki history back to GitHub is intentionally not implemented yet. GitHub documents cloning a wiki after its initial page exists, but does not expose a documented REST API for creating wiki pages. The app therefore avoids relying on an undocumented initialization flow or overwriting an existing target wiki.

Backups that actually contain wiki history persist a non-fatal completeness warning. Main Git refs and Git LFS recovery continue to work, and the bundled wiki remains preserved in the local restored mirror for a future documented/safe publication path.
