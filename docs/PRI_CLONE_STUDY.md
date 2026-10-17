# 换机助手 PriCloneAssistant 连接与传输拆解

> **研究对象**：`/dfb/zhuyang/share/mtk_x/pri_project/android/os_app_src/PriCloneAssistant`
> 下文路径均相对于 `app/src/main/java/com/pri/clonedata/`（Java 源码根）；`Xfer.java`/`Bus.java`/`TapService.java` 等“我们”的文件均相对于 `nfc_tap_demo/app/src/main/java/com/pri/nfctouchdemo/nfc/`。
> **方法**：只读源码；所有结论给 `文件:行号`；推断标 `[推断]`，无法从代码确认的标 `[未确定]`。
> **场景差异（先记住）**：换机助手是“新机开热点 + 二维码 + 老机扫码”的**长时间、大文件搬家**；我们是“NFC 碰一下、立刻连”的**几秒到几十秒传几张图**。它的很多参数/策略不能直接搬（见 §7 与 `PRI_CLONE_ADOPTION_PLAN.md`）。

---

## 0. 结论速览

1. **它不是“UDP 发现 + TCP 传输”的对称模型**：新机（接收方）先开 `LocalOnlyHotspot` 并显示二维码，老机（发送方）扫码入网后起一个**随机端口的 TCP Server**，再用 UDP 把自己的 TCP 端口**单播通告**给新机的 AP 地址；新机收到后主动连这个 TCP Server。UDP 只负责“端口通告”，不负责发现设备（`RadarWaitConnectActivityModel.java:57-81,99-111`；`UdpClientBroadcast.java:32-52`；`RadarConnectNewActivity.java:425-463`）。
2. **角色是产品固定的**：新机=接收方（AP 持有者 + TCP 客户端）；老机=发送方（STA + TCP 服务端）。不存在“谁先上滑谁发送”的跨设备竞态（`MainActivity.java:66-90`；`RadarConnectNewActivity.java:104-254`；`RadarWaitConnectActivityModel.java:57-81`）。
3. **首帧握手存在，但没有认证**：客户端每 100ms 发 `{0xA1, 0x00, memberLevel}`，服务端回同样格式 + 文件清单 JSON，客户端做存储检查后回 `{0xA3}` 或 `{0xBE, reason}`；随后双方交换 `PhoneInfo`，再进入传输（`RadarConnectNewActivity.java:435-463`；`RadarWaitConnectActivityModel.java:161-200`；`WaitSendActivity.java:118-136,230-269`）。
4. **传输是“控制连接 + 最多 20 条数据连接”**：控制连接走文件清单/进度/速度/完成；每条数据连接同一时间只搬一个文件（或 4MB 分包），用 1 字节命令 + Gson `FileInfo` 元信息 + 带 5 字节块头的 10–70KB 数据块，块间用 `0xB3` 做停止等待（`SendActivityModel.java:220-379,533-555`；`ReceiveActivityModel.java:278-295`；`FileThreadSchedule.java:39-41`）。
5. **可靠性核心是心跳 + 每包 ACK/重传**：`HeartManager` 每 200ms 巡检；1s 没有写就发心跳；8–24s 没有收到任何有效包就关 socket 并回调错误。`DataManager` 对未 ACK 的数据包 3s 后重发，收到 16 字节同 ID 包即确认（`HeartManager.java:29-60`；`DataManager.java:49-79`；`TcpClient.java:216-224,285-287`）。
6. **文件级完整性靠 MD5，断点续传只到 4MB 分包/整文件粒度**：每个文件/分包带 MD5；接收端先写临时文件，MD5 通过才落到最终路径；临时文件已存在且 MD5 通过则跳过。没有块内偏移续传（`ReceiveFileTransfers.java:54-81,131-154`；`SendFileTransfers.java:52-53`；`MergeFileManager.java:127-143`）。
7. **没有应用层加密，也没有对端认证**：传输全程明文；二维码里直接带热点密码；握手只交换会员等级/机型信息；工程里唯一的 RSA 工具类没有被调用。WPA2 只是 LOHS 的链路层保护（`ScanCodeInfo.java:5-33`；`OldSelectSendActivity.java:566,621-641`；`RSAHelper.java` 全工程 grep 0 调用）。
8. **最值得借鉴的是“活体检测 + 显式完成 + 分包/MD5/临时文件”这三个机制，不是它的并发数、包级 ACK 或二维码流程**。对本场景应重写成更小、更快、可认证的版本（见 §7、提案）。

---

## 1. 整体架构

### 1.1 拓扑与角色

```text
                二维码（ip / wifiName / wifiPwd / brand / versionCode）
   新机（接收方，AP 持有者）  ───────────────────────────────►  老机（发送方，STA）
        │  1. startLocalOnlyHotspot()，拿到 SSID/密码/AP IP              │
        │  2. 显示二维码                                                  │
        │                                                              3. 扫码 → 加入 AP
        │                                                              4. 起 TCP Server（随机端口 40000–40998）
        │                                                              5. UDP 单播“我的 TCP 端口”到 AP IP:8099（1s 一次）
        │  6. UDP:8099 收到 4 字节端口                                     │
        │  7. 主动 TCP connect 到 老机 IP:随机端口                          │
        │  ◄──────────── 握手：hello / 文件清单 / 存储检查 ─────────────► │
        │  ◄──────────── 控制连接：PhoneInfo/进度/速度/完成 ────────────► │
        │  ◄──────────── 最多 20 条数据连接：FileInfo + 数据块 ──────────► │
```

- **AP 属于接收方**，但 **TCP Server 属于发送方**；两者解耦（`RadarConnectNewActivity.java:104-254`；`RadarWaitConnectActivityModel.java:57-81`）。
- 这个方向和我们相反（我们是接收方监听 8988、发送方连过来），但本质都是“一端有地址、另一端主动连”。它的做法解决的是“AP 持有者不知道 STA 的 IP”的问题：STA 用 UDP 把自己的 IP（数据报源地址）+ 端口告诉 AP 持有者（`UdpService.java:46-49`；`RadarConnectNewActivity.java:425-431`）。

### 1.2 组件分工

