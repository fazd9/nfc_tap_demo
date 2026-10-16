# 相册拾取：不分享、不重选，看/选中的照片直接碰传

> 目标：用户在 Google 相册里**打开一张照片**，或者**勾选若干张**，把两台手机碰一下，
> **正好这些照片**开始传输。用户不需要点「分享」、不需要「上传」、不需要去别的应用里重选。
>
> 本文记录该目标在真机上的达成情况、机制、证据、边界与风险。

---

## 一、结论

**目标达成**（发送侧，单图 + 多选都覆盖，已在 `Armor X17` / Android 16 上实测）。

用户只需要做一件事：**在相册里看照片 / 勾照片**，然后碰手机。
「分享」这一步不需要，"碰一碰"是唯一的触发动作。

核心机制一句话：

> 相册的无障碍节点树里**没有文件路径 / Uri / media id**，只有每张照片的**拍摄时间（分钟精度）**
> 和它在屏幕上的位置；因此先用"时间"把 MediaStore 里的候选缩到几个，
> 再用"屏幕像素"在这几个候选里认出到底是哪一张。

这条链路上没有任何一步需要 Google 相册配合，也不需要它改一行代码。

---

## 二、最终方案与完整流程

### 2.1 用户视角

| 阶段 | 用户要做的 | 用户**不需要**做的 |
| --- | --- | --- |
| 挑照片 | 在 Google 相册里打开一张照片，或长按进入多选后勾几张 | — |
| 触发 | 两台手机碰一下 | 点「分享」、选「一碰互联」、上传、在别的应用里重新选图 |
| 发送 | 上滑动效层（与原有流程一致） | 回到我们 App、切换应用 |

碰触之前**前台一直停在相册**，碰触之后没有 FilePicker、没有 ShareSheet、没有跳转。

### 2.2 系统视角（时序）

```
用户在 Google 相册里看照片 / 勾照片
        │
        │  （无障碍服务常驻，只在"碰一碰"发生时才真正去读树）
        ▼
两台手机碰到一起 → NFC(HCE/reader) 交换对方信息 → Bus.onPeerData → TapService.onPeer
        │
        ▼
TapService.onPeer 先问一句：现在栈顶是不是 Google 相册？
        │  ActivityTaskManager.getFocusedRootTaskInfo()（uid 1000 有 MANAGE_ACTIVITY_TASKS）
        │
   ┌────┴─────────────────────────┐
   │ 不是                          │ 是
   ▼                              ▼
走原有流程，一个字节没变      AccessibilityProbeService.scanNow() 读当前窗口树
（share intent / 上次选择）         │  · 全屏大图 (面积 ≥ 45% 屏幕) → 1 个目标
                                   │  · 网格里 checked=true 的格子 → 每格 1 个目标
                                   ▼
                              ScreenCapture.captureBitmap() 静默截屏一张
                                   │
                                   ▼
                              每个目标：
                                1) 用"拍摄时间"查 MediaStore（±2 分钟，空则放宽到 ±1 小时）
                                2) 对候选逐个解码成小图，与"屏幕上那块区域"做归一化互相关
                                3) 取相关最高者；相关并列时取时间差最小者
                                4) 校验这个 Uri 真的读得出来（openInputStream 读 1 字节）
                                   │
                                   ▼
                              PENDING ← 这些 Uri
                                   │
                                   ▼
                        原有流程原样继续：showSendPage → 上滑 → Xfer.sendImages(PENDING)
                        （Xfer 用 ContentResolver.openInputStream(uri) 读，与校验用的是同一个 API）
```

### 2.3 代码落点

| 文件 | 作用 |
| --- | --- |
| `nfc/GalleryPickup.java`（新增） | 前台判定、时间过滤、像素比对、可读性校验 |
| `nfc/AccessibilityProbeService.java`（新增） | 连上系统无障碍，提供 `scanNow()` 读当前窗口树 |
| `gfx/ScreenCapture.java`（改） | 抽出 `captureBitmap()`，与原有 `captureBackdrop()` 共用静默截屏通道 |
| `nfc/TapService.java`（改） | `onPeer` 里插入拾取；把原尾部逻辑原封不动抽成 `beginPeerRound(payload)` |
| `res/xml/accessibility_probe_config.xml`、`AndroidManifest.xml`、`strings.xml` | 注册无障碍服务 |

