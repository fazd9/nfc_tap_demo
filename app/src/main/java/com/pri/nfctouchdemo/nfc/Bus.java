package com.pri.nfctouchdemo.nfc;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared state + NFC APDU protocol + UI bridge between MainActivity and HceService.
 *
 * Wire protocol (ISO-DEP / HCE):
 *   reader -> card : 00 A4 04 00 07 F0 50 52 49 4E 46 43 00          (SELECT our AID)
 *   card   -> reader: 90 00
 *   reader -> card : 80 10 00 00 <Lc> <readerPayload> 00            (EXCHANGE, INS=0x10)
 *   card   -> reader: <cardPayload> 90 00
 * Payload format: "n=<model>|t=<token>|sid=<32hex>|ts=<time>|ip=<lanIpv4>"
 */
public final class Bus {

    public interface Listener {
        void onLog(String line);
        void onPeer(String peerPayload, String role);
    }

    public static final byte[] SELECT_AID_APDU = {
            0x00, (byte) 0xA4, 0x04, 0x00, 0x07,
            (byte) 0xF0, 0x50, 0x52, 0x49, 0x4E, 0x46, 0x43,
            0x00
    };
    public static final byte[] SW_OK = {(byte) 0x90, 0x00};
    public static final byte[] SW_INS_NOT_SUPPORTED = {0x6D, 0x00};

    private static final int MAX_PAYLOAD = 240; //pri modify by zhuyang,Raise the payload limit to fit sid; short APDU Lc still caps at 255,20261017

    /**
     * payload 短缓存时长：见 [selfPayload] 的注释 —— 碰触时这个方法是在 NFC 会话**中间**被调用的，
     * 每次现算就要枚举网卡，几百毫秒正好把链路拖断。
     */
    private static final long PAYLOAD_TTL_MS = 1200L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<String> LOG = new ArrayList<String>();
    private static volatile Listener sListener;        // UI(Activity),随生命周期挂/摘
    private static volatile Listener sServiceListener; // 常驻服务,一直挂着
    private static volatile String sSelfPayload;
    private static volatile String sP2pName;
    private static volatile String sP2pAddr;
    private static volatile String sHotspotSsid;
    private static volatile String sHotspotPass;
    private static volatile boolean sUserWantsReader;
    private static volatile String sPayloadCache;
    private static volatile long sPayloadCacheAt;
    private static volatile boolean sPayloadGalleryFront; //pri added by zhuyang,Foreground flag the cached payload was built with: a change forces a rebuild,20261020
    private static volatile String sSid; //pri added by zhuyang,Process-wide session id shared by every round,20261017
    private static volatile int sPhotoCount; //pri modify by zhuyang,Advertise how many photos the gallery currently has selected,20261019
    private static volatile String sBtMac; //pri added by zhuyang,Local Bluetooth MAC carried as bm for the SPP control channel,20261020

    private Bus() {}

    //pri modify by zhuyang,Publish the gallery's current selection count and invalidate the payload cache only on change,20261019,start
    public static void setPhotoCount(int count) {
        int v = Math.max(0, Math.min(99, count));
        Bus.log(v == 0 ? "本机无待发照片" : "本机待发照片 " + v + " 张");
        if (sPhotoCount == v) return;
        sPhotoCount = v;
        payloadChanged();
    }
    //pri modify by zhuyang,Publish the gallery's current selection count and invalidate the payload cache only on change,20261019,end

    //pri added by zhuyang,Advertise the local Bluetooth MAC as 12 uppercase hex digits; null = omit bm,20261020,start
    public static void setBtMac(String mac) {
        String v = BtControl.normalizeMac(mac);
        if (v == null || v.equals(sBtMac)) return;
        sBtMac = v;
        payloadChanged();
    }
    //pri added by zhuyang,Advertise the local Bluetooth MAC as 12 uppercase hex digits; null = omit bm,20261020,end

    //pri added by zhuyang,Generate the 128-bit sid once per process and keep it for all rounds,20261017,start
    public static String sid() {
        String s = sSid;
        if (s != null) return s;
        synchronized (Bus.class) {
            if (sSid == null) {
                byte[] b = new byte[16];
                new SecureRandom().nextBytes(b);
                StringBuilder sb = new StringBuilder(32);
                for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
                sSid = sb.toString();
            }
            return sSid;
        }
    }
    //pri added by zhuyang,Generate the 128-bit sid once per process and keep it for all rounds,20261017,end

