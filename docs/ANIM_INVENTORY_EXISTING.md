# 现有动效 / 视觉层复用手册（ANIM_INVENTORY_EXISTING）

> **本文只回答一件事：现在的代码里已经有什么动效、它由什么驱动、怎么触发、有哪几个可以拧的常量、以及"能不能单独拿出来播"。**
> 不评估参考产品需要什么（那是另一份文档的事）。所有结论都来自当前分支 `nfc_tap` 的源码，行号即该分支上的行号。
>
> 一句话结论（先说结论，细节在 §2）：**现有动效全部长在 `ui/OverlayView.kt`（Canvas）+ `gfx/RippleRenderer.kt`（GLES）两个"整屏层"里，共享同一个时钟 `anim/Timeline.kt`。**
> - **能"拿出去单独用"的只有 4 件**：GL 水波层本身（3 个 setter）、GL 背景模糊、`PhotoTint` / `BackdropBlur` / `ImageLoader` 三件图像派生工具、以及 `OverlayView.applyTimeline()` 这个"整页时间轴擦洗"入口。
> - **Canvas 这一层的每一项（白线、辉光、光斑、面板、卡片、按钮、提示）都没有单独的 play 入口**，它们只在 `OverlayView.onDraw()` 里按 `elapsed` 依次画出来；"只要其中一件"必须改代码。
> - 判废的旧动效里，**`ParticleView`（粒子爆发）全无替代、值得捡回**；`GlowView` 只有"顶部虹彩柔光带"这半条没有替代；`SendPage`/`ReceivePage` 整体已被取代，但"卡片堆叠""飞走带旋转""元素分段 reveal"三个细节现在没有。

---

## 0. 怎么用这份文档

| 你要做的事 | 看哪节 |
|---|---|
| 想知道某个效果现在有没有 | §2 总表（一行为一个效果） |
| 想知道某个效果能不能单独播 | §2 总表最后一列 + §2.3 群组说明 |
| 想给新效果挂时间 | §3.1 Timeline 的刻度与回调 |
| 想决定新效果画在哪一层（GL / Canvas / 独立小窗） | §3.2 双层结构 |
| 想画东西（Paint / 文字 / 圆角矩形 / 噪声 / 缓动） | §3.3 helper 清单——**新小效果一律复用这些，不要新造 Paint** |
| 想要"跟着照片变颜色 / 模糊底" | §3.4 图像派生 |
| 想捡回旧动效 | §4（含每一条的恢复命令与兼容性判断） |
| 想知道什么东西现在**根本不存在** | §5 |
| 想查常量值 | §6 附录（常量速查 + 时刻表） |

---

## 1. 概述

### 1.1 分层与时钟（现状）

```
OverlayService.kt:507-524  buildOverlay()
        │
        ├── Timeline(overlay)            anim/Timeline.kt:17    ← 唯一时钟
        │      tl.onFrame = { elapsed, ripple ->
        │          rippleView.setRipple(ripple)          :511   ← GL 层
        │          rippleView.setBlur(photoBlurAt(elapsed)) :514
        │          overlay.applyTimeline(elapsed, ripple)  :515  ← Canvas 层
        │      }
        │
        ├── RippleSurfaceView            gfx/RippleSurfaceView.kt:21   GLSurfaceView, RENDERMODE_WHEN_DIRTY
        │      └── RippleRenderer        gfx/RippleRenderer.kt:41      整个画面 = 一次全屏 drawArrays
        │
        └── OverlayView                  ui/OverlayView.kt:51          Canvas 自绘，全部图层在 onDraw():1198
```

`res/layout/overlay_root.xml` 就两层，根布局不设背景（窗口 `FORMAT_TRANSLUCENT`）：`RippleSurfaceView`（`:15-18`）+ `OverlayView`（`:20-23`）。

- **时钟只有一条**：`Timeline` 用 `host.postOnAnimation` 自走（`Timeline.kt:131-149`），入场段不限帧、常驻段限到 `AMBIENT_FRAME_MS = 33ms`（`Timeline.kt:106,138`，约 30fps）。
- **全屏只有两条绘制路径**：GL 层一次 `glDrawArrays`（`RippleRenderer.kt:212`），Canvas 层一次 `onDraw`（`OverlayView.kt:1198-1211`）。
- **例外**：只有两个"一次性动画"不用时钟，各自起 `ValueAnimator`——卡片回弹（`OverlayView.kt:2633`）和卡片飞走（`:2648`），见 §2 第 15/16 项。全工程再无其它动画 API（已 grep：`ValueAnimator` 只在 `OverlayView.kt:3,938,939,2636,2659`）。

### 1.2 内容归属一句话

| 层 | 管什么 | 扩展难度 |
|---|---|---|
| GL `RippleRenderer` | **整屏氛围 + 图像处理**：背景图折射形变、冷暖光带、波峰微光、扫过压暗、整屏模糊 | 改 `FRAGMENT_SHADER`（GLSL）；加"整屏"效果很省，加"局部图形"不可能 |
| Canvas `OverlayView` | **一切具体物体**：边缘白线/辉光、顶部光斑、面板 scrim、照片卡片、文字、接收按钮、底部状态行 | 加一个 `private fun drawXxx(c: Canvas)` 即可；helper 齐全（§3.3） |
| 独立小窗（现有第三形态） | 不碰动效层的控件式浮层（现在的查看器就用它） | `nfc/Overlay.java:31 show()` / `:55 showPassThrough()` + `nfc/Ui.java:22,31` 两个圆角背景 helper |

---

## 2. 动效 / 视觉层清单（总表）

### 2.1 主表

> 驱动方式列：**T** = `Timeline` 时间轴（`elapsed`）；**G** = GL 渲染循环（`setRipple`/`setBlur` → `requestRender` → `onDrawFrame`）；**C** = Canvas `onDraw`（由 T 触发 `invalidate`）；**V** = `ValueAnimator`；**S** = 纯状态切换。

