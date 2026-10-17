package app.littlenest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra("id");
        for (JSONObject event : Store.get(context).records("event")) {
            if (event.optString("id").equals(id)) {
                Reminders.notify(context, event);
                Reminders.schedule(context, event);
                break;
            }
        }
    }
}
