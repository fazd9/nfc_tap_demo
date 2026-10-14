# 对齐参考产品（OPPO 互传）— 链路层改动清单

> 范围：`nfc/Xfer.java`、`nfc/Bus.java`（协议 / 链路）。**不含** UI 与动效层（`ui/OverlayView.kt`、`service/OverlayService.kt`），**不含** `TapService.java` 的 UI 部分——但 `TapService` 是"下指令 / 收回执"的胶水层，凡是本清单新增的回调与 API，都在第 6 节列出它必须跟着改的那几行。
> 依据：`docs/APP_FLOW_ANALYSIS.md`（代码实证）、`docs/PRODUCT_VIDEO_ANALYSIS.md`（逐帧）。本文所有行号都按当前 `nfc_tap` 分支 `057b617` 的实际源码复核过。
> 目标行为（来自视频）：① 接收方点「拒绝」→ ≤0.5s 发送方弹常驻红色横幅「发送失败 / 对方拒收」；② 接收方点「接收」→ 传输完成、按钮消失，**成功完全静默**；③ 发送方**上滑之后**接收方才有动静（卡片 + 预览一起出现）。

---

## 0. 概述

本清单要解决的核心问题只有一个：**当前协议是单向的，接收方没有任何办法把"我拒绝"告诉发送方。**

- 发送方：`sendImages()` 写完 `TYPE_META` + `TYPE_FULL×N` 后**立刻 `flush()` + `close()`**（`Xfer.java:865-868`），从不读 socket。
- 接收方：读循环靠 **EOF** 判定"发完了"（`Xfer.java:727-730`），退出后在 `finally` 里 `close()`（`Xfer.java:791-793`）；它**没有任何写回动作**（全文件唯一的写入方是 `sendImages` 与 `sendPreviews`，两者都是发送方路径）。
- 所以用户点「拒绝」的那一刻（至少比传输结束晚 1 秒以上，视频里是 2 秒），**两端的 socket 都已经关死了**：接收方早已 EOF，发送方的 `mPendingSocket` 也已经是 `null`（`Xfer.java:868`）。

在此之上，另有 2 个链路层问题会直接破坏视频里的节奏：
- 监听器重复注册（`TapService.java:73` + `Xfer.java:91-93`）→ 每条回调被投递 N 次；
- 预览在**上滑之前**就推给对方（`TapService.java:234-241` → `Xfer.java:887-950`）→ 违背"上滑后才开始传输"。

以及 1 个必须补的协议判定：一旦发送方为了等回执而不再立刻关连接，"接收完成"就不能再用 EOF 表示（详见 A-5）。

**`Bus.java` 本清单不需要任何改动**（新增的帧常量属于 `Xfer` 的 `MAGIC/TYPE_*` 家族；NFC 侧 APDU、payload、角色锁都不动）。

---

## 1. 改动项 A：「拒绝」的反向通道

### A-1 现状（为什么现在做不到）

| 事实 | 出处 |
|---|---|
| 发送方写完就关：`out.flush(); s.close(); mPendingSocket = null;` | `Xfer.java:865-868` |
| 接收方读循环靠 EOF 退出 | `Xfer.java:724-730`（`while (mRunning)` → `in.readInt()` 抛 `EOFException` → `break`） |
| 退出后 socket 被关，且没有任何引用留存 | `Xfer.java:791-793`（`finally { s.close(); }`） |
| 发送方侧对这条连接的唯一引用 `mPendingSocket` 已被置空 | `Xfer.java:99`（字段）、`868`（置空） |
| 接收方没有任何写回接口 | 全文件 `getOutputStream()` 只出现在 `Xfer.java:832`（`sendImages`）与 `906`（`sendPreviews`），都是发送方路径 |

**结论：用户点「拒绝」时，两端都没有一条活着的连接可写。** 所以 A 不是"加一个常量"，而是"选一条回执通路 + 把它在时间上保活"。

### A-2 方案对比与选型

| 方案 | 结论 |
|---|---|
| **(a) 同一条 TCP 连接写回** + 发送方**传完不关、等一条回执** | ✅ **采用** |
| (b) 接收方反连发送方的 8988 监听 | ❌ 不采用 |
| (c) 用"超时未收到确认"反推拒收 | ❌ 不采用 |

**为什么不选 (b)**（尽管两端确实都监听了 8988——`TapService.java:179` 的 `startAsReceiver()` → `Xfer.java:356` → `startServer()`）：
1. 发送方的 accept 循环**无法区分**"数据连接"和"回执连接"：分发靠 `mReceiverRole`（`Xfer.java:677-685`），而 `startAsSender()` 已经把它置回 `false`（`Xfer.java:378`）→ 回执连接会被静静塞进 `mPendingSocket`（`Xfer.java:683`），**永远不会被读**。
2. 那条"没人读的 socket"是有毒的：它会让 `hasLiveLink()`（`Xfer.java:190-193`）为 true → 下一次 `setExpectedPeer` 跳过 `resetRoundState()`（`Xfer.java:167`）→ 下一轮 `sendImages` 拿着这条陈旧 socket 去写。这正是 `Xfer.java:154-166` 注释里记录的"第一轮好、后来再也连不上"那类故障。
3. 接收方要反连就得知道发送方的地址（可从已建立连接的 `s.getInetAddress()` 拿），并且要重新验证**三个通道**里"反向可达"是否都成立——而 (a) 用的就是那条已经被证明能通的连接，不需要任何新的寻址/可达性假设。

