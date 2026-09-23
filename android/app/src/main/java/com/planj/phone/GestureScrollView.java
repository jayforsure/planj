package com.planj.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ScrollView;

/** A ScrollView that also reports horizontal swipes and pinches, without stealing scrolling. */
public class GestureScrollView extends ScrollView {
    interface Listener {
        void onSwipe(int direction); // -1 left, +1 right

        void onPinch(boolean in);
    }

    private Listener listener;
    private final GestureDetector swipes;
    private final ScaleGestureDetector pinches;
    private float spanAtStart;

    public GestureScrollView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float density = getResources().getDisplayMetrics().density;
        swipes = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || listener == null) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > 90 * density && Math.abs(dx) > 2 * Math.abs(dy) && Math.abs(vx) > 600 * density) {
                    listener.onSwipe(dx < 0 ? -1 : 1);
                    return true;
                }
                return false;
            }
        });
        pinches = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                spanAtStart = d.getCurrentSpan();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                if (listener == null || spanAtStart <= 0) return;
                float ratio = d.getCurrentSpan() / spanAtStart;
                if (ratio < 0.7f) listener.onPinch(true);
                else if (ratio > 1.4f) listener.onPinch(false);
            }
        });
    }

    void setGestureListener(Listener l) {
        listener = l;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        pinches.onTouchEvent(ev);
        swipes.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }
}
