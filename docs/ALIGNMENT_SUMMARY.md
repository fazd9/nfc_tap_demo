# 碰一碰传图 · 对齐总结（相册取图 + NFC 触发）

> 对象：`/dfb/zhuyang/share/nfc_test/pri_project/android/pri_app/nfc_tap_demo`，分支 `nfc_tap`，HEAD `9a0a969`。
> 包名 `com.pri.nfctouchdemo`，平台签名系统应用（`sharedUserId="android.uid.system"`，uid 1000）。
> 参考产品：**OPPO 互传**，从 `Temp/video.mp4` 逐帧分析（见 `docs/PRODUCT_VIDEO_ANALYSIS.md`）。
> 实机：`Armor X17`（Android 16，adb 序列号 `333333333333337`）。
>
> **本文是汇总，不是新增研究。** 每个结论都指向原始文档或代码的 `file:line`；冲突时以原始文档为准。
>
> **路径约定**：正文与表格里的 `nfc/Xxx.java`、`service/Xxx.kt`、`gfx/Xxx.java`、`ui/Xxx.kt`、`viewer/Xxx.kt`
> 一律相对 `app/src/main/java/com/pri/nfctouchdemo/`；`AndroidManifest.xml`、`res/...` 相对 `app/src/main/`。
> 带 `/dfb/...` 前缀的是本机绝对路径（ROM 源码树、`mtk_x` 等仓库外的材料）。
>
> | 想知道什么 | 去哪看 |
> |---|---|
> | 相册取图的完整机制 / 证据 / 边界 | `docs/GALLERY_PICKUP_RESULT.md` |
> | NFC 触发点的源码级分析（28 个 OEM 回调） | `docs/NFC_TRIGGER_STUDY.md` |
> | 流程对齐（两阶段协议 / 角色状态机 / 动画） | `docs/ALIGN_PLAN_FINAL.md` |
> | 工程改动前的样子 | `docs/APP_FLOW_ANALYSIS.md` |
> | 悬浮窗三键退出 / 窗口层级 | `docs/OVERLAY_KEY_EXIT_STUDY.md` |

---

## 1. 目标

**用户在相册里看照片 / 勾照片，两台手机碰一下，正好这些照片开始传输。**

用户不需要：点「分享」、选「一碰互联」、上传、在别的应用里重选。

这是参考产品（OPPO 互传）的实际形态，也是本项目的目标形态 —— `docs/GALLERY_PICKUP_RESULT.md` 开篇。
在此之前，本工程的入口是 `HomeActivity` 先选图 → 交给 `TapService`，或从别的应用走 `ACTION_SEND` 分享进来
（`README.md` §一 的流程图）。**"碰一碰"当时不是唯一入口，"用户在看什么"这件事没人知道。**

整个对齐工作因此分成两半：

| 半边 | 问题 | 归属 |
|---|---|---|
| **流程与交互** | 碰触后播放什么、谁发送谁接收、失败了显示什么 | 已基本完成，见 §2 |
| **入口** | **App 怎么知道用户正在看 / 选中哪些照片；碰触怎么被可靠地触发** | 本文的 §3 / §4 |

---

## 2. 结论

### 2.1 今天已经能工作的

| 能力 | 状态 | 证据 |
|---|---|---|
| **相册取图**：在 Google 相册里看 / 勾照片，碰一碰正好发这些 | **已实现，实机验证**（单图 + 多选，含"3 张时间完全相同"的最坏情况） | `docs/GALLERY_PICKUP_RESULT.md` §三；提交 `9a0a969` |
| 两阶段协议（报价 → 表态 → 交货）：对方点头之前一个字节都不发 | 已实现 | `app/src/main/java/com/pri/nfctouchdemo/nfc/Xfer.java:59-63,917-1020,1029-1070` |
| 对等角色模型：谁先上滑谁是发送方，另一台原地变接收方 | 已实现 | `TapService.java:41-43,202-204`；`TapService.java:527 switchSendPageToReceive()` |
| 失败横幅（被拒 / 未响应） | 已实现 | `TapService.java:198`；`docs/ALIGN_PLAN_FINAL.md` §5.4 |
| 应用内自绘查看器（替代系统查看器） | 已实现 | `app/src/main/java/com/pri/nfctouchdemo/viewer/ImageViewerActivity.kt` |
| 动效层挂窗口层 25（`TYPE_NAVIGATION_BAR_PANEL`），Back / Home / 最近任务都能退出 | 已实现 | `service/OverlayService.kt:756-764`；`service/TaskStackWatcher.kt`；`docs/OVERLAY_KEY_EXIT_STUDY.md` §一 |
| 静默截屏做背景、无照片那台的空态 | 已实现 | `gfx/ScreenCapture.java:59,82`；提交 `ad108f5` |

> 细节不在本文重复。流程对齐的完整实施清单在 `docs/ALIGN_PLAN_FINAL.md` §5/§7，动画规格在 `docs/ANIM_IMPL_SPEC.md`。
> **注意**：上表的"已实现"指代码已落地。`docs/ALIGN_PLAN_FINAL.md` §10 还挂着一份**未完成**的
> 真机验收清单（三条通道 × 接收/拒绝、双上滑竞态、横幅 `ACTION_OUTSIDE`、混装兼容性等），
> 其中部分条目在后续提交里已被覆盖，但**没有一份逐条销账的记录** —— 需要的话按那份清单再跑一遍。

### 2.2 推荐的下一步

**做两件事，都不需要改 ROM：**

1. **NFC 触发补锁屏**（§4.3）—— 自己注册 `NfcOemExtension`，用 `onTagDispatch` 拿回当前唯一的真漏洞。
   权限已经到手（`WRITE_SECURE_SETTINGS`，uid 1000），类已经在设备的 `framework-nfc.jar` 里，槽位是空的。
   **唯一的实测缺口是"回调真的注册上了没有"，这是第一件要验的事。**
2. **相册取图去掉"用户开一次无障碍"这个前提**（§7 P0-2）—— 这是目前产品化最大的摩擦点。

---

## 3. 难题一：相册取图

### 3.1 一句话机制

> 相册的无障碍节点树里**没有文件路径 / Uri / media id**，只有每张照片的**拍摄时间（分钟精度）**
> 和它在屏幕上的位置；因此先用"时间"把 MediaStore 里的候选缩到几个，
> 再用"屏幕像素"在这几个候选里认出到底是哪一张。

`docs/GALLERY_PICKUP_RESULT.md` §一。这条链路上没有任何一步需要 Google 相册配合，也不需要它改一行代码。

节点树里到底有什么、没有什么，是这一节的全部难点 —— 代码注释把它写死在
`app/src/main/java/com/pri/nfctouchdemo/nfc/AccessibilityProbeService.java:15-27`：

- **有**：`content-desc` = `拍摄于 2026年10月14日 16:48 的照片`（分钟精度）、`getBoundsInScreen()`、`isChecked()`。
- **没有**：view id、extras、media key —— 树里**从不出现文件本身**。

### 3.2 完整流程

