# Feed and nap planning

**Coming up** on each baby's Today screen now shows two planning comparisons
for **Next milk feed** and **Nap sweet spot** (version 1.5):

1. **Normal schedule:** a moving average of clock times.
2. **History-adjusted:** an estimate of what comes next after similar early/late
   or short/long care in this baby's own history.

Tap either care section for windows, evidence, any earlier/later shift and actions
to log care. Existing imported activity logs count toward learning.

These are personal pattern estimates, not deadlines, medical advice, guaranteed
predictions or a sleep-training schedule. **Follow hunger and sleep cues; never
delay a feed to match an estimate.** Follow your clinician's care plan.

## Normal schedule: moving-average clock time

- Uses daytime sessions in the last **14 local calendar days**, excluding today.
- Matches the next **daily nap/feed number**, not just the time since the latest
  event. After today's second daytime milk feed, for example, it averages the
  third milk feed on previous days.
- Needs that numbered event on **4 previous days**. Uses at most the **7 most
  recent matching days** with equal weight; older days drop out as new days arrive.
- The target is an arithmetic mean of **local clock times**, not a median or
  an elapsed 24-hour offset. This keeps clock-time schedules local across daylight
  saving changes.
- Shows a descriptive middle-half window widened to at least ±15 minutes. This
  is not a calibrated confidence interval. A passed window stays marked as passed.

Daily numbering assumes reasonably complete logs. Missed care can misalign
slots. Known incomplete historical days are excluded, and known incomplete care
today blocks comparisons until reviewed. This is a learned routine, not a
recommended schedule.

## History-adjusted: short/late-care comparison

This is a conservative alternative to inventing a physiological **sleep debt**
or **food debt**. It uses the previous session's timing and measured size:

- Compares the latest session's start with the average prior-session start for
  that daily slot. Early/late means **more than 20 minutes** before/after average.
- For sleep, compares **logged actual sleep duration**, excluding recorded
  pauses. Below **80%** of the prior-slot average is short; above **120%** is long.
- For bottle-only feeding sessions with complete positive volumes, compares
  **recorded ml**, normalizing fl oz and summing top-ups. Below 80% is smaller,
  above 120% is larger. These are relative log comparisons, not intake targets.
- Breastfeeding duration does **not** measure intake. A breastfeeding, mixed
  breast/bottle, or missing-volume session uses **timing only**, explicitly labelled.
  No calories, required milk, nutritional deficit or catch-up amount is inferred.
- Looks for **4 previous days** with the same early/on-time/late band and, when
  measured, the same short/normal/long band. It averages the observed interval
  to the next care event on those days.
- Anchors that interval at the **latest wake time** for naps, or the **latest
  feeding-session start** for milk. An early wake therefore moves the anchor;
  a historically shorter next wake interval after short naps can move it further.
  A late nap/feed can move the estimate later, or observed catch-up timing may
  bring it back toward the clock schedule.
- Shows how many minutes earlier/later this is than the normal schedule.
  Predictions can agree. With too few comparable days it says **Learning /
  unavailable**, explains the missing evidence, and does not invent a correction.

The 20-minute and 80–120% bands are implementation rules, **not medical
thresholds**. Similar past logs show an association, not what your baby needs.
This model does not calculate accumulated sleep deprivation, required daily
sleep, breastfeeding intake or nutritional needs. Missing logs, feeding method,
illness and changing routines can make either prediction unreliable.

## Shared guards and scope

Both comparisons use only this baby's local logs; there is no online model.
Milk includes bottles and breastfeeding, **not solids or pumping**. Top-ups within
30 minutes of the previous feed ending count as one session; sleep splits within
15 minutes are grouped. The first nap can use the preceding overnight sleep as
its wake anchor.

Active/paused care, a latest incomplete entry, future completed end times and
care over 18 hours old suppress comparisons. Today's care must be logged; the
model does not roll yesterday's schedule into tomorrow. Feed comparisons cover
05:00–22:00 and nap starts 05:00–20:00 local time. An adjusted target outside
today's daytime range is not shown. Bedtime and overnight comparisons are not
predicted.

## Existing interval estimate and alerts (unchanged)

The two comparisons are **additional planning information**. Existing alert
timing, opt-ins, manual intervals, snooze and dismiss keep the prior behavior.
Details explicitly label **Interval / alert estimate (unchanged)**. No extra
alerts are generated for the two comparisons.

The interval model used for alerts:

- Uses only this baby's logs on this phone. No online prediction service,
  advertising SDK or upload is involved.
- Learns from usable intervals in the last **14 days**, with at least **4 intervals
  across 2 days** before displaying a learned estimate.