| 组件 | 职责 | 关键代码 |
|---|---|---|
| `TcpService` | 裸 `ServerSocket` 封装；无限 `accept()`，把 `Socket` 回调给上层。握手完成后被 `SendActivityModel` 重新 `ensureServerRunning()`，同一端口继续接收数据连接。 | `TcpService.java:30-43,62-75`；`SendActivityModel.java:176-182` |
| `TcpClient` | 每条 TCP 连接一个：读线程 + 写线程 + 发送队列 + 帧解析 + 每包 ACK + 心跳注册。 | `TcpClient.java:52-103,137-232,424-438` |
| `DataManager` | 每个 `TcpClient` 一个：发送队列 `acb`、未确认队列 `mqd`、已收包 ID 去重环 `jxy`（100 个）。 | `DataManager.java:9-79` |
| `HeartManager` | 进程级单例，一个线程巡检所有 `TcpClient`：发心跳、判超时、驱动未确认包重发。 | `HeartManager.java:8-74` |
| `UdpService` | 接收方：绑定 8099，收 4 字节 TCP 端口并回调。 | `UdpService.java:40-55,88-104` |
| `UdpClientBroadcast` | 发送方：每 1s 把 4 字节端口发到“新机 AP IP:8099”。 | `UdpClientBroadcast.java:32-52,55-72` |
| `RadarConnectNewActivity` | 新机（接收方）连接页：开 LOHS、显示二维码、起 UDP、收到端口后连 TCP、做存储检查、跳 `WaitSendActivity`。 | `RadarConnectNewActivity.java:104-254,425-463` |
| `RadarWaitConnectActivityModel` | 老机（发送方）连接页：随机端口 TCP Server + UDP 通告 + 握手。 | `RadarWaitConnectActivityModel.java:57-81,99-111,161-200` |
| `SendActivityModel` / `ReceiveActivityModel` | 发送/接收编排：文件队列、数据连接调度、重试、进度、完成。 | `SendActivityModel.java:220-379`；`ReceiveActivityModel.java:278-403` |
| `CommTransfers` | 控制连接的“空实现”占位：`BaseTransfers` 的四个回调全空，只用来持有控制通道的 `TcpClient`（这样 `CoreConfig.commTransfers` 可以跨 Activity 传递）。 | `CommTransfers.java:5-20`；`RadarWaitConnectActivityModel.java:181`；`RadarConnectNewActivity.java:373` |
| `BlockFileManager` / `MergeFileManager` | 发送前把 >10MB 文件切成 4MB 分包；接收后按 offset 合并并做整文件 MD5。 | `BlockFileManager.java:101-111,162-183`；`MergeFileManager.java:68-151` |
| `WifiMgr` / `ApMgr` | **不是**热点主实现。`WifiMgr` 只用于 API≤28 关 Wi-Fi、取 AP IP；`ApMgr` 只用于关掉系统旧式热点。真正的热点在 Activity 里直接调 `LocalOnlyHotspot`。 | `RadarConnectNewActivity.java:118-123,139-193,240`；`WifiMgr.java:259-294`；`ApMgr.java:12-32` |

### 1.3 `DataPacket` 字段

`DataPacket` 是**每包 16 字节固定开销**的二进制帧（`DataPacket.java:7,17-18,72-92`）：

```text
偏移  长度  字段                        说明
0     2     HEADER = 0x88 0xDD          魔数
2     4     packetSize (big-endian)     整帧字节数（含头尾）
6     4     packetId (big-endian)       发送序号，从 1 递增
10    1     isAnotherFlagSet            1=要求对方回 ACK
11    1     isFlagSet                   1=payload 也带校验（普通数据实际为 0）
12    1     fieldValue                  头部校验（Util.calculateChecksum）
13    1     anotherFieldValue           payload 校验（仅 isFlagSet=1 时有效）
14    n     payload                      业务数据
末 2  2     FOOTER = 0xCC 0x99          帧尾
```

- `packetSize` 由 `bArr.length + 16` 得出（`DataPacket.java:118-129`）；解析时先按它判是否收全，再检查 footer（`TcpClient.java:181-189`）。
- **实际只有头部 8 位校验生效**：`sendByteData()` 调 `addDataPacketWithSequence(bArr, true, false)`，第三个参数 `isFlagSet=false`，所以 byte 11/13 为 0，接收端跳过 payload 校验（`TcpClient.java:234-238,369-373,190-203`）。文件完整性靠上层 MD5，不靠这个校验。
- 16 字节包是纯 ACK/心跳：`new DataPacket(packetId)` 只带 ID，无 payload；`new DataPacket(0)` 是心跳（`DataPacket.java:20-28`；`TcpClient.java:217-219,285-287`）。
- 收到的每个数据包都会**自动回 ACK**：`if (acb2.isAnotherFlagSet()) addDataPacket(new DataPacket(acb2.getPacketId()))`（`TcpClient.java:217-219`）。ACK 先于去重判断，所以重发的重复包也会再次被 ACK（`TcpClient.java:217-224`；`DataManager.java:21-30`）。
- 没有版本字段、没有会话 ID、没有类型枚举的文本化；业务命令是 payload 第 1 个字节（见 §3.1）。

---

## 2. 发现与连接

### 2.1 端到端时序

```text
新机（接收方 / AP）                         老机（发送方 / STA）
  │                                            │
  │ startLocalOnlyHotspot()                    │
  │ onStarted: SSID/PWD/AP IP                  │
  │ 二维码 {ip,wifiName,wifiPwd,brand,ver} ───►│ 扫码
  │                                            │ requestNetwork(WifiNetworkSpecifier)
  │                                            │ onAvailable → sGloabNetwork
  │                                            │ 起 TCP Server（随机端口）
  │                                            │ UDP 单播端口 → AP IP:8099（1s/次）
  │ UDP:8099 receive 4B port                    │
  │ TCP connect(老机 IP, port) ────────────────►│ accept
  │ {0xA1,0x00,memberLevel} →（100ms 循环）      │
  │ ◄────────────── {0xA1,0x00,serverMember}    │
  │ ◄────────────── {0xA2, fileListJson}        │
  │ 存储检查                                    │
  │ {0xA3} 或 {0xBE, reason} ─────────────────►│
  │ 双方进入 WaitSendActivity                   │
  │ ◄────────────── PhoneInfo (0xAA) 200ms ───►│
  │ 新机点“接收” → ReceiveActivity              │
  │ 老机点“发送” → SendActivity                 │
```

### 2.2 UDP：到底“广播”了什么

- **发送方** `RadarWaitConnectActivityModel` 在 TCP Server 起来后启动 UDP（`RadarWaitConnectActivityModel.java:57-81`）：
  - 先随机选 TCP 端口：`new Random().nextInt(999) + 40000` → `40000..40998`（`:62`）。
  - 建 `TcpService` 并 `ensureServerRunning()`（`:66,71-78`）。
  - 对 `CoreConfig.connectPortArray` 的 4 个值各建一个 `UdpClientBroadcast`（`:99-111`；数组定义在 `CoreConfig.java:60`）。
  - 每个 broadcaster 的 payload 是 **4 字节大端 TCP 端口**（`Util.intToByteArray(this.portNumber)`，`:104`）。
