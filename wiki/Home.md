# Little Nest wiki

A private baby and parent tracker you can install on both partners' Android
phones. Everything works offline except Google Drive authorization, invitations
and synchronization.

1. **[Getting started](Getting-Started.md)** — APK installation and daily use.
2. **[Drive setup](Google-Drive-Setup.md)** — Google project, two accounts, pairing.
3. **[Feature coverage](Feature-Coverage.md)** — research, included features,
   deliberate differences and limitations.
4. **[Development](Development.md)** — tools, builds, signing, tests and REA.
5. **[Import Nara data](Importing-Nara.md)** — CSV migration, ongoing sleep,
   source-field coverage and repeat-import behavior.
6. **[Feed and nap planning](Care-Plan.md)** — personalized windows, upcoming
   care, manual intervals and opt-in advance alerts.
7. **[GitHub / Obtainium releases](Releases.md)** — downloads, updates,
   checksums and maintainer release instructions.

## Important before you start

- Install the **same signed APK** on both phones.
- Use **one family folder**, created by one partner; do not create a folder on
  each phone.
- The owner invites the second Google account as an editor.
- Both people can see **all profiles, photos, pregnancy and postpartum logs**.
- Sync is eventual, not a live messaging service. Check **Family → Sync now**
  on both phones before a caregiver handoff.
- Keep complete JSON backups somewhere safe. Little Nest's own CSV is a readable
  report, not a restorable backup; Nara's original CSV has a separate importer.
- An entry deleted in the app is hidden on both phones after syncing, but its
  historical revisions remain in the immutable Drive log and JSON backups.
- A lost phone can retain offline data. Remove its Google access and use the
  device's remote-lock/erase facilities if available.