| # | 名称 | 实现（文件:行，方法） | 驱动 | 触发入口（谁调） | 关键可调参数（值见 §6） | 能否单独复用 |
|---|---|---|---|---|---|---|
| 1 | **水波折射形变**（背景图被波纹推开） | `RippleRenderer.kt:451-460`（`FRAGMENT_SHADER` 内 `amp`/`offset`/`refracted`） | **G** | `Timeline.play()` → `OverlayService.kt:511 rippleView.setRipple(ripple)` | `RIPPLE_DISP`(331)、`RIPPLE_RIDGE_K`(322)、`RIPPLE_RING_HALF`(313)、`RIPPLE_HEIGHT_FRAC`(278)、`RIPPLE_SLOW`(286)、`RIPPLE_FAR_FALLOFF`(334)、`Timeline.RIPPLE_EASE`(103) | ⚠️ **半可**：`RippleSurfaceView` 是标准 `GLSurfaceView`，可放进任何布局、喂任意 `progress`；但只此一种"全屏折射"形态，不能只作用于一块区域 |
| 2 | **冷暖光带**（粉紫↔近白↔青蓝） | `RippleRenderer.kt:481-489,498`（`band`/`bandT`/`tint`） | **G** | 同 #1 | `RIPPLE_BAND_THIN`(298)、`RIPPLE_BAND_THICK`(299)、`RIPPLE_BAND_FALLOFF`(302)、`RIPPLE_TINT_WARM/MID/COOL`(360-362)、`RIPPLE_GLOW`(365)、`RIPPLE_FADE0/FADE1`(386-387) | ❌ 与 #1/#3/#4 在同一次 draw call 里，无独立开关（要单独用必须改 shader） |
| 3 | **波峰冷白微光 + 波谷压暗** | `RippleRenderer.kt:491-501`（`crest`/`trough`） | **G** | 同 #1 | `RIPPLE_CREST_GAIN`(378)、`RIPPLE_CORE_GAIN`(368)、`RIPPLE_CORE_FALLOFF`(342) | ❌ 同 #2 |
| 4 | **波前扫过即压暗**（整屏变暗，走到底） | `RippleRenderer.kt:471-479`（`darkR`/`reached`/`darken`） | **G** | 同 #1 | 硬编码在着色器里：`0.45` 最深、过渡带 `-0.10/+0.17`、底色 `vec3(0.018,0.026,0.04)`（`RippleRenderer.kt:475-478`） | ❌ 同 #2（注意它与 Canvas 层 scrim 是**两层**压暗，叠加后原图只剩一两成亮度，见 `OverlayView.kt:2137-2139`） |
| 5 | **背景整张模糊（交叉淡入）** | `RippleRenderer.kt:462-467`（`u_blur` 混 `u_blur_tex`）+ `BackdropBlur.kt:43 of()` | **G**（系数由 T 给） | `OverlayService.kt:514 rippleView.setBlur(tl.photoBlurAt(elapsed))` → `RippleSurfaceView.kt:59` | `Timeline.PHOTO_BLUR_MS`(95, 500ms，1500→2000ms)、`BackdropBlur.SCALE_DIV=4`/`BOX_RADIUS=6`/`PASSES=2`(26/29/32) | ✅ **可**：`setBlur(0..1)` 一行就能单独驱动；`BackdropBlur.of()` 是纯 CPU 函数，脱离 GL 也能给任何东西做模糊底 |
| 6 | **四边白线：波前显形**（顶边中点 → 沿顶边 → 两侧 → 底边） | `OverlayView.kt:1849 drawEdgeStroke()` + `2015 frontMaskShader()` + `2081 waveReached()` + `1966 buildRibbon()` | **C**（进度 = `edgeReveal`，`1083 applyTimeline` 里算） | `OverlayService.kt:515 overlay.applyTimeline(elapsed, ripple)`（每帧） | `Timeline.EDGE_START_MS`(51)、`Timeline.EDGE_REVEAL_MS`(65)、`RIPPLE_RADIUS_SCALE`(662)、`RIPPLE_REVEAL_NEAR`(664)、`RIPPLE_REVEAL_SPAN`(678)、`FRONT_MASK_STOPS`(644)、`FRONT_MASK_STEP_PX`(651)、`SNAP_SEAM_TO_PX`(632) | ❌ 焊死在 `onDraw`：`if (edgeReveal > 0f) drawEdgeGlow(canvas)`（`:1210`），无独立入口；**但可时间轴擦洗**（见 §3.1） |
| 7 | **白线线宽呼吸**（亮度恒定，只有粗细在变；逐点不同步） | `OverlayView.kt:1966 buildRibbon()` + `1955 lineJellyAt()` + `1617 breath()` + `1818 lineSwingGate()` | **C** | 同 #6 | `LINE_THIN_PX`(147)、`LINE_SWING_PX`(165)、`LINE_SWING_IN_MS`(174)、`LINE_MIN_PX`(183)、`LINE_ALPHA`(197, 恒定)、`LINE_BREATHE_PERIOD_MS`(372)、`GLOW_BREATHE_WOBBLES`(380)、`GLOW_BREATHE_DECAY`(389)、`LINE_JITTER_BLOCKS`(113)、`LINE_JITTER_PHASE`(119)、`LINE_JITTER_AMP_FLOOR`(131)、`SEED_LINE_PHASE/AMP`(134-135) | ❌ 同 #6；**「呼吸」这套 `breath()` 本身可被任何新效果借用**（§3.3） |
| 8 | **边缘辉光（内侧 + 贴着屏幕的外侧唇光 + 四角）** | `OverlayView.kt:1215 drawEdgeGlow()` → `1376 edgeHalo()` / `1773 cornerHalo()` / `1638 haloGradient()` / `1712 cornerGradient()` / `1678 glowProfile()` | **C** | 同 #6 | `LINE_INSET_PX`(95, 脊线位置)、`GLOW_LAYERS`(217)、`GLOW_DEPTH_PX`(239)、`GLOW_EDGE_ALPHA`(253)、`GLOW_PEAK_ALPHA`(263)、`GLOW_STOPS`(277)、`GLOW_COLORS`(292)、`GLOW_SLICES`(635)、`EDGE_CORNER_R_PX`(613)、`CORNER_SOFTEN`(353)、`CORNER_SOFT_R`(361)、`CORNER_QUIET_FRAC`(606) | ❌ 同 #6。注意三条"备而不用"的通道：`GLOW_BREATHE_AMP`(309)/`GLOW_BREATHE_PHASE`(317)/`GLOW_BREATHE_TRADE`(330) 现全为 0，调非 0 可立刻打开"辉光呼吸/沿边逐段宽度变化"（`1553 glowWidthPx`、`1585 scaleEdgeShader` 都还在） |
| 9 | **边缘点亮瞬间增强**（水波扫到时亮一下又落回） | `OverlayView.kt:2118 arrivalBoost()`（`sin(πt)` 包络，乘在辉光 alpha 上） | **C** | 同 #6 | `Timeline.EDGE_MS`(40, 590ms)、`EDGE_IGNITE_MS`(63, 650ms)、`EDGE_IGNITE_BOOST`(338, 0.32) | ❌ 同 #6 |
| 10 | **顶部波源光斑**（贴顶边的宽扁发光包，独立相位呼吸） | `OverlayView.kt:1246 drawTopBloom()` + `1320 drawBloomOval()` + `1350 topBloomGradient()` | **C** | 同 #6 | `TOP_BLOOM_START_MS`(446)、`TOP_BLOOM_IN_MS`(456)、`TOP_BLOOM_RX_FRAC`(463)、`TOP_BLOOM_RY_PX`(473)、`TOP_BLOOM_PEAK_ALPHA`(476)、`TOP_BLOOM_COLORS`(487)、`TOP_BLOOM_STOPS`(494)、`TOP_BLOOM_HEIGHT_SWING`(543)、`TOP_BLOOM_BREATHE_PHASE`(551) | ❌ 同 #6；但"**取照片主色换色**"这套（#11）是独立可抄的手法 |
| 11 | **光斑按照片主色换色（交叉淡入）** | `OverlayView.kt:1270-1282`（`mix` 分两遍画） + `1290 bloomTintShader()` + `1307 topBloomTintColors()` | **C**（颜色源在 `setCardPhoto`） | `overlay.setCardPhoto(bitmap)`（`:801`，只在换图时算一次主色） | `TOP_BLOOM_TINT_AT_MS`(507, 1500ms)、`TOP_BLOOM_TINT_MS`(513)、`TOP_BLOOM_TINT_CENTER_MIX`(516)、`TOP_BLOOM_TINT_MID_MIX`(519)、`TOP_BLOOM_TINT_MIN_VALUE`(528)、`TOP_BLOOM_TINT_CENTER_ALPHA/MID_ALPHA`(531/534) | ⚠️ **手法可复用**（"两枚同形渐变按 mix 各画一遍"），但代码绑在光斑上，不能直接挪去别的元素 |
| 12 | **发送面板浮现**（全屏 scrim + 整块上浮 8px + 文字淡入） | `OverlayView.kt:2131 drawTransferPanel()`（scrim `2140-2147`、上浮 `2135,2190`） | **C**（进度 = `panelAppearAt`，`:1099` 与 `Timeline.panelAppearAt` 同式） | `OverlayService.kt:515 applyTimeline`（`elapsed >= Timeline.PANEL_MS` 才画，`:1209`） | `Timeline.PANEL_MS`(74, 1500)、`Timeline.PANEL_FADE_MS`(86, 550)、`PANEL_TEXT_MS`(64, 420)、scrim 三色 `0x52070A10/0x5A080C14/0x6D080C14` stops `0/0.48/1`(`2141-2143`)、上浮 `8f*k`(`2135`) | ❌ 焊死；**整页擦洗可以做到**（§3.1） |
| 13 | **接收方信息行**（"发送给 xxx"） | `OverlayView.kt:2220 drawReceiverInfo()` | **C** | 文案入口 `setPeerInfo()`(`:778`)、`setMode()`(`:764`) | `16f*k`、`letterSpacing 0.012f`、阴影 `12f*k/0+2f*k`(`2225-2228`)、`infoGap 14f*k`(`2158`)、`PANEL_CHROME_DRAG_FOLLOW`(575) 控制它随手指让位 | ❌ 焊死（`"发送给 X"` 这一行的绘制与面板一体） |
| 14 | **照片卡片入场**（缩小 0.88→1 + 下移 22px + 淡入） | `OverlayView.kt:2238 drawCard()`（入场 `2242-2248`） | **C** | 同 #12（比面板晚 `CARD_DELAY_MS`） | `CARD_DELAY_MS`(65, 100)、`CARD_ENTER_MS`(66, 680)、`CARD_FADE_MS`(67, 480)、`lerp(0.88f,1f)`(`2246`)、`lerp(22f*k,0f)`(`2247`) | ❌ 焊死 |
| 15 | **卡片投影**（5 层圆角矩形近似 `0 22px 55px #0007`） | `OverlayView.kt:2270-2281`（`drawCard` 内循环） | **C** | 同 #14 | `grow = i*8f*k`、`alpha = 26-i*5`、下移 `22f*k*(1-i*0.12)`、卡片圆角 `23f*k`(`2267`) | ⚠️ **可抄**（10 行，未抽成 helper）；用 `shadowPaint`(`:1059`) |
| 16 | **卡片上滑拖动跟手 + 面板文字让位** | `OverlayView.kt:2482 onTouchEvent()`（`ACTION_MOVE` `2501-2506`）、`913-914 panelChromeFade` | **C**（手势） | 用户手势（命中判定 `2572 hitCard`，卡片外扩 `12f*k`） | `SEND_THRESHOLD_DP`(567, 85dp)、`PANEL_CHROME_DRAG_FOLLOW`(575, 0.35)、拖动时卡片缩放 `max(0.94f, 1f + dragY/(900f*k))`(`2257`) | ❌ 私有方法 + 私有状态，外部只能注入 `MotionEvent` 触发 |
| 17 | **松手回弹** | `OverlayView.kt:2633 returnCard()` | **V** | `onTouchEvent` 的 `ACTION_UP`（未过阈值时，`:2512`） | `duration = 260L`(`2637`)、`easeOutCubic`(`2640,2699`)、`LinearInterpolator`(`2638`) | ❌ 私有，只能由手势触发 |
| 18 | **卡片飞走（上滑发送）** | `OverlayView.kt:2648 startFlyAway()` + `2238 drawCard` 的 `flying` 分支(`2250-2254`) | **V** + **C**（画在第 16 帧的 `onDraw` 里） | `onTouchEvent` 的 `ACTION_UP`：`dragY < -SEND_THRESHOLD_DP*k`（`:2512`）→ 先回调 `onSendConfirmed` → 飞 → `RESET_DELAY_MS` 后 `finishSend()`(`:2671`) → `onSendFinished` → `OverlayService.kt:518 timeline.reset()` | `FLY_MS`(554, 460ms)、上抛 `-720f*k`(`2252`)、缩到 `0.72f`(`2253`)、alpha 在 `flyT/0.76` 处归零(`2254`)、`RESET_DELAY_MS`(564, 1750ms) | ❌ 私有；**这是全工程唯一的"离场"动效**，且**没有旋转**（对比 §4 的 `SendPage`） |
| 19 | **上滑提示浮动**（上下 4px 浮动 + 明暗呼吸） | `OverlayView.kt:2342 drawSwipeTip()` | **C** | 同 #12（发送态才画） | `elapsed % 1500L`(`2344`)、`floatY = -4f*k*smoothStep(tri)`(`2346`)、`alpha lerp(0.72f,1f)`(`2349`)、图标 `arrowUp` 路径(`1069-1072`)、`iconSize 16f*k`/字号 `13f*k`(`2352-2354`) | ⚠️ **方法本身参数化得好（`centerY`+`alpha`），但要它单独出现仍需改 `onDraw`** |
| 20 | **接收态两个按钮（含按下态）** | `OverlayView.kt:2422 drawReceiveActions()` + `2391 layoutReceiveActions()`（绘制与命中同一份矩形） | **C**（淡入跟 `appear`）+ **S**（按下只换底色） | `setMode(Mode.RECEIVE)`(`:764`) → 触摸 `onReceiveTouch()`(`:2528`) → `onReceiveAccepted`/`onReceiveRejected`（`OverlayService.kt:522-523`） | `RECEIVE_PRIMARY_H_PX`(685, 48)、`RECEIVE_SECONDARY_H_PX`(688, 40)、`RECEIVE_BTN_GAP_PX`(691, 10)、`RECEIVE_BTN_R_PX`(694, 14)、`RECEIVE_TAIL_GAP_PX`(703, 18)、主按钮 `0xFFEDF7FF`/按下 `0xFFD7EAFB`(`2430`)、次按钮 `0x14FFFFFF`/按下 `0x29FFFFFF`(`2437`)、字号 `15f*k`(`2446`)、`drawCenteredLabel`(`2454`) | ⚠️ **几何/文字 helper 可复用**（ALIGN_PLAN_FINAL §5.3 C-4 就是靠这几件做的"接收中·取消"）；按钮本身焊在接收态面板上 |
| 21 | **底部状态行**（一行小字，连接进度/失败原因） | `OverlayView.kt:2467 drawStatusLine()` | **C** | `overlay.setStatus(text)`(`:786`) ← `OverlayService.updateStatus()`(`OverlayService.kt:296-299`) ← `OverlayService.statusIntent()`(`:970`) | 字号 `11f*k`(`2470`)、颜色 `0xFFB9C2CC`(`2472`)、阴影 `8f*k/0+2f*k`(`2473`)、离底 `STATUS_BOTTOM_PX`(706, 46) | ❌ 焊死（但它是最容易"照抄"的一件：单方法、单文本、无状态） |
| 22 | **卡片照片 / 内置插画（静态层）** | `OverlayView.kt:2582 artworkBitmap()`（缓存）+ `2616 coverSource()` + `gfx/PhotoArtwork.kt:50 draw()` | **C**（渲染一次进缓存） | `overlay.setCardPhoto(bmp)`(`:801`)、`clearCard()`(`:825`) | 照片区 `9f*k` 内缩、底部留 `53f*k` 给文字(`2295-2300`)、圆角 `pr = 15f*k`(`2301`) | ⚠️ `PhotoArtwork.draw(canvas, rect)` 与 `coverSource()` 都**可独立调用**；缓存位图机制 `artCache`(`:918`) 值得照抄 |
| 23 | **窗口/生命周期的"无动画"事实** | `OverlayService.kt:527 attachOverlay()` / `551 detachOverlay()` | — | `attachAndPlay()`(`:414`)、`hideDemo()`(`:474`)、`scheduleAutoDemo()`(`:583`，挂窗后 **420ms** 自动播) | `REPLAY_DELAY_MS`(1017, 5000ms，通知"重播"的等待) | — （列在此处是为了提醒：**挂窗/摘窗都没有过渡动画**，见 §5） |

