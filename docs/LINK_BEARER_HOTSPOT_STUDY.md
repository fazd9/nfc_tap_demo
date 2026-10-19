# 碰一碰「热点为主」可行性 & 连接热点系统弹窗研究

> 研究日期：2026-10-19
> 设备：Armor X17（GQ3133TH2），Android 16 / SDK 36，userdebug
> 构建：`M30TA86A7.GQU.Ulefone.HB.YAANSNSD.20261013.V2.03`
> 指纹：`Ulefone/GQ3133TH2/GQ3133TH2:16/BP2A.250605.031.A3/1791878958:userdebug/release-keys`
> 结论基于源码 + 两台真机实验；实验用的最小平台签名 APK 在 `_Temp/wifitest/`，原始日志摘录在 `_Temp/hotspot_study_evidence.txt`。

---

## 1. 结论速览

1. **弹窗不是缺权限。** 我们的应用与 Settings 同为 `android.uid.system`（uid 1000），实测 `NETWORK_SETTINGS`、`NETWORK_SETUP_WIZARD`、`MANAGE_WIFI_NETWORK_SELECTION`、`NETWORK_CARRIER_PROVISIONING`、`CHANGE_WIFI_STATE` 全部为 GRANTED，但 `WifiNetworkSpecifier` 仍然弹「连接到设备」。`WifiNetworkFactory` 的审批判定里**根本没有「持有 NETWORK_SETTINGS 就免弹窗」这条分支**——这是 `WifiNetworkSpecifier` 的设计：首次请求某个 AP 必须由用户批准。
2. **弹窗能去掉，且不止一条路。** 首选 ROM 最小改动：在 PriSettings 的 `NetworkRequestDialogActivity` 里，对本应用的 SSID 前缀自动执行「连接」（复用系统已有的用户选择回调，约 10 行）。不改 ROM 的应用侧方案：平台签名应用可以走隐藏的 `WifiManager.connect(WifiConfiguration)`，**实测无弹窗、约 2.9 秒连上**，代价是把用户当前 Wi-Fi 切走、并把热点保存成普通网络（需 `forget()` 清理）。**给应用加 NETWORK_SETTINGS 权限无效**（ uid 1000 本来就有，实测仍弹）。
3. **热点与 Wi-Fi 可以共存。** 本机 `STA + AP Concurrency Supported: true`，`wifi_static_chip_info` 里有 `{1 STA + 1 AP}` 组合；实测 Wi-Fi 已连 Coosea-Office（5 GHz）时，LOHS 在 2.4 GHz 和 5 GHz 均能开启成功（`num ClientModeManagers:1 num SoftApManagers:1`）。`Xfer.java:575-579` 里「Wi-Fi 开着就跳过热点」的互斥判断**错误且过于保守**——这是当前热点路径“实际等于不存在”的直接原因。
4. **客户端加入热点会顶掉自己当前的 Wi-Fi。** 本机硬件支持 STA+STA，但 `config_wifiMultiStaLocalOnlyConcurrencyEnabled=false`（mt6768 不在 MTK dual_sta overlay 的机种列表里），所以 `WifiNetworkSpecifier` 的 local-only 连接走**主 STA**：实测加入热点后主连接从 Coosea-Office 切到 TShare-Probe；只有停止请求（或 forget 该网络）后系统才切回原网络。把该开关在本项目 RRO 里打开，可让客户端用第二条 STA 连接热点、保住用户自己的网络（硬件支持，需额外验证）。
5. **还有一个被忽略的 UX 阻断点：** 碰一碰动效层是 `TYPE_NAVIGATION_BAR_PANEL`（层级 25、整屏、focusable + touchable，见 `OverlayService.kt:859-930`），而 `NetworkRequestDialogActivity` 是普通 Activity 窗口。按窗口层级**悬浮窗会盖住并拦截系统弹窗**，用户根本点不到「连接」。这是两机测试里热点路径反复失败的又一可能原因（`[推断，待实测]`，但层级关系确定）。因此“保留弹窗”的方案必须同时处理悬浮窗遮挡，否则等于不可用。

---

## 2. 弹窗的来源与判定（源码证据）

### 2.1 完整调用链

