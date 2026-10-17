package app.littlenest;

import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Descriptive planning only: goals and age guidance never drive alarms or predictions. */
final class CarePlanner {
    record Goals(int sleepMinutes, int naps, int feeds) {
        Goals {
            if (sleepMinutes < 0 || sleepMinutes > 1440)
                throw new IllegalArgumentException("Sleep goal must be blank or 1–1440 minutes.");
            if (naps < 0 || naps > 12)
                throw new IllegalArgumentException("Nap goal must be blank or 1–12 naps.");
            if (feeds < 0 || feeds > 30)
                throw new IllegalArgumentException("Milk-feed goal must be blank or 1–30 sessions.");
        }
    }
    record Progress(long sleepMs, int naps, int feeds, boolean incomplete) {}
    record Guidance(String age, String sleep, String naps, String feeds) {}

    static Guidance guidance(String dob, LocalDate today) {
        LocalDate birth;
        try { birth = LocalDate.parse(dob); }
        catch (DateTimeParseException e) { return new Guidance("", "", "", ""); }
        if (birth.isAfter(today)) return new Guidance("", "", "", "");
        long months = ChronoUnit.MONTHS.between(birth, today);
        String age = months == 0 ? "Under 1 month" : months + " months";
        String sleep = months < 4
            ? "Newborn sleep varies widely and comes in short stretches, day and night. NHS describes roughly 8–18 hours over 24 hours; this is context, not a target."
            : months < 12 ? "Ages 4–11 months: about 12–16 hours of sleep over 24 hours, including naps (CDC)."
            : months < 36 ? "Ages 1–2 years: about 11–14 hours over 24 hours, including naps (CDC)."
            : months < 72 ? "Ages 3–5 years: about 10–13 hours over 24 hours, including naps (CDC)."
            : "Sleep needs change with age. Ask your child's clinician for guidance suited to them.";
        String naps = months < 3 ? "Many short sleeps are normal; newborns do not need a fixed nap count or schedule."
            : months < 6 ? "Several daytime naps are common while a day/night pattern develops. Nap lengths vary; there is no single count every baby must meet."
            : months < 12 ? "Around 6–12 months, many babies move towards 2 daytime naps (HSE). Transitions vary; follow sleepy cues rather than dropping a nap to match a number."
            : months < 36 ? "Around 12–15 months, many children move towards 1 midday nap (HSE). Some need 2 for longer."
            : "Daytime naps gradually become less frequent. Some preschool children still need a nap.";
        String feeds = months < 1
            ? "Newborns commonly feed 8–12 times in 24 hours, whether breastfed or formula-fed (CDC). Cluster feeding can happen. Some sleepy newborns need waking to feed; follow your clinician's plan."
            : months < 6 ? "In the early months, breastfed babies commonly feed 8–12 times in 24 hours; formula-fed babies often feed about every 3–4 hours (CDC). These are not limits. Mixed feeding has no single normal count."
            : months < 12 ? "At 6–12 months, breastfeed to hunger cues. CDC describes about 5–6 formula or solid-food feeding occasions over 24 hours, not 5–6 milk feeds. Milk-feed counts alone cannot assess intake."
            : "After the first birthday, breastfeeding frequency varies widely. Meals, drinks and family routines change; there is no single milk-feed count to aim for.";
        return new Guidance(age, sleep, naps, feeds);
    }

