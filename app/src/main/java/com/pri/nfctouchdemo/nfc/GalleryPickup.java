package com.pri.nfctouchdemo.nfc;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

//pri added by zhuyang,Gallery pickup: work out *which photos the user is looking at in Google Photos* without the user,20261016,start
//pri added by zhuyang,sharing anything, so a phone-to-phone tap can send exactly those photos (reference product behaviour),20261016
//pri added by zhuyang,,20261016
//pri added by zhuyang,The gallery never tells us the file. What it does tell us, through the accessibility tree, is:,20261016
//pri added by zhuyang,  * the *capture time* it is displaying for each photo ("拍摄于 2026年10月14日 16:48 的照片") - minute precision,20261016
//pri added by zhuyang,  * where that photo sits on screen,20261016
//pri added by zhuyang,  * and, for a multi-selection, which tiles are ticked (isChecked),20261016
//pri added by zhuyang,That minute is not unique (several photos can share it, and our own received files share timestamps),20261016
//pri added by zhuyang,so the time is only a *filter* into MediaStore. The tie-break is the pixels: the tile is on screen, so we,20261016
//pri added by zhuyang,screencap it and compare it against the (few) candidates the filter left, by normalised cross-correlation,20261016
//pri added by zhuyang,of a small grayscale signature. Measured on device the correct file wins by a wide margin,20261016
//pri added by zhuyang,,20261016
//pri added by zhuyang,Both shapes are covered:,20261016
//pri added by zhuyang,  * single photo open full screen -> one target, the whole photo on screen,20261016
//pri added by zhuyang,  * multi-selection -> one target per ticked tile,20261016,end
public final class GalleryPickup {

    private static final String TAG = "GalleryPickup";

    /** How far either side of the displayed capture time a file may sit and still be a candidate. */
    private static final long WINDOW_MS = 120_000L;
    /** Second attempt when the narrow window finds nothing (photo whose shown time comes from EXIF, not from the file mtime). */
    private static final long WIDE_WINDOW_MS = 3_600_000L;
    /** Below this correlation the pixel match is not trusted; with a single candidate left the time alone decides. */
    private static final float MIN_SCORE = 0.30f;
    /** Side of the grayscale signature both sides are reduced to before they are compared. */
    private static final int SIG = 32;
    //pri added by zhuyang,The gallery draws a contained photo inside its tile, so the tile carries a few pixels of the gallery's,20261016
    //pri added by zhuyang,own background; each of these crops a bit further in and the best one is kept,20261016
    private static final float[] INSETS = {0.03f, 0.06f, 0.10f, 0.14f};
    /** Bounded so a pathological MediaStore can never stall the tap. */
    private static final int MAX_CANDIDATES = 400;

    /** One photo the user is looking at: when the gallery says it was taken, and where it is on screen. */
    public static final class Target {
        public final long millis;   //pri added by zhuyang,Local-time millis of the minute the gallery displays,20261016
        public final Rect bounds;   //pri added by zhuyang,Where the photo (or its tile) is, in screen pixels,20261016
        public Target(long millis, Rect bounds) { this.millis = millis; this.bounds = bounds; }
        @Override public String toString() { return fmt(millis) + "@" + bounds.toShortString(); }
    }

    private GalleryPickup() {}

