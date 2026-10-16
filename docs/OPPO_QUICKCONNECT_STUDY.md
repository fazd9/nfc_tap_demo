# OPPO 碰一碰互传取图机制逆向分析（AccessoryFramework / Quick Connect）

> 分析对象：`Temp/AccessoryFramework.apk`（只读，未修改、未入仓）
> 工作目录：`_Temp/afk/`（反编译产物，未入仓）
> 分析日期：2026-10-16
> 方法：`aapt2 dump badging/xmltree/resources` + `baksmali 3.0.9` 反汇编全部 5 个 dex + 字符串/资源交叉检索
>
> **证据标注约定**：每条结论后标注来源强度
> `[smali]` = 从 smali 直接读到 ｜ `[资源/清单]` = 从资源表或 AndroidManifest 读到 ｜ `[推断]` = 由多条证据推理 ｜ `[未确定]` = 无法确认

---

## 一、结论先行

### 1.1 它到底怎么拿图的

**一句话：它根本不"取"图，是相册主动"交"给它的。**

OPPO 的相册（`com.coloros.gallery3d`）集成了 OPPO 私有的 **Share Agent SDK**。相册启动后向 `com.heytap.accessory` 登记为"可分享源"，并挂起一条私有 IPC 长连接；碰一碰发生时框架沿这条连接回调通知相册，相册随即把自己当前的可分享内容（`SharedData`，内含 `SharedRecord.uri` 列表）交给框架。所以：

- **不需要 ShareSheet / Picker**：数据不是"选"出来的，是相册本来就持有的当前状态。
- **屏幕外的照片也在内**：相册给的是**选中集合（selection model）**，与是否滚出屏幕无关。
- **框架自己不读相册的 UI、不扫 MediaStore**（见 §3.1、§3.2 的否定性证据）。

> 注意"由相册主动上交"这一点是 `[smali]` 直接读到的（`startShare` RPC）；但**相册是收到回调后才交、还是本来就有缓存**，仅凭框架侧无法区分，见 §六 #4。

这是一条**合作型（cooperative）架构**，不是"读别人界面"的架构。OPPO 之所以做得干净，是因为**相册是它自己的**。

### 1.2 我们能不能照做

| 目标 | 能否照做 | 说明 |
|---|---|---|
| 抄 OPPO 的**架构**（自己 App 当 share agent，直接交 Uri） | **可以** | 我们自己的相册/看图器就是数据源，直接把 Uri 交给自己即可，无需任何 OPPO 私有件。这正是 OPPO 相册对自己做的事。 |
| 调 OPPO 的 ShareServer RPC 冒充 share agent | **不可以** | ① 我们的设备上没有 `com.heytap.accessory`；② 即便有，调用方要过 **OCS 鉴权**（uid + capability 白名单，见 §5.2），第三方包必被拒。 |
| 让我们的 App 去读**第三方相册**（如 Google 相册）的当前选中项 | **OPPO 也没做到** | OPPO 的白名单里**只有自家相册**（`com.coloros.gallery3d` / `com.oneplus.gallery`，见 §3.6）。对第三方相册，OPPO 的兜底是"投屏/接力"而不是"取正确文件"（见 §3.8）。 |

**对我们的实际含义**：OPPO 的答案**不能**直接解决我们"从 Google 相册取图"的问题——它靠的是相册配合。但反过来看，这条路**确实存在且干净**：只要数据源是我们可控的（例如工程里 `Temp/debuggallery` 那套调试相册，或未来我们自己展示照片的界面），照抄这个"agent 主动上报 + 框架长连接回调"的模型即可，**完全不需要** ShareSheet、不需要无障碍、不需要屏幕像素比对，且天然支持屏幕外项目。

---

## 二、应用概览

### 2.1 基本信息 `[资源/清单]`

| 项 | 值 |
|---|---|
| 包名 | `com.heytap.accessory` |
| 标签 | Quick Connect / 设备快连 |
| versionName / versionCode | `16.35.0` / `160350000` |
| compileSdk / targetSdk / minSdk | 36 / 36 / 33 |
| Application 类 | `com.oplus.pantaconnect.PantaConnectApplication` |
| 大小 / 架构 | 56.8 MB，`native-code: arm64-v8a` |
| dex | 5 个（classes ~ classes5） |
| 组件数 | activity 15、service 35、receiver 25、provider 28 |
| `launchable-activity` | **无**（没有启动图标，纯后台框架/服务型应用） |
| `sharedUserId` | **无** |
| `<queries>` | 14 个包，全是 OPPO 生态（`com.oplus.metis`/`com.heytap.mcs`/`com.oplus.pantanal.ums`/…），**不含相册** |

### 2.2 权限（关键项）`[资源/清单]`

**否定性证据（最重要）**：

- 没有 `READ_MEDIA_IMAGES`
- 没有 `READ_MEDIA_VIDEO`
- 没有 `READ_EXTERNAL_STORAGE`
- 没有 `WRITE_EXTERNAL_STORAGE`
- 没有 `MANAGE_MEDIA` / `ACCESS_MEDIA_LOCATION`

> 一个**没有媒体读取权限**的进程，在 Android 13+ 上不可能枚举用户照片。这条单独就足以否定"框架自己去读相册"的假设。

**它确实有的（说明它是特权系统应用）**`[资源/清单]`：

