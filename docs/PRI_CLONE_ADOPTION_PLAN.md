# 换机助手可借鉴项改造提案（碰一碰传图 / nfc_tap）

> **性质**：只出方案，不改代码。本文所有“改法”都是设计描述，不包含可直接运行的补丁。
> **对象**：`nfc_tap_demo`（分支 `nfc_tap`，`app/src/main/java/com/pri/nfctouchdemo/nfc/`）。
> **参考**：`docs/PRI_CLONE_STUDY.md`（换机助手拆解）、`docs/COMPARE_LINK_VS_OPPO.md`、`docs/COMPARE_FLOW_VS_OPPO.md`、`docs/OPPO_ADOPTION_STATUS.md`。
> **约束**：
> - 碰一碰是“NFC 交换信息 → 立刻连接 → 几秒到几十秒传几张图”；换机助手是“二维码 + 热点 + 长时间大文件搬家”。**不能照搬它的重试次数、超时值和并发数。**
> - 不修改 `ui/OverlayView.kt`、`ui/ReceiverRenderer.kt`、`ui/SenderParticles.kt`（动画另有负责人）。
> - 安全类“NFC payload 加密/TCP 逐帧加密”已由 `OPPO_ADOPTION_STATUS.md` 的 P0（#1/#7/#13）跟踪；本提案的 session token **只解决连接归属/错误设备**，不替代加密。
> - 所有批次都要能独立编译、独立提交、独立回滚；协议改动默认要求两端同版本 APK（demo 场景可接受）。

---

## 0. 一页纸结论

### 最值得借的 3 件事

1. **心跳 + 最后响应时间超时**（换机助手 `HeartManager`）
   - 它用 1s 心跳、8–24s 无包判死，直接命中我们“对端假死/半开永久卡”的缺陷。
   - **借机制、重写实现**：我们用 2s PING、10s 空闲超时（分块后）+ 写停滞看门狗；不抄它的 `else if` 判死缺陷，也不抄包级 ACK/重传。

2. **分块 + 临时文件 + 每文件校验/续传**（`FileTransfers` / `ReceiveFileTransfers` / `MergeFileManager`）
   - 它把大文件切成 4MB 分包、再切成 10–70KB 线块；接收端写临时文件、MD5 通过才落地、已存在则跳过。
   - **借概念、重写**：我们用 64–256KB 分块 + 每图 SHA-256 + `cacheDir` 临时文件 + 图片级续传；不做 4MB 分包/合并。

3. **首帧握手 + 显式完成握手 + 存储预检**（`RadarConnectNewActivity` / `RadarWaitConnectActivityModel` / `SendActivityModel`）
   - 它在传任何数据前先做 hello/文件清单/剩余空间检查；结束时用 `TASK_FINISH` 双向确认。
   - **借结构、加认证**：我们用 NFC 带来的 session token 做 `HELLO/HELLO_ACK`，用 `DONE/DONE_ACK` 替代“EOF 即完成”，并在 ACCEPT 前做存储预检。

### 最不该借的 3 件事

1. **UDP 端口通告/所谓“发现”**
   - 它的 UDP 不是组播发现，而是向二维码里的 AP IP **单播**“我的 TCP 端口”，4 包/s、无 session、无源校验（`UdpClientBroadcast.java:32-52`；`UdpService.java:40-49`）。
   - 我们的 NFC payload 已经带 IP/热点信息，再引入 UDP 只增加一个可被伪造的入口。**不借**（P2 以后若做“无 NFC 入口”再议）。

2. **20 路并发 TCP + 包级 ACK/重传 + 自适应块大小**
   - 它的 20 条数据连接、每包 ACK、3s 重发、10–70KB 块大小自适应，是为“几十 GB、上千文件”设计的；对几张照片是过度设计，且 TCP 本身已保证可靠/有序。
   - 我们只保留“序号/offset”用于续传，**不借**包级可靠层、不借 20 连接、不借块大小自适应。

3. **二维码 + AP-first（先开 AP 再扫码）+ 30 次重试/8–24s/24h 式长迁移参数**
   - 它能“先开热点再扫码”，是因为有二维码这一步给 AP 启动留了几秒；碰一碰没有这一步，本轮 NFC payload 不可能带上刚开的热点凭据。
   - 它的 30 次文件重试、8–24s 心跳、24h 传输上限都不适合“贴一下传几张图”。**不借流程和数值**（只借“单一 AP owner + 凭据先于连接 + 结束释放”的原则，见 P1-5）。

### 推荐的第一批改动（P0，两个可独立提交的 commit）

**目标**：把“同时上滑互等 60s”“任意设备连 8988 抢占发送 socket”“`Android*` 连错设备”三个问题在一个连接层改造里收掉，并为后续分块/续传打协议基础。

- **1a 可信首帧**：NFC payload 加 `sid`（128-bit）；每条 TCP 连接首帧 `HELLO {ver,sid,role,hasPhotos}`，接收侧校验后才置 `mGotConnection`/暂存 socket（1b 拆分为 `mStashedSocket`）；连接侧校验 `HELLO_ACK`；去掉 `startsWith("Android")` 通配；加首帧读超时和显式长度上限；payload 超长改为显式失败。
- **1b 角色收敛**：把 `mPendingSocket` 拆成 `mSendSocket`（出站发送）与 `mStashedSocket`（入站探测/接收）；上滑时先发 `ROLE(SEND)`，再 300ms 探测对端 `ROLE/META`；双方都想发时按 `sid` 确定性裁决，败方立即转接收页、读取对方报价，胜方继续等 ACCEPT；`readVerdict` 忽略非回执帧。先滑者优先；300ms 内同时滑则 `sid` 小者（或大者）优先。

**涉及文件**：`nfc/Xfer.java`、`nfc/TapService.java`、`nfc/Bus.java`、`res/values/strings.xml`；**新增** `nfc/Proto.java`（帧常量/读写/HELLO，约 120–160 行）。
**预计改动量级**：1a 约 280–380 行；1b 约 200–320 行；合计约 480–700 行。
**验证**：顺序发送、同时上滑（两种先后）、第三方静默连 8988、错误 token、两台同型号手机、payload 超长。

---

## 1. 优先级总表

| ID | 问题 | 换机助手的做法 | 判断 | 优先级 | 批次 |
|---|---|---|---|---|---|
| P0-1 | 两端同时上滑互等 60s（`Xfer.java:762-768`；`TapService.java:401-403`；`Xfer.java:1030-1054`） | 角色产品固定；接收方连、发送方服务；首帧握手 | **重写**（借“角色先定”原则） | P0 | 1b |
| P0-2 | 接收 `readInt` 无超时、发送 `write` 无超时、无心跳、假死永久卡（`Xfer.java:804-841,997-1013`） | `HeartManager` 1s 心跳 / 8–24s 判死；`DataManager` 3s 重发 | **借用机制、重写实现** | P0 | 2 |
| P0-3 | 8988 无认证，任意设备可抢占 `mPendingSocket`（`Xfer.java:759-768,922`） | 同样没有认证；但先首帧握手再读数据 | **不借安全模型；借握手结构，自研 token** | P0 | 1a |
| P0-4 | `Android*` 通配寻址，多机连错（`Xfer.java:653-665`） | 二维码精确 IP + UDP 通告端口，无名字通配 | **借原则、重写** | P0 | 1a |
| P1-1 | 整图一帧、无分块/序号/长度校验、EOF 当完成（`Xfer.java:57-62,816-841,879-883,997-1013`） | 4MB 分包 + 10–70KB 线块 + `TASK_FINISH` | **借概念、重写** | P1 | 3 |
| P1-2 | 无断点续传/无校验/无存储预检（`Xfer.java:1216-1233`） | 每文件/分包 MD5；临时文件；已存在跳过；ACCEPT 前查空间 | **借概念、重写** | P1 | 4 |
| P1-3 | 只有 x/y 计数，无字节/速率/进度模型（`Xfer.java:43-45`；`TapService.java:419-431`） | 总百分比 + 速度字符串，UI 与模型解耦 | **借模型、重写** | P1 | 3 |
| P1-4 | 重试混层、线性退避、无阶段/错误码（`Xfer.java:138,600-615,1030-1054`） | 连接/包/文件分层重试（但无退避、30 次） | **借分层原则、重写策略** | P1 | 5 |
| P1-5 | 碰后才开热点、双方都开 AP、本轮 payload 无凭据（`TapService.java:318-323`；`Xfer.java:259-332`） | 接收方先开 AP，二维码发凭据，单一 AP owner | **借原则、重写**（AP owner + `sid` 派生凭据） | P1/P2 | 6 |
| P2-1 | UDP 发现、20 连接、包级 ACK、4MB 分包、二维码等“其他优点” | 见 §3 逐项 | **多数不借** | P2 | 不入批 |