**为什么不选 (c)**：无法区分"拒收"和"还没决定"。参考产品要求点拒绝后 **≤0.5s** 出现横幅；超时反推会把"用户正在犹豫"错报成"对方拒收"，而且延迟由超时决定，天然对不齐。

**选 (a) 的额外理由**：参考产品里"一次碰一碰"是一个**事务**（发送方上滑开传 → 接收方表态 → 发送方知道结果）。连接的生命周期应该等于事务的生命周期，而不是"字节写完就结束"。

TCP 是全双工的，两端的 `getInputStream()/getOutputStream()` 互不影响；发送方此前从未读过这条 socket，接收方此前从未写过——加一次"写 + 读"不改变任何既有字节序。

### A-3 新增帧（复用既有帧格式）

在 `Xfer.java:53-56` 的常量区追加：

```java
private static final int TYPE_ACCEPT = 4;   // 接收方回执:已确认接收(len=0,无 payload)
private static final int TYPE_REJECT = 5;   // 接收方回执:拒绝接收(len=0,无 payload)
```

帧格式与既有完全一致：`[MAGIC:int][TYPE:int][LEN:int][LEN 字节]`，两者 `LEN = 0`。方向由"谁写"决定——发送方→接收方是 META/PREVIEW/FULL，接收方→发送方是 ACCEPT/REJECT，同一条连接、两个独立字节流，不会互相插帧。

### A-4 接收方：留住 socket + 两个回执 API（新增）

```java
private volatile Socket mPeerSocket;   // 接收方:正在读的这条连接(回执要写回它)
private volatile boolean mRejected;    // 本轮已拒收:读循环只收不落(见 B)
private volatile boolean mVerdictSent; // 回执只发一次

/** 接收方点「接收」:回执给发送方,发送方据此结束等待。成功静默,UI 无感。 */
public void confirmReceived() { writeVerdict(TYPE_ACCEPT); }

/** 接收方点「拒绝」:① 回执给发送方(发送方弹「对方拒收」) ② 打断本轮一切落地 ③ 删掉本轮已入库的照片。 */
public void rejectTransfer() {
    mRejected = true;
    writeVerdict(TYPE_REJECT);
    deleteRoundSaved();     // 见 B-3
}

private void writeVerdict(int type) {
    if (mVerdictSent) return;
    mVerdictSent = true;
    final Socket s = mPeerSocket;
    if (s == null || s.isClosed()) {
        Bus.log("传输: 回执发不出去 —— 回执通道已关闭(对方已先行断开)");
        return;
    }
    try {
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        out.writeInt(MAGIC);
        out.writeInt(type);
        out.writeInt(0);
        out.flush();
        Bus.log(type == TYPE_REJECT ? "传输: 已回执「拒绝」" : "传输: 已回执「接收」");
    } catch (Exception e) {
        Bus.log("传输: 回执发送异常 " + e);
    }
}
```

`handleIncoming()`（`Xfer.java:716`）的改动：
- 进循环前：`mPeerSocket = s; mRejected = false; mVerdictSent = false;`
- `finally`（`Xfer.java:791-793`）里、`close()` 之前：`if (mPeerSocket == s) mPeerSocket = null;`

**硬性约定：接收方只写不关。** 收到回执后由发送方关连接（A-5）。理由：如果接收方在"自己还有未读入站数据"（传输中途拒收）时关闭，内核会发 RST，RST 会丢弃对端已收到但未被应用读走的数据 —— 刚写出去的 REJECT 帧可能就这样没了。把"谁关"固定成发送方，这个竞态不存在。

`mWriteLock`（`Xfer.java:50`）**不需要**在回执路径上加锁：本端只有这一个写者。

### A-5 发送方：传完不关，读一条回执（新增）

现状收尾（`Xfer.java:865-878`）是：`flush()` → `close()` → `mPendingSocket = null` → post `onImageSent` → 延迟 5s 释放热点网络。

改成：

```java
private static final int VERDICT_WAIT_MS = 60000; // 发送方等对方「接收/拒绝」的上限(=用户的思考时间)

// …… out.flush(); 之后（仍在 sendImages 的线程里）……
mPendingSocket = null;                       // 语义:这是"待发的连接",发出去了就不再是它
final int round = mRound;                    // 轮次令牌,见 A-7
final int n = total;
mMain.post(new Runnable() {                  // onImageSent 的位置**不动**:必须先于回执回调
    @Override public void run() { mListener.onImageSent(n); }
});
readVerdict(s, round);                       // 阻塞最多 VERDICT_WAIT_MS,返回前一定 close
releaseHotspotLater();                       // 见 A-6:热点释放挪到这里
```

