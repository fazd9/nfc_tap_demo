# 碰一碰传图 · 动效实现规格【对齐 OPPO 互传 · 页面出现之后的五个效果】

> 对象：`/dfb/zhuyang/share/nfc_test/pri_project/android/pri_app/nfc_tap_demo`（分支 `nfc_tap`，提交 `057b617`），包名 `com.pri.nfctouchdemo`。
> **本文只出规格，不落代码。** 所有 `文件:行号` 都按当前工作区逐个复核过；帧引用一律写成 `_Temp/anim/xxx.jpg (n=?, t=?)`，帧号是 `-ss` 起点反推的 0 基序号。
> 素材：`Temp/video.mp4`（只读）；分析产物 `_Temp/anim/`（501 个文件，命名与复现命令见 `ANIM_INVENTORY_VIDEO.md` 附录 A/B）。

## 本文的地位（与既有文档的关系）

| 文档 | 关系 |
|---|---|
| `docs/ANIM_INVENTORY_VIDEO.md` | **参考侧事实来源**。本文的 E4–E8 定义、时长、颜色全部引它，不重复抄它的逐帧时间线。 |
| `docs/ANIM_INVENTORY_EXISTING.md` | **我方现状来源**。本文的"现状"栏引它的 §2 主表条目号，只补它没写到的挂载细节。 |
| `docs/ALIGN_PLAN_FINAL.md` | **功能/协议方案，不动**。本文只做它的动效部分；两者冲突处以本文为准（见下）。已在它 §1.2 范围里加了一段 ≤10 行的范围说明指向本文。 |
| `docs/PRODUCT_VIDEO_ANALYSIS.md` | 仅作背景。它的 §3/§4.2/§5/§6 四处已被 `ANIM_INVENTORY_VIDEO.md` 更正，**不要**按它写规格。 |

**一句话结论**：这五项里，**只有两处是真正的"新绘制代码"** —— E4 的一枚「取消」胶囊分支、E7 的一层照片白化矩形；其余全是状态位、时间轴刻度、窗口参数与一次 74 行文件的 git 恢复。E5 与 E6 **零新绘制**。

---

## 1. 范围与决策

### 1.1 用户已定的范围（本文的全部前提）

| # | 决定 | 后果 |
|---|---|---|
| **D-A** | **E1（碰触瞬间的方位性彩虹弧光晕）不做** —— 跳过。 | 本文不含任何 E1 条目；`OverlayView` 的"顶部波源光斑"（`ANIM_INVENTORY_EXISTING` #10）继续作为我方碰一碰瞬间的唯一顶部光效，不改成彩虹环。 |
| **D-B** | **入场动效全保留** —— 从碰触到「发送页 / 接收页 + 照片」出现在屏幕上这一整段（GL 水波折射 + 四边白线 + 边缘辉光 + 顶部光斑），**以及"面板浮现"本身**（scrim + 接收方信息行 + 照片卡片 + 上滑提示 / 接收按钮），**发送页与接收页都照旧**。 | 参考视频的 **E2（发送面板入场）与 E3（接收卡入场）不计划任何改动**。用户看过两者差异（视频是"就地白雾显影"、我方是"上浮 + 卡片缩放下移入场"）之后明确选择保留我方实现。E6 的"虚化**起**段"归入场，也保持现状（见 §2.3）。 |
| **D-C** | **页面出现之后的动效全部对齐** —— 即本文的 **E4 / E5 / E6 / E7 / E8**。 | 本文的主体。 |

> **⚠️ 必须写进验收口径的三条刻意偏离**（用户已逐条拍板，见 §5.1）：
> ① **E1 光晕不做**；
> ② **"面板浮现"保留我方现状** —— 视频的 **E3 是"就地白雾显影 + blur-up"**（卡片位置自始至终不动，`ANIM_INVENTORY_VIDEO.md` T2 更正 1：`T2c_*.jpg` n=2→n=10 卡片坐标不变），**我方是"卡片缩小 0.88→1 + 下移 22px + 淡入"的入场动画**（`OverlayView.drawCard`，`ui/OverlayView.kt:2242-2248`）；视频的 E2 是"照片收成圆角卡 + 无位移交叉淡入"，我方是"水波推开 + 白线描边 + 面板上浮 8px"（`drawTransferPanel`，`ui/OverlayView.kt:2131-2217`）。用户看过两者差异后明确"**就用现在的**"——**不是漏做，是刻意保留**，后续 reviewer 不要"顺手修成"就地显影、也不要把 `slide`（`:2135`）当缺陷删掉；
> ③ **按压反馈保留** —— 视频的按钮没有任何按下反馈（`T3_tile_1.jpg` n=5），我方保留自己的按下态，用户明确要求（"按压反馈保留吧，这个体验好"）。

### 1.2 本次交付的五项

| 项 | 一句话 | 在谁身上 | 视频依据 |
|---|---|---|---|
| **E4** | 「接收」→「取消」的接收中态 | 接收方 | `ANIM_INVENTORY_VIDEO.md` E4 / T3 更正 2；`_Temp/anim/btn_cmp.jpg`（t=9.45 vs 9.85） |
| **E5** | 整卡消散（crossfade out，≈0.15s）后摘窗 | 接收方（发送方同理，见 §2.2） | E5；`T3_tile_13.jpg` n=15/n=16、`T3c_tile_1.jpg` n=2 |
| **E6** | 背景虚化的**回落**（起 0.15s / 落 0.2–0.3s） | 两方 | E6；`T3c_tile_1.jpg` n=2→n=4 |
| **E7** | 发送触发：照片白化模糊 + 白色粒子迸发 | 发送方 | E7（**两轮都有**）；`_Temp/anim/T2e_007.jpg`（n=6，第一轮，发送方 B） |
| **E8** | 失败横幅（顶部 · blur-up 淡入 · 独立小窗） | 发送方 | E8；`T6c_tile_1.jpg` n=1/n=2/n=3 |

### 1.3 与 `ALIGN_PLAN_FINAL.md` 的两处口径更新（以本文为准）

1. **`ALIGN_PLAN_FINAL.md` §1.3「不做视频 f_0049 的粒子迸发」作废** —— 粒子在本次范围里（E7），实现走"恢复 `ParticleView.java`"而不是新写粒子系统。
2. **`ALIGN_PLAN_FINAL.md` §1.4「复用 vs 新绘制总账」的第 5/6 项行数仍有效，但要按本文 §4 的总账执行** —— 那里没有计入 E5/E6/E7 的三项。另：§5.3 C-4-2 说"接收中只算主按钮那一枚矩形"，与视频证据（取消胶囊落在**「拒绝」那一行**）不符，本文按视频写（见 §2.1）。

---

## 2. 逐项规格

> 每项格式：**目标行为（照视频）→ 现状（file:line + 为什么现在做不到）→ 改法（方法名 + 挂载点）→ 复用 vs 新增 → 规模 → 风险**。

### 2.1 E4 —— 接收中态：两枚胶囊 → 一枚「取消」

#### 目标行为（照视频）

- 接收方点「接收」后，在 **0.20–0.25s** 内：上枚**白色不透明胶囊「接收 N 张照片」的白底先淡成透明、文字随之淡出**；下枚深灰半透明胶囊「拒绝」**原地**变成半透明灰胶囊、文字换成白色「**取消**」。
- **位置与宽度都不变**：取消胶囊仍在**原「拒绝」那一行**，横向占满卡片内容宽（`btn_cmp.jpg` 上下两块可逐像素核对：t=9.45 白胶囊在上、灰胶囊在下；t=9.85 只剩一枚「取消」，落在**下**那枚的位置）。
- 该状态**驻留 ≈0.45s**（视频里因为传输很快），其间标题与照片全程保留，然后整卡消散（E5）。

#### 现状（为什么现在做不到）

- 接收态只有两枚固定胶囊：`OverlayView.layoutReceiveActions()`（`ui/OverlayView.kt:2391-2406`）每帧算出 `receivePrimaryRect` / `receiveSecondaryRect`，`drawReceiveActions()`（`:2422-2451`）画底 + 居中文字。
- **点「接收」之后按钮原地不动**：`drawReceiveActions` 完全不看用户是否已接受；`onReceiveTouch` 的 `ACTION_UP`（`:2547-2559`）只会回调 `onReceiveAccepted` / `onReceiveRejected` 两件事。
- `mAccepted` 只活在 NFC 层（`nfc/TapService.java:39`、`:116`），从不回流动效层 —— 所以**动效层根本不知道"用户已经点了接收"**。这就是这个状态缺失的全部原因。
- 视频这一段的另一个前提（`ANIM_INVENTORY_VIDEO.md` E0 反向清单）：**参考产品的按钮没有任何按压反馈**；我方有，且**用户明确要求保留**（见 §5.1-3）—— 所以 E4 的「取消」胶囊也要有自己的按下态，不能顺手做掉。

#### 改法（具体到方法名和挂载点）

**A. `OverlayView`（唯一的新绘制处）**

1. 新增状态位与开关：
   ```kotlin
   private var acceptInProgress = false
   private var cancelMix = 0f                      // 0 = 还是两枚胶囊，1 = 已是「取消」
   private var cancelAnimator: ValueAnimator? = null
   fun setAcceptInProgress(v: Boolean)             // 置位 + pressedButton = BUTTON_NONE + 起 CANCELLED_SWAP_MS 的 animator + invalidate
   var onReceiveCancel: (() -> Unit)? = null       // 新增回调（与 onReceiveAccepted/onReceiveRejected 并列，:731-735 那三行的位置）
   ```
2. 常量（加在 `RECEIVE_*` 那组旁边，`ui/OverlayView.kt:685-703`）：
   ```kotlin
   const val CANCEL_SWAP_MS = 220f     // 视频 0.20–0.25s
   const val RECEIVE_CANCEL_H_PX = 40f // = RECEIVE_SECONDARY_H_PX，取消胶囊沿用「拒绝」的高度
   ```