---

## 2. 逐项提案

### P0-1 两端同时上滑：从“互等 60s”改为“确定性角色收敛”

**问题（我们的代码）**

- 碰后两端都 `startAsReceiver()`、都监听 8988（`TapService.java:318`；`Xfer.java:383-417`）。
- `mPendingSocket` 一个字段同时承担两种角色：主动连出的发送 socket（`Xfer.java:479,555`）和 accept 进来暂存的入站 socket（`:747`）。双方同时连接时这两个 socket 会互相覆盖，单字段无法同时“发自己的报价”和“探测对方的报价”。
- 两端都上滑后，`mReceiverRole=false`；accept 循环看到 `!mReceiverRole` 就把对方连进来的 socket 直接 `stashForSending`，**不读**（`Xfer.java:762-768`）。
- 各自的 `sendImages` 在出站 socket 上写 `META+PREVIEW`，然后 `readVerdict` 等 60s（`Xfer.java:956-980,1030-1054`；`VERDICT_WAIT_MS=60000` 在 `:63`）。
- 对端即使读到 `META`，`TapService.onSendStarted` 第一行 `if (mUserWantsSend) return;` 也会丢弃（`TapService.java:401-403`）。
- 结果：双方都报“对方未响应”。已有文档 `COMPARE_LINK_VS_OPPO.md` §2.4/§8 #2 与 `COMPARE_FLOW_VS_OPPO.md` §3.3 已把代码路径写清；**未找到真机通过记录**。

**换机助手的做法**

- 没有这个竞态：角色由产品固定（新机=接收方，老机=发送方），谁开 AP、谁当 TCP Server、谁当 Client 在进入连接页之前就定了（`RadarConnectNewActivity.java:62-254`；`RadarWaitConnectActivityModel.java:28-81`；`MainActivity.java:66-90`）。
- 连接建立后先做 hello/文件清单/存储检查，再允许传数据（`RadarConnectNewActivity.java:445-463`；`RadarWaitConnectActivityModel.java:161-200`）。
- 结论：它没有“平局裁决算法”可抄；可抄的是**在传输前就把角色变成双方共识**，而不是靠“谁先写 META”事后推断。

**判断：重写（借原则，不借代码），P0**

理由：这是正常用户（两台都有照片、几乎同时上滑）就会触发的流程正确性 bug，不是极端异常；必须修，但不应引入换机助手的新机/老机产品模型。

**具体改法**

1. **角色字段进入首帧**（与 P0-3 共用）：
   - NFC payload 增加 `sid`（每轮/每进程随机 128-bit，hex 32 字符）。
   - 每条 TCP 连接首帧 `HELLO {ver=2, sid, role, hasPhotos}`：
     - `role = UNDECIDED`：碰后还没上滑；
     - `role = SEND`：本机已上滑，想发；
     - `role = RECV`：本机已决定接收（无照片或已放弃发送）。
   - 接收侧回 `HELLO_ACK {sid, role}`；连接侧校验 `sid` 后才继续（见 P0-3）。
2. **先把发送/暂存 socket 拆开**：
   - 新增 `mSendSocket`（主动连出、`sendImages` 使用）与 `mStashedSocket`（accept 进来、探测/接收使用）；`resetRoundState()` 两者都清。
   - 这是本项的前置重构：当前 `mPendingSocket` 同时被两种用途覆盖，不拆开就无法在“自己发报价”的同时“探测对方报价”。
3. **上滑时先发 ROLE，再探测**：
   - `setSenderRole()` 后，若 `mSendSocket != null`，**立即**在 `mSendSocket` 上发 `ROLE(SEND)`（或在已建立的连接上补发）；然后对 `mStashedSocket` 做 300ms 探测：
     - 读到 `ROLE(SEND)` 或 `MAGIC+TYPE_META` → 对端也想发，进入冲突裁决；
     - 读到 `HELLO/ROLE(RECV)` → 记录对端角色，继续等剩余时间；
     - 超时无数据 → 认为对端未发，正常发送自己的报价。
   - 顺序必须是“先发 ROLE，再探测”：如果双方都先探测再发，双方会在 300ms 后同时发 META，仍然可能互相读到对方的 META 而不是回执。
4. **确定性裁决**：
   - 规则：**先上滑者优先**；如果双方在 300ms 窗口内都上滑，则比较 `sid`（字典序小者/大者固定选一个，建议小者胜），NFC 角色（reader/card）作为最终 tie-break（`sid` 不应相等）。
   - 败方：
     - 置 `mUserWantsSend=false`、`mCanSend=false`、`mReceiverRole=true`；
     - 中止自己的 `sendImages`（关闭/放弃 `mSendSocket`，避免继续写）；
     - 把 `mStashedSocket` 交给 `handleIncoming`，并把已读到的 `META` 总张数传进去；
     - 调 `TapService.switchSendPageToReceive()`（已存在，`TapService.java:596-604`），让用户看到接收卡；
     - 用户点接收 → 在 `mStashedSocket` 回 `ACCEPT`，胜方 `readVerdict` 成功。
   - 胜方：保持发送，等 `ACCEPT`；如果先收到败方的 `ROLE(RECV)`/`YIELD`，继续等 `ACCEPT` 即可（不要因为对端放弃发送就自己转接收）。
   - **`readVerdict` 要能丢弃非回执帧**：如果败方的 `META/PREVIEW` 晚于裁决到达，胜方的 `readVerdict` 目前读到第一帧就返回 0（`Xfer.java:1030-1054`）；改成循环读，忽略 `ROLE/META/PREVIEW` 等已知非回执帧，直到 `ACCEPT/REJECT` 或超时。
5. **accept 循环不再“静默 stash”**：
   - 先读 `HELLO`（1–2s 超时），校验 `sid`；`role=SEND` 且本机也是 SEND → 立即走上面的裁决；`role=RECV` → 直接 `handleIncoming`；`UNDECIDED` → 保留 socket 并挂一个短探测（或等本机上滑时探测）。
   - 未认证/超时的连接：关闭，**不置** `mGotConnection`、不写 `mStashedSocket`。
   - accept 线程只做 `accept`；`HELLO` 读取放到独立线程或小线程池（上限 4 个未认证连接），避免一个静默客户端阻塞所有 accept（换机助手的 accept 线程会被 `peerSpeaksFirst` 的 3s 探测占用，我们不能复制这个 DoS 面）。
6. **失败兜底**：如果 300ms 探测和 `HELLO` 都没能收敛（极端调度），保留“收到对端 `META` 且本机 `mUserWantsSend`”时回 `TYPE_REJECT`/`TYPE_BUSY` 的快速失败路径，文案“双方同时发送，请稍后重试”，绝不静默等 60s。

**改动面**

- `nfc/Xfer.java`：`mSendSocket`/`mStashedSocket` 拆分、角色状态、`probePeerOffer`、accept 分派、`sendImages` 中止、裁决、`readVerdict` 循环（约 200–320 行）。
- `nfc/TapService.java`：`onSendStarted` 冲突分支、败方转接收、状态文案（约 30–60 行）。
- `nfc/Bus.java`：`sid` 生成/解析/传递（约 15–30 行）。
- `res/values/strings.xml`：冲突/失败文案（约 5 行）。
- 新增 `nfc/Proto.java`（若 1a 已建，1b 只扩展；约 0–60 行）。

**风险**

- 300ms 探测增加发送延迟；对“秒传”体验有轻微影响。可只在“对端 `hasPhotos=true`”时探测，纯接收方不探测。
- 双方同时上滑时，UI 可能先显示发送页再切接收页；需保证切换幂等，且不要播放两次动画（动画层不改，靠 `switchSendPageToReceive` 的现有幂等逻辑）。
- 若败方已经写出 `META`，胜方可能收到两份报价；胜方应以 `sid` 对应的一方为准，忽略/关闭重复连接。
- 两台手机时间不同步不影响 `sid` 裁决；不要用时间戳做 tie-break。
- 需要真机覆盖“同时上滑”“一先一后”“一方中途放弃”三种路径。

