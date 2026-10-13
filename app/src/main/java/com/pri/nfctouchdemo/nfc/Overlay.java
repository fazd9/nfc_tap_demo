package com.pri.nfctouchdemo.nfc;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;

/** 悬浮窗管理器:确认页/粒子作为系统级悬浮窗显示(产品形态:服务 + 悬浮窗)。 */
public final class Overlay {

    private static Overlay sInstance;
    private final WindowManager mWm;

    private Overlay(Context c) {
        mWm = (WindowManager) c.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized Overlay get(Context c) {
        if (sInstance == null) sInstance = new Overlay(c);
        return sInstance;
    }

    private static int type() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** 显示全屏可触摸悬浮层(确认页)。 */
    public boolean show(View v) {
        try {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            if (Build.VERSION.SDK_INT >= 30) {
                lp.setFitInsetsTypes(0);
            }
            mWm.addView(v, lp);
            Bus.log("悬浮窗: 已显示 " + v.getClass().getSimpleName());
            return true;
        } catch (Throwable t) {
            Bus.log("悬浮窗显示失败(检查悬浮窗权限): " + t);
            return false;
        }
    }

    /** 显示不拦截触摸的覆盖层(粒子动效)。 */
    public boolean showPassThrough(View v) {
        try {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            if (Build.VERSION.SDK_INT >= 30) {
                lp.setFitInsetsTypes(0);
            }
            mWm.addView(v, lp);
            return true;
        } catch (Throwable t) {
            Bus.log("悬浮窗(粒子)显示失败: " + t);
            return false;
        }
    }

    public void hide(View v) {
        try {
            if (v != null && v.getParent() != null) {
                mWm.removeViewImmediate(v);
            }
        } catch (Throwable ignored) {}
    }
}