```
android.permission.READ_FRAME_BUFFER          ← 截屏
android.permission.WRITE_SECURE_SETTINGS
android.permission.MANAGE_USB
android.permission.BLUETOOTH_PRIVILEGED
android.permission.INTERACT_ACROSS_USERS
android.permission.READ_CLIPBOARD_IN_BACKGROUND
android.permission.OVERRIDE_WIFI_CONFIG   (protectionLevel=signature)
android.permission.TETHER_PRIVILEGED      (signature)
android.permission.GET_TASKS
android.permission.QUERY_ALL_PACKAGES
com.oplus.permission.safe.{BLUETOOTH,PHONE,WINDOW,APP_MANAGER,SETTINGS,SECURITY,CONNECTIVITY,...}
oppo.permission.OPPO_COMPONENT_SAFE
oplus.permission.OPLUS_COMPONENT_SAFE
```

`[推断]` 这些都是 signature/privileged 级权限 → 该 APK **必须以特权系统应用形式预装**（`/system/priv-app`），不可能作为普通三方应用安装并正常工作。

`uses-library-not-required`：`nfcvendorlib`、`vendor-oplus-hardware-cryptoeng-V1.0`、`androidx.window.extensions`、`androidx.window.sidecar`。

### 2.3 自定义权限与对外接口 `[资源/清单]`

它**自己定义**了下列权限（供调用方申请）：

| 权限 | protectionLevel |
|---|---|
| `com.oplus.interconnect.permission.SERVICE` | **normal** |
| `com.heytap.accessory.permission.ACCESSORY_FRAMEWORK` | normal |
| `com.oplus.pantaconnect.permission.CONNECTIVITY` | normal |
| `com.oplus.pantaconnect.breeno.permission.MULTI_WAKEUP` | normal |
| `com.heytap.accessory.permission.IGNORE_DEVICE` / `.NOTIFICATION` / `.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | signature |

对外暴露的关键组件：

| 组件 | 作用 | 保护 |
|---|---|---|
| `com.oplus.interconnect.service.InterconnectService` | action `com.oplus.interconnect.action.SERVICE`，**通用 RPC 入口** | `com.oplus.interconnect.permission.SERVICE` |
| `com.oplus.interconnect.service.TapShareAgentService` | action `com.oplus.interconnect.service.TAP_SHARE_DISCOVERY` | 同上 |
| `com.oplus.interconnect.service.HealthConnectService` | action `com.heytap.accessory.rpc.action.RPC_SERVICE` | — |
| `…nfc.ui.NfcUIService` / `NfcDialogService` | 碰一碰动效与弹窗 | 不导出 |
| `…discovery.adapter.nfc.OplusHostApduService` | HCE 卡模拟（发卡侧） | `BIND_NFC_SERVICE` |
| `…discovery.adapter.nfc.IosHostApduService` | HCE（对 iOS） | `BIND_NFC_SERVICE` |
| `…discovery.adapter.nfc.NfcDiscoveryService` | NFC reader（收卡侧），action `com.oplus.nfc.action.DIRECT_DISCOVERED` | `com.oplus.permission.safe.CONNECTIVITY` |
| `…nfc.NfcContentProvider`（authority `com.heytap.accessory.nfc.NfcContentProvider`） | NFC 状态 | `com.oplus.permission.safe.CONNECTIVITY` |
| `…fusionprovider.provider.FusionContentProvider` | 设备融合 | `com.oplus.pantaconnect.permission.CONNECTIVITY` |
| `…tapshare.BeaconContentProvider` | 信标 | `com.oplus.permission.safe.CONNECTIVITY` |

### 2.4 它依赖谁（同机必须共存）`[smali]` + `[推断]`

| 包 / 组件 | 用途 | 证据 |
|---|---|---|
| `com.coloros.oshare`（`com.oplus.oshare.FILE_TRANSFER_SERVICE`） | **真正的文件传输** | `com/coloros/oshare/FileTransferManager.smali`、`ITransferService`、`TransferCallback` |
| `com.oplus.pantaconnect`（`com.oplus.pantaconnect.tapshare.TapShareAgentService`） | 碰一碰代理服务 | `smali_classes/zh/j.smali:131` |
| `com.vendor.nfc.VendorNfcAdapter` | 厂商 NFC HAL（feature `TAP_TO_SHARE_WITH_IOS`） | `smali_classes/t7/a.smali:69-107` |
| `com.oplus.metis` | 端侧 AI / 页面理解 | 权限 `com.oplus.metis.factdata.permission.DATABASE`，`content://com.oplus.metis.smart_connect_provider/app_relay` |
| `com.oplus.ocs.*` | **调用方鉴权（组件安全）** | `Lcom/oplus/ocs/authenticate/data/AuthenticationDb;` |
| `com.coloros.gallery3d` | 相册（**share agent + NAS token 客户**） | 见 §3.7 |
| `com.android.intentresolver` | 系统分享面板 | `compat_tap_share_file_pkgs` |
| OPPO 平台隐藏 API | `Lcom/oplus/app/OplusWindowInfo`、`Landroid/view/OplusWindowManager`、`Lcom/heytap/addon/*`(38 文件)、`Lcom/oplusx/*`(12 文件)、`Lcom/oplus/os/*`(20 文件)、`Lcom/oplus/ocs/*`(22 文件) | 逐包 grep 计数 |

`[推断]` 这套东西**无法脱离 OPPO ROM 运行**：依赖 OPPO 平台隐藏 API + 厂商 NFC HAL + 多个 OPPO 预装应用。

---

