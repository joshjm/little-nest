package app.littlenest;

import org.json.JSONObject;
import org.junit.Test;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.*;

public class GrowthStandardsTest {
    private GrowthStandards standards() throws IOException {
        return new GrowthStandards(new FileReader("src/main/res/raw/who_growth.csv"));
    }
    private JSONObject event(String measure, String value, String unit, LocalDate date) {
        JSONObject event = new JSONObject();
        Records.put(event, "kind", "growth"); Records.put(event, measure, value);
        Records.put(event, measure.equals("weight") ? "weightUnit" : measure.equals("head") ? "headUnit" : "lengthUnit", unit);
        Records.put(event, "start", date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return event;
    }
    @Test public void matchesPublishedBirthMediansForBothSexesAndAllMeasurements() throws Exception {
        GrowthStandards data = standards();
        assertEquals(3.3464, data.at("weight", "boys", 0).value(0), 1e-8);
        assertEquals(3.2322, data.at("weight", "girls", 0).value(0), 1e-8);
        assertEquals(49.8842, data.at("height", "boys", 0).value(0), 1e-8);
        assertEquals(49.1477, data.at("height", "girls", 0).value(0), 1e-8);
        assertEquals(34.4618, data.at("head", "boys", 0).value(0), 1e-8);
        assertEquals(33.8787, data.at("head", "girls", 0).value(0), 1e-8);
    }
    @Test public void matchesPublishedMonthlyAndWeeklyValuesAndInterpolatesLms() throws Exception {
        GrowthStandards data = standards();
        assertEquals(3.4879, data.at("weight", "boys", 7).m(), 1e-8);
        assertEquals((3.4879 + 3.7529) / 2, data.at("weight", "boys", 10.5).m(), 1e-8);
        assertEquals(7.934, data.at("weight", "boys", 6 * GrowthStandards.DAYS_PER_MONTH).m(), 1e-8);
        assertEquals(44.8965, data.at("head", "girls", 12 * GrowthStandards.DAYS_PER_MONTH).m(), 1e-8);
        assertEquals(18.2193, data.at("weight", "girls", 60 * GrowthStandards.DAYS_PER_MONTH).m(), 1e-8);
        var birth = data.at("weight", "boys", 0);
        assertEquals(2.5, Math.round(birth.value(GrowthStandards.Z[0]) * 10) / 10.0, 1e-8);
        assertEquals(4.3, Math.round(birth.value(GrowthStandards.Z[4]) * 10) / 10.0, 1e-8);
    }
    @Test public void heightSwitchesFromLengthToStandingAt24MonthsWithoutBlendingTables() throws Exception {
        GrowthStandards data = standards(); double boundary = 24 * GrowthStandards.DAYS_PER_MONTH;
        assertEquals(87.8161, data.at("height", "boys", boundary - 1e-7).m(), 1e-5);
        assertEquals(87.1161, data.at("height", "boys", boundary).m(), 1e-8);
        assertEquals(85.7153, data.at("height", "girls", boundary).m(), 1e-8);
    }
    @Test public void lmsForwardAndInverseRoundTripIncludingZeroPower() {
        for (double power : new double[]{0, 1, -.3, .35}) {
            var lms = new GrowthStandards.Lms(0, power, 7, .13);
            for (double z : GrowthStandards.Z) assertEquals(z, lms.z(lms.value(z)), 1e-10);
        }
    }
    @Test public void everySupportedDayHasFiniteOrderedPercentilesForAllCharts() throws Exception {
        GrowthStandards data = standards();
        for (String sex : List.of("boys", "girls")) for (String measure : List.of("weight", "height", "head"))
            for (int day = 0; day <= 1826; day++) {
                var lms = data.at(measure, sex, day); assertNotNull(lms);
                double previous = 0;
                for (double z : GrowthStandards.Z) {
                    double value = lms.value(z); assertTrue(Double.isFinite(value)); assertTrue(value > previous);
                    assertEquals(z, lms.z(value), 1e-8); previous = value;
                }
            }
    }
    @Test public void unsupportedAgesAndSexesDoNotExtrapolateOrGuess() throws Exception {
        GrowthStandards data = standards();
        assertNull(data.at("weight", "boys", -1));
        assertNull(data.at("weight", "boys", 60 * GrowthStandards.DAYS_PER_MONTH + .001));
        assertNull(data.at("weight", "", 60));
        assertNull(data.at("weight", "Other", 60));
        assertNull(data.at("weight", "boys", Double.NaN));
        JSONObject profile = new JSONObject(); Records.put(profile, "sex", "Other");
        assertEquals("", GrowthStandards.sex(profile));
        Records.put(profile, "sex", "Female"); assertEquals("girls", GrowthStandards.sex(profile));
    }
    @Test public void datesUseCalendarDaysAcrossLeapDayAndAdjustedDate() {
        JSONObject profile = new JSONObject(); Records.put(profile, "dob", "2024-02-28"); Records.put(profile, "adjustedDob", "2024-03-28");
        assertEquals(2, GrowthStandards.days(GrowthStandards.birth(profile, false), LocalDate.parse("2024-03-01")), 0);
        assertEquals(-27, GrowthStandards.days(GrowthStandards.birth(profile, true), LocalDate.parse("2024-03-01")), 0);
        assertNull(GrowthStandards.birth(new JSONObject(), false));
    }
    @Test public void mixedUnitsIndependentHeadUnitsAndAllHistoricalSamplesAreRetained() {
        LocalDate date = LocalDate.parse("2025-01-01");
        var metric = event("weight", "1", "kg", date);
        var pounds = event("weight", "2.20462262185", "lb", date.plusDays(30));
        var values = GrowthStandards.samples(List.of(pounds, metric), "weight");
        assertEquals(date, values.get(0).date()); assertEquals(1, values.get(1).value(), 1e-9);
        var head = event("head", "14", "in", date); Records.put(head, "lengthUnit", "cm");
        assertEquals(35.56, GrowthStandards.samples(List.of(head), "head").get(0).value(), 1e-9);
        head.remove("headUnit"); Records.put(head, "lengthUnit", "in");
        assertEquals(35.56, GrowthStandards.samples(List.of(head), "head").get(0).value(), 1e-9);
        assertEquals(1, GrowthStandards.display(.45359237, "weight", true), 1e-9);
        assertEquals(1, GrowthStandards.display(2.54, "height", true), 1e-9);
        var history = new java.util.ArrayList<JSONObject>();
        for (int i = 0; i < 20; i++) history.add(event("height", "50", "cm", date.plusDays(i)));
        assertEquals(20, GrowthStandards.samples(history, "height").size());
    }
    @Test public void percentilesMatchKnownNormalProbabilitiesAndBoundExtremeValues() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertEquals("50.0", GrowthStandards.percentile(0));
            assertEquals("3.0", GrowthStandards.percentile(GrowthStandards.Z[0]));
            assertEquals("15.0", GrowthStandards.percentile(GrowthStandards.Z[1]));
            assertEquals("85.0", GrowthStandards.percentile(GrowthStandards.Z[3]));
            assertEquals("97.0", GrowthStandards.percentile(GrowthStandards.Z[4]));
            assertEquals("below 0.1", GrowthStandards.percentile(-4));
            assertEquals("above 99.9", GrowthStandards.percentile(4));
        } finally { Locale.setDefault(before); }
    }
    @Test public void invalidMeasurementsAreExplicitErrors() {
        for (String value : List.of("0", "-1", "NaN", "Infinity", "not a number")) {
            assertThrows(IllegalArgumentException.class, () -> GrowthStandards.samples(
                List.of(event("weight", value, "kg", LocalDate.now())), "weight"));
        }
    }
    @Test public void incompleteOrInvalidDataFailsExplicitly() {
        assertThrows(IOException.class, () -> new GrowthStandards(new StringReader("bad")));
        assertThrows(IOException.class, () -> new GrowthStandards(new StringReader("measure,sex,series,age,L,M,S\n")));
    }
}
