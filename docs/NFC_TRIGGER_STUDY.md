# 系统 NFC 触发点研究：能否由系统直接拉起我们的悬浮窗

> 调研对象：`/dfb/zhuyang/share/nfc_test/alps`（**本机实际运行的 ROM 源码树**，勿与 `mtk_x/alps` 混淆）
> 我们的 app：`/dfb/zhuyang/share/nfc_test/pri_project/android/pri_app/nfc_tap_demo`
> 机型：Armor X17（`model:Armor_X17`），运行时 NFC 组件为 `/system/priv-app/Nfc_st/Nfc_st.apk`
> 标签约定：**源码已确认** / **实机已测** / **推断** / **未验证**
> 结论日期：2026-10-16

---

## 0. 结论先行

### 0.1 直接回答「能不能让 NFC 触发直接拉起悬浮窗,不用我们自己控制 NFC 的读、模拟」

**能——但要拆成两层看，只有一层能做到「不用我们控制」。**

| 层 | 谁在驱动 | 现状 | 结论 |
|---|---|---|---|
| **NFC 射频/协议层**（发现标签、路由 AID、收并发 APDU） | **系统**（`com.android.nfc`） | **当前就已经是系统驱动**，我们没写 reader 循环、也没写监听循环 | 这一层**从来就不需要「我们自己控制」** |
| **业务载荷层**（两个手机之间交换 SSID/密码/蓝牙名等） | **我们**（`Bus` + `HceService` 的 APDU 协议 + reader 侧 `IsoDep.transceive`） | 必须我们自己做，系统不认识我们的私有 APDU 格式 | 这一层**无法交给系统**，除非换成 NDEF 让系统代读（见 §3.2） |
| **触发时机层**（"碰到了" → 拉起 `OverlayService`） | 目前：系统派发 → 我们的 `TapDispatchActivity`/`HceService` → `TapService` → `OverlayService` | 已可工作 | 这一层**可以进一步提前**，由系统钩子直接触发（见 §3.1 / §3.3） |

**关键发现：系统侧存在两条"不需要 NDEF"的进程内钩子，而且不需要改 ROM——我们自己的 app 现在就能注册。**

### 0.2 三条最重要的结论

1. **`NfcOemExtension` 回调槽位在这个设备上是空的，且我们有权注册。**（**实机已测** + **源码已确认**）
   - 设备的 `framework-nfc.jar`（`/apex/com.android.nfcservices/javalib/framework-nfc.jar`）里**确实含** `android/nfc/NfcOemExtension` 与 `INfcOemExtensionCallback`（我把该 jar 拉到本地 `strings` 验证过）。
   - `NfcAdapter.mNfcOemExtension` 在构造时**无条件**初始化（`framework/java/android/nfc/NfcAdapter.java:947`），没有任何 flag 开关；`getNfcOemExtension()` 只检查 `sHasNfcFeature`（同文件 `:2921-2931`）。
   - 注册入口只要求 `WRITE_SECURE_SETTINGS`（`NfcOemExtension.java:577`、`framework/.../NfcAdapter.java:594` → `NfcService.java:3734-3741` → `NfcPermissions.java:25,85-87`，`ADMIN_PERM = android.Manifest.permission.WRITE_SECURE_SETTINGS`）。
   - 我们的 app **已经持有该权限且已实际授予**（**实机已测**：`dumpsys package com.pri.nfctouchdemo` 中 `android.permission.WRITE_SECURE_SETTINGS: granted=true`，来自 `android:sharedUserId="android.uid.system"`）。
   - 厂商自己的 ST 扩展**从未注册**：`enable_oem_extension` 未设置（`device_config get nfc enable_oem_extension` → `null`），回落到资源 `R.bool.enable_oem_extension = false`（ST overlay `StNfcOverlay/res/values/config.xml:11` = `false`；AOSP 默认 `NfcNci/res/values/config.xml:40` 亦为 `false`），因此 `executeTaskBoot()` 不会发 `ACTION_OEM_EXTENSION_INIT`（`NfcService.java:1444-1452`），`StNfcExtensionReceiver` 不会被唤醒。**实机 logcat 中检索 `registerOemExtensionCallback` / `NfcOemExtension` / `OEM_EXTENSION_INIT` 全部无命中。**

2. **`onTagDispatch` 是本设备上最干净的系统侧触发点：不碰 NDEF、不受锁屏限制、由读卡方（reader 侧）触发。**（**源码已确认**）
   - 调用点：`vendor/st/.../NfcService.java:6120-6125`，位于 `NfcService.dispatchTagEndpoint()` 的**最开头**，早于 `Tag` 对象构造、早于 `mNfcDispatcher.dispatchTag()`（同文件 `:6174`）。
   - 顺序：`MSG_TAG_DISCOVERED` → `onTagConnected(true)`（`:5480`）→ `onNdefRead` 询问（`:5481-5486`）→ NDEF 读取 → `dispatchTagEndpoint()` → **`onTagDispatch`** → 之后才是 `dispatchTag()`。
   - 因为它在 `NfcDispatcher.dispatchTag()` **之前**，所以完全**绕过了** `dispatchTag()` 里那道锁屏闸门（`NfcDispatcher.java:629-635`：锁屏且不是解锁标签 → 直接 `return DISPATCH_FAIL`）。这是当前 `TapDispatchActivity` 做不到的。
   - **代价**：`onTagDispatch` 只给一个 `Consumer<Boolean>`，**不携带 `Tag`**，所以它只能"报信"，不能"取数据"。

3. **卡模拟方（我们被读）侧也有系统钩子：`onHceEventReceived` / `onRfFieldDetected` / `onCardEmulationActivated`。**（**源码已确认**）
   - `CardEmulationManager.java:304` → `HCE_ACTIVATE`；`:331` → `HCE_DATA_TRANSFERRED`；`:361` → `HCE_DEACTIVATE`。
   - `NfcService.onRemoteFieldActivated()`（`:687-693`）→ `onRfFieldDetected(true)`，这是**最早**的信号：对方读卡器一开射频场就报，早于任何 APDU。

### 0.3 对用户前提的现实检验（详见 §5）

**「服务被杀掉所以碰触没反应」基本是理论风险，不是当前的实际漏洞。**（**实机已测**）

---

## 1. 系统 NFC 触发点全景