3. `layoutReceiveActions()`（`:2391-2406`）**不动**：两枚矩形照算（过渡期间还要靠它们把旧胶囊画出去）。命中判定在 `onReceiveTouch` 里按状态切换（见下）。
4. `drawReceiveActions(c, appear)`（`:2422-2451`）加一个分支：
   - `!acceptInProgress`：现状原样（一个字不改）。
   - `acceptInProgress`：
     - 旧两枚按 `(1 - cancelMix)` 画 —— 主按钮的**底与字都乘这个系数**（"白底先淡成透明、文字随之淡出"），次按钮同理；
     - 取消胶囊画在 **`receiveSecondaryRect`**（= 原「拒绝」那一行，横向已占满卡片内容宽）：底 `withAlpha(0x33FFFFFF, appear * cancelMix)`、文字 `withAlpha(0xFFFFFFFF, appear * cancelMix)`、字号沿用 `15f * k`、文字用现成的 `drawCenteredLabel(c, r, text)`（`:2454-2459`）、文案 `receiveCancelText`。
     - 底色口径：视频里它比主按钮"淡"、与「拒绝」同调；`0x33FFFFFF` 是"在 `0x14FFFFFF`（拒绝底，`:2437`）基础上实一档"，**不要新造颜色体系**，真机按 §6 微调。
5. `onReceiveTouch()`（`:2528-2570`）只改命中分支，**不动其它**：
   - `ACTION_DOWN`：`acceptInProgress` 时只认 `receiveSecondaryRect`（命中 → `pressedButton = BUTTON_SECONDARY`，否则 `BUTTON_NONE`）；非该状态时走现状三分支。
   - `ACTION_UP`：`pressed == BUTTON_SECONDARY` 时按状态分流 —— `acceptInProgress` → `onReceiveCancel?.invoke()`；否则 → `onReceiveRejected?.invoke()`（现状）。
   - **几何仍然"绘制 = 命中同一份"**：取消胶囊画在 `receiveSecondaryRect`、命中也判 `receiveSecondaryRect` —— 与 `:2380-2390` 注释立的规矩一致。
   - **过渡期间的命中**：`acceptInProgress` 一旦置位就立即生效（不等 220ms 动画），否则那 220ms 里按钮"看着还在、点着没反应"。
6. `resetSendState()`（`:1103-1114`）里补 `acceptInProgress = false; cancelMix = 0f`；`cancelAnimator` 注册进 `cancelAnimators()`（`:2682-2685`）。

**B. `OverlayService`（接线，2 行）**

```kotlin
// buildOverlay() 里，:520-523 那三行的旁边
overlay.onReceiveAccepted = { overlay.setAcceptInProgress(true); notifyNfc(ACTION_UI_RECEIVE_ACCEPT) }
overlay.onReceiveCancel   = { notifyNfc(ACTION_UI_RECEIVE_CANCEL) }
```
- 新增 `const val ACTION_UI_RECEIVE_CANCEL = "…action.UI_RECEIVE_CANCEL"`（与 `:886-888` 三条并列）。
- 是否需要新 action 进 `OverlayService`？**不需要**（与 `ALIGN_PLAN_FINAL.md` §4.3 末条一致）：进"接收中"是"用户点了接收"同一个事件的本地副作用，在回调里就地做即可，不新增 service 指令。
- 冷路径复位：`wipeVisuals()` → `resetSendState()` 已经覆盖；**就地换身份**那条分支（`ALIGN_PLAN_FINAL.md` §5.3 C-2，`startReceiveFlow` 里）要显式 `overlay.setAcceptInProgress(false)`。

**C. `TapService` / `Xfer`（回执链路，按 `ALIGN_PLAN_FINAL.md` §4.2/§5.1）**

- 进入接收中态**不需要**新的 Xfer 回调 —— 它由 `ACTION_UI_RECEIVE_ACCEPT` 这一个动作触发（本地进 UI 态 + 回执 `TapService.confirmReceived()` 写 `TYPE_ACCEPT`，`ALIGN_PLAN_FINAL.md` §5.1-5/§5.1-12）。
- 点「取消」：`TapService` 新增 `ACTION_UI_RECEIVE_CANCEL` 分支 —— `sXfer.rejectTransfer()`（写 `TYPE_REJECT`）+ `hideIntent(this, DISSOLVE_MS)`（走 E5 的淡出）+ 清本轮接收状态（照抄现有 REJECT 分支 `TapService.java:127-141` 的清理清单：`mAccepted/mReceiveShown/mPreviewCount/mFullCount/mTotalCount/mTransferDone/mBmps/mLastBmp`）。
- 「取消」的**语义是推算的**：视频只拍到"有一枚取消胶囊"、**没拍到点了会怎样**（`ANIM_INVENTORY_VIDEO.md` 无法确定项里没有，但 E4 只描述形态）。默认取 `ALIGN_PLAN_FINAL.md` §2.2 已定的基线：**取消 = 放弃本轮接收**（本地停读、丢弃本轮、关连接、卡片收起），发送方在"已收到 ACCEPT 之后的写异常"上映射为横幅「发送失败 / **对方拒收**」（沿用现有文案，零新增分支）。**这条仍需用户拍板**（§6-4）。

**D. 计数文案怎么处理**

- 视频里「取消」胶囊**没有张数**，所以我方按钮文案在这一态换成裸文案「取消」（新 string `receive_cancel`）。
- `peerCount` **一点不用改**：张数继续由两处承载 —— 卡片副标题 `card_meta_count`（`refreshTexts()`，`ui/OverlayView.kt:998-1000`）与底部状态行（`TapService.onImageReceived` 推的「已收到 k/N 张」，`TapService.java:275-276`）。
- 不要为"已接收 k 张"新增按钮态文案（参考产品没有进度指示，`ANIM_INVENTORY_VIDEO.md` E0）。

#### 复用 vs 新增

| | 内容 |
|---|---|
| **复用** | `layoutReceiveActions()` 的几何（含 `RECEIVE_*` 五个常量）、`cardPaint` 圆角矩形、`textPaint` + `drawCenteredLabel()`、`appear` 淡入（与面板/卡片同一条时间轴）、`pressedButton` 机制、`ValueAnimator` 范式（`returnCard()` `:2633-2645`） |
| **新增** | 1 个状态位 + 1 个进度 + 1 个 animator + 1 个回调 + **1 个绘制分支（约 10–15 行）**；0 个新 Paint / Shader；1 条 string |

**规模**：`OverlayView` 约 25 行，`OverlayService` 3 行，`TapService` 约 12 行（分支 + 清理），`Xfer` 按 `ALIGN_PLAN_FINAL.md` §5.1-5 另计。合计 UI 侧 ≈ 40 行。

#### 风险

1. 视频分辨率下**定不准**取消胶囊的底色/字色与精确高度（E4 只确证"位置与宽度不变、文字换白"）→ 真机比对项，§6-1。
2. 若 `TapService` 收到 `ACTION_UI_RECEIVE_ACCEPT` 时**本轮已经收完**（`mTransferDone && mFullCount > 0`，`TapService.java:117-121`），它会立刻摘窗开查看器 → 用户会看到「取消」胶囊闪一下。可接受（同一帧内完成），但要知会。
3. `setAcceptInProgress(true)` 之后如果对端超时 → `onOfferExpired`（计划中）会摘窗；此时"取消"这个出口其实已经没用了。属可接受，不额外处理。
4. 视频的 0.45s 驻留**不适用**于我方的两阶段协议：我方驻留 = 整轮传输耗时（可能数秒），`onTransferDone` 才收摊。这是协议差异的必然结果，写进验收口径。

---

### 2.2 E5 —— 整卡消散（crossfade out）→ 再摘窗

#### 目标行为（照视频）

- 传输结束时，卡片 + 照片 + 「取消」胶囊**整体同时淡出**（同一 alpha），露出底下**仍然虚化的**背景；**无位移、无缩放、无飞走**。
- 时长 **≈0.15s**（`T3_tile_13.jpg` n=15=t10.15 半淡 / n=16=t10.23 近无 / `T3c_tile_1.jpg` n=2=t10.25 已无）。
- 紧接着（不是同时结束）背景虚化**逐级减弱**（E6，0.2–0.3s）。

#### 现状（为什么现在做不到）

- `OverlayService.hideIntent` → `ACTION_HIDE` → `hideDemo()`（`service/OverlayService.kt:474-477`）→ `detachOverlay()`（`:551-563`）→ `windowManager.removeView(content)`：**瞬时摘窗**，没有任何过渡。
- 全工程**唯一的离场动效**是发送方的卡片飞走（`startFlyAway()` `ui/OverlayView.kt:2648-2669`：上抛 720·k + 缩到 0.72 + 淡出），接收方连这个都没有。
- 时间轴 `Timeline` **永不自动停**（`anim/Timeline.kt:131-149`），它只会在 `detachOverlay()`（`:560`）里被 `stop()` —— 也就是说，摘下窗口那一刻画面是"硬切"。

#### 改法（具体到方法名和挂载点）

**核心：一次"淡出 → 再摘窗"的收尾路径，面板淡出 150ms、模糊回落 250ms、摘窗等两者的较长者。**

1. **`OverlayView` 新增淡出驱动**（0 行新绘制）：

   ```kotlin
   private var fadeOutAnimator: ValueAnimator? = null   // 注册进 cancelAnimators()（:2682-2685）
   fun fadeOutPanel(ms: Long, done: (() -> Unit)? = null)
   // 做两件事：① 起 ValueAnimator(0→1, ms, LinearInterpolator)，每帧 this.alpha = 1f - t；
   //            ② 结束时回调 done()
   ```
   - **为什么用 `View.alpha` 而不是"把系数乘进 `drawTransferPanel`"**：前者是 1 行、0 新绘制、硬件加速下只是 RenderNode 的一个属性（无离屏缓冲），符合"最少新绘制"的优先级；后者要改 scrim / 卡片 / 文字 / 按钮 / 状态行五处 alpha。
   - **代价（必须写进验收）**：`View.alpha` 会把 **`OverlayView` 画的全部东西一起淡掉** —— 包含四边白线、边缘辉光、顶部光斑。视频里没有这些元素，所以参考产品无从对照；我方读起来是"整层动效消散"，语义自洽。若验收要求"只淡面板、保留白线"，改法是把同一个系数乘进 `drawTransferPanel()`（含 `drawCard` 内的 `alpha`、`drawReceiveActions` 的 `appear`、`drawStatusLine` 的 `textAlpha`），约 6 处 + 1 个字段 —— **列为备选，默认不做**。
   - **GL 层不跟着淡**（见下"已知残留"）。