```
应用: WifiNetworkSpecifier.Builder().setSsid(...).build()
      + ConnectivityManager.requestNetwork(req, cb, timeout)
   ↓
ConnectivityService → NetworkFactory 体系
   ↓
WifiNetworkFactory.acceptRequest()                  WifiNetworkFactory.java:775
   ↓
WifiNetworkFactory.needNetworkFor()                 WifiNetworkFactory.java:857
   - mSkipUserDialogue = false                       :912
   - triggerConnectIfUserApprovedMatchFound(...)     :932
       → 已批准 + 扫描到匹配 AP：直接连，return      :1952-1999
       → 未批准：返回 false
   - handleScanResults(cachedScanResults)            :938
   - if (!mSkipUserDialogue) startUi();              :942-943
   ↓
startUi(): Intent action=com.android.settings.wifi.action.NETWORK_REQUEST
          extra=APP_NAME / REQUEST_IS_FOR_SINGLE_NETWORK   WifiNetworkFactory.java:1748-1757
   ↓
com.android.settings/.wifi.NetworkRequestDialogActivity   PriSettings/AndroidManifest.xml:4479-4486
   - 单 SSID 请求 → NetworkRequestSingleSsidDialogFragment（「连接到设备」+ 取消/连接）
   ↓
用户点「连接」→ NetworkRequestDialogActivity.onClickConnectButton()
   → mUserSelectionCallback.select(mMatchedConfig)          :245-250
   ↓
WifiNetworkFactory.handleConnectToNetworkUserSelection()    :1212-1227
   - 真正发起连接                                        :1224
   - 把该 AP 写入 mUserApprovedAccessPointMap（记住批准） :1227, 1902-1943
```

### 2.2 什么情况下弹、什么情况下不弹

**不弹（直接连）的条件只有三条**，见 `WifiNetworkFactory.isAccessPointApprovedForActiveRequest()`（`:1877-1897`）：

| 条件 | 源码位置 | 说明 |
|---|---|---|
| CompanionDeviceManager 已关联该 BSSID | `:1859-1875` | 需要应用先通过 CDM 与某设备关联（有用户确认流程），按 BSSID 放行；不是给普通 Wi-Fi 连接用的 |
| 该应用之前批准过这个 AP | `:1823-1856` | `mUserApprovedAccessPointMap`，按 **包名** 记录；对 WPA2/WPA3 等非开放网络，**SSID + 安全类型匹配即可**（不要求 BSSID 相同）；开放网络要求 BSSID 精确匹配 |
| `mApprovedApp`（shell 临时放行） | `:1893` | 只有 `adb shell cmd wifi network-requests-set-user-approved <pkg> yes` 能设置；注释明确 “Temporary approval set by shell commands”，**不持久化**，网络设置重置/服务清理即失效 |

**弹窗**：以上三条都不满足时，`needNetworkFor` 走 `startUi()`（`:942-943`）。单 SSID 请求（我们的 `setSsid(...)`）会进入 `NetworkRequestSingleSsidDialogFragment`，显示「连接到设备 / “应用名”应用要使用临时 WLAN 网络连接到您的设备 / SSID / 取消 / 连接」。

### 2.3 权限、开关、白名单逐项核对

| 检查项 | 结论 | 证据 |
|---|---|---|
| `NETWORK_SETTINGS` 能免弹窗吗 | **不能** | `WifiNetworkFactory` 中 `checkNetworkSettingsPermission` 只用于：允许覆盖已有请求（`:703-709`）、允许后台应用发请求（`acceptRequest` 内 `:808-818`）、MultiInternet 判定；审批函数 `isAccessPointApprovedForActiveRequest` 完全没有权限分支。真机实测：uid 1000 持有 NETWORK_SETTINGS，仍弹窗（见 §5.2） |
| `NETWORK_SETUP_WIZARD` / `MANAGE_WIFI_NETWORK_SELECTION` / `NETWORK_CARRIER_PROVISIONING` | **不能** | 同上，审批路径不读取这些权限。注意 `NETWORK_CARRIER_PROVISIONING` 对 **NetworkSuggestion** 有自动批准作用（`WifiNetworkSuggestionsManager.java:945-957`），但与 specifier 弹窗无关 |
| `WifiNetworkFactory` 的 config.xml / `Settings.Global` / `Settings.Secure` 开关 | **没有** | 在 Wi-Fi 服务源码与 `ServiceWifiResources/res/values/config.xml` 中未找到控制该弹窗的开关；开发者选项也没有对应项 |
| 按 UID / 包名白名单 | **有，但只是 shell 临时通道** | `mApprovedApp`（`:163`, `:2074-2088`），由 `WifiShellCommand.java:1387-1391` 设置，注释写明 not persisted。ROM 侧若在开机时调用 `setUserApprovedApp` 可达到“持久放行”，但那是 Wi-Fi APEX 内部接口，见 §3.3 |
| 是否与 `peering` 有关 | 无关 | 判定表中没有 peering 字段；peering 是 Wi-Fi Aware/NAN 的概念 |

**device 侧还有一个 `isFromSetting`**：`needNetworkFor()` 里计算了 `checkNetworkSettingsPermission(uid)`，但只用于 MultiInternet 请求分流（`:873-876`），不会跳过审批 UI。

### 2.4 为什么我们（uid 1000、平台签名）还会弹