```
用户在 Google 相册里看照片 / 勾照片
        │
        │  （无障碍服务常驻，但只在"碰一碰"发生时才真正去读树 —— 不缓存，因此不存在"发出陈旧选择"的窗口）
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

与 `docs/GALLERY_PICKUP_RESULT.md` §2.2 的时序图一致。

**关键实现点（都可查代码）：**

| 机制 | 落点 | 说明 |
|---|---|---|
| 前台判定 | `nfc/GalleryPickup.java:60-86 isGalleryForeground()` | 查栈顶而不是信最后一次无障碍事件 —— 事件只在"有变化"时才来，重装/重启后可能一条都没有 |
| 目标解析 | `nfc/AccessibilityProbeService.java:79-107 scan()` | 全屏节点（面积 ≥ 45%）**或** `isChecked()` 的格子；两者互斥 |
| `content-desc` 正则 | `nfc/AccessibilityProbeService.java:36-37` | `拍摄于\s*(\d{4})年(\d{1,2})月(\d{1,2})日\s*(\d{1,2}):(\d{2})` —— **只认中文** |
| 时间窗 | `nfc/GalleryPickup.java:39,41` | `WINDOW_MS = 120_000`（±2 分钟）→ 空则 `WIDE_WINDOW_MS = 3_600_000`（±1 小时） |
| 候选上限 | `nfc/GalleryPickup.java:50` | `MAX_CANDIDATES = 400` |
| 像素比对 | `nfc/GalleryPickup.java:315-352 signature()/ncc()` | 中心方图 → 32×32 灰度 → 零均值单位方差 → 归一化互相关；亮度与缩放被约掉 |
| 内缩兜底 | `nfc/GalleryPickup.java:48 INSETS` | 相册会在格子里内缩照片，四个内缩比例各算一次取最好 |
| 放弃阈值 | `nfc/GalleryPickup.java:43 MIN_SCORE = 0.30f` | 候选 > 1 且最高相关 < 0.30 → **放弃该张**（`GalleryPickup.java:205-210`） |
| 并列裁决 | `nfc/GalleryPickup.java:243-247` | 相关值相同（同一张图的副本）时，取**时间差更小**的那个 |
| 可读性校验 | `nfc/GalleryPickup.java:221-233 readable()` | `openInputStream` 读 1 字节 —— 证明 `Xfer` 稍后用同一个 API 读得动 |
| 只在相册前台才介入 | `nfc/TapService.java:212-239` | 拾取是**加法**：其余情况 `beginPeerRound(payload)` 是原来那段代码，逐字节相同（`TapService.java:242-264`） |
| 解码省一次读 | `nfc/GalleryPickup.java:278-313 decodeSmall()` | 用 MediaStore 里已有的 `width`/`height` 直接算 `inSampleSize`，省掉 `inJustDecodeBounds` |

**代码落点表**（同 `docs/GALLERY_PICKUP_RESULT.md` §2.3，本次已复核存在）：

| 文件 | 作用 | 状态 |
|---|---|---|
| `app/src/main/java/com/pri/nfctouchdemo/nfc/GalleryPickup.java`（361 行） | 前台判定、时间过滤、像素比对、可读性校验 | 新增 |
| `app/src/main/java/com/pri/nfctouchdemo/nfc/AccessibilityProbeService.java`（141 行） | 连上系统无障碍，提供 `scanNow()` | 新增 |
| `app/src/main/java/com/pri/nfctouchdemo/gfx/ScreenCapture.java` | 抽出 `captureBitmap()`（`:82`），与原有 `captureBackdrop()`（`:59`）共用静默截屏通道 | 改 |
| `app/src/main/java/com/pri/nfctouchdemo/nfc/TapService.java` | `onPeer`（`:196`）插入拾取；原尾部逻辑抽成 `beginPeerRound()`（`:245`） | 改 |
| `app/src/main/res/xml/accessibility_probe_config.xml`、`AndroidManifest.xml:185-196`、`strings.xml` | 注册无障碍服务 | 改 |

HCE、`TapDispatchActivity`、角色状态机、`Xfer`、动效层、查看器、失败横幅、空态**全部未动**
（`docs/GALLERY_PICKUP_RESULT.md` §2.3）。

### 3.3 实机证据

> 以下日志与结论出自 `docs/GALLERY_PICKUP_RESULT.md` §三，设备 `Armor X17`（Android 16），
> Google 相册 `7.88.0.966185373`。触发方式统一为 DEBUG action（走真实代码路径，见 §9）。

**(a) 多选 7 张 —— 最有说服力的一组，最坏情况**

7 张里 **3 张时间完全相同**、另外 4 张也是同一分钟，即"时间完全无法区分"。
时间过滤后每一格都剩下**同样 3 个候选**，像素比对每次都能认对该格的那一张，而且正确项与错误项差距极大：

```
I GalleryPickup: 2026-10-01 16:15@[0,284][236,520] 命中 3 个候选,开始像素比对
I GalleryPickup:    候选 content://media/external/images/media/21 时间差=57s 相关=0.9315976
I GalleryPickup:    候选 content://media/external/images/media/20 时间差=54s 相关=-0.25310823
I GalleryPickup:    候选 content://media/external/images/media/19 时间差=50s 相关=0.26993287
I GalleryPickup: 选中 content://media/external/images/media/21(相关 0.9315976)
...
I GalleryPickup: 拾取结束,得到 4 张: [media/21, media/20, media/19, media/22]
```

0.93 vs 0.25 / -0.25 —— **正确项与错误项在数值上不是"接近"，是"分开"的**。
去掉重复后正好 4 张（另外 4 格的日志同上，见原文 §3.1）。

**(b) 单图 —— 含一次真实的并列裁决**

```
I A11yProbe: 相册扫描: 1 个目标 [2026-10-14 16:48@[0,0][720,1640]]
I GalleryPickup: 2026-10-14 16:48@[0,0][720,1640] 命中 5 个候选,开始像素比对
I GalleryPickup:    候选 media/45 相关=0.54581904 / media/44 相关=0.11646926
I GalleryPickup:    候选 media/43 相关=0.54581904 / media/41 相关=0.06272932 / media/40 相关=0.22104116
I GalleryPickup: 选中 content://media/external/images/media/43(相关 0.54581904)
```

`media/45` 与 `media/43` **相关值完全相同**（同一张图的副本）。时间并列时选时间差更小的：
`media/43` 差 52s、`media/45` 差 113s → 选 `media/43`，正确。
**这条保留在报告里的意义是：并列不是理论情况，它真的发生过，而裁决规则是有效的**
（对应 `GalleryPickup.java:243-247`）。

**(c) 其余已实测场景**

| 场景 | 结果 | 出处 |
|---|---|---|
| 多选 2 张（提交版本复测） | 拾取到 2 张，正确 | `GALLERY_PICKUP_RESULT.md` §3.4 |
| 拾取结果真的进入发送动效层 | `screencap` 抓到「发送给 PeerBT」+ 用户看的那张 + 「上滑发送 1 张照片」 | 同上 §3.3 |
| 按 Home 回桌面后触发 | `栈顶是 com.android.launcher3,不是 Google 相册` → 后续逐行与改动前相同 | 同上 §3.5 |
| 相册在前台但什么都没选（网格态） | 拾取到 0 个目标，且**清掉上一轮遗留的选择** | 同上 §4；代码 `TapService.java:222-228` |
| 无障碍被关掉 | 明确报错、退化成"本机没有照片 → 当接收方"，不崩不卡 | 同上 §3.6 |

**(d) 安全失败（这一条是方案能上产品的理由）**

**认不出来就不发，绝不会"发一张错的"。** 两条保证：

1. 相关值低于 `MIN_SCORE = 0.30` 且候选不止一个 → 放弃该张（`GalleryPickup.java:205-210`）；
2. 选中的 Uri 读不出来 → 放弃该张（`GalleryPickup.java:212-217`）。

失败时用户看到的是"本机没有照片"，即本机当接收方 —— 与参考产品里"没照片那台什么都不显示"一致
（`docs/ALIGN_PLAN_FINAL.md` §2.1 `[0]`）。

**(e) 性能**

| 场景 | 耗时 | 日志时间戳 |
|---|---|---|
| 拾取 2 张 | 约 0.6s | `17:14:40.104` → `17:14:40.707` |
| 拾取 7 张 | 约 3.3s | `16:55:28.094` → `16:55:31.420` |

其中静默截屏本身约 200ms。这段时间花在"第一张被认出来之前"，用户感知是碰触后动效层稍晚一点起来
（`docs/GALLERY_PICKUP_RESULT.md` §6.5）。**并行解码候选 / 合并多个目标的时间窗只查一次 MediaStore —— 均未做。**

### 3.4 被证伪 / 被否决的路（**不要重复**）

#### 3.4.1 `AssistContent`（`requestAssistDataForTask`）—— **死路，已证伪**

**当时为什么值得试**：如果 Google 相册通过 Assist 接口把"当前正在看的内容"交出来，那是最正统的做法，不用无障碍、不用截屏。

**门槛全部通过**（`nfc/AssistProbe.java:18-25`，代码注释记录了逐条核查）：

| 门槛 | 结论 |
|---|---|
| `GET_TOP_ACTIVITY_INFO` | `signature\|recents` → 平台签名应用拿得到 |
| `getFocusedRootTaskInfo()` | `enforceTaskPermission(MANAGE_ACTIVITY_TASKS)` → uid 1000 通过 |
| `OP_ASSIST_STRUCTURE` | 默认 `MODE_ALLOWED`，且服务端以 `ignoreFocusCheck = true` 调 `AssistDataRequester` |

**回调也真的到达了**：`AssistContent` 拿到了。**但 `getClipData()` 恒为 null、`webUri` / `structuredData` 也都是 null。**

唯一有内容的入口是 `AssistContent.getIntent()`，里面挂着两个 Bundle
（`nfc/AssistProbe.java:389-396`）：

```
com.google.android.apps.photos.localmedia.ui.localphotosactivity.media_collection_bundle
com.google.android.apps.photos.localmedia.ui.localphotosactivity.query_options_bundle
```

这两个 Bundle 装的是 Google 相册自己的 Parcelable（混淆过的类名），
**必须先 `Bundle.setClassLoader(createPackageContext(pkg, CONTEXT_INCLUDE_CODE|CONTEXT_IGNORE_SECURITY).getClassLoader())`
才读得动**（`nfc/AssistProbe.java:375-393`）—— 否则每次读都抛 `ClassNotFoundException`。

解开之后只有：

- `AllMediaDeviceFolderCollection{bucketId=...}` —— **"哪个相册文件夹"**
- `QueryOptions{startMedia=null}` —— **"筛选条件"**

**是"哪个文件夹"不是"哪一张"。** 死路。

> 这两个字段值的来源是**实机运行 `ASSIST_PROBE` 打印的日志**；仓库里**没有把那次日志留档**
> （`nfc/AssistProbe.java:389-396` 只包含读取这两个 Bundle 的代码，不含解出来的值）。
> 要复核就重跑一次探针（`adb shell am startservice -n com.pri.nfctouchdemo/.nfc.TapService -a com.pri.nfcdemo.ASSIST_PROBE`，
> 见 `TapService.java:105-106`），别信本文的转述。

> 附带的两个坑，将来若有人再碰这条路要记得（`nfc/AssistProbe.java:26-30`）：
> (1) `ActivityTaskManager` 只有 `getInstance()/getService()`，那两个方法在 `IActivityTaskManager` 上，且调用是**异步**的（结果经 `IAssistDataReceiver` 回来）；
> (2) 本工程的 `systemlibs/framework-header.jar` **比这台 ROM 旧** —— jar 里 `requestAssistDataForTask` 是 3 个参数，ROM 实现是 5 个，只能按参数个数在运行时挑重载。

**诊断遗留可以删**：`nfc/AssistProbe.java`（431 行）与 `TapService.java:105-106` 的 `ASSIST_PROBE` 分支。
`docs/GALLERY_PICKUP_RESULT.md` §八.3 已明确"不再投入"。**本次未删（超出范围），列为清理项。**

#### 3.4.2 驱动分享面板（`分享` → 选「一碰互联」）—— **能跑通，但被产品否决**

**这条是通的**：走 `ACTION_SEND` / `ACTION_SEND_MULTIPLE`，端到端交付了 2 个真实 Uri 并带读权限
（实机验证；**该次验证的日志未单独留档**，只在 `docs/GALLERY_PICKUP_RESULT.md` §八.2 记为"仍保留"）。
入口至今仍在 `nfc/MainActivity.java` 的 manifest intent-filter 里（`AndroidManifest.xml:81-99`，
`docs/APP_FLOW_ANALYSIS.md` §1.2）。

**为什么不是答案**：它多了一步「分享」——**参考产品没有这一步**（§1）。
保留它作为无害的降级入口，不是主路径（`docs/GALLERY_PICKUP_RESULT.md` §八.2）。

#### 3.4.3 `getTasks(keepIntentExtra=true)` —— 一行结论

`nfc/AssistProbe.java:125-170` 试过：ROM 的 `getTasks` 只给出**任务的基础 intent**（即启动器的入口 intent），
不是"当前打开的那张照片"。**读不到当前内容，别再从这条路找。**
（同 §3.4.1：结论来自实机探针日志，仓库里没有留档的日志文件；代码 `:137` 传的正是 `keepIntentExtra=true`。）

### 3.5 权限与 ROM

#### 3.5.1 权限：**零新增**（`docs/GALLERY_PICKUP_RESULT.md` §5.1）

`AndroidManifest.xml` 没有加任何 `<uses-permission>`（`:21-46` 全是既有项）。现有 uid 1000 已经覆盖全部所需：

| 能力 | 靠什么 | 验证 |
|---|---|---|
| 查栈顶应用 | `getFocusedRootTaskInfo()` → `MANAGE_ACTIVITY_TASKS` | uid 1000 通过 |
| 读 MediaStore | `READ_MEDIA_IMAGES` 被系统 `SYSTEM_FIXED\|GRANTED_BY_DEFAULT` 自动授予 | `dumpsys package` 里 `granted=true` |
| 读照片字节 | 同上 | `openInputStream` 读 1 字节校验过（`GalleryPickup.java:221-233`） |
| 静默截屏 | 原有 `ScreenCapture` 通道（`STATUS_BAR_SERVICE` 已在清单里） | 已有功能 |
| 读窗口节点树 | 无障碍服务自身 | `dumpsys accessibility` 显示已 bound |

#### 3.5.2 用户必须做的一件事：**开一次无障碍**

```
设置 → 无障碍 → 一碰互联
```

调试用（`docs/GALLERY_PICKUP_RESULT.md` §5.2）：

```bash
adb shell settings put secure enabled_accessibility_services \
    com.pri.nfctouchdemo/com.pri.nfctouchdemo.nfc.AccessibilityProbeService
