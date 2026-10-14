# 交互 / 时机 / 角色层 —— 对齐参考产品（OPPO 互传）改动清单

> 对象：`com.pri.nfctouchdemo`（分支 `nfc_tap`，提交 `057b617`），平台签名 + `android.uid.system`，系统能力不受限。
> 范围：**只含交互 / 时机 / 角色层** —— `nfc/TapService.java`、`service/OverlayService.kt`、`ui/OverlayView.kt`、`HomeActivity.kt`、`res/values/strings.xml`。
> **不含**链路层（`nfc/Xfer.java`、`nfc/Bus.java`，另一人负责）与「图库入口」问题（另行决定）。
> 事实基线：`docs/APP_FLOW_ANALYSIS.md`、`docs/PRODUCT_VIDEO_ANALYSIS.md`（本次已逐条回读源码复核，文中行号按当前工作区核对）。

---

## 概述

参考产品（视频）的交互模型是**对等 + 双方各确认一次**，而当前实现是**碰触即定主从 + 接收方碰触即被打扰**。要对齐的是三件事：

1. **时机**：接收页不能在碰触那一刻弹，只能在「对方真的上滑」之后弹。
2. **角色**：角色不由「本机有没有待发照片」在碰触时刻决定，而由「谁先上滑」决定；败方在同一台手机上**原地**从发送页变成接收页。
3. **收尾**：成功要安静（现在已经是），失败要在发送方出一条**驻留的红色横幅**（现在只有底部一行小字）。

复用盘点（结论先给）：

| 项 | 复用什么 | 真正新增什么 |
|---|---|---|
| A 接收页时机 | 整套接收页与入场动效（`ACTION_RECEIVE` → `startReceiveFlow`）、预览落盘链路 | `Xfer.Listener` 新增回调的实现 + 删掉 `onPeer` 里一次调用 |
| B 对等角色仲裁 | `OverlayView.setMode/setPeerInfo`（**live view 直接支持换身份，零新绘制**）、接收按钮绘制与命中、现有接收冷路径 | `startReceiveFlow` 一个「就地切换」分支；`TapService` 的 `mSending` 标志与分支；链路层通道解耦（**最大风险**） |
| C 文案与张数 | `strings.xml:21-25` 五条带参文案**早就写好没人用**、`peerIntent` 机制、`refreshTexts()` 机制 | 1 行 `sendTextsFromPeer=true` + `cardMeta` 那段收窄；张数接线；**品牌行「一键互联」是全新绘制（~8 行）** |
| D 拒绝语义 | 现有 `ACTION_UI_RECEIVE_REJECT` 分支、`hideIntent`、两个按钮 | `mRejected` 标志 + 3 处 guard；链路层的中止接口 |
| E 成功/失败表现 | 成功路径已「安静」；失败沿用地板兜底的状态行 | **失败横幅是全新绘制（~30 行）** + 新 action `ACTION_FAILURE` |

> **必须新绘制的只有两处**：C-3 底部品牌行、E 的失败横幅。其余全部复用现有动画与绘制路径。
> 另有一处**现已存在的绘制缺口**：`OverlayView` 里根本没有「一键互联」品牌行（`grep` 全仓无此文案，只有注释提到「底部品牌文字」）——见 C-3。

---

## A. 接收页时机：碰触时「什么都不发生」，对方上滑后才弹

### 现状

- `TapService.onPeer`（`nfc/TapService.java:170-194`）：碰触即判角色。`PENDING.isEmpty()` 的分支里，**立刻** `mMain.post(showReceivePage(label))`（`:183-185`），同时 `x.startHotspot()`。
- `showReceivePage(label)`（`:354-361`）：`startForegroundService(receiveIntent(this, label, 1, previewUriOrNull()))`（`:359`）+ 状态行「正在连接对方…」（`:360`）。
- 第二个调用点是 `onImageReceived`（`:263-277`，判定在 `:268-270`）：整图到达且 `!mReceiveShown` 时**补弹**接收页。
- 结果：无内容那台手机在碰触瞬间就被打扰（视频 f_0009–f_0013 里 A 机在碰触后 4 秒内**毫无变化**）。

### 目标行为

- 碰触时：**无 PENDING 的那台手机上什么都不显示**（不改动它下面的图库/桌面），只做准备（`setExpectedPeer` / `startAsReceiver` / `startHotspot`）。
- 只有「对方真的上滑、传输真正开始」时，才弹接收页；弹出来的那一帧**卡片里已经有对方的照片预览**（视频 f_0015，t=7.0s）。

### 改法

1. **删**：`TapService.java:185` 的 `mMain.post(showReceivePage(label))` 整行；分支保留 `x.startHotspot()`（`:184`），日志改成「本机无照片 → 本机是接收候选,等对方上滑」。
2. **新增回调实现**（本文件内，`Xfer.Listener` 的新方法，见「需要链路层提供的接口」#1）：