**因为 `WifiNetworkSpecifier` 的语义就是“临时网络 + 用户授权”，不是“系统应用免授权”。** 真机证据：

- 测试 APK `com.pri.wifitest` 与 `com.android.settings` 同一个 `android.uid.system`（uid 1000）、同一个平台签名（`signatures:[3c1c3027]` 相同）。
- 应用内 `checkSelfPermission` 实测（`_Temp/hotspot_study_evidence.txt`）：
  `NETWORK_SETTINGS=0 NETWORK_SETUP_WIZARD=0 CHANGE_WIFI_STATE=0 NEARBY_WIFI_DEVICES=0 MANAGE_WIFI_NETWORK_SELECTION=0 NETWORK_CARRIER_PROVISIONING=0`（0 = GRANTED）。
- 仍然弹出「连接到设备」；截图 `_Temp/spec_dialog_t40.png`。
- 用户点「连接」后连接成功，并把 AP 写入 `mUserApprovedAccessPointMap`：
  `WifiNetworkFactory: mUserApprovedAccessPointMap {com.pri.wifitest=[AccessPoint: TShare-Probe, be:9e:51:ea:4c:7d, 2]}`。
- **同一 SSID 第二次请求不再弹窗**：10:00:20.105 发起，10:00:20.513 `onAvailable`（约 0.4 s）。

**推论（对本产品很关键）**：批准记录按 `包名 + SSID（+安全类型）` 记忆。我们当前每轮都用会话派生的随机 SSID（`Xfer.java:494-518`：`TShare-` + 6 位 hex），所以**每一轮都是“新 AP”，每一轮都会弹窗**；若改成固定 SSID，同一台手机第一次批准后，后续轮次可免弹窗（但两台手机配对时 SSID 冲突/串台风险随之上升，见 §6）。

### 2.5 源码版本说明

设备实际运行的是 **Google 签名的主线 Wi-Fi APEX**：`/system/apex/com.google.android.wifi_compressed.apex`（`pm path com.google.android.wifi`，versionCode 360911300），不是本树 `alps/packages/modules/Wifi` 编出的 `com.android.wifi`。本文的源码行号取自本地 `alps/packages/modules/Wifi`，其类名/字段名与运行时 `dumpsys` 输出完全对得上（`WifiNetworkFactory`、`mUserApprovedAccessPointMap`），判定逻辑可信；但**精确行号可能与设备 APEX 有小版本差异**。

---

## 3. 能否去掉弹窗：逐方案

### 3.1 应用侧 API（不改 ROM）

| 方案 | 本 ROM 可行性 | 是否弹窗 | 关键限制 | 结论 |
|---|---|---|---|---|
| `WifiNetworkSpecifier` + `requestNetwork` | 可用 | **弹**（首次/每个新 SSID） | 走主 STA（本机 local-only 并发开关为 false），加入时顶掉当前 Wi-Fi | 现状；必须配 ROM 放行或固定 SSID |
| `WifiManager.connect(WifiConfiguration, ActionListener)`（@SystemApi，API 29 起废弃、隐藏） | **实测可用** | **不弹** | 要求 `isPrivileged`（平台签名即满足）；会把网络保存为普通网络（autojoin），切换主 STA；需 `forget()` 清理 | **推荐作为不改 ROM 的兜底**（有副作用） |
| `addNetwork` / `save` / `enableNetwork`（旧流程） | 按代码可用 | 不弹 | 同样要求 platform signature / `isTargetSdkLessThanQOrPrivileged`；分多步、同样切主 STA、会保存网络 | 可用但没必要（`connect()` 一步完成） |
| `WifiNetworkSuggestion` + `setIsUserInteractionRequired(false)` | 可用 | **不弹** | 该 flag 的语义是 **captive portal 登录提示**，不是“免审批”；真正免审批是因为 uid 1000 持有 `NETWORK_CARRIER_PROVISIONING`，`WifiNetworkSuggestionsManager` 自动把该应用标记为 approved（`:945-957`）。但 suggestion 只是“加入自动选网候选”，**不能强制立即连接**；实测在已连有网 AP 时不会切到热点 | **不能作为主连接方式**；可做后台兜底 |
| `connectMultiNetwork`（Android 13+ 多网络） | **不可用** | — | 框架里没有 `WifiManager.connectMultiNetwork` 这个公开 API；MultiInternet 路径要求 `INTERNET` capability + band specifier，并且 `isStaConcurrencyForMultiInternetSupported()` 本机为 **false**；local-only 工厂还会拒绝带 band 的请求（`WifiNetworkFactory.java:752-755`） | 不适用 |
| `WifiNetworkSpecifier.Builder.setBand` | **不可用** | — | 服务端明确拒绝：“Requesting specific frequency bands is not yet supported. Rejecting”（`WifiNetworkFactory.java:752-755`） | 不适用 |
| 反射 `IAppOpsService` / 直接调 `WifiServiceImpl` | 不建议 | — | 越过框架审批 = 绕过用户隐私保护；依赖隐藏 Binder 接口，版本升级即碎；审核/安全风险高 | **明确不推荐** |

