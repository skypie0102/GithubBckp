# Android release APK

GithubBckp publishes a versioned signed **release APK** through GitHub Actions, following the same overall release shape as Intake Edit.

Signing now mirrors Intake Edit: persistent signing secrets are optional, and when they are absent the workflow generates a one-off release key.

## Release artifact

For app version `0.2.0`, the release workflow produces:

```text
githubbckp-v0.2.0.apk
githubbckp-v0.2.0.apk.sha256
```

The APK is a minified/resource-shrunk release build, not a debug APK.

The files are attached both to the workflow run and to a GitHub Release tagged with the app `versionName`.

## Signing modes

Without any signing secrets, the workflow generates a one-off key for that release, exactly like Intake Edit. This requires uninstall/reinstall when the next APK is signed by a different key.

For stable update-in-place installs and a stable Google Drive OAuth SHA-1, you may optionally configure one persistent signing keystore. Do not commit it to the repository.

Example:

```bash
keytool -genkeypair \
  -keystore githubbckp-release.jks \
  -alias githubbckp \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Record the keystore password, alias, and key password securely.

Encode the keystore as one base64 string and add it to the GithubBckp repository's Actions secrets as `ANDROID_KEYSTORE_BASE64`. Also add:

```text
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

On Linux, for example:

```bash
base64 -w0 githubbckp-release.jks
```

On systems whose `base64` does not support `-w0`, encode the file and remove line breaks before saving the secret.

## Google Drive OAuth SHA-1

Get the release-key fingerprint locally with:

```bash
keytool -list -v \
  -keystore githubbckp-release.jks \
  -alias githubbckp
```

Register that SHA-1 in the Google Android OAuth client for:

```text
com.skypie0102.githubbckp
```

The release workflow also writes the signing SHA-1 into the GitHub Release body after it decodes the configured keystore.

When signing secrets are missing, the workflow generates a one-off fallback key and prints that release's SHA-1. Because Google Drive Android OAuth is certificate-bound, that SHA-1 must be registered for the installed release before Drive authorization will work. With persistent signing secrets, the SHA-1 stays stable across releases.

## Publishing a release

Update both `versionName` and `versionCode` in `app/build.gradle.kts`, then merge/push that version to `main`.

The release workflow:

1. validates the version;
2. skips publishing if the matching `v<version>` tag already exists;
3. restores the persistent release keystore from Actions secrets, or generates a one-off fallback key when they are absent;
4. runs unit tests and lint;
5. builds the signed release APK;
6. verifies the APK signature with `apksigner`;
7. renames the APK to `githubbckp-v<version>.apk`;
8. creates a SHA-256 sidecar;
9. publishes both files to a GitHub Release.

The workflow can also be started manually and given a tag, but the tag must match the app's `versionName`.

## Installing and updating

If releases use the one-off fallback key, expect to uninstall before installing a later release because the signing certificate changes. If a persistent release key is configured, future versions signed with that same key can update in place as long as `versionCode` increases.

Because uninstalling clears app data, export or note any settings you need before reinstalling.

## Verification

Before treating a release as known-good:

1. confirm GitHub PAT setup and repository discovery;
2. confirm Google Drive authorization using the release certificate SHA-1, if Drive is used;
3. run a manual mirror backup;
4. run a second backup and confirm the current mirror is replaced rather than intentionally versioned;
5. interrupt a replacement and confirm the previous verified mirror remains usable;
6. confirm automatic backup updates the same logical mirror;
7. re-verify the stored current mirror;
8. restore a representative mirror and validate its Git/LFS/module data.
