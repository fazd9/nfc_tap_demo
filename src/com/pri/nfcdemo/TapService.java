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
    private ReceivePage mReceivePage;
    private Bitmap mLastBmp;
    private boolean mAccepted;
    private int mPreviewCount;

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
        Bus.log("服务: TapService 常驻中(承载碰一碰流程)");
        if (intent != null && "com.pri.nfcdemo.TAG".equals(intent.getAction())) {
            final android.nfc.Tag tag = intent.getParcelableExtra(android.nfc.NfcAdapter.EXTRA_TAG);
            if (tag != null) handleTag(tag);
        }
        if (intent != null && Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null) {
                PENDING.clear();
                PENDING.addAll(uris);
                Bus.log("服务: 收到相册当前选中 " + PENDING.size() + " 张照片(等碰一碰)");
            }
        }
        return START_STICKY;
    }

    // ---------- 碰一碰:发送方(卡方)收到读取 → 弹出发送确认页 ----------

    @Override public void onLog(String line) {}

    @Override
    public void onPeer(String payload, String role) {
        if ("reader".equals(role)) { // 本机读取到了对方
            final String label = "用户" + (field(payload, "t") == null ? "--------" : field(payload, "t"));
            Xfer x = xfer(this);
            x.setExpectedPeer(field(payload,"n"), field(payload,"b"), field(payload,"p"),
                    field(payload,"a"), field(payload,"ip"), field(payload,"hs"), field(payload,"hp"));
            x.startAsReceiver(); // 服务端先就绪
            mPeerLabel = label;
            if (!Bus.lockRole()) { Bus.log("角色已定,忽略接收方事件"); return; }
            mMain.post(new Runnable() { @Override public void run() { showReceivePage(label); } });
            return;
        }
        if (!"card".equals(role)) return; // 只有发送方(被读方)走这里
        if (!Bus.lockRole()) { Bus.log("角色已定(本机已是接收方),忽略本次卡侧事件"); return; }
        if (PENDING.isEmpty()) {
            Bus.log("服务: 碰通了,但相册没有选中照片,忽略");
            return;
        }
        mPeerLabel = "用户" + (field(payload, "t") == null ? "--------" : field(payload, "t"));
        Xfer x = xfer(this);
        x.setExpectedPeer(field(payload,"n"), field(payload,"b"), field(payload,"p"),
                field(payload,"a"), field(payload,"ip"), field(payload,"hs"), field(payload,"hp"));
        x.startAsReceiver(); // 服务端先就绪(两台都可收)
        if (sXfer != null) sXfer.startAsSender(); // 发送方:碰后即开始建立连接
        mMain.post(new Runnable() {
            @Override public void run() { showSendPage(); }
        });
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
        if (mSendPage == null) return; // 接收方由 Activity 处理
        mSendPage.setStatus("已连接,上滑发送即可");
        if (sXfer != null) sXfer.sendPreviews(new ArrayList<Uri>(PENDING));
    }

    @Override public void onPreview(Bitmap bmp) {
        if (mReceivePage != null && bmp != null) {
            mReceivePage.setPreview(bmp);
            mReceivePage.setPreviewCount(++mPreviewCount);
        }
    }

    @Override public void onImageReceived(Bitmap bmp, int bytes, boolean saved) {
        mLastBmp = bmp;
        if (mReceivePage != null) {
            mReceivePage.reveal();
            if (bmp != null) mReceivePage.setPreview(bmp);
            mReceivePage.setStatus("已收到 " + (bytes / 1024) + "KB");
            if (mAccepted) {
                ReceivePage p = mReceivePage; mReceivePage = null;
                p.dismiss(); showViewer(bmp); Bus.unlockRole();
            }
        } else if (bmp != null) {
            showViewer(bmp);
        }
    }

    @Override
    public void onImageSent(int bytes) {
        Bus.log("服务: 发送完成(" + (bytes / 1024) + "KB),悬浮窗关闭,用户仍在相册");
        Bus.unlockRole();
        PENDING.clear();
        dismissSendPage();
    }

    @Override
    public void onXferFailed(String why) {
        if (mSendPage != null) mSendPage.setStatus(why);
    }

    /** 读取对方(接收方动作,由透明跳板转交)。 */
    private void handleTag(final android.nfc.Tag tag) {
        // 有选中照片的手机不读取(只当卡等对方来读),避免双方同时读造成 RF 冲突(TagLost)
        if (hasPending()) {
            Bus.log("本机有选中照片 → 不读取,只当卡等对方来读");
            return;
        }
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
        mAccepted = false; mPreviewCount = 0;
        mReceivePage = new ReceivePage(this, new ReceivePage.Listener() {
            @Override public void onAccept() {
                mAccepted = true;
                if (mLastBmp != null) {
                    ReceivePage p = mReceivePage; mReceivePage = null;
                    p.dismiss(); showViewer(mLastBmp); Bus.unlockRole();
                } else if (mReceivePage != null) {
                    mReceivePage.setStatus("等待对方发送…");
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

    private void showViewer(Bitmap bmp) {
        android.widget.FrameLayout v = new android.widget.FrameLayout(this);
        v.setBackgroundColor(0xFF000000);
        android.widget.ImageView img = new android.widget.ImageView(this);
        img.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        if (bmp != null) img.setImageBitmap(bmp);
        v.addView(img, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        v.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View x) { Overlay.get(TapService.this).hide(x); }
        });
        Overlay.get(this).show(v);
    }

    private static String field(String payload, String key) {
        for (String kv : payload.split("\\|")) {
            if (kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
        }
        return null;
    }
}