adb shell settings put secure accessibility_enabled 1
```

**系统应用不能静默开启无障碍** —— `AccessibilityManagerService` 对 `setEnabledAccessibilityService`
有权限与用户确认门禁。这是 Android 的安全设计，绕过它属于改 ROM。
**产品化必须解决这一点，见 §7。**

#### 3.5.3 ROM 改动：**不需要**

整个方案跑在应用层，Android 16 + Google 相册 7.88 上即可工作。
要做成系统内置能力（免去用户开无障碍）的最小改法见 §7 P0-2。

### 3.6 边界与限制（诚实清单）

| # | 限制 | 现状 | 万一触发会怎样 |
|---|---|---|---|
| 1 | 依赖 `content-desc` 文案 `拍摄于 YYYY年M月D日 HH:MM 的照片` | Google 相册 7.88.0 实测 | 解析不到 → 该拍不拾取，**不会错发** |
| 2 | 依赖勾选态反映在 `node.isChecked()` | 同上 | 多选拾取失效，同上 |
| 3 | 全屏图片节点面积 ≥ 45% 屏幕 | 经验阈值（`AccessibilityProbeService.java:34`） | 单图拾取失效，同上 |
| 4 | **正则只认中文** | `AccessibilityProbeService.java:36-37` | **相册切成英文（`Taken on ...`）时拾取直接失效（安全失败）** |
| 5 | **`force-stop` 会清空已启用的无障碍列表** | 实测：`am force-stop` 后 `dumpsys accessibility` 的 `Enabled services:{}` 变空 | 无障碍不会自己回来，用户必须再去设置里开一次。**被 LMK 回收则不会**（系统会重新拉起） |
| 6 | 视频未测 | 只处理 `android.widget.ImageView` 且能解析出拍摄时间的节点 | 视频的 `content-desc` 文案不同 → 直接跳过（安全失败） |
| 7 | 屏幕旋转 / 分屏未测 | 相册竖屏锁定，`Target.bounds` 与截屏同用屏幕坐标 | 分屏场景未验证 |
| 8 | 超大相册未测 | 本机 MediaStore 只有 47 行；`MAX_CANDIDATES=400` 是硬上限 | 候选过多时的行为未在真机验证 |
| 9 | 跨时区未测 | `content-desc` 按本地时区解析 | `content-desc` 与 `date_modified` 是否同源未验证，宽窗口 ±1 小时可能不够 |
| 10 | 单图"左右滑到下一张再碰" | 未单独实测 | 逻辑上等价于单图（每次碰触都重扫树），但未验证 |
| 11 | **两机对接传输未做** | 本环境只能 adb 到一台设备 | 已验证到**发送侧全链路至 `PENDING` / `Xfer` 入口**，以及 `Xfer` 读取所用的同一个 API 可读；**跨机传输本身是既有功能，本次未改动，也不在验证范围内** |

第 1~3 项出自 `docs/GALLERY_PICKUP_RESULT.md` §6.2，第 5 项出自 §6.1，第 6~10 项出自 §八，第 11 项出自 §八.1。

### 3.7 数据安全

- **只读**。不删、不移、不改任何照片。代码里没有任何 MediaStore 写操作（`insert` / `delete` / `update`）。
- 动效层的「移至回收站」按钮属于产品既有 UI，本次改动未触碰，自动化测试也未点过它（点击坐标一律避开该区域）。
- 静默截屏只落在本应用 `cacheDir`，按原有 `dropOldCaches` 随轮次清理（`gfx/ScreenCapture.java:242`）。

`docs/GALLERY_PICKUP_RESULT.md` §6.4。

---

## 4. 难题二：NFC 触发

### 4.1 现状：触发的一半**本来就是系统驱动的，而且一直如此**

"能不能让 NFC 触发直接拉起悬浮窗，不用我们自己控制 NFC 的读、模拟" —— 这个问题要先拆成三层看
（`docs/NFC_TRIGGER_STUDY.md` §0.1）：

| 层 | 谁在驱动 | 现状 | 结论 |
|---|---|---|---|
| **NFC 射频 / 协议层**（发现标签、路由 AID、收发 APDU） | **系统**（`com.android.nfc`） | **当前就已经是系统驱动** | 这一层**从来不需要"我们自己控制"** |
| **业务载荷层**（交换 SSID / 密码 / 蓝牙名） | **我们**（`Bus` + `HceService` 的私有 APDU 协议） | 必须自己做，系统不认识我们的私有 APDU 格式 | 这一层**无法交给系统**（除非改成 NDEF，见 §6） |
| **触发时机层**（"碰到了" → 拉起 `OverlayService`） | 系统派发 → `TapDispatchActivity` / `HceService` → `TapService` | 已可工作 | 可以进一步提前（§4.3） |

具体地：

- **reader 侧**（我们读对方）：`AndroidManifest.xml:127-141` 声明 `TECH_DISCOVERED` + `res/xml/tech_list.xml`
  （`android.nfc.tech.IsoDep`），`TapDispatchActivity.java:12-22` 取 `EXTRA_TAG` → `startService(TapService)` → `finish()`。
  **系统派发**：`NfcService` 发现标签 → `NfcDispatcher.tryTech()` → `startActivityAsUser`。**我们没有跑任何 reader 循环。**
- **card 侧**（对方读我们）：`AndroidManifest.xml:149-159` 的 `HceService` + `AID F05052494E4643` + `requireDeviceUnlock="false"`。
  **系统在路由到这个 AID 时主动 bind 我们的服务**：`HostEmulationManager.java:1253-1260`
  `bindServiceAsUser(..., BIND_AUTO_CREATE | BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS, ...)`。
  实机 `dumpsys nfc` 确认我们的 AID 已进路由表，且是该 AID 的**默认**服务。

**我们拥有的只有私有 APDU 载荷**：`nfc/Bus.java:33-39`（`SELECT_AID_APDU` / `SW_OK` / `SW_INS_NOT_SUPPORTED`）、
`:106`（交换帧 `80 10 00 00 <Lc> <payload> 00`）、`:144`（`selfPayload()`）。

### 4.2 "服务被杀掉所以碰触没反应" —— 基本是理论风险，不是实际漏洞

`docs/NFC_TRIGGER_STUDY.md` §0.3 / §5，四条依据：

| 依据 | 内容 |
|---|---|
| (a) `BIND_AUTO_CREATE` | `HostEmulationManager.java:1253-1260` 的绑定带 `BIND_AUTO_CREATE` → 进程死了会被重新创建；`HceService.java:20-22` 的注释本身就是团队的实测记录 |
| (b) reader 侧本来就是系统 `startActivity` | `NfcDispatcher.java:716` → `:720` → `startActivityAsUser`，进程同样会被创建 |
| (c) 进程优先级极高 | 实机 `dumpsys activity processes`：`adj cur=100`、`hasStartedServices=true`、`mHasForegroundServices=true`；`dumpsys activity services`：`PROC_STATE_PERSISTENT`。原因：uid 1000 + `OverlayService` 是前台服务 |
| (d) 实机 logcat | 整条链路此刻正在正常工作 |

**真正会漏碰触的只有这几条**（`NFC_TRIGGER_STUDY.md` §5 的表）：

| 漏洞 | 机制 | 严重度 |
|---|---|---|
| **锁屏时碰触** | `NfcDispatcher.dispatchTag()` 在 `SCREEN_STATE_ON_LOCKED` 且非解锁标签时**直接 `return DISPATCH_FAIL`**（`NfcDispatcher.java:629-635`）→ `TECH_DISCOVERED` 完全不派发 → `TapDispatchActivity` 永不启动 | **高 —— 这是唯一的真漏洞** |
| HCE 侧锁屏 | 我们设了 `requireDeviceUnlock="false"`，**HCE 侧锁屏可用**；所以锁屏时"对方读我们"仍能通，是"我们读对方"不行 | — |
| NFC 被关 / 厂商读卡开关被关 | 多处 `if (!isReaderOptionEnabled())` 直接跳过轮询 | 中 |
| 用户关掉本 app 的"标签应用"偏好 | `checkPrefList()` 会过滤派发目标 | 低（实机 `TagAppPreference: pkg: com.pri.nfctouchdemo : true`） |
| 500ms 级响应超时 | `HceService.java:71-87` 把 `startService` 丢后台线程就是为了不拖过对方 ISO-DEP 超时 —— 这是"响应慢"不是"漏碰触" | 中（已有对策） |

### 4.3 可用的修法：自己注册 `NfcOemExtension`（**零 ROM 改动**）

**这是本次调研最有价值的发现：系统侧存在不需要改 ROM 的进程内钩子，我们自己的 app 现在就能注册。**

**为什么可行（静态证据已完整）：**

| 环节 | 证据 |
|---|---|
| 类在设备上 | `/apex/com.android.nfcservices/javalib/framework-nfc.jar` 里**确实含** `android/nfc/NfcOemExtension` 与 `INfcOemExtensionCallback`（拉到本地 `strings` 验证过） |
| 无 flag 闸门 | `NfcAdapter.mNfcOemExtension` 构造时**无条件**初始化（`NfcAdapter.java:947`）；`getNfcOemExtension()` 只检查 `sHasNfcFeature`（`:2921-2931`） |
| 门槛只有 `WRITE_SECURE_SETTINGS` | `NfcOemExtension.java:577` → `NfcAdapter.java:594` → `NfcService.java:3734-3741` → `NfcPermissions.java:25,85-87`（`ADMIN_PERM = WRITE_SECURE_SETTINGS`） |
| 我们已经持有该权限 | 实机 `dumpsys package com.pri.nfctouchdemo`：`android.permission.WRITE_SECURE_SETTINGS: granted=true`（来自 `sharedUserId="android.uid.system"`） |
| **回调槽位是空的** | 厂商 ST 扩展**从未注册**：`device_config get nfc enable_oem_extension` → `null`，回落资源 `R.bool.enable_oem_extension = false`（`StNfcOverlay/res/values/config.xml:11`；AOSP 默认 `NfcNci/res/values/config.xml:40` 亦为 `false`）→ `executeTaskBoot()` 不发 `ACTION_OEM_EXTENSION_INIT`（`NfcService.java:1444-1452`）。**实机 logcat 检索 `registerOemExtensionCallback` / `NfcOemExtension` / `OEM_EXTENSION_INIT` 全部无命中。** |
| `onTagDispatch` 绕开锁屏 | 调用点 `NfcService.java:6120-6125`，位于 `dispatchTagEndpoint()` 的**最开头**，**早于** `mNfcDispatcher.dispatchTag()`（`:6174`）→ 因此完全绕过了 `NfcDispatcher.java:629-635` 那道锁屏闸门。**且不需要 NDEF。** |

**注册链路：**

```
app 侧：NfcAdapter.getNfcOemExtension()            framework/.../NfcAdapter.java:2921-2931
        └─ 返回 mNfcOemExtension                    NfcAdapter.java:947（构造时无条件 new）
