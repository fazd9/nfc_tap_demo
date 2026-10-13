package com.pri.nfcdemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「来自 用户XXXX的分享」接收确认页 —— 逐项对齐网页版:
 * 半透明底(背后应用可见) + 边缘光效(呼吸) + 顶部虹彩柔光;
 * 碰完只显示"背景+光效"(元素全隐藏),发送方发送后才淡入元素。
 */
public class ReceivePage {

    public interface Listener {
        void onAccept();
        void onReject();
    }

    public final FrameLayout root;
    private final Context mCtx;
    private final ImageView mCard;
    private final ImageView mBackdrop;
    private final TextView mTitle;
    private final TextView mStatus;
    private final TextView mAccept;
    private final GlowView mGlow;
    private final java.util.ArrayList<View> mElements = new java.util.ArrayList<View>();

    public ReceivePage(Context ctx, final Listener listener) {
        mCtx = ctx;
        int W = ctx.getResources().getDisplayMetrics().widthPixels;

        root = new FrameLayout(ctx);
        root.setBackgroundColor(0x73000000);
        mBackdrop = new ImageView(ctx);
        mBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mBackdrop.setAlpha(0.7f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { mBackdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    90, 90, android.graphics.Shader.TileMode.CLAMP)); } catch (Throwable ignored) {}
        }
        root.addView(mBackdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mTitle = new TextView(ctx);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(15);
        mTitle.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        tp.topMargin = Ui.dp(ctx, 54);
        root.addView(mTitle, tp);
        mElements.add(mTitle);

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(0xFFDDDDDD);
        close.setTextSize(19);
        close.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { listener.onReject(); }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.TOP | Gravity.RIGHT;
        clp.topMargin = Ui.dp(ctx, 40);
        clp.rightMargin = Ui.dp(ctx, 6);
        root.addView(close, clp);
        mElements.add(close);

        mCard = new ImageView(ctx);
        mCard.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mCard.setBackground(Ui.placeholder(ctx, 18));
        mCard.setClipToOutline(true);
        mCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(mCtx, 18));
            }
        });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                Ui.dp(ctx, 196), Ui.dp(ctx, 252));
        cp.gravity = Gravity.CENTER;
        root.addView(mCard, cp);
        mElements.add(mCard);

        mStatus = new TextView(ctx);
        mStatus.setTextColor(0xCCFFCC80);
        mStatus.setTextSize(12);
        mStatus.setGravity(Gravity.CENTER);

        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = Ui.dp(ctx, 10);
        bottom.addView(mStatus, slp);

        mAccept = new TextView(ctx);
        mAccept.setTextSize(14);
        mAccept.setTextColor(0xFF1C1C1C);
        mAccept.setGravity(Gravity.CENTER);
        mAccept.setBackground(Ui.rounded(ctx, 0xFFF5F1EC, 23));
        mAccept.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { listener.onAccept(); }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                (int) (W * 0.75f), Ui.dp(ctx, 44));
        bottom.addView(mAccept, ap);

        TextView reject = new TextView(ctx);
        reject.setText("拒绝");
        reject.setTextSize(14);
        reject.setTextColor(0xFFD5D5D5);
        reject.setGravity(Gravity.CENTER);
        reject.setBackground(Ui.rounded(ctx, 0x2EFFFFFF, 23));
        reject.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { listener.onReject(); }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                (int) (W * 0.75f), Ui.dp(ctx, 44));
        rp.topMargin = Ui.dp(ctx, 10);
        bottom.addView(reject, rp);

        TextView brand = new TextView(ctx);
        brand.setText("∞  一碰互联");
        brand.setTextColor(0x99FFFFFF);
        brand.setTextSize(12);
        brand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(ctx, 14);
        bottom.addView(brand, bp);

        FrameLayout.LayoutParams bpp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bpp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        bpp.bottomMargin = Ui.dp(ctx, 22);
        root.addView(bottom, bpp);
        mElements.add(bottom);

        mGlow = new GlowView(ctx);
        root.addView(mGlow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mGlow.startBreathing();

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, b.top, 0, b.bottom);
                return insets;
            }
        });

        // 碰完先只留"背景+光效":所有界面元素隐藏,等发送方发送后再显现
        hideElements();
    }

    private void hideElements() {
        for (View v : mElements) v.setAlpha(0f);
    }

    /** 发送方发送后:界面元素淡入出现(450ms)。 */
    public void reveal() {
        for (View v : mElements) {
            v.animate().alpha(1f).setDuration(450).start();
        }
    }

    public void bind(String peerLabel, int count) {
        mTitle.setText("来自 " + peerLabel + " 的分享");
        mAccept.setText("接收 " + count + " 张照片");
        mStatus.setText("");
        mCard.setImageBitmap(null);
        mCard.setBackground(Ui.placeholder(mCtx, 18));
    }

    public void setPreview(Bitmap bmp) {
        if (bmp == null) return;
        mCard.setImageBitmap(bmp);
        mBackdrop.setImageBitmap(bmp);
    }

    public void setPreviewCount(int n) {
        if (n > 0) mAccept.setText("接收 " + n + " 张照片");
    }

    public void setStatus(String s) {
        mStatus.setText(s == null ? "" : s);
    }

    public void dismiss() {
        root.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override public void run() {
                Overlay.get(mCtx).hide(root);
            }
        }).start();
    }
}