    static Progress progress(List<JSONObject> events, long now, ZoneId zone) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long from = today.atStartOfDay(zone).toInstant().toEpochMilli();
        List<long[]> intervals = new ArrayList<>();
        List<CarePredictor.Session> feeds = new ArrayList<>(), naps = new ArrayList<>();
        boolean incomplete = false;
        for (JSONObject event : events) {
            long start = event.optLong("start"), end = event.optLong("end", start);
            String kind = event.optString("kind");
            if (start > now || !(kind.equals("sleep") || kind.equals("breast") || kind.equals("bottle"))) continue;
            if (event.optBoolean("importIncomplete")) {
                if (start >= from || end > from) incomplete = true;
                continue;
            }
            if (kind.equals("sleep")) {
                for (long[] interval : Insights.sleepIntervals(event, now)) {
                    long a = Math.max(from, interval[0]), b = Math.min(now, interval[1]);
                    if (b > a) intervals.add(new long[]{a, b});
                }
            }
            if (start < from - CarePredictor.DAY || end < start) continue;
            CarePredictor.Session session = new CarePredictor.Session(start, event.optBoolean("running") ? now : Math.min(now, end),
                event.optString("id"), false, false, 0);
            if (!kind.equals("sleep")) feeds.add(session);
            else if (event.optString("sleepType").equals("Nap")
                || !event.optString("sleepType").equals("Night sleep") && localMinute(start, zone) >= 300 && localMinute(start, zone) < 1200) {
                naps.add(new CarePredictor.Session(start, Math.max(start + 1, session.end()), session.id(), false, false, 0));
            }
        }
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        long sleep = 0, lastEnd = from;
        for (long[] interval : intervals) {
            sleep += Math.max(0, interval[1] - Math.max(lastEnd, interval[0]));
            lastEnd = Math.max(lastEnd, interval[1]);
        }
        feeds.sort(Comparator.comparingLong(CarePredictor.Session::start));
        naps.sort(Comparator.comparingLong(CarePredictor.Session::start));
        return new Progress(sleep, (int) CarePredictor.grouped(naps, 15 * CarePredictor.MINUTE, true).stream().filter(s -> s.start() >= from).count(),
            (int) CarePredictor.grouped(feeds, 30 * CarePredictor.MINUTE, false).stream().filter(s -> s.start() >= from).count(), incomplete);
    }

    static CarePredictor.Window bedtime(List<JSONObject> events, long now, ZoneId zone) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        var raw = CarePredictor.sessions(events, "nap", now);
        if (raw.stream().anyMatch(CarePredictor.Session::active))
            return missingBedtime("Sleep is running or paused. Finish it when baby wakes.");
        if (!raw.isEmpty() && raw.get(raw.size() - 1).incomplete())
            return missingBedtime("Review the latest incomplete sleep before estimating bedtime.");
        TreeMap<LocalDate, Integer> nights = new TreeMap<>();
        java.util.Set<LocalDate> incompleteDays = new java.util.HashSet<>();
        for (JSONObject event : events) {
            if (!event.optString("kind").equals("sleep")) continue;
            long start = event.optLong("start");
            LocalDate date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate();
            if (date.isBefore(today.minusDays(14)) || date.isAfter(today) || start > now) continue;
            if (event.optBoolean("importIncomplete")) { incompleteDays.add(date); continue; }
            if (!event.optString("sleepType").equals("Night sleep") || localMinute(start, zone) < 1020) continue;
            if (date.equals(today)) return missingBedtime("Night sleep is already logged for this evening. No extra bedtime is projected.");
            if (event.optBoolean("running") || event.optBoolean("timerPaused") || event.optLong("end") > now
                || Records.duration(event, now) <= 0) continue;
            nights.merge(date, localMinute(start, zone), Math::min);
        }
        if (incompleteDays.contains(today)) return missingBedtime("Review today's incomplete sleep before estimating bedtime.");
        incompleteDays.forEach(nights::remove);
        List<Integer> times = new ArrayList<>(nights.values());
        if (times.size() < 4) return missingBedtime("Learning: log sleep as Night sleep with an evening start (17:00–23:59) on at least 4 previous days in the last 14 days (" + times.size() + " found).");
        times = times.subList(Math.max(0, times.size() - 7), times.size());
        long mean = Math.round(times.stream().mapToInt(Integer::intValue).average().orElseThrow());
        List<Integer> sorted = times.stream().sorted().toList();
        long lower = Math.min(sorted.get((sorted.size() - 1) / 4), mean - 15);
        long upper = Math.max(sorted.get((sorted.size() - 1) * 3 / 4), mean + 15);
        return new CarePredictor.Window("bedtime", "", clock(today, lower, zone), clock(today, mean, zone),
            clock(today, upper, zone), times.size(), times.size(),
            "Average first evening Night sleep start on " + times.size() + " recent days. Last 14 days; at most 7 nights. "
                + "Only explicitly labelled Night sleep starting 17:00–23:59 counts. Missing logs can shift this pattern. No bedtime alerts.", "");
    }
    private static int localMinute(long time, ZoneId zone) {
        return Instant.ofEpochMilli(time).atZone(zone).toLocalTime().toSecondOfDay() / 60;
    }
    private static long clock(LocalDate date, long minutes, ZoneId zone) {
        return date.atStartOfDay().plusMinutes(minutes).atZone(zone).toInstant().toEpochMilli();
    }
    private static CarePredictor.Window missingBedtime(String message) {
        return new CarePredictor.Window("bedtime", "", 0, 0, 0, 0, 0, "", message);
    }
    private CarePlanner() {}
}
