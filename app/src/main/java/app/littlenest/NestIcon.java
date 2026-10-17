package app.littlenest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Original geometric symbols drawn on a shared 24-unit grid. */
final class NestIcon extends Drawable {
    private final String name;
    private final int color, accent, size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    NestIcon(Context context, String name, int color, int accent, int dp) {
        this.name = name; this.color = color; this.accent = accent; size = Ui.dp(context, dp);
        setBounds(0, 0, size, size);
    }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void draw(Canvas canvas) {
        canvas.save(); canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        switch (name) {
            case "bottle", "hydration" -> {
                fill(canvas, 7, 10, 17, 21, 2);
                canvas.drawRoundRect(7, 7, 17, 22, 2, 2, paint);
                canvas.drawRect(8, 4, 16, 7, paint);
                canvas.drawArc(10, 1, 14, 6, 180, 180, false, paint);
                line(canvas, 13, 11, 17, 11); line(canvas, 14, 15, 17, 15);
            }
            case "sleep", "parentSleep" -> {
                path.moveTo(15, 3); path.cubicTo(1, 1, 0, 22, 16, 21);
                path.cubicTo(9, 18, 8, 8, 15, 3); canvas.drawPath(path, paint); path.reset();
                star(canvas, 18, 6, 2); star(canvas, 19, 15, 1);
            }
            case "pump" -> {
                fill(canvas, 10, 13, 20, 21, 1);
                canvas.drawRoundRect(10, 12, 20, 22, 1, 1, paint);
                line(canvas, 15, 7, 15, 12); line(canvas, 8, 3, 3, 15);
                line(canvas, 8, 3, 15, 7); line(canvas, 3, 15, 13, 11); line(canvas, 13, 11, 15, 7);
            }
            case "diaper" -> {
                fill(canvas, 4, 7, 20, 13, 1);
                path.moveTo(3, 5); path.lineTo(21, 5); path.lineTo(19, 16); path.quadTo(12, 24, 5, 16); path.close();
                canvas.drawPath(path, paint); path.reset();
                line(canvas, 3, 9, 7, 11); line(canvas, 21, 9, 17, 11); line(canvas, 7, 11, 7, 17); line(canvas, 17, 11, 17, 17);
            }
            case "growth", "vitals", "Trends" -> {
                line(canvas, 3, 3, 3, 21); line(canvas, 3, 21, 22, 21);
                path.moveTo(6, 17); path.lineTo(11, 11); path.lineTo(15, 14); path.lineTo(21, 5);
                canvas.drawPath(path, paint); path.reset(); line(canvas, 17, 5, 21, 5); line(canvas, 21, 5, 21, 9);
            }
            case "medical", "medicine", "vaccine", "temperature", "symptom" -> {
                fill(canvas, 3, 6, 21, 21, 2);
                canvas.drawRoundRect(3, 6, 21, 21, 2, 2, paint);
                canvas.drawRoundRect(8, 2, 16, 6, 1, 1, paint);
                line(canvas, 8, 13, 16, 13); line(canvas, 12, 9, 12, 17);
            }
            case "History", "appointment" -> {
                canvas.drawRoundRect(3, 5, 21, 22, 1, 1, paint);
                line(canvas, 3, 10, 21, 10); line(canvas, 8, 2, 8, 7); line(canvas, 16, 2, 16, 7);
                line(canvas, 7, 14, 10, 14); line(canvas, 14, 14, 17, 14); line(canvas, 7, 18, 10, 18);
            }
            case "Today" -> {
                canvas.drawCircle(12, 12, 4, paint);
                for (int i = 0; i < 8; i++) {
                    canvas.save(); canvas.rotate(i * 45, 12, 12); line(canvas, 12, 2, 12, 5); canvas.restore();
                }
            }
            case "profile", "Family" -> {
                if (name.equals("profile")) canvas.drawCircle(12, 12, 11, paint);
                else { canvas.drawCircle(7, 7, 3, paint); canvas.drawArc(1, 12, 13, 25, 180, 180, false, paint); }
                canvas.drawCircle(name.equals("profile") ? 12 : 17, name.equals("profile") ? 9 : 10, 3, paint);
                if (name.equals("profile")) canvas.drawArc(6, 11, 18, 21, 0, 180, false, paint);
                else canvas.drawArc(12, 15, 23, 26, 180, 180, false, paint);
            }
            case "story", "journal" -> {
                fill(canvas, 3, 4, 21, 20, 1);
                canvas.drawRoundRect(3, 3, 21, 21, 1, 1, paint); line(canvas, 7, 3, 7, 21);
                line(canvas, 11, 9, 17, 9); line(canvas, 11, 13, 17, 13);
            }
            default -> {
                fill(canvas, 5, 6, 19, 19, 6);
                path.moveTo(12, 21); path.cubicTo(-4, 11, 4, -2, 12, 6);
                path.cubicTo(20, -2, 28, 11, 12, 21); canvas.drawPath(path, paint); path.reset();
            }
        }
        canvas.restore();
    }
    private void line(Canvas canvas, float a, float b, float c, float d) { canvas.drawLine(a, b, c, d, paint); }
    private void fill(Canvas canvas, float left, float top, float right, float bottom, float radius) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(accent);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
    }
    private void star(Canvas canvas, float x, float y, float radius) {
        line(canvas, x - radius, y, x + radius, y); line(canvas, x, y - radius, x, y + radius);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
