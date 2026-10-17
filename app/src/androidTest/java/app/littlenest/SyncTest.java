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
    private static final String FOLDER = "family_folder_12345";

    private static final class FakeDrive implements DriveApi.Transport {
        final Map<String, JSONObject> files = new LinkedHashMap<>();
        boolean rejectWrites, loseUploadReply;
        int memberCount = 2, fileCounter;
        @Override public byte[] send(String method, String address, byte[] body, String contentType, int limit) throws IOException {
            try {
                if (address.contains("/upload/")) {
                    if (rejectWrites) throw new IOException("Offline");
                    String multipart = new String(body, StandardCharsets.UTF_8);
                    String[] sections = multipart.split("\r\n\r\n");
                    String json = sections[2].substring(0, sections[2].lastIndexOf("\r\n--"));
                    JSONObject op = new JSONObject(json);
                    String id = "remote_file_" + ++fileCounter;
                    files.put(id, op);
                    if (loseUploadReply) { loseUploadReply = false; throw new IOException("Upload completed but reply was lost"); }
                    return bytes(new JSONObject().put("id", id));
                }
                if (address.endsWith("?alt=media")) {
                    String id = address.substring(address.lastIndexOf('/') + 1).split("\\?")[0];
                    return bytes(files.get(id));
                }
                if (address.contains("/permissions")) {
                    JSONArray permissions = new JSONArray();
                    for (int i = 0; i < memberCount; i++) permissions.put(new JSONObject().put("type", "user").put("role", i == 0 ? "owner" : "writer"));
                    return bytes(new JSONObject().put("permissions", permissions));
                }
                if (address.contains("/files?q=")) {
                    JSONArray list = new JSONArray();
                    for (Map.Entry<String, JSONObject> file : files.entrySet())
                        list.put(new JSONObject().put("id", file.getKey()).put("name", "nest-op-" + file.getValue().getString("opId") + ".json")
                            .put("size", file.getValue().toString().getBytes(StandardCharsets.UTF_8).length));
                    return bytes(new JSONObject().put("files", list));
                }
                return bytes(new JSONObject().put("mimeType", "application/vnd.google-apps.folder").put("trashed", false)
                    .put("appProperties", new JSONObject().put("littleNestSchema", "1"))
                    .put("capabilities", new JSONObject().put("canAddChildren", true)));
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
            FakeDrive remote = new FakeDrive(); DriveApi api = new DriveApi("test-only", remote);
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
            FakeDrive remote = new FakeDrive(); DriveApi api = new DriveApi("test-only", remote);
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
            FakeDrive remote = new FakeDrive(); DriveApi api = new DriveApi("test-only", remote);
            one.save(null, profile("Offline baby"), false); remote.rejectWrites = true;
            assertThrows(IOException.class, () -> SyncEngine.transfer(api, one, FOLDER));
            assertEquals(1, one.records("profile").size()); assertEquals(0, remote.files.size());
            remote.rejectWrites = false; remote.memberCount = 3;
            assertThrows(IllegalArgumentException.class, () -> SyncEngine.transfer(api, one, FOLDER));
            assertEquals(0, remote.files.size());
            remote.memberCount = 2; SyncEngine.transfer(api, one, FOLDER); assertEquals(1, remote.files.size());
        } finally { remove(one); }
    }
    @Test public void cancelledTransferDoesNotUploadOrDiscardPendingData() throws Exception {
        Store one = db();
        try {
            FakeDrive remote = new FakeDrive(); DriveApi api = new DriveApi("test-only", remote);
            one.save(null, profile("Cancelled baby"), false);
            Thread.currentThread().interrupt();
            try { assertThrows(java.io.InterruptedIOException.class, () -> SyncEngine.transfer(api, one, FOLDER)); }
            finally { Thread.interrupted(); }
            assertEquals(0, remote.files.size()); assertEquals(1, one.records("profile").size());
            SyncEngine.transfer(api, one, FOLDER); assertEquals(1, remote.files.size());
        } finally { remove(one); }
    }
}
