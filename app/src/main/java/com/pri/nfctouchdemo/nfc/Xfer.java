package com.pri.nfctouchdemo.nfc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder; //pri added by zhuyang,EXIF-aware card thumbnail decode,20261019
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
    private static final long OWNER_WAIT_MS = 15000L; //pri modify by zhuyang,Exit sync: outlast the 12s tap-time join budget before calling the peer gone,20261020
    private static final long HS_RELEASE_GRACE_MS = 1000L;
    private static final int HS_HIDDEN_ATTEMPTS = 2;
    private static final int HS_HIDDEN_UNAVAILABLE = 0;
    private static final int HS_HIDDEN_OK = 1;
    private static final int HS_HIDDEN_FAILED = 2;
    //pri added by zhuyang,Tap-time preconnect: quick association attempts with backoff, hard-capped at 12s from the tap,20261020,start
    //pri modify by zhuyang,Exit sync: the tap-time join must survive a late AP start, so the early loop gets 12s,20261020
    private static final long HS_EARLY_BUDGET_MS = 12000L;
    private static final long HS_EARLY_ATTEMPT_MS = 4000L;
    //pri added by zhuyang,Exit sync: the first join fires before the framework has scanned the fresh AP; fail it fast and retry,20261020
    private static final long HS_EARLY_FIRST_PROBE_MS = 1200L;
    private static final long HS_EARLY_RETRY_MS = 400L;
    //pri modify by zhuyang,Exit sync: the owner AP is up about 1s after the tap, so wait before the first join,20261020
    private static final long HS_EARLY_START_DELAY_MS = 1500L;
    private static final long HS_EARLY_HANDOFF_WAIT_MS = 5000L;
    //pri added by zhuyang,Tap-time preconnect: quick association attempts with backoff, hard-capped at 8s from the tap,20261019,end
    //pri added by zhuyang,Hotspot bearer: silent hidden join first, specifier fallback with a dialog-sized budget,20261019,end
    private static final int VERDICT_WAIT_MS = 20000;  //pri modify by zhuyang,Shorten the verdict wait from 60s to 20s,20261017
    //pri added by zhuyang,Liveness limits: read cap, PING idle, dead peer, write stall and round total,20261017,start
    private static final int RX_TIMEOUT_MS = 30000;      // control/role paths still allow a slow first frame
    private static final int RX_DATA_TIMEOUT_MS = 10000; // data phase: a chunk or PING must arrive within 10s
    //pri modify by zhuyang,Exit sync: the round lasts seconds, so the interaction heartbeat is second-scale,20261020,start
    private static final int HEARTBEAT_IDLE_MS = 300;    // no frame written for this long -> PING
    private static final int HEARTBEAT_DEAD_MS = 1000;   // no byte received for this long -> link is dead
    private static final int HEARTBEAT_TICK_MS = 50;     // heartbeat check period
    //pri added by zhuyang,Exit sync: once bytes flow a slow link may gap over 1s, so the data phase uses 5s,20261020
    private static final int TRANSFER_DEAD_MS = 5000;    // data phase: no byte received for this long -> link is dead
    //pri added by zhuyang,Exit sync: keep the listener and the AP briefly so a late-joining peer is told st=out,20261020
    private static final int ABORT_FAREWELL_MS = 12000;
    //pri modify by zhuyang,Exit sync: the round lasts seconds, so the interaction heartbeat is second-scale,20261020,end
    private static final int WRITE_STALL_MS = 10000;     // transfer phase: no write progress -> close
    private static final long TRANSFER_TOTAL_MS = 5 * 60 * 1000L; // one photo round is capped at 5 minutes
    private static final int OFFER_WAIT_MS = 20000;      // connected receiver waiting for the peer's offer
    private static final int WRITE_CHUNK = 64 * 1024;    // refresh write progress inside one large frame
    //pri added by zhuyang,Liveness limits: read cap, PING idle, dead peer, write stall and round total,20261017,end
    //pri added by zhuyang,Chunked delivery: 128KB wire chunks, a 20s completion handshake and 4Hz progress posts,20261017,start
    private static final int CHUNK_SIZE = 128 * 1024;
    private static final int DONE_ACK_TIMEOUT_MS = 20000;
    //pri added by zhuyang,Wait this long after FILE_BEGIN for the receiver's TYPE_SKIP before streaming DATA,20261019
    private static final int SKIP_WAIT_MS = 300;
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
    //pri added by zhuyang,Exit sync: readVerdict result when the peer sent CANCEL while we waited for a verdict,20261020
    private static final int VERDICT_CANCELLED = -2;
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
        //pri added by zhuyang,Exit sync: data is flowing, so the dead check relaxes to TRANSFER_DEAD_MS,20261020
        volatile boolean hbTransferPhase;
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
    private final TransferStore mStore; //pri modify by zhuyang,Batch 4: incoming .part files plus the sha256 completion set,20261019
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
    //pri added by zhuyang,Exit sync: a local exit cancels this round exactly once,20261020
    private final java.util.concurrent.atomic.AtomicBoolean mRoundAborted =
            new java.util.concurrent.atomic.AtomicBoolean();
    //pri added by zhuyang,Exit sync: a refused port or a missing hotspot is a liveness signal; PEER_GONE is reported once per round,20261020,start
    private volatile boolean mPeerGoneSignal;
    private final java.util.concurrent.atomic.AtomicBoolean mPeerGoneReported =
            new java.util.concurrent.atomic.AtomicBoolean();
    //pri added by zhuyang,A round that already failed must not be re-reported as PEER_GONE (that would hide its failure banner),20261020
    private final java.util.concurrent.atomic.AtomicBoolean mRoundFailed =
            new java.util.concurrent.atomic.AtomicBoolean();
    //pri added by zhuyang,Exit sync: a refused port or a missing hotspot is a liveness signal; PEER_GONE is reported once per round,20261020,end
    private volatile boolean mDoneNotified; //pri added by zhuyang,"transfer done" is delivered at most once per connection,20261014
    private volatile int mExpect;           //pri added by zhuyang,Total image count announced by TYPE_META this round,20261014
    private volatile int mRound;            //pri added by zhuyang,Round token: a stale verdict must not leak into the next round,20261014
    private volatile Uri mFirstSavedUri;    //pri added by zhuyang,MediaStore Uri of the first image saved this round,20261014
    private volatile Uri mPreviewUri;       //pri added by zhuyang,Card preview picked by the gallery (the photo the user opened); null = first,20261019
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
    //pri added by zhuyang,Liveness probe: whether this round is still on screen (HELLO st=in/out),20261020
    private volatile boolean mRoundLive = true;
    //pri added by zhuyang,Exit sync: while a farewell window is open the radio must stay up so a late peer gets st=out,20261020
    private volatile boolean mFarewellActive;
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
    //pri added by zhuyang,Tap-time preconnect: one Wi-Fi churn owner at a time; a swipe-time join stops the early loop,20261019,start
    private final Object mHotspotJoinLock = new Object();
    private volatile boolean mEarlyJoinAbort;
    private volatile boolean mEarlyJoinInAttempt;
    private volatile boolean mEarlyJoinHandoff;
    //pri added by zhuyang,Tap-time preconnect: one Wi-Fi churn owner at a time; a swipe-time join stops the early loop,20261019,end
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
        //pri modify by zhuyang,Round flags always reset; link state only when no live link remains,20261019,start
        resetRoundFlags();
        if (!hasLiveLink()) resetRoundState();
        //pri modify by zhuyang,Round flags always reset; link state only when no live link remains,20261019,end
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

    //pri added by zhuyang,Liveness probe: set before any socket is closed so a late HELLO already reports st=out,20261020
    public void setRoundLive(boolean live) {
        if (mRoundLive == live) return;
        mRoundLive = live;
        Bus.log("传输: 本轮页面状态 st=" + (live ? "in" : "out") + ",后续 HELLO 携带该状态");
    }
    //pri added by zhuyang,Liveness probe: set before any socket is closed so a late HELLO already reports st=out,20261020

    //pri added by zhuyang,Batch 5: a stray inbound connection must not fail an already-running round,20261017
    private void failInbound(XferError err) {
        if (hasLiveLink()) {
            Bus.log("传输: [认证] 已有活动连接,忽略本次入站失败 " + err);
            return;
        }
        fail(err);
    }

    //pri added by zhuyang,Round-level flags must reset on every tap even when the previous link is still alive,20261019,start
    //pri added by zhuyang,These fields never own a socket, thread or bitmap: clearing them is safe with a live link,20261019
    private void resetRoundFlags() {
        mReceiverRole = false;
        mCanSend = false;
        mHasPhotos = false;
        mPeerRole = "U";
        mPeerSwipedAt = 0;
        mLocalSwipedAt = 0;
        mPeerWantsSend = false;
        mYielded = false;
        mVerdictSent = false;
        mRoundAborted.set(false); //pri added by zhuyang,Exit sync: the next tap may cancel its own round,20261020
        mFarewellActive = false; //pri added by zhuyang,Exit sync: a new round owns the radio again,20261020
        mPeerGoneSignal = false; //pri added by zhuyang,Exit sync: liveness signals belong to one round,20261020
        mPeerGoneReported.set(false); //pri added by zhuyang,Exit sync: the next tap may report its own PEER_GONE,20261020
        mRoundFailed.set(false); //pri added by zhuyang,Exit sync: the next tap starts with no failure,20261020
        mDoneNotified = false;
        mExpect = 0;
        mFirstSavedUri = null;
        mPreviewUri = null; //pri added by zhuyang,New round: the preview hint belongs to the previous tap,20261019
        mEarlyJoinAbort = false;
        mEarlyJoinHandoff = false;
    }
    //pri added by zhuyang,Round-level flags must reset on every tap even when the previous link is still alive,20261019,end

    private void resetRoundState() {
        if (mGoAddress != null || mSenderWaiting || mServer != null || mGotConnection) {
            Bus.log("传输: 新一轮开始,清掉上一轮状态(组主=" + mGoAddress + ", 等直连=" + mSenderWaiting
                    + ", 已连接=" + mGotConnection + ", 服务端=" + (mServer != null) + ")");
        }
        resetRoundFlags();
        mGoAddress = null;
        mImGo = false;
        mConnecting = false;
        mDiscovering = false;
        mGotConnection = false;
        mSenderWaiting = false;
        mP2pAttempt = 0;
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
        synchronized (mPreReadFrames) { mPreReadFrames.clear(); }
        //pri added by zhuyang,Drop every link of the previous round, including the role listener's,20261017,end
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
            Bus.log("热点: 会话派生凭据 SSID=" + mDerivedSsid + ",pass=***");
        } catch (Throwable t) {
            Bus.log("热点: 派生凭据失败,退回固定凭据: " + t);
        }
    }
    //pri added by zhuyang,Batch 6: derive the same SSID/pass on both ends from the two sids,20261017,end

    //pri added by zhuyang,Batch 6: a non-owner receiver joins the owner AP so it can accept the owner's send,20261017,start
    public void startHotspotClient() {
        if (mApOwner) return;
        //pri modify by zhuyang,Exit sync: same LAN still builds the tap-time link, directly against the peer port,20261020,start
        if (likelySameLan()) {
            final String peerIp = mPeerLanIp;
            Bus.log("承载: 对方与本机同处一个 LAN(对方 IP=" + peerIp + "),碰触即同网直连");
            new Thread(new Runnable() {
                @Override public void run() {
                    final long deadline = SystemClock.elapsedRealtime() + HS_EARLY_BUDGET_MS;
                    int attempt = 0;
                    while (mRunning && SystemClock.elapsedRealtime() < deadline) {
                        if (isAlive(mSendSocket) || isAlive(mStashedSocket) || mPeerSocket != null) return;
                        attempt++;
                        if (connectTo(peerIp, "局域网")) return;
                        try { Thread.sleep(400); } catch (InterruptedException ignored) { return; }
                    }
                    Bus.log("承载: 同 LAN 碰触直连未在 " + HS_EARLY_BUDGET_MS + "ms 内完成(尝试 "
                            + attempt + " 次)");
                    //pri added by zhuyang,Exit sync: a joiner that never reached the peer must not leave its page up,20261020,start
                    if (mRunning && mRoundLive && mLocalSwipedAt == 0 && !mRoundAborted.get()
                            && !mRoundFailed.get() && !mGotConnection && !hasLiveLink()) {
                        Bus.log("承载: 同 LAN 碰触直连失败,判定对方已退出");
                        reportPeerGone("同 LAN 碰触直连失败");
                    }
                    //pri added by zhuyang,Exit sync: a joiner that never reached the peer must not leave its page up,20261020,end
                }
            }, "lan-client").start();
            return;
        }
        //pri modify by zhuyang,Exit sync: same LAN still builds the tap-time link, directly against the peer port,20261020,end
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
        mEarlyJoinAbort = false; //pri added by zhuyang,This round's early loop runs until a swipe-time join preempts it,20261019
        mEarlyJoinHandoff = false; //pri added by zhuyang,No swipe-time handoff yet in this round,20261019
        new Thread(new Runnable() {
            @Override public void run() {
                if (!mRunning) return;
                //pri modify by zhuyang,Tap-time preconnect: build the link while the user is still looking at the page; bounded to 8s,20261019,start
                Bus.log("传输: 非 AP owner 碰触即接入对方热点(上滑前建链)");
                joinHotspotEarly(ssid, pass, derived);
                //pri modify by zhuyang,Tap-time preconnect: build the link while the user is still looking at the page; bounded to 8s,20261019,end
            }
        }, "ap-client").start();
    }
    //pri added by zhuyang,Batch 6: a non-owner receiver joins the owner AP so it can accept the owner's send,20261017,end

    //pri added by zhuyang,Tap-time preconnect: quick attempts with backoff, hard-capped at 8s from the tap,20261019,start
    /**
     * Join the owner AP while the user is still looking at the page. The owner's AP is up only
     * ~300ms after the tap, so the first addNetwork/enableNetwork may find nothing: each attempt
     * therefore fails fast (HS_EARLY_ATTEMPT_MS) and is retried after a short backoff instead of
     * burning a full swipe-time association window. The whole loop is bounded by
     * HS_EARLY_BUDGET_MS from the tap; a swipe-time join preempts it via mEarlyJoinAbort.
     */
    private void joinHotspotEarly(String ssid, String pass, boolean derived) {
        final long startedAt = SystemClock.elapsedRealtime();
        final long deadline = startedAt + HS_EARLY_BUDGET_MS;
        if (Build.VERSION.SDK_INT < 29) {
            Bus.log("热点: API<29 不支持热点接入");
            return;
        }
        final WifiManager wm = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        final int gen = mWifiGen;
        //pri added by zhuyang,The owner AP is up ~300ms after the tap: a short delay avoids a doomed enableNetwork that cannot re-scan,20261019
        try { Thread.sleep(HS_EARLY_START_DELAY_MS); } catch (InterruptedException ignored) {}
        if (mEarlyJoinAbort || !mRunning || gen != mWifiGen
                || SystemClock.elapsedRealtime() >= deadline) return;
        if (!ensureWifiEnabled(wm, gen)) return;
        if (gen != mWifiGen) return;
        int attempt = 0;
        boolean joined = false;
        try {
            while (mRunning && gen == mWifiGen && !mEarlyJoinAbort
                    && SystemClock.elapsedRealtime() < deadline) {
                if (isAlive(mSendSocket) || isAlive(mStashedSocket)) {
                    Bus.log("热点: 碰触提前接入结束,链路已由其他路径建立");
                    joined = true;
                    return;
                }
                attempt++;
                long left = deadline - SystemClock.elapsedRealtime();
                final int hr;
                //pri added by zhuyang,A swipe-time join waits for this attempt instead of aborting it half way,20261019,start
                mEarlyJoinInAttempt = true;
                try {
                    synchronized (mHotspotJoinLock) {
                        if (mEarlyJoinAbort || gen != mWifiGen) return;
                        if (isAlive(mSendSocket) || isAlive(mStashedSocket)) {
                            joined = true;
                            return;
                        }
                        long attemptMs = Math.max(500L, Math.min(
                                attempt == 1 ? HS_EARLY_FIRST_PROBE_MS : HS_EARLY_ATTEMPT_MS, left));
                        Bus.log("热点: 碰触提前接入尝试 " + attempt + "(剩余 " + left
                                + "ms,单次 " + attemptMs + "ms)");
                        hr = joinHotspotHidden(wm, ssid, pass, gen, 1, attemptMs, HS_EARLY_BUDGET_MS);
                    }
                    if (hr == HS_HIDDEN_OK) {
                        if (mEarlyJoinAbort || gen != mWifiGen) return;
                        long gwLeft = Math.max(1000L, deadline - SystemClock.elapsedRealtime());
                        String gw = waitForWifiGateway(wm, Math.min(3000L, gwLeft));
                        if (mEarlyJoinAbort || gen != mWifiGen) return;
                        if (gw != null && connectViaGateway(gw, 2, 1500)) {
                            Bus.log("热点: 碰触提前接入成功,耗时 "
                                    + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                            joined = true;
                            return;
                        }
                        Bus.log("热点: 热点已接入但对方服务未就绪,提前接入放弃");
                        return;
                    }
                    if (hr == HS_HIDDEN_UNAVAILABLE) {
                        Bus.log("传输: 隐藏 Wi-Fi 接口不可用,提前接入改用 WifiNetworkSpecifier");
                        long specLeft = Math.max(1000L, deadline - SystemClock.elapsedRealtime());
                        synchronized (mHotspotJoinLock) {
                            joined = joinHotspotOnce(ssid, pass, !derived, specLeft);
                        }
                        return;
                    }
                } finally {
                    mEarlyJoinInAttempt = false;
                }
                //pri added by zhuyang,A swipe-time join waits for this attempt instead of aborting it half way,20261019,end
                long sleep = Math.min(HS_EARLY_RETRY_MS,
                        Math.max(0L, deadline - SystemClock.elapsedRealtime()));
                if (sleep > 0) {
                    try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
                }
            }
            Bus.log("热点: 碰触提前接入未在 " + HS_EARLY_BUDGET_MS + "ms 内完成(尝试 "
                    + attempt + " 次)");
        } finally {
            if (!joined && !mEarlyJoinAbort && !mEarlyJoinHandoff && gen == mWifiGen
                    && !isAlive(mSendSocket) && !isAlive(mStashedSocket)) {
                restoreWifiAfterJoin();
                //pri added by zhuyang,Exit sync: a joiner that never reaches the peer must not leave its page up forever,20261020,start
                if (mRunning && mRoundLive && !mRoundAborted.get() && !mRoundFailed.get()
                        && !mGotConnection && !hasLiveLink()) {
                    Bus.log("热点: 碰触提前接入失败,判定对方已退出");
                    reportPeerGone("碰触后 " + HS_EARLY_BUDGET_MS + "ms 内无法接入对方");
                }
                //pri added by zhuyang,Exit sync: a joiner that never reaches the peer must not leave its page up forever,20261020,end
            }
        }
    }
    //pri added by zhuyang,Tap-time preconnect: quick attempts with backoff, hard-capped at 8s from the tap,20261019,end

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

    //pri added by zhuyang,Compare only the first three IPv4 octets (the hotspot DHCP subnet),20261019,start
    private static boolean sameSubnet24(String a, String b) {
        int[] x = parseIpv4(a);
        int[] y = parseIpv4(b);
        return x != null && y != null && x[0] == y[0] && x[1] == y[1] && x[2] == y[2];
    }
    //pri added by zhuyang,Compare only the first three IPv4 octets (the hotspot DHCP subnet),20261019,end
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
        //pri added by zhuyang,Exit sync: no link within 8s of the tap means the peer left, so this phone closes too,20261020,start
        final int round = mRound;
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mRunning && round == mRound && !mRoundAborted.get() && !mRoundFailed.get()
                        && mRoundLive && mApOwner && !mGotConnection && !hasLiveLink()) {
                    //pri modify by zhuyang,Exit sync: only the AP owner waits for an inbound link; the joiner owns the connect budget,20261020
                    Bus.log("传输: " + (OWNER_WAIT_MS / 1000) + " 秒内仍未收到对方连接,判定对方已退出");
                    reportPeerGone((OWNER_WAIT_MS / 1000) + " 秒内对方未接入");
                }
            }
        }, OWNER_WAIT_MS);
        //pri added by zhuyang,Exit sync: no link within 8s of the tap means the peer left, so this phone closes too,20261020,end
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

    //pri added by zhuyang,TYPE_SKIP: "this content hash is already complete, do not stream its DATA",20261019,start
    private void sendSkip(Link link, int index) {
        if (link == null || link.s.isClosed()) return;
        try {
            byte[] b = Proto.buildSkip(index);
            mWriteLock.lock();
            try {
                Proto.writeFrame(link.out, Proto.TYPE_SKIP, b.length, b);
                link.out.flush();
                markTx(link);
                Bus.log("传输: 已回 TYPE_SKIP(index=" + index + ")");
            } finally {
                mWriteLock.unlock();
            }
        } catch (Exception e) {
            Bus.log("传输: 回 TYPE_SKIP 失败 " + e);
        }
    }
    //pri added by zhuyang,TYPE_SKIP: "this content hash is already complete, do not stream its DATA",20261019,end

    //pri modify by zhuyang,Keep the TransferProgress model updated for watchdogs/logs but never post it to the UI,20261019,start
    private void postProgress(TransferProgress p, boolean force) {
        //pri modify by zhuyang,Progress text lines were removed from the UI by product decision,20261019
    }
    //pri modify by zhuyang,Keep the TransferProgress model updated for watchdogs/logs but never post it to the UI,20261019,end
    //pri added by zhuyang,Liveness helpers: mark a written frame and write a possibly large frame with progress,20261017,end

    //pri modify by zhuyang,Batch 4: receive session key (the sender's sid) for .part paths only; resume uses sha256,20261019
    private String storeSid() {
        return TransferStore.sanitizeSid(mPeerSid != null ? mPeerSid : Bus.sid());
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
            if (!isAlive(link)) return;
            //pri added by zhuyang,Exit sync: a role-listened link promoted to the send channel only flips its expectation,20261020
            if (link.hbRunning) {
                link.hbExpectRx = expectRx;
                return;
            }
            //pri added by zhuyang,Exit sync: a role-listened link promoted to the send channel only flips its expectation,20261020
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
        Bus.log("传输: 心跳已启动(空闲 " + HEARTBEAT_IDLE_MS + "ms 发 PING,交互 "
                + HEARTBEAT_DEAD_MS + "ms / 传输 " + TRANSFER_DEAD_MS + "ms 无数据判失效)");
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
                } else if (link.hbExpectRx) {
                    //pri modify by zhuyang,Exit sync: 1s while the user decides, 5s once bytes flow,20261020,start
                    final int deadMs = link.hbTransferPhase ? TRANSFER_DEAD_MS : HEARTBEAT_DEAD_MS;
                    if (now - link.lastRx.get() >= deadMs) {
                        signalLinkDead(link, new XferError(XferError.Stage.TRANSFER, XferError.TRANSFER_TIMEOUT,
                                "连接超时(" + deadMs + "ms 未收到对端任何数据)"));
                        return;
                    }
                    //pri modify by zhuyang,Exit sync: 1s while the user decides, 5s once bytes flow,20261020,end
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
                //pri added by zhuyang,Tap-time preconnect: promote the link built while the user was deciding instead of joining the hotspot again,20261019,start
                if (adoptTapTimeSendLink()) return;
                //pri added by zhuyang,Tap-time preconnect: promote the link built while the user was deciding instead of joining the hotspot again,20261019,end
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
                        Bus.log("热点: " + (OWNER_WAIT_MS / 1000) + "s 内对方仍未接入,判定对方已退出");
                        reportPeerGone((OWNER_WAIT_MS / 1000) + " 秒内对方未接入");
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
                if (joinHotspot(hsSsid, hsPass, derived)) {
                    //pri added by zhuyang,A link that finished during the handoff wait is stashed; promote it before returning,20261019
                    adoptTapTimeSendLink();
                    return;
                }
                if (!mSenderWaiting) return;
                //pri added by zhuyang,Exit sync: a refused port or a missing AP after the full retry budget means the peer left,20261020,start
                if (mPeerGoneSignal) {
                    Bus.log("承载: 同 LAN 与热点均失败,对方热点/端口不可达,判定对方已退出");
                    reportPeerGone("对方热点/端口不可达");
                    return;
                }
                //pri added by zhuyang,Exit sync: a refused port or a missing AP after the full retry budget means the peer left,20261020,end
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
                //pri modify by zhuyang,Batch 4: do not emit have= any more; resume is decided per FILE_BEGIN sha256,20261019
                byte[] hello = Proto.buildHello(Bus.sid(), localRole(), mHasPhotos, null, mRoundLive);
                Proto.writeFrame(out, Proto.TYPE_HELLO, hello.length, hello);
                out.flush();
                Proto.Frame f = Proto.readFrame(in, Proto.HELLO_TIMEOUT_MS, Proto.MAX_HELLO_LEN);
                if (f == null) {
                    //pri added by zhuyang,Exit sync: the peer dropped before HELLO_ACK, it is gone,20261020
                    Bus.log("传输: [认证] HELLO_ACK 未到(对方已断开),判定对方已退出");
                    closeQuietly(s);
                    throw new FatalXferException(new XferError(XferError.Stage.CONNECT,
                            XferError.PEER_GONE, "对方未回 HELLO_ACK(已断开)"));
                }
                if (f.type != Proto.TYPE_HELLO_ACK) {
                    Bus.log("传输: [认证] 对方首帧不是 HELLO_ACK(type=" + f.type + "),关闭连接");
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
                Bus.log("传输: [认证] 对端状态 st=" + (ack.inRound ? "in" : "out"));
                //pri added by zhuyang,Liveness probe: the peer exited before this link was ready, close now instead of waiting,20261020,start
                if (!ack.inRound) {
                    Bus.log("传输: 对端已退出本轮(st=out),本机立即收起");
                    closeQuietly(s);
                    throw new FatalXferException(new XferError(XferError.Stage.CONNECT,
                            XferError.PEER_GONE, "对方已退出本轮"));
                }
                //pri added by zhuyang,Liveness probe: the peer exited before this link was ready, close now instead of waiting,20261020,end
                //pri added by zhuyang,This phone already left: deliver st=out, then drop the link instead of adopting it,20261020
                if (!mRoundLive) {
                    Bus.log("传输: 本机已退出本轮,握手后即断开(不做角色分类)");
                    closeQuietly(s);
                    return null;
                }
                byte[] role = Proto.buildRole(localRole(), Bus.sid());
                Proto.writeFrame(out, Proto.TYPE_ROLE, role.length, role);
                out.flush();
                Bus.log("传输: [认证] HELLO_ACK 校验通过,对方角色=" + ack.role
                        + ",本机角色=" + localRole());
                Link link = new Link(s, in, out, rxAt, txAt);
                //pri modify by zhuyang,Batch 4: have= is format compatibility only; skip decisions use FILE_BEGIN sha256,20261019
                Bus.log("传输: [认证] 续传按 sha256 判定,have= 位图仅保留格式兼容,不再用于判定");
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
            Bus.log("传输: [认证] 等待 HELLO_ACK 超时(" + Proto.HELLO_TIMEOUT_MS + "ms),判定对方已退出");
            closeQuietly(s);
            throw new FatalXferException(new XferError(XferError.Stage.CONNECT,
                    XferError.PEER_GONE, "等待 HELLO_ACK 超时(" + Proto.HELLO_TIMEOUT_MS + "ms)"));
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
            } catch (java.net.ConnectException ce) {
                //pri added by zhuyang,Exit sync: nothing listens on the peer port, remember it as a peer-gone signal,20261020
                mPeerGoneSignal = true;
                Bus.log("传输: " + how + "连接被拒(对方端口未监听,可能已退出): " + ce);
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
        //pri added by zhuyang,Exit sync: the swipe-time join owns the peer-gone signal; the early probe's misses are normal,20261020
        mPeerGoneSignal = false;
        //pri added by zhuyang,Tap-time preconnect: a swipe during an early attempt waits for that attempt (a live link is reusable) instead of restarting the join,20261019,start
        mEarlyJoinHandoff = true;
        try {
            final long handoffUntil = SystemClock.elapsedRealtime() + HS_EARLY_HANDOFF_WAIT_MS;
            while (mEarlyJoinInAttempt && SystemClock.elapsedRealtime() < handoffUntil) {
                if (isAlive(mSendSocket) || isAlive(mStashedSocket)) {
                    Bus.log("传输: 碰触提前接入已完成,直接复用链路");
                    return true;
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            if (isAlive(mSendSocket) || isAlive(mStashedSocket)) {
                Bus.log("传输: 碰触提前接入已完成,直接复用链路");
                return true;
            }
            //pri added by zhuyang,Tap-time preconnect: a swipe during an early attempt waits for that attempt (a live link is reusable) instead of restarting the join,20261019,end
            //pri added by zhuyang,A swipe-time join preempts the tap-time loop and owns the Wi-Fi churn from here,20261019,start
            mEarlyJoinAbort = true;
            synchronized (mHotspotJoinLock) {
                if (isAlive(mSendSocket) || isAlive(mStashedSocket)) {
                    Bus.log("传输: 碰触提前接入已完成,跳过热点接入");
                    return true;
                }
                //pri added by zhuyang,A swipe-time join preempts the tap-time loop and owns the Wi-Fi churn from here,20261019,end
                final int hr = joinHotspotHidden(wm, ssid, pass, gen,
                        HS_HIDDEN_ATTEMPTS, WIFI_ASSOC_POLL_MS, HS_HIDDEN_BUDGET_MS);
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
        } finally {
            mEarlyJoinHandoff = false;
        }
    }

    //pri added by zhuyang,Silent join via the hidden addNetwork + enableNetwork (deterministic netId for cleanup),20261019,start
    private int joinHotspotHidden(WifiManager wm, String ssid, String pass, int gen,
                                  int attempts, long attemptMs, long budgetMs) {
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
            final long deadline = SystemClock.elapsedRealtime() + budgetMs;
            for (int attempt = 1; attempt <= attempts && mRunning; attempt++) {
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
                Bus.log("传输: 隐藏接口静默接入尝试 " + attempt + "/" + attempts
                        + ",SSID=" + ssid + ",netId=" + netId + ",enable=" + enabled);
                long assocLeft = Math.max(300L,
                        Math.min(attemptMs, deadline - SystemClock.elapsedRealtime()));
                if (waitForAssociation(wm, ssid, assocLeft, gen)) return HS_HIDDEN_OK;
                if (attempt < attempts) {
                    long gap = Math.min(800L, deadline - SystemClock.elapsedRealtime());
                    if (gap > 0) {
                        try { Thread.sleep(gap); } catch (InterruptedException ignored) {}
                    }
                }
            }
            Bus.log("传输: 隐藏接口接入热点超时/失败");
            //pri added by zhuyang,Exit sync: the peer AP never showed up in the whole budget,20261020
            mPeerGoneSignal = true;
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
            //pri added by zhuyang,Immediately after association the old network's DHCP data is still readable: require an IPv4 address on the joined SSID,20261019,start
            android.net.wifi.WifiInfo wi = null;
            try { wi = wm.getConnectionInfo(); } catch (Throwable ignored) {}
            String cur = wi == null ? null : unquote(wi.getSSID());
            if (cur != null && cur.equals(mJoinedSsid) && wi.getIpAddress() != 0) {
                String gw = wifiGateway(wm);
                //pri added by zhuyang,The pre-join DHCP lease can still be cached under the new SSID: require a lease in the gateway subnet,20261019,start
                if (gw != null && sameSubnet24(intToIp(wi.getIpAddress()), gw)) return gw;
                //pri added by zhuyang,The pre-join DHCP lease can still be cached under the new SSID: require a lease in the gateway subnet,20261019,end
            }
            //pri added by zhuyang,Immediately after association the old network's DHCP data is still readable: require an IPv4 address on the joined SSID,20261019,end
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
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
        return connectViaGateway(gw, 3, 2000);
    }

    //pri added by zhuyang,Tap-time preconnect needs a shorter gateway probe so it stays inside the 8s budget,20261019,start
    private boolean connectViaGateway(String gw, int attempts, int connectTimeoutMs) {
        if (gw == null) {
            Bus.log("传输: 热点网关未知(DHCP 未就绪)");
            return false;
        }
        for (int i = 0; i < attempts && mRunning; i++) {
            if (isAlive(mSendSocket) || (mReceiverRole && isAlive(mStashedSocket))) {
                Bus.log("传输: 已有活动连接,停止热点网关连接");
                return true;
            }
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(gw, PORT), connectTimeoutMs);
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
            } catch (java.net.ConnectException ce) {
                //pri added by zhuyang,Exit sync: nothing listens on the peer port, remember it as a peer-gone signal,20261020
                mPeerGoneSignal = true;
                Bus.log("传输: 热点内连接被拒(对方端口未监听,可能已退出): " + ce);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            } catch (Exception e) {
                Bus.log("传输: 热点内连接尝试 " + (i + 1) + " 失败: " + e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }
    //pri added by zhuyang,Tap-time preconnect needs a shorter gateway probe so it stays inside the 8s budget,20261019,end

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
                    mPeerGoneSignal = true; //pri added by zhuyang,Exit sync: the peer AP is not there,20261020
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
                } catch (java.net.ConnectException ce) {
                    //pri added by zhuyang,Exit sync: nothing listens on the peer port, remember it as a peer-gone signal,20261020
                    mPeerGoneSignal = true;
                    Bus.log("传输: 热点内连接被拒(对方端口未监听,可能已退出): " + ce);
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
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
                java.net.ServerSocket ss = null;
                try {
                    //pri modify by zhuyang,SO_REUSEADDR avoids EADDRINUSE when the previous round's listener is still in TIME_WAIT,20261020,start
                    ss = new java.net.ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new java.net.InetSocketAddress(PORT));
                    mServer = ss;
                    //pri modify by zhuyang,SO_REUSEADDR avoids EADDRINUSE when the previous round's listener is still in TIME_WAIT,20261020,end
                    Bus.log("传输: 已监听端口 " + PORT + ",等待对方连接…");
                    while (mRunning) {
                        final Socket s = ss.accept();
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
                    //pri added by zhuyang,Exit sync: an old farewell listener must not clear the next round's server,20261020
                    if (mServer == ss) mServer = null;
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
            if (f == null) {
                //pri added by zhuyang,Exit sync: the peer accepted but closed without HELLO, it is gone,20261020
                Bus.log("传输: [认证] 未收到 HELLO(对方已断开),判定对方已退出 " + s.getInetAddress());
                closeQuietly(s);
                failInbound(new XferError(XferError.Stage.CONNECT, XferError.PEER_GONE, "对方未发送 HELLO(已断开)"));
                return;
            }
            if (f.type != Proto.TYPE_HELLO) {
                Bus.log("传输: [认证] 对方首帧不是 HELLO(type=" + f.type + "),关闭连接 " + s.getInetAddress());
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
            Bus.log("传输: [认证] 对端状态 st=" + (hello.inRound ? "in" : "out"));
            //pri added by zhuyang,Liveness probe: the peer left before this link was ready, close now instead of waiting,20261020,start
            if (!hello.inRound) {
                Bus.log("传输: 对端已退出本轮(st=out),本机立即收起 " + s.getInetAddress());
                closeQuietly(s);
                failInbound(new XferError(XferError.Stage.CONNECT, XferError.PEER_GONE, "对方已退出本轮"));
                return;
            }
            //pri added by zhuyang,Liveness probe: the peer left before this link was ready, close now instead of waiting,20261020,end
            //pri modify by zhuyang,Batch 4: do not emit have= any more; resume is decided per FILE_BEGIN sha256,20261019
            byte[] ack = Proto.buildHelloAck(Bus.sid(), localRole(), null, mRoundLive);
            Proto.writeFrame(out, Proto.TYPE_HELLO_ACK, ack.length, ack);
            byte[] role = Proto.buildRole(localRole(), Bus.sid());
            Proto.writeFrame(out, Proto.TYPE_ROLE, role.length, role);
            out.flush();
            Bus.log("传输: [认证] HELLO 校验通过 " + s.getInetAddress()
                    + ",对方角色=" + hello.role + ",本机角色=" + localRole());
            //pri added by zhuyang,Liveness probe: this phone already left, the ACK above carried st=out, so drop the link,20261020,start
            if (!mRoundLive) {
                Bus.log("传输: 本机已退出本轮,回完 st=out 即断开 " + s.getInetAddress());
                closeQuietly(s);
                return;
            }
            //pri added by zhuyang,Liveness probe: this phone already left, the ACK above carried st=out, so drop the link,20261020,end
            maybeCloseHotspotForLan(s);
            Link link = new Link(s, in, out, rxAt, txAt);
            //pri modify by zhuyang,Batch 4: have= is format compatibility only; skip decisions use FILE_BEGIN sha256,20261019
            Bus.log("传输: [认证] 续传按 sha256 判定,have= 位图仅保留格式兼容,不再用于判定");
            //pri modify by zhuyang,Batch 4: have= is format compatibility only; skip decisions use FILE_BEGIN sha256,20261019,end
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
            Bus.log("传输: [认证] HELLO 超时(" + Proto.HELLO_TIMEOUT_MS + "ms),判定对方已退出 "
                    + s.getInetAddress());
            closeQuietly(s);
            failInbound(new XferError(XferError.Stage.CONNECT, XferError.PEER_GONE, "HELLO 握手超时"));
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
                //pri modify by zhuyang,Exit sync: ROLE(R) only makes this link a candidate send channel; stash it and keep reading until the swipe,20261020,start
                mStashedSocket = link;
                startRoleListener(link);
                Bus.log("传输: 对端是接收方(ROLE=R),暂存连接并监听,上滑后提升为发送通道");
                //pri modify by zhuyang,Exit sync: ROLE(R) only makes this link a candidate send channel; stash it and keep reading until the swipe,20261020,end
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
            //pri added by zhuyang,Yielded receiver still owes the receive card its peer-intent release,20261019
            notifyPeerSwipedFirst();
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
        //pri modify by zhuyang,Exit sync: an unswiped side keeps the link readable until the swipe promotes it,20261020,start
        if (isAlive(mSendSocket)) {
            Bus.log("传输: 发送通道已就绪,关闭多余连接");
            closeQuietly(link);
            return false;
        }
        mStashedSocket = link;
        startRoleListener(link);
        Bus.log("传输: 对端角色=" + (role == null ? "未知" : role) + ",暂存连接并监听(上滑后提升为发送通道)");
        return false;
        //pri modify by zhuyang,Exit sync: an unswiped side keeps the link readable until the swipe promotes it,20261020,end
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
            } else {
                //pri modify by zhuyang,Tap-time preconnect can deliver the peer's first ROLE(S) on a link that was never stashed: the winner must adopt it rather than close it,20261019,start
                // The peer is the loser and turns receiver on this same link, so keep it as a send
                // channel when there is none yet; hand it to sendImages via onP2pReady.
                if (link == mStashedSocket) mStashedSocket = null;
                if (mSendSocket == null) {
                    mSendSocket = link;
                    try { link.s.setSoTimeout(0); } catch (Exception ignored) {}
                    Bus.log("传输: 胜利方沿用对端连接作为发送通道");
                    mMain.post(new Runnable() {
                        @Override public void run() { mListener.onP2pReady(false, null); }
                    });
                } else if (link != mSendSocket) {
                    closeQuietly(link);
                }
                //pri modify by zhuyang,Tap-time preconnect can deliver the peer's first ROLE(S) on a link that was never stashed: the winner must adopt it rather than close it,20261019,end
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
        //pri added by zhuyang,Exit sync: a link that only waits for an offer still needs the 300ms/1s heartbeat,20261020
        startHeartbeat(link, true);
        //pri added by zhuyang,Exit sync: a link that only waits for an offer still needs the 300ms/1s heartbeat,20261020
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
            //pri modify by zhuyang,Exit sync: promote through the single locked helper so two threads cannot adopt the same link,20261020,start
            if (promoteStashedToSend() != null) {
                Bus.log("传输: 本机先上滑(对端未宣告发送),沿用对端连接作为发送通道");
                mMain.post(new Runnable() {
                    @Override public void run() { mListener.onP2pReady(false, null); }
                });
            }
            //pri modify by zhuyang,Exit sync: promote through the single locked helper so two threads cannot adopt the same link,20261020,end
        }
        return true;
    }

    //pri modify by zhuyang,Exit sync: the listener is a full frame reader (ROLE/META/PING/PONG/CANCEL/HELLO) and reports a closed peer at once,20261020,start
    private void roleListenerLoop(Link link) {
        Bus.log("传输: 开始监听对端角色(50ms 轮询,心跳 300ms)");
        long listenStart = SystemClock.elapsedRealtime();
        boolean handedOff = false;
        boolean peerClosed = false;
        try {
            while (mRunning) {
                if (mStashedSocket != link || mRoleListenerAbort == link) break;
                if (link.linkDead.get() || link.s.isClosed()) {
                    peerClosed = true;
                    break;
                }
                //pri added by zhuyang,No offer within 20s while the peer is alive: the long timeout still applies,20261017,start
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
                //pri added by zhuyang,No offer within 20s while the peer is alive: the long timeout still applies,20261017,end
                Proto.Frame f;
                try {
                    //pri modify by zhuyang,Exit sync: poll the buffer (a timed read could split a frame); the 1s heartbeat closes a dead link,20261020,start
                    if (link.in.available() <= 0) {
                        if (settleRoleListener(link)) break;
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    f = Proto.readFrame(link.in, RX_TIMEOUT_MS, Proto.MAX_FRAME_LEN);
                    //pri modify by zhuyang,Exit sync: poll the buffer (a timed read could split a frame); the 1s heartbeat closes a dead link,20261020,end
                } catch (Exception e) {
                    Bus.log("传输: 角色监听结束: " + e);
                    peerClosed = true;
                    break;
                }
                if (f == null) {
                    Bus.log("传输: 角色监听连接已关闭");
                    peerClosed = true;
                    break;
                }
                if (f.type == Proto.TYPE_PING) {
                    replyPong(link);
                    continue;
                }
                if (f.type == Proto.TYPE_PONG) {
                    continue;
                }
                if (f.type == Proto.TYPE_ROLE) {
                    Proto.Role role = Proto.parseRole(f.data);
                    if (role == null) continue;
                    if (mPeerSid != null && !mPeerSid.equalsIgnoreCase(role.sid)) {
                        Bus.log("传输: ROLE 帧 sid 不一致,已忽略");
                        continue;
                    }
                    Bus.log("传输: 收到角色帧 ROLE(" + role.role + ")");
                    if ("S".equals(role.role)) {
                        handedOff = true;
                        handlePeerSendRole(link, role.sid);
                    } else {
                        mPeerRole = role.role;
                    }
                } else if (f.type == Proto.TYPE_META) {
                    Bus.log("传输: 角色监听读到报价,本机按接收处理");
                    handedOff = true;
                    mStashedSocket = link;
                    handleIncoming(link, f);
                    break;
                } else if (f.type == Proto.TYPE_CANCEL) {
                    //pri added by zhuyang,Exit sync: the peer left before its offer, so the page waiting on this link must come down,20261020
                    String reason = new String(f.data, java.nio.charset.StandardCharsets.UTF_8);
                    Bus.log("传输: 角色监听收到 CANCEL — " + reason);
                    notifyLinkFailedOnce(link, new XferError(XferError.Stage.TRANSFER,
                            XferError.CANCELLED, "对方取消: " + reason));
                    break;
                } else if (f.type == Proto.TYPE_HELLO || f.type == Proto.TYPE_HELLO_ACK) {
                    Bus.log("传输: 角色监听忽略握手帧 " + f.type);
                } else {
                    Bus.log("传输: 角色监听忽略帧类型 " + f.type);
                }
                if (mStashedSocket != link) break;
                if (settleRoleListener(link)) break;
            }
        } finally {
            if (mRoleListenerThread == Thread.currentThread()) mRoleListenerThread = null;
            //pri added by zhuyang,Exit sync: a closed socket with no CANCEL still means the peer left - report PEER_GONE,20261020,start
            if (peerClosed && !handedOff && mRoleListenerAbort != link && !mRoundAborted.get()
                    && mRoundLive && mStashedSocket == link && mPeerSocket != link) {
                Bus.log("传输: 角色监听连接已断开,判定对方已退出");
                notifyLinkFailedOnce(link, new XferError(XferError.Stage.CONNECT,
                        XferError.PEER_GONE, "对端在报价前已断开"));
            }
            //pri added by zhuyang,Exit sync: a closed socket with no CANCEL still means the peer left - report PEER_GONE,20261020,end
            // A normal exit means this link has no owner any more. An abort hands it to another reader.
            if (mRoleListenerAbort != link && mStashedSocket == link && mPeerSocket != link) {
                mStashedSocket = null;
                closeQuietly(link);
            }
            Bus.log("传输: 角色监听结束");
        }
    }

    //pri modify by zhuyang,Exit sync: the listener is a full frame reader (ROLE/META/PING/PONG/CANCEL/HELLO) and reports a closed peer at once,20261020,end
    //pri added by zhuyang,Role listener: the only reader of a stashed link until it yields or receives,20261017,end

    private void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }

    private void closeQuietly(Link link) {
        try { if (link != null) link.s.close(); } catch (Exception ignored) {}
    }

    //pri added by zhuyang,Exit sync: the farewell listener is a ServerSocket, closed on its own timer,20261020
    private void closeQuietly(ServerSocket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
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
        int skippedFiles = 0; //pri added by zhuyang,Images already completed by sha256 and answered with TYPE_SKIP,20261019
        //pri added by zhuyang,Verified images of this round; MediaStore inserts happen only after DONE,20261019
        final java.util.ArrayList<PendingSave> pendingSaves = new java.util.ArrayList<PendingSave>();
        String lastFailReason = null;
        boolean metaInfoSeen = false;
        Proto.FileBegin curFile = null;
        OutputStream curOut = null;
        MessageDigest curMd = null;
        File curPart = null;
        int curSkipIndex = -1; //pri added by zhuyang,Index answered with TYPE_SKIP; late DATA is discarded without writing,20261019
        long curBytes = 0;
        final String sid = storeSid(); //pri modify by zhuyang,Batch 4: .part paths are still scoped to this session,20261019
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
                        //pri modify by zhuyang,Exit sync: a close/reset at any phase means the peer left; only a bad frame is a protocol error,20261020,start
                        final boolean noDataYet = expect == 0 && !chunkedStarted;
                        if (noDataYet || isPeerDrop(e)) {
                            error = new XferError(XferError.Stage.CONNECT, XferError.PEER_GONE,
                                    "对端已断开(" + e + ")");
                        } else {
                            error = link.linkDead.get() ? linkError(link, XferError.Stage.TRANSFER)
                                    : new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                        }
                        //pri modify by zhuyang,Exit sync: a close/reset at any phase means the peer left; only a bad frame is a protocol error,20261020,end
                        break;
                    }
                    //pri modify by zhuyang,Offer wait stays at 20s; the data phase tightens to 10s (a chunk or PING must arrive),20261017,end
                    if (f == null) {
                        Bus.log("传输: 对端已关闭连接");
                        //pri modify by zhuyang,Exit sync: EOF at any phase means the peer exited, not a protocol fault,20261020,start
                        readError = "对端已断开";
                        error = new XferError(XferError.Stage.CONNECT, XferError.PEER_GONE, readError);
                        //pri modify by zhuyang,Exit sync: EOF at any phase means the peer exited, not a protocol fault,20261020,end
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
                        ChunkResult cr = closeChunk(curFile, curOut, curMd, curPart, curBytes,
                                sid, Math.max(expect, curFile.total), all, pendingSaves);
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
                    curFile = null;
                    curOut = null;
                    curMd = null;
                    curPart = mStore.partFile(sid, fi.index);
                    curSkipIndex = -1;
                    curBytes = 0;
                    expectedSeq = 0;
                    chunkedStarted = true;
                    //pri added by zhuyang,Exit sync: bytes are about to flow, relax the dead check to 5s,20261020
                    link.hbTransferPhase = true;
                    //pri modify by zhuyang,Batch 4: decide by content hash, never by the index bitmap,20261019,start
                    boolean skip = false;
                    if (fi.sha256 != null) {
                        File known = mStore.completedFile(fi.sha256);
                        if (known != null) {
                            skip = true;
                            skippedFiles++;
                            curSkipIndex = fi.index;
                            sendSkip(link, fi.index);
                            Bus.log("传输: 第 " + (fi.index + 1) + " 张命中已完成(sha256="
                                    + fi.sha256.substring(0, 12) + "),回 TYPE_SKIP,不再接收 DATA");
                            Bus.log("传输: 第 " + (fi.index + 1) + " 张为重复图:仅跳过网络传输,仍插入一份新的相册记录");
                            pendingSaves.add(new PendingSave(fi.index, known, fi.name,
                                    resolveMime(fi.mime, fi.name), fi.sha256, true));
                            submitChunkPreview(known, fi.index + 1, Math.max(expect, fi.total), all);
                        } else if (curPart.exists() && fi.size >= 0 && curPart.length() == fi.size) {
                            //pri added by zhuyang,Batch 4: a matching .part from an earlier connection counts as already received,20261017
                            try {
                                if (fi.sha256.equalsIgnoreCase(TransferStore.sha256Of(curPart))) {
                                    skip = true;
                                    skippedFiles++;
                                    curSkipIndex = fi.index;
                                    sendSkip(link, fi.index);
                                    Bus.log("传输: 第 " + (fi.index + 1)
                                            + " 张 .part 已完整且哈希匹配,回 TYPE_SKIP;仅跳过网络传输,仍插入一份新的相册记录");
                                    pendingSaves.add(new PendingSave(fi.index, curPart, fi.name,
                                            resolveMime(fi.mime, fi.name), fi.sha256, false));
                                    submitChunkPreview(curPart, fi.index + 1, Math.max(expect, fi.total), all);
                                }
                            } catch (Exception e) {
                                Bus.log("传输: 检查已有 .part 失败,重新接收: " + e);
                            }
                        }
                    }
                    if (!skip) {
                        curFile = fi;
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
                    //pri modify by zhuyang,Batch 4: decide by content hash, never by the index bitmap,20261019,end
                    if (fi.sha256 == null) Bus.log("传输: 第 " + (fi.index + 1) + " 张未携带 h=,跳过哈希校验(旧对端)");
                    progress.beginFile(fi.index + 1, fi.total, fi.size);
                    postProgress(progress, false);
                    if (skip) {
                        Bus.log("传输: 跳过第 " + (fi.index + 1) + "/" + fi.total + " 张(内容已完成,不发 DATA)");
                    } else {
                        Bus.log("传输: 开始接收第 " + (fi.index + 1) + "/" + fi.total + " 张 " + fi.size + " 字节 " + fi.mime);
                    }
                    continue;
                }
                if (type == Proto.TYPE_DATA) {
                    if (curFile == null) {
                        if (curSkipIndex >= 0) {
                            //pri added by zhuyang,Batch 4: tolerate a DATA race after TYPE_SKIP: discard it but count wire bytes,20261019
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
                            expectedSeq++;
                            rxBytes += frame.length - 4L;
                            if (seq == 0) {
                                Bus.log("传输: 第 " + (curSkipIndex + 1)
                                        + " 张已回 TYPE_SKIP,对端仍在竞态发送,丢弃后续 DATA");
                            }
                            continue;
                        }
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
                        ChunkResult cr = closeChunk(curFile, curOut, curMd, curPart, curBytes,
                                sid, Math.max(expect, curFile.total), all, pendingSaves);
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
                        curBytes = 0;
                    }
                    curSkipIndex = -1;
                    //pri modify by zhuyang,Batch 4: TYPE_SKIP images count as completed; all-skipped needs no DATA,20261019
                    int announced = expect > 0 ? expect : receivedFiles + skippedFiles;
                    boolean allSkipped = announced > 0 && skippedFiles == announced;
                    boolean countOk = done.count == announced;
                    if ((!chunkedStarted && !allSkipped) || !countOk || done.totalBytes != rxBytes) {
                        readError = "完成校验失败(完成帧 " + done.count + " 张/" + done.totalBytes
                                + "B,实收 " + receivedFiles + " 张/" + rxBytes + "B,预告 " + announced
                                + " 张,跳过 " + skippedFiles + " 张)";
                        error = new XferError(XferError.Stage.TRANSFER, XferError.PROTOCOL_ERROR, readError);
                        Bus.log("传输: " + readError);
                        sendCancel(link, "完成校验失败");
                        break;
                    }
                    //pri modify by zhuyang,Batch 4: TYPE_SKIP images count as completed; all-skipped needs no DATA,20261019
                    doneReceived = true;
                    if (allSkipped) {
                        Bus.log("传输: 本轮 " + announced + " 张全部命中已完成(按 sha256),无数据传输");
                    }
                    final int okTotal = okFiles + skippedFiles;
                    if (failedFiles > 0 && okTotal == 0) {
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
                            + " 字节,成功 " + okTotal + " 张(其中跳过 " + skippedFiles + " 张),失败 "
                            + failedFiles + " 张,已回 DONE_ACK(" + failedFiles + ")");
                    if (!metaInfoSeen) Bus.log("传输: 未收到 META_INFO,跳过存储预检(旧对端)");
                    mDoneNotified = true;
                    //pri modify by zhuyang,Save the whole round in index order before the viewer is notified,20261019
                    enqueueDeliveryAndDone(pendingSaves, all);
                    if (failedFiles > 0) {
                        //pri added by zhuyang,Batch 4: partial failure -> "成功 N 张，失败 M 张",20261017,start
                        final int ok = okTotal;
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
                        + "B,FULL " + total + "B),成功 " + (okFiles + skippedFiles) + " 张(其中跳过 "
                        + skippedFiles + " 张) 失败 " + failedFiles
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
                    //pri modify by zhuyang,Exit sync: a dead peer is a silent collapse, a live peer with no offer keeps the long timeout,20261020,start
                    XferError cerr = error;
                    if (cerr == null) {
                        cerr = link.linkDead.get() || link.s.isClosed()
                                ? new XferError(XferError.Stage.CONNECT, XferError.PEER_GONE, "对端已断开")
                                : new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT, "对方未开始发送(报价超时)");
                    }
                    progress.fail(cerr.detail);
                    postProgress(progress, true);
                    if (cerr.code == XferError.PEER_GONE || cerr.code == XferError.CANCELLED) {
                        notifyLinkFailedOnce(link, cerr);
                    } else {
                        notifyOfferExpiredOnce(link, cerr);
                    }
                    //pri modify by zhuyang,Exit sync: a dead peer is a silent collapse, a live peer with no offer keeps the long timeout,20261020,end
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
                    //pri modify by zhuyang,Exit sync: a dead peer is a silent collapse, never an offer-timeout banner,20261020,start
                    XferError cerr = error;
                    if (cerr == null) {
                        cerr = link.linkDead.get() || link.s.isClosed()
                                ? new XferError(XferError.Stage.CONNECT, XferError.PEER_GONE, "对端已断开(" + e + ")")
                                : new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT, "对方未开始发送(接收异常)");
                    }
                    progress.fail(cerr.detail);
                    postProgress(progress, true);
                    if (cerr.code == XferError.PEER_GONE || cerr.code == XferError.CANCELLED) {
                        notifyLinkFailedOnce(link, cerr);
                    } else {
                        notifyOfferExpiredOnce(link, cerr);
                    }
                    //pri modify by zhuyang,Exit sync: a dead peer is a silent collapse, never an offer-timeout banner,20261020,end
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

    //pri added by zhuyang,One verified image held until the whole round completes,20261019,start
    private static final class PendingSave {
        final int index;
        final File file;
        final String name;
        final String mime;
        final String sha256;
        final boolean shared; // already lives in the completed store from an earlier round

        PendingSave(int index, File file, String name, String mime, String sha256, boolean shared) {
            this.index = index;
            this.file = file;
            this.name = name;
            this.mime = mime;
            this.sha256 = sha256;
            this.shared = shared;
        }
    }
    //pri added by zhuyang,One verified image held until the whole round completes,20261019,end

    //pri modify by zhuyang,Batch 4: verify one image and hold it for end-of-round delivery; no MediaStore insert here,20261019,start
    private ChunkResult closeChunk(Proto.FileBegin fi, OutputStream out, MessageDigest md, File part,
                                   long bytes, String sid, int total,
                                   java.util.ArrayList<Bitmap> all,
                                   java.util.ArrayList<PendingSave> pending) {
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        if (fi == null) return new ChunkResult(false, "缺少图片元信息");
        int human = fi.index + 1;
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
        pending.add(new PendingSave(fi.index, part, fi.name, resolveMime(fi.mime, fi.name),
                fi.sha256, false));
        submitChunkPreview(part, human, Math.max(fi.total, total), all);
        return new ChunkResult(true, null);
    }
    //pri modify by zhuyang,Batch 4: verify one image and hold it for end-of-round delivery; no MediaStore insert here,20261019,end

    //pri added by zhuyang,Decode a verified file for the receive card; the album insert waits for DONE,20261019,start
    private void submitChunkPreview(final File part, final int idx, final int exp,
                                    final java.util.ArrayList<Bitmap> all) {
        final int bytes = (int) Math.min(Integer.MAX_VALUE, part.length());
        mIo.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bmp = decode(part);
                synchronized (all) { all.add(bmp); }
                mMain.post(new Runnable() {
                    @Override public void run() { mListener.onImageReceived(idx, exp, bmp, bytes, false); }
                });
            }
        });
    }
    //pri added by zhuyang,Decode a verified file for the receive card; the album insert waits for DONE,20261019,end

    //pri added by zhuyang,Insert every verified image of this round in index order, then notify the viewer,20261019,start
    private void enqueueDeliveryAndDone(final java.util.ArrayList<PendingSave> pending,
                                        final java.util.ArrayList<Bitmap> all) {
        mIo.execute(new Runnable() {
            @Override public void run() {
                deliverPending(pending);
                final java.util.List<Bitmap> list;
                synchronized (all) { list = new java.util.ArrayList<Bitmap>(all); }
                mMain.post(new Runnable() {
                    @Override public void run() { mListener.onTransferDone(list); }
                });
            }
        });
    }

    private void deliverPending(java.util.ArrayList<PendingSave> pending) {
        java.util.Collections.sort(pending, new java.util.Comparator<PendingSave>() {
            @Override public int compare(PendingSave a, PendingSave b) { return a.index - b.index; }
        });
        for (PendingSave p : pending) {
            File file = p.file;
            boolean moved = false;
            if (!p.shared && p.sha256 != null) {
                try {
                    File stored = mStore.persistCompleted(p.sha256, file);
                    if (stored != null) {
                        file = stored;
                        moved = true;
                    }
                } catch (Exception e) {
                    Bus.log("传输: 第 " + (p.index + 1) + " 张保存去重副本失败(仍入库): " + e);
                }
            }
            Uri savedUri = saveFileToGallery(file, p.name, p.mime);
            if (savedUri != null) {
                mSavedUris.add(savedUri);
                if (mFirstSavedUri == null) mFirstSavedUri = savedUri;
                if (p.sha256 != null && moved) mStore.markCompleted(p.sha256, file);
                Bus.log("传输: 第 " + (p.index + 1) + " 张已存入相册"
                        + (p.name == null ? "" : "(" + p.name + ")"));
            } else {
                Bus.log("传输: 第 " + (p.index + 1) + " 张存相册失败");
                //pri added by zhuyang,Do not keep a completed copy that never reached the gallery,20261019
                if (moved) {
                    try { file.delete(); } catch (Throwable ignored) {}
                }
            }
        }
    }
    //pri added by zhuyang,Insert every verified image of this round in index order, then notify the viewer,20261019,end

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
        //pri added by zhuyang,Exit sync: the role listener and the swipe path may race here; only one may adopt the link,20261020
        synchronized (this) {
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
    }
    //pri added by zhuyang,Promote the peer-initiated stashed link to the send channel (P2P owner case),20261017,end

    //pri added by zhuyang,Tap-time preconnect: turn an early-built stashed link into the send channel and let TapService start the offer,20261019,start
    private boolean adoptTapTimeSendLink() {
        Link stashed = mStashedSocket;
        if (!isAlive(stashed) || mPeerSocket == stashed) return false;
        Link promoted = promoteStashedToSend();
        if (promoted == null) return false;
        Bus.log("承载: 复用碰触时已建立的热点链路,跳过重新加入");
        mMain.post(new Runnable() {
            @Override public void run() { mListener.onP2pReady(false, null); }
        });
        return true;
    }
    //pri added by zhuyang,Tap-time preconnect: turn an early-built stashed link into the send channel and let TapService start the offer,20261019,end

    //pri added by zhuyang,The gallery names the photo it has open; the receive card uses it instead of the first,20261019
    public void setPreviewUri(Uri uri) {
        mPreviewUri = uri;
    }

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
                    //pri modify by zhuyang,Batch 4: resume is negotiated per FILE_BEGIN via sha256; the sender no longer skips by have=,20261019,start
                    long planned = 0;
                    for (int i = 0; i < uris.size(); i++) {
                        long size = imageSize(uris.get(i));
                        if (size > 0) planned += size;
                    }
                    //pri modify by zhuyang,Batch 4: resume is negotiated per FILE_BEGIN via sha256; the sender no longer skips by have=,20261019,end
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
                            //pri added by zhuyang,Use the opened photo only when it is part of this round's selection,20261019,start
                            final boolean picked = mPreviewUri != null && uris.contains(mPreviewUri);
                            Uri previewUri = picked ? mPreviewUri : uris.get(0);
                            byte[] thumb = thumbnail(previewUri);
                            if (thumb != null) {
                                writeBusinessFrame(send, Proto.TYPE_PREVIEW, thumb.length, thumb);
                                Bus.log("传输: 卡片预览取" + (picked ? "用户点开的那张" : "第一张")
                                        + "," + thumb.length + " 字节");
                            }
                            //pri added by zhuyang,Use the opened photo only when it is part of this round's selection,20261019,end
                        }
                        send.out.flush();
                    } finally {
                        mWriteLock.unlock();
                    }
                    Bus.log("传输: 已报价(" + uris.size() + " 张,计划 " + planned
                            + " 字节 + 预览),等对方「接收/拒绝」…("
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
                    //pri added by zhuyang,Exit sync: the peer sent CANCEL while deciding - end the round silently on this side too,20261020,start
                    if (verdict == VERDICT_CANCELLED) {
                        stopHeartbeat(send);
                        closeQuietly(send);
                        if (mSendSocket == send) mSendSocket = null;
                        if (mStashedSocket == send) mStashedSocket = null;
                        releaseHotspotLater();
                        fail(new XferError(XferError.Stage.TRANSFER, XferError.CANCELLED, "对方退出本次传输"));
                        return;
                    }
                    //pri added by zhuyang,Exit sync: the peer sent CANCEL while deciding - end the round silently on this side too,20261020,end
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
                    //pri added by zhuyang,Exit sync: data is flowing, so a slow link gets the 5s dead window,20261020
                    send.hbTransferPhase = true;
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
                        Uri uri = uris.get(i);
                        String mime = mCtx.getContentResolver().getType(uri);
                        if (mime == null) mime = "image/jpeg";
                        //pri added by zhuyang,Send the source display name so the receiver can keep it,20261019
                        String displayName = displayNameOf(uri);
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
                            byte[] meta = Proto.buildFileBegin(i, uris.size(), fileSize, mime, hash, displayName);
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
                            //pri added by zhuyang,Batch 4: the receiver answers TYPE_SKIP for content it already has,20261019
                            if (waitSkip(send, i)) {
                                sentCount++;
                                Bus.log("传输: 第 " + (i + 1) + "/" + uris.size()
                                        + " 张命中对端已完成(sha256=" + hash.substring(0, 12)
                                        + "),跳过 DATA,计入 DONE 张数");
                                continue;
                            }
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
                        //pri added by zhuyang,Exit sync: a stall is a real transfer failure; any other mid-stream close is a peer exit,20261020,start
                        XferError le = send.failError;
                        if (le != null && le.code == XferError.TRANSFER_STALL) throw new FatalXferException(le);
                        throw new FatalXferException(new XferError(XferError.Stage.CONNECT,
                                XferError.PEER_GONE, "发送阶段对端已退出"));
                        //pri added by zhuyang,Exit sync: a stall is a real transfer failure; any other mid-stream close is a peer exit,20261020,end
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
                            //pri added by zhuyang,Exit sync: a closed socket while waiting for DONE_ACK is a silent peer exit,20261020
                            throw new FatalXferException(new XferError(XferError.Stage.CONNECT,
                                    XferError.PEER_GONE, "等待完成确认时对端已退出"));
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
                        } else if (isPeerDrop(e)) {
                            //pri added by zhuyang,Exit sync: a reset/broken pipe mid-transfer means the peer exited,20261020
                            err = new XferError(XferError.Stage.CONNECT, XferError.PEER_GONE,
                                    "发送中断(对端已断开)");
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

    //pri added by zhuyang,Batch 4: after FILE_BEGIN, wait briefly for TYPE_SKIP before streaming DATA,20261019,start
    private boolean waitSkip(Link link, int index) throws java.io.IOException {
        long deadline = SystemClock.elapsedRealtime() + SKIP_WAIT_MS;
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                int remaining = (int) (deadline - SystemClock.elapsedRealtime());
                if (remaining <= 0) break;
                link.s.setSoTimeout(remaining);
                Proto.Frame f;
                try {
                    f = Proto.readFrame(link.in, remaining, Proto.MAX_FRAME_LEN);
                } catch (java.net.SocketTimeoutException te) {
                    return false;
                }
                if (f == null) {
                    //pri added by zhuyang,Exit sync: the peer closed while this file was being announced - it is gone,20261020
                    throw new FatalXferException(new XferError(XferError.Stage.TRANSFER,
                            XferError.PEER_GONE, "对端在接收文件时已退出"));
                }
                if (f.type == Proto.TYPE_SKIP) {
                    int idx = Proto.parseSkip(f.data);
                    if (idx == index) return true;
                    Bus.log("传输: 收到非当前索引的 TYPE_SKIP(" + idx + "),忽略");
                    continue;
                }
                if (f.type == Proto.TYPE_PING) {
                    replyPong(link);
                    continue;
                }
                if (f.type == Proto.TYPE_PONG) continue;
                if (f.type == Proto.TYPE_CANCEL) {
                    String reason = new String(f.data, java.nio.charset.StandardCharsets.UTF_8);
                    throw new FatalXferException(new XferError(XferError.Stage.TRANSFER,
                            XferError.CANCELLED, "对方取消: " + reason));
                }
                Bus.log("传输: 等待 TYPE_SKIP 时忽略帧类型 " + f.type);
            }
            return false;
        } finally {
            try { link.s.setSoTimeout(0); } catch (Exception ignored) {}
        }
    }
    //pri added by zhuyang,Batch 4: after FILE_BEGIN, wait briefly for TYPE_SKIP before streaming DATA,20261019,end

    //pri added by zhuyang,Exit sync: recognise a socket reset / broken pipe anywhere in the cause chain,20261020
    private static boolean isPeerDrop(Throwable t) {
        for (int i = 0; t != null && i < 5; i++, t = t.getCause()) {
            if (t instanceof java.net.SocketException || t instanceof java.io.EOFException) return true;
        }
        return false;
    }
    //pri added by zhuyang,Exit sync: recognise a socket reset / broken pipe anywhere in the cause chain,20261020

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

    //pri added by zhuyang,Query the source display name for FILE_BEGIN name=; null when unavailable,20261019,start
    private String displayNameOf(Uri uri) {
        if (uri == null) return null;
        android.database.Cursor c = null;
        try {
            c = mCtx.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Throwable t) {
            Bus.log("传输: 读取源文件名失败 " + t);
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }
    //pri added by zhuyang,Query the source display name for FILE_BEGIN name=; null when unavailable,20261019,end
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
    //pri modify by zhuyang,Read verdict frames in a loop: PING/ROLE/META are skipped, CANCEL and a closed socket end the wait at once,20261020,start
    private int readVerdict(Link link, final int round) {
        int verdict = 0;
        boolean peerClosed = false;
        String cancelReason = null;
        long deadline = SystemClock.elapsedRealtime() + VERDICT_WAIT_MS;
        try {
            while (mRunning) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new java.net.SocketTimeoutException();
                link.s.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
                Proto.Frame f = Proto.readFrame(link.in, 0, Proto.MAX_FRAME_LEN);
                if (f == null) {
                    Bus.log("传输: 回执连接已关闭");
                    peerClosed = true;
                    break;
                }
                //pri added by zhuyang,Answer the peer heartbeat while the user is deciding,20261017,start
                if (f.type == Proto.TYPE_PING) {
                    replyPong(link);
                    continue;
                }
                if (f.type == Proto.TYPE_PONG) {
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
                //pri added by zhuyang,Exit sync: CANCEL is a verdict - the peer exited while this page waited,20261020,start
                if (f.type == Proto.TYPE_CANCEL) {
                    cancelReason = new String(f.data, java.nio.charset.StandardCharsets.UTF_8);
                    Bus.log("传输: 等回执时收到 CANCEL — " + cancelReason);
                    verdict = VERDICT_CANCELLED;
                    break;
                }
                //pri added by zhuyang,Exit sync: CANCEL is a verdict - the peer exited while this page waited,20261020,end
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
            peerClosed = true;
        }
        if (mYielded) return VERDICT_YIELDED;
        synchronized (mPreReadFrames) { mPreReadFrames.clear(); } //pri added by zhuyang,No yield: the peer's offer frames are stale,20261017
        if (verdict == VERDICT_CANCELLED) {
            Bus.log("传输: 对端已退出本次传输 — " + cancelReason);
            return VERDICT_CANCELLED;
        }
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
        } else if (peerClosed || (link.linkDead.get() && link.failReason != null)) {
            //pri modify by zhuyang,Exit sync: a closed socket while waiting for the verdict is a silent peer exit,20261020
            notifyLinkFailedOnce(link, new XferError(XferError.Stage.CONNECT, XferError.PEER_GONE,
                    "等待回执时对端已退出"));
        } else {
            fail(new XferError(XferError.Stage.OFFER, XferError.OFFER_TIMEOUT,
                    "等待对方「接收/拒绝」超时(" + (VERDICT_WAIT_MS / 1000) + "s)"));
            mMain.post(new Runnable() {
                @Override public void run() { mListener.onVerdictTimeout(); }
            });
        }
        return verdict;
    }
    //pri modify by zhuyang,Read verdict frames in a loop: PING/ROLE/META are skipped, CANCEL and a closed socket end the wait at once,20261020,end

    //pri added by zhuyang,Receiver: write the ACCEPT verdict back (images follow from the peer),20261014
    public void confirmReceived() {
        writeVerdict(Proto.TYPE_ACCEPT);
    }

    //pri added by zhuyang,Receiver: write the REJECT verdict back (no image was ever sent),20261014
    public void rejectTransfer() {
        writeVerdict(Proto.TYPE_REJECT);
    }

    //pri added by zhuyang,Exit sync: this phone left the page mid-round - tell the peer and drop every link,20261020,start
    //pri modify by zhuyang,Exit sync: CANCEL must reach the peer on every live link, not only on the one this side happens to pick,20261020,start
    public void abortRound(String why) {
        if (!mRoundAborted.compareAndSet(false, true)) return;
        final String reason = (why == null || why.isEmpty()) ? "本机退出" : why;
        final java.util.List<Link> links = liveLinks();
        //pri added by zhuyang,Exit sync: hold the radio open at once, so no teardown path kills the farewell connect,20261020
        mFarewellActive = true;
        Bus.log("传输: 本机退出本轮" + (links.isEmpty()
                ? "(无可用 TCP 链路,只做本地收尾)"
                : ",在 " + links.size() + " 条活链路上通知对端 CANCEL — " + reason));
        final XferError cancelled = new XferError(XferError.Stage.TRANSFER, XferError.CANCELLED, reason);
        final int round = mRound;
        //pri added by zhuyang,The write must leave the main thread: a socket write there throws NetworkOnMainThreadException,20261020
        new Thread(new Runnable() { @Override public void run() {
            for (Link link : links) sendCancel(link, reason);
            stopHeartbeat(mPeerSocket);
            stopHeartbeat(mStashedSocket);
            stopHeartbeat(mSendSocket);
            closeAsCancelled(mPeerSocket, cancelled);
            mPeerSocket = null;
            closeAsCancelled(mStashedSocket, cancelled);
            mStashedSocket = null;
            closeAsCancelled(mSendSocket, cancelled);
            mSendSocket = null;
            mRoleListenerThread = null;
            mRoleListenerAbort = null;
            //pri added by zhuyang,Exit sync: keep the listener for a farewell window so a late peer gets HELLO_ACK st=out,20261020,start
            final ServerSocket farewell = mServer;
            new Thread(new Runnable() { @Override public void run() {
                try { Thread.sleep(ABORT_FAREWELL_MS); } catch (InterruptedException ignored) {}
                //pri added by zhuyang,Exit sync: a new round may already own this listener; leave its radio alone,20261020
                if (round != mRound) return;
                mFarewellActive = false;
                closeQuietly(farewell);
                if (mServer == farewell) mServer = null;
                if (mApOwner && (mHotspotActive || mHotspotReservation != null)) {
                    Bus.log("热点: 本轮退出已满 " + (ABORT_FAREWELL_MS / 1000) + "s,释放 AP");
                    closeHotspot();
                } else if (!mApOwner) {
                    restoreWifiAfterJoin();
                }
            }}, "abort-farewell").start();
            //pri added by zhuyang,Exit sync: keep the listener for a farewell window so a late peer gets HELLO_ACK st=out,20261020,end
            fail(cancelled);
        }}, "abort-round").start();
    }
    //pri modify by zhuyang,Exit sync: CANCEL must reach the peer on every live link, not only on the one this side happens to pick,20261020,end

    //pri added by zhuyang,Exit sync: snapshot of the distinct live links of this round,20261020
    private java.util.List<Link> liveLinks() {
        java.util.List<Link> out = new java.util.ArrayList<Link>(3);
        addLiveLink(out, mSendSocket);
        addLiveLink(out, mPeerSocket);
        addLiveLink(out, mStashedSocket);
        return out;
    }

    private static void addLiveLink(java.util.List<Link> out, Link link) {
        if (!isAlive(link) || out.contains(link)) return;
        out.add(link);
    }
    //pri added by zhuyang,Exit sync: snapshot of the distinct live links of this round,20261020

    //pri added by zhuyang,Mark the link with the coded reason before closing so its writer/reader does not report a second cause,20261020
    private void closeAsCancelled(Link link, XferError err) {
        if (link == null) return;
        link.linkDead.set(true);
        link.failError = err;
        link.failReason = err.detail;
        closeQuietly(link);
    }
    //pri added by zhuyang,Exit sync: this phone left the page mid-round - tell the peer and drop every link,20261020,end

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
                //pri added by zhuyang,Exit sync: a farewell window owns the radio; its own timer releases it,20261020
                if (mFarewellActive) return;
                restoreWifiAfterJoin();
                if (hg == mHotspotGen && (mHotspotActive || mHotspotReservation != null)) {
                    Bus.log("热点: 本轮传输结束,释放 AP owner 热点");
                    closeHotspot();
                }
            }
        }, "hotspot-release").start();
        //pri added by zhuyang,1s grace so the peer can read DONE_ACK before the AP goes away; generations cancel stale runs,20261019,end
    }

    //pri modify by zhuyang,Decode through ImageDecoder on API 28+ so EXIF orientation is applied; BitmapFactory stays as the API 27 fallback,20261019,start
    private byte[] thumbnail(Uri uri) {
        try {
            Bitmap bmp;
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = ImageDecoder.createSource(mCtx.getContentResolver(), uri);
                bmp = ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info,
                                                ImageDecoder.Source source) {
                        int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                        int sample = 1;
                        while (max / sample > 900) sample *= 2;
                        if (sample > 1) decoder.setTargetSampleSize(sample);
                        //pri added by zhuyang,Software bitmap: JPEG compress does not work on hardware bitmaps,20261019
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    }
                });
            } else {
                Bus.log("预览: API " + Build.VERSION.SDK_INT + " 无 ImageDecoder,回退 BitmapFactory(不处理 EXIF)");
                InputStream in = mCtx.getContentResolver().openInputStream(uri);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(in, null, o);
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                int scale = 1;
                while (o.outWidth / scale > 900 || o.outHeight / scale > 900) scale *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = scale;
                InputStream in2 = mCtx.getContentResolver().openInputStream(uri);
                bmp = BitmapFactory.decodeStream(in2, null, o2);
                try { if (in2 != null) in2.close(); } catch (Exception ignored) {}
            }
            if (bmp == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            Bus.log("预览: " + bmp.getWidth() + "x" + bmp.getHeight() + ", " + bos.size() + " 字节(JPEG 82%)");
            return bos.toByteArray();
        } catch (Throwable t) {
            Bus.log("预览: 解码失败 " + t);
            return null;
        }
    }
    //pri modify by zhuyang,Decode through ImageDecoder on API 28+ so EXIF orientation is applied; BitmapFactory stays as the API 27 fallback,20261019,end

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
        mRoundFailed.set(true); //pri added by zhuyang,A later liveness check must not overwrite this round's failure,20261020
        mSenderWaiting = false;
        mDiscovering = false; //pri added by zhuyang,Batch 5: a failed round stops its connect stage,20261017
        Bus.log("传输: [失败] " + err.code + "(" + XferError.nameOf(err.code) + ") stage=" + err.stage
                + " — " + (err.detail == null ? "" : err.detail));
        //pri modify by zhuyang,A terminal failure with no live link must not leave the AP/joined Wi-Fi behind,20261019,start
        //pri modify by zhuyang,Exit sync: not while a farewell window is open - the radio is carrying st=out to a late peer,20261020,start
        if (!hasLiveLink() && !mFarewellActive) {
            restoreWifiAfterJoin();
            if (mHotspotActive || mHotspotReservation != null) {
                closeHotspot();
                Bus.log("热点: 本轮失败,已释放热点");
            }
        }
        //pri modify by zhuyang,Exit sync: not while a farewell window is open - the radio is carrying st=out to a late peer,20261020,end
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

    //pri added by zhuyang,Exit sync: the peer is gone, so this phone closes silently; at most once per round,20261020,start
    private void reportPeerGone(String why) {
        if (mRoundAborted.get() || mRoundFailed.get() || hasLiveLink()) return;
        //pri added by zhuyang,Liveness probe: this phone already left the round, nothing to close,20261020
        if (!mRoundLive) return;
        if (!mPeerGoneReported.compareAndSet(false, true)) return;
        Bus.log("传输: 判定对方已退出 — " + why);
        fail(new XferError(XferError.Stage.CONNECT, XferError.PEER_GONE, why));
    }
    //pri added by zhuyang,Exit sync: the peer is gone, so this phone closes silently; at most once per round,20261020,end

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

    //pri modify by zhuyang,Use the peer's original name/MIME and dedupe inside Pictures/NfcTapDemo,20261019,start
    private Uri saveFileToGallery(File file, String name, String mime) {
        if (Build.VERSION.SDK_INT < 29) return null;
        Uri u = null;
        try {
            String resolvedMime = resolveMime(mime, name);
            String base = Proto.sanitizeFileName(name);
            if (base == null) base = "nfctap_" + System.currentTimeMillis();
            String displayName = ensureExtension(base, resolvedMime);
            String dir = android.os.Environment.DIRECTORY_PICTURES + "/NfcTapDemo";
            displayName = uniqueDisplayName(displayName, dir);
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName);
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, resolvedMime);
            v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, dir);
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
            Bus.log("传输: 已写入相册 " + displayName + " (" + resolvedMime + ")");
            return u;
        } catch (Throwable t) {
            Bus.log("传输: 存相册失败 " + t);
            if (u != null) {
                try { mCtx.getContentResolver().delete(u, null, null); } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    //pri added by zhuyang,Empty MIME falls back to the name suffix and then JPEG,20261019,start
    private static String resolveMime(String mime, String name) {
        if (mime != null && !mime.trim().isEmpty()) return mime;
        String guessed = guessMime(name);
        return guessed == null ? "image/jpeg" : guessed;
    }

    private static String guessMime(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return null;
    }

    private static String extensionForMime(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.US);
        if ("image/png".equals(m)) return ".png";
        if ("image/webp".equals(m)) return ".webp";
        return ".jpg";
    }

    private static boolean hasExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1;
    }

    //pri added by zhuyang,Keep the extension consistent with MIME: append one only when the name has none,20261019
    private static String ensureExtension(String name, String mime) {
        return hasExtension(name) ? name : name + extensionForMime(mime);
    }

    //pri added by zhuyang,Append " (n)" before the extension when the album already holds that name,20261019,start
    private String uniqueDisplayName(String name, String dir) {
        if (!galleryNameExists(name, dir)) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 20; i++) {
            String candidate = stem + " (" + i + ")" + ext;
            if (!galleryNameExists(candidate, dir)) return candidate;
        }
        return stem + " (" + System.currentTimeMillis() + ")" + ext;
    }

    private boolean galleryNameExists(String name, String dir) {
        android.database.Cursor c = null;
        try {
            c = mCtx.getContentResolver().query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.Images.Media._ID},
                    android.provider.MediaStore.Images.Media.DISPLAY_NAME + "=? AND ("
                            + android.provider.MediaStore.Images.Media.RELATIVE_PATH + "=? OR "
                            + android.provider.MediaStore.Images.Media.RELATIVE_PATH + "=?)",
                    new String[]{name, dir, dir + "/"}, null);
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            Bus.log("传输: 查询同名照片失败 " + t);
            return false;
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
    }
    //pri added by zhuyang,Empty MIME falls back to the name suffix and then JPEG,20261019,end
    //pri modify by zhuyang,Use the peer's original name/MIME and dedupe inside Pictures/NfcTapDemo,20261019,end
}