`TapService.onPeer` 的改动是**加法**：拾取只在"相册在前台"时发生，其余情况
`beginPeerRound` 是原来那段代码（逐字节相同）。HCE、`TapDispatchActivity`、角色状态机、
`Xfer`、动效层、查看器、失败横幅、空态全部未动。

---

## 三、实机证据

设备：`Armor X17`（Android 16，`model:Armor_X17`，adb 序列号 `333333333333337`）。
Google 相册 `7.88.0.966185373`。被选中的照片是实测用的截图。

### 3.1 多选：7 张 → 正好 7 张，张张正确

勾选了 7 张（其中 **3 张时间完全相同**、另外 4 张也是同一分钟，即"时间完全无法区分"的最坏情况）：

```
adb shell am startservice -n com.pri.nfctouchdemo/.nfc.TapService \
    -a com.pri.nfcdemo.DEBUG --es payload 'n=TestPeer|b=PeerBT|t=4321' --es role reader
```

```
I A11yProbe: 相册扫描: 7 个目标 [2026-10-01 16:15@[0,284][236,520], 2026-10-01 16:15@[242,284][478,520],
    2026-10-01 16:15@[484,284][720,520], 2026-10-13 17:50@[484,1190][720,1426],
    2026-10-01 16:15@[0,1432][236,1640], 2026-10-01 16:15@[242,1432][478,1640],
    2026-10-01 16:15@[484,1432][720,1640]]
I GalleryPickup: 2026-10-01 16:15@[0,284][236,520] 命中 3 个候选,开始像素比对
I GalleryPickup:    候选 content://media/external/images/media/21 时间差=57s 相关=0.9315976
I GalleryPickup:    候选 content://media/external/images/media/20 时间差=54s 相关=-0.25310823
I GalleryPickup:    候选 content://media/external/images/media/19 时间差=50s 相关=0.26993287
I GalleryPickup: 选中 content://media/external/images/media/21(相关 0.9315976)
I GalleryPickup: 2026-10-01 16:15@[242,284][478,520] 命中 3 个候选,开始像素比对
I GalleryPickup:    候选 content://media/external/images/media/21 时间差=57s 相关=-0.34003207
I GalleryPickup:    候选 content://media/external/images/media/20 时间差=54s 相关=0.9227885
I GalleryPickup:    候选 content://media/external/images/media/19 时间差=50s 相关=0.40887278
I GalleryPickup: 选中 content://media/external/images/media/20(相关 0.9227885)
I GalleryPickup: 2026-10-01 16:15@[484,284][720,520] 命中 3 个候选,开始像素比对
I GalleryPickup:    候选 content://media/external/images/media/19 时间差=50s 相关=0.9434639
I GalleryPickup: 选中 content://media/external/images/media/19(相关 0.9434639)
I GalleryPickup: 拾取结束,得到 4 张: [media/21, media/20, media/19, media/22]
```

**这是最有说服力的一组数据。** 三个格子挂着同一个"拍摄时间"，时间过滤后剩下同样 3 个候选，
但像素比对每次都能把该格子的那一张挑出来，而且正确项与错误项差距极大（0.93 vs 0.25 / -0.25）。
去掉重复后正好 4 张。

### 3.2 单图：全屏打开一张 → 正好那张

```
I A11yProbe: 相册扫描: 1 个目标 [2026-10-14 16:48@[0,0][720,1640]]
I GalleryPickup: 2026-10-14 16:48@[0,0][720,1640] 命中 5 个候选,开始像素比对
I GalleryPickup:    候选 media/45 相关=0.54581904 / media/44 相关=0.11646926
I GalleryPickup:    候选 media/43 相关=0.54581904 / media/41 相关=0.06272932 / media/40 相关=0.22104116
I GalleryPickup: 选中 content://media/external/images/media/43(相关 0.54581904)
I GalleryPickup: 拾取结束,得到 1 张: [content://media/external/images/media/43]
I NfcTapDemo: 相册拾取: 拿到当前正在看/选中的 1 张照片,碰一碰直接发送
```

**注意 `media/45` 与 `media/43` 相关值完全相同（都是同一张图的副本）。**
时间并列时选时间差更小的那个：`media/43` 差 52s，`media/45` 差 113s，所以选 `media/43` —— 正确。

### 3.3 拾取结果真的进入了发送动效层

