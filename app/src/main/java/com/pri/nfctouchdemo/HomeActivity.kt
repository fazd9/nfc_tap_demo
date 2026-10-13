package com.pri.nfctouchdemo

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pri.nfctouchdemo.gfx.ImageLoader
import com.pri.nfctouchdemo.nfc.MainActivity
import com.pri.nfctouchdemo.nfc.TapService
import com.pri.nfctouchdemo.service.OverlayService

/**
 * 启动页：**选一张要发送的照片 → 交给「一碰互联」等碰一碰**。
 *
 * 页面只走这一条路：选好要发送的照片，点「把选中的照片交给一碰互联」——
 * 照片经 [TapService]（常驻服务）落到 `PENDING`，之后两台手机碰一碰，
 * 发送方直接弹出发送确认页（就是那套动效页，上滑即发送），接收方弹出接收确认页。
 * 服务要用的折射背景，由它自己拿这张要发送的照片充当。
 *
 * 页面上的状态行读 [OverlayService.isRunning] / [OverlayService.isAnimating]：
 * 服务在跑 ≠ 动画在播，两者分开显示；NFC 那一段另外显示「有没有把照片交给服务」。
 *
 * 想做联调（看日志、切角色、用 adb 顶掉碰触这一步）走「打开 NFC 联调页」。
 *
 * **通知权限有意不申请**（Android 13+ 因此拿不到 `POST_NOTIFICATIONS`）：动效页的退出完全
 * 靠流程本身 —— 发送完、对方点「接收」/「拒绝」都会由 [TapService] 发 `hideIntent` 把窗口摘掉。
 * 代价是"连上了但不想上滑发送"和"连接失败（页面会补弹并停在失败原因上）"这两种停住的情况，
 * 页面上没有退出按钮，只能强制停止应用；这样换掉了那个会在"交给一碰互联"时打断一下的权限弹窗。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var photoThumb: ImageView
    private lateinit var photoLabel: TextView
    private lateinit var stateLabel: TextView
    private lateinit var nfcLabel: TextView
    private lateinit var overlayButton: AppCompatButton
    private lateinit var exitButton: AppCompatButton
    private lateinit var pushButton: AppCompatButton

    private var photoUri: Uri? = null

    /** 已经交给常驻服务的张数（0 = 还没交，碰一碰不会走发送侧）。 */
    private var pushedCount = 0

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            photoUri = uri
            bind(uri, photoThumb, photoLabel, R.string.home_slot_photo_picked)
            refreshState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

        photoThumb = findViewById(R.id.thumb_photo)
        photoLabel = findViewById(R.id.label_photo)
        stateLabel = findViewById(R.id.state_label)
        nfcLabel = findViewById(R.id.nfc_state_label)
        overlayButton = findViewById(R.id.btn_overlay)
        exitButton = findViewById(R.id.btn_exit)
        pushButton = findViewById(R.id.btn_push)

        // 主页是滚动内容，让状态栏 / 导航栏把内容挤开，而不是压在下面
        val content = findViewById<View>(R.id.home_content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop + bars.top,
                view.paddingRight,
                view.paddingBottom + bars.bottom
            )
            insets
        }

        findViewById<View>(R.id.btn_pick_photo).setOnClickListener {
            pickPhoto.launch(IMAGE_MIME)
        }
        overlayButton.setOnClickListener { openOverlaySettings() }
        exitButton.setOnClickListener { exitServices() }
        pushButton.setOnClickListener { pushToNfc() }
        findViewById<View>(R.id.btn_nfc_debug).setOnClickListener {
            // 联调页才是 NFC 那一侧的"控制台"：日志 / 角色 / reader / adb 顶掉碰触
            startActivity(Intent(this, MainActivity::class.java))
        }

        refreshState()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置给完悬浮窗权限回来、或者从通知里停了演示回来，状态都在这儿刷新
        refreshState()
    }

    // ---------------------------------------------------------------- 一碰互联

    /**
     * 把「要发送的照片」交给常驻服务，之后碰一碰就走发送侧。
     *
     * 走的正是相册那条既有入口：`ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` ——
     * `TapService.onStartCommand` 里本来就接这个 action（相册/文件管理器分享过来也是它），
     * 所以这里不需要给 NFC 层加任何新接口。**背景图不往里塞**：NFC 路径里背景由服务
     * 用这张要发送的照片自己当折射源（见 `TapService.showSendPage()`）。
     */
    private fun pushToNfc() {
        val photo = photoUri
        if (photo == null) {
            Toast.makeText(this, R.string.toast_need_images, Toast.LENGTH_SHORT).show()
            return
        }
        if (NfcAdapter.getDefaultAdapter(this) == null) {
            Toast.makeText(this, R.string.home_nfc_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val uris = ArrayList<Uri>()
        uris.add(photo)
        val intent = Intent(this, TapService::class.java)
            .setAction(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        val started = runCatching { startService(intent) }.isSuccess
        if (started) {
            pushedCount = uris.size
            Toast.makeText(this, getString(R.string.toast_pushed, pushedCount), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_push_failed, Toast.LENGTH_SHORT).show()
        }
        refreshState()
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure {
            // 少数机型不认带 package 的写法
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    /**
     * 退出常驻：把两个常驻组件都停掉。
     *
     * - [OverlayService]：`ACTION_STOP` → `teardown()` + 停前台 + `stopSelf()`；
     * - [TapService]：碰一碰那条链路（NFC 监听 / 传输器），`stopService` 直接停。
     *
     * 真正的退出只放在这里（通知里的「关闭悬浮窗」只是摘窗口、服务留着）。窗口一旦盖上，
     * 这个页面就点不到了 —— 而 Android 13+ 默认没有通知权限、也就没有那条能摘窗口的通知，
     * 所以"动效页停住"的两种情况只能强制停止应用：这是有意接受的取舍。
     *
     * 动效服务这边用 `startService` 而不是 `startForegroundService`：服务本来就在前台跑着，
     * 这只是一条普通指令；用 startForegroundService 反而会带上"5 秒内必须 startForeground"
     * 的约束，而这个 action 干的第一件事就是把服务停掉。
     */
    private fun exitServices() {
        runCatching { startService(OverlayService.stopIntent(this)) }
        runCatching { stopService(Intent(this, TapService::class.java)) }
        pushedCount = 0
        // 服务是异步收摊的，稍等一下再读状态
        stateLabel.postDelayed({ refreshState() }, 300L)
    }

    // ---------------------------------------------------------------- 界面状态

    private fun refreshState() {
        val canOverlay = Settings.canDrawOverlays(this)
        overlayButton.visibility = if (canOverlay) View.GONE else View.VISIBLE
        // 服务跑着才给「退出常驻」；悬浮窗盖着的时候这个页面本来就点不到
        exitButton.visibility = if (OverlayService.isRunning()) View.VISIBLE else View.GONE

        stateLabel.text = buildString {
            append(
                getString(
                    if (OverlayService.isRunning()) R.string.home_state_running
                    else R.string.home_state_stopped
                )
            )
            if (OverlayService.isAnimating()) append(getString(R.string.home_state_animating))
        }
        stateLabel.setTextColor(if (OverlayService.isRunning()) COLOR_OK else COLOR_IDLE)

        // NFC 那一段：有没有硬件 + 有没有把照片交给常驻服务
        val nfc = NfcAdapter.getDefaultAdapter(this)
        nfcLabel.text = when {
            nfc == null -> getString(R.string.home_nfc_unavailable)
            pushedCount > 0 -> getString(R.string.home_nfc_ready, pushedCount)
            else -> getString(R.string.home_nfc_idle)
        }
        nfcLabel.setTextColor(if (nfc == null) COLOR_WARN else COLOR_IDLE)

        // 「交给一碰互联」只要有要发送的照片就能点
        val pushable = photoUri != null && nfc != null && canOverlay
        pushButton.isEnabled = pushable
        pushButton.alpha = if (pushable) 1f else 0.45f
    }

    /** 缩略图和文字都跟着选中的文件走，选了什么一眼能看出来。 */
    private fun bind(uri: Uri, thumb: ImageView, label: TextView, pickedRes: Int) {
        label.text = getString(pickedRes, ImageLoader.displayName(this, uri))
        Thread {
            val small = ImageLoader.decode(this, uri, THUMB_SIDE)
            if (small == null) return@Thread
            runOnUiThread {
                if (!isFinishing && !isDestroyed) thumb.setImageBitmap(small)
            }
        }.start()
    }

    private companion object {
        const val IMAGE_MIME = "image/*"

        /** 缩略图只需要这么大，别为了一格预览解一张 12MP 的原图。 */
        const val THUMB_SIDE = 256

        const val COLOR_OK = 0xFF8CE0A8.toInt()
        const val COLOR_IDLE = 0xFF8A93A0.toInt()
        const val COLOR_WARN = 0xFFFFB4B4.toInt()
    }
}
