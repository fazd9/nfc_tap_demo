# 两机连接方式对照：我们（nfc_tap）vs OPPO Quick Connect

> **范围**：只谈两机之间的连接、寻址、会话、帧协议、可靠性与连接相关安全；**不讨论动画/UI 动效**（动画见 `COMPARE_FLOW_VS_OPPO.md`、`OPPO_NFC_ANIMATION_STUDY.md`）。
>
> **我们的代码基线**：分支 `nfc_tap`，2026-10-17 工作区。下文 `Xfer.java` / `Bus.java` / `TapService.java` / `HceService.java` 均指
> `app/src/main/java/com/pri/nfctouchdemo/nfc/` 下的文件；`Manifest` 指 `app/src/main/AndroidManifest.xml`；`apdu_service.xml` 指 `app/src/main/res/xml/apdu_service.xml`。
>
> **OPPO 证据**：`docs/OPPO_TECHNICAL_DETAIL.md`（下称“技术文档 §x”）与 `_Temp/afk/notes/transport.md`（下称 `transport.md:行号`）。已有落地结论见 `docs/OPPO_ADOPTION_STATUS.md`，本文不重复其逐项审计，只补连接/传输细节。
>
> 标注约定：`[未确定]` = 文档/代码没有直接证据；`[推断]` = 由代码/文档可推出但未实测。

---

## 0. 结论速览

1. **承载选型**：我们用 Wi-Fi（SoftAP / 同 LAN / Wi-Fi Direct）+ TCP 8988；OPPO 碰一碰**缺省走 BT-SPP insecure**，仅当 6 条事前判据全真才升级 Wi-Fi P2P，且**选一次不回落**（技术文档 §6.2；`transport.md:101-128`）。对“贴一下传几张照片”，**我们的 Wi-Fi 方向更合适**（吞吐高），但我们的“谁开热点/何时开/如何防错配”实现有明显时序问题。
2. **角色**：我们两端碰后都 `startAsReceiver()` + `startHotspot()`，上滑才 `setSenderRole()`（`TapService.java:318-323`、`Xfer.java:450-453`）；没有跨设备角色裁决。OPPO 在 NFC 契约链里显式交换 `NfcPairRole(SEEKER/PROVIDER)` 与 `InternalNfcEventRole(SENDER/RECEIVER)`，且 P2P 的 GO/GC 由“被碰方 `createGroup` / 碰方 `connect`”确定（技术文档 §3.1、§3.2、§6.2）。
3. **同时上滑**：代码推断为**双方各写一份报价、各等 60s、各报“对方未响应”**，本轮不传；文档已把它列为“可接受的降级 + 待真机验证”，**没有看到实测通过的记录**（`ALIGN_PLAN_FINAL.md:210, 857, 878`；`APP_FLOW_ANALYSIS.md:522`）。
4. **同时开热点**：两端都会被 `startHotspot()` 拉起（Wi-Fi 关闭时）。本轮 NFC 交换发生在 `startHotspot()` **之前**，所以双方本轮 payload 里没有对方新热点的 SSID/口令，热点通道本轮基本用不上；双 AP 还会占住射频，可能导致 P2P 兜底也失败。第二轮再碰时虽然 payload 带了口令，但“自己也是 AP 去加入对方 AP”在本 ROM（热点与 Wi-Fi 互斥，`Xfer.java:264-268`）可能失败；API 33+ 两端又用同一个固定 SSID，加入还有歧义。**是否实测：没有找到结果，文档只列为“必须真机验证”**（`ALIGN_PLAN_FINAL.md:870`）。
5. **寻址防错**：我们靠 `p=` P2P 名 / `a=` P2P 地址 / `ip=` 局域网 IP / `hs=` 热点 SSID 匹配；对端信息缺 P2P 字段时会退化成“任意以 Android 开头的设备”（`Xfer.java:653-665`），API 33+ 热点口令与 SSID **全设备固定**（`Xfer.java:55-56, 312-320`）。办公室多台同 App 手机时**存在连错/传错对象**的路径。OPPO 的 NFC 是点对点读卡，P2P 组名/口令由被碰方随机生成并现场加密下发，不存在“从列表里挑名字”的问题（技术文档 §6.3；`transport.md:242-251`）。
6. **可靠性**：我们的重试都在“连上之前”，**传输/等回执阶段没有心跳、没有总超时、接收侧读 socket 没有超时**（`Xfer.java:804-841, 997-1010`）。对端掉线/假死/半开时，接收方可能永久停在接收页、发送方可能永久阻塞在 `write`。OPPO 有 SCP 心跳 10/90s、进度心跳 10s（两轮无变化判 `BT_DISCONNECTED`）和 24h 传输上限（技术文档 §6.5；`transport.md:659, 733, 943`）。
7. **安全**：我们明文 TCP、固定热点口令、服务端不认证、NFC payload 明文凭据（`Bus.java:170-177`、`Xfer.java:55-56, 751-780`）；OPPO 应用层 AES-GCM-128 + UKey2/KSC + 凭据延迟注入（技术文档 §3A、§6.3.2）。风险等级我们明显更高；最小修法指向 `docs/OPPO_ADOPTION_STATUS.md` 的 P0（`#1/#7/#13`）。

---

## 1. 承载（bearer）对照

### 1.1 逐承载表

| 承载 | OPPO 用不用 | 我们有没有 | 谁发起 / 谁接受 | 备注 |
|---|---|---|---|---|
| **NFC APDU / HCE** | ✅ 只用它传身份、地址、能力位、会话参数、分享意图；**文件字节不走 NFC**（技术文档 §6.1、§6.3；`transport.md:61-78`） | ✅ 读卡方 `TapService.handleTag`（`TapService.java:517-542`），卡方 `HceService.processCommandApdu`（`HceService.java:32-68`）；payload 是 `k=v\|k=v` | 读卡方发 `SELECT`+`EXCHANGE`；卡方回 payload | OPPO 的 payload 在广播态凭据为空、被读瞬间注入并 AES-GCM 加密（技术文档 §6.3.2）；我们是明文常驻（`Bus.java:170-177`） |
| **BT SPP insecure（RFCOMM）** | ✅ **碰一碰缺省主通道**（`ConnectionType.SPP_INSECURE`；技术文档 §6.2；`transport.md:88`） | ❌ 没有 BT 数据通道；只读 `BluetoothAdapter.getName()` 取蓝牙名（`Bus.java:158-168`），Manifest 的 BLUETOOTH 权限仅此用途（`Manifest:45-46`） | OPPO：**SEEKER** `connectAccessory` = BT client；**PROVIDER** `acceptAccessory` = BT server listener（技术文档 §3.1⑥ 425-464；`transport.md:434-441`）。物理上哪台是 SEEKER/PROVIDER，与读卡/卡侧的对应关系文档未直接点名 `[推断：reader=SEEKER、card=PROVIDER]` | BT-SPP 带宽约 100–200 KB/s，一张 5MB 照片 30s+（技术文档 §11.3 2219-2220） |
| **BT SPP secure / L2CAP / BLE GATT / BLE adv** | ⚠️ 存在，**碰一碰不用**（技术文档 §6.2；`transport.md:89-92`） | ❌ | — | 不要把 OPPO 通用快连/BLE 的重试算到碰一碰路径上 |
| **Wi-Fi P2P（Wi-Fi Direct）** | ✅ **条件升级**；被碰方 `createGroup()` = GO，碰方 `connect()` = GC；`enablePersistentMode(false)` 硬编码（技术文档 §6.2 1396；`transport.md:242-251`） | ✅ 第三优先级（发送方兜底），双方都可 `startServer()`；GO/GC 由系统决定（没设 `groupOwnerIntent`，`Xfer.java:693-695`） | OPPO：被碰方（通常即 NFC 卡侧 `[推断]`）建组当 GO，碰方（reader `[推断]`）当 GC。我们：`mayDriveP2p()` 为真的一侧发起 `connect`（`Xfer.java:456-458, 678-705`），组主 `startServer()`、接收方组员 `startClientRead()`（`Xfer.java:721-726`） | OPPO 判据要求双方 `wlanMode==0`（P2P 空闲、SoftAP 关）；我们无条件先开热点再试 P2P，可能自己把 P2P 条件破坏掉 |
| **SoftAP / LocalOnlyHotspot** | ⚠️ 存在，但**不是碰一碰所选**；热点开关交给 OShare（技术文档 §6.2 1398、1416；`transport.md:95`） | ✅ **第一优先级**；`startHotspot()` 用 `LocalOnlyHotspot`（API 33+ 固定配置，`Xfer.java:259-332`） | 设计意图：接收侧开 AP、发送侧 `joinHotspot()` 当 STA 并连网关 8988（`Xfer.java:493-571`）。代码实际：**两端都调 `startHotspot()`**（`TapService.java:323`） | OPPO 的 `wlanMode==1`（SoftAP 已开）会**禁止** P2P；我们的双 AP 会反过来干扰 P2P |
| **同 LAN / 基础架构 Wi-Fi + TCP** | ⚠️ 有 `WLAN_TCP`/LAN 连接器，**碰一碰不选**（`transport.md:96`） | ✅ **第二优先级**；发送方直连 payload 里的 `ip:8988`（`Xfer.java:464-489`）；双方都监听 8988 | 发送方连；接收方 `ServerSocket.accept()`（`Xfer.java:751-780`） | 无 NSD/mDNS，完全依赖 NFC payload 里的 IP |
| **NSD / mDNS** | ⚠️ 另一条链路（`_http._tcp:46888`），碰一碰不用（`transport.md:97`） | ❌ | — | — |
| **Wi-Fi Aware / NAN** | ❌ 完全未用（技术文档 §6.2；`transport.md:94`） | ❌ | — | — |