### 2.2 主表速读（按"能不能单独用"分类）

| 类别 | 条目 |
|---|---|
| ✅ 真正能独立调用的 | #5 背景模糊（`setBlur`）、#22 里的 `PhotoArtwork.draw()` / `coverSource()`、以及 §3.4 三件图像工具 |
| ⚠️ 半独立 / 手法可抄 | #1 GL 水波层（整屏形态可搬）、#11 主色换色的手法、#15 投影、#19 提示浮动、#20 按钮几何与文字 helper、#22 缓存位图 |
| ❌ 焊在整页里，无独立入口 | #6 #7 #8 #9 #10 #12 #13 #14 #16 #17 #18 #21（以及 #2 #3 #4 与 #1 同一个 draw call） |

**唯一"不写代码就能独立出效果"的官方入口**：`OverlayView.applyTimeline(elapsedMs, rippleProgress)`（`OverlayView.kt:1083`，**public**）——把任意 `elapsed` 喂进去即可跳到那一帧（`0` = 干净一屏；`2000` = 面板+卡片完全就位；`500` = 只有刚起步的白线）。`OverlayService.wipeVisuals()`（`:384-395`）就是靠 `applyTimeline(0L, 0f)` 把画面擦干净的，可作为范例。

---

## 3. 关键的可复用机制

### 3.1 `Timeline` 的刻度与回调机制

**文件**：`app/src/main/java/com/pri/nfctouchdemo/anim/Timeline.kt`（211 行）

| 机制 | 位置 | 说明 |
|---|---|---|
| 时钟本体 | `Timeline.kt:131-149` | `postOnAnimation` 自走；`e > INTRO_MS` 后按 `AMBIENT_FRAME_MS = 33L`(`:106`) 限帧（约 30fps）；**永不自动停**（`:147` 注释"四边白线的常驻微颤要一直在"） |
| 唯一回调口 | `Timeline.kt:109-117` (`interface OnFrame`) | `onFrame(elapsedMs: Long, ripple: Float)`；全工程唯一订阅点在 `OverlayService.kt:509-516` |
| 派生进度函数 | `:156 rippleAt` / `:162 panelAppearAt` / `:172 photoBlurAt` | `rippleAt` 带缓入 `RIPPLE_EASE = 1.18`(`:103`)；后两个是线性，`OverlayView` 把 `panelAppearAt` 的式子抄了一份在 `:1099-1100`（因为它只拿到 `elapsed`、没有 `Timeline` 实例） |
| 布尔门 | `:151 glowing` / `:152 edgeLit` / `:153 panelActive` / `:126 introFinished` | **`glowing` / `edgeLit` / `panelActive` / `introFinished` 现在全无人读**（只有 `OverlayView` 里等效的式子 `elapsed >= Timeline.PANEL_MS` 在用，`:1209,2487,2529`）；`SETTLE_MS`(`:83`) 自己也注明"代码里没人读它" |
| 播放控制 | `:176 play` / `:188 resume` / `:197 seek` / `:204 reset` / `:207 stop` | 实际被调用的只有 `play`（`OverlayService.kt:435,455`）、`stop`（`:386,560,684`）、`reset`（`:518`）；**`resume()` 无人调用**，`seek()` 只被 `reset()` 用。→ 擦洗能力现成、但没被用起来 |

