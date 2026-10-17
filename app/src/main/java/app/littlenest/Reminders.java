package app.littlenest;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import org.json.JSONObject;

public final class Reminders {
    public static synchronized void scheduleAll(Context context) {
        var prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE);
        for (String id : prefs.getStringSet("scheduled", java.util.Set.of())) cancel(context, id);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JSONObject event : Store.get(context).records("event")) {
            schedule(context, event);
            if (event.optLong("remindAt") > 0) ids.add(event.optString("id"));
        }
        prefs.edit().putStringSet("scheduled", ids).apply();
        CareReminders.scheduleAll(context);
    }
    public static synchronized void cancel(Context context, String id) {
        context.getSystemService(AlarmManager.class).cancel(intent(context, id));
    }
    private static PendingIntent intent(Context context, String id) {
        Intent intent = new Intent(context, ReminderReceiver.class).setData(Uri.parse("littlenest:reminder/" + id)).putExtra("id", id);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    public static synchronized void schedule(Context context, JSONObject event) {
        String id = event.optString("id");
        cancel(context, id);
        long when = nextTime(event, System.currentTimeMillis());
        if (when > 0) {
            context.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent(context, id));
            var prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE);
            java.util.Set<String> ids = new java.util.HashSet<>(prefs.getStringSet("scheduled", java.util.Set.of()));
            ids.add(id); prefs.edit().putStringSet("scheduled", ids).apply();
        }
    }
    static long nextTime(JSONObject event, long now) {
        long first = event.optLong("remindAt");
        if (first <= 0) return 0;
        long repeat = event.optLong("repeatMinutes") * 60_000L;
        if (first <= now) {
            if (repeat <= 0) return 0;
            first += ((now - first) / repeat + 1) * repeat;
        }
        return first;
    }
    public static void notify(Context context, JSONObject event) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("care", context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_DEFAULT));
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("reminderError", "Notifications are disabled. Enable them in Android settings.").apply();
            return;
        }
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = event.optString("name", Catalog.get(event.optString("kind")).title());
        if (title.isBlank()) title = Catalog.get(event.optString("kind")).title();
        Notification notification = new Notification.Builder(context, "care")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("Little Nest reminder")
            .setContentText(title).setContentIntent(pending).setAutoCancel(true).build();
        manager.notify(event.optString("id").hashCode(), notification);
    }
    private Reminders() {}
}