## 三、取图机制（逐条证据）

### 3.1 否定性证据一：它没有无障碍服务 `[资源/清单]`

对 `AndroidManifest.xml` 全量 xmltree + 全 dex 检索：

- `grep -i "accessibility|a11y" manifest.xmltree` → **0 命中**
- `grep -rn "BIND_ACCESSIBILITY_SERVICE"` → 仅命中 `androidx.core` 的 `AccessibilityServiceInfoCompat`（库代码）

**结论**：OPPO 没有用无障碍读相册界面。（这否定了我们试过的路线 2 是"OPPO 的方案"。）

### 3.2 否定性证据二：它不查 MediaStore `[smali]`

全 5 个 dex 检索：

- `Landroid/provider/MediaStore` 仅 1 处：`com/oplus/pantaconnect/appcore/utils/n.smali:181` → `MediaStore$Images$Media.getBitmap(resolver, uri)`，**只是把已拿到的 uri 解码成缩略图**，不是查询。
- `content://media` → **0 命中**
- `MediaStore$MediaColumns` / `MediaStore$Images` 查询 → **0 命中**
- 全 APK 唯一的 `MediaStore` 字符串是 `MediaStore.getPickImagesMaxLimit`（只是个常量名）

**结论**：它**没有**"枚举媒体库"的能力和意图。

### 3.3 核心机制：ShareServer RPC + Share Agent SDK `[smali]`

框架对外暴露一个通用 RPC 接口（AIDL）：

```
// smali_classes4/com/oplus/interconnect/service/IInterConnIpcInterface.smali
DESCRIPTOR = "com.oplus.interconnect.service.IInterConnIpcInterface"
public abstract byte[] request(String module, String method, byte[] args, Bundle extras)
```

回调方向：

```
// smali_classes4/.../IInterConnIpcCallback.smali
DESCRIPTOR = "com.oplus.interconnect.service.IInterConnIpcCallback"
public abstract boolean callback(String name, byte[] data, Bundle extras)
```

实现方是 `InterconnectService$ShareOuterIpcInterfaces`（"Outer" = 面向外部应用）`[smali: smali_classes4/com/oplus/interconnect/service/InterconnectService$ShareOuterIpcInterfaces.smali]`。

在这个 RPC 之上，架了一个名为 **ShareServer** 的服务端（`RealShareServer`），方法名常量分布如下：

| RPC 方法名 | 常量所在文件 | 语义 |
|---|---|---|
| `isShareAvailable` | `com/oplus/interconnect/nfc/processor/f.smali:106` | App 问框架：碰一碰可用吗 |
| `registerShareAgent` | `com/oplus/interconnect/nfc/processor/g.smali` | App 登记"我是可分享源" |
| `registerShareOperationCallback` | 同上 | **App 开一条长连接事件通道** |
| `startShare` | `com/oplus/interconnect/nfc/processor/i.smali:25` | **App 主动上交当前分享数据** |
| `resumeShare` | `com/oplus/interconnect/nfc/processor/f.smali:92` | 恢复分享（带新数据） |
| `rejectShare` | `com/oplus/interconnect/nfc/processor/f.smali:64` | App 拒绝 |
| `unregisterShareAgent` / `unregisterAgents` | `f.smali:78 / :31` | 注销 |

**上交数据的载荷格式**（`startShare` 的处理链）。注意 `g7/*` 是被混淆的 protobuf 生成类，其语义由 `com/oplus/interconnect/service/serialization/ProtobufExKt` 与 `share/sdk/data/*Kt` 的转换函数给出：

```
ShareProcessors$StartShareProcessor.process()
  → g7/t1.parseFrom(bytes)                      // 名为 InternalSharedData 的 protobuf
  → ProtobufExKt.toShareAgentClient(g7/m)  → client : InternalShareAgentClient(agentId, pkg, pid, appName)
  → SharedDataKt.toData(g7/f0)             → data   : SharedData     ← 真正的分享内容
  → SharedDataKt.toCardOptions(g7/b0)      → card   : CardOptions
  → isReShare : Boolean                       (g7/t1.getIsReShare())
  → new InternalSharedDataParams(client, data, card, isReShare)
  → RealShareServer.g(params)                    // smali_classes/.../nfc/processor/e.smali:560
```

`RealShareServer.g()` 随即：

```java
new ShareOperationEntity(agent, params.getData(), params.getCard(), deferred)   // e.smali:717
tapShareManager.l(entity)                                                       // TapShareManager.l
```

### 3.4 数据模型：`SharedData` / `SharedRecord` `[smali]`

```
// com/oplus/interconnect/share/sdk/data/SharedData.smali
SharedData(
    card:         CardOptions,
    name:         String,
    shareName:    String,
    records:      List<SharedRecord>,     // ← 每一项就是一张照片
    packageNames: List<String>,
    isReShare:    Boolean,
    extrasData:   Map<String,String>
)

// com/oplus/interconnect/share/sdk/data/SharedRecord.smali
SharedRecord(
    type:      MimeType,   // 见下
    content:   String,
    uri:       String,     // ← 照片的 Uri
    extraData: byte[]
)
```

`MimeType` 枚举 `[smali]`（含映射字符串）：

```
IMAGE      -> "image/*"
VIDEO      -> "video/*"
IMAGE_AND_VIDEO -> "image_and_video"
FILE       -> "file/*"
TEXT       -> "text/plain"
APP_LINK   -> "link/*"
CAST       -> "cast"
RELAY      -> "relay"
EXCHANGE_INFO -> "exchange_info/*"
PARAMETERS -> "parameters/*"
AUDIO      -> "audio/*"
UNKNOWN
```