- Milk feeds include breastfeeding and bottles, measured **start to start**.
  Solids and pumping do not reset the milk-feed estimate. Top-ups within
  30 minutes of the previous feed ending are grouped as one feeding session.
- Nap estimates measure **wake to the next daytime sleep**. Sleep entries
  separated by at most 15 minutes are grouped; the last end time anchors the
  next wake interval.
- Prefers enough observations from a similar time of day (within 4 hours).
  Otherwise uses a broader recent pattern. At most 80 recent intervals are used.
- Feed learning separates daytime (05:00–22:00) and overnight intervals.
  Naps use sleep starts between **05:00 and 20:00**, in the phone's local time.
  A predicted time outside that range is not shown as a nap; bedtime is not
  predicted.
- Uses a median typical time and a middle-half range, widened to at least
  **15 minutes either side**. This is not a calibrated probability/confidence
  interval. The details label an early, variable or more consistent pattern.
- Active or paused milk/sleep timers suppress that kind of estimate. Finish the
  timer when care ends. Incomplete latest imports require review. No fresh
  prediction is offered when the latest relevant care is over 18 hours old.
- If a window passes, it stays marked as passed rather than inventing a sequence
  of later feeds or naps. Log missing care or check baby's cues.

These practical cutoffs are implementation rules, not age-based recommendations.
Missing logs, changing routines, illness, travel and inconsistent timestamps can
make estimates unreliable. There is no age-based fallback schedule.

## Optional manual intervals

Open **Today → Care plan & advance alerts** (also under **Family** for a selected
baby).

- Leave manual intervals blank for interval-alert learning.
- Or enter your own milk-feed interval, **30–720 minutes**, and/or wake interval,
  **20–480 minutes**. These replace interval-alert learning for that kind of
  care; the normal and history-adjusted comparisons still learn from logs.
- A manual feed interval is measured from the most recent feeding-session start;
  a manual wake interval is measured from the latest completed sleep end.
- Manual windows are ±15 minutes. A recent completed anchor is still needed;
  active timers and stale/incomplete logs still block estimates.

## Turn on advance alerts

Suggestions appear automatically, but **both alert types are off by default**.

1. Open **Care plan & advance alerts** for the baby.
2. Enable milk-feed and/or nap alerts.
3. Choose advance notice, **0–120 minutes before the window begins**.
   The default is 15 minutes before the earliest suggested time, not before
   the interval-model median. The two comparison targets do not drive alerts.
4. Set quiet hours using local whole hours, 0–23. Default: **22:00–06:00**.
   Set the same start/end hour to disable quiet hours.
5. Save, allow Android notifications, and check **Android notification settings**
   if the app or its **Suggested care** channel is blocked.

During quiet hours, an alert moves to the end of quiet hours only if the window
has not expired; otherwise it is skipped. Enabling alerts while an unexpired
window is already due can trigger one immediately.

Each cycle notifies at most once unless explicitly snoozed. From the notification
or window details:

- **Snooze 15 min** delays this cycle. If that delay would outlast the window,
  no later alert is sent.
- **Dismiss cycle**, or swiping away the notification, suppresses that cycle.
  The displayed estimate remains available; new logged care creates a new cycle.
- Tapping the notification opens the correct baby's Today screen.

New logs, edits, deletes, sync and restore recalculate alarms. Boot, app updates,
clock/time-zone changes and periodic refresh also rebuild them. A stale alarm
rechecks the current logs before notifying. Today refreshes while open.

**Settings and snooze/dismiss state are per baby, per phone.** They are not synced
to your partner or included in JSON backups. The history is shared through
local or OneDrive sync, so each phone independently learns from its current synced copy. Configure
alerts on each phone where you want them; both may notify. Parent profiles do not
get baby feed/nap suggestions.

Android alarms are inexact. Doze, battery saving, a force-stopped app, revoked
permission or OEM restrictions can delay or prevent alerts. Open Little Nest
after enabling notifications or force-stopping it to refresh scheduling.
**Do not rely on these for time-critical feeding, medication or safety.**

## Other upcoming care

**Upcoming reminders & plans** on Today lists the next five reminder times and
future appointments. Tap a row to edit it, or **Add appointment / reminder**.
Other activities also support an explicit reminder and optional repeating
interval in their editor.

Explicit activity reminders use the existing **Care reminders** channel and are
separate from prediction opt-ins and prediction quiet hours. They sync with the
activity, are scheduled locally on both phones, and have the same Android timing
limitations. See [everyday reminders](Getting-Started.md#reminders).
