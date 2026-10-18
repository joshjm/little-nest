package app.littlenest;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import org.json.JSONObject;
import java.time.ZoneId;
import java.util.List;

/** Draws both home-screen widgets. Times are shown as clock times so they stay correct between refreshes. */
final class NestWidgets {
    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) return;
        int[] quick = manager.getAppWidgetIds(new ComponentName(context, QuickWidget.class));
        int[] status = manager.getAppWidgetIds(new ComponentName(context, StatusWidget.class));
        if (quick.length == 0 && status.length == 0) return;
        JSONObject baby = QuickLog.baby(context);
        if (quick.length > 0) manager.updateAppWidget(quick, quick(context, baby));
        if (status.length > 0) manager.updateAppWidget(status, status(context, baby));
    }

    private static RemoteViews quick(Context context, JSONObject baby) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick);
        boolean sleeping = baby != null && QuickLog.openSleep(context, baby.optString("id")) != null;
        views.setTextViewText(R.id.quick_sleep, sleeping ? "End sleep" : "Start sleep");
        views.setOnClickPendingIntent(R.id.quick_wet, baby == null ? open(context, null) : log(context, QuickLog.WET, 1));
        views.setOnClickPendingIntent(R.id.quick_poo, baby == null ? open(context, null) : log(context, QuickLog.POO, 2));
        views.setOnClickPendingIntent(R.id.quick_sleep, baby == null ? open(context, null) : log(context, QuickLog.SLEEP, 3));
        return views;
    }

    private static RemoteViews status(Context context, JSONObject baby) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_status);
        views.setOnClickPendingIntent(R.id.status_root, open(context, baby));
        if (baby == null) {
            views.setTextViewText(R.id.status_title, "Little Nest");
            for (int id : new int[]{R.id.status_nappy, R.id.status_sleep, R.id.status_next_sleep, R.id.status_next_feed})
                views.setTextViewText(id, "\u2014");
            return views;
        }
        String profileId = baby.optString("id");
        List<JSONObject> events = Store.get(context).events(profileId);
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        views.setTextViewText(R.id.status_title, baby.optString("name", "Little Nest"));

        JSONObject nappy = events.stream().filter(e -> e.optString("kind").equals("diaper")).findFirst().orElse(null);
        views.setTextViewText(R.id.status_nappy, nappy == null ? "None yet"
            : nappy.optString("diaper", "Nappy") + " \u00b7 " + AtAGlance.time(nappy.optLong("start"), now, zone));

        JSONObject sleep = events.stream().filter(e -> e.optString("kind").equals("sleep") && !e.optBoolean("importIncomplete")).findFirst().orElse(null);
        views.setTextViewText(R.id.status_sleep, sleep == null ? "None yet"
            : sleep.optBoolean("running") ? "Asleep since " + AtAGlance.time(sleep.optLong("start"), now, zone)
            : sleep.optBoolean("timerPaused") ? "Sleep timer paused"
            : "Woke " + AtAGlance.time(sleep.optLong("end"), now, zone));

        boolean asleep = sleep != null && (sleep.optBoolean("running") || sleep.optBoolean("timerPaused"));
        String nextSleep = asleep ? "Asleep now" : "No estimate yet", nextFeed = "No estimate yet";
        for (CarePredictor.Window window : CarePredictor.predict(events, CareReminders.options(context, profileId).intervals(), now, zone)) {
            CarePredictor.Window best = AtAGlance.best(CareComparison.predict(events, window.type(), now, zone), window);
            if (!best.available()) continue;
            String when = AtAGlance.clock(best.target(), best.latest(), now, zone);
            if (window.type().equals("feed")) nextFeed = when; else nextSleep = when;
        }
        views.setTextViewText(R.id.status_next_sleep, nextSleep);
        views.setTextViewText(R.id.status_next_feed, nextFeed);
        return views;
    }

    private static PendingIntent log(Context context, String action, int request) {
        Intent intent = new Intent(context, QuickLogReceiver.class).putExtra(QuickLogReceiver.EXTRA_ACTION, action);
        return PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent open(Context context, JSONObject baby) {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (baby != null) intent.putExtra("careProfile", baby.optString("id"));
        return PendingIntent.getActivity(context, baby == null ? 10 : 11, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private NestWidgets() {}
}