- **接收方** `UdpService` 绑定 `Constant.DEFAULT_SERVER_COM_PORT = 8099`（`UdpService.java:88-104`；`Constant.java:4`），收 128 字节，把前 4 字节解析成端口，回调 IP = 数据报源地址（`UdpService.java:40-49`）。
- **实际目的地址不是组播/广播**：`UdpClientBroadcast.sendDataPacket()` 用 `new DatagramPacket(data, len, this.efv, Constant.DEFAULT_SERVER_COM_PORT)`，其中 `efv` 是构造时传入的 `ip`（二维码里的**新机 AP IP**），目的端口固定 8099（`UdpClientBroadcast.java:32-52`；`initDatagramSocket` 见 `:55-72`）。`connectPortArray` 的 4 个值只作为构造参数 `acb`，实际发送时**没有使用**（`:27-29,36`）。类注释写“组播/广播”，代码是**单播端口通告**。
- **频率与去重**：每个 broadcaster 1s 一次（`:41-51`），4 个 broadcaster 合计约 4 包/s；接收方 `kwn()` 用 `dyz` 布尔锁，只有第一次回调会发起 TCP 连接，后续包直接 return（`RadarConnectNewActivity.java:425-433`）。没有序列号/会话 ID/源地址校验，任何能向 8099 发包的主机都能触发一次 TCP 连接尝试。
- **结论**：这套 UDP 不是“发现附近设备”，而是“在已知 AP IP 的前提下，通告一个随机 TCP 端口”。它解决的是“接收方不知道发送方在 AP 里的 IP”的问题（源地址就是发送方 IP），不是多设备筛选。

### 2.3 谁主动连、端口、超时与重试

| 项 | 事实 | 代码 |
|---|---|---|
| TCP 服务端 | **发送方（老机）**，端口随机 40000–40998 | `RadarWaitConnectActivityModel.java:57-81` |
| TCP 客户端 | **接收方（新机）**，收到 UDP 端口后主动连 | `RadarConnectNewActivity.java:425-463` |
| 连接超时 | **没有显式 connect timeout**：`new Socket(ip, port)` 用系统默认 | `RadarConnectNewActivity.java:439`；额外数据连接 `ReceiveActivityModel.java:284` |
| 连接重试 | 没有重试循环；`mhz()` 失败把 `dyz=false`，下一个 UDP 包（≈250ms 后）会再次触发连接 | `RadarConnectNewActivity.java:435-463` |
| 退避 | 无；UDP 固定 1s/4 包每秒 | `UdpClientBroadcast.java:41-51` |
| 服务端重试 | 无；`accept()` 无限循环，来一个处理一个 | `TcpService.java:30-43` |
| 握手重试 | 客户端每 100ms 发 hello，直到收到任何数据或连接失败 | `RadarConnectNewActivity.java:445-452` |
| 服务端等待 | 无超时；没客户端就一直等 | `TcpService.java:30-43` |

> 这段和我们的对比：我们的 `connectTo` 有 1.5s×2、热点 30s、P2P 5 次；换机助手的连接层几乎“不重试”，靠 UDP 的 1s 重复和用户重新扫码。**不要因为它是成熟产品就以为它连接层更健壮**，它只是业务上接受“扫码失败就重来”。

### 2.4 热点/AP 管理

**谁开热点**：新机（接收方）在 `RadarConnectNewActivity.startLocalOnlyHotspot()` 里直接调系统 `WifiManager.startLocalOnlyHotspot`（`RadarConnectNewActivity.java:104-204,139-193`）。不是 `WifiMgr`/`ApMgr` 开的。

**什么时候开**：
1. 进入“我是新手机”页面，先要求 `WRITE_SETTINGS` 权限（`RadarConnectNewActivity.java:526-536`）。
2. `isStartingHotspot` CAS 防重入；用反射 `isWifiApEnabled` 检查系统热点/网络共享是否已开，已开则提示“本地热点不可用”并结束（`:106-116,206-216`）。
3. API ≤28 先 `WifiMgr.disableWifi()`；若有旧式 AP 则 `ApMgr.disableAp()`（`:118-123`）。
4. 关闭上一条 `sHotspotReservation`，再 `startLocalOnlyHotspot`（`:133-139`）。
5. `onStarted` 里从 `reservation.getWifiConfiguration()` 取 **系统生成的随机 SSID/密码**，同时用 `WifiMgr.getApIpAddress2()` 取 AP 接口 IP；然后起 UDP 服务并生成二维码（`:141-153,239-254`）。
6. `onFailed`/`onStopped`：提示并 `finish()`，没有回退到 Wi-Fi Direct（`:157-192`）。

**SSID/密码怎么交换**：二维码 JSON `ScanCodeInfo{ip, wifiName, wifiPwd, brand, versionCode}`（`ScanCodeInfo.java:5-33`；`RadarConnectNewActivity.java:245`）。老机扫码后：
- Android Q+ 用 `WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pwd)` + `requestNetwork`（`OldSelectSendActivity.java:621-641`）；
- Q 以下用 `WifiConfiguration` + `addNetwork/enableNetwork`（`:643-668`）；
- `onAvailable` 记录 `CoreConfig.sGloabNetwork`，然后 `startTransferData(ipAddress)`（`:670-690`）。

**什么时候关**：
- 新机回首页/退出连接页：`CoreTool.releaseHotDot`（`RadarConnectNewActivity.java:289-293`；`CoreTool.java:551-562`）。
- 接收完成：`ReceiveActivityView.onReceiverFinish` 先 `releaseHotDot` 再开 Wi-Fi（`ReceiveActivityView.java:78-84`）。
- `MainActivity.onResume` 无条件下 `releaseHotDot`（`MainActivity.java:55-58`），`DataReceiveActivity`/`OverActivity` 初始化也释放（`DataReceiveActivity.java:63`；`OverActivity.java:32`）。
- 传输过程中热点一直保持；没有“发送方用完自己关”的逻辑，因为发送方本来就不开 AP。

**与 Wi-Fi Direct 的关系**：**没有关系**。全工程 `grep -rn "WifiP2p|createGroup|WifiDirect"` 0 命中。它只有 SoftAP + 基础 Wi-Fi + TCP，没有 P2P。`WifiMgr.createWifiCfg`（`WifiMgr.java:152-192`）也不在主流路径上；`WifiMgr` 在本流程只承担 `disableWifi` 和 `getApIpAddress2`。

**可借鉴/不可借鉴**：
- 可借：**单一 AP owner + 凭据在连接前就绪 + 传输全程保持 + 结束/回首页释放 + 开 AP 前检查系统热点**。这几点是它稳定的基础。
- 不可借：**二维码/AP-first 的时序**。碰一碰没有“先展示二维码让用户扫”的几秒等待，热点无法在本轮 NFC payload 之前就绪。详见提案 P1-5。

### 2.5 连接建立后的握手

