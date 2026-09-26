package com.planj.phone;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * A slot for artwork. Set {@code app:slot="welcome"} and drop {@code img_welcome.png} (or .webp,
 * .xml) into res/drawable: the image shows up with no code change. Until then the slot draws a
 * dashed frame with its name, so the layout is already right and the artist knows what to make.
 */
public final class Illustration extends ImageView {
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private String slot = "";
    private String note = "";
    private boolean found;

    public Illustration(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        int[] wanted = {android.R.attr.tag, android.R.attr.contentDescription};
        TypedArray a = ctx.obtainStyledAttributes(attrs, wanted);
        CharSequence tag = a.getText(0);
        CharSequence desc = a.getText(1);
        a.recycle();
        slot = tag == null ? "" : tag.toString();
        note = desc == null ? "" : desc.toString();
        setScaleType(ScaleType.CENTER_CROP);
        setAdjustViewBounds(true);

        float dp = getResources().getDisplayMetrics().density;
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(1.5f * dp);
        frame.setColor(ctx.getColor(R.color.border));
        frame.setPathEffect(new DashPathEffect(new float[]{6 * dp, 5 * dp}, 0));
        label.setColor(ctx.getColor(R.color.idle));
        label.setTextSize(11 * dp);
        label.setTextAlign(Paint.Align.CENTER);
        label.setLetterSpacing(0.08f);

        int id = slot.isEmpty() ? 0 : getResources().getIdentifier("img_" + slot, "drawable", ctx.getPackageName());
        found = id != 0;
        if (found) setImageResource(id);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (found) {
            super.onDraw(canvas);
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        float r = 16 * dp;
        box.set(frame.getStrokeWidth(), frame.getStrokeWidth(), getWidth() - frame.getStrokeWidth(), getHeight() - frame.getStrokeWidth());
        canvas.drawRoundRect(box, r, r, frame);
        float cy = getHeight() / 2f;
        canvas.drawText(("IMAGE · " + slot).toUpperCase(), getWidth() / 2f, cy - 2 * dp, label);
        if (!note.isEmpty()) {
            label.setTextSize(10 * dp);
            canvas.drawText(note, getWidth() / 2f, cy + 14 * dp, label);
            label.setTextSize(11 * dp);
        }
    }
}
