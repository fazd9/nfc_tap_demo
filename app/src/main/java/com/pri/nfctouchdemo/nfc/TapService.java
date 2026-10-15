package com.pri.nfctouchdemo.nfc;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.pri.nfctouchdemo.R; //pri added by zhuyang,D1: fail banner lines live in strings.xml,20261014
import com.pri.nfctouchdemo.gfx.ScreenCapture; //pri added by zhuyang,Screen-capture backdrop (LongShotScreen silent ScreenCaptureInternal port),20261014
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
    private volatile boolean mUserWantsSend;                            //pri added by zhuyang,Local user swiped: this phone is the sender,20261014
    private boolean mSendStarted;                                       //pri added by zhuyang,sendImages already handed over this round,20261014
    private boolean mRejected;                                          //pri added by zhuyang,Peer transfer rejected this round (blocks re-showing),20261014
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

    //pri modify by zhuyang,Hard cap on the silent capture: 1500ms is ~2.5x the device-measured 578ms of the old SystemUI path and stays below the framework's own 4s wait; past it the send page is raised with the photo backdrop instead,20261014
    private static final long CAPTURE_TIMEOUT_MS = 1500L;

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
            //pri modify by zhuyang,Swipe now declares the sender role and triggers the link negotiation,20261014,start
            mUserWantsSend = true;
            sXfer.setSenderRole(); //pri added by zhuyang,Swipe declares the sender role even when the link is already up,20261014
            if (mLinkReady) {
                if (!mSendStarted) {
                    mSendStarted = true;
                    sXfer.sendImages(new ArrayList<Uri>(PENDING));
                }
                return START_STICKY;
            }
            startService(OverlayService.statusIntent(this, "正在连接对方,请稍候…"));
            sXfer.startAsSender();
            //pri modify by zhuyang,Swipe now declares the sender role and triggers the link negotiation,20261014,end
        }
        if (intent != null && OverlayService.ACTION_UI_RECEIVE_ACCEPT.equals(intent.getAction())) {
            // 等价于旧 ReceivePage.Listener.onAccept():用户点了"接收 N 张照片"
            mAccepted = true;
            if (sXfer != null) sXfer.confirmReceived(); //pri added by zhuyang,Write the ACCEPT verdict back on the same socket,20261014
            if (mTransferDone && mFullCount > 0) { // 已经收完,直接看全部
                mReceiveShown = false;
                //pri modify by zhuyang,Detach the animation window; the viewer is the system one now,20261014
                startService(OverlayService.hideIntent(this));
                openSystemViewer();
                Bus.unlockRole();
            } else {
                startService(OverlayService.statusIntent(this,
                        mFullCount > 0 ? "正在接收,收完自动打开…" : "等待对方发送…"));
            }
        }
        if (intent != null && OverlayService.ACTION_UI_RECEIVE_REJECT.equals(intent.getAction())) {
            // 等价于旧 ReceivePage.Listener.onReject():用户点了"拒绝"
            Bus.log("已拒绝接收");
            if (sXfer != null) sXfer.rejectTransfer(); //pri added by zhuyang,Write the REJECT verdict back (no image was ever sent),20261014
            mRejected = true; //pri added by zhuyang,Set before hideIntent: the fallback path must not re-open the card,20261014
            Bus.unlockRole();
            //pri modify by zhuyang,E5: dissolve the card before the window goes,20261014
            startService(OverlayService.hideIntent(this, OverlayService.DISSOLVE_MS)); // 摘掉动效窗口(服务继续常驻,等下轮碰触)
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
        //pri added by zhuyang,E4: 取消 = local abort of this round; reported exactly like a reject,20261014,start
        if (intent != null && OverlayService.ACTION_UI_RECEIVE_CANCEL.equals(intent.getAction())) {
            Bus.log("已取消接收");
            if (sXfer != null) sXfer.rejectTransfer();
            mRejected = true;
            Bus.unlockRole();
            startService(OverlayService.hideIntent(this, OverlayService.DISSOLVE_MS));
            mAccepted = false;
            mReceiveShown = false;
            mPreviewCount = 0;
            mFullCount = 0;
            mTotalCount = 0;
            mTransferDone = false;
            mBmps.clear();
            mLastBmp = null;
        }
        //pri added by zhuyang,E4: 取消 = local abort of this round; reported exactly like a reject,20261014,end
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
        //pri added by zhuyang,D1: drop the previous round's fail banner the moment a new round starts,20261014
        startService(OverlayService.hideFailBannerIntent(this));
        mLinkReady = false; // 新一轮:连接就绪状态重置
        mSendShown = false;    // 新一轮:本轮"发送页已弹"标记清零(替代原来 mSendPage 字段的生命周期)
        mReceiveShown = false; // 同理,接收页标记清零
        mUserWantsSend = false; //pri added by zhuyang,New round: role flags reset,20261014,start
        mSendStarted = false;
        mRejected = false;
        mLastBmp = null;        //pri added by zhuyang,Preview bitmap is sticky across rounds,20261014
        //pri added by zhuyang,New round: role flags reset,20261014,end
        final String label = peerName(payload);
        Xfer x = xfer(this);
        x.setExpectedPeer(field(payload,"n"), field(payload,"b"), field(payload,"p"),
                field(payload,"a"), field(payload,"ip"), field(payload,"hs"), field(payload,"hp"));
        x.startAsReceiver(); // 服务端先就绪
        if (!Bus.lockRole()) { Bus.log("角色已定,忽略重复的碰触事件"); return; }
        mPeerLabel = label;
        //pri modify by zhuyang,Both phones only prepare to receive; the role is decided at swipe time,20261014,start
        x.setCanSend(hasPending()); //pri added by zhuyang,Photos in hand = still undecided: the accept loop must not consume an incoming connection as data,20261014
        x.startHotspot(); //pri added by zhuyang,Unconditional: both sides prepare for a possible receive,20261014
        if (hasPending()) {
            showSendPage();
        } else {
            Bus.log("本机无照片 → 本机是接收候选,等对方上滑");
        }
        //pri modify by zhuyang,Both phones only prepare to receive; the role is decided at swipe time,20261014,end
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
        //pri added by zhuyang,Screen-capture backdrop (LongShotScreen silent ScreenCaptureInternal port): the capture must complete,20261014,start
        //pri added by zhuyang,BEFORE the overlay window is attached, or the captured frame would contain our own page. Done on a,20261014
        //pri added by zhuyang,worker thread because the capture blocks on a binder round trip to system_server,20261014
        new Thread(new Runnable() { @Override public void run() {
            final Uri captured = ScreenCapture.captureBackdrop(TapService.this, CAPTURE_TIMEOUT_MS);
            mMain.post(new Runnable() { @Override public void run() {
                // UI 动作统一回主线程再发(与原实现一致:原来创建 View / show 悬浮窗也在主线程做)
                if (PENDING.isEmpty()) return; // 极端情况:期间照片被清空,保持 mSendShown=true 不再补弹
                if (!mSendShown) return;       //pri added by zhuyang,Round ended while the capture was in flight: do not raise the page,20261014
                final Uri photo = PENDING.get(0);
                // NFC 场景拿不到"对方屏幕画面",就把要发送的第一张照片自己当折射背景:
                // 涟漪/折射的取色与卡片内容一致,视觉上像"这张照片从对方那边荡开",不需要额外截图权限。
                if (captured == null) Bus.log("截屏失败/超时,背景回退为照片");
                //pri added by zhuyang,No capture = the original behaviour above stands: the photo itself is the backdrop,20261014
                final Uri backdrop = (captured != null) ? captured : photo;
                // 带上对端标识和张数:动效页标题就是产品里那句「发送给 用户XXXX」,
                // 上滑提示是「上滑发送 N 张照片」(不给 peer 时会退回动效层自带的默认文案)。
                startForegroundService(OverlayService.intent(
                        TapService.this, backdrop, photo, mPeerLabel, PENDING.size()));
                startService(OverlayService.statusIntent(TapService.this, "正在连接对方…"));
            }});
        }}).start();
        //pri added by zhuyang,Screen-capture backdrop (LongShotScreen silent ScreenCaptureInternal port): the capture must complete,20261014,end
    }

    // ---------- Xfer 事件(仅发送侧处理) ----------

    @Override
    public void onP2pReady(boolean isGroupOwner, String goAddress) {
        mLinkReady = true;
        mMain.post(new Runnable() { @Override public void run() { // 回到主线程再改 UI
            //pri modify by zhuyang,Role-aware guard: never paint the send page over the receive card,20261014,start
            if (mUserWantsSend) {
                startService(OverlayService.statusIntent(TapService.this, "正在发送…"));
                if (!mSendStarted) {
                    mSendStarted = true;
                    sXfer.sendImages(new ArrayList<Uri>(PENDING));
                }
            } else if (mReceiveShown || mRejected) {
                startService(OverlayService.statusIntent(TapService.this, "已连接,等待接收…"));
            } else if (hasPending()) {
                showSendPage();
                startService(OverlayService.statusIntent(TapService.this, "已连接,上滑发送即可"));
            }
            //pri modify by zhuyang,Role-aware guard: never paint the send page over the receive card,20261014,end
        }});
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
        //pri modify by zhuyang,Receive card appears here (with the real count) instead of at tap time,20261014,start
        if (mUserWantsSend) return;
        if (mRejected) return;
        mTotalCount = Math.max(total, 0);
        mMain.post(new Runnable() { @Override public void run() {
            if (mReceiveShown) {
                startService(OverlayService.peerIntent(TapService.this, mPeerLabel, Math.max(1, mTotalCount)));
                return;
            }
            if (hasPending()) { switchSendPageToReceive(); return; }
            showReceivePage(mPeerLabel, mTotalCount);
        }});
        //pri modify by zhuyang,Receive card appears here (with the real count) instead of at tap time,20261014,end
    }

    @Override
    public void onImageReceived(int index, int total, Bitmap bmp, int bytes, boolean saved) {
        mFullCount = index;
        mLastBmp = bmp;
        // 图片到达:必须经过"接收确认"这一步(动效层接收页还没起来就先补起来)
        if (!mReceiveShown && !mRejected) { //pri modify by zhuyang,Narrow the fallback: never re-open the card after a reject,20261014
            showReceivePage(mPeerLabel, mTotalCount);
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
                //pri modify by zhuyang,D4: viewer is now the system one, opened on the saved MediaStore Uri,20261014
                startService(OverlayService.hideIntent(this, OverlayService.DISSOLVE_MS)); //pri modify by zhuyang,E5: dissolve before detaching (E5 main path),20261014
                openSystemViewer();
                Bus.unlockRole();
            }
        } else if (mAccepted) {
            //pri modify by zhuyang,E5: dissolve the card before the window goes,20261014
            startService(OverlayService.hideIntent(this, OverlayService.DISSOLVE_MS)); // 同上:进查看器前先摘掉动效窗口
            openSystemViewer();
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
        startService(OverlayService.hideIntent(this, OverlayService.DISSOLVE_MS)); //pri modify by zhuyang,E5: dissolve before detaching,20261014
    }

    @Override
    public void onXferFailed(String why) {
        if (!mSendShown && !PENDING.isEmpty()) showSendPage(); // 失败也要让用户看到原因(发送页此时可能还没弹)
        startService(OverlayService.statusIntent(this, why)); // 失败原因同样走动效页底部状态行
        //pri added by zhuyang,D1: banner only for a failure after this phone declared itself sender,20261014,start
        if (mUserWantsSend) {
            String detail = why == null ? "" : why;
            if (detail.length() > 12) detail = detail.substring(0, 12); //pri added by zhuyang,Long text stays in the status line; the banner only fits a short line,20261014
            startService(OverlayService.failBannerIntent(this, getString(R.string.fail_title), detail));
        }
        //pri added by zhuyang,D1: banner only for a failure after this phone declared itself sender,20261014,end
        // 流程结束(失败):立即释放角色锁,保证下一轮碰触能重新开始
        Bus.unlockRole();
    }

    //pri added by zhuyang,Two-phase protocol callbacks,20261014,start
    @Override public void onOfferExpired() {
        Bus.log("服务: 对方超时未发货,本轮结束");
        if (!mReceiveShown) return;
        mReceiveShown = false;
        mRejected = true; //pri added by zhuyang,This round must not re-open the card,20261014
        startService(OverlayService.hideIntent(this, OverlayService.DISSOLVE_MS)); //pri modify by zhuyang,E5: dissolve before detaching,20261014
        Bus.unlockRole();
    }

    @Override public void onPeerRejected() {
        Bus.log("服务: 对方拒收");
        Bus.unlockRole();
        //pri modify by zhuyang,D1: the status line becomes the top fail banner,20261014
        startService(OverlayService.failBannerIntent(this,
                getString(R.string.fail_title), getString(R.string.fail_rejected)));
    }

    @Override public void onPeerAccepted() {
        Bus.log("服务: 对方已接收");
    }

    @Override public void onVerdictTimeout() {
        Bus.log("服务: 对方未响应(等回执超时)");
        Bus.unlockRole();
        //pri modify by zhuyang,D1: the status line becomes the top fail banner,20261014
        startService(OverlayService.failBannerIntent(this,
                getString(R.string.fail_title), getString(R.string.fail_timeout)));
    }
    //pri added by zhuyang,Two-phase protocol callbacks,20261014,end

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
    private void showReceivePage(String label, int count) {
        if (mReceiveShown) return; // 幂等:图片到达时可能会再调一次(替代原来 mReceivePage != null 的判断)
        mReceiveShown = true;
        mAccepted = false; mPreviewCount = 0; mFullCount = 0;
        mTransferDone = false; mBmps.clear();
        //pri modify by zhuyang,Use the real announced count and pick the status line by link state,20261014
        startForegroundService(OverlayService.receiveIntent(this, label, Math.max(1, count), previewUriOrNull()));
        startService(OverlayService.statusIntent(this, mLinkReady ? "已连接,等待接收…" : "正在连接对方…"));
    }

    //pri added by zhuyang,Loser side of the race: the live send page becomes the receive card in place,20261014,start
    private void switchSendPageToReceive() {
        mSendShown = false;
        mReceiveShown = true;
        mAccepted = false; mPreviewCount = 0; mFullCount = 0; mTransferDone = false;
        mBmps.clear(); mLastBmp = null;
        startForegroundService(OverlayService.receiveIntent(this, mPeerLabel,
                Math.max(1, mTotalCount), previewUriOrNull()));
        startService(OverlayService.statusIntent(this, mLinkReady ? "已连接,等待接收…" : "正在连接对方…"));
    }
    //pri added by zhuyang,Loser side of the race: the live send page becomes the receive card in place,20261014,end

    //pri added by zhuyang,D3: peer display name = Bluetooth name, then model name,20261014,start
    private static String peerName(String payload) {
        String b = field(payload, "b");
        String n = field(payload, "n");
        if (b != null && !b.isEmpty() && !"?".equals(b)) return b;
        if (n != null && !n.isEmpty() && !"?".equals(n)) return n;
        String t = field(payload, "t");
        return "用户" + (t == null ? "--------" : t);
    }
    //pri added by zhuyang,D3: peer display name = Bluetooth name, then model name,20261014,end

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

    //pri added by zhuyang,D4: open the just-saved photo with the system viewer instead of the old overlay viewer,20261014,start
    //pri added by zhuyang,Uri comes from the MediaStore record Xfer made while saving; nothing saved = log only,20261014
    private void openSystemViewer() {
        Uri u = (sXfer != null) ? sXfer.firstSavedUri() : null;
        if (u == null) { Bus.log("接收完成:本轮没有落盘 Uri,不打系统查看器"); return; }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(u, "image/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION)); //pri added by zhuyang,No slide-in transition: the panel dissolves in place instead of handing off to a sliding Activity,20261014
        } catch (Throwable t) {
            Bus.log("打开系统查看器失败: " + t);
        }
    }
    //pri added by zhuyang,D4: open the just-saved photo with the system viewer instead of the old overlay viewer,20261014,end

    private static String field(String payload, String key) {
        for (String kv : payload.split("\\|")) {
            if (kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
        }
        return null;
    }
}