---

### P0-2 连接活性：读/写超时 + 心跳 + 停滞检测

**问题（我们的代码）**

- 接收端 `handleIncoming` 用 `DataInputStream.readInt()`/`readFully()` 阻塞，**没有 `setSoTimeout`**（`Xfer.java:804-841`）；只有 `readVerdict` 设过 60s `SO_TIMEOUT`（`:1034`）。
- 发送端 `out.write(data)` 没有写超时（`Xfer.java:997-1013`）；对端不读时，发送线程可能永久阻塞。
- 全文件没有心跳/PING；接收异常只 `Bus.log`，不回调失败（`Xfer.java:895-900`）；接收页可能永远停住。
- 发送端等回执 60s（`:1030-1054`）；接收端“等报价”没有超时（`TapService.java:549-557` 没有计时器）。

**换机助手的做法**

- `HeartManager`：每 200ms 巡检；1s 没写就发 16 字节心跳；8s（数据连接）/24s（控制连接）没收到任何有效包就 `closeSocketStream()` + `onConnectionError("Client error 连接超时")`（`HeartManager.java:29-60`；`TcpClient.java:216,459-475`）。
- `TcpClient` 设置 `TCP_NODELAY`、`SO_KEEPALIVE`、256KB 收发缓冲（`TcpClient.java:467-471`）。
- 但它**没有 `setSoTimeout`**（全工程 grep 无命中），读线程是 `available()+sleep(1)` 忙等（`TcpClient.java:58-60`）；`HeartManager` 的超时判断在 `else if` 分支，写阻塞时可能不判死（`HeartManager.java:43-49`）。所以只能借机制，不能照抄实现。

**判断：借用机制、重写实现，P0**

理由：这是“用户不知道对方没点、网断了还是 App 卡死”的根因；比继续增加连接重试次数收益大。TCP keepalive 的时间尺度（小时级）不适合碰一碰。

**具体改法**

1. **统一帧读超时**：`Proto.readFrame(in, timeoutMs, maxLen)`，所有连接读路径都带超时；`Socket.setSoTimeout` 在连接建立后设置。
   - 报价/回执阶段：20s（替代 60s；产品可调到 15–25s）。
   - 传输阶段：分块前先 30s；分块后降到 10s（块间应有数据/PING）。
   - 超时 → `onXferFailed("连接超时")`/`onOfferExpired()`，接收端也要收窗/解锁。
2. **应用层心跳**：
   - 新类型 `TYPE_PING`/`TYPE_PONG`（或复用现有类型加子命令，建议新类型，语义清晰）。
   - 空闲 2s 发 PING；收到任何帧刷新 `lastRxAt`；10s 无帧判失败。
   - 接收端的读循环顺手回 PONG；发送端在 `readVerdict` 阶段和传输阶段各有一个轻量读线程/心跳线程（或一个统一读线程）。
   - 心跳只在“连接已建立且未结束”时跑，结束后立即停，避免下一轮串扰。
3. **写停滞看门狗**：
   - 用 `CountingOutputStream` 包住 socket 输出，每次 `write` 更新 `lastWriteProgressAt`。
   - 一个看门狗线程每 2s 检查：传输中若 `now-lastWriteProgressAt > 10s`，关闭 socket（`close()` 会解开阻塞的 `write`），回调失败。
   - 这样即使 `write` 卡在巨大的 `FULL` 帧里，也能在 10s 内脱身；分块后每个块都会刷新时间，判定更准确。
4. **接收端失败必须回调**：`handleIncoming` 的 `catch` 里按 `count`/`expect`/`mVerdictSent` 区分：
   - 报价阶段断开且未回执 → `onOfferExpired()`；
   - 传输中异常/超时 → `onXferFailed("传输中断")`；
   - `count<expect` 且 EOF → 失败，不是完成（配合 P1-1 的 `DONE`）。
5. **总时限**：照片场景设 5 分钟总上限（或按 `总字节/最低速率` 动态），不是换机助手的 24h。
6. **明确不借**：不引入包级 ACK/重传（TCP 已保证）；不把心跳实现成 `DataManager` 那样的队列，避免无界重发。

**改动面**

- `nfc/Xfer.java`：`setSoTimeout`、`Proto.readFrame` 调用、PING/PONG、看门狗、失败回调（约 120–200 行）。
- `nfc/TapService.java`：接收失败横幅/收窗/解锁；状态文案（约 30–50 行）。
- `res/values/strings.xml`：超时/中断文案（约 5 行）。
- `nfc/Proto.java`：PING/PONG 常量与读写（约 40 行；若 1a 已建则扩展）。

**风险**

- 慢链路/大图解码时误判超时：解码在 `mIo` 线程，不占 socket 读线程；但 `readFully` 一个大帧可能确实超过 10s。分块前用 30s，分块后再收紧。
- 心跳帧与业务帧并发写需要 `mWriteLock` 保护，避免帧交错；现有 `mWriteLock` 可复用（`Xfer.java:54,957-971,998-1011`）。
- `Socket.close()` 从看门狗线程调用是安全的，但接收端要处理 `SocketException` 并只回调一次（用 `AtomicBoolean` 防重）。
- 不要在 `onPause`/后台时误判：连接生命周期与前台状态解耦，只有连接真正断/超时才失败。

---

### P0-3 连接归属与寻址：session token + 首帧认证 + 去掉通配

**问题（我们的代码）**

- `startServer` 在 `accept()` 后立刻 `mGotConnection=true`（`Xfer.java:759-761`），然后：
  - `!mReceiverRole` → `stashForSending(s)`（`:762-768`）；
  - `mCanSend` 的接收方也会把静默连接 stash（`:767-768`）；
  - `sendImages` 优先使用 `mPendingSocket`（`:922`）。
- 服务端不校验来源；帧只有 `MAGIC/TYPE/LEN`（`Xfer.java:57-62`）；没有 token/会话/身份。
- `mGotConnection` 还会抑制 P2P 兜底（`Xfer.java:400-403`），所以一个静默的陌生连接可以让本机不再搜索。
- P2P 匹配在对方没有 `p/a` 时接受任意 `Android*`（`Xfer.java:653-665`，通配在 `:660`），多台同 App 手机时可能连错。
- NFC payload 里的 `t=` 只是 8 位进程随机串，仅用于显示/兜底，不参与 TCP 校验（`Bus.java:163-168`；`TapService.java:613-614`）。

**换机助手的做法**

- 同样**没有**应用层认证；但它的端点来自二维码里的精确 `ip`，没有名字通配（`OldSelectSendActivity.java:601-607`），且所有数据前先有 hello/文件清单/存储检查（`RadarConnectNewActivity.java:445-463`；`RadarWaitConnectActivityModel.java:161-200`）。
- 它的“安全”主要是私有 AP + 二维码物理接近 + 随机端口，不是可照抄的认证模型。

**判断：不借安全模型；借“首帧握手”结构，自研 token；P0**

理由：我们无法把 8988 变成私有 AP 内的端口；必须让“连上”不等于“被信任”。session token 是最小、可独立落地的修复，能同时解决 `mPendingSocket` 抢占和 P2P 连错（连错也会因 token 不匹配而失败）。

**具体改法**

1. **NFC payload 加 `sid`**：
   - `sid` = 128-bit `SecureRandom`，hex 32 字符；每轮/每进程生成，放在 `t=` 附近（靠前，避免被尾部截断）。
   - 不打印 `sid` 到日志；`Bus.setHotspotInfo` 现有的密码日志（`Bus.java:67`）也应删除（属于 OPPO plan #1，但顺手可做）。
   - `MAX_PAYLOAD=200`（`Bus.java:41`）与静默截断（`Bus.java:96-113`）：先加显式长度检查；若加 `sid` 后实测超 200，提高到 240（ISO-DEP 短 APDU 单字节 Lc 上限 255，240 + 5 + 1 = 246 安全），或把 `ts/n/b` 等非关键字段后置/裁剪。**不要继续静默截断**。
2. **首帧 `HELLO`**：
   - 连接侧在 `connect()` 成功后立刻发 `HELLO {ver, sid, role, hasPhotos, nonce}`。
   - 接收侧在 `accept()` 后先读 `HELLO`（1–2s 超时，最大长度小），校验：
     - `sid` 等于本轮 NFC payload 里的对端 sid；
     - 版本兼容；
     - 否则关闭连接，**不**置 `mGotConnection`、**不**写 `mPendingSocket`。
   - 接收侧回 `HELLO_ACK {sid, role}`；连接侧校验后，才允许发 `META/PREVIEW` 或接收数据。
   - 后续所有帧都在已认证连接上；不需要每帧 MAC（TCP 连接已绑定）。
