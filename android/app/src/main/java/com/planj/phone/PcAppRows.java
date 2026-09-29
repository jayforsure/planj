package com.planj.phone;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Rows for PC apps and web services: icon, name, time and category, a bar in its colour. */
final class PcAppRows {
    private PcAppRows() {}

    static void fill(Activity a, LinearLayout list, List<PcDays.App> apps, int limit) {
        list.removeAllViews();
        if (apps == null || apps.isEmpty()) return;
        float dp = a.getResources().getDisplayMetrics().density;
        int max = apps.get(0).minutes;
        for (int i = 0; i < Math.min(limit, apps.size()); i++) list.addView(row(a, apps.get(i), max, dp));
    }

    private static View row(Activity a, PcDays.App x, int max, float dp) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(10 * dp), 0, Math.round(10 * dp));
        row.addView(icon(a, x, dp), size(36, dp, 16));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = new TextView(a);
        name.setText(x.name);
        name.setTextColor(a.getColor(R.color.text));
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView time = new TextView(a);
        time.setText(Fmt.shortDuration(x.minutes * 60_000L) + " · " + label(x.cat));
        time.setTextColor(a.getColor(R.color.muted));
        time.setTextSize(13);
        top.addView(time);
        col.addView(top);

        FrameLayout track = new FrameLayout(a);
        track.setBackground(rounded(a.getColor(R.color.surface_alt), 3 * dp));
        View fill = new View(a);
        fill.setBackground(rounded(a.getColor(DayTimelineView.color(x.cat)), 3 * dp));
        track.addView(fill, new FrameLayout.LayoutParams(0, Math.round(4 * dp)));
        final float share = (float) x.minutes / Math.max(1, max);
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = Math.max(Math.round(4 * dp), Math.round((r - l) * share));
            if (fill.getLayoutParams().width != w) {
                fill.getLayoutParams().width = w;
                fill.requestLayout();
            }
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(4 * dp));
        tp.topMargin = Math.round(8 * dp);
        col.addView(track, tp);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    /** The service's own icon when we have or can fetch it; a globe for other websites; else its initial. */
    private static View icon(Activity a, PcDays.App x, float dp) {
        FrameLayout box = new FrameLayout(a);
        TextView letter = new TextView(a);
        letter.setText(x.name.substring(0, 1).toUpperCase(Locale.ENGLISH));
        letter.setGravity(Gravity.CENTER);
        letter.setTextColor(a.getColor(R.color.bg));
        letter.setTextSize(15);
        letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        letter.setBackground(rounded(a.getColor(DayTimelineView.color(x.cat)), 10 * dp));
        box.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setClipToOutline(true);
        img.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 10 * dp);
            }
        });
        box.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        img.setVisibility(View.GONE);

        if ("Other websites".equals(x.name)) {
            letter.setVisibility(View.GONE);
            img.setVisibility(View.VISIBLE);
            img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            img.setBackground(rounded(a.getColor(R.color.surface_alt), 10 * dp));
            img.setImageResource(R.drawable.ic_globe);
            img.setImageTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.muted)));
            int p = Math.round(8 * dp);
            img.setPadding(p, p, p, p);
            return box;
        }
        Bitmap cached = PcIcons.cached(a, x.name);
        if (cached != null) {
            show(img, letter, cached, a, dp);
        } else if (PcIcons.known(x.name)) {
            new Thread(() -> {
                Bitmap b = PcIcons.fetch(a, x.name);
                if (b != null) a.runOnUiThread(() -> show(img, letter, b, a, dp));
            }).start();
        }
        return box;
    }

    private static void show(ImageView img, TextView letter, Bitmap b, Activity a, float dp) {
        img.setImageBitmap(b);
        img.setBackground(rounded(0xFFFFFFFF, 10 * dp)); // icons with transparency sit on white, like a home screen
        img.setVisibility(View.VISIBLE);
        letter.setVisibility(View.GONE);
    }

    static String label(String cat) {
        switch (cat) {
            case "focus": return "focus";
            case "entertainment": return "watching";
            case "social": return "social";
            case "chat": return "chat";
            default: return "other";
        }
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(color);
        return g;
    }

    private static LinearLayout.LayoutParams size(int dpSize, float dp, int marginEnd) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(dpSize * dp), Math.round(dpSize * dp));
        lp.setMarginEnd(Math.round(marginEnd * dp));
        return lp;
    }
}
