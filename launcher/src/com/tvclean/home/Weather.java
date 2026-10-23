package com.tvclean.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * Temperature and sky for the header. Where the TV is comes from its internet address (ipwho.is, once a
 * day); the weather from Open-Meteo (free, no key), at most once an hour. Both are kept in preferences,
 * so the header shows the last known weather straight away and when offline.
 */
final class Weather {
    private static final long HOUR = 60 * 60 * 1000L, DAY = 24 * HOUR;
    private final SharedPreferences prefs;
    private final String city;   // fixed at build time (tv/city.txt); empty means "locate by IP"

    Weather(Context c) {
        prefs = c.getSharedPreferences("tvclean", Context.MODE_PRIVATE);
        Properties p = new Properties();
        try (InputStream in = c.getAssets().open("keys.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // No config: locate by IP.
        }
        city = p.getProperty("city", "").trim();
        if (!city.equals(prefs.getString("wx_city_query", ""))) {
            // A different city than last time: look it up and fetch the weather on the next refresh.
            prefs.edit().putString("wx_city_query", city).remove("wx_lat").putLong("wx_checked", 0).apply();
        }
    }

    /** "22° <city>", or null before the first successful fetch. */
    String label() {
        if (!prefs.contains("wx_temp")) return null;
        String city = prefs.getString("wx_city", "");
        int t = Math.round(prefs.getFloat("wx_temp", 0));
        return city.isEmpty() ? t + "°" : t + "° " + city;
    }

    /** One of StrokeIcon's SUN, CLOUD, RAIN, STORM. */
    int icon() {
        int code = prefs.getInt("wx_code", 0);
        if (code >= 95) return StrokeIcon.STORM;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return StrokeIcon.RAIN;
        if (code <= 1) return StrokeIcon.SUN;
        return StrokeIcon.CLOUD;
    }

    /** Runs on a worker thread. Returns true when there is something new to show. */
    boolean refresh() {
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("wx_checked", 0) < HOUR) return false;
        prefs.edit().putLong("wx_checked", now).apply();
        try {
            if (!city.isEmpty() && !prefs.contains("wx_lat")) {
                JSONObject found = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=pt&format=json&name="
                        + URLEncoder.encode(city, "UTF-8"));
                JSONArray results = found.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject r = results.getJSONObject(0);
                    locate(r.optString("name", city), r, now);
                }
            } else if (city.isEmpty() && (now - prefs.getLong("wx_located", 0) > DAY || !prefs.contains("wx_lat"))) {
                JSONObject where = get("https://ipwho.is/?fields=success,city,latitude,longitude");
                if (where.optBoolean("success")) locate(where.optString("city"), where, now);
            }
            if (!prefs.contains("wx_lat")) return false;
            JSONObject current = get(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code&timezone=auto",
                    prefs.getFloat("wx_lat", 0), prefs.getFloat("wx_lon", 0))).getJSONObject("current");
            prefs.edit()
                    .putFloat("wx_temp", (float) current.getDouble("temperature_2m"))
                    .putInt("wx_code", current.optInt("weather_code"))
                    .apply();
            return true;
        } catch (Exception e) {
            return false;   // offline: the header keeps the last reading
        }
    }

    private void locate(String name, JSONObject at, long now) {
        prefs.edit()
                .putString("wx_city", name)
                .putFloat("wx_lat", (float) at.optDouble("latitude"))
                .putFloat("wx_lon", (float) at.optDouble("longitude"))
                .putLong("wx_located", now)
                .apply();
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        try {
            return new JSONObject(new String(Net.readAll(c.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }
}
