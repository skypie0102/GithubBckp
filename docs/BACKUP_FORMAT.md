# Backup format

GithubBckp stores one logical `mirror.tar.gz` per GitHub repository ID.

## Directory layout

```text
<selected document tree>/
└── GitHub Backups/
    └── <github-repository-id>/
        └── mirror.tar.gz
```

A transaction may temporarily contain:

```text
mirror.tar.gz
mirror.pending.tar.gz
```

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
8. persisted pending bytes must match the expected SHA-256.

LFS objects are verified by size and SHA-256 when downloaded/reused.

## Update safety

The durable `mirror.tar.gz` is never edited in place.

The app rebuilds into app-private temporary storage, verifies the result, writes `mirror.pending.tar.gz`, re-hashes the persisted bytes, and only then retires the previous stable archive.

If the app is interrupted with only a pending archive available, startup reconciliation re-verifies it before promotion.