### 1.2 我们的优先级链（准确顺序与触发条件）

**只有发送方有“链”**；接收方不选承载，它只是同时把 8988 监听和热点准备好。

```text
上滑（TapService.java:176-191）
  → 若 mLinkReady 已为真：直接 sendImages()（TapService.java:182-187）
  → 否则 startAsSender()（TapService.java:190）
      → 若 mGoAddress != null 或 mSenderWaiting：直接返回（Xfer.java:428-431）
      → startConnectStrategy()（Xfer.java:434, 461-472），顺序：
         ① mPeerHsSsid != null && joinHotspot(hs, hp)        （Xfer.java:464, 493-571）
         ② mPeerLanIp  != null && connectTo(peerIp, "局域网")  （Xfer.java:465, 474-490）
         ③ mDriveP2p = true; warmDiscoverLoop()               （Xfer.java:466-469）
              → onPeers() 匹配到目标 → WifiP2pManager.connect()（Xfer.java:667-710）
```

准确触发条件与常量：

- **① 热点**：只看 payload 里有没有 `hs`（`Xfer.java:464`）。`joinHotspot` 要求 API ≥29（`:494`）；先停 P2P 搜索并睡 1.5s（`:497-501`）；`requestNetwork` 30s、等待 31s（`:524-525`）；从默认路由取网关（`:532-545`）；然后对网关 8988 做 3 次、每次 2s 的连接（`:551-565`）。注意 `startHotspot()` 在 Wi-Fi 开着时直接跳过（`:265-268`），这只影响**本机开 AP**，不影响加入对方热点；热点通道成立的条件是 payload 里 `hs` 非空、且本机能作为 STA 切到该 AP。
- **② 同 LAN**：只看 payload 里有没有 `ip`（`Xfer.java:465`）。`lanIpv4()` 只认 `wlan*`/`wifi*` 接口，排除 p2p/bt/rmnet/usb/ccmni（`Bus.java:202-234`），并把 `192.168.49.x`（P2P）与 `192.168.43.x`（LOHS 常见网段）降级为 fallback（`Bus.java:236-239`）。连接 2 次、每次 1.5s（`Xfer.java:474-490`）。
- **③ P2P**：只有 ①② 都失败才置 `mDriveP2p=true`（`Xfer.java:466-469`）。搜索失败最多重试 5 次，延迟 `3+2n` 秒（`Xfer.java:600-615`）；匹配到目标才 `connect`（`:690-705`）。

> **关键时序（本文最重要的发现之一）**：`startHotspot()` 在 `TapService.beginPeerRound()` 里调用（`TapService.java:313-323`），而这一轮 NFC payload 的读取/交换发生在 `onPeer()` **之前**（读卡侧 `TapService.java:517-537`；卡侧 `HceService.java:48-59` 先返回 payload 再转交服务）。因此：
> - **本轮新开的热点，SSID/口令不可能出现在本轮对方拿到的 payload 里**；热点通道对“第一次碰”基本不可用，除非热点在本轮碰触**之前**就已开着（上一轮遗留，或调试页 `MainActivity.java:387, 701` 手动开）。
> - 第二轮再碰时 payload 才带上热点口令，但此时双方都还开着上一轮的热点（接收方只在 `handleIncoming` 收到数据后关闭，`Xfer.java:891-894`；发送方自己的热点**从不主动关**，`closeHotspot` 只在 `destroy()`/接收结束时调用，`Xfer.java:334-344, 1156-1164`），于是“自己也是 AP 去加入对方 AP”的冲突就出现了。

### 1.3 OPPO 的两级承载与“事前 6 条件”

OPPO 的选路不是“先试 SPP 再试 P2P”，而是在建立 SCP 连接**之前**一次性判：

```java
// 技术文档 §6.2 1401-1414；transport.md:141-149
static boolean a(s7.c disc) {                    // 6 条全部短路
    return  disc.b == DeviceType.PHONE           // 对端是手机
         && local() == DeviceType.PHONE          // 本机也是手机
         && disc.t == 0                          // 对端 wlanMode==0（P2P 空闲）
         && disc.p.length() != 0                 // 对端 networkName 非空
         && disc.q.length() != 0                 // 对端 networkPwd 非空
         && CompatScp.getWlanMode() == 0;        // 本机也 P2P 空闲
}
// true → ConnectionType.P2P；false → SPP_INSECURE
```

为什么是“选一次不回落”：

- 判据在 `createScpConnection` 前执行一次，结果只记录为本次连接的 bearer（`transport.md:101-128`）。P2P 分支 `createP2pGroup()` 后如果超时/失败，**不会**再切回 SPP；SPP 分支也不会因为慢而升级 P2P。失败的后果是整次碰一碰失败（技术文档 §6.2 1414）。
- `wlanMode` 的取值是 0=P2P 空闲 / 1=SoftAP 开 / 2=组已成形 / 3=OShare 不支持 P2P（`transport.md:233-240`）。**任一端已经有 SoftAP 或已有 P2P 组，判据就不成立，直接走 SPP**——这从设计上避免了“热点和 P2P 抢射频”。
- 两个 `length()!=0` 是给 PAD 准备的：PAD 的 TLV 线格式把 `networkName/Pwd` 写死为空，所以平板一定走 SPP（技术文档 §6.2 1418；`transport.md:162`）。V1 路径是否恒为 SPP，文档标 `[未确定]`（`transport.md:128`）。
- 注意：不要把它理解成“OPPO 连接层有大量重试所以碰一碰很稳”。GATT 3 次、指数退避封顶 1h 等是**通用连接/无感连接**路径的重试（`transport.md:681-698`）；碰一碰实际用的 SPP/P2P 没有跨 bearer 重试，且 `NfcConnectAccessoryContract` 自身只有 6s（技术文档 §3.9 429）。

### 1.4 结论：这个场景谁更合适？

**对“两台手机贴一下传几张照片”，Wi-Fi 承载（我们）更合适**：

- 照片是 MB 级。BT-SPP 的 100–200 KB/s 让 5MB 单图就要 30s+（技术文档 §11.3 2219-2220）；我们的热点路径代码注释记录“实测 ~50Mbps”（`Xfer.java:460`），同 LAN/P2P 也是同一量级。OPPO 只有满足 6 条件才升级 P2P，否则就接受 SPP 的慢。
- 但我们只是“承载类型”选对了；**选路方式**不如 OPPO 确定：
  - 我们是在发送方上滑后串行尝试 ①②③，热点加入最长可等 31s + 3 次连接，期间没有总预算（`Xfer.java:493-565`）；
  - 我们两端都开热点，违背了“AP 与 P2P 互斥”的硬件事实（`Xfer.java:264-268`），而 OPPO 的 `wlanMode` 判据正是为了避免这一点。
- **建议的落点**：保留 Wi-Fi（SoftAP/LAN/P2P）作为主承载，**不引入 BT-SPP**；但要补 OPPO 的三件事：`wlanMode` 式的显式判据、只让一侧承担 AP/组主、凭据在碰触瞬间才交付。

---

## 2. 谁连谁 / 角色（重点）

### 2.1 我们的角色模型

碰触后（`TapService.beginPeerRound`，`TapService.java:313-331`）：

1. `setExpectedPeer(...)` 记录对方 payload（`TapService.java:316-317`；`Xfer.java:185-211`）。
2. `startAsReceiver()`：两端都置 `mReceiverRole=true`，都 `startServer()` 监听 8988（`TapService.java:318`；`Xfer.java:383-417`，`:399`，`:751-780`）。
3. `Bus.lockRole()`：**进程内** 30s 锁，只防同一台手机的重复事件（`TapService.java:319`；`Bus.java:270-287`）。
4. `setCanSend(hasPending())`：有照片但未上滑时仍是“可能发送方”（`TapService.java:322`；`Xfer.java:445-447`）。
5. `startHotspot()`：两端无条件准备热点（`TapService.java:323`；Wi-Fi 开着时提前返回，`Xfer.java:265-268`）。
6. 有照片弹发送页，无照片弹空态接收页（`TapService.java:324-329`）。

上滑后：`mUserWantsSend=true`、`setSenderRole()` 把 `mReceiverRole=false`、`mCanSend=false`（`TapService.java:180-181`；`Xfer.java:450-453`），链路已就绪就直接 `sendImages`，否则 `startAsSender()`（`TapService.java:182-190`）。

**三个防“互连”的机制，实际职责各不相同**：

