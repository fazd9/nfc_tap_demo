package com.pri.nfctouchdemo.nfc;

import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.IAssistDataReceiver;
import android.app.assist.AssistContent;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

//pri added by zhuyang,AssistContent probe: answers one question on a real device - does the gallery hand out the,20261016,start
//pri added by zhuyang,photo it is currently showing? If yes, the NFC flow can pick up the photo the user is looking at,20261016
//pri added by zhuyang,without depending on the gallery cooperating with us (the gallery here is Google Photos, which we,20261016
//pri added by zhuyang,cannot modify). Background check on the permission gates, all verified in this ROM's source:20261016
//pri added by zhuyang,  GET_TOP_ACTIVITY_INFO = signature|recents -> a platform-signed app can hold it,20261016
//pri added by zhuyang,  getFocusedRootTaskInfo() -> enforceTaskPermission(MANAGE_ACTIVITY_TASKS); uid 1000 passes,20261016
//pri added by zhuyang,  OP_ASSIST_STRUCTURE -> MODE_ALLOWED by default, and the server calls AssistDataRequester with,20261016
//pri added by zhuyang,  ignoreFocusCheck = true,20261016
//pri added by zhuyang,TWO TRAPS this code works around:,20261016
//pri added by zhuyang,  1. ActivityTaskManager only has getInstance()/getService(); the two methods live on,20261016
//pri added by zhuyang,     IActivityTaskManager, and the call is ASYNCHRONOUS (result arrives via IAssistDataReceiver),20261016
//pri added by zhuyang,  2. the header jar is OLDER than this ROM: the jar's requestAssistDataForTask takes 3 params, the,20261016
//pri added by zhuyang,     ROM implements 5 - so the overload is resolved by parameter count at runtime,20261016
//pri added by zhuyang,This is throwaway diagnostics; remove it and the ASSIST_PROBE branch in TapService when done,20261016,end
public final class AssistProbe {

    private static final String TAG = "AssistProbe";
    private static final String PHOTOS_PKG = "com.google.android.apps.photos";

    //pri added by zhuyang,ActivityTaskManagerInternal.ASSIST_KEY_CONTENT = "content" (see VoiceInteractionSessionConnection),20261016
    private static final String KEY_ASSIST_CONTENT = "content";

    private AssistProbe() {}

    public static void run(Context context) {
        final Context app = context.getApplicationContext();
        try {
            IActivityTaskManager atm = ActivityTaskManager.getService();
            if (atm == null) {
                Log.e(TAG, "getService() 返回 null");
                return;
            }
            ActivityTaskManager.RootTaskInfo info = atm.getFocusedRootTaskInfo();
            if (info == null) {
                Log.e(TAG, "getFocusedRootTaskInfo 返回 null");
                return;
            }
            if (info.topActivity == null) {
                Log.e(TAG, "topActivity 为 null(桌面/系统界面?)");
                return;
            }
            String pkg = info.topActivity.getPackageName();
            Log.i(TAG, "栈顶应用 = " + pkg + " (taskId=" + info.taskId + ")");

            //pri added by zhuyang,Probe the top task's Intent extras before anything else: the LocalPhotosActivity intent,20261016
            //pri added by zhuyang,shows "(has extras)", and AssisContent came back empty - the photo uri may simply be sitting,20261016
            //pri added by zhuyang,in those extras, which getTasks(keepIntentExtra=true) is meant to surface,20261016
            //pri added by zhuyang,Signature in this ROM (IActivityTaskManager.aidl):20261016
            //pri added by zhuyang,  List<RunningTaskInfo> getTasks(int maxNum, boolean filterOnlyVisibleRecents,20261016
            //pri added by zhuyang,          boolean keepIntentExtra, int displayId)20261016
            probeTopTaskIntent(atm);
            if (!PHOTOS_PKG.equals(pkg)) {
                Log.i(TAG, "栈顶不是 Google 相册,探针只测相册,已停止");
                return;
            }

            IAssistDataReceiver receiver = new IAssistDataReceiver.Stub() {
                @Override public void onHandleAssistData(Bundle data) {
                    try {
                        dumpAssist(app, data);
                    } catch (Throwable t) {
                        Log.e(TAG, "解析 AssistContent 异常", t);
                    }
                }
                @Override public void onHandleAssistScreenshot(Bitmap bmp) {
                    Log.i(TAG, "附带截图回来了: "
                            + (bmp == null ? "null" : bmp.getWidth() + "x" + bmp.getHeight()));
                }
            };

            Method target = null;
            int count = 0;
            for (Method m : atm.getClass().getMethods()) {
                if (!"requestAssistDataForTask".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length > 1 && ps[0] == IAssistDataReceiver.class && ps[1] == int.class) {
                    target = m;
                    count = ps.length;
                    break;
                }
            }
            if (target == null) {
                target = IActivityTaskManager.class.getMethod(
                        "requestAssistDataForTask", IAssistDataReceiver.class, int.class, String.class);
                count = 3;
            }
            Log.i(TAG, "requestAssistDataForTask 参数个数 = " + count);
            boolean ok;
            if (count >= 5) {
                ok = (Boolean) target.invoke(atm, receiver, info.taskId, app.getPackageName(), null, true);
            } else {
                ok = (Boolean) target.invoke(atm, receiver, info.taskId, app.getPackageName());
            }
            Log.i(TAG, "请求已下发, 返回 = " + ok + " —— 数据是异步回调的,看下面的日志");
        } catch (Throwable t) {
            Log.e(TAG, "探针异常", t);
        }
    }