```java
@Override
public void onSendStarted(int total) {          // 对方上滑、传输真正开始(对方每台都会收到)
    if (mSending) return;                       // 本机是先上滑那台:保持发送方(见 B)
    if (mRejected) return;                      // 本机已拒绝过:不许再弹(见 D)
    mTotalCount = Math.max(total, 0);
    mMain.post(new Runnable() { @Override public void run() {
        if (mReceiveShown) {                    // 幂等:重复回调只补张数
            startService(OverlayService.peerIntent(TapService.this, mPeerLabel, Math.max(1, mTotalCount)));
            return;
        }
        if (!PENDING.isEmpty()) { switchSendPageToReceive(); return; }   // 见 B
        showReceivePage(mPeerLabel, mTotalCount);                        // 无内容那台:此刻才弹
    }});
}
```

3. **`showReceivePage` 加张数入参**：`showReceivePage(String label, int count)`，`TapService.java:354-361` 里 `receiveIntent(this, label, Math.max(1, count), previewUriOrNull())`（替换 `:359` 写死的 `1`）。
4. **状态行按链路现状挑文案**：`showReceivePage` 现在恒发「正在连接对方…」（`:360`），但新时机下链路**已经就绪**；改成 `mLinkReady ? "已连接,等待接收…" : "正在连接对方…"`（等价于 `TapService.java:231` 那条文案）。否则接收页会一直停在「正在连接对方…」（`onP2pReady` 里那条 `else if (mReceiveShown)` 分支在页还没弹时不会补发，`:230-232`）。
5. **`onImageReceived` 里的补弹保留但收窄**（`:268-270`）：新时机下正常不会再走到（页已在 onSendStarted 弹出），它只剩「onSendStarted 丢失时的兜底」价值，加 `&& !mRejected` 后保留。
6. **新一轮清场**：`onPeer` 开头（`:171-173`）顺手加 `mLastBmp = null;`（现在 `mLastBmp` 跨轮粘滞，下一轮的 `previewUriOrNull()` 会把上一轮的图当本轮预览推给动效层）。

### 复用 vs 新增

- **复用**：`receiveIntent` / `startReceiveFlow` 整套接收页与入场动效；`onPreview` 的缓存逻辑（`:244-252`，它本来就是「先把图留下、`mReceiveShown` 为假就返回」——与「先到预览、后弹页」天然吻合，**一行都不用改**）；`previewUriOrNull()`（`:369-386`）。
- **新增**：一个 `@Override` 方法 + 一次删除 + `showReceivePage` 多一个入参。**无新绘制、无新 action**。

### 风险

- 链路层若不提供 `onSendStarted(int)`，接收方**彻底没有入口**（A 的删除在没有回调时是致命的，必须与链路层同批落地）。
- 预览必须先于上滑到达，卡片才「一出现就有照片」。当前 `sendPreviews` 在 `onP2pReady` 就推（`TapService.java:234-241`），常态是够的；用户上滑比预览推送还快时会先显示占位插画、整图到了再换（可接受的降级，日志已有「对方确认页将显示占位图」）。
- 链路层的 `onSendStarted` **不能**以 `TYPE_PREVIEW` 为触发点（预览在上滑之前就发了，会把时机退回"碰触即弹"）；必须以 `TYPE_META`（传输开始）为标记。

---

## B. 对等角色仲裁：双方都亮发送页，先上滑者胜，败方原地变身

### 现状

- 角色在碰触时刻由 `PENDING` 决定：`TapService.java:182-193`，非空 → `showSendPage()` + `x.startAsSender()`；空 → 接收方。
- **两台都有 PENDING 时没有任何仲裁**（`APP_FLOW_ANALYSIS.md` §3.3）：双方都弹发送页、都 `startAsSender()`，而**没有人调 `startHotspot()`**（热点只在 `PENDING.isEmpty()` 分支里起，`:184`），链路只能指望局域网/P2P 兜底。
- 「谁先上滑」这个概念目前不存在：`ACTION_UI_SEND_CONFIRMED`（`:103-113`）只做「发图」，不做「宣告我是发送方」。

### 目标行为（视频 §2.1 的对称设计）

- 碰触后**凡是当前在看的手机都亮「发送给 <对端>」面板**；谁先上滑谁是发送方。
- 败方**同一台手机、同一次会话、不重启动画**，从「发送给 X」切换成「来自 X 的分享 + 接收/拒绝」。

### 关键可行性结论（已查证）

- **`OverlayView` 支持活体换身份，且零新绘制**：`mode` 是绘制期分支（`drawTransferPanel` 的 `:2165` 尾部高度、`:2184-2186` 布局、`:2203-2210` 尾部内容），`setMode()`（`:764-770`）只做 `mode=…; pressedButton=NONE; refreshTexts(); invalidate()`，**不碰时间轴、不重建视图、不重播入场**。所以「原地切换」就是一次 `setMode(RECEIVE)` + `setPeerInfo(peer,count)`。
- 切换后上滑手势**自动失效**：`onTouchEvent` 在 `:2485` 直接 `if (mode == Mode.RECEIVE) return onReceiveTouch(event)`，接收态走两个按钮的命中判定（`:2528-2570`，与 `layoutReceiveActions()` 共用几何）。
- 若在 `elapsed < Timeline.PANEL_MS` 时切换，面板本来就还没画，切换后直接以接收页内容浮现（`drawReceiveActions(c, appear)` 挂在 `panelAppearAt` 上），不会闪。

