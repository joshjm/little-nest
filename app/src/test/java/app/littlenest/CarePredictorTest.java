package app.littlenest;

import org.json.JSONObject;
import org.junit.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class CarePredictorTest {
    private static final ZoneId ZONE = ZoneId.of("Australia/Brisbane");
    private static final long MINUTE = 60_000L;
    private long time(String value) { return LocalDateTime.parse(value).atZone(ZONE).toInstant().toEpochMilli(); }
    private JSONObject event(String kind, long start, long end) {
        JSONObject event = new JSONObject();
        Records.put(event, "kind", kind); Records.put(event, "id", kind + start); Records.put(event, "start", start); Records.put(event, "end", end);
        return event;
    }
    private List<JSONObject> history() {
        List<JSONObject> events = new ArrayList<>();
        LocalDate today = LocalDate.of(2026, 10, 17);
        for (int day = 1; day <= 5; day++) {
            LocalDate date = today.minusDays(day);
            for (int hour : new int[]{6, 9, 12, 15, 18}) {
                long start = date.atTime(hour, 0).atZone(ZONE).toInstant().toEpochMilli();
                events.add(event(hour % 2 == 0 ? "breast" : "bottle", start, start + 10 * MINUTE));
            }
            for (int[] hours : new int[][]{{6,8},{10,11},{13,14},{16,17}}) {
                events.add(event("sleep", date.atTime(hours[0], 0).atZone(ZONE).toInstant().toEpochMilli(),
                    date.atTime(hours[1], 0).atZone(ZONE).toInstant().toEpochMilli()));
            }
        }
        events.add(event("bottle", time("2026-10-17T06:00"), time("2026-10-17T06:00")));
        events.add(event("breast", time("2026-10-17T09:00"), time("2026-10-17T09:10")));
        events.add(event("sleep", time("2026-10-17T06:00"), time("2026-10-17T08:00")));
        return events;
    }
    private CarePredictor.Window predict(List<JSONObject> events, String type, int feed, int wake, long now) {
        return CarePredictor.predict(events, new CarePredictor.Settings(feed, wake), now, ZONE).stream().filter(w -> w.type().equals(type)).findFirst().orElseThrow();
    }
    @Test public void learnsMilkStartIntervalsAndWakeToNapWindows() {
        var events = history(); long now = time("2026-10-17T09:15");
        var feed = predict(events, "feed", 0, 0, now); var nap = predict(events, "nap", 0, 0, now);
        assertTrue(feed.available()); assertTrue(nap.available());
        assertEquals(time("2026-10-17T12:00"), feed.target());
        assertEquals(time("2026-10-17T11:45"), feed.earliest()); assertEquals(time("2026-10-17T12:15"), feed.latest());
        assertEquals(time("2026-10-17T10:00"), nap.target());
        assertTrue(feed.samples() >= 10); assertTrue(feed.days() >= 3); assertTrue(feed.basis().contains("similar time of day"));
        assertTrue(feed.status(now).startsWith("In about")); assertEquals("Suggested window is now", feed.status(feed.target()));
        assertTrue(feed.status(feed.latest() + 1).contains("passed"));
    }
    @Test public void insufficientDataDoesNotInventAgeBasedAdvice() {
        assertFalse(predict(List.of(), "feed", 0, 0, time("2026-10-17T09:15")).available());
        var one = List.of(event("bottle", time("2026-10-17T09:00"), time("2026-10-17T09:00")));
        var window = predict(one, "feed", 0, 0, time("2026-10-17T09:15"));
        assertFalse(window.available()); assertTrue(window.message().contains("4 usable")); assertTrue(window.message().contains("2 days"));
    }
    @Test public void manyIntervalsOnOneDayAreStillLearning() {
        List<JSONObject> events = new ArrayList<>();
        for (int hour = 6; hour <= 18; hour += 2) events.add(event("bottle", time("2026-10-17T00:00") + hour * 60 * MINUTE, time("2026-10-17T00:00") + hour * 60 * MINUTE));
        assertFalse(predict(events, "feed", 0, 0, time("2026-10-17T18:15")).available());
    }
    @Test public void manualIntervalsWorkWithoutTrainingAndUseCorrectAnchors() {
        var events = List.of(event("breast", time("2026-10-17T09:00"), time("2026-10-17T09:20")),
            event("sleep", time("2026-10-17T06:00"), time("2026-10-17T08:00")));
        assertEquals(time("2026-10-17T11:30"), predict(events, "feed", 150, 90, time("2026-10-17T09:15")).target());
        assertEquals(time("2026-10-17T09:30"), predict(events, "nap", 150, 90, time("2026-10-17T09:15")).target());
    }
    @Test public void solidsPumpingAndFutureLogsDoNotResetOrTrainMilkEstimate() {
        var events = history(); long now = time("2026-10-17T09:15");
        var before = predict(events, "feed", 0, 0, now);
        events.add(event("solids", now - MINUTE, now - MINUTE)); events.add(event("pump", now - MINUTE, now - MINUTE));
        events.add(event("bottle", now + MINUTE, now + MINUTE));
        assertEquals(before, predict(events, "feed", 0, 0, now));
    }
    @Test public void activeOrPausedSessionsSuppressEstimatesEvenWithLaterCompletedLogs() {
        for (String state : List.of("running", "timerPaused")) {
            var events = history();
            JSONObject feed = event("breast", time("2026-10-17T07:00"), time("2026-10-17T07:00")); Records.put(feed, state, true); events.add(feed);
            JSONObject sleep = event("sleep", time("2026-10-17T05:00"), time("2026-10-17T05:00")); Records.put(sleep, state, true); events.add(sleep);
            assertFalse(predict(events, "feed", 180, 120, time("2026-10-17T09:15")).available());
            assertFalse(predict(events, "nap", 180, 120, time("2026-10-17T09:15")).available());
        }
    }
    @Test public void incompleteLatestRowsRequireReview() {
        var events = history(); long now = time("2026-10-17T09:15");
        for (String kind : List.of("bottle", "sleep")) {
            JSONObject event = event(kind, now - MINUTE, now - MINUTE); Records.put(event, "importIncomplete", true); events.add(event);
        }
        assertFalse(predict(events, "feed", 180, 120, now).available()); assertFalse(predict(events, "nap", 180, 120, now).available());
    }
    @Test public void staleAnchorsAndOldTrainingDoNotProduceFreshSuggestions() {
        var events = history();
        assertFalse(predict(events, "feed", 180, 120, time("2026-10-18T09:15")).available());
        assertFalse(predict(events, "nap", 180, 120, time("2026-10-18T09:15")).available());
        events.add(event("bottle", time("2026-11-17T09:00"), time("2026-11-17T09:00")));
        assertFalse(predict(events, "feed", 0, 0, time("2026-11-17T09:15")).available());
    }
    @Test public void nearbyTopupsAndSplitSleepDoNotResetAnchorsOrCreateTinyIntervals() {
        var events = history(); long now = time("2026-10-17T09:30");
        events.add(event("bottle", time("2026-10-17T09:25"), time("2026-10-17T09:25")));
        events.add(event("sleep", time("2026-10-17T08:10"), time("2026-10-17T08:20")));
        assertEquals(time("2026-10-17T12:00"), predict(events, "feed", 180, 120, now).target());
        assertEquals(time("2026-10-17T10:20"), predict(events, "nap", 180, 120, now).target());
    }
    @Test public void bedtimeIsNotPresentedAsADaytimeNap() {
        var events = List.of(event("sleep", time("2026-10-17T17:00"), time("2026-10-17T19:00")));
        var window = predict(events, "nap", 180, 120, time("2026-10-17T19:15"));
        assertFalse(window.available()); assertTrue(window.message().contains("bedtime"));
    }
    @Test public void predictionsAreOrderIndependentAndUseStableCycleUntilNewCare() {
        var events = history(); long now = time("2026-10-17T09:15");
        var original = predict(events, "feed", 0, 0, now); Collections.reverse(events);
        assertEquals(original, predict(events, "feed", 0, 0, now));
        assertEquals(original.cycle(), predict(events, "feed", 150, 120, now).cycle());
        events.add(event("bottle", time("2026-10-17T12:00"), time("2026-10-17T12:00")));
        assertNotEquals(original.cycle(), predict(events, "feed", 180, 120, time("2026-10-17T12:15")).cycle());
    }
    @Test public void medianResistsSingleExtremeInterval() {
        var events = history(); long now = time("2026-10-17T09:15");
        events.add(event("bottle", time("2026-10-11T06:00"), time("2026-10-11T06:00")));
        events.add(event("bottle", time("2026-10-11T13:30"), time("2026-10-11T13:30")));
        assertEquals(time("2026-10-17T12:00"), predict(events, "feed", 0, 0, now).target());
    }
    @Test public void invalidIntervalsAndAlertOptionsAreRejected() {
        for (int invalid : new int[]{-1, 1, 29, 721}) {
            try { new CarePredictor.Settings(invalid, 0); fail(); } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Feed")); }
        }
        for (int invalid : new int[]{-1, 1, 19, 481}) {
            try { new CarePredictor.Settings(0, invalid); fail(); } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Wake")); }
        }
        try { new CareReminders.Options(true, false, 121, 22, 6, new CarePredictor.Settings(0, 0)); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Advance")); }
        try { new CareReminders.Options(true, false, 15, 24, 6, new CarePredictor.Settings(0, 0)); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Quiet")); }
    }
    private CareReminders.Options options(boolean enabled, int lead, int start, int end) {
        return new CareReminders.Options(enabled, enabled, lead, start, end, new CarePredictor.Settings(0, 0));
    }
    private CarePredictor.Window window(long target) {
        return new CarePredictor.Window("feed", "cycle", target - 15 * MINUTE, target, target + 15 * MINUTE, 10, 3, "Test", "");
    }
    @Test public void alertIsBeforeEarliestWindowAndNeverRepeatsWithoutSnooze() {
        long now = time("2026-10-17T09:00"); var window = window(time("2026-10-17T12:00")); var options = options(true, 15, 22, 6);
        assertEquals(time("2026-10-17T11:30"), CareReminders.nextAlert(window, options, now, ZONE, "", "", "", 0));
        assertEquals(0, CareReminders.nextAlert(window, options, now, ZONE, "cycle", "", "", 0));
        assertEquals(0, CareReminders.nextAlert(window, options, now, ZONE, "", "cycle", "", 0));
        assertEquals(0, CareReminders.nextAlert(window, options(false, 15, 22, 6), now, ZONE, "", "", "", 0));
    }
    @Test public void snoozeOverrulesFiredButNotWindowExpiryOrDismissal() {
        long now = time("2026-10-17T12:00"); var window = window(now); var options = options(true, 15, 22, 6);
        assertEquals(now + 10 * MINUTE, CareReminders.nextAlert(window, options, now, ZONE, "cycle", "", "cycle", now + 10 * MINUTE));
        assertEquals(0, CareReminders.nextAlert(window, options, now, ZONE, "cycle", "", "cycle", now + 20 * MINUTE));
        assertEquals(0, CareReminders.nextAlert(window, options, now, ZONE, "", "cycle", "cycle", now + 10 * MINUTE));
        assertEquals(0, CareReminders.nextAlert(window, options, window.latest() + 1, ZONE, "", "", "", 0));
    }
    @Test public void quietHoursDelayOnlyWhileWindowStillValid() {
        long now = time("2026-10-17T05:00"); var options = options(true, 15, 22, 6);
        assertEquals(time("2026-10-17T06:00"), CareReminders.nextAlert(window(time("2026-10-17T06:00")), options, now, ZONE, "", "", "", 0));
        assertEquals(0, CareReminders.nextAlert(window(time("2026-10-17T05:00")), options, now, ZONE, "", "", "", 0));
        assertEquals(time("2026-10-17T12:00"), CareReminders.afterQuietHours(time("2026-10-17T10:00"), 9, 12, ZONE));
        assertEquals(time("2026-10-17T23:00"), CareReminders.afterQuietHours(time("2026-10-17T23:00"), 0, 0, ZONE));
        assertEquals(time("2026-10-18T06:00"), CareReminders.afterQuietHours(time("2026-10-17T23:00"), 22, 6, ZONE));
    }
    @Test public void quietHoursRespectDstAndElapsedIntervalsRemainRealTime() {
        ZoneId newYork = ZoneId.of("America/New_York");
        long beforeJump = LocalDateTime.parse("2026-03-08T01:30").atZone(newYork).toInstant().toEpochMilli();
        long end = LocalDateTime.parse("2026-03-08T06:00").atZone(newYork).toInstant().toEpochMilli();
        assertEquals(end, CareReminders.afterQuietHours(beforeJump, 22, 6, newYork));
        var feed = event("bottle", beforeJump, beforeJump);
        var result = CarePredictor.predict(List.of(feed), new CarePredictor.Settings(180, 0), beforeJump, newYork).get(0);
        assertEquals(beforeJump + 180 * MINUTE, result.target());
    }
}
