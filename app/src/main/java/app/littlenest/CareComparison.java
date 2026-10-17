package app.littlenest;

import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import static app.littlenest.CarePredictor.MINUTE;
import app.littlenest.CarePredictor.Session;
import app.littlenest.CarePredictor.Window;

/** Clock-time averages and observed responses to comparable short/late care. */
public final class CareComparison {
    public record Comparison(Window schedule, Window adjusted) {
        public String shift() {
            if (!schedule.available() || !adjusted.available()) return "";
            long difference = adjusted.target() - schedule.target();
            return Math.abs(difference) < MINUTE ? "Matches the normal schedule"
                : Math.round(Math.abs(difference) / (double) MINUTE) + " min "
                    + (difference < 0 ? "earlier" : "later") + " than the normal schedule";
        }
    }
    private record Pair(Session before, Session next) {}

    public static Comparison predict(List<JSONObject> events, String type, long now, ZoneId zone) {
        if (!type.equals("feed") && !type.equals("nap")) throw new IllegalArgumentException("Unknown care type: " + type);
        boolean nap = type.equals("nap");
        List<Session> raw = CarePredictor.sessions(events, type, now);
        String blocked = blocked(raw, nap);
        if (!blocked.isEmpty()) return unavailable(type, blocked);
        if (events.stream().anyMatch(e -> (nap ? e.optString("kind").equals("sleep")
            : e.optString("kind").equals("bottle") || e.optString("kind").equals("breast"))
            && e.optLong("start") <= now && e.optLong("end") > now))
            return unavailable(type, "A completed care entry has a future end time. Review its times before comparing.");
        List<Session> groups = CarePredictor.grouped(raw, (nap ? 15 : 30) * MINUTE, nap);
        if (groups.isEmpty()) return unavailable(type, "Log completed care with usable times first.");
        Session anchor = groups.get(groups.size() - 1);
        if (now - (nap ? anchor.end() : anchor.start()) > 18 * 60 * MINUTE)
            return unavailable(type, "The latest care is over 18 hours old. Log recent care first.");
        LocalDate today = date(now, zone);
        if (raw.stream().anyMatch(s -> s.incomplete() && date(s.start(), zone).equals(today)))
            return unavailable(type, "Review today's incomplete care first; daily care numbering is uncertain.");
        if (!date(nap ? anchor.end() : anchor.start(), zone).equals(today))
            return unavailable(type, "Log today's care first; comparisons do not roll yesterday's schedule forward.");
        if (!nap && !daytime(anchor.start(), zone, false))
            return unavailable(type, "Clock-schedule comparisons cover daytime milk feeds only (05:00-22:00).");
        List<Session> todaySessions = onDay(groups, today, zone, nap);
        int slot = todaySessions.size();
        List<Long> clockTimes = new ArrayList<>();
        List<Pair> pairs = new ArrayList<>();
        for (int day = 14; day >= 1; day--) {
            LocalDate date = today.minusDays(day);
            if (raw.stream().anyMatch(s -> s.incomplete() && date(s.start(), zone).equals(date))) continue;
            List<Session> daily = onDay(groups, date, zone, nap);
            if (daily.size() <= slot) continue;
            Session next = daily.get(slot);
            clockTimes.add(clock(next.start(), zone));
            int index = groups.indexOf(next);
            if (index == 0) continue;
            Session before = groups.get(index - 1);
            long interval = next.start() - (nap ? before.end() : before.start());
            if (interval < (nap ? 20 : 30) * MINUTE || interval > 8 * 60 * MINUTE
                || !date(nap ? before.end() : before.start(), zone).equals(date)) continue;
            // Do not learn across a missing/review-needed session.
            if (raw.stream().anyMatch(s -> s.incomplete() && s.start() >= before.start() && s.start() <= next.start())) continue;
            pairs.add(new Pair(before, next));
        }
        if (clockTimes.size() < 4)
            return unavailable(type, "Learning schedule: need this daytime " + (nap ? "nap" : "milk feed")
                + " number on at least 4 previous days in the last 14 days (" + clockTimes.size() + " found). Missing logs change daily numbering.");
        List<Long> recent = clockTimes.subList(Math.max(0, clockTimes.size() - 7), clockTimes.size());
        long scheduleTarget = atClock(today, mean(recent), zone);
        String cycle = type + ":" + anchor.id();
        Window schedule = window(type, cycle, scheduleTarget, recent, recent.size(),
            "Moving average of daytime " + (nap ? "nap" : "milk feed") + " #" + (slot + 1)
                + " clock times on the most recent " + recent.size() + " matching days (up to 7, within 14 days). Missing logs can misalign numbering.");
        if (pairs.size() < 4)
            return new Comparison(schedule, missing(type, "Not enough completed prior-care/next-care pairs for an adjustment (need 4)."));
        boolean quantityKnown = Double.isFinite(anchor.quantity()) && anchor.quantity() > 0;
        if (nap && !quantityKnown)
            return new Comparison(schedule, missing(type, "The latest sleep has no positive actual sleep duration. Review its timing before adjusting."));
        List<Pair> measured = pairs.stream().filter(p -> Double.isFinite(p.before.quantity()) && p.before.quantity() > 0).toList();
        if (quantityKnown && measured.size() < 4)
            return new Comparison(schedule, missing(type, "Need at least 4 comparable prior durations/recorded bottle amounts before adjusting."));
        double usualQuantity = quantityKnown ? measured.stream().mapToDouble(p -> p.before.quantity()).average().orElseThrow() : 0;
        long usualStart = mean(pairs.stream().map(p -> relativeClock(p.before.start(), date(p.next.start(), zone), zone)).toList());
        long timingDifference = relativeClock(anchor.start(), today, zone) - usualStart;
        int timing = timingBand(timingDifference);
        int size = quantityKnown ? sizeBand(anchor.quantity() / usualQuantity) : 0;
        List<Pair> matched = pairs.stream().filter(p ->
            timingBand(relativeClock(p.before.start(), date(p.next.start(), zone), zone) - usualStart) == timing
                && (!quantityKnown || Double.isFinite(p.before.quantity()) && p.before.quantity() > 0
                    && sizeBand(p.before.quantity() / usualQuantity) == size)).toList();
        String description = "Last care started " + (timing == 0 ? "near its usual time" : timing < 0 ? "earlier than usual" : "later than usual");
        if (quantityKnown) description += "; " + (nap ? "logged sleep duration" : "recorded bottle volume") + " "
            + (size == 0 ? "near usual" : size < 0 ? "shorter/smaller than usual" : "longer/larger than usual")
            + " (" + Math.round(anchor.quantity() / usualQuantity * 100) + "% of the prior-slot average)";
        else description += "; timing only: milk intake is unknown (breastfeeding minutes are not volume)";
        if (matched.size() < 4)
            return new Comparison(schedule, missing(type, description + ". Need 4 similar previous days; found " + matched.size()
                + ". No debt correction is invented."));
        List<Long> intervals = matched.stream().map(p -> p.next.start() - (nap ? p.before.end() : p.before.start())).toList();
        long base = nap ? anchor.end() : anchor.start();
        long target = base + mean(intervals);
        if (!daytime(target, zone, nap) || !date(target, zone).equals(today))
            return new Comparison(schedule, missing(type, "The adjusted time falls outside today's daytime planning hours. No bedtime/overnight projection."));
        Window adjusted = window(type, cycle, target, intervals, matched.size(),
            description + ". Average next " + (nap ? "wake-to-nap" : "start-to-start")
                + " interval after " + matched.size() + " similar previous days. Observed association, not a physiological sleep/food debt calculation."
                + " Similar means the same early/on-time/late band (20 min), and, when measured, the same short/normal/long band (80-120% of usual)."
                + (nap ? " Earlier waking is included in the anchor." : " No calorie, intake requirement or catch-up amount is inferred."));
        return new Comparison(schedule, adjusted);
    }