3. **绑定来源（可选加强）**：
   - 同 LAN：校验 `socket.getInetAddress()` 等于 `mPeerLanIp`；
   - P2P：校验 socket 地址属于 P2P 组网段（`192.168.49.x`），或至少校验 token；
   - 热点：token 足够；热点密码随机化由 OPPO plan #13 处理。
4. **去掉 `Android*` 通配**：
   - `matchesPeer` 只接受 `p2pAddr` 精确相等或 `p2pName` 精确相等；两者都为空时**不进行 P2P 连接**，记录“对方 P2P 信息缺失，请重新碰触/改用同网或热点”。
   - 不再用 `n`/`b` 做 P2P 匹配兜底；显示名只用于 UI。
5. **未认证连接的资源控制**：
   - accept 线程只 `accept`，`HELLO` 读取放独立线程/小线程池；
   - 最多同时 4 个未认证连接，超时/超限关闭；
   - 未认证连接不抑制 P2P 搜索、不影响 UI 状态。
6. **安全边界说明**：
   - `sid` 在 NFC payload 中是明文，能被物理近距离读卡器拿到；它防的是“同热点/LAN 的陌生客户端”和“连错设备”，不是防物理 NFC 攻击。真正的加密/认证由 OPPO plan 的 P0（#1/#7/#13）继续做。

**改动面**

- `nfc/Bus.java`：`sid` 生成/解析/传递；payload 长度显式失败；删除敏感日志（约 20–40 行）。
- `nfc/Xfer.java`：`HELLO/HELLO_ACK`、accept 分派、token 校验、来源校验、去掉通配、未认证连接处理（约 80–150 行）。
- `nfc/TapService.java`：把 `sid` 传给 `Xfer.setExpectedPeer`；失败文案（约 5–15 行）。
- `res/values/strings.xml`：认证失败/信息缺失文案（约 5 行）。
- `nfc/Proto.java`：HELLO 编解码、帧长度上限（约 30–60 行）。

**风险**

- **payload 长度**：`sid` 可能让 200B 上限溢出；必须先做显式检查/提上限，并在真机验证 HCE/读卡两侧都能收发。
- **兼容性**：旧 APK 不认 `HELLO`，会当成坏帧/EOF；demo 要求两端同版本。若必须兼容旧版，用 `MAGIC2` 让旧版直接失败并提示升级，而不是半兼容。
- **NFC 明文 token**：不要把它当秘密；不要写日志；不要复用到下一轮（每轮生成）。
- **accept 资源**：`HELLO` 读取放线程池，避免慢客户端阻塞正常连接（这是换机助手 accept 线程的教训）。
- **测试**：第三方 socket 静默连接、错误 token、正确 token、两台同型号、P2P 无 `p/a` 时不应连接。

---

### P1-1 分块交付 + 长度校验 + 显式完成 + 进度

**问题（我们的代码）**

- 帧格式 `[MAGIC][TYPE][LEN][data]`，无版本、无序号（`Xfer.java:57-62`）。
- 接收端 `new byte[len]` 直接用对端长度，**没有上限/负值校验**（`Xfer.java:840`）；坏 `LEN` 可导致异常/大内存。
- 每张整图一个 `FULL` 帧，`readAll` 把整张读进内存，整帧一次 `write`（`Xfer.java:982-1010,1186-1197`）。
- 完成判定 `count>=expect` 或 EOF；EOF 时 `count<expect` 也当完成（`Xfer.java:871-883`），少发/截断不报错。
- 进度只有 `onImageReceived(index,total,...)`（`Xfer.java:43-45`；`TapService.java:419-431`），没有字节/速率。

**换机助手的做法**

- 4MB 分包 + 10–70KB 线块 + 每块 `lastFlag/index`（`BlockFileManager.java:162-183`；`FileTransfers.java:135-181`）。
- `TASK_FINISH` 双向完成握手（`SendActivityModel.java:364-366`；`ReceiveActivityModel.java:631-673,705-708`）。
- 进度 = 已完成文件/总大小，1s 一次，速度字符串（`SendActivityModel.java:351-378`）。
- 但它的进度粒度是文件/4MB 块，不是字节；完成也可能在“丢弃 30 次失败文件”后仍然成功（`SendActivityModel.java:309-338`）。这两点不要照抄。

**判断：借概念、重写，P1**

理由：分块是“进度、停滞检测、续传、内存峰值”的共同基础；不做分块，后面的心跳/续传都只能做粗粒度。

**具体改法**

1. **协议 v2**：
   - 建议 `[MAGIC2 4B="NFC2"][VER 1B][TYPE 1B][SEQ 4B][LEN 4B][payload]`，或保留现有 3 个 int、在 `DATA` payload 里带 `seq`。前者更干净，旧版会因 `MAGIC2` 直接失败并提示升级。
   - `LEN` 上限：单帧 payload ≤ 1MB（chunk 最大 256KB + 头）；总图片大小不设帧上限，由分块承载。
   - 所有读路径统一走 `Proto.readFrame`；`LEN<0 || LEN>MAX` 立即失败，不分配。
2. **类型**：
   - `TYPE_FILE_BEGIN {index,total,size,sha256,mime}`（每张图一次）；
   - `TYPE_DATA {seq,last,bytes}`（64–256KB，建议 128KB 起）；
   - `TYPE_DONE {count,totalBytes}` / `TYPE_DONE_ACK`；
   - `TYPE_CANCEL {reason}`（可选，但建议同批做，解决“接收后无法取消”）。
3. **发送端**：
   - 边读边发，不再 `readAll` 整张；`readAll` 只用于缩略图/小图。
   - 每块刷新写进度（供 P0-2 看门狗）；每张开始/结束更新 `TransferProgress`。
   - 收到 `DONE_ACK` 才算成功；没有 ACK 超时 → 失败，不静默成功。
4. **接收端**：
   - 每张写临时文件（见 P1-2），按 `seq` 顺序写；`last=1` 后校验大小/哈希。
   - `DONE` 时校验 `count==expect` 且 `totalBytes==sum`；不一致 → 失败/提示，不再把 EOF 当完成。
   - 传输中失败/超时 → 关闭、回调、清理临时文件（或保留供续传）。
5. **进度模型**：
   - 新增 `TransferProgress`：`state, index, total, totalBytes, transferredBytes, speedBps, startedAt, error`。
   - 发送端按“写入 socket 的字节”更新；接收端按“读到的字节”更新；4–10Hz 节流。
   - `TapService` 状态行显示“正在发送 3/8 · 42% · 1.2 MB/s”；接收端“已接收 12.4/30.0 MB”。只复用现有 `OverlayService.statusIntent`，不改动画文件。
6. **完成语义**：`DONE/DONE_ACK` 替代 EOF；EOF 只作为异常断开的信号（若已收完且校验通过，可容错，但必须有日志/结果）。

**改动面**

- `nfc/Xfer.java`：帧读写、发送/接收主循环、文件循环、DONE（约 250–400 行）。
- `nfc/TapService.java`：进度/取消/失败状态、接收端 DONE 处理（约 50–80 行）。
- `nfc/Proto.java`：v2 帧/类型/上限（约 40–80 行）。
- 新增 `nfc/TransferProgress.java`（约 60–80 行）。
- `res/values/strings.xml`：进度/完成/取消文案（约 10 行）。

**风险**

- 这是最大的一次协议重写；必须和新旧两端一致，旧版直接提示升级。
- 图片解码/落盘仍在 `mIo` 单线程；分块后内存峰值下降，但落盘队列仍可能积压。建议落盘队列有上限（如 4 张）并反压读线程。
- `CANCEL` 的 UI 入口需要 `OverlayService` 新增 action；若不想动动画层，可先只做“对端取消/失败”的协议接收，UI 取消入口后置。
- 测试：1/10/100 张、20MB 单图、慢链路、传输中杀进程、计数不一致、重复块。

---

### P1-2 校验、断点续传、存储预检

**问题（我们的代码）**

