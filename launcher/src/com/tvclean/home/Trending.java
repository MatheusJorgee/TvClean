package com.tvclean.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * "Em alta" per streaming app, from TMDB (themoviedb.org): this week's trending titles, matched to the
 * services that stream them in Brazil. Each service keeps its top 5 and shows one at a time, moving to
 * the next every 3 h. Only the backdrop on screen is kept on disk, so Home never waits on the network.
 */
final class Trending {
    static final class Item {
        String title;
        String overview;
        String kind;
        String source;   // the service's name, e.g. "Netflix"
        File image;
    }

    /** Everything currently shown, for the screensaver's slideshow. */
    synchronized List<Item> all() {
        return new ArrayList<>(items.values());
    }

    static final class Provider {
        final int id;
        final String name;
        final int color;

        Provider(int id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    private static final String YOUTUBE_PKG = "com.google.android.youtube.tv";
    private static final int YOUTUBE_ID = 1;   // not a TMDB provider: YouTube's own "most popular in Brazil"

    /** App package -> TMDB watch-provider id for region BR. */
    static final Map<String, Provider> PROVIDERS = new HashMap<>();
    /** The TMDB providers among them (everything but YouTube). */
    private static final Set<Integer> PROVIDER_IDS = new HashSet<>();

    static {
        provider("com.netflix.ninja", 8, "Netflix", 0xFFE50914);
        provider("com.amazon.amazonvideo.livingroom", 119, "Prime Video", 0xFF1A98FF);
        provider("com.disney.disneyplus", 337, "Disney+", 0xFF113CCF);
        provider("com.globo.globotv", 307, "Globoplay", 0xFFF9532D);
        provider("com.wbd.stream", 1899, "Max", 0xFF3B2BD9);
        provider("com.crunchyroll.crunchyroid", 283, "Crunchyroll", 0xFFF47521);
        provider("com.apple.atve.androidtv.appletv", 350, "Apple TV+", 0xFF333333);
        PROVIDERS.put(YOUTUBE_PKG, new Provider(YOUTUBE_ID, "YouTube", 0xFFFF0033));
    }

    private static void provider(String pkg, int id, String name, int color) {
        PROVIDERS.put(pkg, new Provider(id, name, color));
        PROVIDER_IDS.add(id);
    }

    private static final long PERIOD = 3 * 60 * 60 * 1000L;
    private static final int PER_SERVICE = 5;
    private static final String API = "https://api.themoviedb.org/3/";

    /** Bumped whenever what may be shown changes, so pictures chosen under older rules are dropped. */
    private static final String RULES = "no18-v1";

    private final File dir;
    private final SharedPreferences prefs;
    private final Map<String, Item> items = new HashMap<>();
    private boolean loaded;

    private final String bundledTmdb, bundledYoutube;

    /** SHA-1 of this app's signing certificate, as Google expects it in X-Android-Cert (hex, no colons). */
    private static String androidCert;

    private static synchronized void readCert(Context c) {
        if (androidCert != null) return;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            byte[] cert = pi.signingInfo.getApkContentsSigners()[0].toByteArray();
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(cert);
            StringBuilder hex = new StringBuilder();
            for (byte b : sha1) hex.append(String.format(Locale.US, "%02X", b));
            androidCert = hex.toString();
        } catch (Exception ignored) {
            // Without it the request still goes out; a key restricted to the app would then refuse it.
        }
    }

    Trending(Context c) {
        readCert(c);
        dir = new File(c.getFilesDir(), "trending");
        dir.mkdirs();
        prefs = c.getSharedPreferences("tvclean", Context.MODE_PRIVATE);
        // Keys come with the build (build.ps1 copies tv/*.key, which git ignores); new keys mean fetch now.
        Properties keys = new Properties();
        try (InputStream in = c.getAssets().open("keys.properties")) {
            keys.load(in);
        } catch (Exception ignored) {
            // A build without keys: trending stays off until a key is set.
        }
        bundledTmdb = keys.getProperty("tmdb", "").trim();
        bundledYoutube = keys.getProperty("youtube", "").trim();
        // New keys or new content rules: forget every picture on disk and fetch again under the new rules.
        String stamp = bundledTmdb.hashCode() + ":" + bundledYoutube.hashCode() + ":" + RULES;
        if (!stamp.equals(prefs.getString("keys_stamp", ""))) {
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            prefs.edit().putString("keys_stamp", stamp).putLong("tmdb_checked", 0).apply();
        }
    }

