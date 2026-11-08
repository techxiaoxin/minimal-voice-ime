package com.minivoice.ime.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * 整理态：整条按钮上是几团彩色光斑各走各的，混成随机渐变，不是从左往右滚。
 */
public class MarqueeDrawable extends Drawable {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF rect = new RectF();
    private final float radius;
    private final int[] colors;

    private float phase = 0f;

    public MarqueeDrawable(Context ctx, float radiusPx) {
        this.radius = radiusPx;
        colors = new int[]{0xFF39D2FF, 0xFF62FFB0, 0xFFFFE96B, 0xFFFF9ED8, 0xFFB69CFF};
        fillPaint.setColor(0xFFFFFFFF);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(ctx.getResources().getDisplayMetrics().density);
        strokePaint.setColor(ctx.getColor(com.minivoice.ime.R.color.btn_stroke));
    }

    private final java.util.Random random = new java.util.Random(System.nanoTime());
    private final float[] seedOffset = new float[5];
    private final float[] seedSpeed = new float[5];

    /** 每次开始整理换一组随机相位，看起来不会重复。 */
    public void reshuffle() {
        for (int i = 0; i < 5; i++) {
            seedOffset[i] = random.nextFloat() * 6.28f;
            seedSpeed[i] = 0.45f + random.nextFloat() * 0.9f;
        }
        invalidateSelf();
    }

    /** 0 到 1，各团光斑按不同速度各自移动。 */
    public void setPhase(float phase) {
        this.phase = phase % 1f;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        rect.set(b.left, b.top, b.right, b.bottom);
        canvas.drawRoundRect(rect, radius, radius, fillPaint);

        clipPath.reset();
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clipPath);

        float w = b.width();
        float h = b.height();
        float twoPi = (float) (Math.PI * 2);
        int blobs = 5;
        for (int i = 0; i < blobs; i++) {
            float speed = seedSpeed[i];
            float cx = b.centerX() + (float) Math.sin(phase * twoPi * speed + seedOffset[i]) * w * 0.42f;
            float cy = b.centerY() + (float) Math.cos(phase * twoPi * (speed * 0.75f) + seedOffset[i] * 1.4f) * h * 0.9f;
            float r = Math.max(w, h) * (0.45f + 0.16f * i);
            RadialGradient rg = new RadialGradient(cx, cy, r,
                    colors[(i + 2) % colors.length], 0x00000000, Shader.TileMode.CLAMP);
            blobPaint.setShader(rg);
            blobPaint.setAlpha(232 + 7 * (i % 3));
            canvas.drawCircle(cx, cy, r, blobPaint);
        }

        canvas.restoreToCount(save);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