| 机制 | 代码 | 它解决什么 | 它不能解决什么 |
|---|---|---|---|
| `mayDriveP2p()` / `mDriveP2p` | `Xfer.java:456-458, 466-469` | 只有“已决定接收（且手上无照片）”或“发送方 ①② 都失败”才允许发起 P2P 发现/连接。避免“有照片但还没上滑”的一侧把对方连进来当数据读 | **不防两端同时上滑**（两者都 `mDriveP2p`/都 sender）；**不防无照片的接收方在碰后立刻驱动 P2P**（`mReceiverRole && !mCanSend` 为真） |
| `peerSpeaksFirst()` 3s 探测 | `Xfer.java:730-743, 763-772` | 连接已 accept 后，判断“这条 socket 是对方来写报价的”还是“对方只是来读的空白连接”；静默且本机还能发 → `stashForSending` 暂存 | 不阻止双方互连；它是 **accept 之后的分类器**，不参与 P2P 发起决策；每个静默连接会占用 accept 线程 3s |
| `mCanSend` | `TapService.java:322`；`Xfer.java:767-768` | 有照片未上滑时，不把静默连接当入站数据消费，留给“我上滑后发送” | 跨设备无共识；上滑瞬间到对端 META 到达之间仍有竞态 |

**热点/ LAN 场景里到底谁做什么**：

- **热点**：设计意图是“接收侧开 AP、发送侧连”。代码实际是**两端只要轮到 `beginPeerRound` 就都开**（`TapService.java:323`），发送方在上滑后按 payload 的 `hs` 去加入（`Xfer.java:464, 493-571`）。谁最终是真 AP 取决于“谁的热点在本次碰触前已经起来并写进了 payload”；本轮新开的 AP 不在本轮 payload 里（见 §1.2 的时序说明）。
- **同 LAN**：**两端都监听 8988**（`Xfer.java:399, 751-780`），发送方主动连对方 payload 的 `ip:8988`（`Xfer.java:465, 474-489`）。不存在“固定服务端”。
- **P2P**：`mayDriveP2p()` 为真的一侧发起 `WifiP2pManager.connect`（`Xfer.java:678-705`）；系统决定谁当 GO（代码未设 `groupOwnerIntent`，`:693-695`）。组建立后，GO 调 `startServer()`，作为接收方的 GC 调 `startClientRead()` 主动连 GO 的 8988；发送方若是 GC，则在真正发送时连 GO（`Xfer.java:712-727, 783-800, 923-934`）。

### 2.2 逐场景“谁开、谁连、谁听、谁发”（我们）

设 A=上滑方（发送方），B=另一端。

| 场景 | 谁开 AP | 谁监听 8988 | 谁连谁 | 谁发 / 谁收 | 结果 / 备注 |
|---|---|---|---|---|---|
| **同 LAN，A 上滑** | 无 | A、B 都监听 | A → B 的 `ip:8988`（`Xfer.java:465, 474-489`） | A 发，B 收 | 最确定的一条；前提是 B 的 `lanIpv4()` 拿到的是可达 LAN 地址（`Bus.java:202-239`） |
| **B 的热点在碰前已开，A 上滑** | B（AP），A 作为 STA 加入 | A、B 都监听 | A 加入 `hs` → 网关:8988（`Xfer.java:493-571`） | A 发，B 收 | 只有“热点在碰触前已开”才成立；A 自己若也开了 AP 则可能加不进去 |
| **Wi-Fi 关闭，双方本轮都新开 AP** | A、B 都开 | A、B 都监听 | **没有可用的对方热点凭据** → A 试 `ip`（很可能是自己的 AP 网段，不可达），再试 P2P | 无人能发 | 双 AP 占射频，P2P 可能起不来（`Xfer.java:264-268`；`ALIGN_PLAN_FINAL.md:870`） |
| **P2P 兜底，A 上滑，B 无照片** | 无 | A、B 都监听 | B 可能在碰后就先驱动 P2P 连接 A；A 在 ①② 失败后也驱动 | 组好后：若 A 是 GO，B 作为接收 GC 会 `startClientRead` 连 A；若 A 是 GC，A 发送时连 GO | B 先驱动时，A 的 `peerSpeaksFirst` 若 3s 静默且 A 有照片就 `stash`，等 A 上滑复用（`Xfer.java:746-749, 763-768`） |
| **P2P 组已先建好，A 后上滑** | 无 | GO 监听 | `mLinkReady=true` → A 直接 `sendImages`（`TapService.java:182-187`） | A 发，B 收 | 这条路径成立依赖 `mPendingSocket` 或组地址已就绪（`Xfer.java:922-935`） |
| **两端同时上滑** | 无 | A、B 都监听 | 各自向对方发起/各自 `stash` | **双方都想发，没人读** | 双方各等 60s，各报“对方未响应”（详见 §2.4） |

### 2.3 OPPO 的角色模型

OPPO 把“连接角色”和“内容角色”分开：

- **连接角色**：
  - `NfcPairRole{SEEKER, PROVIDER}`：SEEKER 先发配对报文，PROVIDER 回应（技术文档 §3.1 268-291）。
  - BT：SEEKER `connectAccessory`（client），PROVIDER `acceptAccessory`（server listener）（技术文档 §3.1⑥ 425-464；`transport.md:434-441`）。
  - P2P：**被碰方（通常即 NFC 卡侧 `[推断]`）`createGroup()` = GO；碰方（reader `[推断]`）`connect()` = GC**；`enablePersistentMode(false)`（技术文档 §6.2 1396；`transport.md:242-251`）。GO/GC 不由 `groupOwnerIntent` 争抢，文档说全树 0 命中（`transport.md:249-251`）。
- **内容角色**：`InternalNfcEventRole{SENDER,RECEIVER}` 在 `NfcDataSyncContract` 里交换并裁决（技术文档 §3.1②、§11.2 2211）；它和 GO/GC **不是一回事**。因此“被碰方”在 P2P 里是 GO，但完全可能是接收方；碰方是 GC，也可能是接收方。

| OPPO 场景 | 谁开组/监听 | 谁连接 | 谁发/谁收 | 备注 |
|---|---|---|---|---|
| BT SPP | PROVIDER 起 BT server listener | SEEKER 作为 BT client 连接 | 由 DataSync 的 sender/receiver 决定 | 文件字节最终交 OShare（技术文档 §6.4） |
| Wi-Fi P2P | 被碰方 `createGroup` = GO，GO 侧 SCP server 等待 | 碰方 `connect` = GC | 同上，与 GO/GC 无关 | 组不持久化；P2P 失败不回落 SPP（技术文档 §6.2 1414） |
| 同账号 | — | — | 接收方**自动同意**，不弹接收确认（技术文档 §3A.3 1092-1108） | 只比 accountHash 前 3 字节，是 OPPO 的缺陷 |
| 不同账号 | — | — | 双方各自确认 | 确认在两个阶段发生（配对链 10/23s，传输链 65/65s） |

### 2.4 我们的模型在“两端同时开热点 / 同时上滑”时会发生什么

#### (a) 两端同时开热点（Wi-Fi 都关闭）

代码推断（未实测）：

1. 双方 `onPeer` → `startAsReceiver()` → `startServer()`；随后 `startHotspot()`（`TapService.java:318-323`）。Wi-Fi 关闭，两个 `LocalOnlyHotspot` 都被拉起（`Xfer.java:259-332`）。
2. API 33+ 两端都用**同一个** `TapShare-Transfer` / `tapshare2026`（`Xfer.java:55-56, 312-320`）；API <33 由系统给随机 SSID，但也不在本轮 payload 里。
3. 本轮 payload 是在 `startHotspot()` 之前生成的（`HceService.java:52-58`；`TapService.java:517-537`），所以我们拿到的对方 `mPeerHsSsid` 为空，`startConnectStrategy` **不会**尝试热点（`Xfer.java:464`）。
4. 发送方退到 `ip`（如果 `lanIpv4()` 返回的是自己的 AP 地址，则不可达；`Bus.java:202-239`）再退 P2P。双 SoftAP 会占住 Wi-Fi 射频；本 ROM 的注释明确“热点与 Wi-Fi 互斥”（`Xfer.java:264-268`），P2P 可能无法搜索/建组。
5. 第二轮再碰：双方 payload 才带上各自热点口令。但双方自己的 AP 都还开着（发送方的 AP 没有主动关闭路径，`Xfer.java:334-344, 1156-1164`）；“自己开 AP 的同时作为 STA 加入对方 AP”是否可行，取决于芯片并发能力，本 ROM 注释表明不乐观。API 33+ 还存在两个 AP 同名同密码、`joinHotspot` 仅按 SSID 加入（`Xfer.java:502-524`）的歧义。
6. `mHotspotStarting` 没有超时：如果系统一直不回调 `onStarted/onFailed`，后续 `startHotspot()` 会因 `mHotspotStarting` 直接返回（`Xfer.java:260`），本轮/后续轮都不会再尝试。

**实测覆盖情况**：没有找到“双热点”通过的记录。`ALIGN_PLAN_FINAL.md:440` 把它列为风险，`:870` 明确写“**必须真机验证，不要凭猜测先改**”；`:878` 的真机清单只列了“双上滑”，没有列双热点。结论：**代码路径上双热点是一个已知、未消解的风险**，不是被验证过的稳定路径。

