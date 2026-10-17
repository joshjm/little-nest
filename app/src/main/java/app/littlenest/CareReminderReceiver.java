package app.littlenest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class CareReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        CareReminders.execute(context, () -> {
            try { CareReminders.receive(context, intent); }
            finally { pending.finish(); }
        });
    }
}
