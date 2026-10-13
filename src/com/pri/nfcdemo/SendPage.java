package com.pri.nfcdemo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 「发送给 用户XXXX」发送确认页 —— 逐项对齐网页版:
 * 半透明底(背后应用可见) + 边缘光效(呼吸) + 顶部虹彩柔光 +
 * 中央卡片堆叠(196dp 宽 / 圆角 18 / 呼吸缩放) + 标题/上滑提示/拖柄/品牌 + 右上 ✕。
 */
public class SendPage {

    public interface Listener {
        void onSwipedSend();
        void onClose();
    }

    public final FrameLayout root;
    private final Context mCtx;
    private final Listener mListener;
    private final FrameLayout mStack;
    private final ImageView mBackdrop;
    private final TextView mTitle;
    private final TextView mHint;
    private final TextView mStatus;
    private float mDownY;
    private boolean mSendTriggered;
    private String mHintText = "";

    public SendPage(Context ctx, Listener listener) {
        mCtx = ctx;
        mListener = listener;
        int W = ctx.getResources().getDisplayMetrics().widthPixels;

        root = new FrameLayout(ctx);
        root.setBackgroundColor(0x73000000); // rgba(10,10,12,.45):背后应用可见
        mBackdrop = new ImageView(ctx);
        mBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mBackdrop.setAlpha(0.75f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { mBackdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    90, 90, android.graphics.Shader.TileMode.CLAMP)); } catch (Throwable ignored) {}
        }
        root.addView(mBackdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 标题(顶 54dp / 15sp)
        mTitle = new TextView(ctx);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(15);
        mTitle.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        tp.topMargin = Ui.dp(ctx, 54);
        root.addView(mTitle, tp);

        // 关闭(右上,top 40 / right 14)
        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(0xFFDDDDDD);
        close.setTextSize(19);
        close.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mListener.onClose(); }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.TOP | Gravity.RIGHT;
        clp.topMargin = Ui.dp(ctx, 40);
        clp.rightMargin = Ui.dp(ctx, 6);
        root.addView(close, clp);

        // 卡片堆叠(居中,主卡呼吸)
        mStack = new FrameLayout(ctx);
        root.addView(mStack, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ValueAnimator cv = ValueAnimator.ofFloat(1.0f, 1.028f);
        cv.setDuration(3400);
        cv.setRepeatMode(ValueAnimator.REVERSE);
        cv.setRepeatCount(ValueAnimator.INFINITE);
        cv.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float v = (Float) a.getAnimatedValue();
                mStack.setScaleX(v);
                mStack.setScaleY(v);
            }
        });
        cv.start();

        // 底部:提示 / 状态 / 拖柄 / 品牌(对齐网页版 bottom 值)
        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);

        mHint = new TextView(ctx);
        mHint.setTextColor(Color.WHITE);
        mHint.setTextSize(14);
        mHint.setGravity(Gravity.CENTER);
        bottom.addView(mHint);

        mStatus = new TextView(ctx);
        mStatus.setTextColor(0xCCFFCC80);
        mStatus.setTextSize(12);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        bottom.addView(mStatus);

        View handle = new View(ctx);
        handle.setBackground(Ui.rounded(ctx, 0x99FFFFFF, 3));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(ctx, 46), Ui.dp(ctx, 5));
        hp.topMargin = Ui.dp(ctx, 14);
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        bottom.addView(handle, hp);

        TextView brand = new TextView(ctx);
        brand.setText("∞  一碰互联");
        brand.setTextColor(0x99FFFFFF);
        brand.setTextSize(12);
        brand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(ctx, 16);
        bottom.addView(brand, bp);

        FrameLayout.LayoutParams bpp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bpp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        bpp.bottomMargin = Ui.dp(ctx, 22);
        root.addView(bottom, bpp);

        // 边缘光效(最后加,盖在最上层)
        GlowView glow = new GlowView(ctx);
        root.addView(glow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glow.startBreathing();

        // 全屏上滑手势(拖动卡片堆叠)
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownY = e.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = Math.min(0f, e.getY() - mDownY);
                        mStack.setTranslationY(dy);
                        mStack.setRotation(dy / 60f);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dy = e.getY() - mDownY;
                        if (dy < -Ui.dp(mCtx, 130) && !mSendTriggered) {
                            mSendTriggered = true;
                            mHint.setText("正在发送…");
                            mStack.animate().translationY(-root.getHeight()).alpha(0f)
                                    .rotation(-7f).setDuration(340)
                                    .withEndAction(new Runnable() {
                                        @Override public void run() { mListener.onSwipedSend(); }
                                    }).start();
                        } else {
                            mStack.animate().translationY(0f).rotation(0f)
                                    .setDuration(180).start();
                        }
                        return true;
                    }
                }
                return false;
            }
        });

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, b.top, 0, b.bottom);
                return insets;
            }
        });
    }

    /** 绑定数据:照片 / 标题全文 / 张数(无照片显示占位渐变卡)。 */
    public void bind(List<android.net.Uri> uris, String titleText, int count) {
        mSendTriggered = false;
        mStack.setTranslationY(0f);
        mStack.setRotation(0f);
        mStack.setAlpha(1f);
        mTitle.setText(titleText);
        mHintText = "↑ 上滑发送 " + count + " 张照片";
        mHint.setText(mHintText);
        mStatus.setText("");
        mStack.removeAllViews();

        if (uris != null && !uris.isEmpty()) {
            Ui.loadImage(mCtx, mBackdrop, uris.get(0), 720);
            int show = Math.min(2, uris.size());
            for (int i = show - 1; i >= 0; i--) {
                addCard(uris.get(i), i, null);
            }
        } else {
            addCard(null, 0, count);
        }
    }

    private void addCard(android.net.Uri uri, int depth, Integer placeholderCount) {
        int w = Ui.dp(mCtx, 196);
        int h = Ui.dp(mCtx, 252);
        ImageView card = new ImageView(mCtx);
        card.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.setBackground(Ui.placeholder(mCtx, 18));
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(mCtx, 18));
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        mStack.addView(card, lp);
        card.setTranslationY(depth * Ui.dp(mCtx, 10));
        card.setScaleX(1f - depth * 0.06f);
        card.setScaleY(1f - depth * 0.06f);
        card.setAlpha(1f - depth * 0.25f);
        if (uri != null) {
            Ui.loadImage(mCtx, card, uri, 900);
        } else if (placeholderCount != null) {
            TextView t = new TextView(mCtx);
            t.setText("" + placeholderCount);
            t.setTextColor(0x66FFFFFF);
            t.setTextSize(56);
            t.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(w, h);
            tp.gravity = Gravity.CENTER;
            mStack.addView(t, tp);
        }
    }

    public void setStatus(String s) {
        mStatus.setText(s == null ? "" : s);
    }

    public void setTitle(String t) {
        mTitle.setText(t);
    }

    public void resetStack() {
        mSendTriggered = false;
        mStack.animate().translationY(0f).rotation(0f).alpha(1f).setDuration(180).start();
        mHint.setText(mHintText);
    }

    public void dismiss() {
        root.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override public void run() {
                Overlay.get(mCtx).hide(root);
            }
        }).start();
    }
}
