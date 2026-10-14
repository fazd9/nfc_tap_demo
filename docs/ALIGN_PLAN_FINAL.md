# 碰一碰传图 · 对齐 OPPO 互传【最终实施方案】

> 对象：`/dfb/zhuyang/share/nfc_test/pri_project/android/pri_app/nfc_tap_demo`（分支 `nfc_tap`，提交 `057b617`），包名 `com.pri.nfctouchdemo`。
> 平台签名系统应用（`sharedUserId="android.uid.system"`，装到 `/system/app/PriNfcTap`，真机已验证）—— **签名 / system uid 不是约束**。
> 本文**只出方案，不落代码**。所有结论都带 `文件:行号`，行号按当前工作区逐个复核过。
>
> ### 本文档的地位（与既有四份文档的关系）
>
> | 文档 | 关系 |
> |---|---|
> | `docs/APP_FLOW_ANALYSIS.md` | **基线，未被取代**。本文引用它的现状结论，不改它。 |
> | `docs/PRODUCT_VIDEO_ANALYSIS.md` | **§3 时间轴、§4.2、§5、§6 四处被本文修正**（见 §2.2：接收方点「接收」后不是"两个胶囊整体消失"，而是先进「接收中」+ 单枚「取消」态）。其余仍然有效。 |
> | `docs/ALIGN_CHANGELIST_INTERACTION.md` | **被本文取代**：接口命名以 §4 为准；A/B/C/D/E 各项仍有效，但触发时机与状态机按 §3 的两阶段协议重写；其「取舍 #4/#5/#9」被 D4/D1/D2 推翻；其「接口 #4」的原缺口由本文 §5.2 接管。 |
> | `docs/ALIGN_CHANGELIST_LINK.md` | **被本文取代**：接口命名以 §4 为准；A-2 的"同一条 socket 回执"选型与理由**保留**；A-4「接收方只写不关」保留为**原则**；A-5b/A-6/A-7/E-1/E-2 **保留**；**B-2（排空丢帧）、B-3（回滚已入库照片）、A-5 的"发送方传完不关"全部取消**（两阶段协议下不存在"先传后问"，见 §6）；其「取舍 #4」被两阶段协议直接解决。 |
>
> **入口问题（碰一碰挂到图库/查看器上）不在本文范围**，另行决定；本文所有改动都不假设入口形态。

---

## 1. 目标与范围

### 1.1 目标

把「一碰互联」的**流程与交互**对齐参考产品 OPPO 互传：

1. **对等而非主从**：碰触后"正在看图"的手机都亮出发送面板；**谁先上滑谁成为发送方**，另一台原地变成接收方（视频 f_0046 → f_0051）。
2. **两方各确认一次**：发送方上滑（滑动语义）+ 接收方点按钮（点按语义），缺一不可。
3. **先报价、后交货**：接收方点头之前，**整图一个字节都不发出**（这是本文的核心，见 §3）。
4. **成功安静、失败高调**：发送成功无任何提示；被拒/超时在**发送方顶部**出红色横幅。
5. **接收完成后打开系统查看器**（D4），不再用自绘悬浮查看器。

### 1.2 范围

| 层 | 文件 | 本文档是否覆盖 |
|---|---|---|
| 链路层 | `nfc/Xfer.java`、`nfc/Bus.java` | 是（协议、回执通道、完成判定） |
| 胶水层 | `nfc/TapService.java` | 是（角色状态机、收尾、查看器） |
| UI 层 | `service/OverlayService.kt`、`ui/OverlayView.kt`、`res/values/strings.xml` | 是（接收中态、接收卡时机、失败横幅、就地换身份） |
| 旧悬浮窗 | `nfc/Overlay.java`、`nfc/Ui.java` | **只摘除主流程的用法**；联调页用法保留（见 §5.6） |
| 联调页 | `nfc/MainActivity.java` | 只做**编译同步**（改名 `onImageCount`、删 `sendPreviews` 调用）；不参与产品流程 |

> **动效范围补充（2026-10-14，用户已逐条拍板）—— 实现细节见 `docs/ANIM_IMPL_SPEC.md`：**
> 1. **E1（碰触瞬间的方位性彩虹弧光晕）不做** —— 跳过。
> 2. **入场全保留**：页面级入场（GL 水波折射 + 四边白线 + 边缘辉光 + 顶部光斑）**以及"面板浮现"本身**（scrim + 信息行 + 照片卡片 + 上滑提示 / 接收按钮）**发送页与接收页都照旧**。视频的 E2/E3 是"无位移交叉淡入 / 就地白雾显影"，我方是"上浮 8px + 卡片缩放下移入场"——用户看过两者差异后选择**保留我方实现**。**这是刻意偏离，不是待修缺口**：`OverlayView.slide`（`:2135`）、`CARD_ENTER_MS`、卡片入场缩放/位移都不要"顺手修掉"。
> 3. **按压反馈保留**：视频的按钮没有任何按下反馈，我方保留自己的按下态（用户明确要求）；E4 的「取消」胶囊同样带按下态。
> 4. 页面出现**之后**的动效按 `ANIM_IMPL_SPEC.md` 对齐：**E4** 接收中「取消」/ **E5** 整卡消散 / **E6** 背景虚化回落 / **E7** 发送白化 + 粒子迸发 / **E8** 失败横幅。
> 5. 连带作废/修订：本文 **§1.3「不做粒子迸发」作废**（粒子已进范围，走恢复 `ParticleView.java`）；**§1.4** 的规模以该文 §3 总账为准（未计入 E5/E6/E7）；**§5.3 C-4** 的"接收中"结论仍有效，但取消胶囊落在**「拒绝」那一行**、尺寸/底色按该文 §2.1 执行（与 C-4-2 的"只算主按钮矩形"表述不同，以该文为准）；**§5.4 方案 A（独立小窗）**仍有效，并已在该文 §2.5 补齐窗口参数与收摊点。

### 1.3 不做什么

- 不做视频 f_0049 的**粒子迸发**：复用现有的卡片飞走动画（`OverlayView.startFlyAway`，`ui/OverlayView.kt:2648-2669`）。用户已明确"复用现有动效优先"。
- 不做 OPPO 首次启动的**用户须知 / 权限弹窗**（视频 t=10.5–17.0s）：那是参考产品自己的首次授权流程，与本 app 的碰一碰路径无关。文案见 §2.4 仅供留档。
- 不动 NFC 握手（APDU / payload 结构 / 角色锁），`Bus.java` 只改 1 处日志（§5.7）。
- 不动入口形态、不动 `PENDING` 清空策略（`TapService.java:305-306`，归属"入口"方案）。

### 1.4 复用 vs 新绘制（用户最关心的一条，先给总账）

| # | 项 | 复用 / 新增 | 量级 |
|---|---|---|---|
| 1 | 接收页时机、卡片内容、按钮 | **全复用**（`receiveIntent` / `startReceiveFlow` / `drawReceiveActions` / `layoutReceiveActions`） | 0 新绘制 |
| 2 | 败方"原地换身份" | **全复用**（`OverlayView.setMode` / `setPeerInfo` 是活体切换，`ui/OverlayView.kt:764-790`）+ `setMode` 补 1 行 | 0 新绘制 |
| 3 | 文案与张数（对端名、N 张） | **全复用**（`strings.xml:21-25` 五条带参文案早就在，只是被 `sendTextsFromPeer=false` 关掉） | 0 新绘制 |
| 4 | 发送方卡片飞走、窗口收尾 | **全复用**（`startFlyAway` + `onSendFinished → hideDemo`） | 0 新绘制 |
| 5 | **接收中「取消」态** | **复用**按钮几何与文字绘制，新增一个分支 | **约 10 行**，0 新 Paint |
| 6 | **失败横幅**（D1） | **零 Canvas 新绘制**：用控件搭（复用 `Ui.rounded`）+ 独立小窗 | 约 40 行布局 + 约 60 行窗口/交互 |
| 7 | 底部品牌行「一键互联」 | 可选，Canvas 新增 | 约 8 行（可延后） |

**结论：真正必须新增的视觉只有「接收中·取消」一个小分支和一条失败横幅；横幅走控件路线，可以完全不碰 `OverlayView` 的绘制代码。**

---

## 2. 参考产品的最终流程（含「取消」状态修正）

### 2.1 一轮完整流程（对齐视频第一轮成功路径）

```
[0] 两台手机顶边相贴
      · 两台都亮「发送给 <对端>」面板（前提：本机正在看图 / 有照片）
      · 没照片的那台**什么都不显示**（视频 f_0009–f_0013，接收机 4 秒内毫无变化）
[1] 其中一台（X）在照片上**上滑** —— 这一步才宣告"我是发送方"
[2] 另一台（Y）在 ~0.5s 内弹出接收卡：「来自 <X> 的分享」+ 照片预览
      + 白色胶囊「接收 N 张照片」+ 深灰胶囊「拒绝」（视频 f_0014 → f_0015）
      ⚠️ Y 若本来也亮着发送面板：**同一台手机原地**换成接收卡，不重播、不重启
[3] Y 点「接收」→ 两个胶囊消失，换成**单枚「取消」胶囊**（接收中态，视频 t≈9.6–10.0s）
[4] 传完 → 整张卡片消散，回到 Y 原来的界面（视频 t≈10.2–10.4s）
      · 本方案：D4 —— 接着用 ACTION_VIEW 打开系统查看器看刚存的那张
[5] 成功**完全静默**（X 侧无任何提示）
替代路径：
[3'] Y 点「拒绝」→ Y 的卡片收起，回到原界面；
      X 顶部出现红色横幅「发送失败 / 对方拒收」（≤0.5s，视频 f_0055）
[3''] Y 一直不表态 → 60s 后（D2）X 出横幅「发送失败 / 对方未响应」，两边都收摊
```

### 2.2 ⚠️ 修正：接收后不是"胶囊整体消失"，而是先有「接收中」态

**`docs/PRODUCT_VIDEO_ANALYSIS.md` §4.2 的表格里写着「接收后：两个胶囊整体消失，只留照片」，这条是错的**，本次用 10fps 密采 + 6 倍放大（`_Temp/viewer/`、`_Temp/viewer2/`、`_Temp/viewer3/`）复核后修正如下：

| 时刻 | 帧 | 实际画面 | 证据 |
|---|---|---|---|
| t≈9.6s | `_Temp/viewer3/A_96.jpg` | 接收卡还在，但按钮区正在**从两枚胶囊过渡到一枚**（过渡中间态，图还在） | 画面可辨 |
| **t≈10.0s** | `_Temp/viewer3/A_100.jpg`、`_Temp/viewer2/pill_100.jpg` | 卡片完整保留（标题「来自 …的分享」+ 照片），按钮区只剩**一枚占满卡片宽的半透明胶囊**，居中白字「**取消**」 | 6x 放大可读出「取消」 |
| t≈10.4s | `_Temp/viewer3/A_104.jpg` | 整卡消散，露出（虚化的）桌面 | 画面可辨 |

**修正结论**：接收方点「接收」之后存在一个**新 UI 状态 —— 「接收中」：卡片与照片保留，两枚胶囊（接收/拒绝）被一枚「取消」替代**；传完（或取消）之后整卡才消散。
`PRODUCT_VIDEO_ANALYSIS.md` 需要同步改的地方：§3 时间轴中 `f_0020 (t=9.5s)`「两个胶囊消失，仅保留照片」一行、§4.2 表格「接收后」一行、§5 动效表「按钮消失」一行、§6「完成态（成功）：接收方侧表现为'按钮消失、照片留下'」一句。

**为什么必须做**：这是"两方各确认一次"之后唯一的过程态，也是"接收方在传输期间仍有出口"的设计。我们的接收按钮语义（点一下就开始收）与它天然吻合 —— 点「接收」= 回执 ACCEPT，接着就进「接收中」，传输期间显示「取消」。

**「取消」的语义（视频没拍到点了会怎样，本文给出定义）**：取消 = 放弃本轮接收。基线实现（无新帧）：接收方本地停读、丢弃本轮、关连接、卡片收起；发送方在"已收到 ACCEPT 之后的写异常"上映射为横幅「发送失败 / 对方已取消」。
> 建议文案二选一，**默认沿用「对方拒收」**（零新增文案、零新增分支）；若要区分，新增 detail 文案即可（发送方在整图阶段抛异常时用的就是它）。**这一条需要用户拍板**。

### 2.3 第二轮：对等模型（视频 f_0046）

