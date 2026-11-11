package com.minivoice.ime.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatButton;

import com.minivoice.ime.R;

/** 读取按钮自带一圈描边进度，不用再单独放进度条。 */
public class ProgressOutlineButton extends AppCompatButton {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path barPath = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF rect = new RectF();
    private int progress = 0;
    private float stroke;

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
        stroke = 2.5f * context.getResources().getDisplayMetrics().density;
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
        if (p == progress) return;
        progress = p;
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    private void buildPath() {
        float inset = stroke / 2f + 4f;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = Math.max(0f, Math.min(rect.width(), rect.height()) / 2f);
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
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
        // 按钮本身始终有一圈细描边，读取时进度顺着这圈跑满，不再另放直线进度条。
        canvas.drawPath(path, track);
        if (progress <= 0) return;
        barPath.reset();
        float len = measure.getLength();
        measure.getSegment(0f, len * progress / 100f, barPath, true);
        canvas.drawPath(barPath, bar);
    }
}