NfcOemExtension.registerCallback(executor, cb)     NfcOemExtension.java:578-601
        └─ 首次注册时跨进程注册                      NfcOemExtension.java:594
NFC 侧：INfcAdapter.registerOemExtensionCallback()  NfcService.java:3734-3751
        ├─ NfcPermissions.enforceAdminPermissions() NfcPermissions.java:85-87（= WRITE_SECURE_SETTINGS）
        ├─ mNfcOemExtensionCallback = callbacks     NfcService.java:3741  ← 单槽，last-writer-wins
        ├─ updateNfCState()                         :3744
        ├─ mCardEmulationManager.setOemExtension()  :3745-3747
        └─ mNfcDispatcher.setOemExtension()         :3748-3750
```

**建议用哪几个回调：**

| 回调 | 语义 | 用法 |
|---|---|---|
| `onTagDispatch(Consumer<Boolean>)` | **reader 侧**"有标签被派发了"；在锁屏闸门**之前**；不携带 `Tag` | 立刻 `consumer.accept(false)`（不吞派发，让 `TapDispatchActivity` 照常工作），同时拉起 `OverlayService` / 唤醒 `TapService` |
| `onHceEventReceived(HCE_ACTIVATE)` / `onCardEmulationActivated(true)` / `onRfFieldDetected(true)` | **卡模拟侧**"对方读卡器来了" | 同上。`onRfFieldDetected` 是**最早**的信号（射频场一开就报，早于任何 APDU） |

**代价与注意事项（必须一并说明）：**

- **回调体必须极轻**：`onTagDispatch` 等 6 个回调是 **latch 等待式**，`WAIT_FOR_OEM_CALLBACK_TIMEOUT_MS = 3000`
  （`NfcService.java:357`，`latch.await` 在 `:1113` / `NfcDispatcher.java:1454,1485`）。回调里 3 秒不返回，
  系统按 `false` 继续，但**这 3 秒里 NFC 派发线程是阻塞的**。→ **回调体只做 `startService` / `sendBroadcast`，
  全部 try/catch 包死，绝不阻塞。**
- **注册随进程消亡**：`NfcOemExtension.unregisterCallback` 的 javadoc（`NfcOemExtension.java:620`）明确
  "Callbacks are automatically unregistered when an application process goes away" → 需要开机/启动时**重新注册**，
  且必须有"没注册上"的退化路径 —— **保留现有 `HceService` → `TapService` 通路做兜底**。
  所以这是"**能力叠加**"而不是"替换"。
- **单槽**：我们是唯一 holder，last-writer-wins（`NfcService.java:3738-3741`）。
  将来若某 ROM 版本把 `enable_oem_extension` 打开（ST 扩展会注册），会互相顶掉。
- **要改的文件**（`NFC_TRIGGER_STUDY.md` §4.1）：
  1. `systemlibs/framework-header.jar` **需补 `android/nfc/NfcOemExtension*.class` 与 `INfcOemExtensionCallback*.class`**
     —— 当前 jar **不含**（实机 `unzip -l` 无 `*Oem*`）。现成来源：
     `/dfb/zhuyang/share/nfc_test/alps/out_sys/soong/.intermediates/packages/modules/Nfc/framework/framework-nfc.impl/android_common/jarjar/turbine/framework-nfc.jar`（**已确认含**）。
  2. `AndroidManifest.xml` **显式声明** `<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />`
     —— 当前没声明（`:21-46`），只是靠 uid 继承才拿到。显式声明更稳
     （**公司内已有先例**：门禁卡不是 system uid，所以它一直是显式声明的，见 §5 第 1c 条）。
  3. `nfc/TapService.java`：`onCreate`（+ `BOOT_COMPLETED`）注册回调，实现上述几个回调，并加退化路径。
  4. **ROM 侧：不需要改。**

**评级：★★★★★** —— 不需要改 ROM，权限已到手，是最快见效的路（`NFC_TRIGGER_STUDY.md` §3.1）。

**但必须实测（这是唯一的缺口）**：静态证据完整，**"`sService.registerOemExtensionCallback()` 实际返回成功"
没有在真机上验过**。建议做法：加一段探针，注册成功/失败各打一条 **`Log.i`**（本 ROM 的 logcat 默认 INFO，`Log.d` 看不见）。
判据：`logcat | grep -E "NfcOemExtension|registerOemExtensionCallback"` 有我们自己的行。
另外"锁屏下 `onTagDispatch` 是否真的触发"也未实测（静态路径成立，见 `NFC_TRIGGER_STUDY.md` §六 1~2）。

### 4.4 交付不出去的部分（物理与协议限制，**别在这上面花时间**）

1. **不可能"完全不模拟卡"。** 手机之间只有"卡 + 读卡器"这一个物理通道。
   **"模拟"这一层物理上无法移除** —— 除非引入第二条通道（网络 / 蓝牙 / BLE 发现）替代 NFC 的载荷交换，但那就不叫"NFC 触发"了。
   → "读"这一半可以完全交出去（现状已经是交出去的），"模拟"这一半做不到。
2. **不可能让系统替我们解析私有 APDU 载荷。**
   `onTagDispatch` 不携带 `Tag`（`NfcOemExtension.java:376` 只有 `Consumer<Boolean>`）；
   唯一携带数据的钩子 `onNdefMessage` **硬要求 `message != null`**（`NfcDispatcher.java:884-886`），
   而我们的握手是裸 ISO-DEP、无 NDEF。
3. **`onTagDispatch` 返回 `true` 不能用来"接管"** —— 它只是跳过派发，**不给你 `Tag`**，
   跳过之后你什么都拿不到。只能返回 `false` 当纯观察者。

`docs/NFC_TRIGGER_STUDY.md` §7。

---

## 5. 公司内部先例

对 `/dfb/zhuyang/share/mtk_x` 的普查（**本节材料此前未写入任何文档**，本次逐条核实过 `file:line`）。
这些先例决定了两件事：**"保活 / 被系统拉起"公司里怎么做**，以及**"改系统 NFC 层"是不是可接受的做法**。

> **读这一节前先记住一个区分**：公司里"用到 NFC"分三种，含义完全不同 ——
> ① **对标签 / 卡做反应**（intent-filter 派发、前台派发、HCE）→ `os_app_src` 里只有 `PriTermuxAPI` 一个，见第 4 条；
> ② **只开关 NFC 总闸**（`NfcAdapter.enable/disable`）→ 有几个 app 做，见第 4a 条；
> ③ **引用厂商 NFC 平台库做卡模拟** → 只有门禁卡，见第 1e 条。
> 下面的表按这三类标注，**不要混为一谈**（"声明了 `permission.NFC`"既不等于 ① 也不等于 ②）。

| # | 先例 | 位置（`/dfb/zhuyang/share/mtk_x/…`） | 说明了什么 |
|---|---|---|---|
| 1 | **`PrizeNFCAccessCard`（门禁卡）**，包名 `com.pri.nfcaccesscard` | `pri_project/android/pri_app/apk_oversea/PrizeNFCAccessCard/`（`AndroidManifest.xml:3`） | **公司内"系统侧钩子 → 自研 app"的现成实现**，与我们要做的事同构 |
| 1a | `android:persistent="true"` | `…/app/src/main/AndroidManifest.xml:36` | **"保活"的现成做法**：persistent 应用由系统在开机时拉起、被杀后系统会重启它 |
| 1b | 平台签名 + `LOCAL_PRIVATE_PLATFORM_APIS := true` | `…/Android.mk:17,18` | 与我们的身份路线一致 |
| 1c | **显式声明 `WRITE_SECURE_SETTINGS`**，且**没有** `sharedUserId`（自己一个 uid，不是 1000） | `…/app/src/main/AndroidManifest.xml:5` | **印证了 `NFC_TRIGGER_STUDY.md` §4.1-2 的建议**：即使不是 system uid，公司也选择**显式声明**这个权限，而不是靠继承 |
| 1d | `receiver/NfcStatusReceiver.java` 收自定义系统广播 `pri.intent.nfc.action.ADAPTER_STATE_CHANGED` | `…/receiver/NfcStatusReceiver.java:23`；manifest 声明在 `…/AndroidManifest.xml:135`；来源注释 `:19-22` 点名 `vendor/mediatek/proprietary/packages/apps/ST-Nfc/src/com/android/nfc/NfcService.java` | 反应 NFC 事件的**另一种范式**：系统侧主动广播给 app |
| 1e | **卡模拟不用自己的 `HostApduService`** —— 全树**没有** `HostApduService`，而是反射进 ST 的 `NfcAdapterStExtensions` 调 `programHceParameters(atqa, sak, uid)`；TMS 平台有对应实现 | `…/proposal/st/StProposal.java:40-100`（`Class.forName("com.st.android.nfc_extensions.NfcAdapterStExtensions")` 在 `:47`）；`…/proposal/tms/TmsProposal.java` | **"不要自己写 HCE"的先例** —— 公司在这个平台上宁愿反射进厂商 SDK |
| 1f | reader 侧是普通的 `enableForegroundDispatch` | `…/activity/BasicActivity.java:233-242`；调用点 `SetCardInfoActivity.java:178`、`CopyCardActivity.java:93` | 门禁卡场景前台就是它自己，前台派发成立；**我们的场景不成立**（碰触时前台是相册，见 §6 汇总表） |
| 1g | 引用 ST 平台库（**条件编译**） | `…/Android.mk:36,38`：`ifeq ($(PRI_NFC_ACCESS_CARD_TMS_SUPPORT),yes)` → `com.tms.nfc`（`:36`），`else` → `com.st.android.nfc_extensions`（`:38`） | 公司有"直接用厂商 NFC 平台库"的现成姿势；且**同一份 app 按平台切换两套 NFC SDK** —— 说明这条路本身就是按平台定制的 |
| 1h | 真正打包进 ROM：`PRODUCT_PACKAGES += PrizeNFCAccessCard`，由 `PRI_NFC_ACCESS_CARD_SUPPORT` 开关控制；另有 `LOCAL_DEX_PREOPT := false`、`LOCAL_PROGUARD_ENABLED := full` | `…/apk_oversea/PcbaOversea.mk:263`；`…/Android.mk:44,47` | 内置应用的完整入仓姿势（含"不做 dex2oat 预优化"） |
| 1i | **同一份 app 在树里有两份完全相同的拷贝**（Java / res 树经 `diff -rq` 一致） | `pri_project/android/pri_app/apk_oversea/PrizeNFCAccessCard/` **与** `alps/pri_app/apk_oversea/PrizeNFCAccessCard/` | 查先例时**别只查一处**；`mtk_x` 里同一 app 可能多树并存 |
| 2 | **`public/` 覆盖树上的自研 NFC 补丁**：`vendor/st/nfc/packages/modules/Nfc/NfcNci/src/com/android/nfc/NfcDispatcher.java:1007-1015`，注释 `//pri modify by zhongliming,Fix NPE when AAR app not installed blocks tag dispatch,20261008,begin/end`（把 `Tag tag = dispatch.tag;` 移进 `if (appLaunchIntent != null)` 保护内）。提交 `53468df0`，`opt:public->NfcNci->NA->解决NFC空指针异常引起NFC无作用的问题` | 仓 `/dfb/zhuyang/share/mtk_x/pri_project/android/public/`（子仓 `vendor/st/nfc`），**分支 `os_release_A17_oversea`** | **"改系统 NFC 层"在公司是被接受的实践。** 注意这是 AOSP 风格的 `packages/modules/Nfc/NfcNci`，**不是** ST-Nfc app 的那份。当前工作区切在 `os_dev_A17_oversea`，**该分支上没有这个补丁** —— 它只进了 release 分支 |
| 3 | `os_app_src`（65 个 app 目录）**零 `HostApduService`、零 `enableReaderMode`** | `pri_project/android/os_app_src/` | 结论：**"碰一碰"不是这里任何既有应用的能力** |
| 4 | 唯一通过 intent-filter 对标签做出反应的是 **`PriTermuxAPI`**（`TAG_DISCOVERED` / `NDEF_DISCOVERED` / `TECH_DISCOVERED`），配合前台派发 + 反射开关 | `…/os_app_src/PriTermuxAPI/app/src/main/AndroidManifest.xml:166-172` | 同一套派发方式我们已经在用；**没人用 HCE** |
| 4a | **只开关 NFC 总闸（①/② 的区分在这里）**：`PriThermal` 有 `private fun closeNFC()`，由温控策略 `if (policy.nfc == CLOSE_ABLE) closeNFC()` 调用；`PriFeatureX` 的 `setNFCEnabled`（反向充电用）。**两者都没有 intent-filter、没有 HCE、没有 reader mode** | `…/os_app_src/PriThermal/app/src/main/kotlin/com/pri/thermal/cooling/CoolingEngine.kt:168,1019`；`…/PriFeatureX/featureBase/src/main/java/com/pri/featureX/baseModule/Utils.java:499-512`；权限声明 `…/PriThermal/app/src/main/AndroidManifest.xml:25-26` | **⚠️ 修正一条常见的误读**：`PriThermal` **不是**"零 NFC 内容"—— 它只是**不碰标签**。判断别的 app 时同理：**"有没有 NFC 代码"要按 ①②③ 三类分别查**，只看权限声明会误判 |
| 5 | `LongShotScreen` / `PriDynamicWindow` / `PriFileManagement` **完全零 NFC 内容**（对三个目录做大小写不敏感的 `nfc` 检索，无任何命中 —— 权限、intent-filter、源码、资源全无） | 同上 | 我们参考它们的是**悬浮窗 / 动效 / 保活**，不是 NFC。**这三个的结论与 4a 不同：它们是真正的零** |
| 6 | **自定义 action 推给自家平台服务**：`pri.intent.action.adjust_adj` → `com.pri.permissioncontroller/.permission.PriPermissionManagerService`（接收端声明在 `…/PriPermissionController/app/src/main/AndroidManifest.xml:44`，实现在 `…/permission/PriPermissionManagerService.java`：action 常量 `:42`、`case KEY_ADJUST_ADJ` `:156`、**`:157` 先用 `checkCallingIsSystemApp(mPm, callingUid)` 校验调用方**）。**发送方 13 处 / 12 个 app + 1 个服务**：`PriDynamicWindow`、`LongShotScreen`、`PriFeatureX`（两个文件）、`PriGameSpace`、`PriThermal`、`PriSmartTouch`、`PriRecordAI`、`PriAiPetFloatBall`、`PriAiMemory`、`PriAiAgentChat`、`PriAiCicleSearch`（多数集中在各自的 `utils/FrameworkUtils.java`），以及 `os_app_src` 外的 `pri_app/PriSettings/src/com/pri/settings/usb/BgService.java:33`。senders 用同一套 `sendKeepAlive()` 惯用法：`new Intent("pri.intent.action.adjust_adj")` + `setClassName(...)` + extras `process_name` / `alive` | `pri_project/android/os_app_src/` | **"app 向自家平台服务报到 / 平台推事件"的第二种范式，而且是公司里的主流做法**（12 个 app 用同一个 action、同一套 extras）。**注意它自带鉴权**：接收端只认系统应用（`checkCallingIsSystemApp`）—— 我们若照抄，uid 1000 天然通过 |

