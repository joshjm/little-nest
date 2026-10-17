# Little Nest

An offline-first Android baby and parent tracker for two caregivers.
Keep feeds, sleep, little milestones and your own wellbeing together in a
private shared nest.

## Screenshots

Android screenshots of the **1.4 design**, using synthetic demo data only.
Version 1.5 retains this design and adds the planning comparisons described below.
The Family screenshot predates the 1.6 sync changes described below.
Select a screenshot to view it full-size.

| Today & activity tiles | Activity history | Sleep trends |
|:---:|:---:|:---:|
| [<img src="docs/screenshots/today.png" alt="Today with upcoming care and feed activity tiles" width="240">](docs/screenshots/today.png) | [<img src="docs/screenshots/history.png" alt="History with search and saved activity details" width="240">](docs/screenshots/history.png) | [<img src="docs/screenshots/trends.png" alt="Trends with a sleep timeline and daily sleep chart" width="240">](docs/screenshots/trends.png) |

| Family & preferences | Bottle logging | Feed planning |
|:---:|:---:|:---:|
| [<img src="docs/screenshots/family.png" alt="Family profiles, preferences and private Drive sync settings" width="240">](docs/screenshots/family.png) | [<img src="docs/screenshots/bottle-form.png" alt="Bottle entry form with amounts, units and milk components" width="240">](docs/screenshots/bottle-form.png) | [<img src="docs/screenshots/care-details.png" alt="Suggested feed window with its basis and snooze or dismiss controls" width="240">](docs/screenshots/care-details.png) |

## Install on your two phones

