package com.pri.nfcdemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 发送时的星光粒子动效(自绘)。 */
public class ParticleView extends View {

    private static class P {
        float x, y, vx, vy, r, life;
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<P> mPs = new ArrayList<P>();
    private boolean mRunning;
    private long mLast;

    public ParticleView(Context c) {
        super(c);
    }

    /** 从 (cx, cy) 迸发 n 颗粒子。 */
    public void burst(float cx, float cy, int n) {
        mPs.clear();
        for (int i = 0; i < n; i++) {
            P p = new P();
            double a = Math.random() * Math.PI * 2;
            double sp = 120 + Math.random() * 480;
            p.x = cx;
            p.y = cy;
            p.vx = (float) Math.cos(a) * (float) sp;
            p.vy = (float) Math.sin(a) * (float) sp - 140;
            p.r = 2 + (float) Math.random() * 4.5f;
            p.life = 0.6f + (float) Math.random() * 0.9f;
            mPs.add(p);
        }
        mRunning = true;
        mLast = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mRunning) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (mLast == 0) mLast = now;
        float dt = Math.min(0.05f, (now - mLast) / 1000f);
        mLast = now;
        boolean alive = false;
        for (P p : mPs) {
            if (p.life <= 0) continue;
            alive = true;
            p.life -= dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vy += 280 * dt;
            mPaint.setColor(Color.WHITE);
            mPaint.setAlpha((int) (255 * Math.max(0f, Math.min(1f, p.life))));
            canvas.drawCircle(p.x, p.y, p.r, mPaint);
        }
        if (alive) {
            postInvalidateOnAnimation();
        } else {
            mRunning = false;
        }
    }
}
