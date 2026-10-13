package com.pri.nfctouchdemo.nfc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

/** UI 小工具:dp、圆角占位图、安全异步加载图片(不用 setImageURI,避免无权限时布局阶段崩溃)。 */
public class Ui {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 深色渐变占位图(没有照片时用)。 */
    public static GradientDrawable placeholder(Context c, int radiusDp) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF9AA3B2, 0xFF5A6472});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** 圆角。 */
    public static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** 异步加载图片到 ImageView(带尺寸压缩,失败只打日志)。 */
    public static void loadImage(final Context act, final ImageView iv, final Uri uri, final int maxDim) {
        if (uri == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.InputStream in = act.getContentResolver().openInputStream(uri);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in, null, o);
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    int scale = 1;
                    while (o.outWidth / scale > maxDim || o.outHeight / scale > maxDim) scale *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = scale;
                    java.io.InputStream in2 = act.getContentResolver().openInputStream(uri);
                    final Bitmap bmp = BitmapFactory.decodeStream(in2, null, o2);
                    try { if (in2 != null) in2.close(); } catch (Exception ignored) {}
                    if (bmp != null) {
                        MAIN.post(new Runnable() {
                            @Override public void run() { iv.setImageBitmap(bmp); }
                        });
                    }
                } catch (Throwable t) {
                    Bus.log("图片加载失败(可能无读取权限): " + t);
                }
            }
        }).start();
    }

    /** 全屏大图查看器(支持多张:点左/右半屏切上一张/下一张,顶部显示 序号/总数)。 */
    public static android.widget.FrameLayout buildViewer(final Context ctx, final java.util.List<Bitmap> bmps) {
        final java.util.ArrayList<Bitmap> list = new java.util.ArrayList<Bitmap>();
        if (bmps != null) {
            for (Bitmap b : bmps) if (b != null) list.add(b);
        }

        final android.widget.FrameLayout root = new android.widget.FrameLayout(ctx);
        root.setBackgroundColor(0xFF000000);

        final ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(img, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        final android.widget.TextView idxView = new android.widget.TextView(ctx);
        idxView.setTextColor(0xCCFFFFFF);
        idxView.setTextSize(13);
        android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        ilp.topMargin = dp(ctx, 34);
        root.addView(idxView, ilp);

        final int[] cur = {0};
        final Runnable show = new Runnable() {
            @Override public void run() {
                if (list.isEmpty()) return;
                img.setImageBitmap(list.get(cur[0]));
                idxView.setText((cur[0] + 1) + "/" + list.size());
            }
        };
        show.run();

        if (list.size() > 1) { // 左右半屏点击切图(关闭按钮等由调用方后加,自然盖在上面)
            int half = ctx.getResources().getDisplayMetrics().widthPixels / 2;
            android.view.View prev = new android.view.View(ctx);
            prev.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    if (cur[0] > 0) { cur[0]--; show.run(); }
                }
            });
            android.view.View next = new android.view.View(ctx);
            next.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    if (cur[0] < list.size() - 1) { cur[0]++; show.run(); }
                }
            });
            root.addView(prev, new android.widget.FrameLayout.LayoutParams(
                    half, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            android.widget.FrameLayout.LayoutParams rp = new android.widget.FrameLayout.LayoutParams(
                    half, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            rp.gravity = android.view.Gravity.RIGHT;
            root.addView(next, rp);
        }
        return root;
    }
}
