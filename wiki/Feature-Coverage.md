# Research and feature coverage

## Evidence and limits

Research performed 17 October 2026 using:

- [Google Play: Nara — Baby & Mom Tracker](https://play.google.com/store/apps/details?id=com.naraorganics.nara&pli=1):
  the primary feature description.
- [Nara's official FAQ](https://nara.com/pages/nara-baby-app-faqs):
  voice-command examples, explicitly Siri/iOS.
- [REA repository instructions](https://github.com/morluto/rea/blob/main/AGENTS.md),
  [REA skill](https://github.com/morluto/rea/blob/main/.agents/skills/reverse-engineer-anything/SKILL.md)
  and [Android analysis workflow](https://github.com/morluto/rea/blob/main/docs/android-analysis.md).
- [Google Android authorization documentation](https://developer.android.com/identity/authorization)
  and [Drive scope guidance](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
  for the replacement sync architecture.

The primary listing is **documented evidence**, not observed in-app behavior.
No original Nara APK, account or device session was supplied. No Nara code,
graphics, private API, account access or subscription bypass was used.
The user's own CSV export was subsequently inspected locally to build and test
the [activity import workflow](Importing-CSV.md), without uploading it.
Third-party search results were treated as leads rather than proof of Android
features. Exact UI parity and undocumented/internal Nara behavior are unknown.

### Independent visual identity (version 1.4)

Earlier styling experiments used public screenshots as visual references.
Version 1.4 replaces that direction with Little Nest's own design: warm paper
backgrounds, forest-green navigation, bold sans-serif headings, a woven-nest
mark and rounded activity tiles. Category colors are small icon accents, not
full-width pastel bars. Text-labelled Add controls replace circular plus buttons.
History, charts, forms and settings use the same independent design tokens.

No third-party artwork, font files, logos or screenshot pixels are bundled.
This is an independent design direction, not a legal opinion or a guarantee
about trademark, copyright or trade-dress clearance.

REA 6.5.0 was actually installed and executed locally. Its pinned, digest-verified
JADX 0.7.1 provider inspected **Little Nest's built APK**, including the decoded
manifest and Drive class inventory. These are artifact-bound static observations,
not reverse-engineering observations of Nara. See [verification](Development.md).

## Primary listing checklist

| Documented capability | Little Nest implementation |
|---|---|
| Breastfeeding, left/right timer and last side | Side-aware timer, switch/pause/resume, last side on Today |
| Bottle breastmilk/formula, time and amount | Mixed components, independent units, formula name, total precedence and timestamps |
| Pumping timer on each side | Left/right/both timing, independent side volume units and total-only output |
| Hide activities you do not use | Local visible-activity preferences |
| Solids and preloaded first foods | 40+ foods/allergens, Other, portion and reaction fields |
| Feeding patterns and schedules | Charts, learned milk-feed windows, manual intervals and opt-in advance alerts |
| Feeding notes and photos | Notes and one resized photo on every event |
| Wet, dirty and dry diapers; rash | Wet/dirty/mixed/dry, rash, blowout and multi-select stool colors/textures |
| Handoff and bowel history | Caregiver attribution, timestamps, search, CSV |
| Nap/night sleep timer and manual sessions | Sleep type, timer, manual start/end, persisted paused intervals |
| Sleep patterns by day/week | Seven-day charts, 24-hour sleep timeline, previous-week totals |
| Wake windows | Time since latest sleep ended, learned nap windows and optional advance alerts |
| Weight, height and head circumference | Dated unit-aware measurements and plots |
| Developmental milestones by age | Dated milestone categories, profile birth date and journal notes |
| Medical records, medication and vaccines | Doctor visit, medication/dose/route, vaccine/lot/provider, temperature |
| Tummy time, bath, story, custom routines | Dedicated logs plus named timed custom routines |
| First smile/steps/teeth and memories | Milestones and photo journals |
| Multiple children/twins | Separate profiles, quick switch and today's comparison |
| Multiple caregivers/devices | Deliberately limited to two Google accounts; shared Drive operations |
| Pregnancy vitals | Weight, blood pressure, glucose/unit and pulse |
| Pregnancy symptoms, cravings and aversions | Symptom categories, severity and notes |
| Mood, journal and photos | Mood categories, free text, journal/photo entries |
| Appointments and provider questions | Future appointments, questions and local reminders |
| Postpartum hydration, food and sleep | Dedicated parent activities and sleep timer |
| Postpartum moods and memories | Parent profile, mood, journals and photos |
| Yoga/exercise/self-care | Timed self-care choices and custom details |
| Share health with partners/doctors | Shared partner folder and profile CSV export |

## Original additions

- Google Drive instead of an app-hosted service.
- Fully usable offline without signup.
- JSON backup/merge and per-profile CSV export.
- Nara CSV migration with preview, destination mapping, safe repeat imports,
  original source fields and optional continuation of an ongoing sleep.
- Deterministic, operation-based offline merging and deletion tombstones.
- Local-only caregiver names, visibility and unit preferences.
- Private-folder checks that stop syncing when a third account is added.
- Local pattern-based [feed and nap planning](Care-Plan.md), per-phone advance
  alerts, quiet hours, snooze/dismiss and upcoming appointment/reminder lists.
- Moving-average daily clock schedules alongside history-adjusted timing after
  comparable short/late care, with explicit evidence and insufficient-data states;
  no physiological sleep/food debt or breastfeeding intake is inferred.

## Deliberate differences / not implemented

- No Nara sign-in, import from its private service, exact branding or copied
  screen layouts. Import uses the user's CSV export, not private-service access.
  Generic JSON restore supports Little Nest's format, not a Nara JSON format.
- No Apple Watch, Siri, iOS widgets or live activities. These are out of scope
  for Android and some require online features excluded by the request.
- No Android home-screen widget or wearable companion. Public research did not
  establish these as part of Nara's Android feature set.
- No clinician portal, medical recommendations, percentile reference database,
  age-based milestone recommendations, automatic scheduling, PDF report or AI.
- No multi-grandparent sharing, chat, social features, ads, analytics,
  subscriptions, push service or backend.
- One photo per event, resized rather than original-resolution cloud galleries.
- Foreground eventual sync and scheduled background jobs, not instant push sync.
- Same-record simultaneous edits resolve to one deterministic winner rather than
  a collaborative field-by-field editor or an interactive conflict dialog.
- No end-to-end encryption, separate app PIN, account-specific private parent
  journals, automatic compaction, remote erase or automated Drive migration.
- English UI; Android date labels follow the phone locale, but the text is not
  translated. Accessibility uses native controls, scalable text, chart descriptions
  and text equivalents, but has not had a complete assistive-technology audit.

This is a researched functional implementation of the documented categories,
not an assertion that every undocumented Nara feature has been reproduced.
