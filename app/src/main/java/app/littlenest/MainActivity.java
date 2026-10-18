package app.littlenest;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.job.JobScheduler;
import android.content.ClipData;
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
import net.openid.appauth.AuthorizationService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
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
    private boolean scrollTop;
    private boolean careSaved;
    private String renderedTab = "";
    private LocalDate historyDay;
    private int historyPage;
    private boolean importing;
    private AuthorizationService authorization;
    private LocalSync localSync;
    private String pendingPairing;
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
            if (!prefs.getString("oneDriveFolder", "").isEmpty() && !working && !authorizing && !importing && editor == null)
                authorize(false, () -> sync(false));
            handler.postDelayed(this, 60_000);
        }
    };
    private final Runnable localTick = new Runnable() {
        @Override public void run() {
            if (localSync != null && !importing && editor == null) localSync.sync();
            handler.postDelayed(this, 15_000);
        }
    };
    private final Runnable careTick = new Runnable() {
        @Override public void run() {
            if ((tab.equals("Today") || tab.equals("Plan")) && editor == null && !importing && !working) render();
            handler.postDelayed(this, 60_000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this); prefs = getSharedPreferences("settings", MODE_PRIVATE);
        authorization = new AuthorizationService(this);
        if (prefs.contains("folder")) {
            getSystemService(JobScheduler.class).cancel(7);
            prefs.edit().remove("folder").remove("lastSync").remove("syncError").putBoolean("googleSyncRemoved", true).apply();
        }
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
        localSync = new LocalSync(this, () -> runOnUiThread(() -> {
            if (!isDestroyed() && localSync != null && editor == null && !importing && !working) render();
        }));
        localSync.start(); handler.postDelayed(localTick, 1500);
        if (pendingPairing != null) { localSync.join(pendingPairing); pendingPairing = null; }
        CareReminders.refresh(this);
        handler.postDelayed(careTick, 60_000);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(ticker); handler.removeCallbacks(autoSync); handler.removeCallbacks(careTick); handler.removeCallbacks(localTick);
        if (localSync != null) { localSync.close(); localSync = null; }
        super.onPause();
    }
    @Override protected void onDestroy() { executor.shutdown(); authorization.dispose(); super.onDestroy(); }

    /** After the user logs care on Today, show the top so the new entry is visible in the weave. */
    void logged() { scrollTop = true; careSaved = tab.equals("Today"); changed(); }
    void changed() {
        render();
        CareReminders.refresh(this);
        if (localSync != null) { handler.removeCallbacks(localTick); handler.postDelayed(localTick, 1500); }
        if (!prefs.getString("oneDriveFolder", "").isEmpty()) {
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
        boolean navigating = !renderedTab.isEmpty() && !renderedTab.equals(tab);
        int scrollPosition = mainScroll != null && renderedTab.equals(tab) && !(scrollTop && tab.equals("Today")) ? mainScroll.getScrollY() : 0;
        scrollTop = false;
        renderedTab = tab;
        Ui.apply(this);
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
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 6));
        LinearLayout masthead = Ui.row(this);
        ImageView mark = new ImageView(this); mark.setImageDrawable(new NestIcon(this, "nest", Ui.GREEN, Ui.PEACH, 24));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
        markParams.setMarginEnd(Ui.dp(this, 8)); masthead.addView(mark, markParams);
        TextView wordmark = Ui.title(this, "Little Nest", 15); wordmark.setTextColor(Ui.GREEN);
        masthead.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        boolean shared = !prefs.getString("oneDriveFolder", "").isEmpty() || !prefs.getString("localPeer", "").isEmpty();
        boolean syncProblem = (!prefs.getString("oneDriveFolder", "").isEmpty() && !prefs.getString("syncError", "").isEmpty())
            || (!prefs.getString("localPeer", "").isEmpty() && !prefs.getString("localError", "").isEmpty());
        LinearLayout status = Ui.row(this);
        status.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 14), 0); status.setMinimumHeight(Ui.dp(this, 48));
        status.addView(Ui.dot(this, syncProblem ? Ui.DANGER : shared ? Ui.HEALTH : Ui.MUTED, 8));
        status.addView(Ui.medium(this, syncProblem ? "Sync needs attention" : shared ? "Shared nest" : "On this phone", 13, syncProblem ? Ui.DANGER : Ui.MUTED));
        status.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 24));
        status.setOnClickListener(v -> { tab = "Family"; render(); });
        status.setContentDescription((syncProblem ? "Sync needs attention" : shared ? "Shared nest" : "Stored on this phone only") + ". Open sharing settings.");
        masthead.addView(status); header.addView(masthead);
        LinearLayout branding = Ui.row(this);
        JSONObject profile = profile();
        if (profile != null) {
            LinearLayout identity = Ui.column(this);
            TextView name = Ui.title(this, profile.optString("name") + "  \u2304", 28);
            android.text.SpannableString chevron = new android.text.SpannableString(name.getText());
            int caret = chevron.length() - 1;
            chevron.setSpan(new android.text.style.RelativeSizeSpan(.6f), caret, caret + 1, 0);
            chevron.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED), caret, caret + 1, 0);
            chevron.setSpan(new android.text.style.SuperscriptSpan(), caret, caret + 1, 0);
            name.setText(chevron);
            name.setPadding(0, 0, 0, 0); identity.addView(name);
            TextView date = Ui.text(this, age(profile) + "  \u00b7  " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())), 14, Ui.MUTED);
            date.setPadding(0, 0, 0, 0); identity.addView(date);
            branding.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            branding.setMinimumHeight(Ui.dp(this, 64));
            branding.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
            branding.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 16));
            branding.setContentDescription("Switch profile: " + profile.optString("name"));
            branding.setOnClickListener(v -> chooseProfile());
            header.addView(branding);
        }
        root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        mainScroll = scroll;
        body = Ui.column(this); body.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 28)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (profile == null && !tab.equals("Family")) welcome();
        else switch (tab) {
            case "History" -> history();
            case "Trends" -> trends();
            case "Plan" -> CarePlanUi.plan(this, body, profile, store.events(profileId));
            case "Family" -> family();
            default -> today();
        }
        View navEdge = new View(this); navEdge.setBackgroundColor(Ui.LINE);
        root.addView(navEdge, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        LinearLayout nav = Ui.row(this); nav.setBackgroundColor(Ui.SURFACE);
        nav.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 12));
        for (String item : List.of("Today", "Plan", "History", "Trends", "Family")) {
            boolean selected = tab.equals(item);
            Button button = new Button(this); button.setText(item); button.setAllCaps(false); button.setTextSize(11);
            button.setOnClickListener(v -> { tab = item; filter = ""; historyPage = 0; render(); });
            button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            button.setTextColor(selected ? Ui.INK : Ui.MUTED);
            button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.RIPPLE), null,
                Ui.shape(this, android.graphics.Color.BLACK, 16)));
            button.setStateListAnimator(null);
            android.graphics.drawable.GradientDrawable pill = Ui.shape(this, selected ? Ui.SAGE : android.graphics.Color.TRANSPARENT, 16);
            android.graphics.drawable.LayerDrawable indicator = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                pill, new NestIcon(this, item, selected ? Ui.GREEN : Ui.MUTED, selected ? Ui.SAGE : Ui.SURFACE, 22)});
            int iconInsetX = Ui.dp(this, 21), iconInsetY = Ui.dp(this, 5);
            indicator.setLayerInset(1, iconInsetX, iconInsetY, iconInsetX, iconInsetY);
            indicator.setBounds(0, 0, Ui.dp(this, 64), Ui.dp(this, 32));
            button.setCompoundDrawables(null, indicator, null, null);
            button.setCompoundDrawablePadding(Ui.dp(this, 4));
            button.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0); button.setMinHeight(Ui.dp(this, 56)); button.setMinimumHeight(Ui.dp(this, 56));
            button.setSelected(selected);
            nav.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
            if (selected && navigating && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                button.setScaleX(.92f); button.setScaleY(.92f);
                button.animate().scaleX(1).scaleY(1).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
        }
        root.addView(nav);
        scroll.post(() -> scroll.scrollTo(0, scrollPosition));
    }

    void showPlan() { tab = "Plan"; render(); }

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
        Ui.gap(body, 12);
        body.addView(new NestScene(this, "family", false), new LinearLayout.LayoutParams(-1, Ui.dp(this, 144)));
        Ui.gap(body, 12);
        body.addView(Ui.title(this, "Small moments.\nA little more peace.", 32));
        TextView intro = Ui.text(this, "A calm place for feeds, sleep, little firsts, and you. Private, offline, and shared with just your person.", 17, Ui.MUTED);
        body.addView(intro);
        Ui.gap(body, 18);
        body.addView(Ui.primary(this, "Create a profile", () -> profileEditor(null)));
        body.addView(Ui.button(this, "Join partner's nest", () -> { tab = "Family"; render(); withCaregiverName(this::scanPartner); }));
        Ui.gap(body, 8);
        body.addView(Ui.text(this, "Start with a baby or parent profile. Add twins and other children whenever you need.", 14, Ui.MUTED));
        Ui.stitch(body);
        body.addView(Ui.text(this, "No ads. No tracking servers. Your data stays here unless you enable local or OneDrive sync.", 14, Ui.MUTED));
    }

    private void today() {
        JSONObject profile = profile(); if (profile == null) { welcome(); return; }
        List<JSONObject> events = store.events(profileId);
        long now = System.currentTimeMillis();
        boolean parent = profile.optString("role").equals("Parent");
        body.addView(Ui.illustratedHeading(this, "A day at a time", 22, "nest", careSaved));
        careSaved = false;
        for (JSONObject event : events) if (event.optBoolean("running") || event.optBoolean("timerPaused")) timerCard(event);
        Insights.Day day = Insights.day(events, LocalDate.now(), ZoneId.systemDefault(), now);
        List<CarePredictor.Window> plans = parent ? List.of()
            : CarePredictor.predict(events, CareReminders.options(this, profileId).intervals(), now, ZoneId.systemDefault());
        LinearLayout weave = Ui.panel(this);
        weave.addView(new DayWeaveView(this, events, plans, now), new LinearLayout.LayoutParams(-1, Ui.dp(this, 112)));
        weave.addView(legend(DayWeaveView.visible(events, now)));
        Ui.divider(weave); Ui.gap(weave, 4);
        if (!parent) {
            JSONObject feed = events.stream().filter(e -> List.of("breast", "bottle", "solids").contains(e.optString("kind")) && !e.optBoolean("importIncomplete")).findFirst().orElse(null);
            JSONObject latestBreast = events.stream().filter(e -> e.optString("kind").equals("breast")).findFirst().orElse(null);
            LinearLayout feedFact = fact(weave, Ui.FEED, feed == null ? "No feeds logged yet"
                : feed.optBoolean("running") ? "Feeding now" : now - feed.optLong("start") < 60_000 ? "Fed just now" : "Fed " + Insights.duration(now - feed.optLong("start")) + " ago");
            if (latestBreast != null) feedFact.addView(Ui.text(this, "Last breast side: " + latestBreast.optString("side", "Not recorded"), 13, Ui.MUTED));
            JSONObject sleep = events.stream().filter(e -> e.optString("kind").equals("sleep") && !e.optBoolean("importIncomplete")).findFirst().orElse(null);
            fact(weave, Ui.SLEEP, sleep == null ? "No sleep logged yet" : sleep.optBoolean("running") ? "Sleeping now" : "Awake for " + Insights.duration(now - sleep.optLong("end")));
            fact(weave, Ui.DIAPER, day.wet() + " wet, " + day.dirty() + " dirty today");
        }
        TextView totals = Ui.figures(Ui.text(this, parent
            ? events.stream().filter(e -> Insights.date(e.optLong("start")).equals(LocalDate.now())).count() + " moments logged today  \u00b7  " + Insights.duration(day.sleepMs()) + " sleep"
            : "Today  " + day.feeds() + " feeds  \u00b7  " + milk(day.milkMl()) + " bottle  \u00b7  " + Insights.duration(day.sleepMs()) + " sleep", 13, Ui.MUTED));
        totals.setPadding(0, Ui.dp(this, 8), 0, 0); weave.addView(totals);
        body.addView(weave);
        if ((!prefs.getString("oneDriveFolder", "").isEmpty() && !prefs.getString("syncError", "").isEmpty())
            || (!prefs.getString("localPeer", "").isEmpty() && !prefs.getString("localError", "").isEmpty()))
            body.addView(Ui.button(this, "Sync needs attention - open Family", () -> { tab = "Family"; render(); }));
        CarePlanUi.addToday(this, body, profile, events);
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
        body.addView(Ui.section(this, "Recent moments"));
        List<JSONObject> recent = events.stream().filter(e -> Insights.date(e.optLong("start")).equals(LocalDate.now())).limit(6).toList();
        if (recent.isEmpty()) body.addView(Ui.text(this, "A fresh page. Your first log will appear here.", 15, Ui.MUTED));
        LinearLayout list = Ui.panel(this);
        for (JSONObject event : recent) eventCard(list, event);
        if (!recent.isEmpty()) body.addView(list);
    }
    /** One dot per thread colour actually drawn in the weave, so the key never claims a lane is a single colour. */
    private TextView legend(List<JSONObject> shown) {
        java.util.LinkedHashMap<Integer, String> threads = new java.util.LinkedHashMap<>();
        for (JSONObject event : shown) {
            Catalog.Kind kind = Catalog.get(event.optString("kind"));
            threads.putIfAbsent(Ui.thread(kind), Ui.threadLabel(kind));
        }
        android.text.SpannableStringBuilder key = new android.text.SpannableStringBuilder();
        for (var thread : threads.entrySet()) {
            if (key.length() > 0) key.append("    ");
            int start = key.length(); key.append("\u25cf");
            key.setSpan(new android.text.style.ForegroundColorSpan(thread.getKey()), start, start + 1, 0);
            key.append("\u00a0").append(thread.getValue().replace(" ", "\u00a0"));
        }
        if (key.length() == 0) key.append("Nothing logged in the last 18 hours");
        TextView legend = Ui.text(this, "", 12, Ui.MUTED); legend.setText(key);
        legend.setLineSpacing(Ui.dp(this, 4), 1); legend.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        return legend;
    }
    private LinearLayout fact(LinearLayout target, int color, String value) {
        LinearLayout row = Ui.row(this); row.setGravity(Gravity.TOP); row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        View dot = Ui.dot(this, color, 10); ((LinearLayout.LayoutParams) dot.getLayoutParams()).topMargin = Ui.dp(this, 10);
        ((LinearLayout.LayoutParams) dot.getLayoutParams()).setMarginEnd(Ui.dp(this, 12));
        row.addView(dot);
        LinearLayout text = Ui.column(this);
        text.addView(Ui.figures(Ui.medium(this, value, 17, Ui.INK)));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        target.addView(row); return text;
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
        LinearLayout card = Ui.column(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 4)); card.setLayoutParams(cardParams);
        LinearLayout heading = Ui.row(this);
        heading.addView(Ui.title(this, title, 19), new LinearLayout.LayoutParams(0, -2, 1));
        Button add = Ui.quiet(this, "Add", () -> {
            if (kinds.size() == 1) openEditor(kinds.get(0), null);
            else new AlertDialog.Builder(this).setTitle("Log " + title.toLowerCase(Locale.ROOT))
                .setItems(kinds.stream().map(Catalog.Kind::title).toArray(String[]::new),
                    (dialog, which) -> openEditor(kinds.get(which), null)).show();
        });
        add.setContentDescription("Add " + title.toLowerCase(Locale.ROOT));
        add.setCompoundDrawables(new NestIcon(this, "add", Ui.GREEN, android.graphics.Color.TRANSPARENT, 18), null, null, null);
        add.setCompoundDrawablePadding(Ui.dp(this, 4));
        heading.addView(add);
        card.addView(heading);
        LinearLayout tiles = null;
        int index = 0;
        long now = System.currentTimeMillis();
        for (Catalog.Kind kind : kinds) {
            if (index % 2 == 0) {
                tiles = Ui.row(this); tiles.setGravity(android.view.Gravity.TOP); card.addView(tiles);
            }
            int thread = Ui.thread(kind);
            LinearLayout tile = Ui.column(this);
            int pad = Ui.dp(this, 14); tile.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
            tile.setMinimumHeight(Ui.dp(this, 112));
            android.graphics.drawable.GradientDrawable face = Ui.shape(this, Ui.SURFACE, 20);
            if (!Ui.night) face.setStroke(Ui.dp(this, 1), Ui.LINE);
            tile.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.RIPPLE), face, null));
            JSONObject latest = events.stream().filter(e -> e.optString("kind").equals(kind.id())).findFirst().orElse(null);
            LinearLayout top = Ui.row(this);
            ImageView icon = new ImageView(this); icon.setImageDrawable(new NestIcon(this, kind.id(), thread, Ui.activityColor(kind), 28));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setBackground(Ui.shape(this, Ui.activityColor(kind), 16)); icon.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
            top.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
            String value = latest == null ? "" : latest.optBoolean("importIncomplete") ? "Review"
                : kind.id().equals("bottle") ? milk(Insights.bottleMl(latest))
                : kind.id().equals("pump") ? milk(Insights.pumpMl(latest))
                : kind.timer() ? Insights.duration(Records.duration(latest, now))
                : kind.id().equals("diaper") ? latest.optString("diaper") : "";
            TextView amount = Ui.figures(Ui.medium(this, value, 13, Ui.GREEN)); amount.setGravity(android.view.Gravity.END);
            top.addView(amount, new LinearLayout.LayoutParams(0, -2, 1)); tile.addView(top);
            Ui.gap(tile, 6);
            TextView label = Ui.title(this, kind.title(), 16); label.setPadding(0, 0, 0, 0); tile.addView(label);
            String detail = latest == null ? "Not logged yet" : latest.optBoolean("running") ? "Timer running"
                : Insights.duration(now - latest.optLong("start")) + " ago";
            TextView when = Ui.figures(Ui.text(this, detail, 13, Ui.MUTED)); when.setPadding(0, Ui.dp(this, 2), 0, 0); tile.addView(when);
            tile.setOnClickListener(v -> openEditor(kind, null));
            tile.setFocusable(true); tile.setContentDescription("Log " + kind.title() + ". " + detail);
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -2, 1);
            tileParams.setMargins(index % 2 == 0 ? 0 : Ui.dp(this, 5), Ui.dp(this, 5), index % 2 == 0 ? Ui.dp(this, 5) : 0, Ui.dp(this, 5));
            tiles.addView(tile, tileParams); index++;
        }
        if (index % 2 != 0 && index > 1) tiles.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        body.addView(card);
    }

    private String milk(double ml) {
        return prefs.getBoolean("imperial", false) ? String.format(Locale.getDefault(), "%.1f fl oz", ml / 29.5735295625)
            : String.format(Locale.getDefault(), "%.0f ml", ml);
    }
    private void timerCard(JSONObject event) {
        Catalog.Kind kind = Catalog.get(event.optString("kind"));
        boolean running = event.optBoolean("running");
        LinearLayout card = Ui.card(this, Ui.activityColor(kind));
        LinearLayout heading = Ui.row(this);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new NestIcon(this, kind.id(), Ui.thread(kind), Ui.activityColor(kind), 28));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
        iconParams.setMarginEnd(Ui.dp(this, 10)); heading.addView(icon, iconParams);
        heading.addView(Ui.medium(this, kind.title() + (running ? " \u00b7 running" : " \u00b7 paused"), 16, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(heading);
        TextView label = Ui.figures(Ui.title(this, timerText(event), 40)); label.setLetterSpacing(-0.02f); card.addView(label);
        timerLabels.add(label); timerEvents.add(event);
        LinearLayout controls = Ui.row(this);
        addControl(controls, running ? "Pause" : "Resume", () -> updateTimer(event, "toggle"));
        if (List.of("breast", "pump").contains(event.optString("kind"))) addControl(controls, "Switch side", () -> updateTimer(event, "side"));
        Button finish = Ui.primary(this, "Finish", () -> updateTimer(event, "finish"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        controls.addView(finish, params);
        card.addView(controls); body.addView(card);
    }
    private void addControl(LinearLayout row, String label, Runnable action) {
        Button button = Ui.button(this, label, () -> { scrollTop = true; action.run(); });
        if (Ui.SURFACE != Ui.BG) button.setBackground(Ui.ripple(this, Ui.SURFACE, 24));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0); row.addView(button, params);
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
        store.save(event.optString("id"), event, false);
        if (action.equals("finish")) logged(); else changed();
    }

    private void history() {
        TextView heading = Ui.title(this, "The story so far", 26); heading.setPadding(0, Ui.dp(this, 6), 0, 0); body.addView(heading);
        body.addView(Ui.text(this, "Tap a moment to edit, see details, or remove it.", 14, Ui.MUTED));
        LinearLayout dates = Ui.row(this); dates.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        Button previous = Ui.chip(this, "\u2039", false, () -> { historyDay = historyDay == null ? LocalDate.now().minusDays(1) : historyDay.minusDays(1); historyPage = 0; render(); });
        previous.setContentDescription("Previous day"); previous.setTextSize(20);
        dates.addView(previous, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        Button picker = Ui.chip(this, historyDay == null ? "All dates" : dayLabel(historyDay), historyDay != null, () -> {
            LocalDate initial = historyDay == null ? LocalDate.now() : historyDay;
            new DatePickerDialog(this, (v, y, m, d) -> { historyDay = LocalDate.of(y, m + 1, d); historyPage = 0; render(); },
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
        });
        picker.setContentDescription((historyDay == null ? "All dates" : dayLabel(historyDay)) + ". Choose a date");
        LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        pickerParams.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0); dates.addView(picker, pickerParams);
        Button next = Ui.chip(this, "\u203a", false, () -> { historyDay = historyDay == null ? LocalDate.now() : historyDay.plusDays(1); historyPage = 0; render(); });
        next.setContentDescription("Next day"); next.setTextSize(20);
        dates.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        if (historyDay != null) {
            Button all = Ui.quiet(this, "Show all", () -> { historyDay = null; historyPage = 0; render(); });
            all.setContentDescription("Show all dates"); dates.addView(all);
        }
        body.addView(dates);
        EditText search = Ui.input(this, "Search activity, notes, food, caregiver...", filter, false);
        search.setBackground(Ui.shape(this, Ui.night ? Ui.SURFACE : Ui.SAGE, 24));
        search.setCompoundDrawables(new NestIcon(this, "search", Ui.MUTED, android.graphics.Color.TRANSPARENT, 20), null, null, null);
        search.setCompoundDrawablePadding(Ui.dp(this, 10)); search.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        body.addView(search);
        LinearLayout list = Ui.column(this); body.addView(list);
        populateHistory(list);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter = s.toString(); historyPage = 0; populateHistory(list); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }
    private String dayLabel(LocalDate date) {
        if (date.equals(LocalDate.now())) return "Today";
        if (date.equals(LocalDate.now().minusDays(1))) return "Yesterday";
        return date.format(DateTimeFormatter.ofPattern(date.getYear() == LocalDate.now().getYear() ? "EEE d MMM" : "EEE d MMM yyyy", Locale.getDefault()));
    }
    private void populateHistory(LinearLayout list) {
        list.removeAllViews();
        long from = historyDay == null ? 0 : historyDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long to = historyDay == null ? 253402300800000L : historyDay.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<JSONObject> events = store.eventPage(profileId, filter, from, to, historyPage * 100);
        LocalDate shown = null; LinearLayout group = null;
        for (JSONObject event : events.subList(0, Math.min(100, events.size()))) {
            LocalDate date = Insights.date(event.optLong("start"));
            if (!date.equals(shown) || group == null) {
                shown = date;
                TextView label = Ui.medium(this, dayLabel(date), 14, Ui.MUTED);
                label.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, 0); list.addView(label);
                group = Ui.panel(this); list.addView(group);
            }
            eventCard(group, event);
        }
        if (events.isEmpty()) list.addView(Ui.text(this, "No moments match. Try a different date or search.", 15, Ui.MUTED));
        LinearLayout pages = Ui.row(this); pages.setGravity(Gravity.CENTER);
        if (historyPage > 0) pages.addView(Ui.quiet(this, "Previous 100", () -> { historyPage--; populateHistory(list); mainScroll.smoothScrollTo(0, 0); }));
        TextView page = Ui.text(this, "Page " + (historyPage + 1), 13, Ui.MUTED); page.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        pages.addView(page);
        if (events.size() > 100) pages.addView(Ui.quiet(this, "Next 100", () -> { historyPage++; populateHistory(list); mainScroll.smoothScrollTo(0, 0); }));
        list.addView(pages);
    }

    private String summary(JSONObject event) {
        Catalog.Kind kind = Catalog.get(event.optString("kind"));
        List<String> parts = new ArrayList<>();
        if (event.optBoolean("importIncomplete")) parts.add("NEEDS REVIEW: No completed duration was exported. Not a live timer.");
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
            parts.add("Imported activity; original time zone: " + event.optString("sourceTimeZone"));
            parts.add("Last updated by: " + event.optString("updatedBy"));
        }
        return String.join("\n", parts);
    }
    private void eventCard(LinearLayout target, JSONObject event) {
        Catalog.Kind kind = Catalog.get(event.optString("kind"));
        LinearLayout card = Ui.row(this); card.setGravity(Gravity.TOP);
        card.setPadding(0, Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10)); card.setMinimumHeight(Ui.dp(this, 56));
        card.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 12));
        if (target.getChildCount() > 0) Ui.divider(target);
        TextView time = Ui.figures(Ui.medium(this, java.time.Instant.ofEpochMilli(event.optLong("start")).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm")), 14, Ui.INK));
        card.addView(time, new LinearLayout.LayoutParams(Ui.dp(this, 50), -2));
        View dot = Ui.dot(this, Ui.thread(kind), 10);
        ((LinearLayout.LayoutParams) dot.getLayoutParams()).topMargin = Ui.dp(this, 9); card.addView(dot);
        LinearLayout content = Ui.column(this);
        content.addView(Ui.medium(this, kind.title() + (event.optBoolean("running") ? " \u00b7 running" : ""), 16, Ui.INK));
        String summary = summary(event);
        TextView detail = Ui.text(this, summary, 14, Ui.MUTED); detail.setMaxLines(3);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END); detail.setPadding(0, 0, 0, 0);
        if (!summary.isBlank()) content.addView(detail);
        TextView who = Ui.text(this, event.optString("caregiver", "Caregiver"), 12, Ui.MUTED); who.setPadding(0, Ui.dp(this, 2), 0, 0);
        content.addView(who);
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        card.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(kind.title()).setMessage(summary)
                .setPositiveButton("Edit", (d, w) -> {
                    if (event.optBoolean("running")) error("Pause the timer before editing.");
                    else openEditor(kind, event);
                })
                .setNeutralButton("Delete", (d, w) -> new AlertDialog.Builder(this).setTitle("Delete this moment?")
                    .setMessage("Deletion will sync to your partner. Historical revisions remain in backups and the sync log.")
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
        TextView heading = Ui.title(this, "Find your rhythm", 26); heading.setPadding(0, Ui.dp(this, 6), 0, 0); body.addView(heading);
        body.addView(Ui.text(this, "Last 7 days, by local calendar day", 14, Ui.MUTED));
        List<JSONObject> events = store.events(profileId);
        List<Insights.Day> days = new ArrayList<>();
        for (int i = 6; i >= 0; i--) days.add(Insights.day(events, LocalDate.now().minusDays(i), ZoneId.systemDefault(), System.currentTimeMillis()));
        long weekSleep = days.stream().mapToLong(Insights.Day::sleepMs).sum(), previousSleep = 0;
        int weekFeeds = days.stream().mapToInt(Insights.Day::feeds).sum(), previousFeeds = 0;
        for (int i = 7; i < 14; i++) {
            Insights.Day previous = Insights.day(events, LocalDate.now().minusDays(i), ZoneId.systemDefault(), System.currentTimeMillis());
            previousSleep += previous.sleepMs(); previousFeeds += previous.feeds();
        }
        LinearLayout week = Ui.panel(this);
        LinearLayout weekRow = Ui.row(this); weekRow.setGravity(Gravity.TOP);
        weekStat(weekRow, "This week", weekFeeds + " feeds", Insights.duration(weekSleep) + " sleep");
        weekStat(weekRow, "Previous week", previousFeeds + " feeds", Insights.duration(previousSleep) + " sleep");
        week.addView(weekRow); body.addView(week);
        LinearLayout rhythm = Ui.panel(this);
        rhythm.addView(chartTitle("Sleep pattern", "24 hours, midnight to midnight", Ui.SLEEP));
        rhythm.addView(new SleepTimelineView(this, events), new LinearLayout.LayoutParams(-1, Ui.dp(this, 220)));
        body.addView(rhythm);
        addChart("Sleep", "hours", days.stream().map(d -> new ChartView.Point(d.date().format(DateTimeFormatter.ofPattern("EE")), d.sleepMs() / 3_600_000.0)).toList(), Ui.SLEEP, false);
        addChart("Feeds", "count", days.stream().map(d -> new ChartView.Point(d.date().format(DateTimeFormatter.ofPattern("EE")), d.feeds())).toList(), Ui.FEED, false);
        addChart("Bottle milk", prefs.getBoolean("imperial", false) ? "fl oz" : "ml",
            days.stream().map(d -> new ChartView.Point(d.date().format(DateTimeFormatter.ofPattern("EE")), d.milkMl() / (prefs.getBoolean("imperial", false) ? 29.5735295625 : 1))).toList(), Ui.FEED, false);
        LinearLayout table = Ui.panel(this);
        table.addView(chartTitle("Daily comparison", "per day", Ui.DIAPER));
        tableRow(table, true, "Day", "Feeds", "Wet", "Dirty", "Sleep");
        for (int i = days.size() - 1; i >= 0; i--) {
            Insights.Day day = days.get(i);
            tableRow(table, false, day.date().format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())), Integer.toString(day.feeds()),
                Integer.toString(day.wet()), Integer.toString(day.dirty()), Insights.duration(day.sleepMs()));
        }
        body.addView(table);
        List<JSONObject> babies = store.records("profile").stream().filter(p -> p.optString("role").equals("Baby")).toList();
        if (babies.size() > 1) {
            LinearLayout comparison = Ui.panel(this);
            comparison.addView(chartTitle("Children", "Today", Ui.MEMORY));
            tableRow(comparison, true, "Name", "Feeds", "Wet", "Dirty", "Sleep");
            for (JSONObject baby : babies) {
                Insights.Day stats = Insights.day(store.events(baby.optString("id")), LocalDate.now(), ZoneId.systemDefault(), System.currentTimeMillis());
                tableRow(comparison, false, baby.optString("name"), Integer.toString(stats.feeds()), Integer.toString(stats.wet()),
                    Integer.toString(stats.dirty()), Insights.duration(stats.sleepMs()));
            }
            body.addView(comparison);
        }
        GrowthUi.add(this, body, profile(), events, prefs.getBoolean("imperial", false), () -> profileEditor(profile()));
        body.addView(Ui.text(this, "Sleep is split at midnight; overlapping logs are counted separately. No automatic diagnoses or age-based advice.", 12, Ui.MUTED));
    }
    private void weekStat(LinearLayout row, String label, String first, String second) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.text(this, label, 13, Ui.MUTED));
        column.addView(Ui.figures(Ui.title(this, first, 20)));
        column.addView(Ui.figures(Ui.medium(this, second, 15, Ui.INK)));
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private LinearLayout chartTitle(String title, String unit, int thread) {
        LinearLayout row = Ui.row(this); row.setPadding(0, 0, 0, Ui.dp(this, 6));
        row.addView(Ui.dot(this, thread, 10));
        row.addView(Ui.title(this, title, 17));
        TextView detail = Ui.text(this, "  " + unit, 13, Ui.MUTED); row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }
    private void tableRow(LinearLayout table, boolean header, String... cells) {
        if (!header) Ui.divider(table);
        LinearLayout row = Ui.row(this); row.setMinimumHeight(Ui.dp(this, header ? 32 : 40));
        for (int i = 0; i < cells.length; i++) {
            TextView cell = Ui.figures(header ? Ui.text(this, cells[i], 12, Ui.MUTED) : Ui.text(this, cells[i], 14, Ui.INK));
            if (i > 0) cell.setGravity(Gravity.END);
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, i == 0 ? 1.5f : i == cells.length - 1 ? 1.4f : 0.9f));
        }
        table.addView(row);
    }
    private void addChart(String title, String unit, List<ChartView.Point> points, int color, boolean connected) {
        LinearLayout card = Ui.panel(this); card.addView(chartTitle(title, unit, color));
        card.addView(new ChartView(this, points, connected, color), new LinearLayout.LayoutParams(-1, Ui.dp(this, 170))); body.addView(card);
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
        body.addView(Ui.illustratedHeading(this, "Your people. Your data.", 26, "family", false));
        body.addView(Ui.text(this, "Two caregivers, one private nest. Pair over Wi-Fi without an account, or use optional OneDrive sharing. No app backend.", 15, Ui.MUTED));
        partnerCard();
        body.addView(Ui.section(this, "Profiles & preferences"));
        LinearLayout people = Ui.panel(this);
        people.addView(Ui.rowButton(this, "Add baby or parent", () -> profileEditor(null)));
        if (profile() != null && profile().optString("role").equals("Baby"))
            people.addView(Ui.rowButton(this, "Care plan & advance alerts", () -> CarePlanUi.settings(this, profile())));
        if (!CareReminders.error(this).isEmpty()) people.addView(Ui.text(this, CareReminders.error(this), 14, Ui.INK));
        if (profile() != null) {
            people.addView(Ui.rowButton(this, "Edit " + profile().optString("name"), () -> profileEditor(profile())));
            List<String> facts = new ArrayList<>();
            if (!profile().optString("sex").isBlank()) facts.add("Sex: " + profile().optString("sex"));
            if (!profile().optString("adjustedDob").isBlank()) {
                JSONObject adjustedProfile = Records.copy(profile()); Records.put(adjustedProfile, "dob", adjustedProfile.optString("adjustedDob"));
                facts.add("Adjusted birth date: " + profile().optString("adjustedDob") + " / adjusted age: " + age(adjustedProfile));
            }
            if (!facts.isEmpty()) {
                TextView detail = Ui.text(this, String.join("\n", facts), 13, Ui.MUTED);
                detail.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 8)); people.addView(detail);
            }
        }
        if (profile() != null) people.addView(Ui.rowButton(this, "Delete current profile", () -> {
            JSONObject selected = profile();
            new AlertDialog.Builder(this).setTitle("Delete " + selected.optString("name") + "?")
                .setMessage("This removes the profile and its logs from both phones after sync. Historical revisions remain in backups and the sync log. Export a backup first.")
                .setPositiveButton("Delete profile", (d, w) -> {
                    for (JSONObject event : store.events(selected.optString("id"))) {
                        store.save(event.optString("id"), event, true); Reminders.cancel(this, event.optString("id"));
                    }
                    store.save(selected.optString("id"), selected, true); selectProfile(""); changed();
                }).setNegativeButton("Cancel", null).show();
        }));
        people.addView(Ui.rowButton(this, "Caregiver name: " + prefs.getString("caregiver", "Caregiver"), () -> prompt("Your caregiver name", "Shown on new logs", prefs.getString("caregiver", ""), value -> {
            if (value.isBlank()) { error("Enter a caregiver name"); return; }
            prefs.edit().putString("caregiver", value.trim()).apply(); render();
        })));
        people.addView(Ui.rowButton(this, prefs.getBoolean("imperial", false) ? "Display units: imperial" : "Display units: metric", () -> {
            prefs.edit().putBoolean("imperial", !prefs.getBoolean("imperial", false)).apply(); render();
        }));
        people.addView(Ui.rowButton(this, "Choose visible activities", this::chooseActivities));
        body.addView(people);
        body.addView(Ui.section(this, "OneDrive sync"));
        LinearLayout drive = Ui.panel(this);
        if (prefs.getBoolean("googleSyncRemoved", false))
            drive.addView(Ui.text(this, "Google sync was removed. Your local records are unchanged. Export a backup, then connect OneDrive or pair locally. Your old Google folder and permissions have not been changed.", 14, Ui.INK));
        String folder = prefs.getString("oneDriveFolder", "");
        if (folder.isEmpty()) drive.addView(Ui.text(this, OneDriveAuth.configured()
            ? "Optional: sync while apart, even when the phones are online at different times. Sign in with your own personal Microsoft accounts."
            : "OneDrive is not configured in this build. The maintainer must register Little Nest with Microsoft once; see wiki/Sync-Setup.md. Local sync needs no account.", 14, Ui.MUTED));
        else {
            drive.addView(Ui.text(this, "Folder: " + folder, 12, Ui.MUTED));
            long last = prefs.getLong("lastSync", 0);
            drive.addView(Ui.text(this, last > 0 ? "Last sync: " + EventEditor.format(last) : "Waiting for first sync", 13, Ui.MUTED));
        }
        if (!prefs.getString("syncError", "").isEmpty()) drive.addView(Ui.text(this, "Needs attention: " + prefs.getString("syncError", ""), 14, Ui.INK));
        if (working || authorizing) drive.addView(Ui.text(this, "Working with OneDrive...", 14, Ui.GREEN));
        drive.addView(Ui.rowButton(this, "Connect / reconnect OneDrive", () -> signIn(() -> {
            Toast.makeText(this, "OneDrive connected", Toast.LENGTH_SHORT).show();
            if (!prefs.getString("oneDriveFolder", "").isEmpty()) sync(); else render();
        })));
        if (folder.isEmpty()) {
            drive.addView(Ui.rowButton(this, "Create a shared OneDrive nest", () -> authorize(true, this::createNest)));
            drive.addView(Ui.text(this, "Joining your partner's nest? Use Join partner above instead of creating a second nest.", 12, Ui.MUTED));
            if (OneDriveAuth.connected(this))
                drive.addView(Ui.rowButton(this, "Sign out of OneDrive on this phone", this::disconnectOneDrive));
        } else {
            drive.addView(Ui.text(this, "To add your partner, use Invite partner above. It includes this nest and can send the Microsoft email invitation.", 12, Ui.MUTED));
            drive.addView(Ui.rowButton(this, "Sync now", () -> authorize(true, this::sync)));
            drive.addView(Ui.rowButton(this, "Disconnect OneDrive on this phone", () -> new AlertDialog.Builder(this).setTitle("Disconnect OneDrive?")
                .setMessage("Local logs and local pairing stay here. This does not remove anyone's folder access. Remove access in OneDrive if needed. Do not connect another family without exporting and clearing app storage.")
                .setPositiveButton("Disconnect", (d, w) -> {
                    disconnectOneDrive();
                }).setNegativeButton("Cancel", null).show()));
        }
        drive.addView(Ui.text(this, "Both caregivers can read all profiles, parent logs, photos and revisions. Files in OneDrive are not end-to-end encrypted by Little Nest. Foreground checks every minute; background sync is best-effort, typically 15+ minutes. Local and OneDrive sync can run together for the same nest.", 12, Ui.MUTED));
        body.addView(drive);
        body.addView(Ui.section(this, "Keep a copy"));
        LinearLayout data = Ui.panel(this);
        data.addView(Ui.rowButton(this, importing ? "Import in progress..." : "Import activity CSV", () -> {
            if (importing) { error("Wait for the current import to finish."); return; }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT_NARA);
        }));
        data.addView(Ui.rowButton(this, "Export this profile as CSV", () -> export(false)));
        data.addView(Ui.rowButton(this, "Export complete JSON backup", () -> export(true)));
        data.addView(Ui.rowButton(this, "Restore / merge JSON backup", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT);
        }));
        data.addView(Ui.text(this, "Exports contain private health information. JSON includes photos and deleted historical revisions. Share only with people you trust.", 12, Ui.MUTED));
        body.addView(data);
        body.addView(Ui.section(this, "Reminders"));
        LinearLayout reminders = Ui.panel(this);
        reminders.addView(Ui.rowButton(this, "Enable notifications", this::requestNotifications));
        if (!prefs.getString("reminderError", "").isEmpty()) reminders.addView(Ui.text(this, prefs.getString("reminderError", ""), 13, Ui.INK));
        reminders.addView(Ui.text(this, "Add reminders inside any activity. Android may delay them during battery-saving modes. They are not suitable for time-critical medication alarms.", 13, Ui.MUTED));
        body.addView(reminders);
        Ui.stitch(body);
        TextView footer = Ui.text(this, "Little Nest " + BuildConfig.VERSION_NAME + "\nA shared place for everyday care. No ads, analytics, subscriptions, or app backend.", 12, Ui.MUTED);
        footer.setGravity(Gravity.CENTER); footer.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8)); body.addView(footer);
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

    private void partnerCard() {
        body.addView(Ui.section(this, "Your partner"));
        LinearLayout card = Ui.panel(this);
        boolean paired = !prefs.getString("localPeer", "").isEmpty();
        String folder = prefs.getString("oneDriveFolder", ""), pendingNest = prefs.getString("pendingNest", "");
        boolean joining = !pendingNest.isEmpty() && folder.isEmpty();
        if (!paired && folder.isEmpty() && !joining)
            card.addView(Ui.text(this, "Share this nest with one other caregiver. On one phone tap Invite partner. On the other tap Join partner and scan the code. On the same Wi-Fi or hotspot this pairs instantly, with no account. All profiles, logs, photos and past edits are shared.", 14, Ui.MUTED));
        if (joining) {
            card.addView(Ui.text(this, "Almost done: your partner also shared their OneDrive nest, so you can sync while apart. Accept the Microsoft invitation email they sent you, then tap below.", 14, Ui.INK));
            card.addView(Ui.rowButton(this, "Finish joining OneDrive nest", () -> joinNest(pendingNest)));
            card.addView(Ui.rowButton(this, "Skip OneDrive for now", () -> { prefs.edit().remove("pendingNest").apply(); render(); }));
        }
        if ((!paired || !folder.isEmpty()) && !joining) card.addView(Ui.primary(this, "Invite partner", () -> withCaregiverName(this::invitePartner)));
        if (!paired || folder.isEmpty()) {
            card.addView(Ui.rowButton(this, "Join partner - scan their code", () -> withCaregiverName(this::scanPartner)));
            card.addView(Ui.rowButton(this, "Or paste a code they sent you", () -> withCaregiverName(() ->
                prompt("Partner code", "Paste the Little Nest code your partner sent you.", "", this::usePartnerCode))));
        }
        if (paired) {
            card.addView(Ui.medium(this, "Wi-Fi sync", 16, Ui.INK));
            long last = prefs.getLong("localLastSync", 0);
            card.addView(Ui.text(this, "Paired. Open both apps on the same private Wi-Fi or hotspot to sync automatically.", 14, Ui.MUTED));
            card.addView(Ui.text(this, last == 0 ? "Waiting for first local sync" : "Last local sync: " + EventEditor.format(last), 13, Ui.MUTED));
            card.addView(Ui.text(this, store.pending("local:" + prefs.getString("localPeer", "")) + " changes waiting for partner", 13, Ui.INK));
            card.addView(Ui.rowButton(this, "Sync locally now", () -> {
                if (localSync == null) { error("Open the app to start local sync."); return; }
                if (localSync.busy()) { error("Local sync is already running."); return; }
                localSync.sync();
            }));
            card.addView(Ui.rowButton(this, "Disconnect local partner", () -> new AlertDialog.Builder(this)
                .setTitle("Disconnect local sync?").setMessage("Local records and OneDrive sharing are kept. Disconnect on both phones before pairing again. A previously paired phone retains its copy of your data.")
                .setPositiveButton("Disconnect", (dialog, which) -> {
                    try { if (localSync != null) localSync.disconnect(); render(); }
                    catch (Exception e) { error("Cannot disconnect local sync: " + e.getMessage()); }
                }).setNegativeButton("Cancel", null).show()));
        }
        if (!prefs.getString("localError", "").isEmpty())
            card.addView(Ui.text(this, "Local sync: " + prefs.getString("localError", ""), 13, Ui.INK));
        card.addView(Ui.text(this, "Wi-Fi sync is encrypted phone-to-phone and runs while both apps are open; changes wait safely when a phone is asleep or away. Guest Wi-Fi may block it. To sync while apart, add OneDrive below before inviting.", 12, Ui.MUTED));
        body.addView(card);
    }

    private void withCaregiverName(Runnable next) {
        if (!prefs.getString("caregiver", "").isBlank()) { next.run(); return; }
        prompt("Your name first", "Shown on your logs so you and your partner can tell who logged what.", "", value -> {
            if (value.isBlank()) { error("Enter a caregiver name"); return; }
            prefs.edit().putString("caregiver", value.trim()).apply(); render(); next.run();
        });
    }

    private void invitePartner() {
        String nest = prefs.getString("oneDriveFolder", "");
        LocalSync local = localSync;
        if (!prefs.getString("localPeer", "").isEmpty() || local == null) {
            if (nest.isEmpty()) error("Local sync is not running. Reopen the app.");
            else showInvite(null, nest, null, "");
            return;
        }
        local.invitation(code -> runOnUiThread(() -> {
            if (isDestroyed() || local != localSync) local.cancelInvitation(); else showInvite(code, nest, local, "");
        }), failure -> runOnUiThread(() -> {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            if (isDestroyed()) return;
            if (nest.isEmpty()) error("Cannot create invitation: " + message);
            else showInvite(null, nest, null, "Wi-Fi pairing is unavailable (" + message + "), so this code uses OneDrive only.");
        }));
    }

    private void showInvite(String localCode, String nest, LocalSync local, String note) {
        String code;
        Bitmap image;
        try {
            code = PartnerInvite.create(localCode, nest);
            image = new BarcodeEncoder().createBitmap(new MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 700, 700));
        } catch (Exception e) {
            if (local != null) local.cancelInvitation();
            error("Cannot create invitation: " + e.getMessage()); return;
        }
        LinearLayout content = Ui.column(this);
        int pad = Ui.dp(this, 20); content.setPadding(pad, pad / 2, pad, 0);
        content.addView(Ui.text(this, "On your partner's phone: open Little Nest, tap Join partner and scan this code.", 15, Ui.INK));
        if (!note.isEmpty()) content.addView(Ui.text(this, note, 13, Ui.INK));
        if (localCode != null) content.addView(Ui.text(this, "Keep this screen open with both phones on the same Wi-Fi or hotspot. The code expires in five minutes. Anyone who scans it can join, so do not post it.", 13, Ui.MUTED));
        if (!nest.isEmpty()) content.addView(Ui.text(this, "It also adds them to your OneDrive nest once they accept a Microsoft invitation email. Not together? Use Invite by email instead.", 13, Ui.MUTED));
        ImageView qr = new ImageView(this); qr.setImageBitmap(image);
        qr.setContentDescription("Private partner invitation QR code"); qr.setAdjustViewBounds(true);
        content.addView(qr, new LinearLayout.LayoutParams(-1, Ui.dp(this, 260)));
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Invite your partner").setView(content)
            .setPositiveButton("Done", (dialog, which) -> { if (local != null) local.cancelInvitation(); })
            .setOnCancelListener(dialog -> { if (local != null) local.cancelInvitation(); });
        // Leaving the app stops the Wi-Fi listener, so codes sent remotely carry only the OneDrive nest.
        if (!nest.isEmpty()) builder.setNeutralButton("Invite by email", (dialog, which) -> {
            if (local != null) local.cancelInvitation();
            emailInvite(nest);
        });
        builder.show();
    }

    private void emailInvite(String nest) {
        prompt("Invite by email", "Your partner's personal Microsoft account email. OneDrive emails them access, then your email app opens with their join code. Only two accounts are permitted.", "", value -> {
            String email = value.trim();
            if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) { error("Enter your partner's email address"); return; }
            authorize(true, () -> runDrive(() -> { oneDrive().invite(nest, email); return "Microsoft invitation sent"; }, () -> {
                try { sendJoinCode(email, PartnerInvite.create(null, nest)); }
                catch (Exception e) { error("Cannot create join code: " + e.getMessage()); }
            }));
        });
    }

    private void sendJoinCode(String email, String code) {
        String text = "Join me in Little Nest.\n\n1. Accept the OneDrive invitation email from Microsoft.\n"
            + "2. Open Little Nest, go to Family, tap Join partner, then \"Or paste a code they sent you\".\n3. Paste this code:\n\n" + code
            + "\n\nThis code is private. Please don't share it.";
        Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, new String[]{email})
            .putExtra(Intent.EXTRA_SUBJECT, "Join our Little Nest").putExtra(Intent.EXTRA_TEXT, text);
        try { startActivity(mail); }
        catch (android.content.ActivityNotFoundException e) {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Join our Little Nest").putExtra(Intent.EXTRA_TEXT, text), "Send join code"));
        }
    }

    private void scanPartner() {
        new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("Scan the code on your partner's phone")
            .setBeepEnabled(false).setOrientationLocked(false).initiateScan();
    }

    private void usePartnerCode(String text) {
        PartnerInvite.Parsed code;
        try { code = PartnerInvite.parse(text); }
        catch (Exception e) { error("Cannot use this code: " + e.getMessage()); return; }
        String folder = prefs.getString("oneDriveFolder", "");
        boolean local = code.local() != null && prefs.getString("localPeer", "").isEmpty();
        boolean nest = !code.nest().isEmpty() && !code.nest().equals(folder);
        if (nest && !folder.isEmpty()) { error("This phone already uses a different OneDrive nest. Disconnect it first."); return; }
        if (!local && !nest) {
            error(code.local() != null || !code.nest().isEmpty() ? "This phone is already connected to that partner." : "Cannot use this code: " + code.localProblem());
            return;
        }
        StringBuilder message = new StringBuilder("Only continue if this code came directly from your partner. Everything on both phones (profiles, logs, photos and past edits) will merge and be shared. Export a backup first if unsure.\n");
        if (local) message.append("\n- Pair now over Wi-Fi.");
        if (nest) message.append("\n- Join their OneDrive nest after you accept their Microsoft invitation email.");
        if (!code.localProblem().isEmpty()) message.append("\n\nWi-Fi pairing skipped: ").append(code.localProblem());
        new AlertDialog.Builder(this).setTitle("Join your partner?").setMessage(message.toString())
            .setPositiveButton("Join and share", (dialog, which) -> {
                tab = "Family";
                if (nest) prefs.edit().putString("pendingNest", code.nest()).apply();
                if (local) { if (localSync == null) pendingPairing = code.local(); else localSync.join(code.local()); }
                render();
            }).setNegativeButton("Cancel", null).show();
    }

    private void authorize(boolean interactive, Runnable after) {
        if (authorizing || working || SyncEngine.active()) {
            if (interactive) Toast.makeText(this, "Please wait for the current sync", Toast.LENGTH_SHORT).show();
            return;
        }
        if (OneDriveAuth.connected(this)) { after.run(); return; }
        if (!interactive) { after.run(); return; }
        signIn(after);
    }
    private void signIn(Runnable after) {
        if (authorizing || working || SyncEngine.active()) { error("Wait for the current OneDrive operation."); return; }
        authorizing = true; afterAuthorization = after;
        try { startActivityForResult(OneDriveAuth.signIn(authorization), AUTH); }
        catch (IllegalStateException | android.content.ActivityNotFoundException e) {
            authorizing = false; afterAuthorization = null;
            prefs.edit().putString("syncError", e.getMessage()).apply(); error(e.getMessage());
        }
    }
    private void disconnectOneDrive() {
        if (working || authorizing || SyncEngine.active()) { error("Wait for the current OneDrive operation to finish."); return; }
        runDrive(() -> {
            OneDriveAuth.disconnect(this);
            prefs.edit().remove("oneDriveFolder").remove("lastSync").remove("syncError").apply();
            getSystemService(JobScheduler.class).cancel(7); return "OneDrive disconnected on this phone";
        }, null);
    }
    private void createNest() {
        if (!prefs.getString("oneDriveFolder", "").isEmpty()) { error("Disconnect the existing OneDrive folder first."); return; }
        runDrive(() -> {
            String family = SyncFamily.ensure(this);
            String folder = new OneDriveApi(OneDriveAuth.token(this), family).createFolder();
            SyncFamily.accept(this, family);
            prefs.edit().putString("oneDriveFolder", folder).remove("googleSyncRemoved").apply();
            SyncJob.schedule(this); return "Nest created. Tap Invite partner to add your partner.";
        }, this::sync);
    }
    private void joinNest(String nest) {
        final String folder;
        try { folder = OneDriveApi.folderId(nest.trim()); }
        catch (IllegalArgumentException e) { error(e.getMessage()); return; }
        authorize(true, () -> runDrive(() -> {
            OneDriveApi api = new OneDriveApi(OneDriveAuth.token(this), prefs.getString("syncFamily", ""));
            SyncFamily.accept(this, api.folderFamily(folder));
            prefs.edit().putString("oneDriveFolder", folder).remove("pendingNest").remove("googleSyncRemoved").apply(); SyncJob.schedule(this);
            return "Nest connected";
        }, this::sync));
    }
    private OneDriveApi oneDrive() throws Exception {
        return new OneDriveApi(OneDriveAuth.token(this), SyncFamily.ensure(this));
    }
    private interface DriveWork { String run() throws Exception; }
    private void runDrive(DriveWork work, Runnable after) {
        runDrive(work, after, true);
    }
    private void runDrive(DriveWork work, Runnable after, boolean announce) {
        if (working) { error("Wait for the current OneDrive operation."); return; }
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
                runOnUiThread(() -> { working = false; if (!isDestroyed()) { render(); if (announce) error(message); } });
            }
        });
    }
    private void sync() { sync(true); }
    private void sync(boolean announce) { runDrive(() -> SyncEngine.sync(this), null, announce); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        IntentResult scan = IntentIntegrator.parseActivityResult(request, result, data);
        if (scan != null) {
            if (scan.getContents() != null) usePartnerCode(scan.getContents());
            return;
        }
        if (request == AUTH) {
            authorizing = false;
            if (result != RESULT_OK || data == null) { afterAuthorization = null; error("Microsoft sign-in was cancelled. Local tracking still works."); return; }
            authorizing = true;
            OneDriveAuth.finish(this, authorization, data, () -> runOnUiThread(() -> {
                authorizing = false;
                Runnable after = afterAuthorization; afterAuthorization = null;
                if (!isDestroyed()) { if (after != null) after.run(); else render(); }
            }), message -> runOnUiThread(() -> {
                authorizing = false; afterAuthorization = null;
                prefs.edit().putString("syncError", message).apply(); if (!isDestroyed()) error(message);
            }));
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
                            String json = new String(SyncFiles.readBounded(input, 100_000_000), StandardCharsets.UTF_8);
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
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("Reading activity CSV")
            .setMessage("Validating all records locally. Nothing has been saved yet.").setCancelable(false).show();
        executor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("Cannot open CSV");
                byte[] bytes = SyncFiles.readBounded(input, 25_000_000);
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
            content.addView(Ui.text(this, "Use this if the baby is still sleeping. Elapsed time includes time since the original activity start, including time after export. For an old export, leave unchecked and correct the end time in History.", 13, Ui.MUTED));
        }
        content.addView(Ui.text(this, "\nChoose where each profile belongs. Existing activities, edits and deletions are never overwritten on reimport. Blank profile metadata can be filled in; existing names and values stay unchanged.\n\nAll imported data will sync to your partner if local or OneDrive sync is enabled. Import once on one phone, then sync. Large histories can take time to transfer.", 13, Ui.MUTED));
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
        AlertDialog confirm = new AlertDialog.Builder(this).setTitle("Import activity CSV").setView(scroll)
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