> **⚠️ 一个已确认的 BUG，不要照抄**：`NfcStatusReceiver` 收的 action 与发送端**不一致** ——
>
> | 侧 | 位置 | action 字符串 |
> |---|---|---|
> | 发送端（已 patch 的 ST-Nfc） | `/dfb/zhuyang/share/nfc_test/pri_project/android/project/custom/Hammer/vendor/mediatek/proprietary/packages/apps/ST-Nfc/src/com/android/nfc/NfcService.java:2211-2213`（`new Intent(...)` + `setPackage("com.pri.nfcaccesscard")`） | `android.prize.nfc.action.ADAPTER_STATE_CHANGED` |
> | 接收端（门禁卡 app） | `…/PrizeNFCAccessCard/app/src/main/java/com/pri/nfcaccesscard/receiver/NfcStatusReceiver.java:23` 与 `AndroidManifest.xml:135` | `pri.intent.nfc.action.ADAPTER_STATE_CHANGED` |
>
> **两个字符串不同，该广播实际收不到**（除非实际部署的 ST-Nfc 与此树不同 —— 这一点**未验证**）。
> 另需注意：那个被 patch 过的 `NfcService.java` **只在 `mtk_x` 之外的两棵树里**：
> 一是上表的 `project/custom/Hammer/…`（**有**该补丁，`:2211-2213`），
> 二是 `/dfb/zhuyang/share/nfc_test/vendor_v/vendor/mediatek/proprietary/packages/apps/ST-Nfc/src/com/android/nfc/NfcService.java`
> （**没有**该补丁，`:2211` 起只有原生的 `NfcAdapter.ACTION_ADAPTER_STATE_CHANGED`）。
> 也就是说"哪棵树才是设备上真正编译进去的那份"**没有定论 —— 未验证**。
> **结论：把它当"范式参考"，不要当"可照抄的代码"。**

