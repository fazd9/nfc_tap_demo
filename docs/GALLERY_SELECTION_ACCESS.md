# 相册选中项读取:可行边界(设备 / 系统侧调查)

> **目标**:用户在 Google 相册里**看一张照片**,或者**勾选若干张**,两台手机碰一下,
> **正好这些照片**传输。用户**看不出多了一步** —— 没有分享面板、没有选择器、没有额外点击。
>
> **两条硬性要求**(任何一条不满足即不算方案):
> ① **没有用户可见的额外步骤**(自动化分享面板 = 不通过:面板是看得见的);
> ② **必须包含滚出屏幕的选中项**(勾 3 张、屏幕上只看得见 1 张 → 必须得到 3 张)。
> (无障碍树 + 屏幕像素比对就是因为不满足 ② 被否决的 —— 本文给出它**为什么**不满足,以及有没有别的办法。)
>
> **本文只回答一件事:App 怎么知道用户选了什么。** 传输引擎(`Xfer` / `TapService`)不在本文范围。
>
> **范围声明**:对 `Temp/AccessoryFramework.apk`(`com.heytap.accessory`,「快速连接」)的逆向分析
> 是**另一条独立线**,由另一个 agent 负责。**本文不涉及、也未分析那个 APK。**

---

## 一、结论(先看这五条)

1. **Google 相册的"当前选中项"没有被持久化在任何地方。** 它是内存态,只物化在两个地方:
   **(a) 界面**(渲染出来的格子)、**(b) 分享 intent**(要用户点「分享」)。
   → 实测:它的私有目录里 213 个文件、5 个数据库、41 张表**逐表核过**,没有任何一张记录"选中"。

2. **uid 1000 也读不到它的私有数据。** 双重拦截,都是实测:
   - **DAC**:`/data/data/com.google.android.apps.photos` = `drwx------ u0_a187 u0_a187`(0700,属主是相册自己的 uid 10187);
   - **SELinux**:`/system/etc/selinux/plat_sepolicy.cil:29014-29016` 里 `system_app` 域**只有** `system_app_data_file` 的
     allow 规则,**一条 `app_data_file` 的都没有**(`grep -cE "^\(allow system_app .*app_data_file"` = 3,全部命中 `system_app_data_file`)。
   - **而且即使能读也没用** —— 那里根本没有"选中"这个状态(第 1 条)。

3. **界面上能读到的,只有"已经渲染出来的格子"。**
   - 选中的格子**确实**带 `checked=true` —— **实测**(见 §四),这一点比原文档更确定;
   - 但**滚出屏幕的格子在无障碍树里完全不存在** —— **实测**:选中 1 张 → 把网格滚走 →
     树里 `checked=true` 的数量 = **0**(多选工具条仍显示 "1")。
   - 原因不是 Google 相册的 bug:RecyclerView 的离屏 item 本来就不 layout,无障碍服务拿不到,这是 Android 的既定行为。

4. **所以在"相册 = Google 相册"这个前提下,没有任何一条应用侧路线能同时满足两条硬性要求。**
   屏幕外那一条只有"程序化滚动把整库扫一遍"能覆盖,而它:
   (a) **滚动是用户看得见的**(网格自己滚);(b) **复杂度 O(相册大小)**(1000 张的相册要点几百屏);
   (c) 在这台机器上 `ACTION_SCROLL_FORWARD` **不可靠**(实测:第一次返回 true,之后返回 false 且网格不动)。

5. **唯一干净的答案是 ROM 侧:相册必须是"我们自己的"。**
   这正是 OPPO / 小米 / vivo / 华为的实际做法 —— **每一家都有自己的相册**,而且每一家的"碰一碰"
   都要求内容 App 通过 SDK 把内容**交出来**;公开资料里**没有任何一家**去读**第三方**相册的选中项
   (见 §六 引用)。参考产品视频里 A 手机用的也是**厂商自己的系统相册**(
   `docs/PRODUCT_VIDEO_ANALYSIS.md:199-207`,「A 是系统相册,B 是第三方/简易查看器」)。

---

## 二、两张硬性要求的逐条对照(总表)

