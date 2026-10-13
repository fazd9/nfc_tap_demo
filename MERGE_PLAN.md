# NFCTouchDemo 融合方案（nfc_tap_demo-main + Test → NFCTouchDemo）

> **落地状态：已完成。** 融合后的工程说明、目录、真机验证清单与已知风险见 [`README.md`](README.md)；
> 本文是当初的融合设计与接口契约（`OverlayService` API、包名映射、Manifest/Gradle 决策），
> 实现与本文一致，差异只在这里记录：接收态按钮的几何/命中判定在 `OverlayView` 内闭环
> （`layoutReceiveActions` 一份矩形，绘制与命中共用），另外为「面板标题显示真实对端」
> 增加了 `OverlayService.intent(ctx, backdrop, photo, peer, count)` 四参重载。

> 目标：把 `D:\浏览器\nfc_tap_demo-main`（NFC「一碰互联」业务/传输层，Java）与
> `D:\A17\Test`（TouchAnimeDemo 碰一碰动效层，Kotlin）合并进 `D:\A17\NFCTouchDemo`，
> 形成**一个** Gradle 工程、**一个** apk。
>
> 已确认的四个决策：
> 1. **深度打通**：NFC 碰触 → 动效层悬浮窗（Test 那套 GL 水波 + 白线 + 卡片堆叠 + 上滑发送）；
> 2. **发送/接收确认页以 Test 的动效页为准**（接收页在动效层里补一个「接收 / 拒绝」变体）；
> 3. **混合语言**：动效层 Kotlin，NFC/传输层 Java 原样；包名统一到 `com.pri.nfctouchdemo.*`；
> 4. 单 `:app` 模块；**HomeActivity 做启动页**；死代码删除。

---

## 一、目标目录结构

```
app/src/main/
  AndroidManifest.xml                      合并（NFC + HCE + 悬浮窗 + 前台服务）
  java/com/pri/nfctouchdemo/
    HomeActivity.kt                        启动页：选图 + 状态 + NFC 入口（改自 Test/HomeActivity.kt）
    anim/Timeline.kt                       动效时间轴（Test 原样）
    gfx/ImageLoader.kt                     图片解码（Test 原样）
    gfx/PhotoArtwork.kt                    内置卡片插画（Test 原样）
    gfx/PhotoTint.kt                       主色提取（Test 原样）
    gfx/BackdropBlur.kt                    背景模糊（Test 原样）
    gfx/RippleSurfaceView.kt               GL 水波视图（Test 原样）
    gfx/RippleRenderer.kt                  GL 水波渲染（Test 原样）
    ui/OverlayView.kt                      Canvas 动效层（Test 原样 + **接收态**扩展）
    service/OverlayService.kt              动效层宿主：前台服务 + 整屏悬浮窗（改自 Test）
    nfc/Bus.java                           APDU 协议 / 共享状态 / 日志（nfc 原样）
    nfc/HceService.java                    卡模拟（发送侧）（nfc 原样）
    nfc/TapDispatchActivity.java           透明跳板（nfc 原样）
    nfc/Xfer.java                          Wi-Fi Direct / 热点 + TCP 传图（nfc 原样）
    nfc/Ui.java                            dp / 占位图 / 查看器构建（nfc 原样）
    nfc/Overlay.java                       悬浮窗管理（nfc 原样，查看器还在用）
    nfc/TapService.java                    常驻服务主流程（**改写**：驱动动效层 + Xfer）
    nfc/MainActivity.java                  NFC 联调页（**改写**：日志/角色/选图/reader，去掉旧确认页）
  res/
    layout/activity_home.xml               启动页（Test 原样）
    layout/overlay_root.xml                悬浮窗内容（Test/activity_main.xml 改名 + 新包名）
    drawable/bg_btn.xml, bg_btn_primary.xml（Test 原样）
    values/strings.xml                     合并（Test 文案 + nfc 的 hce/aid 描述 + 新文案）
    values/colors.xml, values/themes.xml   合并（Test 深色主题 + demo_background）
    values-night/themes.xml                （Test 原样）
    xml/apdu_service.xml, xml/tech_list.xml（nfc 原样）
    xml/backup_rules.xml, data_extraction_rules.xml（NFCTouchDemo 已有，保留）
```

**不搬运（删除）**：Test 的 `service/ScreenShot.kt`、`service/SystemScreenCapture.kt`、
`service/ScreenshotAccessibilityService.kt`（截屏/无障碍链路，Manifest 里本就没注册）、
`gfx/GalleryTexture.kt`（已废弃）、Test 的 `MainActivity.kt`、`DemoActivity.kt`（只剩注释）、
`res/layout/activity_demo.xml`、`res/xml/accessibility_service_config.xml`；
nfc 的 `SendPage.java`、`ReceivePage.java`、`GlowView.java`、`ParticleView.java`
（确认页统一由动效层 `OverlayView` 承担）；NFCTouchDemo 原来的 Hello World
`MainActivity.kt` + `layout/activity_main.xml`。

