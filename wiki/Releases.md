# Install and update with GitHub / Obtainium

## Install the app

Download [LittleNest.apk from the latest stable release](https://github.com/joshjm/little-nest/releases/latest/download/LittleNest.apk).
Requires Android 9 or newer. Temporarily allow **Install unknown apps** for
the browser/file manager you use to open it. No Play Store subscription or
developer account is needed by the recipient.

The APK contains the application, not the developer's personal records. Offline
tracking works immediately. The 1.5 source adds account-free local sync and
optional OneDrive sharing; see [sync setup](Sync-Setup.md). Older published
releases still use the historical Google integration.

## Obtainium

1. Install [Obtainium from its official source](https://obtainium.imranr.dev/).
2. Choose **Add app**.
3. Enter **https://github.com/joshjm/little-nest** as the source URL.
4. Keep prereleases disabled. If an APK filename filter is needed, use
   `^LittleNest\.apk$`. No custom API, release-title or architecture settings
   are required.
5. Add/install the app and permit Obtainium to install updates when Android asks.
   A GitHub token is not normally needed for this public repository; GitHub's
   unauthenticated API rate limits can temporarily delay checks.

Already installed Little Nest? Add the same repository to track it. **Do not
uninstall first.** Android retains local data during compatible signed updates.
Back up first; if Android reports a signature mismatch, stop rather than
uninstalling to work around it.

Obtainium is a separate update manager. Little Nest itself has no automatic
update checker. Its use does not add an SDK or analytics to Little Nest.

## Manual updates and integrity

The [releases page](https://github.com/joshjm/little-nest/releases) carries release
notes, one universal `LittleNest.apk`, and `SHA256SUMS`. Download the APK, not the
automatic source ZIP/TAR archives. Install over the existing app.

For a downloaded release, put both assets together and run:

```sh
sha256sum -c SHA256SUMS
```

The original release signing-certificate SHA-256 is:

```text
f515ea3fc3b0bc907ed1cda72f5d982ece498f6b40a4fff730982f15bd4e7f5e
```

This certificate fingerprint identifies the signer; it is different from the
APK file checksum, which changes per version. A checksum hosted alongside an APK
detects corruption but is not an independent guarantee against a compromised
release account.

## Maintainer: cut the next release

Signing stays on the trusted local machine. CI runs JVM tests, lint and a debug
build; it cannot publish a signed release and holds no production signing keys.

1. Increase both `versionCode` and `versionName` in `app/build.gradle`.
   Keep the application ID `app.littlenest`.
2. Write `releases/vVERSION.md` with changes and limitations.
3. Run `bash scripts/build-apk.sh`. Use the **original** private signing key.
   Do not generate a new key to update this application.
4. Run device acceptance on an owned emulator, including
   `python3 scripts/smoke-apk.py emulator-5554 --reset --care-plan` and
   `python3 scripts/smoke-nara-import.py emulator-5554`. Run
   `bash scripts/inspect-apk.sh` for local REA evidence.
5. Run `bash scripts/publish-release.sh --check-only VERSION`.
6. Commit the exact tested source and notes. Wait for the GitHub Android checks.
7. Tag that commit and push just the branch and intended release tag:

   ```sh
   git tag -a vVERSION -m "Little Nest VERSION"
   git push origin main
   git push origin vVERSION
   bash scripts/publish-release.sh VERSION
   ```

The publishing helper rejects a wrong package/version, a different signing
certificate, uncommitted files or an unpushed/mismatched tag. It copies the
existing signed APK into an ignored staging directory and creates a portable
checksum whose filename matches the asset.

Keep each release immutable: publish fixes as a higher version, not by replacing
an APK behind an existing tag. Stable releases are not drafts or prereleases and
are marked latest. One APK avoids asset-selection ambiguity in Obtainium.

Never publish personal CSV/JSON backups, databases, keystores, signing passwords,
OAuth credentials or local diagnostics containing private data. Only the two
explicit APK/checksum files are uploaded by the helper. Keep signing-key and
password backups securely outside GitHub. Developer-verification requirements
for sideloading can vary by country and Android rollout; see
[Android's current guidance](https://developer.android.com/developer-verification/guides).