| # | 路线 | 能拿到选中的哪部分 | ① 用户看不出多一步 | ② 覆盖屏幕外的选中项 | 结论 |
|---|---|---|---|---|---|
| 1 | **无障碍树**(现状实现,`nfc/AccessibilityProbeService.java`) | 已渲染的格子 + 全屏单图 | ✅ 看不见任何 UI | ❌ **不能**(实测:离屏格子不在树里) | **只满足 ①** |
| 2 | 无障碍树 + **程序化滚动**(sweep) | 全库(逐屏扫) | ⚠️ **网格会自己滚,用户看得见** | ✅ 能(如果滚动可靠) | **两条都不干净地满足** |
| 3 | sweep + **先用动效层盖住屏幕**再扫 | 全库 | ✅ 被盖住就看不见 | ✅ 能 | **唯一能同时满足的应用侧路线**,但 O(相册)且滚动可靠性未验证 |
| 4 | 驱动分享面板(`ACTION_SEND`) | 全部(真实 Uri,已跑通) | ❌ **面板看得见**(已否决) | ✅ | 只满足 ② |
| 5 | `AssistContent`(`requestAssistDataForTask`) | 只有"哪个文件夹" | ✅ | ❌ | 已证伪(`getClipData()` 恒 null) |
| 6 | 读 Google 相册私有目录 | — | — | — | **不可行**(DAC + SELinux 双拦,且数据不存在) |
| 7 | MediaStore / `picker.db` / `media_grants` | 只有"哪个包被授予过读权限" | ✅ | ❌ | 与"用户选中什么"无关(§五) |
| 8 | 系统服务(content capture / autofill / media session / ATMS) | 无 | ✅ | ❌ | 全部实测无此信息(§五.4) |
| 9 | **ROM:相册是自家的** | 全部 | ✅ | ✅ | **唯一干净的答案**(§七) |
| 10 | ROM:框架侧替 App 读 a11y 树 | 同路线 1 | ✅ | ❌ | 换了位置,没解决根因 |

---

## 三、环境与既有事实(用于复现)

| 项 | 值 | 证据 |
|---|---|---|
| 设备 | `Armor X17`,Android 16(`BP2A.250605.031.A3`,`userdebug`) | `getprop` |
| 屏幕 | 720x1640 | `wm size` |
| Google 相册 | `7.88.0.966185373`,`/product/app/Photos/Photos.apk`(ROM 预置,`PRESIGNED`) | `dumpsys package` |
| 相册 uid | 10187(`u0_a187`) | `ls -la /data/data/` |
| 我们的 App | `com.pri.nfctouchdemo`,平台签名,`sharedUserId="android.uid.system"`,**uid 1000** | `/proc/<pid>/status` → `Uid: 1000`;签名哈希 `3c1c3027` 与 `com.android.settings` 一致 |
| ADB | 本机 shell 是 **root**(`adbd is already running as root`,`context=u:r:su:s0`) | 下面的 root 操作只是**取证**,不是方案的一部分 |

> **重要**:第 6/7 条的结论**不依赖 root** —— root 只是让我们能"打开相册的数据库看一眼,
> 证明里面没有选中状态"。App 能不能读,答案已经在 §二 第 6 行(DAC/SELinux)里,与数据内容无关。

---

## 四、实测:无障碍树到底能看到什么(路线 1/2/3 的基础)

### 4.1 方法

给 `nfc/AccessibilityProbeService` 加了一个**只读诊断**(新增 `sweepAndDump()`,
由 `am startservice -a com.pri.nfcdemo.A11Y_DUMP --ei steps N` 触发,不走碰一碰主流程):

```
对每一步:
  1. getRootInActiveWindow()
  2. 遍历树,把每个 ImageView 节点的
     desc / viewId / checkable / checked / selected / clickable / bounds / extras 全部 Log.i 出来
  3. 找到第一个 isScrollable 的节点,performAction(ACTION_SCROLL_FORWARD)
  4. 900ms 后再来一步;结束时把滚动回滚,别让用户停在别的位置
```
它**只读**:不点击照片、不开菜单、不碰「移至回收站」,结束时把网格滚回原位。
(该诊断代码在本次提交里,`debug:` 提交,`TapService` 里新增 `com.pri.nfcdemo.A11Y_DUMP` 分支,
主流程一个字节没动。)

### 4.2 实测 A:选中的格子在树里**有** `checked=true`(**修正/加强原文档**)

多选态(工具条显示 "3"),探针输出:

