package com.pri.nfctouchdemo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pri.nfctouchdemo.HomeActivity
import com.pri.nfctouchdemo.R
import com.pri.nfctouchdemo.anim.Timeline
import com.pri.nfctouchdemo.gfx.ImageLoader
import com.pri.nfctouchdemo.gfx.RippleSurfaceView
import com.pri.nfctouchdemo.nfc.TapService
import com.pri.nfctouchdemo.ui.OverlayView

/**
 * 动效层宿主：整屏悬浮窗 + 前台服务，动效本体盖在**别的应用之上**，服务常驻。
 *
 * 这个类是从 TouchAnimeDemo（`com.pri.touchanimedemo.service.OverlayService`）搬过来的，
 * 融合 NFC 之后它多了一个身份：**「一碰互联」的两张确认页都由它来播** ——
 * 原来 nfc demo 里那套 `SendPage` / `ReceivePage`（程序化拼 View）已经删掉，
 * 确认页统一由 [OverlayView] 这套 Canvas 动效承担。
 *
 * **谁给它图，它就播什么** —— 服务自己不截屏、不碰 NFC、不认识 Xfer：
 *
 * - 启动页 [HomeActivity] 选两张图，用 [intent] 起这个服务：一张当背景（水波折射的就是它），
 *   一张当要发送的照片（面板卡片里那张）—— 这是**纯动效演示**路径；
 * - NFC 路径：常驻服务 [TapService] 碰通之后调 [intent]（发送侧）或 [receiveIntent]（接收侧），
 *   并且用 [statusIntent] 推「正在连接对方…」这类状态、用 [photoIntent] 换接收页那张照片。
 *   发送侧拿不到「对方屏幕画面」，就用要发送的照片自己当折射背景。
 * - 用户在动效页上的动作（上滑发送 / 点接收 / 点拒绝）**不在这里处理业务**，
 *   而是用 [ACTION_UI_SEND_CONFIRMED] 这类 action 回执给 [TapService]，由它接着调 Xfer。
 *
 * 窗口是 `FORMAT_TRANSLUCENT` 的、根布局不设背景；动效（GL 水波 + [OverlayView]）
 * 由 [Timeline] 驱动。整屏窗口会把下面的触摸一起吃掉，所以留了一个「触摸穿透」开关 ——
 * 它只能放通知里切：窗口一旦 `FLAG_NOT_TOUCHABLE`，它自己的按钮就点不到了。
 *
 * 动作：
 * - [ACTION_START]：带两张图的 Uri（缺省则沿用上一次那两张）→ 解码 → 建窗 → 播**发送确认页**；
 * - [ACTION_RECEIVE]：带对方标识 / 张数（照片可以后补）→ 建窗 → 播**接收确认页**；
 * - [ACTION_PHOTO]：只换卡片那张照片（接收时预览/整图到了再推，顺便换掉折射背景）；
 * - [ACTION_STATUS]：更新动效页底部的状态行；
 * - [ACTION_REPLAY]：不换图，重播一遍；
 * - [ACTION_HIDE]：只摘窗口，服务继续常驻；
 * - [ACTION_TOGGLE_TOUCH]：切穿透；
 * - [ACTION_STOP]：退出常驻。
 *
 * **无 action 的启动**只进前台待命，绝不碰动画。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var rootView: View? = null
    private lateinit var rippleView: RippleSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var timeline: Timeline

    /** 当前是哪一种确认页：发送（上滑发送）还是接收（接收 / 拒绝）。 */
    private var mode = OverlayView.Mode.SEND

    /** 状态栏 / 刘海那一条的高度，发送面板往下让开。 */
    private var systemTop = 0
    private var touchThrough = false

    /** 窗口是否已经挂上（[ACTION_HIDE] 之后仍然留着视图树）。 */
    private var attached = false

    /** 建窗后延迟自动演示一遍的那个回调，拆窗时要撤掉。 */
    private var startRunnable: Runnable? = null

    /** 通知里「重播」的 5 秒等待，等待期间 [replayPending] 让通知文案变一下。 */
    private var replayRunnable: Runnable? = null
    private var replayPending = false

    /** 最近一次拿到的两张图：重播 / 重复启动都靠它。 */
    private var backdropUri: Uri? = null
    private var photoUri: Uri? = null

    /** 已经解好的位图，以及它们对应的 Uri（换图才重新解码）。 */
    private var backdropBitmap: Bitmap? = null
    private var photoBitmap: Bitmap? = null

    /** 卡片标题 / 副标题（和 [photoBitmap] 配套）：擦干净后复用缓存时要一起灌回去。 */
    private var photoInfo: Pair<String, String>? = null
    private var decodedBackdrop: Uri? = null
    private var decodedPhoto: Uri? = null

    /**
     * 上面那对位图是**哪一种确认页**解出来的：true = 接收侧（一张图兼当卡片与折射背景）。
     *
     * 发送 / 接收来回切的时候，即使 Uri 一模一样（比如碰完自己又选了同一张图），
     * 两边的解码口径也不同（发送侧的背景是 decodeCover、卡片是 decode），所以必须一起比对，
     * 否则会复用上一轮的图、播错内容。null = 还没解过任何图。
     */
    private var decodedReceive: Boolean? = null

    /** 对端标识（「用户XXXX」）：发送侧是「发送给 X」，接收侧是「来自 X的分享」。 */
    private var peerLabel: String? = null
    private var peerCount = 0

    /** 动效页底部的状态行文案（「正在连接对方…」/ 失败原因），由 NFC 层推过来。 */
    private var statusText: String? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSendFlow(intent)
            ACTION_RECEIVE -> startReceiveFlow(intent)
            ACTION_PHOTO -> updatePhoto(intent)
            ACTION_STATUS -> updateStatus(intent)
            ACTION_PEER -> updatePeer(intent)
            ACTION_REPLAY -> scheduleReplay()
            ACTION_HIDE -> hideDemo()
            ACTION_TOGGLE_TOUCH -> setTouchThrough(!touchThrough)
            ACTION_STOP -> stopEverything()
            // 没有任何 action 的启动 = 只进前台待命
            else -> ensureForeground()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = rootView ?: return
        // 旋转 / 手势导航切换后系统栏高度和屏幕尺寸都会变
        applyBarInsets()
        requestFullScreen()
        val size = displaySize()
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.width != size.x || params.height != size.y) {
            params.width = size.x
            params.height = size.y
            // 窗口已被摘掉时不能动 WindowManager（见 updateWindowLayout），
            // 参数先更新好，等下次 attach 生效
            updateWindowLayout(view, params)
        }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 演示流程

    /**
     * 发送侧：开始（或换图重播）发送确认页。
     *
     * 有 extra 就用新的图，没有就沿用上一次那两张 —— 于是"[ACTION_REPLAY]"和
     * "服务被重复启动"都不会丢图。
     */
    private fun startSendFlow(intent: Intent?) {
        mode = OverlayView.Mode.SEND
        uriExtra(intent, EXTRA_BACKDROP)?.let { backdropUri = it }
        uriExtra(intent, EXTRA_PHOTO)?.let { photoUri = it }
        // 发送侧的对端标识：NFC 层不给就退回动效层自带的默认文案
        if (intent?.hasExtra(EXTRA_PEER) == true) peerLabel = intent.getStringExtra(EXTRA_PEER)
        peerCount = countExtra(intent, peerCount)
        if (intent?.hasExtra(EXTRA_STATUS) == true) statusText = intent.getStringExtra(EXTRA_STATUS)

        val backdrop = backdropUri
        val photo = photoUri
        if (backdrop == null || photo == null) {
            Toast.makeText(this, R.string.toast_need_images, Toast.LENGTH_SHORT).show()
            ensureForeground()
            return
        }
        // 悬浮窗权限只能由用户去系统设置里给；没给就只保持常驻，不动画
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_SHORT).show()
            ensureForeground()
            return
        }

        if (rootView == null) buildOverlay()
        if (rootView == null) return
        // 复用的视图树里还留着上一轮的画面：先擦干净，新内容随后由解码回调灌进来
        wipeVisuals()
        ensureForeground()

        val reuse = backdropBitmap != null && photoBitmap != null &&
            decodedBackdrop == backdrop && decodedPhoto == photo && decodedReceive != true
        if (reuse) {
            applyDecodedVisuals() // 已经擦干净了，上一轮解好的位图要重新灌回去
            applyContentAndPlay()
        } else {
            decodeSendImages(backdrop, photo)
        }
    }

    /**
     * 接收侧：播「来自 用户XXXX的分享」接收确认页。
     *
     * 碰通那一刻对方的照片还没到（Xfer 的预览要等连接建立），所以 [EXTRA_PHOTO] 允许为空 ——
     * 那时候用一层纯深色当折射背景（等于"屏幕暗下来 + 涟漪"），等照片到了再由
     * [ACTION_PHOTO] 换上。**不会**因此不播动效。
     */
    private fun startReceiveFlow(intent: Intent?) {
        mode = OverlayView.Mode.RECEIVE
        if (intent?.hasExtra(EXTRA_PEER) == true) peerLabel = intent.getStringExtra(EXTRA_PEER)
        // 没有新的张数就沿用上一轮（重播时不该把「接收 3 张照片」缩回 1 张）
        peerCount = countExtra(intent, if (peerCount > 0) peerCount else 1)
        if (intent?.hasExtra(EXTRA_STATUS) == true) statusText = intent.getStringExtra(EXTRA_STATUS)
        uriExtra(intent, EXTRA_PHOTO)?.let { photoUri = it }

        //pri added by zhuyang,Live switch: a visible SEND page becomes the RECEIVE card in place,20261014,start
        if (rootView != null && attached && mode == OverlayView.Mode.SEND) {
            mode = OverlayView.Mode.RECEIVE
            overlay.setMode(mode)
            overlay.setPeerInfo(peerLabel, peerCount)
            overlay.setStatus(statusText)
            return
        }
        //pri added by zhuyang,Live switch: a visible SEND page becomes the RECEIVE card in place,20261014,end

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_SHORT).show()
            ensureForeground()
            return
        }
        if (rootView == null) buildOverlay()
        if (rootView == null) return
        // 接收页是"上一轮点完拒绝 / 接收之后最容易被留下画面"的那一页，先擦干净
        wipeVisuals()
        ensureForeground()

        val photo = photoUri
        val reuse = photo != null && photoBitmap != null && decodedPhoto == photo && decodedReceive == true
        if (reuse) {
            applyDecodedVisuals()
            applyContentAndPlay()
        } else {
            decodeReceiveImage(photo)
        }
    }

    /**
     * 只换卡片那张照片（接收侧用）：预览图 / 整图到了，卡片和折射背景一起换掉。
     *
     * 窗口还没建起来（用户还没点接收、但对方已经先发过来了）就先只记下来，
     * 等 [startReceiveFlow] 那一轮解码时自然用上。
     */
    private fun updatePhoto(intent: Intent?) {
        val uri = uriExtra(intent, EXTRA_PHOTO) ?: return
        photoUri = uri
        if (rootView == null) return
        // 接收页里这张照片既是卡片也是折射背景；发送页里它只是卡片（背景是用户自己选的那张）
        val isReceive = mode == OverlayView.Mode.RECEIVE
        Thread {
            val picture = ImageLoader.decode(this, uri)
            val background = if (isReceive) ImageLoader.decodeCover(this, uri, displaySize().x, displaySize().y) else null
            val info = ImageLoader.describe(this, uri)
            mainHandler.post {
                if (rootView == null) return@post
                photoBitmap = picture
                decodedPhoto = uri
                decodedReceive = isReceive
                picture?.let { overlay.setCardPhoto(it) }
                background?.let {
                    backdropUri = uri
                    backdropBitmap = it
                    decodedBackdrop = uri
                    rippleView.setGallerySource(it)
                }
                photoInfo = info
                overlay.setCardInfo(info.first, info.second)
            }
        }.start()
    }

    /** 状态行：NFC 层推过来的连接进度 / 失败原因，直接画在动效页底部。 */
    private fun updateStatus(intent: Intent?) {
        statusText = intent?.getStringExtra(EXTRA_STATUS)
        if (rootView != null) overlay.setStatus(statusText)
    }

    /**
     * 就地补正「对端标识 / 张数」（见 [ACTION_PEER]）：标题、上滑提示、卡片副标题、
     * 接收按钮文案全部由 [OverlayView.setPeerInfo] 一次性重算，页面不重启、动画不重播。
     */
    private fun updatePeer(intent: Intent?) {
        if (intent?.hasExtra(EXTRA_PEER) == true) peerLabel = intent.getStringExtra(EXTRA_PEER)
        peerCount = countExtra(intent, peerCount)
        if (rootView != null) overlay.setPeerInfo(peerLabel, peerCount)
    }

    /** 两张图都在后台线程解码 —— 大图放主线程会把首帧卡住。 */
    private fun decodeSendImages(backdrop: Uri, photo: Uri) {
        val screen = displaySize()
        Thread {
            // 背景按屏幕比例裁：着色器是整张铺满屏幕的，不裁会拉变形
            val background = ImageLoader.decodeCover(this, backdrop, screen.x, screen.y)
            val picture = ImageLoader.decode(this, photo)
            val info = ImageLoader.describe(this, photo)
            mainHandler.post {
                if (rootView == null) return@post
                backdropBitmap = background
                photoBitmap = picture
                decodedBackdrop = backdrop
                decodedPhoto = photo
                decodedReceive = false
                background?.let { rippleView.setGallerySource(it) }
                picture?.let { overlay.setCardPhoto(it) }
                photoInfo = info
                overlay.setCardInfo(info.first, info.second)
                applyContentAndPlay()
            }
        }.start()
    }

    /**
     * 接收侧只有一张图（对方发来的那张），它同时当卡片和折射背景。
     *
     * [uri] 为空 = 照片还没到：垫一张深色位图当折射源，让"屏幕暗下来 + 涟漪"先播起来，
     * 卡片退回动效层内置的那张插画（OverlayView 在没图时自己会用内置换的）。
     */
    private fun decodeReceiveImage(uri: Uri?) {
        val screen = displaySize()
        Thread {
            val background = if (uri != null) {
                ImageLoader.decodeCover(this, uri, screen.x, screen.y)
            } else {
                darkBackdrop()
            }
            val picture = uri?.let { ImageLoader.decode(this, it) }
            val info = uri?.let { ImageLoader.describe(this, it) }
            mainHandler.post {
                if (rootView == null) return@post
                backdropBitmap = background
                photoBitmap = picture
                decodedBackdrop = uri
                decodedPhoto = uri
                decodedReceive = true
                background?.let { rippleView.setGallerySource(it) }
                // 没照片时不动卡片：OverlayView 保持内置插画 + 默认文案
                if (picture != null) {
                    overlay.setCardPhoto(picture)
                    if (info != null) {
                        photoInfo = info
                        overlay.setCardInfo(info.first, info.second)
                    }
                }
                applyContentAndPlay()
            }
        }.start()
    }

    /** 照片还没到时的折射源：一块纯深色，等价于"对方的屏幕暗下来"。 */
    private fun darkBackdrop(): Bitmap =
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(BASE_DARK) }

    /**
     * 新一轮开始前把动效层擦回"干净的一屏"。
     *
     * 窗口被摘掉时视图树是**留着**的（[hideDemo] 只做 detach，通知里的「重播」要靠它复用），
     * 于是上一轮走完的画面 —— 卡片照片、卡片文案、已经走到底的时间轴 —— 会在新窗口挂上的
     * 那一瞬间被原样画出来：看起来就是"先去显示上次那个完成了的悬浮窗"。
     * 这里把这几样一次性清掉，新窗口的第一帧和第一次进来时完全一样。
     */
    private fun wipeVisuals() {
        if (rootView == null) return
        timeline.stop()
        overlay.resetSendState()
        overlay.clearCard()
        overlay.setStatus(null)
        // 立刻重画一帧：卡片 / 接收方信息 / 上滑提示 / 按钮全部回到"还没出现"
        overlay.applyTimeline(0L, 0f)
        rippleView.setRipple(0f)
        rippleView.setBlur(0f)
        rippleView.setGallerySource(darkBackdrop()) // 旧的折射背景同样不能留
    }

    /** 把已经解好的位图重新灌回视图（擦干净之后复用上一轮的缓存，必须重灌一遍）。 */
    private fun applyDecodedVisuals() {
        if (rootView == null) return
        backdropBitmap?.let { rippleView.setGallerySource(it) }
        photoBitmap?.let { overlay.setCardPhoto(it) }
        photoInfo?.let { overlay.setCardInfo(it.first, it.second) }
    }

    /** 把当前模式 / 对端文案 / 状态行灌进动效层，然后挂窗口播一遍。 */
    private fun applyContentAndPlay() {
        overlay.setMode(mode)
        overlay.setPeerInfo(peerLabel, peerCount)
        overlay.setStatus(statusText)
        attachAndPlay()
    }

    /** 挂窗口（已挂着就不重复 addView）→ 播一遍入场动效。 */
    private fun attachAndPlay() {
        attachOverlay()
        scheduleAutoDemo()
    }

    /** 重播：图不换。窗口还没建 / 已经被摘掉时，等价于用上一次那两张图重来一遍。 */
    private fun replayDemo() {
        if (rootView == null) {
            // 窗口还没建：按当前模式重来一遍（接收侧没有图也能播）
            if (mode == OverlayView.Mode.RECEIVE) {
                startReceiveFlow(Intent().putExtra(EXTRA_PHOTO, photoUri?.toString()))
            } else {
                startSendFlow(null)
            }
            return
        }
        if (!attached) {
            attachAndPlay()
            return
        }
        overlay.resetSendState()
        timeline.play()
    }

    /**
     * 通知里点「重播」：**等 [REPLAY_DELAY_MS] 之后再播**。
     *
     * 留这几秒是为了让人把手机放下、切到要演示的那个界面 —— 点完立刻播的话，
     * 手指还在通知栏上、要演示的界面也还没出来。等待期间通知文案会变成"5 秒后重播…"，
     * 再点一次就是重新计时；中途点「停止演示 / 退出常驻」会把这一拍作废（[cancelReplay]）。
     */
    private fun scheduleReplay() {
        cancelReplay()
        replayPending = true
        ensureForeground()

        val runnable = Runnable {
            replayRunnable = null
            replayPending = false
            if (attached) {
                overlay.resetSendState()
                timeline.play()
            } else {
                // 等待期间窗口还没建起来（服务刚起、或者还没演示过）：按当前模式重来一遍
                replayDemo()
            }
            ensureForeground()
        }
        replayRunnable = runnable
        // 用 Handler 而不是某个 View：这一刻可能连视图树都还没有
        mainHandler.postDelayed(runnable, REPLAY_DELAY_MS)
    }

    private fun cancelReplay() {
        replayRunnable?.let { mainHandler.removeCallbacks(it) }
        replayRunnable = null
        replayPending = false
    }

    /** 「停止演示」：摘掉窗口、停掉时间轴，服务不动（视图树留着，下次直接复用）。 */
    private fun hideDemo() {
        if (rootView != null) detachOverlay()
        ensureForeground()
    }

    // ---------------------------------------------------------------- 动效层 ↔ NFC 层

    /**
     * 用户在动效页上的确认动作 → 回执给常驻服务 [TapService]。
     *
     * 动效层**只负责"用户点了什么"**：真正的传图 / 打开查看器 / 解锁角色都在 NFC 层做，
     * 那边用 [ACTION_UI_SEND_CONFIRMED] 这类 action 接（`TapService.onStartCommand` 里三个分支）。
     * 用 startService 而不是进程内回调：服务可能刚被冷启，走 intent 不会丢事件。
     */
    private fun notifyNfc(action: String) {
        runCatching { startService(Intent(this, TapService::class.java).setAction(action)) }
            .onFailure { Log.e(TAG, "回执给 TapService 失败：$action", it) }
    }

    // ---------------------------------------------------------------- 悬浮窗

    /** 建视图树，但先不挂窗口。 */
    private fun buildOverlay() {
        // 权限可能在运行中被撤掉，这里兜一下，免得 addView 直接抛 BadToken。
        // 注意不能顺手把服务停掉：服务是常驻的，没权限只是这一次演示做不成。
        if (!Settings.canDrawOverlays(this)) return

        val content = LayoutInflater.from(this).inflate(R.layout.overlay_root, null)
        rippleView = content.findViewById(R.id.ripple)
        overlay = content.findViewById(R.id.overlay)
        rootView = content

        // 时间轴：每推进一帧同时刷新水波、背景模糊与 2D 图层
        val tl = Timeline(overlay)
        timeline = tl
        tl.onFrame = Timeline.OnFrame { elapsed, ripple ->
            // 水波在 1000ms 后恒为 1，setter 内部跳过重复值，常驻段不白刷 GL
            rippleView.setRipple(ripple)
            // 发送的照片（卡片）出现时背景整张糊掉：900 → 1400ms 淡进去，
            // 之后就一直是模糊的（值不变时 setter 不重绘，常驻段仍然不白刷 GL）
            rippleView.setBlur(tl.photoBlurAt(elapsed))
            overlay.applyTimeline(elapsed, ripple)
        }
        // 一次上滑发送结束：复位时间轴
        //pri added by zhuyang,Also drop the window: after the offer the sender waits up to 60s,20261014
        overlay.onSendFinished = { timeline.reset(); hideDemo() }
        // 上滑越过阈值那一刻：只回执给 NFC 层，动效自己继续把卡片飞走
        overlay.onSendConfirmed = { notifyNfc(ACTION_UI_SEND_CONFIRMED) }
        // 接收页的两个按钮
        overlay.onReceiveAccepted = { notifyNfc(ACTION_UI_RECEIVE_ACCEPT) }
        overlay.onReceiveRejected = { notifyNfc(ACTION_UI_RECEIVE_REJECT) }
    }

    /** 把视图树挂上窗口并铺满屏幕。 */
    private fun attachOverlay() {
        val content = rootView ?: return
        if (attached) {
            ensureForeground()
            return
        }
        val added = runCatching { windowManager.addView(content, buildWindowParams()) }
            .onFailure { Log.e(TAG, "挂悬浮窗失败", it) }
            .isSuccess
        if (!added) return
        attached = true
        animating = true

        // GL 的 onResume 要等视图真的挂上窗口，否则 surface 拿不到
        rippleView.onResume()

        applyBarInsets()
        // 悬浮窗不是绝对能收起系统栏；收不起来也没关系，内容本来就铺到最下沿
        requestFullScreen()
        content.postDelayed({ applyBarInsets() }, 400L)
        ensureForeground()
    }

    /** 摘掉窗口、停掉时间轴，但保留视图树。 */
    private fun detachOverlay() {
        val content = rootView ?: return
        startRunnable?.let { overlay.removeCallbacks(it) }
        startRunnable = null
        // 用户已经说"停止演示"了，还没到点的重播就别再自己上来
        cancelReplay()
        if (!attached) return
        attached = false
        animating = false
        timeline.stop()
        runCatching { rippleView.onPause() }
        runCatching { windowManager.removeView(content) }
    }

    /**
     * 只有窗口真的挂在 WindowManager 上时才更新布局参数。
     *
     * `hideIntent` / [hideDemo] 只是 detach —— [rootView] 还在（留给「重播」复用），
     * 但它已经不在 WindowManager 里了。这时调 `updateViewLayout` 会抛
     * `IllegalArgumentException: View … not attached to window manager`；
     * 而 [onConfigurationChanged] 和通知里的「触摸穿透」都跑在主线程上，一抛就是整进程 Fatal
     * （真机日志：`onConfigurationChanged(OverlayService.kt:167)` → FATAL EXCEPTION: main）。
     *
     * 参数本身照改不误，下次 [attachOverlay] 时用新参数自然生效。
     */
    private fun updateWindowLayout(view: View, params: WindowManager.LayoutParams) {
        if (!attached) return
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { Log.e(TAG, "更新悬浮窗布局失败", it) }
    }

    /** 挂上窗口后延迟一小会儿自动演示一遍入场动效。 */
    private fun scheduleAutoDemo() {
        startRunnable?.let { overlay.removeCallbacks(it) }
        val runnable = Runnable { replayDemo() }
        startRunnable = runnable
        overlay.postDelayed(runnable, 420L)
    }

    private fun uriExtra(intent: Intent?, key: String): Uri? =
        intent?.getStringExtra(key)?.takeIf { it.isNotEmpty() }?.let(Uri::parse)

    private fun countExtra(intent: Intent?, fallback: Int): Int =
        if (intent?.hasExtra(EXTRA_COUNT) == true) intent.getIntExtra(EXTRA_COUNT, fallback) else fallback

    /**
     * 一个整屏、透明、不吃焦点的悬浮窗。
     *
     * 宽高显式给"整块屏幕"的像素尺寸（不是 MATCH_PARENT）：配上
     * `FLAG_LAYOUT_NO_LIMITS` 这个尺寸不会被系统栏裁掉，窗口真的铺到最上沿和最下沿，
     * 导航栏落在窗口下面 —— 要么被内容盖住，要么已经被收起。
     */
    private fun buildWindowParams(): WindowManager.LayoutParams {
        val size = displaySize()
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val params = WindowManager.LayoutParams(
            size.x,
            size.y,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (touchThrough) flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else flags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // 竖屏锁定：主页那边是 activity 级的锁，这里再给窗口本身锁一次 ——
        // 动效是按竖屏排版的（设计宽 380、长屏比例），转成横屏只会糊。
        // 应用悬浮窗不一定被允许改方向（系统可能忽略这一项），真被忽略时也还有
        // onConfigurationChanged 兜底：它会按新的屏幕尺寸重新量一遍窗口。
        params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+ 多了一层"窗口适配 insets"：默认会把窗口缩进安全区，
            // 于是连铺满全屏的背景图都到不了最上 / 最下沿（顶上留一条、底下留一段）。
            // 显式声明一个 insets 都不适配、也不因不可见而忽略 —— 窗口真的占满整块屏幕。
            params.fitInsetsTypes = 0
            params.fitInsetsSides = 0
            // 这个 boolean 的 getter 是 isFitInsetsIgnoringVisibility()，Kotlin 合成的属性名会带着 is，
            // 所以这里直接按方法调 —— 属性写法得写成 isFitInsetsIgnoringVisibility，容易踩坑。
            params.setFitInsetsIgnoringVisibility(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 铺到刘海 / 挖孔区
            params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return params
    }

    /**
     * 整屏窗口会连下面的触摸一起吃掉。切成穿透后，窗口完全不吃事件，
     * 下层应用照常使用（代价是卡片上滑发送 / 接收按钮也收不到了）。
     *
     * 开关放在通知里：窗口一旦 `FLAG_NOT_TOUCHABLE`，它自己的按钮就点不到了。
     */
    private fun setTouchThrough(through: Boolean) {
        touchThrough = through
        val view = rootView
        if (view != null) {
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = if (through) {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            // 窗口此刻可能已经被摘掉：参数照改（buildWindowParams 会带上 FLAG_NOT_TOUCHABLE），
            // 但不去动 WindowManager —— 否则同样是主线程 Fatal
            updateWindowLayout(view, params)
        }
        // 动作文案要跟着换，所以重发一次通知
        ensureForeground()
    }

    private fun teardown() {
        startRunnable?.let { overlay.removeCallbacks(it) }
        startRunnable = null
        cancelReplay()

        val view = rootView
        if (view == null) {
            attached = false
            animating = false
            running = false
            return
        }
        rootView = null
        attached = false
        animating = false
        touchThrough = false

        timeline.stop()
        overlay.resetSendState()
        rippleView.onPause()
        runCatching { windowManager.removeView(view) }

        systemTop = 0
        running = false
    }

    private fun stopEverything() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 状态栏 / 刘海那一条：忽略"当前是否可见"，永远留出来，
     * 免得发送面板钻到挖孔或状态栏底下。
     *
     * 悬浮窗里没有 Activity 的 WindowInsets 回调，直接量当前窗口的 metrics。
     */
    private fun applyBarInsets() {
        systemTop = topInsetPx()
        overlay.topInset = systemTop.toFloat()
        // 底部没有控制条要让开了，白线直接贴到屏幕最下沿
        overlay.bottomInset = 0f
    }

    private fun topInsetPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val insets = windowManager.maximumWindowMetrics.windowInsets
                return insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                ).top
            } catch (_: Throwable) {
                // 少数机型 / 定制系统上 metrics 拿不到，走下面的兜底
            }
        }
        return dimenOf("status_bar_height")
    }

    private fun dimenOf(resName: String): Int {
        val id = resources.getIdentifier(resName, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /**
     * 请系统把状态栏 / 导航栏收起来。
     *
     * 悬浮窗（`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE`）不是绝对能收起系统栏的，
     * 收不起来也不影响整屏：窗口本来就铺到最下沿，导航栏落在窗口下面被内容盖住。
     */
    private fun requestFullScreen() {
        val controller = rootView?.let { ViewCompat.getWindowInsetsController(it) } ?: return
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** 整块屏幕的像素尺寸（含系统栏）。 */
    private fun displaySize(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        return point
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        channel.description = getString(R.string.notif_text_idle)
        manager.createNotificationChannel(channel)
    }

    /**
     * 进前台 + 刷新通知（待机 / 演示中 / 穿透三种文案由 [buildNotification] 自己挑）。
     *
     * 这就是"常驻"的全部含义：进前台、挂通知，然后什么都不做，等下一次 [ACTION_START]。
     */
    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
    }

    /**
     * 通知里的动作**只有三个** —— 系统通知的标准布局最多就显示三个，
     * 放第四个会被静默吞掉（不是不显示，是根本不给你看），所以这里只放最该在窗口外操作的三件事：
     *
     * 1. 重播动画：延迟 [REPLAY_DELAY_MS] 再播，留出切界面的时间；
     * 2. 关闭悬浮窗：只摘窗口（服务继续常驻），回家主页就能点得到「退出常驻服务」；
     * 3. 触摸穿透：窗口一旦 `FLAG_NOT_TOUCHABLE`，它自己的按钮就点不到了，只能放这儿。
     */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                getString(
                    when {
                        replayPending -> R.string.notif_text_pending
                        touchThrough -> R.string.notif_text_through
                        animating -> R.string.notif_text_animating
                        else -> R.string.notif_text_idle
                    }
                )
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notif_action_replay), servicePending(REQ_REPLAY, ACTION_REPLAY))
            .addAction(0, getString(R.string.notif_action_hide), servicePending(REQ_HIDE, ACTION_HIDE))
            .addAction(
                0,
                getString(
                    if (touchThrough) R.string.notif_action_touch_off
                    else R.string.notif_action_touch_on
                ),
                servicePending(REQ_TOUCH, ACTION_TOGGLE_TOUCH)
            )
            .build()
    }

    private fun servicePending(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, OverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        /** 发送侧：带两张图的 Uri（缺省则沿用上一次那两张）→ 发送确认页。 */
        const val ACTION_START = "com.pri.nfctouchdemo.action.START"

        /** 接收侧：带对端标识 / 张数 → 接收确认页。 */
        const val ACTION_RECEIVE = "com.pri.nfctouchdemo.action.RECEIVE"

        /** 只换卡片里那张照片（接收侧预览 / 整图到了再推）。 */
        const val ACTION_PHOTO = "com.pri.nfctouchdemo.action.PHOTO"

        /** 更新动效页底部的状态行。 */
        const val ACTION_STATUS = "com.pri.nfctouchdemo.action.STATUS"

        /**
         * 只改「对端标识 / 张数」，别的都不动。
         *
         * 用途：接收页是在碰一碰那一刻就弹出来的，那时还不知道对方要发几张，
         * `receiveIntent` 只能先按 1 张拉页；等对方把真实总张数报过来（Xfer 的 onSendStarted），
         * 已经弹好的页面需要被**就地补正**成「接收 N 张照片」——重播一遍入场动画显然不行。
         */
        const val ACTION_PEER = "com.pri.nfctouchdemo.action.PEER"

        /** 不换图，重播一遍入场动效。 */
        const val ACTION_REPLAY = "com.pri.nfctouchdemo.action.REPLAY"

        /** 只摘掉动画窗口，常驻服务继续待命。 */
        const val ACTION_HIDE = "com.pri.nfctouchdemo.action.HIDE"

        const val ACTION_TOGGLE_TOUCH = "com.pri.nfctouchdemo.action.TOGGLE_TOUCH"

        /** 退出常驻：真正停掉服务。 */
        const val ACTION_STOP = "com.pri.nfctouchdemo.action.STOP"

        /**
         * 动效层 → NFC 层的回执 action（发给 `nfc.TapService`）。
         *
         * 用户在动效页上做的事只有三件：上滑确认发送、点「接收」、点「拒绝」。
         * 动效层只把这三件事报出去，业务（调 Xfer 传图 / 打开查看器 / 解锁角色）全在 TapService 里。
         */
        const val ACTION_UI_SEND_CONFIRMED = "com.pri.nfctouchdemo.action.UI_SEND_CONFIRMED"
        const val ACTION_UI_RECEIVE_ACCEPT = "com.pri.nfctouchdemo.action.UI_RECEIVE_ACCEPT"
        const val ACTION_UI_RECEIVE_REJECT = "com.pri.nfctouchdemo.action.UI_RECEIVE_REJECT"

        /** 背景图（水波折射的那张，产品里对应"对方的屏幕画面"）。 */
        const val EXTRA_BACKDROP = "extra_backdrop"

        /** 要发送的照片（卡片里那张，产品里对应"正在传的文件"）。 */
        const val EXTRA_PHOTO = "extra_photo"

        /** 对端标识（「用户XXXX」，不带前缀）。 */
        const val EXTRA_PEER = "extra_peer"

        /** 本次张数（发送侧 = 选中的张数，接收侧 = 对方预告的张数）。 */
        const val EXTRA_COUNT = "extra_count"

        /** 状态行文案。 */
        const val EXTRA_STATUS = "extra_status"

        /**
         * 启动页 / NFC 联调页读这两个标记显示状态。**服务在跑 ≠ 动画在播**：
         * 启动服务只让 [isRunning] 变 true，动画要等一次 [ACTION_START] / [ACTION_RECEIVE]
         * 才让 [isAnimating] 变 true。服务活在别的组件里，用两个静态标记最省事。
         *
         * 用 @JvmStatic 方法暴露给 Java 侧（`com.pri.nfctouchdemo.nfc.*`）：Kotlin 的
         * `var isRunning` 合成的 getter 名字在 Java 侧容易踩坑，方法最直白。
         */
        @Volatile
        private var running = false

        @Volatile
        private var animating = false

        @JvmStatic
        fun isRunning(): Boolean = running

        @JvmStatic
        fun isAnimating(): Boolean = animating

        /**
         * 发送侧的标准姿势：把两张图塞进两个 extra 就完事，
         * 服务自己解码、建窗、播放。启动页（纯动效演示）这么调 —— 没有对端，用动效层自带文案。
         */
        @JvmStatic
        fun intent(context: Context, backdrop: Uri, photo: Uri): Intent =
            Intent(context, OverlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BACKDROP, backdrop.toString())
                .putExtra(EXTRA_PHOTO, photo.toString())

        /**
         * 发送侧的 NFC 版本：多带一个对端标识和张数，于是面板标题从「发送给 fazd9」
         * 换成产品里那句「发送给 用户XXXX」、上滑提示换成「上滑发送 N 张照片」。
         *
         * `peer` 为空时动效层会退回默认文案（见 [OverlayView.setPeerInfo]）。
         */
        @JvmStatic
        fun intent(context: Context, backdrop: Uri, photo: Uri, peer: String?, count: Int): Intent =
            Intent(context, OverlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BACKDROP, backdrop.toString())
                .putExtra(EXTRA_PHOTO, photo.toString())
                .putExtra(EXTRA_PEER, peer)
                .putExtra(EXTRA_COUNT, count)

        /**
         * 接收侧的标准姿势：只要对端标识和张数就能播（照片可以后补，见 [photoIntent]）。
         */
        @JvmStatic
        fun receiveIntent(context: Context, peer: String, count: Int, preview: Uri?): Intent =
            Intent(context, OverlayService::class.java)
                .setAction(ACTION_RECEIVE)
                .putExtra(EXTRA_PEER, peer)
                .putExtra(EXTRA_COUNT, count)
                .putExtra(EXTRA_PHOTO, preview?.toString() ?: "")

        /** 只换卡片照片（接收侧预览 / 整图到了再推）。 */
        @JvmStatic
        fun photoIntent(context: Context, photo: Uri): Intent =
            Intent(context, OverlayService::class.java)
                .setAction(ACTION_PHOTO)
                .putExtra(EXTRA_PHOTO, photo.toString())

        /** 更新动效页底部状态行。 */
        @JvmStatic
        fun statusIntent(context: Context, text: String): Intent =
            Intent(context, OverlayService::class.java)
                .setAction(ACTION_STATUS)
                .putExtra(EXTRA_STATUS, text)

        /**
         * 只补正对端标识 / 张数（不动窗口、不重播动画）。
         *
         * 接收侧的典型用法：碰一碰时先用 `receiveIntent(ctx, label, 1, preview)` 把页弹出来，
         * 等对方报出真实总张数后再 `peerIntent(ctx, label, total)` 把按钮文案改成「接收 N 张照片」。
         */
        @JvmStatic
        fun peerIntent(context: Context, peer: String?, count: Int): Intent =
            Intent(context, OverlayService::class.java)
                .setAction(ACTION_PEER)
                .putExtra(EXTRA_PEER, peer)
                .putExtra(EXTRA_COUNT, count)

        /** 摘掉动效窗口（服务继续常驻）。 */
        @JvmStatic
        fun hideIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).setAction(ACTION_HIDE)

        /** 退出常驻：真正停掉服务。 */
        @JvmStatic
        fun stopIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).setAction(ACTION_STOP)

        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "touch_anime_overlay"
        private const val NOTIFICATION_ID = 0x4F50

        /** 照片还没到时的折射底色，和动效层的深色底一致。 */
        private const val BASE_DARK = 0xFF050608.toInt()

        private const val REQ_OPEN = 1
        private const val REQ_REPLAY = 2
        private const val REQ_TOUCH = 3
        private const val REQ_HIDE = 5

        /**
         * 通知里点「重播」之后等多久才真的播。
         *
         * 不是动画本身的延迟，是**留给人操作的时间**：点完还要把手机放下 / 切到
         * 要演示的那个界面，立刻播等于白播。
         */
        private const val REPLAY_DELAY_MS = 5000L
    }
}