#### (b) 两端同时上滑

代码推断（与 `COMPARE_FLOW_VS_OPPO.md §3.3` 的流程层结论一致，这里补连接层）：

1. 两端都 `mUserWantsSend=true`、`setSenderRole()` → `mReceiverRole=false`、`mCanSend=false`（`TapService.java:180-181`；`Xfer.java:450-453`）。
2. 各自 `startServer()` 的 accept 循环看到 `!mReceiverRole` 为真，**把对方连进来的 socket 直接 `stashForSending`，不读**（`Xfer.java:762-764`）。
3. 各自（在链路就绪或兜底连接成功后）`sendImages()` 在自己的出站/暂存 socket 上写 `META+PREVIEW`，然后 `readVerdict()` 阻塞 60s（`Xfer.java:956-980, 1030-1054`）；若双方都还没有连接，则各自 `startAsSender()` 去建链，最终仍会走到同一局面。
4. 对方不会读这份报价：它也在等回执；即使读到 `META`，`TapService.onSendStarted` 第一行 `if (mUserWantsSend) return;` 会直接丢弃（`TapService.java:401-403`）。
5. 60s 后两端都 `onVerdictTimeout` → 各弹“对方未响应”（`TapService.java:507-513`）。本轮不传输，需重新碰。

**是否有跨设备防护**：没有。`mRound`/`mVerdictSent` 只防本机回执重复/跨轮（`Xfer.java:124, 242-243, 1085-1089`）；`mConnecting` 只防本机重复 `connect`（`Xfer.java:678, 690-705`）；`Bus.lockRole` 只防本进程重复事件（`Bus.java:270-287`）。**是否实测**：`ALIGN_PLAN_FINAL.md:210` 把“两边各等 60s”定义为可接受降级，`:857` 列为待定项，`:878` 列为真机验证第 1 条；**没有实测通过记录**。`APP_FLOW_ANALYSIS.md:522` 也明确说“代码无仲裁，实际表现无法静态推断”，本文按代码给出的是最可能的退化路径。

---

## 3. 寻址与发现

### 3.1 我们的 payload 与对端选择

**payload 字段**（`Bus.java:155-178`；格式注释 `Bus.java:24`）：

| 字段 | 来源 | 用途 |
|---|---|---|
| `n=` | `Build.MODEL` | 显示名兜底（`TapService.java:607-615`）；无 P2P 字段时也参与匹配（`Xfer.java:661`） |
| `b=` | 蓝牙适配器名 | 显示名；无 P2P 字段时参与匹配（`Xfer.java:662`） |
| `t=` | 进程启动时随机 UUID 前 8 位（`Bus.java:165`） | 只作显示名兜底（`TapService.java:613-614`）；**不参与连接、不参与校验** |
| `ts=` | 本机时间 | 仅日志/展示 |
| `p=` | Wi-Fi Direct 设备名 | P2P 精确匹配（`Xfer.java:657`） |
| `a=` | Wi-Fi Direct 设备地址 | P2P 精确匹配（`Xfer.java:656`）；系统给的匿名地址 `02:00:00:00:00:00` 会被丢弃（`Bus.java:81-87`） |
| `ip=` | `lanIpv4()` | 同 LAN 直连（`Xfer.java:465`） |
| `hs=` / `hp=` | 本机热点 SSID/口令 | 对方热点直连（`Xfer.java:464, 493-571`）；`setHotspotInfo` 还把口令打进 logcat（`Bus.java:64-69`） |

约束与损坏路径：

- `MAX_PAYLOAD=200`，超长**静默截断**（`Bus.java:41, 96-113`）；尾部依次是 `hs`/`hp`/`ip`，最容易被截掉。
- `safe()` 把 `|`、`;` 替换成 `/`、`,`（`Bus.java:197-199`），含这些字符的 SSID/口令会被改坏。
- `selfPayload()` 有 1.2s 缓存（`Bus.java:43-47, 144-153`）；`setHotspotInfo` / `setP2pIdentity` 变更加快作废（`Bus.java:180-195`）。
- 读卡侧把整段 payload 打进日志（`TapService.java:130`；`HceService.java:50`），`setExpectedPeer` 记录对端 SSID（`Xfer.java:206-210`）。

**三种承载的地址匹配**：

| 承载 | 用什么选对端 | 代码 | 失败后 |
|---|---|---|---|
| 热点 | `hs`（SSID）；加入后取默认路由网关，连网关 `:8988` | `Xfer.java:464, 493-571` | 返回 false → 试 LAN → 试 P2P |
| 同 LAN | payload 的 `ip`，直接 `connect(ip, 8988)` | `Xfer.java:465, 474-490` | 2 次失败 → 试 P2P |
| P2P | `matchesPeer()`：先比 `p2pAddr`，再比 `p2pName`；两者都为空时，**任意名字以 `Android` 开头**、或等于型号/蓝牙名的设备都算匹配 | `Xfer.java:653-665` | 无匹配 → `retryDiscovery()`；5 次后 `fail("P2P 搜索多次失败")`（`Xfer.java:600-605, 684-687`） |

### 3.2 多台设备同场会不会连错？

**会，至少有五条路径**：

1. **固定热点 SSID/口令（API 33+）**：所有装了本 App 的手机都开 `TapShare-Transfer` / `tapshare2026`（`Xfer.java:55-56, 312-320`）。`joinHotspot` 只按 SSID 发 `WifiNetworkSpecifier`，不校验 BSSID/对端身份（`Xfer.java:502-524`）。办公室多台手机同时开热点时，可能加入**另一台**同名 AP；随后连它的网关 `:8988`。如果那台也在跑 TapService 且处于接收态，它会接受连接并读数据（`Xfer.java:751-780`），照片就传给了错误设备。概率：在演示/办公环境有多台同 App 手机时**不低**。
2. **P2P 的 `Android*` 通配兜底**：当对方 payload 没带 `p`/`a`（例如早期版本、或本机 SDK<29 时 `fetchSelfIdentity` 直接返回，`Xfer.java:166-167`；或 200B 截断），`matchesPeer` 会接受**任意** `Android...` 设备（`Xfer.java:658-660`）。P2P 默认设备名大量以 `Android` 开头，多台设备同场时可能连到陌生人。若对方没跑本 App，后续 TCP 失败；若对方也跑本 App，可能把照片发过去。
3. **型号/蓝牙名不唯一**：`n=`/`b=` 自报（`Bus.java:163-166`），可重复/可伪造；在“无 P2P 字段”的兜底分支里它们也被当作匹配依据（`Xfer.java:661-662`）。
4. **跨轮残留 socket**：`setExpectedPeer` 只有在 `!hasLiveLink()` 时才 `resetRoundState()`（`Xfer.java:198, 221-224`）。如果上一轮 accept 到一条连接、用户始终没上滑，`mPendingSocket` 仍连接着；新一次碰触会保留它，随后上滑的 `sendImages` 可能把照片写到**上一轮的对象**（`Xfer.java:922`）。代码注释是为了保护“正在传的连接”，但没有把 socket 与对端身份绑定。`[推断/未实测]`
5. **热点网段地址相同**：`lanIpv4()` 把 `192.168.43.x` 仅降级为 fallback（`Bus.java:236-239`）。若两端 LocalOnlyHotspot 都用常见的 `192.168.43.1`，payload 里的 `ip` 会相同；发送方 `connectTo("192.168.43.1")` 可能连回**本机**的 8988（路由到本机地址）或不可达，而不是对方。`[推断；取决于 ROM 的 LOHS 网段]`

**防错配机制**：没有密码学绑定。`t=` 是进程级随机且只用于显示（`Bus.java:163-168`；`TapService.java:613-614`），不进 TCP 帧；服务端 `accept()` 后不校验对端（`Xfer.java:751-780`）；接收方 `handleIncoming` 只看 MAGIC/TYPE/LEN（`Xfer.java:816-841`）。`mRound` 只是本机侧检查“读回执期间是否已开新一轮”（`Xfer.java:124, 1030-1054`），**回执帧本身不带轮次字段**，无法区分对端上一轮的回执。所以“匹配”完全依赖 NFC payload 里的地址/名字，而 NFC payload 是明文且可被截断/伪造。

### 3.3 OPPO 的寻址与匹配