```
I A11yProbe: ---- 步 0/0 窗口=com.google.android.apps.photos 可滚动节点=android.widget.GridView 图片节点=18
I A11yProbe:      desc=拍摄于 2026年10月16日 17:18 的照片 ... checkable=true checked=true selected=false ...
                     bounds=[484,309][720,545]
I A11yProbe:      desc=拍摄于 2026年10月16日 18:44 的照片 ... checkable=true checked=true selected=false ...
                     bounds=[265,883][501,1119]
```

同一时刻的截图核对:**这两格正是屏幕上带勾、带高亮底色的那两格** —— 一一对应。
(两次读数里 bounds 的 x 起点差 23px,是网格滚动还没完全停稳时的正常偏移,不是错位。)
随后走真实碰一碰路径(`com.pri.nfcdemo.DEBUG`),拾取结果:

```
I A11yProbe: 相册扫描: 2 个目标 [2026-10-16 17:18@[484,309][720,545], 2026-10-16 18:44@[481,883][717,1119]]
I GalleryPickup:    候选 media/82 时间差=37s 相关=0.94116384     ← 正确项 0.94,错误项 0.11/0.13/-0.03
I GalleryPickup:    候选 media/29 时间差=59s 相关=0.8149293
I GalleryPickup: 拾取结束,得到 2 张: [media/82, media/29]
```

**结论**:`isChecked()` 在**已渲染**的格子上是可靠可用的(相关值 0.94/0.81,正确项与错误项完全分开)。

> 两个坑,记下来免得后人踩:
> - **`uiautomator dump` 看到的 `checked` 是错的**(它把全部格子报成 `checked=false`)。
>   只有**自己的 AccessibilityService** 读出来才是对的 —— 排查时别拿 uiautomator 的 dump 当基准。
> - 树里能看到 `viewId=null`、`extras` 为空、`selected=false` —— 除 `checked` 外**没有别的选中线索**。

### 4.3 实测 B:滚出屏幕的选中项**不在树里**(硬性要求 ② 的死因)

同一轮多选(工具条仍显示 "1",唯一选中的是 19:00 那张),把网格往下滚几屏,
让那张移出屏幕,再读树:

```
I A11yProbe: ---- 步 0/0 ... 图片节点=16
I A11yProbe:      (checked=true 一个都没有)
```

对照:滚动前同一张的读数 ——

```
I A11yProbe:      desc=拍摄于 2026年10月16日 19:00 的照片 ... checked=true ... bounds=[484,883][720,1119]
```

**选中项在屏幕上时 `checked=true` 读得到,滚出屏幕后整个节点消失,"已选 1 张"这件事在树里归零。**
拾取会因此得到 **0 个目标** → 退化成"本机没有照片"(空态/当接收方)。
注意这是**安全失败**(不会发错),但**不满足硬性要求 ②**。

同时,树里的图片节点数(14~18)远小于库里照片数(53),
**树里只有渲染出来的那一屏多一点** —— 这就是"离线项不存在"的直接证据。

### 4.4 实测 C:程序化滚动**不可靠**(决定路线 2/3 能不能做)

- `ACTION_SCROLL_FORWARD` 打在 GridView 节点上:**第一次返回 `true` 并且网格确实滚了**,
  之后同一步骤再打**返回 `false` 且网格不动**(见 §4.2 那一轮 `步 1: ACTION_SCROLL_FORWARD -> false`)。
- 好消息:**滚动之后新进入屏幕的格子,`checked` 是跟着的** —— 同一张(18:44)滚动前后都是 `checked=true`,
  只是 bounds 从 `[242,883]` 变成 `[242,0]`。所以"sweep 能把选中项找全"在**树这一侧**是成立的,
  **不成立的是"可靠地把网格滚动起来"** 这一侧。
- `canPerformGestures`(用 `dispatchGesture` 发真实滑动)是本工程**没有开**的能力:
  `res/xml/accessibility_probe_config.xml` 里没有 `android:canPerformGestures="true"`。
  要试这条路,得改配置 + 用户再开一次无障碍。**本次未验证**。

---

## 五、逐条方案的证据(为什么其它路都不通)

### 5.1 读 Google 相册的私有目录 —— 不可行(双重拦截,实测)

