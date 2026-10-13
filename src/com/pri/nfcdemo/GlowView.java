package com.pri.nfcdemo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** 屏幕边缘光效(呼吸)+ 顶部虹彩柔光 —— 渐变 shader 平滑绘制,无分层感。 */
public class GlowView extends View {

    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mD;
    private float mAlpha = 0.4f;

    public GlowView(Context c) {
        super(c);
        mD = c.getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        try {
            // 中心透明 → 边缘亮白的径向渐变 = 平滑边缘发光
            float r = (float) Math.hypot(w / 2.0, h / 2.0);
            mEdge.setShader(new RadialGradient(w / 2f, h / 2f, r,
                    new int[]{0x00FFFFFF, 0x00FFFFFF, 0x4DFFFFFF, 0xF2FFFFFF},
                    new float[]{0f, 0.60f, 0.84f, 1f},
                    Shader.TileMode.CLAMP));
            mTop.setShader(new LinearGradient(w * 0.08f, 0, w * 0.92f, 0,
                    new int[]{0x00FFFFFF, 0x88FFB6C8, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDraw(Canvas cv) {
        try {
            mEdge.setAlpha((int) (255 * mAlpha));
            cv.drawRect(0, 0, getWidth(), getHeight(), mEdge);
            mTop.setAlpha((int) (255 * Math.min(1f, mAlpha + 0.05f)));
            cv.drawRect(0, 0, getWidth(), 64 * mD, mTop);
        } catch (Throwable ignored) {}
    }

    /** 呼吸脉动:3.4 秒一轮,0.4 ↔ 0.95。 */
    public void startBreathing() {
        ValueAnimator va = ValueAnimator.ofFloat(0.4f, 0.95f);
        va.setDuration(3400);
        va.setRepeatMode(ValueAnimator.REVERSE);
        va.setRepeatCount(ValueAnimator.INFINITE);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                mAlpha = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        va.start();
    }
}