2. **`OverlayService` 给 `ACTION_HIDE` 加可选时长**（不动现有语义）：

   ```kotlin
   const val EXTRA_FADE_MS = "extra_fade_ms"
   private const val DISSOLVE_MS = 260L      // = max(面板淡出 150, 模糊回落 250) + 余量
   private var dissolveRunnable: Runnable? = null
   ```
   - `hideDemo()` 改成读 `EXTRA_FADE_MS`：`0`（默认，通知里「关闭悬浮窗」`buildNotification():827`）→ 现状瞬时摘；`> 0` → `timeline.releaseBlur()`（E6）+ `overlay.fadeOutPanel(PANEL_DISSOLVE_MS) { }` + `mainHandler.postDelayed({ detachOverlay() }, fadeMs)`。
   - **时间轴在淡出期间必须继续跑**（模糊回落要靠它）——`detachOverlay()` 里本来就有 `timeline.stop()`（`:560`），顺序天然正确。
   - **防重入**：`dissolveRunnable` 只允许一个；`detachOverlay()` 里连同 `startRunnable`（`:553-554` 的写法）一起清掉。
   - **调用点**：`TapService` 里凡是"本轮收摊"的路径改成带时长 —— `onTransferDone` 两处（`TapService.java:290`、`:295`，E5 的主场景）、`onImageSent`（`:310`，发送方也做整卡消散，与视频"面板静默清除"一致）、拒绝分支（`:131`）、计划中的 `onOfferExpired`。**通知里的「关闭悬浮窗」保持 0**（它与动效无关，是用户要"立刻收掉"）。

3. **`wipeVisuals()` 复用行为不受影响的三个保证**（这是本项最容易踩的坑）：

   | 保证 | 做法 |
   |---|---|
   | 下一轮窗口不能是"透明"的 | `resetSendState()`（`:1103-1114`）里补 `alpha = 1f` + 取消 `fadeOutAnimator`。它是 `wipeVisuals()`（`:387`）、`replayDemo()`（`:434`）、`scheduleReplay()`（`:454`）、`teardown()`（`:685`）四条路径的公共复位点。 |
   | 上一轮排的"延时摘窗"不能摘掉下一轮的窗口 | `startSendFlow()`（`:186`）/ `startReceiveFlow()`（`:232`）开头 `dissolveRunnable?.let { mainHandler.removeCallbacks(it) }`；`attachOverlay()` 里再清一次。 |
   | 模糊回落状态不能跨轮 | 见 §2.3 的 `Timeline.play()/seek()` 清零点。 |

#### 复用 vs 新增

| | 内容 |
|---|---|
| **复用** | `hideDemo()` / `detachOverlay()` / `ACTION_HIDE` 通道、`Timeline` 作唯一时钟、`ValueAnimator` 范式、`resetSendState()` 这个既有复位点 |
| **新增** | 1 个 animator + 1 个 `fadeOutPanel()`（约 8 行）、`ACTION_HIDE` 的一个 extra、1 个 `dissolveRunnable`、3 处 TapService 调用点带上时长 |

**规模**：`OverlayView` ≈ 10 行，`OverlayService` ≈ 20 行，`TapService` ≈ 4 行（改调用点）。**0 新绘制。**

#### 风险 / 已知残留（必须知会）

1. **"消散回桌面"只能做到七成 —— 这是架构事实，不是实现偷懒。**
   我方屏幕下**没有真实的桌面**：`RippleSurfaceView` 的 GL 层在 `u_progress = 1` 时把背景图**固定压暗 45%**（`gfx/RippleRenderer.kt:475-479`，`darken = reached * smoothstep(0.17,0.92,u_progress) * 0.45`，**没有任何 uniform 能控制它**），而且那张"背景图"是解码出来的位图（接收侧是对方那张照片、没有照片时是 4×4 纯深色 `darkBase`，`service/OverlayService.kt:373-374`）。
   所以：面板淡出后露出来的是 GL 层那张**压暗的模糊底**；**真实桌面永远是在 `detachOverlay()` 那一刻硬切回来的**。
   想要把最后这一步也抹平，必须给片元着色器加一个 `u_fade` uniform（整层 alpha 乘子）并在淡出期间把它推到 0 —— **改 GLSL，且着色器编译失败会让整层静默变空**（`RippleRenderer.kt:118-121` 的 `runCatching`）。**默认不做，列为可选增强**（§6-2）。
2. 淡出期间窗口仍在吃触摸（约 260ms）。可接受，但要确认这不影响"用户已经看到传输完成、想立刻操作"的手感。
3. `View.alpha` 在**整屏 + 硬件加速 + 透明格式**的悬浮窗上的实际表现需真机确认（§6-2）。
4. 若 `onImageSent`（发送方）与 `onTransferDone`（接收方）在同一台机上被连续触发（脏数据），会各排一次淡出 —— 由"防重入"覆盖。

---

### 2.3 E6 —— 背景虚化的「落」

#### 目标行为（照视频）

- **起**：卡片出现时虚化在 ≈0.15s 内升到满（`T2c_tile_1.jpg` pos1，t≈6.62）。
- **落**：卡片离开时虚化在 **0.2–0.3s** 内**逐级减弱**（`T3c_tile_1.jpg` n=2→n=4，t=10.25→10.45），**不是瞬回**。
- 全程只改虚化半径，**不做位移**。

#### 现状（为什么现在做不到）

- `OverlayService.kt:509-516` 的唯一定阅点里：`rippleView.setBlur(tl.photoBlurAt(elapsed))`（`:514`）。
- `Timeline.photoBlurAt(elapsed)`（`anim/Timeline.kt:172-173`）：
  ```kotlin
  ((elapsed - PANEL_MS).toFloat() / PHOTO_BLUR_MS).coerceIn(0f, 1f)   // 1500 → 2000ms 单调升到 1
  ```
  **只有升、没有降** —— 它是个纯函数，没有任何"回落"的入口，`clamp` 到 1 之后永远是 1。
- 因此 `rippleView.setBlur(0f)` 只在 `wipeVisuals()`（`:393`）里被调一次，那是"新一轮擦干净"，与"本轮收尾的回落"无关。
- 起段的时长（500ms，`Timeline.PHOTO_BLUR_MS = 500f`，`Timeline.kt:95`）与视频的 0.15s 不同 —— **归 D-B 的入场范围，保持现状**（见 §1.1）。

#### 改法（具体到方法名和挂载点）

**只动 `Timeline`（约 12 行），`OverlayService` 一行不用改。**

```kotlin
// anim/Timeline.kt
const val BLUR_RELEASE_MS = 250f        // 加在 companion（:95 PHOTO_BLUR_MS 旁边，注释写明"视频 0.2–0.3s"）

private var blurReleaseAt = -1L         // -1 = 未触发
private var blurReleaseFrom = 1f
private var blurReleaseMs = BLUR_RELEASE_MS

/** 本轮收尾：背景虚化从这里开始回落。可重复调用（幂等，后一次覆盖前一次起点）。 */
fun releaseBlur(ms: Float = BLUR_RELEASE_MS) {
    blurReleaseAt = elapsedMs
    blurReleaseFrom = photoBlurAt(elapsedMs)   // 万一提前触发，从"当前值"回落，不跳变
    blurReleaseMs = ms
}

fun photoBlurAt(elapsed: Long): Float {
    val at = blurReleaseAt
    if (at >= 0L) {
        return (blurReleaseFrom * (1f - (elapsed - at) / blurReleaseMs)).coerceIn(0f, 1f)
    }
    return ((elapsed - PANEL_MS).toFloat() / PHOTO_BLUR_MS).coerceIn(0f, 1f)
}
```
- **清零点（必做，否则下一轮整轮都不虚化）**：`play()`（`Timeline.kt:176-185`）与 `seek()`（`:197-201`）里 `blurReleaseAt = -1L`。`reset()`（`:204`）走 `seek(0)`，自动覆盖；`wipeVisuals()` 另外会显式 `setBlur(0f)`（`:393`），双保险。
  - 若不清：`reset()` 后 `elapsed` 回到 0，而 `blurReleaseAt` 还是上一轮的大数 → `elapsed - at` 为负 → `1 - 负数` > 1 → clamp 到 1 → 下一轮**整个入场段都是满模糊**。这是本项最大的坑。
- **触发点**：与 E5 同一条收尾路径 —— `OverlayService.hideDemo()` 读到 `EXTRA_FADE_MS > 0` 时先 `timeline.releaseBlur()`，再起面板淡出、再延时摘窗。
- **GL 层不用改**：`RippleSurfaceView.setBlur()`（`gfx/RippleSurfaceView.kt:59-64`）本来就接受 0..1 并跳过重复值，回落过程每帧都是新值 → 正常重绘；回到 0 后 `setter` 不再重复触发，GL 不再白刷（`RippleRenderer.kt:465-467` 的 `u_blur > 0.001` 分支）。
- **不需要新刻度挂在 `onFrame` 上**：`photoBlurAt(elapsed)` 仍然每帧被调（`:514`），只是它现在会走回落分支 —— 单订阅点保持不变。

#### 复用 vs 新增

| | 内容 |
|---|---|
| **复用** | 整条链路：`Timeline.onFrame` 单订阅点 → `photoBlurAt` → `RippleSurfaceView.setBlur` → `u_blur` 混 `u_blur_tex`（`RippleRenderer.kt:465-467`）；`BackdropBlur` 生成的模糊纹理；`wipeVisuals()` 的既有清零 |
| **新增** | `Timeline` 里 3 个字段 + 1 个方法 + `photoBlurAt` 的一个分支（约 12 行） |

**规模**：≈12 行，全在 `Timeline.kt`。**0 新绘制、0 GL 改动。**

#### 风险

1. **回落与摘窗的时序**：回落 250ms > 面板淡出 150ms，若摘窗等 150ms，用户只能看到 60% 的回落。已按 `DISSOLVE_MS = 260L` 处理（§2.2-2），两条曲线都能走完。
2. 视频的"逐级"可能不是线性（`ANIM_INVENTORY_VIDEO.md` 无法确定项 #8：分几级、每级多少毫秒无法从像素反推）。默认线性；真机观感若"结束得太硬"，给它一个 `smoothStep` 即可（`OverlayView.smoothStep` 在 Canvas 层，`Timeline` 侧自己写 3t²−2t³）。
3. `photoBlurAt` 从纯函数变成**有状态**的派生量 —— 必须保证"所有复位路径都清"（上文的 `play/seek`）。已列入 §6 真机项（跑一遍"完整一轮 → 再碰一次"，确认第二轮背景在 2.0s 时确实变糊）。

---

### 2.4 E7 —— 发送触发：照片白化模糊 + 白色粒子迸发

#### 目标行为（照视频，**两轮都出现**，`ANIM_INVENTORY_VIDEO.md` §C 更正）

