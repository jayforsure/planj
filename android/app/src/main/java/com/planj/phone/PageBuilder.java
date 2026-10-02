package com.planj.phone;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Builds a page from planj's parts, top to bottom: icon, title, words, sections, rows, buttons. */
final class PageBuilder {
    private final Activity a;
    final LinearLayout stage;

    PageBuilder(Activity a, LinearLayout stage) {
        this.a = a;
        this.stage = stage;
    }

    void clear() {
        stage.removeAllViews();
        stage.startAnimation(android.view.animation.AnimationUtils.loadAnimation(a, R.anim.fade_up));
    }

    /** A big rounded-square icon: an app's own picture, or a glyph in the outline. */
    void icon(Drawable picture, int glyph) {
        ImageView iv = new ImageView(a);
        if (picture != null) {
            iv.setImageDrawable(picture);
            iv.setClipToOutline(true);
            float d = a.getResources().getDisplayMetrics().density;
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * d);
                }
            });
        } else {
            iv.setImageResource(glyph);
            iv.setBackgroundResource(R.drawable.icon_circle);
            iv.setPadding(dp(15), dp(15), dp(15), dp(15));
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.text)));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.bottomMargin = dp(16);
        lp.topMargin = dp(8);
        stage.addView(iv, lp);
    }

    void title(String s) {
        TextView t = new TextView(a, null, 0, R.style.Auth_Title);
        t.setText(s);
        stage.addView(t, wide());
    }

    TextView blurb(String s) {
        TextView t = new TextView(a, null, 0, R.style.Body);
        t.setText(s);
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(10);
        stage.addView(t, lp);
        return t;
    }

    void section(String s) {
        TextView t = new TextView(a, null, 0, R.style.Auth_Section);
        t.setText(s);
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(4);
        stage.addView(t, lp);
    }

    /** A row that only says something. */
    ListRow row(int icon, String title, String subtitle) {
        ListRow r = new ListRow(a);
        r.setIcon(icon);
        r.setTitle(title);
        r.setSubtitle(subtitle);
        r.setSubtitleLines(2);
        r.setChevron(false);
        r.setClickable(false);
        r.setBackground(null);
        stage.addView(r);
        return r;
    }

    /** A row that opens something. */
    ListRow link(int icon, String title, String subtitle, Runnable onClick) {
        ListRow r = row(icon, title, subtitle);
        r.setChevron(true);
        r.setClickable(true);
        r.setBackgroundResource(R.drawable.btn_text);
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    TextView note(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(a.getColor(R.color.muted));
        t.setTextSize(13);
        t.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(12);
        stage.addView(t, lp);
        return t;
    }

    Button primary(String label, Runnable onClick) {
        return button(R.style.Pill_Primary, label, onClick, 28);
    }

    Button secondary(String label, Runnable onClick) {
        return button(R.style.Pill_Secondary, label, onClick, 12);
    }

    private Button button(int style, String label, Runnable onClick, int top) {
        Button b = new Button(a, null, 0, style);
        b.setText(label);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(top);
        stage.addView(b, lp);
        return b;
    }

    private static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
