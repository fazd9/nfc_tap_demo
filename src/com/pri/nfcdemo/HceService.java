package com.pri.nfcdemo;

import android.content.Intent;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.nio.charset.StandardCharsets;

/**
 * Card side (sender): emulates a Type-4-like card so the peer phone can read our payload.
 * Registered AID: F05052494E4643 (see res/xml/apdu_service.xml).
 */
public class HceService extends HostApduService {

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) {
            return new byte[]{0x67, 0x00};
        }
        if (isSelectOurAid(apdu)) {
            Bus.log("HCE: 对方选中了我们的 AID");
            return Bus.SW_OK;
        }
        int ins = apdu[1] & 0xFF;
        if ((apdu[0] & 0xFF) == 0x80 && ins == 0x10) {
            String peer = new String(Bus.apduData(apdu), StandardCharsets.UTF_8);
            Bus.log("HCE: 收到对方(接收方)信息: " + peer);
            // 交给常驻服务处理:必须走 intent —— 应用进程被系统回收后,碰触只会让 NFC 框架把 HCE 拉起来,
            // 服务可能没在跑,直接用进程内监听投递会丢事件(实测:发送端整轮没反应)
            try {
                startService(new Intent(this, TapService.class)
                        .setAction("com.pri.nfcdemo.PEER")
                        .putExtra("payload", peer)
                        .putExtra("role", "card"));
            } catch (Throwable t) {
                Bus.log("HCE: 转交服务失败 " + t);
            }
            byte[] mine = Bus.selfPayload().getBytes(StandardCharsets.UTF_8);
            byte[] resp = new byte[mine.length + 2];
            System.arraycopy(mine, 0, resp, 0, mine.length);
            resp[mine.length] = (byte) 0x90;
            resp[mine.length + 1] = 0x00;
            return resp;
        }
        Bus.log("HCE: 不支持的指令 (INS=" + Integer.toHexString(ins) + ")");
        return Bus.SW_INS_NOT_SUPPORTED;
    }

    @Override
    public void onDeactivated(int reason) {
        Bus.log("HCE: 卡模拟会话结束 (reason=" + reason + ")");
    }

    private static boolean isSelectOurAid(byte[] apdu) {
        byte[] sel = Bus.SELECT_AID_APDU;
        if (apdu.length < sel.length) return false;
        for (int i = 0; i < sel.length; i++) {
            if (apdu[i] != sel[i]) return false;
        }
        return true;
    }
}