- 上滑发送那一刻（第一轮 t≈6.27，第二轮 t≈23.90）：
  1. 照片在 ≈**0.1s** 内先轻微模糊、再**过曝白化成一团亮白头**；卡片亮边框**同时消失**；
  2. 同时出现**白色细小光点**：**30–60 颗**、直径 ≈**屏宽的 0.5–1%**、寿命 ≈**0.4–0.5s**，从照片区向外/向下呈扇形飞散，密度先密后疏、亮度渐暗，**粒子会越过卡片边界飞到卡外与提示行上**；
  3. 白化 + 模糊态**保持 0.7–0.9s**（= 视频里"传输中"的唯一反馈，全片没有任何进度条）；
  4. 传输结束后再恢复清晰（t≈24.73）。
- 证据帧：`_Temp/anim/T2e_007.jpg`（n=6，t≈6.40，第一轮发送方 B）—— 已复核：照片是一团白糊、白色光点密布并向下扇形散开；`T2e_tile_1.jpg` n=2→n=7 可看到"轻微模糊 → 重度模糊 → 过曝白化 + 粒子出现 → 粒子散开"的完整过程。

#### 现状（为什么现在做不到）

- **粒子：全工程没有粒子系统**（`ANIM_INVENTORY_EXISTING.md` §5 第 1 条）。唯一实现是已删除的 `src/com/pri/nfcdemo/ParticleView.java`（74 行，`3df3255 清除代码` 删的，`git show 3df3255^:src/com/pri/nfcdemo/ParticleView.java` 可取）。合并时 `README.md:73` / `MERGE_PLAN.md:60,151` 写着"确认页与粒子改由动效层负责"，但**"粒子"这半句从未兑现**。
- **白化：没有**。发送路径现在是"卡片飞走"：越阈值 → `startFlyAway()`（`ui/OverlayView.kt:2648-2669`）→ 上抛 `-720f*k`、缩到 0.72、`FLY_MS = 460L`（`:554`）内淡出 → `finishSend()`（`:2671-2680`）→ `onSendFinished` → `OverlayService.kt:518` `timeline.reset()`。**卡片是"飞出去"，不是"原地白化"**。
- **保持段：没有**。`RESET_DELAY_MS = 1750L`（`:564`）只是"飞走播完 + 空一会儿"，与"传输中"无关。

#### 改法（具体到方法名和挂载点）

##### A. 粒子层：恢复 `ParticleView`，**作为 `overlay_root.xml` 的第三层**（不另开窗口）

- **取舍（两个方案都验证过）**：

  | 方案 | 结论 |
  |---|---|
  | (i) 沿用旧调用点的 `Overlay.showPassThrough(pv)`（`nfc/Overlay.java:55`，API 仍在） | **不取**。理由三条：① 这会是**第三套悬浮窗**（另两套：`OverlayService` 的整屏窗、`nfc/Overlay.java` 的查看器窗），生命周期要自己管（旧代码 `postDelayed(2600)` 摘）；② 它的窗口参数**不带 `FLAG_LAYOUT_NO_LIMITS`**（`nfc/Overlay.java:57-70`），尺寸是 `MATCH_PARENT`，坐标系受系统栏 inset 影响 → 粒子会与卡片**错位**；③ 摘窗时机与 E5 的淡出、E8 的横幅窗互相干扰。 |
  | (ii) 加进 `res/layout/overlay_root.xml`，作为 `OverlayView` **之后**的第三个 child | **取**。同一个窗口、同一套绝对像素坐标（整屏窗是 `FLAG_LAYOUT_NO_LIMITS` + `fitInsetsTypes=0`，`OverlayService.kt:603-641`）、生命周期跟着窗口走、`detachOverlay()` 一句话就收干净。 |

- 落地：
  1. `git show 3df3255^:src/com/pri/nfcdemo/ParticleView.java` 取出 74 行，**只改包名** `com.pri.nfcdemo` → `com.pri.nfctouchdemo.ui`，落到 `app/src/main/java/com/pri/nfctouchdemo/ui/ParticleView.java`（与 `OverlayView.kt` 同包）。
  2. `overlay_root.xml` 加一层（`app/src/main/res/layout/overlay_root.xml:20-23` 之后）：
     ```xml
     <com.pri.nfctouchdemo.ui.ParticleView
         android:id="@+id/particles"
         android:layout_width="match_parent"
         android:layout_height="match_parent"
         android:clickable="false" />
     ```
     **`clickable="false"` 是必须的**：`ParticleView` 不 override `onTouchEvent`，默认 `View.onTouchEvent` 在不可点击时返回 false → `FrameLayout` 逆序派发会继续把事件交给下面的 `OverlayView`，上滑手势与按钮不受影响。若有人给它加 `clickable` 或 `OnClickListener`，**整页立刻点不动**（这是本项最容易踩的一个坑）。
  3. `ParticleView` 内加一个 3 行的复位方法（`wipeVisuals()` 里调，避免上一轮没散尽的粒子在新一轮窗口上继续画）：
     ```java
     /** 清空全部粒子(换轮次时用)。 */
     public void clear() { mPs.clear(); mRunning = false; invalidate(); }
     ```
  4. **参数按屏宽重算**（把硬编码 px 换成相对量，对齐视频的"直径 ≈ 屏宽的 0.5–1%"）：

     | 参数 | 现值（旧文件行号） | 目标值 | 依据 |
     |---|---|---|---|
     | 数量 | 调用方给（旧调用点 90） | `SEND_PARTICLE_N = 48` | 视频 30–60 |
     | 半径 | `2 + random*4.5` px（`:39`） | `w * (0.0025f + random * 0.0025f)`（w = `getWidth()`） | 视频直径 0.5–1% 屏宽 |
     | 寿命 | `0.6 + random*0.9`（`:40`） | `0.40f + random * 0.15f` | 视频 0.4–0.5s |
     | 初速 / 上抛 / 重力 / dt 上限 / 颜色 | `120+480` / `-140` / `280` / `0.05` / `WHITE`（`:33,38,63,54,64`） | **保留** | 视频分辨不出轨迹（`ANIM_INVENTORY_VIDEO.md` 无法确定项 #4）；真机再调 |
  5. 触发与几何：新增 `OverlayView.onSendBurst: ((Float, Float) -> Unit)?`，在 `startFlyAway()`（`:2648-2669`）**开头**（回报 `onSendConfirmed` 之后、任何动画启动之前）调用：
     ```kotlin
     onSendBurst?.invoke(px, py)     // px/py = 卡片照片区中心（见下）
     ```
     `OverlayService.buildOverlay()`（`:509-524` 那一块）接线：
     ```kotlin
     overlay.onSendBurst = { cx, cy -> particleView.burst(cx, cy, SEND_PARTICLE_N) }
     // particleView = content.findViewById(R.id.particles)
     ```
  6. **照片区矩形要"绘制与发射同源"**：`drawCard` 里那份 `photo` 是局部 `RectF`（`:2295-2300`，`9f*k` 内缩 / 底部留 `53f*k`）。抽一个 `private fun photoArea(out: RectF)`，`drawCard` 与 `startFlyAway()` 两处都调它 —— 否则这组数字一旦在一处改动，粒子就会从错的位置冒出来（这是 `overlay_root` 注释里已经立过的"绘制 = 命中"规矩，`ui/OverlayView.kt:2380-2390`）。
  7. 粒子的**自驱动**（`onDraw` 里 `postInvalidateOnAnimation`，`:68-72`）是"第三套时钟"，但它是一次性短动画（≤0.55s 自动停），与 `returnCard()` / `startFlyAway()` 两个 `ValueAnimator` 同类，**不违反** `Timeline` 的"唯一常驻时钟"格局（`ANIM_INVENTORY_EXISTING.md` §3.1 的第 3 条）。

##### B. 白化：**一层矩形 + 复用现有 Paint**（不新增 Paint）

- `OverlayView` 新增：
  ```kotlin
  private var photoWhite = 0f                                   // 0..1
  private var whiteAnimator: ValueAnimator? = null              // 注册进 cancelAnimators()
  const val SEND_WHITE_IN_MS = 100f                             // 视频 ≈0.1s
  ```
- 在 `startFlyAway()` 里起 `ValueAnimator.ofFloat(0f, 1f)`（`LinearInterpolator`，与 `returnCard()` `:2636-2643` 同款），每帧 `photoWhite = t; invalidate()`。
- **绘制点**：`drawCard()` 的照片绘制之后（`ui/OverlayView.kt:2308` 的 `c.drawBitmap(art, artSrc, photo, photoClip)` 之后、`c.restoreToCount(save2)` 之后都行），加一行：
  ```kotlin
  if (photoWhite > 0.003f) {
      cardPaint.shader = null
      cardPaint.color = withAlpha(0xFFFFFFFF.toInt(), alpha * photoWhite)   // withAlpha :2709
      c.drawRoundRect(photo, pr, pr, cardPaint)                             // 圆角与照片区一致(pr = 15f*k, :2301)
  }
  ```
  - **0 个新 Paint**：`cardPaint`（`:1058`）在既有代码里每次使用前都重设 `color`/`shader`（`:2284`、`:2429`、`:2436`），多一处使用是安全的；不用 `smoothStep()`、不用 `breath()`（这一层要的是线性过曝，不是呼吸）。
  - 白化到 1 时照片区是一块纯白圆角矩形 —— 与视频 n=5 的"一团亮白头"同观感。
- **"重度模糊"怎么办（诚实说明 + 取舍）**：
  - 方案 (a) **只做白化**：过曝到 0.8 以上时照片细节本来就不可辨，视频的"重度模糊"里有一部分就是"过曝"本身。**0 成本，默认取它。**
  - 方案 (b) 顺带做真模糊：`BackdropBlur.of(cardPhoto)`（`gfx/BackdropBlur.kt:43`，纯 CPU，几毫秒）在 `setCardPhoto()` 时后台算一份，`drawCard` 里按 `photoWhite` 交叉淡入。**列为可选**，理由是它的参数（`SCALE_DIV=4 / BOX_RADIUS=6 / PASSES=2`，等效半径约 24px @原图）是给整屏背景调的，用在 292×360 设计 px 的卡片照片上观感未验证。
- `resetSendState()` 里 `photoWhite = 0f`（它已经清了 `flying/flyT/dragY`，`:1103-1114`）。

##### C. 保持段：**给 `startFlyAway()` 加一段"停在原地"的 hold**

