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
        float left = Ui.dp(getContext(), 42), top = Ui.dp(getContext(), 26);
        float width = getWidth() - left - Ui.dp(getContext(), 5), row = (getHeight() - top) / 7;
        paint.setTextSize(Ui.dp(getContext(), 10)); paint.setColor(Ui.MUTED);
        for (int hour : new int[]{0, 6, 12, 18, 24})
            canvas.drawText(Integer.toString(hour), left + width * hour / 24 - Ui.dp(getContext(), 5), Ui.dp(getContext(), 14), paint);
        ZoneId zone = ZoneId.systemDefault(); long now = System.currentTimeMillis();
        for (int i = 0; i < 7; i++) {
            LocalDate day = LocalDate.now().minusDays(6 - i);
            long start = day.atStartOfDay(zone).toInstant().toEpochMilli(), end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            float y = top + i * row;
            paint.setColor(Ui.MUTED); canvas.drawText(day.format(DateTimeFormatter.ofPattern("d/M")), 0, y + row * .65f, paint);
            paint.setColor(0xFFF3F5F7); canvas.drawRect(left, y + row * .2f, left + width, y + row * .8f, paint);
            paint.setColor(Ui.GREEN);
            for (JSONObject event : events) {
                if (!List.of("sleep", "parentSleep").contains(event.optString("kind"))) continue;
                for (long[] interval : Insights.sleepIntervals(event, now)) {
                    if (Records.overlap(interval[0], interval[1], start, end) <= 0) continue;
                    float a = left + width * (Math.max(start, interval[0]) - start) / (end - start);
                    float b = left + width * (Math.min(end, interval[1]) - start) / (end - start);
                    canvas.drawRoundRect(a, y + row * .2f, b, y + row * .8f, Ui.dp(getContext(), 3), Ui.dp(getContext(), 3), paint);
                }
            }
        }
    }
}
