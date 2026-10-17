package app.littlenest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

public class SyncTransportTest {
    private static final String FAMILY = "10bcce6b-6f26-45ea-bd56-24f6967a556d";
    private static byte[] bytes(JSONObject value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
    private JSONObject operation() {
        JSONObject profile = new JSONObject(); Records.put(profile, "entity", "profile");
        Records.put(profile, "role", "Baby"); Records.put(profile, "name", "Test");
        return Records.operation(UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), profile, false);
    }
    @Test public void oneDriveFollowsPaginationButNeverSendsCredentialsToAnotherHost() throws Exception {
        int[] calls = {0};
        JSONObject first = new JSONObject().put("value", new JSONArray().put(new JSONObject().put("id", "one").put("name", "nest-op-test.json")))
            .put("@odata.nextLink", "https://graph.microsoft.com/v1.0/next");
        JSONObject second = new JSONObject().put("value", new JSONArray().put(new JSONObject().put("id", "two").put("name", "nest-op-test.json")));
        OneDriveApi api = new OneDriveApi("test", FAMILY, (method, address, body, type, limit) -> {
            calls[0]++;
            return bytes(calls[0] == 1 ? first : second);
        });
        assertEquals(2, api.list("drive|folder").size()); assertEquals(2, calls[0]);
        JSONObject malicious = new JSONObject().put("value", new JSONArray()).put("@odata.nextLink", "https://example.invalid/stolen");
        OneDriveApi unsafe = new OneDriveApi("test", FAMILY, (method, address, body, type, limit) ->
            bytes(malicious));
        assertThrows(IOException.class, () -> unsafe.list("drive|folder"));
    }
    @Test public void oneDriveRejectsPaginationCyclesAndMismatchedOperationFilenames() throws Exception {
        OneDriveApi cycle = new OneDriveApi("test", FAMILY, (method, address, body, type, limit) ->
            bytes(Records.put(Records.put(new JSONObject(), "value", new JSONArray()), "@odata.nextLink", address)));
        assertThrows(IOException.class, () -> cycle.list("drive|folder"));
        JSONObject operation = operation();
        OneDriveApi api = new OneDriveApi("test", FAMILY, (method, address, body, type, limit) -> bytes(operation));
        JSONObject file = new JSONObject().put("id", "file").put("parentReference", new JSONObject().put("driveId", "drive"))
            .put("name", "nest-op-" + operation.getString("opId") + ".json");
        assertEquals(operation.toString(), api.download(file).toString());
        file.put("name", "nest-op-wrong.json");
        assertThrows(IOException.class, () -> api.download(file));
        file.put("size", Records.MAX_OPERATION_BYTES + 1);
        assertThrows(IOException.class, () -> api.download(file));
    }
    @Test public void localMessagesAreBoundedAndDetectTruncation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        LocalProtocol.write(output, "hello");
        assertEquals("hello", LocalProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        for (int size : new int[]{-1, Records.MAX_OPERATION_BYTES + 1, 5}) {
            bytes.reset(); output.writeInt(size);
            assertThrows(IOException.class, () -> LocalProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        }
    }
    @Test public void oneDriveSpecificPeopleLinksRequireIndividualGrantees() throws Exception {
        JSONObject grant = new JSONObject().put("roles", new JSONArray().put("write"))
            .put("link", new JSONObject().put("scope", "users"))
            .put("grantedToIdentitiesV2", new JSONArray().put(new JSONObject().put("user", new JSONObject().put("id", "partner"))));
        OneDriveApi.validatePermissions(new JSONArray().put(grant));
        grant.put("grantedToIdentitiesV2", new JSONArray());
        assertThrows(IOException.class, () -> OneDriveApi.validatePermissions(new JSONArray().put(grant)));
        grant.put("grantedToIdentitiesV2", new JSONArray().put(new JSONObject().put("group", new JSONObject().put("id", "group"))));
        assertThrows(IOException.class, () -> OneDriveApi.validatePermissions(new JSONArray().put(grant)));
    }
    @Test public void localInventoryAndDeltaOnlySendMissingOperations() throws Exception {
        JSONObject first = operation(), second = operation();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LocalProtocol.inventory(new DataOutputStream(bytes), List.of(first, second));
        assertEquals(Set.of(first.getString("opId"), second.getString("opId")),
            LocalProtocol.inventory(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        bytes.reset();
        assertEquals(1, LocalProtocol.send(new DataOutputStream(bytes), List.of(first, second), Set.of(first.getString("opId"))));
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(1, input.readInt()); assertEquals(second.toString(), LocalProtocol.read(input)); assertEquals(-1, input.read());
    }
    @Test public void localInventoryRejectsDuplicatesAndExcessiveCounts() throws Exception {
        String id = UUID.randomUUID().toString();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(2); LocalProtocol.write(output, id); LocalProtocol.write(output, id);
        assertThrows(IOException.class, () -> LocalProtocol.inventory(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        bytes.reset(); output.writeInt(LocalProtocol.MAX_OPERATIONS + 1);
        assertThrows(IOException.class, () -> LocalProtocol.inventory(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
    }
    @Test public void pairingCodesRejectExpiredPublicAndMalformedEndpoints() throws Exception {
        JSONObject code = new JSONObject().put("version", 1).put("family", FAMILY).put("host", "192.168.1.10").put("port", 12345)
            .put("pin", "A".repeat(43)).put("secret", "B".repeat(43)).put("expires", System.currentTimeMillis() + 300_000);
        assertEquals(FAMILY, LocalSync.parseInvitation(code.toString()).getString("family"));
        code.put("host", "8.8.8.8"); assertThrows(IOException.class, () -> LocalSync.parseInvitation(code.toString()));
        code.put("host", "192.168.1.10").put("expires", 0); assertThrows(IOException.class, () -> LocalSync.parseInvitation(code.toString()));
        code.put("expires", System.currentTimeMillis() + 300_000).put("port", 65536);
        assertThrows(IOException.class, () -> LocalSync.parseInvitation(code.toString()));
    }
}
