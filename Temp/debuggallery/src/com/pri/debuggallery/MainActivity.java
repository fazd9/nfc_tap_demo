package com.pri.debuggallery;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context; //pri added by zhuyang,Tap-time pull reply helper takes a Context,20261019
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * 调试相册:复现产品视频的"相册多选 → 分享"发送流程。
 *  - 长按进入多选,点选/取消,顶部显示"已选择 N 项"
 *  - 「分享」:走系统分享面板(标准解耦入口,任选应用)
 *  - 「一碰互联」:直接拉起 com.pri.nfctouchdemo(NFC 碰一碰 Demo),即"相册工具栏专属按钮"式集成
 *
 * pri added by zhuyang,The tap-to-share model this app stands in for (see docs/DEBUG_GALLERY_VIEWER.md):,20261016,start
 * pri added by zhuyang,  * whatever the user is looking at IS the selection - one photo opened full screen, or the ticked tiles;,20261016
 * pri added by zhuyang,  * it is published to TapService as soon as it changes, and cleared the moment it stops being what the,20261016
 * pri added by zhuyang,    user is looking at (back to the grid, leaving the app), so a later tap can never send a stale photo,20261016
 * pri added by zhuyang,  * the clear is what the service keys "a gallery published this" on, which is why a published selection,20261016
 * pri added by zhuyang,    outranks the service's screen-reading fallback,20261016,end
 */
public class MainActivity extends Activity {

    //pri added by zhuyang,The tap-to-share service this gallery publishes to / clears with; the contract is documented on,20261016
    //pri added by zhuyang,ACTION_CLEAR_SELECTION in com.pri.nfctouchdemo.nfc.TapService,20261016
    private static final String SVC_PKG = "com.pri.nfctouchdemo";
    private static final String SVC_CLS = "com.pri.nfctouchdemo.nfc.TapService";
    private static final String ACTION_CLEAR_SELECTION = "com.pri.nfcdemo.CLEAR_SELECTION";
    //pri added by zhuyang,Tap-time pull: the action com.pri.nfctouchdemo broadcasts before each tap,20261019
    private static final String ACTION_REQUEST_SELECTION = "com.pri.nfcdemo.REQUEST_SELECTION";
    //pri added by zhuyang,Longest side the full-screen photo is decoded to: the file can be far bigger than the panel,20261016
    private static final int VIEW_MAX = 2048;

    //pri added by zhuyang,Tap-time pull: what is selected on screen right now, readable from the manifest receiver even after,20261019,start
    //pri added by zhuyang,the activity was recreated. Maintained by publishCurrent / cleared by clearPublished,20261019,end
    private static volatile ArrayList<Uri> sCurrentUris = new ArrayList<Uri>();

    private TextView mTitle;
    private TextView mHint;
    private Button mCancel;
    private GridView mGrid;
    private Adapter mAdapter;

    //pri added by zhuyang,The "viewing" state: the photo the user opened, drawn full screen over the grid. It is a state of this,20261016,start
    //pri added by zhuyang,activity rather than a second one, so "back to the grid" and "left the app" are plain lifecycle events of,20261016
    //pri added by zhuyang,a single screen - which is what keeps the clear-on-leave rule below true,20261016,end
    private FrameLayout mViewer;
    private ImageView mViewerImage;
    private long mViewingId = -1;   //pri added by zhuyang,-1 = the grid is what the user is looking at,20261016
    //pri added by zhuyang,True while a selection published by this activity is what the service still holds. It gates the clear,20261016
    //pri added by zhuyang,intent, so the app never wakes the service up just to say "nothing" - least of all from the background,20261016
    private boolean mPublished;

