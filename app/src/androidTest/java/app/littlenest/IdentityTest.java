package app.littlenest;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Locale;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class IdentityTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void themeUsesSansHeadingsAndReadableIndependentColors() {
        assertEquals(Typeface.create("sans-serif", Typeface.BOLD), Ui.title(context(), "Heading", 20).getTypeface());
        assertEquals(Color.rgb(247, 244, 236), Ui.BG);
        assertEquals(Color.rgb(43, 91, 69), Ui.GREEN);
        for (int background : new int[]{Ui.BG, Ui.SURFACE, Ui.SAGE, Ui.PEACH, Ui.BLUE, Ui.CORAL, Ui.LAVENDER, Ui.SAND}) {
            assertTrue("Primary text contrast", contrast(Ui.INK, background) >= 4.5);
            assertTrue("Secondary text contrast", contrast(Ui.MUTED, background) >= 4.5);
            assertTrue("Action text contrast", contrast(Ui.GREEN, background) >= 4.5);
        }
        assertTrue("Selected navigation contrast", contrast(Color.WHITE, Ui.GREEN) >= 4.5);
    }

    @Test public void nativeScreensUseNestBrandAndActivityTilesWithoutCompetitorCopy() {
        Store store = Store.get(context());
        JSONObject profile = new JSONObject(); Records.put(profile, "entity", "profile");
        Records.put(profile, "name", "Identity test baby"); Records.put(profile, "role", "Baby");
        String id = store.save(null, profile, false);
        context().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("profile", id).apply();
        JSONObject imported = new JSONObject(); Records.put(imported, "entity", "event"); Records.put(imported, "profileId", id);
        Records.put(imported, "kind", "sleep"); Records.put(imported, "start", System.currentTimeMillis() - 60_000);
        Records.put(imported, "end", imported.optLong("start")); Records.put(imported, "importIncomplete", true);
        Records.put(imported, "naraSource", new JSONObject()); Records.put(imported, "sourceTimeZone", "UTC");
        store.save(null, imported, false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(android.R.id.content);
                assertNotNull(text(root, "Little Nest")); assertUnbranded(root);
                TextView breast = text(root, "Breastfeeding"), bottle = text(root, "Bottle");
                assertNotNull(breast); assertNotNull(bottle);
                assertSame(breast.getParent().getParent(), bottle.getParent().getParent());
                assertEquals("Add", findDescription(root, "Add feed").getText());
                for (String tab : new String[]{"History", "Trends", "Family"}) {
                    text(activity.findViewById(android.R.id.content), tab).performClick();
                    assertUnbranded(activity.findViewById(android.R.id.content));
                }
                assertNotNull(text(activity.findViewById(android.R.id.content), "Import activity CSV"));
                assertNotNull(text(activity.findViewById(android.R.id.content), "Little Nest " + BuildConfig.VERSION_NAME
                    + "\nA shared place for everyday care. No ads, analytics, subscriptions, or app backend."));
            });
        }
    }

    private static TextView text(View view, String value) {
        if (view instanceof TextView label && label.getText().toString().equals(value)) return label;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = text(group.getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private static TextView findDescription(View view, String description) {
        if (view instanceof TextView label && description.equals(label.getContentDescription())) return label;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findDescription(group.getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
    private static void assertUnbranded(View view) {
        if (view instanceof TextView text) assertFalse(text.getText().toString().toLowerCase(Locale.ROOT).contains("nara"));
        if (view.getContentDescription() != null) assertFalse(view.getContentDescription().toString().toLowerCase(Locale.ROOT).contains("nara"));
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) assertUnbranded(group.getChildAt(i));
    }
    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second); return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }
    private static double luminance(int color) {
        return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) + .0722 * linear(Color.blue(color));
    }
    private static double linear(int value) {
        double channel = value / 255.0; return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
    }
}
