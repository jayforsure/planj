package com.planj.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Seven bars of screen time, each stacked by app in that app's colour. Tap a bar for the day. */
public class StackedBarChartView extends View {
    interface OnDayTap {
        void onDayTap(LocalDate day);
    }

    private List<DayUsage> days = List.of();
    private List<String> order = List.of(); // apps drawn bottom-up, in this order
    private OnDayTap listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final int otherColor, labelColor, textColor, accent;
    private final float density;

    public StackedBarChartView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = getResources().getDisplayMetrics().density;
        otherColor = ctx.getColor(R.color.border);
        labelColor = ctx.getColor(R.color.muted);
        textColor = ctx.getColor(R.color.text);
        accent = ctx.getColor(R.color.accent);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
    }

    void setData(List<DayUsage> days, List<String> order, OnDayTap listener) {
        this.days = days;
        this.order = order;
        this.listener = listener;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = days.size();
        if (n == 0) return;
        float labelHeight = 20 * density, valueHeight = 18 * density;
        float chartTop = valueHeight, chartBottom = getHeight() - labelHeight;
        float slot = (float) getWidth() / n;
        float barWidth = Math.min(slot * 0.55f, 30 * density);
        float radius = 7 * density;

        long max = 1;
        int maxIndex = 0;
        for (int i = 0; i < n; i++) {
            if (days.get(i).screenMs > max) {
                max = days.get(i).screenMs;
                maxIndex = i;
            }
        }

        for (int i = 0; i < n; i++) {
            DayUsage d = days.get(i);
            float cx = slot * i + slot / 2;
            float height = Math.max(4 * density, (chartBottom - chartTop) * ((float) d.screenMs / max));
            float top = chartBottom - height;
            rect.set(cx - barWidth / 2, top, cx + barWidth / 2, chartBottom);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);

            canvas.save();
            canvas.clipPath(clip);
            fill.setColor(otherColor);
            canvas.drawRect(rect, fill);
            // Stack known apps from the bottom; whatever is left stays "other".
            float y = chartBottom;
            long total = Math.max(d.screenMs, 1);
            for (String pkg : order) {
                Long ms = d.appMs.get(pkg);
                if (ms == null || ms == 0) continue;
                float h = height * ((float) ms / total);
                fill.setColor(AppPalette.color(pkg));
                canvas.drawRect(rect.left, y - h, rect.right, y, fill);
                y -= h;
            }
            canvas.restore();

            boolean last = i == n - 1;
            if (last || i == maxIndex) {
                text.setColor(last ? textColor : labelColor);
                canvas.drawText(Fmt.shortDuration(d.screenMs), cx, top - 6 * density, text);
            }
            text.setColor(last ? accent : labelColor);
            String label = d.day.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).substring(0, 1);
            canvas.drawText(label, cx, getHeight() - 5 * density, text);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP && listener != null && !days.isEmpty()) {
            int i = Math.min(days.size() - 1, Math.max(0, (int) (e.getX() / (getWidth() / (float) days.size()))));
            performClick();
            listener.onDayTap(days.get(i).day);
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