### 改法

1. **`OverlayView.setMode()` 补一行 `resetSendState()`**（`:764-770`）。原因：若败方此刻**手指正按在卡片上拖动**（`dragging=true, dragY!=0`），切到 RECEIVE 后 `ACTION_UP` 会落进 `onReceiveTouch` 且 `pressedButton==NONE` 直接 `return false`，**卡片会永久停在偏移位置**。`resetSendState()`（`:1103-1114`）会清 `dragging/dragY/flying` 并取消动画器，冷路径（`applyContentAndPlay` 里也调 `setMode`）本来就已经走过 `wipeVisuals()`→`resetSendState()`，加这一行无副作用。
2. **`OverlayService.startReceiveFlow` 增加「就地切换」分支**（`:232-259`，在读 extra 之后、`buildOverlay/wipeVisuals` 之前）：

```kotlin
val liveSwitch = rootView != null && attached && mode == OverlayView.Mode.SEND
if (liveSwitch) {              // 败方:同一台手机、同一次会话,原地换身份
    mode = OverlayView.Mode.RECEIVE
    overlay.setMode(mode)       // 内部会 resetSendState()
    overlay.setPeerInfo(peerLabel, peerCount)
    overlay.setStatus(statusText)
    return                      // 不 wipeVisuals、不重解码、不重播
}
```

- **不需要新的 action**：`ACTION_RECEIVE`/`receiveIntent`（`OverlayService.kt:955-960`）语义本来就是「本机成为接收方」，加一个分支即可；**不建议**用闲置的 `ACTION_PEER`/`peerIntent`（`:983-987`）承担这件事——它的契約是「只补正对端/张数，别的都不动」（`:305-309`、`:867`），用它切身份还要再配一条新指令，两条指令反而更绕。
- 备选（不推荐）：新增 `ACTION_MODE` + `modeIntent(ctx,mode)`。多一个常量、多一个工厂，收益只是语义更纯。

3. **`TapService` 的败方分支**（B 的 `switchSendPageToReceive()`）：

```java
private void switchSendPageToReceive() {
    mSendShown = false;          // 发送页已经不在了(被就地改写)
    mReceiveShown = true;        // 从现在起按接收方走 onImageReceived/onTransferDone/回执分支
    mAccepted = false; mPreviewCount = 0; mFullCount = 0; mTransferDone = false; mBmps.clear();
    startForegroundService(OverlayService.receiveIntent(this, mPeerLabel, Math.max(1, mTotalCount), previewUriOrNull()));
    startService(OverlayService.statusIntent(this, mLinkReady ? "已连接,等待接收…" : "正在连接对方…"));
    Uri p = previewUriOrNull();  // 用预览图把卡片里的"我自己的照片"换成对方那张
    if (p != null) startService(OverlayService.photoIntent(this, p));
}
```

   - `photoIntent`（`OverlayService.kt:267-293`）在 `mode==RECEIVE` 时会把卡片和折射背景一起换成对方照片——正是要的效果，**复用现成动作**。
   - 此处会**多一次 `previewUriOrNull()` 写盘**（同内容覆盖 `cacheDir/peer_preview.jpg`），可提出来只算一次。

4. **「本机先上滑」的标志**：在 `ACTION_UI_SEND_CONFIRMED` 分支真正调用 `sXfer.sendImages(...)` 的地方（`TapService.java:112`）置 `mSending = true`；`mSending` 在 `onPeer` 开头复位，`onImageSent`/`onXferFailed` 里也复位。`onSendStarted` 先看 `mSending`（A 的代码里已体现）。
5. **PENDING 不清空**（与链路口径一致、也是对齐必需）：见「无法对齐或需要取舍的点」#6 —— 参考产品的两台手机在第二轮**都能再发**（f_0046），而当前 `onImageSent` 会 `PENDING.clear()`（`TapService.java:305-306`），发送方发完一次就永久退出「可发送」集合。建议把 README 补丁三（删这两行）**改为必须打**，并在 `HomeActivity` 增加一个「解除发送方身份」按钮（清 `PENDING` + `savePending`）作为手动出口——`activity_home.xml` 现有 `btn_exit`（:181）同款按钮加一枚即可。
6. **链路层**：见「需要链路层提供的接口」#4（通道准备必须与 PENDING 解耦）。

### 复用 vs 新增

- **复用**：`setMode` / `setPeerInfo` / `setStatus` / `photoIntent` / 接收按钮绘制与命中 / 现有接收冷路径（`rootView == null` 或窗口已摘时自动退回原路径，`OverlayService.kt:245-249`）。
- **新增**：`startReceiveFlow` 的 ~8 行分支 + `setMode()` 里 1 行 + `TapService` 的 `mSending`/`switchSendPageToReceive()`。**零新绘制**。