    /** 本机开的热点信息(SSID/密码),随 payload 传给对方,对方可免搜索直连。 */
    public static void setHotspotInfo(String ssid, String pass) {
        if (ssid != null) sHotspotSsid = ssid;
        if (pass != null) sHotspotPass = pass;
        android.util.Log.i("NfcTapDemo", "热点信息: SSID=" + sHotspotSsid + " / pass=***"); //pri modify by zhuyang,Never print the hotspot password,20261017
        payloadChanged();
    }

    /** 角色(是否接收方)持久化到进程级,Activity 重建后不丢。 */
    public static void setUserWantsReader(boolean v) {
        sUserWantsReader = v;
    }

    public static boolean isUserWantsReader() {
        return sUserWantsReader;
    }

    /** 本机在 Wi-Fi Direct 里的名字/地址(如 Android_ghl5),供对方在搜索结果里精确匹配。 */
    public static void setP2pIdentity(String name, String addr) {
        if (name != null) sP2pName = name;
        // 系统给应用的 P2P 地址是匿名化假值(02:00:00:00:00:00),过滤掉,只靠名字匹配
        if (addr != null && !"02:00:00:00:00:00".equals(addr)) sP2pAddr = addr;
        android.util.Log.i("NfcTapDemo", "P2P 身份: " + sP2pName + " / " + sP2pAddr);
        payloadChanged();
    }

    // ---------- protocol helpers ----------

    public static boolean isOk(byte[] resp) {
        return resp != null && resp.length >= 2
                && resp[resp.length - 2] == (byte) 0x90 && resp[resp.length - 1] == 0x00;
    }

    public static byte[] buildExchangeApdu(String payload) {
        byte[] d = payloadBytes(payload, "buildExchangeApdu");
        if (d == null) return null;
        byte[] apdu = new byte[5 + d.length + 1];
        apdu[0] = (byte) 0x80;
        apdu[1] = 0x10;
        apdu[2] = 0x00;
        apdu[3] = 0x00;
        apdu[4] = (byte) d.length;
        System.arraycopy(d, 0, apdu, 5, d.length);
        apdu[5 + d.length] = 0x00;
        return apdu;
    }

    //pri added by zhuyang,HCE response builder with the same explicit length check (no truncation),20261017,start
    public static byte[] buildExchangeResponse(String payload) {
        byte[] d = payloadBytes(payload, "buildExchangeResponse");
        if (d == null) return null;
        byte[] resp = new byte[d.length + 2];
        System.arraycopy(d, 0, resp, 0, d.length);
        resp[d.length] = (byte) 0x90;
        resp[d.length + 1] = 0x00;
        return resp;
    }

    private static byte[] payloadBytes(String payload, String where) {
        byte[] d = payload.getBytes(StandardCharsets.UTF_8);
        if (d.length > MAX_PAYLOAD) {
            android.util.Log.e("NfcTapDemo", where + ": payload too long (" + d.length
                    + " > " + MAX_PAYLOAD + "), rejected without truncation");
            Bus.log(where + ": payload 超长(" + d.length + " > " + MAX_PAYLOAD + "),已拒绝,不截断");
            return null;
        }
        return d;
    }
    //pri added by zhuyang,HCE response builder with the same explicit length check (no truncation),20261017,end

    //pri added by zhuyang,Redact sid and hotspot password before a payload reaches any log,20261017,start
    public static String redactPayload(String payload) {
        if (payload == null) return null;
        String[] parts = payload.split("\\|", -1);
        StringBuilder sb = new StringBuilder(payload.length());
        for (String kv : parts) {
            if (sb.length() > 0) sb.append('|');
            if (kv.startsWith("sid=")) sb.append("sid=***");
            else if (kv.startsWith("hp=")) sb.append("hp=***");
            else sb.append(kv);
        }
        return sb.toString();
    }
    //pri added by zhuyang,Redact sid and hotspot password before a payload reaches any log,20261017,end

