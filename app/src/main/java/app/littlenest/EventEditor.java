package app.littlenest;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

final class EventEditor {
    static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final MainActivity activity;
    private final Catalog.Kind kind;
    private final JSONObject source;
    private final Map<String, View> inputs = new LinkedHashMap<>();
    private EditText start, end, notes, reminder, repeat;
    private EditText leftSeconds, rightSeconds;
    private CheckBox incomplete;
    private String photo;
    private TextView photoLabel;
    private AlertDialog dialog;
    private final String profileId;
    private final long initialStart, initialEnd;

    EventEditor(MainActivity activity, String profileId, Catalog.Kind kind, JSONObject existing) {
        this.activity = activity; this.profileId = profileId; this.kind = kind;
        this.source = existing == null ? new JSONObject() : Records.copy(existing);
        initialStart = source.optLong("start", System.currentTimeMillis());
        initialEnd = source.optLong("end", initialStart);
        photo = source.optString("photo");
    }

    void show() {
        LinearLayout content = Ui.column(activity);
        int pad = Ui.dp(activity, 20); content.setPadding(pad, pad, pad, pad);
        content.addView(Ui.text(activity, "Local time \u00b7 Tap a time to change it", 11, Ui.MUTED));
        start = timeInput(content, "Start", initialStart);
        end = timeInput(content, "End", initialEnd);
        if (source.optBoolean("importIncomplete")) {
            incomplete = new CheckBox(activity); incomplete.setText(R.string.import_needs_review);
            incomplete.setChecked(true); content.addView(incomplete);
            content.addView(Ui.text(activity, "No completed duration was exported. Correct the times / durations and uncheck this flag when reviewed. This is not a live timer.", 12, Ui.MUTED));
        }
        if (kind.id().equals("breast")) {
            content.addView(Ui.text(activity, "Optional per-side duration in seconds (decimals allowed)", 13, Ui.MUTED));
            leftSeconds = Ui.input(activity, "Left duration (seconds)", seconds(source, "leftMs"), true); content.addView(leftSeconds);
            rightSeconds = Ui.input(activity, "Right duration (seconds)", seconds(source, "rightMs"), true); content.addView(rightSeconds);
        }
        if (kind.id().equals("bottle"))
            content.addView(Ui.text(activity, "Enter a total or component amounts. If a total is supplied, totals and charts use it instead of adding components again.", 12, Ui.MUTED));
        if (kind.id().equals("pump"))
            content.addView(Ui.text(activity, "Total output overrides the sum of left and right volumes. Each side can use its own unit.", 12, Ui.MUTED));
        if (kind.id().equals("growth"))
            content.addView(Ui.text(activity, "Trends compares these measurements with WHO growth percentiles. Measure length lying down before age two, and height standing from age two. Note a different measurement position in Notes; charts do not convert positions.", 12, Ui.MUTED));
        for (Catalog.Field field : kind.fields()) {
            content.addView(Ui.text(activity, field.label(), 13, Ui.MUTED));
            if (field.multiple()) {
                String[] options = field.options().split("\\|");
                Button choices = Ui.button(activity, source.optString(field.key(), "Not noted").replace("|", ", "), () -> {});
                choices.setTag(source.optString(field.key(), "Not noted"));
                choices.setOnClickListener(v -> {
                    boolean[] checked = new boolean[options.length];
                    java.util.List<String> selected = java.util.Arrays.asList(choices.getTag().toString().split("\\|"));
                    for (int i = 0; i < options.length; i++) checked[i] = selected.contains(options[i]);
                    new AlertDialog.Builder(activity).setTitle(field.label()).setMultiChoiceItems(options, checked, (d, index, enabled) -> checked[index] = enabled)
                        .setPositiveButton("Done", (d, w) -> {
                            java.util.List<String> values = new java.util.ArrayList<>();
                            for (int i = 1; i < options.length; i++) if (checked[i]) values.add(options[i]);
                            String value = values.isEmpty() ? "Not noted" : String.join("|", values);
                            choices.setTag(value); choices.setText(value.replace("|", ", "));
                        }).setNegativeButton("Cancel", null).show();
                });
                inputs.put(field.key(), choices); content.addView(choices);
            } else if (!field.options().isEmpty()) {
                Spinner spinner = new Spinner(activity);
                String[] options = field.options().split("\\|");
                spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, options));
                String fallback = field.key().equals("headUnit") ? source.optString("lengthUnit", options[0])
                    : field.key().equals("leftUnit") || field.key().equals("rightUnit") ? source.optString("unit", options[0]) : options[0];
                String selected = source.optString(field.key(), fallback);
                for (int i = 0; i < options.length; i++) if (options[i].equals(selected)) spinner.setSelection(i);
                content.addView(spinner, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 50)));
                inputs.put(field.key(), spinner);
            } else {
                EditText input = Ui.input(activity, field.label(), source.optString(field.key()), field.numeric());
                content.addView(input); inputs.put(field.key(), input);
            }
        }
        content.addView(Ui.text(activity, "Notes", 13, Ui.MUTED));
        notes = Ui.input(activity, "Anything you want to remember", source.optString("notes"), false);
        notes.setSingleLine(false); notes.setMinLines(2); content.addView(notes);
        content.addView(Ui.text(activity, "Optional reminder (blank = none)", 13, Ui.MUTED));
        reminder = Ui.input(activity, "yyyy-MM-dd HH:mm", source.optLong("remindAt") > 0 ? format(source.optLong("remindAt")) : "", false);
        reminder.setFocusable(false); reminder.setOnClickListener(v -> pick(reminder));
        content.addView(reminder);
        content.addView(Ui.button(activity, "Clear reminder", () -> reminder.setText("")));
        repeat = Ui.input(activity, "Repeat every N minutes (blank = once)", source.optLong("repeatMinutes") > 0 ? Long.toString(source.optLong("repeatMinutes")) : "", true);
        content.addView(repeat);
        photoLabel = Ui.text(activity, photo.isEmpty() ? "No photo attached" : "Photo attached", 13, Ui.MUTED);
        content.addView(photoLabel);
        content.addView(Ui.button(activity, "Attach / replace photo", activity::choosePhoto));
        content.addView(Ui.button(activity, "Remove photo", () -> setPhoto("")));
        if (!photo.isEmpty()) {
            byte[] bytes = Base64.decode(photo, Base64.DEFAULT);
            ImageView image = new ImageView(activity); image.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
            image.setAdjustViewBounds(true); content.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 180)));
        }
        if (kind.id().equals("medicine") || kind.id().equals("temperature") || kind.group().equals("Parent"))
            content.addView(Ui.text(activity, "A personal log, not medical advice. Contact a clinician for health concerns.", 12, Ui.MUTED));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setCustomTitle(Ui.dialogTitle(activity, kind.title(), Ui.thread(kind))).setView(scroll)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", (d, w) -> activity.editor = null);
        if (kind.timer() && !source.has("id")) builder.setNeutralButton("Start timer", null);
        dialog = builder.create(); dialog.setOnDismissListener(d -> { if (activity.editor == this) activity.editor = null; });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.getWindow().setBackgroundDrawable(Ui.shape(activity, Ui.SURFACE, 20));
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save(false));
        if (kind.timer() && !source.has("id")) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> save(true));
    }

    void setPhoto(String photo) { this.photo = photo; photoLabel.setText(photo.isEmpty() ? "No photo attached" : "Photo attached"); }

    private EditText timeInput(LinearLayout content, String label, long value) {
        LinearLayout row = Ui.row(activity);
        row.addView(Ui.title(activity, label, 15), new LinearLayout.LayoutParams(0, -2, 1));
        EditText input = Ui.input(activity, label, format(value), false);
        input.setTextSize(13); input.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        input.setBackground(Ui.shape(activity, Ui.night ? Ui.BG : Ui.SAGE, 12));
        input.setFocusable(false); input.setOnClickListener(v -> pick(input));
        row.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(activity, 48), 2));
        content.addView(row); Ui.divider(content); return input;
    }

    private void pick(EditText target) {
        LocalDateTime current;
        try { current = LocalDateTime.parse(target.getText().toString(), FORMAT); }
        catch (java.time.format.DateTimeParseException e) { current = LocalDateTime.now(); }
        LocalDateTime initial = current;
        new DatePickerDialog(activity, (view, year, month, day) ->
            new TimePickerDialog(activity, (time, hour, minute) ->
                target.setText(LocalDateTime.of(year, month + 1, day, hour, minute).format(FORMAT)),
                initial.getHour(), initial.getMinute(), android.text.format.DateFormat.is24HourFormat(activity)).show(),
            current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth()).show();
    }

    static String format(long value) { return Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(FORMAT); }
    private static long parse(EditText input) { return LocalDateTime.parse(input.getText().toString().trim(), FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    private static long readTime(EditText input, long initial) {
        return input.getText().toString().equals(format(initial)) ? initial : parse(input);
    }
    private static String seconds(JSONObject data, String key) {
        return data.has(key) ? java.math.BigDecimal.valueOf(data.optLong(key), 3).stripTrailingZeros().toPlainString() : "";
    }
    private static long durationInput(EditText input) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) return 0;
        try {
            long result = new java.math.BigDecimal(value).multiply(java.math.BigDecimal.valueOf(1000)).longValueExact();
            if (result < 0) throw new ArithmeticException();
            return result;
        } catch (NumberFormatException | ArithmeticException e) { throw new IllegalArgumentException("Side durations must be nonnegative seconds with at most three decimal places"); }
    }

    JSONObject draft() {
        JSONObject data = Records.copy(source);
        Records.put(data, "entity", "event"); Records.put(data, "profileId", profileId); Records.put(data, "kind", kind.id());
        Records.put(data, "start", readTime(start, initialStart)); Records.put(data, "end", readTime(end, initialEnd));
        for (Map.Entry<String, View> input : inputs.entrySet()) {
            String value = input.getValue() instanceof Spinner spinner ? spinner.getSelectedItem().toString()
                : input.getValue() instanceof Button button ? button.getTag().toString()
                : ((EditText) input.getValue()).getText().toString().trim();
            Records.put(data, input.getKey(), value);
        }
        if (incomplete != null) Records.put(data, "importIncomplete", incomplete.isChecked());
        if (leftSeconds != null && (!leftSeconds.getText().toString().equals(seconds(source, "leftMs"))
            || !rightSeconds.getText().toString().equals(seconds(source, "rightMs")))) {
            long left = durationInput(leftSeconds), right = durationInput(rightSeconds);
            long elapsed;
            try { elapsed = Math.addExact(left, right); Records.put(data, "end", Math.addExact(data.optLong("start"), elapsed)); }
            catch (ArithmeticException e) { throw new IllegalArgumentException("Breastfeeding duration is too large"); }
            Records.put(data, "leftMs", left); Records.put(data, "rightMs", right);
            Records.put(data, "elapsedMs", elapsed); Records.put(data, "entryMethod", "Manual");
            data.remove("segments"); Records.put(data, "timerPaused", false);
        }
        Records.put(data, "notes", notes.getText().toString()); Records.put(data, "photo", photo);
        Records.put(data, "remindAt", reminder.getText().toString().isBlank() ? 0 : parse(reminder));
        String repetition = repeat.getText().toString().trim();
        long minutes;
        try { minutes = repetition.isEmpty() ? 0 : Long.parseLong(repetition); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Reminder repeat must be a whole number of minutes"); }
        if (minutes < 0 || minutes > 525600) throw new IllegalArgumentException("Reminder repeat must be between 0 and 525600 minutes");
        Records.put(data, "repeatMinutes", minutes);
        return data;
    }

    private void save(boolean run) {
        try {
            JSONObject data = draft();
            if (data.optLong("end") < data.optLong("start")) throw new IllegalArgumentException("End must be at or after start");
            if (!kind.id().equals("appointment") && data.optLong("start") > System.currentTimeMillis() + 60_000)
                throw new IllegalArgumentException("Care logs cannot start in the future. Use a reminder for planned activities.");
            if (!kind.id().equals("appointment") && data.optLong("end") > System.currentTimeMillis() + 60_000)
                throw new IllegalArgumentException("Care logs cannot end in the future.");
            if (data.optLong("remindAt") > 0 && data.optLong("remindAt") <= System.currentTimeMillis())
                throw new IllegalArgumentException("Choose a future reminder time, or clear the reminder");
            if (source.optBoolean("running")) throw new IllegalArgumentException("Pause the timer before editing this activity");
            if (run) {
                for (JSONObject event : activity.store.events(profileId))
                    if (event.optString("kind").equals(kind.id()) && event.optBoolean("running"))
                        throw new IllegalArgumentException("This profile already has a running " + kind.title().toLowerCase(java.util.Locale.getDefault()) + " timer");
                long now = System.currentTimeMillis();
                Records.put(data, "start", now); Records.put(data, "end", now); Records.put(data, "segmentStart", now);
                Records.put(data, "elapsedMs", 0); Records.put(data, "leftMs", 0); Records.put(data, "rightMs", 0);
            } else if (!source.has("id") || source.optLong("start") != data.optLong("start") || source.optLong("end") != data.optLong("end")) {
                Records.put(data, "elapsedMs", data.optLong("end") - data.optLong("start"));
                data.remove("segments");
                Records.put(data, "timerPaused", false);
            }
            Records.put(data, "running", run);
            if (kind.id().equals("breast")) {
                if (run) {
                    Records.put(data, "beginSide", data.optString("side", "Left"));
                    Records.put(data, "entryMethod", "Timer");
                } else if (!source.has("id") || source.optLong("start") != data.optLong("start")
                    || source.optLong("end") != data.optLong("end") || source.optLong("elapsedMs") != data.optLong("elapsedMs")) {
                    Records.put(data, "entryMethod", "Manual");
                }
            }
            if (!source.has("id")) Records.put(data, "caregiver", activity.prefs.getString("caregiver", "Caregiver"));
            Records.put(data, "updatedBy", activity.prefs.getString("caregiver", "Caregiver"));
            String id = activity.store.save(source.has("id") ? source.optString("id") : null, data, false);
            Records.put(data, "id", id); Reminders.schedule(activity, data);
            if (data.optLong("remindAt") > 0) activity.requestNotifications();
            dialog.dismiss(); activity.editor = null; activity.logged();
        } catch (IllegalArgumentException e) { activity.error(e.getMessage()); }
    }
}