    private final ArrayList<Long> mIds = new ArrayList<Long>();
    private final LinkedHashSet<Long> mSelected = new LinkedHashSet<Long>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        ensurePerm();
        //pri added by zhuyang,See handleBack(): this is the callback that back actually reaches on Android 16 / targetSdk 36,20261016
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override public void onBackInvoked() { handleBack(); }
                    });
        }
    }

    //pri added by zhuyang,Clear-on-leave. The rule the user asked for: once this screen is not what the user is looking at, the,20261016,start
    //pri added by zhuyang,service must hold nothing - a leftover selection is sent by the next tap while the user believes they are,20261016
    //pri added by zhuyang,sending what is in front of them (this is a bug that was actually hit with PENDING),20261016
    //pri added by zhuyang,,20261016
    //pri added by zhuyang,onStop is the point that means "left the gallery" (home / recents / another app / screen off). onPause was,20261016
    //pri added by zhuyang,deliberately not used: it also fires for anything laid over this window, and during a tap the send page is,20261016
    //pri added by zhuyang,exactly that - clearing there would throw away the very selection the round is about to send,20261016
    //pri added by zhuyang,,20261016
    //pri added by zhuyang,The UI is reset with it (viewer closed, ticks dropped), so what the user sees and what the service holds can,20261016
    //pri added by zhuyang,never disagree when they come back,20261016
    @Override
    protected void onStop() {
        super.onStop();
        resetToGrid();
        mSelected.clear();
        clearPublished();
        refreshBars();
    }

    //pri added by zhuyang,onDestroy catches what onStop cannot: the activity going away while it is already stopped (back pressed from,20261016
    //pri added by zhuyang,the background, rotation, the activity being reclaimed). A process killed outright cannot be caught at all - the,20261016
    //pri added by zhuyang,service side of that is the leftover-selection guard,20261016
    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearPublished();
    }

    //pri added by zhuyang,Back from the viewer is the "back to the grid" transition: it clears (closeViewer -> updateBars -> publish,20261016
    //pri added by zhuyang,"nothing"). Back from the grid leaves the app, which onStop clears,20261016,start
    //pri added by zhuyang,Android 16 turns predictive back on by default for apps targeting SDK 36 and onBackPressed() is then never,20261016
    //pri added by zhuyang,called - measured on the Armor X17, where back from the viewer finished the whole activity instead of,20261016
    //pri added by zhuyang,returning to the grid. So the dispatcher callback is what actually runs there; onBackPressed stays for the,20261016
    //pri added by zhuyang,platforms that still deliver it,20261016
    private void handleBack() {
        if (mViewingId >= 0) { closeViewer(); return; }
        finish();
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }
    //pri added by zhuyang,Back from the viewer is the "back to the grid" transition: it clears (closeViewer -> updateBars -> publish,20261016,end
    //pri added by zhuyang,Clear-on-leave. The rule the user asked for: once this screen is not what the user is looking at, the,20261016,end

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        FrameLayout outer = new FrameLayout(this); //pri added by zhuyang,Root: the grid page, with the full-screen viewer on top of it,20261016
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
        //pri modify by zhuyang,The grid page is now a child of the frame so the viewer can cover it,20261016,start
        outer.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildViewer(outer);
        setContentView(outer);
        //pri modify by zhuyang,The grid page is now a child of the frame so the viewer can cover it,20261016,end
    }

    //pri added by zhuyang,The single-photo view: black backdrop, the photo fit to the screen, a top bar with "back to grid".,20261016,start
    //pri added by zhuyang,Kept INVISIBLE (not GONE) so it is laid out and its window insets are applied before it is first shown,20261016
    private void buildViewer(FrameLayout outer) {
        mViewer = new FrameLayout(this);
        mViewer.setBackgroundColor(0xFF000000);
        mViewer.setVisibility(View.INVISIBLE);
        mViewer.setClickable(true); //pri added by zhuyang,Swallow taps: the grid behind must not react while a photo is open,20261016

        mViewerImage = new ImageView(this);
        mViewerImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mViewer.addView(mViewerImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(8), dp(14), dp(8));

        Button back = new Button(this);
        back.setText("‹ 网格");
        back.setTextSize(13);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeViewer(); }
        });
        bar.addView(back);

        TextView label = new TextView(this);
        label.setText("查看中 · 碰一碰发送这张");
        label.setTextColor(0xFFEEEEEE);
        label.setTextSize(14);
        bar.addView(label);

        mViewer.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mViewer.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, b.top, 0, b.bottom);
                return insets;
            }
        });
        outer.addView(mViewer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
    //pri added by zhuyang,The single-photo view: black backdrop, the photo fit to the screen, a top bar with "back to grid".,20261016,end

    //pri modify by zhuyang,The intent is now built from a Uri list: the selection is not always the ticked set any more (an,20261016,start
    //pri modify by zhuyang,open photo is a selection of one),20261016,end
    private static Intent buildShareIntent(ArrayList<Uri> uris) {
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("image/*");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // 关键:把读取权限授予接收方
        return i;
    }

    private Uri mediaUri(long id) {
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }

    //pri modify by zhuyang,Publishes whatever is on screen right now (the open photo, else the ticked set); nothing on screen,20261016,start
    //pri modify by zhuyang,means clear, so the service never keeps a selection the user has moved on from,20261016,end
    private void publishCurrent() {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (mViewingId >= 0) {
            uris.add(mediaUri(mViewingId));
        } else {
            for (Long id : mSelected) uris.add(mediaUri(id));
        }
        if (uris.isEmpty()) { clearPublished(); return; }
        sCurrentUris = uris; //pri added by zhuyang,Tap-time pull: the receiver answers with exactly this list,20261019
        try {
            Intent i = buildShareIntent(uris);
            i.setComponent(new ComponentName(SVC_PKG, SVC_CLS));
            // 显式授权:服务侧要能长期读取这些 URI(否则卡片读不到图片)
            for (Uri u : uris) grantUriPermission(SVC_PKG, u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startService(i);
            mPublished = true;
        } catch (Throwable t) {
            android.util.Log.e("DebugGallery", "publish fail " + t);
        }
    }

    //pri added by zhuyang,Undo the last publish (the contract's clear half). Only ever sent when this activity published,20261016,start
    //pri added by zhuyang,something: with nothing published there is nothing to clear, and the service must not be poked awake - from,20261016
    //pri added by zhuyang,the background that would also be a background service start,20261016,end
    private void clearPublished() {
        sCurrentUris = new ArrayList<Uri>(); //pri added by zhuyang,Tap-time pull: nothing on screen must answer as a selection,20261019
        if (!mPublished) return;
        mPublished = false;
        try {
            Intent i = new Intent(ACTION_CLEAR_SELECTION);
            i.setComponent(new ComponentName(SVC_PKG, SVC_CLS));
            startService(i);
        } catch (Throwable t) {
            android.util.Log.e("DebugGallery", "clear fail " + t);
        }
    }

    //pri added by zhuyang,Tap-time pull: answer the service with what is selected right now. An empty answer is a CLEAR reply,20261019,start
    //pri added by zhuyang,so the service never falls back to a stale PENDING just because there was nothing to report,20261019,end
    public static void republishCurrentSelection(Context context) {
        ArrayList<Uri> uris = sCurrentUris;
        try {
            if (uris.isEmpty()) {
                Intent c = new Intent(ACTION_CLEAR_SELECTION);
                c.setComponent(new ComponentName(SVC_PKG, SVC_CLS));
                context.startService(c);
                return;
            }
            Intent i = buildShareIntent(new ArrayList<Uri>(uris));
            i.setComponent(new ComponentName(SVC_PKG, SVC_CLS));
            for (Uri u : uris) context.grantUriPermission(SVC_PKG, u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startService(i);
        } catch (Throwable t) {
            android.util.Log.e("DebugGallery", "republish fail " + t);
        }
    }

    //pri added by zhuyang,Viewing one photo: open it full screen and publish that one photo as the selection,20261016
    private void openViewer(long id) {
        mViewingId = id;
        mViewerImage.setImageBitmap(fullImage(id));
        mViewer.setVisibility(View.VISIBLE);
        updateBars();
    }

    //pri added by zhuyang,Back to the grid: this is the transition the user asked to clear on - the photo is no longer what they are,20261016
    //pri added by zhuyang,looking at, so updateBars() publishes "nothing" and the service drops it,20261016
    private void closeViewer() {
        if (mViewingId < 0) return;
        resetToGrid();
        updateBars();
    }

    //pri added by zhuyang,Leave the viewing state without touching the service (the caller decides what should be pending),20261016
    private void resetToGrid() {
        if (mViewingId < 0) return;
        mViewingId = -1;
        mViewerImage.setImageDrawable(null);
        mViewer.setVisibility(View.INVISIBLE);
    }

    //pri added by zhuyang,Decode the photo at roughly panel size; read only, the file is never modified,20261016
    private Bitmap fullImage(long id) {
        Uri u = mediaUri(id);
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(u);
            try { BitmapFactory.decodeStream(in, null, o); } finally { in.close(); }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int scale = 1;
            while (Math.max(o.outWidth, o.outHeight) / (scale * 2) > VIEW_MAX) scale *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            InputStream in2 = getContentResolver().openInputStream(u);
            try { return BitmapFactory.decodeStream(in2, null, o2); } finally { in2.close(); }
        } catch (Throwable t) {
            android.util.Log.e("DebugGallery", "viewer decode fail " + t);
            return null;
        }
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
        refreshBars();
        publishCurrent(); //pri modify by zhuyang,Every selection change is synced to the service at once; nothing selected = clear,20261016
    }

    //pri added by zhuyang,Screen only - for callers that have already settled what the service should hold,20261016
    private void refreshBars() {
        boolean sel = !mSelected.isEmpty();
        mTitle.setText(sel ? "已选择 " + mSelected.size() + " 项" : "调试相册");
        mHint.setText(sel ? "↑ 直接碰一碰对方手机即可发送" : "长按照片进入多选,点开一张直接碰传");
        mCancel.setVisibility(sel ? View.VISIBLE : View.GONE);
        mAdapter.notifyDataSetChanged();
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
                    //pri modify by zhuyang,Tap while ticking = tick/untick (unchanged); tap with nothing ticked opens the photo,20261016
                    if (!mSelected.isEmpty()) toggle(id);
                    else openViewer(id);
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