第二轮里两台手机**同时**亮着「发送给 <对方>」面板（A 显示「发送给 一路有云」、B 显示「发送给 U119652157」），A 上滑后 B 原地切成「来自 U119652157 的分享」。
本文的两阶段协议让这件事**自动成立**（见 §3.4）：先上滑的那台先发 `TYPE_META`，对端一收到 `TYPE_META` 就知道自己是接收方，就地换身份 —— **不需要额外的"角色改判"广播帧**（这条推翻了 `ALIGN_CHANGELIST_LINK.md` 取舍 #5 里"需要先广播一条 BEGIN"的建议）。

### 2.4 留档：「用户须知」正文（肉眼转录，低置信度）

视频 t=11.2s（`_Temp/viewer3/A_112.jpg`，放大后底部 6 行小字）：

```
"OPPO 互传"需要获取以下权限以提供服务：
获取位置信息以连接设备，联网以获取账号信息，打开和关闭 WLAN 和蓝牙以传输文件，
访问照片、视频、音频等媒体及其他文件以管理相关内容，发送通知以提示文件传输进度和
结果，读取通讯录以识别联系人互传服务。
…（"退出" / "同意并使用" 两个按钮）
```
> 分辨率有限，只能作参考，**不要照抄进代码**（本方案不做这个弹窗，见 §1.3）。

---

## 3. 两阶段协议（本文中心，链路层 + 胶水层一起改）

### 3.1 协议定义：先报价、后交货

| 阶段 | 谁 | 动作 | 线上字节 |
|---|---|---|---|
| **0 碰触** | 两端 | 交换 payload（现有 NFC 流程，不动） | APDU（`Bus`） |
| **1 报价** | 发送方 X（上滑触发） | 连到 Y 的 8988，写 `META(N)` + `PREVIEW(第 1 张缩略图)`，然后**阻塞等回执** | `TYPE_META(3)` + `TYPE_PREVIEW(1)` |
| **2 表态** | 接收方 Y | 收到 `META` → 弹接收卡（张数/预览都是真的）→ 用户点「接收」/「拒绝」→ 写回一帧 | `TYPE_ACCEPT(4)` / `TYPE_REJECT(5)`，`LEN=0` |
| **3 交货** | 发送方 X | 只收到 `ACCEPT` 才写整图；写满 `N` 张 `flush` 后**关闭连接** | `TYPE_FULL(2) × N` |
| **4 落地** | 接收方 Y | 每张解码 + 入库；`count == expect` 即完成 → 卡片消散 → D4 打开系统查看器 | — |

**关键点**
- 帧格式完全复用（`Xfer.java:53-56` 的 `MAGIC/TYPE_*` 家族）：`[MAGIC:int][TYPE:int][LEN:int][LEN 字节]`，新增的 `TYPE_ACCEPT/TYPE_REJECT` 是 `LEN=0`。
- 方向不冲突：X→Y 是 `META/PREVIEW/FULL`，Y→X 是 `ACCEPT/REJECT`；TCP 全双工，两个独立字节流。`mWriteLock`（`Xfer.java:50`）在本端只有一个写者，回执路径不必加锁。
- **`TYPE_META` 同时承担三件事**：①"发送方已上滑"的信号（`ALIGN_CHANGELIST_LINK.md` C-2 的结论依然成立：`TYPE_META` 只在 `sendImages` 里写、`sendImages` 只被上滑触发）；②对端总张数；③角色改判（谁先发 META 谁赢）。

### 3.2 ASCII 时序图（成功路径）

```
  X(发送方,先上滑)                                  Y(接收方 / 后上滑者)
 ────────────────────────────────────────────────────────────────────────────────
 [碰触] 交换 payload                     ←────────────→   交换 payload
   TapService.onPeer:                                      TapService.onPeer:
     setExpectedPeer + startAsReceiver(8988 监听)            setExpectedPeer + startAsReceiver(8988 监听)
     + startHotspot()   ← §5.2(a) 两端都做接收准备           + startHotspot()
     有照片 → showSendPage()【发送面板亮】                   有照片 → showSendPage()【发送面板亮】
     (无照片 → 什么都不显示,只等)                            (无照片 → 什么都不显示,只等)
 ────────────────────────────────────────────────────────────────────────────────
 [X 上滑] mUserWantsSend=true; startAsSender() → ①热点/②同网/③P2P
   连上 Y:8988 ─────────────────────────────►  Y 的 accept 循环
     (mReceiverRole==true → handleIncoming)      mReceiverRole==true → handleIncoming(s) ★
   ┌─ META(N) ────────────────────────────────►  onSendStarted(N)
   │                                              · 本机已上滑过? → 忽略(双上滑竞态)
   │                                              · 有面板 → 原地 switchSendPageToReceive()
   │                                              · 无面板 → showReceivePage(label, N)
   │                                                 → 接收卡出现,按钮「接收 N 张照片」
   └─ PREVIEW(第1张,900px/q82) ───────────────►  onPreview → previewUriOrNull() → photoIntent
                                                   → 卡片里立刻出现对方那张照片(视频 f_0015)
   readVerdict(s) 阻塞 ≤ VERDICT_WAIT_MS(60s)  ←── 用户点「接收」/「拒绝」
   ▲                                              ACTION_UI_RECEIVE_ACCEPT / _REJECT
   │                                              ACCEPT → confirmReceived() → 写 TYPE_ACCEPT
   │                                              REJECT → rejectTransfer()  → 写 TYPE_REJECT
   │                                                       + hideIntent()(卡片收起)
   ├──────────── ACCEPT ────────────────────────┘
   │  onPeerAccepted() (仅日志,成功静默)
   │  写 FULL × N  s.flush()  s.close()
   │  ─────────────────────────────────────►  每张: decode + saveToGallery → onImageReceived
   │                                             count == expect → onTransferDone
   │  releaseHotspotLater() (原 5s 延迟,挪到事务之后)
   │  onImageSent(bytes) → PENDING 收尾 / 解锁
   └─(或) REJECT ──────────────────────────────┘
       onPeerRejected() → 横幅「发送失败 / 对方拒收」(D1)
   └─(或) 60s 超时 / 对方断开
       onVerdictTimeout() → 横幅「发送失败 / 对方未响应」(D2)
       Y 侧: 读循环 EOF 且 count==0 → onOfferExpired() → 卡片收起(不让用户被卡住)
 ────────────────────────────────────────────────────────────────────────────────
 ★ 这一行是 §5.2 那个 unowned blocker 的要害：今天两端都有照片时,两边都会
   startAsSender() → mReceiverRole 被置 false → accept 循环走 else 分支把 socket
   存进 mPendingSocket(Xfer.java:683) → **对方发来的数据永远没人读**。
 ────────────────────────────────────────────────────────────────────────────────
```

### 3.3 协议带来的两个"问题自动消失"

| 老问题（两份 changelist 都在解） | 两阶段协议下 |
|---|---|
| 「拒绝要能打断传输」（`ALIGN_CHANGELIST_LINK.md` B-2 排空丢帧） | **不存在**：拒绝发生在整图发出**之前**，没有传输要打断。 |
| 「拒绝后要回滚已入库的 MediaStore 记录」（B-3 `deleteRoundSaved`） | **不存在**：同意之前没有落盘，拒绝时相册里干干净净。 |
| 「发送方传完不关、长期持有连接等回执」（A-5） | **不需要**：表态在交货之前，交货后即可关闭。 |

**要连字节层的时机也对齐了**：`ALIGN_CHANGELIST_LINK.md` 取舍 #4 说的"最大的语义差异"（本应用是上滑就把整图推过去再问要不要）被本协议**直接消灭**。

### 3.4 角色仲裁：谁先上滑谁赢（不需要新帧）

- 碰触后两端都做"可能接收"的准备（`startAsReceiver` 已在 `TapService.java:179` 无条件调用），`mReceiverRole == true`。
- X 上滑 → 只有它的 `mUserWantsSend = true` → 只有它调 `startAsSender()` → 只有它发 `META`。
- Y 收到 `META` 的那一刻：**Y 就是接收方**（`onSendStarted` 里 `if (mUserWantsSend) return;` 保证已上滑的那台不会被自己的对端 META 拉回来）。
- 剩下唯一没被视频覆盖的情形是**双方几乎同时上滑**（`PRODUCT_VIDEO_ANALYSIS.md` §9.10 已声明拍不到）：两边都发了 `META`，退化行为是两边都等不到回执 → 60s 后两边各出一张「对方未响应」。**这是可接受的降级，列为真机验证项**（§10-A）。

---

## 4. 统一接口契约表（唯一事实来源）

> 两份 changelist 在这里互相冲突（`onSendRejected` vs `onPeerRejected`、`rejectIncoming` vs 一对方法、`ACTION_FAILURE` vs `ACTION_FAIL_BANNER`、`onImageCount` vs `onSendStarted`、`mSending` vs 两个标志）。**以下表为准，实现时只看这张表。**

### 4.1 `Xfer.Listener` 回调

| 回调 | 方向 | 触发点（改动后） | 语义 / UI 要做什么 | 相对旧接口 |
|---|---|---|---|---|
| `onSendStarted(int total)` | 接收方 | 读到 `TYPE_META`（投递点仍为 `Xfer.java:741-743`） | **此刻才弹接收卡**；按钮文案用真实 `total`。**发送方不需要这个回调**（它自己发起的） | **改名**自 `onImageCount` |
| `onPreview(Bitmap)` | 接收方 | 读到 `TYPE_PREVIEW`（`Xfer.java:749-754`） | 换卡片照片（不变） | 不变 |
| `onImageReceived(int index, int total, Bitmap, int bytes, boolean saved)` | 接收方 | 每张整图解码入库后（`Xfer.java:760-770`） | 刷状态行（不变） | 不变 |
| `onTransferDone(List<Bitmap>)` | 接收方 | **`count == expect`** 时（不再等 EOF）+ EOF 兜底 | 卡片消散 + D4 打开系统查看器 | 触发源变了 |
| **`onOfferExpired()`** | 接收方 | 读循环退出时 `count==0` 且没表过态（对方超时/断开） | 收起卡片、解锁角色，别把用户卡在整屏窗口里 | **新增** |
| `onPeerRejected()` | 发送方 | `readVerdict` 读到 `TYPE_REJECT` | **弹常驻红色横幅「发送失败 / 对方拒收」**；不补弹发送页、不写状态行 | 取代 `onSendRejected` |
| `onPeerAccepted()` | 发送方 | `readVerdict` 读到 `TYPE_ACCEPT` | **仅 `Bus.log`**，成功保持静默（链路层内部用它进入交货阶段） | 新增（`ALIGN_CHANGELIST_LINK.md` D-3 保留） |
| **`onVerdictTimeout()`** | 发送方 | `readVerdict` 超时 / 对方在读回执前断开 | 横幅「发送失败 / **对方未响应**」（D2，与"拒收"区分） | **新增** |
| `onXferFailed(String why)` | 发送方 | 链路异常（连不上、写失败） | 不变（语义仍是"链路失败"，**不含拒收/超时**） | 不变 |

### 4.2 `Xfer` 公开 API（接收方调用）

| API | 调用点 | 做什么 |
|---|---|---|
| `confirmReceived()` | `TapService` `ACTION_UI_RECEIVE_ACCEPT` 分支（`TapService.java:114-126`）**开头**加一行 | 写回 `TYPE_ACCEPT`（只发一次），随后 UI 进「接收中」态 |
| `rejectTransfer()` | `TapService` `ACTION_UI_RECEIVE_REJECT` 分支（`TapService.java:127-141`）**最前面**加一行 | 写回 `TYPE_REJECT` + 标记本轮不再落地；本地清理不变 |
| `firstSavedUri()` | `TapService.onTransferDone` 里取（D4） | 返回本轮第一张已入库的 MediaStore Uri（没有则 null） |
| `releaseHotspotIfUnused()`（可选，二级） | `TapService` 上滑分支 | 见 §9-风险 (4) |

> `ALIGN_CHANGELIST_INTERACTION.md` 的单个 `rejectIncoming()` **被 `rejectTransfer()` + `confirmReceived()` 这一对取代**；`mSending` 标志**被 `mUserWantsSend` + `mSendStarted` 取代**（见 §5.3）。

### 4.3 UI 层指令（`OverlayService`）

