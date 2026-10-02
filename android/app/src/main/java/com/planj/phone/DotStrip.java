package com.planj.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** One dot per checked forecast, oldest first: filled when planj was right, hollow when not. */
final class DotStrip extends View {
    private final Paint hit = new Paint(Paint.ANTI_ALIAS_FLAG), miss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private List<Boolean> dots = new ArrayList<>();

    DotStrip(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        hit.setColor(ctx.getColor(R.color.accent));
        miss.setStyle(Paint.Style.STROKE);
        miss.setStrokeWidth(1.5f * dp);
        miss.setColor(ctx.getColor(R.color.idle));
    }

    void setDots(List<Boolean> right) {
        dots = right;
        requestLayout();
        invalidate();
    }

    private float step() {
        return 16 * dp;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int perRow = Math.max(1, (int) (width / step()));
        int rows = Math.max(1, (dots.size() + perRow - 1) / perRow);
        setMeasuredDimension(width, Math.round(rows * step()));
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = step(), r = 5 * dp;
        int perRow = Math.max(1, (int) (getWidth() / s));
        for (int i = 0; i < dots.size(); i++) {
            float x = (i % perRow) * s + s / 2, y = (i / perRow) * s + s / 2;
            if (dots.get(i)) c.drawCircle(x, y, r, hit);
            else c.drawCircle(x, y, r - 0.75f * dp, miss);
        }
    }
}
