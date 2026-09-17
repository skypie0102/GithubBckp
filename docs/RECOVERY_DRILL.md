# Guided recovery drill

The recovery drill is the personal-use proof that a validated Git mirror can still be turned back into a usable GitHub repository before an actual emergency.

It deliberately reuses the normal recovery engine. The drill does **not** have a weaker or destructive shortcut path.

## Before running a drill

1. Import a Git mirror through the normal restore picker. The app validates the mirror before retaining it in app-private storage.
2. Make sure GitHub is connected with the recovery `workflow` permission available.
3. Open **Recovery drill**.
4. Select one of the locally validated mirrors.

A drill can use either:

- a **new private repository**, created by the app without README/license initialization; or
- an **existing private repository** that is still empty across both Git refs and GitHub releases.

Public repositories are rejected for drills.

## What is published

Automatic recovery surfaces:

- writable main-repository Git refs/history;
- referenced Git LFS objects;
- GitHub releases and validated release assets.

Archival-only surfaces:

- initialized wiki Git history;
- issue/pull-request discussion datasets and their comments/reviews.

The archival-only modules remain available in the restored local mirror and its audit data, but the drill does not attempt to recreate them on GitHub.

## Safety model

The drill inherits the same safeguards as real recovery:

- the target is bound to a stable GitHub repository ID;
- a first-time target must have no advertised Git refs and no releases;
- Git LFS publication happens before Git refs;
- the target is checked again through the existing publication/reconciliation flow;
- Git refs are never force-pushed;
- GitHub-owned `refs/pull/*` refs are not published;
- release retries reconcile matching state instead of blindly overwriting it;
- an existing non-empty or conflicting target causes the drill to fail.

Drills use their own recovery transaction key. This keeps routine drill runs separate from the normal recovery transaction for the same imported mirror and allows later real recovery to remain independently target-bound.

## Post-publication verification

A successful publication is followed by independent read-only checks.

### Main Git

The app lists the target's remote refs and requires every writable local mirror ref to exist at the exact same object ID. Unexpected writable refs also fail the drill.

### Git LFS

Every unique LFS pointer from the restored mirror must receive a valid Git LFS download action from the target repository. The app then re-downloads a bounded deterministic sample of up to three objects and verifies their byte size and SHA-256.

This keeps the drill practical for very large LFS repositories while still proving both remote availability and real byte readability.

### Releases and assets

The app re-lists the target's releases and requires the restored release tag set and metadata to match the bundle. Release assets must match the expected names, uploaded state, and size. SHA-256 is checked from GitHub's digest when available; otherwise the asset is downloaded and hashed locally.

## Drill result and audit

Every completed attempt records `VERIFIED` or `FAILED` with:

- source restore/archive identity;
- requested target kind/name;
- resolved GitHub target when one was created or bound;
- verified Git ref count;
- LFS availability and representative-download counts;
- verified release/asset counts;
- archival wiki/discussion counts;
- concise result detail and timestamps.

The **Export audit JSON** action writes the same information to a user-selected document.

## Target cleanup

The app never deletes a drill repository automatically. After inspecting the drill result, delete or retain the private target manually according to your own GitHub housekeeping preference.

This is intentional: automatic deletion would add a destructive code path to a tool whose recovery model otherwise avoids destructive repository operations.
