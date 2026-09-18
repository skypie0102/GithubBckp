# Android release APK

GithubBckp publishes a versioned signed **release APK** through GitHub Actions.

Persistent signing secrets are optional. When they are absent, the workflow generates a one-off release key for that release.

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