- 视频是"照片原地白化模糊 → 保持 0.7–0.9s → 传输完恢复清晰"。我方若照现状"越阈值就飞走"，白化与粒子根本来不及被看见（`FLY_MS = 460L` + `easeOutCubic`，前 100ms 卡片已经走了一半行程）。
- 改法（**最小侵入**）：新增 `const val SEND_HOLD_MS = 700L`；把 `startFlyAway()`（`:2648-2669`）里 `flyAnimator` 的 `.start()` 挪进一个 `postDelayed(SEND_HOLD_MS)`：
  ```
  越阈值 → 回报 onSendConfirmed → 起白化(100ms) + 起粒子 → 卡片停在当前 dragY → 700ms 后启动现有 flyAnimator(460ms) → RESET_DELAY_MS 后 finishSend()
  ```
  - 该延迟 runnable 必须能被取消：`resetSendState()` 现在只清 `resetRunnable`（`:1111-1112`），把 hold 也用同一个字段式的写法挂上、一起清（或新增一个 `holdRunnable` 字段并在 `resetSendState()`+`cancelAnimators()` 里注销）。
  - **与 `ALIGN_PLAN_FINAL.md` §5.3 C-5 的关系**：C-5 的"上滑后自动摘窗"（`onSendFinished → hideDemo`）**不变**，只是整体往后挪 700ms。窗口总占用从"飞走 460 + `RESET_DELAY_MS` 1750"变成"+700"，对"发送方要等最多 60s 回执"这件事没有影响（摘窗时间点仍在上滑后约 2.9s）。
  - **有意偏离（写进 §5）**：视频的保持段一直持续到传完；我方是**固定 0.7s**（取视频区间的下端）。理由是"传完才收起"要把 `onImageSent` 回灌给动效层、并与 C-5 的摘窗时机打架，收益不抵复杂度。

##### D. 卡片亮边框

- 视频里"卡片亮边框**同时消失**"（`ANIM_INVENTORY_VIDEO.md` E7 ①③）。我方卡片描边是 `edgeStroke`（`ui/OverlayView.kt:2288-2292`，`1f*k` 白描边、alpha `0.66f * alpha`）。
- 改法：把它也乘 `(1 - photoWhite)`（1 行）—— 白化一起、描边一起淡掉，与视频一致。**这是本项唯一"顺带改到既有绘制"的地方**，需在评审时点名。

#### 复用 vs 新增

| | 内容 |
|---|---|
| **复用** | `ParticleView.java`（git 恢复，74 行**逐字**）、`cardPaint` / `edgeStroke`（既有 Paint）、`withAlpha` / `ValueAnimator` 范式、`drawCard` 的现成照片矩形、`OverlayView` 的 callback 接线风格 |
| **新增** | 1 个 XML 层（4 行）、`ParticleView.clear()`（3 行）、半径/寿命两处参数（2 行）、`onSendBurst` 回调 + `photoArea()` 抽取（约 8 行）、`photoWhite` + 1 个 animator + 1 行绘制（约 12 行）、`SEND_HOLD_MS` + 1 个 postDelayed（约 6 行）、描边乘子（1 行） |

**规模**：`OverlayView` ≈ 30 行 + `ParticleView` 恢复 74 行 + XML 4 行 + `OverlayService` 3 行。**"真正新写的绘制代码"只有 1 行 `drawRoundRect`**（其余是恢复的文件与状态驱动）。

#### 风险

1. **`ParticleView` 一旦变成可点击，整页手势失效**（§A-2）。必须在 XML 显式 `clickable="false"` 并在类注释里写一句"不要给它加点击"。
2. **粒子会画在「↑ 上滑发送 1 张照片」提示行之上**（`ParticleView` 是最上层 child），而视频里那行提示在粒子之上（`ANIM_INVENTORY_VIDEO.md` T2 时间线："文字**始终清晰**（绘制在粒子上层）"）。若不接受，唯一的替代是把粒子层放到 `OverlayView` **下面** —— 但那会让粒子被不透明的卡片挡住，与"N 颗粒子在照片上可见"（视频确证）冲突。**默认取"粒子在最上层"**，把这一处差异写进 §5。
3. 白化的观感上限：只做白化不做真模糊（方案 a）在**浅色照片**上可能"白得不明显"（本来就近白）。真机若确认不够，再上方案 (b)（§6-3）。
4. `SEND_HOLD_MS = 700L` 让"上滑 → 窗口消失"从约 2.2s 变成约 2.9s。用户若嫌慢，改常量即可（`FLY_MS`/`RESET_DELAY_MS` 都不动）。
5. `photoWhite` 与 `edgeStroke` 的乘子都在 `drawCard` 内 —— 卡片其它状态（`dragging`、`flying`）下的 alpha 已经由既有代码处理，新增的乘子是**叠乘**，不会覆盖（`alpha * photoWhite`）。

---

### 2.5 E8 —— 失败横幅（顶部 · blur-up 淡入 · 独立小窗）

#### 目标行为（照视频）

- 位置：**发送方屏幕最顶部**（状态栏正下方）、**横向占满、紧贴上边**；入场时把被它覆盖的相册返回箭头「←」淡出（这条是"它盖住了别人"，我方无需处理）。
- 内容：左侧**红色圆圈内白色「!」** + 右侧两行 —— **粗体白字「发送失败」** + 小一号灰字「**对方拒收**」。
- 入场：**带模糊的淡入**（先一坨糊字 → 0.10–0.15s 内对焦成清晰），**无下滑、无从状态栏掉落**；`T6c_tile_1.jpg` n=1（t≈27.98 糊）/ n=2（28.02 对焦中）/ n=3（28.05 清晰）。
- 驻留：视频里驻留到片尾（≥1.9s，无自动消失）。**我方按已定决策 D1 改成 5 秒自动收起**（`ALIGN_PLAN_FINAL.md` §5.4 已记录这是与视频的刻意不同）。

#### 现状（为什么"必须独立小窗"）

- 我方**没有横幅**：唯一的失败反馈是底部一行 11 设计 px 状态行（`OverlayView.drawStatusLine`，`ui/OverlayView.kt:2467-2478`），由 `TapService.onXferFailed(why)`（`nfc/TapService.java:313-319`）推。
- 现有整屏窗（复核过，逐条对上）：
  - 尺寸 = 整块屏幕像素（`service/OverlayService.kt:604` + `displaySize()` `:745-754`）；
  - flags = `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_HARDWARE_ACCELERATED`（`:605-608`）；
  - 「触摸穿透」开关另加 `FLAG_NOT_TOUCHABLE`（`:613`），配套 `setTouchThrough()`（`:649-665`）。
- **为什么 D1 的三条行为做不进这个窗口（逐条验证）**：
  1. `FLAG_NOT_FOCUSABLE` **本身就隐含 `FLAG_NOT_TOUCH_MODAL`** —— 这个 flag 现在就是开着的，但**对整屏窗毫无作用**：`NOT_TOUCH_MODAL` 的语义是"落在**窗口矩形之外**的触摸分给下层"，而窗口铺满整屏，**没有"之外"**。
  2. 在 `onTouchEvent` 里对内容区外 `return false` **不会**把触摸交给下层应用 —— 输入分发在 WindowManager 层按**窗口**命中，视图不消费的事件只会被丢掉。
  3. 所以"点内容区外收起"在整屏窗上**不可能实现**；"滑动收起"能实现但要自己起手势；"5s 自动收起"要自己起 Handler。
- 结论：**`ALIGN_PLAN_FINAL.md` §5.4 的方案 A（独立小窗）成立**，且是唯一能同时满足三条、又完全不碰 `OverlayView` 绘制代码的路线。方案 B（画进整屏窗 + `setTouchableRegion`）需要保证窗口在横幅期间**持续 attached**（而 `onImageSent` 现在会 `hideIntent()`）、触摸区域要在每次 `applyBarInsets`/`onSizeChanged` 后**重新下发**、动画层时间轴会一直跑 —— 不建议。

#### 改法

##### A. 窗口参数（精确）

```kotlin
val lp = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,          // 横向占满
    WindowManager.LayoutParams.WRAP_CONTENT,          // 高度跟着内容
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL       // 窗口矩形之外的触摸照常给下层
        or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH   // 但"通知我一声"→ ACTION_OUTSIDE
        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
    PixelFormat.TRANSLUCENT
)
lp.gravity = Gravity.TOP or Gravity.START
// 与整屏窗同一套"铺满"处理：窗口真的从 y=0 起算，状态栏那一条由内容自己让开
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    lp.fitInsetsTypes = 0; lp.fitInsetsSides = 0; lp.setFitInsetsIgnoringVisibility(true)
}
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { lp.layoutInDisplayCutoutMode = ALWAYS / SHORT_EDGES }
```
- 与整屏窗的差别（3 个 flag）：**多** `FLAG_NOT_TOUCH_MODAL` + `FLAG_WATCH_OUTSIDE_TOUCH`，**少** `FLAG_LAYOUT_NO_LIMITS`（横幅只需从顶部起、不需要铺到屏幕外沿）。
- `MATCH_PARENT` 宽 + `WRAP_CONTENT` 高 ⇒ **"窗口之外" = 横幅下沿以下**，正好就是"点内容区外"。
- 状态栏让位：`fitInsetsTypes = 0` 之后窗口从 y=0 起，内容用**现成的** `topInsetPx()`（`OverlayService.kt:712-724`）做 `root.setPadding(0, systemTop, 0, …)` 让开状态栏 —— 这样"紧贴上边"这条同时成立（背景从 y=0 起）且文字不会被状态栏压住。
- 不复用 `nfc/Overlay.java:31 show()`：那个是 `MATCH_PARENT × MATCH_PARENT` + 无 `WATCH_OUTSIDE_TOUCH`，且它属于"调试页专用"（`ALIGN_PLAN_FINAL.md` §5.5-4）。

##### B. 布局（**零 Canvas 新绘制**）

新增 `app/src/main/res/layout/overlay_fail_banner.xml`（或代码里 `new`，二选一，推荐 XML）：

