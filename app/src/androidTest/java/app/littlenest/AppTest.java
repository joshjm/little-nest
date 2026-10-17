package app.littlenest;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private UiObject2 activityControl(UiDevice device, BySelector selector) {
        UiObject2 found = device.wait(Until.findObject(selector), 3000);
        for (int i = 0; found == null && i < 5; i++) {
            device.swipe(device.getDisplayWidth() / 2, device.getDisplayHeight() * 3 / 4,
                device.getDisplayWidth() / 2, device.getDisplayHeight() / 3, 20);
            found = device.wait(Until.findObject(selector), 1500);
        }
        assertNotNull("Missing activity control: " + selector, found); return found;
    }

    @Test public void sqliteMergeIsIdempotentAndTombstoneSurvivesStaleReplay() {
        Store store = Store.get(context());
        String id = UUID.randomUUID().toString(), actor = UUID.randomUUID().toString();
        JSONObject profile = new JSONObject(); Records.put(profile, "entity", "profile"); Records.put(profile, "name", "Merge test"); Records.put(profile, "role", "Baby");
        JSONObject first = Records.operation(id, 100, actor, profile, false);
        JSONObject deletion = Records.operation(id, 101, actor, profile, true);
        store.merge(List.of(deletion, first, deletion));
        assertTrue(store.records("profile").stream().noneMatch(p -> p.optString("id").equals(id)));
        store.merge(List.of(first));
        assertTrue(store.records("profile").stream().noneMatch(p -> p.optString("id").equals(id)));
    }

    @Test public void invalidBackupDoesNotPartiallyApply() {
        Store store = Store.get(context());
        String id = UUID.randomUUID().toString();
        JSONObject profile = new JSONObject(); Records.put(profile, "entity", "profile"); Records.put(profile, "name", "Should not exist"); Records.put(profile, "role", "Baby");
        JSONObject valid = Records.operation(id, 1, UUID.randomUUID().toString(), profile, false);
        JSONObject invalid = Records.copy(valid); Records.put(invalid, "schema", 9);
        try { store.merge(List.of(valid, invalid)); fail("Expected invalid version"); }
        catch (IllegalArgumentException expected) { assertTrue(store.records("profile").stream().noneMatch(p -> p.optString("id").equals(id))); }
    }

    @Test public void appLaunchesAndLogsBottleThroughNativeUi() throws Exception {
        Store store = Store.get(context());
        JSONObject profile = new JSONObject(); Records.put(profile, "entity", "profile"); Records.put(profile, "name", "UI Baby"); Records.put(profile, "role", "Baby");
        String id = store.save(null, profile, false);
        context().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("profile", id).apply();
        Intent launch = context().getPackageManager().getLaunchIntentForPackage(context().getPackageName());
        assertNotNull(launch); launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK); context().startActivity(launch);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertTrue(device.wait(Until.hasObject(By.text("A day at a time")), 10_000));
        activityControl(device, By.text("Bottle")).click();
        assertTrue(device.wait(Until.hasObject(By.res("android:id/button1")), 5000));
        device.findObject(By.desc("Amount")).setText("120");
        device.findObject(By.res("android:id/button1")).click();
        assertTrue(device.wait(Until.hasObject(By.text("A day at a time")), 5000));
        assertTrue(store.events(id).stream().anyMatch(e -> e.optString("kind").equals("bottle") && e.optString("amount").equals("120")));
        device.findObject(By.text("History")).click();
        assertTrue(device.wait(Until.hasObject(By.text("The story so far")), 5000));
        device.findObject(By.text("Trends")).click();
        assertTrue(device.wait(Until.hasObject(By.text("Find your rhythm")), 5000));
        device.findObject(By.text("Family")).click();
        assertTrue(device.wait(Until.hasObject(By.text("Your people. Your data.")), 5000));
    }

    @Test public void nativeTimerCanPauseResumeSwitchAndFinish() throws Exception {
        Store store = Store.get(context());
        JSONObject profile = new JSONObject(); Records.put(profile, "entity", "profile"); Records.put(profile, "name", "Timer Baby"); Records.put(profile, "role", "Baby");
        String id = store.save(null, profile, false);
        context().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("profile", id).apply();
        Intent launch = context().getPackageManager().getLaunchIntentForPackage(context().getPackageName());
        assertNotNull(launch); launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK); context().startActivity(launch);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        activityControl(device, By.text("Breastfeeding")).click();
        assertTrue(device.wait(Until.hasObject(By.res("android:id/button3")), 5000));
        device.findObject(By.res("android:id/button3")).click();
        assertTrue(device.wait(Until.hasObject(By.text("Pause")), 5000));
        device.findObject(By.text("Pause")).click();
        assertTrue(device.wait(Until.hasObject(By.text("Resume")), 5000));
        device.findObject(By.text("Switch side")).click();
        device.findObject(By.text("Resume")).click();
        assertTrue(device.wait(Until.hasObject(By.text("Pause")), 5000));
        device.findObject(By.text("Finish")).click();
        assertTrue(device.wait(Until.gone(By.text("Pause")), 5000));
        JSONObject event = store.events(id).get(0);
        assertFalse(event.optBoolean("running")); assertFalse(event.optBoolean("timerPaused"));
        assertEquals("Right", event.optString("side"));
        assertTrue(event.optLong("elapsedMs") > 0);
        assertNotNull(event.optJSONArray("segments"));
    }

    @Test public void categoryAddAndIconNavigationRemainUsableAfterRestyling() throws Exception {
        Store store = Store.get(context());
        JSONObject profile = new JSONObject(); Records.put(profile, "entity", "profile");
        Records.put(profile, "name", "Style Baby"); Records.put(profile, "role", "Baby");
        String id = store.save(null, profile, false);
        context().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("profile", id).apply();
        Intent launch = context().getPackageManager().getLaunchIntentForPackage(context().getPackageName());
        assertNotNull(launch); launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK); context().startActivity(launch);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        activityControl(device, By.desc("Add feed")).click();
        assertTrue(device.wait(Until.hasObject(By.text("Log feed")), 5000));
        device.findObject(By.text("Solids")).click();
        assertTrue(device.wait(Until.hasObject(By.res("android:id/button1")), 5000));
        device.findObject(By.res("android:id/button2")).click();
        for (String tab : List.of("History", "Trends", "Family", "Today")) {
            var control = device.wait(Until.findObject(By.text(tab)), 5000);
            assertNotNull(control);
            android.graphics.Rect bounds = control.getVisibleBounds();
            assertTrue(bounds.width() >= Ui.dp(context(), 48));
            assertTrue(bounds.height() >= Ui.dp(context(), 48));
            control.click(); device.waitForIdle();
        }
        assertEquals(Ui.PEACH, Ui.activityColor(Catalog.get("bottle")));
        assertEquals(Ui.BLUE, Ui.activityColor(Catalog.get("sleep")));
        assertEquals(Ui.CORAL, Ui.activityColor(Catalog.get("pump")));
        assertEquals(Ui.SAGE, Ui.activityColor(Catalog.get("growth")));
    }
}
