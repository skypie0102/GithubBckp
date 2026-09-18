# Backup format

GithubBckp stores one logical `.tar.gz` mirror per GitHub repository ID, using the owner/repository name for human-readable storage.

## Directory layout

```text
<selected document tree>/
└── GitHub Backups/
    └── <owner>--<repo>--<github-repository-id>/
        └── <owner>--<repo>.tar.gz
```

A transaction may temporarily contain:

```text
<owner>--<repo>.tar.gz
<owner>--<repo>.pending.tar.gz
```

The repository ID remains in the folder name so identity remains unambiguous across repository renames and duplicate-looking names.

The pending file is never an intentional historical generation.

## Archive layout

```text
mirror.tar.gz
├── manifest.json
└── repository.git/
    ├── HEAD
    ├── config
    ├── refs/
    ├── objects/
    └── lfs/
        └── objects/
```

`repository.git` must be a bare Git repository.

## Manifest

Current format version: **1**.

`manifest.json` records:

- format version;
- GitHub repository ID;
- owner;
- repository name;
- remote Git URL;
- default branch;
- private/public flag;
- original creation timestamp;
- last archive update timestamp;
- last successful Git fetch timestamp;
- deterministic refs digest;
- current default-branch commit when available;
- whether reachable LFS objects are included;
- app version.

The archive SHA-256 is stored outside the archive in Room because an archive cannot contain its own final digest.

## Refs digest

The refs digest is SHA-256 over sorted lines of:

```text
<ref name> <object id>
```

for refs under `refs/`.

The app compares this stored digest with the digest produced from `ls-remote` before extracting an existing archive.

## Verification

Before a pending archive can be committed:

1. the gzip/tar stream must parse completely;
2. archive paths must remain inside the extraction root;
3. symbolic and hard links are rejected;
4. `manifest.json` must exist and use a supported format version;
5. manifest repository ID must match the expected GitHub repository;
6. `repository.git` must exist;
7. JGit must open it successfully as a bare repository;
8. every reachable Git LFS pointer must have a corresponding object;
9. every referenced LFS object must match its declared size and SHA-256;
10. persisted pending bytes must match the expected archive SHA-256.

## Update safety

The durable repository-named `.tar.gz` is never edited in place.

The app rebuilds into app-private temporary storage, verifies the result, writes the repository-named `.pending.tar.gz`, re-hashes the persisted bytes, and only then retires the previous stable archive.

If the app is interrupted with only a pending archive available, startup reconciliation re-verifies it before promotion.


## Git LFS

Git LFS objects are part of the mirror completeness contract.

For every reachable standard LFS pointer, the app:

1. requests a read/download action from the Git LFS Batch API;
2. accepts only the basic transfer adapter with SHA-256 object IDs;
3. follows HTTPS redirects only;
4. removes Authorization when a redirect changes host;
5. writes each object to a temporary file;
6. verifies declared size and SHA-256 before accepting it;
7. stores the verified object under `repository.git/lfs/objects/`.

During later updates, already-present LFS objects are re-verified and reused; only missing referenced objects are downloaded.

There is no Git LFS upload or restore path.
