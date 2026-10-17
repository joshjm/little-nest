package app.littlenest;

import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LocalProtocol {
    static final int MAX_OPERATIONS = 200_000;
    static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Records.MAX_OPERATION_BYTES) throw new IOException("Local sync message exceeds 2 MB");
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }
    static String read(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > Records.MAX_OPERATION_BYTES) throw new IOException("Invalid local sync message size");
        byte[] bytes = new byte[size]; input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    static Set<String> inventory(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_OPERATIONS) throw new IOException("Local sync inventory is too large");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String id = read(input);
            try { if (!java.util.UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid local operation ID", e); }
            if (!ids.add(id)) throw new IOException("Duplicate local operation ID");
        }
        return ids;
    }
    static void inventory(DataOutputStream output, List<JSONObject> operations) throws IOException {
        if (operations.size() > MAX_OPERATIONS) throw new IOException("Local sync inventory is too large");
        output.writeInt(operations.size());
        for (JSONObject operation : operations) write(output, operation.optString("opId"));
        output.flush();
    }
    static int send(DataOutputStream output, List<JSONObject> operations, Set<String> known) throws IOException {
        int count = 0;
        for (JSONObject operation : operations) if (!known.contains(operation.optString("opId"))) count++;
        output.writeInt(count);
        for (JSONObject operation : operations) if (!known.contains(operation.optString("opId"))) write(output, operation.toString());
        output.flush(); return count;
    }
    static int receive(DataInputStream input, Store store) throws Exception {
        return receive(input, store, "");
    }
    static int receive(DataInputStream input, Store store, String checkpoint) throws Exception {
        int count = input.readInt();
        if (count < 0 || count > MAX_OPERATIONS) throw new IOException("Too many local operations");
        for (int i = 0; i < count; i++) {
            JSONObject operation = new JSONObject(read(input));
            Records.validate(operation); store.merge(List.of(operation));
            if (!checkpoint.isEmpty()) store.mark("uploaded", checkpoint, operation.getString("opId"));
        }
        return count;
    }
    private LocalProtocol() {}
}
