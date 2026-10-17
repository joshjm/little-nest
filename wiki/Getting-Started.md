# Install and everyday use

## Install on both phones

1. Download [LittleNest.apk](https://github.com/joshjm/little-nest/releases/latest/download/LittleNest.apk)
   and copy it to each phone using USB,
   Quick Share, a private Drive upload, or another trusted transfer method.
2. Open the file on each phone. If Android asks, temporarily enable **Install
   unknown apps** for the app used to open the APK.
3. Confirm installation, then disable that installation permission again.
4. Open **Little Nest**. Android 9 or newer is required. Google Play services
   are needed for Drive, but not for offline tracking.
5. Follow [Drive setup](Google-Drive-Setup.md) if you want shared storage.

For update notifications and installation through Obtainium, follow
[GitHub / Obtainium releases](Releases.md).

For USB installation with developer mode and USB debugging enabled:

```bash
source scripts/env.sh
adb devices
adb -s YOUR_PHONE_SERIAL install -r artifacts/LittleNest.apk
```

Select the exact device serial, especially with two phones connected.

### Updates

Install a newer APK over the old one, using the same application ID and signing
key. Do not uninstall first: uninstalling clears the local database. Before
updating, sync and export a JSON backup. Increase `versionCode` for future builds.
If Android says signatures differ, stop and obtain a build signed with the
original key instead of uninstalling and losing unsynced data.

## Your first profile

Tap **Create a profile**, enter a name and select **Baby** or **Parent**.
Birth/due date is optional. Parent stages are pregnancy, postpartum and parent.
Use the profile selector at the top to switch between children, twins and parents.
Set your name under **Family → Caregiver name** on each phone.

Have a supported activity export? Use **Family → Import activity CSV** before creating duplicate
profiles. Follow the [import guide](Importing-CSV.md), including the option to
continue the baby's current sleep from its original start time.

If you are joining your partner, pair and sync before creating profiles to avoid
creating the same baby twice. Distinct offline profiles are intentionally not
deduplicated by name.

## Log care

1. On **Today**, tap an activity.
2. Tap its start/end times to choose a date and time.
3. Fill in the relevant fields. Numeric values cannot be negative.
4. Add optional notes and a photo, then tap **Save**.
5. For a live session, tap **Start timer** instead.

### Timers

Breastfeeding and pumping track each side. Use **Switch side** to move from left
to right. Pumping also supports simultaneous **Both** sides and separate output
amounts. Timers support **Pause**, **Resume** and **Finish**.

Timers are persisted locally and can be handed off after syncing. They continue
to measure time while the app is closed; no continuous background process is
required. Pauses are excluded from recorded timer durations and sleep intervals.
There is at most one running timer per activity kind per profile on one phone.
Two offline phones can independently start separate sessions; after syncing,
review and remove any accidental overlap rather than assuming it was deduplicated.

Pause before editing. Adjusting start/end times converts the timing to a manual
interval; existing side totals remain a record of the original timed segments.
Keep automatic date/time enabled on both phones for meaningful timer displays.

### Activities and units

Choose bottle milk type and ml/fl oz, wet/dirty/mixed/dry diapers, stool/rash
details, sleep type, food/reaction, growth units, medications and more.
The food list includes common foods and allergens with an **Other** option.
It is a logging convenience, not feeding or allergy guidance.

**Family → Display units** sets chart units and defaults for new entries.
Each saved entry retains its original units. **Choose visible activities**
hides unused activity buttons without deleting their history.

### Photos

Pick an image through Android's document picker. No blanket media or camera
permission is requested. Images are resized to at most 1,000 pixels on the long
edge and re-encoded as JPEG (metadata is not deliberately copied).
One compressed photo per event, at most 750 KB, is stored inside the record.
Large/unreadable images produce an explicit error. Open **History → Edit** to
see an attached photo. Full-resolution originals are not retained.

## History, growth and patterns

- **Today:** feeds, bottle milk, wet and dirty counts, sleep, last-care intervals,
  last breast side, wake window and recent activity. A mixed diaper contributes
  once to each of wet/dirty; those numbers are not a total diaper count.
- **History:** all dates or a selected calendar day, plus text search. Tap an
  entry for full details, editing or confirmed deletion. Large histories have
  previous/next controls, 100 entries per page; search covers all pages.
- **Trends:** seven-day sleep/feeding/milk charts, a sleep timeline, daily totals,
  this-week/previous-week comparison and growth plots. Growth charts show the
  latest seven measurements per measure. Multiple babies get a today's-totals
  comparison.
- Sleep is split across local midnight. Overlapping sessions count separately.
  Dates use the viewing phone's time zone. There are no inferred growth
  percentiles, developmental diagnoses or medical thresholds.

## Reminders

For learned feed/nap windows and optional advance alerts, use **Today → Coming
up → Care plan & advance alerts**. Alerts are off by default and configured
separately on each phone. See the [care-plan guide](Care-Plan.md) for learning,
quiet hours, snooze and limitations.

**Upcoming reminders & plans** on Today shows the next five explicit reminders
and future appointments, with an add/edit action.

Add a future reminder inside any activity. Optionally set a repeat interval in
whole minutes (up to one year). Appointment entries can be future-dated.
Enable notifications in **Family**, and check Android notification-channel and
battery settings if reminders do not arrive.

Reminders are scheduled locally on each phone after sync; both phones may notify.
Missed one-off reminders are not replayed after import, reboot or late sync.
Repeating reminders advance to the next interval. Android may delay inexact
alarms, especially during Doze or battery saving. **Do not rely on these for
time-critical medication or safety alerts.**

## Backups and privacy

**Family → Export this profile as CSV** opens Android's share/save chooser.
The CSV includes event fields, units, timestamps, caregiver and notes, but not
photo binary data. Spreadsheet formula-like text is escaped.

**Export complete JSON backup** includes every profile, operation, photo and
historical deleted revision. Save it somewhere private. **Restore / merge**
validates all records before changing the database; it merges, never blindly
replaces, and does not roll back newer edits. Backups up to 100 MB can be imported;
split/archive externally before exceeding this limit.
Phone-local care-plan settings and alert state are not part of this backup.

Local data lives in Android's private app storage, protected by Android/device
security, not separate app-level encryption or an app PIN. Automatic OS backup
and device transfer are disabled to avoid duplicating identity/sync state.
Drive storage uses Google's normal access controls and encryption; this app
does not implement end-to-end encryption.

Delete a profile from **Family** only after exporting anything you want to keep.
The profile and its current logs are deleted locally and shared as deletions.
This is not a forensic erasure of Drive history or older backups.

## Little Nest appearance

Today groups activities into **Feed, Sleep, Pump, Diaper, Growth,
Health, Routines and Memories** sections. Tap an activity tile to log care, or
**Add** next to a section heading to choose an activity. Hidden
activities stay hidden. Parent profiles have the corresponding parent sections.
The bottom navigation keeps the same Today, History, Trends and Family screens,
with a forest-green selected tab. Profile switching is in the name header below
the Little Nest masthead. The warm paper palette, bold sans-serif type, woven-nest
mark and rounded activity tiles are Little Nest's own visual identity.

The visual refresh does not migrate the database, change sharing or require
Google reauthorization. Install the update directly over the existing app.
