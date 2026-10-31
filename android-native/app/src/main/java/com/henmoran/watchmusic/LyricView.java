package com.henmoran.watchmusic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 自绘歌词。
 *
 * 长句会按控件宽度自动折行（用 Paint.breakText 手动切，优先在标点处断），
 * 行高按折行后的实际行数累加，所以「当前句居中」在长短句混排时也准。
 *
 * 只绘制可视范围内的行，120 行和 10 行的绘制成本一样。
 */
public class LyricView extends View {

    public interface OnLineClick {
        void onLineClick(long timeMs);
    }

    private List<Lrc.Line> lines = new ArrayList<>();
    private final List<String[]> wrapped = new ArrayList<>();
    private int[] tops = new int[0];
    private int totalH = 0;
    private int textWidth = 0;

    private int index = -1;
    private float curOffset = 0f;
    private float targetOffset = 0f;

    private final Paint normal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint near = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int lineH;

    private OnLineClick clickListener;
    private boolean userScrolling = false;
    private float lastY, downY;
    private boolean moved;

    private final Runnable resumeFollow = new Runnable() {
        @Override
        public void run() {
            userScrolling = false;
            targetOffset = centerOf(index);
            invalidate();
        }
    };

    public LyricView(Context c) {
        this(c, null);
    }

    public LyricView(Context c, AttributeSet a) {
        super(c, a);
        lineH = (int) sp(20);

        normal.setColor(0xFF4A4A52);
        near.setColor(0xFF8B8B93);
        active.setColor(0xFFFFFFFF);
        active.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        for (Paint p : new Paint[]{normal, near, active}) {
            p.setTextSize(sp(13));
            p.setTextAlign(Paint.Align.CENTER);
        }
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    public void setOnLineClick(OnLineClick l) {
        clickListener = l;
    }

    public void setLines(List<Lrc.Line> l) {
        lines = l == null ? new ArrayList<>() : l;
        index = -1;
        curOffset = 0;
        targetOffset = 0;
        relayout();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout();
    }

    /** 按当前宽度把每句切成若干视觉行，并累加出顶部偏移 */
    private void relayout() {
        wrapped.clear();
        if (lines.isEmpty() || getWidth() <= 0) {
            tops = new int[0];
            totalH = 0;
            return;
        }

        textWidth = getWidth() - getPaddingLeft() - getPaddingRight() - (int) dp(10);
        if (textWidth <= 0) textWidth = getWidth();

        tops = new int[lines.size()];
        int y = 0;
        for (int i = 0; i < lines.size(); i++) {
            String[] seg = wrap(lines.get(i).text);
            wrapped.add(seg);
            tops[i] = y;
            y += seg.length * lineH;
        }
        totalH = y;
    }

    /** 长句折行，尽量断在标点处，别把词切开 */
    private String[] wrap(String text) {
        if (text == null || text.isEmpty()) return new String[]{""};
        List<String> out = new ArrayList<>();
        String rest = text;

        while (!rest.isEmpty()) {
            int n = normal.breakText(rest, true, textWidth, null);
            if (n <= 0) {
                out.add(rest);
                break;
            }
            if (n >= rest.length()) {
                out.add(rest);
                break;
            }
            int cut = n;
            for (int i = n - 1; i >= Math.max(0, n - 8); i--) {
                char ch = rest.charAt(i);
                if ("，。、！？；：, .!?;:".indexOf(ch) >= 0) {
                    cut = i + 1;
                    break;
                }
            }
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut);
        }
        return out.toArray(new String[0]);
    }

    /** 第 index 行居中时对应的内容偏移 */
    private float centerOf(int i) {
        if (i < 0 || i >= tops.length || i >= wrapped.size()) return 0;
        return tops[i] + wrapped.get(i).length * lineH / 2f;
    }

    public void setTime(long ms) {
        if (lines.isEmpty()) return;
        int i = Lrc.indexAt(lines, ms);
        if (i == index) return;
        index = i;
        if (!userScrolling) {
            targetOffset = centerOf(index);
        }
        invalidate();
    }

    /** 表冠滚动：直接改偏移，暂停自动跟随 2 秒后回归 */
    public void scrollByCrown(float dy) {
        if (lines.isEmpty()) return;
        userScrolling = true;
        removeCallbacks(resumeFollow);
        curOffset = clamp(curOffset + dy);
        targetOffset = curOffset;
        postDelayed(resumeFollow, 2000);
        invalidate();
    }

    private float clamp(float v) {
        float max = Math.max(0, totalH - lineH);
        return Math.max(0, Math.min(max, v));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (lines.isEmpty() || wrapped.isEmpty()) {
            normal.setTextSize(sp(12));
            c.drawText("暂无歌词", getWidth() / 2f, getHeight() / 2f + sp(4), normal);
            normal.setTextSize(sp(13));
            return;
        }
        if (index < 0) {
            index = 0;
            targetOffset = centerOf(0);
        }

        // 平滑逼近
        float diff = targetOffset - curOffset;
        if (Math.abs(diff) > 0.5f) {
            curOffset += diff * 0.2f;
            postInvalidateOnAnimation();
        } else {
            curOffset = targetOffset;
        }

        float base = getHeight() / 2f;
        float topInContent = curOffset - base;
        float botInContent = curOffset + base;

        int first = 0;
        while (first < tops.length - 1
                && tops[first] + wrapped.get(first).length * lineH < topInContent) {
            first++;
        }
        int last = first;
        while (last < tops.length - 1 && tops[last] < botInContent) {
            last++;
        }

        for (int i = first; i <= last; i++) {
            String[] seg = wrapped.get(i);
            Paint p = (i == index) ? active : (Math.abs(i - index) == 1 ? near : normal);
            float top = base + tops[i] - curOffset;
            for (int k = 0; k < seg.length; k++) {
                c.drawText(seg[k], getWidth() / 2f, top + (k + 1) * lineH, p);
            }
        }
    }

    /* ------------------------------ 触摸 ------------------------------ */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (lines.isEmpty()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY = e.getY();
                downY = e.getY();
                moved = false;
                userScrolling = true;
                removeCallbacks(resumeFollow);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - lastY;
                lastY = e.getY();
                if (Math.abs(e.getY() - downY) > dp(6)) moved = true;
                curOffset = clamp(curOffset - dy);
                targetOffset = curOffset;
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
                if (!moved) {
                    float yInContent = e.getY() - getHeight() / 2f + curOffset;
                    int idx = lineAt(yInContent);
                    if (idx >= 0 && clickListener != null) {
                        clickListener.onLineClick(lines.get(idx).time);
                    }
                    userScrolling = false;
                    targetOffset = centerOf(index);
                    invalidate();
                } else {
                    removeCallbacks(resumeFollow);
                    postDelayed(resumeFollow, 2500);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(resumeFollow);
                postDelayed(resumeFollow, 800);
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** 内容坐标 → 第几行（二分） */
    private int lineAt(float y) {
        if (tops.length == 0) return -1;
        int lo = 0, hi = tops.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (tops[mid] <= y) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}
