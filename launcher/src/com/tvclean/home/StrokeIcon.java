package com.tvclean.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Line icons drawn in a 24x24 viewport, stroke style, so the APK carries no image assets. */
final class StrokeIcon extends Drawable {
    static final int INPUT = 0, SETTINGS = 1, ANTENNA = 2, WIFI = 3, WIFI_OFF = 4, SUN = 5, CLOUD = 6, RAIN = 7, STORM = 8;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int kind;

    StrokeIcon(int kind, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        this.kind = kind;
        build(kind);
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    void setKind(int k) {
        if (k == kind) return;
        kind = k;
        path.reset();
        build(k);
        invalidateSelf();
    }

    private void build(int kind) {
        switch (kind) {
            case INPUT:
                path.addRoundRect(new RectF(3, 5, 21, 16), 2, 2, Path.Direction.CW);
                path.moveTo(8, 20); path.lineTo(16, 20);
                path.moveTo(12, 16); path.lineTo(12, 20);
                break;
            case CLOUD:
            case RAIN:
            case STORM:
                // A cloud (as in Lucide's "cloud"), raised a little when something falls from it.
                float up = kind == CLOUD ? 0 : 3;
                path.moveTo(17.5f, 19 - up);
                path.lineTo(9, 19 - up);
                path.arcTo(new RectF(2, 5 - up, 16, 19 - up), 90, 253.4f);
                path.lineTo(17.5f, 10 - up);
                path.arcTo(new RectF(13, 10 - up, 22, 19 - up), -90, 180);
                path.close();
                if (kind == RAIN) {
                    for (float x : new float[]{8, 12, 16}) {
                        path.moveTo(x, 19);
                        path.lineTo(x - 1, 22);
                    }
                } else if (kind == STORM) {
                    path.moveTo(13, 17.5f);
                    path.lineTo(11, 20.5f);
                    path.lineTo(14, 20.5f);
                    path.lineTo(12, 23.5f);
                }
                break;
            case SUN:
                path.addCircle(12, 12, 4, Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float c = (float) Math.cos(a), s = (float) Math.sin(a);
                    path.moveTo(12 + 7 * c, 12 + 7 * s);
                    path.lineTo(12 + 9.5f * c, 12 + 9.5f * s);
                }
                break;
            case SETTINGS:
                gear(12, 12, 6.8f, 9.6f, 3.2f);
                break;
            case WIFI:
            case WIFI_OFF:
                path.addArc(new RectF(2, 6, 22, 26), 225, 90);
                path.addArc(new RectF(5.5f, 9.5f, 18.5f, 22.5f), 225, 90);
                path.addArc(new RectF(9, 13, 15, 19), 225, 90);
                path.addCircle(12, 18, 0.6f, Path.Direction.CW);
                if (kind == WIFI_OFF) {
                    path.moveTo(3, 3);
                    path.lineTo(21, 21);
                }
                break;
            case ANTENNA:
                path.addRoundRect(new RectF(3, 9, 21, 20), 2, 2, Path.Direction.CW);
                path.moveTo(8, 3); path.lineTo(12, 9); path.lineTo(16, 3);
                break;
        }
    }

    /** A cogwheel: eight teeth around a hub, so it cannot be mistaken for the sun (a circle with rays). */
    private void gear(float cx, float cy, float valley, float tip, float hub) {
        final int teeth = 8;
        final double step = 2 * Math.PI / teeth, base = Math.toRadians(13), top = Math.toRadians(8.5);
        for (int i = 0; i < teeth; i++) {
            double a = step * i - Math.PI / 2;
            double[][] pts = {{a - base, valley}, {a - top, tip}, {a + top, tip}, {a + base, valley}};
            for (int k = 0; k < pts.length; k++) {
                float x = cx + (float) (pts[k][1] * Math.cos(pts[k][0])), y = cy + (float) (pts[k][1] * Math.sin(pts[k][0]));
                if (i == 0 && k == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
        }
        path.close();
        path.addCircle(cx, cy, hub, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas canvas) {
        float s = Math.min(getBounds().width(), getBounds().height()) / 24f;
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(s, s);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
