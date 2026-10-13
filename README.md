# NFCTouchDemo —— 「一碰互联」融合版

把两个 demo 合并成一个 Android 工程：

| 来源 | 语言 | 提供了什么 |
|---|---|---|
| `D:\浏览器\nfc_tap_demo-main` | Java（无 Gradle，`build.sh` 手搓 APK） | **业务/传输层**：NFC 碰触握手（HCE 卡模拟 + reader 模式）、Wi-Fi Direct / 热点 / 同网直连、TCP 传图落盘 |
| `D:\A17\Test`（TouchAnimeDemo） | Kotlin + Gradle | **动效层**：GL 水波折射 + 四边白线 + 边缘辉光 + 照片卡片堆叠 + 上滑发送，跑在**前台服务 + 整屏悬浮窗**里 |

合并结果：**碰一碰触发的那两张确认页（发送 / 接收），由动效层来播**。
原来 nfc demo 里用自己的 Canvas 控件拼的 `SendPage` / `ReceivePage` 已经删除。

---

## 一、链路

> 启动页那个「开始演示」按钮（纯动效演示）已按需求删除，**动效层本身没动** ——
> 它仍然能被单独驱动：NFC 联调页走的是两参版 `OverlayService.intent(ctx, backdrop, photo)`；
> 通知里的「重播动画」用动效层记住的上一次那两张图重播（前提是系统给了通知权限，见下方清单第 2 条）。

**一碰互联（NFC）—— 现在唯一的用户入口**

```
发送方：HomeActivity 选好「要发送的照片」
   └─ 点「把选中的照片交给一碰互联」→ startService(TapService, ACTION_SEND_MULTIPLE + EXTRA_STREAM)
        └─ TapService.PENDING = [照片]

两台手机碰一碰
   ├─ 发送方 = 卡方：HceService.processCommandApdu 收到 reader 的 EXCHANGE
   │     └─ startService(TapService, "com.pri.nfcdemo.PEER", payload) → Bus.onPeerData
   │           └─ TapService.onPeer：本机有 PENDING → 本机是发送方
   │                 └─ Xfer 建链 → onP2pReady → showSendPage()
   │                       └─ OverlayService.intent(ctx, 照片, 照片, 对端, 张数)  ← 动效层播发送确认页
   │                             └─ 用户上滑 → 动效层回执 ACTION_UI_SEND_CONFIRMED
   │                                   └─ TapService → Xfer.sendImages(PENDING) → 完成 → 摘窗口
   └─ 接收方 = 读卡方：TapDispatchActivity（TECH_DISCOVERED 跳板）或 nfc/MainActivity（reader 模式）
         └─ TapService.handleTag → Bus.onPeerData(..., "reader")
               └─ 本机无 PENDING → 本机是接收方 → showReceivePage(对端)
                     └─ OverlayService.receiveIntent(ctx, 对端, 张数, 预览)  ← 动效层播接收确认页
                           ├─ Xfer.onPreview → 预览落盘 → OverlayService.photoIntent 换卡片照片
                           ├─ 点「接收」→ ACTION_UI_RECEIVE_ACCEPT → 收完自动打开查看器
                           └─ 点「拒绝」→ ACTION_UI_RECEIVE_REJECT → 解锁角色，等下轮
```

**层次约定（融合的关键）**：动效层**不认识 NFC、不认识 Xfer、不截屏**；NFC 层**不碰 Canvas**。
两边只有三样东西：`OverlayService` 的四个工厂方法（`intent` / `receiveIntent` / `photoIntent` / `statusIntent`）、
三个回执 action（`ACTION_UI_SEND_CONFIRMED` / `ACTION_UI_RECEIVE_ACCEPT` / `ACTION_UI_RECEIVE_REJECT`）、
以及"拿不到对方屏幕就用要发送的照片自己当折射背景"这一条约定。

---

## 二、代码归属

**原样搬运（只改包名 / import）**

- Test → `anim/Timeline.kt`、`gfx/{ImageLoader,PhotoArtwork,PhotoTint,BackdropBlur,RippleSurfaceView,RippleRenderer}.kt`
- nfc → `nfc/{Bus,HceService,TapDispatchActivity,Xfer,Ui,Overlay}.java`
  （Xfer 979 行、Bus 239 行逐字保留，含原有中文注释）

**改写**

