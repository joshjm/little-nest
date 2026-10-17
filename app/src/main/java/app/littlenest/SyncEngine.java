package app.littlenest;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SyncEngine {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    public static boolean active() { return ACTIVE.get(); }

    public static String sync(Context context, String token) throws Exception {
        if (!ACTIVE.compareAndSet(false, true)) return "Sync already in progress";
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        try {
            String folder = prefs.getString("folder", "");
            if (folder.isEmpty()) throw new IllegalStateException("Connect a family folder first");
            DriveApi drive = new DriveApi(token);
            Store store = Store.get(context);
            String result = transfer(drive, store, folder);
            Reminders.scheduleAll(context);
            prefs.edit().putLong("lastSync", System.currentTimeMillis()).putString("syncError", "").apply();
            return result;
        } catch (Exception e) {
            prefs.edit().putString("syncError", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).apply();
            throw e;
        } finally { ACTIVE.set(false); }
    }

    static String transfer(DriveApi drive, Store store, String folder) throws Exception {
        drive.validateFolder(folder);
        int downloaded = pull(drive, store, folder), uploaded = 0;
        for (JSONObject operation : store.operations()) {
            String opId = operation.optString("opId");
            if (!store.marked("uploaded", folder, opId)) {
                String file = drive.upload(folder, operation);
                store.mark("uploaded", folder, opId);
                store.mark("downloaded", folder, file);
                uploaded++;
            }
        }
        downloaded += pull(drive, store, folder);
        return "Synced: " + uploaded + " sent, " + downloaded + " received";
    }

    private static int pull(DriveApi drive, Store store, String folder) throws Exception {
        int count = 0;
        for (JSONObject file : drive.list(folder)) {
            String fileId = file.optString("id");
            if (store.marked("downloaded", folder, fileId)) continue;
            JSONObject operation = drive.download(file);
            store.merge(List.of(operation));
            store.mark("uploaded", folder, operation.optString("opId"));
            store.mark("downloaded", folder, fileId);
            count++;
        }
        return count;
    }
    private SyncEngine() {}
}