Download the signed **[LittleNest.apk](https://github.com/joshjm/little-nest/releases/latest/download/LittleNest.apk)**.
Send the same file to both phones and open it to install. Requires Android 9+
(API 28). No Play Store developer account or subscription is required.

**Obtainium:** add `https://github.com/joshjm/little-nest` as the app source.
Stable releases contain one universal APK. See
[installation, updates and release maintenance](wiki/Releases.md).

**Tracking and local Wi-Fi sync need no account.** In the 1.6 source build,
pair once using a private QR code and keep both apps open on the same Wi-Fi or
hotspot for automatic encrypted transfers. Optional OneDrive sync works while
apart and when the phones connect at different times. Its Microsoft app
registration is maintained once by the app publisher, not by each family.
Builds without that registration explicitly disable Microsoft sign-in.
Google Drive integration and the Google Play services dependency are removed.

Start with the **[small wiki](wiki/Home.md)**:

- [Install and everyday use](wiki/Getting-Started.md)
- [Local pairing, OneDrive and maintainer setup](wiki/Sync-Setup.md)
- [Import activity CSV data](wiki/Importing-CSV.md)
- [Feed/nap windows and advance alerts](wiki/Care-Plan.md)
- [Feature research and coverage](wiki/Feature-Coverage.md)
- [Build, verification and maintenance](wiki/Development.md)
- [GitHub releases and Obtainium updates](wiki/Releases.md)
- [Terraform infrastructure and TerraVision architecture views](wiki/Infrastructure.md)

## Included

- Breastfeeding with left/right timers, bottle amounts, pumping, and first foods.
- Wet/dirty/mixed/dry diapers, rash and stool details.
- Sleep timers, pauses, wake windows, daily/weekly charts and a sleep timeline.
- Personalized milk-feed/nap windows, opt-in advance alerts, quiet hours,
  snooze/dismiss and upcoming appointments/reminders.
- Two planning views: a moving-average daily schedule and history-adjusted
  timing after similar short/late naps or feeds.
- Growth measurements/charts, medicines, vaccinations, temperature and visits.
- Milestones, routines, tummy time, baths, stories, journals and photos.
- Pregnancy/postpartum mood, symptoms, vitals, meals, hydration and self-care.
- Multiple baby/parent profiles, child comparison and caregiver attribution.
- Editable history, search, local reminders, CSV export and JSON backup/restore.
- Activity CSV import with preview, duplicate prevention and ongoing sleep recovery.
- Two-caregiver local Wi-Fi pairing and optional personal-account OneDrive
  sharing, usable together for the same nest. Offline operations merge without
  replacing the other phone's complete dataset.

No ads, analytics, app backend, remote notifications, public feeds or subscriptions.
Parent logs are shared with your partner too.

## Version 1.6: local and OneDrive sync

Local pairing uses mutual TLS, pinned device certificates and a private,
five-minute QR invitation (with copy/paste fallback). Paired phones reconnect
through local discovery and check every 15 seconds while the app is open.
Family shows pending local changes, last successful transfer and explicit errors.
There is no always-on local background service.

OneDrive uses browser-based Microsoft sign-in with PKCE and device-keystore
protected credentials. The maintainer supplies the public application client ID
at build time; no client secret is used. OneDrive files are not end-to-end
encrypted by Little Nest. Personal Microsoft accounts are supported, not
work/school accounts. Cloud background jobs remain best-effort, not instant push.

Existing Google users keep all local data but must pair locally or connect a
new OneDrive nest. Old Google files and sharing permissions are not deleted.
Sync both old installations and export a backup before updating. See
[setup and two-phone acceptance](wiki/Sync-Setup.md); live Microsoft sign-in,
invitations and real-account transfer require a registered build and two accounts.

## Version 1.4: Little Nest's own look

The redesign is included in the
[1.5.0 release](https://github.com/joshjm/little-nest/releases/tag/v1.5.0),
alongside the planning comparisons below.

Warm paper surfaces, forest-green navigation, bold sans-serif headings and an
original woven-nest mark give Little Nest its own identity. Care activities use
rounded tiles with small icon badges, not colored section strips. Forms, charts,
history and settings share the same palette and readable type.

The visual refresh preserves tracking, timers, reminders and imported history.
Install over an existing release without uninstalling to keep local records.

## Version 1.5: compare normal and adjusted planning

**Today → Coming up** shows a **Normal schedule** (moving-average clock time
for the next daily nap/feed number) and **History-adjusted** timing based on
similar early/late or short/long care in recent logs. It explains earlier/later
shifts and shows insufficient evidence rather than guessing.

These are observed patterns, not measured sleep or nutritional debt. Bottle
volumes can be compared, but breastfeeding minutes are never treated as intake.
No catch-up amounts or medical schedule are prescribed. Existing interval-based
alerts remain unchanged. See the [planning guide](wiki/Care-Plan.md) for sample
requirements, grouping, assumptions and limitations.

## Version 1.3 care planning

**Today → Coming up** learns suggested milk-feed and nap windows from recent
logs, including imported data. Tap a window for its basis and quick logging.
Use **Care plan & advance alerts** to set optional manual intervals and turn on
alerts separately on each phone. Defaults are alerts off, 15-minute advance notice
and quiet hours 22:00–06:00. See the [care-plan guide](wiki/Care-Plan.md).

Suggestions are pattern estimates, not medical advice or a reason to delay
feeding. Android can delay alerts. Install this same-key update over the existing
app without uninstalling.

## Activity CSV import

Choose **Family → Import activity CSV**. The importer was checked locally against
a large private export, which is not included in this repository. It preserves source columns and adds
mixed-bottle/formula details, total/per-side pumping units, multi-select diaper
details, manual breastfeeding side durations and profile sex/adjusted date.
Ongoing sleep can continue from its original start time. Review destination
profiles before importing; profile-less pumping logs get their own
Pumping profile by default. Repeat imports preserve edits and deletions.
See the [import guide](wiki/Importing-CSV.md) for the supported schema and limitations.

Install over the previous release without uninstalling. The same signing key
is retained, and the database update preserves existing data while adding
indexes and paged history for large exports.

See the [feature guide](wiki/Feature-Coverage.md) and
[care-planning guide](wiki/Care-Plan.md). Curated demo screenshots are included
above; build artifacts and other local diagnostic captures remain excluded from
the source repository.

## Build

```bash
# Linux: user-local SDK/tools; running this accepts Android SDK licenses.
bash scripts/bootstrap.sh
bash scripts/build-apk.sh

# Actual REA/JADX APK analysis, not just a documentation checklist:
bash scripts/inspect-apk.sh
```

Signing keys live outside the project. **Back up the signing key and password**
before moving to another computer; updates must use the same key.

This is a personal health log, not a medical device or a source of medical
recommendations. Reminders can be delayed by Android. Export backups regularly.
