package app.littlenest;

import org.json.JSONObject;

/** One partner code that can carry a local Wi-Fi pairing, a OneDrive nest, or both. */
final class PartnerInvite {
    /** local is a validated local pairing code or null; nest is a OneDrive nest code or "". */
    record Parsed(String local, String nest, String localProblem) {}

    private PartnerInvite() {}

    static String create(String localCode, String nest) throws Exception {
        JSONObject code = localCode == null ? new JSONObject().put("version", 1) : new JSONObject(localCode);
        if (!nest.isEmpty()) code.put("onedrive", OneDriveApi.folderId(nest));
        return code.toString();
    }

    static Parsed parse(String text) throws Exception {
        String value = text.trim();
        if (!value.startsWith("{")) return new Parsed(null, OneDriveApi.folderId(value), "");
        if (value.length() > 4096) throw new IllegalArgumentException("Partner code is too large");
        JSONObject code = new JSONObject(value);
        String nest = code.optString("onedrive", "");
        if (!nest.isEmpty()) OneDriveApi.folderId(nest);
        if (!code.has("host")) {
            if (nest.isEmpty()) throw new IllegalArgumentException("This is not a Little Nest partner code");
            return new Parsed(null, nest, "");
        }
        try { LocalSync.parseInvitation(value); return new Parsed(value, nest, ""); }
        catch (Exception e) {
            // A shared code may outlive its five-minute Wi-Fi part; the OneDrive part stays usable.
            if (nest.isEmpty()) throw e;
            return new Parsed(null, nest, e.getMessage() == null ? "Wi-Fi pairing part is invalid" : e.getMessage());
        }
    }
}