**对我们的直接含义：**

1. **"保活"我们有更强的答案**：`PrizeNFCAccessCard` 用 `persistent="true"`；
   我们已经是 uid 1000 + 前台服务 + `PROC_STATE_PERSISTENT`（§4.2(c)），**实测 adj cur=100**。
   是否需要升到 `persistent="true"` 可以再评估，但**不是当前的瓶颈**。
   注意 1c 说明的问题是：**门禁卡不是 system uid，所以它必须显式声明权限**；我们是 system uid，
   按 `NFC_TRIGGER_STUDY.md` §4.1-2 的建议**也应该显式声明**（现在是靠继承）。
2. **"改 ROM"这条路公司走得通**（先例 2 是已进 release 分支的真实补丁），但对本机不可行 —— 本环境不能编 ROM（§6 最后一行）。
3. **"不要自己写 HCE"这条先例我们没有跟**：我们自建了 `HceService`。
   原因见 §4.4 —— 公司的先例是**门禁卡**业务，那套 `programHceParameters(atqa, sak, uid)` 下发的是
   卡片的 ATQA/SAK/UID，**我们的私有 APDU 载荷没有等价的厂商接口**。所以这条先例**不适用于我们**，但要记录在案。
4. **"系统推事件给 app"我们有两种可选范式**：先例 1d（定向广播）与先例 6（自定义 action 到平台服务）。
   `NFC_TRIGGER_STUDY.md` §4.2 的 ROM 侧终态建议照抄的正是先例 1d 的写法。