- **NFC 是点对点的**：一次读卡只面对一张卡，不存在“附近设备列表里挑一个”的步骤。`networkName/networkPwd/networkFreq` 在广播态为空，**在被碰的瞬间**由 `OplusNfcProtocolImpl.d()` 用现场值覆写，再 AES-GCM 加密（技术文档 §6.3.2 1470-1491）。所以 P2P 的 SSID/口令是**随本次触碰一起**到达对端的，不会与“另一台同名热点”混淆；并且组名/口令由被碰方本地 `SecureRandom` 生成（`transport.md:251`）。
- **BT**：payload 里有 `btMacAddress`（字段 `e`，全大写，技术文档 §6.3.1 1438；`transport.md:266`），连接时用它。NFC 载荷里**没有设备名字段**（`p7/a` 字段表，`transport.md:258-284`）；设备名只用于 UI 展示，且是对端自报（技术文档 §3A.3 1086-1090）。
- **`protocolDeviceId`**：在 `serviceParams` 里（技术文档 §6.3.1 1465），用作 `ksc_info` 的复合键之一（`(deviceId, alias)`，技术文档 §3A.4-5 1141-1197），以及交给 OShare 的 `"deviceId"`（为空则直接不发送，技术文档 §6.4 1533；`transport.md:491`）。它不是“从列表里选设备”的地址，而是会话/落地路由的身份。
- **`modelId`**：3 字节，运行时来自 BLE/NSD/本地 `Build.MODEL` 查表（技术文档 §7.2 1630-1640），**只用于逐机型 UI 素材**，不参与连接寻址。
- **`major_type` / `DeviceType`**：在 NFC payload 的 `deviceType` 字段里（技术文档 §6.3.1 1436）；决定手机/平板/手表分支、自动接受规则、素材目录（技术文档 §3.7、§7.3 1642-1659）。**不是身份匹配键**。文档还纠正了“4 个 NFC PairTask 由 major_type 选择”的说法——实际由 NFC 发现子类型 + `NfcDeviceBrand` 选（技术文档 §7.3 1652-1660）。
- **账号**：`accountHash` 在 NFC 广播载荷里实际恒为空；账号比较发生在 SCP 契约链的 `NfcDataSync`，不在 NFC payload（技术文档 §6.3.3 1518；§3A.3 1110-1124）。

**结论**：OPPO 的“寻找对端”不靠名字/IP 猜测，而是“物理触碰选中的那一张卡 + 随包带来的 MAC/随机组凭据 + KSC 身份”。它在结构上避免了我们的多机错配；代价是它把信任压在 NFC 触碰和（有缺陷的）账号/KSC 上。我们的寻址在多台同 App 设备同场时**有真实错配路径**，其中固定热点 SSID/口令是最容易触发的一条。

---

## 4. 会话与复用

### 4.1 我们：每轮全新，没有身份记忆

- 每次碰触，`setExpectedPeer()` 在无活连接时调用 `resetRoundState()`，清组地址、等待标志、ServerSocket、pending socket、回执状态、`mRound++`（`Xfer.java:198, 226-256`）。
- 没有密钥、没有证书、没有设备身份表；`t=` 只是进程级 8 位随机串，重启就变，且不参与握手（`Bus.java:163-168`）。
- `SharedPreferences("tapdemo")` 只持久化“待发照片 Uri”，不持久化对端/连接（`TapService.java:229-247`）。
- 因此第二次碰与第一次**完全相同**：重新读 NFC、重新选承载、重新建立 TCP、重新上滑/接收。唯一可能的“复用”是：
  - 上一轮热点没关（接收方在无数据时不会关；发送方从不关自己的 AP）；
  - 上一轮 `mPendingSocket` 还活着（`hasLiveLink()` 为真），于是跳过 reset——这不是有意的会话复用，而是潜在串轮（见 §3.2）。
- 没有“第二次免确认”的协议基础；每次都需接收方点“接收”。

### 4.2 OPPO：首配对 UKey2 → KSC 持久化 → 重连 HMAC 挑战

- **首配对**：`KscNegotiationContract` 里走 UKey2（ECDH P-256 + SHA-512 + HKDF）或同账号的 OobKey（技术文档 §3A.2 1038-1080、§3A.6 1239-1251）。成功后在 `ksc_info` 落库：**16B KSC + 6B alias**，复合唯一键 `(deviceId, alias)`（技术文档 §3A.4 1126-1174；§3A.5 1176-1197）。
- **重连**：用 KSC 做 `HmacSHA512` 挑战-应答，截断 8 字节（技术文档 §3A.8 1306-1329）；`checkAccessoryAvailable()` 为真时直接跳过 KSC 协商（技术文档 §3A.6 1254-1256）。
- **同账号**：接收方在 `NfcReceiverConfirmContract` 里直接 `RECEIVER_AGREE`，不弹 UI（技术文档 §3A.3 1092-1108）。
- **第二次碰的体验**：
  - 省掉 KSC 协商（机器步骤 6s，技术文档 §3.9 的 KscNegotiation 行，613 行）；
  - 同账号时省掉接收方确认（原本 23s 窗口，技术文档 §3.9 的 ReceiverConfirm 行，612 行）；
  - 仍要经过 NFC PairInit/DataSync、发送方确认，以及传输链的 Sender/Receiver Confirm（65s 档，技术文档 §5 1362-1368）；真正传文件仍走 OShare。
  - 因此“老用户碰一下就传完”主要来自 **同账号自动同意 + KSC 免协商**，不是“完全不握手”。
- **OPPO 自身的问题**（不影响上面的机制结论，但决定我们要不要照抄）：
  - UK2 的 2 字节用户验证串被显式丢弃，首配对是纯 TOFU，可被中间人（技术文档 §3A.3 1084-1090）；
  - 账号只比 hash 前 3 字节（24 bit），碰撞概率 1/1677 万（技术文档 §3A.3 1110-1124）；
  - 重连挑战**遍历所有已存 KSC**，不是按 deviceId 精确查（技术文档 §3A.8 1319-1329）；
  - KSC at-rest 用 AES/CBC/NoPadding + 固定 keystore 别名（技术文档 §3A.4 1158-1167）。

### 4.3 结论：第二次碰差在哪？

| 维度 | 我们 | OPPO |
|---|---|---|
| 第二次是否更快 | **否**，流程与第一次相同 | **是**：省 6s KSC 协商；同账号省接收方确认 |
| 是否需要再次确认 | 是，接收方每次点“接收” | 同账号**自动同意**；不同账号仍需确认 |
| 是否记住对端 | 否 | 是，`(deviceId, alias)` + KSC |
| 首次成本 | 无密钥协商（所以也不安全） | UKey2/OOB + KSC（6s 档） |
| 安全前提 | 无 | TOFU + KSC；但 UK2 验证串被丢弃、账号 hash 只比 3B、KSC 遍历有缺陷 |

**判断**：我们现在**没有“第二次更快”的包袱，也没有任何身份/信任**。如果产品要“同一台手机第二次碰只上滑、不再点接收”，需要先补 §7 的 P0 安全基础，再做 KSC 式的身份记忆；否则只是把“免确认”建立在明文/可伪造的身份上，风险更大。

---

## 5. 帧格式与传输协议

### 5.1 我们

**线格式**（`Xfer.java:57-62, 817-841, 958-968, 1004-1007`）：

```text
[MAGIC 4B = 0x4E464331 "NFC1"][TYPE 4B][LEN 4B][data LEN bytes]
```

- 5 种类型：`PREVIEW=1`、`FULL=2`、`META=3`、`ACCEPT=4`、`REJECT=5`（`Xfer.java:58-62`）。
- `META` 的 `LEN` 字段不是字节长度，而是**总张数**，没有 payload（`Xfer.java:829-837`）。
- `ACCEPT/REJECT` 的 `LEN=0`，读回执时也要读掉这个 int 以保持帧对齐（`Xfer.java:1039, 1101-1103`）。
- 每张整图一个 `FULL` 帧，**整张图片一次 `write` 完**，不分块、无序号（`Xfer.java:997-1010`）；预览只发第一张（`:962-969`）。
- 完成判定：`count >= expect` 提前 `onTransferDone`（`:871-876`）；连接 EOF 时 `count > 0` 也补发完成（`:879-883`）。**EOF 时 `count != expect` 不报错**，少发也算完成。
- `MAGIC` 不匹配时直接 `break`，随后按 EOF 路径处理（`Xfer.java:823-826, 878-889`）——**坏帧会被静默当成传输结束**，只打日志。
- 没有任何长度合法性校验：`new byte[len]` 直接用对端给的 `len`（`Xfer.java:840`）；负值/超大值会导致异常或内存问题。
- 没有校验和、没有加密、没有分片序号、没有重复检测；`mRound` 只用于本机判断“读回执期间是否开了新一轮”，既不进入数据帧，也不在回执帧里传输（`Xfer.java:124, 955, 1042-1044`）。
- 进度只有张数 `index/total`（`Xfer.java:39-50, 866-868`），没有字节/速率；没有传输中取消帧，`REJECT` 只能在 ACCEPT 前用（`Xfer.java:1075-1112`）。

### 5.2 OPPO

