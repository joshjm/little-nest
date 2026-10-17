package app.littlenest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

public class RecordsTest {
    private JSONObject event(String kind, long start, long end) {
        JSONObject event = new JSONObject();
        Records.put(event, "entity", "event"); Records.put(event, "kind", kind);
        Records.put(event, "profileId", UUID.randomUUID().toString()); Records.put(event, "start", start); Records.put(event, "end", end);
        return event;
    }
    private JSONObject op(String entity, long clock, boolean deleted) {
        return Records.operation(entity, clock, UUID.randomUUID().toString(), event("bottle", 100, 100), deleted);
    }
    @Test public void mergeIsOrderIndependentAndDeleteWinsWithHigherClock() throws Exception {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        List<JSONObject> operations = List.of(op(first, 1, false), op(second, 1, false), op(first, 2, false), op(first, 3, true));
        Map<String, String> expected = merged(operations);
        for (int i = 0; i < 100; i++) {
            List<JSONObject> shuffled = new ArrayList<>(operations); Collections.shuffle(shuffled);
            assertEquals(expected, merged(shuffled));
        }
        assertTrue(new JSONObject(expected.get(first)).optBoolean("deleted"));
        assertEquals(2, expected.size());
    }
    private Map<String, String> merged(List<JSONObject> operations) {
        Map<String, JSONObject> map = new HashMap<>();
        for (JSONObject op : operations) {
            String entity = op.optString("entityId");
            if (!map.containsKey(entity) || Records.compare(op, map.get(entity)) > 0) map.put(entity, op);
        }
        Map<String, String> result = new HashMap<>(); map.forEach((id, value) -> result.put(id, value.toString())); return result;
    }
    @Test public void concurrentEditsHaveStableTieBreaker() {
        String id = UUID.randomUUID().toString();
        JSONObject a = op(id, 4, false), b = op(id, 4, true);
        assertEquals(-Integer.signum(Records.compare(a, b)), Integer.signum(Records.compare(b, a)));
        assertEquals(merged(List.of(a, b)), merged(List.of(b, a)));
    }
    @Test public void timerPausesSwitchesAndResumesWithoutCountingPause() {
        JSONObject event = event("breast", 1000, 1000);
        Records.put(event, "elapsedMs", 0); Records.put(event, "running", true); Records.put(event, "segmentStart", 1000); Records.put(event, "side", "Left");
        event = Records.settleTimer(event, 61_000);
        assertEquals(60_000, event.optLong("leftMs"));
        Records.put(event, "side", "Right");
        event = Records.settleTimer(event, 91_000); Records.put(event, "running", false);
        assertEquals(30_000, event.optLong("rightMs"));
        assertEquals(90_000, Records.duration(event, 1_000_000));
        Records.put(event, "segmentStart", 1_000_000); Records.put(event, "running", true);
        assertEquals(120_000, Records.duration(event, 1_030_000));
    }
    @Test public void midnightSleepIsSplitAndMixedDiaperCountsBoth() {
        ZoneId zone = ZoneId.of("Australia/Brisbane"); LocalDate day = LocalDate.of(2026, 10, 17);
        long midnight = day.atStartOfDay(zone).toInstant().toEpochMilli();
        JSONObject sleep = event("sleep", midnight - 3_600_000, midnight + 7_200_000);
        JSONObject diaper = event("diaper", midnight + 100, midnight + 100); Records.put(diaper, "diaper", "Mixed");
        Insights.Day result = Insights.day(List.of(sleep, diaper), day, zone, midnight + 10_000_000);
        assertEquals(7_200_000, result.sleepMs()); assertEquals(1, result.wet()); assertEquals(1, result.dirty());
    }
    @Test public void daylightSavingDayUsesCalendarBoundary() {
        ZoneId zone = ZoneId.of("America/New_York"); LocalDate day = LocalDate.of(2026, 11, 1);
        long start = day.atStartOfDay(zone).toInstant().toEpochMilli(), end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        Insights.Day result = Insights.day(List.of(event("sleep", start, end)), day, zone, end);
        assertEquals(25 * 3_600_000L, result.sleepMs());
    }
    @Test public void bottleUnitsAreNormalized() {
        long now = System.currentTimeMillis(); JSONObject bottle = event("bottle", now, now);
        Records.put(bottle, "amount", "4"); Records.put(bottle, "unit", "fl oz");
        assertEquals(118.29411825, Insights.day(List.of(bottle), Insights.date(now), ZoneId.systemDefault(), now).milkMl(), .00001);
    }
    @Test public void rejectInvalidAndNonfiniteNumbers() {
        for (String amount : List.of("-1", "NaN", "Infinity", "banana")) {
            JSONObject data = event("bottle", 1, 1); Records.put(data, "amount", amount);
            assertThrows(IllegalArgumentException.class, () -> Records.operation(UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), data, false));
        }
    }
    @Test public void backupRoundTripPreservesDeletionAndPhoto() throws Exception {
        JSONObject data = event("journal", 1, 1); Records.put(data, "photo", "anBlZy1kYXRh"); Records.put(data, "notes", "A little memory");
        JSONObject operation = Records.operation(UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), data, true);
        String backup = new JSONObject().put("schema", 1).put("operations", new JSONArray().put(operation)).toString();
        assertEquals(operation.toString(), Records.parseBackup(backup).get(0).toString());
        assertThrows(IllegalArgumentException.class, () -> Records.parseBackup(backup.replace("\"schema\":1", "\"schema\":2")));
    }
    @Test public void futureVersionIsNotSilentlyAccepted() {
        JSONObject operation = op(UUID.randomUUID().toString(), 1, false); Records.put(operation, "schema", 2);
        assertThrows(IllegalArgumentException.class, () -> Records.validate(operation));
    }
    @Test public void csvIsQuotedAndSpreadsheetFormulaIsNeutralized() {
        assertEquals("\"' =SUM(A1)\"", Records.csvCell(" =SUM(A1)"));
        assertEquals("\"a,\"\"b\"\"\"", Records.csvCell("a,\"b\""));
        assertEquals("\"line\nnext\"", Records.csvCell("line\nnext"));
    }
    @Test public void catalogHasAllDocumentedTrackingCategories() {
        for (String id : List.of("breast", "bottle", "pump", "solids", "diaper", "sleep", "growth", "medicine", "vaccine", "milestone",
            "tummy", "bath", "story", "routine", "journal", "parentSleep", "hydration", "meal", "mood", "vitals", "symptom", "selfcare", "appointment")) assertNotNull(Catalog.get(id));
    }
    @Test public void permissionsRejectThirdAccountsAndPublicLinks() throws Exception {
        JSONObject owner = new JSONObject().put("roles", new JSONArray().put("owner"))
            .put("grantedToV2", new JSONObject().put("user", new JSONObject().put("id", "owner")));
        JSONObject partner = new JSONObject().put("roles", new JSONArray().put("write"))
            .put("grantedToV2", new JSONObject().put("user", new JSONObject().put("id", "partner")));
        JSONObject third = new JSONObject().put("roles", new JSONArray().put("write"))
            .put("invitation", new JSONObject().put("email", "third@example.invalid"));
        OneDriveApi.validatePermissions(new JSONArray().put(owner).put(partner));
        OneDriveApi.validatePermissions(new JSONArray().put(partner));
        assertThrows(java.io.IOException.class, () -> OneDriveApi.validatePermissions(new JSONArray()));
        assertThrows(java.io.IOException.class, () -> OneDriveApi.validatePermissions(new JSONArray().put(owner).put(partner).put(third)));
        assertThrows(java.io.IOException.class, () -> OneDriveApi.validatePermissions(new JSONArray().put(owner)
            .put(new JSONObject().put("roles", new JSONArray().put("write")).put("link", new JSONObject().put("scope", "anonymous")))));
    }
    @Test public void folderLinkIsParsedWithoutQueryInjection() {
        assertEquals("drive123|folder!123", OneDriveApi.folderId("drive123|folder!123"));
        assertThrows(IllegalArgumentException.class, () -> OneDriveApi.folderId("bad' or trashed=false"));
        assertThrows(IllegalArgumentException.class, () -> OneDriveApi.folderId("drive|folder/../../me"));
    }
    @Test public void oversizedFilesAreRejected() {
        byte[] bytes = new byte[100];
        assertThrows(java.io.IOException.class, () -> SyncFiles.readBounded(new java.io.ByteArrayInputStream(bytes), 99));
    }
    @Test public void fractionalRevisionAndMalformedTimerAreRejected() {
        JSONObject operation = op(UUID.randomUUID().toString(), 1, false); Records.put(operation, "clock", 1.5);
        assertThrows(IllegalArgumentException.class, () -> Records.validate(operation));
        JSONObject data = event("sleep", 100, 200);
        Records.put(data, "segments", new JSONArray().put(new JSONArray().put(50).put(150)));
        assertThrows(IllegalArgumentException.class, () -> Records.operation(UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), data, false));
    }
    @Test public void corruptPhotoIsRejectedBeforeImport() {
        JSONObject data = event("journal", 1, 1); Records.put(data, "photo", "not a base64 image");
        assertThrows(IllegalArgumentException.class, () -> Records.operation(UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), data, false));
    }
    @Test public void pausedSleepDoesNotCountGap() {
        long from = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        JSONObject data = event("sleep", from, from + 3_600_000);
        Records.put(data, "segments", new JSONArray().put(new JSONArray().put(from).put(from + 600_000))
            .put(new JSONArray().put(from + 1_800_000).put(from + 2_400_000)));
        Insights.Day day = Insights.day(List.of(data), LocalDate.now(), ZoneId.systemDefault(), from + 3_600_000);
        assertEquals(1_200_000, day.sleepMs());
    }
    @Test public void clockRollbackDoesNotMakeTimerNegative() {
        JSONObject event = event("sleep", 1000, 5000); Records.put(event, "running", true);
        Records.put(event, "segmentStart", 5000); Records.put(event, "elapsedMs", 4000);
        JSONObject settled = Records.settleTimer(event, 2000);
        assertEquals(5000, settled.optLong("end")); assertEquals(4000, settled.optLong("elapsedMs"));
    }
    @Test public void remindersSkipMissedIntervalsAndNeverReplayOldOneOff() {
        JSONObject event = event("appointment", 1, 1); Records.put(event, "remindAt", 100_000);
        assertEquals(100_000, Reminders.nextTime(event, 90_000));
        assertEquals(0, Reminders.nextTime(event, 100_001));
        Records.put(event, "repeatMinutes", 5);
        assertEquals(1_000_000, Reminders.nextTime(event, 999_999));
    }
}