```bash
# DAC
$ stat -c "%A %U:%G %n" /data/data/com.google.android.apps.photos
drwx------ u0_a187 u0_a187 /data/data/com.google.android.apps.photos

# SELinux:system_app 域只能碰 system_app_data_file
$ grep -nE "^\(allow system_app .*app_data_file" /system/etc/selinux/plat_sepolicy.cil
29014:(allow system_app system_app_data_file (dir (ioctl read write create getattr setattr lock rename open ...
29015:(allow system_app system_app_data_file (file (ioctl read write create getattr setattr lock append map ...
29016:(allow system_app system_app_data_file (lnk_file (ioctl read write create getattr setattr lock append ...
```

**结论**:我们的进程(uid 1000,域 `system_app`)**打不开**那个目录/文件 —— DAC 先拒(属主不是 1000,权限 0700),
SELinux 再拒(没有 allow 规则)。`dumpsys package` 里它那 16 个 ContentProvider 也**没有任何一个**导出到我们能读
(都是 `signature` 级或 App 内部使用;`cloudpicker` 那个要 `MANAGE_CLOUD_MEDIA_PROVIDERS`,定义在**非平台签名**的
MediaProvider 模块里 —— 平台签名的 App 拿不到)。

### 5.2 它自己的数据库里有没有"选中" —— 没有(逐表核过)

(root 取证:把 `/data/data/com.google.android.apps.photos/databases/` 拉出来只读解析。)
私有目录 **213 个文件**;5 个数据库、**229 张表/视图逐张列出并计数**
(`gphotos-1.db` 195、`media_store_extras` 25、`og_cards.db` 4、`disk_cache` 3、`local_trash.db` 2)。
与"选中/最近/分享"沾边的全部命中如下:

| 表 | 行数 | 是什么 |
|---|---|---|
| `local_media` | 52 | 本机媒体的本地索引(等于 MediaStore 的镜像) |
| `media` | 40 | 同上(云端) |
| `media_store_extension`(`media_store_extras` 库) | 52 | **每张照片的技术元数据**:burst id、fingerprint、视频宽高、微视频偏移…… |
| `share_suggestions` | 0 | 分享**建议**(推荐给谁),不是选中 |
| `cloud_picker_tombstone` / `cloud_picker_version` | 0 / 0 | 云相册 provider 的同步簿记 |
| `selective_backup` | 0 | 备份白名单 |
| `media_share_api_requests_v2` | 0 | 分享请求队列 |
| `local_trash.db` / `og_cards.db` / `disk_cache` | 0 | 回收站 / 卡片 / 缓存 |

**没有任何一张表、任何一个字段表示"当前选中/当前打开"。**
`media_store_extension` 的字段清单(实测打印):`is_animated, photo_orientation, burst_id, filename_burst_id,
burst_is_primary, drm, fingerprint, fingerprint_size, oem_special_type_id, photosphere, video_width, video_height,
video_captured_frame_rate, video_encoded_frame_rate, is_vr, is_raw, depth_type, is_microvideo, micro_video_offset, ...`
—— **全是文件本身的属性,没有一项跟"用户此刻选了什么"有关**。

> 这条结论同时也是 §5.1 的补充:**就算 ROM 放开 SELinux 让 system_app 读 app_data_file,也依然读不到选中项** —— 它没被写下来。

### 5.3 MediaStore / MediaProvider / picker(用户提到的"照片选择器 / SAF"角度)—— 与选中无关

**这是用户明确提出的假设(E),认真验过了,结论是不成立。**

- `picker.db`(系统照片选择器的库,实机**存在**:`/data/user/0/com.google.android.providers.media.module/databases/picker.db`,172KB)
  与 `external.db`(MediaStore 主库)里各有一张 `media_grants`:
  ```sql
  CREATE TABLE media_grants (owner_package_name TEXT, file_id INTEGER, package_user_id INTEGER,
                             generation_granted INTEGER DEFAULT 0, UNIQUE(owner_package_name, file_id, package_user_id) ...)
  ```
  实机 **0 行**(`picker.db` 与 `external.db` 都是 0);`picker.db` 另有 `de_selections`(同结构)**也是 0 行**。
  这两张表记的是"**哪个包**被用户授权读**哪张**照片"(picker 返回 Uri 时写),**按调用包名隔离**;
  它**不记录 Google 相册界面上的选中**。而且 AOSP 里唯一的跨包读接口
  (`MediaProvider.getReadGrantedMediaForPackage`) 用 `checkPermissionSelf()` 卡死 —— **只认 MediaProvider 自己的 appId**。