    // ---------------------------------------------------------------- nothing for adults, ever

    private final Map<String, Boolean> rated = new HashMap<>();

    /**
     * Only titles with a known rating below 18 may be shown. Brazil's rating (ClassInd) decides; without
     * it, the US one (R, NC-17 and TV-MA count as adult); without any rating, the title is left out.
     */
    private boolean allowed(String key, JSONObject t) {
        if (t.optBoolean("adult")) return false;
        String type = t.optString("media_type");
        String id = type + "/" + t.optLong("id");
        Boolean cached = rated.get(id);
        if (cached != null) return cached;
        boolean ok = false;
        try {
            String br = null, us = null;
            if ("tv".equals(type)) {
                JSONArray r = json(key, API + "tv/" + t.optLong("id") + "/content_ratings").optJSONArray("results");
                for (int i = 0; r != null && i < r.length(); i++) {
                    JSONObject o = r.getJSONObject(i);
                    if ("BR".equals(o.optString("iso_3166_1"))) br = o.optString("rating");
                    if ("US".equals(o.optString("iso_3166_1"))) us = o.optString("rating");
                }
            } else if ("movie".equals(type)) {
                JSONArray r = json(key, API + "movie/" + t.optLong("id") + "/release_dates").optJSONArray("results");
                for (int i = 0; r != null && i < r.length(); i++) {
                    JSONObject o = r.getJSONObject(i);
                    String c = firstCertification(o.optJSONArray("release_dates"));
                    if ("BR".equals(o.optString("iso_3166_1"))) br = c;
                    if ("US".equals(o.optString("iso_3166_1"))) us = c;
                }
            }
            if (br != null && !br.trim().isEmpty()) {
                ok = !br.trim().startsWith("18");
            } else if (us != null && !us.trim().isEmpty()) {
                String u = us.trim().toUpperCase(Locale.US);
                ok = !(u.equals("R") || u.equals("NC-17") || u.equals("TV-MA") || u.equals("X") || u.equals("NR"));
            }
        } catch (Exception e) {
            ok = false;   // could not check: not shown
        }
        rated.put(id, ok);
        return ok;
    }

    private static String firstCertification(JSONArray dates) {
        for (int i = 0; dates != null && i < dates.length(); i++) {
            String c = dates.optJSONObject(i) == null ? "" : dates.optJSONObject(i).optString("certification");
            if (!c.trim().isEmpty()) return c;
        }
        return null;
    }

    /** A service with nothing that may be shown loses its picture (Home falls back to the app's color). */
    private void forget(String pkg, Provider p) {
        new File(dir, p.id + ".json").delete();
        new File(dir, p.id + ".jpg").delete();
        synchronized (this) {
            items.remove(pkg);
        }
    }

    void setKey(String key) {
        prefs.edit().putString("tmdb_key", key.trim()).putLong("tmdb_checked", 0).apply();
    }

    private String tmdbKey() {
        String k = prefs.getString("tmdb_key", "");
        return k.isEmpty() ? bundledTmdb : k;
    }

    boolean hasKey() {
        return !tmdbKey().isEmpty();
    }

    synchronized Item get(String pkg) {
        return items.get(pkg);
    }

    /** The pictures already on disk, without touching the network (cheap: a few small JSON files). */
    synchronized void loadCached() {
        if (!loaded) {
            loadShown();
            loaded = true;
        }
    }

