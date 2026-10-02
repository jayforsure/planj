package com.planj.phone;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

/** A sideways row of cards that settles on the nearest card when you let go. */
final class SnapScroller extends HorizontalScrollView {
    SnapScroller(Context ctx) {
        super(ctx);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean r = super.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) post(this::snap);
        return r;
    }

    @Override
    public void fling(int velocityX) {
        super.fling(velocityX / 3); // short flings, so a swipe moves about a card
        postDelayed(this::snap, 260);
    }

    private void snap() {
        if (getChildCount() == 0) return;
        ViewGroup row = (ViewGroup) getChildAt(0);
        int best = 0, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < row.getChildCount(); i++) {
            int d = Math.abs(row.getChildAt(i).getLeft() - getScrollX());
            if (d < bestDist) {
                bestDist = d;
                best = row.getChildAt(i).getLeft();
            }
        }
        smoothScrollTo(best, 0);
    }
}