### 风险

- **最大风险在链路层**：现在「谁是热点方/谁是发送方」是按 `PENDING` 在碰触时刻定的（`TapService:184` vs `:192`）。两台都「在看图」时双方都走 `startAsSender()`，**没人起热点**，第二轮（双机同亮面板）大概率连不上。必须由链路层把「通道准备」与「本机是否待发」解耦（见接口 #4）。
- **双上滑竞态**：视频没拍到（`PRODUCT_VIDEO_ANALYSIS.md` §9.10）；交互层无法裁决。退化为「谁先真正写成功谁赢」，败方走 `onXferFailed` → 失败横幅（E）。需链路层给一个确定性判定（接口 #5）。
- `Xfer.mAll` 是 `CopyOnWriteArrayList` 且 `addListener` 不去重，而 `TapService` 每次 `onStartCommand` 都 `addListener`（`TapService.java:73`）→ **新回调会被投递多次**（`APP_FLOW_ANALYSIS.md` §8.2-10）。A/B 的新处理函数必须幂等（本文里的写法已经是幂等的）。顺手可修的 1 行（`if (!mListenerAdded) x.addListener(this)`）不在本次范围，但要知会。

---

## C. 文案与张数

### C-1 发送页显示真实对端（现状：恒为占位「发送给 fazd9」）

- **现状**：`OverlayView.kt:972` `private val sendTextsFromPeer = false` → `refreshTexts()`（`:983-1007`）里 `mode==SEND` 时恒用内置文案：标题 `panel_receiver`＝「发送给 fazd9」、上滑提示 `panel_swipe_tip`＝「上滑发送 1 张照片」（`strings.xml:12,15`）。`TapService.showSendPage()` 其实**已经把真实 peer/张数传进去了**（`:214-215` `OverlayService.intent(this, backdrop, backdrop, mPeerLabel, PENDING.size())`），被这一行开关丢掉。
- **目标**：`发送给 <对端>`（视频 f_0011/f_0046）+ `上滑发送 N 张照片`。文案 `strings.xml:21,23` 已就位。
- **改法**：`OverlayView.kt:972` 改 `true`（1 行）。
- **副作用与建议收窄**：改成 `true` 后 `refreshTexts()` 里 `val count = …` 在 SEND 态变成 `peerCount`，于是 `:998-1000` 会把卡片副标题改写成 `「JPG · N 张照片」`（`card_meta_count`），**覆盖掉发送页现在挺好用的「真文件名 + 大小」**（`ImageLoader.describe`，`OverlayService.kt:329`）。视频里接收卡副标题不可辨，无从对证；建议把副标题改写**收窄到接收态**：

```kotlin
if (mode == Mode.RECEIVE && peerCount > 0) {      // 原: if (count > 0)
    cardMeta = context.getString(R.string.card_meta_count, metaType(), count)
}
```

  这样发送页保留真文件名（更耐看、且不与视频冲突），接收页在 `peerIntent` 补正张数时才切成「JPG · N 张照片」。
- **对端名字的口径（需拍板）**：`TapService.java:175` 用 `"用户" + field(payload,"t")`，`t` 是 8 位随机 token → 屏幕上会出现「发送给 用户abc12345」。视频里两端名字是设备名/昵称（`U119652157` / 「一路有云」）。payload 现成的只有 `n=`（`Build.MODEL`）和 `b=`（蓝牙名）。**建议**改成 `n` → `b` → `"用户"+t` 依次兜底（`发送给 Pixel 7` 比 `用户abc12345` 更接近产品观感）；**真正对齐需要 payload 加昵称字段**——属于链路层，见「需要链路层提供的接口」#6。
- **复用 vs 新增**：纯复用（五条带参文案与 `refreshTexts` 机制都是现成的，此前只是被开关关掉）。**零新绘制**。
- **风险**：`strings.xml:22` 的 `来自 %1$s的分享` 与视频「来自 一路有云 **的**分享」之间差一个空格（视频字号极小，属推断，`PRODUCT_VIDEO_ANALYSIS.md` §9.6）；要不要加空格建议一次定掉，避免反复改版。

### C-2 接收页按钮张数（现状：写死 1）