**实测细节（`WifiManager.connect`）**：

```
10:06:49.826 ACTION=connect ssid=TShare-5G wifiEnabled=true connectedSsid="Coosea-Office"/0
10:06:49.829 CONNECT issued
10:06:49.996 CONNECT listener onSuccess        ← 无任何系统弹窗
10:06:52.678 WATCH onAvailable connected="TShare-5G"/4, IP 172.20.70.213/24
```

- 连接期间主 Wi-Fi 从 Coosea-Office 切到热点；`cmd wifi list-networks` 出现 `4 TShare-5G`（已保存）。
- `cmd wifi forget-network 4` 后，系统自动重新连回 Coosea-Office（约数秒）。
- 该 API 在服务端只检查 `isPrivileged()`（`WifiServiceImpl.java:7216-7223`），其中包含 `isSignedWithPlatformKey(uid)`（`:1336-1342`），所以平台签名应用可用；普通应用会 `SecurityException`。
- 隐藏 API 通过反射调用（公开 SDK 里没有这个重载），需要在 `framework-header.jar` 下编译或用反射。

### 3.2 权限（`privapp-permissions`）

**不能。** `NETWORK_SETTINGS` 不是弹窗的判定条件（§2.3 源码 + §5.2 实测）。给我们的包加这行权限不会改变行为。**不要在这条路上投入。**

> 附注：uid 1000 之所以已经持有这些权限，是因为同一 shared UID 下的 Settings 声明并获授了它们；权限检查是按 UID 的，所以我们的应用“顺带”拥有。这也解释了为什么 `checkSelfPermission` 全绿。

### 3.3 ROM 定制路径

| 方案 | 改动位置 | 改动量 | 能/不能 | 风险 |
|---|---|---|---|---|
| **A. Settings 里自动点「连接」（推荐）** | `pri_project/android/pri_app/PriSettings/src/com/android/settings/wifi/NetworkRequestDialogActivity.java:200-216`（`onMatch()`），复用 `:245-250` 的 `onClickConnectButton()` | ~10 行 + 一个可配置的 SSID 前缀/应用名 | **能**，保留 specifier 流程；不影响其他应用（按前缀 + 应用名双重匹配） | 前缀可被其它应用伪造（但连接仍需正确密码）；需处理 `mUserSelectionCallback` 尚未注册时的回退；需实测悬浮窗遮挡（§1.5） |
| B. 修改 Wi-Fi 服务 `WifiNetworkFactory`，按包名放行 | `alps/packages/modules/Wifi/.../WifiNetworkFactory.java`（`isAccessPointApprovedForActiveRequest` 增加包名判断） | 小 | **理论上能，但本 ROM 不要做** | 设备运行的是 Google 签名的 `com.google.android.wifi` APEX（`vendor/partner_modules/WiFiPrebuilt` 预编译），不是本树 `com.android.wifi`；改源码后必须替换整个主线 APEX，影响 GMS/认证，风险远大于收益 |
| C. 给应用加 `NETWORK_SETTINGS` | `privapp-permissions-*.xml` | 一行 | **不能** | 无效（§2.3/§5.2） |
| D. 开机调用 `setUserApprovedApp` 持久放行 | Wi-Fi APEX 内部（同上） | 小 | 理论上能 | 同样受限于 Google APEX；且 `mApprovedApp` 不是持久化的，需要在每次开机/服务重启后重设；不推荐 |
| E. 打开 `config_wifiMultiStaLocalOnlyConcurrencyEnabled` | 项目 RRO，target `com.google.android.wifi.resources`（MTK `WifiResOverlay/AndroidManifest_Mainline.xml` 已指向该包；`overlayable.xml:251` 允许覆盖） | 一个 bool | **不解决弹窗**，但能让客户端用第二条 STA 连热点，**不中断用户自己的 Wi-Fi** | 需要新增/修改项目 RRO；硬件支持 STA+STA（`STA + STA Concurrency Supported: true`），但 mt6768 当前不在 MTK dual_sta 列表（`WifiResOverlay/Android.mk:91-98`），需实机验证双 STA 稳定性 |

**“最小改动”结论**：改 `PriSettings/NetworkRequestDialogActivity`（方案 A）是唯一既小又不碰主线 APEX 的路。若不能改 ROM，则用 `WifiManager.connect()` 兜底（方案 §3.1），但接受“切换主 Wi-Fi + 保存网络 + 需要清理”的副作用。