握手分三段（六个报文），全部在**同一条控制 TCP 连接**上：

| 段 | 方向 | 报文 | 含义 | 代码 |
|---|---|---|---|---|
| 1 | 新机→老机 | `{0xA1, 0x00, memberLevel}`，每 100ms 重复 | “我来了，我的会员等级” | `RadarConnectNewActivity.java:445-452` |
| 2 | 老机→新机 | `{0xA1, 0x00, serverMember}`；若 `sendData` 非空再发 `{0xA2, fileListJson}` | 回应 + 本次文件清单（TotalSize/TotalTime/Data[{Type,Num}]/versionCode） | `RadarWaitConnectActivityModel.java:161-170` |
| 3 | 新机→老机 | `{0xA3}` 或 `{0xBE, reason}` | 存储检查通过/不通过 | `RadarConnectNewActivity.java:328-378` |
| 4 | 老机→新机 | 收到 `0xA3` → 关 `TcpService`，建 `CommTransfers`，跳 `WaitSendActivity` | 控制连接确定 | `RadarWaitConnectActivityModel.java:176-189` |
| 5 | 双向 | `PhoneInfo` JSON（命令 `0xAA`），每 200ms 重复 | 交换机型/版本/会员信息；代码会比较版本字段，但 `ckw()` 目前是空实现，实际不提示 | `WaitSendActivity.java:118-136,230-269`（比较在 `:255`，空实现 `:166-173`） |
| 6 | 新机 | 点“接收” → `ReceiveActivity`；老机点“发送” → `SendActivity` | 进入传输 | `WaitSendActivity.java:181-200,281-292` |

- 存储检查：用 `CoreTool.getStorageStats()` 与 `getSDAvailableSize()` 取剩余空间，保留 10GB（版本 ≥2 时），若 `TotalSize >= 剩余-10GB` 则回 `{0xBE, 可用空间}`（`RadarConnectNewActivity.java:335-369`）。这是**报价阶段先拒绝**，避免传到一半没空间。
- 握手没有随机数、没有签名、没有密钥交换；`memberLevel`/`PhoneInfo` 只用于业务分支（`WaitSendActivity.java:243-260`）。
- 控制连接建立后 `TcpService` 被关闭，但 `SendActivityModel` 构造时又 `ensureServerRunning()` 把**同一个端口**重新监听，用于后续数据连接（`SendActivityModel.java:176-182`）。

---

## 3. 传输协议（重点）

### 3.1 三层 framing

**第一层：`DataPacket`**（见 §1.3）。所有业务数据都装在这个 16 字节开销的帧里。

**第二层：业务命令字节 + payload**（`BaseTransfers.java:11-74,81-128`）。payload 第 0 字节是命令，后面是内容：

| 命令 | 值 | 方向 | 含义 |
|---|---:|---|---|
| `f3592im` | -95 (0xA1) | 双向 | 握手 hello（`[0]=0`）；数据连接请求（`[1]=1`） |
| `age` | -94 (0xA2) | 老机→新机 | 文件清单 JSON |
| `ckc` | -93 (0xA3) | 新机→老机 | 存储检查通过，开始 |
| `check_error` | -66 (0xBE) | 新机→老机 | 存储不足，带原因 |
| `kwg` | -92 (0xA4) | 老机→新机 | 老机通知新机弹出“是否接收”确认 |
| `meu` | -91 (0xA5) | 双向 | 退出/取消 |
| `lkj` | -90 (0xA6) | 老机→新机 | 最大数据连接数 |
| `imd` | -89 (0xA7) | 老机→新机 | 速度字符串 |
| `hct` | -88 (0xA8) | 老机→新机 | 总进度 0–100（1 字节） |
| `TASK_FINISH` | -87 (0xA9) | 双向 | 全部完成（带耗时字符串） |
| `ljf` | -86 (0xAA) | 双向 | `PhoneInfo` JSON |
| `bew` | -85 (0xAB) | 老机→新机 | 收尾/结束页 |
| `gtp` | -84 (0xAC) | 双向 | 会员等级/升级 |
| `gui` | -79 (0xB1) | 老机→新机 | 一个文件的 `FileInfo` JSON |
| `FILE_TRANSFER_RUNNING` | -78 (0xB2) | 老机→新机 | 数据块（5 字节块头 + 数据） |
| `cbc` | -77 (0xB3) | 新机→老机 | “块收到，请发下一块” |
| `CODE_CLIENT_TRANSFER_OVER` | -76 (0xB4) | 新机→老机 | 本文件/本连接完成 |
| `jyz` | -75 (0xB5) | 新机→老机 | 文件已存在/重复，跳过 |
| `mfe` | -74 (0xB6) | 新机→老机 | MD5 校验失败，请重发 |
| `mbp` | -73 (0xB7) | 老机→新机；新机回 0/1 | 文件不可发/已存在 |
| `code 98` | 98 | 内部状态 | 校验失败 |
| `code 99` | 99 | 内部状态 | 通用发送失败 |
| `code 97` | 97 | 内部状态 | 空间不足 |
| `code 100` | 100 | 内部状态 | 接收 IO 错误 |

> 命令名是反编译后的混淆名；上表按代码实际值整理。`BaseTransfers.java:11-74`。

**第三层：数据块**（`FileTransfers.cpk`，`FileTransfers.java:135-181`）：

```text
[1B lastFlag][4B blockIndex][N bytes data]
```

- `lastFlag=1` 表示这是本文件/本分包的最后一个传输块（`:144-154`）。
- `blockIndex` 是**传输块序号**，从 1 开始（`SendFileTransfers.java:81-84`）。
- 外层再套命令 `0xB2`，再套 `DataPacket`。

### 3.2 文件元信息

- **总清单**：握手时 `0xA2` 的 payload 是 JSON（`OldSelectSendActivity.java:187-245`；`RadarWaitConnectActivityModel.java:167-169`）：

```json
{"TotalSize":1871629691,"TotalTime":356,
 "Data":[{"Type":4,"Num":25},{"Type":1,"Num":4},{"Type":5,"Num":1}, ...],
 "versionCode":123}
```

  只有**按类型的数量**，没有逐文件元信息。
- **逐文件元信息**：每条数据连接开始时，发送方发命令 `0xB1`，payload 是 Gson 序列化的 `FileInfo`（`SendFileTransfers.java:176`；`ReceiveFileTransfers.java:192-221`）。`FileInfo` 的关键字段（`FileInfo.java:27-101,126-147`）：

