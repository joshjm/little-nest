package app.littlenest;

import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Predicts the next wet and dirty nappy with a small random forest that is trained on this phone.
 *
 * Every 30 minutes between logged nappy changes becomes one training example: "what did the day look like
 * at this moment" (time since the last feed, recent milk, sleep, time of day...) and "how long until the
 * next wet/dirty nappy was actually logged". The forest is then asked the same question about right now.
 */
public final class NappyPredictor {
    static final long MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    static final int MAX_ROWS = 1200;
    /**
     * Babies change quickly, so only the last month is used and a day 3 days older counts half as much.
     * Chosen by walk-forward backtests on a real export (see NappyBacktestTest); not final science.
     */
    static int HISTORY_DAYS = 30;
    static double HALF_LIFE_DAYS = 3;
    /** Above this checked error, a clock time would mislead, so only the usual gap is shown. */
    static final long IRREGULAR_MINUTES = 240;
    static final long STEP = 30 * MINUTE, LOGGING_GAP = 14 * HOUR;
    static final String[] FEATURES = {"time of day", "time of day", "time since the last nappy change",
        "time since the last nappy of this kind", "time since the last milk feed", "milk feeds in the last 3 hours",
        "bottle milk in the last 6 hours", "solids in the last 12 hours", "being asleep", "sleep in the last 3 hours",
        "nappies of this kind in the last 24 hours"};

    public record Prediction(String type, long earliest, long target, long latest, int changes, int days,
                             long errorMinutes, long baselineErrorMinutes, boolean forest, List<String> factors, String message) {
        public boolean available() { return target > 0; }
        public String title() { return type.equals("wet") ? "Next wet nappy" : "Next dirty nappy"; }
        public String status(long now) {
            if (!available()) return message;
            if (now > latest) return "Later than usual — check the nappy";
            if (now >= earliest) return "Likely about now";
            return "In about " + Insights.duration(target - now);
        }
        public String basis() {
            if (!available()) return message;
            String model = forest ? "Random forest of " + TREES + " decision trees, trained on this phone"
                : "Simple average of your gaps between nappies (it beat the forest on your recent days)";
            return model + " from " + changes + " nappy changes over " + days + " days.\n"
                + "Checked against your most recent days: usually within ±" + errorMinutes + " min"
                + (forest ? " (a simple average was ±" + baselineErrorMinutes + " min)." : ".")
                + (factors.isEmpty() ? "" : "\nBiggest influences: " + String.join(", ", factors) + ".");
        }
    }

    record Diaper(long time, boolean wet, boolean dirty) {
        boolean is(String type) { return type.equals("wet") ? wet : dirty; }
    }
    record Feed(long time, double ml) {}
    record Sleep(long start, long end) {}
    /** Sorted, oldest-first logs that the features are computed from. */
    record History(List<Diaper> diapers, List<Feed> milk, List<Long> solids, List<Sleep> sleeps) {}

