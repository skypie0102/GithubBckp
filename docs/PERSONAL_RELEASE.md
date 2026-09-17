# Personal APK deployment and device validation

GithubBckp is intended for personal/internal use. The normal install artifact is the optimized **personal** build, produced locally so it keeps a stable signing certificate for Android OAuth services such as Google Drive.

## 1. Verify the repository

Run the normal gate before installing a candidate:

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:assemblePersonal :app:testDebugUnitTest :app:lintDebug
```

Do not treat a candidate as known-good while this gate is failing.

## 2. Build the personal APK locally

Build on a computer whose Android debug keystore you intend to keep:

```bash
./gradlew :app:clean :app:assemblePersonal
```

Expected artifact:

```text
app/build/outputs/apk/personal/app-personal.apk
```

The `personal` variant inherits release minification/resource shrinking but is signed with the local Android debug keystore, normally:

```text
~/.android/debug.keystore
```

Keep using the same keystore for future personal builds. That preserves both Android in-place update compatibility and the certificate SHA-1 used by Google Drive OAuth.

GitHub-hosted CI deliberately does **not** publish an installable APK. A hosted runner's generated debug key is not stable across runs, so publishing that APK would change its SHA-1 and make a previously configured Android OAuth client fail.

## 3. GitHub authentication

GithubBckp uses a user-supplied GitHub personal access token, not GitHub OAuth Device Flow.

On first launch, enter the PAT. The app validates it against GitHub's `/user` endpoint and then stores it encrypted with the Android Keystore-backed secure store. The token must be able to read every private repository you want to back up; recovery needs the additional permissions required by the selected target.

The PAT is never embedded in the APK or repository.

## 4. Google Drive Android OAuth setup

Drive authorization uses Google Play services and an Android OAuth client. The OAuth client must match both:

```text
Package: com.skypie0102.githubbckp
SHA-1:   <certificate fingerprint of the APK you installed>
```

Get the signing fingerprint with:

```bash
./gradlew :app:signingReport
```

Use the SHA-1 for the signing configuration used by the `personal` variant. Because `personal` is signed with the local debug keystore, this will normally match the debug signing fingerprint.

If the account picker closes but Drive does not connect, the app now reports the installed package name and signing SHA-1 in the authorization error/cancellation message. Compare those exact values with the Android OAuth client in Google Cloud Console.

Changing or losing the signing keystore changes the certificate fingerprint. In that case, either restore the previous keystore or update the Google Android OAuth client with the new SHA-1 before Drive authorization can succeed.

The app does not persist Google access tokens.

## 5. Install or update

Install with Android's package installer or ADB:

```bash
adb install app/build/outputs/apk/personal/app-personal.apk
```

For an update signed by the same keystore:

```bash
adb install -r app/build/outputs/apk/personal/app-personal.apk
```

If Android reports a signature mismatch, the currently installed APK was signed with a different key.

## 6. Mirror-only validation gate

Validate the exact APK you intend to keep:

1. Enter a GitHub PAT and confirm repository discovery works.
2. Connect Google Drive, if used, and confirm authorization returns to the app as connected.
3. Select representative repositories, including private/LFS repositories when applicable.
4. Run a manual backup and confirm exactly one current `.mirror.zip` is present per repository at the selected destination.
5. Run another manual backup and confirm that logical mirror is updated rather than intentionally creating another timestamped generation.
6. Enable automatic backups and confirm a scheduled run updates the same logical mirror.
7. Re-verify the stored mirror and confirm no new remote object is created.
8. Restore a representative mirror locally and verify Git refs/LFS/module validation succeeds.
9. Exercise a temporary network/storage failure and confirm the app records the failure without presenting an incomplete backup as verified.
10. Reboot the device and confirm the encrypted PAT, destination selection, schedules, and history remain usable.

A failure in mirror integrity, Google Drive authorization when Drive is part of the setup, scheduled execution, or recovery is a blocker for a known-good personal build.

## 7. Preserve the known-good build

Keep the exact tested `app-personal.apk`, its SHA-256, the source commit/ref used to build it, and the signing keystore in secure personal storage.

Example checksum:

```bash
sha256sum app/build/outputs/apk/personal/app-personal.apk
```