**这就是"哪些照片"的载体**：`SharedData.records[*].uri`。**是一个列表**，因此天然覆盖"多选"；**来自相册自己的选中集合**，因此天然覆盖"屏幕外"。

### 3.5 触发链：App 怎么知道该交数据 `[smali]`

`registerShareAgent` / `registerShareOperationCallback` 都不是普通的一问一答，而是**把这次 RPC 的"回复"挂起保存成一条事件通道**。

RPC 方法名 → 处理器的精确映射（`[smali]` `com/oplus/interconnect/nfc/processor/g.smali` 的构造器 + `packed-switch`）：

```
方法名                              index  处理
registerShareOperationCallback   =  0  →  RealShareServer.d(ResponseWrapper)
                                          → TapShareManager.i(wrapper)
                                          → 若有正在跑的 pair task，转给它（任务结果通道）
registerShareAgent               =  1  →  RealShareServer.c(InternalShareRegisterParams, ResponseWrapper)
                                          → "shareOn agent: "   (e.smali:459)
                                          → new NfcShareAgent(agentId, pkg, pid, appName, wrapper)
                                          → nfc/d.c(agent)  注册到全局 NfcEventRepository
```

`NfcShareAgent` 的 `callback` 字段就是这个被挂起的 `ResponseWrapper`
（`[smali] com/oplus/interconnect/nfc/f.smali`，字段 `e:Lcom/oplus/interconnect/service/serialization/ResponseWrapper;`）。

之后框架可以**多次**往这条通道推事件（`ResponseWrapper.getResponse()` 返回一个可反复调用的 `wn/p`）：

```
// smali_classes/com/oplus/interconnect/nfc/NfcEventRepository$registerShareAgent$1$newData$1.smali
invoke(name, bytes, bundle) {
    agent.e.getResponse().invoke(name, bytes)   // → IInterConnIpcCallback.callback(...)
}
```

推送的调用链（**已完整追通** `[smali]`）：

```
NfcPair.fetchAppStrategyResult(compatEvent, role)                 o.smali:192 (注册为 o.n:Lwn/p;)
  → NfcPair$fetchAppStrategyResult$1.invoke(event, role)
      → 取当前 agent 的回调（p.l:o7/g → o7/g.b，即 registerShareAgent 时挂起的 ResponseWrapper 的 getResponse()）
      → new OuterStrategyData(event, role, callback)               o7/h.smali
      → o.c:Lwn/l (策略链) 由 ClientNfcPairing$nfcPair$2$3 / ServerNfcPairing$nfcPair$2 注入
          → pair/r.c(OuterStrategyData)
              → DefaultNfcPairResultHandler.c(...)                 pair/i.smali:633
                  → notifyOuterNfcContractEventStart(...)          pair/i.smali:660
                      → agent 的 ResponseWrapper.getResponse().invoke("registerEvent", bytes)
                          → IInterConnIpcCallback.callback(...)     ← 相册在这一刻被通知
```

碰一碰时推给相册的内容：

```
Bundle extras:
    "nfc_contract_event" = NfcContractEventType.CONTRACT_START
    "nfc_event_role"     = NfcEventRoleType.{SENDER | RECEIVER}
data_parcel = NfcEventCallbackParcelize(type, result, errorCode, bundle)
invoke("registerEvent", byte[0], {data_parcel: ...})
```

（`pair/i.smali:685-760`；`NfcEventRoleType` 由 `InternalNfcEventRole{SENDER,RECEIVER}` 映射而来）

SDK 侧对应类型（框架内只定义、未被自身引用 → 属于**给客户 App 用的 SDK 面**）`[smali]`：

```
com.heytap.accessory.nfc.sdk.INfcEventIpcCallback
com.heytap.accessory.nfc.sdk.ipc.data.NfcContractEventType  = { CONTRACT_START }
com.heytap.accessory.nfc.sdk.ipc.data.NfcEventRoleType      = { SENDER, RECEIVER }
com.heytap.accessory.nfc.sdk.ipc.data.NfcEventCallbackParcelize(type, result, errorCode, bundle)
com.heytap.accessory.nfc.sdk.ipc.data.NfcEventParcelize(packageName, action, type, count)
com.heytap.accessory.nfc.sdk.ipc.data.NfcActionParcelize(packageName, action, isExecute, localDeviceId, remoteDeviceId)
com.heytap.accessory.nfc.sdk.ipc.data.NfcScenes  = { GALLERY_SELECT, GALLERY_SHARE, OSHARE_SHARE, SYSTEM_SHARE, UNKNOWN }
```

> ⭐ **`NfcScenes` 里赫然有 `GALLERY_SELECT`（相册浏览/选中）与 `GALLERY_SHARE`（相册分享）两个场景。**
> 这就是"框架如何知道当前在相册、处于什么状态"的答案——**由 App 自报场景**，不是框架去猜。

**完整时序**（`[smali]` 拼接 + `[推断]` 顺序）：

