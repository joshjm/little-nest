package app.littlenest;

import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public final class Insights {
    public record Day(LocalDate date, int feeds, double milkMl, int wet, int dirty, long sleepMs) {}

    public static Day day(List<JSONObject> events, LocalDate day, ZoneId zone, long now) {
        long from = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        int feeds = 0, wet = 0, dirty = 0;
        double milk = 0;
        long sleep = 0;
        for (JSONObject event : events) {
            long start = event.optLong("start");
            String kind = event.optString("kind");
            if (kind.equals("sleep") || kind.equals("parentSleep")) {
                for (long[] interval : sleepIntervals(event, now)) sleep += Records.overlap(interval[0], interval[1], from, to);
            }
            if (start < from || start >= to) continue;
            if (kind.equals("bottle") || kind.equals("breast") || kind.equals("solids")) feeds++;
            if (kind.equals("bottle")) milk += bottleMl(event);
            if (kind.equals("diaper")) {
                String diaper = event.optString("diaper");
                if (diaper.equals("Wet") || diaper.equals("Mixed")) wet++;
                if (diaper.equals("Dirty") || diaper.equals("Mixed")) dirty++;
            }
        }
        return new Day(day, feeds, milk, wet, dirty, sleep);
    }

    public static List<long[]> sleepIntervals(JSONObject event, long now) {
        List<long[]> result = new ArrayList<>();
        if (event.optBoolean("importIncomplete")) return result;
        var segments = event.optJSONArray("segments");
        if (segments != null) {
            for (int i = 0; i < segments.length(); i++) {
                var interval = segments.optJSONArray(i);
                if (interval != null) result.add(new long[]{interval.optLong(0), interval.optLong(1)});
            }

            if (event.optBoolean("running")) result.add(new long[]{event.optLong("segmentStart", event.optLong("start")), now});
        } else result.add(new long[]{event.optLong("start"), event.optBoolean("running") ? now : event.optLong("end")});
        return result;
    }

    public static double bottleMl(JSONObject event) {
        return !event.optString("amount").isBlank() ? volumeMl(event, "amount", "unit", "ml")
            : volumeMl(event, "breastAmount", "breastUnit", "ml") + volumeMl(event, "formulaAmount", "formulaUnit", "ml");
    }
    public static double pumpMl(JSONObject event) {
        return !event.optString("totalAmount").isBlank() ? volumeMl(event, "totalAmount", "unit", "ml")
            : volumeMl(event, "leftAmount", "leftUnit", event.optString("unit", "ml"))
                + volumeMl(event, "rightAmount", "rightUnit", event.optString("unit", "ml"));
    }
    private static double volumeMl(JSONObject event, String amount, String unit, String defaultUnit) {
        return event.optDouble(amount, 0) * (event.optString(unit, defaultUnit).equals("fl oz") ? 29.5735295625 : 1);
    }

    public static LocalDate date(long timestamp) { return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate(); }
    public static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
    }
    private Insights() {}
}
