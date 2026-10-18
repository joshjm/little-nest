# Sync: local Wi-Fi and optional OneDrive

Little Nest works offline. Both sync options exchange immutable operations, not
the live SQLite database or an overwritable shared backup. Profiles, parent
logs, photos, edits, historical revisions and deletion tombstones are shared.
Both caregivers can read everything in the nest.

## Add your partner: one code for everything

**Family -> Your partner** is the single place to add the second caregiver.
One phone invites; the other joins. The same QR code sets up Wi-Fi pairing and,
if the inviting phone already has a OneDrive nest, OneDrive sharing too.

1. Install the same signed APK on both phones. Export backups first if you
   already have records. The joining phone should not create profiles first.
2. Connect both phones to the same private Wi-Fi network or hotspot. Some guest
   networks/hotspots isolate clients; use a network that allows phone-to-phone
   connections. (Skip this if you only use OneDrive.)
3. Phone one: **Family -> Your partner -> Invite partner**. If no caregiver name
   is set yet, the app asks for one first.
4. Phone two: tap **Join partner** (on the welcome screen or in Family), scan the
   code and confirm sharing. Camera access is only needed for scanning.
5. Keep both apps open until **Last local sync** appears and pending changes
   reach zero. The Wi-Fi part of the code expires after five minutes and only
   works while the invite is on screen; Done/Back cancels it.

If the inviting phone has a OneDrive nest, the joining phone remembers it and
shows **Finish joining OneDrive nest**. The partner must first accept the
Microsoft invitation email (see below), then tap that button and sign in.

**Not in the same place?** With a OneDrive nest, use **Invite by email** in the
invite screen. Little Nest asks OneDrive to send the Microsoft invitation, then
opens your email app with a private join code. Your partner accepts the
Microsoft email, then uses **Join partner -> Or paste a code they sent you**.
Codes sent this way contain only the OneDrive nest: Wi-Fi pairing needs both
phones together because leaving the app stops its local listener. Older
OneDrive nest codes can also be pasted there.

## Local sync: no account, no internet

Pair once, not every time. While open, apps advertise/discover their paired peer,
check every 15 seconds and after saves, and exchange only missing operations.
The phone certificate is pinned and both sides authenticate using mutual TLS
and a shared random secret delivered by the QR invitation. Pairing codes grant
access: never post a screenshot publicly. Discovery advertises a stable device
certificate identifier on the local network, not health records or the secret.

Local sync is **not an always-on service**. Closing/backgrounding the app stops
the listener. If a phone is asleep, away or on another network, records stay
local until the phones reconnect with both apps open. Check Family on both
phones before handing off care. A zero pending count refers to acknowledged
local operations, not proof that the other caregiver has seen them.

**Disconnect local partner** stops future local access on this phone and keeps
all records and OneDrive configuration. Disconnect on both phones before
pairing again. A lost/unpaired phone retains already received records.

## OneDrive: sync while apart

Requires a build configured by the maintainer as described below and **two
personal Microsoft accounts**, each with OneDrive available. Work/school
accounts are not supported. Neither caregiver creates a developer project.

1. Phone one: **Family -> OneDrive sync -> Connect / reconnect OneDrive**.
   Sign in through your browser and review the permissions.
2. Choose **Create a shared OneDrive nest**. Creation and initial sync must finish.
3. Choose **Family -> Your partner -> Invite partner**. Together: let phone two
   scan the code, then use **Invite by email** to send the Microsoft
   invitation. Apart: use **Invite by email** directly. Enter the partner's
   personal Microsoft account email.
4. Phone two: accept the Microsoft invitation in a browser while signed in to
   the invited account. Then tap **Finish joining OneDrive nest** (after
   scanning) or paste the emailed code via **Join partner**, and sign in with
   that account. Joining uploads all existing local records too.
5. Use **Sync now** on both phones until both have caught up.

If folder creation loses its network response, inspect the owner's OneDrive
before trying again. An unused `Little Nest <family UUID>` folder may have been
created; do not merge folders by hand. Keep a backup and remove only a confirmed
unused folder before retrying creation.

The folder is created at the owner's OneDrive root. Do not place it under a
widely shared folder or add public/organization/group sharing. The app checks
that the permissions it receives describe at most two individual owner/editor accounts,
rejecting unrecognized members, inherited editor access, and public/organization
links. Specific-person links are allowed only when their individual grantees
can be identified. These are fail-closed
application checks, not an atomic Microsoft policy: **Microsoft only returns
a non-owner's own permissions**, so the partner cannot audit other grants.
The owner must maintain private two-person access; external changes can also happen
between checking permissions and transferring. Some account/API permission
representations may be rejected rather than silently relaxing privacy checks.
Only the owner should invite a partner. Remove stale access in OneDrive itself.

OneDrive keeps changes for a phone that is offline, so both phones do not need
to be online together. Foreground checks run every minute and after saves.
Android background jobs run at nominal 15-minute intervals and may be delayed
substantially. Sign-in expiry/revocation, storage quota and network failures
appear in Family; reconnect there when requested.

