package com.planj.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws a DayTimeline: three lanes across the day from midnight to midnight, where you were,
 * phone on, and PC by category, with hour marks and a "now" line on today.
 */
public final class DayTimelineView extends View {
    private DayTimeline data;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;

    public DayTimelineView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = getResources().getDisplayMetrics().density;
        text.setTextSize(11 * dp);
        text.setColor(ctx.getColor(R.color.text));
        text.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        small.setTextSize(10 * dp);
        small.setColor(ctx.getColor(R.color.muted));
    }

    private String fit(String s, float width) {
        return android.text.TextUtils.ellipsize(s, new android.text.TextPaint(small), width, android.text.TextUtils.TruncateAt.END).toString();
    }

    void setData(DayTimeline d) {
        data = d;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(112 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        float labelW = 56 * dp;
        float left = labelW, right = getWidth();
        float yWhere = 4 * dp, hWhere = 28 * dp;
        float yPhone = yWhere + hWhere + 12 * dp, hLane = 10 * dp;
        float yPc = yPhone + hLane + 12 * dp;
        float yAxis = yPc + hLane + 18 * dp;

        float room = labelW - 6 * dp; // device names are the person's own, so long ones are cut short
        c.drawText("Place", 0, yWhere + hWhere / 2 + 4 * dp, small);
        c.drawText(fit(DeviceNames.phone(getContext()), room), 0, yPhone + hLane - 1 * dp, small);
        c.drawText(fit(DeviceNames.pc(getContext()), room), 0, yPc + hLane - 1 * dp, small);

        // tracks
        fill.setColor(getContext().getColor(R.color.surface_alt));
        round(c, left, yWhere, right, yWhere + hWhere, 8 * dp);
        round(c, left, yPhone, right, yPhone + hLane, 5 * dp);
        round(c, left, yPc, right, yPc + hLane, 5 * dp);

        // hour marks
        for (int h = 0; h <= 24; h += 6) {
            float x = x(h * 60, left, right);
            String lbl = String.format(java.util.Locale.ROOT, "%02d", h);
            float w = small.measureText(lbl);
            c.drawText(lbl, Math.max(left, Math.min(right - w, x - w / 2)), yAxis, small);
        }
        if (data == null) return;

        // where
        for (DayTimeline.Where w : data.where) {
            float x0 = x(w.start, left, right), x1 = x(w.end, left, right);
            if (x1 - x0 < 1) continue;
            fill.setColor(getContext().getColor(w.home ? R.color.border_strong : w.marked ? R.color.accent_dim : R.color.border));
            round(c, x0, yWhere, x1, yWhere + hWhere, 6 * dp);
            float tw = text.measureText(w.label);
            if (x1 - x0 > tw + 10 * dp) {
                c.drawText(w.label, x0 + 6 * dp, yWhere + hWhere / 2 + 4 * dp, text);
            }
        }
        // phone
        fill.setColor(getContext().getColor(R.color.accent));
        fill.setAlpha(190);
        for (int[] p : data.phone) {
            float x0 = x(p[0], left, right), x1 = Math.max(x0 + 1.5f * dp, x(p[1], left, right));
            round(c, x0, yPhone, x1, yPhone + hLane, 2 * dp);
        }
        fill.setAlpha(255);
        // pc
        if (data.pc != null) {
            for (PcDays.Seg s : data.pc) {
                fill.setColor(getContext().getColor(color(s.cat)));
                float x0 = x(s.start, left, right), x1 = Math.max(x0 + 1.5f * dp, x(s.end, left, right));
                round(c, x0, yPc, x1, yPc + hLane, 2 * dp);
            }
        }
        // now
        if (data.now < 24 * 60) {
            float x = x(data.now, left, right);
            fill.setColor(getContext().getColor(R.color.text));
            c.drawRect(x - 0.75f * dp, yWhere - 2 * dp, x + 0.75f * dp, yPc + hLane + 2 * dp, fill);
            fill.setColor(0x660E1113); // what hasn't happened yet is dimmed
            c.drawRect(x + 1 * dp, yWhere, right, yPc + hLane, fill);
        }
    }

    static int color(String cat) {
        switch (cat) {
            case "focus": return R.color.accent;
            case "entertainment": return R.color.warn;
            case "social": return R.color.bad;
            case "chat": return R.color.muted;
            default: return R.color.idle;
        }
    }

    private float x(int minute, float left, float right) {
        return left + (right - left) * Math.max(0, Math.min(24 * 60, minute)) / (24f * 60);
    }

    private void round(Canvas c, float l, float t, float rr, float b, float radius) {
        r.set(l, t, rr, b);
        c.drawRoundRect(r, radius, radius, fill);
    }
}
