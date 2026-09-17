# Android release APK

GithubBckp publishes a versioned signed **release APK** through GitHub Actions, following the same overall release shape as Intake Edit.

The important difference is signing: GithubBckp requires one persistent signing key because Google Drive Android OAuth is bound to the APK certificate SHA-1.

## Release artifact

For app version `0.2.0`, the release workflow produces:

```text
githubbckp-v0.2.0.apk
githubbckp-v0.2.0.apk.sha256
```

The APK is a minified/resource-shrunk release build, not a debug APK.

The files are attached both to the workflow run and to a GitHub Release tagged with the app `versionName`.

## Persistent signing setup

Generate or choose one Android signing keystore and keep it permanently. Do not commit it to the repository.

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

The workflow deliberately fails when signing secrets are missing. It does **not** generate Intake Edit's one-off fallback key, because a new key on every release would also create a new Google Drive OAuth identity on every release.

## Publishing a release

Update both `versionName` and `versionCode` in `app/build.gradle.kts`, then merge/push that version to `main`.

The release workflow:

1. validates the version;
2. skips publishing if the matching `v<version>` tag already exists;
3. restores the persistent release keystore from Actions secrets;
4. runs unit tests and lint;
5. builds the signed release APK;
6. verifies the APK signature with `apksigner`;
7. renames the APK to `githubbckp-v<version>.apk`;
8. creates a SHA-256 sidecar;
9. publishes both files to a GitHub Release.

The workflow can also be started manually and given a tag, but the tag must match the app's `versionName`.

## Installing and updating

The first switch from an older debug-signed or locally debug-signed GithubBckp APK to the persistent release key may require uninstalling the existing app because Android will reject an update signed by a different certificate.

After that migration, future versions signed with the same persistent release key can update in place as long as `versionCode` increases.

Because uninstalling clears app data, export or note any settings you need before the one-time signing migration.

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
