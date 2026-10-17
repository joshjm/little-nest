package app.littlenest;

import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class ImportTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private Store database() { return new Store(context(), "import-test-" + UUID.randomUUID() + ".db"); }
    private void remove(Store store) { String name = store.getDatabaseName(); store.close(); context().deleteDatabase(name); }
    private String fixture() {
        return "Type,Profile Name,Start Date/time (Epoch),Time Zone,_familyKey,_profileKey,_activityKey,[Profile] Type,[Profile] Sex,[Profile] Birth Date,Note\n"
            + "Sleep,Import Baby,1700000000123,Australia/Brisbane," + UUID.randomUUID() + ",test-profile,test-sleep,,,,Test note\n";
    }
    private NaraImport.Preview preview() throws Exception { return NaraImport.parse(new StringReader(fixture())); }
    private static void click(UiDevice device, androidx.test.uiautomator.BySelector selector) {
        var control = device.wait(Until.findObject(selector), 5000);
        assertNotNull("Expected UI control: " + selector, control); control.click();
    }

    @Test public void repeatImportsPreserveEditsAndDeletionsAndSyncIdentity() throws Exception {
        Store first = database(), second = database();
        try {
            NaraImport.Preview preview = preview(); List<NaraImport.Entry> entries = preview.entries(Map.of());
            assertEquals(2, first.importNara(entries).added()); assertEquals(0, first.importNara(entries).added());
            assertEquals(2, first.importNara(entries).skipped());
            second.importNara(entries); first.merge(second.operations()); second.merge(first.operations());
            assertEquals(2, first.operations().size()); assertEquals(2, second.operations().size());
            String profile = preview.profiles().get(0).id(); JSONObject event = first.events(profile).get(0);
            Records.put(event, "notes", "Edited locally"); first.save(event.optString("id"), event, false);
            first.importNara(entries); assertEquals("Edited locally", first.events(profile).get(0).optString("notes"));
            first.save(event.optString("id"), event, true); first.importNara(entries);
            assertTrue(first.events(profile).isEmpty());
            second.merge(first.operations()); second.importNara(entries); assertTrue(second.events(profile).isEmpty());
        } finally { remove(first); remove(second); }
    }

    @Test public void changedReexportIsSkippedAndDoesNotOverwriteOriginal() throws Exception {
        Store store = database();
        try {
            String csv = fixture();
            NaraImport.Preview first = NaraImport.parse(new StringReader(csv)), later = NaraImport.parse(new StringReader(csv.replace("Test note", "Changed in Nara")));
            store.importNara(first.entries(Map.of())); Store.ImportResult result = store.importNara(later.entries(Map.of()));
            assertEquals(0, result.added()); assertEquals(2, result.skipped());
            assertEquals("Test note", store.events(first.profiles().get(0).id()).get(0).optString("notes"));
        } finally { remove(store); }
    }

    @Test public void existingProfileMappingFillsOnlyMissingMetadata() throws Exception {
        Store store = database();
        try {
            NaraImport.Preview preview = preview(); JSONObject profile = new JSONObject();
            Records.put(profile, "entity", "profile"); Records.put(profile, "name", "Keep this name"); Records.put(profile, "role", "Baby");
            Records.put(profile, "dob", "2023-01-02"); String target = store.save(null, profile, false);
            Records.put(preview.profiles().get(0).data(), "sex", "Female"); Records.put(preview.profiles().get(0).data(), "dob", "2024-01-01");
            Store.ImportResult result = store.importNara(preview.entries(Map.of(preview.profiles().get(0).id(), target)));
            assertEquals(1, result.profilesEnriched()); assertEquals(1, store.records("profile").size()); assertEquals(1, store.events(target).size());
            JSONObject saved = store.records("profile").get(0); assertEquals("Keep this name", saved.optString("name"));
            assertEquals("2023-01-02", saved.optString("dob")); assertEquals("Female", saved.optString("sex"));
        } finally { remove(store); }
    }

    @Test public void invalidOrOrphanedBatchRollsBackEverything() throws Exception {
        Store store = database();
        try {
            List<NaraImport.Entry> entries = new ArrayList<>(preview().entries(Map.of()));
            JSONObject invalid = Records.copy(entries.get(1).data()); Records.put(invalid, "end", -1);
            entries.add(new NaraImport.Entry(UUID.randomUUID().toString(), invalid));
            try { store.importNara(entries); fail(); } catch (IllegalArgumentException expected) { assertTrue(store.operations().isEmpty()); }
            entries.remove(entries.size() - 1);
            JSONObject orphan = Records.copy(entries.get(1).data()); Records.put(orphan, "profileId", UUID.randomUUID().toString());
            entries.set(1, new NaraImport.Entry(entries.get(1).id(), orphan));
            try { store.importNara(entries); fail(); } catch (IllegalArgumentException expected) { assertTrue(store.operations().isEmpty()); }
        } finally { remove(store); }
    }

    @Test public void versionOneDatabaseMigratesWithoutLosingLogsOrTombstones() throws Exception {
        String name = "migration-test-" + UUID.randomUUID() + ".db"; String profileId = UUID.randomUUID().toString();
        JSONObject profile = new JSONObject(); Records.put(profile, "entity", "profile"); Records.put(profile, "name", "Migrated baby"); Records.put(profile, "role", "Baby");
        JSONObject op = Records.operation(profileId, 1, UUID.randomUUID().toString(), profile, false);
        try (SQLiteDatabase old = context().openOrCreateDatabase(name, Context.MODE_PRIVATE, null)) {
            old.execSQL("CREATE TABLE operations(id TEXT PRIMARY KEY,entity TEXT,clock INTEGER,json TEXT)");
            old.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,clock INTEGER,op TEXT,json TEXT,deleted INTEGER)");
            old.execSQL("CREATE TABLE uploaded(folder TEXT,op TEXT,PRIMARY KEY(folder,op))");
            old.execSQL("CREATE TABLE downloaded(folder TEXT,file TEXT,PRIMARY KEY(folder,file))");
            old.execSQL("INSERT INTO operations VALUES(?,?,?,?)", new Object[]{op.optString("opId"),profileId,1,op.toString()});
            old.execSQL("INSERT INTO records VALUES(?,?,?,?,?)", new Object[]{profileId,1,op.toString(),profile.toString(),0});
            old.execSQL("INSERT INTO records VALUES(?,?,?,?,?)", new Object[]{UUID.randomUUID().toString(),1,op.toString(),profile.toString(),1});
            old.setVersion(1);
        }
        Store upgraded = new Store(context(), name);
        try {
            assertEquals(1, upgraded.records("profile").size()); assertEquals(profileId, upgraded.records("profile").get(0).optString("id"));
            assertEquals(1, upgraded.operations().size()); assertEquals(2, upgraded.getReadableDatabase().getVersion());
            assertTrue(upgraded.eventPage(profileId, "", 0, Long.MAX_VALUE, 0).isEmpty());
        } finally { remove(upgraded); }
    }

    @Test public void nativePreviewCanCancelThenImportAndEditNewFields() throws Exception {
        File file = new File(context().getCacheDir(), "synthetic-nara-import.csv");
        Files.write(file.toPath(), fixture().getBytes(StandardCharsets.UTF_8));
        var device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.onActivityResult(105, android.app.Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(file))));
            assertTrue(device.wait(Until.hasObject(By.text("Import activity CSV")), 20_000));
            click(device, By.res("android:id/button2"));
            scenario.onActivity(activity -> activity.onActivityResult(105, android.app.Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(file))));
            assertTrue(device.wait(Until.hasObject(By.text("Import activity CSV")), 20_000));
            click(device, By.res("android:id/button1"));
            assertTrue(device.wait(Until.hasObject(By.text("Import complete")), 20_000));
            click(device, By.res("android:id/button1"));
            click(device, By.text("History")); device.waitForIdle();
            assertTrue(device.wait(Until.hasObject(By.textContains("NEEDS REVIEW")), 5000));
            click(device, By.textContains("NEEDS REVIEW")); click(device, By.res("android:id/button1"));
            assertTrue(device.wait(Until.hasObject(By.text("Imported entry still needs review")), 5000));
            click(device, By.res("android:id/button2"));
        } finally { assertTrue(file.delete()); }
    }

    @Test public void nativeImportCanContinueAndFinishOngoingSleep() throws Exception {
        File file = new File(context().getCacheDir(), "synthetic-ongoing-sleep.csv");
        Files.write(file.toPath(), fixture().getBytes(StandardCharsets.UTF_8));
        var device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.onActivityResult(105, android.app.Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(file))));
            assertTrue(device.wait(Until.hasObject(By.text("Import activity CSV")), 20_000));
            var checkbox = device.wait(Until.findObject(By.textStartsWith("Continue 1 ongoing sleep")), 5000);
            assertNotNull(checkbox); assertFalse(checkbox.isChecked()); checkbox.click();
            click(device, By.res("android:id/button1"));
            assertTrue(device.wait(Until.hasObject(By.text("Import complete")), 20_000));
            click(device, By.res("android:id/button1"));
            var today = device.wait(Until.findObject(By.text("Today")), 5000); assertNotNull(today); today.click();
            assertTrue(device.wait(Until.hasObject(By.text("Finish")), 5000));
            String profileId = context().getSharedPreferences("settings", Context.MODE_PRIVATE).getString("profile", "");
            JSONObject sleep = Store.get(context()).events(profileId).get(0);
            assertTrue(sleep.optBoolean("running")); assertEquals(1700000000123L, sleep.optLong("segmentStart"));
            click(device, By.text("Finish"));
            assertTrue(device.wait(Until.gone(By.text("Finish")), 5000));
            sleep = Store.get(context()).events(profileId).get(0); assertFalse(sleep.optBoolean("running"));
            assertTrue(sleep.optLong("elapsedMs") > 0); assertFalse(sleep.optBoolean("importIncomplete"));
        } finally { assertTrue(file.delete()); }
    }

    @Test public void nativeManualBreastDurationsUpdateBothSidesAndEndTime() throws Exception {
        Store store = Store.get(context()); JSONObject profile = new JSONObject();
        Records.put(profile, "entity", "profile"); Records.put(profile, "name", "Manual duration test"); Records.put(profile, "role", "Baby");
        String profileId = store.save(null, profile, false);
        context().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("profile", profileId).apply();
        JSONObject draft = new JSONObject(); long start = System.currentTimeMillis() - 600_000;
        Records.put(draft, "start", start); Records.put(draft, "end", start);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.openEditor(Catalog.get("breast"), draft));
            var left = device.wait(Until.findObject(By.desc("Left duration (seconds)")), 5000);
            assertNotNull(left); left.setText("90.5");
            var right = device.wait(Until.findObject(By.desc("Right duration (seconds)")), 5000);
            assertNotNull(right); right.setText("45");
            click(device, By.res("android:id/button1"));
            assertTrue(device.wait(Until.gone(By.desc("Left duration (seconds)")), 5000));
            JSONObject saved = store.events(profileId).get(0);
            assertEquals(90_500, saved.optLong("leftMs")); assertEquals(45_000, saved.optLong("rightMs"));
            assertEquals(135_500, saved.optLong("elapsedMs")); assertEquals(start + 135_500, saved.optLong("end"));
            assertEquals("Manual", saved.optString("entryMethod"));
        }
    }

    @Test public void optionalPrivateExportImportsTwiceAndPaginatesOnAndroid() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("naraCsv", "");
        assumeTrue("Optional private-file acceptance", !path.isEmpty());
        NaraImport.Preview preview;
        try (var input = Files.newBufferedReader(new File(path).toPath(), StandardCharsets.UTF_8)) { preview = NaraImport.parse(input); }
        Store store = database(), partner = database();
        try {
            List<NaraImport.Entry> entries = preview.entries(Map.of());
            long start = android.os.SystemClock.elapsedRealtime();
            assertEquals(entries.size(), store.importNara(entries).added());
            assertEquals(0, store.importNara(entries).added());
            int events = 0;
            for (NaraImport.Profile profile : preview.profiles()) {
                List<JSONObject> all = store.events(profile.id()); events += all.size();
                int count = 0, offset = 0;
                while (true) {
                    List<JSONObject> page = store.eventPage(profile.id(), "", 0, Long.MAX_VALUE, offset);
                    count += Math.min(100, page.size()); if (page.size() <= 100) break; offset += 100;
                }
                assertEquals(all.size(), count);
            }
            assertEquals(preview.events().size(), events);
            partner.merge(store.operations());
            assertEquals(entries.size(), partner.operations().size());
            for (NaraImport.Profile profile : preview.profiles()) assertEquals(store.events(profile.id()).size(), partner.events(profile.id()).size());
            System.out.println("Android private CSV: " + events + " activities; " + preview.profiles().size() + " destination profiles; repeat adds zero; partner merge preserved counts; elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start));
            Store appStore = Store.get(context());
            appStore.importNara(preview.entries(Map.of(), true, System.currentTimeMillis()));
            String babyId = preview.profiles().stream().filter(profile -> profile.data().optString("role").equals("Baby")).findFirst().orElseThrow().id();
            context().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("profile", babyId).apply();
            UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                assertTrue(device.wait(Until.hasObject(By.text("A day at a time")), 20_000));
                assertTrue(device.wait(Until.hasObject(By.text("Sleeping now")), 5000));
                click(device, By.text("History"));
                assertTrue(device.wait(Until.hasObject(By.text("The story so far")), 20_000));
                device.waitForIdle();
                scenario.onActivity(activity -> scrollToEnd(activity.findViewById(android.R.id.content)));
                assertTrue(device.wait(Until.hasObject(By.text("Next 100")), 5000));
                click(device, By.text("Next 100"));
                device.waitForIdle();
                scenario.onActivity(activity -> scrollToEnd(activity.findViewById(android.R.id.content)));
                assertTrue(device.wait(Until.hasObject(By.text("Page 2")), 5000));
                click(device, By.text("Trends"));
                assertTrue(device.wait(Until.hasObject(By.text("Find your rhythm")), 20_000));
            }
        } finally { remove(store); remove(partner); }
    }
    private static boolean scrollToEnd(android.view.View view) {
        if (view instanceof android.widget.ScrollView scroll) { scroll.fullScroll(android.view.View.FOCUS_DOWN); return true; }
        if (view instanceof android.view.ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) if (scrollToEnd(group.getChildAt(i))) return true;
        return false;
    }
}
