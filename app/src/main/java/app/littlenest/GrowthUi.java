package app.littlenest;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

final class GrowthUi {
    static void add(Activity activity, LinearLayout parent, JSONObject profile, List<JSONObject> events,
            boolean imperial, Runnable editProfile) {
        parent.addView(Ui.title(activity, "Growing together", 24));
        if (profile == null || !profile.optString("role").equals("Baby")) {
            parent.addView(Ui.text(activity, "Growth percentiles are available for baby profiles.", 14, Ui.MUTED)); return;
        }
        GrowthStandards standards;
        try {
            standards = new GrowthStandards(new InputStreamReader(activity.getResources().openRawResource(R.raw.who_growth), StandardCharsets.UTF_8));
        } catch (IOException e) {
            android.util.Log.e("GrowthUi", "Could not read bundled WHO standards", e);
            parent.addView(Ui.text(activity, "Growth charts could not load. Restart the app or reinstall the latest update.", 14, Ui.INK)); return;
        }
        String sex = GrowthStandards.sex(profile);
        LocalDate birth = GrowthStandards.birth(profile, false), adjusted = GrowthStandards.birth(profile, true);
        parent.addView(Ui.text(activity, "WHO Child Growth Standards / birth to 5 years\n"
            + "Percentiles compare children of the same age and recorded sex, not a group selected by birth weight.", 14, Ui.MUTED));
        if (birth == null || sex.isEmpty()) {
            parent.addView(Ui.text(activity, "Your entries are shown without reference curves. Add a birth date and select Female or Male in the profile to use the corresponding WHO chart. Sex is never guessed.", 14, Ui.MUTED));
            parent.addView(Ui.button(activity, "Edit growth profile", editProfile));
        }
        LinearLayout charts = Ui.column(activity);
        if (birth != null && adjusted != null && adjusted.isAfter(birth)) {
            boolean[] corrected = {false};
            android.widget.Button toggle = Ui.button(activity, "Use adjusted age (saved due date)", () -> {});
            toggle.setOnClickListener(view -> {
                corrected[0] = !corrected[0];
                toggle.setText(corrected[0] ? "Use actual age (birth date)" : "Use adjusted age (saved due date)");
                charts.removeAllViews();
                cards(activity, charts, standards, events, sex, corrected[0] ? adjusted : birth, imperial, corrected[0]);
            });
            parent.addView(toggle);
        }
        parent.addView(charts); cards(activity, charts, standards, events, sex, birth, imperial, false);
        parent.addView(Ui.text(activity, "These are reference curves, not targets or a diagnosis. Babies can change percentiles; birth weight does not fix a future path. Discuss growth concerns with your child's clinician.\n"
            + "Length assumes lying-down measurement before 24 months; height assumes standing from 24 months. The reference changes at that boundary. Entries are not converted between measurement positions.\n"
            + "For premature babies, ask your clinician which chart and age to use. The optional adjusted-age view uses your saved due date; measurements before that date have no WHO percentile. These are not preterm growth charts.\n"
            + "Percentiles are approximate: WHO weekly LMS tables through 13 weeks, then monthly tables, interpolated between published ages (one month = 30.4375 days). No reference is extrapolated beyond 60 months. Local guidance may use a different chart after age two.", 12, Ui.MUTED));
        parent.addView(Ui.button(activity, "About the WHO growth standards", () -> activity.startActivity(
            new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.who.int/tools/child-growth-standards")))));
    }

    private static void cards(Activity activity, LinearLayout parent, GrowthStandards standards, List<JSONObject> events,
            String sex, LocalDate birth, boolean imperial, boolean adjusted) {
        parent.addView(Ui.text(activity, (adjusted ? "Adjusted age / saved due date" : "Actual age / birth date")
            + (birth == null ? " not recorded" : ": " + birth) + (sex.isEmpty() ? "" : " / WHO " + sex), 13, Ui.MUTED));
        for (String measure : List.of("weight", "height", "head")) {
            String title = measure.equals("weight") ? "Weight" : measure.equals("height") ? "Length / height" : "Head circumference";
            String unit = measure.equals("weight") ? imperial ? "lb" : "kg" : imperial ? "in" : "cm";
            LinearLayout card = Ui.card(activity, android.graphics.Color.WHITE);
            card.addView(Ui.title(activity, title + " / " + unit, 18)); parent.addView(card);
            List<GrowthStandards.Sample> samples;
            try { samples = GrowthStandards.samples(events, measure); }
            catch (IllegalArgumentException e) {
                card.addView(Ui.text(activity, "A recorded measurement is zero or invalid. Edit it in History to display this chart.", 14, Ui.INK)); continue;
            }
            if (samples.isEmpty()) {
                card.addView(Ui.text(activity, "No measurements yet. Log " + title.toLowerCase(Locale.ROOT) + " in Growth on Today.", 14, Ui.MUTED)); continue;
            }
            var latest = samples.get(samples.size() - 1);
            card.addView(Ui.text(activity, describe(standards, latest, measure, sex, birth, imperial, unit), 15, Ui.INK));
            GrowthChartView chart = new GrowthChartView(activity, samples, standards, measure, sex, birth, imperial);
            card.addView(chart, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 250)));
            card.addView(Ui.text(activity, "Solid green / your measurements"
                + (chart.hasReference() ? "\nGrey / WHO percentiles 3, 15, 50, 85, 97" : "\nRecorded measurements only / no supported WHO reference.")
                + (chart.hasAnchor() ? "\nDashed green / first entry's percentile continued for comparison, not a prediction" :
                    chart.hasReference() ? "\nNo first-entry guide when its age is unsupported or its z-score is outside -3 to +3." : ""), 12, Ui.MUTED));
            card.addView(Ui.button(activity, "View " + samples.size() + " measurements", () -> {
                LinearLayout list = Ui.column(activity); int p = Ui.dp(activity, 20); list.setPadding(p, p, p, p);
                for (var sample : samples) list.addView(Ui.text(activity,
                    describe(standards, sample, measure, sex, birth, imperial, unit), 14, Ui.INK));
                ScrollView scroll = new ScrollView(activity); scroll.addView(list);
                new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("Close", null).show();
            }));
        }
    }
    private static String describe(GrowthStandards standards, GrowthStandards.Sample sample, String measure,
            String sex, LocalDate birth, boolean imperial, String unit) {
        var lms = birth == null ? null : standards.at(measure, sex, GrowthStandards.days(birth, sample.date()));
        return sample.date() + " / " + String.format(Locale.getDefault(), "%.2f", GrowthStandards.display(sample.value(), measure, imperial)) + " " + unit
            + (lms == null ? "\nNo WHO percentile for this age or profile." : "\nApprox. percentile " + GrowthStandards.percentile(lms.z(sample.value())));
    }
    private GrowthUi() {}
}
