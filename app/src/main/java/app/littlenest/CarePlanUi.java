package app.littlenest;

import android.app.AlertDialog;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONObject;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CarePlanUi {
    static void addToday(MainActivity activity, LinearLayout body, JSONObject profile, List<JSONObject> events) {
        if (!profile.optString("role").equals("Baby")) return;
        String id = profile.optString("id");
        long now = System.currentTimeMillis();
        var options = CareReminders.options(activity, id);
        body.addView(Ui.section(activity, "Coming up"));
        LinearLayout card = Ui.panel(activity);
        for (CarePredictor.Window window : CarePredictor.predict(events, options.intervals(), now, ZoneId.systemDefault())) {
            var comparison = CareComparison.predict(events, window.type(), now, ZoneId.systemDefault());
            LinearLayout row = Ui.row(activity); row.setGravity(android.view.Gravity.TOP);
            row.setMinimumHeight(Ui.dp(activity, 60)); row.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));
            row.setBackground(Ui.ripple(activity, android.graphics.Color.TRANSPARENT, 12));
            android.view.View dot = Ui.dot(activity, window.type().equals("nap") ? Ui.SLEEP : Ui.FEED, 10);
            ((LinearLayout.LayoutParams) dot.getLayoutParams()).topMargin = Ui.dp(activity, 6); row.addView(dot);
            LinearLayout column = Ui.column(activity); row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
            String text = window.title() + " · Compare timings";
            column.addView(Ui.medium(activity, text, 16, Ui.INK));
            column.addView(Ui.figures(Ui.text(activity, comparisonLine("Normal schedule", comparison.schedule(), now), 14, Ui.INK)));
            column.addView(Ui.figures(Ui.text(activity, comparisonLine("History-adjusted", comparison.adjusted(), now), 14, Ui.INK)));
            if (!comparison.shift().isEmpty()) column.addView(Ui.text(activity, comparison.shift(), 13, Ui.GREEN));
            column.addView(Ui.text(activity, "Tap for evidence and interval-alert details", 12, Ui.MUTED));
            row.setOnClickListener(v -> details(activity, profile, window, comparison)); row.setFocusable(true);
            row.setContentDescription(text + ". " + comparisonLine("Normal schedule", comparison.schedule(), now) + ". "
                + comparisonLine("History-adjusted", comparison.adjusted(), now) + ". " + comparison.shift() + ". Tap for details.");
            card.addView(row); Ui.divider(card);
        }
        card.addView(Ui.rowButton(activity, "Care plan & advance alerts", () -> settings(activity, profile)));
        card.addView(Ui.text(activity, "Estimates, not deadlines. Adjusted timing learns from similar short/late care, not a measured sleep or food debt. Alerts still use the interval estimate. Follow baby's cues; never delay a feed for a prediction.", 11, Ui.MUTED));
        body.addView(card);
    }
    private static String range(CarePredictor.Window window) {
        var format = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm");
        ZoneId zone = ZoneId.systemDefault();
        return java.time.Instant.ofEpochMilli(window.earliest()).atZone(zone).format(format) + "–"
            + java.time.Instant.ofEpochMilli(window.latest()).atZone(zone).format(format);
    }
    private static String comparisonLine(String label, CarePredictor.Window window, long now) {
        return label + ": " + (window.available() ? EventEditor.format(window.target()) + "\n" + window.status(now) : "Learning / unavailable");
    }
    private static void comparisonDetails(MainActivity activity, LinearLayout content, String label, CarePredictor.Window window, long now) {
        content.addView(Ui.title(activity, label, 17));
        content.addView(Ui.text(activity, window.available() ? range(window) + "\nTypical time: " + EventEditor.format(window.target())
            + "\n" + window.status(now) + "\n\n" + window.basis() : window.message(), 14, Ui.INK));
    }
    static void details(MainActivity activity, JSONObject profile, CarePredictor.Window window, CareComparison.Comparison comparison) {
        long now = System.currentTimeMillis(); String id = profile.optString("id");
        LinearLayout content = Ui.column(activity); int pad = Ui.dp(activity, 18); content.setPadding(pad, pad, pad, pad);
        comparisonDetails(activity, content, "Normal schedule · moving average", comparison.schedule(), now);
        comparisonDetails(activity, content, "History-adjusted · last care", comparison.adjusted(), now);
        if (!comparison.shift().isEmpty()) content.addView(Ui.text(activity, comparison.shift(), 15, Ui.GREEN));
        content.addView(Ui.title(activity, "Interval / alert estimate (unchanged)", 17));
        String text = window.available() ? range(window) + "\nTypical time: " + EventEditor.format(window.target()) + "\n"
            + window.status(now) + "\n\n" + window.basis() + "\n\n" + CareReminders.reminderState(activity, id, window) : window.message();
        content.addView(Ui.text(activity, text, 15, Ui.INK));
        content.addView(Ui.text(activity, "This is a planning aid, not medical advice or a feeding/sleep recommendation. Follow cues and your clinician's care plan. Variable patterns and missing logs make estimates less reliable.", 13, Ui.MUTED));
        String blocked = CareReminders.notificationStatus(activity);
        if (!blocked.isEmpty()) content.addView(Ui.text(activity, blocked, 13, Ui.MUTED));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(window.title()).setNegativeButton("Close", null).create();
        if (window.available() && now <= window.latest()) {
            content.addView(Ui.button(activity, "Snooze alert 15 min", () -> {
                if (!CareReminders.options(activity, id).enabled(window.type())) { activity.error("Enable this alert in Care plan & advance alerts first."); return; }
                if (System.currentTimeMillis() + 15 * CarePredictor.MINUTE > window.latest()) { activity.error("This window ends before a 15-minute snooze. Dismiss it or check baby's cues."); return; }
                CareReminders.act(activity, id, window, true); dialog.dismiss(); activity.changed();
            }));
            content.addView(Ui.button(activity, "Dismiss this cycle", () -> {
                CareReminders.act(activity, id, window, false); dialog.dismiss(); activity.changed();
            }));
        }
        content.addView(Ui.button(activity, window.type().equals("feed") ? "Log milk feed" : "Log sleep", () -> {
            dialog.dismiss();
            if (window.type().equals("nap")) activity.openEditor(Catalog.get("sleep"), null);
            else new AlertDialog.Builder(activity).setTitle("Log milk feed").setItems(new String[]{"Breastfeeding", "Bottle"},
                (d, which) -> activity.openEditor(Catalog.get(which == 0 ? "breast" : "bottle"), null)).show();
        }));
        content.addView(Ui.button(activity, "Care plan & advance alerts", () -> { dialog.dismiss(); settings(activity, profile); }));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content); dialog.setView(scroll); dialog.show();
    }
    static void settings(MainActivity activity, JSONObject profile) {
        String id = profile.optString("id"); CareReminders.Options options = CareReminders.options(activity, id);
        LinearLayout content = Ui.column(activity); int pad = Ui.dp(activity, 20); content.setPadding(pad, pad, pad, pad);
        content.addView(Ui.text(activity, "For " + profile.optString("name") + " on this phone. Normal schedule averages clock times for the same daily nap/feed number (4 previous days needed). History-adjusted timing needs 4 similar prior days with short/late care. These comparisons do not change alerts or manual intervals. Interval alerts learn from the last 14 days, with at least 4 intervals across 2 days. Daytime naps use 05:00–20:00 local time.", 14, Ui.MUTED));
        CheckBox feed = checkbox(activity, content, activity.getString(R.string.care_feed_alert), options.feed());
        CheckBox nap = checkbox(activity, content, activity.getString(R.string.care_nap_alert), options.nap());
        EditText lead = input(activity, content, "Advance notice (minutes before window)", Integer.toString(options.leadMinutes()));
        content.addView(Ui.text(activity, "Optional manual intervals replace learning, not baby's cues. Blank = learn from logs. Feeds are measured start-to-start; naps from the last wake time.", 13, Ui.MUTED));
        EditText feeds = input(activity, content, "Manual milk-feed interval (minutes)", options.intervals().feedMinutes() == 0 ? "" : Integer.toString(options.intervals().feedMinutes()));
        EditText wake = input(activity, content, "Manual wake interval (minutes)", options.intervals().wakeMinutes() == 0 ? "" : Integer.toString(options.intervals().wakeMinutes()));
        EditText quietStart = input(activity, content, "Quiet hours start (0–23)", Integer.toString(options.quietStart()));
        EditText quietEnd = input(activity, content, "Quiet hours end (0–23)", Integer.toString(options.quietEnd()));
        content.addView(Ui.text(activity, "Equal quiet hours = no quiet period. Quiet hours affect suggested-care alerts only. A delayed alert is skipped if its window has passed. Android may delay notifications; never rely on these for time-critical care.", 13, Ui.MUTED));
        String blocked = CareReminders.notificationStatus(activity);
        if (!blocked.isEmpty()) content.addView(Ui.text(activity, blocked, 13, Ui.INK));
        content.addView(Ui.button(activity, "Enable notifications", activity::requestNotifications));
        content.addView(Ui.button(activity, "Android notification settings", () -> activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()))));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Care plan & advance alerts").setView(scroll)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                CareReminders.Options saved = new CareReminders.Options(feed.isChecked(), nap.isChecked(), number(lead, false),
                    number(quietStart, false), number(quietEnd, false), new CarePredictor.Settings(number(feeds, true), number(wake, true)));
                CareReminders.save(activity, id, saved); dialog.dismiss(); activity.changed();
                if (saved.feed() || saved.nap()) activity.requestNotifications();
            } catch (IllegalArgumentException e) { activity.error(e.getMessage()); }
        });
    }
    private static CheckBox checkbox(MainActivity activity, LinearLayout content, String title, boolean checked) {
        CheckBox checkbox = new CheckBox(activity); checkbox.setText(title); checkbox.setChecked(checked); content.addView(checkbox); return checkbox;
    }
    private static EditText input(MainActivity activity, LinearLayout content, String label, String value) {
        content.addView(Ui.text(activity, label, 13, Ui.MUTED));
        EditText input = Ui.input(activity, label, value, true); content.addView(input); return input;
    }
    private static int number(EditText input, boolean blankAllowed) {
        String text = input.getText().toString().trim(); if (text.isEmpty() && blankAllowed) return 0;
        try { return Integer.parseInt(text); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(input.getContentDescription() + " must be a whole number"); }
    }
    static void addUpcoming(MainActivity activity, LinearLayout body, List<JSONObject> events) {
        long now = System.currentTimeMillis();
        record Upcoming(long when, JSONObject event, boolean reminder) {}
        List<Upcoming> upcoming = new ArrayList<>();
        for (JSONObject event : events) {
            long reminder = Reminders.nextTime(event, now);
            boolean appointment = event.optString("kind").equals("appointment") && event.optLong("start") > now;
            if (reminder > 0) upcoming.add(new Upcoming(reminder, event, true));
            if (appointment && event.optLong("start") != reminder) upcoming.add(new Upcoming(event.optLong("start"), event, false));
        }
        upcoming.sort(Comparator.comparingLong(Upcoming::when));
        body.addView(Ui.section(activity, "Upcoming reminders & plans"));
        LinearLayout card = Ui.panel(activity);
        if (upcoming.isEmpty()) card.addView(Ui.text(activity, "No scheduled reminders. Add an appointment or a reminder to any care log.", 13, Ui.MUTED));
        for (Upcoming item : upcoming.stream().limit(5).toList()) {
            String title = item.event.optString("name"); if (title.isBlank()) title = Catalog.get(item.event.optString("kind")).title();
            card.addView(Ui.rowButton(activity, EventEditor.format(item.when) + " · " + title + (item.reminder ? " · reminder" : " · appointment"),
                () -> activity.openEditor(Catalog.get(item.event.optString("kind")), item.event)));
        }
        Button add = Ui.quiet(activity, "Add appointment / reminder", () -> activity.openEditor(Catalog.get("appointment"), null));
        add.setCompoundDrawables(new NestIcon(activity, "add", Ui.GREEN, android.graphics.Color.TRANSPARENT, 18), null, null, null);
        add.setCompoundDrawablePadding(Ui.dp(activity, 6)); card.addView(add);
        body.addView(card);
    }
    private CarePlanUi() {}
}