```
FrameLayout  id=fail_root   width=match_parent  height=wrap_content
             background = 一条覆盖到 y=0 的深色底（见下）
  └ LinearLayout  horizontal  gravity=center_vertical  paddingH=16dp  paddingV=10dp
      ├ TextView  id=fail_icon      文本「!」 bold 14sp 白 0xFFFFFFFF
      │           background = Ui.rounded(ctx, 0xFFE5484D, 12)   ← 红色圆底（nfc/Ui.java:31）
      ├ TextView  id=fail_title     marginStart=10dp  bold 15sp  0xFFFFFFFF
      └ TextView  id=fail_detail    marginStart=6dp / 换行第二行  13sp  0xFFB9C2CC
```
- 两行文本：按视频是**上下两行**（`fail_title` 粗体白 + `fail_detail` 小一号灰），所以 title/detail 竖着放进一个 `LinearLayout vertical`，与红色圆点水平并列。字号 `15sp/13sp` 与"动效层调性"同档（卡片标题 12 设计 px、接收方信息 16 设计 px）。
- 副行颜色 **`0xFFB9C2CC` 直接沿用状态行那支灰**（`ui/OverlayView.kt:2472`）；红 **`0xFFE5484D`**（`ALIGN_PLAN_FINAL.md` §5.4 已定）。
- 底色：视频里横幅是"盖在相册之上"的深色条（细节受分辨率限制无法定准）。取一条**深色半透明底**保证任何应用上都读得清（例 `0xE610151C`，与 scrim 的三色 `0x52070A10/0x5A080C14/0x6D080C14`（`ui/OverlayView.kt:2141-2143`）同色系），**精确值列真机比对**（§6-5）。

##### C. 三条收起路径

| 路径 | 实现 | 备注 |
|---|---|---|
| (a) 滑动收起 | 根布局 `setOnTouchListener`：`ACTION_DOWN` 记 `y`，`ACTION_UP` 时 `abs(dy) > 24dp`（新常量 `FAIL_BANNER_SWIPE_DP = 24f`）→ `hideFailBanner()`；顺手置 `root.isClickable = true` 让触摸被本窗消费（横幅区域内的点击不穿透）。 | 视频没有这条（用户加的 D1） |
| (b) 点内容区外收起 | `ACTION_OUTSIDE` → `hideFailBanner()`（靠 `FLAG_WATCH_OUTSIDE_TOUCH` + `NOT_TOUCH_MODAL`）。**事件仍会照常发给下面的应用**，这与参考产品"横幅挂在相册上方、相册照常可用"一致。 | 少数 ROM 可能不发 `ACTION_OUTSIDE`（`ALIGN_PLAN_FINAL.md` §9.2-4 已列）→ 降级路径是 (a)+(c)，不会卡死 |
| (c) 5 秒自动收起 | `mainHandler.postDelayed(FAIL_BANNER_DISMISS_MS /* = 5000L */, hideRunnable)` | `D1_AUTO_DISMISS_MS = 5000`，与通知「重播」的 `REPLAY_DELAY_MS = 5000L`（`OverlayService.kt:1017`）同一个 Handler 写法 |

##### D. 入场（blur-up 淡入，0.10–0.15s）

- 基础：`root.alpha` 0→1，`FAIL_BANNER_IN_MS = 120f`，用 `ValueAnimator`（与 `ui/OverlayView.kt:2636-2643` 同款）或 `root.animate().alpha(1f).setDuration(120)`。
- blur-up **近似**（可选，默认开）：API 31+ 用 `root.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))`，`r` 从 ≈24f 线性降到 0，与 alpha 同一条 120ms 曲线；用 `runCatching` 包住、失败就只留 alpha 淡入。API < 31 直接跳过。
- **不做**任何位移（视频确证：无下滑、无从状态栏掉落）。

##### E. 两条 detail 文案怎么选

| 触发 | 回调（`ALIGN_PLAN_FINAL.md` §4.1） | 横幅内容 |
|---|---|---|
| 对方点「拒绝」 | `onPeerRejected()` | 「发送失败」+「**对方拒收**」（string `fail_rejected`） |
| 60s 超时 / 对方在读回执前断开（D2） | `onVerdictTimeout()` | 「发送失败」+「**对方未响应**」（string `fail_timeout`） |

- **选择发生在 `TapService`，不在动效层**：两条都由 `TapService` 调 `failBannerIntent(ctx, title, detail)` 时把 detail 作为 extra 传进来（`OverlayService` 不认识业务）。标题统一取 `R.string.fail_title`。
- `onXferFailed(why)`（`TapService.java:313-319`）**保留**现有的底部状态行（长文案「对方尚未连接，请让对方保持在前台再试」放这儿才放得下），并且**只在 `mUserWantsSend` 为真时**追加一条横幅（否则会把"还没上滑就失败"也报成"发送失败"）；detail 截到 ~12 字以内。
- 新 string 三条：`fail_title` = 「发送失败」、`fail_rejected` = 「对方拒收」、`fail_timeout` = 「对方未响应」。
- 「取消」的发送方反馈默认沿用「对方拒收」（§2.1-C，待拍板）。

##### F. 收摊点（少一个就会出现"上一轮的横幅挂在新一轮上"）

| 收摊点 | 谁做 |
|---|---|
| `ACTION_FAIL_BANNER` 重入 | `showFailBanner()` 先 `hideFailBanner()` 再挂（幂等） |
| `ACTION_HIDE`（含 E5 的带时长变体） | `hideDemo()` 里顺手 `hideFailBanner()` |
| `ACTION_START` / `ACTION_RECEIVE`（新一轮） | `startSendFlow()` / `startReceiveFlow()` 开头各一行 |
| **`onPeer`（新一轮碰触）** | 新增 `ACTION_HIDE_FAIL_BANNER`（3 行）+ `TapService.onPeer`（`nfc/TapService.java:170-194`）开头一行 —— 碰触那一刻可能正挂着上一轮的横幅 |
| `ACTION_STOP` / `onDestroy` / `teardown()`（`OverlayService.kt:667-691`） | 各一行 |
| 用户收起（滑动 / 点外）/ 5s 到期 | 同一个 `hideFailBanner()` |

**新增窗口机制点名**：这是本工程的**第三套悬浮窗**（① `OverlayService` 整屏窗 ② `nfc/Overlay.java` 的调试查看器窗 ③ 本横幅窗）。归属：**由 `OverlayService` 持有**（成员 `failView`），随服务生命周期；与「触摸穿透」开关（`ACTION_TOGGLE_TOUCH`，`:649-665`）**互不干扰**（后者只改整屏窗的 flags），好处是横幅**永远点得到**；通知仍保持 3 个 action（`:794-800` 的注释已说明系统只显示三个）。

#### 复用 vs 新增

| | 内容 |
|---|---|
| **复用** | `statusIntent` 的工厂范式（`:970-974`）、`Settings.canDrawOverlays` 兜底（`:203-207/240-244/499`）、`nfc/Ui.rounded()`（`nfc/Ui.java:31`）、`topInsetPx()`/`displaySize()`、`mainHandler`、`Bus.log`/`unlockRole` |
| **新增** | 1 个 action + 1 个工厂 + 1 个 `ACTION_HIDE_FAIL_BANNER`、1 个布局文件、1 个小窗 + 三条收起路径 + 5s 计时、3 条 string、`TapService` 两个回调实现 + 1 处 `onXferFailed` 追加 |

**规模**：`OverlayService` ≈ 90 行（窗口 + 交互 + 收摊），`overlay_fail_banner.xml` ≈ 35 行，`TapService` ≈ 20 行，`strings.xml` 3 行。**0 行 Canvas 新绘制、`OverlayView` 一行不动。**

#### 风险

1. `ACTION_OUTSIDE` 在少数 ROM 上不送达（§C 已给降级）。
2. `RenderEffect` 的 blur 在 `TYPE_APPLICATION_OVERLAY` 窗口上的可用性未验证 → 已 `runCatching` 兜底，不做也不影响主效果（§6-5）。
3. 横幅窗与整屏窗**同时存在**时（比如发送失败后用户又碰一次手机）：`ACTION_HIDE_FAIL_BANNER` 收摊点必须覆盖 `onPeer`，否则旧横幅会叠在新一轮的整屏窗上（与 `wipeVisuals` 同类问题）。
4. 视频是"驻留到片尾"，我方 5s 自动收起是 **D1 的刻意不同**（§5）。

---

## 3. 复用 vs 新增 总账

> 用户的优先级：**最少新绘制**。下表"新绘制代码"一列是**严格意义**上的新手写绘制；"恢复/配置"不算新绘制。

| # | 项 | 复用的既有代码（file:line） | 真正新增的绘制代码 | 其它新增 | 规模 |
|---|---|---|---|---|---|
| **E4** | 接收中「取消」 | `layoutReceiveActions`(`OverlayView.kt:2391-2406`) / `drawReceiveActions`(`:2422-2451`) / `drawCenteredLabel`(`:2454-2459`) / `cardPaint`(`:1058`) / `textPaint`(`:1064`) / `appear` 淡入 / `pressedButton`(`:749`) / `ValueAnimator` 范式(`:2633-2645`) | **1 枚胶囊的分支：约 10–15 行**（1 次 `drawRoundRect` + 1 次 `drawCenteredLabel` + 旧两枚乘 `(1-cancelMix)`） | 1 状态位 + 1 进度 + 1 animator + 1 回调 + 1 string + `TapService` 分支 + `Xfer.rejectTransfer()` | UI ≈ 40 行 |
| **E5** | 整卡消散 | `hideDemo`/`detachOverlay`/`ACTION_HIDE`(`OverlayService.kt:474-477/551-563`) / `resetSendState`(`OverlayView.kt:1103-1114`) / `ValueAnimator` 范式 | **无（0 行）** —— 用 `View.alpha` 走 RenderNode | `fadeOutPanel()` 8 行 + `EXTRA_FADE_MS` + `dissolveRunnable` + 3 处调用点改 | ≈ 30 行 |
| **E6** | 背景虚化回落 | 整条链：`Timeline.onFrame` 单订阅(`OverlayService.kt:509-516`) / `photoBlurAt`(`Timeline.kt:172-173`) / `RippleSurfaceView.setBlur`(`gfx/RippleSurfaceView.kt:59-64`) / `u_blur` 混纹理(`RippleRenderer.kt:465-467`) / `BackdropBlur`(`gfx/BackdropBlur.kt:43`) | **无（0 行）** | `Timeline` 3 字段 + 1 方法 + 1 分支（约 12 行）；`play()/seek()` 清零点 | ≈ 14 行 |
| **E7a** | 粒子迸发 | `ParticleView.java`（git `3df3255^` **逐字恢复**，74 行）/ `overlay_root.xml` 双层结构 / `OverlayView` callback 风格(`:731-735`) | **无（0 行）** —— 恢复既有实现 | 改包名、XML 加一层、`clear()` 3 行、半径/寿命 2 行、`onSendBurst` + `photoArea()` | 恢复 74 行 + 新写 ≈ 15 行 |
| **E7b** | 照片白化 | `cardPaint`(`:1058`) / `withAlpha`(`:2709`) / `ValueAnimator` 范式 / `drawCard` 里现成的照片矩形(`:2295-2308`) | **1 行 `drawRoundRect`**（+1 个 `photoWhite` 状态 + 1 个 animator） | 可选：`BackdropBlur.of()` 做真模糊（默认不做） | ≈ 12 行 |
| **E7c** | 保持 0.7s | `startFlyAway`(`:2648-2669`) / `resetRunnable` 的取消范式(`:1111-1112`) | **无（0 行）** | 把 `flyAnimator.start()` 挪进 `postDelayed` + 1 个常量 | ≈ 6 行 |
| **E8** | 失败横幅 | `statusIntent` 范式(`OverlayService.kt:970-974`) / `Ui.rounded`(`nfc/Ui.java:31`) / `topInsetPx`/`displaySize` / `mainHandler` / `Settings.canDrawOverlays` 兜底 | **无（0 行）** —— 纯控件路线 | 小窗 + 3 条收起 + 5s 计时 + 布局 XML + 3 string + 收摊点 | ≈ 145 行（其中 `OverlayView` **0 行**） |