| 指令 | 用途 | 相对旧接口 |
|---|---|---|
| `failBannerIntent(ctx, title, detail)` / `ACTION_FAIL_BANNER` | 显示失败横幅（D1） | 取代 `ACTION_FAILURE` / `failureIntent` |
| `receiveIntent(ctx, peer, count, preview)` | 弹接收卡（时机改到 `onSendStarted`，`count` 传真实张数） | 不变 |
| `peerIntent(ctx, peer, count)` | 就地补正张数（兜底网，幂等） | 不变 |
| **（新状态）接收中** | 不需要新 action：新增一条 `ACTION_UI_RECEIVE_IN_PROGRESS`？**不需要** —— 见下 | 新增状态、不加 action |

> **「接收中」态不新增 action**：它由 `ACTION_UI_RECEIVE_ACCEPT` 直接触发（`updateAcceptInProgress()`），因为"用户点了接收"和"进入接收中"是同一件事。UI 侧只需在 `OverlayView` 里加一个 `mode` 内的子状态位。
> 也**不要**用闲置的 `ACTION_PEER`/`peerIntent`（`OverlayService.kt:983-987`）承担换身份：它的契约是"只补正对端/张数，别的都不动"（`OverlayService.kt:305-309`），用它还会多一条指令。

### 4.4 改名波及清单（一次改完，否则编译不过）

| 文件:行 | 内容 |
|---|---|
| `nfc/Xfer.java:42` | `void onImageCount(int total);` → `void onSendStarted(int total);` |
| `nfc/Xfer.java:74-76` | 转发器 override |
| `nfc/Xfer.java:741-743` | 投递点 |
| `nfc/TapService.java:254` | 实现方（同时重写成"弹接收卡"） |
| `nfc/MainActivity.java:504` | 死监听器（`APP_FLOW_ANALYSIS.md` §8.2-9），只为编译同步 |
| `service/OverlayService.kt:864` | 一句注释（`ACTION_PEER` 的文档串），纯文字 |

---

## 5. 逐项改动

> 每项格式：**现状（file:line）→ 目标 → 改法 → 复用/新增 → 风险**。
> 标注：【复用】= 不改绘制；【新绘制】= 需要新画东西。

### 5.1 链路层 A：两阶段会话（`nfc/Xfer.java`）

**【复用现有帧格式与线程模型】**

**现状**
- `sendImages()`（`Xfer.java:797-884`）：取 socket（`:801-831`）→ 预读线程（`:838-849`）→ `synchronized(mWriteLock)` 里写 `META` + `FULL×N`（`:850-866`）→ `flush` → `s.close()` + `mPendingSocket=null`（`:865-868`）→ 5s 后 `releaseNetworkRequest()`（`:870-874`）→ `onImageSent`（`:876-878`）。**全程不读 socket**。
- `handleIncoming()`（`Xfer.java:716-794`）：读循环靠 `EOF` 判定收完（`:727-730`），退出后在 `finally` 里 `close()`（`:791-793`），**从不写**。全文件 `getOutputStream()` 只出现在 `:832`、`:906`（都是发送方路径）。
- 验收：`APP_FLOW_ANALYSIS.md` §4.6 与 `ALIGN_CHANGELIST_LINK.md` A-1 的表格一致。

**目标**：应用 §3.1 的两阶段协议。

**改法（按改动点列）**
1. **新增 2 个帧常量**（接在 `Xfer.java:53-56` 后面）：
   ```java
   private static final int TYPE_ACCEPT = 4;   // 接收方回执:同意接收(len=0)
   private static final int TYPE_REJECT = 5;   // 接收方回执:拒绝接收(len=0)
   private static final int VERDICT_WAIT_MS = 60000;  // D2:发送方等回执上限
   ```
2. **发送方 `sendImages` 拆两段**（改造 `Xfer.java:850-878` 那段）：
   ```
   取 socket（:801-831 原样复用）
   写 META(uris.size()) + PREVIEW(thumbnail(uris.get(0)))   ← 报价,在 mWriteLock 里,flush
   int verdict = readVerdict(s)                              ← 阻塞 ≤60s,返回前不关
   if (verdict != ACCEPT) { 关闭 + releaseHotspotLater() + 对应回调; return; }
   写 FULL × N + flush                                        ← 交货(原 :854-864 原样搬过来)
   s.close(); mPendingSocket = null;
   releaseHotspotLater();  onImageSent(total)
   ```
   - `readVerdict(Socket s)`（新增私有方法，参考 `ALIGN_CHANGELIST_LINK.md` A-5 的骨架）：`s.setSoTimeout(VERDICT_WAIT_MS)` → 读一帧 `MAGIC/TYPE/LEN` → 校验 magic 与轮次令牌 → 返回 `ACCEPT/REJECT/NONE`；`finally { s.close(); }`。
   - **顺序硬约束**：`onImageSent` 必须**先于**任何回执回调（同一条线程两次 `mMain.post`，主线程 Handler FIFO），否则发送页的收尾会盖在横幅上。
   - 预读线程（`:838-849`）保留原样（它与交货阶段并行）；`thumbnail()`（`:929-950`）原样复用。
3. **`sendPreviews` 删除**（`Xfer.java:887-950`）：预览已并入报价阶段；调用方 `TapService.java:238`、`MainActivity.java:492` 一起删（后者是编译所需的孤儿调用）。**只推 1 张**（接收卡只消费"最后一张预览"：`TapService.java:244-252, 369-386`），避免为每张图都解一次原图（0.2–0.4s）把报价拖慢。
4. **接收方 `handleIncoming`**：
   - 进循环前：`mPeerSocket = s; mVerdictSent = false; mDoneNotified = false; mExpect = expect;`
   - `finally` 里 `close()` **之前**：`if (mPeerSocket == s) mPeerSocket = null;`（回执要写回这条连接）
   - **完成判定从 EOF 改/补成 `count == expect`**（`ALIGN_CHANGELIST_LINK.md` A-5b）：每张 `count++` 之后（`Xfer.java:756` 后）判 `count >= expect && !mDoneNotified` → `enqueueTransferDone()`。**收满不 break**（继续阻塞，等对方关闭或超时）。
   - 循环退出后：`if (count > 0 && !mDoneNotified)` → 兜底 `enqueueTransferDone()`（保持"挂在 `mIo` 单线程队列尾"的既有顺序保证，`Xfer.java:774-783`）；`if (count == 0 && !mVerdictSent)` → `onOfferExpired()`。
   - `onTransferDone` 要 `!mDoneNotified` 去重（否则 `count==expect` 与 EOF 会各投一次）。
   - `expect` 必须**在进循环前**从 `TYPE_META` 里拿到 —— 现成（`Xfer.java:737-744`）。
5. **回执写回**（新增私有）：
   ```java
   public void confirmReceived() { writeVerdict(TYPE_ACCEPT); }   // 接收方点「接收」
   public void rejectTransfer()  { writeVerdict(TYPE_REJECT); }   // 接收方点「拒绝」
   private void writeVerdict(int type) { /* mVerdictSent 只发一次; mPeerSocket 为 null/closed 只记日志 */ }
   ```
6. **轮次令牌**（`ALIGN_CHANGELIST_LINK.md` A-7 保留）：`private volatile int mRound;`，`resetRoundState()`（`Xfer.java:195-213`）里 `mRound++` 并顺带清 `mPeerSocket/mVerdictSent/mDoneNotified/mExpect`；`sendImages` 记下当轮值，`readVerdict` 比对，不等就丢弃（防"上一轮被挂起的回执把横幅盖到新一轮的发送页上"）。
7. **热点释放后移**（A-6 保留）：`Xfer.java:870-874` 的 5s `releaseNetworkRequest()` 从"flush 之后"挪到**整个事务之后**（`readVerdict` 返回之后、或交货完成之后）。理由：走 ① 热点通道时发送方是对方热点的 STA，提前释放会拆掉这条连接 —— 而连接要活到交货完成。
8. **`saveToGallery` 改返回 `Uri`**（`Xfer.java:1011-1032`）：`boolean` → `Uri`（成功返回 insert 出来的 Uri，失败/`API<29` 返回 null）；字段 `private volatile Uri mFirstSavedUri;`（本轮第一张；`resetRoundState` 清空）+ `public Uri firstSavedUri()`。D4 依赖它。
9. **`addListener` 去重（必做，1 行）**：`Xfer.java:91-93` → `if (l != null && !mAll.contains(l)) mAll.add(l);`。现状 `TapService.java:73` 每次 `onStartCommand` 都注册，一次碰触后每轮至少 2 份回调（`APP_FLOW_ANALYSIS.md` §8.2-10）→ `onTransferDone` 重复投递会开出两个查看器窗口。`mAll` 已是 `CopyOnWriteArrayList`（`Xfer.java:59-60`），0 新成员。**不新增 `removeListener`**（全仓无调用方）。
10. **通道准备与角色解耦（新增，供 §5.2 用）**：`startAsSender()` 保持原样（幂等早退在 `Xfer.java:385-388`），但**调用时机从"碰触"挪到"上滑"**。`startAsReceiver()`（`:340-374`）保持无条件调用。`startHotspot()`（`:216-289`）保持原样但由 `TapService` 无条件调用（见 §5.2）。
11. **可选（二级）**：`public void releaseHotspotIfUnused()` —— 上滑成为发送方时，若 `!mGotConnection`（没人连过我们）就关掉自己那个没人用的热点。理由见 §9 风险 (4)。

**复用 vs 新增**：复用同一条已建立的 TCP 连接（三个通道一致，无需新寻址假设 —— 这是选"同 socket 回执"而不是"反连"的主要理由，`ALIGN_CHANGELIST_LINK.md` A-2 保留）、复用 `MAGIC/TYPE_*` 帧格式、`mWriteLock`、`mIo` 单线程队列、`thumbnail()`、`mMain` 回调范式；新增 3 个常量、3 个回调、3 个公开方法、约 5 个字段、1 个 `readVerdict()`；**删除** 1 个公开方法（`sendPreviews`）+ 1 个字段（`TapService.mPreviewsSent`）+ 1 个独立线程模型。
**风险**：见 §9 全部条目。

---

### 5.2 胶水层 B：`TapService` 角色状态机（**unowned blocker 的正解**）

**【无 UI 改动】**

> 这一节回答的是两份 changelist 都没人认领的缺口。**先说结论：协调者给的 (a)(b)(c) 三步方向正确，但理由要修正，并且 (a) 的收益主要不在"热点"，而在"`mReceiverRole` 不被置 false"；同时 (c) 必须补一道守卫，否则会踩一个新坑。**

**现状（逐行复核）**

```java
// TapService.onPeer  TapService.java:170-194
mLinkReady=false; mSendShown=false; mReceiveShown=false;          // :171-173
label = "用户" + field(payload,"t");                              // :175  ← D3 要换
x.setExpectedPeer(n,b,p,a,ip,hs,hp);                              // :177-178
x.startAsReceiver();                                              // :179  两端都起 8988 监听
if (!Bus.lockRole()) { log; return; }                             // :180  只挡"同机重复事件"
if (PENDING.isEmpty()) { x.startHotspot(); post(showReceivePage) } // :182-185
else                   { showSendPage(); x.startAsSender(); }      // :186-193
```
```java
// 上滑回执  TapService.java:103-113
if (sXfer == null || PENDING.isEmpty()) return START_STICKY;      // :105
if (!mLinkReady) { statusIntent("正在连接对方,请稍候…(就绪后再上滑)"); return; }  // :106-111
sXfer.sendImages(new ArrayList<Uri>(PENDING));                    // :112
```
```java
// onP2pReady  TapService.java:222-242
mLinkReady = true;
final boolean sender = !PENDING.isEmpty();                        // :225  ← 用 PENDING 判身份
mMain.post(... if (sender) { showSendPage(); status("已连接,上滑发送即可"); }
             else if (mReceiveShown) { status("已连接,等待接收…"); } ...);
if (sender && sXfer != null) { ... sendPreviews ... }             // :234-241
```
`Bus.lockRole()` 的 `sRoleLocked` 是 **Bus 的静态字段 = 每台手机一份**（`Bus.java:269-286`），挡的是**同一台手机**的重复碰触事件（`APP_FLOW_ANALYSIS.md` §3.2），**不会**挡住另一台 —— 所以两端各自的 `onPeer` 都会跑满。

**根因（复核后修正）**

两台都有照片时，出事的不只是"没人起热点"：

1. 两端都在 `:179` 拿到 `mReceiverRole = true`、`startServer()` 起来了；
2. 紧接着两端都走 `:192` `startAsSender()` → `mReceiverRole = false`（`Xfer.java:378`）；
3. 于是**两端 8988 的 accept 循环都走 `else` 分支**，把对方主动连过来的 socket 静静塞进 `mPendingSocket`（`Xfer.java:680-684`）→ 谁都不读；
4. 用户在其中一台（X）上滑 → `sendImages` 从自己的 `mPendingSocket` 写出去 → Y 那边**数据永远没人读**，小图还会被 TCP 缓冲吃掉，X 以为发成功了。