    /** Shows what is on disk and, every 3 h, fetches the lists again and moves each service to its next title. */
    void refresh() {
        loadCached();
        String key = tmdbKey();
        long now = System.currentTimeMillis();
        if ((key.isEmpty() && bundledYoutube.isEmpty()) || now - prefs.getLong("tmdb_checked", 0) < PERIOD) return;
        prefs.edit().putLong("tmdb_checked", now).apply();
        int slot = (int) (now / PERIOD);
        if (!bundledYoutube.isEmpty()) {
            try {
                Item it = youtube(bundledYoutube, slot);
                if (it != null) {
                    synchronized (this) {
                        items.put(YOUTUBE_PKG, it);
                    }
                } else {
                    forget(YOUTUBE_PKG, PROVIDERS.get(YOUTUBE_PKG));
                }
            } catch (Exception ignored) {
                // Key blocked or offline: YouTube keeps its previous picture.
            }
        }
        if (key.isEmpty()) return;
        Map<Integer, List<JSONObject>> lists;
        try {
            lists = trendingByProvider(key);
        } catch (Exception e) {
            return;   // offline or rate-limited: keep showing what is on disk
        }
        // One title per service and never the same title on two services (a hit is often on several).
        Set<Long> used = new HashSet<>();
        for (Map.Entry<String, Provider> e : PROVIDERS.entrySet()) {
            Provider p = e.getValue();
            if (p.id == YOUTUBE_ID) continue;
            try {
                List<JSONObject> list = lists.get(p.id);
                if (list == null || list.isEmpty()) list = recentPopular(key, p);
                JSONObject pick = null;
                for (int k = 0; k < list.size() && pick == null; k++) {
                    JSONObject c = list.get((slot + k) % list.size());
                    if (allowed(key, c) && used.add(c.optLong("id"))) pick = c;
                }
                if (pick == null) {
                    forget(e.getKey(), p);
                    continue;
                }
                Item it = show(p, pick);
                synchronized (this) {
                    items.put(e.getKey(), it);
                }
            } catch (Exception ignored) {
                // This service keeps its previous title.
            }
        }
    }

    /** YouTube's most popular videos in Brazil (Data API v3, 1 quota unit per call); one per 3 h slot. */
    private Item youtube(String key, int slot) throws Exception {
        JSONArray videos = new JSONObject(new String(get(null, "https://www.googleapis.com/youtube/v3/videos"
                + "?part=snippet,contentDetails&chart=mostPopular&regionCode=BR&hl=pt-BR&maxResults=15"
                + "&key=" + key), StandardCharsets.UTF_8)).optJSONArray("items");
        if (videos == null || videos.length() == 0) return null;
        // Age-restricted videos and titles that announce themselves as adult are never shown.
        List<JSONObject> ok = new ArrayList<>();
        for (int i = 0; i < videos.length(); i++) {
            JSONObject v = videos.getJSONObject(i);
            JSONObject rating = v.optJSONObject("contentDetails") == null ? null
                    : v.getJSONObject("contentDetails").optJSONObject("contentRating");
            if (rating != null && "ytAgeRestricted".equals(rating.optString("ytRating"))) continue;
            String title = v.getJSONObject("snippet").optString("title").toLowerCase(Locale.ROOT);
            if (title.matches(".*(\\+ ?18|18 ?\\+|\\bnsfw\\b|\\bconte[uú]do adulto\\b).*")) continue;
            ok.add(v);
            if (ok.size() >= PER_SERVICE) break;
        }
        if (ok.isEmpty()) return null;
        JSONObject sn = ok.get(slot % ok.size()).getJSONObject("snippet");
        JSONObject th = sn.getJSONObject("thumbnails");
        JSONObject best = th.optJSONObject("maxres");
        if (best == null) best = th.optJSONObject("standard");
        if (best == null) best = th.optJSONObject("high");
        if (best == null) return null;
        return save(PROVIDERS.get(YOUTUBE_PKG), sn.optString("title"), sn.optString("channelTitle"), "Vídeo", best.getString("url"));
    }

    private void loadShown() {
        for (Map.Entry<String, Provider> e : PROVIDERS.entrySet()) {
            try {
                File json = new File(dir, e.getValue().id + ".json");
                File img = new File(dir, e.getValue().id + ".jpg");
                if (!json.exists() || !img.exists()) continue;
                JSONObject o = new JSONObject(new String(Net.readAll(new FileInputStream(json)), StandardCharsets.UTF_8));
                Item it = new Item();
                it.title = o.optString("title");
                it.overview = o.optString("overview");
                it.kind = o.optString("kind");
                it.source = e.getValue().name;
                it.image = img;
                synchronized (this) {
                    items.put(e.getKey(), it);
                }
            } catch (Exception ignored) {
                // A broken cache file just means no picture for that service until the next refresh.
            }
        }
    }