**结论（三句话）**：

1. **E5 / E6 / E7a / E7c / E8 五项的新增绘制代码都是 0 行。**
2. 真正的新绘制只有两处：**E4 的一枚「取消」胶囊分支**（约 10–15 行）与 **E7b 的一行白化矩形**。
3. 恢复类工作量只有一件：`ParticleView.java`（74 行，逐字取回、只改包名）。

---

## 4. 挂载点说明

> 现状事实（`ANIM_INVENTORY_EXISTING.md` §1.1/§2.2）：几乎所有既有动效都**焊在 `OverlayView.onDraw()`**（`ui/OverlayView.kt:1198-1211`：`if (elapsed >= Timeline.PANEL_MS) drawTransferPanel(canvas)` / `if (edgeReveal > 0f) drawEdgeGlow(canvas)`），由**唯一一条 `Timeline`** 驱动，订阅点只有一处：`OverlayService.kt:509-516`。下面逐项说明"新效果挂在哪、为什么安全"。

| 项 | 挂载点 | 需要新 `Timeline` 刻度吗 | 影响 GL 层吗 | 能扛住 `wipeVisuals()` 吗 |
|---|---|---|---|---|
| **E4** | `OverlayView` 内部：`drawReceiveActions` 状态分支（`onDraw` → `drawTransferPanel:2206` → `drawReceiveActions`）+ `onReceiveTouch` 命中分流。**不新增任何全局图层。** | 不需要。触发时刻是"用户点按"，不在 `elapsed` 轴上 → 用 `ValueAnimator`（与 `returnCard`/`startFlyAway` 同类） | 不涉及 | 能：`resetSendState()`（`:1103-1114`）是所有复位路径的公共入口，已加 `acceptInProgress/cancelMix` 清零 |
| **E5** | `OverlayService.hideDemo()`（`:474-477`）新增"带时长的淡出 → 延时 `detachOverlay()`"分支；淡出本体是 `OverlayView.fadeOutPanel()` 改 `View.alpha` | 不需要（一次性收尾，不是常驻时间轴效果） | 不涉及（GL 层不跟着淡，见 §2.2 风险 1） | 能，但要**两道保险**：① `resetSendState()` 里 `alpha = 1f` + 取消 animator（`wipeVisuals` 会调它）；② `startSendFlow`/`startReceiveFlow`/`attachOverlay` 里清 `dissolveRunnable`（否则上一轮排的摘窗会摘掉新窗口） |
| **E6** | **唯一订阅点不用改**：`OverlayService.kt:514` 仍然每帧 `rippleView.setBlur(tl.photoBlurAt(elapsed))`，改的是 `Timeline.photoBlurAt` 内部多一条"回落"分支 | 不需要新刻度；新增的是 `releaseBlur()` **触发入口**与 `BLUR_RELEASE_MS` 常量 | **是**（GL 层 `u_blur`）：但走的是**既有 uniform**，零 GLSL 改动，无编译风险 | 能，但**必须**在 `Timeline.play()`（`:176-185`）与 `seek()`（`:197-201`）里清 `blurReleaseAt` —— 否则下一轮整轮保持模糊（这是本项唯一的硬坑） |
| **E7a** | **新起一整层**：`overlay_root.xml` 第三 child（`ParticleView`），由 `OverlayView.onSendBurst` 回调驱动（`OverlayService.buildOverlay()` 接线） | 不需要（自驱动短动画，≤0.55s 自动停） | 不涉及 | 能：`wipeVisuals()` 里加一句 `particleView.clear()` |
| **E7b/c** | `OverlayView` 内部：`startFlyAway()`（`:2648-2669`）起白化 + 起粒子 + 把飞走延后 `SEND_HOLD_MS`；白化绘制在 `drawCard`（`:2308` 之后） | 不需要（一次性，`ValueAnimator`） | 不涉及 | 能：`resetSendState()` 已清 `photoWhite`；`holdRunnable` 与 `resetRunnable` 一起注销 |
| **E8** | **完全在 `OverlayView` 之外**：`OverlayService` 自己持一个 `failView` + 独立小窗；`OverlayView` 一行不动 | 不需要 | 不涉及 | 能：它是**另一个窗口**，`wipeVisuals()` 管不到它 —— 所以收摊点必须显式覆盖 `ACTION_HIDE`/`ACTION_START`/`ACTION_RECEIVE`/`ACTION_STOP`/`onDestroy`/`onPeer`（§2.5-F） |

**四条通用结论**：

1. **没有任何一项需要动 `OverlayView.onDraw()` 的图层顺序**（`if (elapsed >= Timeline.PANEL_MS) drawTransferPanel` / `if (edgeReveal > 0f) drawEdgeGlow` 这两行原样保留）。
2. **没有任何一项需要新的 `Timeline` 常驻刻度** —— 五项全是"用户动作触发的一次性过程"或"收尾回落"，分别落在 `ValueAnimator`（E4/E5/E7）与 `Timeline` 的一个新分支（E6）上，`OverlayService.kt:509-516` 那个 lambda **只有 E6 通过它生效**（而且调用签名不变）。
3. **GL 层只在 E6 被触及**，且只用既有 uniform；**不需要改 GLSL**（E5 若要做"连背景一起淡"才需要，已列为可选）。
4. **挂载顺序建议**：E6 → E5（两者共用一条收尾路径，一起做最省）→ E4 → E7 → E8。E4 依赖 `ALIGN_PLAN_FINAL.md` 第二批的回执链路（`confirmReceived`/`rejectTransfer`）；E8 依赖第一批的回调改名后的新回调（`onPeerRejected`/`onVerdictTimeout`）。

---

## 5. 有意偏离参考产品的地方（写成清单，避免后续被当成缺陷"修"）

### 5.1 刻意偏离清单（用户已批准，**不是缺口、不要"补做"**）

| # | 偏离 | 参考产品怎么做 | 我方怎么做 | 用户决定 |
|---|---|---|---|---|
| 1 | **E1 光晕不做** | 碰触瞬间只在**朝向对端的那条边**亮起"彩虹色同心环 + 白色亮核扩散"，最后收敛成一条贴边蓝紫光带（E1，`fb_2.9.jpg`/`edgeB_36.jpg`） | 完全没有；碰触瞬间的顶部元素仍是"波源光斑"（`ANIM_INVENTORY_EXISTING` #10，`drawTopBloom` `ui/OverlayView.kt:1246-1283`） | **跳过**（本次范围决定 D-A） |
| 2 | **面板浮现保留我方现状（E2/E3 不对齐）** | E3 是**就地白雾显影**：卡片位置自始至终不动（`T2c_*.jpg` n=2→n=10 坐标不变），先是一团过曝白雾从卡片顶部中央扩张成卡片大小，再 0.3–0.4s"对焦"成照片；E2 是**无位移交叉淡入**（`T1_tile_1.jpg` n=3/n=4 双影），只有卡片亮边框先行 1–2 帧 | **入场动画**：面板整体上浮 8px（`slide`，`ui/OverlayView.kt:2135` + `:2190` 的 `translate`），卡片再缩 0.88→1、下移 22px、淡入（`drawCard` `:2242-2248`） | **保留现状**（"那面板浮现先不改了，就用现在的，现在这个动画也可以"）。⚠️ 因此 `slide`、`CARD_ENTER_MS`、卡片入场缩放/位移 **都不是缺陷、不要删**；`layoutReceiveActions` 的矩形本来就不含 `slide`（`cardRect` 在 `:2180` 算、`translate` 在 `:2190`），今天入场期间"画出来的按钮比能点的位置低 8px·k"这个既有小错位**本次不修** |
| 3 | **按压反馈保留** | **接收按钮没有任何按压反馈**（`T3_tile_1.jpg` n=5：手指已按上，胶囊无波纹、无缩放、无变暗） | **有按下态**：主按钮 `0xFFEDF7FF`→`0xFFD7EAFB`、次按钮 `0x14FFFFFF`→`0x29FFFFFF`（`ui/OverlayView.kt:2429-2440`，状态由 `onReceiveTouch` 维护 `:2533-2543`）。**E4 的「取消」胶囊同样沿用它**（§2.1-A-5） | **保留**（"按压反馈保留吧，这个体验好"） |
| 4 | **E6 的"起"段不改成 0.15s** | 虚化起段 ≈0.15s | `PHOTO_BLUR_MS = 500f`（`Timeline.kt:95`，与面板浮现同步走完 1500→2000ms） | 归 §5.1-2 的入场范围保持现状；**本次只新增"落"段** |

### 5.2 视频"反向清单"里我方**故意做得更多**的地方

> 出处：`ANIM_INVENTORY_VIDEO.md` §E0 明细。这些是**我方故意保留的额外反馈**，不是对齐失败。
> （第 3 条"按压反馈"是最重要的一条，已在 §5.1 单列。）

