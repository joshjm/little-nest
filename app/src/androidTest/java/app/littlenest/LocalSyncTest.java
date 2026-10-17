package app.littlenest;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalSyncTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private Context isolated() {
        String prefix = "local-test-" + UUID.randomUUID() + "-";
        return new ContextWrapper(context()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue("Timed out waiting for local sync", condition.getAsBoolean());
    }
    private String invitation(LocalSync local) throws Exception {
        AtomicReference<String> code = new AtomicReference<>();
        // The listener is deliberately initialized asynchronously.
        long deadline = System.currentTimeMillis() + 10_000;
        while (code.get() == null && System.currentTimeMillis() < deadline) {
            local.invitation(code::set); Thread.sleep(100);
        }
        assertNotNull("Local listener failed to start", code.get()); return code.get();
    }
    @Test public void localTlsPairingAuthenticatesAndReconnectsWithoutPairingAgain() throws Exception {
        Context first = isolated(), second = isolated();
        LocalSync one = new LocalSync(first, () -> {}), two = new LocalSync(second, () -> {});
        try {
            one.start(); two.start();
            String code = invitation(one);
            two.join(code);
            await(() -> one.paired() && two.paired() && !two.busy());
            SharedPreferences prefs = second.getSharedPreferences("settings", Context.MODE_PRIVATE);
            assertEquals("Local transfer failed: " + prefs.getString("localError", ""), "", prefs.getString("localError", ""));
            assertTrue(prefs.getLong("localLastSync", 0) > 0);
            assertEquals(first.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("syncFamily", ""),
                prefs.getString("syncFamily", ""));
            long previous = prefs.getLong("localLastSync", 0);
            Thread.sleep(10); two.sync();
            await(() -> !two.busy() && prefs.getLong("localLastSync", 0) > previous);
            two.disconnect(); assertFalse(two.paired());
        } finally { one.close(); two.close(); }
    }
    @Test public void incorrectCertificateAndSecretDoNotPair() throws Exception {
        for (String field : List.of("pin", "secret")) {
            Context first = isolated(), second = isolated();
            LocalSync one = new LocalSync(first, () -> {}), two = new LocalSync(second, () -> {});
            try {
                one.start(); two.start();
                JSONObject code = new JSONObject(invitation(one)).put(field, "X".repeat(43));
                two.join(code.toString()); await(() -> !two.busy());
                assertFalse(one.paired()); assertFalse(two.paired());
                assertFalse(second.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("localError", "").isEmpty());
            } finally { one.close(); two.close(); }
        }
    }
    @Test public void cancelledSetupDoesNotTrapThePhoneInAnUnusedFamily() {
        Context isolated = isolated();
        String provisional = SyncFamily.ensure(isolated), partner = UUID.randomUUID().toString();
        assertNotEquals(provisional, partner);
        SyncFamily.accept(isolated, partner);
        assertEquals(partner, SyncFamily.ensure(isolated));
        assertThrows(IllegalArgumentException.class, () -> SyncFamily.accept(isolated, provisional));
    }
    @Test public void encryptedCredentialsRoundTripAndDisconnectRemovesThem() throws Exception {
        Context isolated = isolated();
        SyncSecrets.put(isolated, "credential-test", "private-test-token");
        assertEquals("private-test-token", SyncSecrets.get(isolated, "credential-test"));
        assertFalse(isolated.getSharedPreferences("sync-secrets", Context.MODE_PRIVATE).getString("credential-test", "").contains("private-test-token"));
        SyncSecrets.put(isolated, "credential-test", "");
        assertEquals("", SyncSecrets.get(isolated, "credential-test"));
    }
    @Test public void localDeltaPreservesOfflineEntriesPhotosTimersAndTombstones() throws Exception {
        String nameOne = "local-one-" + UUID.randomUUID() + ".db", nameTwo = "local-two-" + UUID.randomUUID() + ".db";
        Store one = new Store(context(), nameOne), two = new Store(context(), nameTwo);
        try {
            JSONObject profile = new JSONObject().put("entity", "profile").put("name", "Local baby").put("role", "Baby");
            String child = one.save(null, profile, false);
            transfer(one, two); transfer(two, one);
            JSONObject event = new JSONObject().put("entity", "event").put("profileId", child).put("kind", "sleep")
                .put("start", 1000).put("end", 1000).put("segmentStart", 1000).put("running", true).put("photo", "cGhvdG8=");
            String sleep = one.save(null, event, false);
            JSONObject bottle = new JSONObject().put("entity", "event").put("profileId", child).put("kind", "bottle")
                .put("start", 2000).put("end", 2000).put("notes", "Offline bottle");
            two.save(null, bottle, false);
            transfer(one, two); transfer(two, one);
            assertEquals(2, two.events(child).size()); assertEquals(one.backup(), two.backup());
            assertTrue(two.events(child).stream().anyMatch(value -> value.optBoolean("running") && value.optString("photo").equals("cGhvdG8=")));
            one.save(sleep, event, true);
            transfer(one, two); transfer(two, one);
            assertEquals(1, two.events(child).size()); assertEquals(one.backup(), two.backup());
            int count = two.operations().size(); transfer(one, two); assertEquals(count, two.operations().size());
            assertEquals(count, two.pending("unacknowledged-destination"));
        } finally {
            one.close(); two.close(); context().deleteDatabase(nameOne); context().deleteDatabase(nameTwo);
        }
    }
    private void transfer(Store from, Store to) throws Exception {
        Set<String> known = new java.util.HashSet<>();
        for (JSONObject operation : to.operations()) known.add(operation.getString("opId"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LocalProtocol.send(new DataOutputStream(bytes), from.operations(), known);
        LocalProtocol.receive(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), to);
    }
}