- **现状**：`TapService.java:359` `receiveIntent(this, label, 1, …)` 写死 `1`；真实总张数只更新底部状态行（`:254-261`）。
- **README §五补丁二是否正确？** —— **机制上正确、可以照打**：`peerIntent`（`OverlayService.kt:983-987`）→ `ACTION_PEER` → `updatePeer()`（`:305-309`）→ `overlay.setPeerInfo()` → `refreshTexts()` 重算 `receiveAcceptText`＝「接收 N 张照片」（`OverlayView.kt:1001-1005`），**不重启页面、不重播动画**；用 `startService` 也合规（服务已在前台）。`Math.max(1, total)` 的写法没问题。
- **它应该改成什么**：
  1. 首选**把张数提前到弹出那一刻**：A 的 `onSendStarted(int total)` 带上 META 的总张数 → `showReceivePage(label, count)` → `receiveIntent(..., Math.max(1,count), ...)`。这样按钮**一出现就是正确张数**，不会先闪一帧「接收 1 张照片」。
  2. `onImageCount` 里的 `peerIntent` 那一行**保留**，作为「弹出时张数未知（回调没带 total / 走的是兜底补弹路径）」时的补正网；幂等、无副作用。
  3. 注意补丁二的副作用：`peerIntent` 会连带重算 `cardMeta`（见 C-1 的收窄），接收页上被改写成「JPG · N 张照片」——**这正好是接收页想要的**，与 C-1 的收窄一致。
- **复用 vs 新增**：纯复用（`peerIntent` + `ACTION_PEER` + `setPeerInfo`），**零新绘制**。

### C-3 底部品牌行「一键互联」（**代码里根本不存在 → 需要新绘制**）

- **现状**：`grep -rn "一键互联"` 在 `app/src` 下**零命中**。`OverlayView.kt:705` 注释写「正好落在底部品牌文字上方」、`:717` 注释写「避免底部品牌文字贴到系统栏」——**注释在描述一个没被实现的东西**。视频里发送面板与接收卡片**都**有这行（f_0009 / f_0015，字号最小、居中，图库图标 + 「一键互联」）。
- **改法（最小的新绘制）**：
  - `strings.xml` 加 `<string name="brand_line">一键互联</string>`（放在动效页文案区，`:9-29`）。
  - `OverlayView.kt` 加常量 `BRAND_BOTTOM_PX = 20f`（与 `STATUS_BOTTOM_PX = 46f`（`:705-706`）上下呼应：品牌行在下、状态行在上）。
  - `drawTransferPanel()` 里、`drawStatusLine` 调用点旁（`:2213-2216`）加一次 `drawBrandLine(c, textAlpha)`：`textPaint`（`normal` + 10f*k）+ `withAlpha(0xFFFFFFFF, alpha*0.55f)` + 居中于 `viewW/2`、`y = viewH - bottomInset - BRAND_BOTTOM_PX*k`。**约 8 行**，复用 `textPaint` / `withAlpha` / `k` / `bottomInset`，不新增 Paint。
  - 落在 `drawTransferPanel` 内 = 天然跟着面板淡入淡出（视频里发送方上滑后面板整体清空，品牌行一起消失）。
- **复用 vs 新增**：**新增绘制（约 8 行）+ 1 条字符串**。视频里的「图标」不画（视频分辨率下无法确认图标形状）。

---

## D. 接收方「拒绝」的 UI 侧语义

### 现状

- 拒绝走 `TapService.java:127-141`：`Bus.unlockRole()` + `hideIntent()` + 清本轮接收状态（`mReceiveShown=false` 等）。
- **缺陷**：清掉 `mReceiveShown` 之后，只要还有整图到达，`onImageReceived` 里的 `if (!mReceiveShown) showReceivePage(...)`（`:268-270`）会**把接收页重新弹出来**（`APP_FLOW_ANALYSIS.md` §8.2-11）；且 socket 读循环与 `saveToGallery` 完全不看用户意向（`Xfer.java:724-771`），**拒绝了照收照存**。

### 目标行为（视频 f_0055–f_0057）

- 点「拒绝」→ 卡片收起、按钮消失，回到纯查看器；**之后本轮的接收页永不再现**；对方收到「拒收」。

### 改法

1. **加一个本轮标志 `private boolean mRejected;`**（`TapService` 字段区，`:45-46` 附近）。
   - `ACTION_UI_RECEIVE_REJECT` 分支（`:127-141`）里 `mRejected = true;`（放在现有清理之前）。
   - `onPeer` 开头（`:171-173`）`mRejected = false;`（新一轮解锁）。
2. **三处 guard**：
   - `onSendStarted`：`if (mRejected) return;`（A 的代码已含）。
   - `onImageReceived`（`:268-270`）：`if (!mReceiveShown && !mRejected) showReceivePage(...)`。
   - `onImageCount`（`:254-261`）/ `onImageReceived` 的状态行：`mRejected` 时直接 `return`，不要再刷「已收到 i/N 张,已存入相册」（拒绝后还在刷进度会自相矛盾）。
3. **链路层联动（必须，UI 改不了）**：拒绝时调 `Xfer` 的中止入口（接口 #3），否则图仍会落相册——这是 UI 层无法兜住的。
4. **顺序**：拒绝分支现在先 `hideIntent()` 再 `unlockRole()`，保持即可；`mRejected` 要在 `hideIntent` **之前**置位，避免同一帧内的 `onImageReceived` 抢先补弹。

### 复用 vs 新增

- **复用**：现有 `ACTION_UI_RECEIVE_REJECT` 分支、`hideIntent`、`Bus.unlockRole()`、两个按钮与命中判定。
- **新增**：1 个字段 + 3 处判断。**零新绘制**。