| # | 参考产品没有 | 我方有 | 位置 |
|---|---|---|---|
| 5 | **没有进度指示**（E0：全片无进度条/百分比/转圈，传送中只有模糊） | **有底部状态行**（`drawStatusLine` `:2467-2478`）显示「正在连接对方…」「已收到 k/N 张」等真实进度 | `OverlayView` + `TapService` |
| 6 | **没有四边白线 / 边缘辉光 / 顶部波源光斑 / 呼吸微颤**（这些全是我方原型的东西） | 全程保留（属 D-B 的入场与常驻层） | `OverlayView.drawEdgeGlow` 等 |
| 7 | **发送方成功时完全静默**（无 toast / 无对勾） | 静默这一点一致；但发送方**多了**一段卡片飞走动画（`startFlyAway`），参考产品是"照片原地白化 → 恢复清晰" | `OverlayView.startFlyAway`（E7c 只把它延后 0.7s，不改形态） |
| 8 | **卡片/弹窗没有关闭 ✕、没有超时自动消失** | 失败横幅**5 秒自动收起 + 滑动收起 + 点外收起**（`ALIGN_PLAN_FINAL.md` 的 D1，用户定）；接收方的"拒绝 / 取消"是出口 | `OverlayService` 横幅窗 |
| 9 | 视频里「取消」态只驻留 ≈0.45s（因为它的传输很快） | 我方驻留 = **整轮传输耗时**（两阶段协议下可能数秒），`onTransferDone` 才收摊 | `OverlayView.acceptInProgress` + `TapService.onTransferDone` |
| 10 | 接收卡入场时"卡片边缘稀疏的细小白点闪烁"（E3 ⑤，`T5b_tile_13.jpg` n=12/14，**推断项**） | 本次**不做**（E7 的 `ParticleView` 已在位，将来若要补只是换个数量/速度的调用，属可选二级） | — |

### 5.3 本次实现里**新引入**的偏离（要知会）

| # | 偏离 | 原因 / 影响 |
|---|---|---|
| 11 | **粒子画在「↑ 上滑发送 N 张照片」提示行之上** | 视频里那行提示在粒子之上（`T2e_tile_1.jpg` n=6/n=7 文字始终清晰）。我方把 `ParticleView` 放在最上层（`overlay_root.xml` 第三 child）才能让它压在**白化后的照片**上（视频确证）。替代方案（放到 `OverlayView` 下面）会让粒子被不透明卡片挡住 —— 不取 |
| 12 | **发送保持段是固定 0.7s，不是"保持到传完"** | 视频的保持段长度 = 传输耗时；我方取 `SEND_HOLD_MS = 700L`（视频区间下端），之后照旧飞走 → 摘窗（`ALIGN_PLAN_FINAL.md` §5.3 C-5 不变） |
| 13 | **E5 的淡出连四边白线/辉光一起淡**（若走 `View.alpha`） | 视频里没有这些元素，无从对照；好处是 0 新绘制。若验收要求"只淡面板"，改法见 §2.2-1 |

---

## 6. 仍需真机确认

> 全部按"能在真机上一眼看出来"的粒度写；每条都给了判据。

1. **E4 底色/字色/高度**：接收中「取消」胶囊用 `0x33FFFFFF` 底 / `0xFFFFFFFF` 字 / 40 设计 px 高 —— 与 `btn_cmp.jpg` 肉眼比对；偏亮压到 `0x29`、偏暗抬到 `0x40`。同时确认 220ms 的交叉淡入手感（视频 0.20–0.25s）。
2. **E5 的 `View.alpha` 在整屏透明硬件加速悬浮窗上是否真的生效**（若无效，退到"把系数乘进 `drawTransferPanel`"的备选，约 6 处）。同时确认 260ms 的摘窗延迟是否被感知为"卡了一下"。**可选增强**：给着色器加 `u_fade` uniform 让 GL 层一起淡（改 GLSL，编译失败会整层静默变空 → 必须在真机上看到画面才算通过）。
3. **E7 白化强度**：只做白化（方案 a）在**浅色/高调照片**上是否够"过曝"；不够就上 `BackdropBlur` 方案 (b)。同时确认粒子的观感：数量 48、直径 0.5–1% 屏宽、寿命 0.4–0.5s、发射点 = 照片区中心 —— 与 `T2e_007.jpg` 比对；若粒子"散得太开/太快下坠"，先调 `ParticleView` 的初速与重力（这两个数视频定不准）。
4. **`SEND_HOLD_MS = 700L` 的手感**：上滑后约 2.9s 窗口才消失，是否会让人以为"卡住了"（对照视频 `T5a_tile_13.jpg` n=12–n=15 的 ~0.5s 保持段）。
5. **E8 三条收起 + 入场**：① 滑动 24dp 是否够灵敏；② **`ACTION_OUTSIDE` 是否送达**（重点项）；③ 5s 自动收起；④ `RenderEffect` blur-up 在悬浮窗上是否可用（不可用就只用 alpha 淡入）；⑤ 底色在浅色/深色应用上的可读性。
6. **E6 回落 + 跨轮复位**：跑完整一轮（收完 → 摘窗）再碰第二次，确认第二轮背景在 2000ms 时**确实变糊**（验 `play()/seek()` 的 `blurReleaseAt` 清零点）；同时确认回落 250ms 的时长观感。
7. **「取消」的语义**（需用户拍板）：视频没拍到点了会怎样。默认 = 放弃本轮接收，发送方收到「发送失败 / 对方拒收」。若要区分成「对方已取消」，需新增一条 detail 文案 + 发送方分支。
8. **E4 与"已经收完"的竞态**：先点「接收」、传输已完结时点，确认「取消」胶囊只闪一下、查看器只开一次。
9. **`OverlayService` 通知里三个 action**（重播/关闭悬浮窗/触摸穿透）与新横幅窗、新淡出路径**互不干扰**：横幅在时点「关闭悬浮窗」→ 横幅也一起收干净。

---

## 附录

### A. 证据帧清单（本文引用的、已复核过的）

| 帧 | 时刻 | 用来证明 | 备注 |
|---|---|---|---|
| `_Temp/anim/T2e_007.jpg` | t≈6.40（n=6，30fps 密采，卡区 2.5x） | **E7 粒子迸发**：照片过曝白化 + 白色光点密布并向下扇形散开 | **本次已逐帧复核**：画面为"白色细小光点散布在过曝的照片上"，与 `ANIM_INVENTORY_VIDEO.md` E7 描述一致 |
| `_Temp/anim/T2e_tile_1.jpg` | n=0–7（同上序列的 2×3 拼图） | E7 全过程：n=2 轻微模糊 → n=3/n=4 重度模糊 → n=5 白化 + 粒子初现 → n=6/n=7 粒子扇形散开、照片仍糊 | 同一序列 |
| `_Temp/anim/btn_cmp.jpg` | t=9.45 vs t=9.85（按钮区逐像素对照） | **E4**：白胶囊在上、「拒绝」在下 → 只剩一枚「取消」落在**下面那枚的位置**、横向仍占满 | **本次已复核**：确认"取消"继承「拒绝」的位置与宽度 → 实现里画在 `receiveSecondaryRect` |
| `_Temp/anim/T3_tile_13.jpg` n=15/n=16 | 10.15 / 10.23 | **E5** 整卡消散（半淡 → 近无） | 见 `ANIM_INVENTORY_VIDEO.md` T3 |
| `_Temp/anim/T3c_tile_1.jpg` n=2/n=3/n=4 | 10.25 / 10.35 / 10.45 | **E6** 虚化逐级减弱（不是瞬回） | 同上 |
| `_Temp/anim/T6c_tile_1.jpg` n=1/n=2/n=3 | 27.98 / 28.02 / 28.05 | **E8** 横幅 blur-up 入场（糊 → 对焦中 → 清晰） | 同上 |

### B. 复现命令

```bash
FF=~/.claude/platform-tools/ffmpeg/ffmpeg
cd /dfb/zhuyang/share/nfc_test/pri_project/android/pri_app/nfc_tap_demo

# E4 按钮区前后对照（btn_945.jpg / btn_985.jpg → vstack 成 btn_cmp.jpg）
$FF -ss 9.45 -i Temp/video.mp4 -frames:v 1 -vf \
 "crop=200:370:205:15,hflip,vflip,crop=190:120:5:180,scale=760:480:flags=lanczos" \
 -q:v 2 _Temp/anim/btn_945.jpg -y
$FF -ss 9.85 -i Temp/video.mp4 -frames:v 1 -vf \
 "crop=200:370:205:15,hflip,vflip,crop=190:120:5:180,scale=760:480:flags=lanczos" \
 -q:v 2 _Temp/anim/btn_985.jpg -y

# E7 粒子密采（T2e_*：30fps，起点 6.20s，卡区 2.5x，带帧号）
LBL="drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='n=%{n}':fontcolor=yellow:fontsize=24:x=6:y=6:box=1:boxcolor=black@0.85"
$FF -ss 6.20 -to 6.55 -i Temp/video.mp4 -vf "fps=30,crop=...,scale=...,$LBL" -q:v 2 _Temp/anim/T2e_%03d.jpg -y
# （crop/scale 参数见 ANIM_INVENTORY_VIDEO.md 附录 B ④ 的通用密采模板）
```
> `-ss` 落在非帧边界时序列头两帧会重复（`ANIM_INVENTORY_VIDEO.md` 附录前的说明），**对时请按标签 `n`**。

### C. 关键文件索引（本次会改到的）

| 文件 | 本次涉及 |
|---|---|
| `app/src/main/java/com/pri/nfctouchdemo/ui/OverlayView.kt` | E4（接收中分支）、E5（`fadeOutPanel`）、E7b（白化）、E7c（hold） |
| `app/src/main/java/com/pri/nfctouchdemo/ui/ParticleView.java` | E7a（**新增文件**，从 `git show 3df3255^:src/com/pri/nfcdemo/ParticleView.java` 取，只改包名） |
| `app/src/main/res/layout/overlay_root.xml` | E7a（第三层 child） |
| `app/src/main/res/layout/overlay_fail_banner.xml` | E8（**新增文件**） |
| `app/src/main/java/com/pri/nfctouchdemo/anim/Timeline.kt` | E6（`releaseBlur` / `photoBlurAt` 分支 / `BLUR_RELEASE_MS` / `play`·`seek` 清零点） |
| `app/src/main/java/com/pri/nfctouchdemo/service/OverlayService.kt` | E5（`EXTRA_FADE_MS` + `dissolveRunnable` + `DISSOLVE_MS`）、E6（调 `releaseBlur`）、E7a（接线 `onSendBurst` + `clear()`）、E8（横幅窗全套） |
| `app/src/main/java/com/pri/nfctouchdemo/nfc/TapService.java` | E4（`ACTION_UI_RECEIVE_CANCEL` 分支）、E5（3 处 `hideIntent` 带时长）、E8（`onPeerRejected`/`onVerdictTimeout`/`onPeer` 收摊） |
| `app/src/main/java/com/pri/nfctouchdemo/nfc/Xfer.java` | E4（`confirmReceived()` / `rejectTransfer()`，按 `ALIGN_PLAN_FINAL.md` §5.1-5） |
| `app/src/main/res/values/strings.xml` | `receive_cancel` / `fail_title` / `fail_rejected` / `fail_timeout` |