再加 ①热点通道缺位（`startHotspot()` 只在 `:184` 被调）。所以协调者说的"没人起热点"是对的，但**同等重要的是第 3 步的 accept 分派被 `mReceiverRole=false` 带偏**。

**目标**
- 碰触后：两端都只做"可能接收"的准备（`setExpectedPeer` + `startAsReceiver` + `startHotspot`），**不预判角色**；
- 有照片的那台把发送面板亮出来（谁都可以发）；
- **上滑那一刻**才 `startAsSender()`，链路就绪后才把图交出去。

**改法**

**(a) `onPeer`（改 `TapService.java:182-193`）**
```java
if (!Bus.lockRole()) { Bus.log("角色已定,忽略重复的碰触事件"); return; }
mPeerLabel = peerName(payload);        // D3,见 §5.7
x.startHotspot();                      // 无条件:两端都做"接收准备"
if (hasPending()) showSendPage();      // 有照片 → 亮发送面板(谁都可以发)
else Bus.log("本机无照片 → 本机是接收候选,等对方上滑");
// 注意:这里**不再调用** x.startAsSender()
```
- 保留 `:179` 的 `startAsReceiver()`：它才是"8988 在听"的真正来源（`Xfer.java:341,356`）。
- `startHotspot()` 的副作用要写清楚：它在 Wi-Fi 开着时**提前 return**（`Xfer.java:221-225`），**不会**设置 `mReceiverRole` —— 所以"`mReceiverRole` 保持 true"靠的是 `:179` 的 `startAsReceiver()`，不是 `startHotspot()`。两者缺一不可，但分工不同。
- `mReceiverRole` 保持 true 的直接收益：accept 循环走 `handleIncoming`（`Xfer.java:680-681`），并且 `onPeers` 允许本机发起 P2P 连接（`Xfer.java:617-618`）→ 两端都可能主动把 P2P 组建起来（`mConnecting` 幂等），这也是"先上滑者胜"能自动成立的基础。

**(b) 上滑回执（改 `TapService.java:103-113`）—— 这是 `mLinkReady` 死锁的解药**
```java
if (sXfer == null || PENDING.isEmpty()) return START_STICKY;
mUserWantsSend = true;                     // 新增:本轮我是发送方(上滑即宣告)
if (mLinkReady) {                          // 链路已经就绪(P2P 组可能先被别人建好)
    if (!mSendStarted) { mSendStarted = true; sXfer.sendImages(new ArrayList<Uri>(PENDING)); }
    return START_STICKY;
}
startService(OverlayService.statusIntent(this, "正在连接对方,请稍候…"));
sXfer.startAsSender();                     // 由上滑触发通道协商;就绪后 onP2pReady 交货
```
**死锁确认（协调者的判断成立）**：`mLinkReady` 只在 `onP2pReady` 里置 true（`TapService.java:224`），而 `onP2pReady` 只由 `connectTo`（`Xfer.java:423`）、`joinHotspot`（`:499`）、`onConn`（`:660`）触发 —— 前两个只在 `startAsSender()` 里才跑（`Xfer.java:391`）。做了 (a) 之后 `onPeer` 不再调 `startAsSender()`，于是同网/热点这两条通道上 **`mLinkReady` 永不为 true → 上滑只会刷一句提示 → 永远发不出去**。`onConn` 那条（P2P 对端主动建组）能偶然解套，但不可依赖。
所以 (b) 不是"顺手优化"，是 **(a) 的必要配套，(a)(b)(c) 必须同批**。

**(c) `onP2pReady`（改 `TapService.java:222-242`）**
```java
mLinkReady = true;
mMain.post(() -> {
    if (mUserWantsSend) {                                  // 我上滑过:就绪即交货
        startService(OverlayService.statusIntent(this, "正在发送…"));
        if (!mSendStarted) { mSendStarted = true; sXfer.sendImages(new ArrayList<Uri>(PENDING)); }
    } else if (mReceiveShown || mRejected) {                // ★ 必加守卫:我已是接收方
        startService(OverlayService.statusIntent(this, "已连接,等待接收…"));
    } else if (hasPending()) {                              // 有照片但还没上滑
        showSendPage();                                     // 幂等(mSendShown)
        startService(OverlayService.statusIntent(this, "已连接,上滑发送即可"));
    }
});
```
- ★ **必须补的守卫**：败方在"原地换身份"后 `mSendShown=false`（见 §5.3），但 `PENDING` **仍然非空**；而 `onP2pReady` 可以被调用**多次**（`connectTo` 一次 + `onConn` 一次）。若不拦，第二次会走 `hasPending()` 分支 → `showSendPage()` 把发送页**重新盖到接收卡上**。这就是协调者的 (c) 不完整的地方。
- 关于 `TapService.java:186-190` 那段注释（"发送页立刻弹：以前要等 onP2pReady，链路卡住时用户那边白屏"）的张力：新方案**更彻底地满足了它的诉求** —— 发送面板在碰触那一刻就弹（`onPeer` 里 `showSendPage()`），而不是等到 `onP2pReady`。`onP2pReady` 里那次 `showSendPage()` 只是给"碰触时还没照片、后来才交给服务"的边角情形兜底，且被 `mSendShown` 幂等。
- **`mPreviewsSent` 与 `sendPreviews` 调用整块删除**（`TapService.java:37, 211, 234-241`）：预览已并入报价阶段（§5.1-3）。

**新增字段（一次定清）**

| 字段 | 含义 | 置位 | 复位 |
|---|---|---|---|
| `mUserWantsSend` | 本机上滑过、承诺做发送方 | 上滑分支 | `onPeer` 开头 |
| `mSendStarted` | 本轮已把 `sendImages` 交出去（防 `onP2pReady` 重复触发） | 交出前 | `onPeer` 开头 |
| `mRejected` | 本轮已拒绝（防接收卡被兜底路径重新弹出来） | 拒绝分支（`hideIntent` **之前**） | `onPeer` 开头 |
| `mSwitched`（可选） | 本机从发送面板就地换成了接收卡 | `switchSendPageToReceive()` | `onPeer` 开头 |

> 交互清单里的 `mSending` **不再使用**（语义被 `mUserWantsSend` 覆盖）。

**复用 vs 新增**：纯胶水改动，**零新绘制**。
**风险**：两端 Wi-Fi 都关时**两台都会起本地热点**（双 AP），见 §9 风险 (4)。

---

### 5.3 UI 层 C：接收卡时机 + 就地换身份 + 接收中态（`OverlayService.kt` / `OverlayView.kt`）

**【除"接收中·取消"一个小分支外全部复用】**

#### C-1 接收卡改到 `TYPE_META` 才弹

- **现状**：碰触那一刻就弹（`TapService.java:185` → `showReceivePage` `:354-361`），张数写死 1（`:359`）；`showReceivePage` 恒发状态行「正在连接对方…」（`:360`）。
- **目标**：碰触时**什么都不显示**（只做准备）；`onSendStarted(total)` 到达时才弹，且**弹出来就是真张数、马上就有对方的照片**。
- **改法**：
  1. `TapService.onPeer` 删掉 `mMain.post(showReceivePage(label))`（`:185`），保留 `x.startHotspot()`（见 §5.2(a)）。
  2. `onSendStarted(int total)` 实现（替换 `TapService.java:254-261`）：
     ```java
     @Override public void onSendStarted(int total) {
         if (mUserWantsSend) return;              // 我已上滑:我是发送方,忽略对方的"开始"
         if (mRejected) return;                   // 已拒绝:不许再弹
         mTotalCount = Math.max(total, 0);
         mMain.post(() -> {
             if (mReceiveShown) {                 // 幂等:重复回调只补张数
                 startService(OverlayService.peerIntent(this, mPeerLabel, Math.max(1, mTotalCount)));
                 return;
             }
             if (hasPending()) { switchSendPageToReceive(); return; }   // 败方:原地换身份
             showReceivePage(mPeerLabel, mTotalCount);                   // 无照片那台:此刻才弹
         });
     }
     ```
  3. `showReceivePage(String label, int count)`：`receiveIntent(this, label, Math.max(1,count), previewUriOrNull())`（替换 `:359` 写死的 `1`）；状态行按链路现状挑：`mLinkReady ? "已连接,等待接收…" : "正在连接对方…"`（等价 `TapService.java:231`）。**否则接收卡一出现就停在「正在连接对方…」**（`onP2pReady` 里那条 `else if (mReceiveShown)` 在页还没弹时不会补发，`:230-232`）。
  4. `onImageReceived` 里的补弹（`:268-270`）**保留但收窄**：`if (!mReceiveShown && !mRejected)`，作为 `onSendStarted` 丢失时的兜底。
  5. `onPeer` 开头补 `mLastBmp = null;`：现在 `mLastBmp` 跨轮粘滞，下一轮的 `previewUriOrNull()`（`:369-386`）会把上一轮的图当成本轮预览推给动效层。
- **复用**：`receiveIntent` / `startReceiveFlow`（`OverlayService.kt:232-259`）整套接收页与入场动效；`onPreview` 的"先留图、页没起就 return"逻辑（`TapService.java:244-252`）与本时机天然吻合，**一行都不用改**；`previewUriOrNull()`（`:369-386`）。
- **风险**：`onSendStarted` 若不存在，接收方**彻底没有入口** → C-1 与 §5.1 必须同批。

#### C-2 败方原地换身份（零新绘制）

- **可行性（已复核）**：`OverlayView.setMode()`（`ui/OverlayView.kt:764-770`）只做 `mode=…; pressedButton=NONE; refreshTexts(); invalidate()`，**不碰时间轴、不重建视图、不重播入场**；`mode` 是绘制期分支（`drawTransferPanel` 的 `:2165` 高度、`:2184-2186` 布局、`:2203-2210` 尾部内容）；切到 RECEIVE 后上滑手势自动失效（`onTouchEvent` 在 `:2485` 直接转 `onReceiveTouch`）。
- **改法**：
  1. **`OverlayView.setMode()` 补一行 `resetSendState()`**（`:764-770`）。原因：败方此刻手指可能正按着卡片拖动（`dragging=true, dragY!=0`），切到 RECEIVE 后 `ACTION_UP` 落进 `onReceiveTouch` 且 `pressedButton==NONE` 直接 `return false`（`:2539`），**卡片会永久停在偏移位置**。`resetSendState()`（`:1103-1114`）清 `dragging/dragY/flying` 并取消动画器；冷路径本来就走过 `wipeVisuals()`→`resetSendState()`，加这一行无副作用。
  2. **`OverlayService.startReceiveFlow` 增加"就地切换"分支**（插在 `:232-259` 里读 extra 之后、`buildOverlay/wipeVisuals` 之前）：
     ```kotlin
     val liveSwitch = rootView != null && attached && mode == OverlayView.Mode.SEND
     if (liveSwitch) {                        // 败方:同一台手机、同一次会话,原地换身份
         mode = OverlayView.Mode.RECEIVE
         overlay.setMode(mode)                // 内部会 resetSendState()
         overlay.setPeerInfo(peerLabel, peerCount)
         overlay.setStatus(statusText)
         applyAcceptNotInProgress()           // 收起"接收中"子状态(见 C-4)
         return                               // 不 wipeVisuals、不重解码、不重播
     }
     ```
  3. **`TapService.switchSendPageToReceive()`**（新增）：
     ```java
     private void switchSendPageToReceive() {
         mSendShown = false;          // 发送页已被就地改写
         mReceiveShown = true;        // 从现在起按接收方走 onImageReceived/onTransferDone/回执
         mAccepted = false; mPreviewCount = 0; mFullCount = 0; mTransferDone = false;
         mBmps.clear(); mLastBmp = null;
         startForegroundService(OverlayService.receiveIntent(this, mPeerLabel,
                 Math.max(1, mTotalCount), previewUriOrNull()));
         startService(OverlayService.statusIntent(this, mLinkReady ? "已连接,等待接收…" : "正在连接对方…"));
         // 卡片里那张"我自己的照片"不用动:紧接着到达的 PREVIEW 会用 photoIntent 换成对方的图
     }
     ```
     （`previewUriOrNull()` 算一次即可，别调两遍 —— 它每次都覆写 `cacheDir/peer_preview.jpg`。）
  4. `ACTION_UI_SEND_CONFIRMED` 分支按 §5.2(b) 改造；`onImageSent`/`onXferFailed` 里 `mUserWantsSend/mSendStarted` 一并复位。