### 3.4 固定 SSID 的折中（只改应用）

如果既不想改 ROM、也不想用隐藏 API：把每轮派生的 SSID 改为**固定 SSID**（或固定前缀 + 极少量候选），利用“批准记录按 SSID 记忆”的特性，让同一台手机**第一次**弹窗、后续免弹。代价：

- 两台手机在同一房间同时碰时，固定 SSID 可能冲突；可把 AP 的 BSSID 写进 NFC payload 并用 `setBssid` 精确匹配（需确认 LOHS 的 BSSID 可读）。
- 50 个 AP 的 LRU 上限（`NUM_OF_ACCESS_POINT_LIMIT_PER_APP`，`WifiNetworkFactory.java:128`）不再被每轮新 SSID 快速耗尽。
- 安全性：SSID 公开不等于密码公开；密码仍是 NFC 交换的会话派生值，WPA2 仍保护链路。

---

## 4. 热点与 Wi-Fi 能否共存

### 4.1 平台能力

| 证据 | 值 |
|---|---|
| `dumpsys wifi` SupportedFeatures（两机） | 含 `WIFI_FEATURE_MOBILE_HOTSPOT`、`WIFI_FEATURE_AP_STA`、`WIFI_FEATURE_DUAL_BAND_SIMULTANEOUS` |
| `dumpsys wifi` | `STA + AP Concurrency Supported: true`；`STA + STA Concurrency Supported: true`（两机） |
| `WifiManager.isStaApConcurrencySupported()` | `true`（测试 APK，uid 1000） |
| `wifi_static_chip_info`（HAL 组合） | 含 `{1 x STA + 1 x AP}`、`{2 x STA}`、`{1 x STA + 1 x P2P}` |
| `WifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported()` | **false**（`config_wifiMultiStaLocalOnlyConcurrencyEnabled=false`） |
| `WifiManager.isStaConcurrencyForMultiInternetSupported()` | false |
| `cmd wifi query-interface 1000 com.pri.nfctouchdemo AP -new` | “Interface AP can be created. Following interfaces will be destroyed: Type=DIRECT” — AP 可创建，仅会拆 P2P |

`config_wifiMultiStaLocalOnlyConcurrencyEnabled` 的默认值见 `alps/packages/modules/Wifi/service/ServiceWifiResources/res/values/config.xml:866`（false）；MTK 只在特定平台把 `WifiResOverlay/concurrency/dual_sta` 编进去（`vendor_v/vendor/mediatek/proprietary/packages/overlay/vendor/WifiResOverlay/Android.mk:91-98`），mt6768 不在列表里，所以本机为 false。

### 4.2 真机实测（Wi-Fi 开着且已连接）

在 t33 上（STA 已连 Coosea-Office，5745 MHz）：

| 实验 | 结果 |
|---|---|
| LOHS 自定义 SSID `TShare-Probe`（不指定 band） | 成功；`num ClientModeManagers:1 num SoftApManagers:1`；STA 保持 Coosea-Office |
| LOHS 自定义 SSID `TShare-5G` + `setBand(BAND_5GHZ)` | 成功；同上，STA 保持 Coosea-Office（同频段 5 GHz 也能共存） |
| 关闭测试进程 | `CMD_AP_STOPPED`（LOHS 生命周期绑定请求进程） |

**结论：本平台支持 concurrent AP+STA；`Xfer.startHotspot()` 里“Wi-Fi 开着就跳过热点”的判断不成立。** 该判断应删除或改为“先尝试，失败再回退”，而不是预先跳过。真实场景 Wi-Fi 常开，正是这个判断让热点承载在当前代码里等于不存在。

**失败原因码**（供兜底日志用，`WifiServiceImpl.java:3244-3262`）：
- `ERROR_INCOMPATIBLE_MODE`：调用 UID 不在前台（`isAppForeground`，`:3255-3258`）或已有 tethering AP 且无法再建（`:3260-3262`）；`[注]` 我们的应用有前台服务（`OverlayService` `isForeground=true`），UID 重要性 ≤ VISIBLE，实测后台也能开。
- `ERROR_TETHERING_DISALLOWED`：用户限制 `DISALLOW_CONFIG_TETHERING`（`:3246-3248`）。
- `ERROR_NO_CHANNEL`：无可用信道（`LohsSoftApTracker.onStateChanged`，`:3046`）。

### 4.3 客户端一侧：加入热点会切走主 Wi-Fi

`WifiNetworkSpecifier` 的 local-only 连接本应使用第二条 STA，但本机 `config_wifiMultiStaLocalOnlyConcurrencyEnabled=false`（`ActiveModeWarden.canRequestMoreClientModeManagersInRole()` 对 `ROLE_CLIENT_LOCAL_ONLY` 直接返回 false，`ActiveModeWarden.java:650-659`），于是只能占用主 STA。实测：