- 没有校验和/哈希；坏帧/截断只能靠 JPEG 解码失败或 EOF 猜（`Xfer.java:816-841,879-883`）。
- 没有断点续传：任何中断都整轮重来；`sendImages` 每次从第一张开始（`Xfer.java:918-1026`）。
- 接收端没有存储预检；`saveToGallery` 直接插入 MediaStore，失败只返回 null（`Xfer.java:1216-1233`）。
- 接收端每张图直接写最终 MediaStore；失败/重复可能留下半成品或重复项。

**换机助手的做法**

- 每文件/4MB 分包 MD5；临时文件；`prepareReceiveFile` 已存在且 MD5 通过则跳过；`isFileAlreadyPresent` 按大小/APK 已安装跳过；`RandomAccessFile.seek(transferredSize)` 从分包起点续传（`ReceiveFileTransfers.java:54-81,89-124,131-154`；`SendFileTransfers.java:52-53`）。
- 握手阶段先查剩余空间，不足直接拒绝（`RadarConnectNewActivity.java:335-369`）。
- 它用 MD5（不是 SHA-256）；4MB 分包/合并是为大文件；这两点我们改写。

**判断：借概念、重写，P1**

理由：对照片，图片级续传 + 每图 SHA-256 足够；4MB 分包/合并是多余复杂度。

**具体改法**

1. **每图 SHA-256**：`TYPE_FILE_BEGIN` 带 `sha256`；接收端边写临时文件边算 `MessageDigest`，`last=1` 后比对。失败：
   - 请求重发该图（最多 1 次）；仍失败则标记该图失败并汇总，不阻塞其他图。
2. **临时文件 + 原子落地**：
   - `cacheDir/incoming/<sha256>.part`（或 `<session>/<index>.part`）；
   - 校验通过后插入 MediaStore（或先写最终文件再插入），失败清理；不在校验前插入相册。
3. **图片级续传**：
   - 新连接/新一轮 `HELLO` 带 `receivedBitmap`（已完成的图片索引位图）或 `RESUME_FROM index`；
   - 发送端从第一个未完成索引开始；接收端已有 `.part` 且大小/哈希匹配时可跳过已完成部分（图片级即可）。
   - 若后续要支持大视频，再考虑 `RESUME_FROM {index,offset}` 的块内续传；照片不需要。
4. **存储预检**：
   - `META` 带 `totalBytes`（不只是张数）；接收端用 `StatFs` 检查 `availableBytes >= totalBytes + 100MB`；
   - 不足 → 回 `REJECT {code=NO_SPACE}`，显示“存储空间不足，需要 X”；不进入 FULL 阶段。
   - 这是换机助手握手阶段做法的直接借用；注意在 ACCEPT 之前做，避免传一半。
5. **失败汇总**：`DONE` 带 `successCount/failedCount`；接收端展示“成功 N 张，失败 M 张”，不再“EOF 即成功”。

**改动面**

- `nfc/Xfer.java`：FILE_BEGIN/SHA、临时文件、RESUME、存储预检、失败汇总（约 120–200 行）。
- `nfc/TapService.java`：存储不足/部分失败文案、续传状态（约 20–40 行）。
- 新增 `nfc/TransferStore.java`（临时文件/MediaStore/清理，约 80–120 行）。
- `res/values/strings.xml`：空间/校验/部分失败文案（约 10 行）。

**风险**

- 大图 SHA-256/落盘在主线程会卡；必须在 IO 线程。
- `.part` 清理策略：成功/取消/超时都要清理；进程被杀后的残留要有启动清理或过期策略。
- MediaStore 在部分 ROM 上对 `RELATIVE_PATH` 行为不同；保持现有 `saveToGallery` 的兼容路径，先写最终文件再插入。
- 续传位图/索引必须与 `sid` 绑定，避免上一轮的 `.part` 串到本轮；建议临时目录按 `sid` 分。
- 测试：强杀接收端、断网重连、低存储、损坏字节、重复图片。

---

### P1-3 进度模型（与 P1-1 同批）

**问题（我们的代码）**

- 只有 `onImageReceived(index,total,bmp,bytes,saved)`（`Xfer.java:43-45`）；`TapService` 只写“已收到 i/n 张”（`TapService.java:419-431`）。
- 没有字节进度、速率、剩余时间；大图传输时用户只看到一张图卡住。

**换机助手的做法**

- 统一的进度百分比 + 速度字符串，由控制连接每秒发一次；UI 只消费模型（`SendActivityModel.java:351-378`；`ReceiveActivityModel.java:695-704`）。
- 但它的百分比只在文件完成时跳变（`totalSentSize` 在状态 2/3 才累加），不是字节级；我们不要抄这个粒度。

**判断：借模型、重写，P1**

**具体改法**

- `TransferProgress` 作为唯一进度源；Xfer 内部按块更新 `transferredBytes`；`TapService` 从模型取文案，不直接拼 x/y。
- 速率用 EWMA（例如 0.3 新值 + 0.7 旧值），避免抖动；每 250ms 最多发一次 UI 更新。
- 发送端/接收端都用同一模型字段；接收端额外显示已落盘张数。
- 不改动画文件；只用 `OverlayService.statusIntent` 更新底部状态行。

**改动面**：`Xfer.java`、`TapService.java`、新增 `TransferProgress.java`、`strings.xml`（合计约 80–150 行，已含在 P1-1 的估算里）。

**风险**：UI 更新过频；进度与真实“对端已收”不一致（发送端是“已写入 socket”，不是“对端已落盘”）；如需精确，接收端可每 1s 回一个 `PROGRESS` 帧（可选，P2）。

---

### P1-4 重试分层、阶段超时与错误码

**问题（我们的代码）**

- 重试混在多个局部循环/共享字段：
  - `mRetry` 同时用于 P2P 搜索重试（`Xfer.java:138,600-615`）；
  - 同 LAN 2 次×1.5s（`:474-490`）；
  - 热点请求 30s/等待 31s、TCP 3 次×2s（`:493-571`）；
  - P2P 搜索 5 次、`3+2n` 秒（`:600-615`）；
  - 组员连组主 6 次×5s+2s（`:783-800`）；
  - `sendImages` 等连接 10s/20s（`:936-951`）；
  - 等回执 60s（`:1030-1054`）。
- 全是线性退避；失败只有自由字符串 `fail(String)`（`Xfer.java:1169-1174`），UI 只能显示“发送失败+前 12 字”（`TapService.java:471-483`）。
- `mSenderWaiting` 置真后只有 `resetRoundState()` 清（`Xfer.java:428-432,236`），同一轮再次上滑不会重试。

**换机助手的做法**

- 连接层、包层、文件层各自重试：UDP 1s 重复；`DataManager` 3s 重发；文件 30 次重排队（`SendActivityModel.java:309-338,381-383`）。
- 但没有指数退避、包重发无上限、文件 30 次过多；它的“分层”是好的，“数值/实现”不适合我们。

**判断：借分层原则、重写策略，P1**

**具体改法**

1. **`Stage` 枚举**：`NFC_EXCHANGE / CONNECT / OFFER / TRANSFER / DONE`；每个阶段有独立 deadline、重试计数、错误码。
2. **分层策略（碰一碰裁剪）**：
   - NFC：不重试；失败提示“贴紧 1 秒再试”。
   - 连接：同 LAN 1 次 2s；P2P 发现最多 3 次（1/2/4s，总 ≤8s）；AP 首轮不参与（P1-5）；总连接预算 ≤10s。
   - 报价：20s 等 ACCEPT/REJECT；10s 时状态行提示；不自动重发报价（避免双份）。
   - 传输：不重开整轮；每图 1 次重发（通过续传）；连接断了先尝试一次重连续传，失败即明确结束。
   - 总上限：照片 5 分钟（或按大小）；不是 24h。
3. **错误模型**：`XferError {stage, code, message}`；`fail(XferError)` 统一出口；`TapService` 映射到 `strings.xml`，两端都显示（接收端也要有 banner/提示）。
4. **清理旧的混层状态**：去掉共享 `mRetry`；`mSenderWaiting` 在连接/报价失败时复位（或本轮直接失败并提示重碰）；`startAsReceiver` 的 20s“只打日志”改成真实的等待超时状态（但不一定失败，可提示）。
5. **不借**：不引入 30 次文件重试、不引入包级 ACK/重发、不引入自适应块大小。

**改动面**

