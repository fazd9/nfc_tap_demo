package com.pri.nfctouchdemo.nfc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

//pri added by zhuyang,Bluetooth SPP side channel: control messages only (photos still use Wi-Fi TCP),20261020,start
/**
 * SPP control channel carried by insecure RFCOMM. The Wi-Fi hotspot needs 3-6s before a TCP link
 * exists; this channel is up in well under a second, so a local exit can still reach the peer.
 *
 * Role convention (both ends must agree): the NFC card side (AP owner) is the SPP server, the
 * reader side is the client and connects to the peer MAC carried by the NFC payload (`bm`).
 */
public final class BtControl {

    public interface Listener {
        void onControl(String msg);
        void onBtDown(String why);
    }

    //pri added by zhuyang,Custom UUID echoed by the NFC AID F05052494E4643,20261020
    public static final UUID SERVICE_UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb");
    private static final String SDP_NAME = "TapShareControl";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    //pri added by zhuyang,Android forces encryption on RFCOMM between SM4 phones, so the first connection also runs SSP; extend the wait only while a bond is being formed,20261020
    private static final int CONNECT_TIMEOUT_PAIRING_MS = 8000;
    private static final int SERVER_ACCEPT_TIMEOUT_MS = 10000;
    private static final String BT_CONNECT = "android.permission.BLUETOOTH_CONNECT";
    private static final String BT_SCAN = "android.permission.BLUETOOTH_SCAN";
    private static final BtControl INSTANCE = new BtControl();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static BtControl get() { return INSTANCE; }

    private Context mApp;
    private BluetoothAdapter mAdapter;
    private volatile Listener mListener;
    private volatile BluetoothServerSocket mServer;
    private volatile BluetoothSocket mSocket;
    private volatile OutputStream mOut;
    private volatile boolean mUp;
    private volatile String mRole = "?";
    private volatile long mStartAt;
    private volatile boolean mEverConnected;
    private volatile boolean mConnecting;
    private volatile String mPendingMsg;
    private final Object mGenLock = new Object();
    private final Object mWriteLock = new Object();
    private int mGen;

    private BtControl() {}

