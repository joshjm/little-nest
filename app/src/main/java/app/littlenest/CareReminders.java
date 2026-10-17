package app.littlenest;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CareReminders {
    static final String CHANNEL = "suggested-care";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    public record Options(boolean feed, boolean nap, int leadMinutes, int quietStart, int quietEnd,
                          CarePredictor.Settings intervals) {
        public Options {
            if (leadMinutes < 0 || leadMinutes > 120) throw new IllegalArgumentException("Advance notice must be 0–120 minutes");
            if (quietStart < 0 || quietStart > 23 || quietEnd < 0 || quietEnd > 23) throw new IllegalArgumentException("Quiet hours must be 0–23");
        }
        public boolean enabled(String type) { return type.equals("feed") ? feed : nap; }
    }
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("care-plan", Context.MODE_PRIVATE); }
    public static Options options(Context context, String profile) {
        SharedPreferences prefs = prefs(context);
        return new Options(prefs.getBoolean(profile + ".feed", false), prefs.getBoolean(profile + ".nap", false),
            prefs.getInt(profile + ".lead", 15), prefs.getInt(profile + ".quietStart", 22), prefs.getInt(profile + ".quietEnd", 6),
            new CarePredictor.Settings(prefs.getInt(profile + ".feedMinutes", 0), prefs.getInt(profile + ".wakeMinutes", 0)));
    }
    public static void save(Context context, String profile, Options options) {
        prefs(context).edit().putBoolean(profile + ".feed", options.feed).putBoolean(profile + ".nap", options.nap)
            .putInt(profile + ".lead", options.leadMinutes).putInt(profile + ".quietStart", options.quietStart).putInt(profile + ".quietEnd", options.quietEnd)
            .putInt(profile + ".feedMinutes", options.intervals.feedMinutes()).putInt(profile + ".wakeMinutes", options.intervals.wakeMinutes()).apply();
        refresh(context);
    }
    public static void refresh(Context context) { execute(context, () -> scheduleAll(context)); }
    static void execute(Context context, Runnable action) {
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            try { action.run(); prefs(app).edit().remove("error").apply(); }
            catch (RuntimeException e) {
                Log.e("LittleNestCare", "Suggested-care reminder operation failed", e);
                prefs(app).edit().putString("error", "Care reminders need attention: " + e.getMessage()).apply();
            }
        });
    }
    public static String error(Context context) { return prefs(context).getString("error", ""); }
    public static String notificationStatus(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return "Notifications are not permitted. Enable notifications to receive advance alerts.";
        if (!manager.areNotificationsEnabled()) return "Android has blocked this app's notifications.";
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
            return "The Suggested care notification channel is disabled in Android settings.";
        return "";
    }
    static String key(String profile, String type) { return profile + "." + type; }
    private static PendingIntent alarm(Context context, String profile, String type, String cycle, String action) {
        Intent intent = new Intent(context, CareReminderReceiver.class).setAction(action)
            .setData(Uri.parse("littlenest:care/" + profile + "/" + type + "/" + action
                + (action.equals("snooze") || action.equals("dismiss") ? "/" + cycle : "")))
            .putExtra("profile", profile).putExtra("type", type).putExtra("cycle", cycle);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static synchronized void scheduleAll(Context context) {
        SharedPreferences prefs = prefs(context);
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        Set<String> old = prefs.getStringSet("scheduled", Set.of()), scheduled = new HashSet<>();
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        boolean anyEnabled = false;
        for (JSONObject profile : Store.get(context).records("profile")) {
            if (!profile.optString("role").equals("Baby")) continue;
            String profileId = profile.optString("id");
            Options options = options(context, profileId);
            anyEnabled |= options.feed || options.nap;
            if (!options.feed && !options.nap && old.stream().noneMatch(key -> key.startsWith(profileId + "."))) continue;
            List<CarePredictor.Window> windows = CarePredictor.predict(Store.get(context).events(profileId), options.intervals, now, zone);
            for (CarePredictor.Window window : windows) {
                String key = key(profileId, window.type()); scheduled.add(key);
                manager.cancel(alarm(context, profileId, window.type(), "", "notify"));
                String priorCycle = prefs.getString(key + ".cycle", "");
                if (!options.enabled(window.type()) || !window.available() || !priorCycle.equals(window.cycle()) || now > window.latest())
                    context.getSystemService(NotificationManager.class).cancel("care:" + key, 0);
                prefs.edit().putString(key + ".cycle", window.cycle()).apply();
                long when = nextAlert(window, options, now, zone, prefs.getString(key + ".fired", ""), prefs.getString(key + ".dismissed", ""),
                    prefs.getString(key + ".snoozeCycle", ""), prefs.getLong(key + ".snoozeAt", 0));
                prefs.edit().putLong(key + ".scheduledAt", when).apply();
                if (when > 0 && notificationStatus(context).isEmpty())
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, alarm(context, profileId, window.type(), window.cycle(), "notify"));
            }
        }
        for (String removed : old) {
            if (scheduled.contains(removed)) continue;
            int split = removed.lastIndexOf('.');
            manager.cancel(alarm(context, removed.substring(0, split), removed.substring(split + 1), "", "notify"));
            context.getSystemService(NotificationManager.class).cancel("care:" + removed, 0);
        }
        prefs.edit().putStringSet("scheduled", scheduled).apply();
        manager.cancel(alarm(context, "all", "all", "", "refresh"));
        if (anyEnabled) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 6 * 60 * CarePredictor.MINUTE, alarm(context, "all", "all", "", "refresh"));
    }
    static long nextAlert(CarePredictor.Window window, Options options, long now, ZoneId zone,
                          String fired, String dismissed, String snoozeCycle, long snoozeAt) {
        if (!window.available() || !options.enabled(window.type()) || window.cycle().equals(dismissed)) return 0;
        boolean snoozed = window.cycle().equals(snoozeCycle) && snoozeAt > 0;
        if (!snoozed && window.cycle().equals(fired)) return 0;
        long when = Math.max(now, snoozed ? snoozeAt : window.earliest() - options.leadMinutes * CarePredictor.MINUTE);
        when = afterQuietHours(when, options.quietStart, options.quietEnd, zone);
        return when <= window.latest() ? when : 0;
    }
    static long afterQuietHours(long time, int start, int end, ZoneId zone) {
        if (start == end) return time;
        var local = Instant.ofEpochMilli(time).atZone(zone);
        int hour = local.getHour();
        boolean quiet = start < end ? hour >= start && hour < end : hour >= start || hour < end;
        if (!quiet) return time;
        var date = local.toLocalDate();
        if (start > end && hour >= start) date = date.plusDays(1);
        return date.atTime(end, 0).atZone(zone).toInstant().toEpochMilli();
    }
    public static String reminderState(Context context, String profile, CarePredictor.Window window) {
        if (!options(context, profile).enabled(window.type())) return "Advance alerts off";
        String key = key(profile, window.type()); SharedPreferences prefs = prefs(context);
        if (window.cycle().equals(prefs.getString(key + ".dismissed", ""))) return "Dismissed for this cycle";
        if (window.cycle().equals(prefs.getString(key + ".snoozeCycle", "")) && prefs.getLong(key + ".snoozeAt", 0) > System.currentTimeMillis())
            return "Snoozed until " + EventEditor.format(prefs.getLong(key + ".snoozeAt", 0));
        if (window.cycle().equals(prefs.getString(key + ".fired", ""))) return "Advance alert sent for this cycle";
        return "Advance alerts on · " + options(context, profile).leadMinutes + " min before window · quiet hours apply";
    }
    public static void act(Context context, String profile, CarePredictor.Window window, boolean snooze) {
        if (!window.available()) return;
        String key = key(profile, window.type());
        SharedPreferences.Editor edit = prefs(context).edit();
        if (snooze) edit.putString(key + ".snoozeCycle", window.cycle()).putLong(key + ".snoozeAt", System.currentTimeMillis() + 15 * CarePredictor.MINUTE)
            .remove(key + ".fired").remove(key + ".dismissed");
        else edit.putString(key + ".dismissed", window.cycle()).remove(key + ".snoozeCycle").remove(key + ".snoozeAt");
        edit.apply();
        context.getSystemService(NotificationManager.class).cancel("care:" + key, 0);
        refresh(context);
    }
    static void receive(Context context, Intent intent) {
        if ("refresh".equals(intent.getAction())) { scheduleAll(context); return; }
        String profileId = intent.getStringExtra("profile"), type = intent.getStringExtra("type"), cycle = intent.getStringExtra("cycle");
        JSONObject profile = Store.get(context).records("profile").stream().filter(item -> item.optString("id").equals(profileId) && item.optString("role").equals("Baby")).findFirst().orElse(null);
        if (profile == null) { scheduleAll(context); return; }
        Options options = options(context, profileId);
        for (CarePredictor.Window window : CarePredictor.predict(Store.get(context).events(profileId), options.intervals, System.currentTimeMillis(), ZoneId.systemDefault())) {
            if (!window.type().equals(type) || !window.available() || !window.cycle().equals(cycle)) continue;
            if ("snooze".equals(intent.getAction()) || "dismiss".equals(intent.getAction())) {
                act(context, profileId, window, "snooze".equals(intent.getAction())); return;
            }
            if (!"notify".equals(intent.getAction())) return;
            String key = key(profileId, type); SharedPreferences prefs = prefs(context); long now = System.currentTimeMillis();
            long when = nextAlert(window, options, now, ZoneId.systemDefault(), prefs.getString(key + ".fired", ""), prefs.getString(key + ".dismissed", ""),
                prefs.getString(key + ".snoozeCycle", ""), prefs.getLong(key + ".snoozeAt", 0));
            if (when > 0 && when <= now && notificationStatus(context).isEmpty()) {
                if (notify(context, profile, window))
                    prefs.edit().putString(key + ".fired", cycle).remove(key + ".snoozeCycle").remove(key + ".snoozeAt").apply();
            }
        }
        scheduleAll(context);
    }
    private static boolean notify(Context context, JSONObject profile, CarePredictor.Window window) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Suggested care", NotificationManager.IMPORTANCE_DEFAULT));
        // Recheck after channel creation: Android can retain a previously blocked channel.
        if (!notificationStatus(context).isEmpty()) return false;
        String profileId = profile.optString("id");
        Intent open = new Intent(context, MainActivity.class).setData(Uri.parse("littlenest:upcoming/" + profileId))
            .putExtra("careProfile", profileId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(profile.optString("name") + " · " + window.title())
            .setContentText("Estimated window " + EventEditor.format(window.earliest()) + " – " + EventEditor.format(window.latest()))
            .setStyle(new Notification.BigTextStyle().bigText("Prepare if useful. This is a pattern estimate, not a deadline. Follow baby's hunger and sleep cues; do not delay feeding to match it."))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null, "Snooze 15 min", alarm(context, profileId, window.type(), window.cycle(), "snooze")).build())
            .addAction(new Notification.Action.Builder(null, "Dismiss cycle", alarm(context, profileId, window.type(), window.cycle(), "dismiss")).build())
            .setDeleteIntent(alarm(context, profileId, window.type(), window.cycle(), "dismiss")).build();
        manager.notify("care:" + key(profileId, window.type()), 0, notification);
        return true;
    }
    private CareReminders() {}
}