### 风险

- 若链路层不提供中止接口，「拒绝后仍然传完并入库」这一点无法对齐（视频里拒绝是**一条正式路径**，有对端反馈闭环）。

---

## E. 成功 / 失败的表现

### E-1 成功：安静（现状已基本符合）

- 发送方：上滑 → `startFlyAway()`（`OverlayView.kt:2648-2669`）→ 卡片飞走 → `finishSend()` → `onSendFinished` → `timeline.reset()`（`OverlayService.kt:518`）→ `TapService.onImageSent`（`:301-311`）→ `hideIntent` 摘窗口。**没有 toast、没有「已发送」提示**（`OverlayView.kt:46-47` 明确写了「按需求不要它」）→ 与视频「成功无任何提示、面板静默清除」一致。
- 接收方：点「接收」→ `ACTION_UI_RECEIVE_ACCEPT` → `hideIntent` + `showViewer`（`:114-126`、`:280-299`）。**差异**：视频里是「两个胶囊消失、照片原地保留、回到桌面」，我们是直接弹全屏查看器（旧 `nfc/Overlay`）。建议**保留现状**（查看器是这个 demo 的既有功能），若要严格对齐需产品拍板（见「取舍」#4）。
- 视频里的**粒子迸发**（f_0049）不做：复用现有的卡片飞走动效代替（用户已明确「复用现有动效优先」）。

### E-2 失败：发送方顶部**驻留红色横幅**（**需要新绘制**）

- **现状**：`onXferFailed(why)`（`TapService.java:313-319`）只做 `statusIntent(why)`（底部一行 11 设计 px 灰字，`OverlayView.kt:2467-2478`）+ `unlockRole()`；窗口不摘。
- **底部状态行能不能改造复用？——不能**，理由要写清楚：
  1. **位置**：视频里横幅在**屏幕最顶部、紧贴状态栏下方**（f_0055）；状态行在底部（`viewH - bottomInset - 46*k`，`OverlayView.kt:2475`）。
  2. **形态**：横幅是「红色圆圈 ! + 粗体主行 + 次级副行」两行；状态行是单行小字。
  3. **存续**：横幅**驻留**（片尾仍在）；状态行挂在 `textAlpha`（`= panelAppearAt` 那一档，`:2213-2216`）上，且发送方上滑成功/失败后 `timeline.reset()`（`OverlayService.kt:518` → `Timeline.kt:204` `reset()=seek(0)`）**整个面板层不再绘制**（`onDraw` 的 `if (elapsed >= Timeline.PANEL_MS)`，`OverlayView.kt:1209`）→ 状态行会随面板一起消失。
  4. **语义**：状态行同时承载「正在连接对方…/已连接,上滑发送即可」等过程信息，混进失败态会互相覆盖。
- **改法（最小新增绘制，最大化复用现有 Canvas 机制）**：
  1. `OverlayView.kt` 加两个字段 + 一个 setter：`private var failureTitle: String? = null` / `private var failureDetail: String? = null` / `fun setFailure(title: String?, detail: String?) { … ; invalidate() }`。
  2. `onDraw()`（`:1198-1215`）末尾加 **不受 `elapsed` 门控**的一笔：`if (failureTitle != null) drawFailureBanner(canvas)`。
     —— 这一条是硬要求：发送方上滑后 `timeline.reset()` 会把面板层整个关掉，横幅只有画在门控之外才能「驻留」（视频 f_0057–f_0060 正是面板已清空、横幅还在）。
  3. `drawFailureBanner(c)`（新函数，约 30 行）：**复用** `textPaint`（`bold` 主行 13f*k / `normal` 副行 11f*k，`OverlayView.kt:1016-1017, 1064`）、`iconPaint`（`drawCircle` 画红圆 + `drawText("!")`，`:1065`）、`shadowPaint`/`cardPaint` 画圆角底板（`:1058-1059`、`drawRoundRect`，同 `drawReceiveActions` 的写法 `:2433`）、`drawCenteredLabel`（`:2454-2459`）、`withAlpha`（`:2709`）、`topInset`（`:715`，横幅顶在状态栏下面）。宽度取 `min(CARD_W*k, viewW - 48*k)`、居中、高约 `56f*k`（两行 + 内边距）；配色用现有调性：红 `0xFFE5484D`、主行 `0xFFFFFFFF`、副行 `0xFFB9C2CC`（与状态行同色）。**不新增 Paint / Shader / 动画**。
  4. `OverlayService.kt`：加 `ACTION_FAILURE` + 工厂 `failureIntent(context, title, detail)`（照抄 `statusIntent`，`:971-974` 的 4 行），在 `onStartCommand` 的 `when`（`:137-152`）里加一支 `updateFailure(intent)`：存进服务状态 + `overlay.setFailure(title, detail)`。
  5. **清场**：`wipeVisuals()`（`:384-395`）里加 `overlay.setFailure(null, null)`（新一轮两种入口都会走它），服务侧字段一并清空。
  6. **接线**：
     - `onSendRejected()`（接口 #2）→ `failureIntent("发送失败", "对方拒收")`（照抄视频两行文案）+ `unlockRole()`；**不摘窗**（驻留）。
     - `onXferFailed(why)`（`:313-319`）→ **保留**现有底部状态行（长文案「对方尚未连接,请让对方保持在前台再试」放这里才放得下），并追加 `failureIntent("发送失败", <短原因>)`；`why` 超过约 12 字就截断（`textPaint.measureText` 后在绘制里收不住，建议在 `TapService` 截断后传入）。若不想动其它失败路径的观感，可只对「传输已开始后的失败」发横幅（用 `mSending` 判定）。
