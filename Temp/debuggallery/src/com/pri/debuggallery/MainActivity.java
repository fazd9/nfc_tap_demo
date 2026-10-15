package com.pri.debuggallery;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * 调试相册:复现产品视频的"相册多选 → 分享"发送流程。
 *  - 长按进入多选,点选/取消,顶部显示"已选择 N 项"
 *  - 「分享」:走系统分享面板(标准解耦入口,任选应用)
 *  - 「一碰互联」:直接拉起 com.pri.nfctouchdemo(NFC 碰一碰 Demo),即"相册工具栏专属按钮"式集成
 */
public class MainActivity extends Activity {

    private TextView mTitle;
    private TextView mHint;
    private Button mCancel;
    private GridView mGrid;
    private Adapter mAdapter;

    private final ArrayList<Long> mIds = new ArrayList<Long>();
    private final LinkedHashSet<Long> mSelected = new LinkedHashSet<Long>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        ensurePerm();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF6F6F6);

        // 顶部栏:取消 + 标题
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(10), dp(14), dp(10));
        top.setBackgroundColor(0xFFFFFFFF);

        mCancel = new Button(this);
        mCancel.setText("取消");
        mCancel.setTextSize(13);
        mCancel.setVisibility(View.GONE);
        mCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                mSelected.clear();
                updateBars();
            }
        });
        top.addView(mCancel);

        mTitle = new TextView(this);
        mTitle.setText("调试相册");
        mTitle.setTextSize(18);
        mTitle.setGravity(Gravity.CENTER);
        mTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(mTitle);
        root.addView(top);

        // 网格
        mGrid = new GridView(this);
        mGrid.setNumColumns(3);
        mGrid.setHorizontalSpacing(dp(3));
        mGrid.setVerticalSpacing(dp(3));
        mGrid.setPadding(dp(3), dp(3), dp(3), dp(3));
        mAdapter = new Adapter();
        mGrid.setAdapter(mAdapter);
        root.addView(mGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部操作栏(选中后出现)
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(10), dp(8), dp(10), dp(8));
        bottom.setBackgroundColor(0xFFFFFFFF);

        mHint = new TextView(this);
        mHint.setTextSize(14);
        mHint.setTextColor(0xFF666666);
        mHint.setGravity(Gravity.CENTER);
        mHint.setPadding(dp(10), dp(12), dp(10), dp(12));
        mHint.setText("长按照片进入多选");
        bottom.addView(mHint);
        root.addView(bottom);

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, b.top, 0, b.bottom);
                return insets;
            }
        });
        setContentView(root);
    }

    private Intent buildShareIntent() {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        for (Long id : mSelected) {
            uris.add(ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
        }
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("image/*");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // 关键:把读取权限授予接收方
        return i;
    }

    private void toggle(long id) {
        if (mSelected.contains(id)) {
            mSelected.remove(id);
        } else {
            mSelected.add(id);
        }
        updateBars();
    }

    private void updateBars() {
        boolean sel = !mSelected.isEmpty();
        mTitle.setText(sel ? "已选择 " + mSelected.size() + " 项" : "调试相册");
        mHint.setText(sel ? "↑ 直接碰一碰对方手机即可发送" : "长按照片进入多选");
        mCancel.setVisibility(sel ? View.VISIBLE : View.GONE);
        if (sel) pushSelection();
        mAdapter.notifyDataSetChanged();
    }

    /** 把"当前选中照片"实时推给碰一碰服务(相册与功能解耦的标准对接方式)。 */
    private void pushSelection() {
        try {
            Intent i = buildShareIntent();
            i.setAction(Intent.ACTION_SEND_MULTIPLE);
            i.setComponent(new ComponentName("com.pri.nfctouchdemo", "com.pri.nfctouchdemo.nfc.TapService"));
            // 显式授权:服务侧要能长期读取这些 URI(否则卡片读不到图片)
            for (Long id : mSelected) {
                Uri u = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                grantUriPermission("com.pri.nfctouchdemo", u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            startService(i);
        } catch (Throwable t) {
            android.util.Log.e("DebugGallery", "push fail " + t);
        }
    }

    private void ensurePerm() {
        try {
            String p = Build.VERSION.SDK_INT >= 33
                    ? "android.permission.READ_MEDIA_IMAGES"
                    : android.Manifest.permission.READ_EXTERNAL_STORAGE;
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{p}, 1);
            } else {
                loadImages();
            }
        } catch (Throwable t) {
            loadImages();
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        loadImages();
    }

    private void loadImages() {
        mIds.clear();
        try {
            Cursor c = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID},
                    null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c != null) {
                while (c.moveToNext() && mIds.size() < 90) {
                    mIds.add(c.getLong(0));
                }
                c.close();
            }
        } catch (Throwable t) {
            android.util.Log.e("DebugGallery", "query fail " + t);
        }
        mTitle.setText(mIds.isEmpty() ? "没有读到图片(检查相册权限)" : "调试相册");
        mAdapter.notifyDataSetChanged();
    }

    private Bitmap thumb(long id) {
        try {
            Uri u = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            if (Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(u, new Size(300, 300), null);
            }
            return MediaStore.Images.Media.getBitmap(getContentResolver(), u);
        } catch (Throwable t) {
            return null;
        }
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return mIds.size(); }
        @Override public Object getItem(int p) { return mIds.get(p); }
        @Override public long getItemId(int p) { return mIds.get(p); }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            final long id = mIds.get(pos);
            FrameLayout fl = new FrameLayout(MainActivity.this);
            int size = mGrid.getColumnWidth();
            fl.setLayoutParams(new GridView.LayoutParams(size, size));

            ImageView iv = new ImageView(MainActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFFDDDDDD);
            Bitmap bmp = thumb(id);
            if (bmp != null) iv.setImageBitmap(bmp);
            fl.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            fl.setLongClickable(true);
            fl.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { toggle(id); return true; }
            });
            fl.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!mSelected.isEmpty()) toggle(id);
                }
            });

            if (mSelected.contains(id)) {
                TextView chk = new TextView(MainActivity.this);
                chk.setText("✓");
                chk.setTextColor(Color.WHITE);
                chk.setTextSize(13);
                chk.setGravity(Gravity.CENTER);
                GradientDrawable g = new GradientDrawable();
                g.setColor(0xFF2196F3);
                g.setShape(GradientDrawable.OVAL);
                chk.setBackground(g);
                int s = (int) (size * 0.24f);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s);
                lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                lp.rightMargin = dp(6);
                lp.bottomMargin = dp(6);
                fl.addView(chk, lp);
            }
            return fl;
        }
    }
}
