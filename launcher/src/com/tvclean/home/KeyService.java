package com.tvclean.home;

import android.accessibilityservice.AccessibilityService;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hold Home anywhere (even over Netflix): a side panel with Início, the previous app, the inputs, "free
 * memory", picture and settings. A short press of Home still goes Home. Enabled by Home's maintenance
 * (secure setting enabled_accessibility_services), since an accessibility service is the only way
 * without root to see the Home key inside other apps. It reads nothing on screen: key events only.
 */
public class KeyService extends AccessibilityService {
    private static final long HOLD_MS = 600;
    private static final int GROUND = 0xF20B0D12, INK = 0xFFF5F2EC, SOFT = 0xFFD6D2CA, MUTED = 0xFFA9A59D;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean held;
    private View panel;
    private String foreground;   // package on screen, from window events
    private Ui ui;

    private final Runnable hold = () -> {
        held = true;
        showPanel();
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            String p = e.getPackageName().toString();
            if (!p.equals("com.android.systemui")) foreground = p;
        }
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * Home inside an app: first press brings up the app bar over it, a second press goes to Início.
     * Holding Home opens the quick panel. On Início itself Home is plain Home.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        if (ev.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (panel != null) {
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                // The release of the very press that opened the panel must not close it again.
                if (held) held = false;
                else closePanel();
            }
            return true;
        }
        if (bar != null) {
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                closeBar();
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
            return true;
        }
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (ev.getRepeatCount() == 0) {
                held = false;
                main.postDelayed(hold, HOLD_MS);
            } else if (!held && ev.isLongPress()) {
                // Remotes that flag the long press themselves: no need to wait for the timer.
                main.removeCallbacks(hold);
                hold.run();
            }
            return true;
        }
        // Key up after a short press: the bar over an app, plain Home anywhere else.
        main.removeCallbacks(hold);
        if (held) return true;
        if (inApp()) showBar();
        else performGlobalAction(GLOBAL_ACTION_HOME);
        return true;
    }

    private boolean inApp() {
        return foreground != null && !foreground.equals(getPackageName());
    }

    // ---------------------------------------------------------------- app bar

    private View bar;
    private List<Sources.App> barApps;
    private String barKey;

    /** The Início row's apps (same order, same hidden ones), with the banners Home keeps in its snapshot. */
    private List<Sources.App> barApps() {
        SharedPreferences prefs = getSharedPreferences("tvclean", MODE_PRIVATE);
        String pinned = prefs.getString("pinned", null), hidden = prefs.getString("hidden", "");
        String key = pinned + "|" + hidden + "|" + new File(new File(getFilesDir(), "snapshot"), "apps.json").lastModified();
        if (key.equals(barKey) && barApps != null) return barApps;
        List<Sources.App> all = Sources.loadSnapshot(this);
        List<Sources.App> out = new ArrayList<>();
        if (all != null) {
            Set<String> away = new HashSet<>(Arrays.asList(hidden.split(",")));
            if (pinned != null) {
                for (String p : pinned.split(",")) {
                    for (Sources.App a : all) if (a.pkg.equals(p) && !away.contains(p)) out.add(a);
                }
            } else {
                for (Sources.App a : all) if (Sources.pinnedByDefault(a) && !away.contains(a.pkg)) out.add(a);
            }
        }
        barApps = out;
        barKey = key;
        return out;
    }

    private void showBar() {
        if (bar != null) return;
        ui = new Ui(this);
        FrameLayout root = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent ev) {
                if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (ev.getAction() == KeyEvent.ACTION_UP) closeBar();
                    return true;
                }
                return super.dispatchKeyEvent(ev);
            }
        };
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x000B0D12, 0xE60B0D12, GROUND}));
        root.setClipChildren(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        head.setPadding(ui.px(96), 0, ui.px(96), ui.px(14));
        head.addView(ui.text("Seus apps", 26, 700, INK), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(ui.text("Casinha de novo: Início   ·   Voltar: fechar", 20, 500, MUTED));
        col.addView(head);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(ui.px(96), ui.px(16), ui.px(96), ui.px(28));
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        scroll.addView(row);
        col.addView(scroll);

        View homeTile = tile(row, null, "Início", () -> performGlobalAction(GLOBAL_ACTION_HOME));
        View focus = homeTile;
        for (Sources.App a : barApps()) {
            View t = tile(row, a.banner, a.label, () -> openApp(a));
            if (a.pkg.equals(foreground)) focus = t;   // OK right away goes back to what was playing
        }
        root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, ui.px(380),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.windowAnimations = android.R.style.Animation_InputMethod;   // slides up from the bottom
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(root, lp);
        bar = root;
        focus.requestFocus();
    }

    private void closeBar() {
        if (bar == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(bar);
        } catch (Exception ignored) {
            // Already removed.
        }
        bar = null;
    }

    /** A 16:9 tile: the app's banner, or the label on a dark tile (Início). */
    private View tile(LinearLayout row, Drawable banner, String label, Runnable action) {
        FrameLayout t = new FrameLayout(this);
        t.setBackground(ui.round(banner == null ? 0xFF2A2D35 : 0xFFF3F4F6, 18));
        t.setContentDescription(label);
        if (banner != null) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(banner);
            t.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView name = ui.text(label, 26, 700, INK);
            t.addView(name, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        t.setFocusable(true);
        t.setClipToOutline(true);
        t.setOutlineProvider(ui.rounded(18));
        GradientDrawable ring = ui.ring(18, INK, 5);
        t.setOnFocusChangeListener((v, has) -> {
            v.setForeground(has ? ring : null);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(180).start();
        });
        t.setOnClickListener(v -> {
            closeBar();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(260), ui.px(146));
        if (row.getChildCount() > 0) lp.setMarginStart(ui.px(24));
        row.addView(t, lp);
        return t;
    }

    /** Opens an app from the bar; like Home, a streaming app takes the memory the idle ones hold. */
    private void openApp(Sources.App a) {
        open(a.intent);
        if (!Sources.STREAMING.contains(a.pkg)) return;
        new Thread(() -> Sources.closeOtherStreaming(this, a.pkg), "tvclean-bar").start();
    }

    // ---------------------------------------------------------------- panel

    private void showPanel() {
        if (panel != null) return;
        ui = new Ui(this);
        FrameLayout root = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent ev) {
                if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (ev.getAction() == KeyEvent.ACTION_UP) closePanel();
                    return true;
                }
                return super.dispatchKeyEvent(ev);
            }
        };
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xD90B0D12, GROUND}));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(ui.px(48), ui.px(56), ui.px(48), ui.px(48));
        scroll.addView(list);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        head.addView(ui.text("Atalhos", 44, 800, INK), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(ui.clock("HH:mm", 30, 700, SOFT));
        list.addView(head);
        TextView mem = ui.text("Memória livre: " + Maintenance.availMem(this) / 1048576 + " MB", 20, 500, MUTED);
        mem.setPadding(0, ui.px(8), 0, ui.px(28));
        list.addView(mem);

        View first = row(list, "Início", () -> performGlobalAction(GLOBAL_ACTION_HOME));
        String prev = previousApp();
        if (prev != null) {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(prev);
            if (launch != null) row(list, "Voltar para " + label(prev), () -> open(launch));
        }

        List<Sources.Input> inputs = Sources.inputs(this);
        if (!inputs.isEmpty()) list.addView(heading("Entradas"));
        for (Sources.Input in : inputs) row(list, in.label + "   " + in.status.toLowerCase(), () -> open(Sources.inputIntent(this, in)));

        list.addView(heading("TV"));
        TextView status = ui.text("", 20, 500, MUTED);
        row(list, "Liberar memória", () -> {
            status.setText("Liberando…");
            new Thread(() -> {
                Maintenance.Report r = Maintenance.run(this, true, 0);
                main.post(() -> status.setText(r.error != null ? "Sem acesso à depuração" :
                        (r.freedBytes / 1048576) + " MB liberados" + (r.fixed > 0 ? " · " + r.fixed + " ajuste(s) refeito(s)" : "")));
            }, "tvclean-panel").start();
        }, false);
        list.addView(status);
        row(list, "Imagem e som", () -> {
            Intent tcl = Sources.tclSettings(getPackageManager());
            open(tcl != null ? tcl : new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        });
        row(list, "Configurações", () -> open(new Intent(Settings.ACTION_SETTINGS)));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ui.px(640), WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.dimAmount = 0.35f;
        lp.windowAnimations = android.R.style.Animation_Toast;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(root, lp);
        panel = root;
        first.requestFocus();
    }

    private void closePanel() {
        if (panel == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(panel);
        } catch (Exception ignored) {
            // Already removed.
        }
        panel = null;
    }

    private View row(LinearLayout list, String label, Runnable action) {
        return row(list, label, action, true);
    }

    /** A focusable row; `closes` hides the panel before running the action. */
    private View row(LinearLayout list, String label, Runnable action, boolean closes) {
        TextView t = ui.text(label, 26, 600, INK);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(ui.px(26), ui.px(18), ui.px(26), ui.px(18));
        t.setFocusable(true);
        GradientDrawable on = ui.round(INK, 20);
        t.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? on : null);
            ((TextView) v).setTextColor(has ? 0xFF0B0D12 : INK);
        });
        t.setOnClickListener(v -> {
            if (closes) closePanel();
            action.run();
        });
        list.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private TextView heading(String s) {
        TextView t = ui.text(s, 20, 700, MUTED);
        t.setPadding(ui.px(26), ui.px(28), 0, ui.px(8));
        return t;
    }

    private void open(Intent i) {
        try {
            startActivity(new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Nothing to open: the panel just closes.
        }
    }

    /** The app used before the one on screen (or the last one, from Home), from usage events. */
    private String previousApp() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        String last = null, beforeLast = null;
        try {
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 12 * 60 * 60 * 1000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            Map<String, Boolean> launchable = new HashMap<>();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
                String p = e.getPackageName();
                Boolean ok = launchable.get(p);
                if (ok == null) {
                    ok = !p.equals(getPackageName()) && getPackageManager().getLeanbackLaunchIntentForPackage(p) != null;
                    launchable.put(p, ok);
                }
                if (!ok) continue;
                if (!p.equals(last)) {
                    beforeLast = last;
                    last = p;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return last != null && last.equals(foreground) ? beforeLast : last;
    }

    private String label(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }
}
