package app.littlenest;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SyncEngine {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    public static boolean active() { return ACTIVE.get(); }

    public static String sync(Context context) throws Exception {
        if (!ACTIVE.compareAndSet(false, true)) return "Sync already in progress";
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        try {
            String folder = prefs.getString("oneDriveFolder", "");
            if (folder.isEmpty()) throw new IllegalStateException("Connect a family folder first");
            OneDriveApi drive = new OneDriveApi(OneDriveAuth.token(context), SyncFamily.ensure(context));
            Store store = Store.get(context);
            String result = transfer(drive, store, "onedrive:" + folder, folder);
            Reminders.scheduleAll(context);
            prefs.edit().putLong("lastSync", System.currentTimeMillis()).putString("syncError", "").apply();
            return result;
        } catch (Exception e) {
            prefs.edit().putString("syncError", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).apply();
            throw e;
        } finally { ACTIVE.set(false); }
    }

    static String transfer(SyncTransport drive, Store store, String folder) throws Exception {
        return transfer(drive, store, "onedrive:" + folder, folder);
    }
    private static String transfer(SyncTransport drive, Store store, String checkpoint, String folder) throws Exception {
        drive.validateFolder(folder);
        int downloaded = pull(drive, store, checkpoint, folder), uploaded = 0;
        for (JSONObject operation : store.operations()) {
            String opId = operation.optString("opId");
            if (!store.marked("uploaded", checkpoint, opId)) {
                String file = drive.upload(folder, operation);
                store.mark("uploaded", checkpoint, opId);
                store.mark("downloaded", checkpoint, file);
                uploaded++;
            }
        }
        downloaded += pull(drive, store, checkpoint, folder);
        return "Synced: " + uploaded + " sent, " + downloaded + " received";
    }

    private static int pull(SyncTransport drive, Store store, String checkpoint, String folder) throws Exception {
        int count = 0;
        for (JSONObject file : drive.list(folder)) {
            String fileId = file.optString("id");
            if (store.marked("downloaded", checkpoint, fileId)) continue;
            JSONObject operation = drive.download(file);
            store.merge(List.of(operation));
            store.mark("uploaded", checkpoint, operation.optString("opId"));
            store.mark("downloaded", checkpoint, fileId);
            count++;
        }
        return count;
    }
    private SyncEngine() {}
}