- **复用**：`setMode` / `setPeerInfo` / `setStatus` / `photoIntent`（`OverlayService.kt:267-293`，接收态下会连折射背景一起换）/ 接收按钮绘制与命中 / 现有接收冷路径（`:245-249`）。
- **风险**：见 §9 风险 (1)(4)。

#### C-3 发送页文案用真实对端（1 行 + 1 处收窄）

- **现状**：`ui/OverlayView.kt:972` `private val sendTextsFromPeer = false` → 发送页标题恒为内置「发送给 fazd9」、提示恒为「上滑发送 1 张照片」（`strings.xml:12,15`）；`TapService.showSendPage()` 其实已经把真实 peer/张数传进去了（`:214-215`），被这一行丢掉。
- **目标**：标题「发送给 <对端>」、提示「上滑发送 N 张照片」（视频 f_0011 / f_0046）。
- **改法**：
  1. `OverlayView.kt:972` 改 `true`。
  2. 副作用收窄：改 true 后 `refreshTexts()`（`:983-1007`）在 SEND 态会把卡片副标题改写成「JPG · N 张照片」，**冲掉发送页现在挺好用的"真文件名 + 大小"**（`ImageLoader.describe`，`OverlayService.kt:329`）。把 `:998-1000` 的改写**收窄到接收态**：
     ```kotlin
     if (mode == Mode.RECEIVE && peerCount > 0) {      // 原: if (count > 0)
         cardMeta = context.getString(R.string.card_meta_count, metaType(), count)
     }
     ```
- **复用**：`strings.xml:21-25` 五条带参文案早就写好没人用，纯接线。**零新绘制**。
- **风险**：`strings.xml:22`「来自 %1$s的分享」与视频「来自 一路有云 **的**分享」差一个空格（视频字号太小，属推断，`PRODUCT_VIDEO_ANALYSIS.md` §9.6）。**建议维持现状不加空格**，一次定掉别再改版。

#### C-4 「接收中」态（**新状态，约 10 行新增绘制**）

- **现状**：接收态只有两个胶囊（`OverlayView.kt:2422-2451`，常量 `RECEIVE_PRIMARY_H_PX=48 / RECEIVE_SECONDARY_H_PX=40 / RECEIVE_BTN_GAP_PX=10`，`:685-694`）；用户点「接收」后按钮**原地不动**（`drawReceiveActions` 与 `mAccepted` 无关），直到 `hideIntent` 把窗口摘掉。
- **目标**（视频 t≈9.6–10.0s，见 §2.2）：点「接收」后，**两枚胶囊换成一枚占满卡片宽的「取消」胶囊**，卡片与照片保留；传完整卡消散。
- **改法**：
  1. `OverlayView` 加一个子状态位：`private var acceptInProgress = false`，配 `fun setAcceptInProgress(v: Boolean)`（`acceptInProgress = v; pressedButton = BUTTON_NONE; invalidate()`）。
  2. `layoutReceiveActions()`（`:2391-2406`）在 `acceptInProgress` 时**只算主按钮那一枚矩形**（`receivePrimaryRect`），次按钮矩形 `setEmpty()` —— 这样命中判定（`:2528-2570`）自动只认一枚按钮，**不用改触摸代码**。
  3. `drawReceiveActions()`（`:2422-2451`）加一个分支：`acceptInProgress` 时用**同一枚** `receivePrimaryRect` 画一枚胶囊，文字取 `receiveCancelText`（新增 `strings.xml` 一条 `receive_cancel` = 「取消」）。底色建议沿用次按钮那组色（`0x14FFFFFF` 底 / `0xFFDDDDDD` 字）或把 alpha 提到 `0x33FFFFFF`；视频里它比主按钮"淡"，但分辨率下无法定准 —— **实现时按现有色调微调即可，不要新造颜色体系**。
  4. 命中回调：`onReceiveTouch` 的 `ACTION_UP`（`:2548-2556`）在 `acceptInProgress` 时把主按钮映射到**取消**而不是接受。做法：在 `drawReceiveActions`/`onReceiveTouch` 共用的那层放一个判断 `if (acceptInProgress) → onReceiveCancel?.invoke()`。
  5. `OverlayView` 新增回调 `var onReceiveCancel: (() -> Unit)? = null`；`OverlayService.buildOverlay()`（`:520-523` 旁）挂 `notifyNfc(ACTION_UI_RECEIVE_CANCEL)`。
  6. `OverlayService.updateAcceptInProgress()`：`ACTION_UI_RECEIVE_ACCEPT` 分支在回执 `TapService` 的同时，**就地** `overlay.setAcceptInProgress(true)`（不重播、不重解码）。
  7. `TapService` 新增 `ACTION_UI_RECEIVE_CANCEL` 分支：`sXfer.rejectTransfer()` 或本地中断（见 §2.2 拍板项）+ `hideIntent()` + 清本轮接收状态。
  8. **完成时立刻收摊**：`onTransferDone` 里若 `mAccepted` → `hideIntent()` + D4（§5.5），不必等动画。
- **复用**：按钮几何（`layoutReceiveActions` / `receiveActionsHeight`）、圆角矩形（`cardPaint` + `drawRoundRect`）、文字（`drawCenteredLabel`，`:2454-2459`）、淡入（`appear`）。**0 个新 Paint / Shader / 动画。**
- **风险**：新增绘制只有这一处（约 10 行），视频分辨率下无法确认底色/字色，需要真机比对。
- **「取消」的发送方反馈**：见 §2.2 的拍板项（默认沿用「对方拒收」文案）。

#### C-5 发送方收尾：上滑后自动摘窗口（1 行，顺手补掉既有缺口）

- **现状**：`OverlayService.kt:518` `overlay.onSendFinished = { timeline.reset() }` → 卡片飞走后时间轴复位，但**整屏窗口还挂着**（`APP_FLOW_ANALYSIS.md` §8.1-1、README §四.7 的"没有出口、只能强杀应用"）。两阶段协议下，发送方在报价后要**等最多 60 秒**（D2），挂着这样一个吃触摸的空白整屏窗口是不可接受的。
- **改法**：`:518` → `overlay.onSendFinished = { timeline.reset(); hideDemo() }`。上滑 → 卡片飞走动画播完（`FLY_MS=460` + `RESET_DELAY_MS=1750`，`OverlayView.kt:554,564`）→ 窗口自动摘掉 → 用户回到自己的应用里等结果。这与参考产品"上滑后面板静默清除、回到相册"完全一致（视频 f_0039/f_0050）。
- **连带收益**：`TapService.onImageSent`（`:310`）与拒绝/超时路径的 `hideIntent()` 变成幂等的兜底，不再承担"唯一出口"的责任。
- **副作用（要知会）**：`HomeActivity` 的**纯动效演示**路径（`OverlayService.kt:930-934` 两参 `intent`）上滑后也会自动摘窗。行为上更合理，但它改了既有 demo 的手感 —— 需要用户确认。
- **复用**：`hideDemo()`（`:474-477`）。**零新绘制**。

---

### 5.4 失败横幅（D1，`OverlayService`）

**【零 Canvas 新绘制（控件路线）】**

**现状**
- `TapService.onXferFailed(why)`（`:313-319`）只做 `statusIntent(why)`（底部一行 11 设计 px 灰字，`OverlayView.kt:2467-2478`）+ `unlockRole()`，**窗口不摘**。
- 现有整屏窗口的标志位：`FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_HARDWARE_ACCELERATED`（`OverlayService.kt:605-608`），尺寸是**屏幕像素**（`:603-641`）。
- 「触摸穿透」开关的存在理由（`OverlayService.kt:59-61, 644-665, 794-836`；README §四.7）：整屏窗口把下面的触摸一起吃掉，开关只能放通知里。

**目标（D1，三条都要）**
(a) 横幅上**滑动**收起；(b) **点内容区外**收起；(c) **5 秒后自动收起**。

**⚠️ 对 D1 措辞的一处技术修正（必须知会）**
D1 写的"`FLAG_NOT_TOUCH_MODAL` + content-region hit test"在**整屏窗口**上不成立：
- 现有窗口已经有 `FLAG_NOT_FOCUSABLE`，而它**本身就隐含 `FLAG_NOT_TOUCH_MODAL`** —— 所以这个 flag 现在就是开着的，但**对整屏窗口毫无作用**：`NOT_TOUCH_MODAL` 的语义是"落在**窗口矩形之外**的触摸分给下面的窗口"，而窗口铺满整屏，**没有"之外"**。
- 同理，"在 `onTouchEvent` 里对内容区外 `return false`"**不会**把触摸交给下层应用 —— 输入分发是在 WindowManager 层按**窗口**命中，视图不处理的事件只会被丢掉。

所以 D1 的三条行为要落地，正确的做法是下面二选一：

**方案 A（推荐）：横幅用独立小窗**
- 新起一个 `TYPE_APPLICATION_OVERLAY` 窗口，尺寸**正好是横幅那块**（居顶、左右留 16dp、高约 56dp），`gravity = TOP|CENTER_HORIZONTAL`，flag = `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN | FLAG_WATCH_OUTSIDE_TOUCH`。
- 三条行为**全部白送**：
  - (a) 滑动手势：横幅自己的 `OnTouchListener`（|dy| > 24dp → 收起）；
  - (b) `FLAG_WATCH_OUTSIDE_TOUCH` → 收到 `ACTION_OUTSIDE` → 收起。这个 flag 的语义就是"**通知我，但事件仍然照常发给下面的窗口**"—— 与参考产品"横幅挂在相册上方、相册照常可用"（视频 f_0059–f_0060）完全一致；
  - (c) `Handler.postDelayed(5000)` → 收起（`D1_AUTO_DISMISS_MS = 5000`）。
- 窗口**不是**动画窗口，也不进 `OverlayView`：横幅用**控件**搭 —— `FrameLayout` + 圆形红底（复用 `Ui.rounded(Context,int,int)`，`nfc/Ui.java:25-30`）+ 两行 `TextView`。**零 Canvas 新绘制**，也不动 `OverlayView` 一行代码（保护既有动效，符合用户优先级）。
- 相对参考产品（视频 f_0055/f_0059）：红色圆圈「!」+ 粗体主行「发送失败」+ 次级副行「对方拒收」；位置贴状态栏下方、水平居中。颜色沿用「动效层调性」：红 `0xFFE5484D`、主行白、副行 `0xFFB9C2CC`（与状态行同色，`OverlayView.kt:2473`）。
- **接线**：`OverlayService` 加 `ACTION_FAIL_BANNER` + `failBannerIntent(ctx, title, detail)`（照抄 `statusIntent`，`OverlayService.kt:970-974` 的 4 行）+ `showFailBanner()/hideFailBanner()`；`Settings.canDrawOverlays` 照现有三处那样兜一下（`:203-207, 240-244, 499`）。
- **收摊点（必须全部覆盖）**：`onPeer`（新一轮）/`ACTION_HIDE`/`ACTION_STOP`/`onDestroy`/用户收起/5s 到期 —— 少一个就会出现"上一轮的横幅挂在新一轮上"（与 `wipeVisuals` 同类问题，`OverlayService.kt:384-395`）。

**方案 B（备选）：横幅画进现有整屏窗口**
- 走 `ALIGN_CHANGELIST_INTERACTION.md` E-2 的 `setFailure/drawFailureBanner`（约 30 行 Canvas），窗口**保持整屏**，靠 `LayoutParams.setTouchableRegion(横幅矩形)` 把触摸区域收成横幅那一块（此时 `NOT_TOUCH_MODAL` 才真正生效）+ 在 `onTouchEvent` 里做内容区命中判定。
- 代价：(i) 必须保证窗口在横幅期间**保持 attached**（`onImageSent` 现在会 `hideIntent()`，`:310`），否则横幅画不出来 —— 要么改成"有横幅待定时不摘窗"，要么在 `failBannerIntent` 里重新 attach（并把 GL surface 一起拉起来）；(ii) 触摸区域要在 `applyBarInsets`/`onConfigurationChanged`/`onSizeChanged` 后重算并**重新下发**，漏一次就点不到；(iii) 动画层的时间轴会一直在跑（`Timeline` 常驻段永不结束，`anim/Timeline.kt:131-149`）。
- 只有在"必须复用同一个窗口"这个前提下才选 B。

