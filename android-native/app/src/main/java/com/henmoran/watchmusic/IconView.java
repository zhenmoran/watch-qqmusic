package com.henmoran.watchmusic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 自绘图标集。手表字体里不缺这些符号的概率不高，
 * 直接画路径最稳，也省掉一堆 png。
 */
public class IconView extends View {

    public static final int BACK = 0, PLAY = 1, PAUSE = 2, PREV = 3, NEXT = 4,
            HEART = 5, LYRIC = 6, VOL = 7, MUTE = 8, SEARCH = 9,
            HOME = 10, USER = 11, REFRESH = 12, CLOCK = 13,
            DOWNLOAD = 14, TRASH = 15, CHECK = 16;

    private int type = PLAY;
    private int color = 0xFFF5F5F7;
    private boolean filled = false;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    public IconView(Context c) {
        this(c, null);
    }

    public IconView(Context c, AttributeSet a) {
        super(c, a);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    public void setType(int t) {
        type = t;
        invalidate();
    }

    public void setColor(int c) {
        color = c;
        invalidate();
    }

    public void setFilled(boolean f) {
        filled = f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        float cx = w / 2, cy = h / 2;
        float u = s / 24f;          // 以 24 网格设计，按实际尺寸缩放

        stroke.setColor(color);
        stroke.setStrokeWidth(1.9f * u);
        fill.setColor(color);

        Paint main = filled ? fill : stroke;
        path.reset();

        switch (type) {
            case BACK:
                path.moveTo(cx + 6 * u, cy - 8 * u);
                path.lineTo(cx - 5 * u, cy);
                path.lineTo(cx + 6 * u, cy + 8 * u);
                c.drawPath(path, stroke);
                break;

            case PLAY:
                path.moveTo(cx - 6 * u, cy - 9 * u);
                path.lineTo(cx + 9 * u, cy);
                path.lineTo(cx - 6 * u, cy + 9 * u);
                path.close();
                c.drawPath(path, fill);
                break;

            case PAUSE:
                oval.set(cx - 7 * u, cy - 9 * u, cx - 2.4f * u, cy + 9 * u);
                c.drawRoundRect(oval, 1.4f * u, 1.4f * u, fill);
                oval.set(cx + 2.4f * u, cy - 9 * u, cx + 7 * u, cy + 9 * u);
                c.drawRoundRect(oval, 1.4f * u, 1.4f * u, fill);
                break;

            case PREV:
            case NEXT: {
                boolean prev = type == PREV;
                float dir = prev ? 1 : -1;
                path.moveTo(cx + dir * 6 * u, cy - 9 * u);
                path.lineTo(cx - dir * 4 * u, cy);
                path.lineTo(cx + dir * 6 * u, cy + 9 * u);
                path.close();
                c.drawPath(path, fill);
                float barX = prev ? cx - 9 * u : cx + 9 * u;
                oval.set(barX - 1.3f * u, cy - 9 * u, barX + 1.3f * u, cy + 9 * u);
                c.drawRoundRect(oval, 1.2f * u, 1.2f * u, fill);
                break;
            }

            case HEART: {
                float r = 5.2f * u;
                path.moveTo(cx, cy + 8 * u);
                path.cubicTo(cx - 13 * u, cy - 1 * u, cx - 7 * u, cy - 11 * u, cx, cy - 4.5f * u);
                path.cubicTo(cx + 7 * u, cy - 11 * u, cx + 13 * u, cy - 1 * u, cx, cy + 8 * u);
                path.close();
                c.drawPath(path, filled ? fill : stroke);
                break;
            }

            case LYRIC:
                c.drawLine(cx - 9 * u, cy - 7 * u, cx + 9 * u, cy - 7 * u, stroke);
                c.drawLine(cx - 9 * u, cy - 1 * u, cx + 2 * u, cy - 1 * u, stroke);
                c.drawLine(cx - 9 * u, cy + 5 * u, cx + 6 * u, cy + 5 * u, stroke);
                break;

            case VOL:
            case MUTE: {
                path.moveTo(cx - 10 * u, cy - 3.5f * u);
                path.lineTo(cx - 6 * u, cy - 3.5f * u);
                path.lineTo(cx - 1 * u, cy - 8 * u);
                path.lineTo(cx - 1 * u, cy + 8 * u);
                path.lineTo(cx - 6 * u, cy + 3.5f * u);
                path.lineTo(cx - 10 * u, cy + 3.5f * u);
                path.close();
                c.drawPath(path, fill);
                if (type == VOL) {
                    oval.set(cx + 1 * u, cy - 4 * u, cx + 9 * u, cy + 4 * u);
                    c.drawArc(oval, -50, 100, false, stroke);
                    oval.set(cx + 1 * u, cy - 7.5f * u, cx + 12.5f * u, cy + 7.5f * u);
                    c.drawArc(oval, -45, 90, false, stroke);
                } else {
                    c.drawLine(cx + 2 * u, cy - 4 * u, cx + 9 * u, cy + 4 * u, stroke);
                    c.drawLine(cx + 9 * u, cy - 4 * u, cx + 2 * u, cy + 4 * u, stroke);
                }
                break;
            }

            case SEARCH:
                c.drawCircle(cx - 1 * u, cy - 1 * u, 6.5f * u, stroke);
                c.drawLine(cx + 3.5f * u, cy + 3.5f * u, cx + 8.5f * u, cy + 8.5f * u, stroke);
                break;

            case HOME:
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        float left = cx + (i == 0 ? -9 : 1.4f) * u;
                        float top = cy + (j == 0 ? -9 : 1.4f) * u;
                        oval.set(left, top, left + 7.6f * u, top + 7.6f * u);
                        c.drawRoundRect(oval, 2 * u, 2 * u, fill);
                    }
                }
                break;

            case USER:
                c.drawCircle(cx, cy - 4.5f * u, 4.4f * u, stroke);
                oval.set(cx - 8 * u, cy + 1 * u, cx + 8 * u, cy + 17 * u);
                c.drawArc(oval, 180, 180, false, stroke);
                break;

            case REFRESH:
                oval.set(cx - 8 * u, cy - 8 * u, cx + 8 * u, cy + 8 * u);
                c.drawArc(oval, -60, 300, false, stroke);
                path.moveTo(cx + 4.5f * u, cy - 9 * u);
                path.lineTo(cx + 9 * u, cy - 8.5f * u);
                path.lineTo(cx + 8.2f * u, cy - 4 * u);
                c.drawPath(path, stroke);
                break;

            case DOWNLOAD:
                c.drawLine(cx, cy - 9 * u, cx, cy + 2 * u, stroke);
                path.moveTo(cx - 6 * u, cy - 3 * u);
                path.lineTo(cx, cy + 3 * u);
                path.lineTo(cx + 6 * u, cy - 3 * u);
                c.drawPath(path, stroke);
                c.drawLine(cx - 9 * u, cy + 8 * u, cx + 9 * u, cy + 8 * u, stroke);
                break;

            case CHECK:
                path.moveTo(cx - 8 * u, cy);
                path.lineTo(cx - 2.5f * u, cy + 6 * u);
                path.lineTo(cx + 8 * u, cy - 6 * u);
                c.drawPath(path, stroke);
                break;

            case TRASH:
                c.drawLine(cx - 9 * u, cy - 6 * u, cx + 9 * u, cy - 6 * u, stroke);
                path.moveTo(cx - 6 * u, cy - 6 * u);
                path.lineTo(cx - 5 * u, cy + 8 * u);
                path.lineTo(cx + 5 * u, cy + 8 * u);
                path.lineTo(cx + 6 * u, cy - 6 * u);
                c.drawPath(path, stroke);
                c.drawLine(cx - 3 * u, cy - 9 * u, cx + 3 * u, cy - 9 * u, stroke);
                break;

            case CLOCK:
                c.drawCircle(cx, cy, 8 * u, stroke);
                c.drawLine(cx, cy - 4 * u, cx, cy, stroke);
                c.drawLine(cx, cy, cx + 3.4f * u, cy + 2 * u, stroke);
                break;
        }
    }
}
