package app.littlenest;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Original nursery illustrations, drawn locally at any screen density. */
final class NestScene extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final String scene;
    private final boolean acknowledgeCare;
    private ValueAnimator animator;
    private float flutter;

    NestScene(Context context, String scene, boolean acknowledgeCare) {
        super(context);
        this.scene = scene;
        this.acknowledgeCare = acknowledgeCare;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!acknowledgeCare || !ValueAnimator.areAnimatorsEnabled()) return;
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(700);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> {
            flutter = (float) Math.sin((float) value.getAnimatedValue() * Math.PI);
            invalidate();
        });
        animator.start();
    }

    private void stopMotion() {
        if (animator != null) { animator.cancel(); animator = null; }
        flutter = 0;
    }

    @Override protected void onDetachedFromWindow() {
        stopMotion();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) { stopMotion(); invalidate(); }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 144f, getHeight() / 100f);
        canvas.save();
        canvas.translate((getWidth() - 144 * scale) / 2, (getHeight() - 100 * scale) / 2);
        canvas.scale(scale, scale);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        fill(Ui.SAGE); canvas.drawOval(10, 17, 134, 96, paint);
        if (scene.equals("rhythm") || Ui.night && !scene.equals("family")) {
            fill(Ui.SLEEP); canvas.drawCircle(109, 25, 13, paint);
            fill(Ui.SAGE); canvas.drawCircle(115, 20, 12, paint);
            stroke(Ui.NAPPY, 2); star(canvas, 26, 28, 4); star(canvas, 126, 47, 3);
        } else {
            fill(Ui.SAND); canvas.drawCircle(110, 23, 15, paint);
            stroke(Ui.NAPPY, 1.8f);
            for (int i = 0; i < 6; i++) {
                canvas.save(); canvas.rotate(i * 60, 110, 23);
                canvas.drawLine(110, 3, 110, 0, paint); canvas.restore();
            }
        }
        branch(canvas, false); branch(canvas, true);
        if (scene.equals("family")) {
            bird(canvas, 49, 43, Ui.HEALTH, false, false);
            canvas.save(); canvas.translate(111, 0); canvas.scale(-1, 1);
            bird(canvas, 14, 41, Ui.FEED, false, false);
            canvas.restore();
            bird(canvas, 72, 58 - flutter * 4, Ui.GREEN, true, false);
        } else {
            bird(canvas, 70, 52 - flutter * 5, Ui.GREEN, true, scene.equals("rhythm") || Ui.night);
        }
        fill(Ui.PEACH); canvas.drawArc(33, 51, 112, 96, 0, 180, false, paint);
        stroke(Ui.FEED, 2.5f);
        canvas.drawArc(31, 49, 114, 88, 10, 160, false, paint);
        canvas.drawArc(36, 55, 109, 94, 8, 164, false, paint);
        canvas.drawArc(43, 64, 102, 96, 5, 170, false, paint);
        canvas.drawLine(39, 72, 56, 80, paint); canvas.drawLine(104, 73, 88, 84, paint);
        if (flutter > 0) {
            canvas.save(); canvas.translate(0, -flutter * 10);
            stroke(Ui.MEMORY, 2);
            path.reset(); path.moveTo(73, 29);
            path.cubicTo(57, 20, 65, 11, 73, 18);
            path.cubicTo(81, 11, 89, 20, 73, 29);
            canvas.drawPath(path, paint); canvas.restore();
        }
        canvas.restore();
    }

    private void bird(Canvas canvas, float x, float y, int color, boolean small, boolean asleep) {
        canvas.save(); canvas.translate(x, y);
        float size = small ? .8f : 1;
        canvas.scale(size, size);
        fill(color); canvas.drawOval(-17, -18, 17, 19, paint);
        stroke(Ui.ON_GREEN, 2);
        canvas.drawArc(-11, 0 - flutter * 8, 9, 15 - flutter * 8, 15, 140, false, paint);
        fill(Ui.NAPPY);
        path.reset(); path.moveTo(13, -6); path.lineTo(23, -2); path.lineTo(13, 2); path.close();
        canvas.drawPath(path, paint);
        if (asleep) {
            stroke(Ui.BG, 2); canvas.drawArc(3, -10, 10, -4, 0, 180, false, paint);
        } else {
            fill(Ui.BG); canvas.drawCircle(7, -8, 2, paint);
        }
        canvas.restore();
    }

    private void branch(Canvas canvas, boolean mirror) {
        canvas.save();
        if (mirror) { canvas.translate(144, 0); canvas.scale(-1, 1); }
        stroke(Ui.HEALTH, 1.8f);
        path.reset(); path.moveTo(42, 87); path.quadTo(20, 69, 17, 47); canvas.drawPath(path, paint);
        fill(Ui.HEALTH);
        canvas.save(); canvas.rotate(-35, 22, 64); canvas.drawOval(17, 53, 23, 66, paint); canvas.restore();
        canvas.save(); canvas.rotate(45, 27, 73); canvas.drawOval(23, 60, 29, 75, paint); canvas.restore();
        canvas.restore();
    }

    private void star(Canvas canvas, float x, float y, float r) {
        canvas.drawLine(x - r, y, x + r, y, paint);
        canvas.drawLine(x, y - r, x, y + r, paint);
    }
    private void fill(int color) { paint.setStyle(Paint.Style.FILL); paint.setColor(color); }
    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(width);
    }
}
