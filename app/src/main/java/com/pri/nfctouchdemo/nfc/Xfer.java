package com.pri.nfctouchdemo.nfc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;

/**
 * Wi-Fi Direct + TCP 传图。组主(GO)由系统决定,不假设角色:
 *  - 组主:         起 ServerSocket 监听,收到连接后 —— 本机是接收方就直接读图显示;
 *                  本机是发送方则暂存连接,等用户点「发送」再写图。
 *  - 组员:         接收方主动连组主读取;发送方等用户点「发送」时连组主写图。
 * 只与「碰一碰交换到的对方 P2P 设备名」建立直连, 避免连上附近的电脑/其他 P2P 设备。
 */
public class Xfer {

    public interface Listener {
        void onP2pReady(boolean isGroupOwner, String goAddress);
        void onPreview(Bitmap bmp);                    // 对方发来的预览缩略图(接收确认页显示用)
        void onSendStarted(int total);                 // 对方预告本次共发几张(接收页文案用)
        void onImageReceived(int index, int total, Bitmap bmp, int bytes, boolean saved); // 每张整图都回调
        void onTransferDone(java.util.List<Bitmap> bmps);  // 对方发送完毕(连接关闭),bmps=本次收到的全部整图
        void onImageSent(int bytes);
        void onXferFailed(String why);
        void onOfferExpired();                         //pri added by zhuyang,Receiver: peer never delivered the offer,20261014
        void onPeerRejected();                         //pri added by zhuyang,Sender: peer rejected the transfer,20261014
        void onPeerAccepted();                         //pri added by zhuyang,Sender: peer accepted the transfer,20261014
        void onVerdictTimeout();                       //pri added by zhuyang,Sender: no verdict within VERDICT_WAIT_MS,20261014
        void onPeerSwipedFirst();                      //pri added by zhuyang,Peer sent ROLE(S) before this phone swiped: switch to the receive card,20261017
        void onRoleYielded();                          //pri added by zhuyang,This phone lost the role race: stop sending and switch to the receive card,20261017
    }

    private static final int PORT = 8988;
    //pri modify by zhuyang,Heartbeat and business frames share this lock so the wire never interleaves,20261017,start
    private final java.util.concurrent.locks.ReentrantLock mWriteLock =
            new java.util.concurrent.locks.ReentrantLock();
    //pri modify by zhuyang,Heartbeat and business frames share this lock so the wire never interleaves,20261017,end
    public static final String HS_SSID = "TapShare-Transfer";
    public static final String HS_PASS = "tapshare2026";
    private static final int VERDICT_WAIT_MS = 20000;  //pri modify by zhuyang,Shorten the verdict wait from 60s to 20s,20261017
    //pri added by zhuyang,Liveness limits: read cap, PING idle, dead peer, write stall and round total,20261017,start
    private static final int RX_TIMEOUT_MS = 30000;      // read cap before chunking lands (batch 3 tightens to 10s)
    private static final int HEARTBEAT_IDLE_MS = 2000;   // no frame written for this long -> PING
    private static final int HEARTBEAT_DEAD_MS = 10000;  // no byte received for this long -> link is dead
    private static final int HEARTBEAT_TICK_MS = 500;    // heartbeat check period
    private static final int WRITE_STALL_MS = 10000;     // transfer phase: no write progress -> close
    private static final long TRANSFER_TOTAL_MS = 5 * 60 * 1000L; // one photo round is capped at 5 minutes
    private static final int OFFER_WAIT_MS = 20000;      // connected receiver waiting for the peer's offer
    private static final int WRITE_CHUNK = 64 * 1024;    // refresh write progress inside one large frame
    //pri added by zhuyang,Liveness limits: read cap, PING idle, dead peer, write stall and round total,20261017,end
    private static final int PROBE_MS = 3000;          //pri added by zhuyang,An accepted connection that stays silent this long carries no incoming data,20261014
    private static final int MAX_UNAUTH_CONNECTIONS = 4; //pri added by zhuyang,Cap concurrent connections that still owe a HELLO,20261017
    private static final int ROLE_CONFLICT_WINDOW_MS = 300; //pri added by zhuyang,Wait window after our own swipe for a peer ROLE(S) before offering,20261017
    private static final int ROLE_READ_TIMEOUT_MS = 1500;   //pri added by zhuyang,Wait this long for the peer ROLE frame after HELLO_ACK,20261017
    private static final int ROLE_LISTEN_TIMEOUT_MS = 1000; //pri added by zhuyang,Upper bound for joining the role listener before reusing its stream,20261017
    private static final int VERDICT_YIELDED = -1;          //pri added by zhuyang,readVerdict result: this phone yielded and now receives,20261017

    private final Context mCtx;
    private final java.util.List<Listener> mAll =
            new java.util.concurrent.CopyOnWriteArrayList<Listener>();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** 接收端后台队列:解码+存相册不占 socket 读线程(否则发送端被 TCP 背压卡住,实测每张卡 ~2.5s)。 */
    private final java.util.concurrent.ExecutorService mIo =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** 事件转发器:支持多个监听者(Activity 与服务各自订阅)。 */
    private final Listener mListener = new Listener() {
        @Override public void onP2pReady(boolean isGroupOwner, String goAddress) {
            for (Listener l : mAll) l.onP2pReady(isGroupOwner, goAddress);
        }
        @Override public void onPreview(Bitmap b) {
            for (Listener l : mAll) l.onPreview(b);
        }
        @Override public void onSendStarted(int t) {
            for (Listener l : mAll) l.onSendStarted(t);
        }
        @Override public void onImageReceived(int i, int t, Bitmap b, int c, boolean d) {
            for (Listener l : mAll) l.onImageReceived(i, t, b, c, d);
        }
        @Override public void onTransferDone(java.util.List<Bitmap> b) {
            for (Listener l : mAll) l.onTransferDone(b);
        }
        @Override public void onImageSent(int b) {
            for (Listener l : mAll) l.onImageSent(b);
        }
        @Override public void onXferFailed(String w) {
            for (Listener l : mAll) l.onXferFailed(w);
        }
        @Override public void onOfferExpired() {
            for (Listener l : mAll) l.onOfferExpired();
        }
        @Override public void onPeerRejected() {
            for (Listener l : mAll) l.onPeerRejected();
        }
        @Override public void onPeerAccepted() {
            for (Listener l : mAll) l.onPeerAccepted();
        }
        @Override public void onVerdictTimeout() {
            for (Listener l : mAll) l.onVerdictTimeout();
        }
        @Override public void onPeerSwipedFirst() {
            for (Listener l : mAll) l.onPeerSwipedFirst();
        }
        @Override public void onRoleYielded() {
            for (Listener l : mAll) l.onRoleYielded();
        }
    };

    public void addListener(Listener l) {
        if (l != null && !mAll.contains(l)) mAll.add(l);
    }

    //pri added by zhuyang,Keep the handshake streams with the socket: later frames must reuse them,20261017,start
    private static final class Link {
        final Socket s;
        final DataInputStream in;
        final DataOutputStream out;
        //pri added by zhuyang,Per-link liveness state shared by the reader, writer and heartbeat,20261017,start
        final java.util.concurrent.atomic.AtomicLong lastRx;   // last byte received (input progress)
        final java.util.concurrent.atomic.AtomicLong lastTx;   // last frame written (idle PING scheduling)
        volatile long lastWriteProgressAt;                     // last business frame/chunk written
        volatile long hbStartedAt;                             // round total deadline base
        volatile boolean hbRunning;
        volatile boolean hbWritePhase;                         // true after ACCEPT: watchdog instead of rx-dead
        volatile boolean hbExpectRx;                           // false while the sender is still writing the offer
        final java.util.concurrent.atomic.AtomicBoolean linkDead =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean failCb =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean offerExpiredCb =
                new java.util.concurrent.atomic.AtomicBoolean();
        volatile String failReason;
        volatile Thread hbThread;
        //pri added by zhuyang,Per-link liveness state shared by the reader, writer and heartbeat,20261017,end
        String peerRole;        // ROLE read right after HELLO_ACK (null = old peer/timeout)
        String peerSid;         // sid carried by that ROLE
        Proto.Frame first;      // non-ROLE first frame already read (1a compatibility)

        Link(Socket s, DataInputStream in, DataOutputStream out,
             java.util.concurrent.atomic.AtomicLong lastRx,
             java.util.concurrent.atomic.AtomicLong lastTx) {
            this.s = s;
            this.in = in;
            this.out = out;
            this.lastRx = lastRx;
            this.lastTx = lastTx;
            this.lastWriteProgressAt = lastRx.get();
        }
    }

    //pri added by zhuyang,Count received bytes so a large in-flight frame keeps the link alive,20261017,start
    private static final class RxProgress extends java.io.InputStream {
        private final java.io.InputStream in;
        private final java.util.concurrent.atomic.AtomicLong stamp;

        RxProgress(java.io.InputStream in, java.util.concurrent.atomic.AtomicLong stamp) {
            this.in = in;
            this.stamp = stamp;
        }

        @Override public int read() throws java.io.IOException {
            int b = in.read();
            if (b >= 0) stamp.set(SystemClock.elapsedRealtime());
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = in.read(b, off, len);
            if (n > 0) stamp.set(SystemClock.elapsedRealtime());
            return n;
        }

        @Override public int available() throws java.io.IOException {
            return in.available();
        }

        @Override public void close() throws java.io.IOException {
            in.close();
        }
    }
    //pri added by zhuyang,Count received bytes so a large in-flight frame keeps the link alive,20261017,end
    //pri added by zhuyang,Keep the handshake streams with the socket: later frames must reuse them,20261017,end

    private final WifiP2pManager mP2p;
    private final WifiP2pManager.Channel mChannel;
    private BroadcastReceiver mRx;
    private ServerSocket mServer;
    //pri added by zhuyang,Role convergence: the send channel and the inbound/stash channel are separate handles,20261017,start
    private volatile Link mSendSocket;      // link used by this phone to send ROLE/OFFER/FULL
    private volatile Link mStashedSocket;   // accepted link kept for role probing or receiving
    private volatile Link mPeerSocket;      // receive link: the verdict is written back on it
    private volatile String mPeerRole = "U";            // last role announced by the peer (U/S/R)
    private volatile long mPeerSwipedAt;                // elapsedRealtime when the peer ROLE(S) was read
    private volatile long mLocalSwipedAt;               // elapsedRealtime when the local user swiped
    private volatile boolean mPeerWantsSend;            // peer announced ROLE(S) this round
    private volatile boolean mYielded;                  // this phone lost the role race
    private volatile Thread mRoleListenerThread;        // at most one role listener at a time
    private volatile Link mRoleListenerAbort;           // listener on this link must exit without closing it
    //pri added by zhuyang,Frames read on the send link before losing the race; replayed to the receiver,20261017
    private final java.util.List<Proto.Frame> mPreReadFrames = new java.util.ArrayList<Proto.Frame>();
    //pri added by zhuyang,Role convergence: the send channel and the inbound/stash channel are separate handles,20261017,end
    private volatile boolean mVerdictSent;  //pri added by zhuyang,Verdict is written back at most once per round,20261014
    private volatile boolean mDoneNotified; //pri added by zhuyang,"transfer done" is delivered at most once per connection,20261014
    private volatile int mExpect;           //pri added by zhuyang,Total image count announced by TYPE_META this round,20261014
    private volatile int mRound;            //pri added by zhuyang,Round token: a stale verdict must not leak into the next round,20261014
    private volatile Uri mFirstSavedUri;    //pri added by zhuyang,MediaStore Uri of the first image saved this round,20261014
    //pri added by zhuyang,Every MediaStore Uri saved this round in receive order: the in-app viewer pages through them,20261014
    //pri added by zhuyang,CopyOnWriteArrayList because saveToGallery runs on the IO thread while the viewer list is read on the main thread,20261014
    private final java.util.List<Uri> mSavedUris = new java.util.concurrent.CopyOnWriteArrayList<Uri>();

    private boolean mReceiverRole;
    private volatile boolean mCanSend;   //pri added by zhuyang,Holds photos and has not swiped: may still become the sender,20261014
    private volatile boolean mDriveP2p;  //pri added by zhuyang,Sender whose hotspot/LAN attempts failed: only this phone drives the P2P fallback,20261014
    private volatile boolean mRunning = true;
    private volatile String mGoAddress;
    private volatile boolean mImGo;
    private volatile boolean mConnecting;
    private volatile boolean mDiscovering;
    private int mRetry;
    private boolean mSenderWaiting;

