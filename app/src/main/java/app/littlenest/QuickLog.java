package app.littlenest;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** One-tap logs with no form: a wet nappy, a dirty nappy, or starting and ending sleep. Used by Today and the widgets. */
final class QuickLog {
    static final String WET = "wet", POO = "poo", SLEEP = "sleep";

    /** The baby chosen in the app, or the first baby if a parent profile is chosen. Null when there is no baby yet. */
    static JSONObject baby(Context context) {
        String selected = settings(context).getString("profile", "");
        List<JSONObject> babies = Store.get(context).records("profile").stream()
            .filter(profile -> profile.optString("role").equals("Baby")).toList();
        return babies.stream().filter(profile -> profile.optString("id").equals(selected)).findFirst()
            .orElse(babies.isEmpty() ? null : babies.get(0));
    }

    /** The sleep that is running or paused for this baby, if any. */
    static JSONObject openSleep(Context context, String profileId) {
        return Store.get(context).events(profileId).stream()
            .filter(e -> e.optString("kind").equals("sleep") && (e.optBoolean("running") || e.optBoolean("timerPaused")))
            .findFirst().orElse(null);
    }

    /** Saves the log and returns a short message to show, such as "Wet nappy logged at 14:05". */
    static String log(Context context, String profileId, String action) {
        long now = System.currentTimeMillis();
        String message = switch (action) {
            case WET -> nappy(context, profileId, "Wet", now);
            case POO -> nappy(context, profileId, "Dirty", now);
            case SLEEP -> sleep(context, profileId, now);
            default -> throw new IllegalArgumentException("Unknown quick log");
        };
        CareReminders.refresh(context);
        NestWidgets.refresh(context);
        return message;
    }

    private static String nappy(Context context, String profileId, String type, long now) {
        JSONObject event = event(context, profileId, "diaper", now);
        Records.put(event, "diaper", type);
        save(context, null, event);
        return (type.equals("Wet") ? "Wet" : "Poo") + " nappy logged at " + clock(now);
    }

    private static String sleep(Context context, String profileId, long now) {
        JSONObject open = openSleep(context, profileId);
        if (open != null) {
            JSONObject event = Records.settleTimer(open, now);
            Records.put(event, "running", false); Records.put(event, "timerPaused", false);
            Records.put(event, "updatedBy", caregiver(context));
            save(context, open.optString("id"), event);
            return "Sleep ended \u00b7 " + Insights.duration(Records.duration(event, now));
        }
        JSONObject event = event(context, profileId, "sleep", now);
        Records.put(event, "running", true); Records.put(event, "segmentStart", now);
        save(context, null, event);
        return "Sleep started at " + clock(now);
    }

    /** A new log filled in the same way the editor fills an untouched form. */
    private static JSONObject event(Context context, String profileId, String kindId, long now) {
        JSONObject event = new JSONObject();
        Records.put(event, "entity", "event"); Records.put(event, "profileId", profileId); Records.put(event, "kind", kindId);
        Records.put(event, "start", now); Records.put(event, "end", now); Records.put(event, "elapsedMs", 0);
        Records.put(event, "running", false); Records.put(event, "timerPaused", false);
        for (Catalog.Field field : Catalog.get(kindId).fields())
            Records.put(event, field.key(), field.options().isEmpty() ? "" : field.options().split("\\|")[0]);
        Records.put(event, "notes", ""); Records.put(event, "photo", "");
        Records.put(event, "remindAt", 0); Records.put(event, "repeatMinutes", 0);
        Records.put(event, "caregiver", caregiver(context)); Records.put(event, "updatedBy", caregiver(context));
        return event;
    }

    private static void save(Context context, String id, JSONObject event) {
        Records.put(event, "id", Store.get(context).save(id, event, false));
        Reminders.schedule(context, event);
    }

    private static String caregiver(Context context) { return settings(context).getString("caregiver", "Caregiver"); }
    private static SharedPreferences settings(Context context) { return context.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    static String clock(long time) { return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")); }

    private QuickLog() {}
}
