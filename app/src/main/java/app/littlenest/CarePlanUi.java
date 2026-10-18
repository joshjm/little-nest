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
    private static void addPredictions(MainActivity activity, LinearLayout body, JSONObject profile, List<JSONObject> events) {
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
            boolean nap = window.type().equals("nap");
            android.widget.ImageView icon = new android.widget.ImageView(activity);
            icon.setImageDrawable(new NestIcon(activity, nap ? "sleep" : "bottle", nap ? Ui.SLEEP : Ui.FEED, nap ? Ui.BLUE : Ui.PEACH, 24));
            icon.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Ui.dp(activity, 28), Ui.dp(activity, 28));
            iconParams.topMargin = Ui.dp(activity, 4); iconParams.setMarginEnd(Ui.dp(activity, 12)); row.addView(icon, iconParams);
            LinearLayout column = Ui.column(activity); row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
            String text = window.title() + " · Compare timings";
            column.addView(Ui.medium(activity, text, 16, Ui.INK));
            column.addView(Ui.figures(Ui.text(activity, window.available()
                ? "Interval estimate: " + shortTime(window.target()) + "\n" + window.status(now)
                : window.message(), 14, Ui.INK)));
            if (window.available()) column.addView(Ui.text(activity, range(window), 13, Ui.MUTED));
            column.addView(Ui.figures(Ui.text(activity, comparisonLine("Normal schedule", comparison.schedule(), now), 14, Ui.INK)));
            column.addView(Ui.figures(Ui.text(activity, comparisonLine("History-adjusted", comparison.adjusted(), now), 14, Ui.INK)));
            if (!comparison.shift().isEmpty()) column.addView(Ui.text(activity, comparison.shift(), 13, Ui.GREEN));
            column.addView(Ui.text(activity, "Tap for evidence and interval-alert details", 12, Ui.MUTED));
            row.setOnClickListener(v -> details(activity, profile, window, comparison)); row.setFocusable(true);
            row.setContentDescription(text + ". " + comparisonLine("Normal schedule", comparison.schedule(), now) + ". "
                + comparisonLine("History-adjusted", comparison.adjusted(), now) + ". "
                + (window.available() ? "Interval estimate " + EventEditor.format(window.target()) + ". " + window.status(now) : window.message())
                + ". " + comparison.shift() + ". Tap for details.");
            card.addView(row); Ui.divider(card);
        }
        card.addView(Ui.rowButton(activity, "Care plan & advance alerts", () -> settings(activity, profile)));
        card.addView(Ui.text(activity, "Estimates, not deadlines. Follow baby's cues; never delay a feed. Only interval estimates drive alerts.", 13, Ui.MUTED));
        body.addView(card);
    }
    static void plan(MainActivity activity, LinearLayout body, JSONObject profile, List<JSONObject> events) {
        body.addView(Ui.illustratedHeading(activity, "Room for a rhythm", 22, "rhythm", false));
        if (!profile.optString("role").equals("Baby")) {
            body.addView(Ui.text(activity, "Sleep and milk-feed planning is for baby profiles. Switch to your baby using the name above. Parent sleep and wellbeing stay on Today and Trends.", 16, Ui.MUTED));
            return;
        }
        body.addView(Ui.text(activity, "Patterns and goals, with room for change.", 15, Ui.MUTED));
        long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
        String id = profile.optString("id");
        CarePlanner.Progress progress = CarePlanner.progress(events, now, zone);
        CarePlanner.Goals goals = goals(activity, id);
        addPredictions(activity, body, profile, events);
        body.addView(Ui.section(activity, "Today · since midnight"));
        LinearLayout daily = Ui.panel(activity);
        daily.addView(Ui.figures(Ui.medium(activity, "Sleep  " + Insights.duration(progress.sleepMs())
            + (goals.sleepMinutes() == 0 ? " · no goal" : " / " + Insights.duration(goals.sleepMinutes() * CarePredictor.MINUTE) + " goal"), 17, Ui.INK)));
        daily.addView(Ui.figures(Ui.text(activity, "Daytime naps  " + progress.naps()
            + (goals.naps() == 0 ? " · no goal" : " / " + goals.naps() + " goal"), 16, Ui.INK)));
        daily.addView(Ui.figures(Ui.text(activity, "Milk-feed sessions  " + progress.feeds()
            + (goals.feeds() == 0 ? " · no goal" : " / " + goals.feeds() + " goal"), 16, Ui.INK)));
        daily.addView(Ui.text(activity, "A partial day, not a full 24-hour comparison. Running sleep counts so far, excluding pauses. Milk top-ups and split naps count together. Solids are not milk feeds.", 13, Ui.MUTED));
        if (progress.incomplete()) daily.addView(Ui.text(activity, "Some care needs review and is excluded. Totals may be incomplete.", 14, Ui.DANGER));
        daily.addView(Ui.rowButton(activity, "Edit daily goals", () -> editGoals(activity, profile)));
        daily.addView(Ui.text(activity, "Goals stay on this phone for this baby. They do not change estimates, reminders or feeding advice. Do not restrict or add care just to meet a number.", 13, Ui.MUTED));
        body.addView(daily);
        body.addView(Ui.section(activity, "Evening bedtime pattern"));
        LinearLayout evening = Ui.panel(activity);
        CarePredictor.Window bedtime = CarePlanner.bedtime(events, now, zone);
        evening.addView(Ui.figures(Ui.medium(activity, bedtime.available() ? range(bedtime) : "Still learning bedtime", 17, Ui.INK)));
        evening.addView(Ui.text(activity, bedtime.available() ? bedtime.status(now) + "\n" + bedtime.basis() : bedtime.message(), 14, Ui.MUTED));
        evening.addView(Ui.text(activity, "A clock-time pattern, not a forecast of sleeping through the night. Night wakes and feeds are normal. Label evening sleep as Night sleep when logging it to build this pattern.", 13, Ui.MUTED));
        evening.addView(Ui.button(activity, "Log sleep", () -> activity.openEditor(Catalog.get("sleep"), null)));
        body.addView(evening);
        guidance(activity, body, profile);
    }
    static CarePlanner.Goals goals(MainActivity activity, String id) {
        var prefs = activity.getSharedPreferences("careGoals", android.content.Context.MODE_PRIVATE);
        return new CarePlanner.Goals(prefs.getInt(id + ".sleepMinutes", 0), prefs.getInt(id + ".naps", 0), prefs.getInt(id + ".feeds", 0));
    }
    private static void editGoals(MainActivity activity, JSONObject profile) {
        String id = profile.optString("id"); CarePlanner.Goals goals = goals(activity, id);
        LinearLayout content = Ui.column(activity); int pad = Ui.dp(activity, 20); content.setPadding(pad, pad, pad, pad);
        content.addView(Ui.text(activity, "Optional goals for " + profile.optString("name") + " on this phone. Leave a field blank to remove that goal. Nothing is prefilled from age guidance. Follow cues and your clinician's plan, not a quota.", 14, Ui.MUTED));
        EditText sleep = input(activity, content, "Total sleep goal (hours, e.g. 14.5)", goals.sleepMinutes() == 0 ? ""
            : java.math.BigDecimal.valueOf(goals.sleepMinutes()).divide(java.math.BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        EditText naps = input(activity, content, "Daytime nap goal (count, 1–12)", goals.naps() == 0 ? "" : Integer.toString(goals.naps()));
        EditText feeds = input(activity, content, "Milk-feed session goal (count, 1–30)", goals.feeds() == 0 ? "" : Integer.toString(goals.feeds()));
        content.addView(Ui.text(activity, "These are personal planning preferences, not recommended amounts. Goals are not synced or included in backups. Existing interval and alert settings stay unchanged.", 13, Ui.MUTED));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Daily goals").setView(scroll)
            .setPositiveButton("Save goals", null).setNegativeButton("Cancel", null).create();
        dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int minutes = 0;
                if (!sleep.getText().toString().trim().isEmpty()) {
                    try {
                        var hours = new java.math.BigDecimal(sleep.getText().toString().trim());
                        if (hours.signum() < 0 || hours.compareTo(java.math.BigDecimal.valueOf(24)) > 0)
                            throw new IllegalArgumentException("Sleep goal must be from 0 to 24 hours.");
                        minutes = hours.multiply(java.math.BigDecimal.valueOf(60)).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
                    }
                    catch (NumberFormatException | ArithmeticException e) { throw new IllegalArgumentException("Sleep goal must be a number of hours from 0 to 24."); }
                }
                CarePlanner.Goals saved = new CarePlanner.Goals(minutes, number(naps, true), number(feeds, true));
                activity.getSharedPreferences("careGoals", android.content.Context.MODE_PRIVATE).edit()
                    .putInt(id + ".sleepMinutes", saved.sleepMinutes()).putInt(id + ".naps", saved.naps()).putInt(id + ".feeds", saved.feeds()).apply();
                dialog.dismiss(); activity.changed();
            } catch (IllegalArgumentException e) { activity.error(e.getMessage()); }
        });
    }
    private static void guidance(MainActivity activity, LinearLayout body, JSONObject profile) {
        body.addView(Ui.section(activity, "A little context for their age"));
        LinearLayout info = Ui.panel(activity);
        var guidance = CarePlanner.guidance(profile.optString("dob"), java.time.LocalDate.now());
        if (guidance.age().isEmpty()) {
            info.addView(Ui.text(activity, "Add a valid birth date in Family → Edit " + profile.optString("name") + " to see age-specific sleep, nap and feeding context. No age has been assumed.", 15, Ui.INK));
        } else {
            info.addView(Ui.medium(activity, guidance.age() + " · age from birth date", 17, Ui.INK));
            info.addView(Ui.text(activity, "For babies born early, ask your clinician about adjusted age and individual needs. These broad ranges are not a diagnosis or a personalised care plan.", 13, Ui.MUTED));
            info.addView(Ui.title(activity, "Sleep across 24 hours", 16)); info.addView(Ui.text(activity, guidance.sleep(), 14, Ui.INK));
            info.addView(Ui.title(activity, "Daytime naps", 16)); info.addView(Ui.text(activity, guidance.naps(), 14, Ui.INK));
            info.addView(Ui.title(activity, "Milk feeds", 16)); info.addView(Ui.text(activity, guidance.feeds(), 14, Ui.INK));
        }
        info.addView(Ui.text(activity, "Never delay a feed for an estimate, limit intake to a goal, or treat breastfeeding minutes as milk volume. Discuss feeding, growth or unusual sleepiness with your clinician. For sleep, place baby on their back in a clear, flat sleep space; share a room for at least the first 6 months.", 13, Ui.MUTED));
        info.addView(Ui.rowButton(activity, "Sources & how planning works", () -> sources(activity)));
        body.addView(info);
    }
    private static void sources(MainActivity activity) {
        LinearLayout content = Ui.column(activity); int pad = Ui.dp(activity, 20); content.setPadding(pad, pad, pad, pad);
        content.addView(Ui.text(activity, "Guidance reviewed 18 October 2026. Sources describe population patterns, not what one baby must do. Saved goals, age guidance and learned windows are separate. No age-based fallback schedule is generated.", 14, Ui.INK));
        source(activity, content, "CDC · sleep by age", "https://www.cdc.gov/sleep/about/index.html");
        source(activity, content, "NHS · baby sleep and safer sleep", "https://www.nhs.uk/baby/caring-for-a-newborn/helping-your-baby-to-sleep/");
        source(activity, content, "NHS · newborn sleep variation", "https://www.nhs.uk/best-start-in-life/baby/baby-basics/newborn-and-baby-sleeping-advice-for-parents/your-babys-sleep-patterns/");
        source(activity, content, "HSE · naps for babies and toddlers", "https://www2.hse.ie/babies-children/sleep/naps-babies-and-toddlers/");
        source(activity, content, "CDC · breastfeeding frequency", "https://www.cdc.gov/infant-toddler-nutrition/breastfeeding/how-much-and-how-often.html");
        source(activity, content, "CDC · formula feeding frequency", "https://www.cdc.gov/infant-toddler-nutrition/formula-feeding/how-much-and-how-often.html");
        content.addView(Ui.text(activity, "Interval estimates use at least 4 intervals over 2 days, or your manual interval. Normal schedule uses the same daily feed/nap number on 4 previous days. History-adjusted timing needs 4 similar prior days. Bedtime needs 4 evenings labelled Night sleep. All use the last 14 days. Missing logs and changing routines can make any estimate unreliable. Only interval estimates drive optional alerts.", 14, Ui.MUTED));
        content.addView(Ui.text(activity, "Today's nap count includes labelled Nap, or unlabelled sleep starting 05:00–20:00; Night sleep is excluded. Milk top-ups within 30 minutes and split naps within 15 minutes count together on the day the session starts. Sleep includes only the part since local midnight, with overlaps counted once. Incomplete care is excluded.", 14, Ui.MUTED));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        new AlertDialog.Builder(activity).setTitle("Sources & planning").setView(scroll).setNegativeButton("Close", null).show();
    }
    private static void source(MainActivity activity, LinearLayout content, String label, String url) {
        content.addView(Ui.rowButton(activity, label + " · opens browser", () -> {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
            catch (android.content.ActivityNotFoundException e) { activity.error("No browser is available to open " + url); }
        }));
    }
    private static String range(CarePredictor.Window window) {
        return shortTime(window.earliest()) + "–" + shortTime(window.latest());
    }
    private static String shortTime(long time) {
        var local = java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault());
        return local.format(java.time.format.DateTimeFormatter.ofPattern(
            local.toLocalDate().equals(java.time.LocalDate.now()) ? "HH:mm" : "EEE d MMM HH:mm"));
    }
    private static String comparisonLine(String label, CarePredictor.Window window, long now) {
        return label + ": " + (window.available() ? shortTime(window.target())
            + (now > window.latest() ? " · window passed" : now >= window.earliest() ? " · window now" : "") : "Learning / unavailable");
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
