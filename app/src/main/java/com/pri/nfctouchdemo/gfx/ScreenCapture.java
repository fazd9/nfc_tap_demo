package com.pri.nfctouchdemo.gfx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

//pri added by zhuyang,Silent screen-capture backdrop, ported from the in-house reference LongShotScreen,20261014,start
//pri added by zhuyang,Reference entry: FrameworkUtils.createScreenshot -> (SDK_INT >= UPSIDE_DOWN_CAKE) createScreenshotU ->,20261014
//pri added by zhuyang,FrameworkReflectUtils.createScreenshotU(windowManagerService, Display.DEFAULT_DISPLAY, crop),20261014
//pri added by zhuyang,The capture goes straight to WindowManagerService, so nothing is saved into the gallery and no preview UI,20261014
//pri added by zhuyang,is raised - the old ScreenshotHelper route went through SystemUI and did both,20261014
public final class ScreenCapture {

    private static final String TAG = "ScreenCapture";

    //pri added by zhuyang,Android 16+ classes of the silent path; none of them is in systemlibs/framework-header.jar nor in the,20261014
    //pri added by zhuyang,public SDK, so every member below is looked up reflectively (the reference does the same),20261014
    //pri added by zhuyang,Name for this ROM: Android 16 merged ScreenCaptureInternal into android.window.ScreenCapture - only the latter,20261014
    //pri added by zhuyang,is in our framework.jar, the old ScreenCaptureInternal name is what threw ClassNotFoundException on device,20261014
    private static final String SCREEN_CAPTURE_CLASS = "android.window.ScreenCapture";
    private static final String WINDOW_MANAGER_GLOBAL_CLASS = "android.view.WindowManagerGlobal";
    private static final String WINDOW_MANAGER_INTERFACE = "android.view.IWindowManager";

    //pri added by zhuyang,Cache file handed to the animation layer as the backdrop Uri (same pattern as TapService.peer_preview.jpg),20261014
    //pri added by zhuyang,Each capture gets a fresh file name: the animation layer keys its decoded bitmaps on the Uri and would,20261014
    //pri added by zhuyang,otherwise replay the previous round's screenshot out of its cache when the same photo is sent twice,20261014
    private static final String CACHE_PREFIX = "screen_backdrop_";

    private ScreenCapture() {}