- `picker.db` 的 `media` 表(53 行)是**媒体库索引**(AOSP 里由 `PickerSyncController` 从本地 + 云端同步),
  **不是"选择记录"** —— 网上把它当成"选择数据库"是常见误解。
- `images` 视图里有 `is_favorite`(用户标的**收藏**)、`date_added`(入库时间,即"最近"的语义),
  **没有任何表示"当前选中"的列**。收藏 ≠ 选中。
- **SAF 同理**:`DocumentsProvider` 只提供文件树/打开文件,没有"当前选中"这一概念。
- **系统的照片选择器(`com.google.android.photopicker`)**:实机装的是 `PhotopickerGoogle@360911460`
  (在 `/apex/com.android.mediaprovider/priv-app/` 里),只导出一个 `androidx.startup.InitializationProvider`,
  **不对外提供任何"选择结果"查询**。它的选择结果是"回调给**发起它的那个 App**"。
  即使用 `MediaStore.QUERY_ARG_LATEST_SELECTION_ONLY`(零 UI 读取"上次选择"),
  拿到的也只是"**授予你 App 的**那批 Uri",与相册界面上的选中没有任何关系。

> **一句话**:选择器/SAF 那条路的"源"是**选择器自己**,不是**相册**。用户没走选择器,这条路上就没有数据。

### 5.4 系统服务里有没有"前台在看什么媒体" —— 实测没有

| 服务 | 实机读数 | 判定 |
|---|---|---|
| `content_capture` | `Cached services: 1` → 只有 `com.google.android.as/...AiAiContentCaptureService`;**无 session**(`no sessions`) | ContentCapture 只对**被 OEM 白名单的**服务开启,不是通用监视通道;且它看的也是**视图结构**(同样只含已渲染内容) |
| `autofill` | 只有 `com.google.android.gms/.autofill.service.AutofillService` | 与媒体无关 |
| `media_session` | **`Sessions Stack - have 0 sessions`** | 相册**没有**发布 MediaSession → 没有"当前在看哪张"的播放态可用 |
| `ActivityTaskManager` | 原文档已证伪 `getTasks(keepIntentExtra=true)`(只给 base intent) | `dumpsys activity top` 的 View Hierarchy 也只有**已 attach** 的视图(与路线 1 同源,同样丢离屏) |
| `notification` / `usagestats` | 只有包/组件级转换,无内容 | 无 |

### 5.5 分享面板(路线 4)—— 能覆盖全部,但用户看得见(已否决)

已跑通(真实 Uri,含屏幕外的项)。**但面板本身就是"多了一步且看得见"**,与硬性要求 ① 直接冲突。
参考产品没有这一步(`docs/PRODUCT_VIDEO_ANALYSIS.md` 逐帧分析:第二轮 A 是**在相册里看图直接碰**)。

### 5.6 `AssistContent` / `getTasks` —— 已证伪,不要再投入

见 `docs/ALIGNMENT_SUMMARY.md:228-287`(`getClipData()` 恒 null;解出来的只有"哪个相册文件夹")。
本次**未重复验证**(无新证据可加),维持原结论。

---

## 六、别的厂商怎么做的(网络调研,含引用)

**结论:所有厂商的"碰一碰"都是"内容 App 把内容交给系统/SDK",没有一家去读第三方相册的选中项。**

| 厂商 / 标准 | 机制 | 引用 |
|---|---|---|
| **小米 贴贴分享(TapShare)** | 提供 AIDL SDK:**App 自己**在回调里构造 `TapShareSendBean`(预览图/标题/透传参数)交给小米互传 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2135> |
| **华为 Share Kit(碰一碰)** | 宿主 App 注册碰一碰监听,回调里自己构造 `systemShare.SharedData` 再 `share()` | <https://developer.huawei.com/consumer/cn/doc/doccenter-capabilities/share-access-one-step> |
| **ITGSA / 金标联盟统一标准** | 分工原文:「**应用负责调用 SDK,SDK 负责适配,系统负责执行动画**」 | <https://www.ithome.com/0/978/157.htm> |
| **互传联盟「碰一碰」规范** | NFC 握手 + Wi-Fi P2P;内容是「用户**已选中**的照片/文档」由**调用方**发起 | <https://mobile.pconline.com.cn/2182/21821451.html> |
| **Samsung Quick Share** | 文档流程:相册 → 选中 → **分享图标 → 分享面板 → Quick Share** | <https://news.samsung.com/za/simpler-and-faster-file-share-with-galaxys-quick-share> |
| **AOSP 的历史答案 Android Beam** | **相册**调 `setBeamPushUrisCallback(...)`,`SelectionManager` 里把选中项(`getSelectedShareableUris()`)交给框架 | <https://android.googlesource.com/platform/packages/apps/Gallery2/+/0bd4d095a/src/com/android/photos/SelectionManager.java> |