    private static final Map<String, Model> CACHE = new LinkedHashMap<>(4, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Model> eldest) { return size() > 4; }
    };

    public static List<Prediction> predict(List<JSONObject> events, long now, ZoneId zone) {
        History history = history(events, now);
        return List.of(predict(history, "wet", now, zone), predict(history, "dirty", now, zone));
    }

    static Prediction predict(History history, String type, long now, ZoneId zone) {
        List<Diaper> diapers = history.diapers;
        if (diapers.isEmpty()) return missing(type, "Log nappy changes to start learning.");
        Diaper last = diapers.get(diapers.size() - 1);
        if (now - last.time > LOGGING_GAP) return missing(type, "The latest nappy is over 14 hours old. Log recent nappies first.");
        String key = type + ":" + HISTORY_DAYS + ":" + HALF_LIFE_DAYS + ":" + signature(history) + ":" + Instant.ofEpochMilli(now).atZone(zone).toLocalDate() + ":" + zone;
        Model model;
        synchronized (CACHE) {
            model = CACHE.get(key);
            if (model == null) { model = Model.train(history, type, zone); CACHE.put(key, model); }
        }
        if (model.message != null) return missing(type, model.message);
        if (model.error > IRREGULAR_MINUTES)
            return missing(type, (type.equals("wet") ? "Wet" : "Dirty") + " nappies have been too irregular lately to give a time (checks were off by about "
                + Insights.duration(model.error * MINUTE) + "). Usually about every " + Insights.duration(Math.round(model.baselineGap) * MINUTE) + ".");
        double minutes = Math.max(5, model.predict(features(history, type, now, zone)));
        long target = now + Math.round(minutes * MINUTE), spread = Math.max(15, model.error) * MINUTE;
        return new Prediction(type, Math.max(now, target - spread), target, target + spread, model.changes, model.days,
            model.error, model.baselineError, model.forest != null, model.factors, "");
    }

    /** A trained model for one nappy type, or a "still learning" message. */
    static final class Model {
        final Forest forest; final double baselineGap; final long error, baselineError; final int changes, days;
        final List<String> factors; final String message;
        private Model(Forest forest, double baselineGap, long error, long baselineError, int changes, int days, List<String> factors, String message) {
            this.forest = forest; this.baselineGap = baselineGap; this.error = error; this.baselineError = baselineError;
            this.changes = changes; this.days = days; this.factors = factors; this.message = message;
        }
        double predict(double[] row) { return forest != null ? forest.predict(row) : baseline(baselineGap, row); }

        static Model train(History history, String type, ZoneId zone) {
            List<Row> rows = rows(history, type, zone);
            int changes = (int) history.diapers.stream().filter(d -> d.is(type)).count();
            Set<LocalDate> dates = new HashSet<>();
            for (Diaper diaper : history.diapers) dates.add(Instant.ofEpochMilli(diaper.time).atZone(zone).toLocalDate());
            int minimum = type.equals("wet") ? 8 : 5;
            if (changes < minimum || dates.size() < 3 || rows.size() < 40)
                return new Model(null, 0, 0, 0, changes, dates.size(), List.of(), "Learning: need at least " + minimum + " "
                    + type + " nappies across 3 days in the last " + HISTORY_DAYS + " days (" + changes + " found, " + dates.size() + " days).");
            // Test on the most recent fifth of the history, after training only on what came before it.
            int split = (int) (rows.size() * .8);
            List<Row> train = rows.subList(0, split), test = rows.subList(split, rows.size());
            Forest trial = Forest.train(matrix(train), targets(train), weights(train), new Random(rows.size()));
            double gap = medianGap(history.diapers, type, test.get(0).time);
            double forestError = 0, baselineError = 0;
            for (Row row : test) {
                forestError += Math.abs(trial.predict(row.x) - row.y);
                baselineError += Math.abs(baseline(gap, row.x) - row.y);
            }
            forestError /= test.size(); baselineError /= test.size();
            boolean useForest = forestError <= baselineError;
            Forest forest = useForest ? Forest.train(matrix(rows), targets(rows), weights(rows), new Random(rows.size() + 1)) : null;
            return new Model(forest, medianGap(history.diapers, type, Long.MAX_VALUE), Math.round(useForest ? forestError : baselineError),
                Math.round(baselineError), changes, dates.size(), useForest ? forest.topFactors(2) : List.of(), null);
        }
    }

    /** The simple "typical gap minus time already waited" guess that the forest has to beat. */
    static double baseline(double gap, double[] row) { return Math.max(15, gap - row[3]); }

    record Row(long time, double[] x, double y) {}

    static List<Row> rows(History history, String type, ZoneId zone) {
        List<Diaper> diapers = history.diapers;
        List<Row> rows = new ArrayList<>();
        long cap = type.equals("wet") ? 16 * HOUR : 4 * DAY;
        for (int i = 0; i + 1 < diapers.size(); i++) {
            long from = diapers.get(i).time, to = diapers.get(i + 1).time;
            if (to - from > LOGGING_GAP) continue;
            long next = 0;
            for (int j = i + 1; j < diapers.size(); j++) {
                if (diapers.get(j).time - diapers.get(j - 1).time > LOGGING_GAP) break;
                if (diapers.get(j).is(type)) { next = diapers.get(j).time; break; }
            }
            if (next == 0) continue;
            for (long t = from; t < to; t += STEP)
                if (next - t <= cap) rows.add(new Row(t, features(history, type, t, zone), (next - t) / (double) MINUTE));
        }
        return rows.size() > MAX_ROWS ? rows.subList(rows.size() - MAX_ROWS, rows.size()) : rows;
    }

    static double[] features(History history, String type, long t, ZoneId zone) {
        ZonedDateTime local = Instant.ofEpochMilli(t).atZone(zone);
        double angle = 2 * Math.PI * local.toLocalTime().toSecondOfDay() / 86_400.0;
        long lastChange = 0, lastSame = 0; int sameToday = 0;
        for (Diaper diaper : history.diapers) {
            if (diaper.time > t) break;
            lastChange = diaper.time;
            if (diaper.is(type)) { lastSame = diaper.time; if (t - diaper.time <= DAY) sameToday++; }
        }
        long lastFeed = 0; int recentFeeds = 0; double ml = 0;
        for (Feed feed : history.milk) {
            if (feed.time > t) break;
            lastFeed = feed.time;
            if (t - feed.time <= 3 * HOUR) recentFeeds++;
            if (t - feed.time <= 6 * HOUR) ml += feed.ml;
        }
        int solids = 0;
        for (long time : history.solids) if (time <= t && t - time <= 12 * HOUR) solids++;
        boolean asleep = false; long slept = 0;
        for (Sleep sleep : history.sleeps) {
            if (sleep.start > t) break;
            if (sleep.end > t) asleep = true;
            slept += Records.overlap(sleep.start, sleep.end, t - 3 * HOUR, t);
        }
        long longAgo = type.equals("wet") ? DAY : 4 * DAY;
        return new double[]{Math.sin(angle), Math.cos(angle), since(t, lastChange, DAY), since(t, lastSame, longAgo),
            since(t, lastFeed, DAY), recentFeeds, ml, solids, asleep ? 1 : 0, slept / (double) MINUTE, sameToday};
    }
    private static double since(long now, long time, long limit) { return (time == 0 ? limit : Math.min(limit, now - time)) / (double) MINUTE; }

    static History history(List<JSONObject> events, long now) {
        long from = now - HISTORY_DAYS * DAY;
        List<Diaper> diapers = new ArrayList<>(); List<Feed> milk = new ArrayList<>();
        List<Long> solids = new ArrayList<>(); List<Sleep> sleeps = new ArrayList<>();
        for (JSONObject event : events) {
            long start = event.optLong("start");
            if (start > now || start < from - DAY || event.optBoolean("importIncomplete")) continue;
            switch (event.optString("kind")) {
                case "diaper" -> {
                    String value = event.optString("diaper");
                    if (start >= from) diapers.add(new Diaper(start, value.equals("Wet") || value.equals("Mixed"), value.equals("Dirty") || value.equals("Mixed")));
                }
                case "bottle" -> milk.add(new Feed(start, Insights.bottleMl(event)));
                case "breast" -> milk.add(new Feed(start, 0));
                case "solids" -> solids.add(start);
                case "sleep" -> {
                    boolean running = event.optBoolean("running") || event.optBoolean("timerPaused");
                    sleeps.add(new Sleep(start, running ? now : Math.max(start, event.optLong("end", start))));
                }
                default -> { }
            }
        }
        diapers.sort(Comparator.comparingLong(Diaper::time)); milk.sort(Comparator.comparingLong(Feed::time));
        solids.sort(Comparator.naturalOrder()); sleeps.sort(Comparator.comparingLong(Sleep::start));
        return new History(diapers, milk, solids, sleeps);
    }

    private static String signature(History history) {
        return Integer.toHexString(history.hashCode()) + ":" + history.diapers.size() + ":" + history.milk.size();
    }
    static double medianGap(List<Diaper> diapers, String type, long before) {
        List<Long> gaps = new ArrayList<>(); long previous = 0;
        for (Diaper diaper : diapers) {
            if (diaper.time >= before) break;
            if (!diaper.is(type)) continue;
            if (previous > 0 && diaper.time - previous <= (type.equals("wet") ? 16 * HOUR : 4 * DAY)) gaps.add(diaper.time - previous);
            previous = diaper.time;
        }
        if (gaps.isEmpty()) return type.equals("wet") ? 180 : 720;
        gaps.sort(Comparator.naturalOrder());
        return gaps.get(gaps.size() / 2) / (double) MINUTE;
    }
    private static double[][] matrix(List<Row> rows) { return rows.stream().map(Row::x).toArray(double[][]::new); }
    private static double[] targets(List<Row> rows) { return rows.stream().mapToDouble(Row::y).toArray(); }
    private static double[] weights(List<Row> rows) {
        long newest = rows.get(rows.size() - 1).time;
        return rows.stream().mapToDouble(row -> Math.pow(.5, (newest - row.time) / (HALF_LIFE_DAYS * DAY))).toArray();
    }
    private static Prediction missing(String type, String message) {
        return new Prediction(type, 0, 0, 0, 0, 0, 0, 0, false, List.of(), message);
    }

    static final int TREES = 30, DEPTH = 6, LEAF = 5;

    /** A plain random forest regressor: many small decision trees, each grown on a random resample of the rows. */
    static final class Forest {
        final List<Node> trees = new ArrayList<>();
        final double[] importance;
        Forest(int features) { importance = new double[features]; }

        static Forest train(double[][] x, double[] y, Random random) {
            double[] even = new double[x.length]; Arrays.fill(even, 1);
            return train(x, y, even, random);
        }
        /** Each tree learns from a random resample of the rows; rows with a bigger weight are picked more often. */
        static Forest train(double[][] x, double[] y, double[] weights, Random random) {
            int features = x[0].length, n = x.length;
            Forest forest = new Forest(features);
            // Sort the rows by each input once; every tree then reuses this order instead of re-sorting.
            int[][] sorted = new int[features][];
            for (int f = 0; f < features; f++) {
                int feature = f;
                sorted[f] = java.util.stream.IntStream.range(0, n).boxed()
                    .sorted(Comparator.comparingDouble(row -> x[row][feature])).mapToInt(Integer::intValue).toArray();
            }
            double[] cumulative = new double[n];
            for (int i = 0; i < n; i++) cumulative[i] = (i == 0 ? 0 : cumulative[i - 1]) + weights[i];
            for (int t = 0; t < TREES; t++) {
                int[] counts = new int[n];
                for (int i = 0; i < n; i++) {
                    int pick = Arrays.binarySearch(cumulative, random.nextDouble() * cumulative[n - 1]);
                    counts[Math.min(n - 1, pick < 0 ? -pick - 1 : pick)]++;
                }
                int[][] sample = new int[features][n];
                for (int f = 0; f < features; f++) {
                    int k = 0;
                    for (int row : sorted[f]) for (int c = 0; c < counts[row]; c++) sample[f][k++] = row;
                }
                forest.trees.add(forest.grow(x, y, sample, 0, random));
            }
            return forest;
        }
        double predict(double[] row) {
            double sum = 0;
            for (Node tree : trees) sum += tree.predict(row);
            return sum / trees.size();
        }
        /** Names of the inputs whose splits reduced prediction error the most. */
        List<String> topFactors(int count) {
            Map<String, Double> byName = new HashMap<>();
            for (int i = 0; i < importance.length; i++) byName.merge(FEATURES[i], importance[i], Double::sum);
            return byName.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(count).map(Map.Entry::getKey).toList();
        }

        /** Grows one regression tree. {@code rows[f]} holds the same resampled rows, ordered by input {@code f}. */
        private Node grow(double[][] x, double[] y, int[][] rows, int depth, Random random) {
            int n = rows[0].length;
            double sum = 0, squares = 0;
            for (int row : rows[0]) { sum += y[row]; squares += y[row] * y[row]; }
            Node node = new Node(); node.value = sum / n;
            if (depth >= DEPTH || n < 2 * LEAF) return node;
            double parentError = squares - sum * sum / n, bestGain = 1e-9;
            int bestFeature = -1; double bestThreshold = 0;
            int features = rows.length, tries = Math.max(1, features / 3);
            for (int f : pick(features, tries, random)) {
                int[] order = rows[f];
                double leftSum = 0, leftSquares = 0;
                for (int i = 0; i < n - 1; i++) {
                    double value = y[order[i]]; leftSum += value; leftSquares += value * value;
                    int left = i + 1, right = n - left;
                    if (left < LEAF || right < LEAF || x[order[i]][f] == x[order[i + 1]][f]) continue;
                    double rightSum = sum - leftSum, rightSquares = squares - leftSquares;
                    double error = leftSquares - leftSum * leftSum / left + rightSquares - rightSum * rightSum / right;
                    if (parentError - error > bestGain) {
                        bestGain = parentError - error; bestFeature = f;
                        bestThreshold = (x[order[i]][f] + x[order[i + 1]][f]) / 2;
                    }
                }
            }
            if (bestFeature < 0) return node;
            importance[bestFeature] += bestGain;
            int leftCount = 0;
            for (int row : rows[0]) if (x[row][bestFeature] <= bestThreshold) leftCount++;
            int[][] left = new int[features][leftCount], right = new int[features][n - leftCount];
            for (int f = 0; f < features; f++) {
                int l = 0, r = 0;
                for (int row : rows[f]) if (x[row][bestFeature] <= bestThreshold) left[f][l++] = row; else right[f][r++] = row;
            }
            node.feature = bestFeature; node.threshold = bestThreshold;
            node.left = grow(x, y, left, depth + 1, random); node.right = grow(x, y, right, depth + 1, random);
            return node;
        }
        private static int[] pick(int features, int count, Random random) {
            int[] all = new int[features];
            for (int i = 0; i < features; i++) all[i] = i;
            for (int i = 0; i < count; i++) { int j = i + random.nextInt(features - i); int swap = all[i]; all[i] = all[j]; all[j] = swap; }
            return Arrays.copyOf(all, count);
        }
    }
    static final class Node {
        int feature = -1; double threshold, value; Node left, right;
        double predict(double[] row) {
            Node node = this;
            while (node.feature >= 0) node = row[node.feature] <= node.threshold ? node.left : node.right;
            return node.value;
        }
    }

    private NappyPredictor() {}
}
