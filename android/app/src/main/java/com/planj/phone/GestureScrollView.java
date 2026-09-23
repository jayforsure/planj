package com.planj.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ScrollView;

/**
 * A ScrollView that commits to one gesture at the first movement: vertical scroll, a horizontal
 * swipe that drags the content with the finger, or a two-finger pinch that scales it live.
 * Pinching in settles into a zoomed-out, desaturated look; pinching out restores it.
 *
 * Touches reach a ScrollView two ways — via onInterceptTouchEvent when a child took the
 * press, or straight into onTouchEvent when nothing did — so the classification runs on both.
 */
public class GestureScrollView extends ScrollView {
    interface Listener {
        void onSwipe(int direction); // -1 left (older), +1 right (newer)

        void onPinch(boolean in);
    }

    private static final int NONE = 0, SCROLL = 1, SWIPE = 2, PINCH = 3;
    private static final float PRIVATE_SCALE = 0.93f;

    private Listener listener;
    private View content;
    private int mode = NONE;
    private float downX, downY, startSpan, liveScale = 1f;
    private boolean privateLook;
    private float saturation = 1f;
    private final int slop;
    private final float density;
    private final Paint layerPaint = new Paint();

    public GestureScrollView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        density = getResources().getDisplayMetrics().density;
    }

    void setGestureListener(Listener l) {
        listener = l;
    }

    private View content() {
        if (content == null && getChildCount() > 0) {
            content = getChildAt(0);
            content.setPivotY(0);
        }
        return content;
    }

    // ----- gesture routing -----

    /** Decides scroll / swipe / pinch from an event while still undecided. */
    private void classify(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                mode = NONE;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mode == NONE || mode == SWIPE) {
                    mode = PINCH;
                    startSpan = span(ev);
                    snapContentX();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == NONE && ev.getPointerCount() == 1) {
                    float dx = ev.getX() - downX, dy = ev.getY() - downY;
                    if (Math.abs(dx) > slop && Math.abs(dx) > 1.5f * Math.abs(dy)) mode = SWIPE;
                    else if (Math.abs(dy) > slop) mode = SCROLL;
                }
                break;
            default:
                break;
        }
    }

    private boolean gestureActive() {
        return mode == SWIPE || mode == PINCH;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        classify(ev);
        if (gestureActive()) return true;
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        View c = content();
        if (c == null) return super.onTouchEvent(ev);
        boolean wasGesture = gestureActive();
        classify(ev);
        if (!gestureActive()) {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) mode = NONE;
            return super.onTouchEvent(ev);
        }
        if (!wasGesture) {
            // The scroll machinery may have started tracking this press; let it go.
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.onTouchEvent(cancel);
            cancel.recycle();
        }
        return mode == SWIPE ? handleSwipe(ev, c) : handlePinch(ev, c);
    }

    private boolean handleSwipe(MotionEvent ev, View c) {
        float dx = ev.getX() - downX;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                c.setTranslationX(dx * 0.6f); // a little resistance keeps it feeling attached
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = NONE;
                if (Math.abs(dx) > 70 * density && listener != null) {
                    int direction = dx < 0 ? -1 : 1;
                    // Slide out, switch, slide the new day in from the other side.
                    c.animate().translationX(direction * getWidth() * 0.4f).alpha(0.4f).setDuration(120)
                            .withEndAction(() -> {
                                listener.onSwipe(direction);
                                c.setTranslationX(-direction * getWidth() * 0.25f);
                                c.animate().translationX(0).alpha(1f).setDuration(220)
                                        .setInterpolator(new DecelerateInterpolator()).start();
                            }).start();
                } else {
                    c.animate().translationX(0).setDuration(180).setInterpolator(new OvershootInterpolator(1.5f)).start();
                }
                return true;
            default:
                return true;
        }
    }

    private boolean handlePinch(MotionEvent ev, View c) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() >= 2 && startSpan > 0) {
                    float ratio = span(ev) / startSpan;
                    float base = privateLook ? PRIVATE_SCALE : 1f;
                    liveScale = clamp(base * ratio, 0.82f, 1.06f);
                    c.setScaleX(liveScale);
                    c.setScaleY(liveScale);
                    // Colour drains as the screen shrinks, so the mode change reads before it commits.
                    setSaturation(clamp((liveScale - 0.82f) / (1f - 0.82f), 0f, 1f));
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerCount() <= 2) commitPinch(c);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode == PINCH) commitPinch(c);
                return true;
            default:
                return true;
        }
    }

    private void commitPinch(View c) {
        mode = NONE;
        float base = privateLook ? PRIVATE_SCALE : 1f;
        boolean in = liveScale < base - 0.07f;
        boolean out = liveScale > base + 0.05f;
        if (in && !privateLook) {
            setPrivateLook(true, true);
            if (listener != null) listener.onPinch(true);
        } else if (out && privateLook) {
            setPrivateLook(false, true);
            if (listener != null) listener.onPinch(false);
        } else {
            setPrivateLook(privateLook, true);
        }
    }

    // ----- the private look -----

    void setPrivateLook(boolean on, boolean animate) {
        View c = content();
        if (c == null) return;
        privateLook = on;
        float targetScale = on ? PRIVATE_SCALE : 1f;
        float targetSat = on ? 0f : 1f;
        if (!animate) {
            c.setScaleX(targetScale);
            c.setScaleY(targetScale);
            setSaturation(targetSat);
            return;
        }
        float fromScale = c.getScaleX(), fromSat = saturation;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(380);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float s = fromScale + (targetScale - fromScale) * f;
            c.setScaleX(s);
            c.setScaleY(s);
            setSaturation(fromSat + (targetSat - fromSat) * f);
        });
        anim.start();
    }

    private void setSaturation(float sat) {
        View c = content();
        if (c == null) return;
        saturation = sat;
        if (sat >= 0.999f) {
            c.setLayerType(View.LAYER_TYPE_NONE, null);
            return;
        }
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(sat);
        layerPaint.setColorFilter(new ColorMatrixColorFilter(m));
        c.setLayerType(View.LAYER_TYPE_HARDWARE, layerPaint);
    }

    private void snapContentX() {
        View c = content();
        if (c != null) c.setTranslationX(0);
    }

    private static float span(MotionEvent ev) {
        if (ev.getPointerCount() < 2) return 0;
        float dx = ev.getX(0) - ev.getX(1), dy = ev.getY(0) - ev.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