### 1.1 派发链路（reader 侧：我们读到对方）

`NfcService.dispatchTagEndpoint()`（`vendor/st/.../NfcService.java:6120`）之前的顺序：

| # | 触发点 | file:line | 是否要 NDEF | 是否受锁屏限制 | 能否拿到 Tag/数据 | 备注 |
|---|---|---|---|---|---|---|
| 1 | `onTagConnected(true/false)` | `NfcService.java:5480`（true）/ `:5473`、`:5531`、`:6174`（false）；定义 `:6240-6243` | 否 | 否 | 否 | 最早，标签一被激活就报 |
| 2 | `onNdefRead(Consumer<Boolean>)` | `NfcService.java:5481-5486`（发射 `:1100`） | 否 | 否 | 否 | 返回 true 可**跳过 NDEF 读取**（省时间），但不跳过后续派发 |
| 3 | `onTagDispatch(Consumer<Boolean>)` | `NfcService.java:6120-6125` | **否** | **否** | 否（无 Tag） | **最佳触发点**；返回 true 会**吞掉**整次派发 |
| 4 | `NfcDispatcher.dispatchTag()` 内部顺序 | `NfcDispatcher.java:606-734` | — | 锁屏闸门在 `:629-635` | — | 见 1.2 |
| 5 | `TECH_DISCOVERED` → `TapDispatchActivity` | `NfcDispatcher.java:716`（`tryTech`）+ 我们的 manifest `:127-141` | 否 | **是**（`:629-635`） | 是（`EXTRA_TAG`） | 我们当前用的路 |
| 6 | `NDEF_DISCOVERED` / `TAG_DISCOVERED` | `NfcDispatcher.java:706`（`tryNdef`）/ `:720`（`setTagIntent`） | 是 / — | 是 | 是 | 我们没用 |
| 7 | `onNdefMessage(Tag, NdefMessage, Consumer)` | `NfcDispatcher.java:884-886` → `:1414-1423` | **是（硬要求）** | 否 | **是（Tag + NDEF）** | 唯一能拿数据的 OEM 钩子，见 §3.2 |
| 8 | 前台派发 `tryOverrides` | `NfcDispatcher.java:653-663` / `:803-850` | 否 | 否 | 是 | 需 app 在前台，见 §3.4 |

### 1.2 `NfcDispatcher.dispatchTag()` 的完整路由顺序（`NfcDispatcher.java:606-734`）

```
:629-635  锁屏闸门：SCREEN_STATE_ON_LOCKED 且非解锁标签 → DISPATCH_FAIL（后续全部跳过）
:653      tryOverrides(...)            ← 前台派发/前台 reader
:665      tryPeripheralHandover(...)   ← 蓝牙/多设备交接
:670      NfcWifiProtectedSetup.tryNfcWifiSetup(...)
:688      provisioningOnly 闸门
:703      tryOemPackage(tag, message)  ← ★ OEM 钩子（要求 message != null）
:706      tryNdef(dispatch, message)
:710-712  if (screenUnlocked) return DISPATCH_UNLOCK
:716      tryTech(dispatch, tag)       ← ★ 我们现在的 TECH_DISCOVERED 走这里
:720      dispatch.setTagIntent(); dispatch.tryStartActivity()
:734      return DISPATCH_FAIL
```

### 1.3 `NfcOemExtension.Callback` 完整回调清单（问题 A 的答案）

来源：`packages/modules/Nfc/framework/java/android/nfc/NfcOemExtension.java:290-524`（app 可见接口，**28 个方法**）；AIDL 对端 `INfcOemExtensionCallback.aidl:30-59`（**28 个方法**）；映射实现 `NfcOemExtension.java:933-1214`。**早期读到的 9 个不完整**——完整清单如下（之前漏了 `onBootFinished`/`onEnableFinished`/`onDisableFinished`/`onEeUpdated`/`onNdefMessage`/`onGetOemAppSearchIntent`/`onExtractOemPackages`/`onLaunchHceAppChooserActivity`/`onLaunchHceTapAgainDialog`/`onRoutingTableFull`/`onLogEventNotified`/`onRfFieldDetected`/`onCardEmulationActivated`/`onRfDiscoveryStarted`/`onEeListenActivated`）：

