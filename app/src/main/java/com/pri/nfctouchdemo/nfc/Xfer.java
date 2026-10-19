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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

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
        //pri added by zhuyang,Batch 5: coded failure callback; TapService maps it to strings.xml,20261017
        default void onTransferError(XferError.Stage stage, int code, String detail) {}
        void onOfferExpired();                         //pri added by zhuyang,Receiver: peer never delivered the offer,20261014
        void onPeerRejected();                         //pri added by zhuyang,Sender: peer rejected the transfer,20261014
        void onPeerAccepted();                         //pri added by zhuyang,Sender: peer accepted the transfer,20261014
        void onVerdictTimeout();                       //pri added by zhuyang,Sender: no verdict within VERDICT_WAIT_MS,20261014
        void onPeerSwipedFirst();                      //pri added by zhuyang,Peer sent ROLE(S) before this phone swiped: switch to the receive card,20261017
        void onRoleYielded();                          //pri added by zhuyang,This phone lost the role race: stop sending and switch to the receive card,20261017
        //pri added by zhuyang,Byte-level progress, already throttled and posted on the main thread,20261017
        default void onProgress(TransferProgress p) {}
        //pri added by zhuyang,Batch 4: final success/failure counts (receiver hash failures or sender DONE_ACK code),20261017
        default void onTransferSummary(int ok, int failed) {}
        //pri added by zhuyang,Batch 4: storage precheck rejected the offer before ACCEPT,20261017
        default void onStorageInsufficient(long totalBytes) {}
    }

    private static final int PORT = 8988;
    //pri modify by zhuyang,Heartbeat and business frames share this lock so the wire never interleaves,20261017,start
    private final java.util.concurrent.locks.ReentrantLock mWriteLock =
            new java.util.concurrent.locks.ReentrantLock();
    //pri modify by zhuyang,Heartbeat and business frames share this lock so the wire never interleaves,20261017,end
    public static final String HS_SSID = "TapShare-Transfer";
    public static final String HS_PASS = "tapshare2026";
    //pri added by zhuyang,Hotspot bearer: silent hidden join first, specifier fallback with a dialog-sized budget,20261019,start
    private static final boolean P2P_ENABLED = false; //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019
    private static final long HS_HIDDEN_BUDGET_MS = 16000L;
    private static final long HS_SPEC_BUDGET_MS = 25000L;
    private static final long WIFI_ENABLE_WAIT_MS = 5000L;
    private static final long WIFI_ASSOC_POLL_MS = 6000L;
    private static final long OWNER_WAIT_MS = 18000L;
    private static final long HS_RELEASE_GRACE_MS = 1000L;
    private static final int HS_HIDDEN_ATTEMPTS = 2;
    private static final int HS_HIDDEN_UNAVAILABLE = 0;
    private static final int HS_HIDDEN_OK = 1;
    private static final int HS_HIDDEN_FAILED = 2;
    //pri added by zhuyang,Hotspot bearer: silent hidden join first, specifier fallback with a dialog-sized budget,20261019,end
    private static final int VERDICT_WAIT_MS = 20000;  //pri modify by zhuyang,Shorten the verdict wait from 60s to 20s,20261017
    //pri added by zhuyang,Liveness limits: read cap, PING idle, dead peer, write stall and round total,20261017,start
    private static final int RX_TIMEOUT_MS = 30000;      // control/role paths still allow a slow first frame
    private static final int RX_DATA_TIMEOUT_MS = 10000; // data phase: a chunk or PING must arrive within 10s
    private static final int HEARTBEAT_IDLE_MS = 2000;   // no frame written for this long -> PING
    private static final int HEARTBEAT_DEAD_MS = 10000;  // no byte received for this long -> link is dead
    private static final int HEARTBEAT_TICK_MS = 500;    // heartbeat check period
    private static final int WRITE_STALL_MS = 10000;     // transfer phase: no write progress -> close
    private static final long TRANSFER_TOTAL_MS = 5 * 60 * 1000L; // one photo round is capped at 5 minutes
    private static final int OFFER_WAIT_MS = 20000;      // connected receiver waiting for the peer's offer
    private static final int WRITE_CHUNK = 64 * 1024;    // refresh write progress inside one large frame
    //pri added by zhuyang,Liveness limits: read cap, PING idle, dead peer, write stall and round total,20261017,end
    //pri added by zhuyang,Chunked delivery: 128KB wire chunks, a 20s completion handshake and 4Hz progress posts,20261017,start
    private static final int CHUNK_SIZE = 128 * 1024;
    private static final int DONE_ACK_TIMEOUT_MS = 20000;
    private static final long PROGRESS_NOTIFY_MS = 250;
    //pri added by zhuyang,Chunked delivery: 128KB wire chunks, a 20s completion handshake and 4Hz progress posts,20261017,end
    //pri added by zhuyang,Batch 4: free-space headroom demanded on top of the announced payload,20261017
    private static final long STORAGE_HEADROOM_BYTES = 100L * 1024 * 1024;
    private static final int PROBE_MS = 3000;          //pri added by zhuyang,An accepted connection that stays silent this long carries no incoming data,20261014
    private static final int MAX_UNAUTH_CONNECTIONS = 4; //pri added by zhuyang,Cap concurrent connections that still owe a HELLO,20261017
    private static final int ROLE_CONFLICT_WINDOW_MS = 300; //pri added by zhuyang,Wait window after our own swipe for a peer ROLE(S) before offering,20261017
    private static final int ROLE_READ_TIMEOUT_MS = 1500;   //pri added by zhuyang,Wait this long for the peer ROLE frame after HELLO_ACK,20261017
    private static final int ROLE_LISTEN_TIMEOUT_MS = 1000; //pri added by zhuyang,Upper bound for joining the role listener before reusing its stream,20261017
    private static final int VERDICT_YIELDED = -1;          //pri added by zhuyang,readVerdict result: this phone yielded and now receives,20261017
    //pri added by zhuyang,Batch 5: connect stage budget (LAN once + P2P 1/2/4s backoff),20261017,start
    private static final int LAN_CONNECT_TIMEOUT_MS = 2000;
    private static final int P2P_DISCOVER_MAX = 3;
    private static final int[] P2P_BACKOFF_MS = {1000, 2000, 4000};
    //pri added by zhuyang,Batch 5: connect stage budget (LAN once + P2P 1/2/4s backoff),20261017,end

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
        @Override public void onTransferError(XferError.Stage stage, int code, String detail) {
            for (Listener l : mAll) l.onTransferError(stage, code, detail);
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
        @Override public void onProgress(TransferProgress p) {
            for (Listener l : mAll) l.onProgress(p);
        }
        @Override public void onTransferSummary(int ok, int failed) {
            for (Listener l : mAll) l.onTransferSummary(ok, failed);
        }
        @Override public void onStorageInsufficient(long totalBytes) {
            for (Listener l : mAll) l.onStorageInsufficient(totalBytes);
        }
    };

    public void addListener(Listener l) {
        if (l != null && !mAll.contains(l)) mAll.add(l);
    }

    //pri added by zhuyang,Batch 5: an authentication/protocol failure must abort the whole connect stage,20261017,start
    private static final class FatalXferException extends java.io.IOException {
        final XferError error;

        FatalXferException(XferError error) {
            super(error == null ? "fatal xfer error" : error.toString());
            this.error = error;
        }
    }
    //pri added by zhuyang,Batch 5: an authentication/protocol failure must abort the whole connect stage,20261017,end

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
        volatile XferError failError; //pri added by zhuyang,Batch 5: coded reason for the reader/writer to report,20261017
        volatile Thread hbThread;
        //pri added by zhuyang,Per-link liveness state shared by the reader, writer and heartbeat,20261017,end
        String peerRole;        // ROLE read right after HELLO_ACK (null = old peer/timeout)
        String peerSid;         // sid carried by that ROLE
        String peerHave;        //pri added by zhuyang,Batch 4: receiver's completed-image bitmap from HELLO/HELLO_ACK,20261017
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
    private final TransferStore mStore; //pri added by zhuyang,Batch 4: incoming .part files and the resume bitmap,20261017
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
    private volatile boolean mRunning = true;
    private volatile String mGoAddress;
    private volatile boolean mImGo;
    private volatile boolean mConnecting;
    private volatile boolean mDiscovering;
    private int mP2pAttempt; //pri modify by zhuyang,Batch 5: local P2P discovery counter, 1/2/4s backoff,20261017
    private volatile boolean mSenderWaiting; //pri modify by zhuyang,Batch 5: also read by the connect-strategy thread,20261017

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
    //pri added by zhuyang,Batch 6: one AP owner per round and credentials derived from both sids,20261017,start
    private volatile boolean mApOwner = true;
    private volatile String mDerivedSsid;
    private volatile String mDerivedPass;
    private volatile int mHotspotGen;
    //pri added by zhuyang,Batch 6: one AP owner per round and credentials derived from both sids,20261017,end
    //pri added by zhuyang,Silent hotspot join bookkeeping: the joined netId and the network to restore,20261019,start
    private volatile int mJoinedNetId = -1;
    private volatile String mJoinedSsid;
    private volatile boolean mHiddenJoinTried;
    private volatile String mPrevWifiSsid;
    private volatile int mPrevWifiNetId = -1;
    private volatile int mWifiGen;
    //pri added by zhuyang,Silent hotspot join bookkeeping: the joined netId and the network to restore,20261019,end
    private volatile android.net.ConnectivityManager.NetworkCallback mWifiNetCb;
    private int mIdRetry;

    public Xfer(Context ctx, Listener listener) {
        mCtx = ctx.getApplicationContext();
        mStore = new TransferStore(mCtx); //pri added by zhuyang,Batch 4: temp files live under cache/incoming/<sid>,20261017
        addListener(listener);
        //pri added by zhuyang,P2P is no longer initialized at all (product decision: same LAN -> hotspot),20261019
        mP2p = null;
        mChannel = null;
    }

    /** 读取本机在 Wi-Fi Direct 里的名字/地址(Android_xxxx 这种),供对方精确匹配。 */
    private void fetchSelfIdentity() {
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019
        if (!P2P_ENABLED) return;
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
        //pri added by zhuyang,Batch 6: both ends derive the same SSID/pass from both sids,20261017
        deriveHotspotCredentials();
        //pri added by zhuyang,The debug path has no sid: both ends must skip HELLO and say so in the log,20261017,start
        if (mPeerSid == null) {
            Bus.log("传输: [未认证] 本轮对方 payload 没有 sid,跳过 HELLO 握手(调试路径)");
        } else {
            Bus.log("传输: [认证] 本轮将在每条 TCP 连接首帧校验对方 sid");
        }
        //pri added by zhuyang,The debug path has no sid: both ends must skip HELLO and say so in the log,20261017,end
        Bus.log("承载: 目标设备 = " + modelName + (btName == null ? "" : "/" + btName)
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

    //pri modify by zhuyang,A half-open socket is still connected/open locally: also require the link not marked dead,20261019
    private static boolean isAlive(Link l) {
        return l != null && !l.linkDead.get() && l.s.isConnected() && !l.s.isClosed();
    }

    //pri added by zhuyang,Batch 5: a stray inbound connection must not fail an already-running round,20261017
    private void failInbound(XferError err) {
        if (hasLiveLink()) {
            Bus.log("传输: [认证] 已有活动连接,忽略本次入站失败 " + err);
            return;
        }
        fail(err);
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
        mP2pAttempt = 0;
        mReceiverRole = false;
        mCanSend = false;   //pri added by zhuyang,New round: undecided until TapService reports the photos,20261014
        mHasPhotos = false; //pri added by zhuyang,New round: no photos reported yet,20261017
        mPeerSid = null;    //pri added by zhuyang,New round: the peer sid is set again from the new payload,20261017
        //pri added by zhuyang,Batch 6: a new round derives new credentials, so any AP left from the previous round is stale,20261019,start
        if (mHotspotReservation != null || mHotspotStarting) {
            Bus.log("热点: 新一轮开始,关闭上一轮残留热点");
            closeHotspot();
        }
        //pri added by zhuyang,Forget the previous round's joined AP and restore the user's network,20261019,start
        restoreWifiAfterJoin();
        //pri added by zhuyang,Forget the previous round's joined AP and restore the user's network,20261019,end
        mDerivedSsid = null;
        mDerivedPass = null;
        //pri added by zhuyang,Batch 6: a new round derives new credentials, so any AP left from the previous round is stale,20261019,end
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

    //pri added by zhuyang,Batch 6: TapService fixes the single AP owner from the NFC role,20261017,start
    public void setApOwner(boolean owner) {
        mApOwner = owner;
        Bus.log("热点: 本轮" + (owner ? "由本机开热点(唯一 AP owner)" : "本机不开热点(由对端当 AP owner)"));
        if (!owner && (mHotspotReservation != null || mHotspotActive || mHotspotStarting)) {
            Bus.log("热点: 非 owner,先关闭本机残留热点");
            closeHotspot();
        }
    }
    //pri added by zhuyang,Batch 6: TapService fixes the single AP owner from the NFC role,20261017,end

    //pri added by zhuyang,Batch 6: derive the same SSID/pass on both ends from the two sids,20261017,start
    //pri added by zhuyang,Batch 6: sessionSid = SHA-256(min(localSid,peerSid) + max(localSid,peerSid)),20261017
    //pri added by zhuyang,Batch 6: ssid = "TShare-" + hex(sessionSid)[0..5]; pass = "ts" + hex(sessionSid)[6..21],20261017
    //pri added by zhuyang,Batch 6: both phones compute the same pair, so no password travels in the payload,20261017
    private void deriveHotspotCredentials() {
        mDerivedSsid = null;
        mDerivedPass = null;
        if (mPeerSid == null) {
            Bus.log("热点: [未认证] 使用固定热点凭据");
            return;
        }
        if (Build.VERSION.SDK_INT < 33) {
            Bus.log("热点: API<33 不支持自定义 LOHS 配置,本轮不派生凭据(第二次碰触用 hs/hp 兜底)");
            return;
        }
        try {
            String local = Bus.sid().toLowerCase(Locale.US);
            String peer = mPeerSid.toLowerCase(Locale.US);
            String min = local.compareTo(peer) <= 0 ? local : peer;
            String max = local.compareTo(peer) <= 0 ? peer : local;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((min + max).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            String hex = sb.toString();
            mDerivedSsid = "TShare-" + hex.substring(0, 6);
            mDerivedPass = "ts" + hex.substring(6, 22);
            Bus.log("热点: 会话派生凭据 SSID=" + mDerivedSsid + ",pass=" + mDerivedPass);
        } catch (Throwable t) {
            Bus.log("热点: 派生凭据失败,退回固定凭据: " + t);
        }
    }
    //pri added by zhuyang,Batch 6: derive the same SSID/pass on both ends from the two sids,20261017,end

    //pri added by zhuyang,Batch 6: a non-owner receiver joins the owner AP so it can accept the owner's send,20261017,start
    public void startHotspotClient() {
        if (mApOwner) return;
        //pri added by zhuyang,LAN first: same subnet means the peer can reach us directly, so never join a hotspot,20261019,start
        if (likelySameLan()) {
            Bus.log("承载: 对方与本机同处一个 LAN(对方 IP=" + mPeerLanIp + "),跳过热点接入,走同网直连");
            return;
        }
        //pri added by zhuyang,LAN first: same subnet means the peer can reach us directly, so never join a hotspot,20261019,end
        final String ssid;
        final String pass;
        final boolean derived;
        if (Build.VERSION.SDK_INT >= 33 && mDerivedSsid != null) {
            ssid = mDerivedSsid;
            pass = mDerivedPass;
            derived = true;
        } else if (mPeerHsSsid != null) {
            ssid = mPeerHsSsid;
            pass = mPeerHsPass;
            derived = false;
        } else if (Build.VERSION.SDK_INT >= 33 && mPeerSid == null) {
            ssid = HS_SSID;
            pass = HS_PASS;
            derived = false;
        } else {
            Bus.log("传输: 非 AP owner 且本轮没有热点凭据,等待局域网直连");
            return;
        }
        if (mHotspotReservation != null || mHotspotActive || mHotspotStarting) {
            Bus.log("热点: 非 owner,加入前关闭本机残留热点");
            closeHotspot();
        }
        new Thread(new Runnable() {
            @Override public void run() {
                if (!mRunning) return;
                Bus.log("传输: 非 AP owner 主动接入对方热点(接收方向)");
                if (joinHotspot(ssid, pass, derived)) {
                    Bus.log("传输: 非 AP owner 接入流程结束(热点已接入或已有活动连接)");
                }
            }
        }, "ap-client").start();
    }
    //pri added by zhuyang,Batch 6: a non-owner receiver joins the owner AP so it can accept the owner's send,20261017,end

    //pri added by zhuyang,Lucky same-subnet peers skip joining; only treat equal /24 as likely same LAN,20261019,start
    private boolean likelySameLan() {
        String local = Bus.lanIpv4();
        String peer = mPeerLanIp;
        if (local == null || peer == null) return false;
        int[] a = parseIpv4(local);
        int[] b = parseIpv4(peer);
        return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private static int[] parseIpv4(String ip) {
        try {
            String[] p = ip.split("\\.");
            if (p.length != 4) return null;
            int[] out = new int[4];
            for (int i = 0; i < 4; i++) out[i] = Integer.parseInt(p[i]);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
    //pri added by zhuyang,Lucky same-subnet peers skip joining; only treat equal /24 as likely same LAN,20261019,end

    //pri added by zhuyang,An AP owner can drop its hotspot once the peer reaches it over the same LAN,20261019,start
    private void maybeCloseHotspotForLan(Socket s) {
        if (!mApOwner) return;
        if (!(mHotspotActive || mHotspotStarting || mHotspotReservation != null)) return;
        if (s == null || s.getInetAddress() == null || mPeerLanIp == null) return;
        String remote = s.getInetAddress().getHostAddress();
        if (mPeerLanIp.equals(remote)) {
            Bus.log("热点: 对方经同 LAN 直连(" + remote + "),关闭热点(省电/恢复射频)");
            closeHotspot();
        }
    }
    //pri added by zhuyang,An AP owner can drop its hotspot once the peer reaches it over the same LAN,20261019,end

    /** 接收方按下按钮时即开启本地热点,把 SSID/密码写进 payload,让对方免搜索直连。 */
    public void startHotspot() {
        if (!mApOwner) {
            Bus.log("热点: 本机不是本轮 AP owner,跳过开启");
            return;
        }
        if (mHotspotReservation != null || mHotspotStarting) return;
        try {
            final WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            //pri added by zhuyang,STA+AP concurrency is supported: try the hotspot even when Wi-Fi is on,20261019,start
            //pri added by zhuyang,No need to turn Wi-Fi off first; LOHS also works when Wi-Fi is off,20261019
            //pri added by zhuyang,STA+AP concurrency is supported: try the hotspot even when Wi-Fi is on,20261019,end
            final int gen = ++mHotspotGen;
            mHotspotStarting = true;
            startServer(); // 提前监听 8988:消除"对方连过来时服务端还没起"的竞态
            Bus.log("热点: 正在开启本地热点(给对方免搜索直连用)…");
            WifiManager.LocalOnlyHotspotCallback cb = new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    if (gen != mHotspotGen || !mApOwner) {
                        //pri added by zhuyang,Batch 6: a closed/superseded attempt must not leave an AP behind,20261017
                        try { reservation.close(); } catch (Throwable ignored) {}
                        Bus.log("热点: 忽略已过期的开启回调,AP 立即关闭");
                        return;
                    }
                    mHotspotReservation = reservation;
                    mHotspotActive = true;
                    mHotspotStarting = false;
                    android.net.wifi.SoftApConfiguration cfg = reservation.getSoftApConfiguration();
                    if (cfg != null && cfg.getSsid() != null) {
                        Bus.setHotspotInfo(cfg.getSsid(), cfg.getPassphrase());
                        Bus.log("热点: 已开启 SSID=" + cfg.getSsid() + ",对方碰一下即可直连");
                    } else {
                        Bus.log("热点: 已开启但拿不到 SSID");
                    }
                }
                @Override public void onFailed(int reason) {
                    if (gen != mHotspotGen) return;
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
                    Bus.log("热点: 开启失败 " + r + "(无 P2P 兜底,等待同 LAN/热点接入)");
                    mHotspotActive = false;
                }
                @Override public void onStopped() {
                    if (gen != mHotspotGen) return;
                    mHotspotActive = false;
                    mHotspotStarting = false;
                    mHotspotReservation = null;
                    Bus.log("热点: 已停止");
                }
            };
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    final boolean derived = mDerivedSsid != null;
                    final String ssid = derived ? mDerivedSsid : HS_SSID;
                    final String pass = derived ? mDerivedPass : HS_PASS;
                    android.net.wifi.SoftApConfiguration cfg =
                            new android.net.wifi.SoftApConfiguration.Builder()
                                    .setWifiSsid(android.net.wifi.WifiSsid.fromBytes(ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                    .setPassphrase(pass,
                                            android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                                    .build();
                    wm.startLocalOnlyHotspotWithConfiguration(cfg, mCtx.getMainExecutor(), cb);
                    Bus.log("热点: " + (derived ? "会话派生配置" : "[未认证] 固定配置")
                            + "已发起(SSID=" + ssid + ")");
                } else {
                    //pri added by zhuyang,Batch 6: API<33 has no custom LOHS config, keep the two-tap hs/hp fallback,20261017
                    wm.startLocalOnlyHotspot(cb, mMain);
                    Bus.log("热点: API<33 不支持自定义配置,使用系统随机热点凭据(第二次碰触 hs/hp 兜底)");
                }
            } catch (Throwable t) {
                Bus.log("热点: 固定配置不可用,退回默认: " + t);
                try { wm.startLocalOnlyHotspot(cb, mMain); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Bus.log("热点: 开启异常 " + t);
        }
    }

    private void closeHotspot() {
        mHotspotGen++; //pri added by zhuyang,Batch 6: invalidate callbacks of the attempt being closed,20261017
        mHotspotStarting = false;
        try { if (mHotspotReservation != null) mHotspotReservation.close(); } catch (Throwable ignored) {}
        mHotspotReservation = null;
        mHotspotActive = false;
    }

    /** 关闭热点(调试/收尾用),同时把客户端从热点网络恢复到原 Wi-Fi。 */
    public void stopHotspot() {
        restoreWifiAfterJoin();
        closeHotspot();
    }

    /** 应用打开即预热: P2P 承载已停用,保留入口不做搜索。 */
    public void warmUp() {
        //pri added by zhuyang,P2P search is disabled (product decision: same LAN -> hotspot),20261019
        Bus.log("承载: 预热跳过 P2P 搜索(产品决策: 同 LAN→热点)");
    }

    private void warmDiscoverLoop() {
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019
        if (!P2P_ENABLED) return;
        if (!mRunning || mGoAddress != null || mGotConnection) return;
        try {
            mP2p.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {}
                @Override public void onFailure(int reason) {}
            });
        } catch (Throwable ignored) {}
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                if (mRunning && mGoAddress == null) warmDiscoverLoop();
            }
        }, 8000);
    }

    /** 接收方入口: 起服务端(同网对方可秒连/对方加入本机热点),P2P 已停用。 */
    public void startAsReceiver() {
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019,start
        mReceiverRole = true;
        startServer();
        if (mGotConnection) {
            Bus.log("传输: 对方已连接,无需重复等待");
            return;
        }
        Bus.log("传输: 等待对方连接(同 LAN 直连,或对方接入本机热点)…");
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mRunning && !mGotConnection) {
                    Bus.log("传输: 20 秒内仍未收到对方连接(检查两台手机的距离与 Wi-Fi 状态)");
                }
            }
        }, 20000);
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019,end
    }

    /** 发送方入口: 同 LAN 直连 → 热点,不再有 P2P 兜底。 */
    public void startAsSender() {
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019,start
        mReceiverRole = false;
        //pri added by zhuyang,A wait left over from a round with no live link must not block the new round,20261019,start
        if (mSenderWaiting && !hasLiveLink()) {
            Bus.log("承载: 上一轮的等待已失效(没有活链路),继续发起本轮连接");
            mSenderWaiting = false;
        }
        if (isAlive(mSendSocket)) {
            Bus.log("承载: 发送通道已就绪,忽略重复的发送请求");
            return;
        }
        if (mSenderWaiting) {
            Bus.log("承载: 已在等待对方接入");
            return;
        }
        //pri added by zhuyang,A wait left over from a round with no live link must not block the new round,20261019,end
        mSenderWaiting = true;
        Bus.log("承载: 本机为发送方:① 同 LAN → ② 热点(无 P2P 兜底)");
        startConnectStrategy();
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019,end
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
        //pri added by zhuyang,Only a live send link may be reused; a dead one must be rebuilt by this round,20261019,start
        if (!isAlive(send)) {
            closeQuietly(send);
            if (mSendSocket == send) mSendSocket = null;
            send = null;
        }
        boolean stashedUsable = isAlive(mStashedSocket) && mPeerSocket != mStashedSocket;
        if (send != null) {
            sendRoleOnLinkAsync(send, "S");
        } else if (stashedUsable) {
            // Only the peer-initiated link exists: announce our role on it now.
            sendRoleOnLinkAsync(mStashedSocket, "S");
        } else {
            //pri added by zhuyang,No usable link: drop a stale wait so startAsSender() really connects this round,20261019
            mSenderWaiting = false;
        }
        //pri added by zhuyang,Only a live send link may be reused; a dead one must be rebuilt by this round,20261019,end
        Bus.log("传输: 本机上滑,宣告 ROLE(S)"
                + (send != null ? "(发送通道已就绪)"
                        : (stashedUsable ? "(发送通道尚未建立,连接建立后补发)" : "(发送通道需重建)")));
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

    //pri added by zhuyang,Write a DATA frame in 64KB sub-writes so the stall watchdog sees real progress,20261017
    private void writeDataFrame(Link link, int seq, byte[] data, int off, int len) throws java.io.IOException {
        link.out.writeInt(Proto.MAGIC);
        link.out.writeInt(Proto.TYPE_DATA);
        link.out.writeInt(len + 4);
        link.out.writeInt(seq);
        markTx(link);
        int done = 0;
        while (done < len) {
            int n = Math.min(WRITE_CHUNK, len - done);
            link.out.write(data, off + done, n);
            done += n;
            markTx(link);
        }
    }

    //pri added by zhuyang,Explicit completion frames: DONE_ACK and CANCEL share the business write lock,20261017,start
    private void sendDoneAck(Link link, int code) {
        if (link == null || link.s.isClosed()) return;
        boolean locked = false;
        try {
            byte[] b = Proto.buildDoneAck(code);
            locked = mWriteLock.tryLock(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (locked) {
                Proto.writeFrame(link.out, Proto.TYPE_DONE_ACK, b.length, b);
                link.out.flush();
                markTx(link);
                Bus.log("传输: 已回 DONE_ACK(" + code + ")");
            }
        } catch (Exception e) {
            Bus.log("传输: 回 DONE_ACK 失败 " + e);
        } finally {
            if (locked) mWriteLock.unlock();
        }
    }

    private void sendCancel(Link link, String reason) {
        if (link == null || link.s.isClosed()) return;
        boolean locked = false;
        try {
            byte[] b = Proto.buildCancel(reason);
            locked = mWriteLock.tryLock(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (locked) {
                Proto.writeFrame(link.out, Proto.TYPE_CANCEL, b.length, b);
                link.out.flush();
                markTx(link);
                Bus.log("传输: 已发送 CANCEL — " + reason);
            }
        } catch (Exception e) {
            Bus.log("传输: 发送 CANCEL 失败 " + e);
        } finally {
            if (locked) mWriteLock.unlock();
        }
    }
    //pri added by zhuyang,Explicit completion frames: DONE_ACK and CANCEL share the business write lock,20261017,end

    //pri added by zhuyang,Post a throttled progress snapshot on the main thread; force bypasses the 250ms gate,20261017
    private void postProgress(TransferProgress p, boolean force) {
        if (p == null) return;
        if (!force && !p.takeNotifySlot(PROGRESS_NOTIFY_MS)) return;
        final TransferProgress snap = p.snapshot();
        mMain.post(new Runnable() {
            @Override public void run() { mListener.onProgress(snap); }
        });
    }
    //pri added by zhuyang,Liveness helpers: mark a written frame and write a possibly large frame with progress,20261017,end

    //pri added by zhuyang,Batch 4: receive session key (the sender's sid) shared by .part paths and the resume bitmap,20261017
    private String storeSid() {
        return TransferStore.sanitizeSid(mPeerSid != null ? mPeerSid : Bus.sid());
    }

    private String haveHex() {
        return mStore.haveHex(storeSid());
    }

    private static String sha256Hex(MessageDigest md) {
        return TransferStore.hex(md.digest());
    }

    private static String mbText(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1048576.0);
    }

    //pri added by zhuyang,Batch 4: read the source exactly once, hashing while staging; the wire then reuses the staged copy,20261017,start
    private File stageImage(Uri uri, MessageDigest md) throws java.io.IOException {
        File dir = new File(mCtx.getCacheDir(), "outgoing");
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new java.io.IOException("无法创建发送临时目录");
        }
        File tmp = File.createTempFile("img-", ".tmp", dir);
        InputStream in = mCtx.getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("无法读取图片");
        OutputStream out = null;
        try {
            out = new java.io.BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024);
            byte[] buf = new byte[CHUNK_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            out.flush();
            return tmp;
        } catch (java.io.IOException e) {
            try { tmp.delete(); } catch (Throwable ignored) {}
            throw e;
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }
    //pri added by zhuyang,Batch 4: read the source exactly once, hashing while staging; the wire then reuses the staged copy,20261017,end

    //pri added by zhuyang,Batch 4: free space on the partition that holds the incoming .part files,20261017
    private long availableBytes() {
        try {
            android.os.StatFs sf = new android.os.StatFs(mCtx.getCacheDir().getAbsolutePath());
            return sf.getAvailableBytes();
        } catch (Throwable t) {
            Bus.log("传输: 读取可用空间失败 " + t);
            return -1;
        }
    }

    //pri added by zhuyang,Batch 4: write REJECT synchronously on the receive thread before the link is closed,20261017
    private void sendRejectNow(Link link, String why) {
        if (link == null || link.s.isClosed()) return;
        boolean locked = false;
        try {
            locked = mWriteLock.tryLock(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (locked) {
                mVerdictSent = true;
                Proto.writeFrame(link.out, Proto.TYPE_REJECT, 0, null);
                link.out.flush();
                markTx(link);
                Bus.log("传输: 已回 REJECT — " + why);
            }
        } catch (Exception e) {
            Bus.log("传输: 回 REJECT 失败 " + e);
        } finally {
            if (locked) mWriteLock.unlock();
        }
    }

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
            //pri added by zhuyang,A failed ROLE write proves the link dead: close it so the next round rebuilds it,20261019
            signalLinkDead(link, new XferError(XferError.Stage.CONNECT, XferError.CONNECT_TIMEOUT,
                    "发送 ROLE 失败: " + e));
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
                    signalLinkDead(link, new XferError(XferError.Stage.TRANSFER, XferError.TRANSFER_TIMEOUT,
                            "传输超时(超过 " + (TRANSFER_TOTAL_MS / 60000) + " 分钟)"));
                    return;
                }
                if (link.hbWritePhase) {
                    //pri added by zhuyang,Transfer phase: a blocked write is caught by the stall watchdog,20261017
                    if (now - link.lastWriteProgressAt >= WRITE_STALL_MS) {
                        signalLinkDead(link, new XferError(XferError.Stage.TRANSFER, XferError.TRANSFER_STALL,
                                "写停滞超过 " + (WRITE_STALL_MS / 1000) + "s,连接失效"));
                        return;
                    }
                } else if (link.hbExpectRx && now - link.lastRx.get() >= HEARTBEAT_DEAD_MS) {
                    signalLinkDead(link, new XferError(XferError.Stage.TRANSFER, XferError.TRANSFER_TIMEOUT,
                            "连接超时(" + (HEARTBEAT_DEAD_MS / 1000) + "s 未收到对端任何数据)"));
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
                        signalLinkDead(link, new XferError(XferError.Stage.TRANSFER, XferError.TRANSFER_TIMEOUT,
                                "心跳发送失败(连接已断开)"));
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
    private void signalLinkDead(final Link link, final XferError err) {
        if (!link.linkDead.compareAndSet(false, true)) return;
        link.failError = err;
        link.failReason = err == null ? null : err.detail;
        Bus.log("传输: 连接失效 — " + err + ",关闭连接");
        closeQuietly(link);
    }

    private void notifyLinkFailedOnce(Link link, XferError err) {
        if (err == null) return;
        if (link == null) {
            fail(err);
            return;
        }
        if (!link.failCb.compareAndSet(false, true)) return;
        Bus.log("传输: " + err);
        fail(err);
    }

    private void notifyOfferExpiredOnce(Link link, XferError err) {
        if (err == null) return;
        if (link != null && !link.offerExpiredCb.compareAndSet(false, true)) return;
        Bus.log("传输: " + err);
        fail(err);
        mMain.post(new Runnable() {
            @Override public void run() { mListener.onOfferExpired(); }
        });
    }

    //pri added by zhuyang,Batch 5: a dead link keeps its coded reason for the waiting reader,20261017
    private static XferError linkError(Link link, XferError.Stage fallbackStage) {
        if (link != null && link.failError != null) return link.failError;
        String why = link != null && link.failReason != null ? link.failReason : "连接已断开";
        return new XferError(fallbackStage, XferError.TRANSFER_TIMEOUT, why);
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

    //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019
    private boolean mayDriveP2p() {
        return false;
    }

    /** 连接策略: ① 同 LAN 直连(碰巧同网) ② 热点(非 owner 加入/owner 等接入);无 P2P 兜底。 */
    private void startConnectStrategy() {
        new Thread(new Runnable() {
            @Override public void run() {
                //pri added by zhuyang,Batch 6: an established send channel needs no new join or direct connect,20261017
                if (isAlive(mSendSocket)) {
                    Bus.log("传输: 发送通道已就绪,跳过重新加入/直连");
                    return;
                }
                //pri added by zhuyang,LAN first: a lucky same-subnet peer is the cheapest bearer,20261019,start
                if (mPeerLanIp != null) {
                    Bus.log("承载: ① 同 LAN 直连 " + mPeerLanIp + "(1 次,"
                            + (LAN_CONNECT_TIMEOUT_MS / 1000) + "s)");
                    if (connectTo(mPeerLanIp, "局域网")) {
                        if (mApOwner && (mHotspotActive || mHotspotReservation != null || mHotspotStarting)) {
                            Bus.log("热点: 同 LAN 已连通,关闭本机热点(省电/恢复射频)");
                            closeHotspot();
                        }
                        return;
                    }
                    if (!mSenderWaiting) return;
                } else {
                    Bus.log("承载: 对方 payload 无局域网 IP,跳过同 LAN");
                }
                //pri added by zhuyang,LAN first: a lucky same-subnet peer is the cheapest bearer,20261019,end
                //pri added by zhuyang,Hotspot second: owner waits for the peer, non-owner joins the derived AP,20261019,start
                if (mApOwner) {
                    Bus.log("承载: ② 本机为 AP owner,等待对方接入本机热点…");
                    long deadline = SystemClock.elapsedRealtime() + OWNER_WAIT_MS;
                    while (mRunning && mSenderWaiting && SystemClock.elapsedRealtime() < deadline) {
                        if (hasLiveLink() || mGotConnection) {
                            Bus.log("承载: 对方已接入(热点/LAN)");
                            return;
                        }
                        if (!mHotspotActive && !mHotspotStarting) {
                            Bus.log("热点: 本机热点不可用,热点承载失败");
                            fail(new XferError(XferError.Stage.CONNECT, XferError.CONNECT_TIMEOUT,
                                    "热点开启失败且无法同 LAN 直连(请确认两台手机距离与 Wi-Fi 状态)"));
                            return;
                        }
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                    if (mRunning && mSenderWaiting && !hasLiveLink()) {
                        Bus.log("热点: " + (OWNER_WAIT_MS / 1000) + "s 内对方仍未接入,判定连接失败");
                        fail(new XferError(XferError.Stage.CONNECT, XferError.CONNECT_TIMEOUT,
                                "热点已开启但对方未接入(请确认两台手机距离与 Wi-Fi 状态)"));
                    }
                    return;
                }
                String hsSsid = null;
                String hsPass = null;
                boolean derived = false;
                if (Build.VERSION.SDK_INT >= 33 && mDerivedSsid != null) {
                    hsSsid = mDerivedSsid;
                    hsPass = mDerivedPass;
                    derived = true;
                } else if (mPeerHsSsid != null) {
                    hsSsid = mPeerHsSsid;
                    hsPass = mPeerHsPass;
                } else if (Build.VERSION.SDK_INT >= 33 && mPeerSid == null) {
                    hsSsid = HS_SSID;
                    hsPass = HS_PASS;
                }
                if (hsSsid == null) {
                    Bus.log("承载: 非 AP owner 且本轮没有热点凭据,热点承载失败");
                    fail(new XferError(XferError.Stage.CONNECT, XferError.CONNECT_TIMEOUT,
                            "没有可用的热点凭据(请确认两台手机距离与 Wi-Fi 状态)"));
                    return;
                }
                Bus.log("承载: ② 非 owner,用" + (derived ? "会话派生" : "对方") + "凭据加入热点 SSID=" + hsSsid);
                if (joinHotspot(hsSsid, hsPass, derived)) return;
                if (!mSenderWaiting) return;
                Bus.log("承载: ①同 LAN 与 ②热点均失败,无 P2P 兜底,报告 CONNECT_TIMEOUT");
                fail(new XferError(XferError.Stage.CONNECT, XferError.CONNECT_TIMEOUT,
                        "同 LAN 与热点均无法连接(请确认两台手机距离与 Wi-Fi 状态)"));
                //pri added by zhuyang,Hotspot second: owner waits for the peer, non-owner joins the derived AP,20261019,end
            }
        }).start();
    }

    //pri modify by zhuyang,Client handshake returns the Link so every later frame reuses the same streams,20261017,start
    private Link clientHello(Socket s) throws FatalXferException {
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
                //pri modify by zhuyang,Batch 4: advertise the completed-image bitmap so the sender can resume,20261017
                byte[] hello = Proto.buildHello(Bus.sid(), localRole(), mHasPhotos, haveHex());
                Proto.writeFrame(out, Proto.TYPE_HELLO, hello.length, hello);
                out.flush();
                Proto.Frame f = Proto.readFrame(in, Proto.HELLO_TIMEOUT_MS, Proto.MAX_HELLO_LEN);
                if (f == null || f.type != Proto.TYPE_HELLO_ACK) {
                    Bus.log("传输: [认证] 对方未回 HELLO_ACK,关闭连接");
                    closeQuietly(s);
                    throw new FatalXferException(new XferError(XferError.Stage.CONNECT,
                            XferError.AUTH_FAILED, "对方未回 HELLO_ACK(校验失败)"));
                }
                Proto.Hello ack = Proto.parseHello(f.data);
                if (ack == null || ack.version != Proto.HELLO_VERSION
                        || !mPeerSid.equalsIgnoreCase(ack.sid)) {
                    Bus.log("传输: [认证] HELLO_ACK 校验失败,关闭连接");
                    closeQuietly(s);
                    throw new FatalXferException(new XferError(XferError.Stage.CONNECT,
                            XferError.AUTH_FAILED, "HELLO_ACK 的 sid/版本不匹配"));
                }
                mPeerRole = ack.role;
                byte[] role = Proto.buildRole(localRole(), Bus.sid());
                Proto.writeFrame(out, Proto.TYPE_ROLE, role.length, role);
                out.flush();
                Bus.log("传输: [认证] HELLO_ACK 校验通过,对方角色=" + ack.role
                        + ",本机角色=" + localRole());
                Link link = new Link(s, in, out, rxAt, txAt);
                link.peerHave = ack.have; //pri added by zhuyang,Batch 4: resume bitmap of the peer receiver,20261017
                if (ack.have != null) Bus.log("传输: [认证] 对端已有图片位图 have=" + ack.have);
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
                                    throw new FatalXferException(new XferError(XferError.Stage.CONNECT,
                                            XferError.AUTH_FAILED, "对端 ROLE sid 不一致"));
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
        } catch (FatalXferException fe) {
            closeQuietly(s);
            throw fe;
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
        //pri modify by zhuyang,Batch 5: the LAN layer gets one 2s attempt; P2P owns its own budget,20261017,start
        for (int i = 0; i < 1 && mRunning; i++) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, PORT), LAN_CONNECT_TIMEOUT_MS);
                Link link = clientHello(s);
                if (link == null) continue;
                if (!handleClientRole(link)) {
                    Bus.log("传输: " + how + "连接交由接收/裁决路径接管 " + ip + ":" + PORT);
                    return true;
                }
                Bus.log("传输: " + how + "直连成功 " + ip + ":" + PORT + "(免搜索)");
                mListener.onP2pReady(false, ip);
                return true;
            } catch (FatalXferException fe) {
                fail(fe.error);
                return false;
            } catch (Exception e) {
                Bus.log("传输: " + how + "直连尝试 " + (i + 1) + " 失败: " + e);
            }
        }
        //pri modify by zhuyang,Batch 5: the LAN layer gets one 2s attempt; P2P owns its own budget,20261017,end
        return false;
    }

    //pri added by zhuyang,Hotspot join: hidden silent connect first, specifier only when the hidden API is unavailable,20261019,start
    private boolean joinHotspot(String ssid, String pass, boolean derived) {
        if (Build.VERSION.SDK_INT < 29) {
            Bus.log("热点: API<29 不支持热点接入");
            return false;
        }
        final WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return false;
        final int gen = mWifiGen;
        if (!ensureWifiEnabled(wm, gen)) return false;
        if (gen != mWifiGen) return false;
        if (isAlive(mSendSocket) || (mReceiverRole && isAlive(mStashedSocket))) {
            Bus.log("传输: 已有活动连接,跳过热点接入");
            return true;
        }
        Bus.log("传输: 接入对方热点 SSID=" + ssid + "(" + (derived ? "会话派生" : "对方凭据") + ")…");
        final int hr = joinHotspotHidden(wm, ssid, pass, gen);
        if (hr == HS_HIDDEN_OK) {
            String gw = waitForWifiGateway(wm, 5000L);
            if (connectViaGateway(gw)) return true;
            Bus.log("传输: 热点已接入但连不上网关,恢复原 Wi-Fi");
            restoreWifiAfterJoin();
            return false;
        }
        if (hr == HS_HIDDEN_UNAVAILABLE) {
            Bus.log("传输: 隐藏 Wi-Fi 接口不可用,回退 WifiNetworkSpecifier(预算 "
                    + (HS_SPEC_BUDGET_MS / 1000) + "s,系统弹窗时需用户点「连接」)");
            boolean ok = joinHotspotOnce(ssid, pass, !derived,
                    derived ? HS_SPEC_BUDGET_MS : (HS_SPEC_BUDGET_MS * 3 / 5));
            if (!ok && !derived && gen == mWifiGen) {
                Bus.log("传输: WPA3 请求未成功,再用 WPA2 试一次(剩余预算)");
                ok = joinHotspotOnce(ssid, pass, false, HS_SPEC_BUDGET_MS * 2 / 5);
            }
            if (!ok) {
                releaseNetworkRequest();
                restoreWifiAfterJoin();
            }
            return ok;
        }
        Bus.log("传输: 隐藏接口接入热点失败,恢复原 Wi-Fi");
        restoreWifiAfterJoin();
        return false;
    }

    //pri added by zhuyang,Silent join via the hidden addNetwork + enableNetwork (deterministic netId for cleanup),20261019,start
    private int joinHotspotHidden(WifiManager wm, String ssid, String pass, int gen) {
        try {
            Class<?> cfgCls = Class.forName("android.net.wifi.WifiConfiguration");
            final Object cfg = cfgCls.getConstructor().newInstance();
            cfgCls.getField("SSID").set(cfg, "\"" + ssid + "\"");
            if (pass != null) cfgCls.getField("preSharedKey").set(cfg, "\"" + pass + "\"");
            BitSet km = (BitSet) cfgCls.getField("allowedKeyManagement").get(cfg);
            km.set(Class.forName("android.net.wifi.WifiConfiguration$KeyMgmt")
                    .getField("WPA2_PSK").getInt(null));
            try {
                int psk = cfgCls.getField("SECURITY_TYPE_PSK").getInt(null);
                cfgCls.getMethod("setSecurityParams", int.class).invoke(cfg, psk);
            } catch (Throwable ignored) {}
            capturePreviousWifi(wm);
            mHiddenJoinTried = true;
            mJoinedSsid = ssid;
            Method addM = WifiManager.class.getMethod("addNetwork", cfgCls);
            Object added = addM.invoke(wm, cfg);
            int netId = (added instanceof Integer) ? (Integer) added : -1;
            if (netId < 0) {
                Bus.log("传输: 隐藏接口 addNetwork 返回 " + netId + ",回退 specifier");
                return HS_HIDDEN_UNAVAILABLE;
            }
            mJoinedNetId = netId;
            Method enableM = WifiManager.class.getMethod("enableNetwork", int.class, boolean.class);
            final long deadline = SystemClock.elapsedRealtime() + HS_HIDDEN_BUDGET_MS;
            for (int attempt = 1; attempt <= HS_HIDDEN_ATTEMPTS && mRunning; attempt++) {
                if (gen != mWifiGen) return HS_HIDDEN_FAILED;
                if (isAlive(mSendSocket) || (mReceiverRole && isAlive(mStashedSocket))) {
                    return HS_HIDDEN_OK;
                }
                Object enabled;
                try {
                    enabled = enableM.invoke(wm, netId, true);
                } catch (Throwable t) {
                    Bus.log("传输: 隐藏接口 enableNetwork 失败(" + t + "),回退 specifier");
                    cleanupHiddenJoin(wm, ssid);
                    return HS_HIDDEN_UNAVAILABLE;
                }
                Bus.log("传输: 隐藏接口静默接入尝试 " + attempt + "/" + HS_HIDDEN_ATTEMPTS
                        + ",SSID=" + ssid + ",netId=" + netId + ",enable=" + enabled);
                long assocLeft = Math.max(1000L,
                        Math.min(WIFI_ASSOC_POLL_MS, deadline - SystemClock.elapsedRealtime()));
                if (waitForAssociation(wm, ssid, assocLeft, gen)) return HS_HIDDEN_OK;
                if (attempt < HS_HIDDEN_ATTEMPTS) {
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                }
            }
            Bus.log("传输: 隐藏接口接入热点超时/失败");
            cleanupHiddenJoin(wm, ssid);
            return HS_HIDDEN_FAILED;
        } catch (Throwable t) {
            Bus.log("传输: 隐藏 Wi-Fi 接口不可用(" + t + "),回退 specifier");
            cleanupHiddenJoin(wm, ssid);
            return HS_HIDDEN_UNAVAILABLE;
        }
    }
    //pri added by zhuyang,Silent join via the hidden addNetwork + enableNetwork (deterministic netId for cleanup),20261019,end

    private void capturePreviousWifi(WifiManager wm) {
        if (mPrevWifiNetId >= 0 || mPrevWifiSsid != null) return;
        try {
            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
            if (wi != null) {
                mPrevWifiNetId = wi.getNetworkId();
                String cur = unquote(wi.getSSID());
                if (cur != null && !cur.isEmpty() && !"<unknown ssid>".equals(cur)) {
                    mPrevWifiSsid = cur;
                }
            }
        } catch (Throwable t) {
            Bus.log("Wi-Fi: 读取加入前网络失败 " + t);
        }
        Bus.log("Wi-Fi: 加入前网络 SSID=" + (mPrevWifiSsid == null ? "<未连接>" : mPrevWifiSsid)
                + ",netId=" + mPrevWifiNetId);
    }

    private void cleanupHiddenJoin(WifiManager wm, String ssid) {
        int id = mJoinedNetId;
        if (id < 0) id = findConfiguredNetId(wm, ssid);
        if (id >= 0) {
            boolean ok = removeSavedNetwork(wm, id);
            Bus.log("Wi-Fi: 清理接入失败的热点网络 netId=" + id + " -> " + ok);
        }
        mJoinedNetId = -1;
    }

    private boolean waitForAssociation(WifiManager wm, String ssid, long timeoutMs, int gen) {
        final long deadline = SystemClock.elapsedRealtime() + Math.max(200L, timeoutMs);
        while (mRunning && gen == mWifiGen && SystemClock.elapsedRealtime() < deadline) {
            try {
                android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                String cur = wi == null ? null : unquote(wi.getSSID());
                if (ssid.equals(cur)) {
                    int id = wi.getNetworkId();
                    if (id >= 0) mJoinedNetId = id;
                    Bus.log("Wi-Fi: 热点已接入 SSID=" + ssid + ",netId=" + mJoinedNetId);
                    return true;
                }
            } catch (Throwable ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private boolean ensureWifiEnabled(WifiManager wm, int gen) {
        try {
            if (wm.isWifiEnabled()) return true;
        } catch (Throwable ignored) {}
        Bus.log("Wi-Fi: 当前关闭,先打开 Wi-Fi(热点接入前置,最多 "
                + (WIFI_ENABLE_WAIT_MS / 1000) + "s)…");
        try {
            wm.setWifiEnabled(true);
        } catch (Throwable t) {
            Bus.log("Wi-Fi: 打开 Wi-Fi 失败 " + t);
            return false;
        }
        final long deadline = SystemClock.elapsedRealtime() + WIFI_ENABLE_WAIT_MS;
        while (mRunning && gen == mWifiGen && SystemClock.elapsedRealtime() < deadline) {
            try {
                if (wm.isWifiEnabled()) {
                    Bus.log("Wi-Fi: 已打开,继续接入热点");
                    return true;
                }
            } catch (Throwable ignored) {}
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        Bus.log("Wi-Fi: 打开超时/失败,跳过热点接入");
        return false;
    }

    private String waitForWifiGateway(WifiManager wm, long timeoutMs) {
        final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (mRunning && mJoinedSsid != null && SystemClock.elapsedRealtime() < deadline) {
            String gw = wifiGateway(wm);
            if (gw != null) return gw;
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    private String wifiGateway(WifiManager wm) {
        try {
            android.net.DhcpInfo di = wm.getDhcpInfo();
            if (di != null && di.gateway != 0) return intToIp(di.gateway);
        } catch (Throwable ignored) {}
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) mCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (android.net.Network n : cm.getAllNetworks()) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc == null || !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue;
                    android.net.LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    for (android.net.RouteInfo r : lp.getRoutes()) {
                        if (r.isDefaultRoute() && r.getGateway() != null) {
                            return r.getGateway().getHostAddress();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean connectViaGateway(String gw) {
        if (gw == null) {
            Bus.log("传输: 热点网关未知(DHCP 未就绪)");
            return false;
        }
        for (int i = 0; i < 3 && mRunning; i++) {
            if (isAlive(mSendSocket) || (mReceiverRole && isAlive(mStashedSocket))) {
                Bus.log("传输: 已有活动连接,停止热点网关连接");
                return true;
            }
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(gw, PORT), 2000);
                Link link = clientHello(s);
                if (link == null) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    continue;
                }
                if (!handleClientRole(link)) {
                    Bus.log("传输: 热点连接交由接收/裁决路径接管(网关=" + gw + ")");
                    return true;
                }
                Bus.log("传输: 热点直连成功(网关=" + gw + ",免搜索)");
                mListener.onP2pReady(false, gw);
                return true;
            } catch (FatalXferException fe) {
                fail(fe.error);
                return false;
            } catch (Exception e) {
                Bus.log("传输: 热点内连接尝试 " + (i + 1) + " 失败: " + e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }

    private void restoreWifiAfterJoin() {
        releaseNetworkRequest();
        final boolean had = mHiddenJoinTried || mJoinedNetId >= 0
                || mPrevWifiNetId >= 0 || mPrevWifiSsid != null;
        mWifiGen++;
        if (!had) return;
        final WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int hotId = mJoinedNetId;
            if (hotId < 0 && mJoinedSsid != null) hotId = findConfiguredNetId(wm, mJoinedSsid);
            if (hotId >= 0) {
                boolean ok = removeSavedNetwork(wm, hotId);
                Bus.log("Wi-Fi: " + (ok ? "已忘记热点网络" : "忘记热点网络失败")
                        + " netId=" + hotId + ",SSID=" + mJoinedSsid);
            } else if (mJoinedSsid != null) {
                Bus.log("Wi-Fi: 未找到已保存的热点网络(无需清理) SSID=" + mJoinedSsid);
            }
            int prevId = mPrevWifiNetId;
            if (prevId < 0 && mPrevWifiSsid != null) prevId = findConfiguredNetId(wm, mPrevWifiSsid);
            if (prevId >= 0) {
                boolean ok = enableSavedNetwork(wm, prevId);
                Bus.log("Wi-Fi: 恢复加入前网络 SSID=" + mPrevWifiSsid + ",netId=" + prevId
                        + " -> " + ok);
            } else if (mPrevWifiSsid != null) {
                Bus.log("Wi-Fi: 无法恢复原网络(未找到 netId) SSID=" + mPrevWifiSsid);
                try { wm.reconnect(); } catch (Throwable ignored) {}
            } else {
                Bus.log("Wi-Fi: 加入前未连接任何网络,保持 Wi-Fi 开启");
            }
        }
        mJoinedNetId = -1;
        mJoinedSsid = null;
        mHiddenJoinTried = false;
        mPrevWifiNetId = -1;
        mPrevWifiSsid = null;
    }
    //pri added by zhuyang,Hotspot join: hidden silent connect first, specifier only when the hidden API is unavailable,20261019,end

    //pri added by zhuyang,Specifier fallback: one request long enough for the user to tap the system dialog,20261019,start
    private boolean joinHotspotOnce(String ssid, String pass, boolean wpa3, long attemptMs) {
        try {
            android.net.wifi.WifiNetworkSpecifier.Builder sb =
                    new android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(ssid);
            if (pass != null) {
                if (wpa3) sb.setWpa3Passphrase(pass);
                else sb.setWpa2Passphrase(pass);
            }
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(sb.build())
                    .build();
            final android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) mCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Bus.log("传输: 回退 WifiNetworkSpecifier 接入 SSID=" + ssid + "(" + (wpa3 ? "WPA3" : "WPA2")
                    + "," + (attemptMs / 1000) + "s)…");
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final android.net.Network[] holder = new android.net.Network[1];
            final boolean[] unavailable = {false};
            mWifiNetCb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) {
                    holder[0] = network;
                    latch.countDown();
                }
                @Override public void onUnavailable() {
                    unavailable[0] = true;
                    latch.countDown();
                }
            };
            cm.requestNetwork(req, mWifiNetCb, (int) attemptMs);
            if (!latch.await(attemptMs + 300, java.util.concurrent.TimeUnit.MILLISECONDS)
                    || holder[0] == null) {
                if (unavailable[0]) {
                    Bus.log("传输: 系统直接拒绝热点请求(未弹窗)");
                } else {
                    Bus.log("传输: 热点接入未成功(" + (attemptMs / 1000)
                            + "s);若系统弹出了「连接到设备」,需用户点击「连接」(悬浮窗可能遮挡)");
                }
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
                    Bus.log("传输: 热点直连成功(免搜索)");
                    mListener.onP2pReady(false, gw);
                    return true;
                } catch (FatalXferException fe) {
                    fail(fe.error);
                    releaseNetworkRequest();
                    return false;
                } catch (Exception e) {
                    Bus.log("传输: 热点内连接尝试 " + (i + 1) + " 失败: " + e);
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
            releaseNetworkRequest();
            return false;
        } catch (Throwable t) {
            Bus.log("传输: 接入对方热点异常 " + t);
            releaseNetworkRequest();
            return false;
        }
    }
    //pri added by zhuyang,Specifier fallback: one request long enough for the user to tap the system dialog,20261019,end

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

    private static int findConfiguredNetId(WifiManager wm, String ssid) {
        try {
            Method m = WifiManager.class.getMethod("getConfiguredNetworks");
            List<?> list = (List<?>) m.invoke(wm);
            if (list != null) {
                for (Object c : list) {
                    if (c == null) continue;
                    Object raw = c.getClass().getField("SSID").get(c);
                    if (!(raw instanceof String)) continue;
                    if (!ssid.equals(unquote((String) raw))) continue;
                    int id = c.getClass().getField("networkId").getInt(c);
                    if (id >= 0) return id;
                }
            }
        } catch (Throwable t) {
            Bus.log("Wi-Fi: 读取已保存网络失败 " + t);
        }
        return -1;
    }

    private static boolean removeSavedNetwork(WifiManager wm, int netId) {
        try {
            Method m = WifiManager.class.getMethod("removeNetwork", int.class);
            return Boolean.TRUE.equals(m.invoke(wm, netId));
        } catch (Throwable t) {
            Bus.log("Wi-Fi: 忘记网络失败 netId=" + netId + " " + t);
            return false;
        }
    }

    private static boolean enableSavedNetwork(WifiManager wm, int netId) {
        try {
            Method m = WifiManager.class.getMethod("enableNetwork", int.class, boolean.class);
            return Boolean.TRUE.equals(m.invoke(wm, netId, true));
        } catch (Throwable t) {
            Bus.log("Wi-Fi: 恢复网络失败 netId=" + netId + " " + t);
            return false;
        }
    }

    private static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String intToIp(int ip) {
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff)
                + "." + ((ip >> 24) & 0xff);
    }

    private void startDiscovery() {
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019
        if (!P2P_ENABLED) return;
        if (mGotConnection) return; // 连接已就绪,不再搜索
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
            fail(new XferError(XferError.Stage.CONNECT, XferError.CONNECT_REFUSED, "搜索异常: " + t));
        }
    }

    //pri modify by zhuyang,Batch 5: P2P discovery owns its retry count; 1/2/4s backoff, max 3,20261017,start
    private void retryDiscovery() {
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019
        if (!P2P_ENABLED) return;
        if (!mDiscovering) return; //pri added by zhuyang,Batch 5: a failed round must not fail again on late peer events,20261017
        if (!mRunning || !mayDriveP2p()) {
            mDiscovering = false;
            mConnecting = false;
            return;
        }
        if (mP2pAttempt >= P2P_DISCOVER_MAX) {
            mDiscovering = false;
            mConnecting = false;
            fail(new XferError(XferError.Stage.CONNECT, XferError.CONNECT_TIMEOUT,
                    "P2P 发现失败(" + mP2pAttempt + " 次,预算 " + P2P_DISCOVER_MAX + " 次)"));
            return;
        }
        long delay = P2P_BACKOFF_MS[mP2pAttempt];
        mP2pAttempt++;
        Bus.log("P2P: " + (delay / 1000) + " 秒后重试搜索(第 " + mP2pAttempt + " 次,上限 "
                + P2P_DISCOVER_MAX + " 次)");
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                if (mRunning && mDiscovering && mGoAddress == null) startDiscovery();
            }
        }, delay);
    }
    //pri modify by zhuyang,Batch 5: P2P discovery owns its retry count; 1/2/4s backoff, max 3,20261017,end

    private void register() {
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019
        if (!P2P_ENABLED) return;
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
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019
        if (!P2P_ENABLED) return;
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
            fail(new XferError(XferError.Stage.CONNECT, XferError.CONNECT_REFUSED, "直连异常: " + t));
        }
    }

    private void onConn(WifiP2pInfo info) {
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019
        if (!P2P_ENABLED) return;
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
                maybeCloseHotspotForLan(s);
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
                failInbound(new XferError(XferError.Stage.CONNECT, XferError.AUTH_FAILED, "未收到 HELLO"));
                return;
            }
            Proto.Hello hello = Proto.parseHello(f.data);
            if (hello == null || hello.version != Proto.HELLO_VERSION
                    || !expected.equalsIgnoreCase(hello.sid)) {
                Bus.log("传输: [认证] HELLO 校验失败,关闭连接 " + s.getInetAddress());
                closeQuietly(s);
                failInbound(new XferError(XferError.Stage.CONNECT, XferError.AUTH_FAILED, "HELLO 的 sid/版本不匹配"));
                return;
            }
            //pri modify by zhuyang,Batch 4: answer with our own completed bitmap; keep the peer's on the Link,20261017,start
            byte[] ack = Proto.buildHelloAck(Bus.sid(), localRole(), haveHex());
            Proto.writeFrame(out, Proto.TYPE_HELLO_ACK, ack.length, ack);
            byte[] role = Proto.buildRole(localRole(), Bus.sid());
            Proto.writeFrame(out, Proto.TYPE_ROLE, role.length, role);
            out.flush();
            Bus.log("传输: [认证] HELLO 校验通过 " + s.getInetAddress()
                    + ",对方角色=" + hello.role + ",本机角色=" + localRole());
            maybeCloseHotspotForLan(s);
            Link link = new Link(s, in, out, rxAt, txAt);
            link.peerHave = hello.have;
            if (hello.have != null) Bus.log("传输: [认证] 对端已有图片位图 have=" + hello.have);
            //pri modify by zhuyang,Batch 4: answer with our own completed bitmap; keep the peer's on the Link,20261017,end
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
            failInbound(new XferError(XferError.Stage.CONNECT, XferError.CONNECT_TIMEOUT, "HELLO 握手超时"));
        } catch (Exception e) {
            Bus.log("传输: [认证] HELLO 处理失败,关闭连接: " + e);
            closeQuietly(s);
            failInbound(new XferError(XferError.Stage.CONNECT, XferError.PROTOCOL_ERROR, "HELLO 处理失败: " + e));
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
                failInbound(new XferError(XferError.Stage.CONNECT, XferError.AUTH_FAILED, "ROLE 帧 sid 不一致"));
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
                    notifyOfferExpiredOnce(link, new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT,
                            "已连接 " + (OFFER_WAIT_MS / 1000) + "s 仍未收到报价(对方未开始发送)"));
                    //pri added by zhuyang,Offer timeout on a joined AP must restore the user's Wi-Fi,20261019
                    if (mJoinedSsid != null && mPeerSocket == null) {
                        Bus.log("热点: 等待报价超时,恢复原 Wi-Fi");
                        restoreWifiAfterJoin();
                    }
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
                boolean connected = false;
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
                        connected = true;
                        if (mPeerSid == null) {
                            handleIncoming(link);
                            return;
                        }
                        handleClientRole(link);
                        return;
                    } catch (FatalXferException fe) {
                        fail(fe.error);
                        return;
                    } catch (Exception e) {
                        Bus.log("传输: 连接组主失败(第 " + (i + 1) + " 次): " + e);
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    }
                }
                //pri added by zhuyang,Batch 5: an exhausted connect stage reports CONNECT_TIMEOUT instead of silence,20261017
                if (!connected && mRunning && mReceiverRole && !hasLiveLink()) {
                    fail(new XferError(XferError.Stage.CONNECT, XferError.CONNECT_TIMEOUT, "连接组主失败"));
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
        final TransferProgress progress = new TransferProgress();
        int count = 0;
        int total = 0;
        int expect = 0;
        String readError = null;
        XferError error = null; //pri added by zhuyang,Batch 5: the first coded reason of this receive runs to the banner,20261017
        boolean doneReceived = false;
        boolean legacyComplete = false;
        boolean chunkedStarted = false;
        boolean storageRejected = false;   //pri added by zhuyang,Batch 4: REJECT already written, no failure banner,20261017
        boolean failedNotified = false;    //pri added by zhuyang,Batch 4: every-image-failed path already reported,20261017
        long rxBytes = 0;
        int expectedSeq = 0;
        int receivedFiles = 0;
        int okFiles = 0;
        int failedFiles = 0;
        String lastFailReason = null;
        boolean metaInfoSeen = false;
        Proto.FileBegin curFile = null;
        OutputStream curOut = null;
        MessageDigest curMd = null;
        File curPart = null;
        boolean curReuse = false;
        long curBytes = 0;
        final String sid = storeSid(); //pri added by zhuyang,Batch 4: .part paths and resume bitmap are bound to this session,20261017
        final String advertisedHave = mStore.haveHex(sid); //pri added by zhuyang,Batch 4: the bitmap this connection advertised in HELLO,20261017
        //pri added by zhuyang,Batch 5: a live peer that keeps PINGing must not extend the offer wait forever,20261017
        final long offerDeadline = SystemClock.elapsedRealtime() + OFFER_WAIT_MS;
        try {
            link.s.setSoTimeout(OFFER_WAIT_MS); //pri modify by zhuyang,No receive read may block forever,20261017
            mPeerSocket = link;     //pri added by zhuyang,Keep the link so the verdict can be written back on it,20261014
            mStashedSocket = link;
            mVerdictSent = false;   //pri added by zhuyang,Reset the per-connection verdict/done flags,20261014,start
            mDoneNotified = false;
            //pri added by zhuyang,Reset the per-connection verdict/done flags,20261014,end
            startHeartbeat(link, true);   //pri added by zhuyang,Heartbeat covers the offer/verdict wait and the receive loop,20261017
            //pri added by zhuyang,Batch 4: completed indices are advertised so the peer can resume at image level,20261017,start
            if (!"0000000000000000".equals(advertisedHave)) {
                Bus.log("传输: 本轮已在 HELLO 告知对端已有图片位图 have=" + advertisedHave);
            }
            //pri added by zhuyang,Batch 4: completed indices are advertised so the peer can resume at image level,20261017,end
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
                    //pri modify by zhuyang,Batch 5: absolute 20s offer deadline even while PING keeps arriving,20261017,start
                    if (expect == 0 && count == 0 && !chunkedStarted
                            && SystemClock.elapsedRealtime() >= offerDeadline) {
                        readError = "等待报价超时(" + (OFFER_WAIT_MS / 1000) + "s 未收到 META)";
                        error = new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT, readError);
                        break;
                    }
                    //pri modify by zhuyang,Batch 5: absolute 20s offer deadline even while PING keeps arriving,20261017,end
                    //pri modify by zhuyang,Offer wait stays at 20s; the data phase tightens to 10s (a chunk or PING must arrive),20261017,start
                    int readTimeout = expect > 0 ? RX_DATA_TIMEOUT_MS : OFFER_WAIT_MS;
                    try {
                        link.s.setSoTimeout(readTimeout);
                        f = Proto.readFrame(link.in, readTimeout, Proto.MAX_FRAME_LEN);
                    } catch (java.net.SocketTimeoutException te) {
                        Bus.log("传输: 接收超时(" + readTimeout + "ms 未读到完整帧)");
                        if (expect > 0) {
                            readError = "接收超时(" + (RX_DATA_TIMEOUT_MS / 1000) + "s 未收到数据)";
                            error = new XferError(XferError.Stage.TRANSFER, XferError.TRANSFER_TIMEOUT, readError);
                        } else {
                            readError = "对方未开始发送(报价超时)";
                            error = new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT, readError);
                        }
                        break;
                    } catch (Exception e) {
                        Bus.log("传输: 帧读取失败 " + e);
                        readError = "传输中断(读帧失败)";
                        error = link.linkDead.get() ? linkError(link, XferError.Stage.TRANSFER)
                                : new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                        break;
                    }
                    //pri modify by zhuyang,Offer wait stays at 20s; the data phase tightens to 10s (a chunk or PING must arrive),20261017,end
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
                    progress.setOffering(expect);
                    postProgress(progress, false);
                    final int n = expect;
                    Bus.log("传输: 对方本次共发 " + n + " 张");
                    mMain.post(new Runnable() {
                        @Override public void run() { mListener.onSendStarted(n); }
                    });
                    continue;
                }
                //pri added by zhuyang,Batch 4: META_INFO drives the storage precheck before the user's ACCEPT,20261017,start
                if (type == Proto.TYPE_META_INFO) {
                    Proto.MetaInfo mi = Proto.parseMetaInfo(frame.data);
                    if (mi == null) {
                        Bus.log("传输: META_INFO 解析失败,跳过存储预检");
                        continue;
                    }
                    metaInfoSeen = true;
                    long avail = availableBytes();
                    long required = mi.totalBytes > Long.MAX_VALUE - STORAGE_HEADROOM_BYTES
                            ? Long.MAX_VALUE : mi.totalBytes + STORAGE_HEADROOM_BYTES;
                    Bus.log("传输: 存储预检 — 本次 " + mbText(mi.totalBytes) + "MB,可用 "
                            + mbText(avail) + "MB(另需余量 " + (STORAGE_HEADROOM_BYTES / 1048576) + "MB)");
                    if (avail >= 0 && avail < required) {
                        storageRejected = true;
                        final long need = mi.totalBytes;
                        Bus.log("传输: 存储空间不足,ACCEPT 前拒绝(需要 " + mbText(need) + "MB,可用 "
                                + mbText(avail) + "MB)");
                        sendRejectNow(link, "存储空间不足");
                        //pri added by zhuyang,Batch 5: NO_SPACE now travels as a coded failure,20261017
                        fail(new XferError(XferError.Stage.OFFER, XferError.NO_SPACE,
                                "需要 " + mbText(need) + "MB,可用 " + mbText(avail) + "MB"));
                        mMain.post(new Runnable() {
                            @Override public void run() { mListener.onStorageInsufficient(need); }
                        });
                        break;
                    }
                    continue;
                }
                //pri added by zhuyang,Batch 4: META_INFO drives the storage precheck before the user's ACCEPT,20261017,end
                //pri added by zhuyang,Chunked delivery: FILE_BEGIN / DATA / DONE replace the single FULL frame,20261017,start
                if (type == Proto.TYPE_FILE_BEGIN) {
                    Proto.FileBegin fi = Proto.parseFileBegin(frame.data);
                    if (fi == null) {
                        readError = "文件元信息解析失败";
                        error = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                        sendCancel(link, readError);
                        break;
                    }
                    if (curFile != null) {
                        //pri modify by zhuyang,Batch 4: verify size/SHA-256 and only then insert into MediaStore,20261017
                        ChunkResult cr = closeChunk(curFile, curOut, curMd, curPart, curReuse, curBytes,
                                sid, Math.max(expect, curFile.total), all);
                        receivedFiles++;
                        if (cr.ok) {
                            okFiles++;
                        } else {
                            failedFiles++;
                            lastFailReason = cr.reason;
                            Bus.log("传输: " + cr.reason);
                            sendCancel(link, cr.reason);
                        }
                        //pri modify by zhuyang,Batch 4: verify size/SHA-256 and only then insert into MediaStore,20261017
                    }
                    curFile = fi;
                    curOut = null;
                    curMd = null;
                    curPart = mStore.partFile(sid, fi.index);
                    curReuse = false;
                    curBytes = 0;
                    expectedSeq = 0;
                    chunkedStarted = true;
                    //pri added by zhuyang,Batch 4: a matching .part from an earlier connection counts as already received,20261017,start
                    if (fi.sha256 != null && curPart.exists() && fi.size >= 0
                            && curPart.length() == fi.size) {
                        try {
                            if (fi.sha256.equalsIgnoreCase(TransferStore.sha256Of(curPart))) {
                                curReuse = true;
                                mStore.commit(sid, fi.index);
                                submitChunkFile(curPart, fi.index + 1, Math.max(expect, fi.total), all);
                                Bus.log("传输: 第 " + (fi.index + 1) + " 张 .part 已完整且哈希匹配,直接视为已完成");
                            }
                        } catch (Exception e) {
                            Bus.log("传输: 检查已有 .part 失败,重新接收: " + e);
                        }
                    }
                    if (!curReuse) {
                        try {
                            curOut = mStore.open(sid, fi.index);
                        } catch (Exception e) {
                            readError = "无法创建临时文件: " + e;
                            error = new XferError(XferError.Stage.TRANSFER,
                                    String.valueOf(e).toLowerCase(java.util.Locale.US).contains("space")
                                            ? XferError.NO_SPACE : XferError.PROTOCOL_ERROR, readError);
                            sendCancel(link, readError);
                            break;
                        }
                        try {
                            curMd = MessageDigest.getInstance("SHA-256");
                        } catch (Exception e) {
                            readError = "本机不支持 SHA-256";
                            error = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                            sendCancel(link, readError);
                            break;
                        }
                    }
                    //pri added by zhuyang,Batch 4: a matching .part from an earlier connection counts as already received,20261017,end
                    if (fi.sha256 == null) Bus.log("传输: 第 " + (fi.index + 1) + " 张未携带 h=,跳过哈希校验(旧对端)");
                    progress.beginFile(fi.index + 1, fi.total, fi.size);
                    postProgress(progress, false);
                    Bus.log("传输: 开始接收第 " + (fi.index + 1) + "/" + fi.total + " 张 " + fi.size + " 字节 " + fi.mime
                            + (curReuse ? "(复用 .part)" : ""));
                    continue;
                }
                if (type == Proto.TYPE_DATA) {
                    if (curFile == null || (!curReuse && curOut == null)) {
                        readError = "未收到 FILE_BEGIN 就收到数据块";
                        error = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                        sendCancel(link, readError);
                        break;
                    }
                    if (frame.length < 4) {
                        readError = "DATA 帧缺少序号";
                        error = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                        sendCancel(link, readError);
                        break;
                    }
                    int seq = Proto.readSeq(frame.data);
                    if (seq != expectedSeq) {
                        readError = "数据块序号不连续(期望 " + expectedSeq + ",收到 " + seq + ")";
                        error = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                        Bus.log("传输: " + readError);
                        sendCancel(link, readError);
                        break;
                    }
                    if (!curReuse) {
                        try {
                            curOut.write(frame.data, 4, frame.length - 4);
                            curMd.update(frame.data, 4, frame.length - 4);
                        } catch (Exception e) {
                            readError = "写入临时文件失败: " + e;
                            error = new XferError(XferError.Stage.TRANSFER,
                                    String.valueOf(e).toLowerCase(java.util.Locale.US).contains("space")
                                            ? XferError.NO_SPACE : XferError.PROTOCOL_ERROR, readError);
                            sendCancel(link, readError);
                            break;
                        }
                        curBytes += frame.length - 4L;
                    }
                    rxBytes += frame.length - 4L;
                    expectedSeq++;
                    progress.addBytes(frame.length - 4);
                    postProgress(progress, false);
                    continue;
                }
                if (type == Proto.TYPE_DONE) {
                    Proto.Done done = Proto.parseDone(frame.data);
                    if (done == null) {
                        readError = "完成帧解析失败";
                        error = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                        sendCancel(link, readError);
                        break;
                    }
                    if (curFile != null) {
                        ChunkResult cr = closeChunk(curFile, curOut, curMd, curPart, curReuse, curBytes,
                                sid, Math.max(expect, curFile.total), all);
                        receivedFiles++;
                        if (cr.ok) {
                            okFiles++;
                        } else {
                            failedFiles++;
                            lastFailReason = cr.reason;
                            Bus.log("传输: " + cr.reason);
                            sendCancel(link, cr.reason);
                        }
                        curFile = null;
                        curOut = null;
                        curMd = null;
                        curPart = null;
                        curReuse = false;
                        curBytes = 0;
                    }
                    //pri modify by zhuyang,Batch 4: count check tolerates indices the sender skipped via have,20261017
                    int announced = expect > 0 ? expect : receivedFiles;
                    int skipped = Proto.bitmapCount(advertisedHave, announced);
                    boolean countOk = done.count == receivedFiles
                            && (receivedFiles == announced || receivedFiles == announced - skipped);
                    if (!chunkedStarted || !countOk || done.totalBytes != rxBytes) {
                        readError = "完成校验失败(完成帧 " + done.count + " 张/" + done.totalBytes
                                + "B,实收 " + receivedFiles + " 张/" + rxBytes + "B,预告 " + expect
                                + " 张,跳过 " + skipped + " 张)";
                        error = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                        Bus.log("传输: " + readError);
                        sendCancel(link, "完成校验失败");
                        break;
                    }
                    //pri modify by zhuyang,Batch 4: count check tolerates indices the sender skipped via have,20261017
                    doneReceived = true;
                    if (failedFiles > 0 && okFiles == 0) {
                        //pri added by zhuyang,Batch 4: every image failed -> report a transfer failure, with the frame counts acknowledged,20261017,start
                        failedNotified = true;
                        sendDoneAck(link, failedFiles);
                        String why = lastFailReason == null
                                ? "第 " + Math.max(1, done.count) + " 张校验失败" : lastFailReason;
                        progress.fail(why);
                        postProgress(progress, true);
                        mDoneNotified = true;
                        Bus.log("传输: 所有图片均校验失败,已回 DONE_ACK(" + failedFiles + ") — " + why);
                        notifyLinkFailedOnce(link, new XferError(XferError.Stage.TRANSFER,
                                XferError.CHECKSUM_FAILED, why));
                        break;
                        //pri added by zhuyang,Batch 4: every image failed -> report a transfer failure, with the frame counts acknowledged,20261017,end
                    }
                    sendDoneAck(link, failedFiles);
                    progress.done();
                    postProgress(progress, true);
                    Bus.log("传输: 完成帧校验通过,共 " + done.count + " 张 " + done.totalBytes
                            + " 字节,成功 " + okFiles + " 张,失败 " + failedFiles + " 张,已回 DONE_ACK("
                            + failedFiles + ")");
                    if (!metaInfoSeen) Bus.log("传输: 未收到 META_INFO,跳过存储预检(旧对端)");
                    mDoneNotified = true;
                    enqueueTransferDone(all);
                    if (failedFiles > 0) {
                        //pri added by zhuyang,Batch 4: partial failure -> "成功 N 张，失败 M 张",20261017,start
                        final int ok = okFiles;
                        final int bad = failedFiles;
                        mMain.post(new Runnable() {
                            @Override public void run() { mListener.onTransferSummary(ok, bad); }
                        });
                        //pri added by zhuyang,Batch 4: partial failure -> "成功 N 张，失败 M 张",20261017,end
                    }
                    break;
                }
                if (type == Proto.TYPE_CANCEL) {
                    readError = "对方取消: " + new String(frame.data, java.nio.charset.StandardCharsets.UTF_8);
                    error = new XferError(XferError.Stage.TRANSFER, XferError.CANCELLED, readError);
                    Bus.log("传输: " + readError);
                    break;
                }
                //pri added by zhuyang,Chunked delivery: FILE_BEGIN / DATA / DONE replace the single FULL frame,20261017,end
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
                //pri modify by zhuyang,Legacy TYPE_FULL stays receivable for debugging/old peers,20261017,start
                count++;
                total += len;
                submitImage(data, count, expect, all);
                if (expect > 0 && count >= expect && !mDoneNotified) {
                    legacyComplete = true;
                    mDoneNotified = true;
                    Bus.log("传输: 旧版 FULL 计数已满,本轮完成");
                    enqueueTransferDone(all);
                }
                //pri modify by zhuyang,Legacy TYPE_FULL stays receivable for debugging/old peers,20261017,end
            }
            //pri modify by zhuyang,Only DONE (or the legacy FULL count) completes; EOF alone is never success,20261017,start
            if (mRunning && !failedNotified && !storageRejected) {
                Bus.log("传输: 接收结束,共 " + count + " 张(分块 " + receivedFiles + " 张/" + rxBytes
                        + "B,FULL " + total + "B),成功 " + okFiles + " 失败 " + failedFiles
                        + ",预告 " + expect + " 张,分块=" + chunkedStarted + ",完成帧=" + doneReceived);
                boolean complete = doneReceived || legacyComplete
                        || (!chunkedStarted && expect > 0 && count >= expect);
                if (complete) {
                    if (!mDoneNotified) {
                        mDoneNotified = true;
                        Bus.log("传输: 接收完成,通知查看器");
                        enqueueTransferDone(all);
                    }
                } else if (expect > 0 || count > 0 || mVerdictSent || chunkedStarted) {
                    XferError cerr = error;
                    if (cerr == null) {
                        if (link.linkDead.get()) {
                            cerr = linkError(link, XferError.Stage.TRANSFER);
                        } else if (readError != null) {
                            cerr = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                        } else if (chunkedStarted) {
                            cerr = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR,
                                    "传输中断: 未收到完成帧(实收 " + receivedFiles + " 张)");
                        } else if (expect > 0) {
                            cerr = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR,
                                    "传输中断: 只收到 " + count + "/" + expect + " 张");
                        } else {
                            cerr = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, "传输中断");
                        }
                    }
                    progress.fail(cerr.detail);
                    postProgress(progress, true);
                    notifyLinkFailedOnce(link, cerr);
                } else {
                    XferError cerr = error != null ? error
                            : new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT, "对方未开始发送(报价超时或连接断开)");
                    progress.fail(cerr.detail);
                    postProgress(progress, true);
                    notifyOfferExpiredOnce(link, cerr);
                }
            }
            //pri modify by zhuyang,Only DONE (or the legacy FULL count) completes; EOF alone is never success,20261017,end
        } catch (Exception e) {
            Bus.log("传输: 接收异常 " + e);
            //pri added by zhuyang,Every receive failure path must call back exactly once,20261017,start
            if (mRunning && !doneReceived && !failedNotified && !storageRejected) {
                if (expect > 0 || count > 0 || mVerdictSent || chunkedStarted) {
                    XferError cerr = error != null ? error
                            : (link.linkDead.get() ? linkError(link, XferError.Stage.TRANSFER)
                            : new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, "传输中断: " + e));
                    progress.fail(cerr.detail);
                    postProgress(progress, true);
                    notifyLinkFailedOnce(link, cerr);
                } else {
                    XferError cerr = error != null ? error
                            : new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT, "对方未开始发送(接收异常)");
                    progress.fail(cerr.detail);
                    postProgress(progress, true);
                    notifyOfferExpiredOnce(link, cerr);
                }
            }
            //pri added by zhuyang,Every receive failure path must call back exactly once,20261017,end
        } finally {
            //pri added by zhuyang,Batch 4: close the staging stream, then clean this sid only after queued saves finish,20261017,start
            try { if (curOut != null) curOut.close(); } catch (Exception ignored) {}
            final String cleanupSid = sid;
            mIo.execute(new Runnable() {
                @Override public void run() {
                    try { mStore.discardAll(cleanupSid); } catch (Throwable ignored) {}
                }
            });
            //pri added by zhuyang,Batch 4: close the staging stream, then clean this sid only after queued saves finish,20261017,end
            stopHeartbeat(link);
            //pri modify by zhuyang,Clear both role handles for the dead Link before closing it,20261017,start
            if (mPeerSocket == link) mPeerSocket = null;
            if (mStashedSocket == link) mStashedSocket = null;
            closeQuietly(link);
            //pri modify by zhuyang,Clear both role handles for the dead Link before closing it,20261017,end
            //pri added by zhuyang,Release the hotspot on failure too so Wi-Fi comes back,20261017,start
            //pri modify by zhuyang,Keep the AP for a 1s grace period so the sender can read DONE_ACK,20261019
            releaseHotspotLater();
            //pri added by zhuyang,Release the hotspot on failure too so Wi-Fi comes back,20261017,end
        }
    }

    //pri added by zhuyang,Batch 4: verification result of one FILE_BEGIN..DATA..(next frame) span,20261017
    private static final class ChunkResult {
        final boolean ok;
        final String reason;

        ChunkResult(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }
    }

    //pri added by zhuyang,Batch 4: close one image, check size + SHA-256, and queue it for MediaStore only on success,20261017,start
    private ChunkResult closeChunk(Proto.FileBegin fi, OutputStream out, MessageDigest md, File part,
                                   boolean reuse, long bytes, String sid, int total,
                                   java.util.ArrayList<Bitmap> all) {
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        if (fi == null) return new ChunkResult(false, "缺少图片元信息");
        int human = fi.index + 1;
        if (reuse) {
            return new ChunkResult(true, null); // already verified and queued at FILE_BEGIN time
        }
        if (fi.size >= 0 && fi.size != bytes) {
            mStore.discard(sid, fi.index);
            return new ChunkResult(false, "第 " + human + " 张大小不符(announced=" + fi.size + ", got=" + bytes + ")");
        }
        if (md == null) {
            mStore.discard(sid, fi.index);
            return new ChunkResult(false, "第 " + human + " 张校验失败(缺少摘要)");
        }
        String got = sha256Hex(md);
        if (fi.sha256 != null && !fi.sha256.equalsIgnoreCase(got)) {
            mStore.discard(sid, fi.index);
            return new ChunkResult(false, "第 " + human + " 张校验失败"
                    + "(期望 " + fi.sha256.substring(0, 8) + ",实际 " + got.substring(0, 8) + ")");
        }
        if (fi.sha256 == null) {
            Bus.log("传输: 第 " + human + " 张无 h=,跳过哈希比对");
        } else {
            Bus.log("传输: 第 " + human + " 张 SHA-256 校验通过 " + got.substring(0, 12));
        }
        File committed = mStore.commit(sid, fi.index);
        submitChunkFile(committed, human, Math.max(fi.total, total), all);
        return new ChunkResult(true, null);
    }
    //pri added by zhuyang,Batch 4: close one image, check size + SHA-256, and queue it for MediaStore only on success,20261017,end

    //pri added by zhuyang,Batch 4: queue one verified .part; it is inserted into MediaStore and deleted on the IO thread,20261017,start
    private void submitChunkFile(final File part, final int idx, final int exp,
                                 final java.util.ArrayList<Bitmap> all) {
        final int bytes = (int) Math.min(Integer.MAX_VALUE, part.length());
        mIo.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bmp = decode(part);
                final Uri savedUri = saveFileToGallery(part, "image/jpeg");
                if (savedUri != null) {
                    mSavedUris.add(savedUri);
                    if (mFirstSavedUri == null) mFirstSavedUri = savedUri;
                }
                final boolean saved = savedUri != null;
                synchronized (all) { all.add(bmp); }
                try { part.delete(); } catch (Throwable ignored) {} //pri added by zhuyang,Batch 4: success or failure, the temp copy goes,20261017
                mMain.post(new Runnable() {
                    @Override public void run() { mListener.onImageReceived(idx, exp, bmp, bytes, saved); }
                });
            }
        });
    }
    //pri added by zhuyang,Batch 4: queue one verified .part; it is inserted into MediaStore and deleted on the IO thread,20261017,end

    //pri added by zhuyang,Decode + save one image off the socket thread, then report it,20261017
    private void submitImage(final byte[] data, final int idx, final int exp,
                             final java.util.ArrayList<Bitmap> all) {
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
                //pri added by zhuyang,Batch 5: the send thread owns its stage locally,20261017
                XferError.Stage stage = XferError.Stage.CONNECT;
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
                        } catch (FatalXferException fe) {
                            fail(fe.error);
                            return;
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
                            fail(new XferError(XferError.Stage.CONNECT, XferError.CONNECT_TIMEOUT,
                                    mImGo ? "对方尚未连接,请让对方保持在前台再试"
                                            : "连接尚未就绪(热点接入中,请稍候再上滑)"));
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
                        if (!mYielded) {
                            notifyLinkFailedOnce(send, send.linkDead.get()
                                    ? linkError(send, XferError.Stage.CONNECT)
                                    : new XferError(XferError.Stage.CONNECT, XferError.PROTOCOL_ERROR,
                                            "角色窗口内读帧失败"));
                        }
                        return;
                    }
                    //pri added by zhuyang,Give a simultaneous peer the 300ms window to announce ROLE(S) before our offer,20261017,end
                    stage = XferError.Stage.OFFER; //pri added by zhuyang,Batch 5: offer/verdict stage begins,20261017
                    //pri added by zhuyang,Heartbeat starts before the offer (keep the receiver alive) but rx-dead is armed only for the verdict wait,20261017,start
                    startHeartbeat(send, false);
                    //pri added by zhuyang,Heartbeat starts before the offer (keep the receiver alive) but rx-dead is armed only for the verdict wait,20261017,end
                    final int round = mRound; //pri added by zhuyang,Round token: a stale verdict is discarded by readVerdict,20261014
                    //pri added by zhuyang,Batch 4: plan the resume subset before the offer so META_INFO matches what is sent,20261017,start
                    final String peerHave = send.peerHave;
                    boolean[] skip = new boolean[uris.size()];
                    long planned = 0;
                    int skipCount = 0;
                    for (int i = 0; i < uris.size(); i++) {
                        long size = imageSize(uris.get(i));
                        if (Proto.bitmapHas(peerHave, i)) {
                            skip[i] = true;
                            skipCount++;
                            continue;
                        }
                        if (size > 0) planned += size;
                    }
                    if (peerHave != null) {
                        Bus.log("传输: 续传 — 对端位图 have=" + peerHave + ",跳过 " + skipCount
                                + "/" + uris.size() + " 张");
                    }
                    //pri added by zhuyang,Batch 4: plan the resume subset before the offer so META_INFO matches what is sent,20261017,end
                    //pri added by zhuyang,Offer stage: META(total) + one preview, then wait for the verdict,20261014,start
                    mWriteLock.lock();
                    try {
                        //pri added by zhuyang,Guarantee ROLE(S) precedes META on this link even if the async write lost the race,20261017
                        sendRoleOnLink(send, "S");
                        writeBusinessFrame(send, Proto.TYPE_META, uris.size(), null);
                        //pri added by zhuyang,Batch 4: META_INFO immediately after META drives the receiver's storage precheck,20261017
                        byte[] mi = Proto.buildMetaInfo(uris.size(), planned);
                        writeBusinessFrame(send, Proto.TYPE_META_INFO, mi.length, mi);
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
                    Bus.log("传输: 已报价(" + uris.size() + " 张,其中跳过 " + skipCount + " 张,计划 "
                            + planned + " 字节 + 预览),等对方「接收/拒绝」…("
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
                    //pri modify by zhuyang,Delivery stage: stream each image in 128KB DATA frames, then DONE/DONE_ACK,20261017,start
                    stage = XferError.Stage.TRANSFER; //pri added by zhuyang,Batch 5: accepted; images are now streaming,20261017
                    //pri added by zhuyang,From here on a blocked write is caught by the stall watchdog,20261017
                    send.hbWritePhase = true;
                    send.hbExpectRx = false;
                    send.lastWriteProgressAt = SystemClock.elapsedRealtime();
                    Bus.log("传输: 进入发送阶段,写停滞 " + (WRITE_STALL_MS / 1000) + "s 看门狗已启用");
                    final TransferProgress progress = new TransferProgress();
                    progress.setPlannedTotals(uris.size(), planned);
                    postProgress(progress, true);
                    long sentBytes = 0;
                    int sentCount = 0;
                    byte[] buf = new byte[CHUNK_SIZE];
                    for (int i = 0; i < uris.size(); i++) {
                        if (mYielded || send.linkDead.get() || !mRunning) break;
                        if (skip[i]) {
                            Bus.log("传输: 续传跳过第 " + (i + 1) + " 张(对端已校验通过)");
                            continue;
                        }
                        Uri uri = uris.get(i);
                        String mime = mCtx.getContentResolver().getType(uri);
                        if (mime == null) mime = "image/jpeg";
                        //pri added by zhuyang,Batch 4: hash and stage while reading the source once, then stream the staged copy,20261017,start
                        MessageDigest md;
                        try {
                            md = MessageDigest.getInstance("SHA-256");
                        } catch (Exception e) {
                            throw new java.io.IOException("本机不支持 SHA-256");
                        }
                        File staged = stageImage(uri, md);
                        long fileSize = staged.length();
                        String hash = sha256Hex(md);
                        try {
                            byte[] meta = Proto.buildFileBegin(i, uris.size(), fileSize, mime, hash);
                            mWriteLock.lock();
                            try {
                                writeBusinessFrame(send, Proto.TYPE_FILE_BEGIN, meta.length, meta);
                                send.out.flush();
                            } finally {
                                mWriteLock.unlock();
                            }
                            progress.beginFile(i + 1, uris.size(), fileSize);
                            postProgress(progress, false);
                            Bus.log("传输: 正在发送第 " + (i + 1) + "/" + uris.size() + " 张 " + fileSize
                                    + " 字节 " + mime + " sha256=" + hash);
                            InputStream in = new FileInputStream(staged);
                            int seq = 0;
                            long fileBytes = 0;
                            try {
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    if (mYielded || send.linkDead.get() || !mRunning) break;
                                    mWriteLock.lock();
                                    try {
                                        writeDataFrame(send, seq, buf, 0, n);
                                    } finally {
                                        mWriteLock.unlock();
                                    }
                                    seq++;
                                    fileBytes += n;
                                    sentBytes += n;
                                    progress.addBytes(n);
                                    postProgress(progress, false);
                                }
                            } finally {
                                try { in.close(); } catch (Exception ignored) {}
                            }
                            if (mYielded || send.linkDead.get() || !mRunning) break;
                            mWriteLock.lock();
                            try { send.out.flush(); } finally { mWriteLock.unlock(); }
                            sentCount++;
                            Bus.log("传输: 第 " + (i + 1) + " 张发送完毕 " + fileBytes + " 字节,已发 " + sentBytes + " 字节");
                        } finally {
                            try { staged.delete(); } catch (Throwable ignored) {}
                        }
                        //pri added by zhuyang,Batch 4: hash and stage while reading the source once, then stream the staged copy,20261017,end
                    }
                    if (mYielded) {
                        stopHeartbeat(send);
                        Bus.log("传输: 已让出,发送中止");
                        return;
                    }
                    if (!mRunning) {
                        stopHeartbeat(send);
                        return;
                    }
                    if (send.linkDead.get()) {
                        throw new FatalXferException(linkError(send, XferError.Stage.TRANSFER));
                    }
                    stage = XferError.Stage.DONE; //pri added by zhuyang,Batch 5: DONE/DONE_ACK completion stage,20261017
                    byte[] done = Proto.buildDone(sentCount, sentBytes);
                    mWriteLock.lock();
                    try {
                        writeBusinessFrame(send, Proto.TYPE_DONE, done.length, done);
                        send.out.flush();
                    } finally {
                        mWriteLock.unlock();
                    }
                    Bus.log("传输: 已发送完成帧(" + sentCount + " 张 " + sentBytes + " 字节),等对方确认("
                            + (DONE_ACK_TIMEOUT_MS / 1000) + "s 上限)…");
                    //pri added by zhuyang,Waiting for DONE_ACK is a read wait: keep the dead-peer check, drop the write stall,20261017
                    send.hbWritePhase = false;
                    send.hbExpectRx = true;
                    send.lastRx.set(SystemClock.elapsedRealtime());
                    int ack = readDoneAck(send);
                    if (ack < 0) {
                        if (ack == -2) {
                            throw new FatalXferException(new XferError(XferError.Stage.DONE,
                                    XferError.DONE_ACK_TIMEOUT,
                                    "等待对方确认完成超时(" + (DONE_ACK_TIMEOUT_MS / 1000) + "s)"));
                        } else if (ack == -3) {
                            throw new FatalXferException(new XferError(XferError.Stage.DONE,
                                    XferError.CANCELLED, "对方取消了本次传输"));
                        } else if (ack == -4) {
                            throw new FatalXferException(linkError(send, XferError.Stage.DONE));
                        } else {
                            throw new FatalXferException(new XferError(XferError.Stage.DONE,
                                    XferError.PROTOCOL_ERROR, "对方未确认完成"));
                        }
                    }
                    //pri added by zhuyang,Batch 4: DONE_ACK code > 0 is the receiver's failure count (partial success),20261017,start
                    final int failedCount = ack;
                    final int okCount = Math.max(0, sentCount - failedCount);
                    if (failedCount > 0) {
                        Bus.log("传输: 对方确认完成,其中 " + failedCount + " 张校验失败(成功 " + okCount + " 张)");
                        mMain.post(new Runnable() {
                            @Override public void run() { mListener.onTransferSummary(okCount, failedCount); }
                        });
                    }
                    //pri added by zhuyang,Batch 4: DONE_ACK code > 0 is the receiver's failure count (partial success),20261017,end
                    progress.done();
                    postProgress(progress, true);
                    stopHeartbeat(send);
                    closeQuietly(send);
                    if (mSendSocket == send) mSendSocket = null;
                    if (mStashedSocket == send) mStashedSocket = null;
                    Bus.log("传输: 对方已确认完成(DONE_ACK),共 " + sentCount + " 张 " + sentBytes + " 字节");
                    releaseHotspotLater();
                    final int n = (int) Math.min(Integer.MAX_VALUE, sentBytes);
                    mMain.post(new Runnable() {
                        @Override public void run() { mListener.onImageSent(n); }
                    });
                    //pri modify by zhuyang,Delivery stage: stream each image in 128KB DATA frames, then DONE/DONE_ACK,20261017,end
                } catch (FatalXferException fe) {
                    if (send != null) stopHeartbeat(send);
                    if (mYielded) {
                        Bus.log("传输: 本机已让出,放弃发送: " + fe.getMessage());
                    } else {
                        if (send != null) sendCancel(send, "发送失败");
                        fail(fe.error);
                        //pri added by zhuyang,Batch 5: a failed send link is closed so the next round starts clean,20261017
                        if (send != null) {
                            closeQuietly(send);
                            if (mSendSocket == send) mSendSocket = null;
                            if (mStashedSocket == send) mStashedSocket = null;
                        }
                        releaseHotspotLater();
                    }
                } catch (Exception e) {
                    if (send != null) stopHeartbeat(send);
                    if (mYielded) {
                        Bus.log("传输: 本机已让出,放弃发送: " + e);
                    } else {
                        Bus.log("传输: 发送异常(" + stage + "): " + e);
                        XferError err;
                        if (send != null && send.failError != null) {
                            err = send.failError;
                        } else if (stage == XferError.Stage.CONNECT) {
                            err = new XferError(XferError.Stage.CONNECT, XferError.CONNECT_TIMEOUT,
                                    "发送通道异常");
                        } else if (stage == XferError.Stage.OFFER) {
                            err = new XferError(XferError.Stage.OFFER, XferError.PROTOCOL_ERROR,
                                    "报价发送失败");
                        } else if (stage == XferError.Stage.DONE) {
                            err = new XferError(XferError.Stage.DONE, XferError.PROTOCOL_ERROR,
                                    "完成确认失败");
                        } else {
                            err = new XferError(XferError.Stage.TRANSFER, XferError.TRANSFER_TIMEOUT,
                                    "发送中断");
                        }
                        if (send != null) sendCancel(send, "发送失败");
                        fail(err);
                        if (send != null) {
                            closeQuietly(send);
                            if (mSendSocket == send) mSendSocket = null;
                            if (mStashedSocket == send) mStashedSocket = null;
                        }
                        releaseHotspotLater();
                    }
                }
            }
        }).start();
    }
    //pri modify by zhuyang,Role convergence: send on the Link, wait out the 300ms role window, and abort when yielding,20261017,end

    //pri added by zhuyang,Wait for DONE_ACK: 0=OK, -1=protocol error, -2=timeout, -3=peer cancel, -4=link dead,20261017,start
    private int readDoneAck(Link link) {
        long deadline = SystemClock.elapsedRealtime() + DONE_ACK_TIMEOUT_MS;
        try {
            while (mRunning) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    Bus.log("传输: 等待 DONE_ACK 超时(" + DONE_ACK_TIMEOUT_MS + "ms)");
                    return -2;
                }
                link.s.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
                Proto.Frame f = Proto.readFrame(link.in, 0, Proto.MAX_FRAME_LEN);
                if (f == null) {
                    Bus.log("传输: 等待 DONE_ACK 时连接已关闭");
                    return -4;
                }
                if (f.type == Proto.TYPE_PING) {
                    replyPong(link);
                    continue;
                }
                if (f.type == Proto.TYPE_PONG) {
                    continue;
                }
                if (f.type == Proto.TYPE_DONE_ACK) {
                    int code = Proto.parseDoneAck(f.data);
                    Bus.log("传输: 收到 DONE_ACK(" + code + ")");
                    return code;
                }
                if (f.type == Proto.TYPE_CANCEL) {
                    String reason = new String(f.data, java.nio.charset.StandardCharsets.UTF_8);
                    //pri added by zhuyang,Batch 4: a per-image failure ("第 N 张...") is reported mid-stream but the receiver keeps going,20261017
                    if (reason.startsWith("第 ")) {
                        Bus.log("传输: 对端报告单张失败(继续等待完成确认): " + reason);
                        continue;
                    }
                    Bus.log("传输: 对方取消: " + reason);
                    return -3;
                }
                Bus.log("传输: 等待 DONE_ACK 时忽略帧类型 " + f.type);
            }
        } catch (java.net.SocketTimeoutException te) {
            Bus.log("传输: 等待 DONE_ACK 超时(" + DONE_ACK_TIMEOUT_MS + "ms)");
            return -2;
        } catch (Exception e) {
            Bus.log("传输: 等待 DONE_ACK 失败: " + e);
            return link.linkDead.get() ? -4 : -1;
        }
        return -1;
    }

    //pri added by zhuyang,File size for FILE_BEGIN and the byte-based progress; -1 when unknown,20261017
    private long imageSize(Uri uri) {
        if (uri == null) return -1;
        try {
            android.database.Cursor c = mCtx.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.SIZE}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {}
        try {
            android.content.res.AssetFileDescriptor afd =
                    mCtx.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd != null) {
                try {
                    return afd.getLength();
                } finally {
                    afd.close();
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }
    //pri added by zhuyang,Wait for DONE_ACK: 0=OK, -1=protocol error, -2=timeout, -3=peer cancel, -4=link dead,20261017,end

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
                } else if (f.type == Proto.TYPE_META || f.type == Proto.TYPE_META_INFO
                        || f.type == Proto.TYPE_PREVIEW || f.type == Proto.TYPE_FULL) {
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
                if (f.type == Proto.TYPE_META || f.type == Proto.TYPE_META_INFO
                        || f.type == Proto.TYPE_PREVIEW) {
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
            fail(new XferError(XferError.Stage.OFFER, XferError.PEER_REJECTED, "对方拒收"));
            mMain.post(new Runnable() {
                @Override public void run() { mListener.onPeerRejected(); }
            });
        } else if (link.linkDead.get() && link.failReason != null) {
            //pri added by zhuyang,Heartbeat already judged the peer dead: report that, not a plain timeout,20261017
            notifyLinkFailedOnce(link, linkError(link, XferError.Stage.OFFER));
        } else {
            fail(new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT,
                    "等待对方「接收/拒绝」超时(" + (VERDICT_WAIT_MS / 1000) + "s)"));
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

    //pri modify by zhuyang,Release the hotspot network when the whole transaction finishes (success/failure/cancel),20261019
    private void releaseHotspotLater() {
        //pri added by zhuyang,1s grace so the peer can read DONE_ACK before the AP goes away; generations cancel stale runs,20261019,start
        final int hg = mHotspotGen;
        final int wg = mWifiGen;
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(HS_RELEASE_GRACE_MS); } catch (InterruptedException ignored) {}
                if (wg != mWifiGen) return; // a new round already cleaned up / re-joined
                restoreWifiAfterJoin();
                if (hg == mHotspotGen && (mHotspotActive || mHotspotReservation != null)) {
                    Bus.log("热点: 本轮传输结束,释放 AP owner 热点");
                    closeHotspot();
                }
            }
        }, "hotspot-release").start();
        //pri added by zhuyang,1s grace so the peer can read DONE_ACK before the AP goes away; generations cancel stale runs,20261019,end
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
        //pri added by zhuyang,Service teardown must forget the joined AP and restore the original network,20261019
        restoreWifiAfterJoin();
        closeHotspot();
        //pri added by zhuyang,P2P bearer disabled (product decision: same LAN -> hotspot),20261019
        if (P2P_ENABLED) {
            try { if (mP2p != null && mChannel != null) mP2p.removeGroup(mChannel, null); } catch (Exception ignored) {}
        }
    }

    // ---------- helpers ----------

    //pri modify by zhuyang,Batch 5: every stage funnels through one coded failure exit,20261017,start
    private void fail(final XferError err) {
        if (err == null) return;
        mSenderWaiting = false;
        mDiscovering = false; //pri added by zhuyang,Batch 5: a failed round stops its connect stage,20261017
        Bus.log("传输: [失败] " + err.code + "(" + XferError.nameOf(err.code) + ") stage=" + err.stage
                + " — " + (err.detail == null ? "" : err.detail));
        //pri modify by zhuyang,A terminal failure with no live link must not leave the AP/joined Wi-Fi behind,20261019,start
        if (!hasLiveLink()) {
            restoreWifiAfterJoin();
            if (mHotspotActive || mHotspotReservation != null) {
                closeHotspot();
                Bus.log("热点: 本轮失败,已释放热点");
            }
        }
        //pri modify by zhuyang,A terminal failure with no live link must not leave the AP/joined Wi-Fi behind,20261019,end
        final String legacy = err.detail == null ? XferError.nameOf(err.code) : err.detail;
        mMain.post(new Runnable() {
            @Override public void run() {
                for (Listener l : mAll) {
                    l.onTransferError(err.stage, err.code, err.detail);
                    l.onXferFailed(legacy);
                }
            }
        });
    }

    //pri added by zhuyang,Batch 5: compatibility shell for callers without a stage/code,20261017
    private void fail(final String why) {
        fail(new XferError(XferError.Stage.CONNECT, XferError.UNKNOWN, why));
    }
    //pri modify by zhuyang,Batch 5: every stage funnels through one coded failure exit,20261017,end

    private static String errName(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY: return "BUSY";
            case WifiP2pManager.ERROR: return "ERROR";
            case WifiP2pManager.P2P_UNSUPPORTED: return "P2P_UNSUPPORTED";
            case WifiP2pManager.NO_SERVICE_REQUESTS: return "NO_SERVICE_REQUESTS";
            default: return "?";
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

    //pri added by zhuyang,Batch 4: decode the verified .part without materializing the whole file in memory,20261017
    private static Bitmap decode(File file) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), o);
            int scale = 1;
            while (o.outWidth / scale > 1600 || o.outHeight / scale > 1600) scale *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), o2);
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

    //pri added by zhuyang,Batch 4: stream the verified .part into MediaStore; an empty row is deleted on a write failure,20261017,start
    private Uri saveFileToGallery(File file, String mime) {
        if (Build.VERSION.SDK_INT < 29) return null;
        Uri u = null;
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "nfctap_" + System.currentTimeMillis() + ".jpg");
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/NfcTapDemo");
            u = mCtx.getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return null;
            InputStream in = new FileInputStream(file);
            OutputStream os = null;
            try {
                os = mCtx.getContentResolver().openOutputStream(u);
                if (os == null) throw new java.io.IOException("openOutputStream returned null");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
            } finally {
                try { in.close(); } catch (Exception ignored) {}
                try { if (os != null) os.close(); } catch (Exception ignored) {}
            }
            return u;
        } catch (Throwable t) {
            Bus.log("传输: 存相册失败 " + t);
            if (u != null) {
                try { mCtx.getContentResolver().delete(u, null, null); } catch (Throwable ignored) {}
            }
            return null;
        }
    }
    //pri added by zhuyang,Batch 4: stream the verified .part into MediaStore; an empty row is deleted on a write failure,20261017,end
}