```java
/** 发送方:等接收方表态。读到 REJECT/ACCEPT 就回调;超时或对方断开则静默收尾。 */
private void readVerdict(Socket s, final int round) {
    try {
        s.setSoTimeout(VERDICT_WAIT_MS);
        DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(s.getInputStream(), 8 * 1024));
        int magic = in.readInt();
        int type = in.readInt();
        in.readInt(); // len,固定 0,读掉保持帧对齐
        if (magic != MAGIC) {
            Bus.log("传输: 回执头不匹配 " + Integer.toHexString(magic));
            return;
        }
        if (round != mRound) { Bus.log("传输: 收到上一轮的回执,已忽略"); return; }
        if (type == TYPE_REJECT) {
            Bus.log("传输: 对方拒收");
            mMain.post(new Runnable() {
                @Override public void run() { mListener.onPeerRejected(); }
            });
        } else if (type == TYPE_ACCEPT) {
            Bus.log("传输: 对方已确认接收");
            mMain.post(new Runnable() {
                @Override public void run() { mListener.onPeerAccepted(); }
            });
        }
    } catch (java.net.SocketTimeoutException e) {
        Bus.log("传输: 等待对方「接收/拒绝」超时(" + (VERDICT_WAIT_MS / 1000) + "s),断开");
    } catch (Exception e) {
        Bus.log("传输: 未收到回执(对方已断开): " + e);
    } finally {
        try { s.close(); } catch (Exception ignored) {}
    }
}
```

**顺序保证（要写进注释）**：`onImageSent` 与 `onPeerRejected` 是同一条发送线程先后 `mMain.post`，主线程 Handler 是 FIFO → `TapService` 一定先收到 `onImageSent`（清 `PENDING` / 摘发送页），后收到 `onPeerRejected`（弹横幅）。**不要把 `onImageSent` 挪到读回执之后**——那会把发送侧的收尾拖最多 60 秒。

`readVerdict` 放在 `synchronized (mWriteLock)` 块**外面**（和现在的 `s.close()` 位置一致，`Xfer.java:867`）：读操作不该占写锁。

### A-5b 配套（不做就有洞）：把"收完"从 EOF 改成协议判定

发送方不再立刻关连接后，接收方的 `onTransferDone` 就**失去触发源**：它今天只由 EOF 驱动（`Xfer.java:727-730` → `773-784`）。后果是实打实的回归：`TapService.mTransferDone` 会一直为 false，用户点「接收」走的是 `TapService.java:123-125` 的 `"正在接收,收完自动打开…"` 分支，查看器最多 60 秒后才打开。

改法（利用 `TYPE_META` 已经给出的总张数，`Xfer.java:737-744`）：

```java
private volatile boolean mDoneNotified;   // per-connection,进入 handleIncoming 时清 false

// 每张整图 count++ 之后（Xfer.java:756 之后）:
if (expect > 0 && count >= expect && !mDoneNotified) {
    mDoneNotified = true;
    enqueueTransferDone();     // 就是现在 775-783 那段 mIo.execute 的封装
}
// 循环退出后（Xfer.java:773 处）:
if (count > 0 && !mRejected && !mDoneNotified) {
    mDoneNotified = true;
    enqueueTransferDone();
    if (expect > 0 && count < expect) {
        Bus.log("传输: 对方提前断链,只收到 " + count + "/" + expect + " 张");
    }
}
```

- `enqueueTransferDone()` 必须仍然挂在 `mIo` 单线程队列尾部（保持"先解码入库、后通知收完"的既有顺序，`Xfer.java:774-783` 的注释）。
- **循环不能因为收满而 `break`**：收满只是提前通知，读循环要继续阻塞（后续要么等来回执/对方关闭，要么等超时断开）。
- 兼容性：发送端若读图失败会少写帧（`Xfer.java:840-848` 的 `eod` 提前 break），此时 `count < expect` → 提前判定不触发 → 仍由 EOF 兜底，不会漏。

### A-6 热点通道：释放网络必须挪到回执之后

现状：`Xfer.java:870-874` 在 `flush()` 之后 5 秒 `releaseNetworkRequest()`。走 ① 热点通道时，发送方是接收方热点的 **STA**，释放 `NetworkRequest` 会拆掉这条关联 → socket 当场死掉。视频里用户点拒绝是在卡片出现 **2 秒**之后；任何慢一点的用户都会落在 5 秒窗口之外 → **只有热点通道会丢横幅**。

改法：把这段延迟释放从 `flush()` 之后挪到 `readVerdict(...)` **返回之后**（即 A-5 代码里的 `releaseHotspotLater()`，内部仍是原来的 `mMain.postDelayed(..., 5000)`）。

接收方一侧不用改：`closeHotspot()` 本来就在读循环退出之后（`Xfer.java:785-788`），而读循环要到发送方关闭连接才退出 → 天然排在回执之后。

### A-7 轮次令牌（4 行，防串轮）

新增 `private volatile int mRound;`，在 `resetRoundState()`（`Xfer.java:195`）里 `mRound++`；`sendImages` 记下当前值，`readVerdict` 里比对，不等就丢弃。避免"上一轮被挂起的回执在读回执期间落地，把横幅盖到新一轮的发送页上"。

同一处 `resetRoundState()` 还要补两句（与它现有语义一致，它已经会关 `mServer`/`mPendingSocket`）：
```java
try { if (mPeerSocket != null) mPeerSocket.close(); } catch (Exception ignored) {}
mPeerSocket = null; mRejected = false; mSavedUris.clear();   // mSavedUris 见 B-3,这里只清不删
```
被挂起的发送侧 socket 是 `readVerdict` 的局部变量，`resetRoundState` 关不到它 —— 它自己最迟在 `VERDICT_WAIT_MS` 到期时关闭，这是刻意保留的小代价（有 A-7 的令牌保护，不会串轮）。