| # | 回调 | 定义 | 触发时机（file:line） | 插件能做什么 / 能否拉起我们的服务 |
|---|---|---|---|---|
| 1 | `onTagConnected(boolean)` | `:297` | `NfcService.java:5480`(true)、`:5473`/`:5531`/`:6174`(false)，定义 `:6240-6243` | **可拉起服务**（最早，标签激活即到） |
| 2 | `onStateUpdated(int)` | `:303` | `NfcService.java:2165`（adapter 状态变更后） | 可拉起（NFC 开关状态） |
| 3 | `onApplyRouting(Consumer<Boolean>)` | `:311` | `NfcService.java:1103`（applyRouting 前询问） | 返回 true 跳过 applyRouting（**危险**） |
| 4 | `onNdefRead(Consumer<Boolean>)` | `:319` | `NfcService.java:1100` / `:5481-5486` | 返回 true 跳过 NDEF 读取（可省时间） |
| 5 | `onEnableRequested(Consumer<Boolean>)` | `:327` | `NfcService.java:1091`（`onEnable`） | 允许/拒绝开 NFC |
| 6 | `onDisableRequested(Consumer<Boolean>)` | `:335` | `NfcService.java:1094`（`onDisable`） | 允许/拒绝关 NFC |
| 7 | `onBootStarted()` | `:340` | `NfcService.java:1867`（`onOemPreExecute`, TASK_BOOT） | 可拉起服务（开机早期） |
| 8 | `onEnableStarted()` | `:345` | `NfcService.java:1868`（TASK_ENABLE） | 同上 |
| 9 | `onDisableStarted()` | `:350` | `NfcService.java:1869`（TASK_DISABLE） | 同上 |
| 10 | `onBootFinished(int)` | `:356` | `NfcService.java:1850`（onPostExecute, TASK_BOOT） | **可拉起服务（推荐）** |
| 11 | `onEnableFinished(int)` | `:362` | `NfcService.java:1853` | 同上 |
| 12 | `onDisableFinished(int)` | `:368` | `NfcService.java:1856` | 同上 |
| 13 | **`onTagDispatch(Consumer<Boolean>)`** | `:376` | **`NfcService.java:6120-6125`** | **★ 可拉起服务；不碰 NDEF、不受锁屏限制、无 Tag；返回 true 会吞掉派发** |
| 14 | `onRoutingChanged(Consumer<Boolean>)` | `:385` | `NfcService.java:1106` | 跳过 routing commit |
| 15 | **`onHceEventReceived(int)`** | `:394` | `CardEmulationManager.java:304`(HCE_ACTIVATE)、`:331`(HCE_DATA_TRANSFERRED)、`:361`(HCE_DEACTIVATE) | **★ 卡模拟侧可拉起服务（最早，HCE 一激活）** |
| 16 | `onReaderOptionChanged(boolean)` | `:401` | `NfcService.java:3406`（ST，`enableReaderOption` 后） | 感知全局读卡开关变化 |
| 17 | **`onCardEmulationActivated(boolean)`** | `:411` | `NfcService.java:609`、`:651`、`:3989-3993` | **★ 卡模拟侧可拉起服务** |
| 18 | **`onRfFieldDetected(boolean)`** | `:419` | `NfcService.java:692-693`（`onRemoteFieldActivated`，方法定义 `:687`）、`:724-725`（`onRemoteFieldDeactivated`，定义 `:721`） | **★ 卡模拟侧最早信号（射频场一开就报，早于 APDU）** |
| 19 | `onRfDiscoveryStarted(boolean)` | `:427` | `NfcService.java:910-911` | 感知发现启停（ST 用它做卡片切换状态机） |
| 20 | `onEeListenActivated(boolean)` | `:434` | `NfcService.java:896-897` | 感知 eSE/SIM 监听 |
| 21 | `onEeUpdated()` | `:443` | `NfcService.java:794-795` | 感知 applet 增删 |
| 22 | `onGetOemAppSearchIntent(List<String>, Consumer<Intent>)` | `:456` | `NfcDispatcher.java:1051-1057` | 只对 NDEF 里的 OEM 包名有效 |
| 23 | **`onNdefMessage(Tag, NdefMessage, Consumer<Boolean>)`** | `:470` | `NfcDispatcher.java:1423`（经 `tryOemPackage`，`:884-886`） | **★ 唯一能拿到 Tag + NDEF 的钩子；`message == null` 时根本不会被调用** |
| 24 | `onLaunchHceAppChooserActivity(...)` | `:484` | `CardEmulationManager` 多 app 冲突时 | OEM 自己弹选择框（ST 就转给 `AppChooserActivity`，`StNfcOemExtension.java:471-492`） |
| 25 | `onLaunchHceTapAgainDialog(ApduServiceInfo, String)` | `:497` | 需解锁才能交易时 | OEM 自己弹"再碰一次" |
| 26 | `onRoutingTableFull()` | `:504` | routing table 满 | 弹提示 |
| 27 | `onLogEventNotified(OemLogItems)` | `:510` | `NfcService.java:6893` | 收 NFC 日志 |
| 28 | `onExtractOemPackages(NdefMessage, Consumer<List<String>>)` | `:522` | `NfcDispatcher.java:1442-1448` | 从 NDEF 抽包名 |

**超时语义（重要）**：`onApplyRouting` / `onNdefRead` / `onEnable` / `onDisable` / `onTagDispatch` / `onRoutingChanged` 这 6 个是 **latch 等待式**——`NfcService.java:357` `WAIT_FOR_OEM_CALLBACK_TIMEOUT_MS = 3000`，`NfcService.java:1113` / `NfcDispatcher.java:1454,1485` `latch.await(...)`。**如果我们的回调 3 秒不返回，系统按 false 继续，但这 3 秒里 NFC 派发线程是阻塞的。**（**源码已确认**）

### 1.4 注册机制：`mNfcOemExtensionCallback` 怎么被设置（问题 C 的答案）

```
app 侧：NfcAdapter.getNfcOemExtension()            framework/.../NfcAdapter.java:2921-2931
        └─ 返回 mNfcOemExtension                    framework/.../NfcAdapter.java:947  （构造时无条件 new）
NfcOemExtension.registerCallback(executor, cb)     NfcOemExtension.java:578-601
        └─ 首次注册时 callService → sService.registerOemExtensionCallback(mOemNfcExtensionCallback)
                                                    NfcOemExtension.java:594
NFC 侧：INfcAdapter.registerOemExtensionCallback()  NfcService.java:3734-3751
        ├─ NfcPermissions.enforceAdminPermissions() NfcPermissions.java:85-87（= WRITE_SECURE_SETTINGS）
        ├─ mNfcOemExtensionCallback = callbacks     NfcService.java:3741  ← 单槽，last-writer-wins
        ├─ updateNfCState()                         :3744
        ├─ mCardEmulationManager.setOemExtension()  :3745-3747
        └─ mNfcDispatcher.setOemExtension()         :3748-3750
```

**没有 flag 闸门、没有白名单、没有 `enable_oem_extension` 检查。** 唯一门槛是 `WRITE_SECURE_SETTINGS` + 能链接到 `android.nfc.NfcOemExtension` 类。

**厂商 ST 实现的位置与加载方式：**
- 实现类：`vendor/st/nfc/packages/modules/Nfc/StExtension/service/com/st/android/nfc_extensions/StNfcOemExtension.java`（`StNfcOemExtensionCallback` 定义在 `:90-542`）
- 注册入口：`StNfcOemExtension.java:867-878`（`doRegister()` → `mNfcAdapter.getNfcOemExtension()` → `registerCallback(...)`）
- 但这个入口**不是由 NFC 服务主动拉起的**——它靠 `StNfcExtensionReceiver` 收 `android.nfc.action.OEM_EXTENSION_INIT` 广播触发（`StExtension/service/AndroidManifest.xml` 的 `<receiver>`），而该广播只在 `enable_oem_extension == true` 时由 `NfcService.executeTaskBoot()` 发出（`:1444-1452`）。**本机该值为 false，所以整条链从未启动。**
- 注意：`StNfcOemExtension` 被编进 `java_library "com.st.android.nfc_extensions"`（`StExtension/Android.bp:19-46`），作为**平台库**给 `PrizeNFCAccessCard` 等 app 用（`pri_app/apk_oversea/PrizeNFCAccessCard/Android.mk:29` `LOCAL_STATIC_JAVA_LIBRARIES += com.st.android.nfc_extensions`）；`StExtension/service/Android.bp` 里那个 `android_app` **整段被注释掉了**。也就是说 ST 的 callback 实现**只在我们 app 进程里跑**，从未在 NFC 服务里注册。