**新效果怎么挂进来（推荐顺序）**：

1. **时间驱动（推荐）**：在 `OverlayService.kt:509-516` 那个 lambda 里加一路（例如 `rippleView.setXxx(...)`），或在 `OverlayView` 里按 `elapsed` 自己算进度——**所有画面都以 `elapsed` 为唯一时间源**，这样"重播 / 擦洗 / 复位"自动跟着对。
2. **一次性动画**：照 `returnCard()`(`:2633`) / `startFlyAway()`(`:2648`) 的写法——`ValueAnimator.ofFloat(0f,1f)` + `duration` + `LinearInterpolator` + 自己 easing（`easeOutCubic`/`easeOutQuint`），并在 `cancelAnimators()`(`:2682`) 与 `resetSendState()`(`:1103`) 里注销。
3. **不要另起第三套时钟**：现在只有一条 `Timeline` + 两个短 `ValueAnimator`，这个格局是刻意的（`Timeline.kt` 头部注释：常驻段只剩慢动效，没必要跑满刷新率）。

**新刻度怎么加**：把常量加进 `Timeline.companion`(`:19-107`)，命名沿用 `XXX_MS`，并在注释里写清"它是谁的基准、改它还会连带挪动谁"（现有注释就是这么串起来的：`EDGE_REVEAL_MS` → `TOP_BLOOM_*` → `PANEL_MS` → `CARD_DELAY_MS`，见 `Timeline.kt:54-74` 与 `OverlayView.kt:497-507`）。

### 3.2 双层结构：谁该画在哪一层

| 问题 | GL 层 | Canvas 层 |
|---|---|---|
| 身份 | `RippleSurfaceView`(`RippleSurfaceView.kt:21`) + `RippleRenderer`(`:41`)；透明窗（EGL 8/8/8/8 + `TRANSLUCENT`，`:32,35`），按需重绘 `RENDERMODE_WHEN_DIRTY`(`:38`) | `OverlayView`(`OverlayView.kt:51`)，普通 `View.onDraw`(`:1198`) |
| 输入面 | 只有 3 个：背景 `Bitmap`(`setGallerySource:72`)、`ripple 0..1`(`setRipple:47`)、`blur 0..1`(`setBlur:59`)。重复值会被跳过，**常驻段不刷 GL**（`:48,61`） | 只有一个：`applyTimeline(elapsed, ripple)`(`:1083`)，其它都是状态 setter（`setMode/setPeerInfo/setStatus/setCardPhoto/setCardInfo/clearCard`） |
| 能画什么 | 只有"整屏一张图 + 全屏后处理"。局部图形**做不到** | 任意：渐变、路径、圆角矩形、文字、位图、裁切 |
| 加东西的成本 | 改 GLSL（`FRAGMENT_SHADER`，`RippleRenderer.kt:398-504`）：加"整屏"效果（暗角、噪点、整体调色、第二道波纹）很省 | 加 `private fun drawXxx(c: Canvas)` + 在 `onDraw`(`:1198-1211`) 里按 `elapsed` 调一次 |
| 风险 | 着色器编译失败会 **整层静默回退成空**（`onSurfaceCreated` 的 `runCatching`，`RippleRenderer.kt:118-121`）；改完全程要在真机上看 | 每帧在 UI 线程重画 → **静态内容必须缓存成 `Bitmap`**（`artCache`/`artworkBitmap`，`OverlayView.kt:2582-2607`） |
| 结论 | **整屏氛围/调色 → GL；具体物体 → Canvas。** 二者用同一个 `elapsed` 同步，天然同帧 | |

**第三种形态（不碰动效层）**：`nfc/Overlay.java`（85 行，**现在仍在，且与删除前的旧版逐字一致**）提供两个现成窗口壳：
- `show(View)`（`:31`）—— 全屏、可触摸、`TYPE_APPLICATION_OVERLAY`；
- `showPassThrough(View)`（`:55`）—— 全屏、`FLAG_NOT_TOUCHABLE`（下层应用照常可点）。
配合 `nfc/Ui.java:22 placeholder()` / `:31 rounded()` 两个圆角背景 helper，就能搭出"不写一行 Canvas 的浮层"（`ALIGN_PLAN_FINAL.md` §5.4 的失败横幅就是走这条路）。

### 3.3 `OverlayView` 里的 helper / Paint 清单（**新小效果一律复用这些**）

**Paint 池（12 枚，全部是常驻字段，构造于 `OverlayView.kt:1020-1065`；绘制路径上不 new 任何 Paint/Shader/Path，见 `:1025-1026` 的注释）**

| Paint | 行 | 用途 / 注意 |
|---|---|---|
| `glowPaint` | `1020` | 贴边辉光逐段矩形（**非** AA，避免相邻切片之间出现细缝） |
| `cornerGlowPaint` | `1023` | 四角径向辉光（AA，外沿就是屏幕圆角） |
| `topBloomPaint` | `1031` | 顶部波源光斑（AA，画 `drawOval`） |
| `edgeStroke` | `1049` | AA + `STROKE`/`Cap.BUTT`/`Join.ROUND`；卡片 1px 白描边用它（`2290-2292`），用完要 `shader = null` |
| `lineRibbon` | `1054` | AA + `FILL`，白线线体（线宽沿边变化，描边做不到所以是填充路径） |
| `scrimPaint` | `1057` | 全屏三段竖渐变压暗（`2140-2147`） |
| `cardPaint` | `1058` | 圆角矩形通用：卡片本体（`2285`）+ 接收按钮底色（`2433,2440`） |
| `shadowPaint` | `1059` | 卡片投影 5 层（`2270-2281`） |
| `photoClip` | `1060` | `drawBitmap` 的 alpha 载体（`2303-2308`） |
| `artPaint` | `1063` | AA + `FILTER_BITMAP_FLAG`，往卡片缓存里缩放画照片（`2595-2600`） |
| `textPaint` | `1064` | **所有文字**；`typeface` 在 `bold`/`normal` 之间切（`1016-1017`），`letterSpacing` 每次显式设 |
| `iconPaint` | `1065` | 只被上滑提示的箭头用（`2362-2367`） |

**工具函数（缓动 / 噪声 / 颜色）**

| helper | 行 | 作用 |
|---|---|---|
| `breath(phase)` | `1617` | **欠阻尼弹簧（果冻）**：`e^(-2.5s)·sin(2π·3s)/PEAK`，周期 `LINE_BREATHE_PERIOD_MS=4800`(`372`)；`elapsed <= WAVE_MS` 时恒 0。**任何"呼吸/脉动/心跳"都该用它，不要新写 `sin`** |
| `smoothStep(t)` | `2704` | 3t²−2t³ |
| `easeOutCubic` / `easeOutQuint` | `2699` / `2702` | 后者注释：近似 CSS `cubic-bezier(.16,1,.3,1)` |
| `lerp` | `2697` | 线性插值 |
| `withAlpha(color, a)` | `2709` | 换 alpha 通道（绘制路径上大量使用） |
| `hash01(seed, i)` | `1523` | 整数哈希 → 0..1（整数运算、无分配） |
| `sliceNoise(seed, index)` | `1513` | 沿段连续的确定性噪声（每 `GLOW_JITTER_BLOCK=9` 段一个控制点，段内 smoothstep） |
| `ringNoise(seed, u)` | `1934` | **首尾相接**的环形噪声（白线是闭环，`wrapBlock:1944`） |
| `lineJellyAt(i)` | `1955` | `breath(自己的相位) × 自己的幅度`——"逐点不同步"的标准写法 |
| `taperAt(mid, lo, hi)` | `1474` | 两端收敛回基准的系数（角接缝严丝合缝的关键） |
| `drawCenteredLabel(c, rect, text)` | `2454` | 矩形内居中一行字（水平按 `measureText`、垂直按 `fontMetrics` 校正）——**新加按钮/胶囊直接用它** |
| `edgeSlice(rect, side, s0, s1, depth)` | `2052` | 把矩形摆成某条边的一段（四条边的统一入口） |

**固定写法（照抄就不会踩坑）**