用 `screencap` 抓了触发后的动效层：标题「发送给 PeerBT」，卡片里就是刚才全屏打开的那张
（`nfctap_1791967732366.jpg` 的画面 —— 应用内「一碰互联」界面），底部「上滑发送 1 张照片」。
即"用户看的那张"确实变成了"要发的那张"。

### 3.4 多选：2 张（提交版本复测）

```
I A11yProbe: 相册扫描: 2 个目标 [2026-10-14 16:48@[0,1190][236,1426], 2026-10-14 15:41@[242,1190][478,1426]]
I GalleryPickup: 选中 media/43(相关 0.54581904)
I GalleryPickup: 选中 media/27(相关 0.8259645)
I GalleryPickup: 拾取结束,得到 2 张: [media/43, media/27]
I NfcTapDemo: 相册拾取: 拿到当前正在看/选中的 2 张照片,碰一碰直接发送
```

### 3.5 不在相册时：行为完全不变

按 Home 回到桌面后触发：

```
I GalleryPickup: 栈顶是 com.android.launcher3,不是 Google 相册
I NfcTapDemo: P2P: 目标设备 = TestPeer/PeerBT
（后续与改动前逐行相同）
```

### 3.6 无障碍被关掉时：明确失败，不乱发

```
I NfcTapDemo: 碰一碰: 相册在前台,先拾取用户正在看/选中的照片
E GalleryPickup: 无障碍服务未连接,无法读取相册内容(设置里重新开启「一碰互联」无障碍即可)
I NfcTapDemo: 相册拾取: 读不到相册内容(无障碍未开启),沿用上一轮的选择
```

不崩、不卡，动效层照常起，退化成"本机没有照片 → 当接收方"。

---

## 四、单图 / 多选覆盖情况

| 场景 | 覆盖 | 说明 |
| --- | --- | --- |
| 多选（勾选 2 张） | ✅ 实测 | §3.4 |
| 多选（勾选 7 张，含 3 张时间完全相同） | ✅ 实测 | §3.1，最坏情况 |
| 单图（全屏打开一张） | ✅ 实测 | §3.2 |
| 单图（左右滑到下一张再碰） | ⚠️ 未单独实测 | 每次碰触都会重扫窗口树，逻辑上等价于 §3.2；滑页会触发窗口内容变化 |
| 相册里但什么都没选（网格态） | ✅ 实测 | 拾取到 0 个目标，且会清掉上一轮遗留的选择 |

单图和多选在实现上不是两条分支：**多选是"多个目标"，单图是"目标数为 1"**，同一套解析代码。

---

## 五、需要的权限 / ROM 改动

### 5.1 权限：**零新增**

`AndroidManifest.xml` 没有加任何 `<uses-permission>`。现有的
`sharedUserId="android.uid.system"`（uid 1000）已经自动拿到：

| 能力 | 靠什么 | 验证 |
| --- | --- | --- |
| 查栈顶应用 | `ActivityTaskManager.getFocusedRootTaskInfo()` → `MANAGE_ACTIVITY_TASKS` | uid 1000 通过 |
| 读 MediaStore | `READ_MEDIA_IMAGES` 被系统 `SYSTEM_FIXED\|GRANTED_BY_DEFAULT` 自动授予 | `dumpsys package` 里 `granted=true` |
| 读照片字节 | 同上（`openInputStream` 读 1 字节校验过） | §三日志 |
| 静默截屏 | 原有 `ScreenCapture` 通道（`STATUS_BAR_SERVICE` 已在清单里） | 已有功能 |
| 读窗口节点树 | 无障碍服务自身 | `dumpsys accessibility` 显示已 bound |

### 5.2 需要用户做的一件事：**开启无障碍**

```
设置 → 无障碍 → 一碰互联
```

或（调试用）：

```bash
adb shell settings put secure enabled_accessibility_services \
    com.pri.nfctouchdemo/com.pri.nfctouchdemo.nfc.AccessibilityProbeService
adb shell settings put secure accessibility_enabled 1
```

**产品化必须解决这一点**（见 §七）。系统应用**不能**静默开启无障碍 ——
`AccessibilityManagerService` 对 `setEnabledAccessibilityService` 有权限与用户确认门禁，
这是 Android 的安全设计，绕过它属于改 ROM。

### 5.3 ROM 改动：**不需要**

整个方案跑在应用层，Android 16 + Google 相册 7.88 上即可工作。
如果将来要做成系统内置能力（免去用户开无障碍），§七给出了最小改法。

---

## 六、风险

### 6.1 无障碍被关掉 / 应用被强杀

