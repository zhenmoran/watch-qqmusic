package com.henmoran.watchmusic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 通用可拖动横条：进度条、音量条都用它 */
public class BarView extends View {

    public interface OnSeek {
        void onSeek(float progress, boolean fromUser);
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float progress = 0f;
    private boolean dragging = false;
    private boolean showDot = false;
    private OnSeek listener;

    private float barH;
    private float dotR;

    public BarView(Context c) {
        this(c, null);
    }

    public BarView(Context c, AttributeSet a) {
        super(c, a);
        barH = dp(3);
        dotR = dp(4.5f);
        bgPaint.setColor(0xFF303038);
        fgPaint.setColor(0xFF31E5A6);
        dotPaint.setColor(Color.WHITE);
        setClickable(true);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    public void setListener(OnSeek l) {
        listener = l;
    }

    /** 加粗：音量条需要比进度条更醒目、更好拖 */
    public void setThickness(float thicknessDp) {
        barH = dp(thicknessDp);
        dotR = barH * 0.85f;
        requestLayout();
        invalidate();
    }

    public void setProgress(float p) {
        if (dragging) return;   // 用户正在拖，别被回调覆盖
        progress = Math.max(0, Math.min(1, p));
        invalidate();
    }

    /** 程序设置且强制刷新（切歌时用） */
    public void reset(float p) {
        progress = Math.max(0, Math.min(1, p));
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = (int) (dotR * 2 + dp(10));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cy = getHeight() / 2f;
        float left = dotR;
        float right = getWidth() - dotR;
        float w = right - left;

        rect.set(left, cy - barH / 2, right, cy + barH / 2);
        c.drawRoundRect(rect, barH / 2, barH / 2, bgPaint);

        if (w * progress > 0.5f) {
            rect.set(left, cy - barH / 2, left + w * progress, cy + barH / 2);
            c.drawRoundRect(rect, barH / 2, barH / 2, fgPaint);
        }

        if (showDot || dragging) {
            c.drawCircle(left + w * progress, cy, dotR, dotPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                showDot = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                apply(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) apply(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                showDot = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void apply(float x) {
        float left = dotR;
        float w = getWidth() - dotR * 2;
        if (w <= 0) return;
        progress = Math.max(0, Math.min(1, (x - left) / w));
        invalidate();
        if (listener != null) listener.onSeek(progress, true);
    }
}
