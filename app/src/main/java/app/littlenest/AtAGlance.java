package app.littlenest;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** "At a glance" on Today: the next feed, nap or nappy in large text, with the others underneath. */
final class AtAGlance {
    record Item(long when, long latest, String title, String status, int color, Runnable open) {}

    static void add(MainActivity activity, LinearLayout body, JSONObject profile, List<JSONObject> events,
                    List<CarePredictor.Window> plans, long now) {
        if (!profile.optString("role").equals("Baby")) return;
        ZoneId zone = ZoneId.systemDefault();
        List<Item> items = new ArrayList<>();
        for (CarePredictor.Window window : plans) {
            // Prefer the history-adjusted time shown on Plan, then the normal schedule, then the interval estimate.
            var comparison = CareComparison.predict(events, window.type(), now, zone);
            CarePredictor.Window best = comparison.adjusted().available() ? comparison.adjusted()
                : comparison.schedule().available() ? comparison.schedule() : window;
            if (best.available()) items.add(new Item(best.target(), best.latest(), window.title(), best.status(now), window.type().equals("nap") ? Ui.SLEEP : Ui.FEED,
                () -> CarePlanUi.details(activity, profile, window, comparison)));
        }
        for (NappyPredictor.Prediction prediction : NappyPredictor.predict(events, now, zone)) if (prediction.available())
            items.add(new Item(prediction.target(), prediction.latest(), prediction.title(), prediction.status(now), Ui.DIAPER, () -> nappy(activity, prediction)));
        if (items.isEmpty()) return;
        items.sort(Comparator.comparingLong(Item::when));
        LinearLayout card = Ui.panel(activity);
        card.addView(Ui.text(activity, "At a glance", 13, Ui.MUTED));
        Item next = items.get(0);
        LinearLayout hero = Ui.column(activity);
        hero.addView(Ui.figures(Ui.title(activity, next.title + " · " + clock(next.when, next.latest, now, zone), 24)));
        hero.addView(Ui.medium(activity, next.status, 16, next.color == Ui.DIAPER ? Ui.INK : next.color));
        clickable(activity, hero, next);
        card.addView(hero);
        for (Item item : items.subList(1, Math.min(items.size(), 4))) {
            LinearLayout row = Ui.row(activity); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 6));
            android.view.View dot = Ui.dot(activity, item.color, 8);
            ((LinearLayout.LayoutParams) dot.getLayoutParams()).setMarginEnd(Ui.dp(activity, 10)); row.addView(dot);
            row.addView(Ui.text(activity, item.title, 15, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(Ui.figures(Ui.medium(activity, clock(item.when, item.latest, now, zone), 15, Ui.INK)));
            clickable(activity, row, item);
            card.addView(row);
        }
        card.addView(Ui.text(activity, "Estimates from your own logs. Tap one to compare timings. Follow baby's cues.", 11, Ui.MUTED));
        body.addView(card);
    }

    private static void clickable(MainActivity activity, LinearLayout view, Item item) {
        view.setMinimumHeight(Ui.dp(activity, 48)); view.setFocusable(true);
        view.setBackground(Ui.ripple(activity, android.graphics.Color.TRANSPARENT, 12));
        view.setContentDescription(item.title + ", " + clock(item.when, item.latest, System.currentTimeMillis(), ZoneId.systemDefault()) + ". " + item.status + ". Tap for details.");
        view.setOnClickListener(v -> item.open.run());
    }

    /** "~10:30" while still ahead or within its window, "was 10:30" once the window has passed. */
    static String clock(long when, long latest, long now, ZoneId zone) {
        return (latest < now ? "was " : "~") + time(when, now, zone);
    }
    private static String time(long when, long now, ZoneId zone) {
        var time = Instant.ofEpochMilli(when).atZone(zone);
        boolean today = time.toLocalDate().equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
        return time.format(DateTimeFormatter.ofPattern(today ? "HH:mm" : "EEE HH:mm"));
    }

    private static void nappy(MainActivity activity, NappyPredictor.Prediction prediction) {
        long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
        LinearLayout content = Ui.column(activity); int pad = Ui.dp(activity, 18); content.setPadding(pad, pad, pad, pad);
        content.addView(Ui.text(activity, "Likely between " + time(prediction.earliest(), now, zone) + " and "
            + time(prediction.latest(), now, zone) + "\n" + prediction.status(now) + "\n\n" + prediction.basis(), 15, Ui.INK));
        content.addView(Ui.text(activity, "Learns how nappies follow feeds, sleep and time of day, giving recent days more say because routines change as baby grows. A guess, not a health check: speak to your clinician if wet nappies drop off.", 13, Ui.MUTED));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(prediction.title()).setNegativeButton("Close", null).create();
        content.addView(Ui.button(activity, "Log nappy", () -> { dialog.dismiss(); activity.openEditor(Catalog.get("diaper"), null); }));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content); dialog.setView(scroll); dialog.show();
    }

    private AtAGlance() {}
}
