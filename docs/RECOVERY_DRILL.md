# Guided recovery drill

The recovery drill is the personal-use proof that a validated Git mirror can still be turned back into a usable GitHub repository before an actual emergency.

It deliberately reuses the normal recovery engine. The drill does **not** have a weaker or destructive shortcut path.

## Before running a drill

1. Import a Git mirror through the normal restore picker. The app validates the mirror before retaining it in app-private storage.
2. Make sure GitHub is connected with a personal access token suitable for recovery. For the simplest classic-token setup, use `repo` + `workflow` scopes.
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
- Git refs are never force-pushed;
- GitHub-owned `refs/pull/*` refs are not published;
- release retries reconcile matching state instead of blindly overwriting it;
- an existing non-empty or conflicting target causes the drill to fail.

Drills use deterministic transaction keys separate from the ordinary recovery transaction. Retrying the same mirror/target can therefore resume safely without consuming the target binding used by a later real recovery.

## Post-publication verification

A successful publication is followed by independent read-only checks.

### Main Git

The app lists the target's remote refs and requires every writable local mirror ref to exist at the exact same object ID. Unexpected writable refs also fail the drill.

### Git LFS

Every unique LFS pointer from the restored mirror must receive a valid Git LFS download action from the target repository. The app then re-downloads a deterministic sample of up to three objects and verifies their byte size and SHA-256.

This keeps the drill practical for large LFS repositories while proving both remote availability and actual byte readability.

### Releases and assets

The app re-lists the target's releases and requires the restored release tag set and supported metadata to match the bundle. Release assets must match the expected names, uploaded state, and size. SHA-256 is checked from GitHub's digest when available; otherwise the asset is downloaded and hashed locally.

## Drill result

After all post-publication checks pass, the app stores the latest successful drill result for that imported mirror in app-private JSON. The result includes:

- resolved GitHub target;
- completion timestamp;
- exact Git ref verification count;
- total LFS object availability count;
- number of representative LFS objects re-downloaded and SHA-256 checked;
- verified release and release-asset counts;
- which modules were automatically republished and which remain archival-only.

The Recovery drill UI reloads and displays this result on later app launches. A failed drill does not overwrite a previously successful result.

## Target cleanup

The app never deletes a drill repository automatically. After inspecting the drill result, delete or retain the private target manually according to your own GitHub housekeeping preference.

This is intentional: automatic deletion would add a destructive code path to a tool whose recovery model otherwise avoids destructive repository operations.
