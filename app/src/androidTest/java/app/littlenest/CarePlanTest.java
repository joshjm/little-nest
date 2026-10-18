package app.littlenest;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CarePlanTest {
    private final List<String> profiles = new ArrayList<>();
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private Store store() { return Store.get(context()); }
    private UiDevice device() { return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()); }
    private androidx.test.uiautomator.UiObject2 require(androidx.test.uiautomator.BySelector selector) throws Exception {
        var found = device().wait(Until.findObject(selector), 3000);
        for (int i = 0; found == null && i < 6; i++) {
            device().swipe(device().getDisplayWidth() / 2, device().getDisplayHeight() * 3 / 4,
                device().getDisplayWidth() / 2, device().getDisplayHeight() / 3, 20);
            found = device().wait(Until.findObject(selector), 1000);
        }
        for (int i = 0; found == null && i < 8; i++) {
            device().swipe(device().getDisplayWidth() / 2, device().getDisplayHeight() / 3,
                device().getDisplayWidth() / 2, device().getDisplayHeight() * 3 / 4, 20);
            found = device().wait(Until.findObject(selector), 1000);
        }
        if (found == null) {
            java.io.ByteArrayOutputStream hierarchy = new java.io.ByteArrayOutputStream(); device().dumpWindowHierarchy(hierarchy);
            fail("Missing " + selector + ": " + hierarchy.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
        }
        return found;
    }
    private String profile(String name, String role) {
        JSONObject data = new JSONObject(); Records.put(data, "entity", "profile"); Records.put(data, "name", name); Records.put(data, "role", role);
        String id = store().save(null, data, false); profiles.add(id);
        context().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("profile", id).apply(); return id;
    }
    private String feed(String profile, long start) {
        JSONObject event = new JSONObject(); Records.put(event, "entity", "event"); Records.put(event, "profileId", profile);
        Records.put(event, "kind", "bottle"); Records.put(event, "start", start); Records.put(event, "end", start);
        Records.put(event, "amount", "90"); Records.put(event, "unit", "ml");
        return store().save(null, event, false);
    }
    private CareReminders.Options options(boolean enabled, int interval, int lead) {
        return new CareReminders.Options(enabled, false, lead, 0, 0, new CarePredictor.Settings(interval, 0));
    }
    private CarePredictor.Window window(String profile) {
        return CarePredictor.predict(store().events(profile), CareReminders.options(context(), profile).intervals(), System.currentTimeMillis(), ZoneId.systemDefault()).get(0);
    }
    private void worker(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<Throwable> error = new AtomicReference<>();
        CareReminders.execute(context(), () -> { try { action.run(); } catch (RuntimeException | AssertionError e) { error.set(e); } finally { done.countDown(); } });
        assertTrue("Care background work timed out", done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private void permission() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context().getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
    }
    private android.service.notification.StatusBarNotification notification(String profile) {
        for (var notification : context().getSystemService(NotificationManager.class).getActiveNotifications())
            if (("care:" + CareReminders.key(profile, "feed")).equals(notification.getTag())) return notification;
        return null;
    }
    private void await(java.util.function.BooleanSupplier ready) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 10_000;
        while (!ready.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50);
        assertTrue("Expected asynchronous care state", ready.getAsBoolean());
    }
    @After public void cleanup() throws Exception {
        for (String id : profiles) CareReminders.save(context(), id, options(false, 0, 15));
        worker(() -> CareReminders.scheduleAll(context()));
    }
    @Test public void suggestionsDefaultToSilentAndManualSettingsPersistPerProfile() throws Exception {
        String first = profile("Care test baby", "Baby"), second = profile("Separate baby", "Baby");
        feed(first, System.currentTimeMillis() - 30 * CarePredictor.MINUTE);
        assertFalse(CareReminders.options(context(), first).feed()); assertFalse(CareReminders.options(context(), first).nap());
        CareReminders.save(context(), first, options(false, 180, 20));
        worker(() -> CareReminders.scheduleAll(context()));
        assertEquals(180, CareReminders.options(context(), first).intervals().feedMinutes());
        assertEquals(0, CareReminders.options(context(), second).intervals().feedMinutes());
        assertEquals(0, CareReminders.prefs(context()).getLong(CareReminders.key(first, "feed") + ".scheduledAt", 0));
        assertTrue(window(first).available());
    }
    @Test public void enabledAlertSchedulesThenNewFeedReplacesCycleAndDisableCancels() throws Exception {
        permission(); String id = profile("Scheduled care baby", "Baby");
        feed(id, System.currentTimeMillis() - 90 * CarePredictor.MINUTE);
        CareReminders.save(context(), id, options(true, 180, 15));
        worker(() -> CareReminders.scheduleAll(context()));
        String key = CareReminders.key(id, "feed"), cycle = window(id).cycle();
        long scheduled = CareReminders.prefs(context()).getLong(key + ".scheduledAt", 0);
        assertTrue(scheduled > System.currentTimeMillis());
        feed(id, System.currentTimeMillis());
        worker(() -> CareReminders.scheduleAll(context()));
        assertNotEquals(cycle, window(id).cycle());
        CareReminders.save(context(), id, options(false, 180, 15)); worker(() -> CareReminders.scheduleAll(context()));
        assertEquals(0, CareReminders.prefs(context()).getLong(key + ".scheduledAt", -1));
    }
    @Test public void refreshRestoresBabyAlarmsAndIgnoresParentProfiles() throws Exception {
        permission(); String baby = profile("Refresh care baby", "Baby"), parent = profile("Care parent", "Parent");
        feed(baby, System.currentTimeMillis() - 30 * CarePredictor.MINUTE);
        feed(parent, System.currentTimeMillis() - 30 * CarePredictor.MINUTE);
        CareReminders.save(context(), baby, options(true, 180, 15));
        CareReminders.save(context(), parent, options(true, 180, 15));
        worker(() -> Reminders.scheduleAll(context()));
        String key = CareReminders.key(baby, "feed"), cycle = window(baby).cycle();
        long expected = CareReminders.prefs(context()).getLong(key + ".scheduledAt", 0);
        CareReminders.prefs(context()).edit().remove(key + ".scheduledAt").apply();
        worker(() -> CareReminders.receive(context(), new Intent().setAction("refresh")));
        assertEquals(expected, CareReminders.prefs(context()).getLong(key + ".scheduledAt", 0));
        assertEquals(cycle, window(baby).cycle());
        assertFalse(CareReminders.prefs(context()).getStringSet("scheduled", java.util.Set.of()).contains(CareReminders.key(parent, "feed")));
        CareReminders.act(context(), baby, window(baby), false);
        worker(() -> Reminders.scheduleAll(context()));
        assertEquals(0, CareReminders.prefs(context()).getLong(key + ".scheduledAt", -1));
        assertEquals(cycle, CareReminders.prefs(context()).getString(key + ".dismissed", ""));
    }
    @Test public void broadcastPostsOnceSnoozesDismissesAndOpensCorrectProfile() throws Exception {
        permission(); String id = profile("Notification care baby", "Baby");
        feed(id, System.currentTimeMillis() - 50 * CarePredictor.MINUTE);
        CareReminders.save(context(), id, options(true, 60, 0));
        worker(() -> CareReminders.scheduleAll(context()));
        CarePredictor.Window window = window(id); String key = CareReminders.key(id, "feed");
        Intent notify = new Intent(context(), CareReminderReceiver.class).setAction("notify").putExtra("profile", id).putExtra("type", "feed").putExtra("cycle", window.cycle());
        context().sendBroadcast(notify);
        await(() -> notification(id) != null);
        var notification = notification(id).getNotification();
        assertEquals(2, notification.actions.length);
        worker(() -> CareReminders.receive(context(), notify));
        assertEquals(window.cycle(), CareReminders.prefs(context()).getString(key + ".fired", ""));
        profile("Other selected baby", "Baby");
        notification.contentIntent.send();
        require(By.text("Notification care baby  \u2304"));
        assertEquals(id, context().getSharedPreferences("settings", Context.MODE_PRIVATE).getString("profile", ""));
        notification.actions[0].actionIntent.send();
        await(() -> CareReminders.prefs(context()).getLong(key + ".snoozeAt", 0) > System.currentTimeMillis());
        worker(() -> CareReminders.scheduleAll(context()));
        assertNull(notification(id)); assertTrue(CareReminders.prefs(context()).getLong(key + ".scheduledAt", 0) > System.currentTimeMillis());
        notification.actions[1].actionIntent.send();
        await(() -> CareReminders.prefs(context()).getString(key + ".dismissed", "").equals(window.cycle()));
        worker(() -> CareReminders.scheduleAll(context()));
        assertEquals(0, CareReminders.prefs(context()).getLong(key + ".scheduledAt", -1));
        assertNull(notification(id));
    }
    @Test public void staleAlarmAndDeletedProfileCannotNotify() throws Exception {
        permission(); String id = profile("Stale alarm baby", "Baby");
        feed(id, System.currentTimeMillis() - 50 * CarePredictor.MINUTE);
        CareReminders.save(context(), id, options(true, 60, 0));
        worker(() -> CareReminders.scheduleAll(context()));
        String cycle = window(id).cycle();
        feed(id, System.currentTimeMillis());
        worker(() -> CareReminders.receive(context(), new Intent().setAction("notify").putExtra("profile", id).putExtra("type", "feed").putExtra("cycle", cycle)));
        assertNull(notification(id));
        JSONObject profile = store().records("profile").stream().filter(p -> p.optString("id").equals(id)).findFirst().orElseThrow();
        store().save(id, profile, true);
        worker(() -> CareReminders.scheduleAll(context()));
        worker(() -> CareReminders.receive(context(), new Intent().setAction("notify").putExtra("profile", id).putExtra("type", "feed").putExtra("cycle", cycle)));
        assertNull(notification(id)); assertFalse(CareReminders.prefs(context()).getStringSet("scheduled", java.util.Set.of()).contains(CareReminders.key(id, "feed")));
    }
    @Test public void nativeSettingsSaveManualPlanAndShowUpcomingAppointments() throws Exception {
        String id = profile("Native care baby", "Baby"); feed(id, System.currentTimeMillis() - 30 * CarePredictor.MINUTE);
        JSONObject appointment = new JSONObject(); Records.put(appointment, "entity", "event"); Records.put(appointment, "profileId", id);
        Records.put(appointment, "kind", "appointment"); Records.put(appointment, "name", "Care test appointment");
        Records.put(appointment, "start", System.currentTimeMillis() + CarePredictor.DAY); Records.put(appointment, "end", appointment.optLong("start"));
        store().save(null, appointment, false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            require(By.text("Plan")).click();
            require(By.text("Care plan & advance alerts")).click();
            var interval = require(By.desc("Manual milk-feed interval (minutes)")); interval.setText("180");
            require(By.res("android:id/button1")).click();
            require(By.textStartsWith("Next milk feed"));
            assertEquals(180, CareReminders.options(context(), id).intervals().feedMinutes()); assertFalse(CareReminders.options(context(), id).feed());
            require(By.text("Today")).click(); require(By.text("A day at a time"));
            scenario.onActivity(activity -> scrollBottom(activity.findViewById(android.R.id.content)));
            assertTrue(device().wait(Until.hasObject(By.textContains("Care test appointment")), 5000));
            require(By.textContains("Care test appointment")).click();
            assertTrue(device().wait(Until.hasObject(By.desc("Appointment / reminder")), 5000));
            require(By.res("android:id/button2")).click();
        }
    }
    @Test public void notificationProfileSelectionIsConsumedAndSurvivesRecreation() throws Exception {
        String id = profile("Recreated care baby", "Baby");
        Intent open = new Intent(context(), MainActivity.class).putExtra("careProfile", id);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(open)) {
            require(By.text("Recreated care baby  \u2304"));
            scenario.onActivity(activity -> assertFalse(activity.getIntent().hasExtra("careProfile")));
            String second = profile("Newly selected baby", "Baby");
            scenario.recreate();
            require(By.text("Newly selected baby  \u2304"));
            assertEquals(second, context().getSharedPreferences("settings", Context.MODE_PRIVATE).getString("profile", ""));
        }
    }
    @Test public void nativePlanShowsBothPredictionsAndEvidenceWithoutChangingAlerts() throws Exception {
        String id = profile("Comparison test baby", "Baby");
        ZoneId zone = ZoneId.systemDefault();
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        long now = System.currentTimeMillis();
        // Use completed daytime care; the test emulator may be running at any hour.
        long dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli();
        if (now < dayStart + 11 * CarePredictor.MINUTE * 60) {
            today = today.minusDays(1);
        }
        for (int day = 12; day >= 0; day--) {
            java.time.LocalDate date = today.minusDays(day);
            boolean small = day <= 4;
            feed(id, date.atTime(6, 0).atZone(zone).toInstant().toEpochMilli());
            long start = date.atTime(small ? 10 : 9, 0).atZone(zone).toInstant().toEpochMilli();
            String eventId = feed(id, start);
            if (small) {
                JSONObject data = store().events(id).stream().filter(e -> e.optString("id").equals(eventId)).findFirst().orElseThrow();
                Records.put(data, "amount", "45"); store().save(eventId, data, false);
            }
            if (day > 0) feed(id, date.atTime(small ? 11 : 12, 0).atZone(zone).toInstant().toEpochMilli());
        }
        var comparison = CareComparison.predict(store().events(id), "feed", now, zone);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> assertFalse(hasText(activity.findViewById(android.R.id.content), "Normal schedule: ")));
            require(By.text("Plan")).click();
            require(By.text("Coming up"));
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertTrue(hasText(root, "Normal schedule: "));
                assertTrue(hasText(root, "History-adjusted: "));
                if (comparison.adjusted().available()) assertTrue(hasText(root, comparison.shift()));
            });
            require(By.textStartsWith("Next milk feed \u00b7")).click();
            require(By.text("Normal schedule \u00b7 moving average"));
            require(By.text("History-adjusted \u00b7 last care"));
            require(By.text("Interval / alert estimate (unchanged)"));
            require(By.res("android:id/button2")).click();
        }
        assertFalse(CareReminders.options(context(), id).feed());
        assertEquals(0, CareReminders.options(context(), id).intervals().feedMinutes());
    }
    private static boolean hasText(View view, String prefix) {
        if (view instanceof android.widget.TextView text && text.getText().toString().startsWith(prefix)) return true;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            if (hasText(group.getChildAt(i), prefix)) return true;
        return false;
    }
    @Test public void unknownMilkVolumesRemainComparableOnAndroid() {
        List<JSONObject> events = new ArrayList<>();
        ZoneId zone = ZoneId.of("Australia/Brisbane");
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 17);
        for (int day = 7; day >= 0; day--) for (int hour : new int[]{6, 9, 12}) {
            if (day == 0 && hour == 12) continue;
            long start = today.minusDays(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli();
            JSONObject event = new JSONObject(); Records.put(event, "kind", "breast"); Records.put(event, "id", "breast" + start);
            Records.put(event, "start", start); Records.put(event, "end", start + 5 * CarePredictor.MINUTE); events.add(event);
        }
        long now = today.atTime(9, 10).atZone(zone).toInstant().toEpochMilli();
        var result = CareComparison.predict(events, "feed", now, zone);
        assertTrue(result.schedule().available()); assertTrue(result.adjusted().available());
        assertEquals(today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), result.adjusted().target());
        assertTrue(result.adjusted().basis().contains("timing only"));
    }
    @Test public void planGoalsPersistPerBabyWithoutChangingIntervalsOrAlerts() throws Exception {
        String id = profile("Planner baby", "Baby");
        feed(id, System.currentTimeMillis() - 10 * CarePredictor.MINUTE);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            require(By.text("Plan")).click();
            require(By.text("Room for a rhythm"));
            require(By.text("Edit daily goals")).click();
            require(By.desc("Total sleep goal (hours, e.g. 14.5)")).setText("14.5");
            require(By.desc("Daytime nap goal (count, 1–12)")).setText("3");
            require(By.desc("Milk-feed session goal (count, 1–30)")).setText("8");
            require(By.res("android:id/button1")).click();
            require(By.text("Sleep  0m / 14h 30m goal"));
            scenario.recreate();
            require(By.text("Room for a rhythm"));
            scenario.onActivity(activity -> {
                assertEquals(new CarePlanner.Goals(870, 3, 8), CarePlanUi.goals(activity, id));
                assertEquals(new CarePlanner.Goals(0, 0, 0), CarePlanUi.goals(activity, "another-baby"));
                assertEquals(0, CareReminders.options(activity, id).intervals().feedMinutes());
                assertFalse(CareReminders.options(activity, id).feed());
                assertFalse(CareReminders.options(activity, id).nap());
            });
            require(By.text("Edit daily goals")).click();
            require(By.desc("Total sleep goal (hours, e.g. 14.5)")).setText("");
            require(By.desc("Daytime nap goal (count, 1–12)")).setText("");
            require(By.desc("Milk-feed session goal (count, 1–30)")).setText("");
            require(By.res("android:id/button1")).click();
            scenario.onActivity(activity -> assertEquals(new CarePlanner.Goals(0, 0, 0), CarePlanUi.goals(activity, id)));
        }
    }
    @Test public void planRejectsInvalidGoalsWithoutSavingAndCancelPreservesPreviousGoals() throws Exception {
        String id = profile("Goal validation baby", "Baby");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            require(By.text("Plan")).click(); require(By.text("Edit daily goals")).click();
            require(By.desc("Total sleep goal (hours, e.g. 14.5)")).setText("24.01");
            require(By.res("android:id/button1")).click(); require(By.text("Sleep goal must be from 0 to 24 hours."));
            require(By.text("OK")).click();
            require(By.desc("Total sleep goal (hours, e.g. 14.5)")).setText("14");
            require(By.desc("Daytime nap goal (count, 1–12)")).setText("13");
            require(By.res("android:id/button1")).click(); require(By.textContains("Nap goal must be blank"));
            require(By.text("OK")).click(); require(By.res("android:id/button2")).click();
            scenario.onActivity(activity -> assertEquals(new CarePlanner.Goals(0, 0, 0), CarePlanUi.goals(activity, id)));
        }
    }
    @Test public void planShowsAgeGuidanceSourcesAndBedtimeLearningWithoutAssumingMissingAge() throws Exception {
        String id = profile("Guidance baby", "Baby");
        JSONObject data = store().records("profile").stream().filter(p -> p.optString("id").equals(id)).findFirst().orElseThrow();
        Records.put(data, "dob", java.time.LocalDate.now().minusMonths(7).toString()); store().save(id, data, false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            require(By.text("Plan")).click();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertTrue(hasText(root, "7 months"));
                assertTrue(hasText(root, "Around 6–12 months"));
                assertTrue(hasText(root, "At 6–12 months"));
                assertTrue(hasText(root, "Learning: log sleep as Night sleep"));
            });
            require(By.text("Sources & how planning works")).click();
            require(By.text("CDC \u00b7 sleep by age \u00b7 opens browser"));
            require(By.res("android:id/button2")).click();
        }
        profile("No birth date baby", "Baby");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            require(By.text("Plan")).click();
            scenario.onActivity(activity -> assertTrue(hasText(activity.findViewById(android.R.id.content), "Add a valid birth date")));
        }
    }
    @Test public void planExcludesParentsAndKeepsNavigationTouchTargets() throws Exception {
        profile("Planner parent", "Parent");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            var plan = require(By.text("Plan"));
            assertTrue(plan.getVisibleBounds().height() >= 48 * context().getResources().getDisplayMetrics().density);
            plan.click();
            require(By.textStartsWith("Sleep and milk-feed planning is for baby profiles."));
            scenario.onActivity(activity -> assertFalse(hasText(activity.findViewById(android.R.id.content), "Edit daily goals")));
        }
    }
    private static boolean scrollBottom(View view) {
        if (view instanceof android.widget.ScrollView scroll) { scroll.fullScroll(View.FOCUS_DOWN); return true; }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (scrollBottom(group.getChildAt(i))) return true;
        return false;
    }
}
