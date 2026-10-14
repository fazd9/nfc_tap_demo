package com.pri.nfctouchdemo.nfc;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.pri.nfctouchdemo.service.OverlayService;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * 常驻服务:承载"碰一碰"主流程(对齐产品视频)。
 *  - 发送方:相册把"当前选中照片"实时推给本服务;碰一碰时本服务**直接拉起动效悬浮层**,
 *           上滑发送后动效窗口消失 —— 用户全程停留在相册,前台应用不被打断。
 *  - 接收方:接收确认同样由动效层弹出(OverlayService 的"接收态":接收 / 拒绝按钮)。
 *
 * 与旧实现的区别:确认页从本包自绘的 SendPage / ReceivePage(已删除)换成 Kotlin 动效层
 * com.pri.nfctouchdemo.service.OverlayService。本服务只负责"下指令"与"收回执":
 *  - 显示类指令(发送页 / 接收页)走 startForegroundService —— 动效层是前台服务 + 整屏悬浮窗;
 *  - 纯更新类指令(status / photo / hide)走 startService;
 *  - 用户在动效层上滑、点"接收"、点"拒绝"时,动效层用 startService 回执三个 ACTION_UI_*,
 *    对应业务判定仍然全部留在本服务(见 onStartCommand 末尾新增的三个分支),原逻辑不变。
 */
public class TapService extends Service implements Bus.Listener, Xfer.Listener {

    private static Xfer sXfer;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private String mPeerLabel = "--------";
    private volatile boolean mLinkReady;
    private volatile boolean mPreviewsSent;
    private Bitmap mLastBmp;                                            // 最近一张预览 / 整图(动效层卡片 + 预览落盘都用它)
    private boolean mAccepted;
    private int mPreviewCount;
    private int mFullCount;
    private int mTotalCount;                                            // 对方预告的总张数
    private boolean mTransferDone;                                      // 对方发送完毕(连接关闭)
    private final ArrayList<Bitmap> mBmps = new ArrayList<Bitmap>();    // 本次收到的全部整图
    private boolean mSendShown;                                         // 本轮发送动效页是否已拉起(替代原来的 mSendPage != null)
    private boolean mReceiveShown;                                      // 本轮接收动效页是否已拉起(替代原来的 mReceivePage != null)

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
        // ---- 以下三个分支:动效层(OverlayService)的"用户操作回执",等价于旧 SendPage/ReceivePage 的 Listener ----
        if (intent != null && OverlayService.ACTION_UI_SEND_CONFIRMED.equals(intent.getAction())) {
            // 等价于旧 SendPage.Listener.onSwipedSend():上滑过阈值那一刻
            if (sXfer == null || PENDING.isEmpty()) return START_STICKY;
            if (!mLinkReady) {
                // 还没连上就上滑:给状态行提示,不去发图(动效层自己的回弹动画负责把卡片放回去,
                // 旧实现这里调 mSendPage.resetStack(),现已由动效层内部处理,故不再调用)
                startService(OverlayService.statusIntent(this, "正在连接对方,请稍候…(就绪后再上滑)"));
                return START_STICKY;
            }
            sXfer.sendImages(new ArrayList<Uri>(PENDING));
        }
        if (intent != null && OverlayService.ACTION_UI_RECEIVE_ACCEPT.equals(intent.getAction())) {
            // 等价于旧 ReceivePage.Listener.onAccept():用户点了"接收 N 张照片"
            mAccepted = true;
            if (mTransferDone && mFullCount > 0) { // 已经收完,直接看全部
                mReceiveShown = false;
                startService(OverlayService.hideIntent(this)); // 摘掉动效窗口,查看器改用旧的 Overlay
                showViewer(mBmps);
                Bus.unlockRole();
            } else {
                startService(OverlayService.statusIntent(this,
                        mFullCount > 0 ? "正在接收,收完自动打开…" : "等待对方发送…"));
            }
        }
        if (intent != null && OverlayService.ACTION_UI_RECEIVE_REJECT.equals(intent.getAction())) {
            // 等价于旧 ReceivePage.Listener.onReject():用户点了"拒绝"
            Bus.log("已拒绝接收");
            Bus.unlockRole();
            startService(OverlayService.hideIntent(this)); // 摘掉动效窗口(服务继续常驻,等下轮碰触)
            mAccepted = false;
            // 清掉本轮接收状态,保证下一轮碰触从干净状态开始(原来靠 mReceivePage = null 达成)
            mReceiveShown = false;
            mPreviewCount = 0;
            mFullCount = 0;
            mTotalCount = 0;
            mTransferDone = false;
            mBmps.clear();
            mLastBmp = null;
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

    // ---------- 碰一碰:发送方(卡方)收到读取 → 拉起动效层发送页 ----------

    @Override public void onLog(String line) {}

    @Override
    public void onPeer(String payload, String role) {
        mLinkReady = false; // 新一轮:连接就绪状态重置
        mSendShown = false;    // 新一轮:本轮"发送页已弹"标记清零(替代原来 mSendPage 字段的生命周期)
        mReceiveShown = false; // 同理,接收页标记清零
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
            // 发送页**立刻**弹:以前要等 onP2pReady,链路一旦卡在"连接中",用户那边就是白屏、
            // 完全没反应(失败时 onXferFailed 也只往一个还不存在的页面写状态行)。
            // showSendPage() 内部有 mSendShown 幂等,onP2pReady 里那次调用不会重复建页。
            showSendPage();
            x.startAsSender();
        }
    }

