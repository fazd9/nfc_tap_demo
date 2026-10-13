package com.pri.nfcdemo;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;

/** 透明跳板:系统把 NFC 标签交给它 → 立即转交服务处理(弹悬浮窗) → 自身秒退(界面不可见)。 */
public class TapDispatchActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Tag tag = getIntent().getParcelableExtra(NfcAdapter.EXTRA_TAG);
            startService(new Intent(this, TapService.class)
                    .setAction("com.pri.nfcdemo.TAG")
                    .putExtra(NfcAdapter.EXTRA_TAG, tag));
        } catch (Throwable ignored) {}
        finish();
        overridePendingTransition(0, 0);
    }
}