---

## 2. 当前设计到底依赖什么（现状盘点）

### 2.1 reader 侧（我们读对方）——已经是系统驱动

`app/src/main/AndroidManifest.xml:127-141`：`TapDispatchActivity` 声明 `android.nfc.action.TECH_DISCOVERED` + `res/xml/tech_list.xml`（`android.nfc.tech.IsoDep`）。
`TapDispatchActivity.java:12-22`：`onCreate` 里取出 `EXTRA_TAG` → `startService(TapService)` → `finish()`。

**这是系统派发的**：`NfcService` 发现标签 → `NfcDispatcher.tryTech()`（`NfcDispatcher.java:716`）→ `startActivityAsUser`。**我们没有跑任何 reader 循环。**

`MainActivity.java:375` 的 `enableReaderMode(...)` 只在 `startReader()` 里，属于**联调页的调试路径**，不是主流程。

### 2.2 card 侧（对方读我们）——也是系统驱动

`AndroidManifest.xml:149-159`：`HceService` + `BIND_NFC_SERVICE` + `res/xml/apdu_service.xml`（AID `F05052494E4643`，`requireDeviceUnlock="false"`）。
**系统在路由到这个 AID 时会主动 bind 我们的服务**：`HostEmulationManager.java:1253-1260` `mContext.bindServiceAsUser(aidIntent, connection, BIND_AUTO_CREATE | BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS, UserHandle.of(userId))`。

**实机已测**（`dumpsys nfc`）：
```
ComponentInfo{com.pri.nfctouchdemo/com.pri.nfctouchdemo.nfc.HceService}
    Static AID groups: Category: other(enabled: true)  AID: F05052494E4643
    "*DEFAULT* ApduService: ComponentInfo{com.pri.nfctouchdemo/...HceService}, UID: 1000
    TagAppPreference: userId=0  pkg: com.pri.nfctouchdemo : true
Routing table: Routed to 0x0: "F05052494E4643" ...
```
我们的 AID 已进路由表、且是该 AID 的**默认**服务（不会被 `AppChooserActivity` 打断）。

### 2.3 那"我们自己控制"的到底是什么？

只剩**业务载荷**：`Bus.java:33-39` 定义 `SELECT_AID_APDU` + `SW_OK(0x9000)` + `SW_INS_NOT_SUPPORTED(0x6D00)`；`:106` 组 `80 10 00 00 <Lc> <payload> 00` 交换帧；`:144` `selfPayload()` 生成 SSID/密码/蓝牙名。

**这部分系统不可能替我们做**：`NfcOemExtension` 的 28 个回调里，唯一携带数据的 `onNdefMessage`（#23）**硬要求 `message != null`**（`NfcDispatcher.java:884-886`），而我们的握手是裸 ISO-DEP、无 NDEF。

---

## 3. 逐条方案

### 3.1 方案 A（推荐）：自己注册 `NfcOemExtension`，用 `onTagDispatch` + `onHceEventReceived` 做**进程内早触发**

**机制**
- 在 `TapService`（常驻）里 `NfcAdapter.getDefaultAdapter(this).getNfcOemExtension().registerCallback(executor, cb)`。
- `cb.onTagDispatch(consumer)`：**读卡侧**——"有标签被派发了"。立刻 `consumer.accept(false)`（不吞派发，让 `TapDispatchActivity` 照常工作），同时直接拉起 `OverlayService` / 唤醒 `TapService`。
- `cb.onHceEventReceived(HCE_ACTIVATE)` + `onCardEmulationActivated(true)` / `onRfFieldDetected(true)`：**卡模拟侧**——"对方读卡器来了"。同样直接拉起。

**file:line 证据**
- `NfcOemExtension.java:578-601`（registerCallback）、`:594`（跨进程注册）
- `NfcService.java:3734-3751`（服务侧接线，门槛仅 `WRITE_SECURE_SETTINGS`）
- `NfcService.java:6120-6125`（`onTagDispatch` 在 `dispatchTag` 之前，绕开锁屏闸门）
- `NfcDispatcher.java:629-635`（锁屏闸门只作用于 `dispatchTag` 之后的路径）
- `CardEmulationManager.java:304`（HCE_ACTIVATE）
- `NfcService.java:687-693`（`onRemoteFieldActivated` → `onRfFieldDetected(true)`）
- `NfcAdapter.java:947`（`mNfcOemExtension` 无条件初始化）
- `packages/modules/Nfc/apex/Android.bp:104-106`（`framework-nfc` 是 bootclasspath fragment）
- 实机：`/apex/com.android.nfcservices/javalib/framework-nfc.jar` 含 `Landroid/nfc/NfcOemExtension;`（**实机已测**，拉取后用 `strings` 验证）

**要改哪里**
- **app 侧**：`TapService` 增加注册 + 回调；`systemlibs/framework-header.jar` 需补上 `android/nfc/NfcOemExtension*.class`（见 §4）。
- **ROM 侧：不需要改。**

**能否不依赖我们自己的 HCE-reader？**
- **触发层：能。** 触发完全由系统回调驱动，我们不再需要在 APDU 响应路径里 `startService`（虽然仍保留更稳妥）。
- **数据层：不能。** `onTagDispatch` 无 Tag、`onNdefMessage` 要 NDEF → 载荷仍要走现有 APDU 交换。

**代价**
- 单槽回调：我们是唯一的 holder，**last-writer-wins**（`NfcService.java:3738-3741`）。将来若有别的 OEM 扩展注册（例如某 ROM 版本把 `enable_oem_extension` 打开），会互相顶掉。
- 3000ms latch 阻塞：`onTagDispatch` 是 latch 式，若我们处理慢会**拖慢 NFC 服务派发线程**（`NfcService.java:357,1113`）。→ **回调体必须极轻（只 `startService`/`sendBroadcast`），绝不阻塞。**
- 进程被回收后回调丢失（`registerCallback` 的注册随进程消亡；`NfcOemExtension.unregisterCallback` 的 javadoc `NfcOemExtension.java:620` 明确 "Callbacks are automatically unregistered when an application process goes away"）。→ 需要**开机/启动时重新注册**，且要有"没注册上"的退化路径（保持现有 HCE → `TapService` 兜底）。