**逐条改动（以方案 A 为准）**
1. `service/OverlayService.kt`：新增 `ACTION_FAIL_BANNER` + `failBannerIntent(ctx, title, detail)` + 独立窗口的 `show/hide` + 5s/滑动/`ACTION_OUTSIDE` 三条收起路径 + 各处收摊点。
2. `nfc/TapService.java`：
   ```java
   @Override public void onPeerRejected() {
       Bus.log("服务: 对方拒收");
       Bus.unlockRole();
       startService(OverlayService.failBannerIntent(this, "发送失败", "对方拒收"));
   }
   @Override public void onVerdictTimeout() {
       Bus.log("服务: 对方未响应(超时)");
       Bus.unlockRole();
       startService(OverlayService.failBannerIntent(this, "发送失败", "对方未响应"));   // D2
   }
   ```
3. `onXferFailed(why)`（`:313-319`）**保留**现有底部状态行（长文案「对方尚未连接,请让对方保持在前台再试」放这儿才放得下），并追加一条横幅 —— 但只对**上滑之后**的失败发（用 `mUserWantsSend` 判定），否则会把"还没上滑就失败"也报成"发送失败"。细节文案在 `TapService` 里截到 ~12 字以内（`textPaint.measureText` 在控件布局里也有宽度约束）。
4. `strings.xml` 加两条：`fail_title`=「发送失败」、`fail_rejected`=「对方拒收」、`fail_timeout`=「对方未响应」（3 条）。

**复用 vs 新增**：复用 `statusIntent` 的工厂范式、`Ui.rounded`、`Bus.log`/`unlockRole`；新增 1 个 action、1 个工厂、1 个小窗、3 条文案。**零 Canvas 新绘制**。
**风险 / blast radius（D1 要求点名）**：
- 这是**第三个悬浮窗机制**（现有两个：`OverlayService` 的整屏窗、`nfc/Overlay.java` 的查看器窗）。必须写清楚它在谁手里、什么时候收。
- 现有「触摸穿透」通知开关（`ACTION_TOGGLE_TOUCH`，`OverlayService.kt:649-665, 875, 828-835`）**继续保留**：它管的是 SEND/RECEIVE 两个整屏状态；横幅窗独立，不受它影响（好处：横幅永远点得到）。但**通知里仍然是 3 个 action**（系统只显示 3 个，`:794-800`）—— 不要把横幅开关塞进去。
- D1(c) 的 5 秒自动收起与参考产品"驻留到片尾"（视频 f_0057–f_0060）**故意不同**，这是用户定的 D1，要在验收时说清。

---

### 5.5 接收完成后打开系统查看器（D4）

**【删除旧实现，纯删除 + 一个 20 行的新方法】**

**现状**
- `TapService.showViewer()`（`:388-407`）：`Ui.buildViewer`（`nfc/Ui.java`）+ 右上角「✕」+ `Overlay.get(this).show(v)`（旧的 `nfc/Overlay.java`）。调用点三处：`:120`（点接收且已收完）、`:291`、`:296`（`onTransferDone` 两个分支）。
- `TapService` 里**没有任何** `startActivity` / `ACTION_VIEW`（已 grep 确认）。
- `saveToGallery()`（`Xfer.java:1011-1032`）返回 **boolean**，不返回 Uri。
- `nfc/Overlay.java` 与 `Ui.buildViewer` 的其它用法只在调试页：`MainActivity.java:605, 620, 654`。

**目标**：接收完成 → 卡片消散 → 用 `ACTION_VIEW` 打开**系统查看器**看刚落盘的那张（`FLAG_ACTIVITY_NEW_TASK`）。

**改法**
1. **删** `TapService.showViewer()`（`:388-407`）整段。
2. 三处调用点（`:120`、`:291`、`:296`）全部换成新方法：
   ```java
   private void openSystemViewer() {
       Uri u = (sXfer != null) ? sXfer.firstSavedUri() : null;   // 5.1-8
       if (u == null) { Bus.log("接收完成:本轮没有落盘 Uri,不打系统查看器"); return; }
       try {
           startActivity(new Intent(Intent.ACTION_VIEW)
                   .setDataAndType(u, "image/*")
                   .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                           | Intent.FLAG_GRANT_READ_URI_PERMISSION));
       } catch (Throwable t) {
           Bus.log("打开系统查看器失败: " + t);
       }
   }
   ```
   - `:120` 分支的语义（用户先点接收、收完再打开）在 C-4 的"接收中"态下变成：点接收 → ACCEPT → 接收中(取消) → `onTransferDone` → 消散 + 打开查看器。
   - `:291/:296` 两个分支在"卡片只在 META 后出现"的新时机下合并为一个（`mAccepted` 才打开）—— 保留 `else if (mAccepted)` 那条兜底也行，行为等价。
3. `mBmps` **保留不动**（还被 `previewUriOrNull()` 的兜底 `:371` 用着）；只是不再有人拿它开查看器。
4. `nfc/Overlay.java` 与 `Ui.buildViewer` **保留**：唯一剩下的用法是调试页 `MainActivity.java:605/620/654`，那是联调入口，不属于产品流程，**不改**（改了就要重写整个调试查看器，收益为零）。代价：`nfc/Overlay.java` 从此是"调试页专用"，建议在类注释里标一句。
   - 可选（不做也行）：把 `MainActivity.showViewer` 也换成系统查看器 → 即可删掉 `Ui.buildViewer` + `nfc/Overlay.java`。**列入"可选清理"，不在本批**。

**复用 vs 新增**：复用 D4 需要的 MediaStore Uri（`saveToGallery` 已经 insert 出来，只是没返回）；新增 1 个方法、1 个 `Xfer` 访问器。**零新绘制**（查看器由系统提供）。
**风险**：`saveToGallery` 在 `API < 29` 直接 `return false`（`Xfer.java:1012`）→ 没有 Uri → 不打开查看器（静默，只记日志）。目标机是 API 29+，可接受。

---

### 5.6 接收失败/超时后的收摊（`onOfferExpired`）

**【无 UI 新增】**

- 场景：发送方 60s 超时后关闭连接，而接收方的接收卡已经弹出（用户还在犹豫）。接收方的读循环退出、`count==0`、没表过态 → 必须有人把卡收掉，否则用户被整屏窗口卡住（`APP_FLOW_ANALYSIS.md` §8.1-1 那个既有缺口的另一面）。
- `Xfer` 投 `onOfferExpired()`（§4.1）→ `TapService`：
  ```java
  @Override public void onOfferExpired() {
      if (!mReceiveShown) return;
      mReceiveShown = false;
      mRejected = true;                      // 本轮别再弹回来
      startService(OverlayService.hideIntent(this));
      Bus.unlockRole();
  }
  ```
- **复用**：`hideIntent` / `unlockRole`。**零新绘制。**

---

### 5.7 对端名字（D3，1 个 helper）

**【无 UI 改动】**

- **现状**：`TapService.java:175` `"用户" + field(payload,"t")`，`t` 是 8 位随机 token（`Bus.java:164`）→ 屏幕上出现「发送给 用户abc12345」。同名逻辑还在调试页 `MainActivity.java:559-562`。
- **payload 里现成的字段**（`Bus.java:154-176`）：`n=<Build.MODEL>`、`b=<蓝牙名>`、`t=<token>`、`ts=...`、`p=/a=/ip=/hs=/hp=`。
- **目标（D3）**：**蓝牙名优先，退化为机型名**。
- **改法**：
  ```java
  private static String peerName(String payload) {
      String b = field(payload, "b");
      String n = field(payload, "n");
      if (b != null && !b.isEmpty() && !"?".equals(b)) return b;   // 蓝牙名
      if (n != null && !n.isEmpty() && !"?".equals(n)) return n;   // 退回机型
      String t = field(payload, "t");
      return "用户" + (t == null ? "--------" : t);                // 最后兜底(现状)
  }
  ```
  `TapService.java:175` 与 `MainActivity.java:559-562` 都换用它（后者是调试页，1 行，建议一起换以保持一致）。
- **`MAX_PAYLOAD = 200` 静默截断**（`Bus.java:41, 96-102`）**要防**：payload 已经很长（`n/b/t/ts/p/a/ip/hs/hp` 八段 + 固定热点 SSID「TapShare-Transfer」12 字节 + 密码「tapshare2026」12 字节），中文设备名按 UTF-8 每字 3 字节。一旦超过 200，尾部 `hs/hp` 被砍掉 → **热点通道静默失效且无任何日志**（`APP_FLOW_ANALYSIS.md` §8.2-13）。
  - **必做（1 行）**：在 `Bus.buildExchangeApdu` 的截断分支（`Bus.java:98-102`）里加一条 `Bus.log("payload 超长被截断: " + d.length + " > " + MAX_PAYLOAD + ",尾部字段(hs/hp)可能丢失")`。
  - 可选（不做也行）：截断前先丢 `ts=` 段（8 字符时间戳，价值最低）。
- **`b=` 取不到的风险**：`BluetoothAdapter.getDefaultAdapter().getName()`（`Bus.java:159-161`）在 API 31+ 需要 `BLUETOOTH_CONNECT`，而 Manifest 只有 `BLUETOOTH/BLUETOOTH_ADMIN`（`AndroidManifest.xml:41-42`）。系统 uid 通常豁免运行时权限，但**取不到时 `getName()` 返回 null → `btName="?"`**（`:157,161`），helper 会把 `"?"` 当缺失处理并退到机型名。**需要真机验证 `b=` 到底是什么**；若恒为 `?` 就补声明 `BLUETOOTH_CONNECT`（system uid 下无弹窗）。
- **复用 vs 新增**：纯复用 payload 既有字段，不新增协议字段 —— 这**取消了** `ALIGN_CHANGELIST_INTERACTION.md`「接口 #6 建议 payload 加昵称字段」那条（加字段会挤掉 `hs/hp`，风险大于收益）。**零新绘制**。

---

### 5.8 可选：底部品牌行「一键互联」（可延后）

- 视频里发送面板与接收卡**都**有这行（f_0009 / f_0015 / f_0051，字号最小、居中）。代码里**根本不存在**：`grep -rn "一键互联" app/src` 零命中；但 `OverlayView.kt:705` 的注释写着「正好落在底部品牌文字上方」、`:717` 写着「避免底部品牌文字贴到系统栏」—— **注释在描述一个没被实现的东西**。
- 改法（最小新增绘制）：`strings.xml` 加 `brand_line`；`OverlayView` 加常量 `BRAND_BOTTOM_PX = 20f`（与 `STATUS_BOTTOM_PX = 46f`（`:706`）上下呼应：品牌行在下、状态行在上）；`drawTransferPanel()` 的 `drawStatusLine` 调用点旁（`:2213-2216`）加一次 `drawBrandLine(c, textAlpha)`（`textPaint` normal + 10f*k、`withAlpha(0xFFFFFFFF, alpha*0.55f)`、居中 `viewW/2`、`y = viewH - bottomInset - BRAND_BOTTOM_PX*k`）。**约 8 行**，落在 `drawTransferPanel` 内 → 天然跟面板淡入淡出。
- 这是**唯一的额外新绘制**，属于"视觉贴齐"而非流程，**建议放到最后一批**（或不做）。

---

## 6. 被本方案取消的改动项（及原因）

### 6.1 取消（原清单里已无必要）