```
[相册启动 / 用户改动选中项]
  相册 → isShareAvailable()                        // 问可用性          f.smali index 2 → e.a()
  相册 → registerShareAgent(type, client)          // 登记 + 拿到事件通道 + 触发 NFC 广播发现
                                                   //                  g.smali index 1 → e.c() → nfc/d.c()

[两台手机碰一下]
  框架 → callback("registerEvent", {CONTRACT_START, role=SENDER})   // 通知相册
                                                   // pair/i.smali notifyOuterNfcContractEventStart()
  相册 → startShare(InternalSharedDataParams{client, data:SharedData{records:[uri...]}, card})
  相册 → registerShareOperationCallback(...)       // 要一条"本次任务结果"通道（需已有 pair task）
  框架 → TapShareManager.l(entity)
           ├─ 断言 isPairing == true，否则 ShareInvalidTaskException("share failed, is not pairing")
           └─ 断言有正在运行的 pair task，否则 ShareInvalidTaskException("share failed, no task running")

[确认与传输]
  框架弹碰一碰确认卡（缩略图由 records[].uri 解码，见 §3.2）
  用户上滑确认 → 框架 → 通过 registerShareOperationCallback 的通道回结果
                        （NfcShare$senderUserEntry： "sender agree" / "sender cancel" / "sender time out"）
  框架 → com.coloros.oshare.FileTransferManager.sendData(...)  // 交给 OShare 真传
```

顺序约束的硬证据（`[smali]` `smali_classes/com/oplus/interconnect/tapshare/d.smali:974 TapShareManager.l`）：
`startShare` 时必须 `isPairing==true` 且有运行中的 pair task，否则直接抛 `ShareInvalidTaskException`。**所以"App 交数据"必然发生在"碰一碰已经触发配对"之后**，不可能是 App 自己周期性盲推。

反向印证（`[smali]`）：
- `com/oplus/interconnect/nfc/share/exception/ShareAppShareDataEmptyException`（"app's shared data is empty!"，`pair/a0.smali:178`）
- `com/oplus/interconnect/nfc/share/exception/ShareWaitAppShareDataTimeoutException`（`SenderIosSharePairing$start$2.smali:229`，等待 10s `0x2710` 超时）

即：**框架等相册交数据，等不到就超时失败**。

### 3.6 场景判定与兼容白名单 `[资源/清单]`

`res/values/arrays.xml`（经 `aapt2 dump resources` 读出）：

```xml
<array name="compat_tap_share_file_pkgs">
  com.oneplus.oshare, com.coloros.oshare,
  com.android.intentresolver, com.oneplus.filemanager,
  com.coloros.filemanager, andes.oplus.documentsreader
</array>
<array name="compat_tap_share_image_pkgs">
  com.coloros.gallery3d, com.oneplus.gallery          ← ⭐ 只有 OPPO/OnePlus 自家相册
</array>
<array name="compat_tap_share_game_pkgs">
  com.dfjz.moba, com.dfjz.moba.nearme.gamecenter, com.dfjz.moba.bytedance.gamecenter
</array>
<array name="ios_tap_share_pkgs">
  com.coloros.gallery3d, com.oneplus.gallery,
  com.oneplus.oshare, com.coloros.oshare, com.android.intentresolver
</array>
```

消费方 `[smali]`：

```
// smali_classes/r7/a.smali  (即 NfcDiscCompatExtKt.pkgToCompatNfcEvent)
static o7/a a(String pkg):
    filePkgs, imagePkgs, gamePkgs = 读上面三个数组
    if pkg in filePkgs  -> InternalNfcAction.SHARE_FILE
    if pkg in imagePkgs -> InternalNfcAction.SHARE_FILE
    if pkg in gamePkgs  -> InternalNfcAction.GAME_INVITATION
    else                -> null
```

调用点：`nfc/d.c(agent)`（registerShareAgent 时）、`discovery/g.smali:179`、`OplusShareDiscovery$startAdvertise$2.smali:640`（NFC 广播发现时）。

`InternalNfcAction` 枚举 `[smali]`：`SCREEN_CAST / SHARE_FILE / SHARE_IMAGE / SCREEN_RELAY / DATA_RELAY / EMPTY_ACTION / GAME_INVITATION`。

> **要点**：白名单里**只有 OPPO/OnePlus 自家相册**。第三方相册（Google 相册、小米相册…）**不在名单里**，`pkgToCompatNfcEvent` 返回 `null`。

### 3.7 相册在框架里出现的全部位置（穷举）`[smali]`

全 dex 检索 `com.coloros.gallery3d`，**仅 3 处**：

1. `res/values/arrays.xml`（上面两个数组）`[资源/清单]`
2. `com/oplus/pantaconnect/appcore/peripheral/nas/observer/a.smali:24` —— NAS（网络存储）相关应用观察者，与碰传无关
3. `com/oplus/pantaconnect/discovery/fusionprovider/provider/call/c.smali:87` —— NAS token 预加载白名单，与碰传无关

**没有任何"相册专用取图代码"**——从反面再次确认：框架不与相册做任何特殊交互，一切走 §3.3 的通用 ShareServer RPC。

### 3.8 非合作 App 的兜底：页面理解 + 截屏（**不是**取图）`[smali]` `[资源]`

框架内确实存在"读别人界面"的组件，但它们**不用于取照片**：

