package app.littlenest;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class NaraImportTest {
    private static final String[] BASE = {"Type","Profile Name","Start Date/time (Epoch)","Time Zone","_familyKey","_profileKey","_activityKey"};
    private static Map<String, String> row(String type) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Type", type); row.put("Profile Name", "Test Baby"); row.put("Start Date/time (Epoch)", "1700000000123");
        row.put("Time Zone", "Australia/Brisbane"); row.put("_familyKey", "test-family"); row.put("_profileKey", "test-baby"); row.put("_activityKey", "test-" + type);
        return row;
    }
    private static String csv(List<Map<String, String>> rows) {
        List<String> columns = new ArrayList<>(List.of(BASE));
        for (Map<String, String> row : rows) for (String key : row.keySet()) if (!columns.contains(key)) columns.add(key);
        StringBuilder csv = new StringBuilder(String.join(",", columns)).append("\r\n");
        for (Map<String, String> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) csv.append(',');
                csv.append('"').append(row.getOrDefault(columns.get(i), "").replace("\"", "\"\"")).append('"');
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }
    private static NaraImport.Preview parse(Map<String, String> row) throws IOException {
        return NaraImport.parse(new StringReader(csv(List.of(row))));
    }
    @Test public void csvSupportsBomCrLfQuotesCommasMultilineAndTrailingEmpty() throws Exception {
        try (CsvReader reader = new CsvReader(new StringReader("\ufeffa,b,c\r\n\"hello,\r\n\"\"baby\"\"\",,tail\r\nx,y,"))) {
            assertEquals(List.of("a","b","c"), reader.next());
            assertEquals(List.of("hello,\r\n\"baby\"","","tail"), reader.next());
            assertEquals(List.of("x","y",""), reader.next()); assertNull(reader.next());
        }
    }
    @Test public void malformedCsvFailsExplicitly() throws Exception {
        for (String input : List.of("a,\"unclosed", "\"closed\"garbage", "bad\"quote")) {
            try (CsvReader reader = new CsvReader(new StringReader(input))) {
                reader.next(); fail(input);
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("record 1")); }
        }
    }
    @Test public void importErrorsAndWarningsUseGenericActivityCopy() throws Exception {
        try {
            NaraImport.parse(new StringReader("Type,Other\n"));
            fail("Missing schema must fail explicitly");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().startsWith("Not a supported activity export:"));
            assertFalse(expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("nara"));
        }
        Map<String, String> pump = row("Pump"); pump.put("Profile Name", ""); pump.put("_profileKey", "");
        var preview = parse(pump);
        assertTrue(preview.summary().contains("pumping entries have no profile in the export"));
        assertFalse(preview.summary().toLowerCase(java.util.Locale.ROOT).contains("nara"));
    }
    @Test public void mixedBottleRetainsComponentsNotesTimezoneAndSource() throws Exception {
        Map<String, String> row = row("Bottle Feed");
        row.put("[Bottle Feed] Type", "Breast Milk Formula"); row.put("[Bottle Feed] Formula Name", "Test formula");
        row.put("[Bottle Feed] Breast Milk Volume", "25.5"); row.put("[Bottle Feed] Breast Milk Volume Unit", "ML");
        row.put("[Bottle Feed] Formula Volume", "1"); row.put("[Bottle Feed] Formula Volume Unit", "FLOZ");
        row.put("Note", "A note, with \"quotes\"\nand trailing spaces  "); row.put("Created By Caregiver", "Person A");
        row.put("Last Updated By Caregiver", "Person B"); row.put("Extra Future Column", "keep this too");
        NaraImport.Preview parsed = parse(row); JSONObject event = parsed.events().get(0).data();
        assertEquals(1700000000123L, event.optLong("start"));
        assertEquals("Mixed", event.optString("milk")); assertEquals("Test formula", event.optString("formulaName"));
        assertEquals(55.0735295625, Insights.bottleMl(event), .000001);
        assertEquals(row.get("Note"), event.optString("notes")); assertEquals("Person B", event.optString("updatedBy"));
        assertEquals("keep this too", event.optJSONObject("naraSource").optString("Extra Future Column"));
        assertEquals("Australia/Brisbane", event.optString("sourceTimeZone"));
        JSONObject operation = NaraImport.operation(parsed.events().get(0));
        assertEquals(operation.toString(), NaraImport.operation(parse(row).events().get(0)).toString());
    }
    @Test public void totalBottleAndPumpDoNotDoubleCountComponents() throws Exception {
        JSONObject event = new JSONObject("{\"amount\":\"100\",\"unit\":\"ml\",\"breastAmount\":\"25\",\"breastUnit\":\"ml\",\"formulaAmount\":\"75\",\"formulaUnit\":\"ml\"}");
        assertEquals(100, Insights.bottleMl(event), 0);
        event = new JSONObject("{\"leftAmount\":\"1\",\"leftUnit\":\"fl oz\",\"rightAmount\":\"10\",\"rightUnit\":\"ml\"}");
        assertEquals(39.5735295625, Insights.pumpMl(event), .000001);
        Records.put(event, "totalAmount", "70"); Records.put(event, "unit", "ml");
        assertEquals(70, Insights.pumpMl(event), 0);
    }
    @Test public void breastPreservesManualSidesAndSecondsWithoutInventedSegments() throws Exception {
        Map<String, String> row = row("Breastfeed");
        row.put("[Breastfeed] Begin Side", "RIGHT"); row.put("[Breastfeed] End Side", "LEFT.nonTimer");
        row.put("[Breastfeed] Left Duration (Seconds)", "300"); row.put("[Breastfeed] Right Duration (Seconds)", "120.5");
        JSONObject event = parse(row).events().get(0).data();
        assertEquals("Right", event.optString("beginSide")); assertEquals("Left", event.optString("side"));
        assertEquals("Manual", event.optString("entryMethod")); assertEquals(300000, event.optLong("leftMs"));
        assertEquals(120500, event.optLong("rightMs")); assertEquals(420500, Records.duration(event, Long.MAX_VALUE));
        assertEquals(1700000420623L, event.optLong("end")); assertFalse(event.has("segments"));
    }
    @Test public void diaperSupportsMultipleColorsTexturesRashAndBlowout() throws Exception {
        Map<String, String> row = row("Diaper"); row.put("[Diaper] Type", "Dirty Wet"); row.put("[Diaper] Detail", "Rash Blowout");
        row.put("[Diaper] Dirty Color", "BLACK GREEN"); row.put("[Diaper] Dirty Texture", "MUCOUS MUSH PEBBLE RUN SOLID");
        JSONObject event = parse(row).events().get(0).data();
        assertEquals("Mixed", event.optString("diaper")); assertEquals("Yes", event.optString("blowout")); assertEquals("Yes", event.optString("rash"));
        assertEquals("Black|Green", event.optString("stool")); assertEquals("Mucous|Mushy|Pebble|Runny|Solid", event.optString("consistency"));
    }
    @Test public void unfinishedSleepAndBreastAreFlaggedNeverRunning() throws Exception {
        for (String kind : List.of("Sleep", "Breastfeed")) {
            NaraImport.Preview preview = parse(row(kind)); JSONObject event = preview.events().get(0).data();
            assertEquals(1, preview.incomplete()); assertTrue(event.optBoolean("importIncomplete")); assertFalse(event.optBoolean("running"));
            assertEquals(0, Records.duration(event, Long.MAX_VALUE));
            if (kind.equals("Sleep")) assertTrue(Insights.sleepIntervals(event, Long.MAX_VALUE).isEmpty());
        }
    }
    @Test public void ongoingSleepCanContinueFromOriginalStartWithoutExtendingBreastEntry() throws Exception {
        NaraImport.Preview preview = parse(row("Sleep"));
        JSONObject sleep = preview.entries(Map.of(), true, 1700003600123L).get(1).data();
        assertTrue(sleep.optBoolean("running")); assertFalse(sleep.optBoolean("importIncomplete"));
        assertEquals(1700000000123L, sleep.optLong("segmentStart"));
        assertEquals(3_600_000, Records.duration(sleep, 1700003600123L));
        assertEquals(1, Insights.sleepIntervals(sleep, 1700003600123L).size());
        JSONObject finished = Records.settleTimer(sleep, 1700003600123L);
        assertEquals(3_600_000, finished.optLong("elapsedMs"));
        try { preview.entries(Map.of(), true, 1700000000000L); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("future")); }
        JSONObject breast = parse(row("Breastfeed")).entries(Map.of(), true, Long.MAX_VALUE).get(1).data();
        assertFalse(breast.optBoolean("running")); assertTrue(breast.optBoolean("importIncomplete"));
    }
    @Test public void sleepAndPumpDurationAndUnitsArePreserved() throws Exception {
        Map<String, String> sleep = row("Sleep"); sleep.put("[Sleep] Duration (Seconds)", "60");
        sleep.put("[Sleep] End Date/time (Epoch)", "1700000060123");
        JSONObject event = parse(sleep).events().get(0).data(); assertEquals(60000, Records.duration(event, 0)); assertEquals("Not recorded", event.optString("sleepType"));
        Map<String, String> pump = row("Pump"); pump.put("[Pump] Duration (Seconds)", "0");
        pump.put("[Pump] Total Volume", "90"); pump.put("[Pump] Total Volume Unit", "ML");
        event = parse(pump).events().get(0).data(); assertEquals(90, Insights.pumpMl(event), 0); assertFalse(event.optBoolean("importIncomplete"));
    }
    @Test public void familyLevelPumpingGetsExplicitDestinationWithoutInventedBaby() throws Exception {
        Map<String, String> pump = row("Pump"); pump.put("_profileKey", ""); pump.put("Profile Name", "");
        pump.put("[Pump] Duration (Seconds)", "0");
        NaraImport.Preview preview = parse(pump);
        assertEquals("Pumping", preview.profiles().get(0).data().optString("name"));
        assertEquals("Parent", preview.profiles().get(0).data().optString("role"));
        assertTrue(preview.profiles().get(0).data().optBoolean("naraUnassigned")); assertEquals(1, preview.warnings().size());
        assertFalse(preview.events().get(0).data().optJSONObject("naraSource").has("_profileKey"));
    }
    @Test public void growthMilestoneAndProfileMetadataAreMapped() throws Exception {
        Map<String, String> growth = row("Growth"); growth.put("[Growth] Weight", "5.23"); growth.put("[Growth] Weight Unit", "KG");
        growth.put("[Growth] Head Size", "15"); growth.put("[Growth] Head Size Unit", "IN");
        JSONObject event = parse(growth).events().get(0).data(); assertEquals("kg", event.optString("weightUnit")); assertEquals("in", event.optString("headUnit"));
        Map<String, String> milestone = row("Baby First"); milestone.put("[Baby First] Baby First", "Grabbing rattle");
        assertEquals("Grabbing rattle", parse(milestone).events().get(0).data().optString("name"));
        Map<String, String> profile = row("Profile"); profile.put("[Profile] Type", "CHILD"); profile.put("[Profile] Sex", "FEMALE");
        profile.put("[Profile] Birth Date", "2024-01-01"); profile.put("[Profile] Birth Date (Adjusted)", "2024-02-02");
        NaraImport.Preview preview = NaraImport.parse(new StringReader(csv(List.of(growth, profile))));
        JSONObject data = preview.profiles().get(0).data(); assertEquals("Female", data.optString("sex")); assertEquals("2024-02-02", data.optString("adjustedDob"));
        String target = java.util.UUID.randomUUID().toString();
        List<NaraImport.Entry> entries = preview.entries(Map.of(preview.profiles().get(0).id(), target));
        assertEquals(target, entries.get(0).id()); assertEquals(target, entries.get(1).data().optString("profileId"));
    }
    @Test public void duplicateIdsDeduplicateButChangedDuplicatesReject() throws Exception {
        Map<String, String> one = row("Sleep"), two = new LinkedHashMap<>(one);
        NaraImport.Preview preview = NaraImport.parse(new StringReader(csv(List.of(one, two))));
        assertEquals(1, preview.events().size()); assertEquals(1, preview.warnings().size());
        two.put("Note", "changed");
        try { NaraImport.parse(new StringReader(csv(List.of(one, two)))); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("record 3")); }
    }
    @Test public void unknownTypesEnumsMalformedWidthsAndAmountsFailWithRecordNumber() throws Exception {
        List<Map<String, String>> bad = new ArrayList<>();
        bad.add(row("Unknown activity"));
        Map<String, String> diaper = row("Diaper"); diaper.put("[Diaper] Type", "Unknown"); bad.add(diaper);
        for (String amount : List.of("-1", "NaN", "Infinity", "not a number")) {
            Map<String, String> bottle = row("Bottle Feed"); bottle.put("[Bottle Feed] Volume", amount); bottle.put("[Bottle Feed] Volume Unit", "ML"); bad.add(bottle);
        }
        for (Map<String, String> row : bad) {
            try { parse(row); fail(row.get("Type")); } catch (IOException expected) { assertTrue(expected.getMessage().contains("record 2")); }
        }
        try { NaraImport.parse(new StringReader(csv(List.of(row("Sleep"))) + "extra,row\n")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("record 3")); }
    }
    @Test public void optionalLocalExportAcceptanceChecksEverySourceCellAndActivity() throws Exception {
        String path = System.getProperty("naraCsv", ""); assumeTrue("Pass -PnaraCsv=/absolute/path.csv to validate a private local export", !path.isBlank());
        NaraImport.Preview preview;
        try (var input = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) { preview = NaraImport.parse(input); }
        List<Map<String, String>> sourceRows = new ArrayList<>();
        try (CsvReader csv = new CsvReader(Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8))) {
            List<String> header = csv.next(), cells;
            while ((cells = csv.next()) != null) {
                if (cells.stream().allMatch(String::isEmpty) || cells.get(0).equals("Profile")) continue;
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < cells.size(); i++) row.put(header.get(i), cells.get(i));
                sourceRows.add(row);
            }
        }
        assertEquals(sourceRows.size(), preview.events().size());
        for (int i = 0; i < sourceRows.size(); i++) {
            Map<String, String> row = sourceRows.get(i); JSONObject data = preview.events().get(i).data(), source = data.optJSONObject("naraSource");
            for (Map.Entry<String, String> cell : row.entrySet()) if (!cell.getValue().isEmpty()) assertEquals("Source cell at activity " + i + ": " + cell.getKey(), cell.getValue(), source.optString(cell.getKey()));
            assertEquals(Long.parseLong(row.get("Start Date/time (Epoch)")), data.optLong("start"));
            assertEquals(row.get("Note"), data.optString("notes")); assertFalse(data.optBoolean("running"));
            NaraImport.operation(preview.events().get(i));
        }
        System.out.println("Local CSV acceptance: " + preview.events().size() + " activities; " + preview.profiles().size() + " profiles; " + preview.incomplete() + " incomplete.\n" + preview.types());
    }
}