- `nfc/Xfer.java`：Stage、超时常量、各层计数器、`fail` 改造（约 100–180 行）。
- `nfc/TapService.java`：错误码→文案、两端 banner、状态行（约 30–60 行）。
- 新增 `nfc/XferError.java`（约 40–60 行）。
- `res/values/strings.xml`：错误码文案（约 10 行）。

**风险**

- 改变超时会改变演示节奏；需要产品确认“20s 报价等待/10s 连接预算”是否可接受。
- 失败太快可能让本来能成的慢设备失败；保留“同 LAN 优先”和 P2P 8s 预算。
- 接收端错误 UI 需要新 action 或复用现有 `failBannerIntent`；不要动动画文件。

---

### P1-5 热点/承载时序：单一 AP owner，但不要 QR-first

**问题（我们的代码）**

- `TapService.beginPeerRound` 里两端都 `startAsReceiver()` + `startHotspot()`（`TapService.java:318-323`）。
- `startHotspot` 用 `LocalOnlyHotspot`；API 33+ 固定 SSID/密码 `TapShare-Transfer`/`tapshare2026`（`Xfer.java:55-56,312-320`）；两端都开 AP 会抢射频；本 ROM 热点与 Wi-Fi 互斥（`Xfer.java:264-268`）。
- NFC payload 在 `TapService.onPeer`/`beginPeerRound` 之前就已交换（HCE 侧 `HceService.java:48-59`；读卡侧 `TapService.java:517-537`），而 `startHotspot` 在碰后才调，所以**本轮 payload 不含本轮新开热点的凭据**。
- 发送方上滑后按 payload 的 `hs` 加入（`Xfer.java:464,493-571`），但拿不到本轮凭据；第二轮虽有凭据，双方 AP 可能还开着，且 API 33+ SSID 相同，加入有歧义。

**换机助手的做法**

- 新机（接收方）先开 LOHS，`onStarted` 后拿到 SSID/密码/AP IP，再显示二维码；老机扫码入网后才连（`RadarConnectNewActivity.java:139-153,239-254`；`OldSelectSendActivity.java:601-641`）。
- 单一 AP owner；热点全程保持；结束/回首页释放（`CoreTool.java:551-562`）。
- 没有 Wi-Fi Direct；没有“双方同时开 AP”的路径。

**判断：借原则、重写为“NFC 角色决定 AP owner + 会话凭据派生”，P1（API 33+ 单 AP）/ P2（API<33 两碰兜底）**

为什么不能照搬二维码：它的“凭据先于连接”依赖扫码这一步的几秒用户操作。碰一碰没有这一步。但**我们不需要二维码**：双方已经通过 NFC 共享了 `sid`，可以**从 `sid` 派生本轮 SSID/密码**；只要确定唯一 AP owner，就能在碰后开 AP、另一方用派生凭据加入，本轮 payload 不需要携带热点密码。

**具体改法**

1. **AP owner 由 NFC 角色固定**：
   - `TapService.onPeer(payload, role)` 已经拿到 `role`（读卡侧 `"reader"`，卡侧 `"card"`；`TapService.java:517-537`；`HceService.java:76-79`），但 `beginPeerRound(payload)` 目前没有用它（`TapService.java:254,307,313`）。
   - 规则：**NFC 卡侧（`role="card"`）是唯一 AP owner**；读卡侧不调用 `startHotspot()`。这样两端不会同时开 AP。
   - 如果 `role` 缺失（调试 payload），退化为“无照片的一侧”当 owner；两边都有照片且角色未知时不走 AP，直接 LAN/P2P。
2. **从会话 id 派生 SSID/密码**：
   - 每端在 NFC payload 里带自己的 `sid`（P0-3）。双方都同时知道两个 `sid`（reader 的、card 的）；定义：
     - `sessionSid = SHA-256(readerSid + cardSid)`（或字典序拼接后哈希，两端算法必须完全一致）；
     - `ssid = "TShare-" + sessionSid.substring(0, 6)`；
     - `pass = "ts" + sessionSid.substring(6, 22)`（18 字符，满足 WPA2 8–63）。
   - **不能各用各的 `sid`**：那会派生出两个不同的 AP，双方永远加入不到一起。必须用同一个 `sessionSid`。
   - 每次碰触 `sessionSid` 不同 ⇒ SSID/密码每轮不同，避免“固定 `TapShare-Transfer`/`tapshare2026`”的多机歧义；也不需要把密码放进 payload（两个 `sid` 已在 payload）。
   - 注意：`sid`/`sessionSid` 是明文 NFC 字段，这不是加密；它只保证“同一轮的两台手机知道同一个热点凭据”。内容加密仍由 OPPO plan 的 P0 负责。
3. **开/加入时序**：
   - 卡侧：碰后（`beginPeerRound` 或首次需要连接时）用 `startLocalOnlyHotspotWithConfiguration(SoftApConfiguration{ssid, pass})` 开 AP；API 33+ 才支持自定义配置，我们的代码已经在用（`Xfer.java:312-320`）。
   - 读卡侧：不 AP，不 `joinHotspot(payload.hs/hp)`；改为用派生 `ssid/pass` 调 `joinHotspot(ssid, pass)`（复用 `Xfer.java:493-571`），加入后连网关 `:8988`。
   - AP 启动有 1–3s 延迟：读卡侧的 `requestNetwork`/join 重试 5–10s（1s 间隔），不要沿用当前 30s（`Xfer.java:524-525`）；连不上再退 LAN/P2P。
   - 双方仍然都 `startAsReceiver()` 监听 8988；AP owner 接受连接后按角色读/暂存，加入方连 `gateway:8988`。现有“发送方也可以做组主/接受连接”的路径可以复用（`Xfer.java:762-780`）。
4. **AP 生命周期**：
   - 只有 owner 开 AP；owner 在传输全程保持；结束/失败/回首页 `closeHotspot()`（学 `CoreTool.releaseHotDot` 的释放时机，`CoreTool.java:551-562`）。
   - 开 AP 前检查系统热点/网络共享（学 `RadarConnectNewActivity.java:106-116,206-216`）；冲突则提示并退 LAN/P2P。
   - 加入方绝不调用 `startHotspot()`；如果本机误开了 AP，先 `closeHotspot()` 再加入。
5. **API <33 兜底（P2）**：
   - API <33 的 `startLocalOnlyHotspot` 不接受自定义配置，系统给随机 SSID/密码，无法从 `sid` 派生。此时：
     - 优先 LAN/P2P；
     - 仍失败则走“两碰热点”：owner 先开 AP，第二次碰触的 payload 带上 `hs/hp`（此时 AP 已在运行），加入方按 payload 加入；提示“请再碰一次以使用热点”。
   - 这是换机助手“凭据先于连接”的等价实现，只是把二维码换成第二次 NFC。
6. **不借**：二维码、扫码入网、`WifiMgr/ApMgr` 整套工具类、`WifiConfiguration` 旧 API 流程。只借“单一 owner/先有凭据/结束释放/开前检查”四条。

**改动面**

- `nfc/TapService.java`：把 `role` 传给 `Xfer`；只在 card 侧调用 `startHotspot()`；失败/结束释放（约 20–40 行）。
- `nfc/Xfer.java`：从 `sid` 派生 SSID/pass；`startHotspot` 只 owner 调；`joinHotspot` 用派生值；join 重试 5–10s；`closeHotspot` 时机（约 50–100 行）。
- `nfc/Bus.java`：`sid` 已在 P0-3 加入；这里不改 payload 结构（约 0–5 行）。
- `res/values/strings.xml`：热点失败/再碰提示（约 5 行）。

**风险**

- `startLocalOnlyHotspotWithConfiguration` 只在 API 33+ 可用；API <33 只能走两碰或 P2P。
- AP 启动 1–3s，加入方需要重试；重试窗口太短会误失败，太长会拖慢。建议 10s 上限 + 状态行“正在建立直连”。
- `sid` 明文在 NFC payload 中；同一轮附近读卡器可推导热点凭据。它比固定密码好，但不是加密。
- 卡侧作为 AP owner 时，它可能同时是发送方或接收方；两种方向都要真机验证（接受连接后的读/暂存分支）。
- 如果系统热点已开、LOHS 不可用，必须有 LAN/P2P 兜底，不能直接失败。
- 两台手机都用同一 `sid` 派生，SSID 相同但只有一台开 AP；如果上一轮 AP 未关，先关再开，避免同名残留。

---

## 3. 换机助手其他优点的逐项评估

