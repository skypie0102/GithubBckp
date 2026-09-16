# Git LFS backup and recovery

Git mirror backups scan every reachable blob in the bare mirror for standard Git LFS pointer files. Unique SHA-256 OIDs and declared sizes are collected once per backup.

For repositories with LFS pointers, `GitLfsDownloadService`:

1. Requests download actions from `https://github.com/<owner>/<repo>.git/info/lfs/objects/batch` in batches of 100 objects.
2. Uses the Git LFS `basic` transfer adapter and SHA-256 object IDs.
3. Sends GitHub credentials only to the GitHub batch endpoint.
4. Applies only the headers returned by each object download action to that action URL.
5. Drops `Authorization` when an object download redirects to a different host.
6. Streams each object to a temporary file and verifies declared size plus SHA-256 before accepting it.
7. Stores verified bytes in the standard bare-repository LFS layout:

```text
lfs/objects/<first 2 hex>/<next 2 hex>/<full sha256 oid>
```

The bare repository and its `lfs/objects` directory are then packaged together into the existing `.mirror.zip` artifact. If any referenced LFS object cannot be authorized, downloaded, size-checked, or SHA-256-verified, the mirror backup fails instead of silently producing an incomplete successful backup.

## GitHub recovery

A restored mirror retains the bundled `lfs/objects` store. Before Git refs are published to a new or provably empty GitHub target, recovery:

1. Rescans reachable Git LFS pointer OIDs from the restored bare repository.
2. Requires every referenced local LFS object to exist and pass size + SHA-256 verification.
3. Requests `upload` plans from the target repository's Git LFS Batch API, again in batches of 100.
4. Treats an object with no returned `actions` as already present on the target.
5. PUTs raw object bytes to each returned upload action using only its server-provided headers.
6. Executes the optional LFS `verify` action with the object's OID and size.
7. Only after all LFS objects are covered does the existing empty-remote preflight run and Git refs get pushed without force.

That order is deliberate: recovery never publishes Git refs that would point at known-missing bundled LFS data. If an older mirror contains LFS pointers but does not contain the corresponding LFS object files, recovery fails before Git refs are published.

## Personal-use recovery boundary

Git LFS is part of the core supported personal recovery promise and remains in scope for backup verification and guided disaster-recovery drills.

Destructive restoration into an arbitrary non-empty Git repository is intentionally **not planned**. LFS recovery remains bound to the same new/provably-empty target safety model as main Git recovery; the app will not add a force-push/ref-deletion mode merely to broaden target compatibility.

See [`ROADMAP.md`](ROADMAP.md).