**风险**：中。最大风险是"我们成了唯一 OEM 扩展，回调里抛异常/变慢会影响整个 NFC 派发"。建议所有回调体 try/catch 包死 + 立即返回。

**评级：★★★★★** —— 不需要改 ROM，`WRITE_SECURE_SETTINGS` 已到手，是最快见效的路。

---

### 3.2 方案 B（数据层彻底交出去）：把握手从裸 ISO-DEP 改成 **Type-4 NDEF**，让对方的**系统**自动读

**机制**
- 我们的 `HceService` 除了自研 AID，再接管 NFC Forum 的 Type-4 NDEF AID（`D2760000850101` / `D2760000850100`），按 T4T 规范提供 CC 文件 + NDEF 文件。
- 对方手机的 **`NfcService` 会自动做 NDEF 读取**（无需对方 app 参与），拿到 NDEF 后走 `NDEF_DISCOVERED` / `TECH_DISCOVERED` 系统派发；即使对方 app 没写 reader 循环也能拿到载荷。
- 我们这边还可以用 `onNdefMessage(tag, message, consumer)` 把载荷直接在 NFC 服务进程里截获。

**file:line 证据**
- `cardemulation/HostEmulationManager.java:114-115`：`NDEF_V1_AID = "D2760000850100"`、`NDEF_V2_AID = "D2760000850101"`
- `cardemulation/RegisteredServicesCache.java:121`：`DEFAULT_T4T_NFCEE_AID = "D2760000850101"`
- `cardemulation/CardEmulationManager.java:719-748`：`isSkipAid()` —— 框架会**特殊对待 NDEF AID**（不触发 userActivity、不计入普通 AID 路由）
- `NfcService.java:5510-5520`（`tag.findAndReadNdef()` 在 `:4453`）—— **读卡侧系统自动读 NDEF**，不依赖 app
- `NfcDispatcher.java:884-886` + `:1414-1423`：`onNdefMessage` 是唯一带数据的钩子，但 `message == null` 直接 return false

**要改哪里**：app 侧 `HceService` + `res/xml/apdu_service.xml`（加 AID）+ 传输层协议改造。ROM 侧不用改。

**能否不依赖我们自己的 HCE-reader？**
- **能，而且连对方 app 的 reader 都不需要。** 这是唯一能让"数据"也由系统读取的路。
- 但注意：**我们这侧仍然要模拟卡**（提供 T4T 文件系统）——模拟这一层是不可避免的，因为手机之间就是"一个模拟卡、一个读"，只要不引入第二个物理设备/网络通道就不可能绕过。

**代价**：高。要重写 `HceService` 的 APDU 处理（T4T 的 SELECT/READ BINARY 状态机 + CC/NDEF 文件），把 `Bus.selfPayload()` 改写成 NDEF 记录。NDEF 单条记录有大小限制（短记录 ~255B，长记录可到 64KB 但需要分块 `READ BINARY`），实测载荷（SSID+密码+蓝牙名）通常 <200B，**放得下**。

**风险**：中。`dumpsys nfc` 的 Event Log 里能看到 `ce_unroutable_aid { aid: "D2760000850101" }`（**实机已测**，10-15 的日志）——说明对方**确实**在做 NDEF 探测，而我们没接。风险点是接入后要与其它 NDEF 相关行为共存（`TagAppPreference`、`AppChooserActivity` 等）。

**评级：★★★★☆** —— 战略上最"正统"（正是 NFC 设计的用法），但工作量最大。

---

### 3.3 方案 C：`RF_FIELD_ON_DETECTED` 广播（最省事，但语义最弱）

**机制**：`NfcService` 在射频场出现/消失时发 `com.android.nfc_extras.action.RF_FIELD_ON_DETECTED` / `..._OFF_...`。我们只需在 manifest 里注册一个 `BroadcastReceiver` + 声明 `android.permission.NFC_TRANSACTION_EVENT`。

**file:line 证据**
- `NfcService.java:320-323`：`ACTION_RF_FIELD_ON_DETECTED = "com.android.nfc_extras.action.RF_FIELD_ON_DETECTED"`
- `NfcService.java:5625-5626` / `:5640-5641`：`sendNfcPermissionProtectedBroadcast(fieldOnIntent / fieldOffIntent)`
- `NfcService.java:5951-5960`：`sendNfcPermissionProtectedBroadcast()` → 发给 `mNfcEventInstalledPackages`；`:5955` `intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)` ← **能冷启被停掉的 app**
- `NfcService.java:1682-1685`：收件人 = **持有 `android.permission.NFC_TRANSACTION_EVENT` 的包**
- `frameworks/base/core/res/AndroidManifest.xml:2571-2574`：`NFC_TRANSACTION_EVENT` 的 **`protectionLevel="normal"`** ← 任何 app 声明即得（**实机已测**：`dumpsys package permissions` 显示 `uid=1000 ... prot=normal`）

**要改哪里**：纯 app 侧（manifest 加权限 + receiver）。ROM 侧不用改。

**能否不依赖我们自己的 HCE-reader？** 触发层能（完全不碰 HCE/reader）；数据层不能。

**代价**：极低。**风险**：**语义弱**——任何射频场都会触发（别人的标签、别的手机的 reader 模式、甚至公交卡）。只能当"有人靠近"的预热信号，不能当"我的对端来了"。而且它**只能拉起我们的服务，不能区分角色**。

**评级：★★★☆☆** —— 当"预热/抢跑"用很划算（提前把 `OverlayService`、Wi-Fi 热点、payload 缓存准备好），但不能单独作为触发依据。

---

### 3.4 方案 D：前台派发 `setForegroundDispatch`（问题 E 的一个补充，但对我们不适用）

**机制**：`NfcDispatcher.setForegroundDispatch(intent, filters, techLists)`（`NfcDispatcher.java:189-210`）把 `mOverrideIntent/mOverrideFilters/mOverrideTechLists` 换成我们的 `PendingIntent`，`dispatchTag()` 第一步 `tryOverrides()`（`:653-663`，实现在 `:803-850`）就派发给我们。

