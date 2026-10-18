package app.littlenest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;
import static app.littlenest.CarePredictor.MINUTE;

public class CarePlannerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private static final ZoneId ZONE = ZoneId.of("Australia/Brisbane");
    private long time(LocalDate day, int minutes) { return day.atStartOfDay().plusMinutes(minutes).atZone(ZONE).toInstant().toEpochMilli(); }
    private JSONObject event(String kind, LocalDate date, int start, int end) {
        JSONObject event = new JSONObject();
        Records.put(event, "id", kind + date + start); Records.put(event, "kind", kind);
        Records.put(event, "start", time(date, start)); Records.put(event, "end", time(date, end)); return event;
    }
    private List<JSONObject> nights(int count) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = count; i > 0; i--) {
            JSONObject event = event("sleep", TODAY.minusDays(i), 1200 + i, 1800);
            Records.put(event, "sleepType", "Night sleep"); result.add(event);
        }
        return result;
    }
    @Test public void goalsAreOptionalAndBoundedWithoutChangingPredictorSettings() {
        assertEquals(0, new CarePlanner.Goals(0, 0, 0).feeds());
        assertEquals(1440, new CarePlanner.Goals(1440, 12, 30).sleepMinutes());
        for (int[] invalid : new int[][]{{-1, 0, 0}, {1441, 0, 0}, {0, -1, 0}, {0, 13, 0}, {0, 0, -1}, {0, 0, 31}}) {
            assertThrows(IllegalArgumentException.class, () -> new CarePlanner.Goals(invalid[0], invalid[1], invalid[2]));
        }
    }
    @Test public void unknownFutureAndInvalidBirthDatesDoNotGuessAnAge() {
        for (String dob : List.of("", "not a date", "2026-02-30", "2026-10-19"))
            assertTrue(CarePlanner.guidance(dob, TODAY).age().isEmpty());
    }
    @Test public void guidanceUsesCalendarMonthsAtEachAgeBoundary() {
        assertTrue(CarePlanner.guidance(TODAY.minusMonths(4).plusDays(1).toString(), TODAY).sleep().contains("Newborn"));
        assertTrue(CarePlanner.guidance(TODAY.minusMonths(4).toString(), TODAY).sleep().contains("12–16"));
        assertTrue(CarePlanner.guidance(TODAY.minusMonths(12).toString(), TODAY).sleep().contains("11–14"));
        assertTrue(CarePlanner.guidance(TODAY.minusMonths(36).toString(), TODAY).sleep().contains("10–13"));
        assertTrue(CarePlanner.guidance(TODAY.minusMonths(72).toString(), TODAY).sleep().contains("clinician"));
    }
    @Test public void guidanceDistinguishesFeedingMethodsAndDoesNotTurnSolidMealsIntoMilkTargets() {
        assertTrue(CarePlanner.guidance(TODAY.toString(), TODAY).feeds().contains("8–12"));
        assertTrue(CarePlanner.guidance(TODAY.minusMonths(3).toString(), TODAY).feeds().contains("Mixed feeding"));
        assertTrue(CarePlanner.guidance(TODAY.minusMonths(6).toString(), TODAY).feeds().contains("not 5–6 milk feeds"));
        assertTrue(CarePlanner.guidance(TODAY.minusMonths(12).toString(), TODAY).feeds().contains("varies"));
        assertTrue(CarePlanner.guidance(TODAY.minusMonths(7).toString(), TODAY).naps().contains("2 daytime naps"));
        assertTrue(CarePlanner.guidance(TODAY.minusMonths(13).toString(), TODAY).naps().contains("1 midday nap"));
    }
    @Test public void progressMergesTopupsSplitNapsAndOverlappingSleepButExcludesSolidsAndNightNaps() {
        List<JSONObject> events = new ArrayList<>();
        events.add(event("bottle", TODAY, 360, 370)); events.add(event("breast", TODAY, 390, 400));
        events.add(event("bottle", TODAY, 500, 510)); events.add(event("solids", TODAY, 600, 610));
        events.add(event("sleep", TODAY, 480, 540)); events.add(event("sleep", TODAY, 550, 570));
        events.add(event("sleep", TODAY, 560, 600));
        var night = event("sleep", TODAY, 360, 420); Records.put(night, "sleepType", "Night sleep"); events.add(night);
        Collections.reverse(events);
        var result = CarePlanner.progress(events, time(TODAY, 720), ZONE);
        assertEquals(170 * MINUTE, result.sleepMs()); assertEquals(1, result.naps()); assertEquals(2, result.feeds());
    }
    @Test public void progressSplitsOvernightSleepAtMidnightAndKeepsCrossMidnightTopupOnStartDay() {
        List<JSONObject> events = List.of(event("sleep", TODAY.minusDays(1), 1320, 1800),
            event("bottle", TODAY.minusDays(1), 1430, 1435), event("bottle", TODAY, 10, 20));
        var result = CarePlanner.progress(events, time(TODAY, 720), ZONE);
        assertEquals(360 * MINUTE, result.sleepMs()); assertEquals(0, result.naps()); assertEquals(0, result.feeds());
    }
    @Test public void progressCountsRunningSleepAndRespectsPausesAndIncompleteCare() {
        JSONObject sleep = event("sleep", TODAY, 480, 480); Records.put(sleep, "running", true);
        Records.put(sleep, "segments", new JSONArray().put(new JSONArray().put(time(TODAY, 480)).put(time(TODAY, 510))));
        Records.put(sleep, "segmentStart", time(TODAY, 530));
        JSONObject incomplete = event("bottle", TODAY, 600, 610); Records.put(incomplete, "importIncomplete", true);
        JSONObject future = event("bottle", TODAY, 800, 800);
        var result = CarePlanner.progress(List.of(sleep, incomplete, future), time(TODAY, 600), ZONE);
        assertEquals(100 * MINUTE, result.sleepMs()); assertEquals(1, result.naps()); assertEquals(0, result.feeds()); assertTrue(result.incomplete());
        Records.put(sleep, "running", false); Records.put(sleep, "timerPaused", true);
        assertEquals(30 * MINUTE, CarePlanner.progress(List.of(sleep), time(TODAY, 600), ZONE).sleepMs());
    }
    @Test public void progressUsesLocalMidnightAcrossDst() {
        ZoneId zone = ZoneId.of("Europe/London"); LocalDate day = LocalDate.of(2026, 10, 25);
        long from = day.atStartOfDay(zone).toInstant().toEpochMilli(), end = day.atTime(5, 0).atZone(zone).toInstant().toEpochMilli();
        JSONObject sleep = new JSONObject(); Records.put(sleep, "kind", "sleep"); Records.put(sleep, "start", from - 2 * 60 * MINUTE); Records.put(sleep, "end", end);
        assertEquals(6 * 60 * MINUTE, CarePlanner.progress(List.of(sleep), end, zone).sleepMs());
    }
    @Test public void bedtimeNeedsFourPreviousNightsAndUsesLatestSevenClockStarts() {
        assertFalse(CarePlanner.bedtime(nights(3), time(TODAY, 720), ZONE).available());
        var window = CarePlanner.bedtime(nights(10), time(TODAY, 720), ZONE);
        assertTrue(window.available()); assertEquals(7, window.samples());
        assertEquals(time(TODAY, 1204), window.target());
        assertEquals(time(TODAY, 1189), window.earliest()); assertEquals(time(TODAY, 1219), window.latest());
        assertTrue(window.basis().contains("No bedtime alerts"));
        assertTrue(window.status(time(TODAY, 1300)).contains("passed"));
        assertEquals("Evening bedtime pattern", window.title());
    }
    @Test public void bedtimeDoesNotInferBedtimeFromNapsOrUnlabelledSleep() {
        var events = nights(5);
        Records.put(events.get(0), "sleepType", "Nap"); Records.put(events.get(1), "sleepType", "Not recorded");
        assertFalse(CarePlanner.bedtime(events, time(TODAY, 720), ZONE).available());
    }
    @Test public void bedtimeExcludesIncompleteHistoricalDaysAndWrongClockTimes() {
        var events = nights(5);
        var incomplete = event("sleep", TODAY.minusDays(1), 600, 650); Records.put(incomplete, "importIncomplete", true); events.add(incomplete);
        Records.put(events.get(0), "start", time(TODAY.minusDays(5), 960));
        assertFalse(CarePlanner.bedtime(events, time(TODAY, 720), ZONE).available());
    }
    @Test public void bedtimeGuardsActiveIncompleteAndAlreadyLoggedEvenings() {
        for (String flag : List.of("running", "timerPaused", "importIncomplete")) {
            var events = nights(5); var latest = event("sleep", TODAY, 600, 660);
            Records.put(latest, flag, true); events.add(latest);
            assertFalse(flag, CarePlanner.bedtime(events, time(TODAY, 720), ZONE).available());
        }
        var events = nights(5); var evening = event("sleep", TODAY, 1080, 1140);
        Records.put(evening, "sleepType", "Night sleep"); events.add(evening);
        assertTrue(CarePlanner.bedtime(events, time(TODAY, 1200), ZONE).message().contains("already logged"));
    }
    @Test public void bedtimeIgnoresFutureAndTooOldEntriesAndIsOrderIndependent() {
        var events = nights(4);
        var future = event("sleep", TODAY.plusDays(1), 1200, 1800); Records.put(future, "sleepType", "Night sleep"); events.add(future);
        var old = event("sleep", TODAY.minusDays(15), 1020, 1800); Records.put(old, "sleepType", "Night sleep"); events.add(old);
        Collections.reverse(events);
        assertEquals(time(TODAY, 1203), CarePlanner.bedtime(events, time(TODAY, 720), ZONE).target());
    }
}