| 字段 | 序列化名 | 含义 |
|---|---|---|
| `A2` | `A2` | 文件大小 |
| `A3` | `A3` | 类型：0 图片 / 1 视频 / 2 联系人 / 3 通话记录 / 4 APK / 5 音频 / 6 文档 / 7 短信 |
| `A0` | `A0` | 文件名 |
| `A1` | `A1` | 相对路径（不含外部存储根） |
| `A4` | `A4` | 文件 MD5 |
| `A5` | `A5` | 分包总数（0=不拆分） |
| `A6` | `A6` | 分包索引 |
| `A7` | `A7` | 分包 MD5 |
| `A8` | `A8` | 已传输大小（分包起始 offset） |
| `A9` | `A9` | 分包大小 |
| `packageName` | `packageName` | APK 包名 |
| `isSplit` / `splitApkTotal` / `splitApkIndex` | 同名字段 | Split APK |
| `absolutePath` | `absolutePath` | 完整路径（Gson 默认会序列化；接收端会覆盖） |

- 联系人/通话记录/短信不是文件：发送前把它们按每 30 条序列化成 JSON 字符串，塞进 `FileInfo.fileName` 字段，`fileSize` 用字符串长度×2 估算（`OldSelectSendActivity.java:216-243`）；接收端从 `fileName` 反序列化（`ReceiveActivityModel.java:329-364`）。
- 文件类型判断：`FileInfo.isNonContactAndCallLog()` 对图片/视频/APK/音频/文档返回 true（`FileInfo.java:187-191`）。

### 3.3 多文件排队、并发与流控

**发送端队列**：
- `CoreConfig.needTransferFileList` 保存待发文件（`CoreConfig.java:51`）。
- `BlockFileManager` 线程每 50ms 处理待处理队列；对非联系人类型且 >10MB 且未分包的文件，按 4MB 切分成多个 `FileInfo` 分包（`BlockFileManager.java:101-111,162-183`；阈值 `CoreConfig.java:65-66`）。
- 每个分包带 `totalPackages/packageIndex/packageSize/transferredSize/packageMd5`，接收端可按 offset 合并。
- `SendActivityModel` 的 `FileTransferThread` 每 50ms 从 `needTransferFileList` 取文件，分配给一个空闲的 `SendFileTransfers`（`SendActivityModel.java:80-107,246-257`）。

**并发度**：
- 控制连接 1 条 + 数据连接 N 条，N 由 `FileThreadSchedule.dtr()` 返回 **20**（`FileThreadSchedule.java:39-41`）。
- 接收端 `ReceiveActivityModel.bew()` 维护 `f5178uv`（发送端通过 `0xA6` 告知的最大连接数），当 `f5178uv > fileTransferList.size()` 就新建一条 `Socket(remoteHost, remotePort)`（`ReceiveActivityModel.java:278-295`；`SendActivityModel.java:367-372`）。
- 每条数据连接同一时间只负责一个文件/分包；文件之间并行，文件内部串行。
- 发送端 `SendActivityModel.onSocketConnect` 接受每条新数据连接，创建 `TcpClient`，等接收端在这条连接上发 `{0xA1,1}` 后创建 `SendFileTransfers`（`SendActivityModel.java:210-215,533-555`；`ReceiveFileTransfers.java:156-159`）。

**流控/背压**：
1. **块级停止等待**：发送方发一个 10–70KB 数据块（命令 `0xB2`），接收方写完（或判断重复）后回 `0xB3`，发送方才发下一块（`SendFileTransfers.java:224-227`；`ReceiveFileTransfers.java:231-251`）。每条数据连接同时只有一块在途。
2. **包级 ACK/重传**：每个 `DataPacket` 都要对方回 16 字节 ACK；未 ACK 的包 3s 后重发（`DataManager.java:49-61`）。这是 TCP 之上的第二层可靠传输。
3. **块大小自适应**：`FileThreadSchedule` 初始块大小 30KB，成功 +5KB，失败 -10KB，范围 10–70KB（`FileThreadSchedule.java:8-14,16-33,35-61`；实际缓冲区按 `hlp*1024`，`SendFileTransfers.java:58-59`）。每条连接第一次分配缓冲区后固定，后续新建连接才用新值。
4. **没有全局窗口/总速率控制**：20 条连接各自停止等待，控制连接只发进度；接收端慢时，发送端写线程会被 TCP 背压阻塞，进而阻塞该连接的 `OutputStreamWriteTask`（`TcpClient.java:82-103`）。

> 这套“多连接 + 每连接停止等待”在 20 条连接下能跑满带宽，但实现复杂、包级 ACK 与 TCP 重复，且没有全局调度；对“几张照片”是明显过度设计。

### 3.4 重传、校验与断点续传

**包级**：
- 每个数据包要求 ACK；`DataManager` 保存未确认包及时间戳；`HeartManager` 每 200ms 调 `DataManager.jxy(3000)`，超过 3s 的包重新入发送队列（`HeartManager.java:50`；`DataManager.java:49-61`）。
- 接收端用最近 100 个包 ID 去重；重复包仍会回 ACK（`DataManager.java:21-30`；`TcpClient.java:217-224`）。
- 没有重传次数上限；如果对端一直不 ACK，包会在队列里反复重发，直到连接被判超时关闭（`HeartManager.java:45-49`）。

**文件级**：
- 发送端对每个文件/分包带 MD5（`BlockFileManager.java:101-104,179`；`SendFileTransfers.java:176`）。
- 接收端把数据写到临时文件，临时文件名包含“分包总数-分包索引-目标路径 SHA1”：

```text
<cacheDir>/<totalPackages>-<packageIndex>-<sha1(absolutePath)>.temp
```

  （`ReceiveFileTransfers.java:72`）。
- 写完最后一个块后计算 MD5，与 `FileInfo.fileMd5` 或 `filePackageMd5` 比较（`:131-154`）；一致才 `copyFile` 到最终路径（`:139-143`）。
- 失败回 `0xB6`（`mfe`），发送端把该文件重新入队，最多 **30 次**（`SendActivityModel.java:322-338,381-383`）。
- 断点续传：
  - 发送端 `RandomAccessFile.seek(dso.getTransferredSize())`，从分包的 offset 开始读（`SendFileTransfers.java:52-53`）。
  - 接收端如果临时文件已存在且 MD5 通过，直接返回“已完成”（`ReceiveFileTransfers.java:75-77`）；否则删掉重写（`:78-80`）。
  - 目标文件已存在且大小一致，或 APK 已安装，则直接跳过（`ReceiveFileTransfers.java:89-124`）。
  - **没有块内偏移续传**：最小重传单位是 4MB 分包（或整文件）。分块 4MB 来自 `CoreConfig.smallPacketSize`（`CoreConfig.java:66`）。
  - 跨进程/跨轮的续传依赖 `.temp` 缓存是否保留；`[推断]` 可复用，但工程里没有显式清理/保留策略说明 `[未确定]`。