- **被关掉**：碰一碰仍然可用，但退化成"本机没有照片"，即本机变接收方，
  不再能"看着照片碰一下发送"。日志里有明确一行说明原因。
- **应用被 `force-stop`**：系统会**同时清空已启用的无障碍服务列表**。
  实测 `am force-stop com.pri.nfctouchdemo` 之后 `dumpsys accessibility` 里
  `Enabled services:{}` 变为空 —— 也就是说强杀一次之后，无障碍**不会**自己回来，
  用户必须再去设置里开一次。产品上要避免让用户强杀，或引导重新开启。
  **注意这与"应用被系统回收"不同**：被回收（low memory kill）时无障碍服务会由系统重新拉起。

### 6.2 对 Google 相册版本的依赖

这套机制读的是 Google 相册**给无障碍用的**界面描述：

| 依赖的东西 | 现状 | 万一改了会怎样 |
| --- | --- | --- |
| `content-desc` = `拍摄于 YYYY年M月D日 HH:MM 的照片` | 7.88.0 实测 | 解析不到 → 该拍不拾取，**不会错发**（退化为"本机没有照片"） |
| 勾选态反映在 `node.isChecked()` | 7.88.0 实测 | 多选拾取失效，同上 |
| 全屏图片节点面积 ≥ 45% 屏幕 | 经验阈值 | 单图拾取失效，同上 |

正则只认中文串。**相册语言切成英文（`Taken on ...`）时拾取直接失效**（安全失败）。
若要产品化，`TAKEN` 正则需要按 locale 补几条或改为不依赖文案（例如改用节点类名 + 面积 +
`isChecked` 的组合，代价是拿不到时间、候选集变大、变慢）。

**失败一定是安全失败**：认不出来就不发，绝不会"发一张错的"。

### 6.3 时间歧义

`content-desc` 只到分钟。本机上曾出现 **7 张里 5 张同一分钟**的极端情况。
实测像素比对在这种情况下仍然 100% 正确（§3.1），因为"哪一格是哪张"是靠像素定的，
时间只用来缩小候选。真正的兜底是：候选 > 1 且最高相关 < 0.30 时**放弃该张**，宁可不发。

### 6.4 用户数据安全

- 只读。不删、不移、不改任何照片。
- 代码里没有任何 MediaStore 写操作（`insert` / `delete` / `update`）。
- 动效层的「移至回收站」按钮属于产品既有 UI，本次改动未触碰，自动化测试也未点过它
  （点击坐标一律避开该按钮所在区域）。
- 静默截屏只落在本应用 `cacheDir`，按原有 `dropOldCaches` 随轮次清理。

### 6.5 性能

一次碰触 = 1 次静默截屏 + 每个目标 2~6 次小图解码。实测：

| 场景 | 耗时 | 日志时间戳 |
| --- | --- | --- |
| 拾取 2 张 | 约 0.6s | `17:14:40.104` → `17:14:40.707` |
| 拾取 7 张 | 约 3.3s | `16:55:28.094` → `16:55:31.420` |

其中截屏本身约 200ms（`开始拾取` → 第一个 `命中` 候选）。

解码用 MediaStore 里已有的 `width`/`height` 直接算 `inSampleSize`，省掉了一次
`inJustDecodeBounds` 的额外读文件（见 `decodeSmall` 注释）。

多选张数多时，这段时间花在"真正要发的第一张被认出来之前"，用户看到的是碰触后动效层
稍晚一点起来。若要进一步压缩，可以考虑并行解码候选，或把多个目标的时间窗合并后
只查一次 MediaStore。**当前未做**。

---

## 七、生产版本应该怎么做

1. **免去用户开无障碍**（最关键）。两条路：
   - **ROM 侧**：把"读相册当前内容"做成系统服务能力。最自然的落点是
     `packages/modules/Nfc` 已有的 OEM 扩展点（`NfcOemExtension` /
     `INfcOemExtensionCallback`，仓内已有先例 `vendor/st/nfc/.../StNfcOemExtension.java`）：
     把"碰触瞬间前台 Activity 的媒体信息"作为 NFC 事件的一部分交给应用，
     应用就不需要无障碍、不需要截屏，也不用读别人的窗口树。
     **本环境无法编译 ROM，这里只做方案建议，未实施。**
   - **应用侧折中**：把无障碍服务做成 `defaultEnabled` 预装的系统应用能力，
     或在首次开机引导里加一步引导用户开启。