- **控制帧**：`[1B 报文类型][protobuf 载荷]`，类型值 111–116 故意落在可打印 ASCII `o p q r s t`（技术文档 §3.6 650-686；§11.2 2212）。共 **18 个**具名类型（技术文档 §3.6 650-675）。
- **SCP**：控制通道（UKey2/KSC/卡片/确认/元数据）走 SCP；文件字节**不经 SCP**，全部交 `com.coloros.oshare`（技术文档 §6.1 1384-1386、§6.4 1520-1550；`transport.md:61-78`）。
- **完整性/保密**：应用层 `AES/GCM/NoPadding`、128 位密钥、12B IV（技术文档 §3A.8 1299-1304）；GCM tag 提供完整性；另有 HMAC-SHA512 挑战用于身份。
- **序号**：NFC PairInit 用 32 位随机 `seq`，PROVIDER 回 `seq+1`，SEEKER 校验（技术文档 §3.1 276-291）。
- **分块**：文件字节的分块/序号在 OShare 内，逆向未展开 `[未确定]`；本 App 只负责 `sendData`。
- **进度**：`onPercentChange(deviceId, percent, remainTime)` 是整体百分比 + 剩余时间；本 App **只打日志并取消 25s 看门狗**，UI 进度环由独立动画源驱动（技术文档 §6.4 1542-1546；`transport.md:742`）。
- **取消**：`MESSAGE_TYPE_NFC_CANCEL_SEND=114`（技术文档 §3.6 671）；`ITransferService.cancel(deviceId)`（技术文档 §6.4 1548；`transport.md:557`）；契约层的用户取消走 `NfcPairCancellation`（技术文档 §6.5 1567-1568）。

### 5.3 对照表

| 维度 | 我们 | OPPO | 差距会带来什么 |
|---|---|---|---|
| 类型数 | 5（PREVIEW/FULL/META/ACCEPT/REJECT） | 18（配对/密钥/同步/确认/取消等） | 我们够用于“报价-裁决-交付”，但没有心跳/取消/进度/版本协商 |
| 帧头 / 长度 | 4B MAGIC + 4B TYPE + 4B LEN | 1B TYPE + protobuf（长度在 proto 内） | 我们的 LEN 直观，但对端可给任意值；OPPO 由 proto 解析器管边界 |
| 长度校验 | 无（`new byte[len]`） | protobuf 解析有边界检查 | 坏 `LEN` 可导致异常/OOM；我们没有“坏帧失败”语义 |
| 校验 | 无 | AES-GCM tag + HMAC-SHA512 身份 | 我们无法发现篡改/截断；坏帧被当 EOF 静默结束 |
| 加密 | 无 | 应用层 AES-GCM-128 | 同一热点/LAN 上的第三方可读内容 |
| 序号/分块 | 无；整图一帧 | PairInit 有 seq；文件分块在 OShare `[未确定]` | 大图/多图时我们只能整帧重来；没有重复检测 |
| 进度 | 张数 x/y | 整体百分比 + 剩余时间（仅日志+看门狗） | 我们没有字节进度/速率，长传输无反馈 |
| 取消 | 仅 ACCEPT 前的 REJECT；传输中无 | 类型 114 + `cancel(deviceId)` | 用户一旦点了接收，无法中途取消；只能杀进程/断网 |
| 完成语义 | `count>=expect` 或 EOF；不校验 `count==expect` | OShare `onResult` 状态码 + 结果枚举 | 少发/截断时我们可能仍然显示“已收到 N 张” |

**结论：我们缺什么、会导致什么**：

- **缺长度校验** → 一个损坏/恶意的 `LEN` 会让接收线程抛异常或分配巨大数组；异常路径**不会**触发 `onTransferDone`（`Xfer.java:895-900`），接收页停在“正在接收”。
- **缺完整性校验/加密** → 传输内容可被同网/同热点的人读取或篡改；坏帧被静默当 EOF（`Xfer.java:823-826`），用户看到“接收完成”但内容可能不完整。
- **缺分块/序号** → 单张超大图必须完整进内存（`readAll` 把整张读进 `byte[]`，`Xfer.java:982-1009`）；重复帧会被当成多张图逐张落盘（`saveToGallery` 每次新文件名，`Xfer.java:1216-1233`）。
- **缺传输中取消** → ACCEPT 之后无法停；对端只能断开，而接收端的异常路径不会通知 UI。
- **缺版本字段** → 新旧 APK 混装时无法识别/拒绝不兼容帧；`MAGIC` 只区分“本协议/非本协议”。

---

## 6. 可靠性

### 6.1 我们的重试矩阵（逐项核对）

| 阶段 | 次数 / 上限 | 超时 / 退避 | 代码 | 失败后的用户可见反馈 |
|---|---|---|---|---|
| 开热点 | 1 次；无超时 | 回调失败只打日志；`mHotspotStarting` 可能永久为真 | `Xfer.java:259-332`（失败 `:287-303`） | 无；日志“热点开启失败…走局域网/P2P 兜底” |
| 加入对方热点 | 网络请求 1 次；TCP 3 次 | `requestNetwork` 30s，等待 31s；网关轮询 10×500ms；TCP 每次 2s + 1s 间隔 | `Xfer.java:493-571` | 返回 false → 继续 ②/③ |
| 同 LAN 连接 | 2 次 | 每次 1.5s | `Xfer.java:474-490` | 返回 false → P2P |
| P2P 发现 | 初始 + 最多 5 次重试 | `3+2n` 秒（5/7/9/11/13s） | `Xfer.java:600-615`（`mRetry>=5` 失败） | 发送方 `onXferFailed("P2P 搜索多次失败")` → 状态行 + 横幅（`TapService.java:471-483`） |
| P2P `connect` | 每次 `onPeers` 1 次，`mConnecting` 幂等 | 无单独超时；失败后 `cancelConnect` + `retryDiscovery` | `Xfer.java:690-705` | 同上，累计到 5 次后失败 |
| 接收方组员连接组主 | 6 次 | 每次 connect 5s + sleep 2s | `Xfer.java:783-800` | **只打日志，不回调失败** |
| `sendImages` 等连接 | 组主 10s；非组主 20s | 1s 轮询 | `Xfer.java:936-951` | `fail("对方尚未连接…")` / `fail("连接尚未就绪…")`（若 `mUserWantsSend` 才显示横幅） |
| 等 ACCEPT/REJECT | 1 次 | 60s `SO_TIMEOUT` | `Xfer.java:63, 1030-1054` | `onVerdictTimeout` → 横幅“对方未响应” |
| 连接方向探测 | 1 次 | 3s | `Xfer.java:64, 730-743` | 静默连接按规则 stash 或关闭 |
| “20 秒”提示 | 仅日志 | 不失败、不改变状态 | `Xfer.java:410-416, 435-441` | 用户无感，可能一直等 |
| **传输阶段** | **无总超时、无空闲超时、无心跳** | 接收 `readInt` 阻塞；发送 `write` 阻塞；只有回执阶段设过 `SO_TIMEOUT` | `Xfer.java:804-841, 997-1010` | 对端掉线/假死时可能永久卡住 |

补充：

- `mSenderWaiting` 在 `startAsSender` 里置 true（`Xfer.java:432`），失败后只有 `resetRoundState` 会清（`:236`）；**同一轮再次上滑不会重试 `startAsSender`**（`:428-431`），只能重新碰。
- `mDiscovering`/`mRetry` 只在 `startAsReceiver` 或 reset 时重置（`Xfer.java:404-405, 237`）；一次 P2P 失败后，本轮不会再给第二次完整的搜索预算。
- `startAsReceiver` 的 20s（`Xfer.java:410-416`）只是日志，接收方**永远不会**因为“没人连进来”而失败/收起页面。
- `startServer` 对每个连接最多做 3s `peerSpeaksFirst`（`Xfer.java:730-743`）；恶意/异常客户端可以反复占用 accept 线程，延迟正常连接（连接层 DoS 面）。

### 6.2 OPPO 的重试与保活

| 层 | OPPO 做法 | 证据 |
|---|---|---|
| 碰一碰主链路（pair/share/tapshare） | **完全不重试**；失败弹 `operation_failed_hint`「操作失败，请稍后重试」，让用户重新碰 | 技术文档 §6.5 1577-1578；`transport.md:698` |
| NFC 配对链 | 每步独立超时：6/6/10/23/6/6s，最坏 ~57s | 技术文档 §3.9 764-786；`transport.md:604-615` |
| 传输链 | Ready 15s / Sender 65s / Receiver 65s / Transfer 24h | 技术文档 §3.2 470-479；`transport.md:636-646` |
| 连接层重试（通用） | IPC bind 2 次、GATT 3 次（线性 2s）、LAN 3 次（1s）、无感连接指数退避封顶 1h、延迟表 15s→10min | `transport.md:685-696` |
| SCP 心跳 | 10s 或 90s（端口 10155），P2P/USB 跳过 | `transport.md:733, 943`；技术文档 §6.5 1561 |
| 接收端进度心跳 | 10s 一轮，**两轮无变化 → `BT_DISCONNECTED`** | `transport.md:659`；技术文档 §6.5 1559 |
| OShare 传输看门狗 | 25s 无进度 → `SendFileException(2003)` | `transport.md:742`；技术文档 §6.5 1557 |
| 传输上限 | 24h（实际接近“永不超时”，但有上限） | 技术文档 §3.2 475；`transport.md:642` |

> **别误读**：GATT/BLE 重试不是碰一碰的承载（碰一碰用 SPP/P2P，`transport.md:88-93`）；`SenselessNetworkingImpl` 的 1h 封顶是通用无感连接路径。碰一碰实际的 P2P/SPP 连接层没有跨 bearer 重试，且受 6s 契约约束（技术文档 §3.9 429）。

