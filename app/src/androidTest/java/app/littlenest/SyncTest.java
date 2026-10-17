package app.littlenest;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SyncTest {
    private static final String FOLDER = "drive123|folder123";
    private static final String FAMILY = "10bcce6b-6f26-45ea-bd56-24f6967a556d";

    private static final class FakeDrive implements OneDriveApi.Transport {
        final Map<String, JSONObject> files = new LinkedHashMap<>();
        boolean rejectWrites, loseUploadReply;
        int memberCount = 2, fileCounter;
        @Override public byte[] send(String method, String address, byte[] body, String contentType, int limit) throws IOException {
            try {
                if (method.equals("PUT") && address.contains("nest-op-")) {
                    if (rejectWrites) throw new IOException("Offline");
                    JSONObject op = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    String id = "remote_file_" + ++fileCounter;
                    files.put(id, op);
                    if (loseUploadReply) { loseUploadReply = false; throw new IOException("Upload completed but reply was lost"); }
                    return bytes(new JSONObject().put("id", id));
                }
                if (address.contains("nest-family.json")) return bytes(new JSONObject().put("schema", 1).put("family", FAMILY));
                if (address.endsWith("/content")) {
                    String[] segments = address.split("/");
                    String id = segments[segments.length - 2];
                    return bytes(files.get(id));
                }
                if (address.contains("/permissions")) {
                    JSONArray permissions = new JSONArray();
                    for (int i = 0; i < memberCount; i++) permissions.put(new JSONObject()
                        .put("roles", new JSONArray().put(i == 0 ? "owner" : "write"))
                        .put("grantedToV2", new JSONObject().put("user", new JSONObject().put("id", "member" + i))));
                    return bytes(new JSONObject().put("value", permissions));
                }
                if (address.contains("/children?")) {
                    JSONArray list = new JSONArray();
                    for (Map.Entry<String, JSONObject> file : files.entrySet())
                        list.put(new JSONObject().put("id", file.getKey()).put("name", "nest-op-" + file.getValue().getString("opId") + ".json")
                            .put("size", file.getValue().toString().getBytes(StandardCharsets.UTF_8).length)
                            .put("parentReference", new JSONObject().put("driveId", "drive123")));
                    return bytes(new JSONObject().put("value", list));
                }
                return bytes(new JSONObject().put("folder", new JSONObject()));
            } catch (org.json.JSONException e) { throw new IOException(e); }
        }
        private byte[] bytes(JSONObject json) { return json.toString().getBytes(StandardCharsets.UTF_8); }
    }

    private JSONObject profile(String name) {
        JSONObject value = new JSONObject(); Records.put(value, "entity", "profile"); Records.put(value, "role", "Baby"); Records.put(value, "name", name); return value;
    }
    private JSONObject event(String profile, String note) {
        JSONObject value = new JSONObject(); Records.put(value, "entity", "event"); Records.put(value, "profileId", profile);
        Records.put(value, "kind", "bottle"); Records.put(value, "start", 1000); Records.put(value, "end", 1000);
        Records.put(value, "notes", note); Records.put(value, "amount", "120"); Records.put(value, "unit", "ml"); return value;
    }
    private Store db() { return new Store(InstrumentationRegistry.getInstrumentation().getTargetContext(), "test-" + UUID.randomUUID() + ".db"); }
    private void remove(Store db) {
        String name = db.getDatabaseName(); db.close(); InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(name);
    }

    @Test public void twoOfflinePhonesExchangeEditsPhotosTimersAndDeletions() throws Exception {
        Store one = db(), two = db();
        try {
            FakeDrive remote = new FakeDrive(); OneDriveApi api = new OneDriveApi("test-only", FAMILY, remote);
            String child = one.save(null, profile("Shared baby"), false);
            SyncEngine.transfer(api, one, FOLDER); SyncEngine.transfer(api, two, FOLDER);
            assertEquals(1, two.records("profile").size());
            JSONObject a = event(child, "One offline"); Records.put(a, "photo", "c2FtcGxlLXBob3Rv");
            String first = one.save(null, a, false);
            JSONObject b = event(child, "Two offline"); String second = two.save(null, b, false);
            SyncEngine.transfer(api, one, FOLDER); SyncEngine.transfer(api, two, FOLDER); SyncEngine.transfer(api, one, FOLDER);
            assertEquals(2, one.events(child).size()); assertEquals(2, two.events(child).size());
            assertTrue(two.events(child).stream().anyMatch(e -> e.optString("photo").equals("c2FtcGxlLXBob3Rv")));
            one.save(first, a, true);
            Records.put(b, "notes", "Partner edit"); two.save(second, b, false);
            SyncEngine.transfer(api, two, FOLDER); SyncEngine.transfer(api, one, FOLDER); SyncEngine.transfer(api, two, FOLDER);
            assertEquals(1, one.events(child).size()); assertEquals(1, two.events(child).size());
            assertEquals("Partner edit", one.events(child).get(0).optString("notes"));
            int count = remote.files.size();
            SyncEngine.transfer(api, one, FOLDER); SyncEngine.transfer(api, two, FOLDER);
            assertEquals(count, remote.files.size());
            assertEquals(one.backup(), two.backup());
        } finally { remove(one); remove(two); }
    }

    @Test public void lostUploadResponseRetriesWithoutDuplicateLogicalRecords() throws Exception {
        Store one = db(), two = db();
        try {
            FakeDrive remote = new FakeDrive(); OneDriveApi api = new OneDriveApi("test-only", FAMILY, remote);
            one.save(null, profile("Retry baby"), false);
            remote.loseUploadReply = true;
            assertThrows(IOException.class, () -> SyncEngine.transfer(api, one, FOLDER));
            SyncEngine.transfer(api, one, FOLDER); SyncEngine.transfer(api, two, FOLDER);
            assertEquals(1, one.records("profile").size()); assertEquals(1, two.records("profile").size());
            assertEquals(1, remote.files.size());
        } finally { remove(one); remove(two); }
    }

    @Test public void failedSyncKeepsPendingChangesAndThirdAccountBlocksWrites() throws Exception {
        Store one = db();
        try {
            FakeDrive remote = new FakeDrive(); OneDriveApi api = new OneDriveApi("test-only", FAMILY, remote);
            one.save(null, profile("Offline baby"), false); remote.rejectWrites = true;
            assertThrows(IOException.class, () -> SyncEngine.transfer(api, one, FOLDER));
            assertEquals(1, one.records("profile").size()); assertEquals(0, remote.files.size());
            remote.rejectWrites = false; remote.memberCount = 3;
            assertThrows(IOException.class, () -> SyncEngine.transfer(api, one, FOLDER));
            assertEquals(0, remote.files.size());
            remote.memberCount = 2; SyncEngine.transfer(api, one, FOLDER); assertEquals(1, remote.files.size());
        } finally { remove(one); }
    }
    @Test public void cancelledTransferDoesNotUploadOrDiscardPendingData() throws Exception {
        Store one = db();
        try {
            FakeDrive remote = new FakeDrive(); OneDriveApi api = new OneDriveApi("test-only", FAMILY, remote);
            one.save(null, profile("Cancelled baby"), false);
            Thread.currentThread().interrupt();
            try { assertThrows(java.io.InterruptedIOException.class, () -> SyncEngine.transfer(api, one, FOLDER)); }
            finally { Thread.interrupted(); }
            assertEquals(0, remote.files.size()); assertEquals(1, one.records("profile").size());
            SyncEngine.transfer(api, one, FOLDER); assertEquals(1, remote.files.size());
        } finally { remove(one); }
    }
}