    // 碰一碰交换到的对方设备名/IP(用于挑对目标)
    private volatile String mPeerName;
    private volatile String mPeerBt;
    private volatile String mPeerP2pName;
    private volatile String mPeerP2pAddr;
    private volatile String mPeerLanIp;
    private volatile String mPeerHsSsid;
    private volatile String mPeerHsPass;
    private volatile String mPeerSid; //pri added by zhuyang,Peer session id from the NFC payload; null means the debug path skips HELLO,20261017
    private volatile boolean mGotConnection;
    //pri added by zhuyang,Counter of accepted sockets whose HELLO has not been checked yet,20261017
    private final java.util.concurrent.atomic.AtomicInteger mUnauthCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean mHasPhotos; //pri added by zhuyang,Whether this phone still holds photos (HELLO hp field),20261017
    private volatile WifiManager.LocalOnlyHotspotReservation mHotspotReservation;
    private volatile boolean mHotspotActive;
    private volatile boolean mHotspotStarting;
    private volatile boolean mPauseWarm;   // P2P 搜索与热点互斥(部分芯片不支持同时进行)
    private volatile android.net.ConnectivityManager.NetworkCallback mWifiNetCb;
    private int mIdRetry;

    public Xfer(Context ctx, Listener listener) {
        mCtx = ctx.getApplicationContext();
        addListener(listener);
        mP2p = (WifiP2pManager) mCtx.getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = (mP2p == null) ? null : mP2p.initialize(mCtx, Looper.getMainLooper(), null);
        fetchSelfIdentity();
    }

    /** 读取本机在 Wi-Fi Direct 里的名字/地址(Android_xxxx 这种),供对方精确匹配。 */
    private void fetchSelfIdentity() {
        if (mP2p == null || mChannel == null || Build.VERSION.SDK_INT < 29) return;
        try {
            mP2p.requestDeviceInfo(mChannel, new WifiP2pManager.DeviceInfoListener() {
                @Override public void onDeviceInfoAvailable(WifiP2pDevice device) {
                    if (device != null && device.deviceName != null) {
                        Bus.setP2pIdentity(device.deviceName, device.deviceAddress);
                    } else if (mIdRetry++ < 10) {
                        mMain.postDelayed(new Runnable() {
                            @Override public void run() { if (mRunning) fetchSelfIdentity(); }
                        }, 3000);
                    }
                }
            });
        } catch (Throwable t) {
            Bus.log("P2P: 读取本机 P2P 信息失败 " + t);
        }
    }

    public void setExpectedPeer(String modelName, String btName, String p2pName, String p2pAddr,
                                String lanIp, String hsSsid, String hsPass, String peerSid) {
        // 每轮碰触只会走到这里一次（TapService 解析出对方 payload 后设目标），所以这里就是
        // "新一轮开始"的钩子：先清掉上一轮的连接状态。不清的话会踩这个坑 ——
        //   mGoAddress / mSenderWaiting 只要连成过一次就永远非空/为真，而 TapService 是常驻服务、
        //   进程不重启 ⇒ 第一轮成功之后，之后每一轮都在早退分支上直接返回：
        //     · 接收方 startAsReceiver() 因 mGoAddress != null 直接 return，startServer() 根本不执行
        //       （日志里没有"已监听端口 8988"，/proc/net/tcp 里也没有 8988 的 LISTEN）；
        //     · 发送方 startAsSender() 因 mSenderWaiting 直接 return（"已在等待对方发起直连"）。
        //   现象正是"刚融合好时是好的，后来再也连不上"。
        // 已经有一条活着的连接（对方连上了、正在传/待发）就**别动它**，只更新目标信息 ——
        // 否则本轮里 setExpectedPeer 被再调一次（又碰了一下）会把正在用的 socket 和 8988
        // 监听一起关掉，那比不修还糟。
        if (!hasLiveLink()) resetRoundState();
        mPeerName = modelName;
        mPeerBt = btName;
        mPeerP2pName = p2pName;
        mPeerP2pAddr = p2pAddr;
        mPeerLanIp = lanIp;
        mPeerHsSsid = hsSsid;
        mPeerHsPass = hsPass;
        mPeerSid = (peerSid == null || peerSid.isEmpty()) ? null : peerSid;
        //pri added by zhuyang,The debug path has no sid: both ends must skip HELLO and say so in the log,20261017,start
        if (mPeerSid == null) {
            Bus.log("传输: [未认证] 本轮对方 payload 没有 sid,跳过 HELLO 握手(调试路径)");
        } else {
            Bus.log("传输: [认证] 本轮将在每条 TCP 连接首帧校验对方 sid");
        }
        //pri added by zhuyang,The debug path has no sid: both ends must skip HELLO and say so in the log,20261017,end
        Bus.log("P2P: 目标设备 = " + modelName + "/" + btName
                + (p2pName != null ? ", P2P名=" + p2pName : "")
                + (p2pAddr != null ? ", P2P地址=" + p2pAddr : "")
                + (lanIp != null ? ", 局域网IP=" + lanIp : "")
                + (hsSsid != null ? ", 对方热点=" + hsSsid : ""));
    }

    /**
     * 清掉上一轮的连接状态（组地址 / 等待标志 / 服务端 socket）。
     *
     * 只在 [setExpectedPeer]（每轮碰触一次）里调用。**不要**把它塞进 [startAsReceiver] /
     * [startAsSender]：那两个在同一轮里可能被调两次（它们开头的幂等早退就是为这个准备的），
     * 放进去会把同一轮刚起来的服务端又关掉。
     */
    /** 手上有一条真正接上的 socket（发送通道 / 暂存通道 / 接收通道）。 */
    private boolean hasLiveLink() {
        return isAlive(mSendSocket) || isAlive(mStashedSocket) || isAlive(mPeerSocket);
    }

    //pri added by zhuyang,A Link counts only while its socket is connected and open,20261017
    private static boolean isAlive(Link l) {
        return l != null && l.s.isConnected() && !l.s.isClosed();
    }

    private void resetRoundState() {
        if (mGoAddress != null || mSenderWaiting || mServer != null || mGotConnection) {
            Bus.log("传输: 新一轮开始,清掉上一轮状态(组主=" + mGoAddress + ", 等直连=" + mSenderWaiting
                    + ", 已连接=" + mGotConnection + ", 服务端=" + (mServer != null) + ")");
        }
        mGoAddress = null;
        mImGo = false;
        mConnecting = false;
        mDiscovering = false;
        mGotConnection = false;
        mSenderWaiting = false;
        mRetry = 0;
        mPauseWarm = false;
        mReceiverRole = false;
        mCanSend = false;   //pri added by zhuyang,New round: undecided until TapService reports the photos,20261014
        mHasPhotos = false; //pri added by zhuyang,New round: no photos reported yet,20261017
        mPeerSid = null;    //pri added by zhuyang,New round: the peer sid is set again from the new payload,20261017
        mDriveP2p = false;  //pri added by zhuyang,New round: the P2P fallback needs a fresh decision,20261014
        //pri added by zhuyang,Bump the round token and drop the verdict session state of the previous round,20261014,start
        mRound++;
        //pri added by zhuyang,Drop every link of the previous round, including the role listener's,20261017,start
        stopHeartbeat(mPeerSocket);
        stopHeartbeat(mStashedSocket);
        stopHeartbeat(mSendSocket);
        closeQuietly(mPeerSocket);
        mPeerSocket = null;
        closeQuietly(mStashedSocket);
        mStashedSocket = null;
        closeQuietly(mSendSocket);
        mSendSocket = null;
        mRoleListenerThread = null; // its socket is closed, the thread exits on its own
        mRoleListenerAbort = null;
        mPeerRole = "U";
        mPeerSwipedAt = 0;
        mLocalSwipedAt = 0;
        mPeerWantsSend = false;
        mYielded = false;
        synchronized (mPreReadFrames) { mPreReadFrames.clear(); }
        //pri added by zhuyang,Drop every link of the previous round, including the role listener's,20261017,end
        mVerdictSent = false;
        mDoneNotified = false;
        mExpect = 0;
        mFirstSavedUri = null;
        mSavedUris.clear(); //pri added by zhuyang,New round: the viewer list of the previous round must not leak into this one,20261014
        //pri added by zhuyang,Bump the round token and drop the verdict session state of the previous round,20261014,end
        try { if (mServer != null) mServer.close(); } catch (Exception ignored) {}
        mServer = null;
    }