| 优点/机制 | 换机助手做法 | 我们是否需要 | 判断 | 优先级 | 理由 |
|---|---|---|---|---|---|
| UDP 端口通告 | 发送方向 AP IP:8099 单播 TCP 端口，1s/次，4 个 broadcaster | 当前不需要 | **不借** | P2 | NFC 已给双方 IP；UDP 增加伪造面；它的实现无 session/源校验，不是真正“发现” |
| 20 路并发 TCP | 控制 + 最多 20 条数据连接，每连接一个文件 | 不需要 | **不借** | P2 | 照片数量少；单连接 + 分块足够；多连接带来鉴权/顺序/进度复杂度 |
| 包级 ACK/重传 | 每包 16B ACK，3s 未确认重发，100 个 ID 去重 | 不需要 | **不借实现** | P2 | TCP 已保证可靠/有序；应用层 ACK 冗余，且它的重发无上限 |
| 4MB 分包 + 合并 | >10MB 文件切 4MB `FileInfo`，接收端按 offset 合并 | 不需要 | **不借** | P2 | 我们按 64–256KB 线块即可；照片不需要二级分包/合并 |
| 块大小自适应 | 10–70KB，成功 +5/失败 -10 | 不需要 | **不借** | P2 | 固定 128KB + TCP 足够；自适应增加状态和测试面 |
| 显式完成握手 | `TASK_FINISH` 双向确认 | 需要 | **借** | P1 | 解决 EOF 误判完成/少发不报错（P1-1） |
| 存储预检 | 握手时算剩余空间，不足拒绝 | 需要 | **借** | P1 | 避免传一半没空间（P1-2） |
| 每文件 MD5 + 临时文件 | 临时文件、已存在跳过、MD5 校验 | 需要 | **借概念** | P1 | 改为每图 SHA-256 + 临时文件 + 图片级续传（P1-2） |
| 已存在文件/APK 跳过 | 目标文件大小一致或 APK 已安装则跳过 | 部分需要 | **不借（照片）** | P2 | 照片每次应保存新副本；“重复传输”产品未定义 |
| 单一 AP owner + 结束释放 | 新机开 AP，回首页/完成释放 | 需要 | **借原则** | P1 | 避免双 AP；我们改为 card 侧单 owner + `sessionSid` 派生凭据（P1-5） |
| 开 AP 前检查系统热点 | 反射 `isWifiApEnabled`，冲突则提示 | 需要 | **借** | P1 | 小而实用（P1-5） |
| 固定角色 + 先握手后数据 | 新机接收/老机发送，hello 后才传 | 需要 | **借原则** | P0 | 我们没有固定产品角色，但需要“角色先共识、首帧先认证”（P0-1/P0-3） |
| 二维码交换凭据 | 扫码加入 AP | 不需要 | **不借** | P2 | 碰一碰没有扫码步骤；我们用 `sessionSid` 派生替代二维码（P1-5） |
| 会员等级/私有数据/APK 安装 | 业务分支 | 不需要 | **不借** | N/A | 与传图无关 |
| 每包 payload 校验 | 8 位头校验 + 可选 payload 校验（实际未启用） | 不需要 | **不借** | P2 | 我们加长度上限 + 每图 SHA-256 更有效 |
| `TcpService`/`TcpClient` 结构 | Server 封装 + 每连接读写线程 + 队列 | 可参考 | **重写** | P2 | 读线程忙等、无超时、缓冲无上限；不建议直接搬 |

---

## 4. 分批实施计划

> 顺序：1 → 2 → 3 → 4 → 5 → 6。批次 5/6 与 3/4 无强依赖，可视排期并行；但每批必须能独立编译/提交/回滚。
> 行数为**改动量级**（新增+修改），不是精确值。

### 批次 1（P0）：可信首帧 + 角色收敛

**目标**：同时上滑不再互等 60s；陌生连接不能抢占 `mPendingSocket`；去掉 `Android*` 通配；所有连接先认证再读写。

**拆成两个可独立提交的 commit**：

| Commit | 内容 | 文件 | 新增文件 | 预计行数 |
|---|---|---|---|---|
| 1a 可信首帧 | `sid` 进 payload；`HELLO/HELLO_ACK`；帧长度上限；首帧读超时；未认证不置 `mGotConnection`；删除 `Android*`；payload 超长显式失败 | `nfc/Xfer.java`、`nfc/Bus.java`、`nfc/TapService.java`、`res/values/strings.xml` | `nfc/Proto.java`（120–160） | 280–380 |
| 1b 角色收敛 | 拆 `mSendSocket`/`mStashedSocket`；上滑先发 `ROLE(SEND)` 再 300ms 探测；`sid` 裁决；败方中止发送、转接收、读对端报价；胜方继续；`readVerdict` 忽略非回执帧 | `nfc/Xfer.java`、`nfc/TapService.java`、`nfc/Bus.java`、`strings.xml` | 无（复用 `Proto`） | 200–320 |

**验证**：
- 顺序：A 上滑 → B 收图；B 上滑 → A 收图。
- 同时上滑：A/B 在 300ms 内都滑；分别以 A/B 的 `sid` 小者胜，确认败方转接收、胜方收到 ACCEPT、只传一份。
- 一先一后：A 先滑 1s，B 后滑；A 胜，B 转接收。
- 第三方：用 `nc`/小脚本连 8988，不读不写；确认不置 `mGotConnection`、不影响 P2P 搜索、用户上滑后不会把图发给它。
- 错误 token：伪造 `HELLO`；连接被关闭且无 UI 状态变化。
- 两台同型号手机：P2P 只连精确 `p/a`，不再 `Android*`。
- payload：加 `sid` 后 HCE/读卡两侧都能收到完整 payload；超长时有明确错误。

**回滚**：两个 commit 独立 revert；协议只影响两端同版本测试机；无持久化数据格式变化（`sid` 只在本轮内存/payload）。

**风险**：payload 200B 溢出；300ms 探测拖慢发送；角色切换 UI 幂等；accept 线程被静默连接占用（用线程池/上限规避）。

---

### 批次 2（P0）：活性/超时/失败回调

**目标**：对端假死、半开、传输中断在 10–20s 内明确失败，接收端不再永久停住。

| 内容 | 文件 | 新增文件 | 预计行数 |
|---|---|---|---|
| `setSoTimeout`；`Proto.readFrame`；PING/PONG；写停滞看门狗；接收端失败回调；报价等待 20s；总时限 5 分钟；去掉“EOF 即完成”的误判（与 P1-1 配合） | `nfc/Xfer.java`、`nfc/TapService.java`、`res/values/strings.xml` | 无（`Proto` 扩展 PING/PONG） | 160–260 |

**验证**：
- 报价阶段杀对端进程 → 发送端 20s 内“对方未响应”；接收端 `onOfferExpired` 收窗/解锁。
- 传输阶段杀对端/飞行模式 → 双方 10s 级失败提示；接收端不再停在“正在接收”。
- 慢读：接收端暂停读 15s（调试开关/断点）→ 发送端写看门狗关闭并报错，不永久阻塞。
- 半开：`iptables` drop 或拔 Wi-Fi → 10–20s 失败。
- 正常大图/多图不误报。

**回滚**：只改超时/心跳，不动帧语义；revert 后回到 60s/无超时行为。

**风险**：慢链路误判；心跳与业务写并发需锁；分块前大帧读可能超 30s（先用 30s，批次 3 后收紧到 10s）。

---

### 批次 3（P1）：分块交付 + 进度 + 显式完成

**目标**：不再整图一帧；有字节进度/速率；`DONE/DONE_ACK` 让“少发/截断”可发现；坏 `LEN` 不再分配大数组。

| 内容 | 文件 | 新增文件 | 预计行数 |
|---|---|---|---|
| 协议 v2（`NFC2`/VER/SEQ/LEN）；`FILE_BEGIN/DATA/DONE/DONE_ACK`（可选 `CANCEL`）；64–256KB 分块；边读边发；接收端按 seq 写；进度模型；UI 状态行 | `nfc/Xfer.java`、`nfc/TapService.java`、`nfc/Proto.java`、`res/values/strings.xml` | `nfc/TransferProgress.java`（60–80） | 350–550 |

**验证**：1/10/100 张；20MB 单图；慢链路；传输中取消（若做）；计数不一致；重复块；大图内存峰值；状态行进度/速率。