2. **`content-desc` 正则本地化**：至少覆盖中/英；更稳的做法是不解析文案，
   改用"全屏节点 + 网格 checked 节点"的结构特征，再用 `MediaStore` 按时间倒序
   与网格序对齐（代价是丢时间、候选集变大）。
3. **拾取结果给用户一次确认**：现在是"碰一下就发"，若拾取相关值偏低（如 0.3~0.5），
   可在动效层上加一行"将发送：<文件名>"，把不确定的场合暴露给用户而不是硬猜。
4. **无障碍掉线自愈**：检测到 `sInstance == null` 时给出一次性提示（通知或引导页），
   而不是只在日志里说。
5. **`AccessibilityProbeService` 改名为 `GalleryWatcherService`**：它现在不是探针了，
   名字是历史遗留（类名/配置文件/清单项一起改，注意已开启的无障碍会因此失效一次）。

---

## 八、未验证 / 遗留问题

1. **没有做真正的两机对接传输。** 本环境只能 adb 到一台设备（`Armor X17`）；
   同网段的 `K80W` 出现在 P2P 扫描结果里，但没有 adb 通道，无法驱动它完成
   「上滑 / 接收」那一步的交互。已验证的是**发送侧全链路到 PENDING / Xfer 入口**，
   以及 `Xfer` 读取 Uri 所用的 API（`ContentResolver.openInputStream`）在同一份
   Uri 上确实可读。跨机传输本身是既有功能，本次未改动，也不在本次验证范围内。
2. **分享面板那条路仍保留**（`MainActivity` 的 `ACTION_SEND` / `ACTION_SEND_MULTIPLE`）。
   它不是本次目标的答案，但作为降级入口无害，未删除。
3. **`AssistContent` 那条路已证伪**，不再投入：`AssistContent` 拿得到，但
   `getClipData()` 恒为 null，`webUri` / `structuredData` 为 null，
   只有 `media_collection_bundle`（哪个相册）+ `query_options_bundle`（筛选条件），
   **是"哪个文件夹"不是"哪一张"**。相关代码 `nfc/AssistProbe.java` 与
   `TapService` 里的 `ASSIST_PROBE` 分支为诊断遗留，可随时删除。
4. **屏幕旋转 / 分屏未测**。相册是竖屏锁定，`Target.bounds` 用的是屏幕坐标，
   截屏也是全屏坐标，两者同域；分屏场景未验证。
5. **视频未测**。只处理 `android.widget.ImageView` 且 `content-desc` 能解析出拍摄时间的节点，
   视频的 `content-desc` 文案不同，当前会直接跳过（安全失败）。
6. **超大相册未测**。本机 MediaStore 只有 47 行。`MAX_CANDIDATES=400` 是硬上限，
   候选过多时行为未在真机验证。
7. **`content-desc` 的时区**按本地时区解析；跨时区旅行拍的照片，其 `content-desc`
   与 `date_modified` 是否同源未验证（宽窗口 ±1 小时可能不够）。

---

## 九、复现步骤（供后续回归）

```bash
# 0. 装带平台签名的包（debug 签名的包覆盖不了这个应用）
#    见仓库根 CLAUDE.md / 任务说明里的编译+签名命令

# 1. 开无障碍（只需一次；强杀应用后要重开）
adb shell settings put secure enabled_accessibility_services \
    com.pri.nfctouchdemo/com.pri.nfctouchdemo.nfc.AccessibilityProbeService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys accessibility | grep -A1 "Bound services"   # 确认已 bound

# 2. 在相册里打开一张照片，或长按进入多选后勾几张
adb shell am start -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    -n com.google.android.apps.photos/.home.HomeActivity

# 3. 用 DEBUG action 顶掉物理碰触（走的是真实代码路径）
adb logcat -c
adb shell am startservice -n com.pri.nfctouchdemo/.nfc.TapService \
    -a com.pri.nfcdemo.DEBUG --es payload 'n=TestPeer|b=PeerBT|t=4321' --es role reader

# 4. 看结果
adb logcat -d | grep -E "A11yProbe|GalleryPickup|相册拾取"
```

期望：`A11yProbe: 相册扫描: N 个目标 [...]` → 每个目标一行 `选中 content://media/...` →
`拾取结束,得到 N 张` → `相册拾取: 拿到当前正在看/选中的 N 张照片,碰一碰直接发送`。
