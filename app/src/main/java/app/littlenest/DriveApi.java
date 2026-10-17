package app.littlenest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DriveApi {
    public static final String SCOPE = "https://www.googleapis.com/auth/drive";
    private final String token;
    interface Transport {
        byte[] send(String method, String address, byte[] body, String contentType, int maxBytes) throws IOException;
    }
    private final Transport transport;

    public DriveApi(String token) {
        this(token, null);
    }

    DriveApi(String token, Transport transport) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Google authorization is required");
        this.token = token; this.transport = transport;
    }

    public static String folderId(String value) {
        String id = value.trim();
        if (id.startsWith("https://drive.google.com/drive/folders/")) {
            id = id.substring("https://drive.google.com/drive/folders/".length()).split("[?#/]")[0];
        }
        if (!id.matches("[A-Za-z0-9_-]{10,200}")) throw new IllegalArgumentException("Paste a valid Google Drive folder ID or folder link");
        return id;
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new AssertionError("Android must support UTF-8", e); }
    }

    public String createFolder() throws IOException, JSONException {
        JSONObject meta = new JSONObject();
        meta.put("name", "Little Nest - shared family");
        meta.put("mimeType", "application/vnd.google-apps.folder");
        meta.put("appProperties", new JSONObject().put("littleNestSchema", "1"));
        return json("POST", "/files?fields=id", meta).getString("id");
    }

    public void invite(String folder, String email) throws IOException, JSONException {
        validateFolder(folder);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Enter your partner's Google account email");
        JSONArray permissions = permissions(folder);
        for (int i = 0; i < permissions.length(); i++) {
            JSONObject permission = permissions.getJSONObject(i);
            if (permission.optString("emailAddress").equalsIgnoreCase(email))
                throw new IllegalArgumentException("That account already has access");
        }
        if (permissions.length() >= 2) throw new IllegalArgumentException("This family already has two accounts. Remove the old partner in Drive first.");
        json("POST", "/files/" + folder + "/permissions?sendNotificationEmail=true&fields=id",
            new JSONObject().put("type", "user").put("role", "writer").put("emailAddress", email));
        validateFolder(folder);
    }

    public static void validatePermissions(JSONArray permissions) throws JSONException {
        if (permissions.length() < 1 || permissions.length() > 2)
            throw new IllegalArgumentException("The shared folder must contain only an owner and at most one partner");
        int owners = 0;
        for (int i = 0; i < permissions.length(); i++) {
            JSONObject permission = permissions.getJSONObject(i);
            String role = permission.optString("role");
            if (!permission.optString("type").equals("user") || (!role.equals("owner") && !role.equals("writer")))
                throw new IllegalArgumentException("Use a private folder with only its owner and one editor. Link/domain sharing is not allowed.");
            if (role.equals("owner")) owners++;
        }
        if (owners != 1) throw new IllegalArgumentException("A single folder owner is required");
    }

    private JSONArray permissions(String folder) throws IOException, JSONException {
        JSONObject response = json("GET", "/files/" + folder + "/permissions?fields=nextPageToken,permissions(id,type,role,emailAddress)&pageSize=100", null);
        if (response.has("nextPageToken")) throw new IllegalArgumentException("Too many folder permissions");
        return response.getJSONArray("permissions");
    }

    public void validateFolder(String folder) throws IOException, JSONException {
        folderId(folder);
        JSONObject meta = json("GET", "/files/" + folder + "?fields=id,mimeType,trashed,appProperties,capabilities(canAddChildren)", null);
        if (meta.optBoolean("trashed") || !meta.optString("mimeType").equals("application/vnd.google-apps.folder")
            || meta.optJSONObject("appProperties") == null
            || !meta.getJSONObject("appProperties").optString("littleNestSchema").equals("1"))
            throw new IllegalArgumentException("This is not a Little Nest family folder");
        if (!meta.getJSONObject("capabilities").optBoolean("canAddChildren"))
            throw new IllegalArgumentException("Your Google account needs editor access to this folder");
        validatePermissions(permissions(folder));
    }

    public List<JSONObject> list(String folder) throws IOException, JSONException {
        List<JSONObject> files = new ArrayList<>();
        String page = "";
        do {
            String query = "'" + folder + "' in parents and trashed = false and mimeType = 'application/json'";
            JSONObject result = json("GET", "/files?q=" + encode(query)
                + "&fields=nextPageToken,files(id,name,size)&pageSize=1000"
                + (page.isEmpty() ? "" : "&pageToken=" + encode(page)), null);
            JSONArray entries = result.getJSONArray("files");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject file = entries.getJSONObject(i);
                if (file.optString("name").startsWith("nest-op-")) files.add(file);
            }
            page = result.optString("nextPageToken");
        } while (!page.isEmpty());
        return files;
    }

    public JSONObject download(JSONObject file) throws IOException, JSONException {
        if (file.optLong("size") > Records.MAX_OPERATION_BYTES) throw new IOException("Shared record exceeds 2 MB: " + file.optString("name"));
        byte[] body = request("GET", "https://www.googleapis.com/drive/v3/files/" + file.getString("id") + "?alt=media", null, null, Records.MAX_OPERATION_BYTES);
        JSONObject operation = new JSONObject(new String(body, StandardCharsets.UTF_8));
        Records.validate(operation);
        if (!file.getString("name").equals("nest-op-" + operation.getString("opId") + ".json"))
            throw new IOException("Shared record identity does not match its filename");
        return operation;
    }

    public String upload(String folder, JSONObject operation) throws IOException, JSONException {
        String boundary = "nest_" + java.util.UUID.randomUUID().toString().replace("-", "");
        JSONObject metadata = new JSONObject().put("name", "nest-op-" + operation.getString("opId") + ".json")
            .put("parents", new JSONArray().put(folder)).put("mimeType", "application/json");
        String body = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + metadata
            + "\r\n--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + operation
            + "\r\n--" + boundary + "--\r\n";
        byte[] response = request("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            body.getBytes(StandardCharsets.UTF_8), "multipart/related; boundary=" + boundary, 1_000_000);
        return new JSONObject(new String(response, StandardCharsets.UTF_8)).getString("id");
    }

    private JSONObject json(String method, String path, JSONObject body) throws IOException, JSONException {
        byte[] response = request(method, "https://www.googleapis.com/drive/v3" + path,
            body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8", 10_000_000);
        return new JSONObject(new String(response, StandardCharsets.UTF_8));
    }

    private byte[] request(String method, String address, byte[] body, String contentType, int maxBytes) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Sync was stopped; pending data is retained");
        if (transport != null) return transport.send(method, address, body, contentType, maxBytes);
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(20_000); connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (var output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String reason = status == 401 ? "Google authorization expired. Reconnect Google Drive."
                    : status == 403 ? "Drive access denied. Check folder permissions, OAuth setup and Drive storage quota."
                    : status == 404 ? "Shared folder or record not found. Check the folder ID and account."
                    : "Google Drive request failed (HTTP " + status + "). Retry later.";
                throw new IOException(reason);
            }
            try (InputStream input = connection.getInputStream()) { return readBounded(input, maxBytes); }
        } finally { connection.disconnect(); }
    }

    public static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() > limit - count) throw new IOException("File exceeds the allowed size");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