    /** 接收方按下按钮时即开启本地热点,把 SSID/密码写进 payload,让对方免搜索直连。 */
    public void startHotspot() {
        if (mHotspotReservation != null || mHotspotStarting) return;
        try {
            final WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            // 本机型热点与 Wi-Fi 互斥(无 bridged-AP 能力):Wi-Fi 开着时必然失败,直接跳过走同网/P2P
            if (wm.isWifiEnabled()) {
                Bus.log("热点: Wi-Fi 正开着,本机型热点与 Wi-Fi 互斥 — 跳过热点(走同网直连/P2P)");
                return;
            }
            mHotspotStarting = true;
            mReceiverRole = true;
            startServer(); // 提前监听 8988:消除"对方连过来时服务端还没起"的竞态
            mPauseWarm = true; // 先停 P2P 搜索:部分芯片不支持"搜索+热点"同时进行
            try { mP2p.stopPeerDiscovery(mChannel, null); } catch (Throwable ignored) {}
            Bus.log("热点: 正在开启本地热点(给对方免搜索直连用)…");
            WifiManager.LocalOnlyHotspotCallback cb = new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    mHotspotReservation = reservation;
                    mHotspotActive = true;
                    android.net.wifi.SoftApConfiguration cfg = reservation.getSoftApConfiguration();
                    if (cfg != null && cfg.getSsid() != null) {
                        Bus.setHotspotInfo(cfg.getSsid(), cfg.getPassphrase());
                        Bus.log("热点: 已开启 SSID=" + cfg.getSsid() + ",对方碰一下即可直连");
                    } else {
                        Bus.log("热点: 已开启但拿不到 SSID");
                    }
                }
                @Override public void onFailed(int reason) {
                    String r;
                    switch (reason) {
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                            r = "INCOMPATIBLE_MODE(当前模式不支持)"; break;
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                            r = "NO_CHANNEL"; break;
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                            r = "TETHERING_DISALLOWED"; break;
                        default:
                            r = "GENERIC";
                    }
                    mHotspotStarting = false;
                    Bus.log("热点: 开启失败 " + r + ",恢复 P2P 参与,走局域网/P2P 兜底");
                    mHotspotActive = false;
                    mPauseWarm = false;
                }
                @Override public void onStopped() {
                    mHotspotActive = false;
                    mPauseWarm = false;
                    mHotspotStarting = false;
                    Bus.log("热点: 已停止");
                }
            };
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    android.net.wifi.SoftApConfiguration cfg =
                            new android.net.wifi.SoftApConfiguration.Builder()
                                    .setWifiSsid(android.net.wifi.WifiSsid.fromBytes(HS_SSID.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                    .setPassphrase(HS_PASS,
                                            android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                                    .build();
                    wm.startLocalOnlyHotspotWithConfiguration(cfg, mCtx.getMainExecutor(), cb);
                    Bus.log("热点: 固定配置已发起(SSID=" + HS_SSID + ")");
                } else {
                    wm.startLocalOnlyHotspot(cb, mMain);
                }
            } catch (Throwable t) {
                Bus.log("热点: 固定配置不可用,退回默认: " + t);
                try { wm.startLocalOnlyHotspot(cb, mMain); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            mPauseWarm = false;
            Bus.log("热点: 开启异常 " + t);
        }
    }

    private void closeHotspot() {
        try { if (mHotspotReservation != null) mHotspotReservation.close(); } catch (Throwable ignored) {}
        mHotspotReservation = null;
        mHotspotActive = false;
        mPauseWarm = false;
    }

    /** 关闭热点(调试用)。 */
    public void stopHotspot() {
        closeHotspot();
    }

    /** 应用打开即预热搜索:碰完之后只剩连接协商,大幅缩短等待。 */
    public void warmUp() {
        register();
        if (mP2p == null || mChannel == null) return;
        checkWifi();
        warmDiscoverLoop();
    }

    private void warmDiscoverLoop() {
        if (!mRunning || mGoAddress != null || mPauseWarm || mGotConnection) return;
        if (!mPauseWarm) {
            try {
                mP2p.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() {}
                    @Override public void onFailure(int reason) {}
                });
            } catch (Throwable ignored) {}
        }
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                if (mRunning && mGoAddress == null) warmDiscoverLoop();
            }
        }, mPauseWarm ? 3000 : 8000);
    }

    private boolean checkWifi() {
        try {
            WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && !wm.isWifiEnabled()) {
                Bus.log("提示: Wi-Fi 未开启,Wi-Fi Direct 传输需要打开 Wi-Fi");
                return false;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /** 接收方入口:先起服务端(同网对方可秒连),5 秒内没连上再走 P2P 搜索兜底。 */
    public void startAsReceiver() {
        mReceiverRole = true;
        register();
        if (mP2p == null || mChannel == null) {
            fail("本机不支持 Wi-Fi Direct");
            return;
        }
        checkWifi();
        if (mGoAddress != null) {
            Bus.log("P2P: 直连已建立,忽略重复的接收请求");
            return;
        }
        if (mDiscovering) {
            Bus.log("P2P: 正在搜索/协商中,忽略重复请求");
            return;
        }
        startServer(); // 局域网直连:对方拿到本机 IP 可直接连过来
        if (mGotConnection) {
            Bus.log("P2P: 对方已直连(局域网),无需 P2P 搜索");
            return;
        }
        mDiscovering = true;
        mRetry = 0;
        Bus.log("P2P: 等待热点/局域网直连,20 秒后仍未连上则 P2P 搜索兜底…");
        try {
            mP2p.removeGroup(mChannel, null); // 清掉可能残留的分组,失败无妨
        } catch (Throwable ignored) {}
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                if (mRunning && !mGotConnection) {
                    Bus.log("传输: 仍未等到对方连接 — 检查:发送方 Wi-Fi 开着?弹窗点了「允许」?");
                }
            }
        }, 20000);
    }

    /** 发送方入口:拿到对方 IP 直接连(同网秒连);搜索已由 warmUp 预热。 */
    public void startAsSender() {
        mReceiverRole = false;
        register();
        if (mP2p == null || mChannel == null) {
            fail("本机不支持 Wi-Fi Direct");
            return;
        }
        checkWifi();
        if (mGoAddress != null || mSenderWaiting) {
            Bus.log("P2P: 已在等待对方发起直连");
            return;
        }
        mSenderWaiting = true;
        Bus.log("P2P: 本机为发送方:优先热点/局域网直连,失败则等 P2P…");
        startConnectStrategy();
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                if (mRunning && mGoAddress == null && !mReceiverRole && !hasLiveLink()) {
                    Bus.log("P2P: 仍未建立直连(可退回重新碰一次,或检查两台 Wi-Fi 是否都打开)");
                }
            }
        }, 20000);
    }

    //pri added by zhuyang,TapService tells the link layer whether this phone still holds photos (may swipe later),20261014
    public void setCanSend(boolean can) {
        mCanSend = can;
        mHasPhotos = can; //pri added by zhuyang,The HELLO hp field mirrors the photo state of this round,20261017
    }

    //pri modify by zhuyang,Role convergence: swipe records the local clock and announces ROLE(S); false = peer already swiped,20261017,start
    public boolean setSenderRole() {
        mCanSend = false;
        if (mPeerWantsSend) {
            Bus.log("传输: 对端先上滑,本机不再发送(让出),转接收");
            mReceiverRole = true;
            mYielded = true;
            Link stashed = mStashedSocket;
            if (stashed != null && mPeerSocket != stashed) {
                sendRoleOnLinkAsync(stashed, "R");
                startIncomingOnStash(stashed);
            }
            notifyRoleYielded();
            return false;
        }
        mReceiverRole = false;
        mHasPhotos = true;
        mLocalSwipedAt = SystemClock.elapsedRealtime();
        Link send = mSendSocket;
        if (send != null) {
            sendRoleOnLinkAsync(send, "S");
        } else if (mStashedSocket != null && mPeerSocket != mStashedSocket) {
            // Only the peer-initiated link exists: announce our role on it now.
            sendRoleOnLinkAsync(mStashedSocket, "S");
        }
        Bus.log("传输: 本机上滑,宣告 ROLE(S)"
                + (send != null ? "(发送通道已就绪)" : "(发送通道尚未建立,连接建立后补发)"));
        return true;
    }
    //pri modify by zhuyang,Role convergence: swipe records the local clock and announces ROLE(S); false = peer already swiped,20261017,end

    //pri added by zhuyang,Role convergence helpers: ROLE write, listener lifecycle and UI notifications,20261017,start
    //pri added by zhuyang,Liveness helpers: mark a written frame and write a possibly large frame with progress,20261017,start
    private static void markTx(Link link) {
        long now = SystemClock.elapsedRealtime();
        link.lastTx.set(now);
        link.lastWriteProgressAt = now;
    }

    private void writeBusinessFrame(Link link, int type, int len, byte[] data) throws java.io.IOException {
        Proto.writeFrame(link.out, type, len, data);
        markTx(link);
    }

    //pri added by zhuyang,Write a FULL frame in 64KB chunks so the stall watchdog sees real progress,20261017
    private void writeFullFrame(Link link, byte[] data) throws java.io.IOException {
        link.out.writeInt(Proto.MAGIC);
        link.out.writeInt(Proto.TYPE_FULL);
        link.out.writeInt(data.length);
        int off = 0;
        while (off < data.length) {
            int n = Math.min(WRITE_CHUNK, data.length - off);
            link.out.write(data, off, n);
            off += n;
            markTx(link);
        }
    }
    //pri added by zhuyang,Liveness helpers: mark a written frame and write a possibly large frame with progress,20261017,end

    private void sendRoleOnLink(Link link, String role) {
        if (link == null || link.s.isClosed()) return;
        try {
            byte[] b = Proto.buildRole(role, Bus.sid());
            mWriteLock.lock();
            try {
                Proto.writeFrame(link.out, Proto.TYPE_ROLE, b.length, b);
                link.out.flush();
                markTx(link); //pri added by zhuyang,A control write counts as link activity,20261017
            } finally {
                mWriteLock.unlock();
            }
            Bus.log("传输: 已发送 ROLE(" + role + ")");
        } catch (Exception e) {
            Bus.log("传输: 发送 ROLE(" + role + ") 失败: " + e);
        }
    }

    //pri added by zhuyang,Main-thread callers (TapService swipe) must not write the socket directly,20261017
    private void sendRoleOnLinkAsync(final Link link, final String role) {
        new Thread(new Runnable() {
            @Override public void run() { sendRoleOnLink(link, role); }
        }, "role-send").start();
    }

    private void notifyPeerSwipedFirst() {
        mMain.post(new Runnable() {
            @Override public void run() { mListener.onPeerSwipedFirst(); }
        });
    }

    private void notifyRoleYielded() {
        mMain.post(new Runnable() {
            @Override public void run() { mListener.onRoleYielded(); }
        });
    }

    //pri added by zhuyang,Connection liveness: one heartbeat thread per active link, shared write lock,20261017,start
    private void startHeartbeat(final Link link, final boolean expectRx) {
        if (link == null) return;
        synchronized (link) {
            if (link.hbRunning || !isAlive(link)) return;
            long now = SystemClock.elapsedRealtime();
            link.lastRx.set(now);
            link.lastTx.set(now);
            link.lastWriteProgressAt = now;
            link.hbStartedAt = now;
            link.hbExpectRx = expectRx;
            link.hbRunning = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { heartbeatLoop(link); }
            }, "link-heartbeat");
            link.hbThread = t;
            t.start();
        }
        Bus.log("传输: 心跳已启动(空闲 " + (HEARTBEAT_IDLE_MS / 1000) + "s 发 PING,无数据 "
                + (HEARTBEAT_DEAD_MS / 1000) + "s 判失效)");
    }

    private static void stopHeartbeat(Link link) {
        if (link != null) link.hbRunning = false;
    }

    //pri added by zhuyang,One lightweight loop per link: PING when idle, fail on silence or write stall,20261017
    private void heartbeatLoop(Link link) {
        try {
            while (mRunning && link.hbRunning && !link.s.isClosed()) {
                long now = SystemClock.elapsedRealtime();
                if (now - link.hbStartedAt >= TRANSFER_TOTAL_MS) {
                    signalLinkDead(link, "传输超时(超过 " + (TRANSFER_TOTAL_MS / 60000) + " 分钟)");
                    return;
                }
                if (link.hbWritePhase) {
                    //pri added by zhuyang,Transfer phase: a blocked write is caught by the stall watchdog,20261017
                    if (now - link.lastWriteProgressAt >= WRITE_STALL_MS) {
                        signalLinkDead(link, "写停滞超过 " + (WRITE_STALL_MS / 1000) + "s,连接失效");
                        return;
                    }
                } else if (link.hbExpectRx && now - link.lastRx.get() >= HEARTBEAT_DEAD_MS) {
                    signalLinkDead(link, "连接超时(" + (HEARTBEAT_DEAD_MS / 1000) + "s 未收到对端任何数据)");
                    return;
                }
                if (now - link.lastTx.get() >= HEARTBEAT_IDLE_MS) {
                    boolean locked = false;
                    try {
                        locked = mWriteLock.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (locked) {
                            Proto.writeFrame(link.out, Proto.TYPE_PING, 0, null);
                            link.out.flush();
                            link.lastTx.set(SystemClock.elapsedRealtime());
                            Bus.log("传输: 心跳 PING(" + (link.hbWritePhase ? "传输中" : "等待中") + ")");
                        }
                    } catch (Exception e) {
                        signalLinkDead(link, "心跳发送失败(连接已断开)");
                        return;
                    } finally {
                        if (locked) mWriteLock.unlock();
                    }
                }
                try {
                    Thread.sleep(HEARTBEAT_TICK_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        } finally {
            link.hbRunning = false;
        }
    }
    //pri added by zhuyang,One lightweight loop per link: PING when idle, fail on silence or write stall,20261017,end

    //pri added by zhuyang,Declare a link dead and close it; the owning reader/writer reports the failure once,20261017
    private void signalLinkDead(final Link link, final String why) {
        if (!link.linkDead.compareAndSet(false, true)) return;
        link.failReason = why;
        Bus.log("传输: 连接失效 — " + why + ",关闭连接");
        closeQuietly(link);
    }

    private void notifyLinkFailedOnce(Link link, String why) {
        if (link == null) {
            fail(why);
            return;
        }
        if (!link.failCb.compareAndSet(false, true)) return;
        Bus.log("传输: " + why);
        final String w = why;
        mMain.post(new Runnable() {
            @Override public void run() { mListener.onXferFailed(w); }
        });
    }

    private void notifyOfferExpiredOnce(Link link, String why) {
        if (!link.offerExpiredCb.compareAndSet(false, true)) return;
        Bus.log("传输: " + why);
        mMain.post(new Runnable() {
            @Override public void run() { mListener.onOfferExpired(); }
        });
    }

    //pri added by zhuyang,Reply PONG without ever interleaving it with a business frame,20261017
    private void replyPong(Link link) {
        boolean locked = false;
        try {
            locked = mWriteLock.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (locked) {
                Proto.writeFrame(link.out, Proto.TYPE_PONG, 0, null);
                link.out.flush();
                link.lastTx.set(SystemClock.elapsedRealtime());
                Bus.log("传输: 收到 PING,已回 PONG");
            }
        } catch (Exception e) {
            Bus.log("传输: 回 PONG 失败 " + e);
        } finally {
            if (locked) mWriteLock.unlock();
        }
    }
    //pri added by zhuyang,Connection liveness: one heartbeat thread per active link, shared write lock,20261017,end

    //pri added by zhuyang,Take over a stashed link for receiving without blocking the caller (setSenderRole runs on main),20261017
    private void startIncomingOnStash(final Link link) {
        if (mPeerSocket == link) return;
        new Thread(new Runnable() {
            @Override public void run() {
                stopRoleListener(link);
                if (mPeerSocket == link) return; // the listener already took over as the reader
                if (link.s.isClosed()) return;
                handleIncoming(link);
            }
        }, "incoming-yield").start();
    }

    //pri added by zhuyang,Stop the single role listener before the same stream is used by another reader,20261017
    private void stopRoleListener(Link link) {
        Thread t = mRoleListenerThread;
        if (t == null || !t.isAlive() || t == Thread.currentThread()) return;
        mRoleListenerAbort = link;
        try { t.join(ROLE_LISTEN_TIMEOUT_MS + 500); } catch (InterruptedException ignored) {}
        if (t.isAlive() && mPeerSocket != link) {
            Bus.log("传输: 角色监听线程未退出,关闭旧连接以免并发读");
            closeQuietly(link);
        }
        if (mRoleListenerAbort == link) mRoleListenerAbort = null;
    }
    //pri added by zhuyang,Role convergence helpers: ROLE write, listener lifecycle and UI notifications,20261017,end

    //pri added by zhuyang,Role intent carried in HELLO: U=undecided S=send R=receive,20261017
    private String localRole() {
        if (!mReceiverRole) return "S";
        return mCanSend ? "U" : "R";
    }

    //pri added by zhuyang,Who may drive P2P discovery/connect: a decided receiver, or a sender whose ①② attempts failed,20261014
    private boolean mayDriveP2p() {
        return (mReceiverRole && !mCanSend) || mDriveP2p;
    }

    /** 连接策略:① 对方热点(直连,最快,实测 ~50Mbps) ② 同一 Wi-Fi 直连 ③ Wi-Fi Direct 兜底。 */
    private void startConnectStrategy() {
        new Thread(new Runnable() {
            @Override public void run() {
                if (mPeerHsSsid != null && joinHotspot(mPeerHsSsid, mPeerHsPass)) return;
                if (mPeerLanIp != null && connectTo(mPeerLanIp, "局域网")) return;
                Bus.log("传输: 热点/同网直连未成功(对方热点没开?不在同一 Wi-Fi?),走 Wi-Fi Direct 兜底…");
                mDriveP2p = true; //pri added by zhuyang,Only here does the sender take over P2P discovery/connect,20261014
                mPauseWarm = false;
                warmDiscoverLoop();
            }
        }).start();
    }

    //pri modify by zhuyang,Client handshake returns the Link so every later frame reuses the same streams,20261017,start
    private Link clientHello(Socket s) {
        try {
            s.setSoTimeout(Proto.HELLO_TIMEOUT_MS);
            //pri added by zhuyang,Wrap the socket streams so liveness sees byte-level progress,20261017,start
            java.util.concurrent.atomic.AtomicLong rxAt =
                    new java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime());
            java.util.concurrent.atomic.AtomicLong txAt =
                    new java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime());
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(
                    new RxProgress(s.getInputStream(), rxAt), 256 * 1024));
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            //pri added by zhuyang,Wrap the socket streams so liveness sees byte-level progress,20261017,end
            if (mPeerSid == null) {
                Bus.log("传输: [未认证] 本轮无对方 sid,跳过 HELLO/ROLE 握手(调试路径)");
            } else {
                byte[] hello = Proto.buildHello(Bus.sid(), localRole(), mHasPhotos);
                Proto.writeFrame(out, Proto.TYPE_HELLO, hello.length, hello);
                out.flush();
                Proto.Frame f = Proto.readFrame(in, Proto.HELLO_TIMEOUT_MS, Proto.MAX_HELLO_LEN);
                if (f == null || f.type != Proto.TYPE_HELLO_ACK) {
                    Bus.log("传输: [认证] 对方未回 HELLO_ACK,关闭连接");
                    closeQuietly(s);
                    return null;
                }
                Proto.Hello ack = Proto.parseHello(f.data);
                if (ack == null || ack.version != Proto.HELLO_VERSION
                        || !mPeerSid.equalsIgnoreCase(ack.sid)) {
                    Bus.log("传输: [认证] HELLO_ACK 校验失败,关闭连接");
                    closeQuietly(s);
                    return null;
                }
                mPeerRole = ack.role;
                byte[] role = Proto.buildRole(localRole(), Bus.sid());
                Proto.writeFrame(out, Proto.TYPE_ROLE, role.length, role);
                out.flush();
                Bus.log("传输: [认证] HELLO_ACK 校验通过,对方角色=" + ack.role
                        + ",本机角色=" + localRole());
                Link link = new Link(s, in, out, rxAt, txAt);
                //pri added by zhuyang,Read the peer's ROLE now so a simultaneous swipe can be arbitrated before we offer,20261017,start
                try {
                    Proto.Frame pf = Proto.readFrame(in, ROLE_READ_TIMEOUT_MS, Proto.MAX_ROLE_LEN);
                    if (pf != null) {
                        if (pf.type == Proto.TYPE_ROLE) {
                            Proto.Role pr = Proto.parseRole(pf.data);
                            if (pr != null) {
                                if (mPeerSid != null && !mPeerSid.equalsIgnoreCase(pr.sid)) {
                                    Bus.log("传输: [认证] 对端 ROLE sid 不一致,关闭连接");
                                    closeQuietly(s);
                                    return null;
                                }
                                link.peerRole = pr.role;
                                link.peerSid = pr.sid;
                                mPeerRole = pr.role;
                                Bus.log("传输: [认证] 收到对端 ROLE(" + pr.role + ")");
                            }
                        } else {
                            link.first = pf; // 1a peer wrote data before its ROLE (or has no ROLE)
                        }
                    }
                } catch (java.net.SocketTimeoutException te) {
                    Bus.log("传输: [认证] 对端未发送 ROLE(" + ROLE_READ_TIMEOUT_MS + "ms),按旧版本兼容");
                }
                s.setSoTimeout(0);
                return link;
                //pri added by zhuyang,Read the peer's ROLE now so a simultaneous swipe can be arbitrated before we offer,20261017,end
            }
            s.setSoTimeout(0);
            return new Link(s, in, out, rxAt, txAt);
        } catch (java.net.SocketTimeoutException e) {
            Bus.log("传输: [认证] 等待 HELLO_ACK 超时(" + Proto.HELLO_TIMEOUT_MS + "ms),关闭连接");
        } catch (Exception e) {
            Bus.log("传输: [认证] HELLO 握手失败: " + e);
        }
        closeQuietly(s);
        return null;
    }
    //pri modify by zhuyang,Client handshake returns the Link so every later frame reuses the same streams,20261017,end

    private boolean connectTo(String ip, String how) {
        for (int i = 0; i < 2 && mRunning; i++) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, PORT), 1500);
                Link link = clientHello(s);
                if (link == null) continue;
                if (!handleClientRole(link)) {
                    Bus.log("传输: " + how + "连接交由接收/裁决路径接管 " + ip + ":" + PORT);
                    return true;
                }
                mPauseWarm = true; // 连上后停止 P2P 搜索:射频让路给数据传输(否则吞吐暴跌)
                try { mP2p.stopPeerDiscovery(mChannel, null); } catch (Throwable ignored) {}
                Bus.log("传输: " + how + "直连成功 " + ip + ":" + PORT + "(免搜索)");
                mListener.onP2pReady(false, ip);
                return true;
            } catch (Exception e) {
                Bus.log("传输: " + how + "直连尝试 " + (i + 1) + " 失败: " + e);
            }
        }
        return false;
    }

    /** 用碰一碰传来的 SSID/密码接入对方热点,再连热点网关的传输端口。 */
    private boolean joinHotspot(String mPeerHsSsid, String mPeerHsPass) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            Bus.log("传输: 用对方热点信息直连 SSID=" + mPeerHsSsid + "…");
            // 关键:先停掉预热的 P2P 搜索 —— P2P 占着射频时,系统弹窗为找热点发起的扫描会
            // 直接失败(WifiNetworkFactory: Scan failed - Device or resource busy)→ 永远"找不到设备"
            mPauseWarm = true;
            try { mP2p.stopPeerDiscovery(mChannel, null); } catch (Throwable ignored) {}
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            android.net.wifi.WifiNetworkSpecifier.Builder sb =
                    new android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(mPeerHsSsid);
            if (mPeerHsPass != null) sb.setWpa3Passphrase(mPeerHsPass); // 测试:本 ROM 热点默认 WPA2/WPA3 混合(PSK+SAE),按 WPA2 请求匹配不上
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(sb.build())
                    .build();
            final android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) mCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final android.net.Network[] holder = new android.net.Network[1];
            mWifiNetCb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) {
                    holder[0] = network;
                    latch.countDown();
                }
                @Override public void onUnavailable() {
                    latch.countDown();
                }
            };
            cm.requestNetwork(req, mWifiNetCb, 30000); // 30s:普通应用会弹系统"允许连接"确认框,留足点按时间
            if (!latch.await(31, java.util.concurrent.TimeUnit.SECONDS) || holder[0] == null) {
                Bus.log("传输: 对方热点接入失败(超时或被拒绝)");
                releaseNetworkRequest();
                return false;
            }
            android.net.Network net = holder[0];
            String gw = null;
            for (int i = 0; i < 10 && gw == null && mRunning; i++) {
                android.net.LinkProperties lp = cm.getLinkProperties(net);
                if (lp != null) {
                    for (android.net.RouteInfo r : lp.getRoutes()) {
                        if (r.isDefaultRoute() && r.getGateway() != null) {
                            gw = r.getGateway().getHostAddress();
                            break;
                        }
                    }
                }
                if (gw == null) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
            Bus.log("传输: 热点已接入" + (gw != null ? ",网关=" + gw : ",网关未知"));
            if (gw == null) {
                releaseNetworkRequest();
                return false;
            }
            for (int i = 0; i < 3 && mRunning; i++) {
                try {
                    Socket s = net.getSocketFactory().createSocket();
                    s.connect(new InetSocketAddress(gw, PORT), 2000);
                    Link link = clientHello(s);
                    if (link == null) continue;
                    if (!handleClientRole(link)) {
                        Bus.log("传输: 热点连接交由接收/裁决路径接管");
                        return true;
                    }
                    mPauseWarm = true; // 接入成功后停 P2P 搜索:射频让路给数据传输(否则吞吐暴跌)
                    try { mP2p.stopPeerDiscovery(mChannel, null); } catch (Throwable ignored) {}
                    Bus.log("传输: 热点直连成功(免搜索)");
                    mListener.onP2pReady(false, gw);
                    return true;
                } catch (Exception e) {
                    Bus.log("传输: 热点内连接尝试 " + (i + 1) + " 失败: " + e);
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
            return false;
        } catch (Throwable t) {
            Bus.log("传输: 接入对方热点异常 " + t);
            return false;
        }
    }

    private void releaseNetworkRequest() {
        try {
            if (mWifiNetCb != null) {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) mCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(mWifiNetCb);
            }
        } catch (Throwable ignored) {}
        mWifiNetCb = null;
    }

    private void startDiscovery() {
        if (mPauseWarm || mGotConnection) return; // 连接已就绪,不再搜索
        Bus.log("P2P: 开始搜索附近设备…");
        try {
            mP2p.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {}
                @Override public void onFailure(int reason) {
                    Bus.log("P2P: 搜索失败 reason=" + reason + "(" + errName(reason) + ")");
                    retryDiscovery();
                }
            });
        } catch (Throwable t) {
            fail("搜索异常: " + t);
        }
    }

    private void retryDiscovery() {
        if (!mRunning || !mayDriveP2p() || mRetry >= 5) {
            mDiscovering = false;
            mConnecting = false;
            fail("P2P 搜索多次失败");
            return;
        }
        mRetry++;
        long delay = 3000L + mRetry * 2000L;
        Bus.log("P2P: " + (delay / 1000) + " 秒后重试搜索(第 " + mRetry + " 次)");
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                if (mRunning && mDiscovering && mGoAddress == null) startDiscovery();
            }
        }, delay);
    }

    private void register() {
        if (mRx != null) return;
        mRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                    WifiP2pDeviceList list =
                            i.getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST);
                    if (list != null) onPeers(list);
                } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(a)) {
                    WifiP2pDevice self =
                            i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    if (self != null) Bus.setP2pIdentity(self.deviceName, self.deviceAddress);
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                    WifiP2pInfo info =
                            i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                    if (info != null) onConn(info);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                mCtx.registerReceiver(mRx, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                mCtx.registerReceiver(mRx, f);
            }
        } catch (Throwable t) {
            Bus.log("注册 P2P 广播失败: " + t);
        }
    }

    //pri modify by zhuyang,Exact P2P address/name only: no Android* wildcard and no model/BT fallback,20261017,start
    private boolean matchesPeer(WifiP2pDevice d) {
        String addr = d.deviceAddress;
        String name = d.deviceName;
        if (mPeerP2pAddr != null && addr != null && mPeerP2pAddr.equalsIgnoreCase(addr)) return true;
        return mPeerP2pName != null && name != null && mPeerP2pName.equalsIgnoreCase(name);
    }
    //pri modify by zhuyang,Exact P2P address/name only: no Android* wildcard and no model/BT fallback,20261017,end

    private void onPeers(WifiP2pDeviceList peers) {
        Collection<WifiP2pDevice> list = peers.getDeviceList();
        if (list == null || list.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (WifiP2pDevice d : list) {
            sb.append(d.deviceName).append('(').append(d.deviceAddress).append(") ");
        }
        Bus.log("P2P: 搜到 " + list.size() + " 个设备: " + sb.toString().trim());

        // 只有接收方主动发起直连;局域网已连上(mGotConnection)后不再发起 P2P,避免传输中协商抢射频
        if (!mayDriveP2p() || mGoAddress != null || mConnecting || mGotConnection) return;

        //pri added by zhuyang,No P2P identity from the peer payload: never guess a device by name,20261017,start
        if (mPeerP2pAddr == null && mPeerP2pName == null) {
            Bus.log("P2P: 对方 P2P 信息缺失,不发起 P2P 连接(请重新碰触或改用同网/热点)");
            return;
        }
        //pri added by zhuyang,No P2P identity from the peer payload: never guess a device by name,20261017,end

        WifiP2pDevice target = null;
        for (WifiP2pDevice d : list) {
            if (matchesPeer(d)) { target = d; break; }
        }
        if (target == null) {
            Bus.log("P2P: 没有匹配到对方手机,继续搜索…");
            retryDiscovery();
            return;
        }

        mConnecting = true;
        Bus.log("P2P: 匹配到对方 " + target.deviceName + " (" + target.deviceAddress + "),发起直连");
        try {
            WifiP2pConfig cfg = new WifiP2pConfig();
            cfg.deviceAddress = target.deviceAddress;
            mP2p.connect(mChannel, cfg, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    Bus.log("P2P: 直连协商已发起,等待组建立…");
                }
                @Override public void onFailure(int reason) {
                    Bus.log("P2P: 直连失败 reason=" + reason + "(" + errName(reason) + ")");
                    mConnecting = false;
                    try { mP2p.cancelConnect(mChannel, null); } catch (Throwable ignored) {}
                    retryDiscovery();
                }
            });
        } catch (Throwable t) {
            mConnecting = false;
            fail("直连异常: " + t);
        }
    }

    private void onConn(WifiP2pInfo info) {
        if (!info.groupFormed) return;
        InetAddress addr = info.groupOwnerAddress;
        mGoAddress = (addr == null) ? null : addr.getHostAddress();
        mImGo = info.isGroupOwner;
        mDiscovering = false;
        mConnecting = false;
        Bus.log("P2P: 组已建立,组主=" + mGoAddress + ", 本机是组主=" + mImGo);
        mListener.onP2pReady(mImGo, mGoAddress);
        if (mImGo) {
            startServer();          // 组主监听:收到连接后按本机角色处理
        } else if (mReceiverRole) {
            startClientRead();      // 接收方是组员:主动连组主读取
        }
        // 发送方且是组员:等用户点「发送」时连组主写图
    }

    //pri modify by zhuyang,Debug path keeps the pre-1a direction probe; it never sees ROLE frames,20261017,start
    private boolean peerSpeaksFirst(Link link) {
        long deadline = System.currentTimeMillis() + PROBE_MS;
        try {
            while (mRunning && System.currentTimeMillis() < deadline) {
                if (link.in.available() > 0) return true;
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        } catch (Throwable t) {
            Bus.log("传输: 探测连接方向失败 " + t);
        }
        return false;
    }
    //pri modify by zhuyang,Debug path keeps the pre-1a direction probe; it never sees ROLE frames,20261017,end

    //pri added by zhuyang,Install/outdate the send channel; a replaced link is closed,20261017
    private void setSendLink(Link link) {
        Link old = mSendSocket;
        mSendSocket = link;
        if (old != null && old != link) closeQuietly(old);
    }

    private void startServer() {
        if (mServer != null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    mServer = new ServerSocket(PORT);
                    Bus.log("传输: 已监听端口 " + PORT + ",等待对方连接…");
                    while (mRunning) {
                        final Socket s = mServer.accept();
                        //pri added by zhuyang,Accept only accepts: HELLO is read on its own thread with a hard cap,20261017,start
                        if (mUnauthCount.incrementAndGet() > MAX_UNAUTH_CONNECTIONS) {
                            mUnauthCount.decrementAndGet();
                            Bus.log("传输: 未认证连接超过上限(" + MAX_UNAUTH_CONNECTIONS
                                    + "),直接关闭 " + s.getInetAddress());
                            closeQuietly(s);
                            continue;
                        }
                        new Thread(new Runnable() {
                            @Override public void run() { authenticateAndClassify(s); }
                        }, "accept-auth").start();
                        //pri added by zhuyang,Accept only accepts: HELLO is read on its own thread with a hard cap,20261017,end
                    }
                } catch (Exception e) {
                    if (mRunning) Bus.log("传输: 监听异常 " + e);
                }
            }
        }).start();
    }

    //pri modify by zhuyang,Server handshake -> Link; classification reads the peer's first ROLE/META frame,20261017,start
    private void authenticateAndClassify(final Socket s) {
        boolean released = false;
        try {
            if (!mRunning) {
                closeQuietly(s);
                return;
            }
            final String expected = mPeerSid;
            //pri added by zhuyang,Wrap the socket streams so liveness sees byte-level progress,20261017,start
            java.util.concurrent.atomic.AtomicLong rxAt =
                    new java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime());
            java.util.concurrent.atomic.AtomicLong txAt =
                    new java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime());
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(
                    new RxProgress(s.getInputStream(), rxAt), 256 * 1024));
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            //pri added by zhuyang,Wrap the socket streams so liveness sees byte-level progress,20261017,end
            if (expected == null) {
                Bus.log("传输: [未认证] 本轮无对方 sid,跳过 HELLO/ROLE 校验 " + s.getInetAddress());
                s.setSoTimeout(0);
                Link link = new Link(s, in, out, rxAt, txAt);
                mUnauthCount.decrementAndGet();
                released = true;
                classifyDebug(link);
                return;
            }
            s.setSoTimeout(Proto.HELLO_TIMEOUT_MS);
            Proto.Frame f = Proto.readFrame(in, Proto.HELLO_TIMEOUT_MS, Proto.MAX_HELLO_LEN);
            if (f == null || f.type != Proto.TYPE_HELLO) {
                Bus.log("传输: [认证] 未收到 HELLO,关闭连接 " + s.getInetAddress());
                closeQuietly(s);
                return;
            }
            Proto.Hello hello = Proto.parseHello(f.data);
            if (hello == null || hello.version != Proto.HELLO_VERSION
                    || !expected.equalsIgnoreCase(hello.sid)) {
                Bus.log("传输: [认证] HELLO 校验失败,关闭连接 " + s.getInetAddress());
                closeQuietly(s);
                return;
            }
            byte[] ack = Proto.buildHelloAck(Bus.sid(), localRole());
            Proto.writeFrame(out, Proto.TYPE_HELLO_ACK, ack.length, ack);
            byte[] role = Proto.buildRole(localRole(), Bus.sid());
            Proto.writeFrame(out, Proto.TYPE_ROLE, role.length, role);
            out.flush();
            Bus.log("传输: [认证] HELLO 校验通过 " + s.getInetAddress()
                    + ",对方角色=" + hello.role + ",本机角色=" + localRole());
            Link link = new Link(s, in, out, rxAt, txAt);
            mUnauthCount.decrementAndGet();
            released = true;
            s.setSoTimeout(ROLE_READ_TIMEOUT_MS);
            Proto.Frame first = null;
            try {
                first = Proto.readFrame(in, ROLE_READ_TIMEOUT_MS, Proto.MAX_ROLE_LEN);
            } catch (java.net.SocketTimeoutException te) {
                Bus.log("传输: 对端未在 " + ROLE_READ_TIMEOUT_MS + "ms 内发送角色帧,按未决定处理");
            }
            s.setSoTimeout(0);
            if (first == null) {
                if (s.isClosed()) return;
                mGotConnection = true;
                mStashedSocket = link;
                startRoleListener(link);
                return;
            }
            classifyAuthenticated(link, first);
        } catch (java.net.SocketTimeoutException e) {
            Bus.log("传输: [认证] HELLO 超时(" + Proto.HELLO_TIMEOUT_MS + "ms),关闭连接 "
                    + s.getInetAddress());
            closeQuietly(s);
        } catch (Exception e) {
            Bus.log("传输: [认证] HELLO 处理失败,关闭连接: " + e);
            closeQuietly(s);
        } finally {
            if (!released) mUnauthCount.decrementAndGet();
        }
    }

    //pri added by zhuyang,Debug (no sid) classification: pre-1a peerSpeaksFirst behaviour on a Link,20261017,start
    private void classifyDebug(Link link) {
        if (!mRunning) {
            closeQuietly(link);
            return;
        }
        mGotConnection = true;
        Bus.log("传输: [未认证] 对方已连接 " + link.s.getInetAddress());
        if (!mReceiverRole) {
            setSendLink(link);
            Bus.log("传输: [未认证] 本机是发送方,连接留作发送通道");
        } else if (peerSpeaksFirst(link)) {
            handleIncoming(link); // 对方在发报价:本机就是这一轮的接收方
        } else if (!mReceiverRole || mCanSend) {
            setSendLink(link); // 本机还有照片没上滑(探测期间上滑也算):留着当发送通道
        } else {
            closeQuietly(link);
            Bus.log("传输: [未认证] 对端只是读取方,已释放空连接");
        }
    }
    //pri added by zhuyang,Debug (no sid) classification: pre-1a peerSpeaksFirst behaviour on a Link,20261017,end

    //pri added by zhuyang,Authenticated classification by the peer's first frame,20261017,start
    private void classifyAuthenticated(Link link, Proto.Frame first) {
        if (!mRunning) {
            closeQuietly(link);
            return;
        }
        mGotConnection = true;
        Bus.log("传输: 对方已连接 " + link.s.getInetAddress());
        if (first.type == Proto.TYPE_ROLE) {
            Proto.Role role = Proto.parseRole(first.data);
            if (role == null) {
                Bus.log("传输: ROLE 帧解析失败,暂存连接并监听");
                mStashedSocket = link;
                startRoleListener(link);
                return;
            }
            if (mPeerSid != null && !mPeerSid.equalsIgnoreCase(role.sid)) {
                Bus.log("传输: ROLE 帧 sid 与本轮 sid 不一致,关闭连接");
                closeQuietly(link);
                return;
            }
            mPeerRole = role.role;
            Bus.log("传输: 收到首帧 ROLE(" + role.role + ")");
            if ("S".equals(role.role)) {
                handlePeerSendRole(link, role.sid);
            } else if ("U".equals(role.role)) {
                mStashedSocket = link;
                startRoleListener(link);
            } else {
                // ROLE(R): the peer is receiving, so this accepted link is our send channel.
                setSendLink(link);
                mStashedSocket = null;
                Bus.log("传输: 对端是接收方(ROLE=R),本连接作为发送通道");
                mMain.post(new Runnable() {
                    @Override public void run() { mListener.onP2pReady(false, null); }
                });
            }
        } else if (first.type == Proto.TYPE_META) {
            // Compatibility: a 1a client (or old debug peer) sends the offer directly.
            mStashedSocket = link;
            handleIncoming(link, first);
        } else {
            Bus.log("传输: 未识别的首帧类型 " + first.type + ",暂存连接并监听");
            mStashedSocket = link;
            startRoleListener(link);
        }
    }
    //pri added by zhuyang,Authenticated classification by the peer's first frame,20261017,end

    //pri added by zhuyang,Peer announced ROLE(S) on this link,20261017,start
    private void handlePeerSendRole(Link link, String peerSid) {
        mPeerRole = "S";
        mPeerSwipedAt = SystemClock.elapsedRealtime();
        mPeerWantsSend = true;
        if (mYielded) {
            //pri added by zhuyang,Already yielded this round: keep receiving, never arbitrate again,20261017
            Bus.log("传输: 本机已让出,按接收方读取对端报价");
            mStashedSocket = link;
            handleIncoming(link);
            return;
        }
        if (mLocalSwipedAt == 0) {
            Bus.log("传输: 对端先上滑(ROLE=S),本机转接收");
            mStashedSocket = link;
            notifyPeerSwipedFirst();
            handleIncoming(link);
        } else {
            Bus.log("传输: 双方都想发送,进入角色裁决");
            arbitrate(link, peerSid, false);
        }
    }
    //pri added by zhuyang,Peer announced ROLE(S) on this link,20261017,end

    //pri added by zhuyang,Client-side classification: the link we opened may be the receive link or our send channel,20261017,start
    private boolean handleClientRole(Link link) {
        if (link.first != null) {
            if (link.first.type == Proto.TYPE_META) {
                Bus.log("传输: 对端直接发来报价(兼容 1a),本机转接收");
                mStashedSocket = link;
                handleIncoming(link, link.first);
                return false;
            }
            Bus.log("传输: 忽略对端首帧类型 " + link.first.type);
        }
        String role = link.peerRole;
        if ("S".equals(role)) {
            mPeerRole = "S";
            mPeerSwipedAt = SystemClock.elapsedRealtime();
            mPeerWantsSend = true;
            if (mLocalSwipedAt == 0) {
                Bus.log("传输: 连接建立时对端已宣告 ROLE(S),本机转接收");
                notifyPeerSwipedFirst();
                mStashedSocket = link;
                handleIncoming(link);
                return false;
            }
            arbitrate(link, link.peerSid, true);
            return !mYielded && mSendSocket == link;
        }
        if (mReceiverRole) {
            mStashedSocket = link;
            startRoleListener(link);
            Bus.log("传输: 对端角色=" + (role == null ? "未知" : role) + ",本机接收:暂存连接并监听");
            return false;
        }
        setSendLink(link);
        Bus.log("传输: 对端角色=" + (role == null ? "未知" : role) + ",本连接作为发送通道");
        return true;
    }
    //pri added by zhuyang,Client-side classification: the link we opened may be the receive link or our send channel,20261017,end

    //pri added by zhuyang,Deterministic role arbitration: both sides are sending, so the smaller sid wins,20261017,start
    private void arbitrate(Link link, String peerSid, boolean keepOnWin) {
        boolean localWins;
        String why;
        if (mLocalSwipedAt == 0) {
            localWins = false;
            why = "本机尚未上滑";
        } else {
            int cmp = Bus.sid().compareTo(peerSid);
            localWins = cmp < 0;
            why = "比较 sid(" + (cmp < 0 ? "本机小" : "对端小") + ")";
        }
        Bus.log("传输: 角色裁决 — " + (localWins ? "本机胜出" : "本机让出(败方)") + ",原因: " + why);
        if (localWins) {
            if (keepOnWin) {
                // This link is the channel we opened; the yielding peer receives on it.
                if (link == mStashedSocket) mStashedSocket = null;
                setSendLink(link);
            } else if (link == mStashedSocket) {
                // The peer is the loser and turns receiver on this same link, so keep it as a send
                // channel when there is none yet; hand it to sendImages via onP2pReady.
                mStashedSocket = null;
                if (mSendSocket == null) {
                    mSendSocket = link;
                    try { link.s.setSoTimeout(0); } catch (Exception ignored) {}
                    Bus.log("传输: 胜利方沿用对端连接作为发送通道");
                    mMain.post(new Runnable() {
                        @Override public void run() { mListener.onP2pReady(false, null); }
                    });
                } else {
                    closeQuietly(link);
                }
            } else if (link != mSendSocket) {
                closeQuietly(link);
            }
            return;
        }
        yieldToPeer(link);
    }

    private void yieldToPeer(Link link) {
        mYielded = true;
        mReceiverRole = true;
        mCanSend = false;
        mPeerRole = "S";
        Link send = mSendSocket;
        boolean sameLink = send == link;
        mSendSocket = null;
        if (send != null && !sameLink) closeQuietly(send);
        //pri added by zhuyang,The link that carried the arbitration also carries the winner's offer,20261017,start
        Link recv = link != null ? link : mStashedSocket;
        mStashedSocket = recv;
        //pri added by zhuyang,The link that carried the arbitration also carries the winner's offer,20261017,end
        if (recv != null) {
            try { recv.s.setSoTimeout(0); } catch (Exception ignored) {}
            sendRoleOnLinkAsync(recv, "R");
        }
        notifyRoleYielded();
        if (recv != null && mPeerSocket != recv) {
            stopRoleListener(recv);
            if (mPeerSocket != recv && !recv.s.isClosed()) handleIncoming(recv);
        }
    }
    //pri added by zhuyang,Deterministic role arbitration: both sides are sending, so the smaller sid wins,20261017,end

    //pri added by zhuyang,Role listener: the only reader of a stashed link until it yields or receives,20261017,start
    private void startRoleListener(final Link link) {
        if (!mRunning) {
            closeQuietly(link);
            return;
        }
        Thread existing = mRoleListenerThread;
        if (existing != null && existing.isAlive()) {
            Bus.log("传输: 已有角色监听线程,关闭多余连接 " + link.s.getInetAddress());
            closeQuietly(link);
            return;
        }
        try { link.s.setSoTimeout(RX_TIMEOUT_MS); } catch (Exception ignored) {} //pri modify by zhuyang,Bound every link read,20261017
        Thread t = new Thread(new Runnable() {
            @Override public void run() { roleListenerLoop(link); }
        }, "role-listen");
        mRoleListenerThread = t;
        t.start();
    }

    private boolean localRoleSettled() {
        long t = mLocalSwipedAt;
        return t != 0 && SystemClock.elapsedRealtime() - t > ROLE_CONFLICT_WINDOW_MS;
    }

    //pri added by zhuyang,The local swipe window passed with no peer ROLE(S): this phone wins,20261017
    private boolean settleRoleListener(Link link) {
        if (!localRoleSettled()) return false;
        if (!mReceiverRole && mSendSocket == null && mStashedSocket == link) {
            mStashedSocket = null;
            mSendSocket = link;
            try { link.s.setSoTimeout(0); } catch (Exception ignored) {}
            Bus.log("传输: 本机先上滑(对端未宣告发送),沿用对端连接作为发送通道");
            mMain.post(new Runnable() {
                @Override public void run() { mListener.onP2pReady(false, null); }
            });
        }
        return true;
    }

    private void roleListenerLoop(Link link) {
        Bus.log("传输: 开始监听对端角色(50ms 轮询)");
        //pri added by zhuyang,Connected receiver must see an offer within 20s,20261017,start
        long listenStart = SystemClock.elapsedRealtime();
        //pri added by zhuyang,Connected receiver must see an offer within 20s,20261017,end
        try {
            while (mRunning) {
                if (mStashedSocket != link || mRoleListenerAbort == link) break;
                //pri added by zhuyang,No offer within 20s: close and let the receive page report it,20261017,start
                if (SystemClock.elapsedRealtime() - listenStart >= OFFER_WAIT_MS) {
                    notifyOfferExpiredOnce(link, "已连接 " + (OFFER_WAIT_MS / 1000)
                            + "s 仍未收到报价(对方未开始发送)");
                    break;
                }
                //pri added by zhuyang,No offer within 20s: close and let the receive page report it,20261017,end
                Proto.Frame f;
                try {
                    if (link.in.available() <= 0) {
                        if (settleRoleListener(link)) break;
                        Thread.sleep(50); //pri added by zhuyang,Poll instead of a timed frame read so a partial frame is never lost,20261017
                        continue;
                    }
                    f = Proto.readFrame(link.in, RX_TIMEOUT_MS, Proto.MAX_FRAME_LEN);
                } catch (InterruptedException ie) {
                    continue;
                } catch (Exception e) {
                    Bus.log("传输: 角色监听结束: " + e);
                    break;
                }
                if (f == null) {
                    Bus.log("传输: 角色监听连接已关闭");
                    break;
                }
                //pri added by zhuyang,Keep the stashed link alive while the peer waits,20261017,start
                if (f.type == Proto.TYPE_PING) {
                    replyPong(link);
                    continue;
                }
                if (f.type == Proto.TYPE_PONG) {
                    Bus.log("传输: 收到 PONG");
                    continue;
                }
                //pri added by zhuyang,Keep the stashed link alive while the peer waits,20261017,end
                if (f.type == Proto.TYPE_ROLE) {
                    Proto.Role role = Proto.parseRole(f.data);
                    if (role == null) continue;
                    if (mPeerSid != null && !mPeerSid.equalsIgnoreCase(role.sid)) {
                        Bus.log("传输: ROLE 帧 sid 不一致,已忽略");
                        continue;
                    }
                    Bus.log("传输: 收到角色帧 ROLE(" + role.role + ")");
                    if ("S".equals(role.role)) {
                        handlePeerSendRole(link, role.sid);
                    } else {
                        mPeerRole = role.role;
                    }
                } else if (f.type == Proto.TYPE_META) {
                    Bus.log("传输: 角色监听读到报价,本机按接收处理");
                    mStashedSocket = link;
                    handleIncoming(link, f);
                    break;
                } else {
                    Bus.log("传输: 角色监听忽略帧类型 " + f.type);
                }
                if (mStashedSocket != link) break;
                if (settleRoleListener(link)) break;
            }
        } finally {
            if (mRoleListenerThread == Thread.currentThread()) mRoleListenerThread = null;
            // A normal exit means this link has no owner any more. An abort hands it to another reader.
            if (mRoleListenerAbort != link && mStashedSocket == link && mPeerSocket != link) {
                mStashedSocket = null;
                closeQuietly(link);
            }
            Bus.log("传输: 角色监听结束");
        }
    }
    //pri added by zhuyang,Role listener: the only reader of a stashed link until it yields or receives,20261017,end

    private void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }

    private void closeQuietly(Link link) {
        try { if (link != null) link.s.close(); } catch (Exception ignored) {}
    }

    /** 接收方是组员:主动连组主,等待读图。 */
    //pri modify by zhuyang,Client-side receiver classifies the owner's ROLE on the same Link,20261017,start
    private void startClientRead() {
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 6 && mRunning; i++) {
                    try {
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(mGoAddress, PORT), 5000);
                        Link link = clientHello(s);
                        if (link == null) {
                            Bus.log("传输: 连接组主未通过 HELLO 校验(第 " + (i + 1) + " 次)");
                            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                            continue;
                        }
                        Bus.log("传输: 已连接组主,等待接收图片…");
                        if (mPeerSid == null) {
                            handleIncoming(link);
                            return;
                        }
                        handleClientRole(link);
                        return;
                    } catch (Exception e) {
                        Bus.log("传输: 连接组主失败(第 " + (i + 1) + " 次): " + e);
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    }
                }
            }
        }).start();
    }
    //pri modify by zhuyang,Client-side receiver classifies the owner's ROLE on the same Link,20261017,end

    /** 从一条已建立的 TCP 连接上读图片(可多张,每张一帧):显示 + 存相册。
     *  读线程只管收;解码+入库放 mIo 单线程队列,收完一张立刻收下一张(否则发送端被背压卡住)。 */
    private void handleIncoming(Link link) {
        handleIncoming(link, null);
    }

    //pri modify by zhuyang,Receive on a Link, skip control frames, and accept a first frame already read,20261017,start
    private void handleIncoming(Link link, Proto.Frame preRead) {
        final java.util.ArrayList<Bitmap> all = new java.util.ArrayList<Bitmap>();
        int count = 0;
        int total = 0;
        int expect = 0;
        String readError = null;
        try {
            link.s.setSoTimeout(RX_TIMEOUT_MS); //pri modify by zhuyang,No receive read may block forever,20261017
            mPeerSocket = link;     //pri added by zhuyang,Keep the link so the verdict can be written back on it,20261014
            mStashedSocket = link;
            mVerdictSent = false;   //pri added by zhuyang,Reset the per-connection verdict/done flags,20261014,start
            mDoneNotified = false;
            //pri added by zhuyang,Reset the per-connection verdict/done flags,20261014,end
            startHeartbeat(link, true);   //pri added by zhuyang,Heartbeat covers the offer/verdict wait and the receive loop,20261017
            //pri added by zhuyang,Replay frames buffered on the send link before the role loss,20261017,start
            final java.util.ArrayDeque<Proto.Frame> replay = new java.util.ArrayDeque<Proto.Frame>();
            if (preRead != null) replay.add(preRead);
            synchronized (mPreReadFrames) {
                replay.addAll(mPreReadFrames);
                mPreReadFrames.clear();
            }
            Proto.Frame f = replay.pollFirst();
            //pri added by zhuyang,Replay frames buffered on the send link before the role loss,20261017,end
            while (mRunning && !link.linkDead.get()) {
                if (f == null) f = replay.pollFirst(); //pri added by zhuyang,Drain every buffered frame before touching the stream,20261017
                if (f == null) {
                    //pri added by zhuyang,Waiting for the offer is capped at 20s; receiving data at 30s,20261017,start
                    int readTimeout = expect > 0 ? RX_TIMEOUT_MS : OFFER_WAIT_MS;
                    try {
                        link.s.setSoTimeout(readTimeout);
                        f = Proto.readFrame(link.in, readTimeout, Proto.MAX_FRAME_LEN);
                    } catch (java.net.SocketTimeoutException te) {
                        Bus.log("传输: 接收超时(" + readTimeout + "ms 未读到完整帧)");
                        readError = expect > 0
                                ? "接收超时(" + (RX_TIMEOUT_MS / 1000) + "s 未收到数据)"
                                : "对方未开始发送(报价超时)";
                        break;
                    } catch (Exception e) {
                        Bus.log("传输: 帧读取失败 " + e);
                        readError = "传输中断: " + e;
                        break;
                    }
                    //pri added by zhuyang,Waiting for the offer is capped at 20s; receiving data at 30s,20261017,end
                    if (f == null) {
                        Bus.log("传输: 对端已关闭连接");
                        break;
                    }
                }
                final Proto.Frame frame = f;
                f = null;
                int type = frame.type;
                int len = frame.length;
                //pri added by zhuyang,Heartbeat frames keep the link alive and are never counted as images,20261017,start
                if (type == Proto.TYPE_PING) {
                    replyPong(link);
                    continue;
                }
                if (type == Proto.TYPE_PONG) {
                    Bus.log("传输: 收到 PONG");
                    continue;
                }
                //pri added by zhuyang,Heartbeat frames keep the link alive and are never counted as images,20261017,end
                if (type == Proto.TYPE_ROLE) {
                    //pri added by zhuyang,Role updates may arrive before META; never count them as images,20261017,start
                    Proto.Role role = Proto.parseRole(frame.data);
                    if (role != null) {
                        mPeerRole = role.role;
                        if ("S".equals(role.role) && mPeerSwipedAt == 0) {
                            mPeerSwipedAt = SystemClock.elapsedRealtime();
                        }
                        Bus.log("传输: 接收连接收到 ROLE(" + role.role + ")");
                    }
                    continue;
                    //pri added by zhuyang,Role updates may arrive before META; never count them as images,20261017,end
                }
                if (type == Proto.TYPE_META) {
                    expect = len; // 对方预告总张数:接收页文案不再回退成 1
                    mExpect = expect; //pri added by zhuyang,Record the announced total for this round,20261014
                    final int n = expect;
                    Bus.log("传输: 对方本次共发 " + n + " 张");
                    mMain.post(new Runnable() {
                        @Override public void run() { mListener.onSendStarted(n); }
                    });
                    continue;
                }
                if (type != Proto.TYPE_PREVIEW && type != Proto.TYPE_FULL) {
                    Bus.log("传输: 接收连接忽略帧类型 " + type);
                    continue;
                }
                Bus.log("传输: 正在接收" + (type == Proto.TYPE_PREVIEW ? "预览" : "第 " + (count + 1) + " 张") + " " + len + " 字节…");
                final byte[] data = frame.data;
                if (type == Proto.TYPE_PREVIEW) {
                    final Bitmap pbmp = decode(data);
                    mMain.post(new Runnable() {
                        @Override public void run() { mListener.onPreview(pbmp); }
                    });
                    continue;
                }
                count++;
                total += len;
                final int idx = count;
                final int exp = expect;
                mIo.execute(new Runnable() {
                    @Override public void run() {
                        final Bitmap bmp = decode(data);
                        final Uri savedUri = saveToGallery(data); //pri added by zhuyang,saveToGallery now returns the MediaStore Uri,20261014
                        if (savedUri != null) {
                            mSavedUris.add(savedUri); //pri added by zhuyang,Keep the whole round's list: the in-app viewer pages through it,20261014
                            if (mFirstSavedUri == null) {
                                mFirstSavedUri = savedUri; //pri added by zhuyang,Remember the first saved Uri of this round,20261014
                            }
                        }
                        final boolean saved = savedUri != null;
                        synchronized (all) { all.add(bmp); }
                        final int bytes = data.length;
                        mMain.post(new Runnable() {
                            @Override public void run() { mListener.onImageReceived(idx, exp, bmp, bytes, saved); }
                        });
                    }
                });
                //pri added by zhuyang,Two-phase protocol completion: count==expect means delivered,20261014,start
                if (expect > 0 && count >= expect && !mDoneNotified) {
                    mDoneNotified = true;
                    enqueueTransferDone(all);
                }
                //pri added by zhuyang,Two-phase protocol completion: count==expect means delivered,20261014,end
            }
            //pri modify by zhuyang,EOF is no longer success: only count==expect completes the round,20261017,start
            if (mRunning) {
                Bus.log("传输: 接收结束,共 " + count + " 张 " + total + " 字节,预告 " + expect + " 张");
                if (expect > 0 && count >= expect) {
                    if (!mDoneNotified) {
                        mDoneNotified = true;
                        enqueueTransferDone(all);
                    }
                } else if (expect > 0 || count > 0 || mVerdictSent) {
                    String why;
                    if (link.linkDead.get() && link.failReason != null) {
                        why = link.failReason;
                    } else if (readError != null) {
                        why = readError;
                    } else if (expect > 0) {
                        why = "传输中断: 只收到 " + count + "/" + expect + " 张";
                    } else {
                        why = "传输中断";
                    }
                    notifyLinkFailedOnce(link, why);
                } else {
                    notifyOfferExpiredOnce(link, "对方未开始发送(报价超时或连接断开)");
                }
            }
            //pri modify by zhuyang,EOF is no longer success: only count==expect completes the round,20261017,end
        } catch (Exception e) {
            Bus.log("传输: 接收异常 " + e);
            //pri added by zhuyang,Every receive failure path must call back exactly once,20261017,start
            if (mRunning) {
                if (expect > 0 || count > 0 || mVerdictSent) {
                    if (count < expect || expect == 0) {
                        notifyLinkFailedOnce(link, "传输中断: " + e);
                    }
                } else {
                    notifyOfferExpiredOnce(link, "对方未开始发送(接收异常)");
                }
            }
            //pri added by zhuyang,Every receive failure path must call back exactly once,20261017,end
        } finally {
            stopHeartbeat(link);
            //pri modify by zhuyang,Clear both role handles for the dead Link before closing it,20261017,start
            if (mPeerSocket == link) mPeerSocket = null;
            if (mStashedSocket == link) mStashedSocket = null;
            closeQuietly(link);
            //pri modify by zhuyang,Clear both role handles for the dead Link before closing it,20261017,end
            //pri added by zhuyang,Release the hotspot on failure too so Wi-Fi comes back,20261017,start
            if (mReceiverRole && mHotspotActive) {
                closeHotspot();
                Bus.log("传输: 热点已关闭,Wi-Fi 恢复可用");
            }
            //pri added by zhuyang,Release the hotspot on failure too so Wi-Fi comes back,20261017,end
        }
    }

    //pri added by zhuyang,Queue the done notification behind the decode tasks of this connection,20261014
    private void enqueueTransferDone(final java.util.ArrayList<Bitmap> all) {
        mIo.execute(new Runnable() {
            @Override public void run() {
                final java.util.List<Bitmap> list;
                synchronized (all) { list = new java.util.ArrayList<Bitmap>(all); }
                mMain.post(new Runnable() {
                    @Override public void run() { mListener.onTransferDone(list); }
                });
            }
        });
    }

    //pri added by zhuyang,Promote the peer-initiated stashed link to the send channel (P2P owner case),20261017,start
    private Link promoteStashedToSend() {
        Link l = mStashedSocket;
        if (l == null || l == mPeerSocket) return null;
        if (!isAlive(l)) { mStashedSocket = null; return null; }
        mStashedSocket = null;
        stopRoleListener(l);
        if (!isAlive(l)) return null;
        mSendSocket = l;
        try { l.s.setSoTimeout(0); } catch (Exception ignored) {}
        sendRoleOnLink(l, "S");
        Bus.log("传输: 暂存连接提升为发送通道");
        return l;
    }
    //pri added by zhuyang,Promote the peer-initiated stashed link to the send channel (P2P owner case),20261017,end

    /** 发送方:把选中的图片(可多张)推给对方(组主用发送通道,组员连组主)。 */
    //pri modify by zhuyang,Role convergence: send on the Link, wait out the 300ms role window, and abort when yielding,20261017,start
    public void sendImages(final java.util.List<Uri> uris) {
        new Thread(new Runnable() {
            @Override public void run() {
                Link send = null;
                try {
                    send = mSendSocket;
                    if (send == null && !mImGo && mGoAddress != null) {
                        // P2P 组已建立且本机是组员:主动连组主(组主的 8988 服务已在跑)。
                        // 原来这条路径没接上 —— 组先建好时上面的策略会因 mGoAddress != null 早退,发送方一直干等
                        try {
                            Socket t = new Socket();
                            t.connect(new InetSocketAddress(mGoAddress, PORT), 3000);
                            Link link = clientHello(t);
                            if (link == null) {
                                Bus.log("传输: P2P 连接未通过 HELLO 校验");
                            } else if (!handleClientRole(link)) {
                                Bus.log("传输: P2P 连接交由接收/裁决路径接管");
                                return;
                            } else {
                                send = mSendSocket;
                                Bus.log("传输: 已连接组主 " + mGoAddress + "(P2P)");
                            }
                        } catch (Exception e) {
                            Bus.log("传输: 连接组主失败 " + e);
                        }
                    }
                    if (send == null) {
                        int seconds = mImGo ? 10 : 20;
                        for (int i = 0; i < seconds && mRunning && send == null && !mYielded; i++) {
                            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                            send = mSendSocket;
                            if (send == null) send = promoteStashedToSend();
                        }
                        if (send == null) {
                            if (mYielded) {
                                Bus.log("传输: 本机已让出,不再建立发送通道");
                                return;
                            }
                            fail(mImGo ? "对方尚未连接,请让对方保持在前台再试"
                                    : "连接尚未就绪(热点接入中,请稍候再上滑)");
                            return;
                        }
                    }
                    if (mPeerWantsSend && mLocalSwipedAt == 0) {
                        Bus.log("传输: 对端先上滑,本机不再发送");
                        mYielded = true;
                        notifyRoleYielded();
                        return;
                    }
                    if (mLocalSwipedAt == 0) mLocalSwipedAt = SystemClock.elapsedRealtime();
                    //pri added by zhuyang,Give a simultaneous peer the 300ms window to announce ROLE(S) before our offer,20261017,start
                    if (!settleRoleWindow(send)) {
                        Bus.log("传输: 角色窗口内本机让出或连接断开,放弃发送报价");
                        return;
                    }
                    //pri added by zhuyang,Give a simultaneous peer the 300ms window to announce ROLE(S) before our offer,20261017,end
                    //pri added by zhuyang,Heartbeat starts before the offer (keep the receiver alive) but rx-dead is armed only for the verdict wait,20261017,start
                    startHeartbeat(send, false);
                    //pri added by zhuyang,Heartbeat starts before the offer (keep the receiver alive) but rx-dead is armed only for the verdict wait,20261017,end
                    final int round = mRound; //pri added by zhuyang,Round token: a stale verdict is discarded by readVerdict,20261014
                    int total = 0;
                    //pri added by zhuyang,Offer stage: META(total) + one preview, then wait for the verdict,20261014,start
                    mWriteLock.lock();
                    try {
                        //pri added by zhuyang,Guarantee ROLE(S) precedes META on this link even if the async write lost the race,20261017
                        sendRoleOnLink(send, "S");
                        writeBusinessFrame(send, Proto.TYPE_META, uris.size(), null);
                        if (!uris.isEmpty()) {
                            byte[] thumb = thumbnail(uris.get(0)); //pri added by zhuyang,Only the first preview, the receive card consumes the last one,20261014
                            if (thumb != null) {
                                writeBusinessFrame(send, Proto.TYPE_PREVIEW, thumb.length, thumb);
                            }
                        }
                        send.out.flush();
                    } finally {
                        mWriteLock.unlock();
                    }
                    Bus.log("传输: 已报价(" + uris.size() + " 张 + 预览),等对方「接收/拒绝」…("
                            + (VERDICT_WAIT_MS / 1000) + "s 上限)");
                    //pri added by zhuyang,Arm the dead-peer check now that we are reading for the verdict,20261017
                    send.lastRx.set(SystemClock.elapsedRealtime());
                    send.hbExpectRx = true;
                    int verdict = readVerdict(send, round);
                    if (verdict == VERDICT_YIELDED) {
                        stopHeartbeat(send);
                        Bus.log("传输: 已让出并转入接收,本机报价作废");
                        return;
                    }
                    if (verdict != Proto.TYPE_ACCEPT) {
                        stopHeartbeat(send);
                        closeQuietly(send);
                        if (mSendSocket == send) mSendSocket = null;
                        if (mStashedSocket == send) mStashedSocket = null;
                        //pri added by zhuyang,Do not report a dead link as a normal rejection,20261017
                        if (send.linkDead.get()) {
                            Bus.log("传输: 报价阶段连接已失效,整图未发出");
                        } else {
                            Bus.log("传输: 对方未同意接收,整图未发出");
                        }
                        releaseHotspotLater();
                        return;
                    }
                    //pri added by zhuyang,Offer stage: META(total) + one preview, then wait for the verdict,20261014,end
                    // 预读线程:先把图读进内存,「读相册」与「写 socket」并行(读一张 ~0.2-0.4s)
                    final java.util.concurrent.ArrayBlockingQueue<Object> q =
                            new java.util.concurrent.ArrayBlockingQueue<Object>(2);
                    final Object eod = new Object();
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                for (Uri uri : uris) {
                                    q.put(readAll(mCtx.getContentResolver().openInputStream(uri)));
                                }
                            } catch (Throwable t) {
                                Bus.log("传输: 读取图片失败 " + t);
                            }
                            try { q.put(eod); } catch (InterruptedException ignored) {}
                        }
                    }).start();
                    //pri added by zhuyang,Delivery stage: full images only after ACCEPT,20261014,start
                    //pri added by zhuyang,From here on a blocked write is caught by the stall watchdog,20261017,start
                    send.hbWritePhase = true;
                    send.hbExpectRx = false;
                    send.lastWriteProgressAt = SystemClock.elapsedRealtime();
                    Bus.log("传输: 进入发送阶段,写停滞 " + (WRITE_STALL_MS / 1000) + "s 看门狗已启用");
                    //pri added by zhuyang,From here on a blocked write is caught by the stall watchdog,20261017,end
                    for (int i = 0; i < uris.size(); i++) {
                        Object o = q.take();
                        if (o == eod) break;
                        if (mYielded) break;
                        byte[] data = (byte[]) o;
                        Bus.log("传输: 正在发送第 " + (i + 1) + "/" + uris.size() + " 张 " + data.length + " 字节…");
                        mWriteLock.lock();
                        try {
                            writeFullFrame(send, data);
                            send.out.flush();
                        } finally {
                            mWriteLock.unlock();
                        }
                        total += data.length;
                    }
                    stopHeartbeat(send);
                    closeQuietly(send);
                    if (mSendSocket == send) mSendSocket = null;
                    if (mStashedSocket == send) mStashedSocket = null;
                    Bus.log("传输: 发送完成,共 " + uris.size() + " 张 " + total + " 字节");
                    releaseHotspotLater();
                    final int n = total;
                    mMain.post(new Runnable() {
                        @Override public void run() { mListener.onImageSent(n); }
                    });
                    //pri added by zhuyang,Delivery stage: full images only after ACCEPT,20261014,end
                } catch (Exception e) {
                    if (send != null) stopHeartbeat(send);
                    if (mYielded) {
                        Bus.log("传输: 本机已让出,放弃发送: " + e);
                    } else if (send != null && send.linkDead.get() && send.failReason != null) {
                        notifyLinkFailedOnce(send, send.failReason);
                    } else {
                        fail("发送失败: " + e);
                    }
                }
            }
        }).start();
    }
    //pri modify by zhuyang,Role convergence: send on the Link, wait out the 300ms role window, and abort when yielding,20261017,end

    //pri added by zhuyang,Wait out the role window while reading frames: a peer ROLE(S) in the window is arbitrated by sid,20261017,start
    private boolean settleRoleWindow(Link send) {
        long deadline = mLocalSwipedAt + ROLE_CONFLICT_WINDOW_MS;
        try {
            send.s.setSoTimeout(RX_TIMEOUT_MS); //pri added by zhuyang,Every read path gets an upper bound,20261017
            while (mRunning && !mYielded) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) return true;
                if (send.in.available() <= 0) {
                    try { Thread.sleep(Math.min(remaining, 20)); } catch (InterruptedException ignored) {}
                    continue;
                }
                Proto.Frame f = Proto.readFrame(send.in, RX_TIMEOUT_MS, Proto.MAX_FRAME_LEN);
                if (f == null) {
                    Bus.log("传输: 角色窗口内连接已关闭");
                    return false;
                }
                if (f.type == Proto.TYPE_ROLE) {
                    Proto.Role role = Proto.parseRole(f.data);
                    if (role == null) continue;
                    if (mPeerSid != null && !mPeerSid.equalsIgnoreCase(role.sid)) continue;
                    mPeerRole = role.role;
                    if ("S".equals(role.role)) {
                        Bus.log("传输: 角色窗口内收到对端 ROLE(S)");
                        mPeerSwipedAt = SystemClock.elapsedRealtime();
                        mPeerWantsSend = true;
                        arbitrate(send, role.sid, true);
                        if (mYielded) return false;
                    }
                } else if (f.type == Proto.TYPE_META || f.type == Proto.TYPE_PREVIEW
                        || f.type == Proto.TYPE_FULL) {
                    synchronized (mPreReadFrames) { mPreReadFrames.add(f); }
                    Bus.log("传输: 角色窗口内暂存帧类型 " + f.type);
                }
            }
            return false;
        } catch (Exception e) {
            Bus.log("传输: 角色窗口内读帧失败: " + e);
            return false;
        } finally {
            try { send.s.setSoTimeout(0); } catch (Exception ignored) {}
        }
    }
    //pri added by zhuyang,Wait out the role window while reading frames: a peer ROLE(S) in the window is arbitrated by sid,20261017,end

    //pri added by zhuyang,Offer/verdict layer of the two-phase protocol,20261014,start
    //pri modify by zhuyang,Read verdict frames in a loop: ROLE/META/PREVIEW are skipped, 0 = no valid verdict,20261017,start
    private int readVerdict(Link link, final int round) {
        int verdict = 0;
        long deadline = SystemClock.elapsedRealtime() + VERDICT_WAIT_MS;
        try {
            while (mRunning) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new java.net.SocketTimeoutException();
                link.s.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
                Proto.Frame f = Proto.readFrame(link.in, 0, Proto.MAX_FRAME_LEN);
                if (f == null) {
                    Bus.log("传输: 回执连接已关闭");
                    break;
                }
                //pri added by zhuyang,Answer the peer heartbeat while the user is deciding,20261017,start
                if (f.type == Proto.TYPE_PING) {
                    replyPong(link);
                    continue;
                }
                if (f.type == Proto.TYPE_PONG) {
                    Bus.log("传输: 收到 PONG");
                    continue;
                }
                //pri added by zhuyang,Answer the peer heartbeat while the user is deciding,20261017,end
                if (round != mRound) {
                    Bus.log("传输: 收到上一轮的回执,已忽略");
                    return 0;
                }
                if (f.type == Proto.TYPE_ACCEPT || f.type == Proto.TYPE_REJECT) {
                    verdict = f.type;
                    break;
                }
                if (f.type == Proto.TYPE_ROLE) {
                    Proto.Role role = Proto.parseRole(f.data);
                    if (role != null) {
                        mPeerRole = role.role;
                        if ("S".equals(role.role) && mLocalSwipedAt != 0 && !mYielded
                                && mPeerSwipedAt == 0) {
                            Bus.log("传输: 发送连接上收到对端 ROLE(S),进入角色裁决");
                            mPeerSwipedAt = SystemClock.elapsedRealtime();
                            mPeerWantsSend = true;
                            arbitrate(link, role.sid, true);
                            if (mYielded) return VERDICT_YIELDED;
                        }
                    }
                    continue;
                }
                if (f.type == Proto.TYPE_META || f.type == Proto.TYPE_PREVIEW) {
                    //pri added by zhuyang,Keep the winner's offer frames: a loser replays them as receiver,20261017
                    synchronized (mPreReadFrames) { mPreReadFrames.add(f); }
                }
                Bus.log("传输: 忽略非回执帧类型 " + f.type);
            }
        } catch (java.net.SocketTimeoutException e) {
            Bus.log("传输: 等待对方「接收/拒绝」超时(" + (VERDICT_WAIT_MS / 1000) + "s)");
        } catch (Exception e) {
            Bus.log("传输: 未收到回执(对方已断开): " + e);
        }
        if (mYielded) return VERDICT_YIELDED;
        synchronized (mPreReadFrames) { mPreReadFrames.clear(); } //pri added by zhuyang,No yield: the peer's offer frames are stale,20261017
        if (verdict == Proto.TYPE_ACCEPT) {
            Bus.log("传输: 对方已确认接收");
            mMain.post(new Runnable() {
                @Override public void run() { mListener.onPeerAccepted(); }
            });
        } else if (verdict == Proto.TYPE_REJECT) {
            Bus.log("传输: 对方拒收");
            mMain.post(new Runnable() {
                @Override public void run() { mListener.onPeerRejected(); }
            });
        } else if (link.linkDead.get() && link.failReason != null) {
            //pri added by zhuyang,Heartbeat already judged the peer dead: report that, not a plain timeout,20261017
            notifyLinkFailedOnce(link, link.failReason);
        } else {
            mMain.post(new Runnable() {
                @Override public void run() { mListener.onVerdictTimeout(); }
            });
        }
        return verdict;
    }
    //pri modify by zhuyang,Read verdict frames in a loop: ROLE/META/PREVIEW are skipped, 0 = no valid verdict,20261017,end

    //pri added by zhuyang,Receiver: write the ACCEPT verdict back (images follow from the peer),20261014
    public void confirmReceived() {
        writeVerdict(Proto.TYPE_ACCEPT);
    }

    //pri added by zhuyang,Receiver: write the REJECT verdict back (no image was ever sent),20261014
    public void rejectTransfer() {
        writeVerdict(Proto.TYPE_REJECT);
    }

    //pri modify by zhuyang,Write the verdict on the receive Link at most once per round,20261017
    private void writeVerdict(final int type) {
        if (mVerdictSent) {
            Bus.log("传输: 本轮回执已发过,忽略");
            return;
        }
        final Link link = mPeerSocket;
        if (link == null || link.s.isClosed() || !link.s.isConnected()) {
            Bus.log("传输: 回执写回失败(连接已关闭) type=" + type);
            return;
        }
        mVerdictSent = true;
        mIo.execute(new Runnable() {
            @Override public void run() {
                try {
                    mWriteLock.lock();
                    try {
                        Proto.writeFrame(link.out, type, 0, null);
                        link.out.flush();
                        markTx(link);
                    } finally {
                        mWriteLock.unlock();
                    }
                    Bus.log("传输: 已" + (type == Proto.TYPE_ACCEPT ? "同意接收" : "拒绝接收") + ",回执已写回");
                } catch (Exception e) {
                    Bus.log("传输: 回执写回异常 " + e);
                }
            }
        });
    }

    //pri added by zhuyang,MediaStore Uri of the first image saved this round (null = nothing saved),20261014
    public Uri firstSavedUri() {
        return mFirstSavedUri;
    }

    //pri added by zhuyang,Snapshot of every MediaStore Uri saved this round, in receive order (empty = nothing saved),20261014
    public java.util.ArrayList<Uri> savedUris() {
        return new java.util.ArrayList<Uri>(mSavedUris);
    }
    //pri added by zhuyang,Offer/verdict layer of the two-phase protocol,20261014,end

    //pri added by zhuyang,Release the hotspot network only after the whole transaction finished,20261014
    private void releaseHotspotLater() {
        if (mWifiNetCb == null) return;
        mMain.postDelayed(new Runnable() {
            @Override public void run() { releaseNetworkRequest(); }
        }, 5000);
    }

    private byte[] thumbnail(Uri uri) {
        try {
            java.io.InputStream in = mCtx.getContentResolver().openInputStream(uri);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(in, null, o);
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            int scale = 1;
            while (o.outWidth / scale > 900 || o.outHeight / scale > 900) scale *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = scale;
            java.io.InputStream in2 = mCtx.getContentResolver().openInputStream(uri);
            Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in2, null, o2);
            try { if (in2 != null) in2.close(); } catch (Exception ignored) {}
            if (bmp == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    public void destroy() {
        mRunning = false;
        try { if (mServer != null) mServer.close(); } catch (Exception ignored) {}
        stopHeartbeat(mPeerSocket);
        stopHeartbeat(mStashedSocket);
        stopHeartbeat(mSendSocket);
        closeQuietly(mPeerSocket);
        closeQuietly(mStashedSocket);
        closeQuietly(mSendSocket);
        try { if (mRx != null) mCtx.unregisterReceiver(mRx); } catch (Exception ignored) {}
        mRx = null;
        releaseNetworkRequest();
        closeHotspot();
        try { if (mP2p != null && mChannel != null) mP2p.removeGroup(mChannel, null); } catch (Exception ignored) {}
    }

    // ---------- helpers ----------

    private void fail(final String why) {
        Bus.log("传输: " + why);
        mMain.post(new Runnable() {
            @Override public void run() { mListener.onXferFailed(why); }
        });
    }

    private static String errName(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY: return "BUSY";
            case WifiP2pManager.ERROR: return "ERROR";
            case WifiP2pManager.P2P_UNSUPPORTED: return "P2P_UNSUPPORTED";
            case WifiP2pManager.NO_SERVICE_REQUESTS: return "NO_SERVICE_REQUESTS";
            default: return "?";
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) throw new Exception("无法读取图片");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int scale = 1;
            while (o.outWidth / scale > 1600 || o.outHeight / scale > 1600) scale *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            Bus.log("传输: 解码失败 " + t);
            return null;
        }
    }

    //pri added by zhuyang,Returns the MediaStore Uri (null on failure / API below 29),20261014
    private Uri saveToGallery(byte[] data) {
        if (Build.VERSION.SDK_INT < 29) return null; // 老版本要写权限,demo 不处理
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "nfctap_" + System.currentTimeMillis() + ".jpg");
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/NfcTapDemo");
            Uri u = mCtx.getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return null;
            java.io.OutputStream os = mCtx.getContentResolver().openOutputStream(u);
            if (os == null) return null;
            os.write(data);
            os.close();
            return u;
        } catch (Throwable t) {
            Bus.log("传输: 存相册失败 " + t);
            return null;
        }
    }
}
