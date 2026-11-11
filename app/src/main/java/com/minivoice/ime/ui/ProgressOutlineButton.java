package com.minivoice.ime.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.widget.AppCompatButton;

import com.minivoice.ime.R;

/** 读取按钮自带一圈描边进度，从顶部中点开始绕按钮边缘走一圈。 */
public class ProgressOutlineButton extends AppCompatButton {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path barPath = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF rect = new RectF();
    private final RectF arcRect = new RectF();
    private int progress = 0;
    private float shownProgress = 0f;
    private float stroke;
    private float cornerRadius;
    private ValueAnimator animator;

    public ProgressOutlineButton(Context context) {
        super(context);
        init(context);
    }

    public ProgressOutlineButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ProgressOutlineButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        stroke = 2.5f * density;
        // 和 bg_btn_secondary 的 14dp 圆角保持一致，描边才会贴着按钮真实边缘。
        cornerRadius = 14f * density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(context.getResources().getColor(R.color.text_hint, null));
        track.setAlpha(120);
        bar.setStyle(Paint.Style.STROKE);
        bar.setStrokeWidth(stroke);
        bar.setColor(context.getResources().getColor(R.color.accent, null));
        bar.setAlpha(255);
    }

    /** 0 到 100，0 时不画。 */
    public void setProgress(int value) {
        int p = Math.max(0, Math.min(100, value));
        if (p == progress && Math.abs(shownProgress - p) < 0.01f) return;
        progress = p;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (p == 0 || !isAttachedToWindow()) {
            shownProgress = p;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(shownProgress, p);
        animator.setDuration(260);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            shownProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public int getProgress() {
        return progress;
    }

    private void buildPath() {
        // 描边中心落在按钮边缘上，只留半个线宽，避免看起来像往里缩了一圈。
        float inset = stroke / 2f + 0.5f;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float r = Math.min(cornerRadius, Math.min(rect.width(), rect.height()) / 2f);
        float left = rect.left;
        float top = rect.top;
        float right = rect.right;
        float bottom = rect.bottom;
        float cx = rect.centerX();

        path.reset();
        // 从顶部中点开始，顺时针走一圈，转满一圈正好 100%。
        path.moveTo(cx, top);
        path.lineTo(right - r, top);
        arcRect.set(right - 2f * r, top, right, top + 2f * r);
        path.arcTo(arcRect, -90f, 90f, false);
        path.lineTo(right, bottom - r);
        arcRect.set(right - 2f * r, bottom - 2f * r, right, bottom);
        path.arcTo(arcRect, 0f, 90f, false);
        path.lineTo(left + r, bottom);
        arcRect.set(left, bottom - 2f * r, left + 2f * r, bottom);
        path.arcTo(arcRect, 90f, 90f, false);
        path.lineTo(left, top + r);
        arcRect.set(left, top, left + 2f * r, top + 2f * r);
        path.arcTo(arcRect, 180f, 90f, false);
        path.lineTo(cx, top);
        measure.setPath(path, false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildPath();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (path.isEmpty()) buildPath();
        if (path.isEmpty()) return;
        // 底圈始终画出来，读取时高亮进度顺着按钮边缘跑。
        canvas.drawPath(path, track);
        if (shownProgress <= 0f) return;
        barPath.reset();
        float len = measure.getLength();
        measure.getSegment(0f, len * shownProgress / 100f, barPath, true);
        canvas.drawPath(barPath, bar);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }
}