| 组件 | 说明 |
|---|---|
| `com.oplus.smartanalysis.SmartAnalysis` | 页面内容理解。`findAllVisibleMainWindowInfo()` 用 `android.view.OplusWindowManager.getAllVisibleWindowInfo()`；规则库 `assets/rule_default.db` |
| `assets/rule_default.db` | SQLite，表 `rule(id, app_name, package_name, config_version, data, ...)`，68 条规则。**逐条检查：全部是新闻/资讯类 App**（今日头条、凤凰新闻、腾讯新闻、知乎、微博、QQ、百度…），提取的是 **url/title/article**（`ArticleParser`/`VideoParser`），**没有一条与照片有关** |
| `…nfc.capture.ScreenCaptureWrapper` | `screenshot()`，配合 `READ_FRAME_BUFFER` 权限 |
| `…discovery.DefaultShareAgentOptions` | `onAgentFound → screenShot / screenCast / screenRelay / relayTask` —— 没有 share agent 时退化为**投屏/接力** |
| `com.heytap.addon.screenshot.OplusLongshotViewInfo` | 长截图 addon（与超级截屏同源） |

规则条目形态（举例，从 `rule_default.db` 直接读出）：

```json
{"parser":"ArticleParser",
 "trigger_rule":{"context":{"context":"DocDetailActivity","action":"display"}},
 "package_name":"com.ifeng.news2",
 "attribute_rule":{"reflection":{"attribute_hierarchy":[...字段索引...]},
                   "intent":{"extra_list":[{"extra_key":"...","capture_pattern":"..."}]}}}
```

`[推断]` 这是 OPPO "应用接力/投屏" 用的**按包+版本+Activity 定制**的反射/View 树提取规则，只抽 链接/标题，**与媒体选中项无关**。

**结论**：对非合作 App，OPPO 的方案是"分享屏幕/接力"，**不是"分享正确的那几张照片"**。

---

## 四、碰一碰与传输（它自己的协议）

### 4.1 NFC 声明 `[资源/清单]`

```
uses-feature android.hardware.nfc       required=true
uses-feature android.hardware.nfc.hce   required=true
uses-permission android.permission.NFC
uses-library    nfcvendorlib            required=false
```

`[smali]` `smali_classes/t7/a.smali` 用反射取厂商 NFC 能力：

```java
Class.forName("com.vendor.nfc.VendorNfcAdapter")
     .getMethod("getVendorNfcAdapter")...
     .hasFeature("TAP_TO_SHARE_WITH_IOS")
```

→ **依赖厂商 NFC HAL**，AOSP 无此类。

### 4.2 HCE AID `[资源/清单]`

`xml/oplus_apdu_service`（资源 `0x7f14000b` → `res/Br.xml`）：

```xml
<host-apdu-service android:requireDeviceUnlock="true">
  <aid-group android:category="other">
    <aid-filter android:name="F000004F50504F2E434F4D"/>
  </aid-group>
</host-apdu-service>
```

AID = `F0 00 00` + ASCII **"OPPO.COM"**。

`xml/ios_apdu_service`（`res/QM.xml`）同样 `requireDeviceUnlock=true` 但**无 aid-filter**（对 iOS 走另一套）。

### 4.3 配对契约 `[smali]`

`com/oplus/interconnect/nfc/pair/` 下是一套自研配对协议：

- `contract/`：`NfcPairInitContract`、`NfcConnectAccessoryContract`、`NfcDataSyncContract`、`KscNegotiationContract`、`NfcSenderConfirmContract`、`NfcReceiverConfirmContract`、`ReadyConfirmContract`、`TransferConfirmContract`（+ Watch 变体）
- 密钥：`contract/keygenerator/OobKeyGenerator`、`UKey2KeyGenerator`、`contract/ksc/KscGeneratorImpl`（UKey2 / KSC 密钥协商）
- 角色：`NfcPairRole`、`SenderIosSharePairing` / `ReceiverIosSharePairing`、`ServerNfcPairingV3` / `ClientNfcPairingV3`
- 底层隧道：`com.oplus.pantaconnect.sdk.internal.scpCompat.CompatScp.createP2pGroup()`（蓝牙/Wi-Fi P2P）
- 兼容 OShare 老链路：`CompatSharedDataTransmitter`

`NfcPair$transferRealNfcEvent$1` `[smali]` 中可见：**APDU 通道只传元数据**——它遍历 `SharedData.records`、按 `MimeType` 判定 `SHARE_IMAGE` vs `SHARE_FILE`、统计 `records.size()` 作为 count（对应 `NfcEventParcelize(packageName, action, type, count)`）。**真正的字节不走 NFC。**

### 4.4 传输委托给 OShare `[smali]`

```
com.coloros.oshare.FileTransferManager.getInstance()
    .sendData(Context, Bundle, ArrayList<...>, ResultCallback)
    → com.coloros.oshare.ITransferService.sendData(Intent)
```

框架侧实现的回调：`com/oplus/interconnect/nfc/share/d.smali` 里的 `onFileInfoReceived(com.coloros.oshare.FileInfo)` / `onPercentChange(int,long)` / `onResult(int,ArrayList)`。

→ **文件传输 100% 委托给 `com.coloros.oshare`（OPPO 互传）**，AccessoryFramework 只做发现/配对/确认卡/编排。

### 4.5 接收侧如何把数据交回 App `[smali]`

`com/oplus/interconnect/nfc/share/d.smali`（约 2468–2500 行）`startSeamlessView`：

```java
Intent i = new Intent();
i.setPackage(targetPkg);
i.addFlags(FLAG_ACTIVITY_...);
i.setAction("com.oplus.interconnect.action.SHARED_DATA");
// resolveActivity 命中才发
i.putExtra("share_data",      InternalSharedData.serialize(PROTOBUF));
i.putExtra("share_data_json", InternalSharedData.serialize(JSON));
```