```
加入热点后：WifiInfo SSID="TShare-Probe"/1，IP 172.20.70.168（热点网段）
原来的 Coosea-Office 连接被顶掉
停止请求 / forget 网络后：自动回到 Coosea-Office
```

**取舍**：如果接受“传图这十几秒用户自己的 Wi-Fi 断开”，当前 specifier 流程可接受；如果不接受，需要打开 local-only 并发开关（§3.3-E）或改用 Wi-Fi Direct（P2P 与 STA 可共存）。

### 4.4 “热点为主”的取舍结论

- 本平台（mt6768 + 本 ROM）**不需要**“先关 Wi-Fi 再开热点”；热点与 STA 可以同时工作。开热点的一方（AP owner）不会掉网。
- 加入热点的一方（client）在 local-only 并发开关关闭时**会短暂掉网**；这是产品需要明确接受的体验（或改 RRO）。
- 因此“热点为主”在本平台可行；真正的阻碍是**系统弹窗**和**悬浮窗遮挡**，不是射频互斥。

---

## 5. 设备验证记录（2026-10-19）

### 5.1 测试 APK

- 路径：`_Temp/wifitest/WifiTest-platform.apk`（源码/构建脚本同目录）
- 包名 `com.pri.wifitest`，`sharedUserId="android.uid.system"`，uid 1000
- 平台签名（SHA-256 `A8:32:01:...:EE:5F`，与 `com.android.settings` 相同）
- 已安装到 t33、t40；**保留未卸载**
- 支持 action：`caps` / `perm` / `lohs` / `lohs-stop` / `spec` / `spec-stop` / `connect` / `suggest` / `suggest-remove` / `disconnect` / `status`

### 5.2 关键结果

| # | 实验 | 结果 |
|---|---|---|
| 1 | uid 1000 权限 | NETWORK_SETTINGS 等 6 项全部 GRANTED |
| 2 | specifier 首次连接 `TShare-Probe` | **弹窗**（`NetworkRequestDialogActivity`，截图 `_Temp/spec_dialog_t40.png`）；用户点「连接」后连上（从请求发出到 onAvailable 共 20.5 s，含等待用户操作） |
| 3 | 同 SSID 第二次 specifier | **不弹窗**，0.4 s 连上（批准记录生效） |
| 4 | `WifiNetworkSuggestion` + `setIsUserInteractionRequired(false)` | 不弹窗；`add status=0`；系统日志 “Setting the carrier provisioning app approved”；**没有切到热点**（已连有网 AP） |
| 5 | `WifiManager.connect(WifiConfiguration)`（反射） | **不弹窗**，约 2.9 s 连上；切换主 STA；保存了网络 |
| 6 | 忘记热点网络 | 自动回连 Coosea-Office |
| 7 | LOHS + Wi-Fi 已连（2.4G/5G） | 均成功，AP+STA 并存 |
| 8 | 测试进程被杀 | LOHS 立即停止（`CMD_AP_STOPPED`） |

### 5.3 清理

实验后两台设备均已恢复：连接 Coosea-Office、无 TShare 保存网络、无热点、无 suggestion 条目；测试 APK 保留安装（`com.pri.wifitest`）。

清理命令（如后续需要）：
```bash
ADB=~/.claude/platform-tools/adb/adb
$ADB -t 33 uninstall com.pri.wifitest
$ADB -t 40 uninstall com.pri.wifitest
# 若还有残留保存网络：
$ADB -t 40 shell cmd wifi list-networks
$ADB -t 40 shell cmd wifi forget-network <netId>
```

> 注意：禁止 `adb kill-server`；本机 adb 有两条同序列号记录，必须用 `-t <transport_id>`。

---

## 6. 承载选型的重新评估（真实客户场景）

场景前提：两台手机、**不在同一网络**、Wi-Fi 常开、可能无蜂窝、用户希望碰一下就传。

| 承载 | 需要同网 | 需要互联网 | 系统弹窗 | 连接耗时 | 吞吐/稳定性 | 对本产品的评价 |
|---|---|---|---|---|---|---|
| **热点（LOHS）** | 否 | 否 | **specifier 首次弹；ROM 放行后不弹** | 开 AP ~0.3 s；客户端加入 ~1-3 s（批准后 0.4 s） | 实测约 2.9 s 建链；走 WPA2，近距离稳定 | **客户场景最匹配，推荐为主承载**；必须解决弹窗 + 悬浮窗遮挡 |
| **Wi-Fi Direct（P2P）** | 否 | 否 | 平台签名应用未观察到邀请弹窗 | 发现/协商 3-10 s，现有代码有 1/2/4 s 退避 | 与 STA 可共存（AP 创建会拆 DIRECT，反之亦然）；P2P + 热点可能争射频 | **最佳兜底**；启动慢，但无需互联网、无需保存网络 |
| **蓝牙 PAN** | 否 | 否 | 配对/授权视实现 | 配对后较快 | 吞吐低（经典蓝牙 1-2 Mbps 量级），大图慢 | 未实现；**只适合小图/最后兜底**，不建议作为主承载 |
| **同 LAN 直连** | 是 | 通常需要 | 无 | 最快（已知 IP 即连） | 取决于路由器；最稳定 | 保留为“机会层”：同网时优先，真实客户场景不能依赖 |

