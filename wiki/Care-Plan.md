# Feed and nap planning

Version 1.3 adds **Coming up** to each baby's Today screen. Tap **Next milk feed**
or **Nap sweet spot** to see a suggested window, typical time, the evidence used
and actions to log care. Existing imported Nara logs count toward learning.

These are personal pattern estimates, not deadlines, medical advice, guaranteed
predictions or a sleep-training schedule. **Follow hunger and sleep cues; never
delay a feed to match an estimate.** Follow your clinician's care plan.

## How learning works

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

- Leave manual intervals blank to learn from logs.
- Or enter your own milk-feed interval, **30–720 minutes**, and/or wake interval,
  **20–480 minutes**. These replace learning for that kind of estimate.
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
   the median.
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
Drive, so each phone independently learns from its current synced copy. Configure
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