| # | 原出处 | 项 | 取消原因 |
|---|---|---|---|
| 1 | LINK B-2 | 「排空丢帧」——拒绝时读循环 `while(mRunning && !mRejected)`、`readFully` 后 `continue`、`mIo` 任务与 `saveToGallery` 加 `mRejected` 守卫 | 两阶段协议下**拒绝时整图还没开始发**，没有传输要打断。守卫全部多余。 |
| 2 | LINK B-3 | `deleteRoundSaved()` 删除本轮已入库的 MediaStore 行 | 同意之前不落盘 → 没有需要回滚的记录。**这是两阶段协议直接消灭的两个问题之一。** |
| 3 | LINK A-4 后半 | 「接收方只写不关」的**规则化**（把"谁关"固定成发送方的完整论证） | 规则本身在交货阶段仍然成立（发送方写完 `FULL×N` 后关），但**不再需要作为一条硬约束去设计**：接收方写 `ACCEPT` 时自己的读缓冲是空的，不存在"RST 吞掉刚写的回执"的窗口。**保留其反面论证**（为什么不选"接收方反连发送方 8988"）—— 见 §9 风险 (2)。 |
| 4 | LINK A-5 前半 | 「发送方传完不关、继续持有连接等一条回执」 | 表态发生在交货**之前**，交货后立刻关闭。发送方只持有连接 **META→回执** 这一段（≤60s），不是"传完还要挂着"。 |
| 5 | LINK 取舍 #4 | 「最大的语义差异：本应用是上滑就把整图推过去 → 再问要不要；彻底对齐要把入库推迟到 ACCEPT，建议单独立项」 | **本方案就是那个立项**，且不需要"把原始 bytes 留在内存里"——因为整图在同意前根本没发。 |
| 6 | LINK 取舍 #5 | 「对等模型要一条"角色改判"广播（BEGIN 帧）」 | `TYPE_META` 自己就是角色改判信号（§3.4），不需要新帧。 |
| 7 | INTERACTION A-1 第 3 点 | 「`onSendStarted` **两端都收到**」（发送侧在 `sendImages` 里本地触发） | 发送方是自己发起的，不需要这个回调（只有接收方消费）。**接收方一侧的契约不变**（以 `TYPE_META` 为触发点，**不能**用 `TYPE_PREVIEW`，否则时机退回"碰触即弹"）。 |
| 8 | INTERACTION B-4 | `mSending` 标志 | 被 `mUserWantsSend` + `mSendStarted` 取代（§5.2 字段表）。 |
| 9 | INTERACTION 接口 #6 | payload 新增"用户昵称"字段 | 被 D3 取代：用现成的 `b=`/`n=`，不加字段（不挤 `hs/hp`）。 |
| 10 | INTERACTION 接口 #3 | 单个 `rejectIncoming()` | 拆成 `rejectTransfer()` / `confirmReceived()` 一对（§4.2）。两个方向都要能回执。 |
| 11 | INTERACTION 接口 #2 / LINK D-2 | `onSendRejected()` / `ACTION_FAILURE` / `failureIntent` | 统一为 `onPeerRejected()` / `ACTION_FAIL_BANNER` / `failBannerIntent(ctx,title,detail)`。 |
| 12 | INTERACTION E-2 | 「失败横幅走 `drawFailureBanner` 画进整屏窗口 + 内容区命中判定」 | D1 的三条行为用**独立小窗**实现更稳、零 Canvas 绘制（§5.4 方案 A）。若坚持单窗，回到方案 B。 |
| 13 | INTERACTION 取舍 #4 | 「接收方点接收后弹全屏查看器：建议保留现状」 | **被 D4 推翻**：改用系统查看器，删掉 `showViewer` 与 `nfc/Overlay` 的主流程用法。 |
| 14 | INTERACTION 取舍 #5 | 「失败横幅驻留 + 补一个点按出口」 | **被 D1 推翻**：滑动收起 + 点外部收起 + 5 秒自动收起（三条都要）。 |
| 15 | INTERACTION 取舍 #9 | 「发送方无超时、无取消；视频也没有超时态，保持」 | **被 D2 推翻**：60 秒超时 + 「对方未响应」。 |
| 16 | INTERACTION 取舍 #6 | 「`PENDING` 不清空 + `HomeActivity` 加'解除发送方身份'按钮」 | **本方案不动 `TapService.java:305-306`**：这属于"入口/发送方身份"，与图库入口方案一起定。本方案只保证收尾逻辑**不依赖** `PENDING` 是否为空。 |

### 6.2 保留（原清单里仍然有效，不要漏）

| 出处 | 项 | 备注 |
|---|---|---|
| LINK A-2 | 用**同一条已建立的 TCP 连接**做回执（不用反连） | 理由保留（三个通道一致、无需新寻址假设） |
| LINK A-5b | `count == expect` 完成判定 + `onTransferDone` 去重 + EOF 兜底 | 让"接收中"态的结束确定化 |
| LINK A-6 | 热点释放从"flush 后 5 秒"挪到事务之后 | 否则 ① 通道下回执/交货会被拆连接 |
| LINK A-7 | 轮次令牌 `mRound` | 60s 等待窗口 + 常驻服务 → 必须防串轮 |
| LINK C-2/C-3 | `TYPE_META` 作为"真正开始"的信号；`onImageCount` → `onSendStarted` 改名 | 契约命名以 §4 为准 |
| LINK E-1 | `addListener` 去重（1 行，**必做**） | 否决一切"靠幂等防御养 bug"的写法 |
| LINK E-2 | 预览并入发送阶段（删 `sendPreviews`） | 报价阶段只推 1 张 |
| LINK 8 | 真机三通道验收 | 见 §10 |
| INTERACTION A/B/C-1/C-2/D/E-1 | 接收卡时机、对等仲裁、文案与张数、`mRejected` 守卫、成功静默 | 触发时机按 §3 重写，结论不变 |
| INTERACTION C-3 | 底部品牌行（新绘制 ~8 行） | 可选，见 §5.8 |

---

## 7. 交付物清单

### 7.1 链路层（`nfc/Xfer.java`、`nfc/Bus.java`）

1. 常量：`TYPE_ACCEPT=4`、`TYPE_REJECT=5`、`VERDICT_WAIT_MS=60000`（`Xfer.java:53-56` 之后）。
2. `Listener` 改名 `onImageCount` → `onSendStarted`（含转发器 `:74-76`、投递点 `:741-743`）。
3. `Listener` 新增 `onOfferExpired()` / `onPeerRejected()` / `onPeerAccepted()` / `onVerdictTimeout()`。
4. `addListener` 去重（`:91-93`）。
5. `sendImages` 拆两阶段 + `readVerdict()` + `releaseHotspotLater()`（`:797-884`）。
6. `handleIncoming`：留 socket、`count==expect` 完成、EOF 兜底、`count==0` → `onOfferExpired`（`:716-794`）。
7. `confirmReceived()` / `rejectTransfer()` / `writeVerdict()` / `firstSavedUri()`（新增）。
8. `saveToGallery` 返回 `Uri`（`:1011-1032`）+ `mFirstSavedUri`。
9. `resetRoundState()` 扩展：`mRound++`、清 `mPeerSocket/mVerdictSent/mDoneNotified/mExpect/mFirstSavedUri`（`:195-213`）。
10. **删除** `sendPreviews`（`:887-950`）。
11. （可选，二级）`releaseHotspotIfUnused()`。
12. `Bus.buildExchangeApdu` 截断处加日志（`Bus.java:98-102`，1 行）。

**链路层不改的**：`MAGIC`/帧格式、`PORT`、NFC APDU、payload 结构、角色锁、三个通道的探测顺序与超时（`startConnectStrategy` `:401-412`）、`startServer/startClientRead/onConn/onPeers`。

### 7.2 胶水层（`nfc/TapService.java`）

1. `onPeer`：按 §5.2(a) 重写分支 + `mUserWantsSend/mSendStarted/mRejected` 复位 + `mLastBmp=null` + D3 名字。
2. 上滑回执按 §5.2(b) 重写。
3. `onP2pReady` 按 §5.2(c) 重写（含 ★ 守卫），删 `sendPreviews`/`mPreviewsSent`。
4. `onSendStarted(total)` 实现（替换 `onImageCount`，`onImageReceived` 补弹收窄）。
5. `showReceivePage(label, count)` 加张数入参 + 状态行分档。
6. `switchSendPageToReceive()` 新增。
7. 接收/拒绝分支：接 `confirmReceived()` / `rejectTransfer()`；拒绝分支置 `mRejected`（在 `hideIntent` **之前**）。
8. 新增 `onPeerRejected()` / `onVerdictTimeout()`（→ `failBannerIntent`）、`onOfferExpired()`、`onPeerAccepted()`（仅日志）。
9. `ACTION_UI_RECEIVE_CANCEL` 分支（取消）。
10. **删除** `showViewer()`（`:388-407`）+ 三处调用（`:120, :291, :296`）→ `openSystemViewer()`。
11. `peerName()` helper（替换 `:175`）。

### 7.3 UI 层（`service/OverlayService.kt`、`ui/OverlayView.kt`、`res/values/strings.xml`）

1. `OverlayService.startReceiveFlow` 就地切换分支（`:232-259`）。
2. `OverlayView.setMode` 补 `resetSendState()`（`:764-770`）。
3. `OverlayView` 接收中态：`acceptInProgress` + `setAcceptInProgress` + `layoutReceiveActions` 单按钮分支 + `drawReceiveActions` 分支 + `onReceiveCancel` 回调（**唯一的新增绘制，约 10 行**）。
4. `OverlayService`：`ACTION_UI_RECEIVE_CANCEL` 回执 + `ACTION_UI_RECEIVE_ACCEPT` 里就地把 UI 切到接收中。
5. `onSendFinished` 追加 `hideDemo()`（`:518`，1 行）。
6. `OverlayView.kt:972` `sendTextsFromPeer = true` + `:998-1000` 的 `cardMeta` 改写收窄到接收态。
7. 失败横幅：`ACTION_FAIL_BANNER` + `failBannerIntent` + 独立小窗（滑动/点外/5s 三条收起）+ 全部收摊点。
8. `strings.xml`：`receive_cancel`、`fail_title`、`fail_rejected`、`fail_timeout`（+ 可选 `brand_line`）。

### 7.4 编译同步（不参与产品流程）

1. `nfc/MainActivity.java:504` 改名 `onImageCount` → `onSendStarted`。
2. `nfc/MainActivity.java:492` 删 `sendPreviews` 调用。
3. （建议）`nfc/MainActivity.java:559-562` 换用 `peerName()` 逻辑。
4. `service/OverlayService.kt:864` 注释里的 `onImageCount` 改名。

---

## 8. 实施顺序

> 标注 **【同批】** 的必须一次落地，否则中间态跑不通（编译过 = 逻辑断）。

### 第一批：链路层接口面一次到位（可单独编译）

1. `Xfer`：新增 3 个常量；`Listener` 改名 + 新增 4 个回调；`addListener` 去重；`TapService.java:254`、`MainActivity.java:504`、`OverlayService.kt:864` 同步改名（**【同批】**：改名跨两层）。
2. `Bus`：截断日志（1 行）。
   → 验证：编译通过；真机碰一次，日志里 `addListener` 后事件只投递一次。

### 第二批：两阶段协议核心（**【同批】，含 §5.2 的 PENDING↔通道修复）

3. `Xfer`：`sendImages` 两阶段 + `readVerdict` + 新字段 + `confirmReceived/rejectTransfer/writeVerdict` + `handleIncoming` 的 `count==expect`/`onOfferExpired` + `saveToGallery` 返回 Uri + `firstSavedUri` + 删 `sendPreviews`。
4. `TapService`：§5.2(a)(b)(c) 三步（**必须同批**）+ `onSendStarted` 实现 + `showReceivePage(label,count)` + `switchSendPageToReceive` + 接收/拒绝分支接回执 + `onPeerRejected/onVerdictTimeout/onOfferExpired/onPeerAccepted` + 删 `sendPreviews` 调用（`MainActivity.java:492` 一起删）。
5. `OverlayService`：`startReceiveFlow` 就地切换 + `OverlayView.setMode` 补 `resetSendState`。
6. `TapService`：`onSendFinished → hideDemo`（`OverlayService.kt:518`）。
   → 验证：真机跑通「你选图我也选图 → 双面板 → 先滑者胜 → 败方原地变接收卡 → 点接收 → 有图 → 完成」，`adb logcat -s NfcTapDemo` 里能看到 `TYPE_META`/`TYPE_ACCEPT`/`count==expect`。
   ⚠️ 这一批**不能拆**：只做 3 不做 4，接收方没有入口；只做 4 不做 3，`sendImages` 还是旧语义。

### 第三批：两条收尾路径（各自独立可上）

7. **D1 失败横幅**：`OverlayService` 小窗 + action + `TapService.onPeerRejected/onVerdictTimeout` 接线（+ `onXferFailed` 追加）。
8. **D4 系统查看器**：删 `showViewer`、三处换 `openSystemViewer`。
9. **C-4 接收中·取消**：`OverlayView` + `OverlayService` + `TapService.ACTION_UI_RECEIVE_CANCEL`（依赖第二批的回执链路）。
10. **D3 对端名字**：`peerName()`（1 处替换，独立）。

### 第四批：文案与视觉贴齐（可选，不影响流程正确性）

11. `sendTextsFromPeer = true` + `cardMeta` 收窄（§5.3 C-3）。
12. 底部品牌行「一键互联」（§5.8，唯一额外的可选新绘制）。
13. 清理：`ALIGN_CHANGELIST_INTERACTION.md` 提到的 `PENDING` 策略（**归属入口方案，本方案不动**）。

### 第五批：真机三通道验收（§10）

---

## 9. 风险与取舍

### 9.1 两笔真实成本（必须明说）

