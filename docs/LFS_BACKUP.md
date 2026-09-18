# Git LFS backup

Git LFS remains part of the mirror completeness contract.

The app scans reachable Git blobs for standard LFS pointer files. For each unique pointer it records the declared SHA-256 OID and size.

`GitLfsDownloadService` then:

1. requests download actions from the repository Git LFS Batch API;
2. uses the basic transfer adapter and SHA-256 object IDs;
3. applies only server-returned download headers;
4. removes Authorization when redirected to a different host;
5. streams each object to a temporary file;
6. verifies size and SHA-256 before accepting it;
7. stores the verified object in the bare-repository LFS layout:

```text
repository.git/
└── lfs/
    └── objects/
        └── <first 2>/<next 2>/<full sha256 oid>
```

The complete bare repository is then packaged into `mirror.tar.gz`.

During later updates, already-present LFS objects are verified and reused; only missing referenced objects are downloaded.

There is no LFS upload or restore path in the refactored product.
