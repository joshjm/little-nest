package app.littlenest;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.job.JobScheduler;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    Store store;
    SharedPreferences prefs;
    EventEditor editor;
    private LinearLayout body;
    private String profileId = "", tab = "Today", filter = "";
    private ScrollView mainScroll;
    private String renderedTab = "";
    private LocalDate historyDay;
    private int historyPage;
    private boolean importing;
    private String token;
    private long tokenAt;
    private boolean authorizing, working;
    private Runnable afterAuthorization;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<TextView> timerLabels = new ArrayList<>();
    private final List<JSONObject> timerEvents = new ArrayList<>();
    private static final int AUTH = 101, PHOTO = 102, IMPORT = 103, IMPORT_NARA = 105;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            for (int i = 0; i < timerLabels.size(); i++)
                timerLabels.get(i).setText(timerText(timerEvents.get(i)));
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable autoSync = new Runnable() {
        @Override public void run() {
            if (!prefs.getString("folder", "").isEmpty() && !working && !authorizing && !importing && editor == null)
                authorize(false, () -> sync(false));
            handler.postDelayed(this, 60_000);
        }
    };
    private final Runnable careTick = new Runnable() {
        @Override public void run() {
            if (tab.equals("Today") && editor == null && !importing && !working) render();
            handler.postDelayed(this, 60_000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this); prefs = getSharedPreferences("settings", MODE_PRIVATE);
        profileId = prefs.getString("profile", "");
        if (savedInstanceState != null) {
            tab = savedInstanceState.getString("tab", "Today");
            String day = savedInstanceState.getString("historyDay");
            if (day != null) historyDay = LocalDate.parse(day);
        }
        readCareIntent(getIntent());
        render();
        if (savedInstanceState != null && savedInstanceState.containsKey("editor")) {
            JSONObject draft = Store.parse(savedInstanceState.getString("editor"));
            openEditor(Catalog.get(draft.optString("kind")), draft);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("tab", tab);
        if (historyDay != null) state.putString("historyDay", historyDay.toString());
        if (editor != null) {
            try { state.putString("editor", editor.draft().toString()); }
            catch (IllegalArgumentException e) { Toast.makeText(this, "Unsaved invalid reminder interval could not be restored", Toast.LENGTH_LONG).show(); }
        }
    }
    @Override protected void onResume() {
        super.onResume(); handler.post(ticker); handler.postDelayed(autoSync, 1500);
        CareReminders.refresh(this);
        handler.postDelayed(careTick, 60_000);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(ticker); handler.removeCallbacks(autoSync); handler.removeCallbacks(careTick); super.onPause();
    }
    @Override protected void onDestroy() { executor.shutdown(); super.onDestroy(); }

    void changed() {
        render();
        CareReminders.refresh(this);
        if (!prefs.getString("folder", "").isEmpty()) {
            handler.removeCallbacks(autoSync); handler.postDelayed(autoSync, 1500);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (readCareIntent(intent)) render();
    }
    private boolean readCareIntent(Intent intent) {
        String requested = intent.getStringExtra("careProfile");
        if (requested == null) return false;
        intent.removeExtra("careProfile");
        boolean exists = store.records("profile").stream().anyMatch(profile -> profile.optString("id").equals(requested));
        if (exists) { selectProfile(requested); tab = "Today"; }
        return exists;
    }

    private JSONObject profile() {
        for (JSONObject profile : store.records("profile")) if (profile.optString("id").equals(profileId)) return profile;
        List<JSONObject> profiles = store.records("profile");
        if (!profiles.isEmpty()) { selectProfile(profiles.get(0).optString("id")); return profiles.get(0); }
        return null;
    }
    private void selectProfile(String id) { profileId = id; historyPage = 0; prefs.edit().putString("profile", id).apply(); }

    private void render() {
        int scrollPosition = mainScroll != null && renderedTab.equals(tab) ? mainScroll.getScrollY() : 0;
        renderedTab = tab;
        timerLabels.clear(); timerEvents.clear();
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        LinearLayout header = Ui.column(this);
        header.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 10));
        LinearLayout branding = Ui.row(this);
        JSONObject profile = profile();
        if (profile != null) {
            ImageView avatar = new ImageView(this);
            avatar.setImageDrawable(new NestIcon(this, "profile", Ui.INK, Ui.BLUE, 36));
            avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
            avatarParams.setMarginEnd(Ui.dp(this, 12)); branding.addView(avatar, avatarParams);
            LinearLayout identity = Ui.column(this);
            TextView name = Ui.title(this, profile.optString("name") + "  \u2304", 23);
            name.setPadding(0, 0, 0, 0); identity.addView(name);
            TextView date = Ui.text(this, age(profile) + "  \u00b7  " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())), 11, Ui.MUTED);
            date.setPadding(0, Ui.dp(this, 2), 0, 0); identity.addView(date);
            branding.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            branding.setMinimumHeight(Ui.dp(this, 52));
            branding.setContentDescription("Switch profile: " + profile.optString("name"));
            branding.setOnClickListener(v -> chooseProfile());
        } else {
            branding.addView(Ui.title(this, "little nest", 25), new LinearLayout.LayoutParams(0, -2, 1));
        }
        TextView offline = Ui.text(this, prefs.getString("folder", "").isEmpty() ? "LOCAL" : "LINKED", 9, Ui.GREEN);
        branding.addView(offline); header.addView(branding);
        root.addView(header);
        Ui.divider(root);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        mainScroll = scroll;
        body = Ui.column(this); body.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 20)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (profile == null && !tab.equals("Family")) welcome();
        else switch (tab) {
            case "History" -> history();
            case "Trends" -> trends();
            case "Family" -> family();
            default -> today();
        }
        Ui.divider(root);
        LinearLayout nav = Ui.row(this); nav.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));
        for (String item : List.of("Today", "History", "Trends", "Family")) {
            Button button = Ui.button(this, item, () -> { tab = item; filter = ""; historyPage = 0; render(); });
            button.setTextColor(tab.equals(item) ? Ui.GREEN : Ui.MUTED);
            button.setTextSize(10);
            button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1827507A), Ui.shape(this, Ui.BG, 0), null));
            button.setCompoundDrawables(null, new NestIcon(this, item, tab.equals(item) ? Ui.GREEN : Ui.MUTED, Ui.BG, 22), null, null);
            button.setCompoundDrawablePadding(Ui.dp(this, 3));
            button.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 3));
            button.setSelected(tab.equals(item));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0); nav.addView(button, params);
        }
        root.addView(nav);
        scroll.post(() -> scroll.scrollTo(0, scrollPosition));
    }

    private String age(JSONObject profile) {
        if (profile.optString("role").equals("Parent")) return profile.optString("stage", "Parent");
        try {
            Period age = Period.between(LocalDate.parse(profile.optString("dob")), LocalDate.now());
            if (age.isNegative()) return "Arriving soon";
            if (age.getYears() > 0) return age.getYears() + "y " + age.getMonths() + "m";
            if (age.getMonths() > 0) return age.getMonths() + "m " + age.getDays() + "d";
            return age.getDays() + " days";
        } catch (java.time.format.DateTimeParseException e) { return "Baby"; }
    }

    private void welcome() {
        Ui.gap(body, 22);
        body.addView(Ui.title(this, "Small moments.\nA little more peace.", 30));
        body.addView(Ui.text(this, "A calm place for feeds, sleep, little firsts, and you. Private, offline, and shared with just your person.", 17, Ui.MUTED));
        LinearLayout card = Ui.card(this, Ui.SAGE);
        card.addView(Ui.title(this, "Welcome to your nest", 22));
        card.addView(Ui.text(this, "Start with a baby or parent profile. Add twins and other children whenever you need.", 15, Ui.MUTED));
        card.addView(Ui.button(this, "Create a profile", () -> profileEditor(null)));
        card.addView(Ui.button(this, "Join partner's nest", () -> { tab = "Family"; render(); }));
        body.addView(card);
        body.addView(Ui.text(this, "No ads. No tracking servers. Your data stays here unless you connect Google Drive.", 13, Ui.MUTED));
    }

    private void today() {
        JSONObject profile = profile(); if (profile == null) { welcome(); return; }
        List<JSONObject> events = store.events(profileId);
        body.addView(Ui.title(this, "A day at a time", 18));
        Insights.Day day = Insights.day(events, LocalDate.now(), ZoneId.systemDefault(), System.currentTimeMillis());
        LinearLayout summary = Ui.column(this);
        summary.setPadding(0, 0, 0, Ui.dp(this, 8));
        boolean parent = profile.optString("role").equals("Parent");
        summary.addView(Ui.text(this, parent ? events.stream().filter(e -> Insights.date(e.optLong("start")).equals(LocalDate.now())).count() + " moments logged  /  " + Insights.duration(day.sleepMs()) + " sleep"
            : day.feeds() + " feeds   \u00b7   " + day.wet() + " wet, " + day.dirty() + " dirty   \u00b7   " + Insights.duration(day.sleepMs()) + " sleep   \u00b7   " + milk(day.milkMl()), 12, Ui.MUTED));
        if (!parent) {
            JSONObject latestBreast = events.stream().filter(e -> e.optString("kind").equals("breast")).findFirst().orElse(null);
            if (latestBreast != null) summary.addView(Ui.text(this, "Last breast side: " + latestBreast.optString("side", "Not recorded"), 11, Ui.MUTED));
            JSONObject sleep = events.stream().filter(e -> e.optString("kind").equals("sleep") && !e.optBoolean("importIncomplete")).findFirst().orElse(null);
            if (sleep != null) summary.addView(Ui.text(this, sleep.optBoolean("running") ? "Sleeping now" : "Awake for " + Insights.duration(System.currentTimeMillis() - sleep.optLong("end")), 11, Ui.MUTED));
        }
        body.addView(summary);
        CarePlanUi.addToday(this, body, profile, events);
        if (!prefs.getString("folder", "").isEmpty() && !prefs.getString("syncError", "").isEmpty())
            body.addView(Ui.button(this, "Sync needs attention - open Family", () -> { tab = "Family"; render(); }));
        for (JSONObject event : events) if (event.optBoolean("running") || event.optBoolean("timerPaused")) timerCard(event);
        Set<String> hidden = prefs.getStringSet("hidden", Set.of());
        List<Catalog.Kind> visible = Catalog.KINDS.stream().filter(k -> !hidden.contains(k.id())
            && (parent ? k.group().equals("Parent") || List.of("pump", "journal", "appointment", "routine", "medical", "medicine", "vaccine", "temperature").contains(k.id())
                : !k.group().equals("Parent"))).toList();
        for (String group : parent ? List.of("Pump", "Parent", "Health", "Routines", "Memories", "Planning")
            : List.of("Feed", "Sleep", "Pump", "Diaper", "Growth", "Health", "Routines", "Memories", "Planning")) {
            List<Catalog.Kind> kinds = visible.stream().filter(k -> activitySection(k).equals(group)).toList();
            if (!kinds.isEmpty()) activityCard(group, kinds, events);
        }
        CarePlanUi.addUpcoming(this, body, events);
        body.addView(Ui.title(this, "Recent moments", 21));
        List<JSONObject> recent = events.stream().filter(e -> Insights.date(e.optLong("start")).equals(LocalDate.now())).limit(6).toList();
        if (recent.isEmpty()) body.addView(Ui.text(this, "A fresh page. Your first log will appear here.", 15, Ui.MUTED));
        for (JSONObject event : recent) eventCard(body, event);
    }

    private String activitySection(Catalog.Kind kind) {
        return switch (kind.id()) {
            case "breast", "bottle", "solids" -> "Feed";
            case "pump" -> "Pump";
            case "diaper" -> "Diaper";
            case "growth" -> "Growth";
            default -> kind.group();
        };
    }

    private void activityCard(String title, List<Catalog.Kind> kinds, List<JSONObject> events) {
        int accent = Ui.activityColor(kinds.get(0));
        LinearLayout card = Ui.column(this);
        android.graphics.drawable.GradientDrawable background = Ui.shape(this, Ui.BG, 7);
        background.setStroke(Ui.dp(this, 1), Ui.LINE);
        card.setBackground(background); card.setClipToOutline(true);
        card.setElevation(Ui.dp(this, 1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 14)); card.setLayoutParams(cardParams);
        LinearLayout heading = Ui.row(this); heading.setBackgroundColor(accent);
        heading.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        heading.addView(Ui.title(this, title, 20), new LinearLayout.LayoutParams(0, -2, 1));
        Button add = Ui.button(this, "+", () -> {
            if (kinds.size() == 1) openEditor(kinds.get(0), null);
            else new AlertDialog.Builder(this).setTitle("Log " + title.toLowerCase(Locale.ROOT))
                .setItems(kinds.stream().map(Catalog.Kind::title).toArray(String[]::new),
                    (dialog, which) -> openEditor(kinds.get(which), null)).show();
        });
        add.setContentDescription("Add " + title.toLowerCase(Locale.ROOT));
        add.setTextSize(23); add.setTextColor(android.graphics.Color.WHITE);
        add.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3027507A),
            new android.graphics.drawable.InsetDrawable(Ui.shape(this, Ui.GREEN, 24), Ui.dp(this, 9)), null));
        add.setPadding(0, 0, 0, 0);
        heading.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        card.addView(heading);
        for (Catalog.Kind kind : kinds) {
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 13), Ui.dp(this, 7), Ui.dp(this, 13), Ui.dp(this, 7));
            row.setMinimumHeight(Ui.dp(this, 62));
            row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1827507A), Ui.shape(this, Ui.BG, 0), null));
            ImageView icon = new ImageView(this); icon.setImageDrawable(new NestIcon(this, kind.id(), Ui.INK, accent, 27));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Ui.dp(this, 27), Ui.dp(this, 30));
            iconParams.setMarginEnd(Ui.dp(this, 12)); row.addView(icon, iconParams);
            LinearLayout text = Ui.column(this);
            TextView label = Ui.title(this, kind.title(), 16); label.setPadding(0, 0, 0, 0); text.addView(label);
            JSONObject latest = events.stream().filter(e -> e.optString("kind").equals(kind.id())).findFirst().orElse(null);
            String detail = latest == null ? "Tap to add" : latest.optBoolean("running") ? "Timer running"
                : Insights.duration(System.currentTimeMillis() - latest.optLong("start")) + " ago";
            TextView last = Ui.text(this, detail, 11, Ui.MUTED); last.setPadding(0, Ui.dp(this, 4), 0, 0); text.addView(last);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            String value = latest == null ? "+" : latest.optBoolean("importIncomplete") ? "Review"
                : kind.id().equals("bottle") ? milk(Insights.bottleMl(latest))
                : kind.id().equals("pump") ? milk(Insights.pumpMl(latest))
                : kind.timer() ? Insights.duration(Records.duration(latest, System.currentTimeMillis()))
                : kind.id().equals("diaper") ? latest.optString("diaper") : "\u203a";
            TextView amount = Ui.title(this, value, 20);
            amount.setMaxWidth(Ui.dp(this, 100)); row.addView(amount);
            row.setOnClickListener(v -> openEditor(kind, null));
            row.setFocusable(true); row.setContentDescription("Log " + kind.title() + ". " + detail);
            card.addView(row); Ui.divider(card);
        }
        body.addView(card);
    }

    private String milk(double ml) {
        return prefs.getBoolean("imperial", false) ? String.format(Locale.getDefault(), "%.1f fl oz", ml / 29.5735295625)
            : String.format(Locale.getDefault(), "%.0f ml", ml);
    }
    private void timerCard(JSONObject event) {
        LinearLayout card = Ui.card(this, Ui.activityColor(Catalog.get(event.optString("kind"))));
        card.addView(Ui.title(this, Catalog.get(event.optString("kind")).title() + (event.optBoolean("running") ? " / running" : " / paused"), 18));
        TextView label = Ui.title(this, timerText(event), 30); card.addView(label);
        timerLabels.add(label); timerEvents.add(event);
        LinearLayout controls = Ui.row(this);
        addControl(controls, event.optBoolean("running") ? "Pause" : "Resume", () -> updateTimer(event, "toggle"));
        if (List.of("breast", "pump").contains(event.optString("kind"))) addControl(controls, "Switch side", () -> updateTimer(event, "side"));
        addControl(controls, "Finish", () -> updateTimer(event, "finish"));
        card.addView(controls); body.addView(card);
    }
    private void addControl(LinearLayout row, String label, Runnable action) {
        Button button = Ui.button(this, label, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0); row.addView(button, params);
    }
    private String timerText(JSONObject event) {
        long duration = Records.duration(event, System.currentTimeMillis());
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", duration / 3_600_000, duration / 60_000 % 60, duration / 1000 % 60)
            + (List.of("breast", "pump").contains(event.optString("kind")) ? "  " + event.optString("side", "Left") : "");
    }
    private void updateTimer(JSONObject source, String action) {
        JSONObject event = Records.settleTimer(source, System.currentTimeMillis());
        boolean running = source.optBoolean("running");
        if (action.equals("toggle")) {
            if (!running && store.events(profileId).stream().anyMatch(other -> !other.optString("id").equals(source.optString("id"))
                && other.optString("kind").equals(source.optString("kind")) && other.optBoolean("running"))) {
                error("Finish or pause the other timer of this kind first."); return;
            }
            Records.put(event, "running", !running); Records.put(event, "timerPaused", running);
        } else if (action.equals("finish")) {
            Records.put(event, "running", false); Records.put(event, "timerPaused", false);
        } else {
            Records.put(event, "side", source.optString("side", "Left").equals("Left") ? "Right" : "Left");
        }
        store.save(event.optString("id"), event, false); changed();
    }

    private void history() {
        body.addView(Ui.title(this, "The story so far", 24));
        body.addView(Ui.text(this, "Tap a moment to edit, see details, or remove it.", 14, Ui.MUTED));
        LinearLayout dates = Ui.row(this);
        addControl(dates, "<", () -> { historyDay = historyDay == null ? LocalDate.now().minusDays(1) : historyDay.minusDays(1); historyPage = 0; render(); });
        addControl(dates, historyDay == null ? "All dates" : historyDay.format(DateTimeFormatter.ofPattern("d MMM yy")), () -> {
            LocalDate initial = historyDay == null ? LocalDate.now() : historyDay;
            new DatePickerDialog(this, (v, y, m, d) -> { historyDay = LocalDate.of(y, m + 1, d); historyPage = 0; render(); },
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
        });
        addControl(dates, ">", () -> { historyDay = historyDay == null ? LocalDate.now() : historyDay.plusDays(1); historyPage = 0; render(); });
        body.addView(dates);
        body.addView(Ui.button(this, "Show all dates", () -> { historyDay = null; historyPage = 0; render(); }));
        EditText search = Ui.input(this, "Search activity, notes, food, caregiver...", filter, false); body.addView(search);
        LinearLayout list = Ui.column(this); body.addView(list);
        populateHistory(list);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter = s.toString(); historyPage = 0; populateHistory(list); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }
    private void populateHistory(LinearLayout list) {
        list.removeAllViews();
        long from = historyDay == null ? 0 : historyDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long to = historyDay == null ? 253402300800000L : historyDay.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<JSONObject> events = store.eventPage(profileId, filter, from, to, historyPage * 100);
        for (JSONObject event : events.subList(0, Math.min(100, events.size()))) eventCard(list, event);
        if (events.isEmpty()) list.addView(Ui.text(this, "No moments match. Try a different date or search.", 15, Ui.MUTED));
        LinearLayout pages = Ui.row(this);
        if (historyPage > 0) addControl(pages, "Previous 100", () -> { historyPage--; populateHistory(list); mainScroll.smoothScrollTo(0, 0); });
        pages.addView(Ui.text(this, "Page " + (historyPage + 1), 12, Ui.MUTED));
        if (events.size() > 100) addControl(pages, "Next 100", () -> { historyPage++; populateHistory(list); mainScroll.smoothScrollTo(0, 0); });
        list.addView(pages);
    }

    private String summary(JSONObject event) {
        Catalog.Kind kind = Catalog.get(event.optString("kind"));
        List<String> parts = new ArrayList<>();
        if (event.optBoolean("importIncomplete")) parts.add("NEEDS REVIEW: Nara did not export a completed duration. Not a live timer.");
        for (Catalog.Field field : kind.fields()) {
            String value = event.optString(field.key());
            if (!value.isBlank()) parts.add(field.label() + ": " + value.replace("|", ", "));
        }
        if (kind.id().equals("bottle")) parts.add("Bottle total: " + milk(Insights.bottleMl(event)));
        if (kind.id().equals("pump")) parts.add("Pump output: " + milk(Insights.pumpMl(event)));
        if (kind.timer()) {
            parts.add("Duration: " + Insights.duration(Records.duration(event, System.currentTimeMillis())));
            if (event.optLong("leftMs") + event.optLong("rightMs") > 0)
                parts.add("Left " + Insights.duration(event.optLong("leftMs")) + " / Right " + Insights.duration(event.optLong("rightMs")));
        }
        if (!event.optString("notes").isBlank()) parts.add(event.optString("notes"));
        if (!event.optString("photo").isEmpty()) parts.add("Photo attached");
        if (event.optLong("remindAt") > 0) parts.add("Reminder: " + EventEditor.format(event.optLong("remindAt")));
        if (event.has("naraSource")) {
            parts.add("Imported from Nara; original time zone: " + event.optString("sourceTimeZone"));
            parts.add("Last updated by: " + event.optString("updatedBy"));
        }
        return String.join("\n", parts);
    }
    private void eventCard(LinearLayout target, JSONObject event) {
        Catalog.Kind kind = Catalog.get(event.optString("kind"));
        LinearLayout card = Ui.card(this, android.graphics.Color.WHITE);
        LinearLayout heading = Ui.row(this);
        ImageView icon = new ImageView(this); icon.setImageDrawable(new NestIcon(this, kind.id(), Ui.INK, Ui.activityColor(kind), 25));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Ui.dp(this, 25), Ui.dp(this, 28));
        iconParams.setMarginEnd(Ui.dp(this, 10)); heading.addView(icon, iconParams);
        heading.addView(Ui.title(this, kind.title() + (event.optBoolean("running") ? " - running" : ""), 17),
            new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(Ui.text(this, "\u203a", 22, Ui.MUTED));
        card.addView(heading);
        card.addView(Ui.text(this, EventEditor.format(event.optLong("start")) + "  /  " + event.optString("caregiver", "Caregiver"), 12, Ui.MUTED));
        String summary = summary(event);
        TextView detail = Ui.text(this, summary, 14, Ui.INK); detail.setMaxLines(4); card.addView(detail);
        card.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(kind.title()).setMessage(summary)
                .setPositiveButton("Edit", (d, w) -> {
                    if (event.optBoolean("running")) error("Pause the timer before editing.");
                    else openEditor(kind, event);
                })
                .setNeutralButton("Delete", (d, w) -> new AlertDialog.Builder(this).setTitle("Delete this moment?")
                    .setMessage("Deletion will sync to your partner. Historical revisions remain in backups and Drive.")
                    .setPositiveButton("Delete", (confirm, which) -> {
                        store.save(event.optString("id"), event, true); Reminders.cancel(this, event.optString("id")); changed();
                    }).setNegativeButton("Cancel", null).show())
                .setNegativeButton("Close", null).create();
            dialog.show();
        });
        card.setContentDescription(kind.title() + " " + EventEditor.format(event.optLong("start")) + " " + summary);
        target.addView(card);
    }

    private void trends() {
        body.addView(Ui.title(this, "Find your rhythm", 24));
        body.addView(Ui.text(this, "Last 7 days / local calendar days", 14, Ui.MUTED));
        List<JSONObject> events = store.events(profileId);
        List<Insights.Day> days = new ArrayList<>();
        for (int i = 6; i >= 0; i--) days.add(Insights.day(events, LocalDate.now().minusDays(i), ZoneId.systemDefault(), System.currentTimeMillis()));
        LinearLayout rhythm = Ui.card(this, android.graphics.Color.WHITE);
        rhythm.addView(Ui.title(this, "Sleep pattern / 24 hours", 18));
        rhythm.addView(new SleepTimelineView(this, events), new LinearLayout.LayoutParams(-1, Ui.dp(this, 230)));
        body.addView(rhythm);
        long weekSleep = days.stream().mapToLong(Insights.Day::sleepMs).sum(), previousSleep = 0;
        int weekFeeds = days.stream().mapToInt(Insights.Day::feeds).sum(), previousFeeds = 0;
        for (int i = 7; i < 14; i++) {
            Insights.Day previous = Insights.day(events, LocalDate.now().minusDays(i), ZoneId.systemDefault(), System.currentTimeMillis());
            previousSleep += previous.sleepMs(); previousFeeds += previous.feeds();
        }
        body.addView(Ui.text(this, "This week: " + weekFeeds + " feeds / " + Insights.duration(weekSleep) + " sleep\nPrevious week: "
            + previousFeeds + " feeds / " + Insights.duration(previousSleep) + " sleep", 14, Ui.MUTED));
        addChart("Sleep / hours", days.stream().map(d -> new ChartView.Point(d.date().format(DateTimeFormatter.ofPattern("EE")), d.sleepMs() / 3_600_000.0)).toList(), Ui.LAVENDER);
        addChart("Feeds / count", days.stream().map(d -> new ChartView.Point(d.date().format(DateTimeFormatter.ofPattern("EE")), d.feeds())).toList(), Ui.PEACH);
        addChart("Bottle milk / " + (prefs.getBoolean("imperial", false) ? "fl oz" : "ml"),
            days.stream().map(d -> new ChartView.Point(d.date().format(DateTimeFormatter.ofPattern("EE")), d.milkMl() / (prefs.getBoolean("imperial", false) ? 29.5735295625 : 1))).toList(), Ui.BLUE);
        LinearLayout table = Ui.card(this, Ui.SAGE);
        table.addView(Ui.title(this, "Daily comparison", 19));
        for (Insights.Day day : days) table.addView(Ui.text(this, day.date().format(DateTimeFormatter.ofPattern("d MMM")) + "   " + day.feeds() + " feeds   " + day.wet() + " wet   " + day.dirty() + " dirty   " + Insights.duration(day.sleepMs()) + " sleep", 13, Ui.INK));
        body.addView(table);
        List<JSONObject> babies = store.records("profile").stream().filter(p -> p.optString("role").equals("Baby")).toList();
        if (babies.size() > 1) {
            LinearLayout comparison = Ui.card(this, Ui.PEACH);
            comparison.addView(Ui.title(this, "Children / today", 19));
            for (JSONObject baby : babies) {
                Insights.Day stats = Insights.day(store.events(baby.optString("id")), LocalDate.now(), ZoneId.systemDefault(), System.currentTimeMillis());
                comparison.addView(Ui.text(this, baby.optString("name") + ": " + stats.feeds() + " feeds / " + stats.wet() + " wet / "
                    + stats.dirty() + " dirty / " + Insights.duration(stats.sleepMs()) + " sleep", 14, Ui.INK));
            }
            body.addView(comparison);
        }
        List<JSONObject> growth = events.stream().filter(e -> e.optString("kind").equals("growth")).sorted((a, b) -> Long.compare(a.optLong("start"), b.optLong("start"))).toList();
        for (String measure : List.of("weight", "height", "head")) {
            List<ChartView.Point> points = new ArrayList<>();
            boolean imperial = prefs.getBoolean("imperial", false);
            for (JSONObject event : growth) {
                if (event.optString(measure).isBlank()) continue;
                double value = event.optDouble(measure);
                boolean weight = measure.equals("weight");
                if (weight && event.optString("weightUnit").equals("lb")) value *= .45359237;
                if (!weight && event.optString(measure.equals("head") ? "headUnit" : "lengthUnit", event.optString("lengthUnit")).equals("in")) value *= 2.54;
                if (imperial) value /= weight ? .45359237 : 2.54;
                points.add(new ChartView.Point(Insights.date(event.optLong("start")).format(DateTimeFormatter.ofPattern("d/M")), value));
            }
            if (!points.isEmpty()) addChart((measure.equals("head") ? "Head circumference" : measure.equals("weight") ? "Weight" : "Length") + " / "
                + (measure.equals("weight") ? imperial ? "lb" : "kg" : imperial ? "in" : "cm"), points.subList(Math.max(0, points.size() - 7), points.size()), Ui.SAGE, true);
        }
        body.addView(Ui.text(this, "Charts show your entries, not medical targets or growth percentiles. Sleep is split at midnight; overlapping logs are counted separately. No automatic diagnoses or age-based advice.", 12, Ui.MUTED));
    }
    private void addChart(String title, List<ChartView.Point> points, int color) {
        addChart(title, points, color, false);
    }
    private void addChart(String title, List<ChartView.Point> points, int color, boolean connected) {
        LinearLayout card = Ui.card(this, color); card.addView(Ui.title(this, title, 18));
        card.addView(new ChartView(this, points, connected), new LinearLayout.LayoutParams(-1, Ui.dp(this, 180))); body.addView(card);
    }

    private void chooseProfile() {
        List<JSONObject> profiles = store.records("profile");
        List<String> names = profiles.stream().map(p -> p.optString("name") + " / " + p.optString("role")).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        names.add("+ Add baby or parent");
        new AlertDialog.Builder(this).setTitle("Your nest").setItems(names.toArray(new String[0]), (d, which) -> {
            if (which == profiles.size()) profileEditor(null);
            else { selectProfile(profiles.get(which).optString("id")); render(); }
        }).show();
    }

    private void profileEditor(JSONObject existing) {
        LinearLayout content = Ui.column(this); int p = Ui.dp(this, 20); content.setPadding(p, p, p, p);
        EditText name = Ui.input(this, "Name", existing == null ? "" : existing.optString("name"), false); content.addView(name);
        content.addView(Ui.text(this, "Profile type", 13, Ui.MUTED));
        Spinner role = spinner(List.of("Baby", "Parent")); if (existing != null && existing.optString("role").equals("Parent")) role.setSelection(1);
        content.addView(role);
        content.addView(Ui.text(this, "Birth date / due date (optional)", 13, Ui.MUTED));
        EditText dob = Ui.input(this, "yyyy-MM-dd", existing == null ? "" : existing.optString("dob"), false);
        dob.setFocusable(false); dob.setOnClickListener(v -> {
            LocalDate initial = LocalDate.now();
            new DatePickerDialog(this, (view, y, m, d) -> dob.setText(LocalDate.of(y, m + 1, d).toString()),
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
        }); content.addView(dob);
        content.addView(Ui.text(this, "Adjusted birth date / due date (optional)", 13, Ui.MUTED));
        EditText adjusted = Ui.input(this, "Adjusted birth date", existing == null ? "" : existing.optString("adjustedDob"), false);
        adjusted.setFocusable(false); adjusted.setOnClickListener(v -> {
            LocalDate initial = adjusted.getText().toString().isEmpty() ? LocalDate.now() : LocalDate.parse(adjusted.getText().toString());
            new DatePickerDialog(this, (view, y, m, d) -> adjusted.setText(LocalDate.of(y, m + 1, d).toString()),
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
        }); content.addView(adjusted);
        content.addView(Ui.button(this, "Clear adjusted birth date", () -> adjusted.setText("")));
        content.addView(Ui.text(this, "Sex (optional)", 13, Ui.MUTED));
        Spinner sex = spinner(List.of("Not recorded", "Female", "Male", "Other"));
        if (existing != null) for (int i = 0; i < 4; i++) if (sex.getItemAtPosition(i).equals(existing.optString("sex"))) sex.setSelection(i);
        content.addView(sex);
        content.addView(Ui.text(this, "Parent stage", 13, Ui.MUTED));
        Spinner stage = spinner(List.of("Pregnancy", "Postpartum", "Parent"));
        if (existing != null) for (int i = 0; i < 3; i++) if (stage.getItemAtPosition(i).equals(existing.optString("stage"))) stage.setSelection(i);
        content.addView(stage);
        ScrollView profileScroll = new ScrollView(this); profileScroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "Add a profile" : "Edit profile").setView(profileScroll)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (name.getText().toString().isBlank()) { name.setError("A name is required"); return; }
            JSONObject data = existing == null ? new JSONObject() : Records.copy(existing);
            Records.put(data, "entity", "profile"); Records.put(data, "name", name.getText().toString().trim());
            Records.put(data, "role", role.getSelectedItem().toString()); Records.put(data, "dob", dob.getText().toString());
            Records.put(data, "stage", stage.getSelectedItem().toString());
            Records.put(data, "sex", sex.getSelectedItem().toString()); Records.put(data, "adjustedDob", adjusted.getText().toString());
            selectProfile(store.save(existing == null ? null : existing.optString("id"), data, false));
            dialog.dismiss(); changed();
        });
    }
    private Spinner spinner(List<String> values) {
        Spinner spinner = new Spinner(this); spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values)); return spinner;
    }

    private void family() {
        body.addView(Ui.title(this, "Your people. Your data.", 24));
        body.addView(Ui.text(this, "Two caregivers, separate Google accounts, one private nest. No app backend.", 15, Ui.MUTED));
        LinearLayout people = Ui.card(this, Ui.SAGE);
        people.addView(Ui.title(this, "Profiles & preferences", 20));
        people.addView(Ui.button(this, "Add baby or parent", () -> profileEditor(null)));
        if (profile() != null && profile().optString("role").equals("Baby"))
            people.addView(Ui.button(this, "Care plan & advance alerts", () -> CarePlanUi.settings(this, profile())));
        if (!CareReminders.error(this).isEmpty()) people.addView(Ui.text(this, CareReminders.error(this), 14, Ui.INK));
        if (profile() != null && !profile().optString("sex").isBlank()) people.addView(Ui.text(this, "Sex: " + profile().optString("sex"), 13, Ui.MUTED));
        if (profile() != null && !profile().optString("adjustedDob").isBlank()) {
            JSONObject adjustedProfile = Records.copy(profile()); Records.put(adjustedProfile, "dob", adjustedProfile.optString("adjustedDob"));
            people.addView(Ui.text(this, "Adjusted birth date: " + profile().optString("adjustedDob") + " / adjusted age: " + age(adjustedProfile), 13, Ui.MUTED));
        }
        if (profile() != null) people.addView(Ui.button(this, "Edit " + profile().optString("name"), () -> profileEditor(profile())));
        if (profile() != null) people.addView(Ui.button(this, "Delete current profile", () -> {
            JSONObject selected = profile();
            new AlertDialog.Builder(this).setTitle("Delete " + selected.optString("name") + "?")
                .setMessage("This removes the profile and its logs from both phones after sync. Historical revisions remain in backups and Drive. Export a backup first.")
                .setPositiveButton("Delete profile", (d, w) -> {
                    for (JSONObject event : store.events(selected.optString("id"))) {
                        store.save(event.optString("id"), event, true); Reminders.cancel(this, event.optString("id"));
                    }
                    store.save(selected.optString("id"), selected, true); selectProfile(""); changed();
                }).setNegativeButton("Cancel", null).show();
        }));
        people.addView(Ui.button(this, "Caregiver name: " + prefs.getString("caregiver", "Caregiver"), () -> prompt("Your caregiver name", "Shown on new logs", prefs.getString("caregiver", ""), value -> {
            if (value.isBlank()) { error("Enter a caregiver name"); return; }
            prefs.edit().putString("caregiver", value.trim()).apply(); render();
        })));
        people.addView(Ui.button(this, prefs.getBoolean("imperial", false) ? "Display units: imperial" : "Display units: metric", () -> {
            prefs.edit().putBoolean("imperial", !prefs.getBoolean("imperial", false)).apply(); render();
        }));
        people.addView(Ui.button(this, "Choose visible activities", this::chooseActivities));
        body.addView(people);
        LinearLayout drive = Ui.card(this, Ui.BLUE);
        drive.addView(Ui.title(this, "Google Drive sync", 20));
        String folder = prefs.getString("folder", "");
        if (folder.isEmpty()) drive.addView(Ui.text(this, "Not connected. All your logs are safely stored on this phone. Google OAuth setup is required; see the project wiki.", 14, Ui.MUTED));
        else {
            drive.addView(Ui.text(this, "Folder: " + folder, 12, Ui.MUTED));
            long last = prefs.getLong("lastSync", 0);
            drive.addView(Ui.text(this, last > 0 ? "Last sync: " + EventEditor.format(last) : "Waiting for first sync", 13, Ui.MUTED));
        }
        if (!prefs.getString("syncError", "").isEmpty()) drive.addView(Ui.text(this, "Needs attention: " + prefs.getString("syncError", ""), 14, Ui.INK));
        if (working) drive.addView(Ui.text(this, "Working with Drive...", 14, Ui.GREEN));
        drive.addView(Ui.button(this, "Connect / reconnect Google", () -> authorize(true, () -> {
            Toast.makeText(this, "Google Drive authorized", Toast.LENGTH_SHORT).show();
            if (!prefs.getString("folder", "").isEmpty()) sync(); else render();
        })));
        if (folder.isEmpty()) {
            drive.addView(Ui.button(this, "1. Create a shared nest", () -> authorize(true, this::createNest)));
            drive.addView(Ui.button(this, "Or join partner's nest", this::joinNest));
        } else {
            drive.addView(Ui.button(this, "2. Invite partner by Google email", () -> prompt("Invite your partner", "Their Google account email. Only two accounts are permitted.", "", email ->
                authorize(true, () -> runDrive(() -> { new DriveApi(token).invite(folder, email.trim()); return "Partner invited. Send them the folder ID."; }, this::render)))));
            drive.addView(Ui.button(this, "Copy pairing folder ID", () -> {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Little Nest folder", folder));
                Toast.makeText(this, "Folder ID copied. Your partner also needs an email invitation.", Toast.LENGTH_LONG).show();
            }));
            drive.addView(Ui.button(this, "Sync now", () -> authorize(true, this::sync)));
            drive.addView(Ui.button(this, "Disconnect this phone", () -> new AlertDialog.Builder(this).setTitle("Disconnect Drive?")
                .setMessage("Local logs stay here. This does not remove anyone's folder access. Remove the partner in Google Drive if needed. Do not connect a different family without exporting and clearing app storage.")
                .setPositiveButton("Disconnect", (d, w) -> {
                    if (working || SyncEngine.active()) { error("Wait for the current Drive operation to finish."); return; }
                    prefs.edit().remove("folder").remove("lastSync").remove("syncError").apply();
                    getSystemService(JobScheduler.class).cancel(7); token = null; render();
                }).setNegativeButton("Cancel", null).show()));
        }
        drive.addView(Ui.text(this, "Sync checks private folder permissions (owner + one editor). Both people can read all profiles, parent logs and photos. Foreground checks every minute; background sync is best-effort, typically 15+ minutes. Manual sync is available.", 12, Ui.MUTED));
        body.addView(drive);
        LinearLayout data = Ui.card(this, Ui.PEACH);
        data.addView(Ui.title(this, "Keep a copy", 20));
        data.addView(Ui.button(this, importing ? "Import in progress..." : "Import Nara Baby CSV", () -> {
            if (importing) { error("Wait for the current import to finish."); return; }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT_NARA);
        }));
        data.addView(Ui.button(this, "Export this profile as CSV", () -> export(false)));
        data.addView(Ui.button(this, "Export complete JSON backup", () -> export(true)));
        data.addView(Ui.button(this, "Restore / merge JSON backup", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT);
        }));
        data.addView(Ui.text(this, "Exports contain private health information. JSON includes photos and deleted historical revisions. Share only with people you trust.", 12, Ui.MUTED));
        body.addView(data);
        LinearLayout reminders = Ui.card(this, Ui.LAVENDER);
        reminders.addView(Ui.title(this, "Reminders", 19));
        reminders.addView(Ui.button(this, "Enable notifications", this::requestNotifications));
        if (!prefs.getString("reminderError", "").isEmpty()) reminders.addView(Ui.text(this, prefs.getString("reminderError", ""), 13, Ui.INK));
        reminders.addView(Ui.text(this, "Add reminders inside any activity. Android may delay them during battery-saving modes. They are not suitable for time-critical medication alarms.", 13, Ui.MUTED));
        body.addView(reminders);
        body.addView(Ui.text(this, "Little Nest 1.1 / independent, original app\nInspired by publicly documented baby-tracking workflows. Not affiliated with Nara. No ads, analytics, subscriptions, or non-Google servers.", 12, Ui.MUTED));
    }

    private void chooseActivities() {
        List<Catalog.Kind> kinds = Catalog.KINDS;
        Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", Set.of()));
        boolean[] selected = new boolean[kinds.size()];
        for (int i = 0; i < selected.length; i++) selected[i] = !hidden.contains(kinds.get(i).id());
        new AlertDialog.Builder(this).setTitle("Show activities on Today")
            .setMultiChoiceItems(kinds.stream().map(Catalog.Kind::title).toArray(String[]::new), selected, (d, which, checked) -> {
                if (checked) hidden.remove(kinds.get(which).id()); else hidden.add(kinds.get(which).id());
            }).setPositiveButton("Save", (d, w) -> { prefs.edit().putStringSet("hidden", hidden).apply(); render(); })
            .setNegativeButton("Cancel", null).show();
    }

    private interface TextAction { void accept(String text); }
    private void prompt(String title, String hint, String value, TextAction action) {
        EditText input = Ui.input(this, hint, value, false);
        int pad = Ui.dp(this, 20); LinearLayout content = Ui.column(this); content.setPadding(pad, pad, pad, pad);
        content.addView(Ui.text(this, hint, 14, Ui.MUTED)); content.addView(input);
        new AlertDialog.Builder(this).setTitle(title).setView(content).setPositiveButton("Continue", (d, w) -> action.accept(input.getText().toString())).setNegativeButton("Cancel", null).show();
    }

    void openEditor(Catalog.Kind kind, JSONObject existing) {
        JSONObject profile = profile(); if (profile == null) { error("Create a profile first"); return; }
        if (existing == null) {
            existing = new JSONObject();
            if (prefs.getBoolean("imperial", false)) {
                Records.put(existing, "unit", "fl oz"); Records.put(existing, "weightUnit", "lb"); Records.put(existing, "lengthUnit", "in");
                if (kind.id().equals("temperature")) Records.put(existing, "unit", "F");
            }
        }
        editor = new EventEditor(this, profileId, kind, existing); editor.show();
    }

    void choosePhoto() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PHOTO);
    }

    void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 104);
        else Toast.makeText(this, "Notifications are enabled. Check Android channel settings if reminders are muted.", Toast.LENGTH_LONG).show();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(code, permissions, grantResults);
        if (code == 104) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            prefs.edit().putString("reminderError", granted ? "" : "Notifications are disabled. Enable them in Android settings.").apply();
            if (!granted) error("Reminders cannot notify you until notifications are enabled.");
            CareReminders.refresh(this);
        }
    }

    private void authorize(boolean interactive, Runnable after) {
        if (authorizing || working || SyncEngine.active()) {
            if (interactive) Toast.makeText(this, "Please wait for the current sync", Toast.LENGTH_SHORT).show();
            return;
        }
        if (token != null && System.currentTimeMillis() - tokenAt < 45 * 60_000L) { after.run(); return; }
        authorizing = true; afterAuthorization = after;
        var request = AuthorizationRequest.builder().setRequestedScopes(List.of(new Scope(DriveApi.SCOPE))).build();
        Identity.getAuthorizationClient(this).authorize(request).addOnSuccessListener(this, result -> {
            if (result.hasResolution()) {
                if (!interactive) {
                    authorizing = false; prefs.edit().putString("syncError", "Reconnect Google Drive in Family to resume sync.").apply(); return;
                }
                try {
                    if (result.getPendingIntent() == null) throw new IllegalStateException("Google did not provide a consent screen");
                    startIntentSenderForResult(result.getPendingIntent().getIntentSender(), AUTH, null, 0, 0, 0);
                } catch (android.content.IntentSender.SendIntentException | IllegalStateException e) { authorizing = false; error("Cannot open Google consent: " + e.getMessage()); }
            } else authorized(result);
        }).addOnFailureListener(this, e -> {
            authorizing = false;
            prefs.edit().putString("syncError", "Google authorization failed. Check Play services and OAuth setup (" + e.getClass().getSimpleName() + ").").apply();
            if (interactive) error("Google authorization failed: " + e.getMessage() + "\nSee wiki/Google-Drive-Setup.md for OAuth setup.");
        });
    }
    private void authorized(AuthorizationResult result) {
        authorizing = false;
        token = result.getAccessToken(); tokenAt = System.currentTimeMillis();
        if (token == null || token.isBlank()) { error("Google returned no access token. Grant Drive access and retry."); return; }
        Runnable after = afterAuthorization; afterAuthorization = null; if (after != null) after.run();
    }
    private void createNest() {
        if (!prefs.getString("folder", "").isEmpty()) { error("Disconnect the existing folder first."); return; }
        runDrive(() -> {
            String folder = new DriveApi(token).createFolder();
            prefs.edit().putString("folder", folder).apply();
            SyncJob.schedule(this); return "Nest created. Invite your partner next.";
        }, this::sync);
    }
    private void joinNest() {
        prompt("Join your partner", "Paste their Little Nest folder ID or Google Drive folder link. They must invite your Google email first. Your existing local logs will be shared with them.", "", value -> {
            final String folder;
            try { folder = DriveApi.folderId(value); }
            catch (IllegalArgumentException e) { error(e.getMessage()); return; }
            authorize(true, () -> runDrive(() -> {
                new DriveApi(token).validateFolder(folder);
                prefs.edit().putString("folder", folder).apply(); SyncJob.schedule(this);
                return "Nest connected";
            }, this::sync));
        });
    }
    private interface DriveWork { String run() throws Exception; }
    private void runDrive(DriveWork work, Runnable after) {
        runDrive(work, after, true);
    }
    private void runDrive(DriveWork work, Runnable after, boolean announce) {
        if (working) { error("Wait for the current Drive operation."); return; }
        working = true; render();
        executor.execute(() -> {
            try {
                String result = work.run();
                runOnUiThread(() -> {
                    working = false; if (isDestroyed()) return;
                    if (announce) Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                    render(); if (after != null) after.run();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                prefs.edit().putString("syncError", message).apply();
                if (token != null) Identity.getAuthorizationClient(this).clearToken(ClearTokenRequest.builder().setToken(token).build())
                    .addOnFailureListener(failure -> android.util.Log.w("LittleNest", "Could not clear authorization cache: " + failure.getClass().getSimpleName()));
                token = null;
                runOnUiThread(() -> { working = false; if (!isDestroyed()) { render(); if (announce) error(message); } });
            }
        });
    }
    private void sync() { sync(true); }
    private void sync(boolean announce) { runDrive(() -> SyncEngine.sync(this, token), null, announce); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == AUTH) {
            authorizing = false;
            if (result != RESULT_OK || data == null) { error("Google consent was cancelled. Local tracking still works."); return; }
            try { authorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)); }
            catch (ApiException e) { error("Google authorization failed (" + e.getStatusCode() + "). Check the Android OAuth package and signing SHA-1."); }
        } else if (result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (request == PHOTO && editor != null) {
                EventEditor target = editor;
                executor.execute(() -> {
                    try {
                        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
                        Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                            double scale = Math.min(1, 1000.0 / Math.max(width, height));
                            decoder.setTargetSize(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)));
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        });
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 75, bytes)) throw new java.io.IOException("Image could not be encoded");
                        bitmap.recycle();
                        if (bytes.size() > 750_000) throw new java.io.IOException("Photo is too large. Choose a smaller image.");
                        String photo = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
                        runOnUiThread(() -> { if (!isDestroyed() && editor == target) target.setPhoto(photo); });
                    } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) error("Cannot attach photo: " + e.getMessage()); }); }
                });
            } else if (request == IMPORT_NARA) {
                readNara(uri);
            } else if (request == IMPORT) {
                new AlertDialog.Builder(this).setTitle("Merge this backup?")
                    .setMessage("All profiles, photos and revisions in the backup will merge into this phone and sync to your partner. This cannot be undone.")
                    .setPositiveButton("Merge", (d, w) -> executor.execute(() -> {
                        try (InputStream input = getContentResolver().openInputStream(uri)) {
                            if (input == null) throw new java.io.IOException("Cannot open backup");
                            String json = new String(DriveApi.readBounded(input, 100_000_000), StandardCharsets.UTF_8);
                            List<JSONObject> operations = Records.parseBackup(json);
                            store.merge(operations); Reminders.scheduleAll(this);
                            runOnUiThread(() -> { if (!isDestroyed()) { changed(); Toast.makeText(this, "Backup merged", Toast.LENGTH_LONG).show(); } });
                        } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) error("Backup was not imported: " + e.getMessage()); }); }
                    })).setNegativeButton("Cancel", null).show();
            }
        }
    }

    private void readNara(Uri uri) {
        if (importing) { error("Wait for the current import to finish."); return; }
        importing = true;
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("Reading Nara CSV")
            .setMessage("Validating all records locally. Nothing has been saved yet.").setCancelable(false).show();
        executor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("Cannot open CSV");
                byte[] bytes = DriveApi.readBounded(input, 25_000_000);
                var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                NaraImport.Preview preview = NaraImport.parse(new java.io.InputStreamReader(new java.io.ByteArrayInputStream(bytes), decoder));
                runOnUiThread(() -> { progress.dismiss(); if (!isDestroyed()) previewNara(preview); });
            } catch (Exception e) {
                runOnUiThread(() -> { importing = false; progress.dismiss(); if (!isDestroyed()) error("CSV was not imported: " + e.getMessage()); });
            }
        });
    }

    private void previewNara(NaraImport.Preview preview) {
        LinearLayout content = Ui.column(this);
        int pad = Ui.dp(this, 20); content.setPadding(pad, pad, pad, pad);
        content.addView(Ui.text(this, preview.summary(), 14, Ui.INK));
        long unfinishedSleeps = preview.events().stream().filter(entry -> entry.data().optString("kind").equals("sleep") && entry.data().optBoolean("importIncomplete")).count();
        android.widget.CheckBox continueSleep = new android.widget.CheckBox(this);
        if (unfinishedSleeps > 0) {
            continueSleep.setText(getResources().getQuantityString(R.plurals.import_continue_sleep, (int) unfinishedSleeps, unfinishedSleeps));
            long now = System.currentTimeMillis();
            continueSleep.setChecked(preview.events().stream().filter(entry -> entry.data().optString("kind").equals("sleep") && entry.data().optBoolean("importIncomplete"))
                .allMatch(entry -> entry.data().optLong("start") <= now && now - entry.data().optLong("start") < 86_400_000));
            content.addView(continueSleep);
            content.addView(Ui.text(this, "Use this if the baby is still sleeping. Elapsed time includes time since the original Nara start, including time after export. For an old export, leave unchecked and correct the end time in History.", 13, Ui.MUTED));
        }
        content.addView(Ui.text(this, "\nChoose where each profile belongs. Existing activities, edits and deletions are never overwritten on reimport. Blank profile metadata can be filled in; existing names and values stay unchanged.\n\nAll imported data will sync to your partner if Drive is linked. Import once on one phone, then sync. Large histories can take time to upload.", 13, Ui.MUTED));
        List<JSONObject> existing = store.records("profile");
        java.util.Map<String, Spinner> choices = new java.util.LinkedHashMap<>();
        for (NaraImport.Profile profile : preview.profiles()) {
            content.addView(Ui.text(this, "\n" + profile.data().optString("name") + " / " + profile.data().optString("role"), 16, Ui.INK));
            List<String> names = new ArrayList<>(); names.add("Create imported profile (or reuse its source identity)");
            for (JSONObject candidate : existing) names.add("Use " + candidate.optString("name") + " / " + candidate.optString("role"));
            Spinner destination = spinner(names);
            for (int i = 0; i < existing.size(); i++)
                if (existing.get(i).optString("naraProfileKey").equals(profile.sourceKey())) { destination.setSelection(i + 1); break; }
            content.addView(destination); choices.put(profile.id(), destination);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog confirm = new AlertDialog.Builder(this).setTitle("Import Nara Baby CSV").setView(scroll)
            .setPositiveButton("Import", null).setNegativeButton("Cancel", (d, w) -> importing = false).create();
        confirm.setOnCancelListener(d -> importing = false); confirm.show();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean resumeSleep = continueSleep.isChecked();
            java.util.Map<String, String> targets = new java.util.LinkedHashMap<>();
            for (NaraImport.Profile profile : preview.profiles()) {
                int selection = choices.get(profile.id()).getSelectedItemPosition();
                targets.put(profile.id(), selection == 0 ? profile.id() : existing.get(selection - 1).optString("id"));
            }
            confirm.dismiss();
            AlertDialog progress = new AlertDialog.Builder(this).setTitle("Importing")
                .setMessage("Saving the validated history in one transaction. Please wait.").setCancelable(false).show();
            executor.execute(() -> {
                try {
                    Store.ImportResult result = store.importNara(preview.entries(targets, resumeSleep, System.currentTimeMillis()));
                    runOnUiThread(() -> {
                        importing = false; progress.dismiss();
                        if (!isDestroyed()) {
                            selectProfile(targets.get(preview.profiles().get(0).id())); changed();
                            new AlertDialog.Builder(this).setTitle("Import complete").setMessage(result.message()
                                + (resumeSleep ? "\n\nOngoing sleep was restored from its original start time for newly imported entries. Finish it from Today when the baby wakes." : "")
                                + (preview.incomplete() > 0 ? "\n\nSearch \"needs review\" in History for entries still flagged. Existing entries are never restarted on reimport." : ""))
                                .setPositiveButton("OK", null).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> { importing = false; progress.dismiss(); if (!isDestroyed()) error("CSV was not imported: " + e.getMessage()); });
                }
            });
        });
    }

    private void export(boolean backup) {
        if (!backup && profile() == null) { error("Create a profile first"); return; }
        String selectedProfile = profileId;
        executor.execute(() -> {
            try {
                String filename = backup ? "little-nest-backup.json" : "little-nest.csv";
                String content;
                if (backup) content = store.backup();
                else {
                    StringBuilder csv = new StringBuilder("Activity,Start,End,Caregiver,Details,Notes\r\n");
                    for (JSONObject event : store.events(selectedProfile)) {
                        JSONObject details = Records.copy(event); details.remove("photo"); details.remove("notes");
                        List<String> cells = List.of(Catalog.get(event.optString("kind")).title(), EventEditor.format(event.optLong("start")),
                            EventEditor.format(event.optLong("end")), event.optString("caregiver"), details.toString(), event.optString("notes"));
                        csv.append(cells.stream().map(Records::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
                    }
                    content = csv.toString();
                }
                try (FileOutputStream output = new FileOutputStream(new File(getCacheDir(), filename))) { output.write(content.getBytes(StandardCharsets.UTF_8)); }
                Uri uri = Uri.parse("content://" + getPackageName() + ".exports/" + filename);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    Intent share = new Intent(Intent.ACTION_SEND).setType(backup ? "application/json" : "text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    share.setClipData(ClipData.newRawUri("Export", uri));
                    startActivity(Intent.createChooser(share, "Save or share your " + (backup ? "backup" : "CSV")));
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) error("Export failed: " + e.getMessage()); }); }
        });
    }

    void error(String message) {
        if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setTitle("Please check").setMessage(message == null ? "An unexpected error occurred" : message).setPositiveButton("OK", null).show();
    }
}