### 6.3 三种故障下我们卡在哪、用户看到什么

| 故障 | 我们的卡点 | 用户看到 | OPPO 对应 |
|---|---|---|---|
| **对端掉线（有 FIN/RST）** | 连接前：发送方可能在 `joinHotspot` 30s+ / `connectTo` / P2P 5 次重试中；接收方无失败回调（`Xfer.java:410-416`）。传输中：发送方 `write` 抛异常 → `fail`（`:1021-1023`）；接收方在帧边界 EOF → 若 `count>0` 调 done（`:878-883`），若半帧异常 → 只打日志（`:895-900`） | 连接前：状态行“正在连接对方…”可能一直不消失（接收方）；发送方约 45s+ 后出“P2P 搜索多次失败”。传输中：发送方出失败横幅；接收方可能永久停在接收页（半帧），或显示已收 N 张但没后续 | 主链路失败会弹 toast 要求重碰；SCP/进度心跳可判定 `BT_DISCONNECTED` |
| **假死（进程在、socket 在、不读不写）** | 发送方等回执：60s 超时 → `onVerdictTimeout` → 横幅（`Xfer.java:1030-1054`；`TapService.java:507-513`）。发送方写数据：无 `SO_SNDTIMEO`，`write` 可能永久阻塞（`:998-1010`）。接收方 `readInt` 无超时（`:804-841`） | 发送方：等回执时有 60s 后文案；进入传输后无反馈，可能一直“发送中”。接收方：接收页永远不结束；热点不关（`:891-894`） | 10s 进度心跳两轮无变化 → 判定断连；25s 看门狗 |
| **半开连接（对端消失，无 FIN）** | 接收方阻塞在 `readInt`，OS TCP 重传超时可能很久；没有应用层心跳、没有 keepalive（全文件只有 `readVerdict` 的 `setSoTimeout`，`Xfer.java:1034`）。发送方写缓冲未满时看不出来，满后阻塞 | 双方可能长时间无任何错误提示；接收页/发送页都像“卡住”；只能杀进程/重新碰 | SCP 心跳（10/90s）+ 进度停滞检测 → 失败；24h 上限兜底 |

**结论**：我们的重试是“前置”的（连接尝试多），但**用户等待最长的两个阶段（等人点接收、真正传图）没有任何失败探测**。这是比“重试次数不够”更严重的问题：用户不知道是对方没点、网络断了，还是 App 卡死。

---

## 7. 安全（只谈连接相关）

### 7.1 我们的事实

| 事实 | 代码/证据 | 后果 |
|---|---|---|
| NFC payload 明文携带 `ip` / `hs` / `hp` | `Bus.java:170-177` | 任何能选中我们 AID 的读卡器（`apdu_service.xml:4` 明确 `requireDeviceUnlock=false`）都能拿走热点 SSID/口令/局域网 IP |
| 热点口令固定 | API 33+ 用 `TapShare-Transfer` / `tapshare2026`（`Xfer.java:55-56, 312-320`） | 所有安装同 App 的设备共享口令；知道口令即可加入热点、连 8988 |
| 传输 TCP 明文、无对端认证 | `ServerSocket(8988)` 接受任意连接（`Xfer.java:751-780`）；帧明文（`:57-62`）；文件字节直接写（`:997-1010`） | 同一热点/LAN 的第三方可连接、注入帧；若在同一 AP 上还可旁路未加密流量（取决于链路） |
| 没有加密/身份 | 全 `nfc/` 无 `Cipher`/`KeyAgreement`/`Mac`；只有本机 `mRound`（`Xfer.java:124`） | 内容与回执都可被旁观/中间人处理 |
| 凭据进日志 | `Bus.setHotspotInfo` 打口令（`Bus.java:64-69`）；HCE 打完整对方 payload（`HceService.java:50`）；`TapService.java:130` 也打 | logcat/调试日志是第二条泄漏面 |
| 服务端不认证、静默连接会被 `stashForSending` | `Xfer.java:759-761, 763-768, 746-749` | 任意主机连上 8988 就能把 `mGotConnection` 置真，抑制接收方的 P2P 兜底（`Xfer.java:400-403, 678`）；更严重的是，若本机此时 `mCanSend=true`，这个静默连接会被暂存为 `mPendingSocket`，之后 `sendImages` 会优先用它（`Xfer.java:922`） |
| `TapService` exported + DEBUG action | `Manifest:144-146`；`TapService.java:133-138` | 任意应用可伪造一次“碰触”和任意对端 payload（含 IP/热点），把连接引向攻击者指定的地址 |
| 蓝牙权限只用于取名 | `Manifest:45-46`；`Bus.java:158-168` | 不构成 BT 通道风险，但说明没有 BT 承载 |

> **一条完整的取图路径（代码成立，未实测）**：攻击者与本机在同一热点/LAN（固定口令 `tapshare2026`，`Xfer.java:55-56`）→ 连接 8988 → 静默 3s，`peerSpeaksFirst` 返回 false，而本机 `mCanSend=true`，于是 `stashForSending()` 把攻击者 socket 存成 `mPendingSocket`（`Xfer.java:765-768, 746-749`）→ 用户上滑 → 若链路就绪/走 P2P 兜底，`sendImages` 优先使用这个 `mPendingSocket`（`Xfer.java:922`）→ 攻击者回一个 `MAGIC+TYPE_ACCEPT+LEN=0` 的帧，即可收到后续全部 `FULL` 整图（`Xfer.java:1030-1072, 997-1010`）。正常连接若在之后到达会把 `mPendingSocket` 覆盖回合法 socket，所以这是一条**竞态窗口内的攻击**；但它说明“无认证”不只是被动泄漏，存在主动取图的可能。

### 7.2 OPPO 的事实（连接相关）

- NFC 广播态凭据为空，被碰瞬间注入 + AES-GCM-128（12B IV）（技术文档 §6.3.2 1470-1491）；APDU 内不放 IP（技术文档 §6.3.3 1516）。
- UKey2（ECDH P-256/SHA-512/HKDF）或同账号 OobKey；KSC 持久化后重连用 HMAC-SHA512 挑战（技术文档 §3A.2、§3A.4、§3A.8）。
- 应用层 `AES/GCM/NoPadding` 128 位，SCP 通道加密；`SPP_INSECURE` 指的是“无感连接通道”，**不跳过加密**（技术文档 §3A.7 1294、§3A.8 1299-1304）。
- 但 OPPO 也有安全缺陷：UK2 验证串被丢弃（§3A.3 1084-1090）、账号只比 3B hash（§3A.3 1110-1124）、KSC 挑战遍历所有密钥（§3A.8 1319-1329）、KSC at-rest 用 CBC（§3A.4 1158-1167）、SPP 链路本身是明文（技术文档 §3.8 761-762）。
- 文件字节交 OShare，OShare 自身的传输安全未在本 APK 内展开 `[未确定]`。

### 7.3 风险等级与最小修法

| 维度 | 我们 | OPPO |
|---|---|---|
| NFC 凭据暴露 | **高**：锁屏可读、明文、还进日志 | 低：延迟注入 + 加密 |
| 热点加入门槛 | **高**：固定口令 | 低：被碰方随机生成，随 NFC 加密下发 |
| TCP 对端认证 | **无** | KSC 挑战/身份（虽有遍历缺陷） |
| 内容保密/完整性 | **无** | AES-GCM-128 |
| 首配对 MITM | 无认证概念 | TOFU，且 UK2 验证串被丢弃 |
| 综合（近场传图） | **高** | **中**（仍明显强于我们） |

**最小修法**（不展开完整方案，按影响排序，详见 `docs/OPPO_ADOPTION_STATUS.md`）：

1. **#1 + #7（P0）**：NFC payload 不再常驻明文凭据；NFC 只交换临时 ECDH 公钥/会话标识，凭据现场派生密钥后 AES-GCM 加密；删除口令日志（`OPPO_ADOPTION_STATUS.md:37-40, 66, 72`）。
2. **#13（P0）**：热点口令随机化（或改用系统随机口令）；在会话密钥上做逐帧 AES-GCM（nonce + AAD 含 MAGIC/TYPE/LEN）；校验对端身份/会话令牌（`OPPO_ADOPTION_STATUS.md:78, 158-160`）。
3. **低成本立即项**：限制 `TapService` 的 DEBUG action（加签名权限或仅 debug 构建）；帧长上限/合法性校验；给接收 `Socket` 设读超时；删除 `hs/hp` 日志（`Manifest:144-146`；`Xfer.java:840`；`Bus.java:64-69`）。

---

## 8. 连接上最关键的 5 个差异（按影响排序）

### #1 安全与信任链：明文/固定口令/无认证 vs 延迟注入 + AES-GCM + KSC

