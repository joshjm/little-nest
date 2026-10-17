package app.littlenest;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.junit.Assume.assumeTrue;

/**
 * Seeds two weeks of made-up care for README screenshots. Skipped in normal test runs.
 * Run with: adb shell am instrument -w -e class app.littlenest.DemoData -e demo true app.littlenest.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class DemoData {
    private static final long MIN = 60_000L;

    @Test public void seed() {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("demo")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Store store = Store.get(context);
        JSONObject profile = new JSONObject(); Records.put(profile, "entity", "profile"); Records.put(profile, "name", "Ada");
        Records.put(profile, "role", "Baby"); Records.put(profile, "dob", LocalDate.now().minusDays(118).toString());
        String id = store.save(null, profile, false);
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("profile", id).putString("caregiver", "Sam").apply();
        ZoneId zone = ZoneId.systemDefault(); long now = System.currentTimeMillis();
        String[] caregivers = {"Sam", "Alex"};
        for (int d = 13; d >= 0; d--) {
            long day = LocalDate.now().minusDays(d).atStartOfDay(zone).toInstant().toEpochMilli();
            int jitter = (d * 7) % 13 - 6;
            int[][] sleeps = {{0, 175}, {205, 390}, {540 + jitter, 615 + jitter}, {750, 840 + jitter}, {960 + jitter, 1005}, {1170, 1440}};
            for (int[] s : sleeps) {
                long start = day + s[0] * MIN, end = Math.min(day + s[1] * MIN, now - 25 * MIN);
                if (end - start < 20 * MIN) continue;
                JSONObject e = event(id, "sleep", start, end, caregivers[d % 2]);
                Records.put(e, "sleepType", s[0] >= 1140 || s[0] < 420 ? "Night sleep" : "Nap"); store.save(null, e, false);
            }
            int[] feeds = {180, 395, 600 + jitter, 760, 900 + jitter, 1080, 1260};
            for (int i = 0; i < feeds.length; i++) {
                long start = day + feeds[i] * MIN; if (start + 20 * MIN > now) continue;
                boolean bottle = i == 4 || i == 6;
                JSONObject e = event(id, bottle ? "bottle" : "breast", start, start + (bottle ? 15 : 22 + i % 3 * 3) * MIN, caregivers[(d + i) % 2]);
                if (bottle) { Records.put(e, "milk", i == 6 ? "Formula" : "Breast milk"); Records.put(e, "amount", Integer.toString(110 + (d % 3) * 10)); Records.put(e, "unit", "ml"); }
                else {
                    String begin = (d + i) % 2 == 0 ? "Left" : "Right", end = begin.equals("Left") ? "Right" : "Left";
                    Records.put(e, "beginSide", begin); Records.put(e, "side", end); Records.put(e, "entryMethod", "Timer");
                    Records.put(e, "leftMs", 11 * MIN + i * 20_000L); Records.put(e, "rightMs", 10 * MIN + d * 15_000L);
                }
                store.save(null, e, false);
            }
            int[] diapers = {200, 410, 620, 790, 930, 1100, 1270};
            for (int i = 0; i < diapers.length; i++) {
                long start = day + diapers[i] * MIN; if (start > now) continue;
                JSONObject e = event(id, "diaper", start, start, caregivers[(d + i + 1) % 2]);
                Records.put(e, "diaper", i % 3 == 1 ? "Mixed" : i == 5 ? "Dirty" : "Wet"); store.save(null, e, false);
            }
            if (d % 7 == 2) {
                long start = day + 11 * 60 * MIN;
                JSONObject e = event(id, "growth", start, start, "Sam");
                Records.put(e, "weight", d == 9 ? "6.1" : "6.4"); Records.put(e, "weightUnit", "kg");
                Records.put(e, "height", d == 9 ? "61.0" : "62.2"); Records.put(e, "head", d == 9 ? "40.1" : "40.6"); Records.put(e, "lengthUnit", "cm"); Records.put(e, "headUnit", "cm");
                store.save(null, e, false);
            }
        }
        long visit = LocalDate.now().plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli() + 10 * 60 * MIN;
        JSONObject appointment = event(id, "appointment", visit, visit, "Sam");
        Records.put(appointment, "name", "Four-month check-up"); store.save(null, appointment, false);
    }

    private static JSONObject event(String profile, String kind, long start, long end, String caregiver) {
        JSONObject e = new JSONObject(); Records.put(e, "entity", "event"); Records.put(e, "profileId", profile);
        Records.put(e, "kind", kind); Records.put(e, "start", start); Records.put(e, "end", end); Records.put(e, "caregiver", caregiver);
        return e;
    }
}
