package app.littlenest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class SleepTimelineView extends View {
    private final List<JSONObject> events;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public SleepTimelineView(Context context) { this(context, List.of()); }
    SleepTimelineView(Context context, List<JSONObject> events) {
        super(context); this.events = events;
        setContentDescription("Sleep sessions for the last seven days, midnight to midnight. Daily totals are in the comparison table.");
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float text = Ui.sp(getContext(), 11);
        paint.setTextSize(text); paint.setColor(Ui.MUTED); paint.setFontFeatureSettings("tnum");
        float left = paint.measureText("00/00") + Ui.dp(getContext(), 10), top = text + Ui.dp(getContext(), 12);
        float width = getWidth() - left - Ui.dp(getContext(), 2), row = (getHeight() - top) / 7;
        for (int hour : new int[]{0, 6, 12, 18, 24}) {
            paint.setTextAlign(hour == 0 ? Paint.Align.LEFT : hour == 24 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(Integer.toString(hour), left + width * hour / 24, text + Ui.dp(getContext(), 2), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
        ZoneId zone = ZoneId.systemDefault(); long now = System.currentTimeMillis();
        for (int i = 0; i < 7; i++) {
            LocalDate day = LocalDate.now().minusDays(6 - i);
            long start = day.atStartOfDay(zone).toInstant().toEpochMilli(), end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            float y = top + i * row;
            paint.setColor(Ui.MUTED); canvas.drawText(day.format(DateTimeFormatter.ofPattern("d/M")), 0, y + row * .65f, paint);
            float radius = Ui.dp(getContext(), 6);
            paint.setColor(Ui.BLUE); canvas.drawRoundRect(left, y + row * .2f, left + width, y + row * .8f, radius, radius, paint);
            paint.setColor(Ui.SLEEP);
            for (JSONObject event : events) {
                if (!List.of("sleep", "parentSleep").contains(event.optString("kind"))) continue;
                for (long[] interval : Insights.sleepIntervals(event, now)) {
                    if (Records.overlap(interval[0], interval[1], start, end) <= 0) continue;
                    float a = left + width * (Math.max(start, interval[0]) - start) / (end - start);
                    float b = left + width * (Math.min(end, interval[1]) - start) / (end - start);
                    canvas.drawRoundRect(a, y + row * .2f, b, y + row * .8f, radius, radius, paint);
                }
            }
        }
    }
}
