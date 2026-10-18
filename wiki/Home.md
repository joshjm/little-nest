# Little Nest wiki

A private baby and parent tracker you can install on both partners' Android
phones. Tracking and local Wi-Fi sync work without accounts or internet.
Optional OneDrive sharing keeps phones up to date while apart.

1. **[Getting started](Getting-Started.md)** — APK installation and daily use.
2. **[Sync setup](Sync-Setup.md)** — local QR pairing, OneDrive and publisher setup.
3. **[Feature coverage](Feature-Coverage.md)** — research, included features,
   deliberate differences and limitations.
4. **[Development](Development.md)** — tools, builds, signing, tests and REA.
5. **[Import activity data](Importing-CSV.md)** — CSV migration, ongoing sleep,
   source-field coverage and repeat-import behavior.
6. **[Feed and nap planning](Care-Plan.md)** — personalized windows, upcoming
   care, manual intervals and opt-in advance alerts.
7. **[GitHub / Obtainium releases](Releases.md)** — downloads, updates,
   checksums and maintainer release instructions.
8. **[Growth charts](Growth-Charts.md)** — WHO percentiles, first-entry guides,
   adjusted-age viewing, research and interpretation.
9. **[Screenshots](Screenshots.md)** — every screen in day and night mode, and
   how to make new screenshots with synthetic demo data.
10. **[Infrastructure and architecture](Infrastructure.md)** — import-first
    Terraform for the publisher registration and TerraVision interaction views.

## Important before you start

- Install the **same signed APK** on both phones.
- Pair locally once. For cloud sharing, use **one OneDrive nest**, created by
  one partner; do not create separate nests on each phone.
- The owner invites the second personal Microsoft account as an editor.
- Both people can see **all profiles, photos, pregnancy and postpartum logs**.
- Sync is eventual, not a live messaging service. Open both apps and check
  **Family** before a caregiver handoff. Local sync requires both apps open on
  the same reachable Wi-Fi/hotspot.
- Keep complete JSON backups somewhere safe. Little Nest's own CSV is a readable
  report, not a restorable backup; supported activity CSVs have a separate importer.
- An entry deleted in the app is hidden on both phones after syncing, but its
  historical revisions remain in the immutable operation log and JSON backups.
- A lost phone can retain offline data. Disconnect local pairing, remove its
  OneDrive access and use the
  device's remote-lock/erase facilities if available.