**A 项复用 vs 新增**：复用同一条已建立的 TCP 连接、复用既有帧格式与 `MAGIC`、复用 `mWriteLock`/`Bus.log`/`mMain` 回调范式、复用 `TYPE_META` 的总张数语义；新增 2 个帧常量、1 个回调（`onPeerRejected`，另加 `onPeerAccepted`）、2 个公开方法（`confirmReceived`/`rejectTransfer`）、3 个字段（`mPeerSocket`/`mRejected`/`mRound` 等）、1 个 `VERDICT_WAIT_MS` 常量、1 个 `readVerdict` 方法。

---

## 2. 改动项 B：「拒绝」要能打断传输

### B-1 现状

| 事实 | 出处 |
|---|---|
| 读循环只判 `mRunning`，不判任何用户意向 | `Xfer.java:724` |
| 每张整图无条件丢给 `mIo` 解码 + 入库 | `Xfer.java:760-770` |
| 入库直接写进相册 `Pictures/NfcTapDemo/nfctap_<ms>.jpg` | `Xfer.java:1011-1032` |
| 收完通知无条件投递 | `Xfer.java:773-784` |
| 于是拒绝后：整图继续入库；`onImageReceived` 继续到达，`!mReceiveShown` 时**把接收页重新弹出来** | `TapService.java:267-270`（§8.2 第 11 条的完整后果） |

### B-2 改法：排空丢帧（drain-to-discard），不粗暴断链

在 `handleIncoming` 里加 3 处守卫 + 1 处判定：

1. 读循环条件：`while (mRunning && !mRejected)`（`Xfer.java:724`）。
2. `in.readFully(data)`（`Xfer.java:748`）**之后**立即 `if (mRejected) continue;` —— 字节必须照读（这正是"排空"），但不再解码、不再入库、不再回调；预览帧同样丢弃。
3. `mIo` 任务体（`Xfer.java:760-770`）第一行 `if (mRejected) return;`（兜住"拒绝时这张已经排进队列"）。
4. `saveToGallery()`（`Xfer.java:1011`）第一行 `if (mRejected) return false;`（兜住"拒绝时这张正在解码/写盘"的竞态）。
5. 收完通知按 A-5b 的写法加 `!mRejected` 条件。

**为什么排空而不是直接 `s.close()` 打断**：
- 关闭时若本端还有未读入站数据，内核发 RST，可能把刚写出的 REJECT 帧一起丢掉（A-4 的同一原因）；
- 发送方的 `out.write` 会因断链抛异常 → 走 `Xfer.java:879-881` → `fail("发送失败: …")`：**用户主动拒收被报成链路失败**，语义错，而且和 D 项"拒收不走 onXferFailed"直接冲突。
- 排空的代价：多发最多几 MB 的字节（局域网上是零点几秒），换来"回执 100% 送达 + 发送方零误报"。

### B-3 已经入库的照片：删掉

参考产品的语义是"拒收 = 什么都没收到"（视频里接收方点拒绝后卡片收起、回到纯照片查看器，没有任何新增内容）。"到货即入库"模型下最贴近的做法是：**把本轮已插入的行删掉。**

- `saveToGallery()`（`Xfer.java:1011-1032`）在 `insert()` 成功后记下 Uri：`mSavedUris.add(u)`（新增字段 `private final java.util.List<Uri> mSavedUris = new java.util.concurrent.CopyOnWriteArrayList<Uri>();`）。
- `deleteRoundSaved()`（新增私有方法）：逐条 `mCtx.getContentResolver().delete(u, null, null)`，无论成败都清空列表，失败只 `Bus.log`，绝不抛。
- `resetRoundState()`（`Xfer.java:195`）只 `clear()` 不删：新一轮开始时上一轮已定局（要么用户接受，要么用户根本没表态——后者今天也是"图留在相册里"，不额外回退）。
- 只删**本应用本次插入的行**（列表里的 Uri 是我们自己 `insert` 出来的），不按目录/时间做批量删除。

**取舍（已排序，非并列）**：更彻底的对齐是"入库推迟到点接收"（参考产品文档 §8.5 说的"确认只决定接不接收/落不落盘"），但那要把每张图的原始 bytes 留在内存里、把 `saveToGallery` 从"到达时"挪到 ACCEPT 分支、还要改 `onImageReceived(..., saved)` 的语义（`TapService.java:275-276` 的"已存入相册"文案）——改动面明显更大，不在本清单内。

**B 项复用 vs 新增**：复用现有的单线程 `mIo` 队列、`saveToGallery`、收完通知的顺序保证；新增 1 个字段 `mSavedUris`、1 个方法 `deleteRoundSaved()`、4 处守卫。

---

## 3. 改动项 C：「发送方真正开始」的信号

### C-1 现状

接收页在**碰触那一刻**就弹：`TapService.onPeer` → `showReceivePage(label)`（`TapService.java:185`）→ `receiveIntent(this, label, 1, …)`（`TapService.java:359`）。而参考产品里，碰触之后到发送方上滑之前，接收方屏幕上**什么都不该发生**（视频 f_0009–f_0013：接收机毫无变化）。

### C-2 信号：`TYPE_META` 就是那个钩子（不需要新帧）

- `TYPE_META` 只在一处写：`sendImages()` 里（`Xfer.java:851-853`）；
- `sendImages()` 只被一处调：上滑回执 `ACTION_UI_SEND_CONFIRMED`（`TapService.java:103-113` → `112`）；
- `sendPreviews` 只写 `TYPE_PREVIEW`，不写 META（`Xfer.java:911-918`）→ 不会误触发。