    public static String parseExchangeResponse(byte[] resp) {
        if (resp == null || resp.length < 2) return null;
        return new String(resp, 0, resp.length - 2, StandardCharsets.UTF_8);
    }

    public static byte[] apduData(byte[] apdu) {
        if (apdu == null || apdu.length < 5) return new byte[0];
        int lc = apdu[4] & 0xFF;
        if (lc == 0 || apdu.length < 5 + lc) return new byte[0];
        byte[] d = new byte[lc];
        System.arraycopy(apdu, 5, d, 0, lc);
        return d;
    }

    // ---------- self payload ----------

    /**
     * 组装本机 payload（长度受 [MAX_PAYLOAD] 限制）。
     *
     * **这里的短缓存是必须的，不是优化**：碰一碰时它是在 NFC 会话**中间**被调用的 ——
     * 读卡方在 `SELECT`→`EXCHANGE` 之间（`TapService` / `MainActivity` 的读卡分支），
     * 卡方在 `processCommandApdu` 里。而每次现算都要 [lanIpv4]（枚举网卡），首次还要
     * 初始化蓝牙取本机名 —— 真机实测这几百毫秒正好把链路拖断：
     * 卡方在"对方选中了我们的 AID"之后 **249ms** 就以链路丢失结束会话，
     * 读卡方第二次 `transceive` 抛 IOException，于是 EXCHANGE 永远送不到卡方。
     *
     * 所以：算一次缓存 [PAYLOAD_TTL_MS]（只影响"新鲜度"，IP 变化一两秒内照样跟上），
     * 任何影响内容的字段一变（[setHotspotInfo] / [setP2pIdentity]）立刻作废并后台预热。
     */
    public static String selfPayload() {
        final boolean galleryFront = GalleryPickup.isGalleryForegroundQuiet(); //pri added by zhuyang,One cheap binder call: a changed foreground app invalidates the cached string,20261020
        final String cached = sPayloadCache;
        if (cached != null && galleryFront == sPayloadGalleryFront
                && System.currentTimeMillis() - sPayloadCacheAt < PAYLOAD_TTL_MS) {
            return cached;
        }
        String built = buildPayload(galleryFront);
        sPayloadGalleryFront = galleryFront;
        sPayloadCache = built;
        sPayloadCacheAt = System.currentTimeMillis();
        return built;
    }

    private static String buildPayload(boolean galleryFront) {
        String base = sSelfPayload;
        if (base == null) {
            //pri modify by zhuyang,Bluetooth is no longer used for matching: drop the BT name from the payload,20261019
            base = "n=" + safe(Build.MODEL)
                    + "|t=" + UUID.randomUUID().toString().substring(0, 8)
                    + "|sid=" + sid() //pri added by zhuyang,Keep sid right after t so a tail cut can never drop it,20261017
                    + "|ts=" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            sSelfPayload = base;
        }
        // P2P 名/局域网 IP/热点信息实时拼接:IP 会随网络变化,靠上面的短缓存控制新鲜度
        StringBuilder sb = new StringBuilder(base);
        //pri modify by zhuyang,Right after ts so a tail cut can never drop it; the current selection count (0..99),20261019
        //pri added by zhuyang,Google Photos cannot report a count: ph=? marks it unknown so the peer never sees a false 0,20261020,start
        if (galleryFront) {
            sb.append("|ph=?");
        } else {
            sb.append("|ph=").append(sPhotoCount);
        }
        //pri added by zhuyang,Google Photos cannot report a count: ph=? marks it unknown so the peer never sees a false 0,20261020,end
        if (sP2pName != null) sb.append("|p=").append(safe(sP2pName));
        if (sP2pAddr != null) sb.append("|a=").append(safe(sP2pAddr));
        String ip = lanIpv4();
        if (ip != null) sb.append("|ip=").append(ip);
        if (sBtMac != null) sb.append("|bm=").append(sBtMac); //pri added by zhuyang,Peer needs our Bluetooth MAC for the SPP side channel,20261020
        if (sHotspotSsid != null) sb.append("|hs=").append(safe(sHotspotSsid));
        if (sHotspotPass != null) sb.append("|hp=").append(safe(sHotspotPass));
        return sb.toString();
    }

