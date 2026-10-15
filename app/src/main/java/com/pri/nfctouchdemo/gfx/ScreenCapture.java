package com.pri.nfctouchdemo.gfx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.util.ScreenshotHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

//pri added by zhuyang,Screen-capture backdrop, ported from the in-house reference LongShotScreen,20261014,start
//pri added by zhuyang,Reference entry: ScreenshotUtils.takeScreenshot -> FrameworkUtils.takeScreenshot,20261014
//pri added by zhuyang,Reference impl: new ScreenshotHelper(context.getApplicationContext()) + takeScreenshot(helper,,20261014
//pri added by zhuyang,SCREENSHOT_ACCESSIBILITY_ACTIONS, new Handler(Looper.getMainLooper()), completionConsumer),20261014
//pri added by zhuyang,The reference calls it through FrameworkReflectUtils (its own build setup); the same call is reflective here because,20261014
//pri added by zhuyang,systemlibs/framework-header.jar and this ROM disagree on which overload exists,20261014
public final class ScreenCapture {

    private static final String TAG = "ScreenCapture";

    //pri added by zhuyang,Cache file handed to the animation layer as the backdrop Uri (same pattern as TapService.peer_preview.jpg),20261014
    //pri added by zhuyang,Each capture gets a fresh file name: the animation layer keys its decoded bitmaps on the Uri and would,20261014
    //pri added by zhuyang,otherwise replay the previous round's screenshot out of its cache when the same photo is sent twice,20261014
    private static final String CACHE_PREFIX = "screen_backdrop_";

    //pri added by zhuyang,Failure marker for the hand-off queue: a LinkedBlockingQueue rejects null elements,20261014
    private static final Uri FAILED = Uri.EMPTY;

    private ScreenCapture() {}

    //pri added by zhuyang,Captures the current screen and returns it as a file:// Uri ready to be the overlay backdrop,20261014
    //pri added by zhuyang,BLOCKING: waits up to timeoutMs for ScreenshotHelper's completion consumer, so it must NOT run on the main,20261014
    //pri added by zhuyang,thread - that consumer is delivered on the main looper and would deadlock against a main-thread wait,20261014
    //pri added by zhuyang,Returns null on every failure path (request, timeout, null Uri, decode); the caller then keeps the old backdrop,20261014
    public static Uri captureBackdrop(Context context, long timeoutMs) {
        final Context app = context.getApplicationContext();
        final LinkedBlockingQueue<Uri> box = new LinkedBlockingQueue<Uri>(1);
        //pri added by zhuyang,The reference passes a main-looper Handler; ScreenshotHelper builds its reply Messenger on that looper,20261014
        final Handler handler = new Handler(Looper.getMainLooper());
        final Consumer<Uri> consumer = new Consumer<Uri>() {
            @Override
            public void accept(Uri uri) {
                box.offer(uri == null ? FAILED : uri);
            }
        };
        Log.i(TAG, "开始截屏取背景,最多等 " + timeoutMs + "ms");
        try {
            takeScreenshot(new ScreenshotHelper(app), handler, consumer);
        } catch (Throwable t) {
            Log.e(TAG, "截屏请求下发失败,背景回退照片", t);
            return null;
        }
        Uri shot = null;
        try {
            Uri got = box.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (got != null && !FAILED.equals(got)) shot = got;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (shot == null) {
            Log.e(TAG, "截屏无结果(超时或系统未回执),背景回退照片");
            return null;
        }
        Log.i(TAG, "截屏完成: " + shot);
        return writeCache(app, shot);
    }

    //pri added by zhuyang,The two overloads the reference switches between: Android 14+ (source, handler, consumer) and the older,20261014
    //pri added by zhuyang,(type, hasStatus, hasNav, source, handler, consumer); found by name because systemlibs/framework-header.jar,20261014
    //pri added by zhuyang,still carries only the older one while this ROM has the newer one - a direct call would not even compile,20261014
    private static void takeScreenshot(ScreenshotHelper helper, Handler handler, Consumer<Uri> consumer)
            throws Exception {
        Method modern = find(helper, "takeScreenshot", int.class, Handler.class, Consumer.class);
        if (modern != null) {
            modern.invoke(helper, WindowManager.ScreenshotSource.SCREENSHOT_ACCESSIBILITY_ACTIONS,
                    handler, consumer);
            return;
        }
        Method legacy = find(helper, "takeScreenshot",
                int.class, boolean.class, boolean.class, int.class, Handler.class, Consumer.class);
        if (legacy == null) {
            throw new NoSuchMethodException("ScreenshotHelper.takeScreenshot");
        }
        legacy.invoke(helper, WindowManager.TAKE_SCREENSHOT_FULLSCREEN, true, true,
                WindowManager.ScreenshotSource.SCREENSHOT_ACCESSIBILITY_ACTIONS, handler, consumer);
    }

    private static Method find(Object target, String name, Class<?>... params) {
        try {
            return target.getClass().getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    //pri added by zhuyang,Mirrors TapService.previewUriOrNull(): the captured image becomes a cache JPEG the animation layer can read,20261014
    private static Uri writeCache(Context app, Uri shot) {
        InputStream in = null;
        Bitmap bmp = null;
        try {
            in = app.getContentResolver().openInputStream(shot);
            bmp = (in == null) ? null : BitmapFactory.decodeStream(in);
            if (bmp == null) {
                Log.e(TAG, "截屏读取/解码失败,背景回退照片: " + shot);
                return null;
            }
            File f = new File(app.getCacheDir(), CACHE_PREFIX + System.currentTimeMillis() + ".jpg");
            FileOutputStream os = new FileOutputStream(f);
            try {
                //pri added by zhuyang,Quality 90 like the preview file: the backdrop is blurred by BackdropBlur anyway,20261014
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
            } finally {
                try { os.close(); } catch (Throwable ignored) {}
            }
            dropOldCaches(app, f);
            Uri cache = Uri.fromFile(f);
            Log.i(TAG, "截屏已落盘: " + cache);
            return cache;
        } catch (Throwable t) {
            Log.e(TAG, "截屏落盘失败,背景回退照片", t);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
        }
    }

    //pri added by zhuyang,Keeps the cache dir to the one capture of the current round,20261014
    private static void dropOldCaches(Context app, File keep) {
        File[] olds = app.getCacheDir().listFiles();
        if (olds == null) return;
        for (File old : olds) {
            if (old.getName().startsWith(CACHE_PREFIX) && !old.equals(keep)) old.delete();
        }
    }
}
//pri added by zhuyang,Screen-capture backdrop, ported from the in-house reference LongShotScreen,20261014,end
