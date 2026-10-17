package app.littlenest;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class SyncJob extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final class RunningJob {
        volatile boolean stopped;
        Future<?> future;
    }
    private RunningJob running;

    public static void schedule(Context context) {
        JobInfo job = new JobInfo.Builder(7, new ComponentName(context, SyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15 * 60_000L).setPersisted(true).build();
        if (context.getSystemService(JobScheduler.class).schedule(job) == JobScheduler.RESULT_FAILURE)
            context.getSharedPreferences("settings", MODE_PRIVATE).edit().putString("syncError", "Android could not schedule background sync. Open the app to sync.").apply();
    }

    @Override public boolean onStartJob(JobParameters params) {
        RunningJob run = new RunningJob(); running = run;
        run.future = executor.submit(() -> {
            boolean retry = false;
            try {
                if (!getSharedPreferences("settings", MODE_PRIVATE).getString("oneDriveFolder", "").isEmpty()) {
                    SyncEngine.sync(this);
                }
            } catch (Exception e) {
                getSharedPreferences("settings", MODE_PRIVATE).edit().putString("syncError",
                    "Background sync: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())).apply();
                android.util.Log.w("LittleNest", "Background sync did not finish: " + e.getClass().getSimpleName());
                retry = true;
            } finally { if (!run.stopped) jobFinished(params, retry); }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopRunning();
        return true;
    }
    private void stopRunning() {
        if (running != null) {
            running.stopped = true;
            if (running.future != null) running.future.cancel(true);
        }
    }
    @Override public void onDestroy() { stopRunning(); executor.shutdownNow(); super.onDestroy(); }
}
