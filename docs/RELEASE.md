# Android release APK

GithubBckp publishes a versioned signed **release APK** through GitHub Actions.

Persistent signing secrets are optional. When they are absent, the workflow generates a one-off release key for that release.

## Refactor release

The local-mirror refactor is versioned as **0.3.0** with Android version code **4**. The release is published only after the refactor branch is merged to `main`.

## Release artifact

For app version `<version>`, the release workflow produces:

```text
githubbckp-v<version>.apk
githubbckp-v<version>.apk.sha256
```

The APK is a minified/resource-shrunk release build, not a debug APK.

The files are attached to the workflow run and to a GitHub Release tagged with the app `versionName`.

## Signing modes

Without signing secrets, the workflow generates a one-off key. A later APK signed by a different key requires uninstall/reinstall.

For stable update-in-place installs, configure one persistent signing keystore. Do not commit it to the repository.

Example:

```bash
keytool -genkeypair \
  -keystore githubbckp-release.jks \
  -alias githubbckp \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Add the base64-encoded keystore to Actions secrets as `ANDROID_KEYSTORE_BASE64`, plus:

```text
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

On Linux:

```bash
base64 -w0 githubbckp-release.jks
```

On systems whose `base64` does not support `-w0`, encode the file and remove line breaks before saving the secret.

## Publishing a release

Update both `versionName` and `versionCode` in `app/build.gradle.kts`, then merge/push that version to `main`.

The release workflow:

1. validates the version;
2. skips publishing if the matching `v<version>` tag already exists;
3. restores the persistent release keystore or generates a one-off fallback key;
4. runs unit tests and lint;
5. builds the signed release APK;
6. verifies the APK signature with `apksigner`;
7. renames the APK to `githubbckp-v<version>.apk`;
8. creates a SHA-256 sidecar;
9. publishes both files to a GitHub Release.

The workflow can also be started manually with a tag, but the tag must match the app's `versionName`.

## Installing and updating

If releases use one-off fallback keys, expect to uninstall before installing a later release because the signing certificate changes.

If a persistent release key is configured, future versions signed with that key can update in place as long as `versionCode` increases.

Uninstalling clears app data, including the encrypted GitHub token and local app settings. The mirror archives in the user-selected document tree are external to app-private data and should remain in that selected storage location.

## 0.3.0 manual release gates

These checks intentionally remain manual because unit/CI tests cannot reproduce Android process death, Storage Access Framework provider behavior, or organization approval controls faithfully.

Before merging the refactor PR and publishing 0.3.0:

1. **Upgrade migration:** using the same signing key (or the same local debug key), install a pre-refactor build with an app-owned local `.mirror.zip`, then upgrade to the 0.3.0 candidate. Confirm the ZIP remains untouched until a verified `mirror.tar.gz` is committed, then confirm only the exact app-owned legacy ZIP is removed.
2. **Organization approval:** use a fine-grained token whose organization approval/repository access is pending or revoked. Confirm repository refresh/scheduled preflight marks that repository blocked and does not start its backup worker.
3. **Real large repository:** mirror a repository materially larger than the CI synthetic stress fixture and confirm temporary-space preflight, extraction, fetch, compression, and final commit complete without excessive memory use.
4. **Kill during fetch:** terminate the app/process while an existing mirror is fetching. Relaunch and confirm the previous `mirror.tar.gz` still verifies.
5. **Kill during compression:** terminate during candidate tar.gz creation. Relaunch and confirm the previous stable archive still verifies and loose cache state is cleaned.
6. **Kill around promotion:** terminate once with both stable+pending present and once after stable retirement with pending-only. Relaunch and confirm stable wins in the first case and verified pending recovery works in the second.
7. **SAF failure:** move the selected folder or revoke its persisted permission. Confirm health becomes BLOCKED and no backup starts; restore access and confirm reconciliation clears the storage block.
8. **Notification failure:** disable app notifications and then disable only the active-backup channel. Confirm jobs do not start in either case.
9. **Concurrent mirrors:** start several selected repositories together and confirm each has independent cancellable foreground work under the grouped notification surface.

## Verification

Before treating a release as known-good:

1. confirm fine-grained PAT setup and repository discovery;
2. choose a local backup folder;
3. confirm active-job notifications are visible;
4. create a first mirror and verify `mirror.tar.gz` is produced;
5. run another update without GitHub changes and confirm the archive is not rebuilt;
6. change the source repository and confirm the same logical mirror is updated;
7. delete a test branch/tag upstream and confirm fetch/prune removes that ref from the mirror;
8. interrupt an update and confirm the previous verified mirror remains intact;
9. confirm daily/weekly scheduling updates the same logical mirror;
10. confirm the health dashboard distinguishes last checked from last changed.
