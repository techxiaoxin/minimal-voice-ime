package com.minivoice.ime.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * 录音态：红底胶囊上跑一排白色波形，波形铺满整条按钮，没有外圈装饰。
 */
public class WaveformDrawable extends Drawable {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF rect = new RectF();
    private final float radius;
    private final float density;

    private float phase = 0f;
    /** 麦克风实测音量，0 到 1，由录音回调喂进来。 */
    private float level = 0.2f;
    private float targetLevel = 0.2f;
    private float[] heights = new float[0];
    private final java.util.Random random = new java.util.Random();

    /** 录音时每段 PCM 都调用一次，音量大小直接决定波形起伏。 */
    public void setLevel(float l) {
        targetLevel = Math.max(0.04f, Math.min(1f, l));
    }

    public WaveformDrawable(Context ctx, float radiusPx) {
        this.radius = radiusPx;
        this.density = ctx.getResources().getDisplayMetrics().density;
        fillPaint.setColor(ctx.getColor(com.minivoice.ime.R.color.recording));
    }

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

        float barW = 2.8f * density;
        float gap = 4.4f * density;
        float cy = b.centerY();
        float maxH = b.height() * 0.9f;
        barPaint.setColor(0xFFFFFFFF);
        int count = (int) ((b.width() - 10 * density) / gap);
        if (count < 8) count = 8;
        if (heights.length != count) heights = new float[count];
        float startX = b.left + (b.width() - (count - 1) * gap) / 2f;

        // 音量平滑跟上，说话大声波形就高，安静就缩回去
        level = level * 0.82f + targetLevel * 0.18f;
        float scale = 0.18f + 0.82f * level;
        float mid = (count - 1) / 2f;
        for (int i = 0; i < count; i++) {
            // 每根柱子各自随机跳动，再平滑一下，像频谱一样不死板
            float target = 0.25f + random.nextFloat() * 0.75f;
            heights[i] = heights[i] * 0.72f + target * 0.28f;
            float env = 1f - Math.abs(i - mid) / mid;
            env = (float) Math.pow(Math.max(0.05f, env), 0.45f);
            float f = heights[i] * (0.55f + 0.45f * env);
            float h = maxH * scale * f;
            barPaint.setAlpha((int) (255 * Math.min(1f, 0.35f + 0.65f * f)));
            canvas.drawRoundRect(startX + i * gap, cy - h / 2,
                    startX + i * gap + barW, cy + h / 2,
                    barW / 2, barW / 2, barPaint);
        }
        canvas.restoreToCount(save);
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