    private static String blocked(List<Session> raw, boolean nap) {
        if (raw.isEmpty()) return "Log " + (nap ? "sleep and wake times" : "milk feeds") + " to start comparing.";
        if (raw.stream().anyMatch(Session::active)) return "Care is running or paused. Finish it before comparing.";
        Session last = raw.get(raw.size() - 1);
        if (last.incomplete()) return "Review the latest incomplete care before comparing.";
        return "";
    }
    private static List<Session> onDay(List<Session> groups, LocalDate date, ZoneId zone, boolean nap) {
        return groups.stream().filter(s -> date(s.start(), zone).equals(date) && daytime(s.start(), zone, nap)).toList();
    }
    private static boolean daytime(long time, ZoneId zone, boolean nap) {
        int hour = Instant.ofEpochMilli(time).atZone(zone).getHour();
        return hour >= 5 && hour < (nap ? 20 : 22);
    }
    private static LocalDate date(long time, ZoneId zone) { return Instant.ofEpochMilli(time).atZone(zone).toLocalDate(); }
    private static long clock(long time, ZoneId zone) {
        return Instant.ofEpochMilli(time).atZone(zone).toLocalTime().toNanoOfDay() / 1_000_000;
    }
    private static long relativeClock(long time, LocalDate reference, ZoneId zone) {
        return java.time.Duration.between(reference.atStartOfDay(), Instant.ofEpochMilli(time).atZone(zone).toLocalDateTime()).toMillis();
    }
    private static long atClock(LocalDate date, long millis, ZoneId zone) {
        return date.atStartOfDay().plusNanos(millis * 1_000_000).atZone(zone).toInstant().toEpochMilli();
    }
    private static long mean(List<Long> values) { return Math.round(values.stream().mapToLong(Long::longValue).average().orElseThrow()); }
    private static int timingBand(long difference) { return difference < -20 * MINUTE ? -1 : difference > 20 * MINUTE ? 1 : 0; }
    private static int sizeBand(double ratio) { return ratio < .8 ? -1 : ratio > 1.2 ? 1 : 0; }
    private static Window window(String type, String cycle, long target, List<Long> values, int days, String basis) {
        long average = mean(values);
        List<Long> sorted = values.stream().sorted().toList();
        long lower = sorted.get((sorted.size() - 1) / 4), upper = sorted.get((sorted.size() - 1) * 3 / 4);
        return new Window(type, cycle, target - Math.max(15 * MINUTE, average - lower), target,
            target + Math.max(15 * MINUTE, upper - average), values.size(), days, basis, "");
    }
    private static Window missing(String type, String message) { return new Window(type, "", 0, 0, 0, 0, 0, "", message); }
    private static Comparison unavailable(String type, String message) { return new Comparison(missing(type, message), missing(type, message)); }
    private CareComparison() {}
}
