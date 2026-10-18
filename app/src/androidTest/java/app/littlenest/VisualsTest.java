package app.littlenest;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VisualsTest {
    private Context context(boolean night) {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(config);
    }

    @Test public void activityArtworkIsDistinctAndRendersInBothPalettes() {
        try {
            for (boolean night : new boolean[]{false, true}) {
                Context context = context(night); Ui.apply(context);
                Set<Integer> artwork = new HashSet<>();
                for (String id : new String[]{"breast", "bottle", "solids", "bath", "tummy", "milestone",
                    "mood", "selfcare", "routine", "medicine", "vaccine", "temperature", "medical", "journal", "story"}) {
                    Catalog.Kind kind = Catalog.get(id);
                    Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                    NestIcon icon = new NestIcon(context, id, Ui.thread(kind), Ui.activityColor(kind), 28);
                    icon.setBounds(0, 0, 96, 96); icon.draw(new Canvas(bitmap));
                    int[] pixels = new int[96 * 96]; bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96);
                    assertTrue("Distinct artwork for " + id, artwork.add(Arrays.hashCode(pixels)));
                    assertTrue("Visible artwork for " + id, Arrays.stream(pixels).anyMatch(pixel -> pixel != 0));
                    bitmap.recycle();
                }
            }
        } finally { Ui.apply(context(false)); }
    }

    @Test public void illustrationsScaleAndStayOutOfTheAccessibilityTree() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                for (boolean night : new boolean[]{false, true}) {
                    Context context = context(night); Ui.apply(context);
                    Set<Integer> scenes = new HashSet<>();
                    for (String name : new String[]{"nest", "family", "rhythm"}) {
                        NestScene scene = new NestScene(context, name, false);
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, scene.getImportantForAccessibility());
                        scene.layout(0, 0, 288, 200);
                        Bitmap bitmap = Bitmap.createBitmap(288, 200, Bitmap.Config.ARGB_8888);
                        scene.draw(new Canvas(bitmap));
                        int[] pixels = new int[288 * 200]; bitmap.getPixels(pixels, 0, 288, 0, 0, 288, 200);
                        assertTrue(Arrays.stream(pixels).anyMatch(pixel -> pixel != 0));
                        if (!night) assertTrue("Distinct day scene", scenes.add(Arrays.hashCode(pixels)));
                        bitmap.recycle();
                    }
                }
            } finally { Ui.apply(context(false)); }
        });
    }
}
