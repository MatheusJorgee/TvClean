package com.tvclean.home;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * The drawing helpers Home, the screensaver and the key service share. Sizes are written on a 1920x1080
 * grid and scaled to the screen (this TCL draws at 1280x720).
 */
final class Ui {
    private final Context context;
    private final float scale;

    Ui(Context c) {
        context = c;
        scale = c.getResources().getDisplayMetrics().widthPixels / 1920f;
    }

    int px(float design) {
        return Math.round(design * scale);
    }

    TextView text(String s, int size, int weight, int color) {
        TextView t = new TextView(context);
        t.setText(s);
        style(t, size, weight, color);
        return t;
    }

    void style(TextView t, int size, int weight, int color) {
        t.setTypeface(Fonts.get(context, weight));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
    }

    /** A 24-hour clock ("HH:mm"), or any other TextClock format. */
    TextClock clock(String format, int size, int weight, int color) {
        TextClock c = new TextClock(context);
        c.setFormat24Hour(format);
        c.setFormat12Hour(format);
        style(c, size, weight, color);
        return c;
    }

    GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radius));
        return d;
    }

    /** The light outline a focused tile, pill or card wears. */
    GradientDrawable ring(int radius, int color, int width) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(radius));
        d.setStroke(px(width), color);
        return d;
    }

    ViewOutlineProvider rounded(int radius) {
        final float r = px(radius);
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        };
    }
}
