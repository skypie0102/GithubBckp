# Backup format

GithubBckp stores one logical `.tar.gz` mirror per GitHub repository ID, using the owner/repository name for human-readable storage.

## Directory layout

```text
<selected document tree>/
└── GitHub Backups/
    └── <owner>--<repo>--<github-repository-id>/
        ├── <owner>--<repo>.tar.gz
        └── latest-release/
            └── <owner>--<repo>--<tag>--release.tar.gz
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
<owner>--<repo>.tar.gz
├── repository/
│   ├── README.md
│   ├── src/
│   └── ... default-branch files ...
├── manifest.json
└── repository.git/
    ├── HEAD
    ├── config
    ├── refs/
    ├── objects/
    └── lfs/
        └── objects/
```

`repository/` is a human-readable snapshot of the configured default branch, similar to the files visible after a normal clone/checkout.

`repository.git/` remains the authoritative bare Git mirror containing full reachable history, refs, and Git LFS object storage. Keeping the bare mirror is what makes incremental fetch/prune and full-history preservation reliable.

For safety, Git symlink entries are materialized in `repository/` as regular files containing the link target text. Submodule entries are represented as empty directories, matching a clone before submodules are initialized.

Git LFS paths in `repository/` remain their small pointer files so the archive does not duplicate potentially very large LFS payloads. The verified LFS object bytes remain under `repository.git/lfs/objects/`.

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
- whether the browsable default-branch working tree is included;
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
8. when the manifest promises a browsable checkout, `repository/` must exist;
9. every reachable Git LFS pointer must have a corresponding object;
10. every referenced LFS object must match its declared size and SHA-256;
11. persisted pending bytes must match the expected archive SHA-256.

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


## Compatibility upgrade

Archives created before 0.3.3 do not contain `repository/`. They remain readable because the new manifest field is optional when parsing older archives.

On the next successful mirror operation, an older archive is rebuilt once to add the browsable checkout even when Git refs themselves are unchanged. After that one-time upgrade, unchanged repositories return to the no-extraction/no-recompression fast path.


## Latest release bundle

Each selected repository may also have one current latest-release bundle:

```text
latest-release/
└── <owner>--<repo>--<tag>--release.tar.gz
    ├── release.json
    ├── source/
    │   └── source.tar.gz
    └── assets/
        └── ...uploaded GitHub Release assets...
```

This is deliberately separate from the Git mirror archive.

`release.json` records repository identity, GitHub release ID, tag, release name/body, release URL, publish/update timestamps, app version, source archive size/SHA-256, and every asset's original name, stored path, size, and SHA-256.

Only one stable latest-release bundle is retained. The app compares GitHub release ID plus `updated_at` before downloading. A changed release is streamed into app-private temporary storage, individually hashed, packed, fully verified, written as a pending SAF file, re-hashed after persistence, and only then promoted over the previous latest-release backup.

GitHub's `/releases/latest` semantics are used: drafts and prereleases are not treated as the latest published release. If no current latest published release exists, an already-retained local release bundle is not deleted.