**"META 到达 == 发送方上滑、真正开传"** 成立且唯一。加一个显式 `TYPE_BEGIN` 帧是多余的：它和 META 永远同时同地发出，只会多一次写 + 多一次读。

### C-3 回调：`onImageCount(int total)` 改名为 `onSendStarted(int total)`

事件、时刻、参数都不变，只改名字与契约：

```java
void onSendStarted(int total);   // 对方(发送方)上滑开始传输:接收方据此弹接收卡片,按钮文案用 total
```

- 投递点仍是 `Xfer.java:741-743`（读到 `TYPE_META` 时）。
- 改名涉及 5 处：接口声明 `Xfer.java:42`、转发器 `Xfer.java:74-76`、投递点 `Xfer.java:741-743`、`TapService.java:254`、`MainActivity.java:504`（不参与编译的死监听器，只为编译同步改名）。
- 理由：现在两个实现方都把它当"顺手更新一下张数文案"用（`MainActivity.java:504-506` 只写一行状态文案），名字会诱导 UI 层忽略"这一刻才是接收方该弹卡片的时刻"这个契约。
- 降级选项（仅供评审，非并列方案）：保留 `onImageCount` 名字，行为一字不改；契约写在本文档里即可。

### C-4 胶水层要跟着改的（TapService，与 UI 层交界）

1. `onPeer` 的接收分支（`TapService.java:182-185`）**删掉** `showReceivePage(label)` 调用，**保留** `x.startHotspot()`。
2. `onSendStarted(int total)` 里弹接收页：`showReceivePage(mPeerLabel, total)`。
3. `onImageReceived` 里的 `if (!mReceiveShown) showReceivePage(...)`（`TapService.java:267-270`）**保留**作兜底（正常路径不会再触发，因为 META 必然先于 FULL）。
4. 顺带（1 行）：`showReceivePage` 里写死的 `receiveIntent(..., 1, ...)`（`TapService.java:359`）改成传真实 `total` —— 同一时刻就有真值，直接修掉 §8.2 第 2 条（按钮文案恒为 1）。

### C-5 节奏核对

META 与整图写在同一个 `synchronized (mWriteLock)` 块里连续发出（`Xfer.java:850-866`），接收方可能在 100ms 内既收到 META 又收到整图 → 卡片与照片"一起出现"，与视频 f_0014（空/占位卡入场）→ f_0015（照片已经在了）一致。

**C 项复用 vs 新增**：复用 `TYPE_META` 帧与既有投递点，**零新帧、零新字段**；只是把一个既有回调改名并把契约写明。

---

## 4. 改动项 D：发送成功 / 失败的回报

### D-1 现状

| 发送方目前能学到的 | 出处 |
|---|---|
| `onImageSent(int bytes)`：`flush()` + `close()` 之后 | `Xfer.java:875-878` → `TapService.java:301-311`（清 `PENDING`、`unlockRole`、摘窗） |
| `onXferFailed(String why)`：写/连异常 | `Xfer.java:879-881`、`965-970` → `TapService.java:313-319` |
| **对方拒收** | **什么都学不到**（今天拒收只影响接收方本机：`TapService.java:127-141`） |

### D-2 新增 `onPeerRejected()`，且**不得**复用 `onXferFailed`

```java
void onPeerRejected();                   // 对方点了「拒绝」:发送方要弹常驻失败横幅
```

`TapService` 侧：

```java
@Override public void onPeerRejected() {
    Bus.log("服务: 对方拒收");
    Bus.unlockRole();
    startService(OverlayService.failBannerIntent(this, "发送失败", "对方拒收")); // UI 层新增动作,见第 6 节
}
```

**不要走 `onXferFailed`**，原因是语义与实现都冲突：
- `onXferFailed` 的语义是"链路失败"；拒收是对方**主动**的选择，链路完全正常；
- `TapService.onXferFailed`（`TapService.java:314-319`）会**补弹发送页**（`if (!mSendShown && !PENDING.isEmpty()) showSendPage()`）并把原因写进底部状态行 —— 此时发送页已经因为 `onImageSent` 摘掉了（`TapService.java:310`），补弹回来等于"用户上滑飞走的卡片又回来了"，明显走味；
- 横幅是**独立于发送页的常驻元素**（视频里发送页早已消失，横幅在相册上方驻留到片尾），状态行扛不住这个语义。

### D-3 新增 `onPeerAccepted()`（仅日志，UI 不消费）

```java
void onPeerAccepted();                   // 对方点了「接收」:成功静默,不做任何提示
```

它存在的意义是让"发送方读到回执"这条路径有确定的终点（而不是等 `VERDICT_WAIT_MS` 超时），`TapService` 里一行 `Bus.log` 即可。**发送方不弹任何成功提示**（参考产品"成功是安静的"，见视频 §6）。

### D-4 顺序与状态

- `onImageSent` **先于** `onPeerRejected`（同线程两次 `mMain.post`，见 A-5），所以 `TapService` 收到拒收回调时：`PENDING` 已清、发送页已摘、角色锁已放 —— 拒收回调只负责"弹横幅"，不碰任何发送状态。
- 横幅的显示前提（**UI 层契约，需与 UI 层对齐**）：必须能在 `hideIntent()` 之后显示，且**不自动消失**（视频里驻留 ≥3s 到片尾）。现有的 `statusIntent` 只改状态行、`intent/receiveIntent` 会重建发送/接收页，都不适用 → UI 层需要一个新动作（见第 6 节）。

