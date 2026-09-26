package com.planj.phone;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A rounded card: an image slot on top, then a headline, a line of context and a pill with
 * a round arrow. The words sit under the picture, so the artwork can be anything.
 */
public final class HeroCard extends LinearLayout {
    private final TextView headline, subline;
    private final Button button;
    private final View action;

    public HeroCard(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float dp = getResources().getDisplayMetrics().density;
        TypedArray a = ctx.obtainStyledAttributes(attrs, R.styleable.HeroCard);
        String slot = a.getString(R.styleable.HeroCard_heroSlot);
        CharSequence head = a.getText(R.styleable.HeroCard_heroHeadline);
        CharSequence sub = a.getText(R.styleable.HeroCard_heroSubline);
        CharSequence btn = a.getText(R.styleable.HeroCard_heroButton);
        int height = a.getDimensionPixelSize(R.styleable.HeroCard_heroHeight, (int) (200 * dp));
        a.recycle();

        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.card_bg);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 24 * dp);
            }
        });

        Illustration img = new Illustration(ctx, slot == null ? "" : slot, "");
        img.setSquareCorners(true);
        addView(img, new LayoutParams(LayoutParams.MATCH_PARENT, height));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        int pad = (int) (22 * dp);
        col.setPadding(pad, (int) (20 * dp), pad, pad);
        addView(col, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        headline = new TextView(ctx);
        headline.setTypeface(getResources().getFont(R.font.display));
        headline.setFontVariationSettings("'wght' 800, 'opsz' 96, 'wdth' 100");
        headline.setTextColor(ctx.getColor(R.color.text));
        headline.setTextSize(26);
        headline.setLetterSpacing(-0.03f);
        headline.setLineSpacing(0, 0.98f);
        headline.setIncludeFontPadding(false);
        headline.setText(head);
        col.addView(headline);

        subline = new TextView(ctx);
        subline.setTextColor(ctx.getColor(R.color.muted));
        subline.setTextSize(14);
        subline.setLineSpacing(3 * dp, 1);
        subline.setText(sub);
        subline.setVisibility(sub == null || sub.length() == 0 ? GONE : VISIBLE);
        LayoutParams sp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sp.topMargin = (int) (8 * dp);
        col.addView(subline, sp);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams rp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rp.topMargin = (int) (18 * dp);
        col.addView(row, rp);

        button = new Button(ctx, null, android.R.attr.borderlessButtonStyle);
        button.setText(btn);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        button.setTextColor(ctx.getColor(R.color.on_accent));
        button.setBackgroundResource(R.drawable.btn_primary);
        button.setPadding((int) (22 * dp), 0, (int) (22 * dp), 0);
        button.setMinWidth(0);
        button.setClickable(false); // the card is the target
        row.addView(button, new LayoutParams(LayoutParams.WRAP_CONTENT, (int) (46 * dp)));

        ImageView arr = new ImageView(ctx);
        arr.setImageResource(R.drawable.ic_arrow);
        arr.setBackgroundResource(R.drawable.btn_circle);
        int ap = (int) (12 * dp);
        arr.setPadding(ap, ap, ap, ap);
        arr.setImageTintList(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.text)));
        LayoutParams alp = new LayoutParams((int) (46 * dp), (int) (46 * dp));
        alp.setMarginStart((int) (8 * dp));
        row.addView(arr, alp);
        action = row;
        action.setVisibility(btn == null || btn.length() == 0 ? GONE : VISIBLE);

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
        action.setVisibility(t == null || t.length() == 0 ? GONE : VISIBLE);
    }
}