**file:line 证据**：`NfcDispatcher.java:189-210`（setter）、`:653-663`（调用点）、`:803-850`（匹配逻辑）。

**能否不依赖我们自己的 HCE-reader？** 触发层能（且优先级**高于** OEM 钩子和所有 manifest 派发）。

**代价/风险**：**要求我们的 Activity 在 `mForegroundUid` 处于前台**（`:201-209` 会 `registerUidToBackgroundCallback`，一旦退到后台就失效）。我们的场景是"用户在相册里选图然后碰"，**前台的是相册不是我们**，所以此路基本不可用。**评级：★☆☆☆☆**

---

### 3.5 方案 E（ROM 侧）：在 NFC 栈里内置我们的触发（用户问的"改 ROM"）

**机制**：把 §3.1 的逻辑直接写进 `Nfc_st`：在 `NfcOemExtension.NfcOemExtensionCallback` 或 `NfcService.dispatchTagEndpoint()` 里，发现标签就 `startService`/`sendBroadcast` 到 `com.pri.nfctouchdemo`。

**参考先例（**实机/源码已确认**，但注意在**别的**项目树里）**：
- `/dfb/zhuyang/share/nfc_test/pri_project/android/project/custom/Hammer/vendor/mediatek/proprietary/packages/apps/ST-Nfc/src/com/android/nfc/NfcService.java:2211-2213` —— 厂商 NFC 服务 **`setPackage("com.pri.nfcaccesscard")` 定向广播** `android.prize.nfc.action.ADAPTER_STATE_CHANGED`。这就是"ROM 侧主动把 NFC 事件推给自研 app"的现成范本。
  - ⚠️ 顺带发现一个**疑似 BUG**：发送端 action 是 `android.prize.nfc.action.ADAPTER_STATE_CHANGED`，而 `PrizeNFCAccessCard` 的接收端（`app/src/main/AndroidManifest.xml:142` 与 `receiver/NfcStatusReceiver.java:23`）用的是 `pri.intent.nfc.action.ADAPTER_STATE_CHANGED`，**两个字符串不一致**，该广播实际收不到（除非实际部署的 ST-Nfc 与此树不同）。**未验证**。

**要改哪里**：ROM 侧（`vendor/st/nfc/.../NfcNci/src/com/android/nfc/*` 或 `public/` 覆盖树），并逐仓提交。**app 侧无需服务常驻。**

**能否不依赖我们自己的 HCE-reader？** 触发层**能，且最彻底**（连 app 都不需要先起来，ROM 直接拉起）。

**代价**：**需要改 ROM、重新编 ROM、走 `public/` 覆盖树提交流程**（见全局 `MTK_platform.md` §2.4：覆盖平台已有文件要先单独提交一笔"上传源代码"作基线）。**风险**：改 `NfcService` 属于动系统核心组件，回归面大；且此设备的 NFC 是 ST vendor 定制栈（`Nfc_st` overrides `Nfc`/`NfcNci`），改上游 AOSP 路径**不生效**，必须改 `vendor/st/nfc/...`。

**评级：★★★☆☆** —— 产品化最干净，但本次不可行（本机不能编 ROM）。

---

## 4. 最小改动集（问题 F）

### 4.1 推荐落地：方案 A（纯 app 侧，**不需要改 ROM**）

| # | 侧 | 文件 | 改动 |
|---|---|---|---|
| 1 | **app** | `nfc_tap_demo/systemlibs/framework-header.jar`（或新增一个 compile-only stub 源集） | **补 `android/nfc/NfcOemExtension*.class` 与 `android/nfc/INfcOemExtensionCallback*.class`**。现成来源：`/dfb/zhuyang/share/nfc_test/alps/out_sys/soong/.intermediates/packages/modules/Nfc/framework/framework-nfc.impl/android_common/jarjar/turbine/framework-nfc.jar`（**已确认含** `NfcOemExtension.class`、`NfcOemExtension$Callback.class` 等）。当前 `systemlibs/framework-header.jar` **不含**（**实机已测**：`unzip -l` 无 `*Oem*`）。 |
| 2 | **app** | `app/src/main/AndroidManifest.xml` | 显式声明 `<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />`。**当前没声明**，只是因为 `sharedUserId="android.uid.system"`（`:17`）继承了 uid 的授权才拿到（**实机已测**：`granted=true`）。显式声明更稳。 |
| 3 | **app** | `nfc/TapService.java` | `onCreate`（+ 开机/`BOOT_COMPLETED`）里注册 OEM 回调；实现 `onTagDispatch` / `onHceEventReceived` / `onCardEmulationActivated` / `onRfFieldDetected` / `onBootFinished`。**回调体内只做 `startForegroundService(OverlayService...)` + 立即 `accept(false)`，全部 try/catch 包死，绝不阻塞**（防 3000ms latch 拖慢 NFC）。 |
| 4 | **app** | `nfc/TapService.java` | 加**退化路径**：注册失败 / 无 `NfcOemExtension` 类 / 权限被拒 → 保持现有 `HceService`→`TapService` 通路不变（现有代码已具备，属兜底）。 |
| 5 | **ROM** | — | **无需改动。** |

**是否去掉对 `TapService` 常驻的依赖？**
- **没有完全去掉，但把风险降到接近零。**
- 真正的变化是：**触发的"第一跳"从"我们被动等系统 bind HCE / 派发 Activity"变成"系统主动回调我们"**——同一件事的两面，但后者：
  - 不受锁屏闸门影响（`NfcDispatcher.java:629-635` vs `NfcService.java:6120-6125`）；
  - 不用等 APDU 往返（`onRfFieldDetected` / `HCE_ACTIVATE` 比第一个 APDU 更早）；
  - 不再需要在 APDU 响应路径里做 `startService`（`HceService.java:71-87` 现在丢后台线程就是为了不拖过超时，这本身是个脆弱点）。
- **只要 `TapService` 进程在，回调就在**；进程不在时 `onTagDispatch` 也收不到，仍需现有 HCE 通路兜底。所以是"**能力叠加**"而不是"替换"。

### 4.2 若允许改 ROM（产品化终态）

