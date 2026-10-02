package com.planj.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** A ring that fills to the odds when it first appears, with the number in the middle. */
final class OddsRing extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float dp;
    private int percent = -1;
    private float shown;
    private boolean animated;

    OddsRing(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setColor(ctx.getColor(R.color.surface_alt));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(ctx.getColor(R.color.accent));
        text.setColor(ctx.getColor(R.color.text));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(ctx.getResources().getFont(R.font.display));
        text.setFontVariationSettings("'wght' 800, 'opsz' 96, 'wdth' 100");
    }

    /** -1 shows a dash, for odds still being learned. */
    void setPercent(int p) {
        percent = p;
        if (animated) shown = Math.max(0, p);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animated || percent < 0) return;
        animated = true;
        ValueAnimator va = ValueAnimator.ofFloat(0, percent);
        va.setDuration(900);
        va.setStartDelay(120);
        va.setInterpolator(new DecelerateInterpolator(1.6f));
        va.addUpdateListener(v -> {
            shown = (float) v.getAnimatedValue();
            invalidate();
        });
        va.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Math.min(getWidth(), getHeight());
        float stroke = Math.max(5 * dp, size * 0.08f);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        float pad = stroke / 2 + dp;
        box.set((getWidth() - size) / 2 + pad, (getHeight() - size) / 2 + pad, (getWidth() + size) / 2 - pad, (getHeight() + size) / 2 - pad);
        c.drawArc(box, 0, 360, false, track);
        if (percent >= 0 && shown > 0) c.drawArc(box, -90, 360 * shown / 100f, false, arc);
        text.setTextSize(size * 0.27f);
        String label = percent < 0 ? "–" : Math.round(animated ? shown : percent) + "%";
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(label, getWidth() / 2f, getHeight() / 2f - (fm.ascent + fm.descent) / 2, text);
    }
}