**推荐顺序（真实客户场景）**：

1. **同 LAN**（如果碰巧同网，零成本、最快）
2. **热点**（主承载；AP owner = NFC 卡侧/无照片侧，客户端用 ROM 放行的 specifier，或 `connect()` 兜底）
3. **Wi-Fi Direct**（热点失败/被策略禁止/客户端无法弹窗授权时）
4. **蓝牙 PAN**（未实现；仅在小图且前三条都不可用时考虑）

> 当前代码顺序是 ①热点 → ②同网 → ③P2P，但热点被 `isWifiEnabled()` 提前跳过，实际等价于 ②→③。把第 575-579 行的跳过删掉，才是真正的“热点优先”。

---

## 7. 建议的实施方案

### 7.1 应用侧可做（不改 ROM）

| # | 改动 | 文件/位置 | 改动面 | 风险 |
|---|---|---|---|---|
| A1 | 删除 `isWifiEnabled()` 跳过，改为“尝试开热点，失败按 reason 回退” | `app/src/main/java/com/pri/nfctouchdemo/nfc/Xfer.java:575-579` | ~5 行 | 低；本平台已验证 AP+STA 共存；旧平台/异常场景要靠 onFailed 回退 |
| A2 | specifier 超时预算覆盖用户操作（当前 5×1.2 s / 10 s，弹窗还没点完就超时重试） | `Xfer.java:1370-1404`（`HS_JOIN_MAX_ATTEMPTS/ATTEMPT_MS/BUDGET_MS`） | 常量调整 | 低；但“靠用户点”本身不是产品方案 |
| A3 | 增加 `WifiManager.connect(WifiConfiguration)` 兜底（平台签名构建特性检测；连上后 `forget()` 清理） | `Xfer.java` 的 `joinHotspotOnce` 附近 | ~60-80 行（反射 + 回调 + 清理） | 中：隐藏 API 随版本变化；切换主 Wi-Fi 会短暂断网；保存的网络若未清理会残留 autojoin |
| A4 | 固定 SSID（或 BSSID 精确匹配）以获得“一次批准、后续免弹” | `Xfer.java:494-518` | 小 | 中：SSID 冲突/串台；需把 BSSID 放进 NFC payload；安全性需评估 |
| A5 | 弹窗期间临时收起悬浮窗（若保留弹窗） | `TapService.java:373-376` / `OverlayService.kt` | 中 | 破坏动效连贯性；且仍要用户点一次 |

### 7.2 必须改 ROM

| # | 改动 | 文件/位置 | 改动面 | 风险 |
|---|---|---|---|---|
| R1（推荐） | **Settings 自动放行**：单 SSID 请求命中本应用 SSID 前缀（如 `TShare-`）且 `EXTRA_APP_NAME` 等于本应用名时，直接调用 `onClickConnectButton()`，不显示对话框 | `pri_project/android/pri_app/PriSettings/src/com/android/settings/wifi/NetworkRequestDialogActivity.java:200-216`（复用 `:245-250`） | ~10 行 + 常量 | 低-中：前缀可被第三方伪造（但连上仍需正确密码）；建议只在 `EXTRA_APP_NAME` 匹配时放行，并保留回调未就绪时的回退 |
| R2（可选） | 打开 local-only 并发开关，客户端用第二条 STA 连热点，不中断用户自己的 Wi-Fi | 新增项目 RRO，target `com.google.android.wifi.resources`，`config_wifiMultiStaLocalOnlyConcurrencyEnabled=true`（参考 `WifiResOverlay/concurrency/dual_sta/res/values/config.xml`） | 一个 bool + RRO 配置 | 中：需验证 mt6768 双 STA 稳定性/功耗；可能影响其它 Wi-Fi 场景 |
| R3（不推荐） | 改 Wi-Fi 服务 `WifiNetworkFactory` 按包名放行 | `alps/packages/modules/Wifi/...` | 小 | 高：设备跑的是 Google 签名 `com.google.android.wifi` APEX，需替换主线 APEX，影响 GMS/认证 |
| R4（无效） | 加 `NETWORK_SETTINGS` privapp 权限 | `privapp-permissions-*.xml` | 一行 | 无效果（已实测） |

