package com.planj.phone;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A tappable row: outlined circle icon, title, optional subtitle, chevron. */
public final class ListRow extends LinearLayout {
    private final ImageView icon;
    private final TextView title, subtitle;
    private final ImageView chevron;

    public ListRow(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float dp = getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight((int) (72 * dp));
        setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
        setBackgroundResource(R.drawable.btn_text);
        setClickable(true);
        setFocusable(true);

        TypedArray a = ctx.obtainStyledAttributes(attrs, R.styleable.ListRow);
        int iconRes = a.getResourceId(R.styleable.ListRow_rowIcon, 0);
        CharSequence t = a.getText(R.styleable.ListRow_rowTitle);
        CharSequence s = a.getText(R.styleable.ListRow_rowSubtitle);
        boolean chev = a.getBoolean(R.styleable.ListRow_rowChevron, true);
        int tint = a.getColor(R.styleable.ListRow_rowTint, ctx.getColor(R.color.text));
        a.recycle();

        icon = new ImageView(ctx);
        int size = (int) (52 * dp), pad = (int) (14 * dp);
        LayoutParams ip = new LayoutParams(size, size);
        ip.setMarginEnd((int) (16 * dp));
        icon.setLayoutParams(ip);
        icon.setBackgroundResource(R.drawable.icon_circle);
        icon.setPadding(pad, pad, pad, pad);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        if (iconRes != 0) icon.setImageResource(iconRes);
        addView(icon);

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(VERTICAL);
        text.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        title = new TextView(ctx);
        title.setText(t);
        title.setTextColor(tint);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        text.addView(title);
        subtitle = new TextView(ctx);
        subtitle.setTextColor(ctx.getColor(R.color.muted));
        subtitle.setTextSize(13);
        subtitle.setText(s);
        subtitle.setVisibility(s == null || s.length() == 0 ? GONE : VISIBLE);
        text.addView(subtitle);
        addView(text);

        chevron = new ImageView(ctx);
        int cs = (int) (24 * dp);
        LayoutParams cp = new LayoutParams(cs, cs);
        cp.setMarginStart((int) (8 * dp));
        chevron.setLayoutParams(cp);
        chevron.setImageResource(R.drawable.ic_chevron);
        chevron.setImageTintList(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.muted)));
        chevron.setVisibility(chev ? VISIBLE : GONE);
        addView(chevron);
    }

    public void setTitle(CharSequence t) {
        title.setText(t);
    }

    public void setSubtitle(CharSequence s) {
        subtitle.setText(s);
        subtitle.setVisibility(s == null || s.length() == 0 ? GONE : VISIBLE);
    }

    public void setIcon(int res) {
        icon.setImageResource(res);
    }

    public void setTint(int color) {
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        title.setTextColor(color);
    }
}
