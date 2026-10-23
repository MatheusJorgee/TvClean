package com.tvclean.home;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Plus Jakarta Sans at each weight, built once per process and shared by Home and the screensaver. */
final class Fonts {
    private static final SparseArray<Typeface> CACHE = new SparseArray<>();

    private Fonts() {
    }

    static synchronized Typeface get(Context c, int weight) {
        Typeface t = CACHE.get(weight);
        if (t != null) return t;
        try {
            // At the assets root: aapt2 on Windows stores subfolder paths with a backslash.
            t = new Typeface.Builder(c.getApplicationContext().getAssets(), "PlusJakartaSans.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (Exception e) {
            t = null;
        }
        if (t == null) t = Typeface.create(Typeface.SANS_SERIF, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        CACHE.put(weight, t);
        return t;
    }
}