- **文字阴影**：`setShadowLayer(8~12f*k, 0f, 2f*k, 0x??000000)` + **用完立刻 `clearShadowLayer()`**（`2228/2235`、`2371/2374`、`2473/2477`）。
- **圆角矩形**：`c.drawRoundRect(rect, r, r, cardPaint)`；**照片区裁切**：`cardPath.addRoundRect + c.clipPath`（`2305-2307`）。
- **渐变"按最宽建、每帧用 Matrix 压回来"**：`haloGradient:1638` / `cornerGradient:1712` / `topBloomGradient:1350` 只在 `onSizeChanged`（或首帧）建一次，每帧只 `setLocalMatrix`（`scaleEdgeShader:1585`、`drawBloomOval:1332`）。**不要每帧 new Shader**。
- **可交互元素**：几何必须"绘制与命中共用同一份"——`layoutReceiveActions()`(`2391`) 每帧算、`onReceiveTouch()`(`2528`) 判命中前也调一次同一个函数（注释在 `:2380-2390`）。新加可点元素照这个模式。
- **复用缓冲**：`cardRect`/`scratch`(`856-857`)、`topBloomOval`(`1035`)、`profileDepth`/`profileColor`(`1663-1664`)——绘制路径上不分配对象。
- **尺寸口径**：全部设计 px × `k`，`k = viewW / DESIGN_W`，`DESIGN_W = 380f`(`59`、`1122`)；安全区用 `topInset`(`715`)/`bottomInset`(`718`)，由 `OverlayService.applyBarInsets()`(`705`) 灌。

### 3.4 图像派生输入（可直接喂给新效果）

| 能力 | 入口 | 关键参数 | 现有用法 |
|---|---|---|---|
| **照片主色** | `PhotoTint.dominant(bitmap): Int?`（`PhotoTint.kt:43`） | `SAMPLE=32`(`18`)、`MIN_ALPHA=32`(`21`)、`DARK_CUTOFF=32`(`24`)、`BINS=16³`(`27`)、打分 `count × (0.25 + 饱和度)`(`87`) | `OverlayView.setCardPhoto:803`（换图时算一次）→ 光斑换色 |
| **主色变浅** | `PhotoTint.lighten(color, whiteMix, minValue)`（`PhotoTint.kt:111`） | HSV 抬 V、S 降 35%·mix、V 兜底 `minValue` | `OverlayView.topBloomTintColors:1307-1317` |
| **背景模糊版** | `BackdropBlur.of(bitmap): Bitmap?`（`BackdropBlur.kt:43`） | `SCALE_DIV=4`(`26`)、`BOX_RADIUS=6`(`29`)、`PASSES=2`(`32`)、`MIN_SIDE=8`(`35`) | `RippleRenderer.upload:108` 顺手算一次（UI 线程，几毫秒） |
| **解码 / 裁切 / 文案** | `ImageLoader.decode`(`:60`)、`decodeCover`(`:50`)、`describe`(`:106`)、`coverCrop`(`:138`)、`displayName`(`:103`) | `MAX_DECODE_SIDE=1536`(`39`)、垫底色 `0xFF050608`(`:42`) | `OverlayService.decodeSendImages:312` / `decodeReceiveImage:341` |
| **Bitmap 的 cover 源矩形** | `OverlayView.coverSource(src, dstW, dstH)`（`:2616`） | 只裁不缩 | `artworkBitmap:2597` |
| **内置插画（无照片时）** | `PhotoArtwork.draw(canvas, rect)`（`PhotoArtwork.kt:50`） | 视口 274×298(`:20-21`)，cover 语义 | `artworkBitmap:2602` |
| **缓存位图范式** | `artworkBitmap(w,h)`（`:2582`） | 尺寸变了就 `recycle` 重建（`:2586-2589`） | 卡片照片/插画 |

---

## 4. 已删除但可从 git 历史恢复的动效

**历史背景**（这一点很重要，先纠正一个常见说法）：这些文件**不是 merge 删的**，是 `3df3255 清除代码` 把旧的扁平结构 NFC demo（根目录 `src/com/pri/nfcdemo/*`、`variants/*`、`res/*`）整体删掉；随后 `057b617 上传合成代码` 才把合并后的工程放进 `app/`。所以恢复源是 **`3df3255^`（= `1d7cf92` / `cca085d` 都行）**。

一条命令查全部被删文件：

```bash
git log --all --diff-filter=D --name-only --pretty=format:'=== %h %s'
```

### 4.1 逐文件清单

| 文件（旧路径） | 删除提交 | 行数 | 恢复命令 | 画什么 / 公开 API | **现在是否冗余** |
|---|---|---|---|---|---|
| `src/com/pri/nfcdemo/ParticleView.java` | `3df3255` | **74** | `git show 3df3255^:src/com/pri/nfcdemo/ParticleView.java` | **星光粒子爆发**：白色实心圆自 `(cx,cy)` 向四周迸发，`vy -= 140` 上抛 + `vy += 280·dt` 重力；`public ParticleView(Context)` / `public void burst(float cx, float cy, int n)` | ❌ **完全不冗余——全工程没有任何粒子系统**。恢复成本极低（见 §4.2） |
| `src/com/pri/nfcdemo/GlowView.java` | `3df3255` | **64** | `git show 3df3255^:src/com/pri/nfcdemo/GlowView.java` | 两件事：① 边缘径向光晕（中心透明 → 边缘亮白），`startBreathing()` 用 `ValueAnimator(0.4→0.95, 3400ms, REVERSE, INFINITE)` 呼吸；② **顶部虹彩柔光**：顶部 `64dp` 高的一条横向渐变 `0x00FFFFFF → 0x88FFB6C8 → 0x00FFFFFF`（`GlowView.java:35-36,46`） | **一半冗余**：① 的"边缘呼吸"已被 `OverlayView` 的辉光 + 白线线宽呼吸全面取代（周期 4800 / 果冻弹簧 / 逐点抖动，`OverlayView.kt:1617,1966`），且旧版是"整圈亮度一起变"，观感更糙；② 的**顶部粉/虹彩横带当前没有对应物**（现在的顶部元素是椭圆形的"波源光斑"，纵向鼓包 `TOP_BLOOM_*`，不是横带） |
| `src/com/pri/nfcdemo/SendPage.java` | `3df3255` | 273 | `git show 3df3255^:src/com/pri/nfcdemo/SendPage.java` | 程序化拼 View 的发送确认页（已被 `OverlayView` 全量取代） | **整体冗余**，但有 3 处细节现在没有：见 §4.3 |
| `src/com/pri/nfcdemo/ReceivePage.java` | `3df3255` | 211 | `git show 3df3255^:src/com/pri/nfcdemo/ReceivePage.java` | 程序化拼 View 的接收确认页 | **整体冗余**，但"分段 reveal"机制没有替代（§4.3） |
| `src/com/pri/nfcdemo/{Overlay,Ui,Bus,TapService,Xfer,MainActivity,TapDispatchActivity,HceService}.java` | `3df3255` | 85/127/239/335/979/791/23/62 | `git show 3df3255^:src/com/pri/nfcdemo/Ui.java` | — | **没有丢**：其中 `Overlay.java`(85)、`Ui.java`(127) 与现在的 `app/src/main/java/com/pri/nfctouchdemo/nfc/` 下同名文件**逐字一致**（`nfc/Overlay.java` 85 行、`nfc/Ui.java` 127 行）；`Xfer.java` 也是完整搬过来的（现 1033 行，比旧版多） |
| `variants/Xfer_{bt,wifi,hotspot_only}.java.txt` | `3df3255` | 382/742/428 | `git show 3df3255^:variants/Xfer_wifi.java.txt` | 三套通道的存档实现（与动效无关） | 与动效无关；当前 `Xfer.java` 已把三套阶梯合并 |
| `一碰互联_技术实现方案.md` | `3df3255` | 139 | `git show 3df3255^:"一碰互联_技术实现方案.md"` | 旧技术方案；其中 §1.2 明确写过"触发发送 → **星光粒子动效** + 卡片飞出"（该行提到 `ParticleView` 的定位） | 文档，仅作参考 |
| `debuggallery/**`（调试相册 APK 源码） | `3df3255` | 283 | `git show 3df3255^:debuggallery/src/com/pri/debuggallery/MainActivity.java` | 与动效无关 | — |

> 合并时的取舍在仓库里是写明了的：`README.md:73` 与 `MERGE_PLAN.md:60,151` 都写了"删掉 `SendPage` / `ReceivePage` / `GlowView` / `ParticleView`，确认页与粒子改由动效层负责"。
> **注意：这个"改由动效层负责"其实只兑现了一半——确认页确实由 `OverlayView` 接管了，但"粒子"在 `OverlayView` 里根本没有对应实现。**

### 4.2 `ParticleView`：恢复成本与调用方式（重点）

