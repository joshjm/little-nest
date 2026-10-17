package app.littlenest;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OneDriveApi implements SyncTransport {
    private static final String ROOT = "https://graph.microsoft.com/v1.0";
    interface Transport {
        byte[] send(String method, String address, byte[] body, String contentType, int limit) throws IOException;
    }
    private final String token;
    private final Transport transport;
    private final String family;

    public OneDriveApi(String token, String family) { this(token, family, null); }
    OneDriveApi(String token, String family, Transport transport) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Microsoft sign-in is required");
        this.token = token; this.family = family; this.transport = transport;
    }

    static String reference(JSONObject item) throws Exception {
        return folderId(item.getJSONObject("parentReference").getString("driveId") + "|" + item.getString("id"));
    }
    public static String folderId(String value) {
        if (!value.matches("[A-Za-z0-9!_-]{1,200}\\|[A-Za-z0-9!_-]{1,200}"))
            throw new IllegalArgumentException("Paste the OneDrive nest code copied by your partner");
        return value;
    }
    private static String path(String folder) {
        String[] parts = folderId(folder).split("\\|");
        return "/drives/" + parts[0] + "/items/" + parts[1];
    }

    public String createFolder() throws Exception {
        JSONObject item = json("POST", "/me/drive/root/children", new JSONObject()
            .put("name", "Little Nest " + family).put("folder", new JSONObject()).put("@microsoft.graph.conflictBehavior", "fail"));
        String folder = reference(item);
        request("PUT", ROOT + path(folder) + ":/nest-family.json:/content",
            new JSONObject().put("schema", Records.SCHEMA).put("family", family).toString().getBytes(StandardCharsets.UTF_8), 1_000_000);
        return folder;
    }

    public void invite(String folder, String email) throws Exception {
        validateFolder(folder);
        if (!json("GET", "/me/drive?$select=id", null).getString("id").equals(folder.split("\\|")[0]))
            throw new IOException("Only the OneDrive nest owner can invite a partner");
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException("Enter your partner's Microsoft account email");
        if (members(permissions(folder)).size() >= 2) throw new IllegalArgumentException("This nest already has two accounts. Remove the old partner in OneDrive first.");
        json("POST", path(folder) + "/invite", new JSONObject().put("requireSignIn", true).put("sendInvitation", true)
            .put("roles", new JSONArray().put("write"))
            .put("recipients", new JSONArray().put(new JSONObject().put("email", email))));
        validateFolder(folder);
    }

    private JSONArray permissions(String folder) throws Exception {
        JSONObject result = json("GET", path(folder) + "/permissions", null);
        if (result.has("@odata.nextLink")) throw new IOException("Too many OneDrive permissions");
        return result.getJSONArray("value");
    }
    static Set<String> members(JSONArray permissions) throws Exception {
        Set<String> members = new HashSet<>();
        int owners = 0;
        for (int i = 0; i < permissions.length(); i++) {
            JSONObject permission = permissions.getJSONObject(i);
            JSONArray roles = permission.getJSONArray("roles");
            boolean owner = false, writer = false;
            for (int r = 0; r < roles.length(); r++) {
                owner |= roles.getString(r).equals("owner");
                writer |= roles.getString(r).equals("write");
            }
            if (!owner && !writer) throw new IOException("Only the owner and one editor may access this nest");
            JSONObject link = permission.optJSONObject("link");
            if ((link != null && !link.optString("scope").equals("users")) || (permission.has("inheritedFrom") && !owner))
                throw new IOException("Use a private OneDrive folder without public links or inherited editor access");
            JSONObject identity = permission.optJSONObject("grantedToV2");
            if (identity == null) identity = permission.optJSONObject("grantedTo");
            Set<String> grants = new HashSet<>();
            if (identity != null) grants.add(member(identity));
            JSONArray identities = permission.optJSONArray("grantedToIdentitiesV2");
            if (identities == null) identities = permission.optJSONArray("grantedToIdentities");
            if (identities != null) for (int j = 0; j < identities.length(); j++) grants.add(member(identities.getJSONObject(j)));
            if (grants.isEmpty() && link == null && permission.optJSONObject("invitation") != null) {
                String email = permission.getJSONObject("invitation").optString("email").toLowerCase(java.util.Locale.ROOT);
                if (!email.isBlank()) grants.add("email:" + email);
            }
            if (grants.isEmpty()) throw new IOException("Cannot verify an individual OneDrive folder member");
            members.addAll(grants);
            if (owner) owners += grants.size();
        }
        // Graph only returns the caller's own grants to a non-owner.
        if (owners > 1 || members.isEmpty() || members.size() > 2)
            throw new IOException("The nest permissions must describe at most two individual accounts");
        return members;
    }
    private static String member(JSONObject identity) throws Exception {
        JSONObject user = identity.optJSONObject("user");
        if (user == null || user.optString("id").isBlank() || identity.has("group") || identity.has("siteGroup"))
            throw new IOException("Only identifiable individual Microsoft accounts may access this nest");
        return "user:" + user.getString("id");
    }
    public static void validatePermissions(JSONArray permissions) throws Exception { members(permissions); }

    public String folderFamily(String folder) throws Exception {
        JSONObject item = json("GET", path(folder), null);
        if (!item.has("folder") || item.has("deleted")) throw new IOException("This OneDrive folder is unavailable");
        validatePermissions(permissions(folder));
        JSONObject marker = new JSONObject(new String(request("GET", ROOT + path(folder) + ":/nest-family.json:/content", null, 4096), StandardCharsets.UTF_8));
        if (marker.getInt("schema") != Records.SCHEMA) throw new IOException("Unsupported nest format");
        String identity = marker.getString("family");
        if (!java.util.UUID.fromString(identity).toString().equals(identity)) throw new IOException("Invalid nest identity");
        return identity;
    }
    @Override public void validateFolder(String folder) throws Exception {
        if (!folderFamily(folder).equals(family)) throw new IOException("This OneDrive folder belongs to a different nest");
    }

    @Override public List<JSONObject> list(String folder) throws Exception {
        List<JSONObject> files = new ArrayList<>();
        String next = ROOT + path(folder) + "/children?$select=id,name,size,parentReference&$top=200";
        Set<String> pages = new HashSet<>();
        while (!next.isEmpty()) {
            if (!next.startsWith(ROOT + "/") || !pages.add(next)) throw new IOException("Invalid OneDrive pagination");
            JSONObject result = new JSONObject(new String(request("GET", next, null, 10_000_000), StandardCharsets.UTF_8));
            JSONArray entries = result.getJSONArray("value");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject file = entries.getJSONObject(i);
                if (file.optString("name").startsWith("nest-op-")) files.add(file);
            }
            next = result.optString("@odata.nextLink");
        }
        return files;
    }
    @Override public JSONObject download(JSONObject file) throws Exception {
        if (file.optLong("size") > Records.MAX_OPERATION_BYTES) throw new IOException("Shared record exceeds 2 MB");
        JSONObject operation = new JSONObject(new String(request("GET", ROOT + path(reference(file)) + "/content", null, Records.MAX_OPERATION_BYTES), StandardCharsets.UTF_8));
        Records.validate(operation);
        if (!file.getString("name").equals("nest-op-" + operation.getString("opId") + ".json"))
            throw new IOException("Shared record identity does not match its filename");
        return operation;
    }
    @Override public String upload(String folder, JSONObject operation) throws Exception {
        Records.validate(operation);
        JSONObject result = new JSONObject(new String(request("PUT", ROOT + path(folder) + ":/nest-op-"
            + operation.getString("opId") + ".json:/content", operation.toString().getBytes(StandardCharsets.UTF_8), 1_000_000), StandardCharsets.UTF_8));
        return result.getString("id");
    }
    private JSONObject json(String method, String path, JSONObject body) throws Exception {
        return new JSONObject(new String(request(method, ROOT + path,
            body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), 1_000_000), StandardCharsets.UTF_8));
    }
    private byte[] request(String method, String address, byte[] body, int limit) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Sync stopped; pending changes are retained");
        if (transport != null) return transport.send(method, address, body, "application/json", limit);
        boolean bearer = true;
        for (int redirects = 0; redirects < 5; redirects++) {
            URI uri = URI.create(address);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IOException("OneDrive returned an unsafe address");
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            try {
                connection.setRequestMethod(method); connection.setConnectTimeout(20_000); connection.setReadTimeout(30_000);
                connection.setInstanceFollowRedirects(false);
                if (bearer) {
                    if (!address.startsWith(ROOT + "/")) throw new IOException("Refusing to send Microsoft credentials to another host");
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                }
                if (body != null) {
                    connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                    if (method.equals("PUT")) connection.setRequestProperty("If-None-Match", "*");
                    connection.setFixedLengthStreamingMode(body.length);
                    try (var output = connection.getOutputStream()) { output.write(body); }
                }
                int status = connection.getResponseCode();
                if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308) && method.equals("GET")) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("OneDrive download has no redirect address");
                    address = uri.resolve(location).toString(); bearer = false; continue;
                }
                if (status < 200 || status >= 300) throw new IOException(
                    status == 401 ? "Reconnect OneDrive in Family to resume sync."
                    : status == 403 ? "OneDrive denied access. Check the invitation, editor permissions and storage quota."
                    : status == 404 ? "OneDrive nest or record not found. Check the nest code and accept the invitation."
                    : "OneDrive request failed (HTTP " + status + "). Retry sync; local changes are retained.");
                try (var input = connection.getInputStream()) { return SyncFiles.readBounded(input, limit); }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many OneDrive download redirects");
    }
}
