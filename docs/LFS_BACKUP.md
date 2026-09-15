# Git LFS backup

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

The bare repository and its `lfs/objects` directory are then packaged together into the existing `.mirror.zip` artifact.

If any referenced LFS object cannot be authorized, downloaded, size-checked, or SHA-256-verified, the mirror backup fails instead of silently producing an incomplete successful backup.

## Current restore boundary

Local mirror restoration preserves the bundled LFS object files because they are inside the mirror ZIP. GitHub publication currently pushes Git refs only; it does not yet upload bundled LFS objects to the target repository's LFS store. Completed backups that contain LFS objects therefore persist a non-fatal warning describing this restore limitation.
