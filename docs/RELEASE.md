# Android release APK

GithubBckp publishes a versioned installable **release APK** through GitHub Actions.

## Release model

The release flow intentionally uses **no persistent signing secrets or stored keystore**.

Android requires an APK to contain a signature before it can be installed, so the workflow follows the same model used by the Intake Edit releases:

1. create a disposable one-off key inside the GitHub Actions runner;
2. build the minified/resource-shrunk release APK with that temporary key;
3. verify it with `apksigner`;
4. publish the APK and SHA-256 checksum;
5. allow the runner and temporary key to disappear.

Nothing needs to be configured in GitHub Secrets.

Because every release can use a different disposable key, a future release may require uninstall/reinstall rather than update-in-place. The user-selected repository mirrors live outside app-private storage and are not embedded in the APK.

## 0.3.0

The local-mirror refactor is versioned as:

```text
versionName: 0.3.0
versionCode: 4
package: com.skypie0102.githubbckp
```

The release is published from `main` as tag `v0.3.0`.

## Release assets

The workflow publishes:

```text
githubbckp-v0.3.0.apk
githubbckp-v0.3.0.apk.sha256
```

The APK is the optimized release build, not the much larger debug/testing APK.

## Publishing

A push/merge to `main` triggers the release workflow. It:

1. reads `versionName` and `versionCode` from `app/build.gradle.kts`;
2. skips publishing when `v<version>` already exists;
3. generates the one-off Android key;
4. runs unit tests and lint;
5. builds the minified/resource-shrunk release APK;
6. verifies the APK signature;
7. renames it to `githubbckp-v<version>.apk`;
8. creates a SHA-256 sidecar;
9. uploads both as workflow artifacts;
10. publishes both to the matching GitHub Release.

The workflow may also be started manually with a tag, but the requested tag must match the app's `versionName`.

## Device gate

Repository owner **skypie0102** tested the refactored app on a real Android device, confirmed repository backup worked successfully, and explicitly accepted that testing as sufficient for the 0.3.0 device gate.

Issue #53 is closed as completed by owner attestation.

## Verification

Before treating a later release as known-good:

1. confirm PAT setup and repository discovery;
2. choose a local backup folder;
3. confirm active-job notifications are visible;
4. create a first mirror;
5. run an unchanged update and confirm the archive is not rebuilt;
6. change the source repository and confirm the same logical mirror is updated;
7. confirm deleted refs are pruned;
8. confirm health distinguishes last checked from last changed.
