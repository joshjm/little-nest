package app.littlenest;

import org.json.JSONObject;
import org.junit.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

public class NappyPredictorTest {
    private static final ZoneId ZONE = ZoneId.of("Australia/Brisbane");
    private static final long MINUTE = 60_000L;
    private long time(String value) { return LocalDateTime.parse(value).atZone(ZONE).toInstant().toEpochMilli(); }
    private JSONObject event(String kind, long start, String diaper) {
        JSONObject event = new JSONObject();
        Records.put(event, "kind", kind); Records.put(event, "id", kind + start);
        Records.put(event, "start", start); Records.put(event, "end", start + (kind.equals("diaper") ? 0 : 15 * MINUTE));
        if (diaper != null) Records.put(event, "diaper", diaper);
        return event;
    }
    /** Feeds at irregular times; a wet nappy ~60 min after each feed; one dirty nappy ~40 min after the first feed. */
    private List<JSONObject> history(int days) {
        List<JSONObject> events = new ArrayList<>();
        Random random = new Random(7);
        LocalDate today = LocalDate.of(2026, 10, 17);
        for (int day = days; day >= 1; day--) {
            long t = today.minusDays(day).atTime(6, 0).atZone(ZONE).toInstant().toEpochMilli();
            boolean first = true;
            while (t < today.minusDays(day).atTime(21, 0).atZone(ZONE).toInstant().toEpochMilli()) {
                events.add(event("bottle", t, null));
                events.add(event("diaper", t + (55 + random.nextInt(11)) * MINUTE, first ? "Mixed" : "Wet"));
                first = false;
                t += (120 + random.nextInt(121)) * MINUTE;
            }
        }
        return events;
    }
    private NappyPredictor.Prediction predict(List<JSONObject> events, String type, long now) {
        return NappyPredictor.predict(events, now, ZONE).stream().filter(p -> p.type().equals(type)).findFirst().orElseThrow();
    }

    @Test public void learnsThatWetNappiesFollowFeeds() {
        List<JSONObject> events = history(14);
        events.add(event("bottle", time("2026-10-17T06:00"), null));
        events.add(event("diaper", time("2026-10-17T07:00"), "Wet"));
        events.add(event("bottle", time("2026-10-17T09:00"), null));
        long now = time("2026-10-17T09:05");
        var wet = predict(events, "wet", now);
        assertTrue(wet.message(), wet.available());
        assertTrue(wet.forest());
        assertTrue("expected about 10:00, got " + (wet.target() - now) / MINUTE + " min",
            Math.abs(wet.target() - time("2026-10-17T10:00")) <= 25 * MINUTE);
        assertTrue(wet.errorMinutes() < wet.baselineErrorMinutes());
        assertTrue(wet.factors().contains("time since the last milk feed"));
        assertTrue(wet.basis().contains("Random forest"));
    }
    @Test public void aNewFeedMovesTheWetPredictionEarlier() {
        List<JSONObject> events = history(14);
        events.add(event("bottle", time("2026-10-17T06:00"), null));
        events.add(event("diaper", time("2026-10-17T07:00"), "Wet"));
        long now = time("2026-10-17T08:00");
        long without = predict(events, "wet", now).target();
        events.add(event("bottle", time("2026-10-17T07:55"), null));
        long with = predict(events, "wet", now).target();
        assertTrue(with < without);
    }
    @Test public void predictsDirtyNappy() {
        List<JSONObject> events = history(14);
        events.add(event("diaper", time("2026-10-17T05:30"), "Wet"));
        var dirty = predict(events, "dirty", time("2026-10-17T05:45"));
        assertTrue(dirty.message(), dirty.available());
        assertTrue(dirty.target() > time("2026-10-17T05:45"));
    }
    @Test public void needsEnoughRecentData() {
        assertFalse(predict(List.of(), "wet", time("2026-10-17T09:00")).available());
        var few = history(1);
        few.add(event("diaper", time("2026-10-17T08:00"), "Wet"));
        var wet = predict(few, "wet", time("2026-10-17T09:00"));
        assertFalse(wet.available()); assertTrue(wet.message().contains("Learning"));
        var stale = predict(history(14), "wet", time("2026-10-17T18:00"));
        assertFalse(stale.available()); assertTrue(stale.message().contains("14 hours"));
    }
    @Test public void isDeterministicAndIgnoresFutureLogs() {
        List<JSONObject> events = history(10);
        events.add(event("diaper", time("2026-10-17T07:00"), "Wet"));
        long now = time("2026-10-17T08:00");
        var first = predict(events, "wet", now);
        events.add(event("diaper", now + 30 * MINUTE, "Wet"));
        assertEquals(first, predict(events, "wet", now));
    }
    @Test public void forestFitsASimpleStep() {
        double[][] x = new double[200][1]; double[] y = new double[200];
        for (int i = 0; i < 200; i++) { x[i][0] = i; y[i] = i < 100 ? 10 : 50; }
        var forest = NappyPredictor.Forest.train(x, y, new Random(1));
        assertEquals(10, forest.predict(new double[]{20}), 3);
        assertEquals(50, forest.predict(new double[]{180}), 3);
    }
}