**方向是反的**:Beam / 小米 SDK / 华为 SDK / ITGSA,**都是"App 推给系统"**,系统只负责搬运。
**公开资料里没有任何一例"厂商读第三方相册的选中项"。** 厂商能做到"相机/相册里直接碰",
是因为**那个相册就是它自己写的**(OPPO 互传 + OPPO 系统相册 = 同一家)。
这与 `docs/PRODUCT_VIDEO_ANALYSIS.md:201-207` 的逐帧观察一致(参考产品用的是**厂商自己的系统相册**)。

**Google 相册自己的立场**:2025-03-31 起 `photoslibrary.readonly` / `sharing` 等 Library API 被移除
(调用返回 `403 PERMISSION_DENIED`),只剩下**用户每次手动选**的 Picker API
<https://developers.google.com/photos/support/updates>。
即"让 Google 相册把选中交出来"这件事,在官方口径上是被**收紧**的,不是被开放的。

---

## 七、ROM 侧方案(本环境不能编 ROM,**只提方案,未实施**)

> 先摆一个结构事实:**这台 ROM 上没有任何框架组件"持有"相册的选中项**
>(源码普查见下),所以 ROM 不能"把它读出来" —— 只能**换掉数据源**。

**源码普查结论(只读调研,`/dfb/zhuyang/share/nfc_test/alps` 等树):**
- 本树**没有厂商相册**:`MtkGallery2` 在 `device.mk` 里被整块注释掉
  (`/dfb/zhuyang/share/nfc_test/vendor_v/device/mediatek/vendor/common/device.mk:3020-3025`);
- **Google 相册是 GMS 预置 APK**,并且**显式覆盖**掉 AOSP 相册:
  `/dfb/zhuyang/share/nfc_test/alps/gms/gms_202608/apps/Photos/Android.mk:21` 的
  `LOCAL_OVERRIDES_PACKAGES := Gallery2 MtkGallery2 ...`;`LOCAL_CERTIFICATE := PRESIGNED`
  → **ROM 改不了它一行**;
- AOSP `Gallery2` 的 `SelectionManager`(即 Beam 那套"相册发布选中")**在树里,但设备上没装**;
- `media_grants` 的跨包读接口存在,但被 `checkPermissionSelf()` 锁死(只认 MediaProvider 自己的 appId);
- `NfcOemExtension` 的注册链路在源码里齐全(28 个回调,`onTagDispatch` / `onHceEventReceived` /
  `onRfFieldDetected` 都在),但**本 ROM 上是关的**(`enable_oem_extension=false` 三处;
  `StExtension/service/Android.bp` 整块注释掉)→ 且**它也不产生"选中项"这个数据**,只是把问题换个位置。

### 方案 R1(推荐):**把相册做成自家的** —— 也就是 OPPO 的做法

- **改哪里**:ROM 里内置一个自研相册(或把自研相册设为默认相册),
  在它内部**已经知道选中项**的地方(多选管理器的选择集、全屏查看器的当前 item)直接发布出来。
  AOSP 已有现成范式可抄:`Gallery2` 的
  `packages/apps/Gallery2/src/com/android/photos/SelectionManager.java`
  (`getSelectedShareableUris()` / `setSelectedSharingEnabled`)。
- **读什么**:相册内存里的选择集(它本来就是权威来源),不用猜、不用像素比对。
- **怎么到我们 App**:三种现成姿势,按公司内先例选:
  1. **定向广播**(公司先例:`ST-Nfc` 的 `android.prize.nfc.action.ADAPTER_STATE_CHANGED` + `setPackage(...)`,
     `pri_project/android/project/custom/Hammer/vendor/mediatek/proprietary/packages/apps/ST-Nfc/src/com/android/nfc/NfcService.java:2210-2215`);
  2. **自家平台服务**(公司先例:`toolservice` / `IPriToolService` +
     `PriAppSwitchManager.registerAppSwitchObserver`,
     `pri_project/android/public/frameworks/base/core/java/com/pri/app/PriAppSwitchManager.java:48,54`
     与 `pri_project/android/public/vendor/prize/framework/base/services/core/java/com/pri/server/PriSystemServerImpl.java:151`);
  3. 相册侧直接 `ContentProvider` 暴露一个"当前选择"查询(要有 signature 权限)。
