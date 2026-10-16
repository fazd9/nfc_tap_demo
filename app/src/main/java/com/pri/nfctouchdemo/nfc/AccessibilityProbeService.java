package com.pri.nfctouchdemo.nfc;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//pri added by zhuyang,Gallery watcher: tells the tap flow "which photos is the user looking at in Google Photos", so that,20261016,start
//pri added by zhuyang,phone-to-phone tap can send exactly those without the user sharing or re-picking anything,20261016
//pri added by zhuyang,,20261016
//pri added by zhuyang,It reads the accessibility tree of the gallery and hands GalleryPickup the two things the tree exposes:,20261016
//pri added by zhuyang,  * a full-screen photo being viewed  -> one target (its capture time, its bounds),20261016
//pri added by zhuyang,  * a grid with ticked tiles            -> one target per ticked tile,20261016
//pri added by zhuyang,One thing it deliberately does NOT expose is the file itself - the tree never names it (no view id on photo,20261016
//pri added by zhuyang,nodes, empty extras, no media key), only the capture time the gallery is displaying, to the minute. Turning,20261016
//pri added by zhuyang,that into an actual file is GalleryPickup's job (time filter into MediaStore + pixel tie-break),20261016
//pri added by zhuyang,,20261016
//pri added by zhuyang,The scan is driven by the tap (GalleryPickup.scanNow), not by accessibility events: nothing is cached, so,20261016
//pri added by zhuyang,there is no window in which a stale selection could be sent. The service only has to stay connected.,20261016
//pri added by zhuyang,Class name says "Probe" for historical reasons: it started life as a throwaway diagnostic,20261016,end
public class AccessibilityProbeService extends AccessibilityService {

    private static final String TAG = "A11yProbe";
    private static final int MAX_DEPTH = 40;
    private static final int MAX_NODES = 1500;
    /** Screen area above which a photo node counts as "the photo being viewed full screen" rather than a grid tile. */
    private static final float VIEWER_AREA_RATIO = 0.45f;

    private static final Pattern TAKEN = Pattern.compile(
            "拍摄于\\s*(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})");

    //pri added by zhuyang,The live instance, so GalleryPickup can ask for a fresh scan at tap time instead of trusting whatever,20261016
    //pri added by zhuyang,the last event happened to leave behind. Null while accessibility is off - the pickup then does nothing,20261016
    static volatile AccessibilityProbeService sInstance;