| 文件 | 改了什么 |
|---|---|
| `service/OverlayService.kt` | 加「接收态」动作（`ACTION_RECEIVE/PHOTO/STATUS`）、对端文案、对端回执；布局改用 `overlay_root.xml`；对 Java 暴露 `@JvmStatic` 工厂与 `isRunning()/isAnimating()` |
| `ui/OverlayView.kt` | 加 `Mode { SEND, RECEIVE }`、`setPeerInfo`、`setStatus`、`onSendConfirmed`/`onReceiveAccepted`/`onReceiveRejected`，以及接收态的两个按钮（绘制 + 命中判定）；发送态绘制与手势逻辑未动 |
| `HomeActivity.kt` | 由 Test 的主页改造：删掉「背景图」槽与「开始演示」（纯动效演示入口），保留「要发送的照片」槽，新增「一碰互联」区块（NFC 状态、交给常驻服务、打开联调页） |
| `nfc/TapService.java` | 把 `SendPage`/`ReceivePage` 的调用换成 `OverlayService` 指令 + 三个回执分支；其余业务（角色判定、Xfer 回调、查看器）原样 |
| `nfc/MainActivity.java` | 降级为**二级 NFC 联调页**：日志 / 角色 / reader / 分享入口保留，确认页与粒子改为驱动动效层 |
| `AndroidManifest.xml` | 合并六个组件与两边的权限声明 |
| `res/values/{strings,colors,themes}.xml` | 合并（动效层文案 + HCE 描述 + 新增的「接收 / 拒绝 / 状态行」文案） |

**删除（不再搬运）**：Test 的 `ScreenShot.kt` / `SystemScreenCapture.kt` / `ScreenshotAccessibilityService.kt`（截屏与无障碍链路，Manifest 里本就没注册）、`GalleryTexture.kt`（废弃）、Test 的 `MainActivity.kt` / `DemoActivity.kt`（只剩注释）、`layout/activity_demo.xml`、`xml/accessibility_service_config.xml`；
nfc 的 `SendPage.java` / `ReceivePage.java` / `GlowView.java` / `ParticleView.java`；
模板自带的 `MainActivity.kt` / `layout/activity_main.xml`（已留成注释占位文件，可以随手删）。

> 注：本环境没有可用的 shell（`pwsh` 起不来），所以退役文件是"写成注释占位"而不是直接删除。
> 想彻底清掉就删这两个文件：
> `app/src/main/java/com/pri/nfctouchdemo/MainActivity.kt` 与 `app/src/main/res/layout/activity_main.xml`。

**目录**

```
app/src/main/java/com/pri/nfctouchdemo/
  HomeActivity.kt              启动页
  MainActivity.kt              占位（模板 Hello World，已退役）
  anim/Timeline.kt             动效时间轴
  gfx/                         GL 水波 + 图片解码 + 主色/模糊
  ui/OverlayView.kt            动效页（发送态 + 接收态）
  service/OverlayService.kt    动效层宿主（前台服务 + 整屏悬浮窗）
  nfc/                         NFC/传输层（Java）：Bus HceService TapService TapDispatchActivity Xfer Ui Overlay MainActivity
```

---

## 三、构建与验证

- `namespace` / `applicationId` = `com.pri.nfctouchdemo`，`minSdk 27`、`targetSdk 37`、`compileSdk 37`，Java 17。
- 用的是 AGP 9.3.1 自带的 Kotlin 编译（两个源工程都没有 kotlin 插件），**保持原样不要加**。
- 直接用 Android Studio 打开 `D:\A17\NFCTouchDemo`，或 `gradlew assembleDebug`。
- 合并说明与接口契约见 [`MERGE_PLAN.md`](MERGE_PLAN.md)。

**发送页文案口径**：发送页（NFC 流程与纯演示**一样**）与 `D:\A17\Test` **逐字一致** ——
标题是内置的「发送给 fazd9」、上滑提示是「上滑发送 1 张照片」、卡片副标题是照片的真文件名与大小；
NFC 的连接进度只出现在**底部状态行**（「正在连接对方…」/「已连接,上滑发送即可」/失败原因），不动门面文案。
想换成产品里那两句「发送给 用户XXXX」「上滑发送 N 张照片」，把 `ui/OverlayView.kt` 里的
`sendTextsFromPeer` 改成 `true` 即可（接收页不受这个开关影响，它本来就用对端标识与真实张数）。

**真机验证清单（按顺序）**

1. 装 `app-debug.apk` → 启动页能看到「要发送的照片」槽 + 「一碰互联」区块（**没有**背景图槽、**没有**「开始演示」按钮）。
2. 点「开启悬浮窗权限」给出权限；点「把选中的照片交给一碰互联」应弹「已交给常驻服务（1 张）」。
   **不会再弹通知权限**（`HomeActivity` 有意不申请 `POST_NOTIFICATIONS`）：Android 13+ 于是拿不到那条
   动效层控制通知（重播 / 触摸穿透 / 关闭悬浮窗）；Android 12 及以下本来就不需要运行时权限，通知照常出现。
