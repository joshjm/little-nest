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
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public ChartView(Context context) { this(context, List.of()); }
    ChartView(Context context, List<Point> points) {
        this(context, points, false);
    }
    ChartView(Context context, List<Point> points, boolean connected) {
        super(context); this.points = points; this.connected = connected;
        setMinimumHeight(Ui.dp(context, 170));
        setContentDescription(points.stream().map(p -> p.label() + ": " + String.format(Locale.getDefault(), "%.1f", p.value())).collect(java.util.stream.Collectors.joining(", ")));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) return;
        float pad = Ui.dp(getContext(), 22), bottom = getHeight() - Ui.dp(getContext(), 28), height = bottom - pad;
        double max = points.stream().mapToDouble(Point::value).max().orElse(1); if (max <= 0) max = 1;
        float column = (getWidth() - pad * 2) / points.size();
        paint.setColor(Ui.MUTED); paint.setTextSize(Ui.dp(getContext(), 10));
        canvas.drawLine(pad, bottom, getWidth() - pad, bottom, paint);
        float previousX = 0, previousY = 0;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i); float x = pad + i * column;
            float top = bottom - (float) (point.value() / max * (height - Ui.dp(getContext(), 18)));
            paint.setColor(Ui.GREEN);
            if (connected) {
                float center = x + column / 2;
                paint.setStrokeWidth(Ui.dp(getContext(), 1));
                if (i > 0) canvas.drawLine(previousX, previousY, center, top, paint);
                canvas.drawCircle(center, top, Ui.dp(getContext(), 3), paint);
                previousX = center; previousY = top;
            } else canvas.drawRoundRect(x + column * .2f, top, x + column * .8f, bottom, Ui.dp(getContext(), 3), Ui.dp(getContext(), 3), paint);
            paint.setColor(Ui.MUTED); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(point.label(), x + column / 2, bottom + Ui.dp(getContext(), 18), paint);
            canvas.drawText(String.format(Locale.getDefault(), "%.1f", point.value()), x + column / 2, top - Ui.dp(getContext(), 6), paint);
        }
    }
}