- **差异**：我们的热点口令、IP、回执、文件全部明文，服务端不认证；OPPO 凭据只在碰触瞬间注入并加密，控制通道 AES-GCM，重连有 KSC 身份（技术文档 §6.3.2、§3A.8）。
- **实际后果**：锁屏时任何人贴一下就能读走热点口令；同一热点/LAN 的第三方可连 8988、注入帧或旁观未加密流量，甚至利用未认证的静默连接抢占 `mPendingSocket` 在用户上滑后取走整图（§7.1 的路径）；办公室多台同 App 手机可互相加入固定 SSID。这是“路人可读”级别，不是理论风险。
- **建议**：**必须改**，按 `OPPO_ADOPTION_STATUS.md` 的 P0 顺序（#1/#7/#13）。先做随机热点口令 + 删除明文凭据，再做 ECDH/AES-GCM；不要只做“随机口令”就认为安全，NFC 明文和 TCP 无认证仍在。

### #2 角色与并发仲裁：本机各自为政 vs 协议字段先裁决

- **差异**：我们两端都先当接收方，上滑才宣告发送；没有跨设备角色字段。OPPO 在 DataSync 里交换 `InternalNfcEventRole`，并在进入确认卡前就有共同答案（技术文档 §3.2、§11.2 2211）。
- **实际后果**：两端几乎同时上滑 → 双方各发 `META`、各等 60s、各报“对方未响应”；用户看到的是“对方没响应”，实际双方都在等。双热点/双 AP 也与角色不清有关（§2.4）。
- **建议**：**改**。最小方案：把已有的 `t=` 变成**轮次令牌**并在 payload 里加 `hasPhotos/roleIntent`，上滑时用确定性规则（如 token 小者发送）裁决；收到对方 `META` 且自己也想发时，败方转接收而不是静默返回（`TapService.java:401-403`）。不必引入 OPPO 的完整契约链，但必须有跨设备共识。

### #3 传输可靠性：无心跳/无读超时/无总超时 vs 心跳 + 停滞检测 + 24h 上限

- **差异**：我们只有连接前的重试；接收 `readInt` 和发送 `write` 都没有超时，传输阶段无心跳、无总时限（`Xfer.java:804-841, 997-1010`）。OPPO SCP 心跳 10/90s、进度心跳 10s 两轮无变化判失败、25s 看门狗、24h 上限（技术文档 §6.5；`transport.md:659, 733, 742`）。
- **实际后果**：对端掉线/假死/半开时，接收方永久停在接收页，发送方永久阻塞在 `write`；用户不知道是对方没点、网断了还是 App 卡死。只重碰也不一定有用，因为旧 socket/热点可能仍在。
- **建议**：**改**。优先给接收 socket 设 `SO_TIMEOUT`（例如 10–30s 空闲）、加应用层 `PING/PONG`、给 `sendImages` 一个总时限；断线时明确回调 `onXferFailed`（接收侧也要有）。这比继续增加连接重试次数收益大。

### #4 寻址与多机防错：名字/IP/固定 SSID vs 点对点 + 随机组凭据

- **差异**：我们靠 payload 明文里的 P2P 名/地址/IP/热点 SSID 寻址，`Android*` 通配兜底，API 33+ 热点 SSID/口令全设备固定（`Xfer.java:55-56, 653-665`）。OPPO 的 NFC 是点对点，P2P 组名/口令随机生成并加密随包下发，BT 用 MAC（技术文档 §6.3；`transport.md:242-251, 266`）。
- **实际后果**：办公室/演示现场多台手机时，可能加入别人的热点、连到别人的 P2P、或把照片发给上一轮残留 socket 对应的设备。
- **建议**：**改**。去掉 `Android*` 通配；热点 SSID 每轮随机并带对端可校验的轮次标识；payload 加 `sessionToken` 并在 TCP 首帧校验；发送前确认对端身份。不要只靠“名字相等”。

### #5 承载时序与双 AP：Wi-Fi 优先但热点在碰后才开 vs 显式判据一次选路

- **差异**：我们上滑后串行试“对方热点 → 同 LAN → P2P”，但两端都在碰后无条件开热点，且本轮 payload 没有新热点凭据（`TapService.java:323`；`HceService.java:58`）；OPPO 用 6 条件事前判据，SoftAP 开着就不选 P2P，走 SPP（技术文档 §6.2 1401-1416）。
- **实际后果**：Wi-Fi 关闭的第一次碰，热点通道用不上；双 AP 占射频，P2P 可能起不来；第二轮加入对方 AP 又可能因自己也是 AP 失败。用户表现为“碰了没反应/一直连接中”。
- **建议**：**改**，但**保留 Wi-Fi 承载**（照片场景不要学 BT-SPP）。最小方案：只有“决定做接收方”的一侧开 AP，发送方上滑时若自己 AP 无人使用就关掉；或把热点凭据放到碰后第二条消息（不能只靠本轮 NFC payload）。同时加一个 OPPO 式的 `wlanMode` 检查：本机/对端已有 AP/组时不再并发起 P2P。

> 其余差异（帧类型可打印、契约链、两次确认）属于可读性/工程化改进，不是连接上最关键的 5 条；见 §5、§9 与 `OPPO_ADOPTION_STATUS.md`。

---

## 9. 我们优于 OPPO 的部分

1. **Wi-Fi 直连对传图更合适**：我们热点/LAN/P2P 都是 Wi-Fi + TCP；热点路径代码注释记录实测 ~50Mbps（`Xfer.java:460`）。OPPO 碰一碰缺省 BT-SPP 只有约 100–200KB/s（技术文档 §11.3 2219-2220），5MB 单图 30s+。对“传几张照片”这个场景，Wi-Fi 是正确的主承载，不应为了“像 OPPO”退回 SPP。
2. **同一轮内有兜底，不“一次失败就重碰”**：我们上滑后自动 ①热点 → ②同 LAN → ③P2P，P2P 还有最多 5 次搜索重试（`Xfer.java:461-472, 600-615`）；OPPO 的 P2P 选了但连不上就是失败，不回落 SPP，用户只能重新碰（技术文档 §6.2 1414；§11.3 2221）。虽然我们的接收侧失败反馈很差（§6.3），但“连接层尽量在同一轮内完成”的方向是对的。
3. **接收侧自己落盘、自己打开查看器，不依赖私有 OShare**：`saveToGallery()` 直接写 MediaStore（`Xfer.java:1216-1237`），完成后用本轮的 MediaStore Uri 打开应用内查看器（`TapService.java:646-665`）。OPPO 把文件字节和落地都交给 `com.coloros.oshare`（技术文档 §6.4 1520-1550），依赖一个我们拿不到的私有服务；我们的实现可移植、可控，也避免了 OShare 的 `deviceId` 为空/回调异常等外部依赖。
4. **角色对用户意图更直接**：两端都先准备接收，谁上滑谁发送（`TapService.java:313-331`），不需要像 OPPO 那样在配对链里先做两次确认再进入传输链（技术文档 §5 1362-1368）。这在“我只想发、对方只想收”的普通场景下步骤更少。代价是缺少跨设备仲裁（§2.4、§8 #2），需要补而不是推翻。
5. **不依赖账号/厂商生态，触发用公开 NFC 机制**：我们不用账号体系，payload 里没有 accountHash；读卡用公开 `TECH_DISCOVERED`（`Manifest:127-141`），HCE 锁屏可用（`apdu_service.xml:4`），跨账号、跨品牌（只要装 App）都能用。OPPO 的同账号自动放行是体验优势，但它绑定账号/KSC 生态，且有 3 字节 hash、UK2 验证串丢弃等缺陷（技术文档 §3A.3）。我们可以在补安全后选择性借鉴“同一设备第二次免确认”，而不是整体照搬。
6. **两阶段“先报价、后发整图”**：`META+PREVIEW` 先到，接收方点接收后才发 `FULL`（`Xfer.java:956-980, 997-1020`）。这和 OPPO 的确认模型方向一致，但我们的实现更简单、内容边界清楚：ACCEPT 之前绝不发整图。保留这一点。

---

## 附：证据索引（便于复核）

**我们的代码**

- 连接/承载：`Xfer.java:53-64, 259-332, 383-417, 420-442, 450-472, 474-571, 600-615, 653-710, 712-727, 730-780, 783-800`
- 帧/传输：`Xfer.java:57-62, 804-901, 918-1026, 1030-1112, 1216-1237`
- 角色/流程：`TapService.java:176-191, 254-331, 371-389, 401-403, 419-455, 471-513, 517-542`
- 寻址/payload：`Bus.java:24, 41, 64-69, 81-87, 96-118, 144-178, 197-239`；`HceService.java:32-87`
- 权限：`Manifest:38-46, 127-159`；`apdu_service.xml:4`

**OPPO**

- 承载/选路：技术文档 §6.1-§6.3（1384-1516）；`transport.md:82-128, 132-253`
- 连接/BT/P2P：技术文档 §3.1⑥（425-464）、§6.2（1388-1424）；`transport.md:198-251, 431-441`
- 安全：技术文档 §3A.2-§3A.9（1038-1334）
- 超时/重试：技术文档 §3.9（764-786）、§3.2（466-504）、§6.5（1552-1593）；`transport.md:585-760`
- 帧/类型：技术文档 §3.6（650-686）
- 已有落地结论：`docs/OPPO_ADOPTION_STATUS.md`（P0 在第 37-40、62-78 行）
