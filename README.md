# Little Nest

An original, offline-first Android baby and parent tracker for a family of two
caregivers. Inspired by the publicly documented capabilities of Nara Baby;
not affiliated with Nara and not a copy of its code, artwork, or branding.

## Install on your two phones

Download the signed **[LittleNest.apk](https://github.com/joshjm/little-nest/releases/latest/download/LittleNest.apk)**.
Send the same file to both phones and open it to install. Requires Android 9+
(API 28). No Play Store developer account or subscription is required.

**Obtainium:** add `https://github.com/joshjm/little-nest` as the app source.
Stable releases contain one universal APK. See
[installation, updates and release maintenance](wiki/Releases.md).

**Tracking works immediately without an account. Drive sync requires a one-time
Google Cloud/OAuth setup for your two Google accounts.** No credentials or
Google project have been supplied, so live Google consent and account-to-account
sync cannot be preconfigured or claimed as verified.

Start with the **[small wiki](wiki/Home.md)**:

- [Install and everyday use](wiki/Getting-Started.md)
- [Google Drive setup and partner pairing](wiki/Google-Drive-Setup.md)
- [Import Nara Baby CSV data](wiki/Importing-Nara.md)
- [Feed/nap windows and advance alerts](wiki/Care-Plan.md)
- [Feature research and coverage](wiki/Feature-Coverage.md)
- [Build, verification and maintenance](wiki/Development.md)
- [GitHub releases and Obtainium updates](wiki/Releases.md)

## Included

- Breastfeeding with left/right timers, bottle amounts, pumping, and first foods.
- Wet/dirty/mixed/dry diapers, rash and stool details.
- Sleep timers, pauses, wake windows, daily/weekly charts and a sleep timeline.
- Personalized milk-feed/nap windows, opt-in advance alerts, quiet hours,
  snooze/dismiss and upcoming appointments/reminders.
- Growth measurements/charts, medicines, vaccinations, temperature and visits.
- Milestones, routines, tummy time, baths, stories, journals and photos.
- Pregnancy/postpartum mood, symptoms, vitals, meals, hydration and self-care.
- Multiple baby/parent profiles, child comparison and caregiver attribution.
- Editable history, search, local reminders, CSV export and JSON backup/restore.
- Nara CSV import with preview, duplicate prevention and ongoing sleep recovery.
- Two-Google-account Drive folder sharing; offline operations merge without
  replacing the other phone's complete dataset.

No ads, analytics, app backend, remote notifications, public feeds, subscriptions,
or connections to Nara's service. Parent logs are shared with your partner too.

## Version 1.3 care planning

**Today → Coming up** learns suggested milk-feed and nap windows from recent
logs, including imported data. Tap a window for its basis and quick logging.
Use **Care plan & advance alerts** to set optional manual intervals and turn on
alerts separately on each phone. Defaults are alerts off, 15-minute advance notice
and quiet hours 22:00–06:00. See the [care-plan guide](wiki/Care-Plan.md).

Suggestions are pattern estimates, not medical advice or a reason to delay
feeding. Android can delay alerts. Install this same-key update over the existing
app without uninstalling.

## Nara data import (added in 1.2)

Choose **Family → Import Nara Baby CSV**. The importer was checked locally against
a large private export, which is not included in this repository. It preserves source columns and adds
mixed-bottle/formula details, total/per-side pumping units, multi-select diaper
details, manual breastfeeding side durations and profile sex/adjusted date.
Ongoing sleep can continue from its original start time. Review destination
profiles before importing; Nara's profile-less pumping logs get their own
Pumping profile by default. Repeat imports preserve edits and deletions.
See the [import guide](wiki/Importing-Nara.md) for exact behavior and limitations.

Install over the previous release without uninstalling. The same signing key
is retained, and the database update preserves existing data while adding
indexes and paged history for large exports.

## Nara-inspired styling

The interface now uses a white/navy palette, serif headings, pastel section
headers, compact icon rows and circular add controls based on Nara's official
Android screenshot gallery. It retains Little Nest's name and original icons.
This styling is retained in the import update.

See the [feature guide](wiki/Feature-Coverage.md) and
[care-planning guide](wiki/Care-Plan.md). Build artifacts and local diagnostic
screenshots are intentionally excluded from the source repository.

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