**回滚**：协议 v2 与批次 1/2 同版本；revert 后旧帧协议恢复；无持久化格式变化（临时文件若未做批次 4，不产生）。

**风险**：最大重写；解码/落盘队列积压；`CANCEL` 的 UI 入口可能涉及 `OverlayService`（若不想动，先只做协议侧取消，UI 后置）。

---

### 批次 4（P1）：校验 + 续传 + 存储预检

**目标**：坏图可发现/可重发；断线后可只补缺失图片；空间不足在 ACCEPT 前拒绝。

| 内容 | 文件 | 新增文件 | 预计行数 |
|---|---|---|---|
| `FILE_BEGIN` 带 SHA-256；`cacheDir/incoming/<sid>/<index>.part`；校验后落地；`RESUME_FROM index`/已收位图；`META` 带 `totalBytes`；`StatFs` 预检；失败汇总 | `nfc/Xfer.java`、`nfc/TapService.java`、`res/values/strings.xml` | `nfc/TransferStore.java`（80–120） | 220–360 |

**验证**：篡改一个字节 → 校验失败/重发；传输中杀接收端 → 重连后只补缺失；低存储 → ACCEPT 前拒绝；取消/成功后 `.part` 清理；重复图片不串轮（目录按 `sid`）。

**回滚**：依赖批次 3 的 `FILE_BEGIN/DATA`；可单独 revert 校验/临时文件逻辑，退回“直接写最终文件”（不推荐）。

**风险**：SHA/落盘线程；MediaStore 兼容；`.part` 残留；续传状态必须绑定 `sid`。

---

### 批次 5（P1）：重试分层 + 错误码

**目标**：每层只有一个重试 owner；失败原因可定位；不再共享 `mRetry`/线性退避；避免 60s 误导文案。

| 内容 | 文件 | 新增文件 | 预计行数 |
|---|---|---|---|
| `Stage` 枚举；`XferError`；连接/报价/传输分层预算；P2P 1/2/4s 退避；报价 20s；每图 1 次重发；`mSenderWaiting` 复位；两端失败文案 | `nfc/Xfer.java`、`nfc/TapService.java`、`res/values/strings.xml` | `nfc/XferError.java`（40–60） | 170–300 |

**验证**：逐阶段注入失败（NFC、连接、报价、传输、完成）；确认只重试该层、无重复重试；banner 文案正确；接收端也有失败提示；下一轮能重新开始。

**回滚**：独立 revert；不改变帧格式（只改超时/分支）。

**风险**：超时/预算改变演示节奏；失败太快；需要产品确认文案和时限。

---

### 批次 6（P1/P2）：承载与热点

**目标**：第一碰不再双 AP；Wi-Fi 关闭时优先 P2P；热点只作为第二碰兜底；单一 AP owner + 结束释放。

| 内容 | 文件 | 新增文件 | 预计行数 |
|---|---|---|---|
| 移除 `beginPeerRound` 无条件 `startHotspot`；**AP owner = NFC 卡侧**；从 `sid` 派生 SSID/pass；join 重试 5–10s；`closeHotspot` 时机；API<33 两碰 `hs/hp` 兜底 | `nfc/TapService.java`、`nfc/Xfer.java`、`nfc/Bus.java`、`res/values/strings.xml` | 无 | 100–200 |

**验证**：Wi-Fi 开/关；同 LAN/P2P/无 LAN；API 33+ 派生 SSID/pass 加入成功；双 AP 不再出现；热点 owner 唯一（card 侧）；读卡侧不误开 AP；结束/失败/回首页释放；api<33 两碰 `hs/hp` 兜底；两台手机同场 SSID 不歧义。

**回滚**：独立 revert 承载策略；不涉及帧协议。

**风险**：API<33 没有自定义 LOHS 配置，只能走两碰或 P2P；AP 启动 1–3s 需要 join 重试；卡侧作为 owner 时两种发送/接收方向都要真机验证；系统热点冲突/LOHS 不可用必须有 LAN/P2P 兜底；`sessionSid` 派生算法两端必须一致（否则永远加入不到一起）。

---

## 5. 不做/缓做清单

| 项 | 结论 | 优先级 | 原因 |
|---|---|---|---|
| UDP 端口通告/组播发现 | 不做 | P2 | NFC 已有端点；它的实现是单播、无 session、4 包/s，增加伪造面 |
| 20 路并发 TCP | 不做 | P2 | 照片场景单连接足够；多连接增加鉴权/顺序/进度复杂度 |
| 包级 ACK/重传 | 不做 | P2 | TCP 已可靠；应用层 ACK 冗余，换机助手实现还会无界重发 |
| 4MB 分包 + MergeFileManager | 不做 | P2 | 线级分块足够；照片不需要二级分包/合并 |
| 自适应块大小 | 不做 | P2 | 固定 128KB + TCP 足够；自适应增加测试面 |
| 二维码/扫码入网 | 不做 | P2 | 碰一碰没有扫码步骤；“第二碰热点”已能覆盖 AP 兜底 |
| 30 次文件重试/24h 上限 | 不做 | P2 | 场景不匹配；我们每图 1 次重发 + 5 分钟总上限 |
| 会员等级/联系人/短信/APK 安装 | 不做 | N/A | 换机业务专属 |
| 包级 payload 校验 | 不做 | P2 | 每图 SHA-256 + 长度上限更有效 |
| 加密/密钥协商 | 不在本提案重复 | P0（已另行跟踪） | 已由 `OPPO_ADOPTION_STATUS.md` P0 #1/#7/#13 跟踪；本提案的 `sid` 不是加密 |
| 动画文件 | 不改 | N/A | 约束：`OverlayView.kt`/`ReceiverRenderer.kt`/`SenderParticles.kt` 由动画负责人维护 |

---

## 6. 真机验证清单（按批次合并执行）

1. **正常路径**：A 有图、B 无图；A 上滑，B 点接收；收到 N 张，查看器打开。
2. **双向正常**：B 有图、A 无图；反向同样成立。
3. **同时上滑**：两端都有图，分别在 ≤300ms、>300ms 内上滑；只传一份；败方转接收；无“双方都等 60s”。
4. **陌生连接**：PC/另一台手机连 8988，静默 10s；本机不改变 UI、不抑制 P2P、上滑后不发图给陌生连接。
5. **错误 token**：伪造 `HELLO`；连接被关闭，无状态变化。
6. **多机同场**：两台同型号 + 一台不同型号；P2P 只连 NFC 指定对端。
7. **对端假死**：报价阶段/传输阶段杀进程、飞行模式、拔 Wi-Fi；10–20s 内双方明确失败，接收端不永久停留。
8. **慢读/半开**：接收端暂停读；发送端 10s 看门狗生效，不永久阻塞。
9. **大图/多图**：20MB 单图、50 张、100 张；内存峰值可接受；进度/速率正常。
10. **计数/校验**：故意少发、重复发、改一字节；`DONE` 能发现；坏图不落相册。
11. **断线续传**：传输中杀接收端/断网，重连后只补缺失图片；`.part` 清理。
12. **存储不足**：剩余空间 < 总大小 + 100MB；ACCEPT 前拒绝，文案明确。
13. **Wi-Fi 关闭**：无同 LAN；P2P 成功；不再双 AP；第二碰热点兜底（若做）。
14. **回归**：不改 3 个动画文件；状态行/失败 banner 正常；下一轮碰触能重新开始；`Bus.lockRole` 30s 不误锁。

---

## 7. 与已有文档的关系

- `docs/COMPARE_LINK_VS_OPPO.md`：已列出连接/安全/可靠性缺陷；本提案的 P0-1/P0-2/P0-3/P0-4 对应其 §8 #2/#3/#4/#1，但**方案来源换成换机助手的可复用机制**，并给出碰一碰裁剪。
- `docs/COMPARE_FLOW_VS_OPPO.md`：§3.3 的最小修法（tie-break + 败方转接收）与本提案 P0-1 一致；§4.3 的“上滑即锁定内容”不在本提案范围（相册侧，另行处理）。
- `docs/OPPO_ADOPTION_STATUS.md`：P0 #1/#7/#13 的加密/凭据问题不由本提案替代；本提案只做连接归属/会话 token，避免与 OPPO 安全改造冲突。若先做 OPPO #1（NFC payload 加密），`sid` 可以并入同一轮 payload 改版，减少两次协议升级。
- `docs/PRI_CLONE_STUDY.md`：本文所有换机助手结论的展开和证据索引。
