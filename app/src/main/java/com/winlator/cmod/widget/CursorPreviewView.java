package com.winlator.cmod.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.winlator.cmod.core.UnitUtils;

/** Draws an arrow pointer so the user can see the chosen inside and outline colors. */
public class CursorPreviewView extends View {
    // Arrow outline on a 12x19 grid, hotspot at the top-left corner
    private static final float[] ARROW = {0, 0, 0, 16, 4, 12, 7, 19, 9.5f, 18, 6.5f, 11.5f, 12, 11.5f};

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public CursorPreviewView(Context context) {
        this(context, null);
    }

    public CursorPreviewView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CursorPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.MITER);
        setColors(Color.WHITE, Color.BLACK);
    }

    public void setColors(int insideRgb, int outlineRgb) {
        fillPaint.setColor(0xff000000 | insideRgb);
        strokePaint.setColor(0xff000000 | outlineRgb);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float padding = UnitUtils.dpToPx(8);
        float scale = Math.min(getWidth() - padding * 2, getHeight() - padding * 2) / 19.0f;
        if (scale <= 0) return;

        float offsetX = (getWidth() - 12 * scale) * 0.5f;
        float offsetY = padding;

        path.reset();
        path.moveTo(offsetX + ARROW[0] * scale, offsetY + ARROW[1] * scale);
        for (int i = 2; i < ARROW.length; i += 2) {
            path.lineTo(offsetX + ARROW[i] * scale, offsetY + ARROW[i + 1] * scale);
        }
        path.close();

        strokePaint.setStrokeWidth(Math.max(2, scale * 1.2f));
        canvas.drawPath(path, fillPaint);
        canvas.drawPath(path, strokePaint);
    }
}
