# NFCTouchDemo 端到端流程分析（代码实证版）

> 分析对象：`/dfb/zhuyang/share/nfc_test/pri_project/android/pri_app/nfc_tap_demo`（分支 `nfc_tap`，提交 `057b617 上传合成代码`）
> 方法：逐行读源码，所有结论都带 `文件:行号`。**本文以代码为准**，与 `README.md` / `MERGE_PLAN.md` 冲突处在第 9、10 节显式列出。
> 包名 `com.pri.nfctouchdemo`；`minSdk 27 / targetSdk 37 / compileSdk 37`，Java 17（`app/build.gradle.kts:16-17,52-53`）。

---

## 1. 组件地图

### 1.1 进程 / 线程

| 项 | 事实 | 出处 |
|---|---|---|
| 进程数 | **单进程**（Manifest 里没有任何 `android:process`），全部组件跑在 `com.pri.nfctouchdemo` 默认进程里 | `app/src/main/AndroidManifest.xml:16-146` |
| UID | `<manifest android:sharedUserId="android.uid.system">` —— **以 system uid 运行**（需平台签名，见 §9.7） | `AndroidManifest.xml:17` |
| `TapService.onStartCommand` / `OverlayService.onStartCommand` | 主线程（Service 生命周期回调） | — |
| `TapService.onPeer` / Xfer 全部回调 | **主线程**：`Bus.onPeerData` 和 `Xfer` 都用 `Handler(Looper.getMainLooper())` post | `Bus.java:292-305`、`Xfer.java:61`、`TapService.java:34` |
| 读卡收发 APDU | 显式起 worker 线程 `new Thread(...).start()` | `TapService.java:323`、`MainActivity.java:412-414` |
| 卡模拟回调 `processCommandApdu` | HCE 框架分发（应用进程内），实现里只做字符串拼接 + 后台线程转交 | `HceService.java:33-87` |
| Xfer 网络收发 | 裸 `Thread`（`startServer`/`startClientRead`/`sendImages`/`sendPreviews`/`startConnectStrategy`/`joinHotspot`） | `Xfer.java:671,696,798,889,403` |
| 接收端解码 + 存相册 | 单线程 `ExecutorService`（注释：不占 socket 读线程，否则发送端被 TCP 背压卡住） | `Xfer.java:63-64,760-770` |
| OverlayService 图片解码 | worker `Thread` + `mainHandler.post` 回主线程 | `OverlayService.kt:273-292,314-332,343-369` |

### 1.2 组件表

