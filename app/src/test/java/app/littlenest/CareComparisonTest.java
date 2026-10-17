package app.littlenest;

import org.json.JSONObject;
import org.junit.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static app.littlenest.CarePredictor.MINUTE;
import static org.junit.Assert.*;

public class CareComparisonTest {
    private static final ZoneId ZONE = ZoneId.of("Australia/Brisbane");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private long time(LocalDate date, int minutes) { return date.atStartOfDay().plusMinutes(minutes).atZone(ZONE).toInstant().toEpochMilli(); }
    private long today(int minutes) { return time(TODAY, minutes); }
    private JSONObject event(String kind, LocalDate date, int start, int end, int amount) {
        JSONObject event = new JSONObject();
        Records.put(event, "id", kind + date + start); Records.put(event, "kind", kind);
        Records.put(event, "start", time(date, start)); Records.put(event, "end", time(date, end));
        if (amount > 0) { Records.put(event, "amount", Integer.toString(amount)); Records.put(event, "unit", "ml"); }
        return event;
    }
    private List<JSONObject> feeds(boolean changed, int currentStart, int currentAmount) {
        List<JSONObject> events = new ArrayList<>();
        for (int day = 12; day >= 1; day--) {
            LocalDate date = TODAY.minusDays(day); boolean unusual = changed && day <= 4;
            events.add(event("bottle", date, 360, 360, 120));
            events.add(event("bottle", date, unusual ? 600 : 540, unusual ? 600 : 540, unusual ? 60 : 120));
            events.add(event("bottle", date, unusual ? 660 : 720, unusual ? 660 : 720, 120));
        }
        events.add(event("bottle", TODAY, 360, 360, 120));
        events.add(event("bottle", TODAY, currentStart, currentStart, currentAmount));
        return events;
    }
    private List<JSONObject> naps(boolean changed, int currentEnd) {
        List<JSONObject> events = new ArrayList<>();
        for (int day = 12; day >= 1; day--) {
            LocalDate date = TODAY.minusDays(day); boolean unusual = changed && day <= 4;
            events.add(event("sleep", date, 480, unusual ? 510 : 540, 0));
            events.add(event("sleep", date, unusual ? 600 : 660, unusual ? 660 : 720, 0));
        }
        events.add(event("sleep", TODAY, 480, currentEnd, 0)); return events;
    }
    private CareComparison.Comparison compare(List<JSONObject> events, String type, int now) {
        return CareComparison.predict(events, type, today(now), ZONE);
    }
    @Test public void normalClockScheduleIsMovingMeanOfLatestSevenMatchingDays() {
        var comparison = compare(feeds(true, 600, 60), "feed", 605);
        assertEquals(today(720) - Math.round(4 * 60 * MINUTE / 7.0), comparison.schedule().target());
        assertEquals(7, comparison.schedule().samples());
        assertTrue(comparison.schedule().basis().contains("milk feed #3"));
        assertTrue(comparison.schedule().basis().contains("Missing logs"));
    }
    @Test public void smallLateBottleLearnsAnEarlierReturnRatherThanInventingCatchupAmount() {
        var comparison = compare(feeds(true, 600, 60), "feed", 605);
        assertEquals(today(660), comparison.adjusted().target());
        assertTrue(comparison.adjusted().target() < comparison.schedule().target());
        assertEquals(4, comparison.adjusted().samples());
        assertTrue(comparison.adjusted().basis().contains("later than usual"));
        assertTrue(comparison.adjusted().basis().contains("smaller than usual"));
        assertTrue(comparison.shift().contains("earlier than the normal schedule"));
        assertTrue(comparison.adjusted().basis().contains("No calorie"));
    }
    @Test public void shortNapUsesEarlierWakeAndObservedShorterNextWakeInterval() {
        var comparison = compare(naps(true, 510), "nap", 515);
        assertEquals(today(660) - Math.round(4 * 60 * MINUTE / 7.0), comparison.schedule().target());
        assertEquals(today(600), comparison.adjusted().target());
        assertTrue(comparison.adjusted().basis().contains("logged sleep duration"));
        assertTrue(comparison.adjusted().basis().contains("Earlier waking is included"));
    }
    @Test public void delayedNapCanPushNextNapLaterWhenHistorySupportsIt() {
        var events = naps(false, 540);
        for (JSONObject e : events) {
            if (e.optLong("start") >= today(0)) {
                Records.put(e, "start", today(540)); Records.put(e, "end", today(600)); continue;
            }
            if (e.optLong("start") >= time(TODAY.minusDays(4), 0)) {
                Records.put(e, "start", e.optLong("start") + 60 * MINUTE);
                Records.put(e, "end", e.optLong("end") + 60 * MINUTE);
            }
        }
        var comparison = compare(events, "nap", 605);
        assertEquals(today(720), comparison.adjusted().target());
        assertTrue(comparison.adjusted().target() > comparison.schedule().target());
        assertTrue(comparison.shift().contains("later than the normal schedule"));
    }
    @Test public void noDeviationCanProduceTwoEqualPredictions() {
        var comparison = compare(feeds(false, 540, 120), "feed", 545);
        assertEquals(today(720), comparison.schedule().target());
        assertEquals(comparison.schedule().target(), comparison.adjusted().target());
        assertEquals("Matches the normal schedule", comparison.shift());
    }
    @Test public void novelShortCareDoesNotInventDebtCorrection() {
        var comparison = compare(naps(false, 510), "nap", 515);
        assertTrue(comparison.schedule().available()); assertFalse(comparison.adjusted().available());
        assertTrue(comparison.adjusted().message().contains("found 0"));
    }
    @Test public void tooFewMatchingDaysDoNotMasqueradeAsAdjustedPrediction() {
        var events = feeds(true, 600, 60);
        events.removeIf(e -> e.optLong("start") >= time(TODAY.minusDays(1), 0) && e.optLong("start") < today(0));
        var comparison = compare(events, "feed", 605);
        assertTrue(comparison.schedule().available()); assertFalse(comparison.adjusted().available());
        assertTrue(comparison.adjusted().message().contains("found 3"));
    }
    @Test public void sparseOrOneDayHistoryCannotCreateClockSchedule() {
        assertFalse(compare(List.of(), "nap", 600).schedule().available());
        var events = feeds(false, 540, 120);
        events.removeIf(e -> e.optLong("start") < time(TODAY.minusDays(3), 0));
        var comparison = compare(events, "feed", 545);
        assertFalse(comparison.schedule().available()); assertFalse(comparison.adjusted().available());
        assertTrue(comparison.schedule().message().contains("at least 4 previous days"));
    }
    @Test public void breastfeedingDurationNeverBecomesAnIntakeProxy() {
        var events = feeds(false, 540, 120);
        for (JSONObject e : events) { Records.put(e, "kind", "breast"); Records.put(e, "end", e.optLong("start") + 5 * MINUTE); }
        var comparison = compare(events, "feed", 550);
        assertEquals(today(720), comparison.adjusted().target());
        assertTrue(comparison.adjusted().basis().contains("timing only"));
        assertTrue(comparison.adjusted().basis().contains("minutes are not volume"));
    }
    @Test public void unknownBottleAmountsAreNotComparedAsZeroIntake() {
        var events = feeds(false, 540, 120);
        events.get(events.size() - 1).remove("amount");
        var comparison = compare(events, "feed", 545);
        assertTrue(comparison.adjusted().available()); assertTrue(comparison.adjusted().basis().contains("intake is unknown"));
        assertFalse(comparison.adjusted().basis().contains("smaller than usual"));
    }
    @Test public void currentKnownBottleNeedsEnoughMeasuredPriorBottles() {
        var events = feeds(false, 540, 120);
        for (JSONObject e : events) if (e.optLong("start") < today(0)) Records.put(e, "kind", "breast");
        var comparison = compare(events, "feed", 545);
        assertFalse(comparison.adjusted().available()); assertTrue(comparison.adjusted().message().contains("recorded bottle amounts"));
    }
    @Test public void topupsAreGroupedAndBottleUnitsNormalized() {
        var events = feeds(false, 540, 120);
        JSONObject current = events.get(events.size() - 1);
        Records.put(current, "amount", "2"); Records.put(current, "unit", "fl oz");
        events.add(event("bottle", TODAY, 555, 555, 60));
        var comparison = compare(events, "feed", 560);
        assertEquals(today(720), comparison.schedule().target()); assertEquals(today(720), comparison.adjusted().target());
    }
    @Test public void splitSleepIsGroupedAndPausedTimeDoesNotCountAsSleep() {
        var events = naps(false, 510);
        JSONObject first = events.get(events.size() - 1);
        Records.put(first, "elapsedMs", 30 * MINUTE);
        events.add(event("sleep", TODAY, 520, 550, 0));
        var comparison = compare(events, "nap", 555);
        assertEquals(today(670), comparison.adjusted().target());
        assertTrue(comparison.adjusted().basis().contains("100%"));
        Records.put(first, "elapsedMs", 5 * MINUTE);
        assertFalse(compare(events, "nap", 555).adjusted().available());
    }
    @Test public void runningPausedIncompleteAndStaleCareBlockBothComparisons() {
        for (String state : List.of("running", "timerPaused", "importIncomplete")) {
            var events = feeds(false, 540, 120); Records.put(events.get(events.size() - 1), state, true);
            var result = compare(events, "feed", 545);
            assertFalse(result.schedule().available()); assertFalse(result.adjusted().available());
        }
        var result = compare(feeds(false, 540, 120), "feed", 1800);
        assertFalse(result.schedule().available()); assertFalse(result.adjusted().available());
    }
    @Test public void incompleteHistoricalDaysAreNotNumberedAsCompleteSchedules() {
        var events = feeds(false, 540, 120);
        for (JSONObject e : events) if (e.optLong("start") < today(0)) Records.put(e, "importIncomplete", true);
        assertFalse(compare(events, "feed", 545).schedule().available());
    }
    @Test public void oldFutureAndUnrelatedLogsDoNotAffectComparisons() {
        var events = feeds(false, 540, 120); var before = compare(events, "feed", 545);
        events.add(event("bottle", TODAY, 600, 600, 200));
        events.add(event("bottle", TODAY.minusDays(20), 540, 540, 200));
        events.add(event("solids", TODAY, 544, 544, 200));
        events.add(event("pump", TODAY, 544, 544, 200));
        Collections.reverse(events);
        assertEquals(before, compare(events, "feed", 545));
    }
    @Test public void passedWindowsStayPassedRatherThanRollingToTomorrow() {
        var result = compare(feeds(false, 540, 120), "feed", 800);
        assertEquals(today(720), result.schedule().target()); assertEquals(today(720), result.adjusted().target());
        assertTrue(result.adjusted().status(today(800)).contains("passed"));
    }
    @Test public void localClockAverageSurvivesDstChangeWithoutAdding24HourDays() {
        ZoneId zone = ZoneId.of("America/New_York");
        LocalDate date = LocalDate.of(2026, 11, 2);
        List<JSONObject> events = new ArrayList<>();
        for (int d = 7; d >= 0; d--) for (int h : new int[]{6,9,12}) {
            if (d == 0 && h == 12) continue;
            JSONObject e = new JSONObject(); long at = date.minusDays(d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli();
            Records.put(e, "id", Long.toString(at)); Records.put(e, "kind", "bottle"); Records.put(e, "start", at); Records.put(e, "end", at);
            events.add(e);
        }
        long now = date.atTime(9, 5).atZone(zone).toInstant().toEpochMilli();
        var result = CareComparison.predict(events, "feed", now, zone);
        assertEquals(date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), result.schedule().target());
        assertEquals(result.schedule().target(), result.adjusted().target());
    }
    @Test public void nighttimeFeedsAndTomorrowProjectionAreNotClockComparisons() {
        var events = feeds(false, 540, 120);
        events.add(event("bottle", TODAY, 1380, 1380, 120));
        assertFalse(compare(events, "feed", 1385).schedule().available());
        assertFalse(compare(feeds(false, 540, 120), "feed", 1445).schedule().available());
    }
    @Test public void invalidCareTypeFailsExplicitly() {
        try { CareComparison.predict(List.of(), "solids", today(600), ZONE); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Unknown care type")); }
    }
    @Test public void earlyCareWithNormalDurationCanBringNextCareForward() {
        var events = naps(false, 540);
        for (JSONObject e : events) {
            if (e.optLong("start") >= time(TODAY.minusDays(4), 0)) {
                Records.put(e, "start", e.optLong("start") - 60 * MINUTE);
                Records.put(e, "end", e.optLong("end") - 60 * MINUTE);
            }
        }
        var comparison = compare(events, "nap", 490);
        assertEquals(today(600), comparison.adjusted().target());
        assertTrue(comparison.shift().contains("earlier"));
    }
    @Test public void largerBottleMayDelayNextFeedOnlyWithComparableEvidence() {
        var events = feeds(true, 600, 180);
        for (JSONObject e : events) if (e.optInt("amount") == 60) {
            Records.put(e, "amount", "180");
        }
        for (JSONObject e : events) {
            if (e.optLong("start") >= time(TODAY.minusDays(4), 0) && e.optLong("start") < today(0)
                && java.time.Instant.ofEpochMilli(e.optLong("start")).atZone(ZONE).getHour() == 11) {
                Records.put(e, "start", e.optLong("start") + 120 * MINUTE); Records.put(e, "end", e.optLong("end") + 120 * MINUTE);
            }
        }
        var comparison = compare(events, "feed", 605);
        assertEquals(today(780), comparison.adjusted().target());
        assertTrue(comparison.adjusted().basis().contains("larger than usual"));
        assertTrue(comparison.shift().contains("later"));
    }
    @Test public void previouslyIncompleteCareTodayAndFutureEndTimesRequireReview() {
        var events = feeds(false, 540, 120);
        Records.put(events.get(events.size() - 2), "importIncomplete", true);
        assertTrue(compare(events, "feed", 545).schedule().message().contains("numbering"));
        events = feeds(false, 540, 120);
        Records.put(events.get(events.size() - 1), "end", today(600));
        assertTrue(compare(events, "feed", 545).schedule().message().contains("future end"));
    }
    @Test public void overnightSleepCanAnchorFirstDaytimeNap() {
        List<JSONObject> events = new ArrayList<>();
        for (int day = 7; day >= 0; day--) {
            LocalDate date = TODAY.minusDays(day);
            events.add(event("sleep", date.minusDays(1), 1320, 1800, 0));
            if (day > 0) events.add(event("sleep", date, 480, 540, 0));
        }
        var comparison = compare(events, "nap", 365);
        assertEquals(today(480), comparison.schedule().target());
        assertEquals(today(480), comparison.adjusted().target());
    }
    @Test public void zeroDurationSleepDoesNotBecomeAPriorNap() {
        var events = naps(false, 540);
        var before = compare(events, "nap", 545);
        events.add(event("sleep", TODAY, 541, 541, 0));
        assertEquals(before, compare(events, "nap", 545));
        Records.put(events.get(events.size() - 2), "elapsedMs", 0L);
        var zeroSleep = compare(events, "nap", 545);
        assertTrue(zeroSleep.schedule().available()); assertFalse(zeroSleep.adjusted().available());
        assertTrue(zeroSleep.adjusted().message().contains("actual sleep duration"));
    }
    @Test public void overnightStartsAroundMidnightAreAveragedRelativeToNextNapDay() {
        List<JSONObject> events = new ArrayList<>();
        for (int day = 8; day >= 0; day--) {
            LocalDate date = TODAY.minusDays(day);
            events.add(event("sleep", date, day % 2 == 0 ? -15 : 15, 360, 0));
            if (day > 0) events.add(event("sleep", date, 480, 540, 0));
        }
        var comparison = compare(events, "nap", 365);
        assertEquals(today(480), comparison.adjusted().target());
        assertTrue(comparison.adjusted().basis().contains("near its usual time"));
    }
}