→ 接收端"无缝看"由**目标 App 的一个 Activity**（声明 action `com.oplus.interconnect.action.SHARED_DATA`）承接，数据以 protobuf/JSON 双份塞在 extra 里。

`[smali]` 相关 provider / action：
`com.oplus.pantaconnect.NFC.animation`、`com.oplus.pantaconnect.NFC.seedingCard`、`com.oplus.seedling.action.SEEDLING_CARD`（`assets/PTCConnectSeedling.upk`）、`NfcEventContentLifeCycleProvider`、`NfcConnectedSeedlingCardProvider`。

---

## 五、对我们可行的部分 vs 依赖 OPPO 私有能力的部分

### 5.1 可以照抄的（无 OPPO 依赖）

| 可抄的点 | 说明 |
|---|---|
| **"数据源自报" 模型** | 我们自己 App 内的相册/看图器，直接持有选中集合（含屏幕外项），把它交给传输侧。这是唯一能同时满足"无 ShareSheet、无 Picker、含屏幕外"的模型——**且不依赖任何 OPPO 件**。 |
| **触发与数据分离** | 碰一碰只负责"触发/握手"，数据在触发后由数据源按需给出（OPPO 的 `TapShareManager.l` 顺序约束证明了这一点是设计意图）。我们可照抄：`onPeer` 后回调当前前台 UI 取选中项。 |
| **`SharedRecord(type, uri)` 形状** | 一份"类型 + Uri"的列表足够表达照片集合，不需要文件路径、不需要读媒体库权限（Uri 由数据源自己给出，并附 `FLAG_GRANT_READ_URI_PERMISSION`）。 |
| **HCE + reader 双角色** | AID 自定义 + `requireDeviceUnlock` 的设计可直接借鉴（AID 换我们自己的）。 |

### 5.2 依赖 OPPO 私有能力、**我们无法照搬**的部分

| 依赖项 | 为什么搬不了 | 证据 |
|---|---|---|
| `com.oplus.interconnect.service.InterconnectService` + ShareServer RPC | 该服务在本 APK 内，我们的设备没有 | 清单；`processor/*.smali` |
| **OCS 调用方鉴权** | `InterconnectService$ShareOuterIpcInterfaces.request()` 先做 `getOcs().a(uid, pid)`，非 `1001(0x3E9)` 一律回 `"ocs auth failed"` + 错误码 `0x66`。鉴权基于 **uid + capability 白名单**（`SELECT * FROM a_e WHERE uid=? AND capability_name=?`，`capability_name="OAF_CLIENT"`，库文件 `authentication.db`，类 `com.oplus.ocs.authenticate.data.AuthenticationDb`），另有 `hi/e` 的权限位表（`com.heytap.accessory.permission.{PREROGATIVE,PUSH_MESSAGE,DISCOVERY,AWAKENABLE}`）与 `hi/b.c(pkg, signature)` 的签名字节位比对 | `smali_classes4/…/InterconnectService$ShareOuterIpcInterfaces.smali:337-470`、`smali_classes/hi/{b,c,d,e}.smali` |
| **compat 白名单** | `pkgToCompatNfcEvent()` 只认表内包名；我们的包名不在 `compat_tap_share_image_pkgs` 里，`registerShareAgent` 拿不到 NFC 事件 → 无法成为 share agent。而且这两个数组**硬编码在 OPPO 的 APK 资源里** | `smali_classes/r7/a.smali`、`res/values/arrays.xml` |
| **`com.coloros.oshare` 传输服务** | 真传文件靠它；我们没有 | `com/coloros/oshare/FileTransferManager.smali` |
| **`com.vendor.nfc.VendorNfcAdapter`** | 厂商 NFC HAL 特性 `TAP_TO_SHARE_WITH_IOS`，AOSP 无 | `smali_classes/t7/a.smali:69-107` |
| **OPPO 平台隐藏 API** | `Lcom/oplus/app/OplusWindowInfo`(10 文件)、`Landroid/view/OplusWindowManager`(3)、`Lcom/heytap/addon/*`(38)、`Lcom/oplusx/*`(12)、`Lcom/oplus/os/*`(20)、`Lcom/oplus/ocs/*`(22)、`Landroid/view/OplusWindowManager` 等 | 逐包 grep 计数 |
| **特权系统权限** | `READ_FRAME_BUFFER`/`WRITE_SECURE_SETTINGS`/`MANAGE_USB`/`BLUETOOTH_PRIVILEGED` 等 signature/privileged 权限 | 清单 |

### 5.3 对"Google 相册取图"这个原始问题的最终判断 `[推断]`

OPPO 的答案**不适用于**"让我们的 App 去读第三方相册的当前选中项"：

- 它的取图能力**只对自家相册成立**（白名单证据）。对第三方相册，OPPO 的兜底是投屏/接力（§3.8），**不是取正确文件**。
- 因此，"第三方相册 + 屏幕外选中项 + 无 ShareSheet" 这个组合，**OPPO 自己也没有非合作解**。

如果我们的场景可以收窄为"**数据源是我们自己的 UI**"（我们自己的相册/看图器/文件列表），那 OPPO 这套架构就是最优解，且可以直接实现，不依赖 OPPO 任何东西——**推荐的落地方式**。

---

## 六、未确定的部分

