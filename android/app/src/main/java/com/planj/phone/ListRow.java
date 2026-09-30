package com.planj.phone;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one list row used on every page: a 52dp icon square, title, optional subtitle, and on
 * the right either a chevron or a value ("64%", "29m"). The icon is an outlined glyph, an
 * app's own icon filling the square, or a letter when there is no icon.
 */
public final class ListRow extends LinearLayout {
    private final ImageView icon;
    private final TextView title, subtitle, value;
    private final ImageView chevron;
    private final float dp;

    /** For rows built in code. */
    public ListRow(Context ctx) {
        this(ctx, null);
    }

    public ListRow(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = getResources().getDisplayMetrics().density;
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
        icon.setBackgroundResource(R.drawable.icon_circle); // an outlined circle, transparent inside
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
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        text.addView(title);
        subtitle = new TextView(ctx);
        subtitle.setTextColor(ctx.getColor(R.color.muted));
        subtitle.setTextSize(13);
        subtitle.setText(s);
        subtitle.setVisibility(s == null || s.length() == 0 ? GONE : VISIBLE);
        text.addView(subtitle);
        addView(text);

        value = new TextView(ctx);
        value.setTextColor(ctx.getColor(R.color.text));
        value.setTextSize(22);
        value.setTypeface(ctx.getResources().getFont(R.font.display));
        value.setFontVariationSettings("'wght' 800, 'opsz' 48, 'wdth' 100");
        value.setPadding((int) (12 * dp), 0, 0, 0);
        value.setVisibility(GONE);
        addView(value);

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
        outline();
        icon.setImageResource(res);
    }

    /** Back to the outlined square, after an app's own icon was shown in it. */
    private void outline() {
        int pad = (int) (14 * dp);
        icon.setBackgroundResource(R.drawable.icon_circle);
        icon.setPadding(pad, pad, pad, pad);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setClipToOutline(false);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(title.getCurrentTextColor()));
    }

    public void setIcon(android.graphics.drawable.Drawable d) {
        icon.setImageDrawable(d);
    }

    /** An app's own icon, filling the square with the same rounded corners as the outline. */
    public void setImage(android.graphics.drawable.Drawable d) {
        icon.setBackground(null);
        icon.setPadding(0, 0, 0, 0);
        icon.setImageTintList(null);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * dp);
            }
        });
        icon.setImageDrawable(d);
    }

    /** No icon to show: the first letter, inside the usual outline. */
    public void setLetter(String name) {
        String l = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        outline();
        icon.setImageTintList(null);
        icon.setImageDrawable(new LetterDrawable(l, getContext().getColor(R.color.text), 20 * dp));
    }

    /** A value on the right ("29m") in place of the chevron, set like the title. */
    public void setValue(CharSequence v, boolean muted) {
        value.setTextSize(16);
        value.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        value.setFontVariationSettings(null);
        showValue(v, muted);
    }

    /** A headline value on the right ("64%"), in the display face. */
    public void setBigValue(CharSequence v, boolean muted) {
        value.setTextSize(24);
        value.setTypeface(getContext().getResources().getFont(R.font.display));
        value.setFontVariationSettings("'wght' 800, 'opsz' 48, 'wdth' 100");
        showValue(v, muted);
    }

    private void showValue(CharSequence v, boolean muted) {
        value.setText(v);
        value.setTextColor(getContext().getColor(muted ? R.color.muted : R.color.text));
        value.setVisibility(v == null || v.length() == 0 ? GONE : VISIBLE);
        chevron.setVisibility(GONE);
    }

    public void setValueColor(int color) {
        value.setTextColor(color);
    }

    public void setChevron(boolean shown) {
        chevron.setVisibility(shown ? VISIBLE : GONE);
    }

    /** Lets a long subtitle wrap instead of running on, for rows that explain themselves. */
    public void setSubtitleLines(int lines) {
        subtitle.setMaxLines(lines);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setLineSpacing(2 * dp, 1f);
    }

    private static final class LetterDrawable extends android.graphics.drawable.Drawable {
        private final String letter;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        LetterDrawable(String letter, int color, float size) {
            this.letter = letter;
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
            c.drawText(letter, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2, paint);
        }

        @Override
        public void setAlpha(int a) {
            paint.setAlpha(a);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    public void setTint(int color) {
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        title.setTextColor(color);
    }
}
