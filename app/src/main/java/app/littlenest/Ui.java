package app.littlenest;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.WHITE;
    static final int INK = Color.rgb(24, 56, 94);
    static final int MUTED = Color.rgb(96, 112, 134);
    static final int GREEN = Color.rgb(39, 80, 122);
    static final int SAGE = Color.rgb(178, 220, 151);
    static final int PEACH = Color.rgb(255, 212, 77);
    static final int LAVENDER = Color.rgb(207, 206, 239);
    static final int BLUE = Color.rgb(183, 225, 236);
    static final int CORAL = Color.rgb(246, 168, 157);
    static final int SAND = Color.rgb(239, 226, 194);
    static final int LINE = Color.rgb(232, 232, 233);

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

    static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout;
    }
    static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(context, radius)); return shape;
    }
    static TextView text(Context context, String value, int size, int color) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return text;
    }
    static TextView title(Context context, String value, int size) {
        TextView text = text(context, value, size, INK); text.setTypeface(Typeface.create("serif", Typeface.NORMAL)); return text;
    }
    static Button button(Context context, String label, Runnable action) {
        Button button = new Button(context); button.setText(label); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(GREEN); button.setMinHeight(dp(context, 48)); button.setMinimumHeight(dp(context, 48));
        GradientDrawable background = shape(context, Color.WHITE, 22);
        background.setStroke(dp(context, 1), LINE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1827507A), background, null));
        button.setStateListAnimator(null); button.setElevation(0);
        button.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(context, 5), 0, dp(context, 5)); button.setLayoutParams(params);
        return button;
    }
    static LinearLayout card(Context context, int color) {
        LinearLayout card = column(context); int p = dp(context, 14); card.setPadding(p, p, p, p);
        GradientDrawable background = shape(context, Color.WHITE, 8);
        background.setStroke(dp(context, 1), color == Color.WHITE ? LINE : color);
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(context, 6), 0, dp(context, 12)); card.setLayoutParams(params);
        return card;
    }
    static EditText input(Context context, String label, String value, boolean number) {
        EditText edit = new EditText(context); edit.setHint(label); edit.setText(value); edit.setTextColor(INK);
        edit.setContentDescription(label);
        edit.setTextSize(16); edit.setSingleLine(true); edit.setMinHeight(dp(context, 52));
        edit.setHintTextColor(MUTED);
        android.graphics.drawable.LayerDrawable background = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{
                new android.graphics.drawable.ColorDrawable(LINE),
                new android.graphics.drawable.ColorDrawable(BG)
            });
        background.setLayerInset(1, 0, 0, 0, dp(context, 1));
        edit.setBackground(background);
        edit.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
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
    static LinearLayout dialogTitle(Context context, String label, int color) {
        LinearLayout header = row(context); header.setBackgroundColor(color);
        header.setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10));
        TextView title = title(context, label, 23); title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(-1, -2)); return header;
    }
    private Ui() {}
}
