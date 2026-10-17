package app.littlenest;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Little Nest's "woven day" design system.
 * Each kind of care has one thread colour (see {@link #thread}). Threads mark care everywhere:
 * the day strip, list dots, chart bars and dialog titles. Paper by day, a dim nursery palette at night.
 * Colours are set from the system light/dark setting by {@link #apply} before every render.
 */
final class Ui {
    static boolean night;
    static int BG, SURFACE, INK, MUTED, GREEN, ON_GREEN, SAGE, PEACH, LAVENDER, BLUE, CORAL, SAND, LINE, RIPPLE, DANGER;
    static int FEED, SLEEP, PUMP, DIAPER, HEALTH, PARENT, MEMORY;
    static { set(false); }

    static void apply(Context context) {
        set((context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }
    private static void set(boolean dark) {
        night = dark;
        if (!dark) {
            BG = Color.rgb(247, 244, 236); SURFACE = Color.rgb(255, 253, 248);
            INK = Color.rgb(38, 53, 44); MUTED = Color.rgb(87, 99, 87);
            GREEN = Color.rgb(43, 91, 69); ON_GREEN = Color.WHITE;
            SAGE = Color.rgb(225, 235, 219); PEACH = Color.rgb(242, 226, 211); LAVENDER = Color.rgb(232, 226, 235);
            BLUE = Color.rgb(223, 232, 227); CORAL = Color.rgb(242, 224, 218); SAND = Color.rgb(238, 231, 211);
            LINE = Color.rgb(221, 224, 212); RIPPLE = 0x242B5B45; DANGER = Color.rgb(160, 52, 40);
            FEED = Color.rgb(196, 110, 52); SLEEP = Color.rgb(74, 101, 158); PUMP = Color.rgb(174, 82, 104);
            DIAPER = Color.rgb(166, 128, 38); HEALTH = Color.rgb(44, 124, 106); PARENT = Color.rgb(122, 92, 166);
            MEMORY = Color.rgb(150, 98, 140);
        } else {
            BG = Color.rgb(17, 22, 19); SURFACE = Color.rgb(26, 33, 29);
            INK = Color.rgb(232, 228, 216); MUTED = Color.rgb(168, 177, 164);
            GREEN = Color.rgb(150, 201, 167); ON_GREEN = Color.rgb(14, 36, 24);
            SAGE = Color.rgb(36, 51, 42); PEACH = Color.rgb(54, 41, 32); LAVENDER = Color.rgb(45, 40, 54);
            BLUE = Color.rgb(32, 45, 52); CORAL = Color.rgb(56, 37, 39); SAND = Color.rgb(51, 46, 33);
            LINE = Color.rgb(48, 58, 52); RIPPLE = 0x3396C9A7; DANGER = Color.rgb(240, 160, 148);
            FEED = Color.rgb(231, 160, 110); SLEEP = Color.rgb(147, 174, 219); PUMP = Color.rgb(226, 150, 168);
            DIAPER = Color.rgb(217, 188, 106); HEALTH = Color.rgb(127, 199, 180); PARENT = Color.rgb(187, 162, 222);
            MEMORY = Color.rgb(214, 160, 200);
        }
    }

    /** Soft background tint for a kind of care. */
    static int activityColor(Catalog.Kind kind) {
        return switch (kind.id()) {
            case "breast", "bottle", "solids" -> PEACH;
            case "sleep", "parentSleep" -> BLUE;
            case "pump" -> CORAL;
            case "growth" -> SAGE;
            case "diaper", "tummy", "bath", "story", "routine" -> SAND;
            default -> kind.group().equals("Parent") ? SAGE : LAVENDER;
        };
    }
    /** The thread colour for a kind of care, used for marks and graphics, not for body text. */
    static int thread(Catalog.Kind kind) {
        return switch (kind.id()) {
            case "breast", "bottle", "solids" -> FEED;
            case "sleep", "parentSleep" -> SLEEP;
            case "pump" -> PUMP;
            case "diaper", "tummy", "bath", "story", "routine" -> DIAPER;
            case "growth", "medicine", "vaccine", "temperature", "medical" -> HEALTH;
            case "appointment" -> GREEN;
            default -> kind.group().equals("Parent") ? PARENT : MEMORY;
        };
    }

    /** Legend name for a thread colour family; mirrors {@link #thread}. */
    static String threadLabel(Catalog.Kind kind) {
        return switch (kind.id()) {
            case "breast", "bottle", "solids" -> "Feeds";
            case "sleep", "parentSleep" -> "Sleep";
            case "pump" -> "Pumping";
            case "diaper", "tummy", "bath", "story", "routine" -> "Diapers & routines";
            case "growth", "medicine", "vaccine", "temperature", "medical" -> "Health";
            case "appointment" -> "Appointments";
            default -> kind.group().equals("Parent") ? "Parent care" : "Memories";
        };
    }

    static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    /** Text size in pixels for canvas drawing; follows the user's font-size setting. */
    @SuppressWarnings("deprecation")
    static float sp(Context context, float value) { return value * context.getResources().getDisplayMetrics().scaledDensity; }
    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout;
    }
    static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(context, radius)); return shape;
    }
    static RippleDrawable ripple(Context context, int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape(context, color, radius), shape(context, Color.BLACK, radius));
    }
    static TextView text(Context context, String value, int size, int color) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setPadding(0, dp(context, 4), 0, dp(context, 4)); text.setLineSpacing(0, 1.12f);
        return text;
    }
    static TextView title(Context context, String value, int size) {
        TextView text = text(context, value, size, INK); text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        if (size >= 22) text.setLetterSpacing(-0.01f);
        return text;
    }
    static TextView medium(Context context, String value, int size, int color) {
        TextView text = text(context, value, size, color); text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return text;
    }
    /** Tabular figures so times and amounts line up in columns. */
    static TextView figures(TextView text) { text.setFontFeatureSettings("tnum"); return text; }

    /** Tonal action: the default button. */
    static Button button(Context context, String label, Runnable action) {
        return pill(context, label, action, SAGE, GREEN);
    }
    /** Filled action for the one main step in a place. */
    static Button primary(Context context, String label, Runnable action) {
        return pill(context, label, action, GREEN, ON_GREEN);
    }
    private static Button pill(Context context, String label, Runnable action, int fill, int ink) {
        Button button = new Button(context); button.setText(label); button.setAllCaps(false); button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(ink); button.setMinHeight(dp(context, 48)); button.setMinimumHeight(dp(context, 48));
        button.setBackground(ripple(context, fill, 24));
        button.setStateListAnimator(null); button.setElevation(0);
        button.setPadding(dp(context, 18), dp(context, 6), dp(context, 18), dp(context, 6));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(context, 5), 0, dp(context, 5)); button.setLayoutParams(params);
        return button;
    }
    /** Text-only action, used beside headings and for low-emphasis steps. */
    static Button quiet(Context context, String label, Runnable action) {
        Button button = pill(context, label, action, Color.TRANSPARENT, GREEN);
        button.setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6));
        button.setLayoutParams(new LinearLayout.LayoutParams(-2, -2)); return button;
    }
    /** Small outlined selector, e.g. history dates. */
    static Button chip(Context context, String label, boolean selected, Runnable action) {
        Button chip = pill(context, label, action, selected ? SAGE : Color.TRANSPARENT, selected ? GREEN : INK);
        GradientDrawable outline = shape(context, selected ? SAGE : SURFACE, 12);
        outline.setStroke(dp(context, 1), selected ? SAGE : LINE);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), outline, null));
        chip.setTextSize(14); chip.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        chip.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(context, 48))); return chip;
    }
    /** A full-width settings row: label on the left, a chevron on the right. */
    static Button rowButton(Context context, String label, Runnable action) {
        Button row = pill(context, label, action, Color.TRANSPARENT, label.startsWith("Delete") || label.startsWith("Disconnect") ? DANGER : INK);
        row.setTypeface(Typeface.DEFAULT); row.setTextSize(16);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setBackground(ripple(context, Color.TRANSPARENT, 14));
        row.setMinHeight(dp(context, 52)); row.setMinimumHeight(dp(context, 52));
        row.setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8));
        row.setCompoundDrawables(null, null, new Chevron(context), null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, 0, 0, 0); row.setLayoutParams(params);
        return row;
    }
    /** Grouping surface. Hairline edge by day, a lifted tone by night. */
    static LinearLayout panel(Context context) {
        LinearLayout panel = column(context); int p = dp(context, 18); panel.setPadding(p, dp(context, 14), p, dp(context, 14));
        GradientDrawable background = shape(context, SURFACE, 24);
        if (!night) background.setStroke(dp(context, 1), LINE);
        panel.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(context, 6), 0, dp(context, 14)); panel.setLayoutParams(params);
        return panel;
    }
    static LinearLayout card(Context context, int color) {
        if (color == Color.WHITE || color == SURFACE) return panel(context);
        LinearLayout card = column(context); int p = dp(context, 18); card.setPadding(p, dp(context, 16), p, dp(context, 16));
        card.setBackground(shape(context, color, 24));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(context, 6), 0, dp(context, 14)); card.setLayoutParams(params);
        return card;
    }
    /** Section heading with more room above than below. */
    static TextView section(Context context, String label) {
        TextView title = title(context, label, 19);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(context, 18), 0, dp(context, 2));
        title.setLayoutParams(params); return title;
    }
    static View dot(Context context, int color, int size) {
        View dot = new View(context); GradientDrawable oval = new GradientDrawable(); oval.setShape(GradientDrawable.OVAL); oval.setColor(color);
        dot.setBackground(oval); dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, size), dp(context, size)); params.setMarginEnd(dp(context, 10));
        dot.setLayoutParams(params); return dot;
    }
    static EditText input(Context context, String label, String value, boolean number) {
        EditText edit = new EditText(context); edit.setHint(label); edit.setText(value); edit.setTextColor(INK);
        edit.setContentDescription(label);
        edit.setTextSize(16); edit.setSingleLine(true); edit.setMinHeight(dp(context, 52));
        edit.setHintTextColor(MUTED);
        GradientDrawable background = shape(context, night ? BG : SURFACE, 14);
        background.setStroke(dp(context, 1), night ? LINE : Color.rgb(200, 205, 191));
        edit.setBackground(background);
        edit.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(context, 3), 0, dp(context, 9)); edit.setLayoutParams(params);
        edit.setInputType(number ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return edit;
    }
    static void gap(LinearLayout layout, int height) {
        View gap = new View(layout.getContext()); layout.addView(gap, new LinearLayout.LayoutParams(1, dp(layout.getContext(), height)));
    }
    static void divider(LinearLayout layout) {
        View line = new View(layout.getContext()); line.setBackgroundColor(LINE);
        layout.addView(line, new LinearLayout.LayoutParams(-1, dp(layout.getContext(), 1)));
    }
    /** A dashed "stitch" between groups that are related but separate, such as days. */
    static void stitch(LinearLayout layout) {
        View stitch = new View(layout.getContext()) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                paint.setColor(LINE); paint.setStrokeWidth(dp(getContext(), 1.5f));
                paint.setPathEffect(new DashPathEffect(new float[]{dp(getContext(), 5), dp(getContext(), 4)}, 0));
                canvas.drawLine(0, getHeight() / 2f, getWidth(), getHeight() / 2f, paint);
            }
        };
        stitch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layout.addView(stitch, new LinearLayout.LayoutParams(-1, dp(layout.getContext(), 12)));
    }
    static LinearLayout dialogTitle(Context context, String label) { return dialogTitle(context, label, GREEN); }
    static LinearLayout dialogTitle(Context context, String label, int thread) {
        LinearLayout header = row(context); header.setBackgroundColor(SURFACE);
        header.setPadding(dp(context, 24), dp(context, 22), dp(context, 24), dp(context, 8));
        header.addView(dot(context, thread, 12));
        TextView title = title(context, label, 22);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); return header;
    }

    private static final class Chevron extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Chevron(Context context) {
            int size = dp(context, 20); setBounds(0, 0, size, size);
            paint.setColor(MUTED); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(context, 1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas canvas) {
            float w = getBounds().width(), h = getBounds().height(); path.reset();
            path.moveTo(w * .38f, h * .25f); path.lineTo(w * .64f, h * .5f); path.lineTo(w * .38f, h * .75f);
            canvas.drawPath(path, paint);
        }
        @Override public int getIntrinsicWidth() { return getBounds().width(); }
        @Override public int getIntrinsicHeight() { return getBounds().height(); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private Ui() {}
}