- 合并：接收端 `MergeFileManager` 按 `packageIndex` 收集分包，用 `copyFileWithOffset` 写入合并临时文件，最后对整文件再算一次 MD5（`MergeFileManager.java:68-151`），失败删除（`:139-142`）。

### 3.5 进度与完成

**进度**（`SendActivityModel.java:351-378`）：
- `totalFileSize` 来自握手清单的 `TotalSize`；`totalSentSize` 在文件/分包完成时累加（状态 2/3，`:259-308`）。
- 每 1s 计算 `transferProgress = totalSentSize/totalFileSize*100`，速度 = `bytesTransferredInUnitTime / 经过秒数`；`bytesTransferredInUnitTime` 来自 `FileTransfers.noq()`（`cfo`，即**写入 TCP 队列的字节数**，`FileTransfers.java:107-111,121-129`）。
- 通过控制连接发：
  - `0xA8` + 1 字节总进度（0–100）；
  - `0xA7` + 速度字符串；
  - `0xA6` + 最大连接数。
- 接收端 `ReceiveActivityModel` 收到后更新进度/速度（`ReceiveActivityModel.java:695-704`）。
- **粒度**：进度只在文件/分包完成时跳变；一个大文件内部没有进度（除非它被切成 4MB 分包，则以 4MB 为步长）。没有字节级回调，没有 ETA。

**完成**：
- 发送端队列/分包/私有数据都空后，发 `0xA9 TASK_FINISH`（`SendActivityModel.java:364-366`）。
- 接收端收到后设 `isTaskOver=true`，处理联系人/短信等私有数据，完成后回 `0xA9`（`ReceiveActivityModel.java:631-673,705-708`）。
- 发送端收到回执后 `completeTransfer()` 打开结束页（`SendActivityModel.java:567-572,471-491`）。
- **失败/丢弃也算完成**：单文件重试 30 次后只打日志并丢弃（`SendActivityModel.java:309-338`），队列最终仍会空并走 `TASK_FINISH`；接收端没有“少文件”的最终校验。这是一个真实缺陷，不要照搬。

---

## 4. 可靠性

### 4.1 心跳（`HeartManager`）

- 单例、一个线程，注册表 `List<TcpClient>`；每 **200ms** 巡检一次（`HeartManager.java:29-60`）。
- 对每个 `TcpClient`：
  - `now - lastWriteTime > reconnectDelay` → 发一个 16 字节心跳包 `DataPacket(0)`（`:43-44`；`TcpClient.java:285-287`）。
  - `else if (now - lastResponseTime > timeout)` → `closeSocketStream()` + `onConnectionError("Client error 连接超时")` + 从列表移除（`:45-49`）。
  - 每次巡检都调 `DataManager.jxy(heartbeatInterval)`，驱动未 ACK 包重发（`:50`）。
- `lastResponseTime` 在收到**任何**有效包时更新（`TcpClient.java:216`），所以心跳/ACK/业务包都能续命。
- `reconnectDelay` 名字有误导：它是**心跳发送周期**（默认 1000ms），不是重连延迟（`TcpClient.java:130-135,459-475`）。
- `timeout`：
  - 数据连接：`CoreConfig.timeout = 8000`（`CoreConfig.java:62`；`ReceiveActivityModel.java:284`，`SendActivityModel.java:541`）。
  - 控制连接：`ReceiveActivityModel.java:271` 和 `SendActivityModel.java:183` 会 `setTimeout(CoreConfig.timeout * 3)` → 24000；新机连接页建链时也用 `timeout*3`（`RadarConnectNewActivity.java:439`）。
  - 因此控制连接 24s、数据连接 8s 无包 → 判定断连。注意 `WaitSendActivity` 阶段：新机侧控制连接是 24s（`RadarConnectNewActivity.java:439`），老机侧是 8s（`RadarWaitConnectActivityModel.java:152`），要到 `SendActivityModel` 构造后才被改成 24s；两端超时并不对称。
- 心跳包本身不要求 ACK（`isAnotherFlagSet=false`），只用来刷新对端 `lastResponseTime`；它不能确认“对端应用层还活着”，只能确认“TCP 栈还能收到我们的包/对端读线程在跑”。

### 4.2 socket 超时与设置

- **没有 `setSoTimeout`**：全工程 grep 无命中；读线程用 `mInputStream.available() <= 0` 时 `sleep(1)` 忙等（`TcpClient.java:58-60`）。
- 只设置了：
  - `setTcpNoDelay(true)`；
  - `setKeepAlive(true)`；
  - `setReceiveBufferSize(262144)` / `setSendBufferSize(262144)`（`TcpClient.java:467-471`）。
- 连接建立：`new Socket(ip, port)` **没有 connect timeout**（`RadarConnectNewActivity.java:439`；`ReceiveActivityModel.java:284`）。
- 传输阶段：**没有总时限、没有空闲 SO_TIMEOUT**；只有心跳线程会在 8–24s 无包时关 socket。慢但持续有数据的传输可以永久进行。
- 写线程：`mOutputStream.write()` 阻塞；写异常时把包放回队列、sleep 100ms、关 socket、回调错误（`TcpClient.java:88-97`）。没有写超时。

### 4.3 异常与重试矩阵

| 层 | 失败 | 重试/动作 | 代码 |
|---|---|---|---|
| UDP 通告 | 发送异常 | 只打日志；循环继续 | `UdpClientBroadcast.java:41-51` |
| TCP 连接 | `Socket` 异常 | `dyz=false`；下一个 UDP 包触发重连；无上限/无退避 | `RadarConnectNewActivity.java:456-462` |
| TCP accept | 异常 | 直接 return，Server 线程结束；没有重启 | `TcpService.java:36-42` |
| 包传输 | 3s 未 ACK | 重新入队重发；无次数上限 | `DataManager.java:49-61` |
| 连接活性 | 8–24s 无包 | 关 socket + `onError`；接收端 `ReceiveActivityModel` 结束/报错 | `HeartManager.java:45-49`；`BaseSendReceiveActivityModel.java:97-103` |
| 文件传输 | 状态 99（连接/读错误） | 重新入队，最多 30 次 | `SendActivityModel.java:309-320,381-383` |
| 文件校验 | 状态 98（MD5 失败） | 重新入队，最多 30 次 | `SendActivityModel.java:322-338` |
| 接收 IO | `ENOSPC`/write failed | 状态 97（空间不足），上报 UI；其他状态 100 | `ReceiveFileTransfers.java:255-267` |
| 接收端文件完成 | 临时文件 MD5 通过 | 直接跳过/完成 | `ReceiveFileTransfers.java:75-77,139-143` |

- **退避策略**：只有 `DataManager` 的固定 3s 重发；连接层没有指数退避；文件层没有退避，只是立即重排队。
- **重试预算**：包级无上限（靠心跳兜底）；文件级 30 次；连接级无上限（靠用户重扫）。
- **主流程不重开一轮**：失败后 UI 结束，用户重新扫码/重新选文件。