Microsoft sign-in requests `Files.ReadWrite`, `offline_access` and `openid`.
**Files.ReadWrite is broader than the nest folder**; grant it only to a build
you trust. The app uses its selected nest, but Microsoft grants broader file
access. The narrow per-account app folder is not used for cross-account sharing.
No client secret is embedded. Refresh credentials are encrypted using a key
in Android Keystore, excluded from backups and never included in JSON exports.
Signing out of Little Nest does not sign your browser out of Microsoft.

OneDrive holds readable JSON operations under Microsoft's normal storage
protections. **Little Nest does not provide end-to-end encryption for cloud
files.** Local transfers are encrypted independently.

**Disconnect OneDrive on this phone** removes the app's stored tokens and cloud
folder selection, stops its scheduled cloud job and leaves local pairing/data
intact. It does not revoke Microsoft's grant, remove partner access or delete
cloud history. Manage those through Microsoft/OneDrive.

## Using both options

Easiest: create the OneDrive nest on one phone first, then use **Invite
partner** once; a single scan sets up both. If you paired locally first, create
the nest later and use **Invite partner** again; the partner scans or pastes
the new code to join OneDrive. Alternatively, join the same OneDrive nest on both phones before
pairing locally. Do not independently create a nest on each phone.

Both transports use the same family identity and operation IDs. Repeated
delivery is idempotent; local and cloud delivery checkpoints are separate.
An attempted connection to a different family is rejected to avoid accidentally
bridging unrelated families. Disconnecting does not reset family identity:
export first, then clear app storage if deliberately changing families.

Concurrent edits of the same record resolve deterministically by Lamport
revision then operation ID; one edit wins, rather than a field-by-field merge.
Historical operations remain stored. Older revisions do not undo deletions,
but a deliberately newer edit can supersede an older deletion. The operation
log grows with edits/photos; there is no automatic compaction.

Local exchange currently allows 200,000 operations per phone and 2 MB per
operation. An interruption can leave some validated operations already merged;
retry resumes by comparing IDs. Invalid operations are rejected explicitly.

## Maintainer: register Microsoft sign-in once

This is publisher setup, **not a task for each family**.

The existing registration can be adopted and maintained with
[Terraform](Infrastructure.md), including architecture views generated through
TerraVision. Keep the same client ID used by release builds.

1. In Microsoft Entra's app-registration portal create **Little Nest** with
   supported account type **Personal Microsoft accounts only**.
2. Add the **Mobile and desktop applications** platform with custom redirect
   URI **`app.littlenest:/oauth`**. This is a public native client using
   authorization code + PKCE, not a web application or confidential client.
3. Configure Microsoft Graph **delegated** permission `Files.ReadWrite`.
   OpenID/offline scopes are requested during sign-in. Do not grant
   application-wide permissions and do not create/embed a client secret.
4. Record the **Application (client) ID**, which is public configuration.
   Set it in the release build environment:

   ```bash
   export ONEDRIVE_CLIENT_ID='YOUR-APPLICATION-CLIENT-ID'
   bash scripts/build-apk.sh
   ```

   Or use `./gradlew -PoneDriveClientId=YOUR-APPLICATION-CLIENT-ID ...`.
   Never commit tokens or signing keys. Both phones should use the same APK.
5. Complete Microsoft's applicable branding, privacy and publisher requirements
   and test real-account consent. Maintain the registration for future releases.

Builds without a client ID still build and work offline/locally, but show an
explicit OneDrive configuration notice and cannot start Microsoft sign-in.
The app does not use `sharedWithMe` (deprecated by Microsoft); pairing uses the
exact drive/item reference after the partner accepts the invitation.

References: [native-app OAuth](https://www.rfc-editor.org/rfc/rfc8252),
[Microsoft app registration](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app),
[invite API](https://learn.microsoft.com/en-us/graph/api/driveitem-invite),
[OneDrive permissions](https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/permissions_reference).

## Upgrade from Google Drive

See [Google migration](Google-Drive-Setup.md). Sync both old installations and
export backups before updating. Google operations and Play services are removed
from the new app; old cloud files/grants are not deleted. Local data and existing
backup formats remain compatible. All locally retained operations are eligible
for the newly selected transport, regardless of old Drive upload checkpoints.

## Two-phone acceptance before relying on sync

1. Back up. Create a profile on only one phone, pair locally, and confirm it
   appears on the other without creating a duplicate.
2. Log different events on each phone while disconnected. Reconnect with both
   apps open. Confirm both events, notes, units and caregiver attribution.
3. Test a photo, running/paused timer, edit and deletion. Check both phones
   converge and old data is not resurrected after reconnect/restart.
4. Close/reopen each app, change Wi-Fi/DHCP addresses, and verify rediscovery.
   Check expiry, wrong-code rejection and disconnect/re-pair behavior.
5. With a registered build, test real Microsoft sign-in and invitation with two
   accounts. Sync one phone while the other is offline, then sync the other.
6. Enable both transports for the same nest; confirm no duplicates. Test
   interrupted uploads, revoked access, full quota and unexpected extra sharing.
7. Check that your phones' battery policies delay background jobs visibly,
   rather than trusting an always-current status.

Simulated Graph tests are not proof of real Microsoft consent, permission
responses or account-to-account transfer. Those require the configured build
and your two accounts. Physical-phone Wi-Fi/hotspot behavior also needs this
acceptance check.