- **复用 vs 新增**：**唯一必须新增绘制的交互项（约 30 行）+ 1 个 action**。成功路径**零改动**。

### 风险

- 横幅驻留 + 悬浮窗整屏吃触摸 + Android 13+ 拿不到通知 = **用户没有出口**（既有缺口 `APP_FLOW_ANALYSIS.md` §8.1-1，视频里也驻留到片尾，但那台手机是图库、还能操作）。建议就地补一个小出口：**点横幅 / 点空白 → `hideIntent()`**（约 3 行，命中判定用横幅矩形即可，几何与 `drawFailureBanner` 共用一份）。这不算违背「失败要驻留」——观众看到的是驻留，用户点得掉。
- 若链路层区分不出「拒收」与普通失败（接口 #2），横幅文案就只能退化成「发送失败 / 网络中断」，视频那句「对方拒收」对不上。

---

## 需要链路层提供的接口（`nfc/Xfer.java` / `nfc/Bus.java`，另一人负责）

> 交互层不改这两个文件，但下面这些缺一不可；括号里是「没有它会怎样」。

1. **`Xfer.Listener.onSendStarted(int total)`** —— 传输**真正开始**时，**两端都收到**：发送侧在 `sendImages()` 开始写 `TYPE_META` 时本地触发；接收侧在**收到第一个 `TYPE_META`** 时触发。`total` = META 的 `len`（本次总张数）。
   （缺 → 接收方永远不弹页；或在 `TYPE_PREVIEW` 上触发 → 时机退回「碰触即弹」，A 白做。）
2. **`Xfer.Listener.onSendRejected()`** —— 发送方收到对端的「拒绝」时触发。
   推荐协议加一个 `TYPE_REJECT` 回帧；兜底方案：接收方拒绝时**关闭 socket**，发送方在 `sendImages()` 的写路径上捕获 `IOException` 且「传输已开始过」时上报本回调（这个场景里对端提前关闭基本只可能是拒绝，可接受）。
   （缺 → 视频里唯一的失败态复刻不出来。）
3. **`Xfer.rejectIncoming()`（名字随意）** —— 接收方拒绝时由 `TapService` 调用：**停掉读循环、关 socket、不再 `saveToGallery`**。
   （缺 → 拒绝了照样收完存相册，D 只能是半成品。）
4. **通道准备与 `PENDING` 解耦（最重要）** —— 现在 `TapService` 用「有没有 PENDING」决定 `startHotspot()`（`:184`）还是 `startAsSender()`（`:192`）；两台都在看图时没人起热点。需要：碰触后**两端都做「可能接收」的准备**（监听 + 通道候选），只有**上滑那台**在真正开始传输时才占用通道；败方要能把自己的「发送准备」退回/复用为接收准备。具体 API 形态由链路层定。
   （缺 → 「双机同亮面板」（视频 f_0046，第二轮）在真机上大概率连不上。）
5. **双上滑竞态的确定性裁决**（可选但建议）—— 谁先真正写成功谁赢；败方沿用 `onXferFailed` 上报即可（交互层显示失败横幅）。
   （缺 → 两边同时滑时行为取决于 TCP 竞态，`APP_FLOW_ANALYSIS.md` §10.6 已承认无法静态推断。）
6. **对端昵称（可选）** —— 若能在 payload 里多带一个用户昵称字段（现仅 `n`/`b`/`t`），C-1 的「发送给 X」才能真正像产品；否则退而用机型名。注意 `MAX_PAYLOAD = 200` 与静默截断（`Bus.java:41,98-102`）、以及新增字段会挤掉尾部 `hs/hp` 的风险。
7. **预览时机保持现状**：链路就绪即推预览（`sendPreviews`），**预览不能当作「对方已开始发送」的信号**（它是上滑之前发的）。

---

## 无法对齐或需要取舍的点

