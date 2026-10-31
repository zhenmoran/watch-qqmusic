package com.henmoran.watchmusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/** 圆角封面，等价于 H5 版的 border-radius + object-fit: cover */
public class CoverView extends View {

    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final float radius;

    public CoverView(Context c) {
        this(c, null);
    }

    public CoverView(Context c, AttributeSet a) {
        super(c, a);
        radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10,
                getResources().getDisplayMetrics());
        bg.setColor(0xFF1C1C20);
    }

    public void setBitmap(Bitmap bm) {
        bitmap = bm;
        if (bm != null) {
            BitmapShader shader = new BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(getWidth() / (float) bm.getWidth(),
                    getHeight() / (float) bm.getHeight());
            matrix.setScale(scale, scale);
            // 居中裁切
            matrix.postTranslate(
                    (getWidth() - bm.getWidth() * scale) / 2f,
                    (getHeight() - bm.getHeight() * scale) / 2f);
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
        } else {
            paint.setShader(null);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        rect.set(0, 0, getWidth(), getHeight());
        c.drawRoundRect(rect, radius, radius, bg);
        if (paint.getShader() != null) {
            c.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
