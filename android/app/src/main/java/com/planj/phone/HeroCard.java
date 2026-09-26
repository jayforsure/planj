package com.planj.phone;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A rounded image card with a headline over it, and optionally a pill button with a round
 * arrow. The image comes from an {@link Illustration} slot, so artwork drops in later.
 */
public final class HeroCard extends FrameLayout {
    private final TextView headline, subline;
    private final Button button;
    private final View arrow;

    public HeroCard(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float dp = getResources().getDisplayMetrics().density;
        TypedArray a = ctx.obtainStyledAttributes(attrs, R.styleable.HeroCard);
        String slot = a.getString(R.styleable.HeroCard_heroSlot);
        CharSequence head = a.getText(R.styleable.HeroCard_heroHeadline);
        CharSequence sub = a.getText(R.styleable.HeroCard_heroSubline);
        CharSequence btn = a.getText(R.styleable.HeroCard_heroButton);
        int height = a.getDimensionPixelSize(R.styleable.HeroCard_heroHeight, (int) (300 * dp));
        a.recycle();

        setMinimumHeight(height);
        setBackgroundResource(R.drawable.hero_bg);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 24 * dp);
            }
        });

        Illustration img = new Illustration(ctx, slot == null ? "" : slot, "");
        img.setLabelAtTop(true);
        img.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(img);

        View scrim = new View(ctx);
        scrim.setBackgroundResource(R.drawable.hero_scrim);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (22 * dp);
        col.setPadding(pad, pad, pad, pad);
        LayoutParams cp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        addView(col, cp);

        headline = new TextView(ctx);
        headline.setTypeface(getResources().getFont(R.font.display));
        headline.setAllCaps(true);
        headline.setTextColor(ctx.getColor(R.color.text));
        headline.setTextSize(34);
        headline.setLineSpacing(0, 0.92f);
        headline.setIncludeFontPadding(false);
        headline.setText(head);
        headline.setShadowLayer(12 * dp, 0, 2 * dp, 0x80000000);
        col.addView(headline);

        subline = new TextView(ctx);
        subline.setTextColor(ctx.getColor(R.color.text));
        subline.setAlpha(0.85f);
        subline.setTextSize(14);
        subline.setText(sub);
        subline.setVisibility(sub == null || sub.length() == 0 ? GONE : VISIBLE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = (int) (6 * dp);
        col.addView(subline, sp);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = (int) (16 * dp);
        col.addView(row, rp);

        button = new Button(ctx, null, android.R.attr.borderlessButtonStyle);
        button.setText(btn);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        button.setTextColor(ctx.getColor(R.color.text));
        button.setBackgroundResource(R.drawable.btn_secondary);
        button.setPadding((int) (20 * dp), 0, (int) (20 * dp), 0);
        button.setMinWidth(0);
        button.setClickable(false); // the card is the target
        row.addView(button, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (48 * dp)));

        ImageView arr = new ImageView(ctx);
        arr.setImageResource(R.drawable.ic_arrow);
        arr.setBackgroundResource(R.drawable.btn_circle_accent);
        int ap = (int) (13 * dp);
        arr.setPadding(ap, ap, ap, ap);
        arr.setImageTintList(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.on_accent)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams((int) (48 * dp), (int) (48 * dp));
        alp.setMarginStart((int) (8 * dp));
        row.addView(arr, alp);
        arrow = row;
        arrow.setVisibility(btn == null || btn.length() == 0 ? GONE : VISIBLE);

        setForeground(ctx.getDrawable(R.drawable.btn_text));
        setClickable(true);
    }

    public void setHeadline(CharSequence t) {
        headline.setText(t);
    }

    public void setSubline(CharSequence t) {
        subline.setText(t);
        subline.setVisibility(t == null || t.length() == 0 ? GONE : VISIBLE);
    }

    public void setButton(CharSequence t) {
        button.setText(t);
        arrow.setVisibility(t == null || t.length() == 0 ? GONE : VISIBLE);
    }
}