**D 项复用 vs 新增**：复用 `onImageSent` 的时机与 `TapService` 现有收尾逻辑、复用 `Bus.log`/`unlockRole`；新增 2 个回调（`onPeerRejected` 必需、`onPeerAccepted` 仅日志）、1 个 UI 层横幅动作、1 段 `TapService` 分支。

---

## 5. 改动项 E：其他影响对齐的链路层问题

### E-1 监听器重复注册（**必须修，阻塞对齐**）

**现状**：`Xfer.addListener` 不去重（`Xfer.java:91-93`），`mAll` 没有出队；`TapService.onStartCommand` 每次启动都 `x.addListener(this)`（`TapService.java:73`），而 `TapService` 是常驻单例服务 → 一次碰触（PEER 一次 + 上滑回执一次）后每轮至少 2 份回调，多碰几轮不断累加（§8.2 第 10 条）。

**是否阻塞视频里的流程：是。**
- 现象级后果：`onTransferDone` 重复投递 → `showViewer(mBmps)` 被调两次（`TapService.java:286-293` 第一次把 `mReceiveShown` 置 false、`unlockRole`；第二次落到 `else if (mAccepted)` 分支 `294-298` 又调一次）→ 两个查看器悬浮窗叠在一起，点一个 ✕ 关不掉另一个。
- 对齐级后果：C/D/B 三项都建立在"事件只发生一次"上（卡片只弹一次、横幅只弹一次、回执只发一次、拒收只落地一次）。带着重复投递去写这些逻辑，每一处都得写幂等防御，等于用一个 bug 去养另外三个。

**改法（1 行）**：

```java
public void addListener(Listener l) {
    if (l != null && !mAll.contains(l)) mAll.add(l);
}
```

`TapService` 是单例实例，`contains` 用引用相等即可去重；`Xfer` 构造函数里的 `addListener(listener)`（`Xfer.java:128`，参数恒为 `null`）已被 `l != null` 挡住。

**不新增 `removeListener`**：全仓没有任何调用方（`MainActivity` 的死监听器从未 `addListener`，§8.2 第 9 条；`TapService` 生命周期与服务相同），加了就是无人使用的接口。

**复用 vs 新增**：纯复用（`mAll` 已是 `CopyOnWriteArrayList`），新增 0 个成员。

### E-2 预览的发送时机（**建议移，语义 + 竞态双重理由**）

**现状**：`sendPreviews` 由 `onP2pReady` 触发（`TapService.java:234-241`）→ **链路一就绪（用户还没上滑）就把缩略图推给对方**；它自己起线程、等最多 10 秒拿 socket（`Xfer.java:896-905`）、最多推 3 张 900px/q82 缩略图（`Xfer.java:929-950`）。

**是否破坏对齐**：
- **可见层面**：只要 C 做了就不破——卡片在 META 前不弹，`TapService.onPreview` 在 `!mReceiveShown` 时只缓存 `mLastBmp`（`TapService.java:244-252`），`photoIntent` 对未建窗的动效层只记 Uri（`OverlayService.kt:267-293` 自己兜底）。所以照片不会"提前露面"。
- **语义层面**：破了"发送方上滑后才真正开始传输"——数据在上滑之前就已经离开发送方手机。这是参考产品明确的对位（视频里接收方在发送方上滑前**毫无变化**）。
- **工程层面**：它是个隐患。预览线程（独立线程 + 最多等 10s）与 `sendImages` 共用同一条 socket 和同一把 `mWriteLock`（`Xfer.java:850` / `908`），谁先抢到锁谁先写 → 可能出现 `PREVIEW` 排在 `FULL` 之后，接收方卡片先换成整图、又被缩略图覆盖回去。A 项让发送方在传输后长期持有这条连接，只会放大这类交错。

**改法（净减少代码）**：
1. 删 `Xfer.sendPreviews`（`Xfer.java:887-950`）、删 `TapService` 的调用与 `mPreviewsSent`（`TapService.java:37, 211, 234-241`）、删 `MainActivity.java:492` 的调用（不删则编译不过；这是因本改动而失效的孤儿调用）。
2. 在 `sendImages` 的 `synchronized (mWriteLock)` 块里，紧跟 META 写 1 张预览：

```java
out.writeInt(MAGIC);
out.writeInt(TYPE_META);
out.writeInt(uris.size());
out.flush();                                    // META 先到:接收方据此弹卡片
byte[] pv = thumbnail(uris.get(0));             // 只推第一张(thumbnail 原样复用)
if (pv != null) {
    out.writeInt(MAGIC);
    out.writeInt(TYPE_PREVIEW);
    out.writeInt(pv.length);
    out.write(pv);
}
for (int i = 0; i < uris.size(); i++) { ... }   // 原样
out.flush();
```

- **只推 1 张**的理由：卡片只消费"最后一张预览"（`TapService.onPreview` → `mLastBmp` → `previewUriOrNull`，`TapService.java:244-252, 369-386`），多推没有消费方；而每张缩略图都要解一次原图（0.2–0.4s），会把整图开传往后拖。
- 顺序由同一把 `mWriteLock`、同一个线程保证，不再有交错。
- 效果正好复刻视频：META → 卡片（占位/空）入场（f_0014）→ 预览帧 → 卡片填上照片（f_0015）→ 整图替换。