---

## 二、动效层 ↔ NFC 层的接口契约（两侧都按这个写，不要各写一套）

### 2.1 `service/OverlayService.kt`（Kotlin）对外 API

```kotlin
class OverlayService : Service() {
    companion object {
        // ---- 启动动效层（NFC 层只调这几个工厂） ----
        /** 发送侧：背景图 + 要发送的照片 → 涟漪 + 白线 + 卡片堆叠 + 「上滑发送」。 */
        @JvmStatic fun intent(ctx: Context, backdrop: Uri, photo: Uri): Intent
        /** 接收侧：对方标识 + 张数 → 涟漪 + 白线 + 「来自 X 的分享」+ 卡片 + 接收/拒绝按钮。 */
        @JvmStatic fun receiveIntent(ctx: Context, peer: String, count: Int, preview: Uri?): Intent
        /** 只换卡片里那张照片（接收时预览图到了再推）。 */
        @JvmStatic fun photoIntent(ctx: Context, photo: Uri): Intent
        /** 更新动效页底部状态行（「正在连接对方…」/「已连接，上滑发送即可」/ 失败原因）。 */
        @JvmStatic fun statusIntent(ctx: Context, text: String): Intent
        /** 摘窗口（服务继续常驻）；退出常驻用 stopIntent。 */
        @JvmStatic fun hideIntent(ctx: Context): Intent
        @JvmStatic fun stopIntent(ctx: Context): Intent
        /** 状态查询（启动页读它显示「运行中 / 演示中」）。 */
        @JvmStatic fun isRunning(): Boolean
        @JvmStatic fun isAnimating(): Boolean

        // ---- 动效层 → NFC 层的回执：用 startService 发给 nfc/TapService.java ----
        const val ACTION_UI_SEND_CONFIRMED = "com.pri.nfctouchdemo.action.UI_SEND_CONFIRMED"
        const val ACTION_UI_RECEIVE_ACCEPT = "com.pri.nfctouchdemo.action.UI_RECEIVE_ACCEPT"
        const val ACTION_UI_RECEIVE_REJECT = "com.pri.nfctouchdemo.action.UI_RECEIVE_REJECT"
    }
}
```

OverlayService 内部 action（只有它自己和通知用）：
`ACTION_START / ACTION_RECEIVE / ACTION_PHOTO / ACTION_STATUS / ACTION_REPLAY / ACTION_HIDE /
ACTION_TOGGLE_TOUCH / ACTION_STOP`，extra：
`EXTRA_BACKDROP / EXTRA_PHOTO / EXTRA_PEER / EXTRA_COUNT / EXTRA_STATUS`。

回执走 `startService(Intent(this, TapService::class.java).setAction(ACTION_UI_*))`：
Kotlin 单方面引用 Java 类（同模块允许），Java 侧不再反向依赖 Kotlin 的回调接口。

### 2.2 `ui/OverlayView.kt`（Kotlin）接收态扩展

在 Test 原文件基础上**只加**下列成员（其余绘制逻辑一律不动）：

```kotlin
enum class Mode { SEND, RECEIVE }
fun setMode(mode: Mode)                       // 默认 SEND
fun setPeerInfo(peer: String?, count: Int)    // 发送：「发送给 X」/「上滑发送 N 张照片」
                                              // 接收：「来自 X 的分享」/「N 张照片」
fun setStatus(text: String?)                  // 底部状态行；null = 不显示
var onSendConfirmed: (() -> Unit)?            // 上滑过阈值那一刻（先于飞走动画）
var onReceiveAccepted: (() -> Unit)?          // 点「接收 N 张照片」
var onReceiveRejected: (() -> Unit)?          // 点「拒绝」
```

- 文案改用带参数的资源：`peer_title_send`(「发送给 %1$s」)、`peer_title_receive`(「来自 %1$s的分享」)、
  `swipe_tip_count`(「上滑发送 %1$d 张照片」)、`card_meta_count`(「%1$s · %2$d 张照片」)；
  `peer` 为空时仍用原来的 `panel_receiver` / `panel_swipe_tip` 默认文案（保留独立演示能力）。
- 接收态：**禁用上滑手势**，`onSendFinished` 不触发；面板就位后在卡片下方画两个按钮
  （主按钮浅色「接收 N 张照片」、次按钮灰色「拒绝」，复用 `cardPaint`/`textPaint` 那套圆角矩形 + 居中文字），
  在 `onTouchEvent` 里做命中判定 → `onReceiveAccepted` / `onReceiveRejected`；
  两个按钮跟随 `panelAppearAt(elapsed)` 一起淡入（和时间轴/白线一致）。