    //pri added by zhuyang,Ask for the top task WITH its Intent extras, and dump every extra - this is the most direct,20261016
    //pri added by zhuyang,place the "which photo" answer could live, since AssistContent came back empty,20261016
    //pri added by zhuyang,Reads extras by key rather than logging the whole Intent, because a Parcel with a Bitmap or a,20261016
    //pri added by zhuyang,large ClipData would blow up toString(); the key set is what tells us what is inside,20261016
    private static void probeTopTaskIntent(IActivityTaskManager atm) {
        try {
            Method m = null;
            for (Method cand : atm.getClass().getMethods()) {
                if (!"getTasks".equals(cand.getName())) continue;
                Class<?>[] ps = cand.getParameterTypes();
                if (ps.length == 4 && ps[0] == int.class && ps[1] == boolean.class
                        && ps[2] == boolean.class && ps[3] == int.class) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                Log.e(TAG, "getTasks(int,boolean,boolean,int) 不存在,这条查不了");
                return;
            }
            Object raw = m.invoke(atm, 5, false, true /* keepIntentExtra */, 0 /* displayId */);
            if (!(raw instanceof java.util.List)) {
                Log.e(TAG, "getTasks 返回了 " + raw);
                return;
            }
            java.util.List<?> tasks = (java.util.List<?>) raw;
            Log.i(TAG, "getTasks(keepIntentExtra=true) 返回 " + tasks.size() + " 个任务");
            for (Object o : tasks) {
                android.app.ActivityManager.RunningTaskInfo t =
                        (android.app.ActivityManager.RunningTaskInfo) o;
                Log.i(TAG, "--- taskId=" + t.taskId + " top=" + t.topActivity + " base=" + t.baseIntent + " ---");
                android.content.Intent bi = t.baseIntent;
                if (bi != null) {
                    Log.i(TAG, "  baseIntent action=" + bi.getAction() + " data=" + bi.getData()
                            + " type=" + bi.getType() + " comp=" + bi.getComponent());
                    Bundle ex = bi.getExtras();
                    if (ex == null) {
                        Log.i(TAG, "  baseIntent 没有 extras");
                    } else {
                        Log.i(TAG, "  baseIntent extras keys = " + ex.keySet());
                        for (String k : ex.keySet()) {
                            try {
                                Object v = ex.get(k);
                                Log.i(TAG, "    " + k + " = " + (v == null ? "null"
                                        : (v instanceof android.os.Parcelable
                                            ? "<" + v.getClass().getSimpleName() + "> " + v
                                            : String.valueOf(v))));
                            } catch (Throwable tt) {
                                Log.e(TAG, "    " + k + " 读取失败", tt);
                            }
                        }
                    }
                } else {
                    Log.i(TAG, "  baseIntent 为 null");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "getTasks 探测异常", t);
        }
    }

    //pri added by zhuyang,Dump an Intent's extras safely: an extra can be a Bitmap, a huge Parcelable or a ClipData,20261016
    //pri added by zhuyang,whose toString can be enormous or throw, so each value is described by type first and only,20261016
    //pri added by zhuyang,rendered when it is provably small - and every read is individually wrapped,20261016
    //pri added by zhuyang,Uri / ClipData / String / numbers are exactly what we are looking for, so those are spelled out,20261016
    private static void dumpIntentExtras(String label, android.content.Intent intent) {
        if (intent == null) {
            Log.i(TAG, "  [" + label + "] intent 为 null");
            return;
        }
        Log.i(TAG, "  [" + label + "] action=" + intent.getAction()
                + " data=" + intent.getData()
                + " type=" + intent.getType()
                + " flags=0x" + Integer.toHexString(intent.getFlags())
                + " clipData=" + (intent.getClipData() == null ? "null"
                    : intent.getClipData().getItemCount() + " 项"));
        android.content.ClipData ic = intent.getClipData();
        if (ic != null) {
            for (int i = 0; i < ic.getItemCount(); i++) {
                Log.i(TAG, "    intent.clipData[" + i + "] uri=" + ic.getItemAt(i).getUri());
            }
        }
        Bundle ex;
        try {
            ex = intent.getExtras();
        } catch (Throwable t) {
            Log.e(TAG, "  [" + label + "] getExtras 抛异常", t);
            return;
        }
        if (ex == null) {
            Log.i(TAG, "  [" + label + "] 没有 extras");
            return;
        }
        //pri added by zhuyang,getExtras can return a bundle whose classloader cannot materialise the app's own parcelables,20261016
        //pri added by zhuyang,xflg=0x4 means the creator package was recorded, so try that loader before iterating,20261016
        try {
            ex.size();
        } catch (Throwable t) {
            Log.e(TAG, "  [" + label + "] extras.size() 抛异常(可能需要 packages 可见性)", t);
            return;
        }
        Log.i(TAG, "  [" + label + "] extras keys = " + ex.keySet());
        for (String k : ex.keySet()) {
            try {
                Object v = ex.get(k);
                if (v == null) {
                    Log.i(TAG, "    " + k + " = null");
                } else if (v instanceof Uri) {
                    Log.i(TAG, "    " + k + " = <Uri> " + v);
                } else if (v instanceof String) {
                    Log.i(TAG, "    " + k + " = <String> " + v);
                } else if (v instanceof Number || v instanceof Boolean) {
                    Log.i(TAG, "    " + k + " = <" + v.getClass().getSimpleName() + "> " + v);
                } else if (v instanceof java.util.ArrayList) {
                    java.util.ArrayList<?> list = (java.util.ArrayList<?>) v;
                    Log.i(TAG, "    " + k + " = <ArrayList> 大小 " + list.size());
                    for (int i = 0; i < list.size() && i < 20; i++) {
                        Object it = list.get(i);
                        Log.i(TAG, "      [" + i + "] " + (it == null ? "null"
                                : "<" + it.getClass().getSimpleName() + "> " + it));
                    }
                } else if (v instanceof android.os.Parcelable) {
                    Log.i(TAG, "    " + k + " = <" + v.getClass().getName() + "> " + v);
                } else if (v instanceof android.os.Parcelable[]) {
                    android.os.Parcelable[] arr = (android.os.Parcelable[]) v;
                    Log.i(TAG, "    " + k + " = <Parcelable[]> 长度 " + arr.length);
                    for (int i = 0; i < arr.length; i++) {
                        Log.i(TAG, "      [" + i + "] " + arr[i]);
                    }
                } else {
                    Log.i(TAG, "    " + k + " = <" + v.getClass().getName() + ">");
                }
            } catch (Throwable t) {
                Log.e(TAG, "    " + k + " 读取失败", t);
            }
        }
    }

    //pri added by zhuyang,Give a Bundle the class loader of the app that produced it, so its own Parcelables can be,20261016
    //pri added by zhuyang,deserialised here. Without this every read throws ClassNotFoundException (obfuscated short names,20261016
    //pri added by zhuyang,like nrn/vwv, live only in Google Photos' dex),20261016
    //pri added by zhuyang,createPackageContext with INCLUDE_CODE is the documented route; it is logged so we can see whether,20261016
    //pri added by zhuyang,it worked or was refused,20261016
    private static ClassLoader foreignLoader(Context app, String pkg) {
        try {
            Context other = app.createPackageContext(pkg,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader cl = other.getClassLoader();
            Log.i(TAG, "拿到 " + pkg + " 的 ClassLoader(createPackageContext): " + cl);
            return cl;
        } catch (Throwable t) {
            Log.e(TAG, "createPackageContext 失败", t);
            return null;
        }
    }

    //pri added by zhuyang,Expand a nested Bundle that arrived as an unparcelled Parcel - as long as its contents are,20261016
    //pri added by zhuyang,primitives / String / Uri / nested Bundle it can be read here; if it holds Google Photos' own,20261016
    //pri added by zhuyang,Parcelables the read throws and we log which key failed rather than losing the whole dump,20261016
    //pri added by zhuyang,Reading one key at a time also matters: getBundle() on a bad classloader poisons the whole Bundle,20261016
    private static void expandBundle(String label, Bundle b) {
        if (b == null) {
            Log.i(TAG, "  [" + label + "] 为 null(没有这个 extra)");
            return;
        }
        int size;
        try {
            //pri added by zhuyang,size() is what forces the unparcel; it throwing means the bundle is opaque to us,20261016
            size = b.size();
        } catch (Throwable t) {
            Log.e(TAG, "  [" + label + "] size() 抛异常 —— 内容需要 Google Photos 的类加载器,读不了", t);
            return;
        }
        Log.i(TAG, "  [" + label + "] 有 " + size + " 个键");
        for (String k : b.keySet()) {
            try {
                Object v = b.get(k);
                if (v == null) {
                    Log.i(TAG, "    " + k + " = null");
                } else if (v instanceof Bundle) {
                    Log.i(TAG, "    " + k + " = <Bundle> 递归展开:");
                    expandBundle(label + "." + k, (Bundle) v);
                } else if (v instanceof Uri) {
                    Log.i(TAG, "    " + k + " = <Uri> " + v);
                } else if (v instanceof String) {
                    Log.i(TAG, "    " + k + " = <String> " + v);
                } else if (v instanceof Number || v instanceof Boolean) {
                    Log.i(TAG, "    " + k + " = <" + v.getClass().getSimpleName() + "> " + v);
                } else if (v instanceof java.util.Collection) {
                    java.util.Collection<?> col = (java.util.Collection<?>) v;
                    Log.i(TAG, "    " + k + " = <" + v.getClass().getSimpleName() + "> 大小 " + col.size());
                    int i = 0;
                    for (Object it : col) {
                        if (i++ >= 30) { Log.i(TAG, "      … 还有更多"); break; }
                        Log.i(TAG, "      [" + i + "] " + describe(it));
                    }
                } else if (v.getClass().isArray()) {
                    int n = java.lang.reflect.Array.getLength(v);
                    Log.i(TAG, "    " + k + " = <" + v.getClass().getSimpleName() + "> 长度 " + n);
                    for (int i = 0; i < n && i < 30; i++) {
                        Log.i(TAG, "      [" + i + "] " + describe(java.lang.reflect.Array.get(v, i)));
                    }
                } else {
                    Log.i(TAG, "    " + k + " = <" + v.getClass().getName() + "> " + safeToString(v));
                }
            } catch (Throwable t) {
                Log.e(TAG, "    " + k + " 读取失败(可能是别的应用的 Parcelable)", t);
            }
        }
    }

    private static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof Uri) return "<Uri> " + o;
        if (o instanceof String) return "<String> " + o;
        if (o instanceof Number || o instanceof Boolean) return "<" + o.getClass().getSimpleName() + "> " + o;
        return "<" + o.getClass().getName() + "> " + safeToString(o);
    }

