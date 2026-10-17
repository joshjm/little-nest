package app.littlenest;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GrowthTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private JSONObject profile(String sex) {
        JSONObject profile = new JSONObject();
        Records.put(profile, "entity", "profile"); Records.put(profile, "name", "Growth demo baby");
        Records.put(profile, "role", "Baby"); Records.put(profile, "dob", "2026-01-01");
        Records.put(profile, "adjustedDob", "2026-02-01"); Records.put(profile, "sex", sex);
        return profile;
    }
    private List<JSONObject> measurements() {
        List<JSONObject> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            JSONObject event = new JSONObject();
            Records.put(event, "entity", "event"); Records.put(event, "kind", "growth");
            long time = LocalDate.parse("2026-01-01").plusDays(i * 14L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            Records.put(event, "start", time); Records.put(event, "end", time);
            Records.put(event, "weight", Double.toString(3.2 + i * .35)); Records.put(event, "weightUnit", "kg");
            Records.put(event, "height", Double.toString(49 + i * 1.8)); Records.put(event, "lengthUnit", "cm");
            Records.put(event, "head", Double.toString(34 + i * .65)); Records.put(event, "headUnit", "cm");
            events.add(event);
        }
        return events;
    }
    @Test public void trendsUsesAllThreeChartsAndPreservesStoredHistory() {
        Store store = Store.get(context()); JSONObject profile = profile("Female");
        String id = store.save(null, profile, false);
        context().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("profile", id).commit();
        for (JSONObject event : measurements()) {
            Records.put(event, "profileId", id); store.save(null, event, false);
        }
        String before = store.backup();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                text(root, "Trends").performClick(); root = activity.findViewById(android.R.id.content);
                assertNotNull(text(root, "Growing together")); assertEquals(3, charts(root).size());
                for (GrowthChartView chart : charts(root)) {
                    assertTrue(chart.hasAnchor()); assertTrue(chart.hasReference());
                    assertTrue(chart.getContentDescription().toString().contains("2026-01-01"));
                    assertTrue(chart.getContentDescription().toString().contains("2026-05-07"));
                    chart.layout(0, 0, 360, 250);
                    Bitmap bitmap = Bitmap.createBitmap(360, 250, Bitmap.Config.ARGB_8888);
                    chart.draw(new Canvas(bitmap)); bitmap.recycle();
                }
                assertNotNull(text(root, "View 10 measurements"));
                text(root, "Use adjusted age (saved due date)").performClick();
                root = activity.findViewById(android.R.id.content);
                assertNotNull(text(root, "Adjusted age / saved due date: 2026-02-01 / WHO girls"));
                for (GrowthChartView chart : charts(root)) assertFalse(chart.hasAnchor());
                assertEquals(before, store.backup());
            });
        }
    }
    @Test public void missingProfileDetailsRetainEntriesWithoutGuessingAndShowEmptyMeasurementState() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                LinearLayout content = Ui.column(activity); JSONObject profile = profile("Other"); profile.remove("dob");
                GrowthUi.add(activity, content, profile, measurements().subList(0, 1), true, () -> {});
                assertEquals(3, charts(content).size());
                assertNotNull(text(content, "Edit growth profile"));
                assertNotNull(text(content, "Weight / lb")); assertNotNull(text(content, "Length / height / in"));
                assertNotNull(text(content, "Head circumference / in"));
                for (GrowthChartView chart : charts(content)) {
                    assertFalse(chart.hasReference()); assertFalse(chart.hasAnchor());
                }
                content.removeAllViews();
                GrowthUi.add(activity, content, profile("Female"), List.of(), false, () -> {});
                assertEquals(0, charts(content).size());
                assertNotNull(text(content, "No measurements yet. Log weight in Growth on Today."));
                JSONObject parent = profile("Female"); Records.put(parent, "role", "Parent");
                content.removeAllViews(); GrowthUi.add(activity, content, parent, measurements(), false, () -> {});
                assertEquals(0, charts(content).size());
                assertNotNull(text(content, "Growth percentiles are available for baby profiles."));
            });
        }
    }
    @Test public void bundledDataWorksOfflineAndChartRendersAtLargeFontScale() throws Exception {
        GrowthStandards standards = new GrowthStandards(new InputStreamReader(
            context().getResources().openRawResource(R.raw.who_growth), StandardCharsets.UTF_8));
        assertEquals(3.3464, standards.at("weight", "boys", 0).m(), 1e-8);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                android.content.res.Configuration configuration = new android.content.res.Configuration(context().getResources().getConfiguration());
                configuration.fontScale = 2;
                Context large = context().createConfigurationContext(configuration);
                GrowthChartView chart = new GrowthChartView(large, GrowthStandards.samples(measurements(), "head"),
                    standards, "head", "girls", LocalDate.parse("2026-01-01"), false);
                chart.layout(0, 0, Ui.dp(large, 300), Ui.dp(large, 250));
                Bitmap bitmap = Bitmap.createBitmap(chart.getWidth(), chart.getHeight(), Bitmap.Config.ARGB_8888);
                chart.draw(new Canvas(bitmap)); bitmap.recycle();
                assertTrue(chart.hasReference()); assertTrue(chart.getContentDescription().toString().contains("97"));
            });
        }
    }
    private static TextView text(View view, String value) {
        if (view instanceof TextView text && text.getText().toString().equals(value)) return text;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView result = text(group.getChildAt(i), value); if (result != null) return result;
        }
        return null;
    }
    private static List<GrowthChartView> charts(View view) {
        List<GrowthChartView> result = new ArrayList<>();
        if (view instanceof GrowthChartView chart) result.add(chart);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) result.addAll(charts(group.getChildAt(i)));
        return result;
    }
}