- 状态行画在底部品牌文字上方一行小字（`setStatus` 有值才画）。

### 2.3 `nfc/TapService.java`（Java）改写点

- 保留：`PENDING` 选中照片、`xfer()`、`onStartCommand` 的 `TAG/PEER/DEBUG/ACTION_SEND_MULTIPLE`
  分支、`savePending/loadPending`、`onPeer` 的角色判定、`handleTag`、`onImageReceived/onTransferDone`、
  `showViewer`（继续用 `Ui.buildViewer` + `Overlay`）。
- 改：`showSendPage()` → `startForegroundService(OverlayService.intent(this, PENDING.get(0), PENDING.get(0)))`
  （NFC 场景下拿不到「对方屏幕」，用要发送的照片自己当折射背景）；
  `showReceivePage(label)` → `OverlayService.receiveIntent(this, label, 1, 缓存里的预览图 Uri)`；
  状态更新 → `OverlayService.statusIntent(this, "…")`；失败原因同样走状态行；
  上滑确认 / 点接收 / 点拒绝 → 由 `onStartCommand` 新增的
  `ACTION_UI_SEND_CONFIRMED / ACTION_UI_RECEIVE_ACCEPT / ACTION_UI_RECEIVE_REJECT` 分支接住，
  分别做 `sXfer.sendImages(PENDING)`、置 `mAccepted` + 收完打开查看器、拒绝并 `Bus.unlockRole()`。
- 删：`SendPage` / `ReceivePage` 字段与方法、相关 import。
- 接收页那张照片：`Xfer` 给的是 `Bitmap`，Intent 里塞不下 → 落成缓存文件
  `new File(getCacheDir(), "peer_preview.jpg")`，用 `Uri.fromFile(...)` 交给动效层
  （`ImageLoader` 走 `contentResolver.openInputStream`，`file://` 可用）。

### 2.4 `nfc/MainActivity.java`（Java）改写点

保留：权限申请、`enableReaderMode` / `onTagDiscovered` / `exchange`、分享入口
（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`）、选图、日志控制台、`onPeer` 里的
`showSendPage/showReceivePage` 调用点改为走 2.3 的同一套 `OverlayService` 调用；
删掉 `ParticleView` / `SendPage` / `ReceivePage` 的构造与引用（粒子动效由动效层负责）。
它现在是**二级页面**（NFC 联调页），启动页是 `HomeActivity`。

---

## 三、Manifest 与 Gradle

- `applicationId` / `namespace` = `com.pri.nfctouchdemo`；`minSdk = 27`（动效层要求）、
  `targetSdk = 37`、`compileSdk = 37`；`compileOptions` Java 17（AGP 9.3.1 自带 Kotlin 编译，
  两个源工程都没有 kotlin 插件，保持现状）。
- 权限：`NFC`、`SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE(_SPECIAL_USE)`、`POST_NOTIFICATIONS`、
  `INTERNET`、`ACCESS_NETWORK_STATE`、`CHANGE_NETWORK_STATE`、`ACCESS_WIFI_STATE`、
  `CHANGE_WIFI_STATE`、`ACCESS_FINE_LOCATION`、`NEARBY_WIFI_DEVICES`、`BLUETOOTH(_ADMIN)`；
  `<uses-feature nfc required="true">`。
- 组件：
  - `.HomeActivity`（LAUNCHER，portrait）
  - `.nfc.MainActivity`（NFC 联调页，`singleTask` + `SEND`/`SEND_MULTIPLE` 分享入口）
  - `.nfc.TapDispatchActivity`（`TECH_DISCOVERED` + `tech_list`，透明、不进最近任务）
  - `.nfc.HceService`（`HOST_APDU_SERVICE` + `apdu_service.xml`，`BIND_NFC_SERVICE`）
  - `.nfc.TapService`（exported=true，demo 用；产品化改签名级权限）
  - `.service.OverlayService`（`foregroundServiceType="specialUse"`）

---

## 四、已知风险（编译验证由用户侧负责，pwsh 在本环境不可用）

1. **`OverlayView.kt` 接收态**是新写的绘制代码（约 100 行），只能静态自检，需真机看效果。
2. `file://` 预览图解码：`ImageDecoder.createSource(contentResolver, fileUri)` 在个别 ROM 上可能失败
   （失败时卡片退回内置插画，不崩）。
3. `TapService` 由 HCE 事件 `startService` 冷启：Android 12+ 后台启动服务限制是原 demo 就带着的
   风险（动效层是前台服务，实际会把这个风险盖住一部分）。
4. 通知里「触摸穿透」开关保留：动效窗口整屏吃触摸，NFC 发送/接收流程结束时由 TapService
   主动 `hideIntent` 摘窗。
