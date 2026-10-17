# Google Drive setup: two people, two accounts

Offline tracking needs no setup. Google Drive sync needs a Google Cloud project
you control, plus Android OAuth registration for the APK's signing certificate.
There is no app server, client secret, Firebase dependency or paid backend.
Google can change console labels and OAuth rules.

## 1. Register the Android app once

Use [Google Cloud Console](https://console.cloud.google.com/):

1. Create/select a project and enable **Google Drive API**.
2. Open **Google Auth Platform / OAuth consent screen**. Configure an external
   application for personal use, with a name and support/developer email.
3. While the app is in **Testing**, add **both Google account emails** as test users.
4. Add the scope `https://www.googleapis.com/auth/drive`.
5. Create an **OAuth client ID → Android** with:
   - Package name: **`app.littlenest`**
   - SHA-1: the **Signer #1 certificate SHA-1 digest**, printed by the
     `apksigner` command below. The original published build uses
     `64ea198bcb9269878a0b5df8368f9d648251a7b6`.
6. Install the same release APK on both phones, with recent Google Play services.

The Android client is recognized by package and signing SHA-1. There is no
secret or client ID to paste into this source tree, and no
`google-services.json` is needed. A debug build uses a different signing
certificate and requires its own Android OAuth client.

To print the signing fingerprint again:

```bash
source scripts/env.sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs artifacts/LittleNest.apk
```

### Why the broad Drive permission?

`drive.appdata` cannot be shared between different people's accounts.
`drive.file` by itself does not give this folder-ID pairing flow access to files
created by the partner: it needs a separate Google Picker per-file authorization
flow. This implementation uses the full Drive scope to operate on the one
selected shared folder across two accounts. **The grant is broader than the
application's own folder restriction.** Only authorize a build you trust.

The implementation lists/reads/writes its marked family folder and its
`nest-op-*.json` files. It does not enumerate unrelated folders, upload contacts,
or send data to any non-Google endpoint.

Google classifies this as a restricted scope. For private use with two test
accounts, Google may show an unverified-app warning. Only proceed if you created
the project and trust this APK. Testing-mode grants involving Drive may expire
after seven days, requiring consent again. Public distribution or changing
publishing status can entail verification requirements; a personal APK does
not bypass Google's policies. A Workspace administrator can block this scope.

## 2. Phone one: create and invite

1. Open **Family → Caregiver name** and set your name.
2. Tap **Connect / reconnect Google** and authorize your own Google account.
3. Tap **1. Create a shared nest**. Wait for initial sync.
4. Tap **2. Invite partner by Google email** and enter their Google account.
5. Tap **Copy pairing folder ID**, then send that ID to your partner privately.

The folder is created at the top level of the owner's Drive and carries a
Little Nest schema marker. It is owned by the creator, with the partner added as
a writer. Do not move it into a more widely shared folder or a Shared Drive.

If creating a nest fails, check your Drive before trying again: a successful
Google request with a lost response may have created an unused folder.
Keep the folder reported in the app; do not merge two family folders by hand.

## 3. Phone two: join

1. Install the same APK.
2. Set a different caregiver name under **Family**.
3. Accept/view the owner's Drive sharing invitation if needed.
4. Tap **Or join partner's nest**.
5. Paste the folder ID (or its `https://drive.google.com/drive/folders/...` link).
6. Authorize **your own** Google account when asked.
7. Wait for sync. The owner's profiles/history should appear.

Joining shares all existing local data on the joining phone as well. For the
cleanest setup, let the owner create profiles, then join before logging on phone
two.

## 4. Check the handoff

1. On phone one, add a bottle with a distinctive note. Tap **Sync now**.
2. On phone two, tap **Sync now** and verify the amount, units and note.
3. Add a diaper on phone two; sync both and verify it on phone one.
4. Turn off connectivity on both phones, log different events, restore
   connectivity, then sync each phone twice. Both events should remain.
5. Edit and delete test records, sync again, and check convergence.
6. Test a photo, a running timer and a reminder on both phones.

**Live Google consent, invitations and two-real-account network sync have not
been executed in this development session:** no Google project/accounts were
supplied. The HTTP protocol, merge and failure/retry behavior are covered by
automated local tests, not a substitute for this credentialed acceptance check.

## How syncing works

- Every save/delete creates a UUID-named immutable JSON operation.
- Operations carry a Lamport revision counter. Independent events are retained.
- Concurrent edits to the *same* record resolve deterministically by revision
  counter, then operation ID; one edit wins. Historical revisions remain stored.
- Deleted records have tombstones, so replaying older revisions does not revive
  them. A deliberately newer edit can supersede an older deletion.
- Pull occurs before upload, so the local counter observes remote changes.
- Operations are uploaded one at a time; successful uploads/downloads are
  checkpointed per folder. An interrupted sync resumes without discarding local
  data. Duplicate remote copies of one operation are logically idempotent.
- No complete shared snapshot is overwritten. Photos travel within operations.
- Foreground polling is every minute and after saves. Background jobs are
  scheduled at 15-minute intervals but Android may defer them substantially.
- Tokens are kept in memory by the app, never in backups or logs. Google Play
  services manages its own authorization cache. Background jobs cannot show
  consent and report when foreground reconnection is needed.
- Before transfer, the app rejects public/domain/group sharing, more than two
  accounts, or a non-editor role. This is an application check, not an atomic
  Google security policy: the owner can alter permissions outside the app.

## Troubleshooting

| Symptom | Check |
|---|---|
| Authorization error / status 10 | Package name, release signing SHA-1, Drive API, consent configuration; allow propagation time |
| Consent canceled | Retry when ready; offline logs are unaffected |
| Access denied | Correct Google account, owner/editor permissions, test-user registration, Workspace rules, storage quota |
| Folder not found | Invitation sent to the exact account; correct folder ID; folder not trashed |
| Not a Little Nest folder | Create it from this app; do not use an arbitrary Drive folder |
| Third person / public sharing detected | In Drive remove extra, inherited, group, domain or link access; keep owner + one editor |
| App remains behind | Sync both phones; check Family's last-sync/error status and network; foreground reconnect if requested |
| Background sync delayed | Android battery optimization, background network restriction, Google Play services and consent state |
| Duplicate babies | Each phone created its own profile; sync first, move needed records manually, then delete the extra profile |

**Disconnect** stops this phone's scheduled sync but keeps its local logs. It
does not revoke the Google grant or remove the partner's folder access. Manage
grants in your Google Account's third-party access settings and sharing in Drive.
Do not connect a different family without exporting, disconnecting and clearing
app storage: all locally retained operations otherwise upload into the new nest.

Back up before removing access, changing accounts, deleting the folder or
clearing app storage. The immutable log grows with edits/photos; there is no
automatic log compaction or multi-folder migration.