| # | 未确定项 | 说明 / 已知线索 |
|---|---|---|
| 1 | **相册调用 ShareServer 的确切代码** | 相册 APK（`com.coloros.gallery3d`）不在手边，无法直接读到它调用 `isShareAvailable/registerShareAgent/startShare` 的代码。**"相册是 share agent" 是 `[推断]`**，依据：① 白名单里只有它；② `NfcScenes.GALLERY_SELECT/GALLERY_SHARE` 两个场景专为它定义；③ 框架无任何其它取图途径；④ 框架无媒体读权限。 |
| 2 | **客户端 SDK 的发布形态** | `IInterConnIpcInterface` / `com.heytap.accessory.nfc.sdk` / `com.oplus.interconnect.share.sdk.Internal*` 在本 APK 内是**服务端实现**，dex 中**没有任何客户端调用点**（`grep -rl "IInterConnIpcInterface"` 只命中服务端 Stub/Proxy 与该 Service 自身）。`[推断]` 客户端 SDK 是单独 AAR/JAR 随相册发布。 |
| 3 | **`InternalShareRegisterParams.type` 的取值** | 该字段是 String，框架侧**只打日志、未参与逻辑**（`e.smali` 中 `getType()` 无业务分支）。`[推断]` 客户端用它填 `NfcScenes` 场景名，但无法证实。 |
| 4 | **相册收到 `CONTRACT_START` 后是"立刻交数据"还是"本来就有缓存"** | 框架→相册的通知链**已完整追通**（见 §3.5）。但相册侧（`com.coloros.gallery3d`）代码不在手边，无法看到它收到回调后做了什么。**只靠框架侧无法区分**"拉"（收到回调才 `startShare`）与"推"（本来就缓存，回调只是提示）。`[未确定]` |
| 5 | **相册是否在选中项变化时持续上报** | 有 `isReShare` / `resumeShare` / `SharedData.setReShare()` 等字段暗示"重分享/续传"，但没有直接证据表明相册会持续 push。`[未确定]` |
| 6 | **`NfcScenes` 由谁填充** | 枚举在框架内定义但**框架自身零引用** → 一定由客户端填写。填在哪（registerAgent 的 type？还是 callback 的 bundle？）未定。`[未确定]` |
| 7 | **OCS 白名单的具体内容** | `hi/d.a` / `hi/d.b` 两个 `ArrayMap` 在 `<clinit>` 里**只初始化为空**，运行时由 `hi/d.a(int, String, byte[])` 填充（`byte[]` 长度必须为 4 = 32 位权限位图）。**填充源（ContentProvider？配置文件？）未追到**。`[未确定]` |
| 8 | **`SharedData.card` / `packageNames` 的语义** | `CardOptions`（`CardType`/`Thumbnail`/`ButtonOptions`/`TextLine`/`Alignment`…）显然是确认卡外观；`SharedData.packageNames` 应指接收端可承接的包（配合 §4.5 的 `resolveActivity`），但未逐字段验证。`[未确定]` |
| 9 | **iOS 侧协议** | `IosHostApduService` 无 aid-filter，配对走 `SenderIosSharePairing`/`ReceiverIosSharePairing` + `NfcEventParcelize(count)`；具体报文格式未展开分析。`[未确定]` |

---

## 附录：本次分析产出的证据文件（均在 `_Temp/afk/`，未入仓）

| 文件 | 内容 |
|---|---|
| `badging.txt` | `aapt2 dump badging` 输出 |
| `manifest.xmltree` | `aapt2 dump xmltree --file AndroidManifest.xml`（1251 行） |
| `resources.txt` | `aapt2 dump resources`（69333 行，含 `compat_tap_share_*` 数组） |
| `str_classes*.txt` | 5 个 dex 的 `strings` 输出 |
| `smali_classes*/` | baksmali 反汇编结果（5 个 dex 全量） |
| `apk/` | 从 APK 抽出的 dex / assets（原 APK 未改动） |

关键类速查：

```
取图链路：
  com/oplus/interconnect/service/InterconnectService$ShareOuterIpcInterfaces.smali   RPC 入口 + OCS 鉴权
  com/oplus/interconnect/service/IInterConnIpcInterface.smali                        request(module,method,bytes,bundle)
  com/oplus/interconnect/service/IInterConnIpcCallback.smali                         callback(name,data,bundle)
  com/oplus/interconnect/nfc/processor/{e,f,g,i}.smali                               RealShareServer + 方法表
  com/oplus/interconnect/nfc/processor/ShareProcessors$StartShareProcessor$process$1  startShare 解包
  com/oplus/interconnect/share/sdk/data/{SharedData,SharedRecord,MimeType}.smali      数据模型
  com/oplus/interconnect/nfc/NfcEventRepository$registerShareAgent$1$newData$1.smali 框架→App 回调通道
  com/oplus/interconnect/nfc/pair/i.smali                                            notifyOuterNfcContractEventStart
  com/oplus/interconnect/tapshare/d.smali                                            TapShareManager（顺序约束）
  com/heytap/accessory/nfc/sdk/ipc/data/NfcScenes.smali                              GALLERY_SELECT / GALLERY_SHARE
碰一碰/传输：
  res/Br.xml (xml/oplus_apdu_service)                    AID F000004F50504F2E434F4D
  com/oplus/interconnect/nfc/pair/CompatSharedDataTransmitter*.smali
  com/coloros/oshare/FileTransferManager.smali           真传
鉴权：
  smali_classes/hi/{b,c,d,e}.smali + com/oplus/ocs/authenticate/data/AuthenticationDb
```
