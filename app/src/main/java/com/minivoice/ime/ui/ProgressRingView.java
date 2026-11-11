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
import android.view.animation.LinearInterpolator;
import android.view.View;

import com.minivoice.ime.R;

/**
 * 叠在读取按钮上方的独立进度层。按钮自己只负责文字和背景，
 * 进度圈画在这个 View 里，避免被按钮背景或按压状态刷新盖掉。
 */
public class ProgressRingView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint percentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path barPath = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF rect = new RectF();
    private final RectF arcRect = new RectF();

    private int progress = 0;
    private float shownProgress = 0f;
    private float spin = 0f;
    private float stroke;
    private float cornerRadius;
    private float percentPadding;
    private boolean indeterminate;
    private ValueAnimator progressAnimator;
    private ValueAnimator spinAnimator;

    public ProgressRingView(Context context) {
        super(context);
        init(context);
    }

    public ProgressRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ProgressRingView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        float density = context.getResources().getDisplayMetrics().density;
        stroke = 4f * density;
        cornerRadius = 14f * density;
        percentPadding = 10f * density;
        percentPaint.setTextSize(11f * density);
        percentPaint.setFakeBoldText(true);
        percentPaint.setTextAlign(Paint.Align.RIGHT);
        percentPaint.setColor(context.getResources().getColor(R.color.text_secondary, null));
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(context.getResources().getColor(R.color.text_hint, null));
        track.setAlpha(150);
        track.setStrokeCap(Paint.Cap.ROUND);
        bar.setStyle(Paint.Style.STROKE);
        bar.setStrokeWidth(stroke);
        bar.setColor(context.getResources().getColor(R.color.accent, null));
        bar.setAlpha(255);
        bar.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setRingVisible(boolean visible) {
        if (visible) {
            setVisibility(VISIBLE);
            invalidate();
        } else {
            setVisibility(GONE);
            cancelAnimators();
        }
    }

    /** 读取刚开始、还不知道百分比时，先让一段蓝色弧线绕按钮转圈。 */
    public void setIndeterminate(boolean value) {
        if (indeterminate == value) return;
        indeterminate = value;
        if (indeterminate) {
            startSpin();
        } else {
            stopSpin();
        }
        invalidate();
    }

    public void setProgress(int value) {
        int p = Math.max(0, Math.min(100, value));
        if (p == progress && Math.abs(shownProgress - p) < 0.01f && !indeterminate) return;
        progress = p;
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        if (p == 0 || !isAttachedToWindow() || getVisibility() != VISIBLE) {
            shownProgress = p;
            invalidate();
            return;
        }
        progressAnimator = ValueAnimator.ofFloat(shownProgress, p);
        progressAnimator.setDuration(450);
        progressAnimator.setInterpolator(new DecelerateInterpolator());
        progressAnimator.addUpdateListener(a -> {
            shownProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        progressAnimator.start();
    }

    private void startSpin() {
        stopSpin();
        spinAnimator = ValueAnimator.ofFloat(0f, 1f);
        spinAnimator.setDuration(1100);
        spinAnimator.setRepeatCount(ValueAnimator.INFINITE);
        spinAnimator.setInterpolator(new LinearInterpolator());
        spinAnimator.addUpdateListener(a -> {
            spin = (float) a.getAnimatedValue();
            invalidate();
        });
        spinAnimator.start();
    }

    private void stopSpin() {
        if (spinAnimator != null) {
            spinAnimator.cancel();
            spinAnimator = null;
        }
        spin = 0f;
        invalidate();
    }

    private void cancelAnimators() {
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        stopSpin();
    }

    private void buildPath() {
        float inset = stroke / 2f + 0.5f;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float r = Math.min(cornerRadius, Math.min(rect.width(), rect.height()) / 2f);
        float left = rect.left;
        float top = rect.top;
        float right = rect.right;
        float bottom = rect.bottom;
        float cx = rect.centerX();

        path.reset();
        // 从顶部中点开始，顺时针一圈。
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
        if (getVisibility() != VISIBLE) return;
        if (path.isEmpty()) buildPath();
        if (path.isEmpty()) return;
        canvas.drawPath(path, track);
        float len = measure.getLength();
        if (len <= 0f) return;
        barPath.reset();
        if (indeterminate) {
            // 一段约 22% 的弧线在按钮边缘匀速转圈，先给一个明确的“正在读取”反馈。
            float seg = len * 0.22f;
            float start = len * spin;
            float end = start + seg;
            if (end <= len) {
                measure.getSegment(start, end, barPath, true);
            } else {
                measure.getSegment(start, len, barPath, true);
                measure.getSegment(0f, end - len, barPath, true);
            }
        } else if (shownProgress > 0f) {
            measure.getSegment(0f, len * shownProgress / 100f, barPath, true);
        }
        canvas.drawPath(barPath, bar);
        String pct = Math.round(shownProgress) + "%";
        float baseline = getHeight() / 2f
                - (percentPaint.ascent() + percentPaint.descent()) / 2f;
        canvas.drawText(pct, getWidth() - percentPadding, baseline, percentPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimators();
        super.onDetachedFromWindow();
    }
}