**(1) 发送方要持有连接/热点最长 60 秒（D2）**
- 现状：`flush` 后立刻 `close()`（`Xfer.java:867`），5 秒后就释放热点网络（`:870-874`）。改后：从"写完 META + 预览"到"收到表态"这一段全在等，**最长 60 秒**。
- 影响面：走 ① 热点通道时，发送方这 60 秒是挂在**对方热点上的 STA** → 它自己上不了网、Wi-Fi 弹窗/系统状态都会提示"已连接到 XXX"。这是"先报价后交货"的直接代价（参考产品也有这段等待，只是它做得快）。
- 缓解：`VERDICT_WAIT_MS` 是常量，可调（`ALIGN_CHANGELIST_LINK.md` 取舍 #2 建议 30s 用于混装场景）；正常路径下用户几秒内就会点，实际持有时间 = 用户的反应时间（视频里是 2 秒）。
- **不要**为了让这个数字变小而把"自动接受"塞进来（那就退化成"一碰即传"）。

**(2) 旧/新构建互相不兼容，两端必须同批升级**
- 旧发送方（发完即关、不读回执）对新接收方：新接收方在报价阶段就写 `ACCEPT`，但旧发送方的 socket 已经关了 → 收不到，接收方停在"接收中"直到超时；且旧发送方**在同意前就把整图发完了**——新接收方的读循环会发现先到的是 `FULL`（没有 `META` 的前导）…… 帧是自描述的，能读，但**没有 `META` → 接收卡不会弹**（`onSendStarted` 不来）→ 数据白收。
- 新发送方对旧接收方：旧接收方收 `META` 先弹卡（它的旧 `onImageCount` 路径），但它**不会写回执** → 新发送方等满 60 秒 → 横幅「对方未响应」。
- 结论：**本仓两个 APK 必须一起改、一起装**；演示现场若可能混装，把 `VERDICT_WAIT_MS` 调小（30s）减少空等。

### 9.2 仍然对不齐 / 别扭的点（诚实清单）

| # | 项 | 说明 |
|---|---|---|
| 1 | **「取消」的语义**是推算的 | 视频只拍到"有一枚取消胶囊"，没拍到点了会怎样。本方案定义为"放弃本轮接收"，发送方文案二选一（默认沿用「对方拒收」）——**需用户拍板**。 |
| 2 | **双上滑竞态**没有确定性裁决 | 视频没拍到（`PRODUCT_VIDEO_ANALYSIS.md` §9.10）；退化行为是两边各等 60s 各出一张「对方未响应」。可选增强：accept 循环即使 `mReceiverRole==false` 也偷看一眼首帧类型，是 `META` 就让先到的那台当发送方（需改 `Xfer.java:680-684`，**不建议首批**）。 |
| 3 | **粒子迸发不做** | 用现有卡片飞走动画代替（视频 f_0049）。视觉差异，已接受。 |
| 4 | **「点内容区外收起」依赖 `ACTION_OUTSIDE`** | 独立小窗 + `FLAG_WATCH_OUTSIDE_TOUCH` 是标准做法，但少数 ROM 可能不发 `ACTION_OUTSIDE`。降级路径已经存在（5 秒自动收起 + 滑动收起），不会卡死。**列入真机验证项。** |
| 5 | **接收方在"接收确认"态没有主动出口** | 参考产品的卡片也只有「拒绝」一个出口（`PRODUCT_VIDEO_ANALYSIS.md` §4.2 注）。60s 后由 `onOfferExpired` 兜底自动收（§5.6），比视频更宽容。 |
| 6 | **发送方 60s 内没有"正在等待对方"的可视反馈** | 上滑后窗口已摘（§5.3 C-5），用户在相册里看到的是安静的画面。视频里发送方状态栏有「等待发送」小字（`PRODUCT_VIDEO_ANALYSIS.md` §4.5，推断项）。**本方案不做**（系统状态栏文案需要独立的常驻通知，13+ 又拿不到通知权限）。列为取舍。 |
| 7 | **`b=`（蓝牙名）可能取不到** | 见 §5.7；退化为机型名，仍比"用户abc12345"好。 |

### 9.3 通道相关风险

| # | 风险 | 说明 / 缓解 |
|---|---|---|
| 1 | **回执走的是数据那条连接的反方向** | 三个通道（①热点 STA/AP ②同网 ③P2P，含组主/组员两种）用的都是**已建立的那条 socket**，不需要新的寻址/可达性假设 —— 这是选"同 socket 回执"而不是"接收方反连"的核心理由（`ALIGN_CHANGELIST_LINK.md` A-2 保留）。反连方案被否决的**具体原因仍然成立**：发送方 accept 循环按 `mReceiverRole` 分发（`Xfer.java:680-684`），`startAsSender()`（`:378`）已把该标志置 false → 反连上来的回执连接会被静静塞进 `mPendingSocket`（`:683`），**永远不会被读**；而且这条"没人读的 socket"会让 `hasLiveLink()`（`:190-193`）为真 → 下一轮 `setExpectedPeer` 跳过 `resetRoundState()`（`:167`）→ 下一轮拿陈旧 socket 去写（正是 `Xfer.java:154-166` 注释里记的那个"第一轮好、后来再也连不上"）。 |
| 2 | **① 热点通道有硬配套** | `releaseNetworkRequest()` 必须挪到事务之后（§5.1-7），否则 5 秒就把连接拆了。接收侧的 `closeHotspot()` 本来就在读循环退出之后（`Xfer.java:785-788`），天然在事务之后。 |
| 3 | **两端都起热点 → 双 AP** | §5.2(a) 让两端都调 `startHotspot()`。Wi-Fi 关着时两台都会成为 AP：下一轮里"要加入对方热点"的那台自己也开着 AP → 本 ROM 热点与 Wi-Fi 互斥（`Xfer.java:221-225` 的注释），加入可能失败 → 退到 ②/③。**缓解（二级）**：上滑成为发送方时若 `!mGotConnection` 就关掉自己那个没人用的热点（`releaseHotspotIfUnused()`）。**必须真机验证**，不要凭猜测先改。 |
| 4 | **P2P 顺序** | `startAsReceiver()` 会 `removeGroup` 并启动 20s 计时（`Xfer.java:365-373`）；两端都可能主动发起 P2P 连接（`onPeers` 要求 `mReceiverRole`，`:617-618`）→ 由 `mConnecting` 幂等保证同机只发一次。组建立后：组主 `startServer()`、接收方组员 `startClientRead()`（`:652-667`）。 |

---

## 10. 仍需真机验证的清单

**A. 协议与仲裁**
1. 双上滑（几乎同时）：确认退化行为是"两边各出「对方未响应」"，不出现进程异常/死锁。
2. `mUserWantsSend` 路径：链路已就绪（P2P 组先建好）后才上滑 → 确认走"立即交货"分支而不是 `startAsSender()`。
3. ★ `onP2pReady` 守卫：构造"败方原地换身份后 `onP2pReady` 再触发一次"的场景，确认发送页**没有**盖回来。
4. 报价阶段只推 1 张预览（`Xfer.java` 新逻辑），确认接收卡在 META 后 ≤0.5s 内就有对方的照片（对齐视频 f_0014→f_0015 的节奏）。

**B. 三条通道 × 两条路径**（每条都跑「接收」与「拒绝」两遍）
5. ① 对方热点（需要**第二次碰触**才有 `hs=`，见 §9.3-2 的说明）：重点量"拒绝 → 发送方横幅"时延（目标 ≤0.5s），确认横幅能出现（即 `releaseHotspotLater()` 生效）。**同时观察发送方那 60 秒里自己的 Wi-Fi 状态**（§9.1-1）。
6. ② 同一 Wi-Fi 直连。7. ③ Wi-Fi Direct 兜底（含"发送方做组主"与"发送方当组员"两种，`Xfer.java:802-814`）。
8. 回归：接受后**只**打开一次系统查看器（验证 `addListener` 去重生效）；拒绝后相册里**没有** `Pictures/NfcTapDemo/` 新图（两阶段协议的直接结果，应当天然成立）。

**C. UI**
9. 「接收中·取消」：底色/字色与视频比对；点「取消」后接收方与发送方各自的表现。
10. 失败横幅三条收起路径：滑动、点内容区外（**重点验 `ACTION_OUTSIDE` 是否送达**）、5 秒自动。
11. 「触摸穿透」通知开关与横幅**互不干扰**（横幅窗独立）。
12. `sendTextsFromPeer = true` 后：发送页标题为「发送给 <对端>」、副标题**仍是真文件名+大小**（收窄生效）、接收页副标题为「JPG · N 张照片」。
13. `ACTION_VIEW` 打开的是系统图库/查看器（不是我们的自绘窗），返回键能回到用户原来的界面。
14. 新一轮碰触：横幅、接收卡、接收中态**全部复位**（`wipeVisuals` + 新收摊点）。
15. 上滑后自动摘窗（§5.3 C-5）在**纯动效演示路径**上的手感（`HomeActivity` → 两参 `intent`）。

**D. 协议文案与字段**
16. `adb logcat -s NfcTapDemo` 里 payload 的 `b=` 到底是什么（`?` 还是真蓝牙名）；确认 D3 的退化链。
17. 长机型名/长蓝牙名 + 热点已开时，payload 是否触发 200 字节截断（看新增的那条日志）。

---

## 附录 A：与两份 changelist 的命名差异速查

| 概念 | `…_INTERACTION.md` | `…_LINK.md` | **本文档（以此为准）** |
|---|---|---|---|
| 接收方"对方开始发了" | `onSendStarted(total)`（两端都收到） | `onSendStarted(int total)`（接收方） | `onSendStarted(int total)`（**仅接收方**） |
| 发送方"被拒" | `onSendRejected()` | `onPeerRejected()` | **`onPeerRejected()`** |
| 发送方"被接受" | — | `onPeerAccepted()` | **`onPeerAccepted()`**（仅日志） |
| 发送方"超时" | — | — | **`onVerdictTimeout()`**（D2） |
| 接收方"对方超时/断开" | — | — | **`onOfferExpired()`** |
| 接收方拒绝/确认 | `rejectIncoming()` | `rejectTransfer()` / `confirmReceived()` | **`rejectTransfer()` / `confirmReceived()`** |
| 失败横幅指令 | `ACTION_FAILURE` / `failureIntent` | `failBannerIntent` / `ACTION_FAIL_BANNER` | **`ACTION_FAIL_BANNER` / `failBannerIntent(ctx,title,detail)`** |
| "本机是发送方"标志 | `mSending` | — | **`mUserWantsSend` + `mSendStarted`** |
| "本轮已拒绝"标志 | `mRejected` | `mRejected`（链路层） | **`mRejected`（只在 `TapService` 一层）** |
| 回执等待上限 | — | `VERDICT_WAIT_MS`(60s) | **`VERDICT_WAIT_MS = 60000`（D2）** |

## 附录 B：关键帧 ↔ 动作速查（含本次修正）

| 帧 | 时刻 | 事件 | 本方案对应动作 |
|---|---|---|---|
| f_0009 | 4.0s | 发送方面板出现 | `onPeer` → `showSendPage()`（§5.2a） |
| f_0014 | 6.5s | 接收卡入场 | `onSendStarted` → `showReceivePage(label,N)`（§5.3 C-1） |
| f_0015 | 7.0s | 卡片已带照片 | 报价阶段的 `PREVIEW` → `photoIntent`（§5.1-3） |
| f_0019 | 9.0s | 点「接收」 | `ACTION_UI_RECEIVE_ACCEPT` → `confirmReceived()`（§4.2） |
| f_0020 | 9.5s | **修正**：不是"胶囊整体消失"，而是**只剩一枚「取消」**（`_Temp/viewer3/A_100.jpg`，t≈10.0s） | 接收中态（§5.3 C-4） |
| f_0021 | 10.0s | **修正**：整卡消散发生在 t≈10.2–10.4s（`A_104.jpg`） | `onTransferDone` → `hideIntent()` → D4（§5.5） |
| f_0046 | 22.5s | 双机同亮发送面板 | `onPeer` 里两端各自 `showSendPage()`（§5.2a） |
| f_0051 | 25.0s | 败方原地变成接收卡 | `switchSendPageToReceive()`（§5.3 C-2） |
| f_0055 | 27.0s | 发送方失败横幅 | `onPeerRejected()` → `failBannerIntent`（§5.4） |
| f_0057–f_0060 | 28–30s | 横幅驻留 | **D1 改为 5 秒自动收起**（与视频不同，用户已定） |