    /**
     * 拉起动效层的发送页(涟漪 + 白线 + 卡片堆叠 + 上滑发送)。
     * 旧实现是 new SendPage(...) 自绘控件 + Overlay.get(this).show(root);现在换成让 Kotlin 动效层
     * 自己起前台服务 + 整屏悬浮窗,本服务只发一条 startForegroundService 指令。
     */
    private void showSendPage() {
        if (PENDING.isEmpty()) return; // 没有待发照片(正常不会走到这):直接返回
        if (mSendShown) return;        // 本轮已经拉起过:幂等,避免重复 start 动效层(替代原来 mSendPage != null 的判断)
        mSendShown = true;
        // UI 动作统一回主线程再发(与原实现一致:原来创建 View / show 悬浮窗也在主线程做)
        mMain.post(new Runnable() { @Override public void run() {
            if (PENDING.isEmpty()) return; // 极端情况:期间照片被清空,保持 mSendShown=true 不再补弹
            // NFC 场景拿不到"对方屏幕画面",就把要发送的第一张照片自己当折射背景:
            // 涟漪/折射的取色与卡片内容一致,视觉上像"这张照片从对方那边荡开",不需要额外截图权限。
            final Uri backdrop = PENDING.get(0);
            mPreviewsSent = false;
            // 带上对端标识和张数:动效页标题就是产品里那句「发送给 用户XXXX」,
            // 上滑提示是「上滑发送 N 张照片」(不给 peer 时会退回动效层自带的默认文案)。
            startForegroundService(OverlayService.intent(
                    TapService.this, backdrop, backdrop, mPeerLabel, PENDING.size()));
            startService(OverlayService.statusIntent(TapService.this, "正在连接对方…"));
        }});
    }

    // ---------- Xfer 事件(仅发送侧处理) ----------