    /** 影响 payload 的字段变了：作废缓存，并在后台线程先算一遍（别把这个开销留到 NFC 会话里）。 */
    private static void payloadChanged() {
        sPayloadCache = null;
        warmUpAsync();
    }

    /** 后台把 [selfPayload] 先算一遍：首次会初始化蓝牙、枚举网卡，几百毫秒。 */
    public static void warmUpAsync() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { selfPayload(); } catch (Throwable ignored) {}
            }
        }, "payload-warmup");
        t.setDaemon(true);
        t.start();
    }

    private static String safe(String s) {
        return s == null ? "?" : s.replace('|', '/').replace(';', ',');
    }

    /** 本机 Wi-Fi(wlan)IPv4 地址,用于"同网直连免搜索"。只认 wlan/wifi 接口,排除 p2p/蜂窝等。 */
    public static String lanIpv4() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String ifName = ni.getName();
                if (ifName == null) continue;
                String n = ifName.toLowerCase(Locale.US);
                if (n.contains("p2p") || n.startsWith("bt") || n.startsWith("rmnet")
                        || n.startsWith("usb") || n.startsWith("ccmni")) continue;
                if (!(n.startsWith("wlan") || n.startsWith("wifi"))) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    if (a.isLoopbackAddress() || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    // 关键坑:本机型 Wi-Fi Direct 接口就叫 wlan1、地址是 192.168.49.x —— 只按接口名
                    // 筛选会把"P2P 组地址"当成局域网 IP 发进 payload(真机日志里对方拿到的就是
                    // 192.168.49.200)。对方拿着这个地址去连必然失败,于是"同网直连"永远走不通、只能
                    // 退回 P2P。所以这里把 P2P/热点的保留网段排到最后(fallback),真局域网 IP 优先返回。
                    if (isP2pOrHotspotSubnet(ip)) { if (fallback == null) fallback = ip; continue; }
                    return ip;
                }
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 192.168.49.x = Android Wi-Fi Direct 组,192.168.43.x = LocalOnlyHotspot 常见网段:都不是局域网。 */
    private static boolean isP2pOrHotspotSubnet(String ip) {
        return ip != null && (ip.startsWith("192.168.49.") || ip.startsWith("192.168.43."));
    }

    // ---------- UI bridge ----------

    public static void setListener(Listener l) {
        sListener = l;
        if (l != null) {
            List<String> snapshot;
            synchronized (Bus.class) {
                snapshot = new ArrayList<String>(LOG);
            }
            for (String line : snapshot) l.onLog(line);
        }
    }

    public static void log(String line) {
        android.util.Log.i("NfcTapDemo", line);
        final String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                + "  " + line;
        synchronized (Bus.class) {
            LOG.add(stamped);
            if (LOG.size() > 200) LOG.remove(0);
        }
        final Listener l = sListener;
        if (l != null) {
            MAIN.post(new Runnable() {
                @Override public void run() { l.onLog(stamped); }
            });
        }
    }

    //pri added by zhuyang,Role lock is per round now: sRoleRound only generates a token, sRoleHeldRound is the round that owns the lock,20261020,start
    private static volatile boolean sRoleLocked;
    private static volatile long sRoleRound;
    private static volatile long sRoleHeldRound;

    public static long newRoleRound() {
        synchronized (Bus.class) {
            return ++sRoleRound;
        }
    }

    public static boolean lockRole(long round) {
        synchronized (Bus.class) {
            if (sRoleLocked && sRoleHeldRound == round) return false;
            sRoleLocked = true;
            sRoleHeldRound = round;
            return true;
        }
    }

    public static void unlockRole() {
        sRoleLocked = false;
    }
    //pri added by zhuyang,Role lock is per round now: sRoleRound only generates a token, sRoleHeldRound is the round that owns the lock,20261020,end

    public static void setServiceListener(Listener l) {
        sServiceListener = l;
    }

    public static void onPeerData(String payload, String role) {
        final Listener l = sListener;
        if (l != null) {
            MAIN.post(new Runnable() {
                @Override public void run() { l.onPeer(payload, role); }
            });
        }
        final Listener sl = sServiceListener;
        if (sl != null) {
            MAIN.post(new Runnable() {
                @Override public void run() { sl.onPeer(payload, role); }
            });
        }
    }
}