- **碰触时要交换什么**:与今天**完全一样** —— 两台手机之间仍然只交换对端信息(`Bus` 的私有 APDU 载荷),
  **照片 Uri 根本没有跨机传输过**;变的是"本机从哪儿知道用户选了什么"。
  所以这条改动**不触碰 `Xfer` / 两阶段协议 / 动效层**,只是把 `GalleryPickup` 的输入源换掉。
- **代价**:要做(或集成)一个相册 App;用户得用它而不是 Google 相册(产品定位问题)。
- **两条硬性要求**:① ✅(用户无感);② ✅(选择集本来就不受屏幕限制)。

### 方案 R2:框架侧替 App 读无障碍树(不推荐,只解决"免开无障碍")

用 `Pri*Service` 那套(或 `AccessibilityManagerService` 内部)在框架里读前台 App 的节点树,
把自己的 a11y 依赖去掉。**但它读的还是同一棵树** → **依然只覆盖已渲染的格子**,
所以硬性要求 ② 依旧不满足;要覆盖离屏还得在框架里做滚动,而**滚动这件事用户照样看得见**
(除非此时前台正好是我们的动效层)。
→ **不解决根因,不建议作为主方案。**

### 方案 R3:放开 SELinux 让 system_app 读 `app_data_file`(不要做,而且没用)

技术上 ROM 可以改 sepolicy,但:
(a) 这会把**所有** App 的私有数据对 system_app 敞开,是安全边界的整体拆除;
(b) **最关键的:Google 相册根本没把选中项写进磁盘**(§5.2 逐表核过)→ **放开了也读不到**。

### 方案 R4:`NfcOemExtension` 做触发(与"读选中"正交)

它解决的是"锁屏时碰触漏触发"(`docs/ALIGNMENT_SUMMARY.md` §4.3),**不产生选中项数据**。
本 ROM 上还是关的。**不要把"读相册选中"这件事挂到它身上。**

### 顺带:P0 级、零 ROM 改动的一条(去掉"用户必须开无障碍")

现状最大的摩擦是"用户得去设置里开一次无障碍"(且 `force-stop` 后失效)。
源码调研发现**公司里已有现成做法**:系统 uid 的 App 自己写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
把自家的无障碍服务打开 —— `pri_project/android/os_app_src/PriSmartLinkKey/app/src/main/java/com/pri/smartlinkkey/utils/AccessUtils.java:63-77,79-119`
(`sharedUserId="android.uid.system"`,`AndroidManifest.xml:5`)。
框架侧对应路径:`AccessibilityManagerService` 对该 setting 注册了 ContentObserver,
变更后走 `onUserStateChangedLocked()`,**这条路径里没有用户确认对话框**
(注意 `setEnabledAccessibilityService` 这个 API 在本树里已不存在)。
我们已持有 `WRITE_SECURE_SETTINGS`(`dumpsys package` 里 `granted=true`)。
**本次未在本机实测这一条**(它只解决"要不要用户手动开无障碍",不解决"能不能拿到选中项"),列为 P0 待验。

---

## 八、推荐方案

1. **产品定位问题优先解决**:如果"碰一碰传图"要求**同时**满足两条硬性要求,
   那**必须让相册是我们自己的**(方案 R1)—— 这也是参考产品(OPPO)能成立的唯一原因。
   Google 相册这条路,在 Android 的既有边界下**没有解**。
2. **如果必须支持 Google 相册**,现实选项只有两个,都要用户/产品接受代价:
   - **接受"只发屏幕上看得到的"**(路线 1,现状):满足 ①,不满足 ②;
     建议补一条**安全网**:多选态下读工具条上的**已选张数**
     (多选工具条里有一个 `TextView` 显示当前计数,`uiautomator dump` 里可见,如 `text="1"`),
     与实际拾取到的张数**比对,不一致就不发**(把"默默少发几张"变成"安全失败")。**本次未改**,列为建议。
   - **接受"网格自己滚一下"**(路线 3):满足 ②,① 取决于能否用动效层把滚动盖住;
     代价是 O(相册大小) + 滚动可靠性未验证(§九)。
