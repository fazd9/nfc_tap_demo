package com.pri.nfcdemo;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;

/**
 * 一个应用两种角色,先碰一碰(NFC 交换信息),碰通后自动建立 Wi-Fi Direct 直连传图片:
 *  - 发送方(默认,card): 可先「选择图片」,碰通后点「发送给对方」。
 *  - 接收方(reader):   碰通后自动做组主监听,收到图片直接显示并存相册。
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback, Bus.Listener {

    private static final int REQ_PICK = 100;

    private NfcAdapter mAdapter;
    private TextView mStateView;
    private TextView mPeerView;
    private TextView mSelfView;
    private TextView mLogView;
    private Button mModeButton;
    private Button mPickButton;
    private Button mSendButton;
    private ImageView mImageView;
    private boolean mReaderOn;
    private boolean mUserWantsReader;

    private Xfer mXfer;
    private final java.util.ArrayList<Uri> mSendUris = new java.util.ArrayList<Uri>();
    private boolean mAmReceiver;
    private boolean mP2pReady;
    private FrameLayout mContainer;
    private ParticleView mParticles;
    private SendPage mSendPage;
    private ReceivePage mReceivePage;
    private View mViewer;
    private int mPreviewCount;
    private Bitmap mLastBmp;
    private boolean mAccepted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        mXfer = TapService.xfer(this); // 与服务共用同一个传输器,避免重复建链
        mXfer.addListener(mXferListener);
        ensurePermissions();
        mUserWantsReader = Bus.isUserWantsReader();
        mXfer.warmUp();
        try {
            startService(new Intent(this, TapService.class)); // 常驻服务:支撑后台弹出悬浮窗
        } catch (Throwable t) {
            Bus.log("启动常驻服务失败: " + t);
        }
        Bus.log("=== NFC Tap Demo v4.0 启动(服务+悬浮窗架构) ===");

        handleShareIntent(getIntent()); // 相册等应用通过系统分享传入的图片(与相册解耦的标准入口)
        handleDebugIntent(getIntent()); // 调试/演示直达:am start -a com.pri.nfcdemo.DEBUG -e page send|receive|viewer

        mAdapter = NfcAdapter.getDefaultAdapter(this);
        if (mAdapter == null) {
            setState("本机没有 NFC,无法使用", Color.RED);
            mModeButton.setEnabled(false);
            return;
        }
        handleNfcIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bus.setListener(this);
        if (mAdapter == null) return;
        if (!mAdapter.isEnabled()) {
            mReaderOn = false;
            mModeButton.setText("切换为接收方(开启读卡)");
            setState("NFC 未开启,请先打开 NFC(可点下方按钮)", Color.RED);
            return;
        }
        if (mUserWantsReader) {
            startReader();
        } else if (!mP2pReady) {
            if (!mSendUris.isEmpty()) {
                setState("已拿到 " + mSendUris.size() + " 张图片,去碰对方手机发送", Color.parseColor("#1565C0"));
            } else {
                showSenderState();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Bus.setListener(null);
        if (mAdapter != null) {
            try {
                mAdapter.disableReaderMode(this);
            } catch (Throwable t) {
                Bus.log("关闭读卡模式异常(可能是 NFC 服务挂了): " + t);
            }
            mReaderOn = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mXfer != null) mXfer.destroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
        handleDebugIntent(intent);
        handleNfcIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            Uri picked = data.getData();
            if (picked != null) {
                mSendUris.clear();
                mSendUris.add(picked);
                loadPreview(picked);
                Bus.log("已选择图片: " + picked);
                updateSendEnabled();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        if (requestCode == 7) {
            Bus.log("定位权限结果: " + (results.length > 0 ? results[0] : -1));
        }
    }

    private void ensurePermissions() {
        try {
            java.util.ArrayList<String> need = new java.util.ArrayList<String>();
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                    != PackageManager.PERMISSION_GRANTED) {
                need.add("android.permission.NEARBY_WIFI_DEVICES");
            }
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), 7);
            }
        } catch (Throwable t) {
            Bus.log("权限请求异常: " + t);
        }
    }

    // ---------- 界面 ----------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 30, 30, 30);

        TextView title = new TextView(this);
        title.setText("NFC 碰一碰 Demo");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("用法:\n"
                + "1. 接收方:打开本应用,点「切换为接收方」,然后去贴对方手机。\n"
                + "2. 发送方:在相册选好图片后「分享」到本应用,或点「选择图片」。\n"
                + "3. 两台机器背对背贴近,出现「握手成功」后自动建立连接。\n"
                + "4. 连上后发送方点「发送给对方」即可传图(支持多张)。");
        hint.setTextSize(13);
        hint.setPadding(0, 10, 0, 10);
        root.addView(hint);

        mStateView = new TextView(this);
        mStateView.setTextSize(17);
        mStateView.setTypeface(Typeface.DEFAULT_BOLD);
        mStateView.setPadding(0, 10, 0, 10);
        root.addView(mStateView);

        mModeButton = new Button(this);
        mModeButton.setText("切换为接收方(开启读卡)");
        mModeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMode(); }
        });
        root.addView(mModeButton);

        Button wifiButton = new Button(this);
        wifiButton.setText("打开 NFC 设置");
        wifiButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_NFC_SETTINGS));
                } catch (Throwable ignored) {}
            }
        });
        root.addView(wifiButton);

        mPickButton = new Button(this);
        mPickButton.setText("① 选择要发送的图片");
        mPickButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, REQ_PICK);
                } catch (Throwable t) {
                    Bus.log("打开相册失败: " + t);
                }
            }
        });
        root.addView(mPickButton);

        mSendButton = new Button(this);
        mSendButton.setText("② 发送给对方");
        mSendButton.setEnabled(false);
        mSendButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!mSendUris.isEmpty()) mXfer.sendImages(mSendUris);
            }
        });
        root.addView(mSendButton);

        mPeerView = new TextView(this);
        mPeerView.setTextSize(15);
        mPeerView.setPadding(0, 16, 0, 0);
        root.addView(mPeerView);

        mImageView = new ImageView(this);
        mImageView.setAdjustViewBounds(true);
        mImageView.setMaxHeight(420);
        mImageView.setPadding(0, 12, 0, 0);
        root.addView(mImageView);

        mSelfView = new TextView(this);
        mSelfView.setTextSize(12);
        mSelfView.setTextColor(Color.DKGRAY);
        mSelfView.setPadding(0, 10, 0, 0);
        root.addView(mSelfView);

        TextView logTitle = new TextView(this);
        logTitle.setText("--- 运行日志 ---");
        logTitle.setTextSize(12);
        logTitle.setPadding(0, 20, 0, 4);
        root.addView(logTitle);

        mLogView = new TextView(this);
        mLogView.setTextSize(11);
        mLogView.setTypeface(Typeface.MONOSPACE);
        mLogView.setMovementMethod(new ScrollingMovementMethod());

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.addView(mLogView);
        root.addView(scroll);

        // targetSdk 36 强制边到边:控制台加系统栏内边距
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                android.graphics.Insets b = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(30, 30 + b.top, 30, 30 + b.bottom);
                return insets;
            }
        });

        mContainer = new FrameLayout(this);
        mContainer.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mParticles = new ParticleView(this);
        mParticles.setClickable(false);
        mContainer.addView(mParticles, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(mContainer);
    }

    private void updateSendEnabled() {
        mSendButton.setEnabled(!mAmReceiver && mP2pReady && !mSendUris.isEmpty());
    }

    private void toggleMode() {
        if (mReaderOn) {
            try {
                mAdapter.disableReaderMode(this);
            } catch (Throwable t) {
                Bus.log("关闭读卡模式异常(可能是 NFC 服务挂了): " + t);
            }
            mReaderOn = false;
            mUserWantsReader = false;
            Bus.setUserWantsReader(false);
            mModeButton.setText("切换为接收方(开启读卡)");
            showSenderState();
            Bus.log("已切回发送方模式(关闭读卡)");
        } else {
            mUserWantsReader = true;
            startReader();
        }
    }

    private void showSenderState() {
        setState("发送方模式(等对方来读;如本机要接收,请点下方按钮)", Color.parseColor("#1565C0"));
    }

    private void startReader() {
        if (mAdapter == null || !mAdapter.isEnabled()) return;
        try {
            mAdapter.enableReaderMode(this, this,
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null);
        } catch (Throwable t) {
            Bus.log("开启读卡模式失败(NFC 服务异常?): " + t);
            setState("NFC 服务异常,请重开应用再试", Color.RED);
            return;
        }
        mReaderOn = true;
        Bus.setUserWantsReader(true);
        // 热点改由服务在"碰后"开启(不再提前开,避免占 Wi-Fi)
        mModeButton.setText("切回发送方(停止读卡)");
        setState("接收方模式(读卡中)— 现在去贴对方", Color.parseColor("#2E7D32"));
        Bus.log("读卡模式已开启,等待碰触");
    }

    private void setState(String text, int color) {
        mStateView.setText(text);
        mStateView.setTextColor(color);
    }

    // ---------- NFC ----------

    private void handleNfcIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
            if (TapService.hasPending()) {
                Bus.log("本机有选中照片 → 本机是发送方,忽略本次读取(等对方来读本机)");
                return;
            }
            if (!Bus.lockRole()) { Bus.log("角色已定(本机已是发送方),忽略本次读取"); return; }
            // 系统派发(被碰)即自动进入接收方角色:无需预先打开应用/点按钮
            // (普通应用的近似实现:系统把应用拉起;完全不亮应用直接弹悬浮窗需系统应用)
            mUserWantsReader = true;
            mAmReceiver = true;
            Bus.setUserWantsReader(true);
            Bus.log("系统派发碰触(自动拉起):本机作为接收方处理");
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            Bus.log("系统分发了 NFC 标签(" + action + "),开始读取");
            onTagDiscovered(tag);
        }
    }

    @Override
    public void onTagDiscovered(final Tag tag) {
        if (tag == null) return;
        new Thread(new Runnable() {
            @Override public void run() { exchange(tag); }
        }).start();
    }

    private void exchange(Tag tag) {
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) {
            Bus.log("对方不是 IsoDep(HCE)设备,无法读取");
            return;
        }
        try {
            iso.setTimeout(3000);
            if (!iso.isConnected()) iso.connect();
            Bus.log("NFC 链路已建立(ISO-DEP, maxTransceive=" + iso.getMaxTransceiveLength() + ")");
            byte[] r = iso.transceive(Bus.SELECT_AID_APDU);
            if (!Bus.isOk(r)) {
                Bus.log("对方未接受我们的 AID");
                return;
            }
            Bus.log("对端 AID 已选中,交换信息…");
            byte[] resp = iso.transceive(Bus.buildExchangeApdu(Bus.selfPayload()));
            String peer = Bus.parseExchangeResponse(resp);
            if (peer == null) {
                Bus.log("响应为空");
                return;
            }
            Bus.log("收到对方信息: " + peer);
            Bus.onPeerData(peer, "reader");
        } catch (IOException e) {
            Bus.log("读取失败(" + e.getClass().getSimpleName()
                    + ")— 把两台机器贴紧、慢慢移动位置,再试一次");
        } catch (Throwable t) {
            Bus.log("读取异常: " + t);
        } finally {
            try { iso.close(); } catch (IOException ignored) {}
        }
    }

    // ---------- Bus.Listener ----------

    @Override
    public void onLog(String line) {
        mLogView.append(line + "\n");
        mSelfView.setText("本机: " + Bus.selfPayload());
    }

    @Override
    public void onPeer(String payload, String role) {
        mPeerView.setText("✅ 握手成功,对方信息:\n" + pretty(payload));
        mSelfView.setText("本机信息: " + Bus.selfPayload());
        mAmReceiver = "reader".equals(role);
        mXfer.setExpectedPeer(field(payload, "n"), field(payload, "b"),
                field(payload, "p"), field(payload, "a"), field(payload, "ip"),
                field(payload, "hs"), field(payload, "hp"));;
        if (mAmReceiver) {
            setState("握手成功 → 正在建立 Wi-Fi 直连(本机接收)…", Color.parseColor("#2E7D32"));
            mXfer.startAsReceiver();
        } else {
            setState("握手成功 → 正在建立 Wi-Fi 直连(本机发送)…", Color.parseColor("#2E7D32"));
            mXfer.startAsSender();
        }
        mModeButton.setEnabled(false); // 本次会话不再切换角色
        updateSendEnabled();

        // 确认页:接收方由本 Activity 弹出;发送方由常驻服务弹出(不打断前台应用,对齐产品视频)
        if (mAmReceiver) {
            showReceivePage(peerLabel(payload), 1);
        } else {
            Bus.log("发送方:确认页由常驻服务弹出(相册不被打断)");
        }
    }

    // ---------- Xfer.Listener ----------

    private final Xfer.Listener mXferListener = new Xfer.Listener() {
        @Override public void onP2pReady(boolean isGroupOwner, String goAddress) {
            mP2pReady = true;
            updateSendEnabled();
            if (mAmReceiver) {
                setState("✅ 已连接,等待接收…", Color.parseColor("#2E7D32"));
                if (mReceivePage != null) mReceivePage.setStatus("已连接,等待接收…");
            } else {
                setState("✅ 已连接,上滑发送即可", Color.parseColor("#2E7D32"));
                if (mSendPage != null) mSendPage.setStatus("已连接");
                mXfer.sendPreviews(mSendUris); // 链路一好就把预览推给对方(接收确认页显示照片)
            }
        }

        @Override public void onPreview(Bitmap bmp) {
            mPreviewCount++;
            if (mReceivePage != null) {
                mReceivePage.setPreview(bmp);
                mReceivePage.setPreviewCount(mPreviewCount);
            }
        }

        @Override public void onImageReceived(Bitmap bmp, int bytes, boolean saved) {
            if (bmp != null) mImageView.setImageBitmap(bmp);
            mLastBmp = bmp;
            Bus.unlockRole();
            setState("✅ 已收到图片(" + (bytes / 1024) + "KB"
                    + (saved ? ",已存入相册" : "") + ")", Color.parseColor("#2E7D32"));
            if (mReceivePage != null) {
                mReceivePage.reveal();   /* 发送方发送后,接收方界面元素才出现 */
                mReceivePage.setPreview(bmp);
                mReceivePage.setStatus("已收到 " + (bytes / 1024) + "KB");
                if (mAccepted) {
                    mReceivePage.dismiss();
                    mReceivePage = null;
                    showViewer(bmp);
                }
            } else {
                showViewer(bmp);
            }
        }

        @Override public void onImageSent(int bytes) {
            setState("✅ 图片已发送(" + (bytes / 1024) + "KB)", Color.parseColor("#2E7D32"));
            if (mSendPage != null) {
                mSendPage.dismiss();
                mSendPage = null;
            }
        }

        @Override public void onXferFailed(String why) {
            setState("❌ " + why, Color.RED);
            if (mSendPage != null) mSendPage.setStatus(why);
            if (mReceivePage != null) mReceivePage.setStatus(why);
        }
    };

    // ---------- 全屏确认页(还原产品视频) ----------

    private String peerLabel(String payload) {
        String t = field(payload, "t");
        return "用户" + (t == null ? "--------" : t);
    }

    private void showSendPage(final String titleText, int count) {
        if (mSendPage != null) return;
        mSendPage = new SendPage(this, new SendPage.Listener() {
            @Override public void onSwipedSend() {
                if (!mP2pReady) {
                    if (mSendPage != null) {
                        mSendPage.setStatus("正在连接对方,请稍候…");
                        mSendPage.resetStack();
                    }
                    return;
                }
                playParticles();
                if (!mSendUris.isEmpty()) {
                    mXfer.sendImages(mSendUris);
                } else {
                    Bus.log("演示模式:无真实图片,跳过实际发送");
                }
            }

            @Override public void onClose() {
                Bus.log("已关闭发送页");
                if (mSendPage != null) {
                    mSendPage.dismiss();
                    mSendPage = null;
                }
            }
        });
        mSendPage.bind(mSendUris, titleText, count);
        mSendPage.setStatus(mP2pReady ? "已连接" : "正在连接对方…");
        mSendPage.root.setAlpha(0f);
        Overlay.get(this).show(mSendPage.root); // 悬浮窗显示(产品形态:服务+悬浮窗)
        mSendPage.root.animate().alpha(1f).setDuration(280).start();
    }

    private void showReceivePage(String label, int count) {
        if (mReceivePage != null) return;
        mPreviewCount = 0;
        mReceivePage = new ReceivePage(this, new ReceivePage.Listener() {
            @Override public void onAccept() {
                mAccepted = true;
                if (mLastBmp != null) {
                    if (mReceivePage != null) { mReceivePage.dismiss(); mReceivePage = null; }
                    showViewer(mLastBmp);
                } else if (mReceivePage != null) {
                    mReceivePage.setStatus("等待对方发送…");
                }
            }
            @Override public void onReject() {
                Bus.log("已拒绝接收");
                if (mReceivePage != null) {
                    mReceivePage.dismiss();
                    mReceivePage = null;
                }
            }
        });
        mReceivePage.bind(label, Math.max(1, count));
        mReceivePage.setStatus(mP2pReady ? "已连接" : "正在连接对方…");
        mReceivePage.root.setAlpha(0f);
        Overlay.get(this).show(mReceivePage.root); // 悬浮窗显示(产品形态:服务+悬浮窗)
        mReceivePage.root.animate().alpha(1f).setDuration(280).start();
    }

    private void playParticles() {
        final ParticleView pv = new ParticleView(this);
        if (!Overlay.get(this).showPassThrough(pv)) return;
        pv.post(new Runnable() {
            @Override public void run() {
                pv.burst(pv.getWidth() / 2f, pv.getHeight() * 0.42f, 90);
            }
        });
        pv.postDelayed(new Runnable() {
            @Override public void run() { Overlay.get(MainActivity.this).hide(pv); }
        }, 2600);
    }

    /** 接收完成后的全屏查看器(模拟相册大图页)。 */
    private void showViewer(Bitmap bmp) {
        FrameLayout v = new FrameLayout(this);
        v.setBackgroundColor(0xFF000000);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (bmp != null) img.setImageBitmap(bmp);
        v.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, Ui.dp(this, 26), 0, 0);
        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(Color.WHITE);
        back.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), Ui.dp(this, 6));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                if (mViewer != null) {
                    Overlay.get(MainActivity.this).hide(mViewer);
                    mViewer = null;
                }
            }
        });
        top.addView(back);
        TextView when = new TextView(this);
        when.setText("今天 " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date()));
        when.setTextColor(Color.WHITE);
        when.setTextSize(16);
        top.addView(when);
        v.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        String[] acts = {"分享", "收藏", "编辑"};
        for (String a : acts) {
            TextView t = new TextView(this);
            t.setText(a);
            t.setTextColor(0xFFDDDDDD);
            t.setTextSize(15);
            t.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8));
            bar.addView(t);
        }
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        blp.bottomMargin = Ui.dp(this, 40);
        v.addView(bar, blp);

        mViewer = v;
        Overlay.get(this).show(v); // 查看器也用悬浮窗
    }

    /** 调试/演示直达:am start -n com.pri.nfcdemo/.MainActivity -a com.pri.nfcdemo.DEBUG -e page send|receive|viewer */
    private void handleDebugIntent(Intent intent) {
        if (intent == null || !"com.pri.nfcdemo.DEBUG".equals(intent.getAction())) return;
        String page = intent.getStringExtra("page");
        int count = Math.max(1, intent.getIntExtra("count", 2));
        if ("send".equals(page)) {
            mAmReceiver = false;
            showSendPage("发送给 用户021e465j580e94", count);
        } else if ("receive".equals(page)) {
            mAmReceiver = true;
            showReceivePage("用户021e465j580e94", count);
        } else if ("viewer".equals(page)) {
            showViewer(null);
        }
    }

    /** 相册/文件管理器等通过系统分享传入的图片:与相册解耦的标准入口。 */
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        try {
            if (Intent.ACTION_SEND.equals(a)) {
                Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) {
                    mSendUris.clear();
                    mSendUris.add(u);
                    onUrisReady();
                }
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
                java.util.ArrayList<Uri> list =
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null && !list.isEmpty()) {
                    mSendUris.clear();
                    mSendUris.addAll(list);
                    onUrisReady();
                }
            }
        } catch (Throwable t) {
            Bus.log("接收分享图片失败: " + t);
        }
    }

    private void onUrisReady() {
        Bus.log("收到分享的 " + mSendUris.size() + " 张图片(URI 读取权限已随分享授予)");
        loadPreview(mSendUris.get(0));
        mAmReceiver = false;
        mUserWantsReader = false;
        Bus.setUserWantsReader(false);
        setState("已拿到 " + mSendUris.size() + " 张图片,去碰对方手机发送", Color.parseColor("#1565C0"));
        updateSendEnabled();
        // 立即进入发送确认页(视频流程:相册分享后就是这张页面)
        showSendPage("碰一下对方手机发送", mSendUris.size());
        if (mSendPage != null) mSendPage.setStatus("等待碰触…");
    }

    /** 安全加载图片预览:自己解码,不用 setImageURI(它在布局阶段读 URI,无权限时会崩)。 */
    private void loadPreview(final Uri uri) {
        if (uri == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeStream(in, null, o);
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    int scale = 1;
                    while (o.outWidth / scale > 1080 || o.outHeight / scale > 1080) scale *= 2;
                    android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                    o2.inSampleSize = scale;
                    java.io.InputStream in2 = getContentResolver().openInputStream(uri);
                    final Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in2, null, o2);
                    try { if (in2 != null) in2.close(); } catch (Exception ignored) {}
                    if (bmp != null) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { mImageView.setImageBitmap(bmp); }
                        });
                    } else {
                        Bus.log("预览解码失败");
                    }
                } catch (Throwable t) {
                    Bus.log("预览加载失败(可能没有该图片的读取权限): " + t);
                }
            }
        }).start();
    }

    private static String field(String payload, String key) {
        for (String kv : payload.split("\\|")) {
            if (kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
        }
        return null;
    }

    private static String pretty(String payload) {
        StringBuilder sb = new StringBuilder();
        for (String kv : payload.split("\\|")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                sb.append("   ").append(kv.substring(0, i)).append(": ")
                        .append(kv.substring(i + 1)).append('\n');
            } else {
                sb.append("   ").append(kv).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