### 4.4 半开连接/对端假死怎么发现

- 应用层心跳每 1s 一次；对端只要读线程还在跑，收到包就更新 `lastResponseTime`，于是半开/假死会在 `timeout`（8–24s）内被发现。
- 如果对端进程死了但 socket 还在（半开），心跳包会进入 TCP 发送缓冲；没有 ACK 时 TCP 会重传；应用层 `lastResponseTime` 不更新，8–24s 后 `HeartManager` 关 socket 并回调。
- 如果对端活着但应用层卡死（不读 socket）：心跳写一段时间后发送缓冲满，`write` 阻塞；`HeartManager` 的 `lastWriteTime` 不更新（写线程卡住），于是 `now-lastWriteTime > reconnectDelay` 为真，会不断尝试发心跳（实际写不出去），但 `else if` 的超时判断不会执行，**可能无法判死**。这是一个边界缺陷：`HeartManager` 的超时检查在 `else if` 分支，写阻塞时不会走超时分支（`HeartManager.java:43-49`）。真正兜底的是 TCP 自己的 keepalive/重传，时间可能很长。`[推断]`
- 没有“进度停滞”检测：只要心跳能通，传输可以永远停在某个块上。

---

## 5. 并发与线程模型

**连接层**：
- 1 个 `TcpService` accept 线程（`TcpService.java:66`）。
- 每条 `TcpClient` 2 个线程：`InputStreamReadTask`、`OutputStreamWriteTask`（`TcpClient.java:52-103,431-432`）。
- 1 个 `HeartManager` 线程（`HeartManager.java:65-68`）。
- 最坏：1 控制 + 20 数据 = 21 条连接 × 2 + accept + heart ≈ 44 个线程；再加业务线程。

**业务层**：
- 发送端：`FileTransferThread`（50ms 轮询，`SendActivityModel.java:80-107`）、`BlockFileManager`（50ms，`BlockFileManager.java:248-260`）。
- 接收端：`bod`（50ms，`ReceiveActivityModel.java:176-212`）、`MergeFileManager`（1s，`MergeFileManager.java:260-280`）。
- 新机连接页：UDP 接收线程（`UdpService.java:95`）、每个 `UdpClientBroadcast` 1 个线程（4 个，`UdpClientBroadcast.java:64`）、二维码生成线程（`RadarConnectNewActivity.java:243`）。

**队列与锁**：
- `DataManager` 的 `acb`/`mqd`/`jxy` 全部 `synchronized(this)`（`DataManager.java:15-79`）。
- `HeartManager.acb` 列表 `synchronized(acb)`（`HeartManager.java:31-53,63-71`）。
- `SendActivityModel`/`ReceiveActivityModel` 的 `fileTransferList` 用 `lockObject`（`SendActivityModel.java:230-350`；`ReceiveActivityModel.java:296-403`）。
- `BlockFileManager`/`MergeFileManager` 用静态对象 `f4922ym`/`f5217ju` 保护队列（`BlockFileManager.java:24,186-216`；`MergeFileManager.java:26,53-65,220-238`）。
- 大量 `Thread.sleep(1/50/100/200/1000)` 轮询；没有 `BlockingQueue`/`ExecutorService`（除 `RadarConnectNewActivity` 的 `MAIN_EXECUTOR` 一处，`:151`）。

**并发风险**：
- `TcpClient.dataBuffer` 无上限扩容：`ensureBufferCapacity` 按对端给的 `packetSize` 扩容到 `i3*2`（`TcpClient.java:240-249`）；恶意/损坏的长度可导致大内存分配。
- `DataManager` 只增不减的发送队列在慢对端下会堆积。
- 读线程直接同步调用业务回调（`TcpClient.java:222-224`），业务里再做文件 IO/UI post；没有背压隔离。
- `TcpService.accept` 无连接数上限、无来源校验；任何人都可以连随机端口（如果能扫到）。

---

## 6. 安全

**明确结论：没有应用层加密，也没有对端认证。**

| 项 | 事实 | 证据 |
|---|---|---|
| 传输加密 | 无 TLS/SSL/Cipher；数据包明文 | 全工程 grep `Cipher|SSL|TLS|KeyAgreement|SecretKey`，除未使用的 `RSAHelper.java` 外 0 命中；`DataPacket.java` 无加密字段 |
| 对端认证 | 无证书、无预共享密钥、无签名；握手只交换 `memberLevel`/`PhoneInfo` | `RadarConnectNewActivity.java:445-452`；`RadarWaitConnectActivityModel.java:161-200`；`WaitSendActivity.java:230-269` |
| 会话标识 | 无；端口随机不等于身份 | `RadarWaitConnectActivityModel.java:62` |
| 热点凭据 | 二维码明文携带 `wifiPwd`；扫码结果还被打进日志 | `ScanCodeInfo.java:5-33`；`RadarConnectNewActivity.java:245`；`OldSelectSendActivity.java:566`（`TLog.logD(TAG + " scanMac=" + scanMac)`，scanMac 是完整 JSON，含密码） |
| 链路层 | LOHS 默认 WPA2/WPA3，有链路层加密；但 AP 持有者（接收方）本来就能看到流量，且密码在二维码里 | `RadarConnectNewActivity.java:141-147`；`OldSelectSendActivity.java:621-641` |
| 完整性 | MD5/SHA1 仅用于文件名/文件校验，不是认证 | `CoreTool.java:387-410,412-483` |
| 二维码泄露面 | 截图/相册/日志/录屏都会泄露 Wi-Fi 密码；没有时效/一次性 | `ScanCodeInfo.java:42-50`（`toString` 含 `wifiPwd`） |
| 服务端暴露 | 任意能连到随机端口的客户端都会被 `accept`；没有源 IP/令牌校验 | `TcpService.java:30-53` |

- `RSAHelper` 是一个完整的 RSA 工具类（`RSAHelper.java:13-80`），但全工程没有调用点；**不能据此认为传输有加密**。
- 它的“安全”主要依赖：AP 是私有的、密码通过二维码（物理接近）传递、端口随机。这是** obscured by proximity**，不是认证。对我们来说，TCP 8988 是固定端口、热点密码固定，风险比它更高；但不要因为它有二维码就认为它的模型可以照搬。

---

## 7. 可复用性评估

### 7.1 纯传输能力（与换机业务无关）

