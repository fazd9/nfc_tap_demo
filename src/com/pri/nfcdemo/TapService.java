package com.pri.nfcdemo;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;

/**
 * 常驻服务:承载"碰一碰"主流程(对齐产品视频)。
 *  - 发送方:相册把"当前选中照片"实时推给本服务;碰一碰时本服务**直接弹出发送确认悬浮窗**,
 *           上滑发送后悬浮窗消失 —— 用户全程停留在相册,前台应用不被打断。
 *  - 接收方:接收确认页由持有 reader 模式的 Activity 弹出(系统应用化后也移到服务)。
 */
public class TapService extends Service implements Bus.Listener, Xfer.Listener {

    private static Xfer sXfer;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private SendPage mSendPage;
    private String mPeerLabel = "--------";
    private volatile boolean mLinkReady;
    private volatile boolean mPreviewsSent;
    private ReceivePage mReceivePage;
    private Bitmap mLastBmp;
    private boolean mAccepted;
    private int mPreviewCount;
    private int mFullCount;
    private int mTotalCount;                                            // 对方预告的总张数
    private boolean mTransferDone;                                      // 对方发送完毕(连接关闭)
    private final ArrayList<Bitmap> mBmps = new ArrayList<Bitmap>();    // 本次收到的全部整图

    /** 相册推送过来的"当前选中照片"。 */
    private static final ArrayList<Uri> PENDING = new ArrayList<Uri>();