    //pri added by zhuyang,Every framework type below is resolved through the system class loader, never Class.forName(String): the,20261014
    //pri added by zhuyang,single-argument form resolves with the caller's loader, whose DexPathList lists only our own APK, so a lookup,20261014
    //pri added by zhuyang,that cannot be delegated reports "Didn't find class ... on path: DexPathList[[zip file ".../base.apk"]]" - a,20261014
    //pri added by zhuyang,message that hides whether the class exists on the boot classpath (/system/framework/framework.jar) at all,20261014
    //pri added by zhuyang,(that wording is what led the previous attempt to a wrong class-loading diagnosis; do not go back to the 1-arg form),20261014
    //pri added by zhuyang,initialize=false because we only need the type and never want the lookup to trigger its static initialisers,20261014
    private static Class<?> systemClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ClassLoader.getSystemClassLoader());
    }

    //pri added by zhuyang,Captures the current screen and returns it as a file:// Uri ready to be the overlay backdrop,20261014
    //pri added by zhuyang,BLOCKING: the capture itself is synchronous (the framework listener waits up to 4s), so it must NOT run,20261014
    //pri added by zhuyang,on the main thread; run it on a throwaway thread and give up on the result after timeoutMs,20261014
    //pri added by zhuyang,Returns null on every failure path (WMS handle, builder, captureDisplay, null buffer, asBitmap, encode),20261014
    public static Uri captureBackdrop(Context context, long timeoutMs) {
        final Context app = context.getApplicationContext();
        Log.i(TAG, "开始截屏取背景,最多等 " + timeoutMs + "ms");
        FutureTask<Uri> task = new FutureTask<Uri>(new Callable<Uri>() {
            @Override public Uri call() { return captureDisplayToCache(app); }
        });
        new Thread(task, "screen-capture").start();
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.e(TAG, "截屏超时(" + timeoutMs + "ms 内未回执),背景回退照片");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "截屏等待被中断,背景回退照片");
        } catch (ExecutionException e) {
            Log.e(TAG, "截屏异常,背景回退照片", e);
        }
        return null;
    }

    //pri added by zhuyang,The silent capture, step by step:,20261014
    //pri added by zhuyang,WindowManagerGlobal.getWindowManagerService().captureDisplay(DEFAULT_DISPLAY, CaptureArgs, listener),20261014
    //pri added by zhuyang,then syncListener.getBuffer() -> ScreenshotHardwareBuffer -> asBitmap. No display token involved - this,20261014
    //pri added by zhuyang,path takes a display id (the SurfaceControl version the old branch used is what needed the token),20261014
    private static Uri captureDisplayToCache(Context app) {
        Bitmap screenShot = null;
        try {
            //pri added by zhuyang,Full-screen crop of the default display, taken from WindowManager (not hardcoded); this path only,20261014
            //pri added by zhuyang,exists on Android 16+, where getMaximumWindowMetrics (API 30) is always available,20261014
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                Log.e(TAG, "截屏失败:拿不到 WindowManager,背景回退照片");
                return null;
            }
            Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            int width = bounds.width();
            int height = bounds.height();
            if (width <= 0 || height <= 0) {
                Log.e(TAG, "截屏失败:屏幕尺寸异常 " + width + "x" + height + ",背景回退照片");
                return null;
            }

            Object wms = systemClass(WINDOW_MANAGER_GLOBAL_CLASS)
                    .getMethod("getWindowManagerService").invoke(null);
            if (wms == null) {
                Log.e(TAG, "截屏失败:WindowManagerGlobal.getWindowManagerService 返回空,背景回退照片");
                return null;
            }

            Class<?> captureArgsClass = systemClass(SCREEN_CAPTURE_CLASS + "$CaptureArgs");
            Class<?> listenerClass = systemClass(SCREEN_CAPTURE_CLASS + "$ScreenCaptureListener");
            //pri added by zhuyang,Look the listener/buffer members up on these public classes, not on getClass(): the concrete,20261014
            //pri added by zhuyang,listener is a package-private anonymous subclass, which invoke() could not reach,20261014
            Class<?> syncListenerClass = systemClass(SCREEN_CAPTURE_CLASS + "$SynchronousScreenCaptureListener");
            Class<?> bufferClass = systemClass(SCREEN_CAPTURE_CLASS + "$ScreenshotHardwareBuffer");
            Class<?> builderClass = systemClass(SCREEN_CAPTURE_CLASS + "$CaptureArgs$Builder");

            Constructor<?> builderCtor = builderClass.getDeclaredConstructor();
            Object builder = builderCtor.newInstance();
            if (builder == null) {
                Log.e(TAG, "截屏失败:CaptureArgs.Builder 构造失败,背景回退照片");
                return null;
            }
            builderClass.getMethod("setSourceCrop", Rect.class)
                    .invoke(builder, new Rect(0, 0, width, height));
            Object captureArgs = builderClass.getMethod("build").invoke(builder);
            if (captureArgs == null) {
                Log.e(TAG, "截屏失败:CaptureArgs.build 返回空,背景回退照片");
                return null;
            }

            Object syncListener = systemClass(SCREEN_CAPTURE_CLASS)
                    .getMethod("createSyncCaptureListener").invoke(null);
            if (syncListener == null) {
                Log.e(TAG, "截屏失败:createSyncCaptureListener 返回空,背景回退照片");
                return null;
            }

            Object buffer;
            try {
                //pri added by zhuyang,One-way binder call into system_server; getBuffer() then blocks until the result arrives,20261014
                systemClass(WINDOW_MANAGER_INTERFACE)
                        .getMethod("captureDisplay", int.class, captureArgsClass, listenerClass)
                        .invoke(wms, Display.DEFAULT_DISPLAY, captureArgs, syncListener);
                buffer = syncListenerClass.getMethod("getBuffer").invoke(syncListener);
            } catch (Throwable t) {
                Log.e(TAG, "截屏失败:captureDisplay 调用异常,背景回退照片", t);
                return null;
            }
            if (buffer == null) {
                Log.e(TAG, "截屏失败:captureDisplay 无回执(系统侧拒绝或等待超时),背景回退照片");
                return null;
            }

            Object asBitmap = bufferClass.getMethod("asBitmap").invoke(buffer);
            if (!(asBitmap instanceof Bitmap)) {
                Log.e(TAG, "截屏失败:asBitmap 返回空或类型异常,背景回退照片");
                return null;
            }
            //pri added by zhuyang,The wrapped Bitmap is hardware backed; copy the pixels into a software Bitmap (the documented,20261014
            //pri added by zhuyang,recipe) because JPEG encoding needs to read them back on the CPU,20261014
            Bitmap hwBitmap = (Bitmap) asBitmap;
            screenShot = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
            hwBitmap.recycle();
            //pri added by zhuyang,The gralloc buffer is ours to release (the Bitmap kept its own reference),20261014
            closeHardwareBuffer(bufferClass, buffer);
            if (screenShot == null) {
                Log.e(TAG, "截屏失败:转软件位图失败,背景回退照片");
                return null;
            }
            return writeCache(app, screenShot);
        } catch (Throwable t) {
            Log.e(TAG, "截屏失败:静默截屏通道异常,背景回退照片", t);
            return null;
        } finally {
            if (screenShot != null) screenShot.recycle();
        }
    }

    //pri added by zhuyang,Closing the captured hardware buffer is the caller's job (Bitmap.wrapHardwareBuffer only holds its own,20261014
    //pri added by zhuyang,reference; same cleanup as AOSP's snapshot consumers); a failure here must not lose the capture,20261014
    private static void closeHardwareBuffer(Class<?> bufferClass, Object screenshotBuffer) {
        try {
            Object hb = bufferClass.getMethod("getHardwareBuffer").invoke(screenshotBuffer);
            if (hb instanceof HardwareBuffer) ((HardwareBuffer) hb).close();
        } catch (Throwable t) {
            Log.e(TAG, "截屏缓冲释放失败(不影响本次截屏)", t);
        }
    }

    //pri added by zhuyang,Mirrors TapService.previewUriOrNull(): the captured image becomes a cache JPEG the animation layer can read,20261014
    private static Uri writeCache(Context app, Bitmap bmp) {
        try {
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
//pri added by zhuyang,Silent screen-capture backdrop, ported from the in-house reference LongShotScreen,20261014,end
