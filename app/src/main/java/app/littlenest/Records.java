package app.littlenest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Records {
    public static final int SCHEMA = 1;
    public static final int MAX_OPERATION_BYTES = 2_000_000;

    public static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); }
        catch (JSONException e) { throw new IllegalArgumentException("Invalid record", e); }
    }

    public static JSONObject put(JSONObject object, String key, Object value) {
        try { object.put(key, value); return object; }
        catch (JSONException e) { throw new IllegalArgumentException("Invalid " + key, e); }
    }

    public static JSONObject operation(String entityId, long clock, String actor, JSONObject data, boolean deleted) {
        JSONObject op = new JSONObject();
        put(op, "schema", SCHEMA);
        put(op, "opId", UUID.randomUUID().toString());
        put(op, "entityId", entityId);
        put(op, "clock", clock);
        put(op, "actor", actor);
        put(op, "deleted", deleted);
        put(op, "data", copy(data));
        validate(op);
        return op;
    }

    public static void validate(JSONObject op) {
        if (op.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_OPERATION_BYTES)
            throw new IllegalArgumentException("A record is too large (maximum 2 MB)");
        if (!integer(op.opt("schema")) || op.optInt("schema", -1) != SCHEMA) throw new IllegalArgumentException("Unsupported data version");
        uuid(op.optString("opId"));
        uuid(op.optString("entityId"));
        uuid(op.optString("actor"));
        if (!integer(op.opt("clock")) || op.optLong("clock") < 1 || op.optLong("clock") > Long.MAX_VALUE - 1)
            throw new IllegalArgumentException("Invalid revision clock");
        if (!(op.opt("deleted") instanceof Boolean)) throw new IllegalArgumentException("Missing deletion state");
        JSONObject data = op.optJSONObject("data");
        if (data == null) throw new IllegalArgumentException("Missing record data");
        String entity = data.optString("entity");
        if (!entity.equals("profile") && !entity.equals("event")) throw new IllegalArgumentException("Unknown record entity");
        if (entity.equals("profile")) {
            if (data.optString("name").isBlank()) throw new IllegalArgumentException("Profile name is required");
            if (!List.of("Baby", "Parent").contains(data.optString("role"))) throw new IllegalArgumentException("Invalid profile role");
            for (String date : List.of("dob", "adjustedDob")) if (!data.optString(date).isBlank()) {
                try { java.time.LocalDate.parse(data.optString(date)); }
                catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Invalid profile date"); }
            }
            if (!List.of("", "Not recorded", "Female", "Male", "Other").contains(data.optString("sex")))
                throw new IllegalArgumentException("Invalid profile sex");
        } else {
            uuid(data.optString("profileId"));
            Catalog.Kind kind = Catalog.get(data.optString("kind"));
            long start = time(data, "start", true), end = time(data, "end", true);
            if (start < 0 || end < start) throw new IllegalArgumentException("End must be at or after start");
            if (data.optBoolean("running") && !kind.timer()) throw new IllegalArgumentException("Activity cannot use a timer");
            if (!List.of("Left", "Right", "Both").contains(data.optString("side", "Left")))
                throw new IllegalArgumentException("Invalid timer side");
            for (Catalog.Field field : kind.fields()) {
                String value = data.optString(field.key());
                if (data.has(field.key()) && !(data.opt(field.key()) instanceof String))
                    throw new IllegalArgumentException(field.label() + " must be text");
                if (!value.isBlank() && !field.options().isEmpty()) {
                    List<String> choices = List.of(field.options().split("\\|"));
                    for (String selected : field.multiple() ? value.split("\\|", -1) : new String[]{value})
                        if (!choices.contains(selected)) throw new IllegalArgumentException("Invalid " + field.label());
                }
                if (field.numeric() && !value.isBlank()) {
                    double number;
                    try { number = Double.parseDouble(value); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException(field.label() + " must be a number"); }
                    if (!Double.isFinite(number) || number < 0) throw new IllegalArgumentException(field.label() + " must be nonnegative");
                }
            }
            for (String field : List.of("leftMs", "rightMs", "elapsedMs", "segmentStart", "remindAt")) time(data, field, false);
            if (data.has("repeatMinutes") && (!integer(data.opt("repeatMinutes")) || data.optLong("repeatMinutes") < 0 || data.optLong("repeatMinutes") > 525600))
                throw new IllegalArgumentException("Invalid reminder repeat interval");
            for (String field : List.of("running", "timerPaused", "importIncomplete"))
                if (data.has(field) && !(data.opt(field) instanceof Boolean)) throw new IllegalArgumentException("Invalid timer state");
            if (data.has("segments")) {
                JSONArray segments = data.optJSONArray("segments");
                if (segments == null) throw new IllegalArgumentException("Invalid timer segments");
                for (int i = 0; i < segments.length(); i++) {
                    JSONArray interval = segments.optJSONArray(i);
                    if (interval == null || interval.length() != 2 || !integer(interval.opt(0)) || !integer(interval.opt(1))
                        || interval.optLong(0) < start || interval.optLong(1) < interval.optLong(0) || interval.optLong(1) > end)
                        throw new IllegalArgumentException("Invalid timer interval");
                }
            }
            String photo = data.optString("photo");
            if (!photo.isEmpty()) {
                try {
                    if (java.util.Base64.getDecoder().decode(photo).length > 750_000) throw new IllegalArgumentException("Photo exceeds 750 KB");
                } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid or oversized photo"); }
            }
        }
    }

    private static boolean integer(Object value) { return value instanceof Integer || value instanceof Long; }
    private static long time(JSONObject data, String field, boolean required) {
        if (!required && !data.has(field)) return 0;
        if (!integer(data.opt(field)) || data.optLong(field) < 0 || data.optLong(field) > 253402300799999L)
            throw new IllegalArgumentException("Invalid " + field);
        return data.optLong(field);
    }

    private static void uuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid record identifier", e); }
    }

    public static int compare(JSONObject first, JSONObject second) {
        int clock = Long.compare(first.optLong("clock"), second.optLong("clock"));
        return clock != 0 ? clock : first.optString("opId").compareTo(second.optString("opId"));
    }

    public static long duration(JSONObject event, long now) {
        long saved = event.has("elapsedMs") ? event.optLong("elapsedMs")
            : Math.max(0, event.optLong("end") - event.optLong("start"));
        return saved + (event.optBoolean("running") ? Math.max(0, now - event.optLong("segmentStart", event.optLong("start"))) : 0);
    }

    public static JSONObject settleTimer(JSONObject original, long now) {
        now = Math.max(now, Math.max(original.optLong("end"), original.optLong("segmentStart")));
        JSONObject event = copy(original);
        long segment = original.optBoolean("running") ? Math.max(0, now - original.optLong("segmentStart", original.optLong("start"))) : 0;
        put(event, "elapsedMs", duration(original, now));
        if (original.optBoolean("running") && segment > 0) {
            JSONArray segments = event.optJSONArray("segments");
            if (segments == null) segments = new JSONArray();
            JSONArray interval = new JSONArray();
            interval.put(original.optLong("segmentStart", original.optLong("start"))); interval.put(now);
            segments.put(interval); put(event, "segments", segments);
        }
        if (original.optString("kind").equals("breast") || original.optString("kind").equals("pump")) {
            String side = original.optString("side", "Left");
            if (side.equals("Left") || side.equals("Both")) put(event, "leftMs", original.optLong("leftMs") + segment);
            if (side.equals("Right") || side.equals("Both")) put(event, "rightMs", original.optLong("rightMs") + segment);
        }
        put(event, "segmentStart", now);
        put(event, "end", Math.max(now, original.optLong("start")));
        return event;
    }

    public static long overlap(long start, long end, long rangeStart, long rangeEnd) {
        return Math.max(0, Math.min(end, rangeEnd) - Math.max(start, rangeStart));
    }

    public static String csvCell(String input) {
        String safe = input;
        String trimmed = safe.stripLeading();
        if ((!trimmed.isEmpty() && "=+@-".indexOf(trimmed.charAt(0)) >= 0)
            || safe.startsWith("\t") || safe.startsWith("\r")) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public static List<JSONObject> parseBackup(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!integer(root.opt("schema")) || root.optInt("schema") != SCHEMA) throw new IllegalArgumentException("Unsupported backup version");
        JSONArray operations = root.getJSONArray("operations");
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < operations.length(); i++) {
            JSONObject op = operations.getJSONObject(i);
            validate(op);
            result.add(op);
        }
        return result;
    }

    private Records() {}
}
