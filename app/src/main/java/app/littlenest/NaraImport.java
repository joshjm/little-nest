package app.littlenest;

import org.json.JSONObject;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NaraImport {
    public record Entry(String id, JSONObject data) {}
    public record Profile(String sourceKey, String id, JSONObject data) {}
    public record Preview(List<Profile> profiles, List<Entry> events, Map<String, Integer> types,
                          List<String> warnings, int incomplete) {
        public List<Entry> entries(Map<String, String> targets) {
            return entries(targets, false, System.currentTimeMillis());
        }
        public List<Entry> entries(Map<String, String> targets, boolean continueSleep, long now) {
            List<Entry> result = new ArrayList<>();
            Map<String, String> ids = new LinkedHashMap<>();
            for (Profile profile : profiles) {
                String target = targets.getOrDefault(profile.id(), profile.id());
                ids.put(profile.id(), target);
                result.add(new Entry(target, Records.copy(profile.data())));
            }
            for (Entry entry : events) {
                JSONObject data = Records.copy(entry.data());
                Records.put(data, "profileId", ids.get(data.optString("profileId")));
                if (continueSleep && data.optString("kind").equals("sleep") && data.optBoolean("importIncomplete")) {
                    if (data.optLong("start") > now) throw new IllegalArgumentException("An ongoing sleep starts in the future. Check the phone clock or import it for review instead.");
                    Records.put(data, "running", true); Records.put(data, "segmentStart", data.optLong("start"));
                    Records.put(data, "elapsedMs", 0); Records.put(data, "importIncomplete", false);
                }
                result.add(new Entry(entry.id(), data));
            }
            return result;
        }
        public String summary() {
            StringBuilder text = new StringBuilder(events.size() + " activities, " + profiles.size() + " profiles\n\n");
            types.forEach((type, count) -> text.append(type).append(": ").append(count).append('\n'));
            if (incomplete > 0) text.append("\n").append(incomplete).append(" unfinished / incomplete entries. Ongoing sleep can continue with the option below; other incomplete entries are marked for review.\n");
            for (String warning : warnings) text.append("\n").append(warning);
            return text.toString();
        }
    }

    private static final String ACTOR = id("nara-csv-importer-v1");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static Preview parse(Reader reader) throws IOException {
        try (CsvReader csv = new CsvReader(reader)) {
            List<String> header = csv.next();
            if (header == null) throw new IOException("CSV is empty");
            if (header.stream().distinct().count() != header.size()) throw new IOException("CSV has duplicate column names");
            for (String required : List.of("Type", "Profile Name", "Start Date/time (Epoch)", "_familyKey", "_profileKey", "_activityKey"))
                if (!header.contains(required)) throw new IOException("Not a supported Nara export: missing " + required);
            Map<String, JSONObject> profileData = new LinkedHashMap<>();
            Map<String, String> profileIds = new LinkedHashMap<>();
            Map<String, Entry> events = new LinkedHashMap<>();
            Map<String, Integer> types = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            int record = 1, incomplete = 0, unassignedPumps = 0;
            List<String> cells;
            while ((cells = csv.next()) != null) {
                record++;
                if (cells.stream().allMatch(String::isEmpty)) continue;
                if (record > 100_001) throw new IOException("CSV exceeds 100,000 records");
                if (cells.size() == header.size() - 1 && cells.get(0).equals("Profile") && header.get(header.size() - 1).equals("_activityKey")) {
                    cells.add("");
                } else if (cells.size() != header.size()) throw new IOException("CSV record " + record + ": expected " + header.size() + " columns, found " + cells.size());
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) row.put(header.get(i), cells.get(i));
                try {
                    String type = required(row, "Type");
                    boolean unassignedPump = type.equals("Pump") && value(row, "_profileKey").isEmpty() && value(row, "Profile Name").isEmpty();
                    if (unassignedPump) unassignedPumps++;
                    String sourceKey = required(row, "_familyKey") + "/" + (unassignedPump ? "unassigned-pump" : required(row, "_profileKey"));
                    String profileId = profileIds.computeIfAbsent(sourceKey, key -> id("nara-profile:" + key));
                    JSONObject profile = profileData.get(sourceKey);
                    if (profile == null) {
                        profile = new JSONObject();
                        put(profile, "entity", "profile"); put(profile, "name", unassignedPump ? "Pumping" : required(row, "Profile Name"));
                        put(profile, "role", unassignedPump ? "Parent" : "Baby");
                        if (unassignedPump) put(profile, "naraUnassigned", true);
                        put(profile, "naraProfileKey", sourceKey); profileData.put(sourceKey, profile);
                    } else if (!unassignedPump && !profile.optString("name").equals(required(row, "Profile Name"))) {
                        throw new IllegalArgumentException("Profile name changes within the same CSV; export one consistent snapshot");
                    }
                    if (type.equals("Profile")) {
                        String role = value(row, "[Profile] Type");
                        if (!List.of("CHILD", "PARENT", "MOM", "ADULT").contains(role)) throw new IllegalArgumentException("Unsupported profile type: " + role);
                        put(profile, "role", role.equals("CHILD") ? "Baby" : "Parent");
                        for (String[] field : new String[][]{{"[Profile] Birth Date","dob"},{"[Profile] Birth Date (Adjusted)","adjustedDob"}}) {
                            String date = value(row, field[0]);
                            if (!date.isEmpty()) { LocalDate.parse(date); put(profile, field[1], date); }
                        }
                        String sex = value(row, "[Profile] Sex");
                        if (!sex.isEmpty()) put(profile, "sex", switch (sex) {
                            case "FEMALE" -> "Female"; case "MALE" -> "Male";
                            case "OTHER" -> "Other"; case "UNKNOWN", "UNSPECIFIED" -> "Not recorded";
                            default -> throw new IllegalArgumentException("Unsupported profile sex: " + sex);
                        });
                        put(profile, "naraSource", source(row)); continue;
                    }
                    String activityKey = required(row, "_activityKey");
                    JSONObject event = event(row, type, profileId);
                    String entityId = id("nara-event:" + sourceKey + "/" + activityKey);
                    Entry prior = events.putIfAbsent(entityId, new Entry(entityId, event));
                    if (prior != null) {
                        if (!canonical(prior.data()).equals(canonical(event))) throw new IllegalArgumentException("Conflicting duplicate activity key");
                        warnings.add("Duplicate CSV record " + record + " was counted once."); continue;
                    }
                    if (event.optBoolean("importIncomplete")) incomplete++;
                    types.merge(type, 1, Integer::sum);
                    operation(new Entry(entityId, event));
                } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException e) {
                    throw new IOException("CSV record " + record + ": " + e.getMessage(), e);
                }
            }
            if (events.isEmpty() && profileData.isEmpty()) throw new IOException("CSV contains no profiles or activities");
            if (unassignedPumps > 0) warnings.add(unassignedPumps + " pumping entries have no profile in Nara. They will use a separate Pumping profile unless you choose a destination below.");
            List<Profile> profiles = new ArrayList<>();
            for (Map.Entry<String, JSONObject> profile : profileData.entrySet()) {
                String profileId = profileIds.get(profile.getKey());
                operation(new Entry(profileId, profile.getValue()));
                profiles.add(new Profile(profile.getKey(), profileId, profile.getValue()));
            }
            return new Preview(profiles, new ArrayList<>(events.values()), types, warnings, incomplete);
        }
    }

    private static JSONObject event(Map<String, String> row, String type, String profileId) {
        String kind = switch (type) {
            case "Sleep" -> "sleep"; case "Breastfeed" -> "breast"; case "Diaper" -> "diaper";
            case "Bottle Feed" -> "bottle"; case "Pump" -> "pump"; case "Growth" -> "growth";
            case "Baby First" -> "milestone";
            default -> throw new IllegalArgumentException("Unsupported activity type '" + type + "'. Nothing was imported.");
        };
        JSONObject event = new JSONObject();
        put(event, "entity", "event"); put(event, "kind", kind); put(event, "profileId", profileId);
        long start = timestamp(row, "Start Date/time (Epoch)", "Start Date/time");
        put(event, "start", start); put(event, "end", start); put(event, "running", false);
        put(event, "notes", value(row, "Note"));
        put(event, "caregiver", value(row, "Created By Caregiver"));
        put(event, "updatedBy", value(row, "Last Updated By Caregiver"));
        put(event, "sourceTimeZone", required(row, "Time Zone")); ZoneId.of(event.optString("sourceTimeZone"));
        put(event, "naraSource", source(row));
        switch (kind) {
            case "sleep", "pump" -> {
                String prefix = kind.equals("sleep") ? "[Sleep]" : "[Pump]";
                String endValue = value(row, prefix + " End Date/time (Epoch)");
                String endLocal = value(row, prefix + " End Date/time");
                String duration = value(row, prefix + " Duration (Seconds)");
                if (endValue.isEmpty() && endLocal.isEmpty() && duration.isEmpty()) put(event, "importIncomplete", true);
                else {
                    long elapsed = duration.isEmpty() ? -1 : milliseconds(duration);
                    long end = endValue.isEmpty() && endLocal.isEmpty() ? Math.addExact(start, elapsed)
                        : timestamp(row, prefix + " End Date/time (Epoch)", prefix + " End Date/time");
                    if (end < start) throw new IllegalArgumentException("Activity end precedes start");
                    put(event, "end", end); put(event, "elapsedMs", elapsed < 0 ? end - start : elapsed);
                }
                if (kind.equals("sleep")) put(event, "sleepType", "Not recorded");
                else {
                    put(event, "side", "Both");
                    quantity(row, event, "[Pump] Left Volume", "[Pump] Left Volume Unit", "leftAmount", "leftUnit", false);
                    quantity(row, event, "[Pump] Right Volume", "[Pump] Right Volume Unit", "rightAmount", "rightUnit", false);
                    quantity(row, event, "[Pump] Total Volume", "[Pump] Total Volume Unit", "totalAmount", "unit", false);
                    if (!event.has("unit")) put(event, "unit", event.optString("leftUnit", event.optString("rightUnit", "ml")));
                }
            }
            case "breast" -> {
                String begin = value(row, "[Breastfeed] Begin Side"), end = value(row, "[Breastfeed] End Side");
                if (!begin.isEmpty()) put(event, "beginSide", side(begin));
                if (!end.isEmpty()) put(event, "side", side(end.replace(".nonTimer", "")));
                put(event, "entryMethod", end.endsWith(".nonTimer") ? "Manual" : end.isEmpty() ? "Not recorded" : "Timer");
                String left = value(row, "[Breastfeed] Left Duration (Seconds)"), right = value(row, "[Breastfeed] Right Duration (Seconds)");
                long leftMs = left.isEmpty() ? 0 : milliseconds(left), rightMs = right.isEmpty() ? 0 : milliseconds(right);
                put(event, "leftMs", leftMs); put(event, "rightMs", rightMs);
                put(event, "elapsedMs", Math.addExact(leftMs, rightMs)); put(event, "end", Math.addExact(start, Math.addExact(leftMs, rightMs)));
                if (left.isEmpty() && right.isEmpty()) put(event, "importIncomplete", true);
            }
            case "bottle" -> {
                String milk = value(row, "[Bottle Feed] Type");
                put(event, "milk", switch (milk) {
                    case "Breast Milk" -> "Breast milk"; case "Formula" -> "Formula";
                    case "Breast Milk Formula" -> "Mixed"; case "" -> "Other";
                    default -> throw new IllegalArgumentException("Unknown bottle type: " + milk);
                });
                put(event, "formulaName", value(row, "[Bottle Feed] Formula Name"));
                quantity(row, event, "[Bottle Feed] Breast Milk Volume", "[Bottle Feed] Breast Milk Volume Unit", "breastAmount", "breastUnit", false);
                quantity(row, event, "[Bottle Feed] Formula Volume", "[Bottle Feed] Formula Volume Unit", "formulaAmount", "formulaUnit", false);
                quantity(row, event, "[Bottle Feed] Volume", "[Bottle Feed] Volume Unit", "amount", "unit", false);
            }
            case "diaper" -> {
                put(event, "diaper", switch (required(row, "[Diaper] Type")) {
                    case "Wet" -> "Wet"; case "Dirty" -> "Dirty"; case "Dirty Wet" -> "Mixed"; case "Dry" -> "Dry";
                    default -> throw new IllegalArgumentException("Unknown diaper type");
                });
                String detail = value(row, "[Diaper] Detail");
                if (!detail.isEmpty()) {
                    for (String flag : detail.split(" ")) {
                        if (flag.equals("Rash")) put(event, "rash", "Yes");
                        else if (flag.equals("Blowout")) put(event, "blowout", "Yes");
                        else throw new IllegalArgumentException("Unknown diaper detail: " + flag);
                    }
                }
                if (!value(row, "[Diaper] Dirty Color").isEmpty()) put(event, "stool", tokens(value(row, "[Diaper] Dirty Color"), false));
                if (!value(row, "[Diaper] Dirty Texture").isEmpty()) put(event, "consistency", tokens(value(row, "[Diaper] Dirty Texture"), true));
            }
            case "growth" -> {
                quantity(row, event, "[Growth] Weight", "[Growth] Weight Unit", "weight", "weightUnit", true);
                quantity(row, event, "[Growth] Height", "[Growth] Height Unit", "height", "lengthUnit", true);
                quantity(row, event, "[Growth] Head Size", "[Growth] Head Size Unit", "head", "headUnit", true);
            }
            case "milestone" -> {
                String name = required(row, "[Baby First] Baby First"); put(event, "name", name);
                put(event, "milestone", name.equals("Smile") ? "First smile" : "Other");
            }
            default -> throw new IllegalArgumentException("Unsupported activity");
        }
        return event;
    }

    private static JSONObject source(Map<String, String> row) {
        JSONObject source = new JSONObject();
        row.forEach((key, value) -> { if (!value.isEmpty()) put(source, key, value); });
        return source;
    }
    private static String tokens(String text, boolean texture) {
        List<String> values = new ArrayList<>();
        for (String token : text.split(" ")) {
            String mapped = texture ? switch (token) {
                case "SOLID" -> "Solid"; case "PEBBLE" -> "Pebble"; case "RUN" -> "Runny";
                case "MUSH" -> "Mushy"; case "MUCOUS" -> "Mucous";
                default -> throw new IllegalArgumentException("Unknown stool texture: " + token);
            } : switch (token) {
                case "YELLOW" -> "Yellow"; case "GREEN" -> "Green"; case "BLACK" -> "Black";
                case "BROWN" -> "Brown"; case "RED" -> "Red"; case "PALE" -> "Pale";
                default -> throw new IllegalArgumentException("Unknown stool color: " + token);
            };
            if (!values.contains(mapped)) values.add(mapped);
        }
        return String.join("|", values);
    }
    private static String side(String value) {
        return switch (value) { case "LEFT" -> "Left"; case "RIGHT" -> "Right"; case "BOTH" -> "Both"; default -> throw new IllegalArgumentException("Unknown breast side: " + value); };
    }
    private static void quantity(Map<String, String> row, JSONObject target, String amountColumn, String unitColumn, String amountKey, String unitKey, boolean growth) {
        String amount = value(row, amountColumn), rawUnit = value(row, unitColumn);
        if (amount.isEmpty()) {
            if (!rawUnit.isEmpty()) throw new IllegalArgumentException("Unit without amount: " + unitColumn);
            return;
        }
        BigDecimal number = decimal(amount);
        String unit = growth ? switch (rawUnit) {
            case "KG" -> "kg"; case "LB", "LBS" -> "lb"; case "CM" -> "cm"; case "IN", "INCH" -> "in";
            default -> throw new IllegalArgumentException("Unknown growth unit: " + rawUnit);
        } : switch (rawUnit) {
            case "ML" -> "ml"; case "FLOZ" -> "fl oz";
            default -> throw new IllegalArgumentException("Unknown volume unit: " + rawUnit);
        };
        put(target, amountKey, number.stripTrailingZeros().toPlainString()); put(target, unitKey, unit);
    }
    private static BigDecimal decimal(String value) {
        try {
            BigDecimal number = new BigDecimal(value);
            if (number.signum() < 0 || !Double.isFinite(number.doubleValue())) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid nonnegative number: " + value); }
    }
    private static long milliseconds(String value) {
        try { return decimal(value).multiply(BigDecimal.valueOf(1000)).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Duration is too large or finer than milliseconds"); }
    }
    private static long timestamp(Map<String, String> row, String epochColumn, String localColumn) {
        String epoch = value(row, epochColumn);
        if (!epoch.isEmpty()) {
            try { return Long.parseLong(epoch); } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid epoch timestamp: " + epochColumn); }
        }
        String local = required(row, localColumn);
        return LocalDateTime.parse(local, LOCAL_TIME).atZone(ZoneId.of(required(row, "Time Zone"))).toInstant().toEpochMilli();
    }
    private static String value(Map<String, String> row, String key) { return row.getOrDefault(key, ""); }
    private static String required(Map<String, String> row, String key) {
        String value = value(row, key);
        if (value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
    private static void put(JSONObject object, String key, Object value) { Records.put(object, key, value); }
    static String id(String source) { return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString(); }
    static JSONObject operation(Entry entry) {
        JSONObject operation = Records.operation(entry.id(), 1, ACTOR, ordered(entry.data()), false);
        put(operation, "opId", id("nara-import:" + entry.id() + ":" + canonical(entry.data())));
        return operation;
    }
    private static String canonical(JSONObject data) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(sorted(data).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String sorted(JSONObject data) {
        List<String> keys = new ArrayList<>(); data.keys().forEachRemaining(keys::add); java.util.Collections.sort(keys);
        StringBuilder result = new StringBuilder("{");
        for (String key : keys) {
            Object value = data.opt(key);
            result.append(JSONObject.quote(key)).append(':').append(value instanceof JSONObject nested ? sorted(nested)
                : value instanceof String text ? JSONObject.quote(text) : String.valueOf(value)).append(',');
        }
        return result.append('}').toString();
    }
    private static JSONObject ordered(JSONObject data) {
        List<String> keys = new ArrayList<>(); data.keys().forEachRemaining(keys::add); java.util.Collections.sort(keys);
        JSONObject ordered = new JSONObject();
        for (String key : keys) {
            Object value = data.opt(key); put(ordered, key, value instanceof JSONObject nested ? ordered(nested) : value);
        }
        return ordered;
    }
    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray(), result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255; result[i * 2] = digits[value >>> 4]; result[i * 2 + 1] = digits[value & 15];
        }
        return new String(result);
    }
    private NaraImport() {}
}