| 侧 | 文件 | 改动 |
|---|---|---|
| ROM | `vendor/st/nfc/packages/modules/Nfc/NfcNci/src/com/android/nfc/NfcService.java`（`dispatchTagEndpoint`，`:6120` 附近）或 `StNfcOemExtension.java` | 发现标签/收到 HCE 事件时**定向启动** `com.pri.nfctouchdemo`（`Context.startServiceAsUser` 或 `setPackage()` 定向广播，照抄 `project/custom/Hammer/.../ST-Nfc/.../NfcService.java:2211-2213` 的写法） |
| ROM | 同上，逐仓提交 | 按 `MTK_platform.md` §2.4：改 `public/` 覆盖树，覆盖平台已有文件先单独提交一笔"上传源代码"作基线 |
| app | — | 只需一个 exported receiver/service 接招呼，**`TapService` 可以非常驻** |

---

## 5. 现实检验：当前设计真的会漏碰触吗（问题 D）

**结论：`TapService` 被杀导致碰触失效，基本是理论风险，不是实际漏洞。** 依据：

**(a) `HceService` 由 NFC 栈 `BIND_AUTO_CREATE` 绑定 → 进程会被拉起**（**源码已确认**）
`HostEmulationManager.java:1253-1260`：`bindServiceAsUser(..., Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS, ...)`。`BIND_AUTO_CREATE` 意味着进程死了会被重新创建。
`HceService.java:20-22` 的注释本身就是团队的实测记录：*"应用被系统回收后，碰触只会让 NFC 框架把 HCE 拉起来，此时服务未必在跑"* —— 即**进程会被拉起，需要补的只是"服务"**，而 `postPeerToService()`（`:71-87`）的 `startService` 正好补上。

**(b) reader 侧本来就是系统 `startActivity`**（**源码已确认**）
`NfcDispatcher.java:716`（`tryTech`）→ `:720`（`tryActivityOrLaunchAppStore`/`tryStartActivity`）→ `context.startActivityAsUser(rootIntent, UserHandle.CURRENT)`。系统主动拉 Activity，进程同样会被创建。

**(c) 我们的进程优先级极高，LMK 很难杀**（**实机已测**）
```
dumpsys activity processes:
  Proc # 7: vis F/S/FGS LCMNFUAT t:0 13656:com.pri.nfctouchdemo/1000 (service)
  hasStartedServices=true   mHasForegroundServices=true
  oom adj: max=1001 curRaw=100 setRaw=100 cur=100
dumpsys activity services:
  mAllowStart_noBinding=PROC_STATE_PERSISTENT
  mAllowWiu_noBinding=PROC_STATE_PERSISTENT
  code:PROC_STATE_PERSISTENT   (ActivityManager 允许其后台启动 FGS 的理由)
```
原因：`android:sharedUserId="android.uid.system"`（`AndroidManifest.xml:17` → uid 1000）+ `OverlayService` 是前台服务（`AndroidManifest.xml:166-173`）。`adj cur=100` 属于可见级，配合 `PROC_STATE_PERSISTENT` 授权，被 LMK 回收的概率极低。

**(d) 实机 logcat 显示整条链路此刻正在正常工作**（**实机已测**，见 §6.1）。

**那么真正会漏碰触的是哪些情况？（这些才是实际漏洞）**

| 漏洞 | 机制 | file:line | 严重度 |
|---|---|---|---|
| **锁屏时碰触** | `dispatchTag()` 在 `SCREEN_STATE_ON_LOCKED` 且非解锁标签时**直接 `DISPATCH_FAIL`**，`TECH_DISCOVERED` 完全不派发 → `TapDispatchActivity` 永不启动 | `NfcDispatcher.java:629-635` | **高**（这是唯一的真漏洞） |
| HCE 侧锁屏 | 我们设了 `requireDeviceUnlock="false"`（`res/xml/apdu_service.xml`），**HCE 侧锁屏可用**；所以锁屏时"对方读我们"仍能通，是"我们读对方"不行 | — | — |
| NFC 被关 / 厂商读卡开关被关 | `mIsReaderOptionEnabled=false` 且无 readerMode 时，多处直接跳过轮询（`NfcService.java:4944,4971` 等 12 处 `if (!isReaderOptionEnabled())`） | `NfcService.java:4097,4136,...,4944,4971` | 中 |
| 用户把本 app 的"标签应用"偏好关掉 | `checkPrefList()` 会过滤掉派发目标（`NfcDispatcher.java:435,467,532`）；靠 `setTagIntentAppPreferenceForUser` 的默认值 `true`（`:1109-1126`）兜底 | `NfcDispatcher.java:435` 等 | 低（**实机已测**：`TagAppPreference: pkg: com.pri.nfctouchdemo : true`） |
| 500ms 级响应超时 | `HceService.java:14-18` 注释记录的实测坑（`startService` 拖过对方 ISO-DEP 超时）——**这是"响应慢"不是"漏碰触"** | `HceService.java:71-87` | 中（已有对策） |

**方案 A 的 `onTagDispatch` 恰好能补上"锁屏"这一条**（因为它在 `dispatchTag()` 之前，见 §3.1）。

---

## 6. 需真机验证的部分（诚实清单）

> 本节所有条目我**没有**在真机上跑过（未构建/安装任何 APK——本任务的规则与全局约定都要求不主动编译），属于**未验证**。

1. **我们的 app 能否成功注册为 OEM 扩展**（**最关键的一条，必须实测**）
   - 静态证据已完整（类在 bootclasspath / 权限已授予 / 槽位空闲），但"`sService.registerOemExtensionCallback()` 实际返回成功"没有实测过。
   - 建议做法：在 `TapService` 里加一段 `//pri added by zhuyang,...` 的探针，注册成功/失败各打一条 **`Log.i`**（本 ROM 的 logcat 默认 INFO，`Log.d` 看不见——**这一条已由任务方确认**）。
   - 判据：`logcat | grep -E "NfcOemExtension|registerOemExtensionCallback"` 有我们自己的行；且 `dumpsys nfc` 能看到回调已挂上。
2. **`onTagDispatch` 是否真的在锁屏状态下也触发**
   - 静态路径成立（`NfcService.java:6120` 在 `NfcDispatcher.java:629-635` 之前），但没实测过锁屏场景。
   - 判据：锁屏状态下碰两台机器，看我们回调的日志是否出现。
3. **`onRfFieldDetected(true)` 是否在"我们被读"时触发**（而不是只在 reader 侧）
   - `NfcService.onRemoteFieldActivated()`（`:681-690`）语义上是"远端射频场"，理论上覆盖被读场景，但**没实测**。
