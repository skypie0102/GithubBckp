# Android release

GithubBckp publishes a versioned release APK through GitHub Actions.

## Release artifact

For app version `0.3.0` the workflow produces:

```text
githubbckp-v0.3.0.apk
githubbckp-v0.3.0.apk.sha256
```

The APK is the minified/resource-shrunk release build.

## Signing

The workflow follows the existing personal-app pattern:

- if Android signing secrets are configured, it uses the persistent key;
- otherwise it generates a one-off release key for that release.

With one-off signing, Android requires uninstall/reinstall before installing a later APK signed by a different key.

There is no Google Drive OAuth integration anymore, so release signing is no longer tied to any Google OAuth SHA-1.

## Publishing

Update `versionName` and `versionCode` in `app/build.gradle.kts` and merge to `main`.

The release workflow:

1. validates the version;
2. creates or restores signing;
3. runs tests and lint;
4. builds the signed release APK;
5. verifies its signature;
6. publishes the versioned APK and SHA-256 sidecar in a GitHub Release.