3. **发送侧**：两台机各装一份；A 机选好照片并交给服务，两台碰一下 → A 机弹出与 Test **一致**的发送页
   （标题「发送给 fazd9」、卡片副标题是照片真文件名与大小、下面「上滑发送 1 张照片」），
   底部状态行「正在连接对方…」→「已连接,上滑发送即可」→ 上滑后 B 机收到图。
4. **接收侧**：B 机停在「NFC 联调页」或停在任意界面（靠 `TapDispatchActivity` 跳板）→ 碰一下应弹出「来自 用户XXXX的分享 + 接收 1 张照片 / 拒绝」→ 点「接收」后自动打开查看器。
5. **退出靠流程本身**：发送端上滑发出后、对方点「接收」/「拒绝」后，窗口应自己收起来；
   回到启动页能「退出常驻服务」。（13+ 没有那条通知，所以通知里三个按钮这一步跳过；12 及以下可以顺手看一眼。）
6. 联调：`adb shell am startservice -n com.pri.nfctouchdemo/.nfc.TapService -a com.pri.nfcdemo.DEBUG -e payload "n=Pixel|t=abc123|ip=192.168.1.5" -e role reader` 可以在不碰触的情况下走一遍流程。

---

## 四、已知风险（没有实机验证过的部分）

1. **接收态按钮是这次新写的绘制代码**（`OverlayView.drawReceiveActions` + `layoutReceiveActions` + 命中判定），
   几何与命中用同一份矩形、按 `panelAppearAt()` 淡入，但只有真机能确认手感与位置。
2. **`file://` 预览图解码**：接收侧那张照片由 `TapService.previewUriOrNull()` 落成 `cacheDir/peer_preview.jpg`
   再交给动效层（`ContentResolver.openInputStream` 支持 `file://`）。个别 ROM 上 `ImageDecoder` 可能失败，
   失败时卡片退回内置插画，不会崩。
3. **后台启动服务限制**：HCE 事件里 `startService(TapService)` 是原 demo 就有的写法（Android 8+ 可能被拦，
   原代码用 try/catch 吞掉）。动效层是前台服务，且本 app 必须拿到 `SYSTEM_ALERT_WINDOW`
   （该权限本身就是后台启动前台服务的豁免项），所以实际影响比原 demo 小。
4. **`minSdk` 从原 nfc 的 23 提到 27**（动效层要求），原机型（Armor X17 / Android 14）不受影响。
5. 运行时协议字符串（`com.pri.nfcdemo.TAG` / `.PEER` / `.DEBUG`）**故意保留旧包名**，
   `HceService` / `TapDispatchActivity` / `TapService` 三处成对使用，不要"顺手统一"。
6. **接收页按钮上的张数固定写死为 1**：接收页在碰一碰那一刻就弹（那时还不知道对方要发几张），
   真实总张数到了只更新底部状态行。单张分享（demo 默认）完全正确；多张时按钮文案偏小。
   原因与 1 行补丁见下方「五、可选补丁」的**补丁二**（动效层侧已备好 `OverlayService.peerIntent`）。
7. **动效页停住时页面上没有出口**（有意接受）：正常流程它自己会退（发送完 / 接收 / 拒绝都发 `hideIntent`），
   但 `onXferFailed` 只更新底部状态行、**不摘窗口**（`TapService.java:190` 的注释写明失败时还会"补弹"发送页
   显示原因），而发送页只有"上滑发送"、没有取消。叠加 13+ 默认拿不到通知权限，
   "连上了没上滑"与"连接失败"这两种情况就只能强制停止应用。
   想去掉这个取舍：给发送页加"点空白关闭"，并让 `onXferFailed` 延迟几秒自动 `hideIntent`。

---

## 五、可选补丁（不影响编译）

> **补丁一现在是可选的**：发送页已经改成与 `D:\A17\Test` 逐字一致（不再用对端标识拼标题，
> 见 `ui/OverlayView.kt` 的 `sendTextsFromPeer`），所以不剥前缀也不会露出占位文案。
> 想让发送页显示「发送给 用户XXXX」，先把 `sendTextsFromPeer` 改成 `true`，再打这个补丁。

**背景**（把发送页文案换成对端版本时才需要）：发送确认页的面板标题是动效层自己拼的「发送给 &lt;对端&gt;」。产品路径（碰一碰 →
`TapService.showSendPage()`）已经传了真实对端，显示正常；但 **NFC 联调页**那条路
（`nfc/MainActivity.java` 里分享图片后手动触发）用的还是两参版 `OverlayService.intent(ctx, backdrop, photo)`，
所以它弹出来的标题是动效层自带的占位文案「发送给 fazd9」。