**它在旧代码里怎么被驱动**（`git show 3df3255^:src/com/pri/nfcdemo/MainActivity.java`，`playParticles()` 在 605-616 行；字段声明在 55、309-312）：

```java
// 旧 MainActivity.java:605-616
private void playParticles() {
    final ParticleView pv = new ParticleView(this);
    if (!Overlay.get(this).showPassThrough(pv)) return;      // ← 不拦截触摸的独立全屏窗
    pv.post(new Runnable() { @Override public void run() {
        pv.burst(pv.getWidth() / 2f, pv.getHeight() * 0.42f, 90);   // 屏幕中央偏上 90 颗
    }});
    pv.postDelayed(new Runnable() { @Override public void run() {
        Overlay.get(MainActivity.this).hide(pv);
    }}, 2600);
}
```

**恢复难度：低。** `showPassThrough()` 现在仍在（`nfc/Overlay.java:55`），`MainActivity.playParticles` 的调用点也还留着语义位置（发送确认那一刻），所以恢复 = 把 74 行的 `ParticleView.java` 拷回 `nfc/` 包（包名要改）+ 抄回约 12 行调用。

**可调参数（全部硬编码在 `burst()` / `onDraw()` 内，`ParticleView.java` 行号）**

| 参数 | 值 | 行 |
|---|---|---|
| 粒子数 | 调用方给（旧调用点给 90） | 旧 `MainActivity:610` |
| 初速 | `120 + random*480` px/s | `:34` |
| 方向 | 全圆均匀随机 | `:33` |
| 上抛偏置 | `vy -= 140` | `:38` |
| 重力 | `vy += 280 * dt` px/s² | `:63` |
| 半径 | `2 + random*4.5` px | `:39` |
| 寿命 | `0.6 + random*0.9` s | `:40` |
| alpha | `255 * life`（寿命线性衰减，无淡入） | `:65` |
| 颜色 | 纯白 `Color.WHITE` | `:64` |
| dt 上限 | `0.05` s（防掉帧时位置跳变） | `:54` |
| 驱动 | `onDraw` 里 `postInvalidateOnAnimation` 自循环，粒子全部死掉自动停（`mRunning=false`） | `:68-72` |

**已知小瑕疵**（恢复时要顺带决定是否修）：`burst()` 里 `mLast = 0`，而 `onDraw` 用 `mLast == 0` 判断首帧（`:53`）——首帧按 `dt = (now-0)/1000` 会被 `Math.min(0.05f, ...)` 救回，逻辑上侥幸成立；另外粒子层没有 `setLayerType`，全屏 90 个小圆在主线程 `drawCircle` 没有大问题。

### 4.3 `SendPage` / `ReceivePage`：整体不要，三块细节可捡

| 细节 | 旧位置 | 现在的状态 | 值不值得捡 |
|---|---|---|---|
| **卡片堆叠**（最多 2 张：第 2 张 `translationY 10dp` / `scale 0.94` / `alpha 0.75`；主卡呼吸缩放 `1.0↔1.028` / 3400ms / REVERSE） | `SendPage.java:219-250`（`addCard`）、`:88-103`（呼吸 `ValueAnimator`） | `OverlayView` 只有**单卡**，且卡片入场后不再有任何缩放运动（`drawCard:2238` 只在 `flying`/`dragging` 时改 scale） | ✅ **要"多张照片"或"卡片常驻微缩放"的话，这是唯一的现成实现** |
| **飞走带旋转** | `SendPage.java:171-175`：`translationY(-h) .alpha(0) .rotation(-7f)`，340ms | `startFlyAway:2648` 只做"上抛 720·k + 缩到 0.72 + 淡出"，**没有旋转** | ✅ 一行 `c.rotate()` 或 `Matrix`（`drawCard:2262-2265` 已有 `save/translate/scale/translate` 骨架，加旋转是 1 行） |
| **元素分段 reveal**（碰完先只留背景+光效，对方开始发才 `reveal()` 450ms 渐显全部元素） | `ReceivePage.java:167-180`（`hideElements()` / `reveal()`） | 现在的接收页是"一次性按 `PANEL_MS` 时间轴淡入"（`drawTransferPanel:2131`），**没有"先空屏、后补元素"的开关**；最接近的近似是 `applyTimeline(之前的时间)` 或 `setStatus` 换文案 | ⚠️ 看是否需要"延迟揭晓"的节奏；实现上给 `OverlayView` 加一个 `revealAt` 偏移量即可（约 10 行） |
| 背景模糊（`RenderEffect.createBlurEffect(90,90)` + `alpha 0.7/0.75`） | `SendPage.java:53-57`、`ReceivePage.java:46-51` | 已被 `BackdropBlur` + GL 交叉淡入（#5）取代，后者质量更高（多级降采样 + 2 遍盒模糊） | ❌ 冗余 |

### 4.4 `sendPreviews` / `thumbnail` / 其它 helper：**没有丢，不需要恢复**

| 名字 | 现状 | 说明 |
|---|---|---|
| `Xfer.sendPreviews(List<Uri>)` | ✅ 在 `nfc/Xfer.java:887` | 比旧版更稳（会等最多 10 秒连接就绪，`:896-901`；最多推 3 张，`:910`） |
| `Xfer.thumbnail(Uri)` | ✅ 在 `nfc/Xfer.java:929` | 长边 900px + `JPEG q82`（`:937,945`）；旧版是 360px + `q60`（旧 `Xfer.java:882-891`）→ 现行参数更清晰 |
| `MainActivity.loadPreview(Uri)` | ✅ 在 `nfc/MainActivity.java:723` | 1080px 降采样，自己解码不用 `setImageURI`（防无权限崩溃） |
| `Ui.dp/placeholder/rounded/loadImage/buildViewer` | ✅ 在 `nfc/Ui.java:17/22/31/39/69` | 与旧版逐字一致；`buildViewer`（全屏大图查看器，左右半屏翻页）仍在用 |
| `Overlay.get/show/showPassThrough/hide` | ✅ 在 `nfc/Overlay.java:19/31/55/78` | 与旧版逐字一致 |

**结论：唯一真正需要从 git 捡回来的动效文件是 `ParticleView.java`（+ 可选 `GlowView.java` 的顶部横带那 3 行渐变）。**

---

## 5. 当前代码中**不存在的**效果（诚实清单）

> 只陈述"我们的代码里没有"，不推测参考视频需要什么。

| # | 不存在的效果 | 依据 / 现状 |
|---|---|---|
| 1 | **粒子 / 星光爆发（任何形式的粒子系统）** | 全工程无粒子代码；唯一实现是已删除的 `ParticleView.java`（§4.2） |
| 2 | **方向性 / 单边辉光** | 四边辉光的参数表是**按层**索引的（`GLOW_DEPTH_PX`/`GLOW_EDGE_ALPHA`/`GLOW_PEAK_ALPHA`/`GLOW_STOPS`/`GLOW_COLORS`，`OverlayView.kt:239-300`），**没有任何按边（上/下/左/右）的强度或颜色开关**；逐边的分支只有"宽度对偶守恒"那条（`GLOW_BREATHE_TRADE`，`1553-1559`），且现为 0。`CORNER_SOFTEN`(`353`) 只改过渡带宽度、不改亮度 |
| 3 | **独立的淡出 / 消散过渡** | 全工程唯一的离场动效是卡片飞走（`startFlyAway:2648`，位移+缩小+淡出，**无旋转、无粒子化、无溶解**）；窗口收起是瞬时 `removeView`（`detachOverlay:551-563`），**没有窗口淡出**；`Timeline.stop()`(`:207`) 只是停表，画面留在最后一帧 |
| 4 | **独立横幅 / 药丸 / toast 式浮层** | 原型的 `.toast-msg` 被明确删除（`OverlayView.kt:46-47` 注释：卡片飞走后不再有任何提示）；现在唯一的"提示"是面板里那行上滑提示（#19）和底部状态行（#21），**两者都不能独立于面板显示**，也没有任何控件路线的横幅实现（`ALIGN_PLAN_FINAL.md` §5.4 的失败横幅属于"待新增"） |
| 5 | **进度指示（进度条 / 进度环 / 百分比）** | 没有任何进度类绘制；传输进度只能靠 `setStatus()` 的一行文字（`drawStatusLine:2467`）表达 |
| 6 | **多张卡片的堆叠 / 网格 / 缩略图列表** | `OverlayView` 只画一张卡（`cardRect` 单例，`856`；`drawCard:2238`）；旧 `SendPage` 的卡堆已删（§4.3） |
| 7 | **旋转（任何元素）** | 全工程无 `rotate`/`setRotation`（卡片飞走、面板上浮、按钮、光斑都不转） |
| 8 | **点按反馈动画（触摸涟漪 / 按下缩放）** | 接收按钮的按下反馈是**纯状态切换**（`pressedButton` → 换一档底色，`2429-2440`），无扩散、无缩放、无涟漪；卡片/其它区域点击无反馈 |
| 9 | **页面 / 窗口转场动画** | 无 Activity 转场、无共享元素、无窗口进出场（`attachOverlay:527` / `detachOverlay:551` 都是瞬时）；`scheduleAutoDemo:583` 的 420ms 只是延时，不是动画 |
| 10 | **文字的动态效果（数字滚动 / 打字机 / 逐字淡入）** | 文案只有整体 alpha 淡入（`panelAppearAt`）与 `setPeerInfo:778` 直接换字符串（只 `invalidate()`），**没有内容变化动画** |
| 11 | **声音 / 触感反馈** | 全工程无 `SoundPool`/`MediaPlayer`/`Vibrator`/`HapticFeedback` |
| 12 | **卡片的常驻运动（呼吸缩放 / 悬浮）** | 卡片入场后完全静止（`drawCard:2238` 只有 `flying`/`dragging` 两条分支改几何）；"常驻在动"的只有白线线宽（#7）、辉光呼吸通道（当前关闭）、顶部光斑（#10）、上滑提示浮动（#19） |
| 13 | **缩略图 / 大图查看器之外的图片相关视觉** | 查看器（`nfc/Ui.java:69 buildViewer`）是纯控件、无动画 |