1. **通道角色 vs `PENDING`（最大阻碍）**：见接口 #4。这不是 UI 能绕的，不解决则「对等模型」只在「一方无照片」时才成立。
2. **双上滑竞态**：视频未拍到，交互层无权裁决，需链路层给判定；退化为「先写成功者赢」。
3. **粒子迸发不做**：复用现有卡片飞走动画（`startFlyAway`）代替视频 f_0049 的星点粒子。视觉差异，接受。
4. **接收方点「接收」后我们弹全屏查看器**，视频里只是「按钮消失、照片留下」。保留现状（demo 既有功能）还是改成「先留照片再静默摘窗」，需产品拍板。
5. **失败横幅驻留 + 无退出入口**：既有缺口（`APP_FLOW_ANALYSIS.md` §8.1-1）。视频里驻留到片尾（f_0060），建议对齐「驻留」，但补一个「点一下收起」的出口，否则只能强杀应用。
6. **`PENDING` 清空时机**（README 补丁三）：参考产品第一轮发完的 B 在第二轮仍能发（f_0046），所以**建议改为不清空**；代价是这台手机不会自动退回接收方，需要 `HomeActivity` 给一个「解除发送方身份」的手动出口。与「图库入口」方案耦合，最终以入口方案为准。
7. **对端名字**：payload 只有机型/蓝牙名/随机 token，做不出视频里的「一路有云」；用机型名是当前能拿到的最接近的形态（其余需接口 #6）。
8. **接收页卡片副标题会显示缓存文件名**：`photoIntent` → `updatePhoto` → `ImageLoader.describe(cacheDir/peer_preview.jpg)`（`OverlayService.kt:276,289`、`ImageLoader.kt:106-118`）→ 卡片副标题变成「peer_preview」，主标题变成「peer_preview」。视频无法验证副标题，但这是明显缺陷。**可选小改**：给 `photoIntent` 加两个可选 extra（`EXTRA_CARD_TITLE` / `EXTRA_CARD_META`），`updatePhoto` 带了就不走 `describe`，由 `TapService` 传「照片」+「JPG · N 张照片」。属新增 2 个 extra，无新绘制。
9. **发送方无超时、无取消**：视频也没有超时态（`PRODUCT_VIDEO_ANALYSIS.md` §6），保持；但「连上了没上滑」会一直停住，与第 5 点同源。
10. **预览丢失时的降级**：用户上滑早于预览推送时，接收卡先显示内置插画、整图到了再换——可接受，不额外处理。
11. **重复回调**：`TapService` 每次 `onStartCommand` 都 `addListener`（`:73`）→ 新回调会到 2+ 次。本文所有处理函数都写成幂等（`mReceiveShown` / `mSending` / `mRejected` 三重 guard），**不依赖链路层修这个问题**。

---

## 实施顺序

**第一批（不依赖链路层，可独立编译验证）**

1. **C-1**：`OverlayView.kt:972` → `true`；`:998` 的 `cardMeta` 改写收窄到 `mode == Mode.RECEIVE`。（验证：发送页标题出现「发送给 用户XXXX」，卡片副标题仍是真文件名；接收页不受影响。）
2. **C-3**：`strings.xml` 加 `brand_line` + `OverlayView.drawBrandLine`（新绘制 #1）。（验证：两态底部都出现「一键互联」，随面板淡入淡出。）
3. **E 的视图与指令部分**：`OverlayView.setFailure/drawFailureBanner`（新绘制 #2）、`OverlayService.ACTION_FAILURE/failureIntent`、`wipeVisuals()` 清场。（验证：用 `adb shell am startservice … -a com.pri.nfctouchdemo.action.FAILURE` 看横幅；再确认面板复位后横幅仍在。）
4. **D 的 UI guard**：`mRejected` 字段 + 3 处 guard（此时尚无链路层中止，仍能防「拒绝后又弹页」）。
5. **C-2 兜底**：`onImageCount` 里的 `peerIntent`（README 补丁二，按原样即可）。

**第二批（依赖链路层 `onSendStarted`，A + B 必须同批）**

6. **A + B 一起落**：`onPeer` 删掉碰触即弹接收页 + `showReceivePage(label,count)` 改造 + `onSendStarted` 处理 + `startReceiveFlow` 就地切换分支 + `setMode()` 补 `resetSendState()` + `mSending`/`switchSendPageToReceive()` + `mLastBmp` 跨轮清理。
   —— 分开落会出现「接收方既没有旧入口、也没有新入口」的空窗，**必须同一批**。
7. **E 的失败接线**：`onSendRejected()` → 横幅「发送失败 / 对方拒收」；`onXferFailed` 追加横幅。

**第三批（链路层联动，随接口到位逐个接）**

8. **D 闭环**：拒绝 → `Xfer.rejectIncoming()`。
9. **C-2 精确化**：`onSendStarted(total)` 带上张数，弹页即正确张数。
10. （可选）PENDING 策略（取舍 #6）+ `HomeActivity` 的「解除发送方身份」按钮 + 失败横幅的点按收起。

**验证锚点（真机）**：碰触后无内容那台**毫无变化** → 有内容那台 ~0.5–1s 出面板 → 上滑 → 对方**≤0.5s** 出接收卡且卡里已有照片 → 点「接收」→ 按钮消失/进查看器；第二轮双机同亮面板，先滑者胜、败方**原地**变「来自 X 的分享」；点「拒绝」→ 接收方卡片收起且不再弹，发送方顶部出现驻留横幅且重播/新碰触后消失。
