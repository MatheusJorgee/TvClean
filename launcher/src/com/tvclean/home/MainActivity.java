package com.tvclean.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The home screen, "Cinema" layout. A fixed frame (backdrop of what is trending on the focused app,
 * header with sections, Wi-Fi, settings and clock) and three sections that swap underneath it:
 * Início (hero, app row, inputs), Apps (every app) and Entradas (the TV's inputs).
 *
 * Built for a 943 MB TV: plain views on a 1920x1080 grid scaled to the screen (this TCL draws at
 * 1280x720), nothing rebuilt when Home comes back, background threads at background priority.
 */
public class MainActivity extends Activity {
    private static final int GROUND = 0xFF0B0D12, INK = 0xFFF5F2EC, SOFT = 0xFFD6D2CA, MUTED = 0xFFA9A59D;
    private static final int CHIP = 0x24FFFFFF, PILL = 0xFF1C1F27;
    private static final int HOME = 0, APPS = 1, INPUTS = 2, HEALTH = 3;

    private Ui ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** What the screen needs (apps, inputs, "Continuar"): normal priority, so it is not starved at startup. */
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> new Thread(r, "tvclean-startup"));
    /** Trending pictures and weather: background priority, and never in the way of the screen. */
    private final ScheduledExecutorService background =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "tvclean-network"));
    /** After a cold start the network waits a little: the TV is busy waking up everything else. */
    private static final long NETWORK_DELAY_MS = 8000;
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.6f);
    private Images images;
    private Trending trending;
    private Weather weather;
    private SharedPreferences prefs;

    private FrameLayout root;
    private ImageView backdrop;
    private GradientDrawable backdropFallback;
    private View dim;
    private FocusGate header;
    private final TextView[] tabs = new TextView[4];
    private FrameLayout content;
    private final View[] sections = new View[4];
    private int section = HOME;
    private StrokeIcon wifiIcon;
    private final StrokeIcon weatherIcon = new StrokeIcon(StrokeIcon.SUN, INK);
    private LinearLayout weatherBox;
    private TextView weatherText;

    private LinearLayout hero;
    private TextView heroBadge, heroTitle, heroText, heroButton;
    private final GradientDrawable badgeBg = new GradientDrawable();
    private LinearLayout appRow, inputRow;

    private List<Sources.App> apps = new ArrayList<>();
    /** The Início row, in the person's order. Hidden apps show in neither the row nor the Apps grid. */
    private final List<Sources.App> pinned = new ArrayList<>();
    private final List<Sources.App> rowApps = new ArrayList<>();   // what the row shows: pinned, recent first
    private String lastUsed;
    private final Set<String> hidden = new HashSet<>();
    private int moving = -1;           // index in `pinned` being moved, -1 when not moving
    private View menu;
    private View menuReturn;
    private List<Sources.Input> inputs = new ArrayList<>();
    private final List<View> appTiles = new ArrayList<>();
    private int appFocus = 0;
    private int inputFocus = 0;
    private String appsSignature = "";
    private Runnable heroAction;
    private Runnable pendingHero;
    private View launchSource;
    private boolean resumedOnce;
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        images = new Images(this);
        trending = new Trending(this);
        weather = new Weather(this);
        prefs = getSharedPreferences("tvclean", MODE_PRIVATE);
        hidden.addAll(split(prefs.getString("hidden", "")));
        takeKey(getIntent());
        badgeBg.setCornerRadius(ui.px(18));

        // No background on the root: the window (theme) already paints the ground color.
        root = new FrameLayout(this);
        buildFrame(root);
        setContentView(root);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, main);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) cm.registerDefaultNetworkCallback(network, main);

        // The row from the last run, on the very first frame; reload() then checks it against the system.
        lastUsed = prefs.getString("recent_app", null);
        List<Sources.App> snap = Sources.loadSnapshot(this);
        if (snap != null) {
            apps = snap;
            appsSignature = Sources.signature(snap);
            computePinned();
            fillRows();
        }
        reload();
        updateRecent();
        // Quiet maintenance a while after startup: TCL tends to switch its apps back on at boot.
        background.schedule(this::autoMaintenance, 30, TimeUnit.SECONDS);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) cm.unregisterNetworkCallback(network);
        loader.shutdownNow();
        background.shutdownNow();
        super.onDestroy();
    }

    /** Coming back to Home is cheap: inputs, Wi-Fi and the trending picture; the rows stay as they are. */
    @Override
    protected void onResume() {
        super.onResume();
        if (!resumedOnce) {
            resumedOnce = true;
            return;
        }
        reloadInputs();
        updateWifi();
        refreshTrending();
        refreshWeather();
        updateRecent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (takeKey(intent)) refreshTrending();
        // The Home key while Home is already up: back to Início.
        showSection(HOME, true);
    }

    @Override
    public void onBackPressed() {
        if (menu != null) closeMenu();
        else if (moving >= 0) finishMove();
        else if (section != HOME) showSection(HOME, true);
        // On Início Back does nothing: there is nowhere to go back to.
    }

    /**
     * UI_HIDDEN keeps everything (going back must be instant). Deeper levels let go of the picture and of
     * the Apps and Entradas sections, which are rebuilt in a few milliseconds when opened again.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) {
            images.clear();
            backdrop.setTag(null);
            backdrop.setImageDrawable(null);
            for (int s : new int[]{APPS, INPUTS, HEALTH}) {
                if (section == s || sections[s] == null) continue;
                content.removeView(sections[s]);
                sections[s] = null;
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // The picture may have been released in the background: put it back.
        if (backdrop.getDrawable() == null && resumedOnce) showFocused();
    }

    /** `am start -n com.tvclean.home/.MainActivity --es tmdb_key <key>` sets the TMDB key. */
    private boolean takeKey(Intent i) {
        String key = i == null ? null : i.getStringExtra("tmdb_key");
        if (TextUtils.isEmpty(key)) return false;
        trending.setKey(key);
        return true;
    }

    private final Runnable reloadSoon = this::reload;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Installs arrive as several broadcasts: rebuild once, after they settle.
            main.removeCallbacks(reloadSoon);
            main.postDelayed(reloadSoon, 1200);
        }
    };

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String id, int state) { reloadInputs(); }
        @Override public void onInputAdded(String id) { reloadInputs(); }
        @Override public void onInputRemoved(String id) { reloadInputs(); }
    };

    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { main.post(MainActivity.this::updateWifi); }
        @Override public void onLost(Network n) { main.post(MainActivity.this::updateWifi); }
    };

    // ---------------------------------------------------------------- data

    private void reload() {
        loader.execute(() -> {
            // Banners are drawn at the Apps grid's tile size; the Início row shows them a touch smaller.
            List<Sources.App> a = Sources.apps(this, ui.px(326), ui.px(184));
            List<Sources.Input> in = Sources.inputs(this);
            String sig = Sources.signature(a);
            trending.loadCached();
            main.post(this::showFocused);
            boolean changed = !sig.equals(appsSignature);
            // Next cold start draws the row from this snapshot before asking the system for anything.
            if (changed || !Sources.hasSnapshot(this)) Sources.saveSnapshot(this, a);
            main.post(() -> {
                if (isDestroyed()) return;
                inputs = in;
                if (changed) {
                    appsSignature = sig;
                    apps = a;
                    computePinned();
                    fillRows();
                    if (sections[APPS] != null) sections[APPS] = rebuild(APPS);
                }
                fillInputs();
                if (sections[INPUTS] != null) sections[INPUTS] = rebuild(INPUTS);
                updateWifi();
                updateWeather();
            });
        });
        background.schedule(() -> {
            trending.refresh();
            main.post(this::showFocused);
            if (weather.refresh()) main.post(this::updateWeather);
        }, NETWORK_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void refreshWeather() {
        background.execute(() -> {
            if (weather.refresh()) main.post(this::updateWeather);
        });
    }

    private void updateWeather() {
        String label = weather.label();
        weatherBox.setVisibility(label == null ? View.GONE : View.VISIBLE);
        if (label == null) return;
        weatherText.setText(label);
        weatherIcon.setKind(weather.icon());
    }

    // ---------------------------------------------------------------- favorites

    /**
     * The Início row: the saved order, with newly installed apps added at the end. Before the person
     * arranges anything, the streaming services and the apps they installed themselves.
     */
    private void computePinned() {
        String saved = prefs.getString("pinned", null);
        Set<String> known = new HashSet<>(split(prefs.getString("known", "")));
        pinned.clear();
        if (saved == null) {
            for (Sources.App a : apps) if (Sources.pinnedByDefault(a) && !hidden.contains(a.pkg)) pinned.add(a);
        } else {
            for (String pkg : split(saved)) {
                Sources.App a = find(pkg);
                if (a != null && !hidden.contains(pkg)) pinned.add(a);
            }
            for (Sources.App a : apps) {
                if (!known.contains(a.pkg) && Sources.pinnedByDefault(a) && !hidden.contains(a.pkg) && !pinned.contains(a)) pinned.add(a);
            }
        }
        StringBuilder all = new StringBuilder();
        for (Sources.App a : apps) all.append(a.pkg).append(',');
        prefs.edit().putString("known", all.toString()).apply();
        savePinned();
    }

    private void savePinned() {
        StringBuilder s = new StringBuilder();
        for (Sources.App a : pinned) s.append(a.pkg).append(',');
        StringBuilder h = new StringBuilder();
        for (String p : hidden) h.append(p).append(',');
        prefs.edit().putString("pinned", s.toString()).putString("hidden", h.toString()).apply();
    }

    private Sources.App find(String pkg) {
        for (Sources.App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    /** Hold OK on an app: a small menu with what can be done with it from where it was picked. */
    private void openMenu(Sources.App a, View from, boolean inRow) {
        boolean isPinned = pinned.contains(a), isHidden = hidden.contains(a.pkg);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (isHidden) {
            labels.add("Mostrar de novo");
            actions.add(() -> {
                hidden.remove(a.pkg);
                savePinned();
                afterArrange();
            });
        } else {
            if (inRow) {
                labels.add("Mover");
                actions.add(() -> startMove(pinned.indexOf(a)));
            }
            labels.add(isPinned ? "Remover do Início" : "Fixar no Início");
            actions.add(() -> {
                if (isPinned) pinned.remove(a);
                else pinned.add(a);
                savePinned();
                afterArrange();
            });
            labels.add("Esconder");
            actions.add(() -> {
                hidden.add(a.pkg);
                pinned.remove(a);
                savePinned();
                afterArrange();
            });
        }
        showChoices(a.label, labels, actions, from);
    }

    /** Hold OK on an input: give it a name ("PS5"), from a list or typed, or go back to the original. */
    private void openInputMenu(Sources.Input in, View from) {
        String original = in.device != null ? in.device : in.port;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String[] presets = {"PS5", "PS4", "Xbox", "Nintendo Switch", "Chromecast", "Fire TV Stick",
                "TV a cabo", "Receptor de satélite", "Computador", "Soundbar"};
        for (String p : presets) {
            labels.add(p);
            actions.add(() -> renameInput(in, p));
        }
        labels.add("Digitar um nome…");
        actions.add(() -> typeInputName(in));
        if (!in.label.equals(original)) {
            labels.add("Voltar ao nome original (" + original + ")");
            actions.add(() -> renameInput(in, null));
        }
        labels.add("Cancelar");
        actions.add(() -> { });
        showChoices("Nome para " + in.port, labels, actions, from);
    }

    private void renameInput(Sources.Input in, String name) {
        Sources.nameInput(this, in.id, name);
        reloadInputs();
        showFocused();
    }

    private void typeInputName(Sources.Input in) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(in.label);
        field.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Nome para " + in.port)
                .setView(field)
                .setPositiveButton("Salvar", (d, w) -> renameInput(in, field.getText().toString()))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** A centered list of choices over a dark veil; focus stays inside until a choice or Back. */
    private void showChoices(String heading, List<String> labels, List<Runnable> actions, View from) {
        FrameLayout veil = new FrameLayout(this);
        veil.setBackgroundColor(0xCC05060A);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.px(40), ui.px(36), ui.px(40), ui.px(36));
        card.setBackground(ui.round(PILL, 28));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(items);
        TextView title = ui.text(heading, 36, 800, INK);
        title.setPadding(ui.px(8), 0, 0, ui.px(24));
        card.addView(title);
        View first = null;
        for (int i = 0; i < labels.size(); i++) {
            final Runnable act = actions.get(i);
            TextView b = ui.text(labels.get(i), 26, 600, INK);
            b.setPadding(ui.px(28), ui.px(18), ui.px(28), ui.px(18));
            b.setFocusable(true);
            GradientDrawable on = ui.round(INK, 22);
            b.setOnFocusChangeListener((v, has) -> {
                v.setBackground(has ? on : null);
                ((TextView) v).setTextColor(has ? GROUND : INK);
            });
            b.setOnClickListener(v -> {
                closeMenu();
                act.run();
            });
            items.addView(b, new LinearLayout.LayoutParams(ui.px(620), -2));
            if (first == null) first = b;
        }
        // Long lists (input names) scroll inside the card instead of running off the screen.
        card.addView(scroll, new LinearLayout.LayoutParams(-2, labels.size() > 6 ? ui.px(640) : -2));
        veil.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        menu = veil;
        menuReturn = from;
        // Only the menu takes focus while it is open.
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        header.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        veil.setAlpha(0f);
        veil.animate().alpha(1f).setDuration(160).start();
        first.requestFocus();
    }

    /**
     * The app menu also opens with the remote's Menu key, and on the key event a held OK marks as a long
     * press (some remotes send that flag instead of just repeating the key).
     */
    private boolean menuKey(View v, int code, KeyEvent ev, Runnable open) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        boolean held = (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && ev.isLongPress();
        if (code != KeyEvent.KEYCODE_MENU && !held) return false;
        v.setPressed(false);
        open.run();
        return true;
    }

    private void closeMenu() {
        if (menu == null) return;
        root.removeView(menu);
        menu = null;
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        header.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (menuReturn != null && menuReturn.isAttachedToWindow()) menuReturn.requestFocus();
        menuReturn = null;
    }

    /** After pinning, hiding or moving: the row and the Apps grid show the new arrangement. */
    private void afterArrange() {
        fillRows();
        if (sections[APPS] != null) sections[APPS] = rebuild(APPS);
        if (section == HOME && !appTiles.isEmpty()) appTiles.get(Math.min(appFocus, appTiles.size() - 1)).requestFocus();
        showFocused();
    }

    /** Move mode: Left/Right carry the app along the row, OK or Back puts it down. */
    private void startMove(int index) {
        if (index < 0) return;
        if (section != HOME) showSection(HOME, false);
        moving = index;
        appFocus = index;
        fillRows();
        appTiles.get(index).requestFocus();
        markMoving();
    }

    private void moveBy(int d) {
        int j = moving + d;
        if (j < 0 || j >= pinned.size()) return;
        Collections.swap(pinned, moving, j);
        moving = j;
        appFocus = j;
        fillRows();
        appTiles.get(j).requestFocus();
        markMoving();
    }

    private void finishMove() {
        moving = -1;
        savePinned();
        fillRows();
        if (!appTiles.isEmpty()) appTiles.get(Math.min(appFocus, appTiles.size() - 1)).requestFocus();
        showFocused();
    }

    private void markMoving() {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(ui.px(22));
        ring.setStroke(ui.px(6), 0xFFF2A541);
        appTiles.get(moving).setForeground(ring);
        setBadge("Organizando", 0xFFB86E12);
        setHeroTitle(pinned.get(moving).label);
        heroText.setText("Use ◀ e ▶ no controle para mover. Aperte OK para terminar.");
        heroButton.setText("Concluir");
        heroAction = this::finishMove;
    }

    private void refreshTrending() {
        background.execute(() -> {
            trending.refresh();
            main.post(this::showFocused);
        });
    }

    private void reloadInputs() {
        inputs = Sources.inputs(this);
        fillInputs();
        sections[INPUTS] = rebuild(INPUTS);
    }

    // ---------------------------------------------------------------- frame

    private void buildFrame(FrameLayout root) {
        root.setClipChildren(false);

        // Backdrop: a gradient in the app's color, and the trending title's art over it once decoded.
        backdropFallback = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF121A33, 0xFF1A1F33});
        View fallback = new View(this);
        fallback.setBackground(backdropFallback);
        root.addView(fallback, new FrameLayout.LayoutParams(-1, ui.px(800), Gravity.TOP));
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // The root does not clip children (focused tiles grow past their rows), so the crop must clip itself.
        backdrop.setCropToPadding(true);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, ui.px(800), Gravity.TOP));
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF00B0D12, 0x990B0D12, 0x000B0D12}));
        root.addView(left, new FrameLayout.LayoutParams(ui.px(1400), ui.px(800), Gravity.TOP));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x000B0D12, 0x660B0D12, 0xFF0B0D12}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, ui.px(800), Gravity.TOP));
        // Apps and Entradas sit on a darker veil so their grids read well over the picture.
        dim = new View(this);
        dim.setBackgroundColor(0xD90B0D12);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        // Sections swap here, under the header.
        content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        sections[HOME] = buildHome();
        content.addView(sections[HOME], new FrameLayout.LayoutParams(-1, -1));

        // Header: sections, then Wi-Fi, settings and the clock. Always on screen.
        header = new FocusGate(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        String[] names = {"Início", "Apps", "Entradas", "TV"};
        for (int i = 0; i < names.length; i++) {
            final int s = i;
            tabs[i] = tab(names[i], v -> showSection(s, true));
            header.addView(tabs[i]);
        }
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        wifiIcon = new StrokeIcon(StrokeIcon.WIFI, INK);
        header.addView(roundButton(wifiIcon, "Wi-Fi", v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)), null));
        header.addView(roundButton(new StrokeIcon(StrokeIcon.SETTINGS, INK), "Configurações",
                v -> openTclSettings(), v -> {
                    launch(new Intent(Settings.ACTION_SETTINGS));
                    return true;
                }));
        weatherBox = new LinearLayout(this);
        weatherBox.setGravity(Gravity.CENTER_VERTICAL);
        weatherBox.setPadding(ui.px(28), 0, 0, 0);
        ImageView wx = new ImageView(this);
        wx.setImageDrawable(weatherIcon);
        weatherBox.addView(wx, new LinearLayout.LayoutParams(ui.px(30), ui.px(30)));
        weatherText = ui.text("", 24, 600, SOFT);
        weatherText.setPadding(ui.px(10), 0, 0, 0);
        weatherBox.addView(weatherText);
        weatherBox.setVisibility(View.GONE);
        header.addView(weatherBox);
        TextClock clock = ui.clock("HH:mm", 34, 700, INK);
        clock.setPadding(ui.px(24), 0, 0, 0);
        header.addView(clock);
        root.addView(header, frame(-1, -2, ui.px(96), ui.px(52), ui.px(96)));
        paintTabs();
    }

    /**
     * The header row. Moving up into it from a section lands on the current section's tab, not on
     * whichever tab happens to sit closest to the focused tile.
     */
    private final class FocusGate extends LinearLayout {
        FocusGate(Context c) {
            super(c);
        }

        @Override
        public void addFocusables(ArrayList<View> views, int direction, int mode) {
            View f = getCurrentFocus();
            boolean fromOutside = f == null || !isAncestorOf(f);
            if (fromOutside && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) {
                views.add(tabs[section]);
                return;
            }
            super.addFocusables(views, direction, mode);
        }

        private boolean isAncestorOf(View v) {
            for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) {
                if (p == this) return true;
            }
            return false;
        }
    }

    private void showSection(int s, boolean focusContent) {
        // The TV section shows live numbers: build it fresh every time it is opened.
        if (s == HEALTH && section != HEALTH && sections[HEALTH] != null) {
            content.removeView(sections[HEALTH]);
            sections[HEALTH] = null;
        }
        if (sections[s] == null) sections[s] = rebuild(s);
        if (s != section) {
            View old = sections[section];
            if (old != null) old.setVisibility(View.GONE);
            View now = sections[s];
            if (now.getParent() == null) content.addView(now, new FrameLayout.LayoutParams(-1, -1));
            now.setVisibility(View.VISIBLE);
            now.setAlpha(0f);
            now.setTranslationY(ui.px(24));
            now.animate().alpha(1f).translationY(0).setInterpolator(ease).setDuration(240).withLayer().start();
            dim.animate().alpha(s == HOME ? 0f : 1f).setDuration(240).start();
            section = s;
            paintTabs();
            if (now instanceof ScrollView) now.scrollTo(0, 0);
        }
        if (focusContent) {
            View target = firstFocus(s);
            if (target != null) target.requestFocus();
        }
        if (s == HOME) showFocused();
    }

    private View firstFocus(int s) {
        if (s == HOME) return appTiles.isEmpty() ? null : appTiles.get(Math.min(appFocus, appTiles.size() - 1));
        View v = sections[s];
        ArrayList<View> f = new ArrayList<>();
        if (v != null) v.addFocusables(f, View.FOCUS_DOWN);
        return f.isEmpty() ? null : f.get(0);
    }

    /** Builds (or rebuilds) a section, keeping it in place if it is the one on screen. */
    private View rebuild(int s) {
        if (s == HOME) return sections[HOME];
        View old = sections[s];
        boolean showing = old != null && section == s;
        View v = s == APPS ? buildApps() : s == INPUTS ? buildInputs() : buildHealth();
        if (old != null && old.getParent() != null) content.removeView(old);
        v.setVisibility(showing ? View.VISIBLE : View.GONE);
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        if (showing) {
            sections[s] = v;
            View target = firstFocus(s);
            if (target != null) target.requestFocus();
        }
        return v;
    }

    // ---------------------------------------------------------------- Início

    private FrameLayout buildHome() {
        FrameLayout page = new FrameLayout(this);
        page.setClipChildren(false);

        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        heroBadge = ui.text("", 20, 700, INK);
        heroBadge.setPadding(ui.px(18), ui.px(8), ui.px(18), ui.px(8));
        heroBadge.setBackground(badgeBg);
        hero.addView(heroBadge, new LinearLayout.LayoutParams(-2, -2));
        heroTitle = ui.text("", 96, 800, INK);
        heroTitle.setLetterSpacing(-0.02f);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setLineSpacing(0, 0.92f);
        heroTitle.setPadding(0, ui.px(16), 0, 0);
        hero.addView(heroTitle, new LinearLayout.LayoutParams(ui.px(1000), -2));
        heroText = ui.text("", 26, 400, SOFT);
        heroText.setMaxLines(2);
        heroText.setEllipsize(TextUtils.TruncateAt.END);
        heroText.setLineSpacing(0, 1.3f);
        heroText.setPadding(0, ui.px(20), 0, 0);
        hero.addView(heroText, new LinearLayout.LayoutParams(ui.px(860), -2));
        heroButton = ui.text("", 24, 700, GROUND);
        heroButton.setGravity(Gravity.CENTER);
        heroButton.setPadding(ui.px(34), 0, ui.px(34), 0);
        heroButton.setBackground(ui.round(INK, 32));
        heroButton.setFocusable(true);
        heroButton.setOnClickListener(v -> {
            if (heroAction != null) heroAction.run();
        });
        GradientDrawable ring = ui.ring(32, INK, 5);
        heroButton.setOnFocusChangeListener((v, has) -> v.setForeground(has ? ring : null));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, ui.px(64));
        blp.topMargin = ui.px(24);
        hero.addView(heroButton, blp);
        // The hero sits on the app row: a two-line title grows upwards instead of into "Seus apps".
        FrameLayout heroArea = new FrameLayout(this);
        heroArea.addView(hero, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        page.addView(heroArea, frame(-1, ui.px(468), ui.px(96), ui.px(120), 0));

        page.addView(ui.text("Seus apps", 24, 600, SOFT), frame(-2, -2, ui.px(96), ui.px(612), 0));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(ui.px(96), ui.px(20), ui.px(96), ui.px(20));
        appRow = new LinearLayout(this);
        appRow.setClipChildren(false);
        scroll.addView(appRow);
        page.addView(scroll, frame(-1, ui.px(169 + 40), 0, ui.px(650), 0));

        page.addView(ui.text("Entradas", 24, 600, SOFT), frame(-2, -2, ui.px(96), ui.px(888), 0));
        inputRow = new LinearLayout(this);
        inputRow.setClipChildren(false);
        page.addView(inputRow, frame(-2, ui.px(72), ui.px(96), ui.px(930), 0));
        return page;
    }

    private void fillRows() {
        View focused = getCurrentFocus();
        boolean keepFocus = appTiles.isEmpty() || focused == null || appTiles.contains(focused);
        appRow.removeAllViews();
        appTiles.clear();
        // "Continuar": the app used last leads the row (the saved order is untouched). Not while moving.
        rowApps.clear();
        rowApps.addAll(pinned);
        Sources.App recent = moving < 0 && lastUsed != null ? find(lastUsed) : null;
        if (recent != null && hidden.contains(recent.pkg)) recent = null;
        if (recent != null) {
            rowApps.remove(recent);
            rowApps.add(0, recent);
        }
        for (Sources.App a : rowApps) {
            final int index = appTiles.size();
            View tile = appTile(a, 300, 169, 1.08f, () -> {
                appFocus = index;
                if (moving < 0) scheduleHero(() -> showApp(a));
            });
            tile.setOnLongClickListener(v -> {
                if (moving < 0) openMenu(a, v, true);
                return true;
            });
            tile.setOnKeyListener((v, code, ev) -> {
                if (moving != index) return moving < 0 && menuKey(v, code, ev, () -> openMenu(a, v, true));
                if (code == KeyEvent.KEYCODE_BACK) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) moveBy(-1);
                    else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) moveBy(1);
                    else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) finishMove();
                }
                // Everything else, and the key-up that would otherwise open the app, stays here while moving.
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(300), ui.px(169));
            if (index > 0) lp.setMarginStart(ui.px(28));
            appRow.addView(tile, lp);
            if (a == recent) {
                TextView tag = ui.text("Continuar", 16, 700, INK);
                tag.setPadding(ui.px(12), ui.px(5), ui.px(12), ui.px(5));
                tag.setBackground(ui.round(0xD90B0D12, 14));
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                tlp.setMargins(ui.px(12), ui.px(12), 0, 0);
                ((FrameLayout) tile).addView(tag, tlp);
            }
            appTiles.add(tile);
        }
        if (appFocus >= appTiles.size()) appFocus = 0;
        if (!appTiles.isEmpty() && keepFocus && section == HOME) appTiles.get(appFocus).requestFocus();
    }

    private void fillInputs() {
        boolean hadFocus = inputRow.hasFocus();
        inputRow.removeAllViews();
        for (Sources.Input in : inputs) {
            final int index = inputRow.getChildCount();
            LinearLayout pill = new LinearLayout(this);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(ui.px(28), 0, ui.px(30), 0);
            pill.setBackground(ui.round(PILL, 36));
            pill.addView(statusDot("Conectado".equals(in.status)));
            TextView name = ui.text(in.label, 22, 600, INK);
            name.setPadding(ui.px(14), 0, ui.px(12), 0);
            pill.addView(name);
            // A renamed input still says which port it is on.
            String sub = (in.label.equals(in.port) ? "" : in.port.toLowerCase() + " · ") + in.status.toLowerCase();
            pill.addView(ui.text(sub, 22, 400, MUTED));
            focusable(pill, 1.06f, 36, () -> launch(Sources.inputIntent(in)), () -> {
                inputFocus = index;
                scheduleHero(() -> showInput(in));
            });
            pill.setOnLongClickListener(v -> {
                openInputMenu(in, v);
                return true;
            });
            pill.setOnKeyListener((v, code, ev) -> menuKey(v, code, ev, () -> openInputMenu(in, v)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
            if (inputRow.getChildCount() > 0) lp.setMarginStart(ui.px(16));
            inputRow.addView(pill, lp);
        }
        if (hadFocus && inputRow.getChildCount() > 0) {
            // Stay on the same input after a rename rebuilt the row.
            int i = Math.min(inputFocus, inputRow.getChildCount() - 1);
            inputRow.getChildAt(i).requestFocus();
        }
    }

    /** Waits a moment before repainting the hero, so holding Right does not decode every backdrop. */
    private void scheduleHero(Runnable r) {
        if (pendingHero != null) main.removeCallbacks(pendingHero);
        pendingHero = r;
        main.postDelayed(r, 160);
    }

    private void showFocused() {
        if (rowApps.isEmpty() || moving >= 0) return;
        View f = getCurrentFocus();
        if (f != null && inputRow.indexOfChild(f) >= 0) return;
        showApp(rowApps.get(Math.min(appFocus, rowApps.size() - 1)));
    }

    /**
     * The app on screen last before Home, from the system's usage events (so it also counts apps opened by
     * a remote button). Falls back to the last app opened from Home if usage access was not granted.
     */
    private String recentApp() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        String found = null;
        try {
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 12 * 60 * 60 * 1000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
                String p = e.getPackageName();
                if (find(p) != null) found = p;   // only apps in the row/grid; skips Home, settings, TV input
            }
        } catch (Exception ignored) {
            // No usage access: use what Home itself saw.
        }
        return found != null ? found : prefs.getString("last_app", null);
    }

    /** Back on Home: if another app was used since, it moves to the front of the row and gets the focus. */
    private void updateRecent() {
        loader.execute(() -> {
            String r = recentApp();
            main.post(() -> {
                if (isDestroyed() || r == null || r.equals(lastUsed) || moving >= 0) return;
                lastUsed = r;
                prefs.edit().putString("recent_app", r).apply();   // so a cold start draws "Continuar" right away
                appFocus = 0;
                fillRows();
                if (section == HOME && !appTiles.isEmpty() && menu == null) appTiles.get(0).requestFocus();
                showFocused();
            });
        });
    }

    private void showApp(Sources.App a) {
        String button = "Abrir " + a.label;
        boolean changed = !button.contentEquals(heroButton.getText());
        Trending.Item t = trending.get(a.pkg);
        Trending.Provider p = Trending.PROVIDERS.get(a.pkg);
        heroAction = () -> launch(a.intent);
        heroButton.setText(button);
        if (t != null && p != null) {
            setBadge("Em alta no " + p.name, p.color);
            changed |= !t.title.contentEquals(heroTitle.getText());
            setHeroTitle(t.title);
            heroText.setText(TextUtils.isEmpty(t.overview) ? t.kind : t.kind + " · " + t.overview);
            setBackdrop(Uri.fromFile(t.image), p.color);
        } else {
            setBadge("App", CHIP);
            setHeroTitle(a.label);
            heroText.setText(p != null && !trending.hasKey()
                    ? "Aperte OK para abrir. As séries em alta aparecem aqui quando a chave do TMDB for configurada."
                    : "Aperte OK no controle para abrir.");
            // A known service's brand color beats the banner's average (Netflix's banner is mostly white).
            setBackdrop(null, p != null ? p.color : a.color);
        }
        if (changed) animateHero();
    }

    private void showInput(Sources.Input in) {
        boolean changed = !in.label.contentEquals(heroTitle.getText());
        heroAction = () -> launch(Sources.inputIntent(in));
        heroButton.setText(in.tuner ? "Assistir TV" : "Abrir " + in.label);
        setBadge(in.tuner ? "Antena" : "Entrada", CHIP);
        setHeroTitle(in.label);
        heroText.setText(in.tuner ? "Canais da TV aberta pela antena." : "Status: " + in.status.toLowerCase() + ".");
        setBackdrop(null, in.tuner ? 0xFF2E3B52 : 0xFF4B2E73);
        if (changed) animateHero();
    }

    /** One line holds about 18 characters at full size; longer titles shrink so two lines always fit the hero. */
    private void setHeroTitle(String title) {
        heroTitle.setText(title);
        int size = title.length() <= 18 ? 96 : title.length() <= 34 ? 80 : 68;
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(size));
    }

    private void setBadge(String s, int color) {
        heroBadge.setText(s);
        badgeBg.setColor(color);
    }

    /** The hero copy slides up and fades in as one block, drawn through a temporary GPU layer. */
    private void animateHero() {
        hero.animate().cancel();
        hero.setAlpha(0f);
        hero.setTranslationY(ui.px(18));
        hero.animate().alpha(1f).translationY(0).setInterpolator(ease).setDuration(260).withLayer().start();
    }

    private void setBackdrop(Uri image, int tint) {
        backdropFallback.setColors(new int[]{shade(tint, 0.30f), shade(tint, 0.62f)});
        if (image == null) {
            backdrop.setTag(null);
            backdrop.setImageDrawable(null);
            return;
        }
        images.load(image, backdrop, ui.px(1920), ui.px(800), () -> {
            backdrop.setAlpha(0.2f);
            backdrop.animate().alpha(1f).setInterpolator(ease).setDuration(380).withLayer().start();
        });
    }

    // ---------------------------------------------------------------- Apps and Entradas

    private ScrollView buildApps() {
        ScrollView scroll = sectionScroll();
        LinearLayout page = (LinearLayout) scroll.getChildAt(0);
        List<Sources.App> shown = new ArrayList<>(), away = new ArrayList<>();
        for (Sources.App a : apps) (hidden.contains(a.pkg) ? away : shown).add(a);
        page.addView(sectionTitle("Todos os apps",
                shown.size() + " apps  ·  Segure OK em um app para fixar no Início, mover ou esconder"));
        addGrid(page, shown, false);
        if (!away.isEmpty()) {
            TextView h = ui.text("Escondidos", 30, 700, SOFT);
            h.setPadding(0, ui.px(56), 0, ui.px(8));
            page.addView(h);
            addGrid(page, away, true);
        }
        // Required by TMDB's terms when its data and images are shown.
        TextView credit = ui.text("Imagens e títulos em alta: TMDB. Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.", 18, 400, MUTED);
        credit.setPadding(0, ui.px(56), 0, 0);
        page.addView(credit);
        return scroll;
    }

    /** Rows of five tiles; hidden apps are drawn faded and their menu only offers to show them again. */
    private void addGrid(LinearLayout page, List<Sources.App> list, boolean faded) {
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, ui.px(184));
                rlp.topMargin = ui.px(i == 0 ? 8 : 32);
                page.addView(row, rlp);
            }
            Sources.App a = list.get(i);
            View tile = appTile(a, 326, 184, 1.08f, null);
            if (faded) tile.setAlpha(0.45f);
            tile.setOnLongClickListener(v -> {
                openMenu(a, v, false);
                return true;
            });
            tile.setOnKeyListener((v, code, ev) -> menuKey(v, code, ev, () -> openMenu(a, v, false)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i % 5 > 0) lp.setMarginStart(ui.px(24));
            row.addView(tile, lp);
        }
        // Keep the last row's tiles the same width as the full rows.
        if (row != null) {
            for (int k = list.size() % 5; k > 0 && k < 5; k++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                lp.setMarginStart(ui.px(24));
                row.addView(new View(this), lp);
            }
        }
    }

    // ---------------------------------------------------------------- TV health

    /** Memory, storage, uptime and Play Services' data, a "free memory" button and what uses memory now. */
    private ScrollView buildHealth() {
        ScrollView scroll = sectionScroll();
        LinearLayout page = (LinearLayout) scroll.getChildAt(0);
        page.addView(sectionTitle("Saúde da TV", lastMaintenanceText()));

        long total = Maintenance.totalMem(this), avail = Maintenance.availMem(this);
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long diskFree = fs.getAvailableBytes(), diskTotal = fs.getTotalBytes();
        long up = SystemClock.elapsedRealtime();

        LinearLayout stats = new LinearLayout(this);
        stats.setClipChildren(false);
        stats.addView(statCard("Memória livre", mb(avail), "de " + mb(total)), statLp(0));
        stats.addView(statCard("Armazenamento", gb(diskFree) + " livres",
                Math.round(100.0 * (diskTotal - diskFree) / diskTotal) + "% usado"), statLp(1));
        stats.addView(statCard("Ligada há", duration(up), "desde o último reinício"), statLp(2));
        TextView gms = statValue("…");
        stats.addView(statCard("Play Services", gms, "dados guardados"), statLp(3));
        page.addView(stats, new LinearLayout.LayoutParams(-1, ui.px(200)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setClipChildren(false);
        actions.setClipToPadding(false);
        actions.setPadding(0, ui.px(40), 0, 0);
        TextView free = ui.text("Liberar memória e reaplicar ajustes", 26, 700, GROUND);
        free.setGravity(Gravity.CENTER);
        free.setPadding(ui.px(36), 0, ui.px(36), 0);
        free.setBackground(ui.round(INK, 34));
        String last = prefs.getString("maint_result", "");
        boolean recent = System.currentTimeMillis() - prefs.getLong("maint_at", 0) < 15 * 60 * 1000L;
        TextView result = ui.text(recent && !last.isEmpty() ? last
                : "Fecha apps parados em segundo plano e desliga de novo o que a TCL religou.", 22, 500, MUTED);
        result.setPadding(ui.px(32), 0, 0, 0);
        result.setMaxLines(3);
        focusable(free, 1.04f, 34, () -> freeMemory(free, result), null);
        actions.addView(free, new LinearLayout.LayoutParams(-2, ui.px(72)));
        actions.addView(result, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(actions);

        TextView topTitle = ui.text("O que mais usa memória agora", 30, 700, SOFT);
        topTitle.setPadding(0, ui.px(56), 0, ui.px(16));
        page.addView(topTitle);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        TextView loading = ui.text("Carregando…", 22, 500, MUTED);
        top.addView(loading);
        page.addView(top);

        loader.execute(() -> {
            String gmsSize = packageData("com.google.android.gms");
            main.post(() -> gms.setText(gmsSize));
            List<String> heavy = Maintenance.topMemory(this, 8);   // dumpsys is slow here: the card above need not wait for it
            main.post(() -> {
                top.removeAllViews();
                if (heavy.isEmpty()) top.addView(ui.text("Sem acesso à depuração: use o botão acima uma vez para autorizar.", 22, 500, MUTED));
                for (String h : heavy) {
                    TextView t = ui.text(h, 24, 500, SOFT);
                    t.setPadding(0, ui.px(6), 0, ui.px(6));
                    top.addView(t);
                }
            });
        });
        return scroll;
    }

    private void freeMemory(TextView button, TextView result) {
        button.setEnabled(false);
        button.setText("Trabalhando…");
        result.setText("Se aparecer \"Permitir depuração?\" na tela, marque \"Sempre permitir\" e confirme.");
        background.execute(() -> {
            Maintenance.Report r = Maintenance.run(this, true, 60000);
            saveMaintenance(r);
            main.post(() -> {
                button.setEnabled(true);
                button.setText("Liberar memória e reaplicar ajustes");
                if (r.error != null) {
                    result.setText("Não consegui acessar a depuração da TV (" + r.error + ").");
                    return;
                }
                StringBuilder s = new StringBuilder();
                s.append(mb(r.freedBytes)).append(" liberados");
                s.append(r.fixed == 0 ? " · tudo já estava em ordem" : " · " + r.fixed + " ajuste(s) refeito(s)");
                for (int i = 0; i < Math.min(3, r.lines.size()); i++) s.append("\n").append(r.lines.get(i));
                result.setText(s.toString());
                prefs.edit().putString("maint_result", s.toString()).apply();
                if (section == HEALTH) {
                    sections[HEALTH] = rebuild(HEALTH);
                }
            });
        });
    }

    /** Runs the maintenance quietly (no prompt, no closing apps) at most every 6 hours. */
    private void autoMaintenance() {
        if (System.currentTimeMillis() - prefs.getLong("maint_at", 0) < 6 * 60 * 60 * 1000L) return;
        saveMaintenance(Maintenance.run(this, false, 0));
    }

    private void saveMaintenance(Maintenance.Report r) {
        if (r.error != null) return;
        prefs.edit().putLong("maint_at", System.currentTimeMillis()).putInt("maint_fixed", r.fixed)
                .putString("maint_lines", TextUtils.join("\n", r.lines)).apply();
    }

    private String lastMaintenanceText() {
        long at = prefs.getLong("maint_at", 0);
        if (at == 0) return "Manutenção automática: ainda não rodou (autorize com o botão abaixo)";
        String when = DateFormat.format("dd/MM 'às' HH:mm", at).toString();
        int fixed = prefs.getInt("maint_fixed", 0);
        return "Última manutenção: " + when + (fixed == 0 ? " · tudo em ordem" : " · " + fixed + " ajuste(s) refeito(s)");
    }

    private String packageData(String pkg) {
        try {
            StorageStatsManager ssm = (StorageStatsManager) getSystemService(STORAGE_STATS_SERVICE);
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            StorageStats st = ssm.queryStatsForUid(ai.storageUuid, ai.uid);
            return mb(st.getDataBytes());
        } catch (Exception e) {
            return "—";
        }
    }

    private View statCard(String title, String value, String sub) {
        return statCard(title, statValue(value), sub);
    }

    private TextView statValue(String value) {
        return ui.text(value, 40, 800, INK);
    }

    private View statCard(String title, TextView value, String sub) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.px(30), ui.px(26), ui.px(30), ui.px(26));
        card.setBackground(ui.round(PILL, 26));
        card.addView(ui.text(title, 22, 600, MUTED));
        card.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        card.addView(value);
        TextView s = ui.text(sub, 20, 500, MUTED);
        s.setPadding(0, ui.px(6), 0, 0);
        card.addView(s);
        return card;
    }

    private LinearLayout.LayoutParams statLp(int i) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (i > 0) lp.setMarginStart(ui.px(24));
        return lp;
    }

    private static String mb(long bytes) {
        return Math.round(bytes / 1048576.0) + " MB";
    }

    private static String gb(long bytes) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%.1f GB", bytes / 1073741824.0);
    }

    private static String duration(long ms) {
        long h = ms / 3600000, d = h / 24;
        if (d >= 1) return d + (d == 1 ? " dia " : " dias ") + (h % 24) + " h";
        if (h >= 1) return h + " h";
        return (ms / 60000) + " min";
    }

    private ScrollView buildInputs() {
        ScrollView scroll = sectionScroll();
        LinearLayout page = (LinearLayout) scroll.getChildAt(0);
        page.addView(sectionTitle("Entradas", "Escolha o que assistir  ·  Segure OK (ou Menu) para dar um nome, como PS5"));
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        for (Sources.Input in : inputs) {
            boolean on = "Conectado".equals(in.status);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(ui.px(32), ui.px(30), ui.px(32), ui.px(30));
            card.setBackground(ui.round(PILL, 28));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(new StrokeIcon(in.tuner ? StrokeIcon.ANTENNA : StrokeIcon.INPUT, INK));
            card.addView(icon, new LinearLayout.LayoutParams(ui.px(48), ui.px(48)));
            card.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            card.addView(ui.text(in.label, 36, 800, INK));
            LinearLayout status = new LinearLayout(this);
            status.setGravity(Gravity.CENTER_VERTICAL);
            status.setPadding(0, ui.px(10), 0, 0);
            status.addView(statusDot(on));
            String sub = (in.label.equals(in.port) ? "" : in.port + " · ") + in.status;
            TextView st = ui.text(sub, 22, 500, on ? SOFT : MUTED);
            st.setPadding(ui.px(10), 0, 0, 0);
            status.addView(st);
            card.addView(status);
            focusable(card, 1.06f, 28, () -> launch(Sources.inputIntent(in)), null);
            card.setOnLongClickListener(v -> {
                openInputMenu(in, v);
                return true;
            });
            card.setOnKeyListener((v, code, ev) -> menuKey(v, code, ev, () -> openInputMenu(in, v)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ui.px(260), 1);
            if (row.getChildCount() > 0) lp.setMarginStart(ui.px(28));
            row.addView(card, lp);
        }
        page.addView(row, new LinearLayout.LayoutParams(-1, ui.px(260)));
        return scroll;
    }

    private View statusDot(boolean on) {
        View dot = new View(this);
        dot.setBackground(ui.round(on ? 0xFF5BD38A : 0xFF5A5E68, 6));
        dot.setLayoutParams(new LinearLayout.LayoutParams(ui.px(12), ui.px(12)));
        return dot;
    }

    private ScrollView sectionScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.px(96), ui.px(170), ui.px(96), ui.px(80));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        scroll.addView(page);
        return scroll;
    }

    private View sectionTitle(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, ui.px(28));
        TextView t = ui.text(title, 60, 800, INK);
        t.setLetterSpacing(-0.02f);
        box.addView(t);
        TextView s = ui.text(sub, 24, 500, MUTED);
        s.setPadding(0, ui.px(10), 0, 0);
        box.addView(s);
        return box;
    }

    // ---------------------------------------------------------------- building blocks

    private View appTile(Sources.App a, int w, int h, float lift, Runnable onFocus) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(ui.round(0xFFF3F4F6, 22));
        tile.setContentDescription(a.label);
        if (a.banner != null) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(a.banner);
            tile.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView img = new ImageView(this);
            img.setImageDrawable(a.icon);
            col.addView(img, new LinearLayout.LayoutParams(ui.px(h / 2), ui.px(h / 2)));
            TextView t = ui.text(a.label, 22, 700, GROUND);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            col.addView(t);
            tile.addView(col, new FrameLayout.LayoutParams(-1, -1));
        }
        focusable(tile, lift, 22, () -> launch(a.intent), onFocus);
        return tile;
    }

    private TextView tab(String label, View.OnClickListener click) {
        TextView t = ui.text(label, 24, 600, SOFT);
        t.setPadding(ui.px(24), ui.px(10), ui.px(24), ui.px(10));
        t.setFocusable(true);
        t.setOnClickListener(click);
        t.setOnFocusChangeListener((v, has) -> paintTabs());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(ui.px(12));
        t.setLayoutParams(lp);
        return t;
    }

    /** Focused tab: solid light pill. Current section, unfocused: faint pill. Others: text only. */
    private void paintTabs() {
        for (int i = 0; i < tabs.length; i++) {
            TextView t = tabs[i];
            if (t == null) continue;
            boolean has = t.isFocused(), current = i == section;
            t.setBackground(has ? ui.round(INK, 26) : current ? ui.round(CHIP, 26) : null);
            t.setTextColor(has ? GROUND : current ? INK : SOFT);
        }
    }

    private View roundButton(StrokeIcon icon, String label, View.OnClickListener click, View.OnLongClickListener longClick) {
        FrameLayout b = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(CHIP);
        b.setBackground(bg);
        b.setContentDescription(label);
        b.setFocusable(true);
        b.setOnClickListener(click);
        if (longClick != null) b.setOnLongClickListener(longClick);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        b.addView(iv, new FrameLayout.LayoutParams(ui.px(26), ui.px(26), Gravity.CENTER));
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? INK : CHIP);
            icon.setColor(has ? GROUND : INK);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setInterpolator(ease).setDuration(160).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(56), ui.px(56));
        lp.setMarginStart(ui.px(14));
        b.setLayoutParams(lp);
        return b;
    }

    /** Focus look for tiles, pills and cards: a light ring and a small zoom. Transforms only, no shadow. */
    private void focusable(View v, float lift, int radius, Runnable click, Runnable onFocus) {
        v.setFocusable(true);
        v.setClipToOutline(true);
        v.setOutlineProvider(ui.rounded(radius));
        v.setOnClickListener(x -> {
            launchSource = x;
            click.run();
        });
        GradientDrawable ring = ui.ring(radius, INK, 5);
        v.setOnFocusChangeListener((x, has) -> {
            x.setForeground(has ? ring : null);
            x.animate().scaleX(has ? lift : 1f).scaleY(has ? lift : 1f).setInterpolator(ease).setDuration(220).start();
            if (has && onFocus != null) onFocus.run();
        });
    }

    // ---------------------------------------------------------------- actions

    /** Opens an app (growing out of its tile when picked from one) and frees the RAM other streaming apps hold. */
    private void launch(Intent i) {
        if (i == null) return;
        View from = launchSource;
        launchSource = null;
        Bundle options = from != null && from.isAttachedToWindow()
                ? ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle()
                : null;
        String pkg = i.getPackage() != null ? i.getPackage()
                : i.getComponent() != null ? i.getComponent().getPackageName() : null;
        try {
            Sources.open(this, i, options);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pkg != null && find(pkg) != null) prefs.edit().putString("last_app", pkg).apply();
        if (pkg != null && (Sources.STREAMING.contains(pkg) || Sources.LIVE_TV_PKG.equals(pkg))) loader.execute(() -> Sources.closeOtherStreaming(this, pkg));
    }

    /** TCL's quick panel (picture, sound, network); the Android settings where it is missing. Hold OK: full settings. */
    private void openTclSettings() {
        Intent tcl = Sources.tclSettings(getPackageManager());
        launch(tcl != null ? tcl : new Intent(Settings.ACTION_SETTINGS));
    }

    private void updateWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean online = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        wifiIcon.setKind(online ? StrokeIcon.WIFI : StrokeIcon.WIFI_OFF);
    }

    // ---------------------------------------------------------------- helpers

    private FrameLayout.LayoutParams frame(int w, int h, int start, int top, int end) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.setMarginStart(start);
        lp.topMargin = top;
        lp.setMarginEnd(end);
        return lp;
    }

    private static int shade(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }
}
