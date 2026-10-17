package app.littlenest;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class GrowthStandards {
    static final double DAYS_PER_MONTH = 365.25 / 12;
    static final double[] Z = {-1.880793608, -1.036433389, 0, 1.036433389, 1.880793608};
    static final String[] CENTILES = {"3", "15", "50", "85", "97"};
    record Lms(double age, double l, double m, double s) {
        double value(double z) {
            return l == 0 ? m * Math.exp(s * z) : m * Math.pow(1 + l * s * z, 1 / l);
        }
        double z(double value) {
            return l == 0 ? Math.log(value / m) / s : (Math.pow(value / m, l) - 1) / (l * s);
        }
    }
    record Sample(LocalDate date, double value) {}
    private final Map<String, List<Lms>> tables = new HashMap<>();

    GrowthStandards(Reader source) throws IOException {
        try (BufferedReader reader = new BufferedReader(source)) {
            if (!"measure,sex,series,age,L,M,S".equals(reader.readLine())) throw new IOException("Invalid WHO data header");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split(",");
                if (cells.length != 7) throw new IOException("Invalid WHO data row");
                try {
                    Lms row = new Lms(Double.parseDouble(cells[3]), Double.parseDouble(cells[4]),
                        Double.parseDouble(cells[5]), Double.parseDouble(cells[6]));
                    tables.computeIfAbsent(cells[0] + "/" + cells[1] + "/" + cells[2], key -> new ArrayList<>()).add(row);
                } catch (NumberFormatException e) { throw new IOException("Invalid WHO data number", e); }
            }
        }
        if (tables.size() != 14) throw new IOException("Incomplete WHO growth data");
    }

    Lms at(String measure, String sex, double days) {
        if (!Double.isFinite(days) || days < 0 || days > 60 * DAYS_PER_MONTH) return null;
        String series = days <= 91 ? "weeks" : measure.equals("height") ? days < 24 * DAYS_PER_MONTH ? "length" : "standing" : "months";
        List<Lms> rows = tables.get(measure + "/" + sex + "/" + series);
        if (rows == null) return null;
        double age = series.equals("weeks") ? days / 7 : days / DAYS_PER_MONTH;
        for (int i = 1; i < rows.size(); i++) {
            Lms high = rows.get(i), low = rows.get(i - 1);
            if (age <= high.age()) {
                double f = (age - low.age()) / (high.age() - low.age());
                return new Lms(age, low.l() + f * (high.l() - low.l()), low.m() + f * (high.m() - low.m()), low.s() + f * (high.s() - low.s()));
            }
        }
        return null;
    }

    static String sex(JSONObject profile) {
        return switch (profile.optString("sex")) { case "Male" -> "boys"; case "Female" -> "girls"; default -> ""; };
    }
    static LocalDate birth(JSONObject profile, boolean adjusted) {
        String date = profile.optString(adjusted ? "adjustedDob" : "dob");
        return date.isBlank() ? null : LocalDate.parse(date);
    }
    static double days(LocalDate birth, LocalDate date) { return ChronoUnit.DAYS.between(birth, date); }
    static double display(double value, String measure, boolean imperial) {
        return imperial ? value / (measure.equals("weight") ? .45359237 : 2.54) : value;
    }
    static List<Sample> samples(List<JSONObject> events, String measure) {
        List<Sample> result = new ArrayList<>();
        for (JSONObject event : events) {
            if (!event.optString("kind").equals("growth") || event.optString(measure).isBlank()) continue;
            double value = event.optDouble(measure, Double.NaN);
            if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Growth measurements must be positive numbers.");
            if (measure.equals("weight") && event.optString("weightUnit").equals("lb")) value *= .45359237;
            if (!measure.equals("weight") && event.optString(measure.equals("head") ? "headUnit" : "lengthUnit",
                    event.optString("lengthUnit")).equals("in")) value *= 2.54;
            result.add(new Sample(Insights.date(event.optLong("start")), value));
        }
        result.sort(java.util.Comparator.comparing(Sample::date));
        return List.copyOf(result);
    }
    static String percentile(double z) {
        if (z < -3.090232306) return "below 0.1";
        if (z > 3.090232306) return "above 99.9";
        double t = 1 / (1 + .2316419 * Math.abs(z));
        double tail = Math.exp(-z * z / 2) / Math.sqrt(2 * Math.PI)
            * t * (.319381530 + t * (-.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return String.format(java.util.Locale.getDefault(), "%.1f", 100 * (z < 0 ? tail : 1 - tail));
    }
}
