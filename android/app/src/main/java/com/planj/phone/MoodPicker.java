package com.planj.phone;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** The five mood buttons, shared by the Today and Journal screens. */
final class MoodPicker {
    static final String[] LABELS = {"Awful", "Bad", "Okay", "Good", "Great"};

    private final TextView[] circles = new TextView[5];

    MoodPicker(Activity a, LinearLayout row, IntConsumer onPick) {
        row.setClipChildren(false);
        for (int i = 0; i < 5; i++) {
            int mood = i + 1;
            LinearLayout column = new LinearLayout(a);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setClipChildren(false);

            TextView circle = new TextView(a);
            circle.setText(String.valueOf(mood));
            circle.setGravity(Gravity.CENTER);
            circle.setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_selected}, {}},
                    new int[]{a.getColor(R.color.on_accent), a.getColor(R.color.muted)}));
            circle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            circle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            circle.setBackground(circle(a, MonthView.MOOD_COLORS[i]));
            circle.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80)
                        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                                .setInterpolator(new OvershootInterpolator(2.5f)).start()).start();
                onPick.accept(mood);
            });
            int size = dp(a, 52);
            column.addView(circle, new LinearLayout.LayoutParams(size, size));

            TextView label = new TextView(a);
            label.setText(LABELS[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTextColor(a.getColor(R.color.muted));
            label.setPadding(0, dp(a, 8), 0, 0);
            column.addView(label);

            row.addView(column, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            circles[i] = circle;
        }
    }

    void select(int mood) {
        for (int i = 0; i < 5; i++) {
            circles[i].setSelected(mood == i + 1);
            circles[i].setAlpha(mood == 0 || mood == i + 1 ? 1f : 0.5f);
        }
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /**
     * The same softly rounded square as every icon: an outline when idle; when chosen, filled
     * with the mood's colour inside a faint square of the same colour.
     */
    private static Drawable circle(Activity a, int color) {
        float r = dp(a, 16);
        GradientDrawable halo = new GradientDrawable();
        halo.setCornerRadius(r);
        halo.setColor((color & 0x00FFFFFF) | 0x40000000);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(r - dp(a, 4));
        fill.setColor(color);
        int inset = dp(a, 4);
        LayerDrawable selected = new LayerDrawable(new Drawable[]{halo, new InsetDrawable(fill, inset)});

        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(r);
        idle.setColor(0x00000000);
        idle.setStroke(Math.max(1, Math.round(1.2f * a.getResources().getDisplayMetrics().density)), a.getColor(R.color.border_strong));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, selected);
        states.addState(new int[]{}, idle);

        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(r);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(a.getColor(R.color.ripple)), states, mask);
    }
}
