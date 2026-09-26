package com.planj.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/** A month grid; each logged day carries a dot in its mood colour. Tap a day to open it. */
public class MonthView extends View {
    interface OnDayTap {
        void onDayTap(LocalDate day);
    }

    static final int[] MOOD_COLORS = {0xFFE5616B, 0xFFE0955E, 0xFFD9C25A, 0xFF7FCBA4, 0xFF2EC4B6};

    private YearMonth month = YearMonth.now();
    private Map<LocalDate, MoodStore.Entry> entries = Map.of();
    private LocalDate selected = LocalDate.now();
    private OnDayTap listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int textColor, mutedColor, accent, border;

    public MonthView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = getResources().getDisplayMetrics().density;
        textColor = ctx.getColor(R.color.text);
        mutedColor = ctx.getColor(R.color.muted);
        accent = ctx.getColor(R.color.accent);
        border = ctx.getColor(R.color.border);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
    }

    void set(YearMonth month, Map<LocalDate, MoodStore.Entry> entries, LocalDate selected, OnDayTap listener) {
        this.month = month;
        this.entries = entries;
        this.selected = selected;
        this.listener = listener;
        requestLayout();
        invalidate();
    }

    private int rows() {
        int offset = month.atDay(1).getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        return (int) Math.ceil((offset + month.lengthOfMonth()) / 7.0);
    }

    private float cellHeight() {
        return 48 * density;
    }

    private float headerHeight() {
        return 26 * density;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, (int) (headerHeight() + rows() * cellHeight()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cellWidth = getWidth() / 7f;
        String[] names = {"M", "T", "W", "T", "F", "S", "S"};
        text.setColor(mutedColor);
        for (int i = 0; i < 7; i++) {
            canvas.drawText(names[i], cellWidth * i + cellWidth / 2, 14 * density, text);
        }

        int offset = month.atDay(1).getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        LocalDate today = LocalDate.now();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate day = month.atDay(d);
            int index = offset + d - 1;
            float cx = cellWidth * (index % 7) + cellWidth / 2;
            float cy = headerHeight() + cellHeight() * (index / 7) + cellHeight() / 2;
            boolean future = day.isAfter(today);

            if (day.equals(selected)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * density);
                paint.setColor(accent);
                canvas.drawCircle(cx, cy - 3 * density, 17 * density, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            text.setColor(future ? border : day.equals(today) ? accent : textColor);
            canvas.drawText(String.valueOf(d), cx, cy + 2 * density, text);

            MoodStore.Entry e = entries.get(day);
            if (e != null) {
                paint.setColor(MOOD_COLORS[e.mood - 1]);
                canvas.drawCircle(cx, cy + 14 * density, 3 * density, paint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP && listener != null) {
            int col = (int) (e.getX() / (getWidth() / 7f));
            int row = (int) ((e.getY() - headerHeight()) / cellHeight());
            int offset = month.atDay(1).getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
            int d = row * 7 + col - offset + 1;
            performClick();
            if (row >= 0 && d >= 1 && d <= month.lengthOfMonth()) {
                LocalDate day = month.atDay(d);
                if (!day.isAfter(LocalDate.now())) listener.onDayTap(day);
            }
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