| 能力 | 代码 | 可复用性 | 结论 |
|---|---|---|---|
| 帧格式 + 8 位头校验 + ACK | `DataPacket.java`；`TcpClient.java:137-232` | 概念可复用；实现有混淆命名、无长度上限、payload 校验实际未启用 | **重写**一个干净的 `FrameIO`（魔数/版本/类型/序号/长度/最大长度校验） |
| 心跳 + 最后响应超时 | `HeartManager.java:29-60` | 概念极好，直接命中我们的“假死永久卡” | **重写**（不要 `else if` 的判死缺陷；用独立时间戳 + 单线程看门狗） |
| 未 ACK 重传/去重 | `DataManager.java` | 概念可复用；但 TCP 已保证可靠，包级 ACK 对我们冗余 | **不借实现**；只借“序号/去重”用于断点续传 |
| 分包 + 块级停止等待 | `FileTransfers.java:135-181`；`SendFileTransfers.java:48-97`；`ReceiveFileTransfers.java:231-251` | 概念可复用；4MB 分包/10–70KB 块/20 连接过重 | **重写**成单连接 + 64–256KB 分块 + 序号/offset |
| 文件 MD5 + 临时文件 + 续传 | `ReceiveFileTransfers.java:54-81,131-154`；`MergeFileManager.java` | 概念极好；MD5 可换 SHA-256；4MB 分包合并可去掉 | **重写**成“每图 SHA-256 + 临时文件 + 图片级续传” |
| 显式完成握手 | `SendActivityModel.java:364-366`；`ReceiveActivityModel.java:631-673,705-708` | 概念好，解决 EOF 误判完成 | **借概念**，加 `DONE/DONE_ACK` |
| 存储预检 | `RadarConnectNewActivity.java:335-369` | 直接可用 | **借**（在 ACCEPT 前检查） |
| 多连接并发 | `SendActivityModel.java:533-555`；`ReceiveActivityModel.java:278-295` | 对少量照片过度设计 | **不借**（P2 再考虑） |
| UDP 端口通告 | `UdpClientBroadcast.java`；`UdpService.java` | 只在“AP 持有者不知道对端 IP”时需要；我们 NFC payload 已有 IP | **不借**（P2） |
| AP 生命周期检查 | `RadarConnectNewActivity.java:106-123,141-192`；`CoreTool.java:551-562` | 小而实用 | **借**（系统热点检查/结束释放） |
| 二维码/AP-first | `RadarConnectNewActivity.java:104-254`；`OldSelectSendActivity.java:563-641` | 依赖扫码等待；碰一碰没有这一步 | **不借流程** |

### 7.2 与业务/工程强耦合的部分

- `FileInfo` 的类型枚举（图片/视频/联系人/通话记录/APK/音频/文档/短信）、`absolutePath`、联系人/短信 JSON 批处理、Split APK：换机业务专属（`FileInfo.java:5-12,27-101,126-147`；`OldSelectSendActivity.java:216-243`）。
- `SendActivityModel`/`ReceiveActivityModel`：与 `CoreConfig` 静态状态、UI 生命周期、会员等级、操作日志、APK 安装强耦合（`SendActivityModel.java:161-208`；`ReceiveActivityModel.java:224-276`）。
- `BlockFileManager`/`MergeFileManager`：与 `CoreConfig.cacheDirPath`、`receiverFilePath`、APK 安装、媒体扫描、文件类型耦合（`BlockFileManager.java:22-35,162-183`；`MergeFileManager.java:24-51,68-151`）。
- `WifiMgr`/`ApMgr`：大量方法（`createWifiCfg`/`addNetwork`/`getHotspotLocalIpAddress`）在本流程未使用；不要把它们当成“热点框架”整体搬。
- `TcpClient` 的混淆命名（`mqd`/`aui`/`jxy`/`acb`）和静态 `CoreConfig` 使直接复用成本高于重写。
- `TLog`/`CoreTool`/`PreferencesUtils`/`BaseApplication` 等基础设施与本工程绑定。

### 7.3 真正值得抄的三件事

**抄它的“活性检测/显式完成/可续传”思路，不要抄它的数据面实现。**
- 它真正解决“对端假死/半开”的是：应用层心跳 + 最后响应时间 + 关 socket 回调错误（`HeartManager.java:29-60`）。
- 它真正解决“大文件可续”的是：临时文件 + 每文件/分包 MD5 + 已存在校验（`ReceiveFileTransfers.java:54-81,131-154`）。
- 它真正解决“何时算传完”的是：显式 `TASK_FINISH` 双向握手（`SendActivityModel.java:364-366`；`ReceiveActivityModel.java:631-673`）。
- 这三件事与换机业务无关，可以独立重写；其余（20 连接、包级 ACK、4MB 分包、二维码、会员等级）都应按碰一碰场景裁剪或放弃。

---

## 8. 关键证据索引

**连接/发现**
- 新机 AP + 二维码 + UDP + TCP 连接：`RadarConnectNewActivity.java:104-254,328-388,425-463,487-516`
- 老机随机端口 Server + UDP 通告 + 握手：`RadarWaitConnectActivityModel.java:57-81,99-111,148-200`
- 端口常量/配置：`Constant.java:4`；`CoreConfig.java:59-68`
- UDP 发送/接收：`UdpClientBroadcast.java:27-72`；`UdpService.java:40-55,88-104`
- 扫码/入网：`OldSelectSendActivity.java:563-700`
- 热点释放：`CoreTool.java:551-562`；`RadarConnectNewActivity.java:289-293`；`ReceiveActivityView.java:78-84`；`MainActivity.java:55-58`

**传输**
- 帧格式：`DataPacket.java:7,17-18,31-45,72-92,118-129`
- 帧解析/ACK/心跳：`TcpClient.java:52-103,137-232,285-287,424-438,459-475`
- 队列/重传：`DataManager.java:9-79`
- 心跳：`HeartManager.java:29-60`
- 命令/块格式：`BaseTransfers.java:11-74,81-128`；`FileTransfers.java:121-129,135-181`
- 发送块/元信息/重试状态：`SendFileTransfers.java:48-97,176,200-238`
- 接收块/临时文件/MD5/续传：`ReceiveFileTransfers.java:32-46,54-81,89-124,131-154,180-267`
- 并发调度：`FileThreadSchedule.java:8-61`；`SendActivityModel.java:220-379,533-555`；`ReceiveActivityModel.java:278-403`
- 分包/合并：`BlockFileManager.java:101-111,162-183`；`MergeFileManager.java:68-151`
- 进度/完成：`SendActivityModel.java:351-378,364-366,471-491,567-572`；`ReceiveActivityModel.java:631-673,695-708`

**安全**
- 二维码字段：`ScanCodeInfo.java:5-50`
- 扫码日志：`OldSelectSendActivity.java:566`
- 无加密/无认证：`RSAHelper.java` 未被调用；`DataPacket.java`/`BaseTransfers.java` 无加密；全工程 grep 无 TLS/Cipher 调用
- 文件校验算法：`CoreTool.java:387-410,412-483`