    private static String safeToString(Object o) {
        try {
            return String.valueOf(o);
        } catch (Throwable t) {
            return "(toString 抛异常: " + t + ")";
        }
    }

    //pri added by zhuyang,Named dumpAssist, not dump: the anonymous Stub extends Binder, whose dump(FileDescriptor,20261016
    //pri added by zhuyang,String[]) would shadow a method called dump and make the call resolve to it at compile time,20261016
    private static void dumpAssist(Context app, Bundle data) {
        if (data == null) {
            Log.e(TAG, "回调的 Bundle 为 null");
            return;
        }
        Log.i(TAG, "=== Bundle keys = " + data.keySet() + " ===");

        AssistContent content = null;
        try {
            content = data.getParcelable(KEY_ASSIST_CONTENT, AssistContent.class);
        } catch (Throwable t) {
            Log.e(TAG, "带类型的 getParcelable 失败,退回无类型读取", t);
            Object raw = data.getParcelable(KEY_ASSIST_CONTENT);
            if (raw instanceof AssistContent) content = (AssistContent) raw;
        }
        if (content == null) {
            Log.e(TAG, "❌ 没有 AssistContent —— 相册没有交出当前内容");
            return;
        }
        Log.i(TAG, "✅ 拿到 AssistContent");
        Log.i(TAG, "  intent     = " + content.getIntent());
        Log.i(TAG, "  webUri     = " + content.getWebUri());
        Log.i(TAG, "  structured = " + content.getStructuredData());

        //pri added by zhuyang,The intent inside AssistContent prints as "(has extras)" but AssistContent exposes no,20261016
        //pri added by zhuyang,ClipData - so dump that intent's extras key by key; the photo uri (or the selected set) is,20261016
        //pri added by zhuyang,the most likely thing hiding in there,20261016
        dumpIntentExtras("AssistContent.getIntent", content.getIntent());

        //pri added by zhuyang,The extras contained media_collection_bundle and query_options_bundle - names that say "which,20261016
        //pri added by zhuyang,media", and a collection can hold one photo or a multi-selection; expand them,20261016
        android.content.Intent ci = content.getIntent();
        if (ci != null) {
            Bundle cex = ci.getExtras();
            if (cex != null) {
                //pri added by zhuyang,The values are Google Photos' own Parcelables (obfuscated names like nrn / vwv), so the,20261016
                //pri added by zhuyang,Bundle must be read with THAT app's class loader or every read throws
                //pri added by zhuyang,ClassNotFoundException - hand it the loader before touching any value,20261016
                ClassLoader foreign = foreignLoader(app, PHOTOS_PKG);
                if (foreign != null) {
                    cex.setClassLoader(foreign);
                    Log.i(TAG, "已给 extras Bundle 设置外部 ClassLoader");
                }
                Bundle mcb = cex.getBundle("com.google.android.apps.photos.localmedia.ui.localphotosactivity.media_collection_bundle");
                Bundle qob = cex.getBundle("com.google.android.apps.photos.localmedia.ui.localphotosactivity.query_options_bundle");
                if (foreign != null) {
                    if (mcb != null) mcb.setClassLoader(foreign);
                    if (qob != null) qob.setClassLoader(foreign);
                }
                expandBundle("media_collection_bundle", mcb);
                expandBundle("query_options_bundle", qob);
            }
        }

        ClipData clip = content.getClipData();
        if (clip == null || clip.getItemCount() == 0) {
            Log.e(TAG, "❌ AssistContent 里没有 ClipData —— 拿不到图片 Uri");
            return;
        }
        Log.i(TAG, "✅ ClipData 有 " + clip.getItemCount() + " 项");
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri u = clip.getItemAt(i).getUri();
            Log.i(TAG, "  [" + i + "] uri=" + u
                    + " mime=" + (u == null ? "-" : app.getContentResolver().getType(u)));
            if (u == null) continue;
            InputStream in = null;
            try {
                in = app.getContentResolver().openInputStream(u);
                if (in == null) {
                    Log.e(TAG, "  [" + i + "] ❌ openInputStream 返回 null");
                } else {
                    byte[] head = new byte[8];
                    int n = in.read(head);
                    Log.i(TAG, "  [" + i + "] ✅ 能读出图流,读了 " + n + " 字节,头 4 字节 = "
                            + (n >= 4 ? String.format("%02X %02X %02X %02X", head[0], head[1], head[2], head[3]) : "-"));
                }
            } catch (Throwable t) {
                Log.e(TAG, "  [" + i + "] ❌ 打开图流失败(可能缺权限)", t);
            } finally {
                if (in != null) {
                    try { in.close(); } catch (IOException ignored) {}
                }
            }
        }
    }
}