---

## 6. 方案汇总

| 路线 | 状态 | 需要改哪里 | 代价 |
|---|---|---|---|
| **相册取图 · 无障碍树 + 时间过滤 + 像素比对** | **已实现，实机验证** | 应用侧：新增 `nfc/GalleryPickup.java`、`nfc/AccessibilityProbeService.java`；改 `gfx/ScreenCapture.java`、`nfc/TapService.java`、`AndroidManifest.xml` | 零新增权限；**用户需开一次无障碍**；拾取 2 张约 0.6s / 7 张约 3.3s |
| 相册取图 · `AssistContent`（`requestAssistDataForTask`） | **不可行（已证伪）** | — | 门槛全过、回调也到，但 `getClipData()` 恒 null；解开 Bundle 只有"哪个文件夹"（`AllMediaDeviceFolderCollection{bucketId=...}`）不是"哪一张"。**死路** |
| 相册取图 · `getTasks(keepIntentExtra=true)` | **不可行** | — | 只给任务的 base intent（启动器入口），不是当前照片 |
| 相册取图 · 驱动分享面板 | **可做（已跑通），被产品否决** | 应用侧（已有：`ACTION_SEND` / `ACTION_SEND_MULTIPLE`） | 多一步「分享」，参考产品没有这一步。保留为无害的降级入口 |
| 相册取图 · ROM 侧做成系统能力 | **可做（未实施）** | ROM 侧：`packages/modules/Nfc` 的 OEM 扩展点（仓内已有先例 `vendor/st/nfc/.../StNfcOemExtension.java`）——把"碰触瞬间前台 Activity 的媒体信息"作为 NFC 事件的一部分交给应用 | 免去用户开无障碍、免去截屏、免读别人窗口树。**本环境无法编译 ROM，仅方案建议** |
| 相册取图 · 应用侧折中（免去用户开无障碍） | **可做（未实施）** | 应用侧：做成 `defaultEnabled` 预装的系统应用能力，或首次开机引导加一步 | 不用改 ROM，但依赖 ROM 集成方式 |
| NFC 触发 · 现状 `TECH_DISCOVERED` → `TapDispatchActivity` | **已实现** | 应用侧（已有） | **锁屏时漏碰**（`NfcDispatcher.java:629-635`） |
| **NFC 触发 · 注册 `NfcOemExtension`（`onTagDispatch` / `onHceEventReceived`）** | **可做（静态证据完整，未实测）** | 应用侧：`systemlibs/framework-header.jar` 补类、`AndroidManifest.xml` 显式声明权限、`nfc/TapService.java` 注册回调 | **零 ROM 改动**；单槽 last-writer-wins；latch 3s（回调体必须极轻）；注册随进程消亡，需 HCE 通路兜底 |
| NFC 触发 · `RF_FIELD_ON_DETECTED` 广播做预热 | **可做（未实施）** | 应用侧：manifest 加 `NFC_TRANSACTION_EVENT`（`protectionLevel="normal"`）+ receiver | 极低；**但语义太弱** —— 任何射频场都触发（别人的标签、公交卡），只能预热，不能当判据 |
| NFC 触发 · 改为 Type-4 NDEF，让对方的系统自动读 | **可做（未实施）** | 应用侧：重写 `HceService` 的 T4T 状态机（CC / NDEF 文件）+ `res/xml/apdu_service.xml` 加 AID + 载荷改 NDEF 记录 | 工作量大（现载荷 <200B 放得下）；**这是唯一能把"数据读取"也交给系统的路**。实机 `dumpsys nfc` 已见对方在做 NDEF 探测（`ce_unroutable_aid { aid: "D2760000850101" }`） |
| NFC 触发 · 前台派发 `setForegroundDispatch` | **不可行** | — | 要求我们的 Activity 在前台；**碰触时前台是相册不是我们** |
| NFC 触发 · ROM 侧定向拉起（产品化终态） | **可做（未实施）** | ROM 侧：改 `vendor/st/nfc/.../NfcService.java`（`dispatchTagEndpoint`，`:6120` 附近）或 `StNfcOemExtension.java`，定向 `startServiceAsUser` / `setPackage()` 广播到 `com.pri.nfctouchdemo` | **最彻底**（app 无需常驻）；需编 ROM + 走 `public/` 覆盖树提交流程；**本环境不可行**；且此设备 NFC 是 ST vendor 定制栈，改 AOSP 上游路径不生效 |

评级与完整论证见 `docs/NFC_TRIGGER_STUDY.md` §3；相册取图部分的原始记录见 `docs/GALLERY_PICKUP_RESULT.md`。

---

## 7. 生产化建议

合并 `docs/GALLERY_PICKUP_RESULT.md` §七 与 `docs/NFC_TRIGGER_STUDY.md` §8，去重后按优先级排。

### P0 —— 决定这个功能能不能上产品

| # | 建议 | 为什么 | 在哪改 |
|---|---|---|---|
| 1 | **注册 `NfcOemExtension`，补上锁屏这个唯一的真漏洞** | 锁屏时碰触完全无反应（§4.2）；这是当前唯一会"漏碰触"的路径 | 应用侧（零 ROM 改动）。**先写探针实测"注册是否成功"** |
| 2 | **免去用户开无障碍**（最关键） | 现在是"碰触能用的前提是用户先去设置里开一次无障碍"，且 `force-stop` 后要重开（§3.6 第 5 条） | 两条路：**ROM 侧**把"读相册当前内容"做成系统服务能力（最自然的落点是 `packages/modules/Nfc` 已有的 OEM 扩展点，仓内已有先例 `vendor/st/nfc/.../StNfcOemExtension.java`）；**应用侧折中**：做成 `defaultEnabled` 预装的系统应用能力，或首次开机引导加一步。**ROM 侧部分本环境无法实施，仅建议** |

### P1 —— 直接影响可用性

| # | 建议 | 说明 |
|---|---|---|
| 3 | **`content-desc` 正则本地化** | 至少覆盖中 / 英；更稳的做法是**不解析文案**，改用"全屏节点 + 网格 `checked` 节点"的结构特征，再按 `MediaStore` 时间倒序与网格序对齐（代价：丢时间、候选集变大、变慢） |
| 4 | **`RF_FIELD_ON_DETECTED` 做预热** | 提前 `startForegroundService(OverlayService)`、预热 payload、备热点。**只做预热，不做判据**（§6） |
| 5 | **无障碍掉线自愈** | 检测到 `sInstance == null` 时给一次性提示（通知或引导页），而不是只在日志里说（`GalleryPickup.java:94-97` 现在只 `Log.e`） |
| 6 | **拾取相关值偏低时给用户一次确认** | 现在是"碰一下就发"。若最高相关落在 0.3~0.5，可在动效层加一行「将发送：<文件名>」，把不确定暴露给用户而不是硬猜 |