**想做就改这一处**（`app/src/main/java/com/pri/nfctouchdemo/nfc/MainActivity.java`，`showSendOverlay` 里，
L544 `try {` 之后那行）：

```java
// 改前
            startForegroundService(OverlayService.intent(this, u, u)); // 显示类指令走 startForegroundService

// 改后
            // titleText 是旧实现的整句标题(「发送给 用户XXXX」/「碰一下对方手机发送」),
            // 而动效层的标题是自己拼的「发送给 <peer>」——所以这里把前缀剥出来当 peer,
            // 剥不出来(例如"碰一下对方手机发送")就传 null,动效层退回自带默认文案。
            final String peer = titleText != null && titleText.startsWith("发送给 ")
                    ? titleText.substring("发送给 ".length()) : null;
            startForegroundService(OverlayService.intent(this, u, u, peer, Math.max(1, count)));
```

注意 `"发送给 "` 和 `"发送给 ".length()` 里是**半角空格**（和 L651 的字符串一致）。

> 为什么没顺手改掉：这个文件被本工具链的 `read` 误判成 binary（原始 nfc 工程的 `.java` 同样如此，
> 而它们 javac 编译通过），`read` 失败 → 策略不允许 `edit`/`write`，所以只能留给人手改。
> 顺手一提：`nfc/TapService.java` 有同样的问题，但它已经改好了。

### 补丁二：接收页按钮显示真实张数（1 行）

**背景**：接收页是在**碰一碰那一刻**就弹出来的，那时还不知道对方要发几张，
所以 `TapService.showReceivePage()` 只能先按 1 张拉页（按钮 =「接收 1 张照片」）；
对方报出真实总张数后（`Xfer.onImageCount`）只更新了底部状态行（「已收到 0/3 张」）。

动效层这边**已经准备好**就地补正的指令：`OverlayService.peerIntent(ctx, peer, count)`
（内部是 `ACTION_PEER` → `updatePeer()` → `OverlayView.setPeerInfo()`，只重算标题/上滑提示/
卡片副标题/按钮文案，**不重启页面、不重播动画**）。所以只差 `TapService` 里加一行：

```java
    @Override public void onImageCount(int total) {
        mTotalCount = total;
        if (mReceiveShown) {
            // ← 新增这一行：把已经弹出来的接收页补正成「接收 N 张照片」
            startService(OverlayService.peerIntent(this, mPeerLabel, Math.max(1, total)));
            startService(OverlayService.statusIntent(this,
                    "已收到 " + mFullCount + "/" + Math.max(total, mFullCount) + " 张"));
        }
    }
```

单张照片（demo 默认场景）本来就正确，所以这个补丁只影响"一次分享多张"时的按钮文案。

### 补丁三：发送成功后不再清空照片（删 2 行）

**背景**：`TapService.onImageSent()` 在发送完成后做了 `PENDING.clear(); savePending();`（**原 nfc demo 的行为**）。
后果是**发送方发完一次就变回接收方**：那台机 `PENDING` 空了 → 下一次碰一碰被判成接收方；
两台机各发过一次之后，两边都成了接收方，再碰就没意义了。
想让一台机固定当发送方（反复碰、反复发），把这两行删掉即可
（`app/src/main/java/com/pri/nfctouchdemo/nfc/TapService.java`，`onImageSent` 里，L303–304）：

```java
    @Override
    public void onImageSent(int bytes) {
        Bus.log("服务: 发送完成(" + (bytes / 1024) + "KB),悬浮窗关闭,用户仍在相册");
        Bus.unlockRole();
        // PENDING.clear();   // ← 删掉：照片留着，这台机继续当发送方
        // savePending();     // ← 删掉
        mSendShown = false; // 本轮结束,下一轮碰触重新拉起
        startService(OverlayService.hideIntent(this));
    }
```

删掉之后 `onImageSent` 里剩下的东西（解锁角色、复位 `mSendShown`、摘窗口）都不依赖 `PENDING` 是否为空，
`onXferFailed` 的"失败补弹"逻辑也是按当时的 `PENDING` 判断，所以没有连带影响。

**副作用（要想清楚）**：删掉之后这台机**不再自动退回接收方**。想让它偶尔当接收方，有两个办法：
回启动页重新点一次「交给一碰互联」（会用当前选中的那张照片**覆盖**列表），或者清 app 数据。
需要的话可以在启动页加一个「解除发送方身份」按钮（发一个空列表把 `PENDING` 清空）—— 说一声就加。

> 这个文件同样读不了 / 改不了，原因见上面的说明（`read` 误判 binary → 策略禁止 `edit`/`write`）。
