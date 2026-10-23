package com.tvclean.home;

import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/**
 * Screensaver: what is trending on each service, one picture every 20 s with a slow zoom and a cross-fade,
 * under a large clock, the date and the weather. Two decoded pictures at most; nothing runs once it ends.
 */
public class Dream extends DreamService {
    private static final long SLIDE = 20_000;
    private static final int INK = 0xFFF5F2EC, SOFT = 0xFFD6D2CA;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Images images;
    private Trending trending;
    private Weather weather;
    private List<Trending.Item> slides = Collections.emptyList();
    private int index;
    private ImageView front, back;   // the incoming picture always fades in on `front`, which stays on top
    private TextView caption, weatherText;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV up
        setFullscreen(true);
        ui = new Ui(this);
        images = new Images(this);
        trending = new Trending(this);
        weather = new Weather(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D12);
        back = picture();
        front = picture();
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        root.addView(front, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[]{0xE60B0D12, 0x660B0D12, 0x000B0D12}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextClock clock = ui.clock("HH:mm", 200, 800, INK);
        clock.setLetterSpacing(-0.03f);
        info.addView(clock);
        info.addView(ui.clock("EEEE, d 'de' MMMM", 36, 500, SOFT));
        weatherText = ui.text("", 30, 600, SOFT);
        weatherText.setPadding(0, ui.px(10), 0, 0);
        info.addView(weatherText);
        caption = ui.text("", 24, 500, SOFT);
        caption.setPadding(0, ui.px(40), 0, 0);
        caption.setAlpha(0.8f);
        info.addView(caption);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        ilp.setMargins(ui.px(110), 0, 0, ui.px(96));
        root.addView(info, ilp);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        String wx = weather.label();
        weatherText.setText(wx == null ? "" : wx);
        // The pictures are on disk already (Home keeps them); reading them takes milliseconds.
        new Thread(() -> {
            trending.refresh();
            List<Trending.Item> all = trending.all();
            Collections.shuffle(all);
            main.post(() -> {
                slides = all;
                next.run();
            });
        }, "tvclean-dream").start();
    }

    @Override
    public void onDreamingStopped() {
        main.removeCallbacksAndMessages(null);
        front.animate().cancel();
        back.animate().cancel();
        images.clear();
        super.onDreamingStopped();
    }

    private final Runnable next = new Runnable() {
        @Override public void run() {
            if (slides.isEmpty()) return;
            Trending.Item it = slides.get(index++ % slides.size());
            // The old picture moves to the lower view, frozen where its zoom got to; the new one fades in on top.
            front.animate().cancel();
            back.setImageDrawable(front.getDrawable());
            back.setScaleX(front.getScaleX());
            back.setScaleY(front.getScaleY());
            front.setAlpha(0f);
            front.setScaleX(1f);
            front.setScaleY(1f);
            images.load(Uri.fromFile(it.image), front, ui.px(1920), ui.px(1080), () -> {
                front.animate().alpha(1f).setDuration(1500).start();
                front.animate().scaleX(1.08f).scaleY(1.08f).setDuration(SLIDE + 2000).start();
            });
            caption.setText(it.source != null ? "Em alta no " + it.source + "  ·  " + it.title : it.title);
            main.postDelayed(this, SLIDE);
        }
    };

    private ImageView picture() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setCropToPadding(true);
        return v;
    }
}