**如果评审要求"只做必要改动"**：C 做完之后，把预览留在 `onP2pReady` 表面上看不出问题（照片不会提前露面）—— 但上面第 3 条竞态与第 2 条语义仍然存在。**本清单的推荐是移**，因为它同时删掉了一个公开方法、一个字段、一个独立线程和一类竞态。

**复用 vs 新增**：复用 `TYPE_PREVIEW` 帧、`thumbnail()`、`mWriteLock`；**删除** 1 个公开方法（`sendPreviews`）、1 个字段（`mPreviewsSent`）、1 个后台线程模型；`sendImages` 内新增约 8 行。

---

## 6. 提供给 UI 层的回调（链路层 → 上层的完整契约）

| 回调 / API | 方向 | 触发点（出处） | UI / 胶水层要做什么 |
|---|---|---|---|
| `onSendStarted(int total)` | 接收方 | 收到 `TYPE_META`（`Xfer.java:741-743`） | **此刻才弹接收卡片**（原来在碰触时弹）；按钮文案用真实 `total` |
| `onPreview(Bitmap)` | 接收方 | 收到 `TYPE_PREVIEW`（`Xfer.java:749-754`） | 不变（换卡片照片） |
| `onImageReceived(...)` | 接收方 | 每张整图解码入库后（`Xfer.java:764-768`） | 不变（**B 项之后拒收时不会再投递**） |
| `onTransferDone(List)` | 接收方 | **`count == expect`** 时（A-5b，不再等 EOF） | 不变（"已收到 N 张,请确认接收"） |
| `onPeerRejected()` | 发送方 | 读到 `TYPE_REJECT`（A-5） | **弹常驻红色横幅「发送失败 / 对方拒收」**；不补弹发送页、不写状态行、不自动消失 |
| `onPeerAccepted()` | 发送方 | 读到 `TYPE_ACCEPT`（A-5） | 不用（日志即可）——成功保持静默 |
| `onXferFailed(String)` | 发送方 | 链路异常（不变） | 不变（语义仍是"链路失败"，**不含拒收**） |
| **API** `Xfer.confirmReceived()` | 接收方调用 | `ACTION_UI_RECEIVE_ACCEPT` 分支（`TapService.java:114-126`） | `TapService` 里加一行：`if (sXfer != null) sXfer.confirmReceived();` |
| **API** `Xfer.rejectTransfer()` | 接收方调用 | `ACTION_UI_RECEIVE_REJECT` 分支（`TapService.java:127-141`）**最前面** | `TapService` 里加一行：`if (sXfer != null) sXfer.rejectTransfer();`（其余本地清理不变） |

**UI 层需要新增的一个动作**（不在本清单实现范围，但链路层的横幅依赖它）：

```
OverlayService.failBannerIntent(context, title, detail) / ACTION_FAIL_BANNER
```
要求：(1) 能在发送页已被 `hideIntent()` 摘掉之后显示；(2) **不自动消失**（视频里驻留到片尾）；(3) 位置在屏幕顶部、样式为红色图标 + 两行文字。现有 `statusIntent`（只改状态行）与 `intent/receiveIntent`（会重建发送/接收页）都替代不了它。

**不需要新增的回调**：接收方"我拒收了"不需要回调（UI 层就是动作发起方，自己已经知道）。

---

## 7. 无法对齐或需要取舍的点

