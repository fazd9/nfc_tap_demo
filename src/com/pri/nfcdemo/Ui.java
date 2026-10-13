package com.pri.nfcdemo;

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
}