4. **3000ms latch 对碰触体验的实际影响**
   - 若回调体不阻塞，理论上无影响；需实测确认回调线程模型（`NfcOemExtension.registerCallback` 收一个 `Executor`，我们给单线程 executor 即可）。
5. **`onHceEventReceived` 与 `onCardEmulationActivated` 的相对先后与抖动**
   - 决定用哪个作为卡模拟侧的触发信号，需实测时间戳。
6. **多 holder 冲突**
   - 若某 ROM 版本把 `enable_oem_extension` 打开（ST 扩展会注册），两者会互相顶掉（`NfcService.java:3738-3741` 单槽）。需在目标 ROM 上确认该值（`device_config get nfc enable_oem_extension` + `getprop`/overlay 实际生效值）。
7. **Type-4 NDEF 路（方案 B）的可行性**
   - T4T 文件系统实现 + NDEF 载荷大小实测（**推断** <200B 的现有 payload 放得下，未实测）。
   - `dumpsys nfc` 的 Event Log 里已经出现 `ce_unroutable_aid { aid: "D2760000850101" }`（**实机已测**，10-15 日志），说明对端确实做 NDEF 探测——值得实测接管后能否被自动读取。

---

## 7. 明确做不到的部分

1. **不可能"完全不模拟卡、不交换数据"就让两台手机互相识别。**
   手机之间只有"卡+读卡器"这一个物理通道。**"模拟"这一层物理上无法移除**——除非引入第二条通道（网络/蓝牙/BLE 发现）来替代 NFC 的载荷交换，但那就不是"NFC 触发"了。
   → 用户的诉求「不用依靠我们自己去控制 nfc 的读、模拟」，**"读"这一半可以完全交出去（现状已经是交出去的），"模拟"这一半做不到**。

2. **不可能让系统替我们解析私有 APDU 载荷。**
   `onTagDispatch` 不携带 `Tag`（`NfcOemExtension.java:376` 只有 `Consumer<Boolean>`），`onNdefMessage` 硬要求 NDEF（`NfcDispatcher.java:884-886`）。除非改成 NDEF（方案 B），否则**载荷一定要我们自己读**。

3. **`onTagDispatch` 返回 true 不能用来"接管"**——它只是跳过派发，**不给你 Tag**，跳过之后你什么都拿不到。只能返回 false 当纯观察者。

4. **本次任务内不能改 ROM。** 方案 E 需要重新编 ROM 并走 `public/` 覆盖树提交流程，本机做不到（且设备的 NFC 是 ST vendor 定制栈，改 AOSP 上游路径不生效）。

5. **不能只靠 `RF_FIELD_ON_DETECTED` 广播完成识别。** 它太泛（任何射频场都触发），只能做预热，不能做判据（§3.3）。

6. **前台派发 `setForegroundDispatch` 在本场景不可用**——因为碰触时前台是相册不是我们（§3.4）。

---

## 8. 附：一句话行动建议

**先做方案 A + 方案 C 的组合（纯 app 侧，零 ROM 改动）**：
- 用 `RF_FIELD_ON_DETECTED` 广播做"有人靠近"的**预热**（提前 `startForegroundService(OverlayService)`、预热 payload、备热点）；
- 用 `NfcOemExtension.onTagDispatch` / `onHceEventReceived` 做**正式触发**（且补上锁屏这个唯一的真漏洞）；
- 保留现有 `HceService` → `TapService` 通路做**兜底**，实现"能力叠加、不替换"。

**中期**再评估方案 B（Type-4 NDEF），那是唯一能把"数据读取"也交给系统、让整个交互更接近 NFC 原生语义的路。

---

## 附：引用文件清单（绝对路径）

**ROM 侧（`/dfb/zhuyang/share/nfc_test/alps`）**
- `packages/modules/Nfc/framework/java/android/nfc/NfcOemExtension.java`
- `packages/modules/Nfc/framework/java/android/nfc/INfcOemExtensionCallback.aidl`
- `packages/modules/Nfc/framework/java/android/nfc/NfcAdapter.java`
- `packages/modules/Nfc/NfcNci/res/values/config.xml`
- `packages/modules/Nfc/apex/Android.bp`
- `frameworks/base/core/res/AndroidManifest.xml`（`NFC_TRANSACTION_EVENT`）
- `vendor/st/nfc/packages/modules/Nfc/NfcNci/src/com/android/nfc/NfcService.java` ← **本机实际运行的实现**
- `vendor/st/nfc/packages/modules/Nfc/NfcNci/src/com/android/nfc/NfcDispatcher.java` ← 同上
- `vendor/st/nfc/packages/modules/Nfc/NfcNci/src/com/android/nfc/NfcPermissions.java`
- `vendor/st/nfc/packages/modules/Nfc/NfcNci/src/com/android/nfc/DeviceConfigFacade.java`
- `vendor/st/nfc/packages/modules/Nfc/NfcNci/src/com/android/nfc/cardemulation/CardEmulationManager.java`
- `vendor/st/nfc/packages/modules/Nfc/NfcNci/src/com/android/nfc/cardemulation/HostEmulationManager.java`
- `vendor/st/nfc/packages/modules/Nfc/NfcNci/Android.bp`（`Nfc_st`，`overrides: ["Nfc","NfcNci"]`）
- `vendor/st/nfc/packages/modules/Nfc/StExtension/service/com/st/android/nfc_extensions/StNfcOemExtension.java`
- `vendor/st/nfc/packages/modules/Nfc/StExtension/service/AndroidManifest.xml`
- `vendor/st/nfc/packages/modules/Nfc/StNfcOverlay/res/values/config.xml`

**我们的 app**
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/apdu_service.xml`、`res/xml/tech_list.xml`
- `app/src/main/java/com/pri/nfctouchdemo/nfc/HceService.java`
- `app/src/main/java/com/pri/nfctouchdemo/nfc/TapDispatchActivity.java`
- `app/src/main/java/com/pri/nfctouchdemo/nfc/Bus.java`
- `app/src/main/java/com/pri/nfctouchdemo/nfc/MainActivity.java`
- `app/src/main/java/com/pri/nfctouchdemo/nfc/TapService.java`
- `app/build.gradle.kts`、`systemlibs/framework-header.jar`