    @Override
    public void onP2pReady(boolean isGroupOwner, String goAddress) {
        mLinkReady = true;
        final boolean sender = !PENDING.isEmpty();
        mMain.post(new Runnable() { @Override public void run() { // 回到主线程再改 UI
            if (sender) {
                showSendPage(); // 连接就绪才弹(见 onPeer 里的说明);内部有 mSendShown 幂等
                if (mSendShown) startService(OverlayService.statusIntent(TapService.this, "已连接,上滑发送即可"));
            } else if (mReceiveShown) {
                startService(OverlayService.statusIntent(TapService.this, "已连接,等待接收…"));
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
        if (bmp == null) return;
        mLastBmp = bmp; // 记住最近一张预览:动效层卡片的照片和预览落盘都用它
        if (!mReceiveShown) return; // 接收页还没起来:图先留着,showReceivePage 时会带上
        mPreviewCount++;            // 原来是 setPreviewCount(++mPreviewCount),现在张数只在状态行里体现
        Uri u = previewUriOrNull();
        if (u != null) startService(OverlayService.photoIntent(this, u)); // 只换卡片照片,不重启动效页
        else Bus.log("预览图落盘失败,动效层卡片继续用内置插画");
    }

    @Override public void onSendStarted(int total) {
        mTotalCount = total;
        if (mReceiveShown) {
            // 动效层接收页的按钮文案张数来自 receiveIntent 的 count(=1);真实总张数到了,用状态行补正
            startService(OverlayService.statusIntent(this,
                    "已收到 " + mFullCount + "/" + Math.max(total, mFullCount) + " 张"));
        }
    }

    @Override
    public void onImageReceived(int index, int total, Bitmap bmp, int bytes, boolean saved) {
        mFullCount = index;
        mLastBmp = bmp;
        // 图片到达:必须经过"接收确认"这一步(动效层接收页还没起来就先补起来)
        if (!mReceiveShown) {
            showReceivePage(mPeerLabel);
        }
        if (bmp != null) {
            Uri u = previewUriOrNull();
            if (u != null) startService(OverlayService.photoIntent(this, u)); // 卡片换成对方刚发来的整图
        }
        startService(OverlayService.statusIntent(this, "已收到 " + index + "/" + Math.max(total, index) + " 张"
                + (saved ? ",已存入相册" : "")));
    }

    /** 对方发送完毕:把本次收到的全部图交给查看器(用户已确认接收就打开)。 */
    @Override public void onTransferDone(java.util.List<Bitmap> bmps) {
        mTransferDone = true;
        mBmps.clear();
        if (bmps != null) mBmps.addAll(bmps);
        if (mReceiveShown) {
            startService(OverlayService.statusIntent(this, "已收到 " + mFullCount + " 张,请确认接收"));
            if (mAccepted) {
                mReceiveShown = false;
                // 用户已经点过"接收":动效层没别的用途了,先摘掉它的窗口再开查看器
                // (查看器仍走旧的 Overlay.get(this).show(...),两个悬浮窗别叠在一起)
                startService(OverlayService.hideIntent(this));
                showViewer(mBmps);
                Bus.unlockRole();
            }
        } else if (mAccepted) {
            startService(OverlayService.hideIntent(this)); // 同上:进查看器前先摘掉动效窗口
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
        mSendShown = false; // 本轮结束,下一轮碰触重新拉起
        // 发送完成后摘掉动效窗口(动效层自己也会播"飞走"动画,这里再兜一次底;
        // 用 startService 而不是 stopIntent:服务继续常驻,只是窗口没了)
        startService(OverlayService.hideIntent(this));
    }

    @Override
    public void onXferFailed(String why) {
        if (!mSendShown && !PENDING.isEmpty()) showSendPage(); // 失败也要让用户看到原因(发送页此时可能还没弹)
        startService(OverlayService.statusIntent(this, why)); // 失败原因同样走动效页底部状态行
        // 流程结束(失败):立即释放角色锁,保证下一轮碰触能重新开始
        Bus.unlockRole();
    }

    //pri added by zhuyang,Stubs for the two-phase protocol callbacks (batch 1: interface only),20261014,start
    @Override public void onOfferExpired() {
        Bus.log("服务: 对方超时未发货,本轮结束");
    }

    @Override public void onPeerRejected() {
        Bus.log("服务: 对方拒收");
    }

    @Override public void onPeerAccepted() {
        Bus.log("服务: 对方已接收");
    }

    @Override public void onVerdictTimeout() {
        Bus.log("服务: 等待对方回执超时");
    }
    //pri added by zhuyang,Stubs for the two-phase protocol callbacks (batch 1: interface only),20261014,end

    /** 读取对方(接收方动作,由透明跳板转交)。 */
    private void handleTag(final android.nfc.Tag tag) {
        new Thread(new Runnable() { public void run() {
            android.nfc.tech.IsoDep iso = android.nfc.tech.IsoDep.get(tag);
            if (iso == null) { Bus.log("对方不是 IsoDep(HCE)设备"); return; }
            try {
                // APDU 必须在会话开始前算好:Bus.selfPayload() 首次要初始化蓝牙取本机名 + 枚举网卡,
                // 是几百毫秒级的活 —— 夹在 SELECT 与 EXCHANGE 之间会把链路拖断
                // (真机实测:对方在"AID 被选中"后 249ms 就链路丢失,本机第二次 transceive 抛
                //  IOException,EXCHANGE 永远送不到卡方)
                byte[] ex = Bus.buildExchangeApdu(Bus.selfPayload());
                iso.setTimeout(3000);
                if (!iso.isConnected()) iso.connect();
                Bus.log("NFC 链路已建立(ISO-DEP)");
                byte[] r = iso.transceive(Bus.SELECT_AID_APDU);
                if (!Bus.isOk(r)) { Bus.log("对方未接受我们的 AID"); return; }
                Bus.log("读卡: SELECT 已通过,发 EXCHANGE " + ex.length + "B");
                byte[] resp = iso.transceive(ex);
                String peer = Bus.parseExchangeResponse(resp);
                if (peer == null) { Bus.log("响应为空"); return; }
                Bus.log("收到对方信息: " + peer);
                Bus.onPeerData(peer, "reader");
            } catch (Throwable t) {
                Bus.log("读取失败: " + t + " — 贴紧约 1 秒再分开,重试一次");
            } finally { try { iso.close(); } catch (Throwable ignored) {} }
        }}).start();
    }

    /**
     * 拉起动效层的接收页(涟漪 + 白线 +「来自 X的分享」+ 卡片 + 接收/拒绝按钮)。
     * 旧实现是 new ReceivePage(...) 自绘控件;现在由 OverlayService.receiveIntent 承担。
     * 接收页那张照片:Xfer 回调给的是 Bitmap,塞不进 Intent(有大小限制),先落成缓存文件再给 file:// Uri。
     */
    private void showReceivePage(String label) {
        if (mReceiveShown) return; // 幂等:图片到达时可能会再调一次(替代原来 mReceivePage != null 的判断)
        mReceiveShown = true;
        mAccepted = false; mPreviewCount = 0; mFullCount = 0;
        mTotalCount = 0; mTransferDone = false; mBmps.clear();
        startForegroundService(OverlayService.receiveIntent(this, label, 1, previewUriOrNull()));
        startService(OverlayService.statusIntent(this, "正在连接对方…"));
    }

    /**
     * 把最近一张预览 / 整图落成缓存文件并返回 file:// Uri(没有图返回 null)。
     * 动效层(ImageLoader)走 contentResolver.openInputStream 读它,file:// 可用;
     * 按方案 §2.3:接收页那张照片只能靠缓存文件传给动效层。
     * 写盘很小(一张预览),这里同步写完再返回,保证 Uri 立刻可用;失败只记日志,绝不让服务崩。
     */
    private Uri previewUriOrNull() {
        Bitmap bmp = mLastBmp;
        if (bmp == null && !mBmps.isEmpty()) bmp = mBmps.get(mBmps.size() - 1); // 回退:用收到的最后一张整图
        if (bmp == null) return null;
        try {
            File f = new File(getCacheDir(), "peer_preview.jpg");
            FileOutputStream os = new FileOutputStream(f);
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, os); // 质量 90:动效层卡片用,清晰度够且体积小
            } finally {
                try { os.close(); } catch (Throwable ignored) {}
            }
            return Uri.fromFile(f);
        } catch (Throwable t) {
            Bus.log("接收页预览图落盘失败: " + t);
            return null;
        }
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