    //pri added by zhuyang,Resolve the local MAC once per process and publish it into the NFC payload,20261020
    public void init(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        synchronized (this) {
            if (mApp != null) return;
            mApp = app;
        }
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        mAdapter = bm == null ? null : bm.getAdapter();
        Bus.log("蓝牙: 运行环境 API=" + Build.VERSION.SDK_INT
                + ", BLUETOOTH_CONNECT=" + perm(BT_CONNECT)
                + ", BLUETOOTH_SCAN=" + perm(BT_SCAN));
        String mac = resolveLocalMac();
        if (mac != null) {
            Bus.log("蓝牙: 本机 MAC=" + mac);
        } else {
            Bus.log("蓝牙: 拿不到本机 MAC,payload 不带 bm(对端退回无蓝牙旁路)");
        }
        Bus.setBtMac(mac);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    /** true while a control connection is established and usable. */
    public boolean isUp() {
        return mUp && mSocket != null;
    }

    /** true when send() will go out now or as soon as the in-flight connection completes. */
    public boolean isAvailable() {
        return isUp() || mConnecting;
    }

    //pri added by zhuyang,Decide the SPP role from the NFC role and start a fresh channel,20261020
    public void start(String peerMac, String role) {
        close();
        mRole = role == null ? "?" : role;
        mStartAt = SystemClock.elapsedRealtime();
        if (mAdapter == null) {
            Bus.log("蓝牙: 蓝牙不可用,本轮无控制通道");
            return;
        }
        if (!mAdapter.isEnabled()) {
            mEverConnected = false; //pri added by zhuyang,BT was toggled: the controller may have dropped the link key, so the next connect may pair again,20261020
            Bus.log("蓝牙: 蓝牙未开启,本轮无控制通道");
            return;
        }
        if (!hasPermission(BT_CONNECT)) {
            Bus.log("蓝牙: BLUETOOTH_CONNECT 未授予,本轮无控制通道");
            return;
        }
        if ("card".equals(role)) {
            startServer();
        } else if ("reader".equals(role)) {
            String mac = normalizeMac(peerMac);
            if (mac == null) {
                Bus.log("蓝牙: 对方 MAC 无效,本轮无控制通道");
                return;
            }
            connectTo(mac);
        } else {
            Bus.log("蓝牙: NFC 角色未知(" + role + "),本轮无控制通道");
        }
    }

    //pri added by zhuyang,Card side: publish the SDP record and accept one insecure RFCOMM client,20261020
    public void startServer() {
        final int gen;
        synchronized (mGenLock) {
            gen = ++mGen;
            mConnecting = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                BluetoothServerSocket server = null;
                try {
                    server = mAdapter.listenUsingInsecureRfcommWithServiceRecord(SDP_NAME, SERVICE_UUID);
                    synchronized (mGenLock) {
                        if (gen != mGen) {
                            closeQuietly(server);
                            return;
                        }
                        mServer = server;
                    }
                    Bus.log("蓝牙: SPP 服务端已监听(" + SDP_NAME + "),等待对方连接…");
                    //pri added by zhuyang,accept(timeout) sets SO_RCVTIMEO on the bluetooth socket, which this SELinux domain denies; bound the wait by closing the server instead,20261020,start
                    final BluetoothServerSocket listen = server;
                    final AtomicBoolean gotClient = new AtomicBoolean(false);
                    Thread timeout = new Thread(new Runnable() {
                        @Override public void run() {
                            try { Thread.sleep(SERVER_ACCEPT_TIMEOUT_MS); } catch (InterruptedException ignored) {}
                            if (!gotClient.get() && isCurrent(gen)) {
                                Bus.log("蓝牙: SPP 服务端等待超时(" + SERVER_ACCEPT_TIMEOUT_MS + "ms)");
                                closeQuietly(listen);
                            }
                        }
                    }, "bt-ctl-accept-timeout");
                    timeout.setDaemon(true);
                    timeout.start();
                    //pri added by zhuyang,accept(timeout) sets SO_RCVTIMEO on the bluetooth socket, which this SELinux domain denies; bound the wait by closing the server instead,20261020,end
                    BluetoothSocket s = server.accept();
                    gotClient.set(true);
                    synchronized (mGenLock) {
                        mServer = null;
                        if (gen != mGen || s == null) {
                            closeQuietly(server);
                            closeQuietly(s);
                            return;
                        }
                    }
                    closeQuietly(server);
                    adopt(s, gen);
                } catch (Throwable e) {
                    synchronized (mGenLock) {
                        if (gen == mGen) {
                            mConnecting = false;
                            mPendingMsg = null;
                        }
                    }
                    if (isCurrent(gen)) {
                        Bus.log("蓝牙: SPP 服务端失败("
                                + (SystemClock.elapsedRealtime() - mStartAt) + "ms) " + e);
                        notifyDown("服务端失败: " + e);
                    }
                } finally {
                    synchronized (mGenLock) {
                        if (mServer == server) mServer = null;
                    }
                    closeQuietly(server);
                }
            }
        }, "bt-ctl-server");
        t.setDaemon(true);
        t.start();
    }

    //pri added by zhuyang,Reader side: connect to the card MAC with a hard 3s budget,20261020
    public void connectTo(final String mac) {
        final int gen;
        synchronized (mGenLock) {
            gen = ++mGen;
            mConnecting = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                BluetoothSocket s = null;
                final AtomicBoolean connected = new AtomicBoolean(false);
                try {
                    try { mAdapter.cancelDiscovery(); } catch (Throwable ignored) {}
                    s = mAdapter.getRemoteDevice(colonMac(mac))
                            .createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
                    final BluetoothSocket target = s;
                    //pri added by zhuyang,3s cap; only the very first connection gets 8s because Android runs SSP before an unpaired RFCOMM link can be encrypted,20261020,start
                    Thread watchdog = new Thread(new Runnable() {
                        @Override public void run() {
                            final long started = SystemClock.elapsedRealtime();
                            long deadline = started + CONNECT_TIMEOUT_MS;
                            while (!connected.get()) {
                                long now = SystemClock.elapsedRealtime();
                                if (now >= deadline) {
                                    if (!mEverConnected
                                            && deadline < started + CONNECT_TIMEOUT_PAIRING_MS) {
                                        deadline = started + CONNECT_TIMEOUT_PAIRING_MS;
                                        Bus.log("蓝牙: 首次连接可能需配对,延长等待至 "
                                                + CONNECT_TIMEOUT_PAIRING_MS + "ms");
                                        continue;
                                    }
                                    Bus.log("蓝牙: SPP 连接超时("
                                            + (now - started) + "ms),放弃本轮");
                                    closeQuietly(target);
                                    return;
                                }
                                try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
                            }
                        }
                    }, "bt-ctl-timeout");
                    watchdog.setDaemon(true);
                    watchdog.start();
                    //pri added by zhuyang,3s cap; only the very first connection gets 8s because Android runs SSP before an unpaired RFCOMM link can be encrypted,20261020,end
                    s.connect();
                    connected.set(true);
                    adopt(s, gen);
                } catch (Throwable e) {
                    closeQuietly(s);
                    synchronized (mGenLock) {
                        if (gen == mGen) {
                            mConnecting = false;
                            mPendingMsg = null;
                        }
                    }
                    if (isCurrent(gen)) {
                        Bus.log("蓝牙: SPP 连接 " + mac + " 失败("
                                + (SystemClock.elapsedRealtime() - mStartAt) + "ms) " + e);
                        notifyDown("连接失败: " + e);
                    }
                }
            }
        }, "bt-ctl-connect");
        t.setDaemon(true);
        t.start();
    }

    //pri added by zhuyang,Single-line text message,newline terminated; never blocks the caller,20261020
    public void send(final String msg) {
        if (msg == null) return;
        final String line = msg.endsWith("\n") ? msg : msg + "\n";
        final OutputStream out;
        synchronized (mGenLock) {
            if (!mUp || mSocket == null || mOut == null) {
                //pri added by zhuyang,A CANCEL raised while the first SPP connection is still pairing is queued and flushed on connect,20261020,start
                if (mConnecting) {
                    mPendingMsg = line;
                    Bus.log("蓝牙: 控制通道建立中,消息排队 — " + msg);
                } else {
                    Bus.log("蓝牙: 控制通道不可用,消息未发送 — " + msg);
                }
                //pri added by zhuyang,A CANCEL raised while the first SPP connection is still pairing is queued and flushed on connect,20261020,end
                return;
            }
            out = mOut;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    synchronized (mWriteLock) {
                        out.write(line.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    Bus.log("蓝牙: 已发送控制消息 — " + msg);
                } catch (Throwable e) {
                    Bus.log("蓝牙: 发送控制消息失败 " + e);
                }
            }
        }, "bt-ctl-send");
        t.setDaemon(true);
        t.start();
    }

    //pri added by zhuyang,A new round or a destroyed service drops the channel; no onBtDown for this,20261020
    public void close() {
        final BluetoothServerSocket server;
        final BluetoothSocket socket;
        synchronized (mGenLock) {
            mGen++;
            mUp = false;
            mConnecting = false;
            mPendingMsg = null;
            server = mServer;
            mServer = null;
            socket = mSocket;
            mSocket = null;
            mOut = null;
        }
        closeQuietly(server);
        closeQuietly(socket);
        if (server != null || socket != null) Bus.log("蓝牙: 控制通道已关闭");
    }

    private void adopt(BluetoothSocket s, int gen) {
        final OutputStream out;
        try {
            out = s.getOutputStream();
        } catch (Throwable e) {
            closeQuietly(s);
            synchronized (mGenLock) {
                if (gen == mGen) {
                    mConnecting = false;
                    mPendingMsg = null;
                }
            }
            if (isCurrent(gen)) {
                Bus.log("蓝牙: SPP 取输出流失败 " + e);
                notifyDown("取输出流失败: " + e);
            }
            return;
        }
        final String pending;
        synchronized (mGenLock) {
            if (gen != mGen) {
                closeQuietly(s);
                return;
            }
            mSocket = s;
            mOut = out;
            mUp = true;
            mEverConnected = true;
            mConnecting = false;
            pending = mPendingMsg;
            mPendingMsg = null;
        }
        Bus.log("蓝牙: SPP 控制通道已建立(本机=" + mRole
                + ",耗时 " + (SystemClock.elapsedRealtime() - mStartAt) + "ms)");
        if (pending != null) {
            try {
                synchronized (mWriteLock) {
                    out.write(pending.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                Bus.log("蓝牙: 已发送排队消息 — " + pending.trim());
            } catch (Throwable e) {
                Bus.log("蓝牙: 发送排队消息失败 " + e);
            }
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() { readLoop(s, gen); }
        }, "bt-ctl-read");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop(BluetoothSocket s, int gen) {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                final String msg = line.trim();
                if (msg.isEmpty()) continue;
                Bus.log("蓝牙: 收到控制消息 — " + msg);
                final Listener l = mListener;
                if (l != null) {
                    MAIN.post(new Runnable() {
                        @Override public void run() { l.onControl(msg); }
                    });
                }
            }
        } catch (Throwable e) {
            if (isCurrent(gen) && mUp) Bus.log("蓝牙: 控制通道中断 " + e);
        } finally {
            boolean stillCurrent;
            synchronized (mGenLock) {
                stillCurrent = gen == mGen;
                if (stillCurrent) {
                    mUp = false;
                    mSocket = null;
                    mOut = null;
                }
            }
            closeQuietly(s);
            if (stillCurrent) notifyDown("对端断开");
        }
    }

    private boolean isCurrent(int gen) {
        synchronized (mGenLock) {
            return gen == mGen;
        }
    }

    private void notifyDown(final String why) {
        Bus.log("蓝牙: 控制通道不可用 — " + why);
        final Listener l = mListener;
        if (l != null) {
            MAIN.post(new Runnable() {
                @Override public void run() { l.onBtDown(why); }
            });
        }
    }

    private boolean hasPermission(String name) {
        if (mApp == null) return false;
        try {
            return mApp.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private String perm(String name) {
        return hasPermission(name) ? "granted" : "denied";
    }

    //pri added by zhuyang,getAddress is hidden for normal apps: reflect it, then fall back to Settings.Secure,20261020
    private String resolveLocalMac() {
        if (mAdapter != null) {
            try {
                Method m = BluetoothAdapter.class.getMethod("getAddress");
                Object v = m.invoke(mAdapter);
                if (v instanceof String) {
                    String mac = normalizeMac((String) v);
                    if (mac != null) {
                        Bus.log("蓝牙: getAddress() 取到本机 MAC");
                        return mac;
                    }
                    Bus.log("蓝牙: getAddress() 未给出本机 MAC(" + v + ")");
                }
            } catch (Throwable t) {
                Bus.log("蓝牙: 反射 getAddress 失败 " + t);
            }
        }
        try {
            String s = android.provider.Settings.Secure.getString(
                    mApp.getContentResolver(), "bluetooth_address");
            String mac = normalizeMac(s);
            if (mac != null) {
                Bus.log("蓝牙: 回退 Settings.Secure 取到本机 MAC");
                return mac;
            }
            if (s != null) Bus.log("蓝牙: Settings.Secure 的 bluetooth_address 无效(" + s + ")");
        } catch (Throwable t) {
            Bus.log("蓝牙: 读取 Settings.Secure.bluetooth_address 失败 " + t);
        }
        return null;
    }

    /** 12 uppercase hex digits, colons removed; null for a missing / placeholder address. */
    public static String normalizeMac(String raw) {
        if (raw == null) return null;
        String s = raw.replace(":", "").replace("-", "").replace(" ", "")
                .toUpperCase(Locale.US);
        if (s.length() != 12) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) return null;
        }
        if ("000000000000".equals(s) || "020000000000".equals(s)) return null;
        return s;
    }

    /** getRemoteDevice() on API 36 only accepts the colon-separated form. */
    private static String colonMac(String mac12) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (sb.length() > 0) sb.append(':');
            sb.append(mac12, i, i + 2);
        }
        return sb.toString();
    }

    private static void closeQuietly(BluetoothServerSocket s) {
        try { if (s != null) s.close(); } catch (Throwable ignored) {}
    }

    private static void closeQuietly(BluetoothSocket s) {
        try { if (s != null) s.close(); } catch (Throwable ignored) {}
    }
}
//pri added by zhuyang,Bluetooth SPP side channel: control messages only (photos still use Wi-Fi TCP),20261020,end
