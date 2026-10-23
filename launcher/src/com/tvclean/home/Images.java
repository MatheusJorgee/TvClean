package com.tvclean.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Poster loading for a 1 GB TV: one worker thread, decoded at display size, small memory cache. */
final class Images {
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        // Decoding never competes with the app the person just opened.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "tvclean-images"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {   // two 1280x720 backdrops
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    Images(Context c) {
        context = c.getApplicationContext();
    }

    void load(Uri uri, ImageView into, int width, int height) {
        load(uri, into, width, height, null);
    }

    /** Sets the image when decoded (or at once from the cache); `shown` runs after it is on the view. */
    void load(Uri uri, ImageView into, int width, int height, Runnable shown) {
        String key = uri + "@" + width + "@" + new File(String.valueOf(uri.getPath())).lastModified();
        if (key.equals(into.getTag()) && into.getDrawable() != null) return;   // already showing it: no fade again
        into.setTag(key);
        Bitmap hit = cache.get(key);
        if (hit != null) {
            into.setImageBitmap(hit);
            if (shown != null) shown.run();
            return;
        }
        worker.execute(() -> {
            if (!key.equals(into.getTag())) return;   // the user already moved on
            Bitmap b = decode(uri, width, height);
            if (b == null) return;
            b.prepareToDraw();   // upload to the GPU now, not during the fade-in
            cache.put(key, b);
            main.post(() -> {
                if (!key.equals(into.getTag())) return;
                into.setImageBitmap(b);
                if (shown != null) shown.run();
            });
        });
    }

    void clear() {
        cache.evictAll();
    }

    private Bitmap decode(Uri uri, int width, int height) {
        try {
            byte[] data = read(uri);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private byte[] read(Uri uri) throws Exception {
        String scheme = uri.getScheme();
        InputStream in;
        HttpURLConnection http = null;
        if ("http".equals(scheme) || "https".equals(scheme)) {
            http = (HttpURLConnection) new URL(uri.toString()).openConnection();
            http.setConnectTimeout(5000);
            http.setReadTimeout(8000);
            in = http.getInputStream();
        } else {
            in = context.getContentResolver().openInputStream(uri);
        }
        if (in == null) return null;
        try {
            return Net.readAll(in, 4 * 1024 * 1024);
        } finally {
            if (http != null) http.disconnect();
        }
    }
}