    /** 本机是否有选中的照片(=本机是发送方)。 */
    public static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    /** 全局唯一的传输器(Activity 与服务共用,避免重复建链)。 */
    public static Xfer xfer(Context c) {
        if (sXfer == null) {
            sXfer = new Xfer(c.getApplicationContext(), null);
        }
        return sXfer;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Bus.setServiceListener(this);
        Xfer x = xfer(this);
        x.addListener(this);
        x.warmUp();
        loadPending(); // 进程被回收后冷启:恢复上次选中的照片,避免碰触时误判成接收方
        Bus.log("服务: TapService 常驻中(承载碰一碰流程)");
        if (intent != null && "com.pri.nfcdemo.TAG".equals(intent.getAction())) {
            final android.nfc.Tag tag = intent.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG);
            if (tag != null) handleTag(tag);
        }
        if (intent != null && "com.pri.nfcdemo.PEER".equals(intent.getAction())) {
            // 卡侧(HCE)碰到的对方信息经 intent 转交:服务没跑时会被冷启,事件不会丢
            String payload = intent.getStringExtra("payload");
            Bus.log("服务: 收到碰触信息" + (payload == null ? "" : ",对方=" + payload));
            Bus.onPeerData(payload, intent.getStringExtra("role"));
        }
        if (intent != null && "com.pri.nfcdemo.DEBUG".equals(intent.getAction())) {
            // 调试:把 NFC 交换这一步用 adb 参数顶掉,其余流程走真实代码(免物理碰触做回归/演示)
            String payload = intent.getStringExtra("payload");
            Bus.log("调试: 模拟碰一碰,对方信息 = " + payload);
            Bus.onPeerData(payload, intent.getStringExtra("role"));
        }
        if (intent != null && Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null) {
                PENDING.clear();
                PENDING.addAll(uris);
                savePending();
                Bus.log("服务: 收到相册当前选中 " + PENDING.size() + " 张照片(等碰一碰)");
            }
        }
        return START_STICKY;
    }

    /** 选中照片持久化(URI 读取授权按 uid 保留,重启进程后仍可读)。 */
    private void savePending() {
        StringBuilder sb = new StringBuilder();
        for (Uri u : PENDING) {
            if (sb.length() > 0) sb.append('|');
            sb.append(u.toString());
        }
        getSharedPreferences("tapdemo", 0).edit().putString("pending", sb.toString()).apply();
    }

    private void loadPending() {
        if (!PENDING.isEmpty()) return;
        String s = getSharedPreferences("tapdemo", 0).getString("pending", "");
        if (s == null || s.isEmpty()) return;
        for (String p : s.split("\\|")) {
            if (!p.isEmpty()) PENDING.add(Uri.parse(p));
        }
        if (!PENDING.isEmpty()) Bus.log("服务: 恢复上次选中的 " + PENDING.size() + " 张照片");
    }

    // ---------- 碰一碰:发送方(卡方)收到读取 → 弹出发送确认页 ----------

    @Override public void onLog(String line) {}

    @Override
    public void onPeer(String payload, String role) {
        mLinkReady = false; // 新一轮:连接就绪状态重置
        // 角色只由"本机有没有选中照片"决定,与 NFC 的"读/被读"方向无关
        final String label = "用户" + (field(payload, "t") == null ? "--------" : field(payload, "t"));
        Xfer x = xfer(this);
        x.setExpectedPeer(field(payload,"n"), field(payload,"b"), field(payload,"p"),
                field(payload,"a"), field(payload,"ip"), field(payload,"hs"), field(payload,"hp"));
        x.startAsReceiver(); // 服务端先就绪
        if (!Bus.lockRole()) { Bus.log("角色已定,忽略重复的碰触事件"); return; }
        mPeerLabel = label;
        if (PENDING.isEmpty()) {
            Bus.log("本机无照片 → 本机是接收方");
            x.startHotspot(); // 接收方起本地热点(SSID/密码进 payload,对方下次碰可直接接入;Wi-Fi 开着会启失败并提示)
            mMain.post(new Runnable() { @Override public void run() { showReceivePage(label); } });
        } else {
            Bus.log("本机有照片 → 本机是发送方");
            x.startAsSender();
            // 发送页不在此处弹:热点接入时系统会弹「允许连接」确认框,而悬浮窗 z 序在其之上会把它挡住,
            // 改为连接就绪后再弹(onP2pReady);失败时由 onXferFailed 补弹并显示原因
        }
    }

    private void showSendPage() {
        if (mSendPage != null) return;
        mSendPage = new SendPage(this, new SendPage.Listener() {
            @Override public void onSwipedSend() {
                if (sXfer == null || PENDING.isEmpty()) return;
                if (!mLinkReady) {
                    mSendPage.setStatus("正在连接对方,请稍候…(就绪后再上滑)");
                    mSendPage.resetStack();
                    return;
                }
                sXfer.sendImages(new ArrayList<Uri>(PENDING));
            }
            @Override public void onClose() {
                Bus.unlockRole();
                dismissSendPage();
            }
        });
        mPreviewsSent = false;
        mSendPage.bind(PENDING, "发送给 " + mPeerLabel, PENDING.size());
        mSendPage.setStatus("正在连接对方…");
        mSendPage.root.setAlpha(0f);
        Overlay.get(this).show(mSendPage.root);
        mSendPage.root.animate().alpha(1f).setDuration(280).start();
    }

    private void dismissSendPage() {
        if (mSendPage != null) {
            mSendPage.dismiss();
            mSendPage = null;
        }
    }

    // ---------- Xfer 事件(仅发送侧处理) ----------

    @Override
    public void onP2pReady(boolean isGroupOwner, String goAddress) {
        mLinkReady = true;
        final boolean sender = !PENDING.isEmpty();
        mMain.post(new Runnable() { @Override public void run() { // 回到主线程再改 UI
            if (sender) {
                if (mSendPage == null) showSendPage(); // 连接就绪才弹(见 onPeer 里的说明)
                if (mSendPage != null) mSendPage.setStatus("已连接,上滑发送即可");
            } else if (mReceivePage != null) {
                mReceivePage.setStatus("已连接,等待接收…");
            }
        }});
        if (sender && sXfer != null) {
            synchronized (this) { // 原子防重:两个后台线程同时回调时也只推一次
                if (!mPreviewsSent) {
                    mPreviewsSent = true;
                    sXfer.sendPreviews(new ArrayList<Uri>(PENDING));
                }
            }
        }
    }

    @Override public void onPreview(Bitmap bmp) {
        if (mReceivePage != null && bmp != null) {
            mReceivePage.setPreview(bmp);
            mReceivePage.setPreviewCount(++mPreviewCount);
        }
    }

    @Override public void onImageCount(int total) {
        mTotalCount = total;
        if (mReceivePage != null) mReceivePage.setPreviewCount(total); // 文案用真实总张数,不再回退成 1
    }

    @Override public void onImageReceived(int index, int total, Bitmap bmp, int bytes, boolean saved) {
        mFullCount = index;
        mLastBmp = bmp;
        // 图片到达:必须经过"接收确认"这一步(没有接收页就先弹出来)
        if (mReceivePage == null) {
            dismissSendPage();
            showReceivePage(mPeerLabel);
        }
        if (mReceivePage != null) {
            mReceivePage.reveal();
            if (bmp != null) mReceivePage.setPreview(bmp);
            mReceivePage.setStatus("已收到 " + index + "/" + Math.max(total, index) + " 张"
                    + (saved ? ",已存入相册" : ""));
        }
    }

    /** 对方发送完毕:把本次收到的全部图交给查看器(用户已确认接收就打开)。 */
    @Override public void onTransferDone(java.util.List<Bitmap> bmps) {
        mTransferDone = true;
        mBmps.clear();
        if (bmps != null) mBmps.addAll(bmps);
        if (mReceivePage != null) {
            mReceivePage.setStatus("已收到 " + mFullCount + " 张,请确认接收");
            if (mAccepted) {
                ReceivePage p = mReceivePage; mReceivePage = null;
                p.dismiss(); showViewer(mBmps); Bus.unlockRole();
            }
        } else if (mAccepted) {
            showViewer(mBmps);
            Bus.unlockRole();
        }
    }

    @Override
    public void onImageSent(int bytes) {
        Bus.log("服务: 发送完成(" + (bytes / 1024) + "KB),悬浮窗关闭,用户仍在相册");
        Bus.unlockRole();
        PENDING.clear();
        savePending();
        dismissSendPage();
    }

    @Override
    public void onXferFailed(String why) {
        if (mSendPage == null && !PENDING.isEmpty()) showSendPage(); // 失败也要让用户看到原因(发送页此时可能还没弹)
        if (mSendPage != null) mSendPage.setStatus(why);
        // 流程结束(失败):立即释放角色锁,保证下一轮碰触能重新开始
        Bus.unlockRole();
    }

    /** 读取对方(接收方动作,由透明跳板转交)。 */
    private void handleTag(final android.nfc.Tag tag) {
        new Thread(new Runnable() { public void run() {
            android.nfc.tech.IsoDep iso = android.nfc.tech.IsoDep.get(tag);
            if (iso == null) { Bus.log("对方不是 IsoDep(HCE)设备"); return; }
            try {
                iso.setTimeout(3000);
                if (!iso.isConnected()) iso.connect();
                Bus.log("NFC 链路已建立(ISO-DEP)");
                byte[] r = iso.transceive(Bus.SELECT_AID_APDU);
                if (!Bus.isOk(r)) { Bus.log("对方未接受我们的 AID"); return; }
                byte[] resp = iso.transceive(Bus.buildExchangeApdu(Bus.selfPayload()));
                String peer = Bus.parseExchangeResponse(resp);
                if (peer == null) { Bus.log("响应为空"); return; }
                Bus.log("收到对方信息: " + peer);
                Bus.onPeerData(peer, "reader");
            } catch (Throwable t) {
                Bus.log("读取失败: " + t + " — 贴紧约 1 秒再分开,重试一次");
            } finally { try { iso.close(); } catch (Throwable ignored) {} }
        }}).start();
    }

    private void showReceivePage(String label) {
        if (mReceivePage != null) return;
        mAccepted = false; mPreviewCount = 0; mFullCount = 0;
        mTotalCount = 0; mTransferDone = false; mBmps.clear();
        mReceivePage = new ReceivePage(this, new ReceivePage.Listener() {
            @Override public void onAccept() {
                mAccepted = true;
                if (mTransferDone && mFullCount > 0) { // 已经收完,直接看全部
                    ReceivePage p = mReceivePage; mReceivePage = null;
                    p.dismiss(); showViewer(mBmps); Bus.unlockRole();
                } else if (mReceivePage != null) {
                    mReceivePage.setStatus(mFullCount > 0 ? "正在接收,收完自动打开…" : "等待对方发送…");
                }
            }
            @Override public void onReject() {
                Bus.log("已拒绝接收");
                Bus.unlockRole();
                if (mReceivePage != null) { mReceivePage.dismiss(); mReceivePage = null; }
            }
        });
        mReceivePage.bind(label, 1);
        mReceivePage.setStatus("正在连接对方…");
        mReceivePage.root.setAlpha(0f);
        Overlay.get(this).show(mReceivePage.root);
        mReceivePage.root.animate().alpha(1f).setDuration(280).start();
    }

    private void showViewer(java.util.List<Bitmap> bmps) {
        android.widget.FrameLayout v = Ui.buildViewer(this, bmps);
        // 关闭按钮(右上):点图片本体不退出
        android.widget.TextView vc = new android.widget.TextView(this);
        vc.setText("✕");
        vc.setTextColor(0xFFDDDDDD);
        vc.setTextSize(20);
        vc.setPadding(36, 24, 36, 24);
        vc.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View x) { Overlay.get(TapService.this).hide(v); }
        });
        android.widget.FrameLayout.LayoutParams vlp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT;
        vlp.topMargin = 60;
        vlp.rightMargin = 4;
        v.addView(vc, vlp);
        Overlay.get(this).show(v);
    }

    private static String field(String payload, String key) {
        for (String kv : payload.split("\\|")) {
            if (kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
        }
        return null;
    }
}
