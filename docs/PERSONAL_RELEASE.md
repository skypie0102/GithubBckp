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

If the account picker closes but Drive does not connect, the app reports the installed package name and signing SHA-1 in the authorization error/cancellation message. Compare those exact values with the Android OAuth client in Google Cloud Console.

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

1. Enter a GitHub PAT and confirm repository discovery works, including at least one private repository if private backup is part of your normal use.
2. Connect the destination you intend to use. For Google Drive, confirm authorization returns to the app as connected; for a backup folder, confirm the selected document tree remains usable after leaving and reopening the app.
3. Select representative repositories, including an LFS repository when applicable, and run a manual mirror backup.
4. Confirm the completed activity row reports a current verified mirror at the selected destination and that the stored bytes can be freshly re-verified.
5. Run another manual update for the same repository. Confirm the replacement becomes current only after verification and that the previous remote object is then removed. A temporary extra object is acceptable only while replacement/cleanup is in progress; persistent cleanup residue must be surfaced as a warning rather than silently treated as a second intentional generation.
6. Exercise replacement failure before completion—for example by interrupting connectivity or making the destination temporarily unavailable during upload. Confirm the failed attempt is recorded and the **previous verified mirror remains readable and re-verifiable**.
7. If using a document-tree destination, change to a different backup folder and run another update. Confirm the new current mirror is written under the newly selected tree rather than continuing to modify the old tree through a persisted URI.
8. If using Google Drive, retry after a failed/interrupted upload and confirm the app can converge back to one current verified object; cleanup of an already-missing old object should not block success.
9. Enable automatic backups and allow a scheduled run to complete. Confirm the scheduled child work produces a mirror update and that legacy source-snapshot history, if present from an older version, does not make current mirror health look protected by itself.
10. Re-verify the stored current mirror and confirm the operation reads the full remote object without creating another backup object.
11. Restore a representative mirror locally and verify Git refs/LFS/module validation succeeds, then exercise normal recovery only against a new or provably empty GitHub target if recovery is part of your personal workflow.
12. Reboot the device and confirm the encrypted PAT, destination selection, schedules, mirror health/history, and re-verification behavior remain usable.

A failure in replacement safety, mirror integrity, Google Drive authorization when Drive is part of the setup, document-tree destination switching, scheduled execution, re-verification, or recovery is a blocker for a known-good personal build.

## 7. Preserve the known-good build

Keep the exact tested `app-personal.apk`, its SHA-256, the source commit/ref used to build it, and the signing keystore in secure personal storage.

Example checksum:

```bash
sha256sum app/build/outputs/apk/personal/app-personal.apk
```
