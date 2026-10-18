package app.littlenest;

import org.json.JSONObject;
import org.junit.Test;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.Assume.assumeTrue;

/**
 * Optional walk-forward check of the nappy model on a private local export. Prints only summary numbers.
 * Run with: ./gradlew :app:testDebugUnitTest --tests '*NappyBacktest*' -PnaraCsv=/absolute/path.csv -i
 */
public class NappyBacktestTest {
    private static final long HOUR = 3_600_000L;

    @Test public void walkForwardOnLocalExport() throws Exception {
        String path = System.getProperty("naraCsv", "");
        assumeTrue("Pass -PnaraCsv=/absolute/path.csv to backtest a private local export", !path.isBlank());
        NaraImport.Preview preview;
        try (Reader input = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) { preview = NaraImport.parse(input); }
        ZoneId zone = ZoneId.systemDefault();
        Map<String, List<JSONObject>> byProfile = preview.events().stream().map(NaraImport.Entry::data)
            .collect(Collectors.groupingBy(e -> e.optString("profileId")));
        int days = NappyPredictor.HISTORY_DAYS; double halfLife = NappyPredictor.HALF_LIFE_DAYS;
        try {
            for (int history : new int[]{14, 30, 60}) for (double half : new double[]{3, 7, 1000}) {
                NappyPredictor.HISTORY_DAYS = history; NappyPredictor.HALF_LIFE_DAYS = half;
                System.out.println("Settings: " + history + " days of history, half-life " + (half > 100 ? "none" : half + " days"));
                backtest(byProfile, zone);
            }
        } finally { NappyPredictor.HISTORY_DAYS = days; NappyPredictor.HALF_LIFE_DAYS = halfLife; }
    }

    private static void backtest(Map<String, List<JSONObject>> byProfile, ZoneId zone) {
        for (List<JSONObject> events : byProfile.values()) {
            List<JSONObject> diapers = events.stream().filter(e -> e.optString("kind").equals("diaper"))
                .sorted(Comparator.comparingLong(e -> e.optLong("start"))).toList();
            if (diapers.size() < 50) continue;
            long last = diapers.get(diapers.size() - 1).optLong("start");
            for (String type : List.of("wet", "dirty")) {
                double forest = 0, baseline = 0; int count = 0, inside = 0, usedForest = 0;
                for (long now = last - 14 * 24 * HOUR; now < last; now += 2 * HOUR) {
                    long actual = actual(diapers, type, now);
                    if (actual == 0) continue;
                    var prediction = NappyPredictor.predict(events, now, zone).stream().filter(p -> p.type().equals(type)).findFirst().orElseThrow();
                    if (!prediction.available()) continue;
                    var history = NappyPredictor.history(events, now);
                    double guess = NappyPredictor.baseline(NappyPredictor.medianGap(history.diapers(), type, now),
                        NappyPredictor.features(history, type, now, zone));
                    forest += Math.abs(prediction.target() - actual) / 60_000.0;
                    baseline += Math.abs(now + guess * 60_000 - actual) / 60_000.0;
                    if (actual >= prediction.earliest() && actual <= prediction.latest()) inside++;
                    if (prediction.forest()) usedForest++;
                    count++;
                }
                if (count == 0) continue;
                System.out.printf("Backtest %s nappy: %d checks, model error %.0f min, simple-average error %.0f min, %d%% inside window, forest chosen %d%%%n",
                    type, count, forest / count, baseline / count, 100 * inside / count, 100 * usedForest / count);
            }
        }
    }

    /** When the next nappy of this type was actually logged, or 0 if logs have a gap first. */
    private static long actual(List<JSONObject> diapers, String type, long now) {
        long previous = now;
        for (JSONObject diaper : diapers) {
            long time = diaper.optLong("start");
            if (time <= now) continue;
            if (time - previous > NappyPredictor.LOGGING_GAP) return 0;
            String value = diaper.optString("diaper");
            if (value.equals("Mixed") || value.equals(type.equals("wet") ? "Wet" : "Dirty")) return time;
            previous = time;
        }
        return 0;
    }
}