1. **回执依赖"发送方在传输后保活连接最多 `VERDICT_WAIT_MS`"**。这是 A 项方案 (a) 的固有代价：正常路径下横幅是"对方 write + 本端 read"的一次往返（局域网上毫秒级，≤0.5s 完全够），但如果用户把卡片晾到超过 `VERDICT_WAIT_MS` 才点拒绝、或任一端进程被杀，回执就发不出去——接收方只记一行日志（`writeVerdict` 的空通道分支），发送方看不到横幅。**对不齐的只有这一种情形**，常量可调（60s 是"用户思考时间"与"不无限挂着 socket/线程"的折中）。
2. **混装（一端跑旧 APK）会退化，两端必须同批升级**。旧发送方 `flush` 后立刻 `close` → 新接收方的读循环立刻 EOF、`mPeerSocket` 被清空 → 拒收回执发不出去（只记日志，不崩）。反向组合更糟：旧接收方从不发回执 → 新发送方要等满 `VERDICT_WAIT_MS` 才关闭连接 → 旧接收方那条"靠 EOF 判定收完"的路径被拖 60 秒。本仓两个 APK 一起改，正常不会出现混装；如果演示现场可能出现，把 `VERDICT_WAIT_MS` 调小（如 30s）。
3. **三个通道的风险：回执本身没有通道差异，① 热点通道有一个必须修的配套**。回执走的就是数据那条连接的反方向（① 热点：发送方是 STA、接收方是 AP，同一条 socket；② 同网；③ Wi-Fi Direct：无论是"发送方做组主"还是"发送方当组员"（`Xfer.java:802-814`），都是那条已经建立的连接）→ **不需要新的寻址/可达性假设，三个通道一致**，这是选 (a) 而不是 (b) 的主要理由。唯一的通道差异是 ①：发送方原本在 `flush` 后 5 秒就释放热点网络（`Xfer.java:870-874`），会拆掉这条连接——已由 A-6 挪到回执之后；接收方的 `closeHotspot()` 本身就在读循环退出之后（`Xfer.java:785-788`），天然在回执之后。**这三条都靠代码时序保证，无法离线证明，需要真机三条通道各验一遍（见第 8 节）。**
4. **最大的语义差异（链路层单独解决不了）**：参考产品是"先给预览报价 → 对方点接收 → 才落盘（甚至才传整图）"；本应用是"上滑就把整图推过去 → 再问要不要"（`Xfer.java:850-866`）。在保住这个模型的前提下，能对齐的是**可见行为**（卡片时机、两个按钮、静默成功、失败横幅、拒收不落地），对不齐的是**字节层面的时机**（拒收时整图其实已经传完了）。彻底对齐要把入库推迟到 ACCEPT（见 B-3 的取舍），改动面更大，建议单独立项。
5. **对等模型 / 双发送方（超出本清单 A–E，但影响对齐）**：参考产品是"两台都亮发送面板，谁先上滑谁是发送方，另一台随即切换成接收卡片"（产品文档 §2.1）；本应用在碰触那一刻就用 `PENDING.isEmpty()` 定死角色，双发送方无仲裁（分析文档 §3.3，`TapService.java:182-193`）。链路层要支持"先上滑者胜出"，需要的是一条"角色改判"消息（例如发送方在 META 之前先广播一条 BEGIN，另一方据此把自己降级成接收方）——属于角色/UI 层决策，本清单只登记这个缺口。
6. **传输中途对方断链（收到 `count < expect`）**：沿用今天的"投 `onTransferDone`"行为，只补一行日志（A-5b）。参考产品没有这个状态，不做额外设计。
7. **改名会牵动死代码与一句注释**：`onImageCount` → `onSendStarted` 要同步改 `MainActivity.java:504`（死监听器，只为编译）与 `OverlayService.kt:864` 的一句注释（纯文字，无行为影响）。
8. **被挂起的回执读取（发送侧）是刻意留下的小代价**：它是 `readVerdict` 的局部变量，`resetRoundState()` 关不到，最坏活到 `VERDICT_WAIT_MS`（1 个线程 + 1 个 socket）；由 A-7 的轮次令牌保证它不会污染新一轮。

---

## 8. 实施顺序（每步都可单独编译验证）

1. **Xfer：接口面一次到位** —— 加 `TYPE_ACCEPT`/`TYPE_REJECT` 常量；`Listener` 里 `onImageCount` 改名 `onSendStarted`，新增 `onPeerRejected()`/`onPeerAccepted()`（可写成 `default` 空实现以减少对死代码的扰动，但 `Xfer.java:67-89` 的转发器必须 override 才能分发）；`addListener` 去重（E-1）。
   → 验证：编译通过（同步改 `TapService.java:254`、`MainActivity.java:504`）。
2. **Xfer：字段与新 API** —— `mPeerSocket`/`mRejected`/`mVerdictSent`/`mSavedUris`/`mRound`/`mDoneNotified` 字段；`confirmReceived()`/`rejectTransfer()`/`writeVerdict()`/`deleteRoundSaved()`；`resetRoundState()` 扩展（A-4、A-7、B-3）。
   → 验证：编译通过，无调用方。
3. **Xfer：接收侧** —— `handleIncoming` 的 socket 留存与 `finally` 清理、drain 守卫（B-2 的 1/2/3/4）、`count == expect` 提前完成 + `onTransferDone` 去重 + `!mRejected`（A-5b、B-2.5）。
   → 验证：旧发送方（不持连接）对本机发图，行为与今天一致（EOF 兜底路径）。
4. **Xfer：发送侧** —— `sendImages` 重构：预览并入（E-2）、`flush` 后不关、post `onImageSent`、`readVerdict()`、热点释放后移（A-5、A-6）；删 `sendPreviews`（E-2）。
   → 验证：编译通过（删 `TapService.java:238`、`MainActivity.java:492`）。
5. **TapService 胶水** —— ACCEPT/REJECT 分支调 `confirmReceived()`/`rejectTransfer()`；接收页改由 `onSendStarted` 弹（C-4），`receiveIntent` 传真实 total；新增 `onPeerRejected()` → 横幅动作；删 `mPreviewsSent` 与其在 `onP2pReady` 里的整块。
   → 验证：编译通过；`OverlayService.failBannerIntent` 由 UI 层同时提供（否则编译不过，需与 UI 层约定先后）。
6. **真机三通道验收** —— ① 对方开热点 ② 同一 Wi-Fi ③ Wi-Fi Direct 兜底，各跑一遍「接收」与「拒绝」：
   - `adb logcat -s NfcTapDemo` 看：`传输: 已回执「拒绝」` → `传输: 对方拒收`；`传输: 对方已确认接收`；
   - 重点量「拒绝 → 发送方横幅」时延（目标 ≤0.5s，三个通道应当一致）；
   - 重点看 ① 热点通道：横幅必须能出现（即 A-6 生效，5 秒释放没有提前拆连接）；
   - 回归：接受后查看器只出现**一次**（E-1 生效），拒收后相册里没有 `Pictures/NfcTapDemo/` 新图（B-3 生效），拒收后接收页不会被整图重新弹出来（B-2 生效）。
