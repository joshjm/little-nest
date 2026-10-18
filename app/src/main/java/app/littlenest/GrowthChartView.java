package app.littlenest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@android.annotation.SuppressLint("ViewConstructor")
final class GrowthChartView extends View {
    private final List<GrowthStandards.Sample> samples;
    private final GrowthStandards standards;
    private final String measure, sex;
    private final LocalDate birth;
    private final boolean imperial;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curvePath = new Path();
    private final DashPathEffect dashed;
    private final double from, to, anchorZ;
    private double min, max;
    private float left, right, top, bottom;

    GrowthChartView(Context context, List<GrowthStandards.Sample> samples, GrowthStandards standards,
            String measure, String sex, LocalDate birth, boolean imperial) {
        super(context);
        this.samples = samples; this.standards = standards; this.measure = measure;
        this.sex = sex; this.birth = birth; this.imperial = imperial;
        dashed = new DashPathEffect(new float[]{Ui.dp(context, 6), Ui.dp(context, 4)}, 0);
        from = samples.get(0).date().toEpochDay();
        to = Math.max(from + 30, samples.get(samples.size() - 1).date().toEpochDay());
        var first = samples.get(0);
        var lms = reference(first.date().toEpochDay());
        double z = lms == null ? Double.NaN : lms.z(first.value());
        anchorZ = Math.abs(z) <= 3 ? z : Double.NaN;
        min = samples.stream().mapToDouble(s -> value(s.value())).min().orElseThrow();
        max = samples.stream().mapToDouble(s -> value(s.value())).max().orElseThrow();
        for (int i = 0; i <= 180; i++) {
            lms = reference(from + (to - from) * i / 180);
            if (lms == null) continue;
            min = Math.min(min, value(lms.value(GrowthStandards.Z[0])));
            max = Math.max(max, value(lms.value(GrowthStandards.Z[4])));
            if (Double.isFinite(anchorZ)) {
                min = Math.min(min, value(lms.value(anchorZ)));
                max = Math.max(max, value(lms.value(anchorZ)));
            }
        }
        double margin = Math.max((max - min) * .1, .1); min = Math.max(0, min - margin); max += margin;
        String title = measure.equals("weight") ? "Weight" : measure.equals("height") ? "Length / height" : "Head circumference";
        String unit = measure.equals("weight") ? imperial ? "lb" : "kg" : imperial ? "in" : "cm";
        setContentDescription(title + " growth chart in " + unit + ". " + (!hasReference() ? "Recorded measurements only. " :
            "WHO percentiles 3, 15, 50, 85, 97. " + (Double.isFinite(anchorZ) ? "Dashed line follows the first measurement's percentile, not a prediction. " : ""))
            + samples.stream().map(s -> s.date() + ": " + String.format(Locale.getDefault(), "%.1f", value(s.value())))
                .collect(java.util.stream.Collectors.joining("; ")));
    }

    boolean hasAnchor() { return Double.isFinite(anchorZ); }
    boolean hasReference() {
        return birth != null && !sex.isEmpty() && to >= birth.toEpochDay()
            && from <= birth.toEpochDay() + 60 * GrowthStandards.DAYS_PER_MONTH;
    }
    private double value(double metric) { return GrowthStandards.display(metric, measure, imperial); }
    private GrowthStandards.Lms reference(double epochDay) {
        return birth == null || sex.isEmpty() ? null : standards.at(measure, sex, epochDay - birth.toEpochDay());
    }
    private float x(double day) { return left + (float) ((day - from) / (to - from)) * (right - left); }
    private float y(double v) { return bottom - (float) ((v - min) / (max - min)) * (bottom - top); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float text = 11 * getResources().getDisplayMetrics().scaledDensity;
        paint.setTextSize(text);
        left = Math.max(Ui.dp(getContext(), 40), paint.measureText(String.format(Locale.getDefault(), "%.1f", max)) + Ui.dp(getContext(), 8));
        right = getWidth() - Math.max(Ui.dp(getContext(), 30), paint.measureText("97") + Ui.dp(getContext(), 8));
        top = text; bottom = getHeight() - text * 2.8f;
        if (right <= left || bottom <= top) return;
        paint.setPathEffect(null); paint.setStrokeWidth(Ui.dp(getContext(), 1));
        for (int i = 0; i <= 4; i++) {
            double v = min + (max - min) * i / 4;
            paint.setColor(Ui.LINE); canvas.drawLine(left, y(v), right, y(v), paint);
            paint.setColor(Ui.MUTED); paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format(Locale.getDefault(), "%.1f", v), left - Ui.dp(getContext(), 5), y(v) + text / 3, paint);
        }
        for (int c = 0; c < GrowthStandards.Z.length; c++) {
            paint.setColor(Ui.MUTED); paint.setStrokeWidth(Ui.dp(getContext(), c == 2 ? 1.5f : 1));
            curve(canvas, GrowthStandards.Z[c]);
            var end = reference(to);
            if (end != null) {
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(GrowthStandards.CENTILES[c], right + Ui.dp(getContext(), 4), y(value(end.value(GrowthStandards.Z[c]))) + text / 3, paint);
            }
        }
        if (Double.isFinite(anchorZ)) {
            paint.setColor(Ui.GREEN); paint.setStrokeWidth(Ui.dp(getContext(), 2)); paint.setPathEffect(dashed);
            curve(canvas, anchorZ); paint.setPathEffect(null);
        }
        paint.setColor(Ui.GREEN); paint.setStrokeWidth(Ui.dp(getContext(), 2.5f));
        for (int i = 0; i < samples.size(); i++) {
            var sample = samples.get(i);
            if (i > 0) {
                var previous = samples.get(i - 1);
                canvas.drawLine(x(previous.date().toEpochDay()), y(value(previous.value())), x(sample.date().toEpochDay()), y(value(sample.value())), paint);
            }
            canvas.drawCircle(x(sample.date().toEpochDay()), y(value(sample.value())), Ui.dp(getContext(), 3.5f), paint);
        }
        paint.setColor(Ui.MUTED);
        for (int i = 0; i < 3; i++) {
            double day = from + (to - from) * i / 2;
            paint.setTextAlign(i == 0 ? Paint.Align.LEFT : i == 2 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(LocalDate.ofEpochDay(Math.round(day)).format(DateTimeFormatter.ofPattern(to - from >= 365 ? "MMM yy" : "d MMM")),
                x(day), bottom + text * 1.7f, paint);
        }
    }
    private void curve(Canvas canvas, double z) {
        curvePath.rewind();
        double previousAge = Double.NaN;
        for (int i = 0; i <= 180; i++) {
            double day = from + (to - from) * i / 180;
            var lms = reference(day);
            if (lms == null) { previousAge = Double.NaN; continue; }
            double age = day - birth.toEpochDay();
            float cx = x(day), cy = y(value(lms.value(z)));
            // Length and standing height have separate standards at 24 months.
            if (Double.isFinite(previousAge) && !(measure.equals("height") && previousAge < 24 * GrowthStandards.DAYS_PER_MONTH
                    && age >= 24 * GrowthStandards.DAYS_PER_MONTH))
                curvePath.lineTo(cx, cy);
            else curvePath.moveTo(cx, cy);
            previousAge = age;
        }
        paint.setStyle(Paint.Style.STROKE); canvas.drawPath(curvePath, paint); paint.setStyle(Paint.Style.FILL);
    }
}
