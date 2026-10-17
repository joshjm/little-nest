package app.littlenest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!java.util.List.of(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED).contains(intent.getAction())) return;
        PendingResult pending = goAsync();
        CareReminders.execute(context, () -> {
            try {
                Reminders.scheduleAll(context);
                if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("folder", "").isEmpty()) SyncJob.schedule(context);
            } finally { pending.finish(); }
        });
    }
}