    //pri added by zhuyang,Deliberately does nothing on events: the gallery tree is read on demand by GalleryPickup at tap time,20261016
    //pri added by zhuyang,instead of being cached here, so there is no window in which a stale selection could be sent. The service,20261016
    //pri added by zhuyang,still has to be connected for scanNow() to reach the window, and the event types in the config are what,20261016
    //pri added by zhuyang,keep it bound,20261016
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    //pri added by zhuyang,Fresh scan of the window on screen right now. Callable from any thread - GalleryPickup runs it on its,20261016
    //pri added by zhuyang,own worker at tap time, which is what makes the answer "what the user is looking at at this instant",20261016
    public java.util.ArrayList<GalleryPickup.Target> scanNow() {
        java.util.ArrayList<GalleryPickup.Target> targets = new java.util.ArrayList<GalleryPickup.Target>();
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            Log.e(TAG, "取当前窗口失败: " + t);
            return targets;
        }
        if (root == null) {
            Log.e(TAG, "当前窗口不可读(getRootInActiveWindow 返回 null)");
            return targets;
        }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        try {
            scan(root, 0, targets, screen);
        } catch (Throwable t) {
            Log.e(TAG, "扫描相册节点树失败: " + t);
        } finally {
            try { root.recycle(); } catch (Throwable ignored) {}
        }
        Log.i(TAG, "相册扫描: " + targets.size() + " 个目标 " + targets);
        return targets;
    }

    //pri added by zhuyang,Walks the tree looking for photo nodes. Two shapes are recognised, and they are mutually exclusive in,20261016
    //pri added by zhuyang,practice: a photo covering most of the screen (the full-screen viewer) and small ticked tiles (multi-select),20261016
    private void scan(AccessibilityNodeInfo node, int depth, List<GalleryPickup.Target> out,
                      Rect screen) {
        if (node == null || depth > MAX_DEPTH || out.size() > 64) return;
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            long millis = parseTaken(desc.toString());
            if (millis > 0
                    && "android.widget.ImageView".contentEquals(String.valueOf(node.getClassName()))) {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                boolean big = screen.width() > 0 && screen.height() > 0
                        && ((float) b.width() * b.height()) / ((float) screen.width() * screen.height())
                        >= VIEWER_AREA_RATIO;
                //pri added by zhuyang,Full-screen viewer: the node is the photo, whether or not anything is ticked,20261016
                //pri added by zhuyang,Grid: only ticked tiles count - that is exactly "the user selected these",20261016
                if (big || node.isChecked()) {
                    out.add(new GalleryPickup.Target(millis, b));
                }
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) {
                scan(c, depth + 1, out, screen);
                try { c.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    //pri added by zhuyang,"拍摄于 2026年10月14日 16:48 的照片" -> local-time millis of that minute, 0 when the node is not a photo,20261016
    //pri added by zhuyang,The gallery labels a photo with the minute it displays for it, which is the file time when the photo has,20261016
    //pri added by zhuyang,no EXIF date of its own,20261016
    private static long parseTaken(String desc) {
        Matcher m = TAKEN.matcher(desc);
        if (!m.find()) return 0L;
        try {
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1,
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)));
            return c.getTimeInMillis();
        } catch (Throwable t) {
            return 0L;
        }
    }

    //pri added by zhuyang,Diagnostic sweep: dump every photo node of the gallery tree, scroll the grid forward one page,20261016,start
    //pri added by zhuyang,and dump again - repeated `steps` times, then scroll back to where the user left it. It answers two,20261016
    //pri added by zhuyang,questions that cannot be answered by reading the tree once: (1) is the multi-select state visible in the,20261016
    //pri added by zhuyang,tree at all (checked / selected / extras), (2) does a programmatic scroll bring the ticked tiles that are,20261016
    //pri added by zhuyang,scrolled off screen into the tree. Read only: it never clicks a photo, never opens a menu,20261016,end
    public void sweepAndDump(final int steps) {
        mScrollBack = 0;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        for (int i = 0; i <= steps; i++) {
            final int step = i;
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        dumpStep(step, steps);
                    } catch (Throwable t) {
                        Log.e(TAG, "扫描步 " + step + " 失败: " + t);
                    }
                    if (step == steps) restoreScroll();
                }
            }, (long) i * 900L);
        }
    }

    //pri added by zhuyang,Put the grid back where the user left it: the sweep scrolled the gallery, and the user must not be left,20261016
    //pri added by zhuyang,looking at a different part of their library because we read it,20261016
    private void restoreScroll() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        List<CharSequence> ignore = new ArrayList<CharSequence>();
        AccessibilityNodeInfo[] scrollable = new AccessibilityNodeInfo[1];
        collect(root, 0, ignore, scrollable, "none");
        if (scrollable[0] != null) {
            for (int i = 0; i < mScrollBack; i++) {
                scrollable[0].performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
        }
        Log.i(TAG, "扫描结束,已回滚 " + mScrollBack + " 次滚动");
        try { root.recycle(); } catch (Throwable ignored) {}
    }

    private void dumpStep(int step, int steps) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.e(TAG, "步 " + step + ": 当前窗口不可读");
            return;
        }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        List<CharSequence> lines = new ArrayList<CharSequence>();
        AccessibilityNodeInfo[] scrollable = new AccessibilityNodeInfo[1];
        collect(root, 0, lines, scrollable, "android.widget.ImageView");
        Log.i(TAG, "---- 步 " + step + "/" + steps + " 窗口=" + root.getPackageName()
                + " 可滚动节点=" + (scrollable[0] == null ? "无" : String.valueOf(scrollable[0].getClassName()))
                + " 图片节点=" + lines.size());
        for (CharSequence l : lines) Log.i(TAG, "     " + l);
        if (scrollable[0] != null && step < steps) {
            boolean ok = scrollable[0].performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            Log.i(TAG, "步 " + step + ": ACTION_SCROLL_FORWARD -> " + ok);
            if (ok) mScrollBack++;
        }
        try { root.recycle(); } catch (Throwable ignored) {}
    }

    private int mScrollBack = 0;

    //pri added by zhuyang,Walks the tree and describes every node of the wanted class: class, view id, content description,20261016
    //pri added by zhuyang,checkable/checked/selected, the extras keys and the bounds - the full set of attributes a service can see,20261016
    private void collect(AccessibilityNodeInfo node, int depth, List<CharSequence> out,
                         AccessibilityNodeInfo[] scrollable, String wantClass) {
        if (node == null || depth > MAX_DEPTH) return;
        String cls = String.valueOf(node.getClassName());
        if (node.isScrollable() && scrollable[0] == null) scrollable[0] = node;
        if (wantClass.equals(cls)) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            StringBuilder sb = new StringBuilder();
            sb.append("desc=").append(node.getContentDescription())
              .append(" viewId=").append(node.getViewIdResourceName())
              .append(" checkable=").append(node.isCheckable())
              .append(" checked=").append(node.isChecked())
              .append(" selected=").append(node.isSelected())
              .append(" clickable=").append(node.isClickable())
              .append(" bounds=").append(b.toShortString());
            android.os.Bundle ex = node.getExtras();
            if (ex != null && !ex.isEmpty()) sb.append(" extras=").append(ex.keySet());
            out.add(sb);
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) {
                collect(c, depth + 1, out, scrollable, wantClass);
                try { c.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    @Override public void onInterrupt() {}

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "相册监听服务已连接");
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        //pri added by zhuyang,Accessibility turned off: drop the instance so the pickup fails loudly instead of reading a dead window,20261016
        Log.i(TAG, "相册监听服务已断开");
        sInstance = null;
        return super.onUnbind(intent);
    }
}
