package app.littlenest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Handles taps on the quick-log widget. Not exported, so only this app's widget buttons can reach it. */
public final class QuickLogReceiver extends BroadcastReceiver {
    static final String EXTRA_ACTION = "quickAction";
    // One at a time, so two fast taps on the sleep button start and then end sleep in order.
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getStringExtra(EXTRA_ACTION);
        if (action == null) return;
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        WORK.execute(() -> {
            String message;
            try {
                JSONObject baby = QuickLog.baby(app);
                message = baby == null ? "Open Little Nest to add your baby first" : QuickLog.log(app, baby.optString("id"), action);
            } catch (RuntimeException e) {
                android.util.Log.e("LittleNest", "Quick log failed", e);
                message = "Could not save. Open Little Nest and try again.";
            } finally { pending.finish(); }
            String shown = message;
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, shown, Toast.LENGTH_SHORT).show());
        });
    }
}
