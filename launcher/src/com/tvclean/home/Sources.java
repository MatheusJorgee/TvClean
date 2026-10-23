package com.tvclean.home;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the home screen shows, read from the system: apps and inputs. */
final class Sources {
    static final String LIVE_TV_PKG = "com.tcl.tv";

    /** Streaming apps: opening one (or live TV) closes the others left idle; they hold 80-110 MB each. */
    static final Set<String> STREAMING = new HashSet<>(Arrays.asList(
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.globo.globotv", "com.wbd.stream",
            "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.crunchyroll.crunchyroid",
            "com.apple.atve.androidtv.appletv"));

    /** Dock order: the first of these that are installed, then the rest of the apps alphabetically. */
    private static final List<String> FAVORITES = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.globo.globotv",
            "com.wbd.stream",
            "com.crunchyroll.crunchyroid",
            "com.spotify.tv.android",
            "com.android.vending");

    /** System entries that are not apps a person opens from Home. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.tvclean.home",
            "com.tcl.tv",
            "com.tcl.partnercustomizer",
            "com.tcl.guard",
            "com.tcl.smartalexa",
            "com.tcl.dashboard",
            "com.tcl.suspension",
            // Disabled by tv/debloat.txt, but TCL's services switch them back on from time to time.
            "com.tcl.waterfall.overseas",
            "com.tcl.usercenter"));

    static final class App {
        String pkg;
        String label;
        Drawable banner;   // pre-scaled to the tile size, RGB_565: ~50 KB instead of the app's full-size banner
        Drawable icon;     // only kept when there is no banner
        int color;
        boolean userInstalled;
        Intent intent;
    }

    /** What the Início row shows before the person picks favorites: the services above plus apps they installed. */
    static boolean pinnedByDefault(App a) {
        return FAVORITES.contains(a.pkg) || a.userInstalled || "com.google.android.youtube.tvmusic".equals(a.pkg);
    }

    static final class Input {
        String id;
        String label;    // what to show: the name the person gave it, else the device's own (HDMI-CEC), else the port
        String port;     // "HDMI 2", "AV", "TV Aberta"
        String device;   // the connected device's name over HDMI-CEC ("PlayStation 5"), or null
        String status;
        boolean tuner;
    }

    /** A name for an input ("PS5"), kept in Home's preferences; null or empty puts the original back. */
    static void nameInput(Context c, String id, String name) {
        SharedPreferences p = c.getSharedPreferences("tvclean", Context.MODE_PRIVATE);
        if (name == null || name.trim().isEmpty()) p.edit().remove("input_name_" + id).apply();
        else p.edit().putString("input_name_" + id, name.trim()).apply();
    }

    static List<App> apps(Context c, int bannerW, int bannerH) {
        PackageManager pm = c.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (HIDDEN.contains(pkg) || !seen.add(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (banner != null) {
                a.color = averageColor(banner);
                a.banner = scaled(c, banner, bannerW, bannerH);
            } else {
                a.icon = ri.loadIcon(pm);
                a.color = averageColor(a.icon);
            }
            a.userInstalled = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            a.intent = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (a.intent == null) a.intent = pm.getLaunchIntentForPackage(pkg);
            if (a.intent != null) out.add(a);
        }
        Collections.sort(out, (x, y) -> {
            int fx = FAVORITES.indexOf(x.pkg), fy = FAVORITES.indexOf(y.pkg);
            if (fx < 0) fx = Integer.MAX_VALUE;
            if (fy < 0) fy = Integer.MAX_VALUE;
            if (fx != fy) return Integer.compare(fx, fy);
            return x.label.compareToIgnoreCase(y.label);
        });
        return out;
    }

    // ---------------------------------------------------------------- startup snapshot

    private static File snapDir(Context c) {
        return new File(c.getFilesDir(), "snapshot");
    }

    static boolean hasSnapshot(Context c) {
        return new File(snapDir(c), "apps.json").exists();
    }

    /**
     * Writes what the row needs (name, color, launch intent, tile-size banner as PNG) so that a cold start
     * can draw it straight away, without asking the package manager or opening each app's resources.
     */
    static void saveSnapshot(Context c, List<App> apps) {
        File dir = snapDir(c);
        dir.mkdirs();
        try {
            JSONArray list = new JSONArray();
            for (App a : apps) {
                JSONObject o = new JSONObject();
                o.put("pkg", a.pkg);
                o.put("label", a.label);
                o.put("color", a.color);
                o.put("user", a.userInstalled);
                o.put("intent", a.intent.toUri(Intent.URI_INTENT_SCHEME));
                list.put(o);
                if (a.banner instanceof BitmapDrawable) {
                    try (FileOutputStream out = new FileOutputStream(new File(dir, a.pkg + ".png"))) {
                        ((BitmapDrawable) a.banner).getBitmap().compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                }
            }
            // Written last and atomically enough: a snapshot without apps.json is simply not used.
            File tmp = new File(dir, "apps.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(list.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(new File(dir, "apps.json"));
        } catch (Exception ignored) {
            // No snapshot: the next cold start just waits for the package manager, as before.
        }
    }

    /** The saved row, or null. Banners decode at their saved (tile) size; apps without one show their name. */
    static List<App> loadSnapshot(Context c) {
        File dir = snapDir(c);
        try (FileInputStream in = new FileInputStream(new File(dir, "apps.json"))) {
            byte[] data = new byte[(int) new File(dir, "apps.json").length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONArray list = new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            List<App> out = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                App a = new App();
                a.pkg = o.getString("pkg");
                a.label = o.getString("label");
                a.color = o.optInt("color", 0xFF2A2D35);
                a.userInstalled = o.optBoolean("user");
                a.intent = Intent.parseUri(o.getString("intent"), Intent.URI_INTENT_SCHEME);
                File png = new File(dir, a.pkg + ".png");
                if (png.exists()) {
                    Bitmap b = BitmapFactory.decodeFile(png.getPath(), opts);
                    if (b != null) a.banner = new BitmapDrawable(c.getResources(), b);
                }
                out.add(a);
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /** The banner drawn once at tile size on the tile's light ground; the GPU then uploads a small texture. */
    private static Drawable scaled(Context c, Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas cv = new Canvas(b);
        cv.drawColor(0xFFF3F4F6);
        d.setBounds(0, 0, w, h);
        d.draw(cv);
        b.prepareToDraw();
        return new BitmapDrawable(c.getResources(), b);
    }

    /** The drawable's average color, from an 8x4 render, for tinting the hero behind an app. */
    private static int averageColor(Drawable d) {
        if (d == null) return 0xFF2A2D35;
        Bitmap b = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, 8, 4);
        d.draw(cv);
        long r = 0, g = 0, bl = 0, n = 0;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 8; x++) {
                int p = b.getPixel(x, y);
                if ((p >>> 24) < 128) continue;
                r += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; bl += p & 0xFF; n++;
            }
        }
        b.recycle();
        if (n == 0) return 0xFF2A2D35;
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (bl / n);
    }

    static List<Input> inputs(Context c) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> list = new ArrayList<>(tim.getTvInputList());
        Collections.sort(list, (a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return Integer.compare(ra, rb);
            return String.valueOf(a.loadLabel(c)).compareToIgnoreCase(String.valueOf(b.loadLabel(c)));
        });
        // Some TVs report every port connected whether or not anything is plugged in: then the state means nothing.
        int ports = 0, connected = 0;
        for (TvInputInfo i : list) {
            if (!i.isPassthroughInput()) continue;
            ports++;
            if (tim.getInputState(i.getId()) == TvInputManager.INPUT_STATE_CONNECTED) connected++;
        }
        boolean bogus = ports > 1 && connected == ports;
        // Devices that answer over HDMI-CEC show up as child inputs of their port, named by the device.
        Map<String, String> cecNames = new HashMap<>();
        for (TvInputInfo i : list) {
            if (i.getParentId() == null) continue;
            CharSequence l = i.loadLabel(c);
            if (l != null && l.length() > 0) cecNames.put(i.getParentId(), l.toString());
        }
        SharedPreferences prefs = c.getSharedPreferences("tvclean", Context.MODE_PRIVATE);
        Input tuner = null;
        for (TvInputInfo i : list) {
            if (i.getParentId() != null) continue;   // listed through its port
            boolean system = (i.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (i.getType() == TvInputInfo.TYPE_TUNER && system) {
                // Prefer a hardware tuner (".../HW<n>") over software inputs that also register as TUNER.
                if (tuner == null || i.getId().matches(".*/HW\\d+$")) {
                    tuner = new Input();
                    tuner.id = i.getId();
                    tuner.port = "TV Aberta";
                    tuner.label = prefs.getString("input_name_" + tuner.id, tuner.port);
                    tuner.status = "Antena";
                    tuner.tuner = true;
                }
                continue;
            }
            if (!i.isPassthroughInput()) continue;
            Input in = new Input();
            in.id = i.getId();
            CharSequence l = i.loadLabel(c);
            in.port = l == null ? "Entrada" : l.toString();
            if (i.getType() == TvInputInfo.TYPE_HDMI && !in.port.toUpperCase().contains("HDMI")) in.port = "HDMI " + in.port;
            in.device = cecNames.get(i.getId());
            in.label = prefs.getString("input_name_" + in.id, in.device != null ? in.device : in.port);
            if (bogus) {
                in.status = "Entrada";
            } else {
                int s = tim.getInputState(i.getId());
                in.status = s == TvInputManager.INPUT_STATE_CONNECTED ? "Conectado"
                        : s == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? "Em espera" : "Sem sinal";
            }
            out.add(in);
        }
        if (tuner != null) out.add(tuner);
        return out;
    }

    private static int rank(TvInputInfo i) {
        if (i.getType() == TvInputInfo.TYPE_HDMI) return 0;
        if (i.isPassthroughInput()) return 1;
        return 2;
    }

    static Intent inputIntent(Input in) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(in.id);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel");
        if (in.tuner) i.setPackage(LIVE_TV_PKG);
        return i;
    }

    /** A change in the apps on screen: the same list gives the same text. */
    static String signature(List<App> apps) {
        StringBuilder sig = new StringBuilder();
        for (App a : apps) sig.append(a.pkg).append('|').append(a.label).append(';');
        return sig.toString();
    }

    /** TCL's quick panel (picture, sound, network), or null on a TV without it. */
    static Intent tclSettings(PackageManager pm) {
        Intent tcl = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("com.tcl.settings", "com.tcl.settings.MainActivity"));
        return pm.resolveActivity(tcl, 0) != null ? tcl : null;
    }

    /**
     * Two streaming apps do not fit in 943 MB together: the one being opened gets the memory the idle ones
     * hold. The one in use is never touched, so going Home and back to it stays instant. Music apps keep
     * playing (they are not in STREAMING, and foreground services are not background processes anyway).
     * Blocks briefly: call it from a worker thread.
     */
    static void closeOtherStreaming(Context c, String keep) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        for (String p : STREAMING) if (!p.equals(keep)) am.killBackgroundProcesses(p);
    }

    static void open(Context c, Intent i, Bundle options) throws ActivityNotFoundException {
        c.startActivity(new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options);
    }
}