---

## 6. 附录

### 附录 A：时刻表（谁在什么时候开始）

来源：`Timeline.kt:19-107` 与 `OverlayView.kt` 里的派生常量。**所有时刻都是"从 `Timeline.play()` 起算的毫秒"**。

| 时刻 | 事件 | 常量 / 出处 |
|---|---|---|
| 0 | 时间轴起步；GL 水波开始扩散（`ripple` 0→1，缓入 `1-(1-t)^1.18`） | `Timeline.play():176`、`RIPPLE_EASE:103` |
| 300 | `glowing = true`（**当前无人读**） | `GLOW_MS:37` |
| 500 | **白线从顶边中点起步**（`edgeReveal` 从 0 开始线性推进） | `EDGE_START_MS = WAVE_MS/2 :51`；`OverlayView.applyTimeline:1083-1090` |
| 590 | `edgeLit = true`；**边缘点亮增强**起（alpha 1 → 1.32 → 1，650ms） | `EDGE_MS:40`、`EDGE_IGNITE_MS:63`、`EDGE_IGNITE_BOOST:338`、`arrivalBoost:2118` |
| 1000 | **水波结束**（`ripple = 1`，GL 停止重绘）；**顶部光斑开始冒头**；**呼吸起振**（`breath()` 的 `dt = elapsed - WAVE_MS` 转正） | `WAVE_MS:31`、`TOP_BLOOM_START_MS:446`、`breath:1618-1619` |
| 1100 | **白线四边全亮**（波前铺满，`frontMaskFilled` 转真，此后纯色一次画完）；**线宽呼吸开始渐入** | `EDGE_START_MS+EDGE_REVEAL_MS`(`51+65`)、`frontMaskFilled:2004`、`lineSwingGate:1818` |
| 1400 | 顶部光斑长满（`appear = 1`） | `TOP_BLOOM_START_MS + TOP_BLOOM_IN_MS` |
| 1500 | **面板阶段开始**：scrim + 接收方信息 + 上滑提示开始浮现；**卡片**再晚 100ms；**背景模糊从 0 起**（500ms 到满）；**光斑开始换主色**（300ms 交叉淡入） | `PANEL_MS:74`、`CARD_DELAY_MS:65`、`PHOTO_BLUR_MS:95`、`TOP_BLOOM_TINT_AT_MS:507`、`TOP_BLOOM_TINT_MS:513` |
| 1600 | 卡片入场起点（缩 0.88→1 / 下移 22px→0 / 淡入 480ms） | `PANEL_MS + CARD_DELAY_MS`、`drawCard:2242-2248` |
| 1700 | 白线线宽呼吸渐入满幅 | `+LINE_SWING_IN_MS:174` |
| 1800 | 光斑换色完成（此后一直是照片主色） | `+TOP_BLOOM_TINT_MS` |
| 2000 | 背景模糊完成 | `+PHOTO_BLUR_MS` |
| 2050 | 面板就位（**`SETTLE_MS`，代码里没人读**） | `SETTLE_MS:83` |
| 2300 | `introFinished`（**无人读**）；此后帧间隔放宽到 33ms | `INTRO_MS:34`、`AMBIENT_FRAME_MS:106` |
| 常驻（永不结束） | 白线线宽果冻呼吸（周期 4800ms，一个周期抖 3 下）；顶部光斑独立相位呼吸（相位 0.31）；上滑提示 1500ms 循环浮动；背景保持模糊 | `LINE_BREATHE_PERIOD_MS:372`、`GLOW_BREATHE_WOBBLES:380`、`TOP_BLOOM_BREATHE_PHASE:551`、`drawSwipeTip:2344` |

### 附录 B：`Timeline.kt` 常量

| 常量 | 值 | 行 | 说明 |
|---|---|---|---|
| `WAVE_MS` | `1000L` | 31 | 水波扩散全程（GL 层）；**边缘白线不跟它走** |
| `INTRO_MS` | `2300L` | 34 | 入场段总长；只影响 `introFinished` 与限帧切换点 |
| `GLOW_MS` | `300L` | 37 | `glowing` 门（无人读） |
| `EDGE_MS` | `590L` | 40 | `edgeLit` 门 / 边缘点亮增强的起点 |
| `EDGE_START_MS` | `WAVE_MS/2 = 500L` | 51 | 白线起步（顶边中点） |
| `EDGE_REVEAL_MS` | `600f` | 65 | 白线 500→1100ms 铺满；**后续多个常量的基准** |
| `PANEL_MS` | `1500L` | 74 | 面板/卡片/模糊/换色的共同起点 |
| `SETTLE_MS` | `PANEL_MS+550 = 2050L` | 83 | 无人读 |
| `PANEL_FADE_MS` | `550f` | 86 | 面板浮现时长 |
| `PHOTO_BLUR_MS` | `500f` | 95 | 背景模糊 1500→2000ms |
| `RIPPLE_EASE` | `1.18` | 103 | 水波缓入指数（只 GL 层用） |
| `AMBIENT_FRAME_MS` | `33L` | 106 | 常驻段帧间隔（约 30fps） |

### 附录 C：`OverlayView.kt` 白线 / 辉光常量

| 常量 | 值 | 行 |
|---|---|---|
| `DESIGN_W` / `CARD_W` / `CARD_H` | `380f` / `292f` / `360f` | 59-61 |
| `LINE_INSET_PX` | `2.4f`（脊线离屏幕边缘；**同时是线宽上限的来源**） | 95 |
| `LINE_RIBBON_STEP_PX` | `6f` | 104 |
| `LINE_JITTER_BLOCKS` / `LINE_JITTER_PHASE` / `LINE_JITTER_AMP_FLOOR` | `10` / `0.08f` / `0.55f` | 113 / 119 / 131 |
| `SEED_LINE_PHASE` / `SEED_LINE_AMP` | `21` / `22` | 134-135 |
| `LINE_THIN_PX` / `LINE_SWING_PX` / `LINE_SWING_IN_MS` / `LINE_MIN_PX` | `2.6f` / `1.1f` / `600f` / `1.2f` | 147 / 165 / 174 / 183 |
| `LINE_ALPHA` | `0.96f`（恒定，不参与呼吸） | 197 |
| `GLOW_LAYERS` / `GLOW_NEAR` | `1` / `0` | 217-218 |
| `GLOW_DEPTH_PX` / `GLOW_EDGE_ALPHA` / `GLOW_PEAK_ALPHA` | `[12f]` / `[0.74f]` / `[0.53f]` | 239 / 253 / 263 |
| `GLOW_STOPS` / `GLOW_COLORS` | `[0, .22, .48, .74, 1]` / 5 档白（尾端冰蓝透明） | 277 / 292 |
| `GLOW_BREATHE_AMP` / `GLOW_BREATHE_PHASE` / `GLOW_BREATHE_TRADE` | **`[0]` / `[0]` / `[0]`（三条待打开的通道）** | 309 / 317 / 330 |
| `LINE_BREATHE_PERIOD_MS` / `GLOW_BREATHE_WOBBLES` / `GLOW_BREATHE_DECAY` | `4800f` / `3f` / `2.5f` | 372 / 380 / 389 |
| `GLOW_JITTER_BLOCK` / `GLOW_SLICE_PHASE_JITTER` / `GLOW_SLICE_AMP_JITTER` | `9` / `0.08f` / `0.9f` | 412 / 424 / 436 |
| `EDGE_IGNITE_MS` / `EDGE_IGNITE_BOOST` | `650f` / `0.32f` | 63 / 338 |
| `CORNER_SOFTEN` / `CORNER_SOFT_R` / `CORNER_QUIET_FRAC` | `1.4f` / `0.18f` / `0.18f` | 353 / 361 / 606 |
| `EDGE_CORNER_R_PX` / `SNAP_SEAM_TO_PX` / `GLOW_SLICES` | `22f` / `true` / `28` | 613 / 632 / 635 |
| `RIPPLE_RADIUS_SCALE` / `RIPPLE_REVEAL_NEAR` / `RIPPLE_REVEAL_SPAN` | `2.18f` / `0.10f` / `0.38f` | 662 / 664 / 678 |
| `FRONT_MASK_STOPS` / `FRONT_MASK_STEP_PX` | `7` / `6f` | 644 / 651 |

