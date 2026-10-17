package com.pri.nfctouchdemo.nfc;

import android.content.Intent;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.nio.charset.StandardCharsets;

/**
 * Card side (sender): emulates a Type-4-like card so the peer phone can read our payload.
 * Registered AID: F05052494E4643 (see res/xml/apdu_service.xml).
 *
 * 响应路径上有两条硬约束（都是真机踩出来的，动之前先看注释）：
 *
 * 1. [processCommandApdu] 必须在对方的 ISO-DEP 超时之前交出响应 —— 这里**什么重活都不能做**。
 *    payload 走 [Bus.selfPayload]（内部短缓存 + 启动时已预热），转交常驻服务丢后台线程。
 *    之前是"先 startService 再构造响应"：startService 是跨进程调用，实测把响应拖过超时，
 *    读卡方第二次 transceive 抛 IOException —— 现象就是"一边已经在放动画，另一边全程静默"。
 *
 * 2. 转交必须走 startService（进程内监听会丢事件：应用被系统回收后，碰触只会让 NFC 框架
 *    把 HCE 拉起来，此时服务未必在跑）。
 */
public class HceService extends HostApduService {

    @Override
    public void onCreate() {
        super.onCreate();
        // 预热 payload：首次要初始化蓝牙取本机名 + 枚举网卡，几百毫秒，别留到 APDU 响应路径里
        Bus.warmUpAsync();
    }

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) {
            Bus.log("HCE: APDU 异常(null 或过短)");
            return new byte[]{0x67, 0x00};
        }
        // 诊断用：每个进来的 APDU 都记一行 —— "EXCHANGE 没到" 和 "到了但解析失败" 一眼可分
        boolean exchange = apdu.length > 4 && (apdu[0] & 0xFF) == 0x80 && (apdu[1] & 0xFF) == 0x10;
        Bus.log("HCE: 收到 APDU(" + apdu.length + "B) " + (exchange ? "(EXCHANGE,data hidden)" : hex(apdu))); //pri modify by zhuyang,Hide payload bytes so sid and hotspot credentials never reach the log,20261017
        try {
            if (isSelectOurAid(apdu)) {
                Bus.log("HCE: 对方选中了我们的 AID");
                // 对方紧接着就要发 EXCHANGE，趁这点空档在后台把 payload 备好
                Bus.warmUpAsync();
                return Bus.SW_OK;
            }
            int ins = apdu[1] & 0xFF;
            if ((apdu[0] & 0xFF) == 0x80 && ins == 0x10) {
                String peer = new String(Bus.apduData(apdu), StandardCharsets.UTF_8);
                Bus.log("HCE: 收到对方(接收方)信息: " + Bus.redactPayload(peer)); //pri modify by zhuyang,Redact sid and hotspot password,20261017
                // ① 先把响应拼好（Bus 里有短缓存，正常就是字符串拼接）
                byte[] resp = Bus.buildExchangeResponse(Bus.selfPayload()); //pri modify by zhuyang,Explicit length failure instead of silent truncation,20261017
                if (resp == null) {
                    Bus.log("HCE: 本机 payload 超长,回 6700(wrong length)");
                    return new byte[]{0x67, 0x00};
                }
                // ② 再转交常驻服务 —— 绝不能同步做，否则响应会被拖过对方的超时
                postPeerToService(peer);
                return resp;
            }
            Bus.log("HCE: 不支持的指令 (INS=" + Integer.toHexString(ins) + ")");
            return Bus.SW_INS_NOT_SUPPORTED;
        } catch (Throwable t) {
            // 以前这里没兜底：一旦解析/转发抛异常，框架拿不到响应直接结束会话，双方都懵
            Bus.log("HCE: 处理 APDU 出错 " + t);
            return Bus.SW_INS_NOT_SUPPORTED;
        }
    }

    /** 转交常驻服务：必须走 startService，但不能卡在 APDU 响应路径上，所以丢后台线程。 */
    private void postPeerToService(final String peer) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startService(new Intent(HceService.this, TapService.class)
                            .setAction("com.pri.nfcdemo.PEER")
                            .putExtra("payload", peer)
                            .putExtra("role", "card"));
                } catch (Throwable e) {
                    Bus.log("HCE: 转交服务失败 " + e);
                }
            }
        }, "hce-to-service");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onDeactivated(int reason) {
        Bus.log("HCE: 卡模拟会话结束 (reason=" + reason + ")");
        // 一轮结束，趁现在把下一轮要用的 payload 预热好
        Bus.warmUpAsync();
    }

    private static boolean isSelectOurAid(byte[] apdu) {
        byte[] sel = Bus.SELECT_AID_APDU;
        if (apdu.length < sel.length) return false;
        for (int i = 0; i < sel.length; i++) {
            if (apdu[i] != sel[i]) return false;
        }
        return true;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
