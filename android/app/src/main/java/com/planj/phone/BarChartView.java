package com.planj.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** A row of rounded bars, the last one highlighted. No library, just a canvas. */
public class BarChartView extends View {
    private float[] values = new float[0];
    private String[] labels = new String[0];
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int barColor, highlightColor, labelColor;

    public BarChartView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        barColor = ctx.getColor(R.color.border);
        highlightColor = ctx.getColor(R.color.accent);
        labelColor = ctx.getColor(R.color.muted);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
    }

    void setData(float[] values, String[] labels) {
        this.values = values;
        this.labels = labels;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = values.length;
        if (n == 0) return;
        float density = getResources().getDisplayMetrics().density;
        float labelHeight = 18 * density;
        float valueHeight = 16 * density;
        float chartTop = valueHeight;
        float chartBottom = getHeight() - labelHeight;
        float slot = (float) getWidth() / n;
        float barWidth = Math.min(slot * 0.5f, 28 * density);
        float radius = barWidth / 2;
        float minBar = 4 * density;

        float max = 0;
        for (float v : values) max = Math.max(max, v);
        if (max <= 0) max = 1;

        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2;
            float h = Math.max(minBar, (chartBottom - chartTop) * (values[i] / max));
            rect.set(cx - barWidth / 2, chartBottom - h, cx + barWidth / 2, chartBottom);
            boolean last = i == n - 1;
            bar.setColor(last ? highlightColor : barColor);
            canvas.drawRoundRect(rect, radius, radius, bar);

            text.setColor(last ? getContext().getColor(R.color.text) : labelColor);
            if (last || values[i] == max) {
                canvas.drawText(formatHours(values[i]), cx, rect.top - 5 * density, text);
            }
            text.setColor(labelColor);
            if (i < labels.length) {
                canvas.drawText(labels[i], cx, getHeight() - 4 * density, text);
            }
        }
    }

    private static String formatHours(float hours) {
        int minutes = Math.round(hours * 60);
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "m");
    }
}