**关于“在设置里放行”的准确答复**：

- 系统设置里**没有**“允许某应用免确认连接热点”的普通开关（无论开发者选项还是 Wi-Fi 设置）。
- 能做到“设置里放行”的唯一落点是 **Settings 的 `NetworkRequestDialogActivity` 代码级自动批准**（R1），不是给权限。
- 如果不改 ROM，则只能应用侧用隐藏 API（A3）或固定 SSID 换“少弹”（A4）。

---

## 8. 未验证项

1. **批准记录是否跨重启持久化** `[未确定]`：源码注册了 `NetworkRequestStoreData` 并在批准后 `saveToStore()`（`WifiNetworkFactory.java:1942`, `NetworkRequestStoreData.java:126`），但实验后拉取的 `/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml` 中未见 `NetworkRequestMap` 段（可能尚未刷盘/或运行时 APEX 版本差异）。同一次开机内的免弹已被实测证实。
2. **STA 在 2.4 GHz + LOHS 2.4 GHz 同频共存** `[未验证]`：本次 STA 均连 5 GHz；同频 AP+STA 需要信道共享，建议后续用 2.4G 路由器验证。
3. **悬浮窗遮挡系统弹窗** `[推断，待实测]`：窗口类型/层级/flags 已确认（`OverlayService.kt:859-930`），但未用真实碰一碰动效层与弹窗同屏实测。若属实，保留弹窗的方案必须同时收起悬浮窗。
4. **R2（local-only 双 STA）在 mt6768 上的稳定性** `[未验证]`：硬件组合支持 `{2 x STA}`，但 MTK 未给该平台编 dual_sta overlay，需实机验证连接成功率、功耗、与 P2P 的共存。
5. **Wi-Fi Direct 与热点的吞吐/时延对比** `[未测量]`：本次只验证了建链与承载可行性。
6. **蓝牙 PAN** `[未实现/未验证]`。
7. **其它项目机型** `[未验证]`：AP+STA 能力依平台/芯片/overlay 而定，换项目必须重新跑 `dumpsys wifi | grep "STA + AP"` 与 LOHS 实测。
8. **`WifiManager.connect()` 在非平台签名构建上的行为**：会直接 `SecurityException`（服务端 `isPrivileged` 检查），本方案仅适用于系统签名应用。

---

## 9. 附：关键源码索引

| 主题 | 文件:行 |
|---|---|
| specifier 请求分流 + 弹窗触发 | `alps/packages/modules/Wifi/service/java/com/android/server/wifi/WifiNetworkFactory.java:857-949`（`:912`, `:932`, `:942-943`） |
| 审批判定（三条放行） | 同上 `:1877-1897`；内部批准表 `:1823-1856`；CDM `:1859-1875`；shell 放行 `:1893` |
| 批准记录写入 | 同上 `:1212-1227`, `:1902-1943` |
| 启动 Settings 弹窗 | 同上 `:1748-1757`；action 常量 `:115-125` |
| shell 临时放行 | `.../WifiShellCommand.java:1387-1391`；`WifiNetworkFactory.java:2074-2088` |
| `connect()` 权限门 | `.../WifiServiceImpl.java:7216-7223`；`isPrivileged` `:1336-1342` |
| LOHS 启动校验/失败码 | `.../WifiServiceImpl.java:3194-3275`（前台 `:3255-3258`，tethering `:3246-3248`，AP 能力 `:3260-3262`） |
| suggestion 自动批准 | `.../WifiNetworkSuggestionsManager.java:945-967` |
| local-only 并发开关 | `.../ActiveModeWarden.java:641-665`；`ServiceWifiResources/res/values/config.xml:866` |
| AP+STA 能力上报 | `.../WifiVendorHal.java:1594-1600`；`WifiManager.java:4136-4149` |
| 弹窗 Activity | `pri_project/android/pri_app/PriSettings/src/com/android/settings/wifi/NetworkRequestDialogActivity.java:102-110, 200-216, 245-250`；`NetworkRequestSingleSsidDialogFragment.java:53-66`；`AndroidManifest.xml:4479-4486` |
| 我们应用的热点判断 | `pri_project/android/pri_app/nfc_tap_demo/app/src/main/java/com/pri/nfctouchdemo/nfc/Xfer.java:565-579`（跳过在 `:575-579`） |
| 我们应用的 specifier 加入 | 同上 `:1370-1490`（`joinHotspotOnce` `:1405-1418`） |
| 我们应用的派生 SSID | 同上 `:494-518` |
| 悬浮窗类型/层级/触摸 | `.../service/OverlayService.kt:859-930`；`TapService.java:373-376, 415` |
| 应用 uid/签名 | `app/src/main/AndroidManifest.xml:17`（`sharedUserId="android.uid.system"`） |