3. **触发侧**(锁屏漏碰)按 `docs/ALIGNMENT_SUMMARY.md` §4.3 推进,与本文无关。

---

## 九、未验证(诚实清单)

| # | 项 | 现状 |
|---|---|---|
| 1 | **`dispatchGesture` 能不能可靠地驱动相册滚动** | 未试。需要 `android:canPerformGestures="true"`(改配置后无障碍要重开一次)。
若要做路线 3,这是**第一个要验的**。 |
| 2 | **动效层盖住时,滚动能不能"透传"到下面的相册** | 未试。我们的动效层是 `TYPE_NAVIGATION_BAR_PANEL`(层 25)。
理论:若它 `FLAG_NOT_TOUCHABLE`,`dispatchGesture` 注入的滑动会落到下面的相册;
若它可触摸,滑动会被它吃掉 → 安全失败。**未实测**。 |
| 3 | **`ACTION_SCROLL_FORWARD` 为什么第一次成功、之后失败** | 未定位(可能与 scrollable 节点是 GridView 包装层、真正的 scroller 是里层 RecyclerView 有关)。 |
| 4 | **多选态读到的 `checked` 是否存在"滞后"** | 观察到一次:多选刚建立约 1 分钟时读到的目标数与工具条计数不一致(一次 0/1,一次 2/3)。
干净复现(单张、网格静止)时**完全一致**。怀疑与"节点属性按需填充"有关。**未定性**。
→ 这也是第 2 条"建议补计数比对安全网"的理由。 |
| 5 | **App(uid 1000)自己写 `ENABLED_ACCESSIBILITY_SERVICES` 在本机是否无对话框生效** | 未实测(§七末)。 |
| 6 | **大相册下的 sweep 耗时** | 本机 MediaStore 只有 53 张。按 14~18 格/屏、900ms/步估值,1000 张 ≈ 1 分钟量级 —— **未实测**。 |
| 7 | **视频 / 分屏 / 横屏** | 沿用原文档的未测项(`docs/GALLERY_PICKUP_RESULT.md` §八)。 |
| 8 | **`NfcOemExtension` 注册实测** | 沿用 `docs/ALIGNMENT_SUMMARY.md` §8.1 第 1 条,本次未做。 |

---

## 十、本文用到的实测命令(供复现)

```bash
A="adb -s 333333333333337"

# 1) 无障碍树诊断(本次新增的只读探针;steps=0 表示不滚动)
adb shell am startservice -n com.pri.nfctouchdemo/.nfc.TapService \
    -a com.pri.nfcdemo.A11Y_DUMP --ei steps 0
adb logcat -d | grep A11yProbe

# 2) 走真实碰一碰路径(拾取 + 后续流程)
adb shell am startservice -n com.pri.nfctouchdemo/.nfc.TapService \
    -a com.pri.nfcdemo.DEBUG --es payload 'n=TestPeer|b=PeerBT|t=4321' --es role reader
adb logcat -d | grep -E "A11yProbe|GalleryPickup|相册拾取"

# 3) 取证:相册私有目录的 DAC / SELinux
adb shell stat -c "%A %U:%G %n" /data/data/com.google.android.apps.photos
adb shell 'grep -nE "^\(allow system_app .*app_data_file" /system/etc/selinux/plat_sepolicy.cil'

# 4) 取证:系统服务里有没有前台媒体态
adb shell dumpsys content_capture | head -40
adb shell dumpsys media_session | head -30

# 5) 取证:MediaProvider 的 picker.db / media_grants(需 root,仅取证)
adb shell 'cp /data/user/0/com.google.android.providers.media.module/databases/picker.db /data/local/tmp/'
adb pull /data/local/tmp/picker.db
#   然后 sqlite3 查:media_grants / de_selections 都是 0 行,且按 owner_package_name 隔离
```

> ⚠️ **数据安全**:本文所有操作**只读**。全程没有点过「移至回收站」,没有删除/移动/修改任何照片,
> 诊断探针结束时会把网格滚回原位。相册数据库的 dump 只在服务器本地 `/tmp` 暂存用于解析。