| 组件 | 类 | 角色 | Manifest 声明 | 关键入口 |
|---|---|---|---|---|
| 启动页 | `HomeActivity.kt:41` | 选一张要发送的照片 → 交给 `TapService`；显示服务状态；退出常驻 | `exported=true`，`MAIN/LAUNCHER`，portrait（`AndroidManifest.xml:58-67`） | `pushToNfc()` `HomeActivity.kt:120-144` |
| NFC 联调页 | `nfc/MainActivity.java:47` | 二级页：日志控制台 / reader 模式 / 选图 / 分享入口 / adb 调试直达 | `exported=true`，`singleTask`，`SEND`+`SEND_MULTIPLE`（image/*）（`AndroidManifest.xml:73-91`） | `enableReaderMode` `MainActivity.java:362-364` |
| 透明跳板 | `nfc/TapDispatchActivity.java:10` | 系统把 NFC 标签投递到它 → 转 `TapService`（action `com.pri.nfcdemo.TAG` + `EXTRA_TAG`）→ `finish()` 秒退 | `exported=true`，`singleTop`，`excludeFromRecents`，`Theme.Translucent.NoTitleBar`，intent-filter `TECH_DISCOVERED` + `meta-data tech_list`（`AndroidManifest.xml:97-111`） | `onCreate` `TapDispatchActivity.java:12-22` |
| 常驻服务 | `nfc/TapService.java:31` | 碰一碰主流程：角色判定 / 驱动 Xfer / 驱动动效层 / 查看器 | `exported=true`（demo 用，无 intent-filter）（`AndroidManifest.xml:114-116`） | `onStartCommand` `TapService.java:70-143` |
| 卡模拟 | `nfc/HceService.java:23` | 发送侧（卡方）：响应 SELECT / EXCHANGE | `exported=true`，`permission=BIND_NFC_SERVICE`，`HOST_APDU_SERVICE` + `apdu_service.xml`（`AndroidManifest.xml:119-129`） | `processCommandApdu` `HceService.java:33-68` |
| 动效层宿主 | `service/OverlayService.kt:75` | 前台服务 + **整屏系统悬浮窗**（GL 水波 + Canvas 动效），播发送页/接收页 | `exported=false`，`foregroundServiceType="specialUse"`（`AndroidManifest.xml:136-143`） | `onStartCommand` `OverlayService.kt:137-152` |
| 旧悬浮窗工具 | `nfc/Overlay.java:10` | **另一套**悬浮窗（`TYPE_APPLICATION_OVERLAY`，直接 `addView`），只给"接收完成后的大图查看器"用 | 无 Manifest 声明（普通类） | `Overlay.show(View)` `Overlay.java:31-52`；调用点 `TapService.java:406`、`MainActivity.java:654` |

> 注意：项目里**同时存在两套悬浮窗**——动效层走 `OverlayService` 自己 `addView`（`OverlayService.kt:527-548`），查看器走 `nfc/Overlay.java`。进查看器前必须先摘掉动效窗口，注释里写明"两个悬浮窗别叠在一起"（`TapService.java:288-290,295`）。
> 声明了但**没有注册接收器**：`Xfer.register()` 是动态注册的 `BroadcastReceiver`（`Xfer.java:557-591`），Manifest 里没有 `<receiver>`。

---

## 2. NFC 握手（真实 APDU）

协议定义集中在 `Bus.java:19-24`（注释）、`Bus.java:33-39`（常量）。

### 2.1 常量与字节

| 名称 | 值 | 出处 |
|---|---|---|
| AID（7 字节） | `F05052494E4643`（= `'F0' 'P' 'R' 'I' 'N' 'F' 'C'`） | `res/xml/apdu_service.xml:8` |
| SELECT AID APDU | `00 A4 04 00 07 F0 50 52 49 4E 46 43 00` | `Bus.java:33-37` |
| SELECT 成功响应 | `90 00`（`Bus.SW_OK`） | `Bus.java:38` |
| EXCHANGE APDU | `80 10 00 00 <Lc> <payload…> 00`（CLA=0x80，INS=0x10，P1P2=0000，Lc=payload 长度，Le=0x00） | `Bus.java:96-112` |
| EXCHANGE 响应 | `<cardPayload…> 90 00` | `HceService.java:52-59` |
| 不支持指令 | `6D 00`（`SW_INS_NOT_SUPPORTED`） | `Bus.java:39` |
| APDU 异常（null / <4B） | `67 00` | `HceService.java:34-37` |
| payload 上限 | `MAX_PAYLOAD = 200` 字节，**超长静默截断** | `Bus.java:41,98-102` |
| 成功判定 | 末两字节 == `90 00` | `Bus.java:91-94` |
| 响应解析 | 去掉末 2 字节，其余按 UTF-8 = 对端 payload | `Bus.java:114-117` |

### 2.2 payload 报文格式

```
n=<Build.MODEL>|b=<蓝牙名>|t=<8位随机token>|ts=<HH:mm:ss>|p=<P2P名>|a=<P2P地址>|ip=<局域网IPv4>|hs=<热点SSID>|hp=<热点密码>
```

- 前 4 段只在首次计算时生成并缓存（`Bus.java:154-167`，`t` 是 `UUID` 前 8 位）。
- 后 5 段每次现拼（`Bus.java:169-176`）；`ip` 是 `lanIpv4()` 枚举网卡拿到的 wlan 地址（`Bus.java:200-233`）。
- 报文长度靠 **1200ms 短缓存**控制新鲜度（`Bus.PAYLOAD_TTL_MS`，`Bus.java:47,143-152`）；原因是注释里写的真机实测：现算要初始化蓝牙 + 枚举网卡，"卡方在对方选中 AID 之后 **249ms** 就以链路丢失结束会话"（`Bus.java:130-142`）。
- 类注释（`Bus.java:24`）只列了 `n/b/t/ts/p/ip` 六个字段，**漏了代码实际会发的 `a/hs/hp`**（文档滞后于代码）。

### 2.3 卡方（HCE）— `HceService.processCommandApdu`

```
收到 APDU
 ├─ 长度 < 4            → log + 返回 67 00                                    (HceService.java:34-37)
 ├─ 每个 APDU 都 hex 落日志（诊断"EXCHANGE 没到"还是"到了但解析失败"）        (HceService.java:39)
 ├─ isSelectOurAid(apdu)  → 逐字节比对 SELECT_AID_APDU → warmUpAsync() → 90 00 (HceService.java:41-46,96-103)
 ├─ CLA==0x80 && INS==0x10:
 │    ① 先拼响应：bytes = Bus.selfPayload().getBytes(UTF-8) + 90 00           (HceService.java:51-56)
 │    ② 再异步转交：daemon 线程 "hce-to-service" → startService(TapService,
 │       action="com.pri.nfcdemo.PEER", extra payload=<对方payload>, role="card") (HceService.java:57-59,71-87)
 │    顺序不可颠倒：注释写明"先 startService 再构造响应"会把响应拖过 ISO-DEP 超时      (HceService.java:16-19)
 └─ 其它 INS            → log + 6D 00                                        (HceService.java:61-62)
```

`onCreate` 与 `onDeactivated` 都会 `Bus.warmUpAsync()` 预热 payload（`HceService.java:26-30,90-94`）。

### 2.4 读卡方（reader）— 两条入口，同一套交换

| 入口 | 触发方式 | 代码 |
|---|---|---|
| A. 透明跳板（默认路径） | 系统 `TECH_DISCOVERED`（tech_list 只认 `android.nfc.tech.IsoDep`，`res/xml/tech_list.xml:4`）→ `TapDispatchActivity` → `TapService` action `com.pri.nfcdemo.TAG` | `TapDispatchActivity.java:15-19` → `TapService.java:77-80` → `handleTag()` `TapService.java:322-347` |
| B. 联调页 reader 模式 | 用户手动点"切换为接收方"→ `enableReaderMode(FLAG_READER_NFC_A\|FLAG_READER_SKIP_NDEF_CHECK)` → `onTagDiscovered` → `exchange()` | `MainActivity.java:359-378,409-453` |

两条入口的交换逻辑**逐句相同**：

```java
byte[] ex = Bus.buildExchangeApdu(Bus.selfPayload());   // 先算好 APDU（关键：不能夹在会话中间算）
iso.setTimeout(3000);                                   // ISO-DEP 超时 3s
if (!iso.isConnected()) iso.connect();
byte[] r = iso.transceive(Bus.SELECT_AID_APDU);         // ① SELECT
if (!Bus.isOk(r)) return;                               // 非 90 00 → "对方未接受我们的 AID"
byte[] resp = iso.transceive(ex);                       // ② EXCHANGE 送出本机 payload
String peer = Bus.parseExchangeResponse(resp);          // 去掉 90 00
Bus.onPeerData(peer, "reader");                         // ③ 广播给 UI 与服务
```
（`TapService.java:331-342`；`MainActivity.java:427-444`）

**读卡方是"先 SELECT 再 EXCHANGE"，卡方是被动应答**——所以一次碰触里两台手机各自都拿到了对方的 payload：卡方从 EXCHANGE 的 Lc 数据段拿到，读卡方从响应体拿到。

### 2.5 角色（"卡方/读卡方"）与"发送方/接收方"的关系

- `role="card"` / `"reader"` 这个字符串**只进日志**，不参与任何判定（`TapService.java:85` 原样透传，`TapService.onPeer` 完全没用 `role` 参数）。
- 谁是发送方完全由 **本机 `PENDING` 是否为空**决定（见下一节）。
- 与 NFC 方向无关，所以"发送方 = 卡方"只是 README 里的一种常见情形，不是约束（读卡方同样可以是发送方）。

---

## 3. 角色仲裁（我 = 发送方 or 接收方）

### 3.1 `PENDING` 列表

| 事实 | 出处 |
|---|---|
| `private static final ArrayList<Uri> PENDING` —— **进程级静态**，Activity/Service 共用 | `TapService.java:49` |
| 写入：`onStartCommand` 里 `ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` → `PENDING.clear/addAll` + `savePending()` | `TapService.java:93-101` |
| 持久化：SharedPreferences `tapdemo/pending`，`\|` 分隔的 Uri 串 | `TapService.java:146-153` |
| 恢复：`loadPending()` 在每个 `onStartCommand` 开头调用（只补一次，非空不覆盖） | `TapService.java:75,155-163` |
| 判定：`hasPending()` = `!PENDING.isEmpty()` | `TapService.java:52-54` |
| 清空：**只有** `onImageSent()`（发送成功后）清空 | `TapService.java:302-311` |
| 从启动页写入：`HomeActivity.pushToNfc()` 走 `ACTION_SEND_MULTIPLE`（等价于相册分享） | `HomeActivity.kt:120-144` |

### 3.2 精确代码路径

```
NFC 交换完成
   ├─ 卡方: HceService.postPeerToService() → startService(TapService, action=com.pri.nfcdemo.PEER)
   │        → TapService.onStartCommand:81-86 → Bus.onPeerData(payload, "card")
   └─ 读卡方: TapService.handleTag() (后台线程) → Bus.onPeerData(payload, "reader")
                       │
                       ▼  Bus.onPeerData (Bus.java:292-305)
        主线程 post → sListener.onPeer (MainActivity, 若有) + sServiceListener.onPeer (TapService)
                       │
                       ▼  TapService.onPeer (TapService.java:170-194)
  ① mLinkReady=false; mSendShown=false; mReceiveShown=false        (同一轮的状态清零)
  ② label = "用户" + field(payload,"t")                            (取不到 → "用户--------")
  ③ x = xfer(this);  x.setExpectedPeer(n,b,p,a,ip,hs,hp)           (解析对端 payload 存为匹配目标)
  ④ x.startAsReceiver()   ← **两边都先起服务端**（8988 监听 + removeGroup + 20s 计时）
  ⑤ if (!Bus.lockRole()) { log("角色已定,忽略重复的碰触事件"); return; }   ← 30s 自动过期的角色锁
  ⑥ if (PENDING.isEmpty())  → 接收方: x.startHotspot(); post(showReceivePage(label))
     else                    → 发送方: showSendPage(); x.startAsSender()
```

关键点：

- **角色锁 `Bus.lockRole()`**（`Bus.java:274-286`）：`synchronized`，`sRoleLocked && now - lockedAt < 30000` 时返回 false（30 秒自动过期，注释称之为"只支持一次的根因修复"）。它挡的是**同一轮重复的碰触事件**（例如选择 AID 后又来一次 TAG 派发），不是发送/接收仲裁。
- `x.startAsReceiver()` 在角色判定**之前**调用，注释写"服务端先就绪"（`TapService.java:179`）。副作用：**发送方也会** 起 8988 监听、`mReceiverRole=true`、`mDiscovering=true`、`removeGroup`、挂 20s 计时；随后 `startAsSender()` 再把 `mReceiverRole` 置回 false（`Xfer.java:378`）。
- 重复的碰触事件在 `onPeer` 开头就已经执行过 ③④（`setExpectedPeer` 会按 `hasLiveLink()` 决定是否清上一轮状态），然后才到 ⑤ 早退——**锁拦不住对 Xfer 目标信息的刷新**。

### 3.3 README 未覆盖的边界：双方都有 `PENDING`

代码里**没有任何"两边都是发送方"的仲裁**：两台机都选了照片时，两边都走 `showSendPage() + startAsSender()`，两边都弹发送页，谁先连上对方的 8988 谁先成为通信的写入端。README/MERGE_PLAN 都没提这个 case。

---

## 4. 传输层 `Xfer`（通道阶梯 / 协议 / 落盘）

### 4.1 常量总表

| 常量 | 值 | 含义 | 出处 |
|---|---|---|---|
| `PORT` | `8988` | 唯一 TCP 端口（收发共用） | `Xfer.java:49` |
| `MAGIC` | `0x4E464331`（"NFC1"） | 每帧魔数头 | `Xfer.java:53` |
| `TYPE_PREVIEW` | `1` | 预览缩略图 | `Xfer.java:54` |
| `TYPE_FULL` | `2` | 完整图片 | `Xfer.java:55` |
| `TYPE_META` | `3` | 发送预告：`len` 字段即总张数，无 payload | `Xfer.java:56` |
| `HS_SSID` | `"TapShare-Transfer"` | API 33+ 固定热点 SSID | `Xfer.java:51,268-277` |
| `HS_PASS` | `"tapshare2026"` | 固定热点密码（WPA2-PSK） | `Xfer.java:52,273-274` |

### 4.2 帧格式（TCP 上的私有二进制协议，无 HTTP）

```
[ MAGIC:int(4) ][ TYPE:int(4) ][ LEN:int(4) ][ LEN 字节数据 ]
TYPE_META  → LEN = 本次总张数
TYPE_PREVIEW → LEN = 缩略图 JPEG 字节（sender 侧 ≤900px，质量 82）
TYPE_FULL  → LEN = 原图文件字节（原样，不重压）
```
写：`Xfer.java:851-864`（META → 逐张 FULL）；读：`Xfer.java:724-771`；`EOFException` = 对方关闭连接 = 发送完毕（`Xfer.java:728-730`）。

### 4.3 通道阶梯（**注意顺序与 README 的直觉不同**）

接收方（无照片那台）在 `onPeer` 里 `x.startHotspot()`（`TapService.java:184`）——**先把自己变成热点**，把 SSID/密码写进下次碰触的 payload（`Xfer.setHotspotInfo` → `Bus.setHotspotInfo` `Bus.java:64-69`）。
发送方（有照片那台）拿到对端 payload 后按 `startConnectStrategy()` 依次尝试（`Xfer.java:401-412`）：

| # | 通道 | 触发条件 | 细节 | 超时/重试 |
|---|---|---|---|---|
| ① | **接入对方热点** `joinHotspot()` | 对方 payload 带 `hs=`（API ≥ 29） | `WifiNetworkSpecifier` + `requestNetwork`（`removeCapability(NET_CAPABILITY_INTERNET)`）；密码按 **WPA3** 请求（注释：本 ROM 是 WPA2/WPA3 混合，按 WPA2 匹配不上）；拿到 `Network` 后轮询默认路由取网关 IP，再用 `net.getSocketFactory()` 连网关:8988 | `requestNetwork(..., 30000)` + latch `await(31s)`；网关轮询 10×500ms；连接 3 次 × 2000ms，间隔 1s（`Xfer.java:433-511`）。**会弹系统"允许连接"确认框** |
| ② | **同网直连** `connectTo(mPeerLanIp,"局域网")` | 对方 payload 带 `ip=` | 直连 `new Socket().connect(ip:8988, 1500)`，最多 2 次（`Xfer.java:414-430`） | 1500ms × 2 |
| ③ | **Wi-Fi Direct 兜底** | 前两者都失败 | 见 §4.4 | 搜索重试 5 次，退避 `3000 + retry*2000` ms；失败即 `fail("P2P 搜索多次失败")`（`Xfer.java:540-555`） |

热点成功/失败都在日志里（`Xfer.java:222-225` 明确写"本机型热点与 Wi-Fi 互斥：Wi-Fi 正开着就跳过热点"）。

### 4.4 Wi-Fi Direct 细节

- 启动即 `warmUp()` → 注册广播 + `warmDiscoverLoop()`（每 8s 一次 `discoverPeers`，`mPauseWarm` 时 3s；`Xfer.java:304-326`）。
- 本机 P2P 名/地址通过 `requestDeviceInfo` 拿（API ≥ 29），写进 payload 的 `p=`/`a=`；匿名化地址 `02:00:00:00:00:00` 被过滤（`Xfer.java:135-152`，`Bus.java:81-87`）。
- 设备匹配 `matchesPeer()`：先比 P2P 地址、再比 P2P 名；两者都缺时兜底"名字以 Android 开头 / 等于机型名 / 等于蓝牙名"（`Xfer.java:593-605`）。
- **只有接收方主动发起 `connect`**（`Xfer.java:617-618`：`if (!mReceiverRole || ...) return;`）；发送方要么当组主等连接，要么当组员在"点发送"时连组主（`Xfer.java:802-814`）。
- 组建立后 `onConn()`：记 `mGoAddress` / `mImGo`；组主 `startServer()`，接收方组员 `startClientRead()`（`Xfer.java:652-667`）。
- 组员读图：最多 6 次、每次 `connect(goAddress:8988, 5000)`、间隔 2s（`Xfer.java:695-712`）。

### 4.5 `ServerSocket` 的服务端行为

```
startServer(): while(mRunning) { s = mServer.accept(); mGotConnection = true;
    if (mReceiverRole) handleIncoming(s);        // 接收方：直接读
    else mPendingSocket = s;                     // 发送方：暂存连接，等用户上滑           (Xfer.java:669-692)
}
```
发送方"暂存连接"再在 `sendImages()` 里取用（`Xfer.java:801-831`）；若还没等到：组主最多等 10s（`"对方尚未连接,请让对方保持在前台再试"`），非组主最多等 20s（`"连接尚未就绪(热点接入中,请稍候再上滑)"`）。

### 4.6 发送/接收的完整动作

**发送（`sendImages`，用户上滑后）**
1. 取 `mPendingSocket`；组员则现连组主 `connect(3000)`（`Xfer.java:802-814`）；
2. 起"预读线程"：逐张 `ContentResolver.openInputStream` → 全量读进内存 → 塞进容量 2 的 `ArrayBlockingQueue`（读相册与写 socket 并行，`Xfer.java:834-849`）；
3. 加 `mWriteLock` 串行化：先写 `MAGIC/TYPE_META/<张数>`，再逐张 `MAGIC/TYPE_FULL/<len>/<bytes>`，`flush()`（`Xfer.java:850-866`）；
4. `s.close()`、`mPendingSocket=null`、日志"发送完成,共 N 张 X 字节"；
5. 若走过热点通道：延迟 5s `releaseNetworkRequest()` 让本机 Wi-Fi 回原网（`Xfer.java:870-874`）；
6. 主线程回调 `onImageSent(totalBytes)`。

**预览（`sendPreviews`，链路刚就绪时）**：等最多 `20×500ms = 10s` 拿到已连接的 socket；最多推 **3 张**；每张 `thumbnail()` = 长边 >900px 时按 2 的幂降采样后 JPEG **质量 82**（`Xfer.java:887-950`；`.bak` 里的旧值是 360px / 质量 60）。

**接收（`handleIncoming`，可多张、一连接一帧流）**
- 读线程只收；每张 `TYPE_FULL` 丢给单线程 `mIo`：`decode()`（按 2 的幂降采样到长边 ≤1600px，`Xfer.java:995-1009`）+ `saveToGallery()`，然后主线程回调 `onImageReceived(idx, expect, bmp, bytes, saved)`；
- 读完后把"收完通知"也排在同一个 `mIo` 队列尾（保证顺序），再回调 `onTransferDone(list)`（`Xfer.java:772-784`）；
- 接收方且热点活着 → `closeHotspot()`（`Xfer.java:785-788`）。

### 4.7 文件落盘位置

| 内容 | 位置 | 出处 |
|---|---|---|
| 收到的**整图** | MediaStore，`Pictures/NfcTapDemo/nfctap_<System.currentTimeMillis()>.jpg`（**仅 API ≥ 29**；`<29` 直接 `return false`，注释"老版本要写权限,demo 不处理"） | `Xfer.java:1011-1032` |
| 接收页卡片用图 / 折射背景 | `getCacheDir()/peer_preview.jpg`（固定文件名，每次覆盖；JPEG 质量 90） | `TapService.java:369-386` |
| 发送侧 | 无临时文件（`Uri → 内存 → socket`） | `Xfer.java:841-842` |

---

## 5. UI / 动效层

### 5.1 `OverlayService` 的指令面（工厂方法 → action）

| 工厂方法 | action | 效果 | 出处 |
|---|---|---|---|
| `intent(ctx, backdrop, photo)` | `ACTION_START` | 发送页（无对端文案，退回内置文案） | `OverlayService.kt:930-934` |
| `intent(ctx, backdrop, photo, peer, count)` | `ACTION_START` | 发送页 + 对端标识/张数（**NFC 主路径用这个**） | `OverlayService.kt:943-949` |
| `receiveIntent(ctx, peer, count, preview)` | `ACTION_RECEIVE` | 接收页（`preview` 可为 null → 深色底 + 涟漪先播） | `OverlayService.kt:955-960` |
| `photoIntent(ctx, photo)` | `ACTION_PHOTO` | **只换卡片照片**（不移窗口、不重播）：`updatePhoto()` 后台解码 → `setCardPhoto` + 接收态时同时换折射背景 | `OverlayService.kt:964-967,267-293` |
| `statusIntent(ctx, text)` | `ACTION_STATUS` | 只改底部状态行 | `OverlayService.kt:971-974,296-299` |
| `peerIntent(ctx, peer, count)` | `ACTION_PEER` | 只补正对端/张数（不重播动画）——**代码里没有任何调用方** | `OverlayService.kt:983-987,305-309` |
| `hideIntent(ctx)` | `ACTION_HIDE` | 只摘窗口（视图树留着给"重播"复用） | `OverlayService.kt:991-992,474-477` |
| `stopIntent(ctx)` | `ACTION_STOP` | `teardown()` + 停前台 + `stopSelf()` | `OverlayService.kt:996-997,693-697` |
| `isRunning()` / `isAnimating()` | — | 两个 `@Volatile` 静态标记，给启动页读 | `OverlayService.kt:913-923` |

**三者的差别对照**（题目要求明确区分）：

| | 触发 | 谁调 | 是否建窗 | 是否重播动画 |
|---|---|---|---|---|
| `intent(...)` | `ACTION_START` | TapService（发送侧）/ MainActivity（联调页） | 是（`startForegroundService`） | 是 |
| `receiveIntent(...)` | `ACTION_RECEIVE` | TapService（接收侧）/ MainActivity | 是（`startForegroundService`） | 是 |
| `photoIntent(...)` | `ACTION_PHOTO` | TapService 在预览/整图到达时 | **不建窗**（`rootView == null` 就只记 Uri） | 否 |

无 action 启动只进前台待命（`OverlayService.kt:149`）。所有 `ACTION_START/RECEIVE` 里都先查 `Settings.canDrawOverlays`，没权限只 Toast（`OverlayService.kt:203-207,240-244`）。

### 5.2 用户实际看到什么

**发送页**（`OverlayView.Mode.SEND`）
- 涟漪 + 四边白线 + 边缘辉光 + 顶部光斑（GL/Canvas 动效，见 §8）；
- 面板：顶部标题、白色照片卡片（照片 = 要发送的那张，标题/副标题 = 真文件名与大小，`OverlayService.kt:329` → `ImageLoader.describe`）、下方"上滑发送"提示、底部状态行；
- **文案是内置占位**：`OverlayView.sendTextsFromPeer = false`（`OverlayView.kt:972`）→ 标题恒为 `发送给 fazd9`、提示恒为 `上滑发送 1 张照片`（`res/values/strings.xml:12,15`）。NFC 传来的 peer/count 在发送页被**丢掉**（`OverlayView.kt:985-1000`）；
- 底部状态行才是真实进度：`正在连接对方…` → `已连接,上滑发送即可`（`TapService.java:216,229`）。

**接收页**（`OverlayView.Mode.RECEIVE`）
- 同一套页面，"上滑发送"那一行换成**两个按钮**：主按钮浅色底「接收 N 张照片」、次按钮半透明「拒绝」（`OverlayView.kt:2422-2451`，常量 `RECEIVE_PRIMARY_H_PX=48 / RECEIVE_SECONDARY_H_PX=40 / RECEIVE_BTN_GAP_PX=10`，`OverlayView.kt:685-691`）；
- 标题「来自 用户XXXX的分享」（`strings.xml:22`），卡片照片由 `photoIntent` 换上对方预览/整图，没有时先播"深色底 + 涟漪"（`OverlayService.kt:341-374`）；
- 按钮文案的张数**恒为 1**：`TapService.showReceivePage` 写死 `receiveIntent(..., 1, ...)`（`TapService.java:359`），真实总张数只更新状态行（`TapService.java:254-261`）。

### 5.3 手势 → 回执链路

**上滑发送（SEND 态）** — 全部在 `OverlayView.onTouchEvent`（`OverlayView.kt:2482-2517`）：
1. `ACTION_DOWN` 必须命中卡片（`hitCard`，卡片矩形外扩 `12*k`）才吃事件（`OverlayView.kt:2490-2499,2572-2576`）；
2. `ACTION_MOVE`：`dragY = min(0, y - downY)`（只允许向上），卡片跟手、面板文字按 0.35 倍跟手淡出（`OverlayView.kt:913-914`）；
3. `ACTION_UP`：`dragY < -SEND_THRESHOLD_DP * k`（**85dp**，`OverlayView.kt:567`）→ `startFlyAway()`，否则 `returnCard()`（260ms 回弹）；
4. `startFlyAway()` 里**先回执、后播飞走动画**，`sendConfirmedNotified` 保证一次手势只回一次（`OverlayView.kt:2648-2669`）；
5. 回执 → `OverlayService.buildOverlay()` 里挂的 lambda：`overlay.onSendConfirmed = { notifyNfc(ACTION_UI_SEND_CONFIRMED) }`（`OverlayService.kt:520`）；
6. `notifyNfc()` = `startService(Intent(this, TapService::class.java).setAction(action))`（`OverlayService.kt:488-491`）；
7. `TapService.onStartCommand` 接住（`TapService.java:103-113`）：未就绪 → 状态行"正在连接对方,请稍候…(就绪后再上滑)"；就绪 → `sXfer.sendImages(new ArrayList<>(PENDING))`。

**接收/拒绝（RECEIVE 态）**：`onReceiveTouch`（`OverlayView.kt:2528-2570`）——按下记 `pressedButton`，抬起时**仍在同一矩形内**才回调；回调 → `notifyNfc(ACTION_UI_RECEIVE_ACCEPT / REJECT)`（`OverlayService.kt:522-523`）→ `TapService.java:114-141`。

**其他回执路径**：`overlay.onSendFinished = { timeline.reset() }`（`OverlayService.kt:518`）。

### 5.4 窗口与生命周期要点

- 整屏窗口：`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE|FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS|FLAG_HARDWARE_ACCELERATED`，尺寸是**屏幕像素**不是 MATCH_PARENT，API 30+ 显式 `fitInsetsTypes=0/fitInsetsSides=0`，`screenOrientation = PORTRAIT`（`OverlayService.kt:603-641`）；
- `ACTION_HIDE` 只 `detach`（`removeView`），`rootView` 留着；因此**新一轮必须先 `wipeVisuals()`**，否则会闪一下上一轮的画面（`OverlayService.kt:384-395,211-212,247-248`）；
- 挂窗后 420ms 自动 `replayDemo()` 播一遍入场（`OverlayService.kt:583-588`）；
- 通知：channel `touch_anime_overlay`，ID `0x4F50`，三个 action「重播动画 / 关闭悬浮窗 / 触摸穿透」（`OverlayService.kt:801-845`）；重播延迟 `REPLAY_DELAY_MS = 5000`（`OverlayService.kt:1017`）。Android 13+ 未申请 `POST_NOTIFICATIONS`（`HomeActivity.kt:36-39` 有意为之）→ 通知不出现。

---

## 6. 状态机 / 时序图

### 6.1 状态变量

| 变量 | 归属 | 含义 |
|---|---|---|
| `PENDING` | `TapService.java:49` | 本机是否发送方（静态、跨 Activity） |
| `mLinkReady` | `TapService.java:36` | 本轮链路是否就绪（`onP2pReady` 置 true，`onPeer` 清 false） |
| `mSendShown` / `mReceiveShown` | `TapService.java:45-46` | 本轮动效页是否已拉起（幂等） |
| `mAccepted` / `mTransferDone` / `mFullCount` / `mTotalCount` | `TapService.java:39-43` | 接收侧进度与用户意向 |
| `Bus.sRoleLocked` | `Bus.java:269-286` | 角色锁（30s 过期） |
| `mGotConnection` / `mGoAddress` / `mPendingSocket` / `mSenderWaiting` | `Xfer.java:99-108` | 链路状态；`setExpectedPeer` 时按 `hasLiveLink()` 决定是否 `resetRoundState()`（`Xfer.java:154-213`） |
| `attached` / `mode` / `statusText` | `OverlayService.kt:86-93,129` | 窗口是否挂着、发送/接收身份、状态行 |

### 6.2 成功路径（A=发送方，B=接收方；A 碰 B 时 A 为卡方）

```
 A(有 PENDING=照片)                NFC 空中接口                 B(无 PENDING)
──────────────────────────────────────────────────────────────────────────────────
 用户: HomeActivity.pushToNfc()
   → startService(TapService, ACTION_SEND_MULTIPLE)      (HomeActivity.kt:133-136)
   → PENDING=[photo]; savePending()                      (TapService.java:93-101)

 ─────────────── 两台靠近 ───────────────
 HceService.processCommandApdu
   ← 00 A4 04 00 07 F05052494E4643 00
   → 90 00                                               (HceService.java:41-46)
   ← 80 10 00 00 <Lc> B的payload 00
   → A的payload 90 00        ─────────────►  TapDispatchActivity (TECH_DISCOVERED)
   → 后台线程 startService(TapService, PEER)  (HceService:71-87)   → TapService HANDLE TAG
                                                          handleTag(): SELECT→EXCHANGE
                                                          (TapService.java:331-342)
                     Bus.onPeerData → 主线程 → onPeer (TapService.java:170-194)
   ◄──────────────────────────────────────────────────────────────────────────────
   两边都: setExpectedPeer(...) + startAsReceiver()(起 8988 监听)
   两边都: Bus.lockRole() 只成功一次
   PENDING 非空 → 发送方             PENDING 空 → 接收方
   showSendPage()                    startHotspot() (Xfer.java:216-289)
     startForegroundService(intent(backdrop=photo,photo,peer,count))
     + statusIntent("正在连接对方…")   showReceivePage(label)
   startAsSender() → ①热点 ②同网 ③P2P  startForegroundService(receiveIntent(label,1,null))
                                       + statusIntent("正在连接对方…")
 ─────────────── 链路建立 ───────────────
 连接成功: mPendingSocket = s
   onP2pReady → mLinkReady=true
   statusIntent("已连接,上滑发送即可")   statusIntent("已连接,等待接收…")
   sendPreviews(≤3 张, 900px q82) ─────►  handleIncoming: TYPE_PREVIEW
                                          → onPreview → previewUriOrNull()
                                            → cacheDir/peer_preview.jpg
                                            → photoIntent → 卡片换成对方照片
     ◄──────────────── 用户上滑(≥85dp) ────────────────────────────────────────────
   OverlayView.startFlyAway → onSendConfirmed
     → ACTION_UI_SEND_CONFIRMED → TapService
   sendImages(PENDING)
     帧: META(张数) + FULL×N ───────────►  TYPE_META → onImageCount → 状态行"已收到 0/N"
                                          TYPE_FULL → mIo: decode+saveToGallery
                                            → onImageReceived → photoIntent(整图)
                                            → 状态行"已收到 i/N 张,已存入相册"
     s.close(); onImageSent(bytes)        EOF → onTransferDone(list)
   PENDING.clear(); mSendShown=false      状态行"已收到 N 张,请确认接收"
   hideIntent() ──────────────────────►  用户点「接收」→ ACTION_UI_RECEIVE_ACCEPT
                                          mAccepted=true
                                          if (mTransferDone) → hideIntent()
                                            → showViewer(mBmps) (旧 Overlay)
                                            → Bus.unlockRole()
 ──────────────────────────────────────────────────────────────────────────────────
```

### 6.3 变体与失败路径

```
① 接收方先点「接收」、对方还没发完
   ACTION_UI_RECEIVE_ACCEPT → mAccepted=true; mTransferDone=false
     → 状态行"等待对方发送…"/"正在接收,收完自动打开…"      (TapService.java:114-126)
   之后 onTransferDone 到达时: mReceiveShown 且 mAccepted → 摘窗 + showViewer (TapService.java:286-293)

② 点「拒绝」
   ACTION_UI_RECEIVE_REJECT → unlockRole + hideIntent + 清本轮接收状态  (TapService.java:127-141)
   ⚠ 不影响已在进行的接收：socket 循环照读 (Xfer.java:724)、saveToGallery 照存；
     若之后还有 TYPE_FULL 到达且 mReceiveShown==false → showReceivePage 会把接收页**重新弹出来**(TapService.java:267-270)

③ 用户上滑但链路没就绪
   TapService.java:106-111 → 状态行"正在连接对方,请稍候…(就绪后再上滑)"，不发图；
   卡片回弹由动效层自己做(旧实现调 mSendPage.resetStack())

④ 连接失败 onXferFailed(why)
   → 若发送页还没弹则补弹；statusIntent(why)；unlockRole()      (TapService.java:313-319)
   ⚠ **不摘窗口**：发送页没有取消/关闭入口（13+ 又没有通知），只能强杀应用

⑤ 预览推不出去 / 预览落盘失败
   sendPreviews 等 10s 仍无连接 → 日志"对方确认页将显示占位图"    (Xfer.java:896-905)
   previewUriOrNull 失败 → 日志"预览图落盘失败,动效层卡片继续用内置插画" (TapService.java:382-385)

⑥ 卡方转交失败（后台启动服务被限）
   HceService.postPeerToService catch → 日志"HCE: 转交服务失败"    (HceService.java:80-82)
   → 卡方自己永远不会进 onPeer（不动效、不建链）

⑦ 服务被系统回收后碰触
   TapService 冷启 → loadPending() 恢复 PENDING (TapService.java:75,155-163)
   → 冷启那台若恢复了照片则仍是发送方

⑧ 第二轮碰触
   动效层 rootView 复用 → wipeVisuals() 擦回干净一屏 (OverlayService.kt:384-395)
   TapService: mLinkReady/mSendShown/mReceiveShown 在 onPeer 清零 (TapService.java:171-173)
   Xfer: setExpectedPeer → hasLiveLink? 决定是否 resetRoundState (Xfer.java:154-213)
```

---

## 7. 渲染层速览

### 7.1 分层

`res/layout/overlay_root.xml` 只有两层，根布局无背景（窗口 TRANSLUCENT）：
`RippleSurfaceView`（GL 水波，`overlay_root.xml:15-18`）+ `OverlayView`（Canvas 全动效，`:20-23`）。

### 7.2 `RippleRenderer`（GLES 2.0，`GLSurfaceView` `RENDERMODE_WHEN_DIRTY`）

- 折射源 = 一张真实位图（`u_image`，`upload()` 上传；未上传时绑 1×1 全透明占位），另有 `BackdropBlur` 生成的 1/4 小图作模糊版（`u_blur_tex` + `u_blur` 交叉淡入）（`RippleRenderer.kt:41-111,398-505`）；
- 画面构成（片元着色器参数）：冷暖光带（宽 0.06→0.24 屏宽，指数 0.9；色带粉紫→近白→青蓝，`RippleRenderer.kt:298-302,360-368`）、形变环 `±0.26` 屏宽 + 长波长行波 `sin((dist-radius)*10.4)`（位移 0.075 屏宽，远处只衰减到 0.12，`RippleRenderer.kt:313-334`）、波峰冷白微光（`RippleRenderer.kt:378`）、**扫过即压暗**（半径 `u_progress*(aspect+0.17)`，最深 0.45，`RippleRenderer.kt:471-479`）；
- 光带只推到屏高 `0.75`，压暗则走到底（`RippleRenderer.kt:278,471-479`）。

### 7.3 `Timeline`（动效时钟，`anim/Timeline.kt:17-211`）

- 两段：入场 0→`INTRO_MS(2300)`，之后**不停**（常驻呼吸），常驻段限帧 `AMBIENT_FRAME_MS = 33`（约 30fps）（`Timeline.kt:131-149`）；
- 关键刻度：`WAVE_MS=1000`、`EDGE_START_MS=WAVE_MS/2=500`、`EDGE_REVEAL_MS=600`（白线 500→1100ms 铺满）、`PANEL_MS=1500`、`PANEL_FADE_MS=550`、`PHOTO_BLUR_MS=500`（背景模糊 1500→2000ms）、`RIPPLE_EASE=1.18`（`Timeline.kt:31-103,156-173`）；
- 每帧回调 `onFrame(elapsed, ripple)` → `OverlayService` 同时喂 `rippleView.setRipple/setBlur` 与 `overlay.applyTimeline`（`OverlayService.kt:509-516`）。

### 7.4 `OverlayView`（Canvas 自绘，2711 行）

| 图层 | 画法要点 | 关键常量 |
|---|---|---|
| 四边白线 | 圆角矩形中线（内缩 `LINE_INSET_PX=2.4` 设计 px）按**逐点半宽**拼成的闭合填充路径（描边做不到变宽）；亮度恒定 `LINE_ALPHA=0.96`，**呼吸改的是线宽**：`2.6 + 1.1×(1+果冻)`，果冻是欠阻尼弹簧 `breath()`（周期 4800ms、抖 3 下、衰减 2.5）（`OverlayView.kt:95-183,1617-1625,1966-1987`） |
| 边缘辉光 | 一层，脊线压在白线中线上：外侧到屏幕边缘、内侧按 `GLOW_STOPS/GLOW_COLORS` 衰减到冰蓝透明；深度 12 设计 px、峰值 alpha 0.53、边缘 0.74（`OverlayView.kt:239-300,1638-1692`） |
| 波前显形 | 白线**自己一条时间轴**（`edgeReveal=(elapsed-500)/600`），不是跟水波；显形用径向渐变遮罩 `1-smoothstep`（`OverlayView.kt:1083-1090,2015-2049`） |
| 顶部光斑 | 顶边中点、圆心在 y=0 的宽扁椭圆（横向 2/3 屏宽、纵向 64 设计 px），独立相位呼吸；**换色**：1500ms 起 300ms 交叉淡入照片主色（`OverlayView.kt:446-551,1246-1337`） |
| 面板/卡片 | scrim 三段竖渐变（约 32/35/43% 压暗）→ 接收方信息 → 卡片（292×360 设计 px、圆角 23、5 层近似投影）→ 上滑提示/接收按钮；面板 1500ms 起浮现、卡片再延后 100ms 入场（`OverlayView.kt:2131-2340`） |
| 接收按钮 | `layoutReceiveActions()` 一份矩形供绘制与命中**共用**（`OverlayView.kt:2391-2410`） |

设计基准宽 `DESIGN_W=380`，所有尺寸乘 `k = viewW/380`（`OverlayView.kt:58,1122`）。

### 7.5 颜色/图像派生

| 模块 | 作用 | 要点 |
|---|---|---|
| `PhotoTint.dominant` | 从照片取主色（顶部光斑换色用） | 32×32 采样、4bit/通道直方图（16³ 格）、打分 `count × (0.25 + 饱和度)`（折中"最大块"与"最高饱和"），返回格内均值色（`PhotoTint.kt:43-98`） |
| `PhotoTint.lighten` | 主色变浅 | HSV 抬 V、降 S，V 兜底 `TOP_BLOOM_TINT_MIN_VALUE=0.68`（`PhotoTint.kt:111-119`、`OverlayView.kt:1307-1317`） |
| `BackdropBlur.of` | 背景模糊版 | 逐级对半降到 1/4 → 2 遍可分离盒模糊（小图半径 6 ≈ 原图 24px），GL 用 `GL_LINEAR` 放大回来（`BackdropBlur.kt:23-84`） |
| `ImageLoader` | 解码/裁切/文案 | API 28+ 走 `ImageDecoder`（含 EXIF 摆正）+`ALLOCATOR_SOFTWARE`（GL 上传要软件位图）；27 回落 `BitmapFactory`；`decodeCover` 按屏幕比例居中裁切并垫深色底（防透明 PNG 透底）；`describe` 出"标题(文件名≤18字) / 副标题(类型·1 张照片·X MB)"（`ImageLoader.kt:34-176`） |

---

## 8. 已知缺口 / 粗糙边缘（全部来自代码与注释）

### 8.1 代码/注释自己承认的

| # | 问题 | 位置 |
|---|---|---|
| 1 | 失败时**不摘窗口**、发送页没有取消入口；13+ 拿不到通知 → 只能强杀应用 | `TapService.java:313-319`；`HomeActivity.kt:36-39,163-165`；`strings.xml:42` |
| 2 | 接收页按钮张数**写死 1**；`peerIntent` 已备好但没人调 | `TapService.java:359,254-261`；`OverlayService.kt:983` |
| 3 | 发送成功后 `PENDING.clear()` → 发送方发完一次就变回接收方 | `TapService.java:305-306`（README 建议的补丁三未打） |
| 4 | 发送页文案与动效层内置占位一致（`发送给 fazd9`），非真实对端 | `OverlayView.kt:972`；`strings.xml:12,15` |
| 5 | HCE 事件里 `startService` 可能被 Android 8+ 后台限制拦住，靠 `catch (Throwable)` 吞掉 | `HceService.java:76-82`；`TapDispatchActivity.java:14-19` |
| 6 | API <29 收图**不落相册**（`saveToGallery` 直接 return false），demo 不处理 | `Xfer.java:1012` |
| 7 | HCE 转交/接管自身不鉴权：任何能发 `SELECT F05052494E4643` 的读卡器都能拿到 payload（设备名/蓝牙名/IP/热点密码）并触发本机发送流程 | `HceService.java:41-59`、`AndroidManifest.xml:115`（`TapService exported=true` 无权限） |
| 8 | `Xfer.destroy()` 没有调用方（刻意，注释解释原因），`mRunning` 置 false 的路径只有它 —— 常驻进程里"重启引擎"没有接口 | `Xfer.java:952-961`；`MainActivity.java:144-156` |

### 8.2 我读出来的（代码没写但确实存在）

| # | 问题 | 证据 |
|---|---|---|
| 9 | **`MainActivity.mXferListener` 是死代码**：声明了 `Xfer.Listener`（`MainActivity.java:482-537`）但从未 `addListener` → `mP2pReady` 永远 false → 联调页「② 发送给对方」按钮**永远灰着**（`updateSendEnabled()` 要求 `mP2pReady`，`MainActivity.java:332-334`） |
| 10 | **监听器重复注册**：`TapService` 每次 `onStartCommand` 都 `x.addListener(this)`（`TapService.java:73`），而 `Xfer.mAll` 是 `CopyOnWriteArrayList` 且 `addListener` 不去重（`Xfer.java:59-60,91-93`）→ 一碰之后每轮至少 2 个回调（PEER 1 次 + 上滑回执 1 次），多碰几轮不断累加；后果是 `onImageReceived/onPreview/onTransferDone` 被重复投递（状态行重复刷、`showViewer` 可能被重复调用并叠出第二个查看器窗口） |
| 11 | **「拒绝」不打断已在传输**：socket 读循环与 `saveToGallery` 不看 `mAccepted`（`Xfer.java:724-771`）；且拒绝后若还有整图到达，`onImageReceived` 会因 `!mReceiveShown` **把接收页重新弹出来**（`TapService.java:267-270`） |
| 12 | `MainActivity.handleNfcIntent()`（`MainActivity.java:387-407`，"系统派发自动进入接收方"）**实际不可达**：`MainActivity` 没有声明任何 NFC intent-filter（`AndroidManifest.xml:73-91` 只有 SEND/SEND_MULTIPLE），TECH_DISCOVERED 只会派给 `TapDispatchActivity` |
| 13 | payload **静默截断**：`buildExchangeApdu` 超过 200 字节直接砍掉尾部（`Bus.java:98-102`），最坏情况丢掉 `hs/hp`（热点信息）→ 热点通道失效且无任何日志 |
| 14 | 动效层 `peerLabel/peerCount` **跨轮粘滞**：只在 intent 带 extra 时才覆盖（`OverlayService.kt:191-193,234-237`），两参版 `intent()` 之后仍保留上一轮的值 |
| 15 | `Xfer.java.bak` 与 `Xfer.java` 只差 `thumbnail()` 两个参数（900/82 vs 360/60）——**同一提交里入仓的旧版快照**，不参与编译（非 `.java` 后缀），当前 `Xfer.java` 生效 | `diff Xfer.java.bak Xfer.java`；两者均 1033 行 |
| 16 | `Xfer.java` 有 21 处 `catch (... ignored)` / 5 处仅 log 的兜底（`grep -c`），网络失败信息只落 `Bus.log`（内存 200 条环形日志，`Bus.java:253-267`），UI 上只有状态行一行字 |
| 17 | 查看器仍走**旧** `nfc/Overlay`（`TapService.java:406`），与 `OverlayService` 是两套悬浮窗实现、两套触摸/生命周期语义 |

---

## 9. 代码与 README / MERGE_PLAN 的冲突（显式列出）

| # | 文档说法 | 代码事实 | 证据 |
|---|---|---|---|
| 1 | README:57 "`Xfer` 979 行、`Bus` 239 行**逐字保留**" | `Xfer.java` = **1033 行**、`Bus.java` = **306 行**；且 `Xfer.java` 与同目录 `.bak` 内容不同（缩略图参数被改过）→ 不是"逐字保留" | `wc -l`；`diff Xfer.java.bak Xfer.java` |
| 2 | README:33（流程图）"`OverlayService.intent(ctx, 照片, 照片, 对端, 张数)` ← 动效层播发送确认页"，暗示发送页显示对端 | 发送页**不使用**对端/张数：`sendTextsFromPeer = false` → 恒显示内置 `发送给 fazd9` / `上滑发送 1 张照片`。README 正文（第 102-106 行、149-152 行）确实说明了这点，但顶部流程图未修正，两处自相矛盾 | `OverlayView.kt:972,985-1000`;`TapService.java:214-215` |
| 3 | README:46 "两边只有三样东西：`OverlayService` 的**四个**工厂方法" | 实际对外 `@JvmStatic` 工厂有 **7 个**（含 `peerIntent`/`hideIntent`/`stopIntent`），其中 `hideIntent` 是主流程退出窗口的唯一手段，`peerIntent` 无人调用 | `OverlayService.kt:930-997` |
| 4 | README:142 "`TapService.java:190` 的注释写明失败时还会补弹发送页" | 该逻辑现在在 `TapService.java:314-316`（行号已漂移） | `TapService.java:314-319` |
| 5 | README:207-211 "补丁三：`onImageSent` 里 L303–304" | 实际 `PENDING.clear(); savePending();` 在 `TapService.java:305-306`（行号漂移）；补丁确实未打 | `TapService.java:302-311` |
| 6 | README:161 补丁一 "`MainActivity.java` L544 `try {` 之后" | 实际 `startForegroundService(OverlayService.intent(this, u, u));` 在 `MainActivity.java:558` | `MainActivity.java:551-565` |
| 7 | MERGE_PLAN:159 权限清单 / README 全篇 | 都没提 Manifest 的 **`android:sharedUserId="android.uid.system"`**（`AndroidManifest.xml:17`）：这决定安装必须与 ROM 平台签名一致，同时是"HCE / 后台启动 / 悬浮窗"这些能力实际可用的重要前提。README §三 的"直接装 `app-debug.apk`"清单对这一点只字未提 | `AndroidManifest.xml:16-18`；`app/build.gradle.kts:26-34` |
| 8 | MERGE_PLAN:169 ".nfc.TapService（exported=true，demo 用）" 与 README §四风险 3 并列 | 一致，但风险被低估：`TapService` 完全 exported 且**无权限**，`action=com.pri.nfcdemo.DEBUG` 可被任意应用调用直接伪造碰触（`adb` 与第三方 app 同一条路径） | `TapService.java:87-92`；`AndroidManifest.xml:114-116` |
| 9 | MERGE_PLAN:76-86 接口契约里 `isRunning()/isAnimating()` | 一致（用 `@JvmStatic` 方法暴露），但 MERGE_PLAN 只列 API 未提 `running/animating` 是两个静态标记、`isRunning()` 在**任何一次** `ensureForeground()` 后即为 true（包括"只进前台待命"） | `OverlayService.kt:790,913-923` |
| 10 | README:96-98 "用的是 AGP 9.3.1 自带的 Kotlin 编译（两个源工程都没有 kotlin 插件）" | 与代码一致（`libs.versions.toml:2` agp=9.3.1，`app/build.gradle.kts:1-3` 只 apply android application） | 一致，无需修改 |
| 11 | README:76-78 "模板自带的 `MainActivity.kt` / `activity_main.xml` 已留成注释占位文件，可以随手删" | `MainActivity.kt` **已经不存在**（目录里没有该文件）；`res/layout/activity_main.xml` 仍在（空壳 FrameLayout，无人 inflate） | `ls app/src/main/java/com/pri/nfctouchdemo/`；`activity_main.xml:9-11` |
| 12 | README:19（动效层可单独驱动）"通知里的「重播动画」用动效层记住的上一次那两张图重播（前提是系统给了通知权限）" | 一致；但要补一句：出厂实测路径里 **13+ 默认没有通知权限**，所以"重播"入口在目标机型上通常不可见 | `OverlayService.kt:445-465`, `HomeActivity.kt:36-39` |

---

## 10. 我无法从代码确定的事

1. **实机行为**：所有通道（热点 / 同网 / P2P）的真实成功率、`192.168.43.x` 与 `192.168.49.x` 的过滤是否覆盖目标机型、系统的"允许连接热点"弹窗是否会出现——代码里只有注释里的实测记录（如"~50Mbps"`Xfer.java:401`），无法离线验证。
2. **安装可行性**：`sharedUserId="android.uid.system"` 与 `keystore/debug.keystore`（`app/build.gradle.kts:31`）是否能在这台机器上装成功，取决于 ROM 对 sharedUserId 校验的宽松程度与 keystore 的实际身份——工程里没有说明这两者如何对上。
3. **`getRoundedCorner` / `maximumWindowMetrics`** 在目标机型上的取值（`OverlayView.kt:1189-1194`、`OverlayService.kt:712-724` 都有兜底，但实际走哪条分支只能真机看）。
4. **`ImageDecoder` 对 `file://`（`cacheDir/peer_preview.jpg`）** 在个别 ROM 上是否可用——README §四风险 2 存疑，代码只有"失败退回插画"的兜底（`TapService.java:382-385`、`ImageLoader.kt:66-69`）。
5. **`busybox`/日志之外的证据**：`Bus.log` 只写 logcat（tag `NfcTapDemo`）与内存环形缓冲（200 条），进程重启即丢；没有任何落盘日志，所以现场问题只能靠 `adb logcat` 复现。
6. **两端同时上滑**（双方都有 PENDING）时的抖动行为——代码无仲裁，实际表现取决于 TCP 竞态，无法静态推断。