### P2 —— 清理与中期

| # | 建议 | 说明 |
|---|---|---|
| 7 | **删除 `nfc/AssistProbe.java` 与 `TapService` 的 `ASSIST_PROBE` 分支** | 诊断遗留（`TapService.java:105-106`），那条路已证伪（§3.4.1）。**本次未删** |
| 8 | **`AccessibilityProbeService` 改名 `GalleryWatcherService`** | 它现在不是探针了，名字是历史遗留（类名 / 配置文件 / 清单项一起改，注意已开启的无障碍会因此失效一次） |
| 9 | **中期评估 Type-4 NDEF** | 唯一能把"数据读取"也交给系统、让整个交互更接近 NFC 原生语义的路（§6 表格） |

### 组合建议（`NFC_TRIGGER_STUDY.md` §8 原文）

> 先做 **上面第 1 条 + 第 4 条的组合（纯应用侧，零 ROM 改动）**：
> 用 `RF_FIELD_ON_DETECTED` 广播做"有人靠近"的**预热**，
> 用 `NfcOemExtension.onTagDispatch` / `onHceEventReceived` 做**正式触发**（且补上锁屏这个唯一的真漏洞），
> 保留现有 `HceService` → `TapService` 通路做**兜底** —— **能力叠加，不替换**。

---

## 8. 风险与未验证

**本节刻意不做软化。凡是推断、未实测的，都在这里。**

### 8.1 未在真机验证过（按重要性排）

| # | 项 | 现状 | 怎么验 |
|---|---|---|---|
| 1 | **我们的 app 能否成功注册为 OEM 扩展** | 静态证据完整（类在 bootclasspath / 权限已授予 / 槽位空闲），**但没实测过** | 加 `Log.i` 探针，判断 `registerOemExtensionCallback` 有无命中（§4.3 末） |
| 2 | **`onTagDispatch` 是否真的在锁屏下触发** | 静态路径成立（`NfcService.java:6120` 在 `NfcDispatcher.java:629-635` 之前），**没实测** | 锁屏状态下碰两台机器，看回调日志 |
| 3 | **`onRfFieldDetected(true)` 是否在"我们被读"时触发** | 语义上覆盖，未实测 | 实机打时间戳 |
| 4 | **3000ms latch 对碰触体验的实际影响** | 回调体不阻塞则理论上无影响，未实测回调线程模型 | 给单线程 executor + 打时间戳 |
| 5 | **`onHceEventReceived` 与 `onCardEmulationActivated` 的先后与抖动** | 未实测 | 决定用哪个作卡模拟侧触发信号 |
| 6 | **多 holder 冲突** | 若某 ROM 把 `enable_oem_extension` 打开，ST 扩展会注册 → 单槽互相顶掉 | 目标 ROM 上确认该值 |
| 7 | **相册取图的两机对接传输** | 本环境只能 adb 到一台设备，**只验证到发送侧全链路至 `PENDING` / `Xfer` 入口** | 需要第二台可 adb 的设备 |
| 8 | **视频 / 屏幕旋转 / 分屏 / 超大相册 / 跨时区** | 全部未测（§3.6） | 见 §3.6 逐条 |
| 9 | **Type-4 NDEF 路（方案 B）的可行性** | T4T 文件系统实现 + 载荷大小未实测（推断 <200B 放得下） | 接管 NDEF AID 后看能否被自动读取 |
| 10 | **400 个候选上限的行为** | 本机 MediaStore 只有 47 行 | 灌一个大相册再跑 |

### 8.2 已知会失效的场景（不是"风险"，是"确定会发生"）

| # | 场景 | 后果 |
|---|---|---|
| 1 | **相册语言切成英文** | 拾取直接失效 → 退化成"本机没有照片 → 当接收方"。**安全失败**（不会发错） |
| 2 | **用户 `force-stop` 本应用** | 系统清空已启用的无障碍服务列表（实测），用户必须再去设置里开一次。**被 LMK 回收则不会** |
| 3 | **锁屏时碰触**（修法未落地前） | `TECH_DISCOVERED` 不派发，碰触无任何反应（§4.2） |
| 4 | **无障碍被关掉** | 碰一碰仍可用，但退化成"本机没有照片"；日志里有明确一行说明原因（`GalleryPickup.java:94-97`） |

### 8.3 方案本身的代价（必须明说，不要淡化）

| # | 代价 | 说明 |
|---|---|---|
| 1 | **依赖 Google 相册的无障碍文案** | 相册改一行 `content-desc`，单图/多选拾取就失效。**这是这套方案的根本脆弱点**，也是 §7 第 2 条要做成系统能力的原因 |
| 2 | **"碰一下就发"没有二次确认** | 拾取错了会直接发出去 —— 现有护栏只有"相关 <0.30 就放弃"（安全失败）与可读性校验。§7 第 6 条是补这个 |
| 3 | **拿走了 NFC 服务派发线程的 3 秒上限**（若采纳 OEM 扩展方案） | 回调体一旦变慢会影响**整个 NFC 派发**（不只是我们的功能）。必须 try/catch 包死 + 立即返回 |
| 4 | **我们成了唯一 OEM 扩展后，单槽会被顶掉** | ROM 打开 `enable_oem_extension` 时与 ST 扩展冲突 |
| 5 | **两阶段协议的另一笔成本**（与本文件主题无关，但同属本项目） | 发送方要持有连接/热点最长 60 秒（`VERDICT_WAIT_MS`）；**新旧构建必须同批升级**（`docs/ALIGN_PLAN_FINAL.md` §9.1） |

### 8.4 其它

- **`AssistProbe` 的死代码**：`nfc/AssistProbe.java` 431 行 + `TapService.java:105-106`，纯诊断遗留，可删（本次未删）。
- **`getTasks` 那条路**：`AssistProbe.java:125-170` 只给 base intent，别再试。

---

## 9. 复现步骤

出自 `docs/GALLERY_PICKUP_RESULT.md` §九。前提是**已按仓库根的编译 + 平台签名流程装好包**
（**debug 签名的包覆盖不了这个应用**）。

```bash
# 0. 装带平台签名的包
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

**期望输出：**

```
I A11yProbe: 相册扫描: N 个目标 [...]
I GalleryPickup: ... 命中 M 个候选,开始像素比对
I GalleryPickup: 选中 content://media/external/images/media/XX(相关 0.9...)
I GalleryPickup: 拾取结束,得到 N 张: [...]
I NfcTapDemo: 相册拾取: 拿到当前正在看/选中的 N 张照片,碰一碰直接发送
```

**注意**（`docs/APP_FLOW_ANALYSIS.md` §9 第 8 条）：`TapService`（`AndroidManifest.xml:144-148`）是
`exported=true` **且无权限**的，
任何人调 `action=com.pri.nfcdemo.DEBUG` 都能伪造一次碰触 —— adb 与第三方 app 走的是同一条路径。
**这是 demo 期的便利，产品化前必须收口。**

**抓相册界面的截图辅助排查：**

```bash
adb shell screencap -p /sdcard/tap.png && adb pull /sdcard/tap.png
```

---

## 附：本文与原文档的关系

| 原文档 | 本文如何引用它 |
|---|---|
| `docs/GALLERY_PICKUP_RESULT.md` | §3 是它的压缩版；**它是权威，冲突时以它为准** |
| `docs/NFC_TRIGGER_STUDY.md` | §4 是它的压缩版；**它是权威**（该文件目前尚未提交） |
| `docs/ALIGN_PLAN_FINAL.md` | §2 只做状态汇总；协议/状态机/动画细节全在原文 §3~§8 |
| `docs/APP_FLOW_ANALYSIS.md` | 提供"改动前"的基线（§1） |
| `docs/OVERLAY_KEY_EXIT_STUDY.md` | §2 的窗口层级 / 三键退出一行引用 |
| `/dfb/zhuyang/share/mtk_x` | §5 **首次成文**（该普查此前未写入任何文档） |
