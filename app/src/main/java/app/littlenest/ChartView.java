package app.littlenest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.util.List;
import java.util.Locale;

final class ChartView extends View {
    record Point(String label, double value) {}
    private final List<Point> points;
    private final boolean connected;
    private final int color;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public ChartView(Context context) { this(context, List.of()); }
    ChartView(Context context, List<Point> points) {
        this(context, points, false, Ui.GREEN);
    }
    ChartView(Context context, List<Point> points, boolean connected, int color) {
        super(context); this.points = points; this.connected = connected; this.color = color;
        setMinimumHeight(Ui.dp(context, 170));
        setContentDescription(points.stream().map(p -> p.label() + ": " + String.format(Locale.getDefault(), "%.1f", p.value())).collect(java.util.stream.Collectors.joining(", ")));
    }
    private static String label(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : String.format(Locale.getDefault(), "%.1f", value);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) return;
        float text = Ui.sp(getContext(), 11);
        float pad = Ui.dp(getContext(), 22), bottom = getHeight() - text - Ui.dp(getContext(), 14), height = bottom - pad;
        double max = points.stream().mapToDouble(Point::value).max().orElse(1); if (max <= 0) max = 1;
        float column = (getWidth() - pad * 2) / points.size();
        paint.setTextSize(text); paint.setFontFeatureSettings("tnum");
        paint.setColor(Ui.LINE); paint.setStrokeWidth(Ui.dp(getContext(), 1));
        canvas.drawLine(pad, bottom, getWidth() - pad, bottom, paint);
        float previousX = 0, previousY = 0;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i); float x = pad + i * column;
            float top = bottom - (float) (point.value() / max * (height - text - Ui.dp(getContext(), 8)));
            paint.setColor(color);
            if (connected) {
                float center = x + column / 2;
                paint.setStrokeWidth(Ui.dp(getContext(), 2));
                if (i > 0) canvas.drawLine(previousX, previousY, center, top, paint);
                canvas.drawCircle(center, top, Ui.dp(getContext(), 4), paint);
                previousX = center; previousY = top;
            } else canvas.drawRoundRect(x + column * .22f, top, x + column * .78f, bottom, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Ui.MUTED);
            canvas.drawText(point.label(), x + column / 2, bottom + text + Ui.dp(getContext(), 6), paint);
            paint.setColor(Ui.INK);
            canvas.drawText(label(point.value()), x + column / 2, top - Ui.dp(getContext(), 6), paint);
        }
    }
}
