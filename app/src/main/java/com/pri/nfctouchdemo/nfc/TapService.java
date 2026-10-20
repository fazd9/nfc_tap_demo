package com.pri.nfctouchdemo.nfc;

import android.app.ActivityOptions; //pri added by zhuyang,D4: launch the viewer with zero animations,20261014
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
import com.pri.nfctouchdemo.viewer.ImageViewerActivity; //pri added by zhuyang,D5: viewer is our own Activity now,20261014

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch; //pri added by zhuyang,Tap-time gallery pull reply latch,20261019
import java.util.concurrent.TimeUnit;     //pri added by zhuyang,Tap-time gallery pull reply wait,20261019

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
    private volatile boolean mRoleYielded;                              //pri added by zhuyang,This round lost the role race: never send,20261017
    private boolean mRejected;                                          //pri added by zhuyang,Peer transfer rejected this round (blocks re-showing),20261014
    private boolean mPeerOffered;                                       //pri added by zhuyang,Peer announced a transfer this round (Xfer onSendStarted),20261014
    private Bitmap mLastBmp;                                            // 最近一张预览 / 整图(动效层卡片 + 预览落盘都用它)
    private boolean mAccepted;
    private int mPreviewCount;
    private int mFullCount;
    private int mTotalCount;                                            // 对方预告的总张数
    private boolean mTransferDone;                                      // 对方发送完毕(连接关闭)
    private final ArrayList<Bitmap> mBmps = new ArrayList<Bitmap>();    // 本次收到的全部整图
    private boolean mSendShown;                                         // 本轮发送动效页是否已拉起(替代原来的 mSendPage != null)
    private boolean mReceiveShown;                                      // 本轮接收动效页是否已拉起(替代原来的 mReceivePage != null)
    private long mLastErrorAt;                                          //pri added by zhuyang,Batch 5: coded error already rendered,20261017

    /** 相册推送过来的"当前选中照片"。 */
    private static final ArrayList<Uri> PENDING = new ArrayList<Uri>();

    //pri added by zhuyang,How many photos the gallery currently has selected; survives PENDING.clear() after a send,20261019
    private static volatile int sGalleryCount;

    //pri added by zhuyang,Gallery -> tap-to-share interface (the contract our own gallery implements; the debug gallery,20261016,start
    //pri added by zhuyang,in Temp/debuggallery is the reference implementation). Two intents, both sent with an explicit,20261016
    //pri added by zhuyang,component so no <intent-filter> and no chooser is involved:,20261016
    //pri added by zhuyang,,20261016
    //pri added by zhuyang,  PUBLISH  the photos on screen right now (1..N; a single open photo is just N=1),20261016
    //pri added by zhuyang,    Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);,20261016
    //pri added by zhuyang,    i.setType("image/*");,20261016
    //pri added by zhuyang,    i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);   // ArrayList<Uri>,20261016
    //pri added by zhuyang,    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);,20261016
    //pri added by zhuyang,    i.setComponent(new ComponentName("com.pri.nfctouchdemo",,20261016
    //pri added by zhuyang,            "com.pri.nfctouchdemo.nfc.TapService"));,20261016
    //pri added by zhuyang,    // the service keeps reading these Uris long after the gallery is gone, so grant them to it,20261016
    //pri added by zhuyang,    // by uid as well (a one-shot intent flag only lives as long as the activity that sent it):,20261016
    //pri added by zhuyang,    for (Uri u : uris) grantUriPermission("com.pri.nfctouchdemo", u,,20261016
    //pri added by zhuyang,            Intent.FLAG_GRANT_READ_URI_PERMISSION);,20261016
    //pri added by zhuyang,    context.startService(i);,20261016
    //pri added by zhuyang,,20261016
    //pri added by zhuyang,  CLEAR    the user is no longer looking at that selection (back to the grid, left the gallery,20261016
    //pri added by zhuyang,    screen off) - nothing must be left behind for a later tap to send by mistake:,20261016
    //pri added by zhuyang,    Intent c = new Intent("com.pri.nfcdemo.CLEAR_SELECTION");,20261016
    //pri added by zhuyang,    c.setComponent(new ComponentName("com.pri.nfctouchdemo",,20261016
    //pri added by zhuyang,            "com.pri.nfctouchdemo.nfc.TapService"));,20261016
    //pri added by zhuyang,    context.startService(c);,20261016
    //pri added by zhuyang,,20261016
    //pri added by zhuyang,Rule: what the gallery last published is what a tap sends. A published selection therefore outranks,20261016
    //pri added by zhuyang,the screen-reading fallback (GalleryPickup, Google Photos only) - see onPeer. Clearing is not optional:,20261016
    //pri added by zhuyang,a selection left behind would be sent as if it were what the user is looking at,20261016,end
    //
    //pri added by zhuyang,REQUEST  the reverse direction: before each tap the service asks the gallery what it shows right now,,20261019
    //pri added by zhuyang,so an unchanged selection is still fresh after a send consumed it (see requestGallerySelection):,20261019
    //pri added by zhuyang,    Intent r = new Intent("com.pri.nfcdemo.REQUEST_SELECTION");,20261019
    //pri added by zhuyang,    r.setPackage("com.pri.debuggallery");,20261019
    //pri added by zhuyang,    context.sendBroadcast(r);,20261019
    //pri added by zhuyang,The gallery answers with PUBLISH (what is on screen) or CLEAR (nothing is),20261019
    public static final String ACTION_CLEAR_SELECTION = "com.pri.nfcdemo.CLEAR_SELECTION";
    //pri added by zhuyang,The photo the user opened in the gallery: the card thumbnail must be that one,20261019
    public static final String EXTRA_PREVIEW_URI = "com.pri.nfcdemo.extra.PREVIEW_URI";

    //pri added by zhuyang,Tap-time pull: every tap asks the gallery what it is showing right now instead of trusting the,20261019,start
    //pri added by zhuyang,selection pushed when it last changed (onImageSent clears PENDING, so an unchanged selection would,20261019
    //pri added by zhuyang,otherwise be missing on the next tap). The request is an explicit-package broadcast; the gallery answers,20261019
    //pri added by zhuyang,with the same ACTION_SEND_MULTIPLE / ACTION_CLEAR_SELECTION it already publishes and clears with,20261019,end
    public static final String ACTION_REQUEST_SELECTION = "com.pri.nfcdemo.REQUEST_SELECTION";
    private static final String GALLERY_PKG = "com.pri.debuggallery";
    private static final long GALLERY_REPLY_WAIT_MS = 300L;

    //pri added by zhuyang,Released on either reply intent; the tap waits on it off the main thread for at most 300ms,20261019
    private static volatile CountDownLatch sSelectionReply;

    //pri added by zhuyang,True while the selection in hand was published by a gallery (ACTION_SEND_MULTIPLE) and not cleared,20261016,start
    //pri added by zhuyang,since - i.e. while "a gallery is reporting what the user is looking at". It gates the screen-reading fallback,20261016
    //pri added by zhuyang,in onPeer, and deliberately does NOT survive a process restart: a list restored by loadPending is a leftover,20261016
    //pri added by zhuyang,from a service that was killed with the gallery still open, and a fresh screen read beats it. That keeps the,20261016
    //pri added by zhuyang,Google Photos path exactly as it was before this feature,20261016,end
    private static volatile boolean sPublished;
    //pri added by zhuyang,Preview override published by the gallery (the opened photo); null = fall back to the first,20261019
    private static volatile Uri sPreviewOverride;

    //pri modify by zhuyang,Hard cap on the silent capture: 1500ms is ~2.5x the device-measured 578ms of the old SystemUI path and stays below the framework's own 4s wait; past it the send page is raised with the photo backdrop instead,20261014
    private static final long CAPTURE_TIMEOUT_MS = 1500L;

    /** 本机是否有选中的照片(=本机是发送方)。 */
    public static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    //pri modify by zhuyang,Keep the NFC payload's photo count in sync with the gallery's current selection,20261019
    private void syncPhotoCount() {
        Bus.setPhotoCount(sGalleryCount);
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

    //pri added by zhuyang,Batch 6: never leave the round's AP behind when the service/task goes away,20261017,start
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Bus.log("服务: 任务被移除,释放本轮热点");
        if (sXfer != null) sXfer.stopHotspot();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Bus.log("服务: 被销毁,释放本轮热点");
        if (sXfer != null) sXfer.stopHotspot();
        super.onDestroy();
    }
    //pri added by zhuyang,Batch 6: never leave the round's AP behind when the service/task goes away,20261017,end

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
            Bus.log("服务: 收到碰触信息" + (payload == null ? "" : ",对方=" + Bus.redactPayload(payload))); //pri modify by zhuyang,Redact sid and hotspot password,20261017
            Bus.onPeerData(payload, intent.getStringExtra("role"));
        }
        if (intent != null && "com.pri.nfcdemo.DEBUG".equals(intent.getAction())) {
            // 调试:把 NFC 交换这一步用 adb 参数顶掉,其余流程走真实代码(免物理碰触做回归/演示)
            String payload = intent.getStringExtra("payload");
            Bus.log("调试: 模拟碰一碰,对方信息 = " + Bus.redactPayload(payload)); //pri modify by zhuyang,Redact sid and hotspot password,20261017
            Bus.onPeerData(payload, intent.getStringExtra("role"));
        }
        //pri added by zhuyang,AssistContent probe triggered by am startservice: unlike am start it does not change the,20261016
        //pri added by zhuyang,top activity, so the gallery can stay in the foreground while the probe runs,20261016
        if (intent != null && "com.pri.nfcdemo.ASSIST_PROBE".equals(intent.getAction())) {
            AssistProbe.run(this);
        }
        //pri added by zhuyang,Diagnostic: dump the gallery node tree and sweep the grid, to see whether the multi-select state and,20261016
        //pri added by zhuyang,the tiles scrolled off screen are reachable at all. Does not touch the tap flow,20261016,start
        if (intent != null && "com.pri.nfcdemo.A11Y_DUMP".equals(intent.getAction())) {
            AccessibilityProbeService svc = AccessibilityProbeService.sInstance;
            if (svc == null) {
                Bus.log("诊断: 无障碍未连接,无法扫描");
            } else {
                svc.sweepAndDump(intent.getIntExtra("steps", 3));
            }
        }
        //pri added by zhuyang,Diagnostic: dump the gallery node tree and sweep the grid, to see whether the multi-select state and,20261016
        //pri added by zhuyang,the tiles scrolled off screen are reachable at all. Does not touch the tap flow,20261016,end
        if (intent != null && Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null) {
                PENDING.clear();
                PENDING.addAll(uris);
                //pri added by zhuyang,Always take the extra as-is: an intent without it (grid multi-select) clears the previous hint,20261019
                String preview = intent.getStringExtra(EXTRA_PREVIEW_URI); //pri added by zhuyang,The gallery publishes the Uri as a string,20261019
                sPreviewOverride = (preview == null || preview.isEmpty()) ? null : Uri.parse(preview);
                sPublished = !PENDING.isEmpty(); //pri added by zhuyang,A gallery published this selection: it now outranks the screen-reading fallback,20261016
                savePending();
                sGalleryCount = PENDING.size(); //pri added by zhuyang,The gallery's on-screen selection drives ph, not the send queue,20261019
                syncPhotoCount(); //pri added by zhuyang,Selection changed: refresh the photo count,20261019
                Bus.log("服务: 收到相册当前选中 " + PENDING.size() + " 张照片(等碰一碰)");
            }
            signalSelectionReply(); //pri added by zhuyang,Answer to the tap-time pull: release it early,20261019
        }
        //pri added by zhuyang,The gallery says "nothing selected on screen any more" - see the contract above ACTION_CLEAR_SELECTION,20261016,start
        //pri added by zhuyang,Clearing is what keeps the next tap honest: without it a leftover selection would be sent while the user,20261016
        //pri added by zhuyang,believes they are sending what they are looking at,20261016,end
        if (intent != null && ACTION_CLEAR_SELECTION.equals(intent.getAction())) {
            Bus.log("服务: 相册撤销了选择,清掉待发的 " + PENDING.size() + " 张照片");
            PENDING.clear();
            sPublished = false;
            sPreviewOverride = null; //pri added by zhuyang,Cleared with the selection: no stale opened-photo hint,20261019
            savePending();
            sGalleryCount = 0; //pri added by zhuyang,The gallery cleared its selection: ph goes to 0,20261019
            syncPhotoCount(); //pri added by zhuyang,Selection cleared: refresh the photo count,20261019
            signalSelectionReply(); //pri added by zhuyang,Answer to the tap-time pull: release it early,20261019
        }
        // ---- 以下三个分支:动效层(OverlayService)的"用户操作回执",等价于旧 SendPage/ReceivePage 的 Listener ----
        if (intent != null && OverlayService.ACTION_UI_SEND_CONFIRMED.equals(intent.getAction())) {
            // 等价于旧 SendPage.Listener.onSwipedSend():上滑过阈值那一刻
            if (sXfer == null || PENDING.isEmpty()) return START_STICKY;
            if (mRoleYielded) { //pri added by zhuyang,Already lost the role race this round: never send,20261017
                Bus.log("服务: 本轮已让出接收,忽略这次上滑");
                return START_STICKY;
            }
            //pri modify by zhuyang,Swipe now declares the sender role and triggers the link negotiation,20261014,start
            mUserWantsSend = true;
            if (!sXfer.setSenderRole()) { //pri modify by zhuyang,Peer announced ROLE(S) first: yield instead of sending,20261017
                Bus.log("服务: 对端先上滑,本机让出,不发送");
                mUserWantsSend = false;
                mRoleYielded = true;
                // Xfer posts onRoleYielded, which turns the send page into the receive card.
                return START_STICKY;
            }
            if (mLinkReady) {
                if (!mSendStarted) {
                    mSendStarted = true;
                    sXfer.sendImages(new ArrayList<Uri>(PENDING));
                }
                return START_STICKY;
            }
            sXfer.startAsSender();
            //pri modify by zhuyang,Swipe now declares the sender role and triggers the link negotiation,20261014,end
        }
        if (intent != null && OverlayService.ACTION_UI_RECEIVE_ACCEPT.equals(intent.getAction())) {
            // 等价于旧 ReceivePage.Listener.onAccept():用户点了"接收 N 张照片"
            mAccepted = true;
            if (sXfer != null) sXfer.confirmReceived(); //pri added by zhuyang,Write the ACCEPT verdict back on the same socket,20261014
            if (mTransferDone && mFullCount > 0) { // 已经收完,直接看全部
                mReceiveShown = false;
                //pri modify by zhuyang,Detach the animation window; the in-app viewer takes over,20261014
                startService(OverlayService.hideIntent(this));
                openViewer();
                Bus.unlockRole();
            } else {
                startService(OverlayService.statusIntent(this,
                        mFullCount > 0 ? "正在接收,收完自动打开…" : getString(R.string.status_transferring)));
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
            //pri added by zhuyang,Batch 6: rejecting cancels the round, so the AP owner releases its hotspot,20261017
            if (sXfer != null) sXfer.stopHotspot();
        }
        return START_STICKY;
    }

    //pri added by zhuyang,Tap-time pull reply arrived: wake the worker that is waiting before falling through to the round,20261019
    private static void signalSelectionReply() {
        CountDownLatch reply = sSelectionReply;
        if (reply != null) reply.countDown();
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
        if (!PENDING.isEmpty()) { syncPhotoCount(); return; }
        String s = getSharedPreferences("tapdemo", 0).getString("pending", "");
        if (s == null || s.isEmpty()) { syncPhotoCount(); return; }
        for (String p : s.split("\\|")) {
            if (!p.isEmpty()) PENDING.add(Uri.parse(p));
        }
        if (!PENDING.isEmpty()) Bus.log("服务: 恢复上次选中的 " + PENDING.size() + " 张照片");
        //pri added by zhuyang,A restored PENDING is last round's send queue, not what the gallery shows now: sGalleryCount stays as is,20261019
        syncPhotoCount(); //pri added by zhuyang,Refresh the photo count without treating the restored queue as a gallery selection,20261019
    }

    // ---------- 碰一碰:发送方(卡方)收到读取 → 拉起动效层发送页 ----------

    @Override public void onLog(String line) {}

    @Override
    public void onPeer(final String payload, final String role) {
        //pri added by zhuyang,Tap-time pull: ask the gallery first, then continue with the old body once it answers or times out,20261019
        requestGallerySelection(payload, role);
    }

    //pri added by zhuyang,Explicit-package broadcast so only our gallery receives the request. The wait is on a worker thread and,20261019,start
    //pri added by zhuyang,capped at GALLERY_REPLY_WAIT_MS: a gallery that is absent / does not know the contract simply falls through,20261019
    //pri added by zhuyang,to onPeerAfterSelection with the old behaviour,20261019,end
    private void requestGallerySelection(final String payload, final String role) {
        final CountDownLatch latch = new CountDownLatch(1);
        sSelectionReply = latch;
        final long startedAt = android.os.SystemClock.elapsedRealtime();
        try {
            sendBroadcast(new Intent(ACTION_REQUEST_SELECTION).setPackage(GALLERY_PKG));
        } catch (Throwable t) {
            android.util.Log.d("NfcTapDemo", "gallery selection request failed: " + t);
        }
        new Thread(new Runnable() { @Override public void run() {
            boolean replied = false;
            try {
                replied = latch.await(GALLERY_REPLY_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            if (sSelectionReply == latch) sSelectionReply = null;
            final boolean gotReply = replied;
            final long cost = android.os.SystemClock.elapsedRealtime() - startedAt;
            mMain.post(new Runnable() { @Override public void run() {
                if (gotReply) {
                    Bus.log("碰一碰: 相册已回复当前选择 " + PENDING.size() + " 张(拉取 " + cost + "ms)");
                } else {
                    android.util.Log.d("NfcTapDemo", "gallery selection request timed out in " + cost + "ms");
                }
                onPeerAfterSelection(payload, role);
            }});
        }}, "gallery-selection-pull").start();
    }

    //pri added by zhuyang,Former onPeer body, unchanged: it decides this round's role from PENDING,20261019,start
    //pri added by zhuyang,Split out only so the tap-time pull can run first without the NFC path waiting on a gallery that is,20261019
    //pri added by zhuyang,absent or does not answer,20261019,end
    private void onPeerAfterSelection(final String payload, final String role) {
        //pri added by zhuyang,Every real tap advances the round token before any early exit, so the next tap can always start,20261020,start
        final long round = Bus.newRoleRound();
        Bus.log("碰触: 新一轮 round=" + round);
        //pri added by zhuyang,Every real tap advances the round token before any early exit, so the next tap can always start,20261020,end
        //pri added by zhuyang,Both sides have no photos: product does nothing at all this round (no page/AP/link),20261019,start
        final String peerPh = field(payload, "ph");
        final int localCount = sGalleryCount;                 //pri added by zhuyang,The gallery selection, never the send queue,20261019
        int peerCount = -1;                                   //pri added by zhuyang,-1 = unknown (old version / debug path without ph),20261019
        try { if (peerPh != null) peerCount = Integer.parseInt(peerPh); } catch (Throwable ignored) {}
        if (localCount <= 0 && peerCount == 0) {
            Bus.log("碰触: 双方都没有照片(本机 " + localCount + ",对端 " + peerCount + "),本轮不做任何事");
            return;
        }
        //pri added by zhuyang,Both sides have no photos: product does nothing at all this round (no page/AP/link),20261019,end
        //pri added by zhuyang,Batch 6: NFC role decides the single AP owner,20261017
        //pri added by zhuyang,D1: drop the previous round's fail banner the moment a new round starts,20261014
        startService(OverlayService.hideFailBannerIntent(this));
        mLinkReady = false; // 新一轮:连接就绪状态重置
        mSendShown = false;    // 新一轮:本轮"发送页已弹"标记清零(替代原来 mSendPage 字段的生命周期)
        mReceiveShown = false; // 同理,接收页标记清零
        mUserWantsSend = false; //pri added by zhuyang,New round: role flags reset,20261014,start
        mSendStarted = false;
        mRoleYielded = false;
        mRejected = false;
        mPeerOffered = false;
        mLastBmp = null;        //pri added by zhuyang,Preview bitmap is sticky across rounds,20261014
        //pri added by zhuyang,Reset the receive counters and viewer list so nothing from the previous round leaks in,20261019,start
        mAccepted = false;
        mPreviewCount = 0;
        mFullCount = 0;
        mTotalCount = 0;
        mTransferDone = false;
        mBmps.clear();
        //pri added by zhuyang,Reset the receive counters and viewer list so nothing from the previous round leaks in,20261019,end
        //pri added by zhuyang,New round: role flags reset,20261014,end
        //pri added by zhuyang,A gallery that publishes its own selection wins over reading the screen: while one is held, the,20261016
        //pri added by zhuyang,fallback below is not run at all (contract: see ACTION_CLEAR_SELECTION). The gallery clears when the user,20261016
        //pri added by zhuyang,leaves, so a published selection can only be live while it is genuinely what the user is looking at,20261016
        if (sPublished) {
            Bus.log("碰一碰: 相册已直接上报当前选择 " + PENDING.size() + " 张,不再读屏幕");
            beginPeerRound(payload, role, round);
            return;
        }
        //pri added by zhuyang,Gallery pickup (the product flow): if the user is in Google Photos looking at a photo, or with several,20261016,start
        //pri added by zhuyang,tiles ticked, the tap must send exactly those - no share step. The lookup needs a screen capture, so it,20261016
        //pri added by zhuyang,runs off the main thread and the rest of the round starts when it is done. When the gallery is not what,20261016
        //pri added by zhuyang,the user is looking at, this costs nothing and the flow below is byte-for-byte the old one,20261016
        if (GalleryPickup.isGalleryForeground()) {
            Bus.log("碰一碰: 相册在前台,先拾取用户正在看/选中的照片");
            new Thread(new Runnable() { @Override public void run() {
                final ArrayList<Uri> picked = GalleryPickup.pickup(TapService.this);
                mMain.post(new Runnable() { @Override public void run() {
                    if (!picked.isEmpty()) {
                        PENDING.clear();
                        PENDING.addAll(picked);
                        sPublished = false; //pri added by zhuyang,Read off the screen, not published: the flag must not stay set,20261016
                        savePending();
                        sGalleryCount = PENDING.size(); //pri added by zhuyang,The picked selection is what the gallery shows: update ph,20261019
                        syncPhotoCount(); //pri added by zhuyang,Pickup replaced the selection: refresh the photo count,20261019
                        Bus.log("相册拾取: 拿到当前正在看/选中的 " + picked.size() + " 张照片,碰一碰直接发送");
                    } else if (GalleryPickup.canReadGallery()) {
                        //pri added by zhuyang,The gallery is on screen and readable, and the user has neither opened nor ticked,20261016,start
                        //pri added by zhuyang,anything - so there is nothing to send from here. A selection left over from an earlier,20261016
                        //pri added by zhuyang,round must NOT be sent as if it were what the user is looking at,20261016
                        PENDING.clear();
                        sPublished = false; //pri added by zhuyang,Cleared from the screen side: nothing is published any more,20261016
                        savePending();
                        sGalleryCount = 0; //pri added by zhuyang,The gallery shows no selection: ph goes to 0,20261019
                        syncPhotoCount(); //pri added by zhuyang,Pickup cleared the stale selection: refresh the photo count,20261019
                        Bus.log("相册拾取: 相册在前台且可读,但用户没有打开或勾选照片,清掉上一轮留下的选择");
                    } else {
                        Bus.log("相册拾取: 读不到相册内容(无障碍未开启),沿用上一轮的选择");
                    }
                    //pri added by zhuyang,The gallery is on screen and readable, and the user has neither opened nor ticked,20261016,end
                    beginPeerRound(payload, role, round);
                }});
            }}, "gallery-pickup").start();
            return;
        }
        //pri added by zhuyang,Gallery pickup (the product flow): if the user is in Google Photos looking at a photo, or with several,20261016,end
        beginPeerRound(payload, role, round);
    }

    //pri added by zhuyang,The body that used to be onPeer's tail, unchanged: it decides this round's role from PENDING,20261016,start
    //pri added by zhuyang,Split out only so the gallery pickup above can run first without the NFC path waiting on it when the,20261016
    //pri added by zhuyang,gallery is not involved,20261016,end
    private void beginPeerRound(String payload, String nfcRole, long round) {
        final String label = peerName(payload);
        Xfer x = xfer(this);
        x.setExpectedPeer(field(payload,"n"), field(payload,"b"), field(payload,"p"),
                field(payload,"a"), field(payload,"ip"), field(payload,"hs"), field(payload,"hp"),
                field(payload,"sid")); //pri added by zhuyang,Carry the peer sid into the first-frame handshake,20261017
        x.startAsReceiver(); // 服务端先就绪
        if (!Bus.lockRole(round)) { Bus.log("角色已定,忽略同一轮的重复碰触事件(round=" + round + ")"); return; }
        mPeerLabel = label;
        //pri modify by zhuyang,Both phones only prepare to receive; the role is decided at swipe time,20261014,start
        x.setCanSend(hasPending()); //pri added by zhuyang,Photos in hand = still undecided: the accept loop must not consume an incoming connection as data,20261014
        //pri added by zhuyang,Batch 6: NFC card side is the single AP owner; reader never opens an AP,20261017,start
        final boolean apOwner;
        if ("card".equals(nfcRole)) {
            apOwner = true;
        } else if ("reader".equals(nfcRole)) {
            apOwner = false;
        } else {
            // Debug am startservice path has no NFC role: the phone without photos owns the AP so
            // two phones that both have photos never open two APs.
            apOwner = !hasPending();
        }
        Bus.log("承载: NFC 角色=" + (nfcRole == null ? "缺失" : nfcRole)
                + " → 本机" + (apOwner ? "是" : "不是") + " AP owner");
        x.setApOwner(apOwner);
        if (apOwner) {
            x.startHotspot();
        } else {
            //pri modify by zhuyang,Tap-time preconnect: a non-owner joins the owner AP even when it holds photos, so the link is ready before the swipe,20261019,start
            x.startHotspotClient();
            //pri modify by zhuyang,Tap-time preconnect: a non-owner joins the owner AP even when it holds photos, so the link is ready before the swipe,20261019,end
        }
        //pri added by zhuyang,Batch 6: NFC card side is the single AP owner; reader never opens an AP,20261017,end
        if (hasPending()) {
            showSendPage();
        } else {
            Bus.log("本机无照片 → 本机是接收候选,等对方上滑");
            showReceivePageEmpty(); //pri added by zhuyang,Empty state: both phones play the same entrance; this one shows the blurred backdrop only,20261014
        }
        //pri modify by zhuyang,Both phones only prepare to receive; the role is decided at swipe time,20261014,end
    }
    //pri added by zhuyang,The body that used to be onPeer's tail, unchanged: it decides this round's role from PENDING,20261016,end

    /**
     * 拉起动效层的发送页(涟漪 + 白线 + 卡片堆叠 + 上滑发送)。
     * 旧实现是 new SendPage(...) 自绘控件 + Overlay.get(this).show(root);现在换成让 Kotlin 动效层
     * 自己起前台服务 + 整屏悬浮窗,本服务只发一条 startForegroundService 指令。
     */
    private void showSendPage() {
        if (PENDING.isEmpty()) return; // 没有待发照片(正常不会走到这):直接返回
        if (mSendShown) return;        // 本轮已经拉起过:幂等,避免重复 start 动效层(替代原来 mSendPage != null 的判断)
        mSendShown = true;
        xfer(this).setPreviewUri(sPreviewOverride); //pri added by zhuyang,Hand the opened photo to Xfer for this round's card preview,20261019
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
            if (mUserWantsSend && !mRoleYielded) {
                if (!mSendStarted) {
                    mSendStarted = true;
                    sXfer.sendImages(new ArrayList<Uri>(PENDING));
                }
            } else if (!mRoleYielded && !mReceiveShown && !mRejected && hasPending()) {
                showSendPage();
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
        if (mUserWantsSend && !mRoleYielded) return; //pri modify by zhuyang,After yielding the send intent no longer blocks the receive card,20261017
        if (mRejected) return;
        mPeerOffered = true; //pri added by zhuyang,Empty state: the peer swiped - a page raise still in flight must not arm the wait,20261014
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
                //pri modify by zhuyang,D5: viewer is our own Activity now, opened on this round's saved MediaStore Uris,20261014
                startService(OverlayService.hideIntent(this, OverlayService.DISSOLVE_MS)); //pri modify by zhuyang,E5: dissolve before detaching (E5 main path),20261014
                openViewer();
                Bus.unlockRole();
            }
        } else if (mAccepted) {
            //pri modify by zhuyang,E5: dissolve the card before the window goes,20261014
            startService(OverlayService.hideIntent(this, OverlayService.DISSOLVE_MS)); // 同上:进查看器前先摘掉动效窗口
            openViewer();
            Bus.unlockRole();
        }
    }

    @Override
    public void onImageSent(int bytes) {
        Bus.log("服务: 发送完成(" + (bytes / 1024) + "KB),悬浮窗关闭,用户仍在相册");
        Bus.unlockRole();
        PENDING.clear();
        sPublished = false; //pri added by zhuyang,Sent: the gallery's published selection is consumed, not held,20261016
        sPreviewOverride = null; //pri added by zhuyang,Consumed with the selection: the next round reports its own opened photo,20261019
        savePending();
        //pri added by zhuyang,PENDING.clear() consumes the send queue only: sGalleryCount still reflects the on-screen selection,20261019
        mSendShown = false; // 本轮结束,下一轮碰触重新拉起
        // 发送完成后摘掉动效窗口(动效层自己也会播"飞走"动画,这里再兜一次底;
        // 用 startService 而不是 stopIntent:服务继续常驻,只是窗口没了)
        startService(OverlayService.hideIntent(this, OverlayService.DISSOLVE_MS)); //pri modify by zhuyang,E5: dissolve before detaching,20261014
    }

    //pri added by zhuyang,Byte-level progress -> the existing status line (sender/receiver wording),20261017,start
    @Override
    public void onProgress(TransferProgress p) {
        if (p == null || p.state != TransferProgress.STATE_TRANSFERRING || p.totalBytes <= 0) return;
        if (mUserWantsSend && !mRoleYielded) {
            int percent = (int) (p.transferredBytes * 100L / p.totalBytes);
            String line = getString(R.string.status_send_progress,
                    p.index, Math.max(p.total, p.index), Math.min(percent, 100), speedText(p.speedBps));
            Bus.log("服务: " + line);
            startService(OverlayService.statusIntent(this, line));
        } else if (mReceiveShown && !mRejected) {
            String line = getString(R.string.status_recv_progress,
                    mbText(p.transferredBytes), mbText(p.totalBytes));
            Bus.log("服务: " + line);
            startService(OverlayService.statusIntent(this, line));
        }
    }

    private static String mbText(long bytes) {
        return String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0);
    }

    private static String speedText(double bps) {
        if (bps >= 1048576.0) return String.format(java.util.Locale.US, "%.1f MB/s", bps / 1048576.0);
        return String.format(java.util.Locale.US, "%.0f KB/s", bps / 1024.0);
    }
    //pri added by zhuyang,Byte-level progress -> the existing status line (sender/receiver wording),20261017,end

    //pri added by zhuyang,Batch 4: partial failure summary "成功 N 张，失败 M 张",20261017,start
    @Override
    public void onTransferSummary(int ok, int failed) {
        final String line = getString(R.string.transfer_summary, ok, failed);
        final boolean sender = mUserWantsSend && !mRoleYielded;
        Bus.log("服务: 传输结果 — " + line + (sender ? "(发送侧)" : "(接收侧)"));
        startService(OverlayService.statusIntent(this, line));
        //pri added by zhuyang,Batch 4: show the banner after the receive card/viewer transition so it stays visible,20261017,start
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                startService(OverlayService.failBannerIntent(TapService.this,
                        getString(sender ? R.string.result_title_send : R.string.result_title_receive), line));
            }
        }, 1200);
        //pri added by zhuyang,Batch 4: show the banner after the receive card/viewer transition so it stays visible,20261017,end
    }
    //pri added by zhuyang,Batch 4: partial failure summary "成功 N 张，失败 M 张",20261017,end

    //pri added by zhuyang,Batch 4: storage precheck refused the offer before the user accepted,20261017,start
    @Override
    public void onStorageInsufficient(long totalBytes) {
        String mb = String.format(java.util.Locale.US, "%.0f", totalBytes / 1048576.0);
        Bus.log("服务: 存储空间不足,拒绝接收 — 需要 " + mb + "MB");
        forwardLegacyFailure(new XferError(XferError.Stage.OFFER, XferError.NO_SPACE, "需要 " + mb + "MB"));
    }
    //pri added by zhuyang,Batch 4: storage precheck refused the offer before the user accepted,20261017,end

    //pri added by zhuyang,Batch 5: one coded failure exit; receiver and sender both get a banner,20261017,start
    @Override
    public void onTransferError(XferError.Stage stage, int code, String detail) {
        handleTransferError(new XferError(stage, code, detail));
    }

    @Override
    public void onXferFailed(String why) {
        //pri added by zhuyang,Batch 5: Xfer emits onTransferError first; never paint twice,20261017
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - mLastErrorAt < 1000) {
            Bus.log("服务: 兼容失败回调已由错误码处理,忽略 — " + why);
            return;
        }
        handleTransferError(new XferError(XferError.Stage.TRANSFER, XferError.UNKNOWN, why));
    }

    private void forwardLegacyFailure(XferError err) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - mLastErrorAt < 1000) {
            Bus.log("服务: 兼容失败回调已由错误码处理,忽略 — " + err);
            return;
        }
        handleTransferError(err);
    }

    private void handleTransferError(XferError err) {
        mLastErrorAt = android.os.SystemClock.elapsedRealtime();
        boolean sender = (mUserWantsSend && !mRoleYielded)
                || (mSendShown && !mReceiveShown && !mRoleYielded);
        String line = failureText(err.code, !sender);
        if (err.detail != null && !err.detail.trim().isEmpty() && err.code != XferError.UNKNOWN) {
            if (!err.detail.startsWith(line)) line = line + " — " + err.detail;
        } else if (err.code == XferError.UNKNOWN && err.detail != null && !err.detail.trim().isEmpty()) {
            line = err.detail;
        }
        Bus.log("服务: 传输错误 " + err + " → " + line + (sender ? "(发送侧)" : "(接收侧)"));
        if (sender) {
            //pri added by zhuyang,Batch 5: drop the full-screen send page so the banner is not covered,20261017
            mSendShown = false;
            startService(OverlayService.statusIntent(this, line));
            final String title = err.code == XferError.NFC_FAIL
                    ? getString(R.string.fail_title_nfc) : getString(R.string.fail_title);
            final String bannerLine = line;
            mMain.postDelayed(new Runnable() {
                @Override public void run() {
                    startService(OverlayService.hideIntent(TapService.this, OverlayService.DISSOLVE_MS));
                    startService(OverlayService.failBannerIntent(TapService.this, title, bannerLine));
                }
            }, 600);
        } else {
            mRejected = true;
            mReceiveShown = false;
            startService(OverlayService.statusIntent(this, line));
            final String bannerLine = line;
            mMain.postDelayed(new Runnable() {
                @Override public void run() {
                    startService(OverlayService.hideIntent(TapService.this, OverlayService.DISSOLVE_MS));
                    startService(OverlayService.failBannerIntent(TapService.this,
                            getString(R.string.fail_title_receive), bannerLine));
                }
            }, 600);
        }
        Bus.unlockRole();
    }

    private String failureText(int code, boolean receiver) {
        switch (code) {
            case XferError.NFC_FAIL: return getString(R.string.err_nfc);
            case XferError.CONNECT_TIMEOUT: return getString(R.string.err_connect_timeout);
            case XferError.CONNECT_REFUSED: return getString(R.string.err_connect_refused);
            case XferError.AUTH_FAILED: return getString(R.string.err_auth_failed);
            case XferError.OFFER_TIMEOUT:
                return getString(receiver ? R.string.err_offer_timeout_receive : R.string.err_offer_timeout_send);
            case XferError.PEER_REJECTED: return getString(R.string.err_peer_rejected);
            case XferError.NO_SPACE: return getString(R.string.err_no_space);
            case XferError.TRANSFER_TIMEOUT: return getString(R.string.err_transfer_timeout);
            case XferError.TRANSFER_STALL: return getString(R.string.err_transfer_stall);
            case XferError.CHECKSUM_FAILED: return getString(R.string.err_checksum);
            case XferError.PROTOCOL_ERROR: return getString(R.string.err_protocol);
            case XferError.CANCELLED: return getString(R.string.err_cancelled);
            case XferError.ROLE_CONFLICT: return getString(R.string.err_role_conflict);
            case XferError.DONE_ACK_TIMEOUT: return getString(R.string.err_done_ack_timeout);
            default: return getString(R.string.err_unknown);
        }
    }
    //pri added by zhuyang,Batch 5: one coded failure exit; receiver and sender both get a banner,20261017,end

    //pri added by zhuyang,Batch 5: NFC exchange failure gets the same stable banner,20261017
    private void reportNfcFailure(Throwable t) {
        XferError err = new XferError(XferError.Stage.NFC_EXCHANGE, XferError.NFC_FAIL, "NFC 交换失败: " + t);
        Bus.log("服务: " + err);
        mLastErrorAt = android.os.SystemClock.elapsedRealtime();
        String line = getString(R.string.err_nfc);
        startService(OverlayService.statusIntent(this, line));
        startService(OverlayService.failBannerIntent(this, getString(R.string.fail_title_nfc), line));
        Bus.unlockRole();
    }

    //pri added by zhuyang,Two-phase protocol callbacks,20261014,start
    @Override public void onOfferExpired() {
        Bus.log("服务: 对方超时未发货,本轮结束");
        forwardLegacyFailure(new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT, null));
    }

    @Override public void onPeerRejected() {
        Bus.log("服务: 对方拒收");
        forwardLegacyFailure(new XferError(XferError.Stage.OFFER, XferError.PEER_REJECTED, null));
    }

    @Override public void onPeerAccepted() {
        Bus.log("服务: 对方已接收");
        //pri added by zhuyang,Batch 2: status line shows transfer is now running,20261017
        startService(OverlayService.statusIntent(this, getString(R.string.status_peer_transferring)));
    }

    @Override public void onVerdictTimeout() {
        Bus.log("服务: 对方未响应(等回执超时)");
        forwardLegacyFailure(new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT, null));
    }
    //pri added by zhuyang,Two-phase protocol callbacks,20261014,end

    //pri added by zhuyang,Role convergence UI: peer swiped first / this phone yielded,20261017,start
    @Override public void onPeerSwipedFirst() {
        Bus.log("服务: 对端先上滑(ROLE=S),本机准备接收");
        mMain.post(new Runnable() { @Override public void run() {
            if (mRejected) return;
            //pri added by zhuyang,The peer swiped, so an empty-state raise still in flight must not arm the wait again,20261019,start
            mPeerOffered = true;
            //pri added by zhuyang,The peer swiped, so an empty-state raise still in flight must not arm the wait again,20261019,end
            if (mReceiveShown) {
                //pri modify by zhuyang,Release the waiting receive page (empty state or an already raised card): ACTION_PEER is the only thing that clears waitingForPeer,20261019,start
                startService(OverlayService.peerIntent(TapService.this, mPeerLabel, Math.max(1, mTotalCount)));
                //pri modify by zhuyang,Release the waiting receive page (empty state or an already raised card): ACTION_PEER is the only thing that clears waitingForPeer,20261019,end
                return;
            }
            if (mSendShown || hasPending()) {
                switchSendPageToReceive(); // 发送页就地切成接收卡,复用既有动效
            } else {
                showReceivePage(mPeerLabel, 0);
            }
            // Bus.lockRole stays held: this round continues as the receiver.
        }});
    }

    @Override public void onRoleYielded() {
        Bus.log("服务: 角色裁决本机让出(败方),转为接收方");
        mMain.post(new Runnable() { @Override public void run() {
            mRoleYielded = true;
            mUserWantsSend = false;
            if (mRejected) return;
            if (mReceiveShown) {
                //pri added by zhuyang,Same release as onPeerSwipedFirst: a page raised for the empty state is still waiting for ACTION_PEER,20261019,start
                startService(OverlayService.peerIntent(TapService.this, mPeerLabel, Math.max(1, mTotalCount)));
                //pri added by zhuyang,Same release as onPeerSwipedFirst: a page raised for the empty state is still waiting for ACTION_PEER,20261019,end
            } else {
                switchSendPageToReceive(); // 让出后用户只应看到接收卡
            }
            // Bus.lockRole stays held: this round continues as the receiver, released on accept/reject/failure.
        }});
    }
    //pri added by zhuyang,Role convergence UI: peer swiped first / this phone yielded,20261017,end

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
                if (ex == null) { //pri added by zhuyang,Explicit payload failure aborts the read without touching the UI flow,20261017
                    Bus.log("读卡: 本机 payload 超长,无法发起 EXCHANGE,本轮中止");
                    return;
                }
                iso.setTimeout(3000);
                if (!iso.isConnected()) iso.connect();
                Bus.log("NFC 链路已建立(ISO-DEP)");
                byte[] r = iso.transceive(Bus.SELECT_AID_APDU);
                if (!Bus.isOk(r)) { Bus.log("对方未接受我们的 AID"); return; }
                Bus.log("读卡: SELECT 已通过,发 EXCHANGE " + ex.length + "B");
                byte[] resp = iso.transceive(ex);
                String peer = Bus.parseExchangeResponse(resp);
                if (peer == null) { Bus.log("响应为空"); return; }
                Bus.log("收到对方信息: " + Bus.redactPayload(peer)); //pri modify by zhuyang,Redact sid and hotspot password,20261017
                Bus.onPeerData(peer, "reader");
            } catch (Throwable t) {
                Bus.log("读取失败: " + t + " — 贴紧约 1 秒再分开,重试一次");
                reportNfcFailure(t);
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
        //pri modify by zhuyang,Use the real announced count; the bottom status line stays empty until real transfer progress arrives,20261019
        startForegroundService(OverlayService.receiveIntent(this, label, Math.max(1, count), previewUriOrNull()));
    }

    //pri added by zhuyang,Empty state (E7): the phone with no photo selected raises the same page,20261014,start
    /**
     * Empty state: this phone has no photo, but a tap must still open the page so both phones play
     * the same entrance animation (reference product). The difference is only what is visible:
     * here the title / card / pills / status line are all held back by receiveIntent's waiting extra,
     * and they appear when the peer swipes (peerIntent / photoIntent).
     *
     * The backdrop is the silent screen capture of this phone's own screen (same channel and the same
     * bounded timeout as the send page, taken on a worker thread before the window is attached), so the
     * empty state shows a blurred desktop instead of a flat dark fill. Capture failed / timed out ->
     * null, and the motion layer keeps its dark fill fallback.
     */
    private void showReceivePageEmpty() {
        if (mReceiveShown) return; //pri added by zhuyang,Idempotent like showReceivePage: this round already raised a page,20261014
        mReceiveShown = true;
        mAccepted = false; mPreviewCount = 0; mFullCount = 0;
        mTransferDone = false; mBmps.clear();
        new Thread(new Runnable() { @Override public void run() {
            final Uri captured = ScreenCapture.captureBackdrop(TapService.this, CAPTURE_TIMEOUT_MS);
            mMain.post(new Runnable() { @Override public void run() {
                if (!mReceiveShown) return; //pri added by zhuyang,Round ended while the capture was in flight (timeout / reject): do not raise,20261014
                if (mPeerOffered) {
                    //pri added by zhuyang,The peer swiped while the capture was in flight: raise the ordinary receive card instead,20261014
                    //pri added by zhuyang,(nothing is waiting any more, so the wait must not be armed again),20261014
                    mReceiveShown = false; //pri added by zhuyang,Let the idempotent showReceivePage do the real raise of this round,20261014
                    showReceivePage(mPeerLabel, mTotalCount);
                    return;
                }
                if (captured == null) Bus.log("截屏失败/超时,空态页背景回退为深色");
                startForegroundService(OverlayService.receiveIntent(TapService.this, mPeerLabel, captured));
                //pri added by zhuyang,No statusIntent at all: the empty state shows nothing but the backdrop,20261014
            }});
        }}).start();
    }
    //pri added by zhuyang,Empty state (E7): the phone with no photo selected raises the same page,20261014,end

    //pri added by zhuyang,Loser side of the race: the live send page becomes the receive card in place,20261014,start
    private void switchSendPageToReceive() {
        mSendShown = false;
        mReceiveShown = true;
        mAccepted = false; mPreviewCount = 0; mFullCount = 0; mTransferDone = false;
        mBmps.clear(); mLastBmp = null;
        startForegroundService(OverlayService.receiveIntent(this, mPeerLabel,
                Math.max(1, mTotalCount), previewUriOrNull()));
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

    //pri added by zhuyang,D5: open this round's saved photos in our own viewer,20261014,start
    //pri added by zhuyang,Uris come from the MediaStore records Xfer made while saving; nothing saved = log only,20261014
    //pri added by zhuyang,Uris (not bitmaps) cross the Intent boundary: the saved list is already there and stays small,20261014
    //pri modify by chenpengqi,Hand the saved public MediaStore item to Google Photos first (private/cache URIs are unreadable to another process), fall back to our own viewer,20261019,start
    private static final String PHOTOS_PACKAGE = "com.google.android.apps.photos";

    private void openViewer() {
        Uri first = (sXfer != null) ? sXfer.firstSavedUri() : null;
        if (first == null) { Bus.log("接收完成:本轮没有落盘 Uri,不打开查看器"); return; }
        final ArrayList<Uri> uris = sXfer.savedUris();
        if (uris.isEmpty()) uris.add(first); //pri added by zhuyang,Defensive: never open the viewer with an empty list,20261014
        if (uris.size() > 1) Bus.log("接收完成:本轮共 " + uris.size() + " 张,相册只展示第一张");
        //pri added by chenpengqi,Wait for the dissolve animation to finish so the gallery is not covered by the still-fading window,20261019
        final Uri target = uris.get(0);
        mMain.postDelayed(new Runnable() { @Override public void run() {
            if (openInGooglePhotos(target)) return;
            openOwnViewer(uris);
        }}, OverlayService.DISSOLVE_MS + 80L);
    }

    /** 把媒体库里的公共 content:// 交给 Google 相册;拉起失败返回 false,由调用方回退到应用内查看器。 */
    private boolean openInGooglePhotos(Uri media) {
        final Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(media, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(new Intent(view).setPackage(PHOTOS_PACKAGE));
            Bus.log("接收完成:已用 Google 相册打开 " + media);
            return true;
        } catch (Throwable t) {
            Bus.log("接收完成:显式拉起 Google 相册失败(" + t + "),回退系统图片查看器");
        }
        try {
            startActivity(Intent.createChooser(view, "查看图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Bus.log("接收完成:已交给系统图片查看器 " + media);
            return true;
        } catch (Throwable t) {
            Bus.log("接收完成:系统查看器也失败: " + t);
            return false;
        }
    }

    /** 回退:应用内查看器(可翻页看本轮全部照片)。 */
    private void openOwnViewer(ArrayList<Uri> uris) {
        try {
            //pri added by zhuyang,D5: the target is our own Activity, so the transition is ours to kill: the viewer
            //pri added by zhuyang,theme sets windowAnimationStyle=@null (the load-bearing part - a Service cannot call
            //pri added by zhuyang,overridePendingTransition); the options below plus NO_ANIMATION are belt-and-braces,20261014
            Bus.log("接收完成:打开应用内查看器 " + uris.size() + " 张");
            startActivity(new Intent(this, ImageViewerActivity.class)
                            .putParcelableArrayListExtra(ImageViewerActivity.EXTRA_URIS, uris)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        } catch (Throwable t) {
            Bus.log("打开应用内查看器失败: " + t);
        }
    }
    //pri modify by chenpengqi,Hand the saved public MediaStore item to Google Photos first, fall back to our own viewer,20261019,end
    //pri added by zhuyang,D5: open this round's saved photos in our own viewer,20261014,end

    private static String field(String payload, String key) {
        for (String kv : payload.split("\\|")) {
            if (kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
        }
        return null;
    }
}