    //pri added by zhuyang,Ask the platform who is on top instead of trusting the last accessibility event: a window-state event only,20261016,start
    //pri added by zhuyang,arrives when something changes, and after a process restart or a reinstall there may have been none,20261016
    //pri added by zhuyang,getFocusedRootTaskInfo needs MANAGE_ACTIVITY_TASKS, which uid 1000 holds,20261016
    //pri added by zhuyang,This is the cheap gate TapService uses before committing a round to the gallery path - a binder call, not a,20261016
    //pri added by zhuyang,screen capture,20261016
    public static boolean isGalleryForeground() {
        try {
            android.app.IActivityTaskManager atm = android.app.ActivityTaskManager.getService();
            if (atm == null) {
                Log.e(TAG, "取 ActivityTaskManager 失败,当作相册不在前台");
                return false;
            }
            android.app.ActivityTaskManager.RootTaskInfo info = atm.getFocusedRootTaskInfo();
            if (info == null || info.topActivity == null) {
                Log.i(TAG, "栈顶任务不可读(桌面/系统界面),当作相册不在前台");
                return false;
            }
            String pkg = info.topActivity.getPackageName();
            if (!"com.google.android.apps.photos".equals(pkg)) {
                Log.i(TAG, "栈顶是 " + pkg + ",不是 Google 相册");
                return false;
            }
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "查询栈顶应用失败: " + e);
            return false;
        }
    }
    //pri added by zhuyang,Ask the platform who is on top instead of trusting the last accessibility event: a window-state event only,20261016,end
    //pri added by zhuyang,Quiet variant for the NFC payload path: same probe with no log per call,20261020,start
    public static boolean isGalleryForegroundQuiet() {
        try {
            android.app.IActivityTaskManager atm = android.app.ActivityTaskManager.getService();
            if (atm == null) return false;
            android.app.ActivityTaskManager.RootTaskInfo info = atm.getFocusedRootTaskInfo();
            if (info == null || info.topActivity == null) return false;
            return "com.google.android.apps.photos".equals(info.topActivity.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }
    //pri added by zhuyang,Quiet variant for the NFC payload path: same probe with no log per call,20261020,end

    //pri added by zhuyang,Re-read the gallery tree right now through the accessibility service; empty when accessibility is off,20261016
    private static List<Target> scanNow() {
        AccessibilityProbeService svc = AccessibilityProbeService.sInstance;
        if (svc == null) {
            Log.e(TAG, "无障碍服务未连接,无法读取相册内容(设置里重新开启「一碰互联」无障碍即可)");
            return new ArrayList<Target>();
        }
        return svc.scanNow();
    }

    //pri added by zhuyang,Can the gallery be read at all right now? False means accessibility is off, which is a different,20261016
    //pri added by zhuyang,situation from "the user has picked nothing": in the first case we know nothing and must leave the,20261016
    //pri added by zhuyang,previous selection alone, in the second we know there is nothing to send,20261016
    public static boolean canReadGallery() {
        return AccessibilityProbeService.sInstance != null;
    }

    //pri added by zhuyang,The whole pickup, run at tap time: capture the screen once, then resolve every target against MediaStore,20261016
    //pri added by zhuyang,BLOCKING (the capture alone is a few hundred ms) - never call it from the main thread,20261016
    public static ArrayList<Uri> pickup(Context ctx) {
        ArrayList<Uri> out = new ArrayList<Uri>();
        if (!isGalleryForeground()) {
            Log.i(TAG, "栈顶不是 Google 相册,不做拾取");
            return out;
        }
        List<Target> targets = scanNow();
        if (targets.isEmpty()) {
            Log.i(TAG, "相册在前台,但当前既没有打开单张照片也没有勾选任何照片");
            return out;
        }
        Log.i(TAG, "开始拾取相册当前内容,目标 " + targets.size() + " 个: " + targets);
        Bitmap screen = com.pri.nfctouchdemo.gfx.ScreenCapture.captureBitmap(ctx, 1200L);
        if (screen == null) {
            Log.e(TAG, "截屏失败,无法做像素比对,本次拾取放弃");
            return out;
        }
        try {
            for (Target t : targets) {
                Uri u = resolveOne(ctx, screen, t);
                if (u != null && !out.contains(u)) out.add(u);
            }
        } finally {
            try { screen.recycle(); } catch (Throwable ignored) {}
        }
        Log.i(TAG, "拾取结束,得到 " + out.size() + " 张: " + out);
        return out;
    }

    private static Uri resolveOne(Context ctx, Bitmap screen, Target t) {
        Rect b = t.bounds;
        if (b == null || b.width() < 16 || b.height() < 16
                || b.left < 0 || b.top < 0 || b.right > screen.getWidth() || b.bottom > screen.getHeight()) {
            Log.e(TAG, "目标区域不可用,跳过: " + t);
            return null;
        }
        float[] tile = null;
        List<float[]> tileSigs = new ArrayList<float[]>();
        try {
            Bitmap crop = Bitmap.createBitmap(screen, b.left, b.top, b.width(), b.height());
            int side = Math.min(crop.getWidth(), crop.getHeight());
            for (float inset : INSETS) {
                tileSigs.add(signature(crop, inset));
            }
            crop.recycle();
        } catch (Throwable e) {
            Log.e(TAG, "截取屏幕上的照片区域失败: " + e);
            return null;
        }
        if (tileSigs.isEmpty()) return null;

        ArrayList<long[]> cands = queryCandidates(ctx, t.millis, WINDOW_MS);
        if (cands.isEmpty()) {
            Log.i(TAG, "±" + (WINDOW_MS / 1000) + "s 内没有候选,放宽到 ±" + (WIDE_WINDOW_MS / 3600000) + "h");
            cands = queryCandidates(ctx, t.millis, WIDE_WINDOW_MS);
        }
        if (cands.isEmpty()) {
            Log.e(TAG, "MediaStore 里没有时间接近的照片,跳过 " + t);
            return null;
        }
        Log.i(TAG, t + " 命中 " + cands.size() + " 个候选,开始像素比对");

        Uri bestUri = null;
        float bestScore = -2f;
        long bestDelta = Long.MAX_VALUE;
        for (long[] c : cands) {
            Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c[0]);
            Bitmap small = decodeSmall(ctx, uri, 128, (int) c[2], (int) c[3]);
            if (small == null) continue;
            float score;
            try {
                float[] candSig = signature(small, 0f);
                score = -2f;
                for (float[] ts : tileSigs) {
                    float s = ncc(ts, candSig);
                    if (s > score) score = s;
                }
            } finally {
                small.recycle();
            }
            long delta = Math.abs(c[1] - t.millis);
            Log.i(TAG, "   候选 " + uri + " 时间差=" + (delta / 1000) + "s 相关=" + score);
            //pri added by zhuyang,Identical photos (the same picture received twice, or a copy) score exactly the same; the one whose,20261016
            //pri added by zhuyang,file time actually matches what the gallery is showing is then the right one,20261016
            boolean better = score > bestScore + 1e-6f
                    || (Math.abs(score - bestScore) <= 1e-6f && delta < bestDelta);
            if (better) {
                bestScore = score; bestUri = uri; bestDelta = delta;
            }
        }
        if (bestUri == null) {
            Log.e(TAG, "候选全部解码失败,跳过 " + t);
            return null;
        }
        if (bestScore < MIN_SCORE && cands.size() > 1) {
            //pri added by zhuyang,Nothing matched well enough to pick between several candidates: better to send nothing than to send,20261016
            //pri added by zhuyang,the wrong photo,20261016
            Log.e(TAG, "像素比对不可信(最高相关 " + bestScore + " < " + MIN_SCORE + ",候选 " + cands.size() + " 个),跳过 " + t);
            return null;
        }
        Log.i(TAG, "选中 " + bestUri + "(相关 " + bestScore + ")");
        //pri added by zhuyang,Resolving the right file is only half the job - the transfer engine reads it through the,20261016
        //pri added by zhuyang,ContentResolver, so check that read really is allowed before this photo is announced to the peer,20261016
        if (!readable(ctx, bestUri)) {
            Log.e(TAG, "选中的照片读不出来(缺媒体读取权限?),本次拾取放弃");
            return null;
        }
        return bestUri;
    }

    //pri added by zhuyang,One-byte read: proves the transfer engine will be able to open the same Uri later,20261016
    private static boolean readable(Context ctx, Uri uri) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            return in.read() >= 0;
        } catch (Throwable e) {
            Log.e(TAG, "读取 " + uri + " 被拒绝: " + e);
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    //pri added by zhuyang,Candidates = images whose file time is within +/-window of the capture time the gallery is displaying,20261016
    //pri added by zhuyang,both date_modified (seconds) and datetaken (milliseconds) are considered because the gallery falls back to,20261016
    //pri added by zhuyang,the file time when a photo has no EXIF date - which is exactly the case for the photos we receive,20261016
    private static ArrayList<long[]> queryCandidates(Context ctx, long millis, long windowMs) {
        ArrayList<long[]> out = new ArrayList<long[]>();
        long lo = millis - windowMs, hi = millis + windowMs;
        String sel = "((" + MediaStore.Images.Media.DATE_MODIFIED + " BETWEEN ? AND ?)"
                + " OR (" + MediaStore.Images.Media.DATE_TAKEN + " BETWEEN ? AND ?))";
        String[] args = {
                String.valueOf(lo / 1000), String.valueOf(hi / 1000),
                String.valueOf(lo), String.valueOf(hi)
        };
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID,
                            MediaStore.Images.Media.DATE_MODIFIED,
                            MediaStore.Images.Media.DATE_TAKEN,
                            MediaStore.Images.Media.WIDTH,
                            MediaStore.Images.Media.HEIGHT},
                    sel, args,
                    MediaStore.Images.Media.DATE_MODIFIED + " DESC");
            if (c == null) return out;
            while (c.moveToNext() && out.size() < MAX_CANDIDATES) {
                long id = c.getLong(0);
                long dm = c.isNull(1) ? 0 : c.getLong(1) * 1000L;
                long dt = c.isNull(2) ? 0 : c.getLong(2);
                //pri added by zhuyang,Keep the timestamp that is actually inside the window; 0 means "no such date",20261016
                long use = (Math.abs(dm - millis) <= Math.abs(dt - millis)) ? dm : dt;
                if (use == 0) use = Math.max(dm, dt);
                //pri added by zhuyang,The stored dimensions let the decode pick its inSampleSize without a first pass over the file,20261016
                out.add(new long[]{id, use,
                        c.isNull(3) ? 0 : c.getInt(3), c.isNull(4) ? 0 : c.getInt(4)});
            }
        } catch (Throwable e) {
            Log.e(TAG, "查询 MediaStore 失败: " + e);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    //pri added by zhuyang,Small decode: inSampleSize is doubled until the short side is near the target, so a 12MP JPEG costs,20261016,start
    //pri added by zhuyang,the same as a thumbnail, and only the true downscale (not MediaStore's cached crop) is compared,20261016
    //pri added by zhuyang,w/h come from the MediaStore row when it has them, which saves the extra openInputStream that,20261016
    //pri added by zhuyang,inJustDecodeBounds would need - measured at roughly half the per-candidate cost,20261016,end
    private static Bitmap decodeSmall(Context ctx, Uri uri, int target, int w, int h) {
        try {
            if (w <= 0 || h <= 0) {
                //pri added by zhuyang,No stored dimensions (rare): fall back to a bounds-only pass over the header,20261016
                InputStream in = null;
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    in = ctx.getContentResolver().openInputStream(uri);
                    BitmapFactory.decodeStream(in, null, o);
                    w = o.outWidth; h = o.outHeight;
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) {}
                }
            }
            if (w <= 0 || h <= 0) return null;
            int scale = 1;
            while (Math.min(w, h) / (scale * 2) >= target) scale *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            InputStream in2 = ctx.getContentResolver().openInputStream(uri);
            try {
                return BitmapFactory.decodeStream(in2, null, o2);
            } finally {
                try { if (in2 != null) in2.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            Log.e(TAG, "解码失败 " + uri + ": " + e);
            return null;
        }
    }

    //pri added by zhuyang,Signature of the centre square of bmp, shrunk by inset, reduced to SIG x SIG grayscale and normalised,20261016
    //pri added by zhuyang,(zero mean, unit deviation) so that only structure is compared - brightness and scaling drop out,20261016
    private static float[] signature(Bitmap bmp, float inset) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int side = Math.min(w, h);
        int cut = (int) (side * inset);
        int s = side - cut * 2;
        if (s < 8) { s = Math.min(w, h); cut = 0; }
        int x0 = (w - s) / 2, y0 = (h - s) / 2;
        Bitmap sq = Bitmap.createBitmap(bmp, x0, y0, s, s);
        Bitmap small = Bitmap.createScaledBitmap(sq, SIG, SIG, true);
        if (small != sq) sq.recycle();
        int[] px = new int[SIG * SIG];
        small.getPixels(px, 0, SIG, 0, 0, SIG, SIG);
        small.recycle();
        float[] g = new float[px.length];
        double mean = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            g[i] = (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF));
            mean += g[i];
        }
        mean /= g.length;
        double var = 0;
        for (int i = 0; i < g.length; i++) { g[i] -= mean; var += g[i] * g[i]; }
        double sd = Math.sqrt(var / g.length);
        if (sd < 1e-6) return g;
        for (int i = 0; i < g.length; i++) g[i] /= (float) sd;
        return g;
    }

    /** Normalised cross-correlation of two zero-mean unit-deviation signatures. */
    private static float ncc(float[] a, float[] b) {
        if (a.length != b.length) return -2f;
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return (float) (s / a.length);
    }

    static String fmt(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }
}