### 附录 D：顶部光斑 / 面板 / 卡片 / 接收按钮

| 常量 | 值 | 行 |
|---|---|---|
| `TOP_BLOOM_START_MS` | `EDGE_START_MS+500 = 1000L` | 446 |
| `TOP_BLOOM_IN_MS` | `400f` | 456 |
| `TOP_BLOOM_RX_FRAC` / `TOP_BLOOM_RY_PX` | `1f/3f` / `64f` | 463 / 473 |
| `TOP_BLOOM_PEAK_ALPHA` | `0.56f` | 476 |
| `TOP_BLOOM_COLORS` / `TOP_BLOOM_STOPS` | `0xE6DCE9FF / 0xC06FA9FF / 0x006FA9FF` / `[0, .30, 1]` | 487 / 494 |
| `TOP_BLOOM_TINT_AT_MS` / `TOP_BLOOM_TINT_MS` | `EDGE_START_MS+1000 = 1500L` / `300f` | 507 / 513 |
| `TOP_BLOOM_TINT_CENTER_MIX` / `MID_MIX` / `MIN_VALUE` | `0.26f` / `0.10f` / `0.68f` | 516 / 519 / 528 |
| `TOP_BLOOM_TINT_CENTER_ALPHA` / `MID_ALPHA` | `0xE6` / `0xC0` | 531 / 534 |
| `TOP_BLOOM_HEIGHT_SWING` / `TOP_BLOOM_BREATHE_PHASE` | `0.35f` / `0.31f` | 543 / 551 |
| `EDGE_IGNITE_MS`（面板相关）| 见附录 C | — |
| `PANEL_TEXT_MS` | `420f` | 64 |
| `CARD_DELAY_MS` / `CARD_ENTER_MS` / `CARD_FADE_MS` | `100f` / `680f` / `480f` | 65-67 |
| 卡片入场位移 / 投影 / 圆角 | `22f*k`(`2247`) / 5 层(`2271-2281`) / `23f*k`(`2267`) | — |
| `FLY_MS` / `RESET_DELAY_MS` | `460L` / `1750L` | 554 / 564 |
| 飞走上抛 / 终缩放 / alpha 归零点 | `-720f*k`(`2252`) / `0.72f`(`2253`) / `flyT/0.76`(`2254`) | — |
| `SEND_THRESHOLD_DP` / `PANEL_CHROME_DRAG_FOLLOW` | `85f` / `0.35f` | 567 / 575 |
| 回弹时长 | `260L` | 2637 |
| `RECEIVE_PRIMARY_H_PX` / `SECONDARY_H_PX` / `BTN_GAP_PX` / `BTN_R_PX` / `TAIL_GAP_PX` | `48f` / `40f` / `10f` / `14f` / `18f` | 685 / 688 / 691 / 694 / 703 |
| 主按钮色 / 按下 / 字色 | `0xFFEDF7FF` / `0xFFD7EAFB` / `0xFF111923` | 2430 / 2430 / 2447 |
| 次按钮色 / 按下 / 字色 | `0x14FFFFFF` / `0x29FFFFFF` / `0xFFDDDDDD` | 2437 / 2437 / 2449 |
| 按钮字号 / 圆角 | `15f*k`(`2446`) / `RECEIVE_BTN_R_PX*k`(`2425`) | — |
| `STATUS_BOTTOM_PX` / 状态行字号 / 颜色 | `46f` / `11f*k` / `0xFFB9C2CC` | 706 / 2470 / 2472 |
| scrim 三色 | `0x52070A10 / 0x5A080C14 / 0x6D080C14`（stops `0/0.48/1`） | 2141-2143 |

### 附录 E：GL 层常量（`RippleRenderer.kt`）

| 常量 | 值 | 行 |
|---|---|---|
| `RIPPLE_HEIGHT_FRAC` | `0.75f`（光带只推到 3/4 屏高；压暗仍走到底） | 278 |
| `RIPPLE_SLOW` | `1.7f`（半径减速指数） | 286 |
| `RIPPLE_BAND_THIN` / `RIPPLE_BAND_THICK` / `RIPPLE_BAND_FALLOFF` | `0.06f` / `0.24f` / `0.9f` | 298 / 299 / 302 |
| `RIPPLE_RING_HALF` | `0.26f` | 313 |
| `RIPPLE_RIDGE_K` | `10.4f`（波长 ≈ 0.604 屏宽） | 322 |
| `RIPPLE_DISP` / `RIPPLE_FAR_FALLOFF` | `0.075f` / `0.12f` | 331 / 334 |
| `RIPPLE_CORE_FALLOFF` / `RIPPLE_CORE_GAIN` | `2.5f` / `0.07f` | 342 / 368 |
| `RIPPLE_TINT_WARM` / `MID` / `COOL` | `(1.00,0.70,0.96)` 粉紫 / `(0.98,0.97,1.00)` 近白 / `(0.62,0.80,1.00)` 青蓝 | 360-362 |
| `RIPPLE_GLOW` / `RIPPLE_CREST_GAIN` | `0.30f` / `0.10f` | 365 / 378 |
| `RIPPLE_FADE0` / `RIPPLE_FADE1` | `0.7f` / `1.0f` | 386 / 387 |
| 着色器内硬编码 | 压暗最深 `0.45`、暗色 `vec3(0.018,0.026,0.04)`、`darkR = u_progress*(aspect+0.17)`、过渡带 `-0.10/+0.17` | 475-478 |

### 附录 F：图像派生常量

| 常量 | 值 | 行 |
|---|---|---|
| `PhotoTint.SAMPLE / MIN_ALPHA / DARK_CUTOFF / BINS` | `32` / `32` / `32` / `16³` | `PhotoTint.kt:18/21/24/27` |
| `BackdropBlur.SCALE_DIV / BOX_RADIUS / PASSES / MIN_SIDE` | `4` / `6` / `2` / `8` | `BackdropBlur.kt:26/29/32/35` |
| `ImageLoader.MAX_DECODE_SIDE / BASE_COLOR` | `1536` / `0xFF050608` | `ImageLoader.kt:39/42` |
| `OverlayService.BASE_DARK`（无照片时的折射底色） | `0xFF050608` | `OverlayService.kt:1004` |
| `OverlayService.REPLAY_DELAY_MS`（通知"重播"等待） | `5000L` | `OverlayService.kt:1017` |
| 自动演示延迟（挂窗后） | `420L` | `OverlayService.kt:587` |
| `OverlayService.NOTIFICATION_ID` / `CHANNEL_ID` | `0x4F50` / `"touch_anime_overlay"` | `OverlayService.kt:1001/1000` |

---

## 附：一句话给"要对齐新效果"的人

1. **先查 §2 主表**——现有 23 项里，绝大多数"氛围类"（水波、光带、边缘光、顶部光斑、面板浮现、卡片入场/飞走）都已经做好且参数化得很细，改常量就能调手感，**不要重画**。
2. **要挂时间** → 只动 §3.1 的两处：`Timeline.companion` 加刻度 + `OverlayService.kt:509-516` 加一路（或 `OverlayView` 里按 `elapsed` 算）。
3. **要画新东西** → 一律留在 Canvas 层，复用 §3.3 的 Paint/helper（尤其 `breath()`、`smoothStep()`、`withAlpha()`、`drawCenteredLabel()`、`layoutReceiveActions()` 的"绘制=命中"模式）。
4. **要整屏氛围** → 才动 GLSL，且务必真机验证（编译失败整层会静默变空）。
5. **要粒子** → 只有 `ParticleView.java`（§4.2）这一条路，且它现在真的不存在。
