package app.littlenest;

import android.content.Context;
import java.util.UUID;

final class SyncFamily {
    static synchronized String ensure(Context context) {
        var prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String family = prefs.getString("syncFamily", "");
        if (family.isEmpty()) {
            family = UUID.randomUUID().toString();
            if (!prefs.edit().putString("syncFamily", family).commit()) throw new IllegalStateException("Cannot save family identity");
        }
        return family;
    }

    static synchronized void check(Context context, String family) {
        if (!UUID.fromString(family).toString().equals(family)) throw new IllegalArgumentException("Invalid family identity");
        var prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String existing = prefs.getString("syncFamily", "");
        boolean established = prefs.getBoolean("syncFamilyEstablished", false)
            || !prefs.getString("localPeer", "").isEmpty() || !prefs.getString("oneDriveFolder", "").isEmpty();
        if (established && !existing.isEmpty() && !existing.equals(family))
            throw new IllegalArgumentException("This phone belongs to another nest. Export a backup before clearing app storage to join another family.");
    }
    static synchronized void accept(Context context, String family) {
        check(context, family);
        if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("syncFamily", family).putBoolean("syncFamilyEstablished", true).commit())
            throw new IllegalStateException("Cannot save family identity");
    }
    private SyncFamily() {}
}
