package app.littlenest;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class CarePredictor {
    static final long MINUTE = 60_000L, DAY = 86_400_000L;
    public record Settings(int feedMinutes, int wakeMinutes) {
        public Settings {
            if (feedMinutes != 0 && (feedMinutes < 30 || feedMinutes > 720))
                throw new IllegalArgumentException("Feed interval must be blank (learned) or 30–720 minutes");
            if (wakeMinutes != 0 && (wakeMinutes < 20 || wakeMinutes > 480))
                throw new IllegalArgumentException("Wake interval must be blank (learned) or 20–480 minutes");
        }
    }
    public record Window(String type, String cycle, long earliest, long target, long latest,
                         int samples, int days, String basis, String message) {
        public boolean available() { return target > 0; }
        public String title() { return type.equals("feed") ? "Next milk feed" : "Nap sweet spot"; }
        public String status(long now) {
            if (!available()) return message;
            if (now > latest) return "Window has passed — check cues or log any missing care";
            if (now >= earliest) return "Suggested window is now";
            return "In about " + Insights.duration(target - now);
        }
    }
    private record Session(long start, long end, String id, boolean active, boolean incomplete) {}
    private record Sample(long interval, long anchor) {}

    public static List<Window> predict(List<JSONObject> events, Settings settings, long now, ZoneId zone) {
        List<Session> feeds = new ArrayList<>(), sleeps = new ArrayList<>();
        for (JSONObject event : events) {
            long start = event.optLong("start"), end = event.optLong("end", start);
            if (start > now || start < now - 15 * DAY || end < start) continue;
            String kind = event.optString("kind");
            Session session = new Session(start, Math.min(end, now), event.optString("id", Long.toString(start)),
                event.optBoolean("running") || event.optBoolean("timerPaused"), event.optBoolean("importIncomplete"));
            if (kind.equals("bottle") || kind.equals("breast")) feeds.add(session);
            if (kind.equals("sleep")) sleeps.add(session);
        }
        feeds.sort(Comparator.comparingLong(Session::start).thenComparing(Session::id));
        sleeps.sort(Comparator.comparingLong(Session::start).thenComparing(Session::id));
        return List.of(feed(feeds, settings.feedMinutes(), now, zone), nap(sleeps, settings.wakeMinutes(), now, zone));
    }

    private static Window feed(List<Session> raw, int manual, long now, ZoneId zone) {
        if (raw.isEmpty()) return missing("feed", "Log a milk feed to start learning. Solids do not reset milk-feed estimates.");
        Session last = raw.get(raw.size() - 1);
        if (raw.stream().anyMatch(Session::active)) return missing("feed", "A milk feed is running or paused. Finish it before the next estimate.");
        if (last.incomplete) return missing("feed", "Review the latest incomplete milk feed before estimating the next one.");
        if (now - last.start > 18 * 60 * MINUTE) return missing("feed", "The latest milk feed is over 18 hours old. Log recent care first.");
        List<Session> groups = new ArrayList<>();
        for (Session session : raw) {
            if (session.active || session.incomplete) continue;
            if (!groups.isEmpty()) {
                Session prior = groups.get(groups.size() - 1);
                if (session.start - prior.end <= 30 * MINUTE) {
                    groups.set(groups.size() - 1, new Session(prior.start, Math.max(prior.end, session.end), session.id, false, false));
                    continue;
                }
            }
            groups.add(session);
        }
        Session anchor = groups.get(groups.size() - 1);
        List<Sample> samples = new ArrayList<>();
        for (int i = 1; i < groups.size(); i++) {
            Session before = groups.get(i - 1), after = groups.get(i);
            long interval = after.start - before.start;
            if (after.start >= now - 14 * DAY && interval >= 30 * MINUTE && interval <= 8 * 60 * MINUTE
                && daytime(before.start, zone, 22) == daytime(anchor.start, zone, 22)
                && daytime(after.start, zone, 22) == daytime(before.start, zone, 22))
                samples.add(new Sample(interval, before.start));
        }
        return estimate("feed", anchor, anchor.start, samples, manual, now, zone);
    }

    private static Window nap(List<Session> raw, int manual, long now, ZoneId zone) {
        if (raw.isEmpty()) return missing("nap", "Log sleep and wake times to learn a nap window.");
        Session last = raw.get(raw.size() - 1);
        if (raw.stream().anyMatch(Session::active)) return missing("nap", "Sleep is running or paused. Finish it when baby wakes to estimate the next nap.");
        if (last.incomplete) return missing("nap", "Review the latest incomplete sleep before estimating the next nap.");
        List<Session> groups = new ArrayList<>();
        for (Session session : raw) {
            if (session.incomplete || session.active || session.end <= session.start) continue;
            if (!groups.isEmpty()) {
                Session prior = groups.get(groups.size() - 1);
                if (session.start - prior.end <= 15 * MINUTE) {
                    groups.set(groups.size() - 1, new Session(prior.start, Math.max(prior.end, session.end), session.id, false, false));
                    continue;
                }
            }
            groups.add(session);
        }
        if (groups.isEmpty()) return missing("nap", "Log a completed sleep with an end time first.");
        Session anchor = groups.get(groups.size() - 1);
        if (now - anchor.end > 18 * 60 * MINUTE) return missing("nap", "The latest wake time is over 18 hours old. Log recent sleep first.");
        List<Sample> samples = new ArrayList<>();
        for (int i = 1; i < groups.size(); i++) {
            Session before = groups.get(i - 1), after = groups.get(i);
            long awake = after.start - before.end;
            if (after.start >= now - 14 * DAY && awake >= 20 * MINUTE && awake <= 8 * 60 * MINUTE
                && daytime(after.start, zone, 20)) samples.add(new Sample(awake, before.end));
        }
        Window window = estimate("nap", anchor, anchor.end, samples, manual, now, zone);
        if (window.available() && !daytime(window.target, zone, 20))
            return missing("nap", "The next sleep falls outside daytime (05:00–20:00). No nap estimate; follow bedtime cues.");
        return window;
    }

    private static Window estimate(String type, Session anchor, long base, List<Sample> all, int manual, long now, ZoneId zone) {
        String cycle = UUID.nameUUIDFromBytes((type + ":" + anchor.id + ":" + base).getBytes(StandardCharsets.UTF_8)).toString();
        if (manual > 0) {
            long target = base + manual * MINUTE;
            return new Window(type, cycle, target - 15 * MINUTE, target, target + 15 * MINUTE, 0, 0,
                "Your manual interval: " + manual + " min; planning window ±15 min", "");
        }
        List<Sample> local = all.stream().filter(sample -> hourDistance(sample.anchor, base, zone) <= 4).toList();
        boolean matched = sufficient(local, zone);
        List<Sample> selected = matched ? local : all;
        if (selected.size() > 80) selected = selected.subList(selected.size() - 80, selected.size());
        int days = dates(selected, zone).size();
        if (!sufficient(selected, zone))
            return missing(type, "Learning: need at least 4 usable " + (type.equals("feed") ? "milk-feed intervals" : "wake-to-nap intervals")
                + " across 2 days in the last 14 days (" + selected.size() + " found). Set an optional manual interval meanwhile.");
        List<Long> intervals = selected.stream().map(Sample::interval).sorted().toList();
        long median = quantile(intervals, .5), lower = Math.min(quantile(intervals, .25), median - 15 * MINUTE);
        long upper = Math.max(quantile(intervals, .75), median + 15 * MINUTE);
        lower = Math.max(5 * MINUTE, lower);
        String consistency = upper - lower > median / 2 ? "Variable pattern" : selected.size() >= 10 && days >= 3 ? "More consistent pattern" : "Early estimate";
        return new Window(type, cycle, base + lower, base + median, base + upper, selected.size(), days,
            consistency + " · " + selected.size() + " intervals / " + days + " days · "
                + (matched ? "similar time of day" : "broader recent pattern") + " · last 14 days", "");
    }
    private static boolean sufficient(List<Sample> samples, ZoneId zone) { return samples.size() >= 4 && dates(samples, zone).size() >= 2; }
    private static Set<LocalDate> dates(List<Sample> samples, ZoneId zone) {
        Set<LocalDate> dates = new HashSet<>();
        for (Sample sample : samples) dates.add(Instant.ofEpochMilli(sample.anchor).atZone(zone).toLocalDate());
        return dates;
    }
    private static double hourDistance(long first, long second, ZoneId zone) {
        var a = Instant.ofEpochMilli(first).atZone(zone).toLocalTime();
        var b = Instant.ofEpochMilli(second).atZone(zone).toLocalTime();
        double hours = Math.abs(a.toSecondOfDay() - b.toSecondOfDay()) / 3600.0;
        return Math.min(hours, 24 - hours);
    }
    private static boolean daytime(long time, ZoneId zone, int endHour) {
        int hour = Instant.ofEpochMilli(time).atZone(zone).getHour(); return hour >= 5 && hour < endHour;
    }
    private static long quantile(List<Long> values, double fraction) {
        double index = (values.size() - 1) * fraction;
        int lower = (int) index, upper = (int) Math.ceil(index);
        return Math.round(values.get(lower) + (values.get(upper) - values.get(lower)) * (index - lower));
    }
    private static Window missing(String type, String message) { return new Window(type, "", 0, 0, 0, 0, 0, "", message); }
    private CarePredictor() {}
}
