package app.littlenest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The woven day: the last 18 hours as solid threads and the next 6 hours with planned
 * windows stitched as dashed outlines. Lanes: sleep, feeds and pumping, then everything else as knots.
 */
final class DayWeaveView extends View {
    static final long PAST = 18 * 3_600_000L, FUTURE = 6 * 3_600_000L;
    private final List<JSONObject> events;
    private final List<CarePredictor.Window> plans;
    private final long now;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    DayWeaveView(Context context, List<JSONObject> events, List<CarePredictor.Window> plans, long now) {
        super(context); this.events = events; this.plans = plans; this.now = now;
        setMinimumHeight(Ui.dp(context, 112));
        int sleeps = 0, feeds = 0, other = 0;
        for (JSONObject event : visible(events, now)) {
            switch (lane(event.optString("kind"))) { case 0 -> sleeps++; case 1 -> feeds++; default -> other++; }
        }
        StringBuilder description = new StringBuilder("Last 18 hours: " + sleeps + " sleep, " + feeds + " feed or pumping, " + other + " other logs.");
        for (CarePredictor.Window plan : plans) if (shown(plan, now))
            description.append(" Planned: ").append(plan.title()).append(" around ").append(EventEditor.format(plan.target())).append('.');
        setContentDescription(description);
    }

    /** Logged care that overlaps the drawn past, in lane order. */
    static List<JSONObject> visible(List<JSONObject> events, long now) {
        return events.stream().filter(e -> Catalog.KINDS.stream().anyMatch(k -> k.id().equals(e.optString("kind"))))
            .filter(e -> e.optLong("start") <= now && (e.optBoolean("running") ? now : Math.max(e.optLong("end"), e.optLong("start"))) >= now - PAST)
            .sorted(java.util.Comparator.comparingInt(e -> lane(e.optString("kind")))).toList();
    }
    private static boolean shown(CarePredictor.Window plan, long now) {
        return plan.available() && plan.latest() >= now && plan.earliest() <= now + FUTURE;
    }

    private static int lane(String kind) {
        return switch (kind) {
            case "sleep", "parentSleep" -> 0;
            case "breast", "bottle", "solids", "pump", "hydration", "meal" -> 1;
            default -> 2;
        };
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Context c = getContext();
        float left = Ui.dp(c, 2), right = getWidth() - Ui.dp(c, 2), width = right - left;
        float text = Ui.sp(c, 11), labels = text + Ui.dp(c, 7), laneHeight = (getHeight() - labels - Ui.dp(c, 6)) / 3f;
        long from = now - PAST, to = now + FUTURE;
        float thread = Ui.dp(c, 10);

        // Hour ticks every three clock hours.
        paint.setPathEffect(null); paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(text); paint.setTextAlign(Paint.Align.CENTER); paint.setFontFeatureSettings("tnum");
        ZonedDateTime tick = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).plusHours(1);
        while (tick.getHour() % 3 != 0) tick = tick.plusHours(1);
        for (; tick.toInstant().toEpochMilli() < to; tick = tick.plusHours(3)) {
            float x = left + width * (tick.toInstant().toEpochMilli() - from) / (float) (to - from);
            paint.setColor(Ui.LINE); paint.setStrokeWidth(Ui.dp(c, 1));
            canvas.drawLine(x, labels + Ui.dp(c, 2), x, getHeight(), paint);
            paint.setColor(Ui.MUTED);
            canvas.drawText(String.format(java.util.Locale.ROOT, "%02d", tick.getHour()), x, text + Ui.dp(c, 1), paint);
        }

        // Empty warp for each lane: solid in the past, stitched in the future.
        float nowX = left + width * PAST / (float) (to - from);
        for (int i = 0; i < 3; i++) {
            float y = labels + laneHeight * i + laneHeight / 2;
            paint.setColor(Ui.LINE); paint.setStrokeWidth(Ui.dp(c, 2)); paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(left, y, nowX, y, paint);
            paint.setPathEffect(new DashPathEffect(new float[]{Ui.dp(c, 4), Ui.dp(c, 4)}, 0));
            canvas.drawLine(nowX, y, right, y, paint); paint.setPathEffect(null);
        }

        // Planned windows, outlined in the thread they belong to.
        for (CarePredictor.Window plan : plans) {
            if (!shown(plan, now)) continue;
            int laneIndex = plan.type().equals("feed") ? 1 : 0;
            float a = left + width * (Math.max(plan.earliest(), from) - from) / (float) (to - from);
            float b = left + width * (Math.min(plan.latest(), to) - from) / (float) (to - from);
            float y = labels + laneHeight * laneIndex + laneHeight / 2;
            rect.set(a, y - thread / 2, Math.max(b, a + thread), y + thread / 2);
            paint.setColor(laneIndex == 1 ? Ui.FEED : Ui.SLEEP); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(c, 1.6f));
            paint.setPathEffect(new DashPathEffect(new float[]{Ui.dp(c, 3), Ui.dp(c, 3)}, 0));
            canvas.drawRoundRect(rect, thread / 2, thread / 2, paint);
            paint.setPathEffect(null); paint.setStyle(Paint.Style.FILL);
        }

        // Logged care.
        for (JSONObject event : events) {
            String kind = event.optString("kind");
            if (Catalog.KINDS.stream().noneMatch(k -> k.id().equals(kind))) continue;
            int laneIndex = lane(kind);
            paint.setColor(Ui.thread(Catalog.get(kind)));
            float y = labels + laneHeight * laneIndex + laneHeight / 2;
            List<long[]> spans = laneIndex == 0 ? Insights.sleepIntervals(event, now)
                : List.of(new long[]{event.optLong("start"), event.optBoolean("running") ? now : Math.max(event.optLong("end"), event.optLong("start"))});
            for (long[] span : spans) {
                if (span[1] < from || span[0] > now) continue;
                float a = left + width * (Math.max(span[0], from) - from) / (float) (to - from);
                float b = left + width * (Math.min(span[1], now) - from) / (float) (to - from);
                if (laneIndex == 2) { canvas.drawCircle(a, y, thread * .55f, paint); continue; }
                rect.set(a, y - thread / 2, Math.max(b, a + thread * .8f), y + thread / 2);
                canvas.drawRoundRect(rect, thread / 2, thread / 2, paint);
            }
        }

        // Now needle.
        paint.setColor(Ui.GREEN); paint.setStrokeWidth(Ui.dp(c, 2));
        canvas.drawLine(nowX, labels, nowX, getHeight(), paint);
        canvas.drawCircle(nowX, labels, Ui.dp(c, 3.5f), paint);
    }
}