    /** This week's trending movies and series (3 pages), grouped by the BR subscription services that carry them. */
    private Map<Integer, List<JSONObject>> trendingByProvider(String key) throws Exception {
        Map<Integer, List<JSONObject>> out = new HashMap<>();
        for (int page = 1; page <= 3; page++) {
            JSONArray results = json(key, API + "trending/all/week?language=pt-BR&page=" + page).optJSONArray("results");
            if (results == null) break;
            for (int i = 0; i < results.length(); i++) {
                JSONObject t = results.getJSONObject(i);
                String type = t.optString("media_type");
                if (!("tv".equals(type) || "movie".equals(type)) || t.optString("backdrop_path").isEmpty()) continue;
                JSONObject br = json(key, API + type + "/" + t.getLong("id") + "/watch/providers")
                        .optJSONObject("results");
                JSONArray flat = br == null || br.optJSONObject("BR") == null ? null
                        : br.getJSONObject("BR").optJSONArray("flatrate");
                if (flat == null) continue;
                Boolean ok = null;   // the rating is looked up only for titles on one of our services
                for (int f = 0; f < flat.length(); f++) {
                    int id = flat.getJSONObject(f).optInt("provider_id");
                    if (!PROVIDER_IDS.contains(id)) continue;
                    if (ok == null) ok = allowed(key, t);
                    if (!ok) break;
                    List<JSONObject> l = out.get(id);
                    if (l == null) out.put(id, l = new ArrayList<>());
                    if (l.size() < PER_SERVICE) l.add(t);
                }
            }
        }
        return out;
    }

    /** Fallback for services with nothing trending worldwide (Globoplay): its most popular releases of the last year. */
    private List<JSONObject> recentPopular(String key, Provider p) throws Exception {
        String since = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(System.currentTimeMillis() - 365L * 86400000L));
        List<JSONObject> out = new ArrayList<>();
        for (String type : new String[]{"tv", "movie"}) {
            String date = "tv".equals(type) ? "first_air_date.gte=" : "primary_release_date.gte=";
            JSONArray r = json(key, API + "discover/" + type + "?watch_region=BR&with_watch_providers=" + p.id
                    + "&sort_by=popularity.desc&language=pt-BR&include_adult=false&" + date + since).optJSONArray("results");
            if (r == null) continue;
            for (int i = 0; i < r.length() && out.size() < PER_SERVICE * 2; i++) {
                JSONObject o = r.getJSONObject(i);
                if (o.optString("backdrop_path").isEmpty()) continue;
                o.put("media_type", type);
                if (!allowed(key, o)) continue;
                out.add(o);
            }
        }
        out.sort((a, b) -> Double.compare(b.optDouble("popularity"), a.optDouble("popularity")));
        return out.size() > PER_SERVICE ? out.subList(0, PER_SERVICE) : out;
    }

    /** Downloads the chosen title's backdrop (replacing the previous one) and records it as shown. */
    private Item show(Provider p, JSONObject t) throws Exception {
        boolean tv = "tv".equals(t.optString("media_type"));
        return save(p, tv ? t.optString("name") : t.optString("title"), t.optString("overview"),
                tv ? "Série" : "Filme", "https://image.tmdb.org/t/p/w1280" + t.getString("backdrop_path"));
    }

    private Item save(Provider p, String title, String overview, String kind, String imageUrl) throws Exception {
        byte[] image = get(null, imageUrl);
        File img = new File(dir, p.id + ".jpg");
        try (FileOutputStream out = new FileOutputStream(img)) {
            out.write(image);
        }
        Item it = new Item();
        it.title = title;
        it.overview = overview;
        it.kind = kind;
        it.source = p.name;
        it.image = img;
        JSONObject save = new JSONObject();
        save.put("title", it.title);
        save.put("overview", it.overview);
        save.put("kind", it.kind);
        try (FileOutputStream out = new FileOutputStream(new File(dir, p.id + ".json"))) {
            out.write(save.toString().getBytes(StandardCharsets.UTF_8));
        }
        return it;
    }

    private static JSONObject json(String key, String url) throws Exception {
        return new JSONObject(new String(get(key, url), StandardCharsets.UTF_8));
    }

    /** TMDB takes either a v3 API key (query parameter) or a v4 read token (Bearer header). */
    private static byte[] get(String key, String url) throws Exception {
        boolean bearer = key != null && key.length() > 40;
        if (key != null && !bearer) url += (url.contains("?") ? "&" : "?") + "api_key=" + key;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        if (url.startsWith("https://www.googleapis.com/") && androidCert != null) {
            // Lets the YouTube key be restricted to this app (Google Cloud: "Android apps" restriction).
            c.setRequestProperty("X-Android-Package", "com.tvclean.home");
            c.setRequestProperty("X-Android-Cert", androidCert);
        }
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            return Net.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }
}
