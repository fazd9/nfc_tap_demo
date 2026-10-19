package com.pri.debuggallery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * pri added by zhuyang,Tap-time pull: com.pri.nfctouchdemo asks "what is selected right now" before it decides the role of a,20261019,start
 * pri added by zhuyang,tap, so every tap gets a fresh answer instead of relying on the selection-change push. The receiver is,20261019
 * pri added by zhuyang,manifest-registered so it also runs when this process has no live activity (an empty answer is then correct),20261019
 * pri added by zhuyang,Keep it short: onReceive runs on the main thread, the work itself is delegated to the static helper,20261019,end
 */
public class SelectionRequestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        MainActivity.republishCurrentSelection(context);
    }
}
