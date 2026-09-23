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

/** The five mood circles, shared by the Today and Journal screens. */
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
            int size = dp(a, 56);
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

    /** Outlined when idle; when chosen, a solid disc inside a translucent halo of the same colour. */
    private static Drawable circle(Activity a, int color) {
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setColor((color & 0x00FFFFFF) | 0x40000000);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(color);
        int inset = dp(a, 5);
        LayerDrawable selected = new LayerDrawable(new Drawable[]{halo, new InsetDrawable(disc, inset)});

        GradientDrawable idleDisc = new GradientDrawable();
        idleDisc.setShape(GradientDrawable.OVAL);
        idleDisc.setColor(a.getColor(R.color.surface_alt));
        idleDisc.setStroke(dp(a, 1), a.getColor(R.color.border));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, selected);
        states.addState(new int[]{}, new InsetDrawable(idleDisc, inset));

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(a.getColor(R.color.ripple)), states, mask);
    }
}
