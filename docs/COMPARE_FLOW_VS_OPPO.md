# 我们的 NFC 碰一碰 vs OPPO Quick Connect —— 流程逐阶段对照

> 对象：`com.pri.nfctouchdemo`（分支 `nfc_tap`）↔ `com.heytap.accessory` 16.35.0（OPPO Quick Connect）。
> 范围：**只谈功能与流程**，不讨论动画（COE / 水波 / 粒子 / 卡片飞走均不在本文）。
> 约定：
> - “我们”的每一个结论都带 `file:line`（`app/src/main/java/com/pri/nfctouchdemo/` 下的相对路径）。
> - “OPPO”的每一个结论都引 `docs/OPPO_TECHNICAL_DETAIL.md` 的章节号；不确定处标 `[未确定]`。
> - 本文不重新反编译 OPPO，除非文档缺关键细节（本次只补了 `compat_tap_share_image_pkgs` 的资源值，见 §5）。
> - 代码路径简写：`nfc/` = `app/src/main/java/com/pri/nfctouchdemo/nfc/`，`service/` = `.../service/`，`viewer/` = `.../viewer/`。

**一句话结论**：我们的“碰 → 上滑 → 接收方点接收 → 传图”在**字节层的顺序**上已经对齐 OPPO 的核心原则（**同意之前不发整图、成功静默失败高调**），但**角色裁决、接收方超时/退出、发送内容的锁定**三处仍是“靠时序碰运气”；OPPO 用显式契约链把这三件事都协议化了。相册取图则是两条完全不同的路线：OPPO 是“相册把数据推给框架”，我们是“自家相册推 + 三方相册读屏兜底”。

---

## 1. 端到端流程分阶段对照

### 1.1 总表

| 阶段 | OPPO 做法（§） | 我们做法（file:line） | 差异 | 对用户/功能的影响 |
|---|---|---|---|---|
| **1. 触发：NFC 发现/读卡** | 发卡侧 `OplusHostApduService`：AID `F000004F50504F2E434F4D`，`SELECT` → `9000`，`00CA0101` 取数据；读卡侧 `NfcDiscoveryService` 由系统 NFC 服务的私有广播 `com.oplus.nfc.action.DIRECT_DISCOVERED` 拉起，处理完立刻 `stopSelf()`；`requireDeviceUnlock=true`（**锁屏不可用**）（§3.4 / §3.5 / §6.3.3 / §11.1）。两台手机都有发卡与读卡组件，谁读谁被读由系统 NFC 决定 [推断] | 读卡侧 `TapDispatchActivity` 挂 `TECH_DISCOVERED`（`app/src/main/AndroidManifest.xml:127-141`），收到 `Tag` 后转 `TapService` 并秒退（`nfc/TapDispatchActivity.java:15-21`）；`TapService.handleTag` 走 IsoDep：`SELECT AID` → `EXCHANGE`（`nfc/TapService.java:517-542`）。发卡侧 `HceService` AID `F05052494E4643`（`app/src/main/res/xml/apdu_service.xml:8`），`SELECT` 回 `9000`、`EXCHANGE` 回本机 payload（`nfc/HceService.java:33-68`）；`requireDeviceUnlock=false`（`apdu_service.xml:4`）。两端都同时具备读卡与发卡能力。条件：NFC 打开 + 标签是 IsoDep（`app/src/main/res/xml/tech_list.xml:3-5`）+ NFC 权限（`AndroidManifest.xml:21`）；传输另需 Wi-Fi（`nfc/Xfer.java:371-379`） | OPPO 依赖**改过的系统 NFC 服务**发私有广播；我们用公开 `TECH_DISCOVERED`，零 ROM 改动（§11.1 同结论）。OPPO 锁屏全链路不可用；我们发卡侧确定可用，但读卡侧要拉起 Activity + 悬浮窗，**锁屏端到端是否可弹 [未确定]** | 我们部署门槛低；锁屏场景可能比 OPPO 多一个可用入口（需真机确认）。OPPO 的触发是系统级、更“原生”，但离开它的 ROM 就不成立 |
| **2. 碰后第一屏（空态/有图态）** | 触碰后两端先出触碰动效（§5 第 2 行）；发送方在配对链②之后、进入③时弹**确认卡**（缩略图 + 文案，提示上滑，§5 第 4 行）；接收方在发送方上滑后才出接收卡（§5 第 5 行）。发送方的确认要等相册 `startShare` 交数据（`await shareStartDeferred`，§3.1③ / §4.6 第 5 步）。文档**没有描述“无图也弹空态”**；从“先有数据再有确认卡”的顺序看，无内容时更可能是超时而不是空态页 `[未确定]` | 碰后 `beginPeerRound`：有照片 → 立即弹发送页（`nfc/TapService.java:324-325`，`339-366`）；无照片 → 立即弹**空态接收页**（`TapService.java:327-329`，`571-592`；`service/OverlayService.kt:285-303`，`1285-1303`，`waitingForPeer` 把标题/卡片/按钮/状态行全部压住）。真正的接收卡要等对方 `TYPE_META` 才出（`TapService.java:401-416`，`549-557`） | 我们**碰一下必有一屏**，且是有图/无图两种确定形态；OPPO 以内容为前提，文档未给空态 | 我们的反馈更确定，不会“碰了没反应”；但无照片方会先看到一屏等待页，如果对方始终不发送，这屏会一直留着（见阶段 9）。OPPO 没有这个“空等”问题，但也可能在“相册没交数据”时表现为超时/无卡 |
| **3. 角色决定（谁是发送方/接收方）** | 显式协议：`NfcPairRole{SEEKER,PROVIDER}` 决定谁先发（§3.1①）；`InternalNfcEventRole{SENDER,RECEIVER}` 在 `NfcDataSyncContract` 的 `nfcEventSender` / `nfcEvent` 里交换并裁决（§3.1② / §3.6）；传输链另有 `NfcShareRole`。角色来自“这次要干什么”（相册/分享场景），不是“谁先动手”。精确平局规则 `[未确定]` | 碰后**不裁决**：两端都 `startAsReceiver()`（`nfc/TapService.java:318`），都 `setCanSend(hasPending())`（`:322`），都 `startHotspot()`（`:323`）；有照片的弹发送页，没照片的弹空态（`:324-329`）。**上滑那一刻**才 `setSenderRole()`（`TapService.java:180-181`；`nfc/Xfer.java:450-453`）。`Bus.lockRole()` 是**进程内** 30s 锁（`nfc/Bus.java:270-283`），只挡同一台手机的重复碰触，不跨设备 | OPPO 是“事件决定角色”；我们是“用户动作决定角色”，存在**同时上滑**竞态（详见 §3） | 正常一先一后时体验等价；两人几乎同时上滑时会退化成双方都等不到回执（详见 §3.3）。这是目前流程上最硬的缺口 |
| **4. 第一次确认（要不要传）** | 配对链③发送方确认（`MESSAGE_TYPE_NFC_SENDER_CONFIRM=112`，10s，上滑；手机→平板可自动接受）＋配对链④接收方确认（`113/114`，23s；**同账号自动同意**，跳过接收确认）（§3.1③④ / §3.10 / §3A.3） | 发送方上滑一次（`nfc/TapService.java:176-192`；动效层回调见 `service/OverlayService.kt:674`）；接收方点“接收/拒绝”一次（`TapService.java:193-225`；`nfc/Xfer.java:1075-1112`）。发送的第一帧是 `TYPE_META`+`TYPE_PREVIEW`，不是整图（`Xfer.java:957-972`） | 我们都是“人点一次”；OPPO 第一次确认侧重“建立信任/建连”，且同账号/手机→平板会免确认 | 我们少一步且无账号体系；代价是接收方**每次都被问**，也没有“同账号免确认”的捷径。OPPO 在自家生态里能做到“碰一下就传完” |
| **5. 第二次确认（开始传）** | 传输链②发送方确认（65s）＋③接收方确认（65s）（§3.2 / §5 第 8-9 行）。文档明确：第一次确认用于建立信任与连接，第二次用于**锁定内容**——因为相册是后交数据的，两次之间内容可能变化（§5） | **没有第二次确认**。收到 `TYPE_ACCEPT` 就直接发 `TYPE_FULL × N`（`nfc/Xfer.java:973-1010`）。但若上滑时链路未就绪，真正发送用的是**就绪时的 `PENDING`**（`nfc/TapService.java:180-190`，`370-389`），不是上滑瞬间的快照 | OPPO 两次人确认；我们一次。OPPO 第二次的真正价值是“内容锁定”；我们顺带省掉了“锁定” | 少一次确认对“相册里选好就碰”是明显更好的体验；但上滑到链路就绪之间若相册改选/清空，可能发出**与用户上滑时看到的不一致**的照片（窗口较小，见 §4） |
| **6. 传输中（进度/状态展示）** | 真实字节交给 `com.coloros.oshare`；`onPercentChange` 只打日志 + 取消 25s 看门狗，**UI 进度环另有驱动**（§6.4）；接收端进度心跳 10s，两轮无变化判 `BT_DISCONNECTED`（§6.5）；传输契约超时 24h（§3.2） | 发送方：上滑后动效页按动效自己的结束逻辑摘掉（`service/OverlayService.kt:670-674`），`TapService` 只在状态行写“正在发送…”（`TapService.java:376`），**没有字节进度**；完成后 `onImageSent` 清 `PENDING`、隐藏窗口（`:457-468`）。接收方：每张整图到达后更新状态行“已收到 i/n 张”（`TapService.java:430-431`），卡片照片随预览/整图替换（`:391-399`，`:418-432`），并用 `TYPE_META` 的 `mExpect` 校验数量（`nfc/Xfer.java:123`，`:831`，`:872`） | OPPO 有百分比/心跳/停滞检测；我们只有张数、没有传输超时、没有停滞检测 | 用户能知道“收到了几张”，但链路假死（TCP 半开、Wi-Fi 掉）时界面不会动，也没有自动失败；OPPO 至少会判 `BT_DISCONNECTED` |
| **7. 成功（谁跳转、跳到哪、看什么）** | 发送方显示“已发送”；接收方跳到**目标 App 的“无缝看”页面**：`Intent(com.oplus.interconnect.action.SHARED_DATA)`，`setPackage` 来自发送方 `SharedData.packageNames`，`resolveActivity` 门控，`share_data`（protobuf）+`share_data_json` 双份；没有目标 App 就换下一个候选包名，全失败打开文件管理器（§4.7 / §5 第 11 行）。框架自己不落盘，字节由 OShare 写（§4.7） | 发送方**不跳转**，留在相册；清 `PENDING`、隐藏悬浮窗（`nfc/TapService.java:457-468`）。接收方打开**自家查看器** `ImageViewerActivity`，只带本轮 `mSavedUris`（`TapService.java:435-455`，`646-665`；`viewer/ImageViewerActivity.kt:130-140`，`429-442`；`AndroidManifest.xml:115-121`），查看器内可左右翻、Back 退出（`ImageViewerActivity.kt:113`） | OPPO 把落地交给外部 App；我们自带查看器，不依赖谁安装了什么 | 我们“一定看得到”，OPPO 依赖接收端 App/文件管理器；OPPO 能把内容交给业务 App（继续编辑/分享），我们只是看图。发送方我们都保持安静（OPPO 有“已发送”文案，文档 §5 第 11 行） |
| **8. 失败/超时/拒收** | 配对链每一步有**独立超时和专属异常**（6/6/10/23/6/6s）；传输链 15/65/65/24h；传输结果区分 7 种（含 `RECEIVER_TRANSFER_PERMISSION_GRANT_FAIL`）；主链路**不重试**，失败 toast“操作失败，请稍后重试”，让用户重碰；连接层另有大量重试（§3.9 / §3.2 / §6.5） | 发送方等回执 60s（`nfc/Xfer.java:63`，`:1030-1054`）→ `onVerdictTimeout` 横幅（`TapService.java:507-513`）；对方拒绝 → `onPeerRejected` 横幅（`:495-501`）；链路失败 → `onXferFailed`，仅在已上滑时出横幅（`:470-483`）。接收方：对方断开且一张未收 → `onOfferExpired` 收卡并解锁（`:486-493`；`Xfer.java:879-890`）。**接收方在“没有连接/没有报价”时没有超时**。失败横幅 5s 自动消失、可滑动/点外关闭（`service/OverlayService.kt:882-891`，`:918-920`，`:1375-1377`） | OPPO 能精确到“哪一步超时/授权失败”；我们只有“拒收/未响应/链路失败”三类粗粒度原因 | 用户能分清“被拒绝”和“没响应”（比只有一句“失败”好），但分不清“连不上/对方热点没开/等回执超时”；接收方可能被永久留在等待页 |
| **9. 取消/退出（Back/Home/Recents/超时）** | 取消是横切能力：`NfcPairCancellation` 打断“当前卡住的那个契约”并 `job.cancel()`（§3.3 取消路径）；各步超时自动收尾；卡片等用户 61s（§6.5）。Back 的具体实现文档未展开 `[未确定]` | 接收页按 Back = 发 `REJECT` 再收窗（`service/OverlayService.kt:586-600`）；发送页按 Back **只收窗、不通知 NFC 层、不解角色锁**（`:593-600`），下一碰在 30s 内会被 `Bus.lockRole` 忽略（`nfc/Bus.java:275-283`；`TapService.java:319`）。Home/Recents：只有手势导航（`navigation_mode==2`）才自动收页（`OverlayService.kt:602-619`）；三键导航忽略。通知里有“隐藏”和“触摸穿透”（`OverlayService.kt:169-184`，`:554-575`）。接收方无连接/无报价时**没有自动退出** | OPPO 的超时是流程的一部分；我们的接收方没有“等不到就收”的兜底，发送页 Back 还会留下 30s 死区 | 接收方可能被整屏悬浮窗卡住，只能主动 Back（Back 会当拒绝，算可恢复）；发送者误按 Back 后 30s 内再碰会“没反应”。三键导航下 Home 也收不掉 |
| **10. 相册取图** | 数据源自报（share agent）：`registerShareAgent(params, callback)` 建长连接 → 框架推 `registerEvent(CONTRACT_START, role)` → 相册 `startShare(InternalSharedDataParams)`；卡片缩略图由源 App 给 Bitmap（§4.6 / §4.2 / §4.4）。白名单硬编码为 `com.coloros.gallery3d` / `com.oneplus.gallery`（§2；资源值见 `_Temp/afk/jadx_out/resources/res/values/arrays.xml:16-19`），第三方包名不在表里就拿不到 NFC 事件（§4.6） | 自家相册：显式 `ACTION_SEND_MULTIPLE` 发布 Uri 列表 + 显式 `ACTION_CLEAR_SELECTION` 撤销；`sPublished` 优先于读屏（`nfc/TapService.java:58-92`，`:156-174`，`:269-273`；参考实现 `Temp/debuggallery/src/com/pri/debuggallery/MainActivity.java:267-308`）。第三方 Google 相册：读屏拿 `content-desc` 的拍摄时间 + 勾选态 → MediaStore 时间过滤 → 截屏像素比对（`TapService.java:278-305`；`nfc/GalleryPickup.java:67-219`；`nfc/AccessibilityProbeService.java:36-107`） | OPPO 只有“自家相册主动推”这一条路，但**极其可靠**（屏幕外、多选、语言都无关）；我们是“自家推 + 三方读屏”，覆盖更广但依赖屏幕内容与语言 | OPPO 的相册体验是产品级；我们的三方兜底只在“Google 相册在前台、中文、无障碍开启、且目标在可视树里”时成立。实测第三方（非白名单）无法走 OPPO 的 share-agent 路；我们的读屏路则在屏幕外/英文/无障碍关闭时失效（详见 §5） |

### 1.2 逐阶段的“两端各自看到什么”

| 阶段 | 发送方看到 | 接收方看到 |
|---|---|---|
| 碰后 | 有照片：发送页（卡片 + “上滑发送 N 张”）；无照片：空态等待页 | 同左——有照片也先看到发送页（还没决定角色）；无照片为空态页 |
| 第一次确认 | 上滑 | 发送方上滑后才出现接收卡（带真实张数/预览） |
| 传输 | 页面按动效结束逻辑收起，用户回到相册；只有底部状态行短暂出现 | 接收卡 + 卡片照片 + “已收到 i/n 张”状态行；点接收后按钮消失，无“接收中/取消” |
| 成功 | 安静，无跳转 | 查看器打开，显示本轮照片 |
| 失败 | 顶部红色横幅（拒收/未响应/发送失败），5s 消失 | 拒收/超时：卡片收起；若只是对方没连上，则一直等待 |

> OPPO 的对应表见 §5；OPPO 的“发送方/接收方用户可见步骤”与协议步骤是一一对应的，而我们的用户可见步骤比协议步骤少（没有第二次确认与“接收中”态）。

---

## 2. 协议步数与时序对照

### 2.1 我们在 NFC 上交换什么（APDU）

| 方向 | 报文 | 内容 | 代码 |
|---|---|---|---|
| 读卡方 → 卡方 | `SELECT`：`00 A4 04 00 07 F0 50 52 49 4E 46 43 00` | 选择我方 AID | `nfc/Bus.java:33-37`；发送 `nfc/TapService.java:530` |
| 卡方 → 读卡方 | `90 00` | AID 接受 | `nfc/HceService.java:41-46` |
| 读卡方 → 卡方 | `EXCHANGE`：`80 10 00 00 Lc <payload> 00` | 读卡方的 payload | `nfc/Bus.java:96-113`；发送 `nfc/TapService.java:533`；解析 `nfc/HceService.java:47-59` |
| 卡方 → 读卡方 | `<payload> 90 00` | 卡方的 payload | `nfc/HceService.java:51-59`；解析 `nfc/TapService.java:534-537` |

- **步数**：一次碰触 = **2 个命令-响应回合（4 个 APDU 报文）**，每端只发一次 payload。
- **payload 字段**：`n=机型 | b=蓝牙名 | t=随机 8 位 token | ts=时间 | p=P2P 名 | a=P2P 地址 | ip=局域网 IPv4 | hs=热点 SSID | hp=热点密码`（`nfc/Bus.java:162-177`）。
- **上限与截断**：`MAX_PAYLOAD=200`，超过则**静默截断**（`Bus.java:41`，`:96-103`），对端无法察觉。
- **超时**：IsoDep `setTimeout(3000)`（`TapService.java:527`）。
- **角色**：两端都注册 HCE（`AndroidManifest.xml:149-159`）与 `TECH_DISCOVERED`（`:127-141`）；系统决定谁当读卡方、谁当卡方。卡方也会从 `EXCHANGE` 的数据里拿到读卡方 payload（`HceService.java:49-58`，`:71-87`），所以**两端最终都拿到对方的 payload**。
- **锁屏**：`requireDeviceUnlock=false`（`apdu_service.xml:4`），发卡侧在锁屏可用；读卡侧需要 `TapDispatchActivity` 与悬浮窗，端到端 [未确定]。

**与 OPPO 的差别**：OPPO 的 APDU 也是 2 个回合（`SELECT` + `00CA0101`），单帧 ≤255B、无分块（§3.4 / §6.3.3）。差别不在步数，而在**载荷内容**：OPPO 广播时 `networkName=""` / `networkPwd=""` / `networkFreq=-1`，凭据在“被碰的一瞬间”注入并 AES-GCM 加密（§6.3.2）；我们把热点 SSID/密码/IP **明文**放进每次可读的 payload（`Bus.java:175-176`）。

### 2.2 我们在 TCP 上交换什么（`Xfer`）

帧格式：`[MAGIC:4B="NFC1"][TYPE:4B][LEN:4B][data:LEN]`（`nfc/Xfer.java:57-62`，写帧见 `:958-968`）。

| 序 | 方向 | 帧 | `LEN` / 内容 | 触发 | 代码 |
|---|---|---|---|---|---|
| 1 | 发送方 → 接收方 | `TYPE_META(3)` | 本次总张数 N | 发送方上滑且链路就绪 | `Xfer.java:958-960` |
| 2 | 发送方 → 接收方 | `TYPE_PREVIEW(1)` | 第 1 张的 JPEG 缩略图（长边 ≤900，q82） | 紧随 META | `Xfer.java:961-969`，`:1133-1154` |
| 3 | 接收方 → 发送方 | `TYPE_ACCEPT(4)` / `TYPE_REJECT(5)` | `LEN=0` | 用户点“接收/拒绝” | `Xfer.java:1075-1112` |
| 4 | 发送方 → 接收方 | `TYPE_FULL(2) × N` | 每张完整图片字节 | 收到 ACCEPT 后 | `Xfer.java:997-1010` |
| 5 | 发送方 → 接收方 | **TCP FIN** | 无帧；关闭连接即“发完” | 所有 FULL 写完 | `Xfer.java:1010-1013` |

接收端的完成判定：`count >= mExpect` 先通知完成（`Xfer.java:871-876`），EOF 兜底（`:879-890`）；每张解码并写入 MediaStore（`:1216-1237`）。发送端等回执上限 **60s**（`VERDICT_WAIT_MS`，`Xfer.java:63`，`:1034`）。

连接建立：接收方监听 `8988`（`Xfer.java:53`，`:751-780`）；发送方按 ①对方热点 ②同网 IP ③Wi-Fi Direct 的顺序尝试（`Xfer.java:461-471`，`:474-571`）。接收方的 accept 循环按“谁先说话”决定这条 socket 的用途（`Xfer.java:730-743`，`:762-773`）。

### 2.3 OPPO 的 6 + 4 契约链

**配对链（碰后立刻；①–⑤ 的契约报文走 SCP 通道，承载缺省 BT-SPP、满足六条件时升级 Wi-Fi P2P，V1 建链 7s / V3 9s；⑥ 再建供后续传输用的 accessory 连接，§3.0 / §3.2 / §6.2 / §6.5）**

| 步 | 契约 | 报文 | 超时 | 失败分类 |
|---|---|---|---|---|
| ① | `NfcPairInitContract` | `MESSAGE_TYPE_NFC_PAIR_INIT=7`，版本/序号 | 6s | `NfcPairInitTimeoutException("nfc pair init time out")` |
| ② | `NfcDataSyncContract` | `116/111/115`，action / accountEqual / deviceType / nfcEventSender | 6s | `NfcDataSyncTimeoutException` |
| ③ | `NfcSenderConfirmContract` | `112`，发送方上滑；手机→平板自动接受 | 10s | `NfcUserCancelException("sender timeout cancel")` |
| ④ | `NfcReceiverConfirmContract` | `113/114`，接收方接受/拒绝/超时；同账号自动同意 | 23s | `NfcUserRejectException("receiver timeout reject")` |
| ⑤ | `KscNegotiationContract` | `12/13`（或 UKey2 16-19 / OOB 25-29） | 6s | `NfcKscNegotiationTimeoutException` |
| ⑥ | `NfcConnectAccessoryContract` | **无报文**，`CompatScp.connectAccessory(SPP_INSECURE)` | 6s | `NfcConnectAccessoryTimeoutException` |

**传输链（连接建好后，§3.2）**。确认报文同样走已建立的 SCP（BT-SPP / P2P）；真正的文件字节另由 `com.coloros.oshare` 搬运（§6.1 / §6.4）。

| 步 | 契约 | 超时 | 结果 |
|---|---|---|---|
| ① | `ReadyConfirmContract` | 15s | `LOCAL_READY / PEER_READY / SENDER_USER_CANCEL` |
| ② | `SenderConfirmContract` | 65s | `SENDER_USER_SLIDE_AGREE / REJECT / TIMEOUT / QUIT…` |
| ③ | `ReceiverConfirmContract` | 65s | `RECEIVER_AGREE / REJECT / EXCHANGE / QUIT / CARD_TIME_OUT` |
| ④ | `TransferConfirmContract` | **24h** | `SENDER_TRANSFER_SUCCESS / RECEIVER_TRANSFER_SUCCESS / FAILURE / RECEIVER_TRANSFER_PERMISSION_GRANT_FAIL / CANCEL` |

- 报文线格式 = `[1B type][protobuf payload]`，类型值 111–116 是 ASCII `o p q r s t`（§3.6）。
- ⚠️ 上表②里的 `115` 名义上属于 DataSync 枚举，但 §4.8 已纠正：它的实际消费者在 **GAME_INVITATION 场景的独立裸 BT-SPP** 上，契约②只解析 `116/111`、会静默丢弃 `0x73(115)`；手机↔手机传图的协商实际用的是 `116/111`。
- 配对链最坏约 **57s**；传输链最坏 24h（§3.9 / §3.2）。
- 失败分类：配对链 6 个专属异常；传输链 7 个结果枚举；**主链路不重试**，失败只 toast，需用户重碰；连接层（IPC/GATT/SoftAP/无感连接）另有多次重试（§6.5）。
- 传输实际字节由 OShare 搬运，框架只做编排（§1.1 / §6.4）。

### 2.4 并排时序图（触碰 → 完成）

```
时间   OPPO（NFC → SCP/BT → OShare）                         我们（NFC → Wi-Fi/TCP）
────────────────────────────────────────────────────────────────────────────────────────────
t0     贴一下                                                    贴一下
       ├ 系统 NFC → HCE: SELECT + 00CA0101                       ├ 系统 NFC → reader 发现 IsoDep
       │  （≤255B，凭据现场注入+加密）                            │  TapDispatchActivity → TapService
       │                                                          ├ SELECT AID（9000）
       │                                                          ├ EXCHANGE：双方 payload（≤200B，明文）
       ▼                                                          ▼
       交换：身份/地址/能力/意图                                   双方各拿到对方 n/b/t/ip/hs/hp…
       │                                                          │
       ├ 配对链① PairInit       6s                                ├ TapService.onPeer：
       ├ 配对链② DataSync       6s                                │   startAsReceiver + setCanSend
       │   （角色/账号/设备类型裁决）                              │   + startHotspot（无条件）
       ├ 配对链③ SenderConfirm 10s ← 发送方上滑                    │   有图→发送页；无图→空态页
       ├ 配对链④ ReceiverConfirm 23s ← 接收方点接受                │
       ├ 配对链⑤ KSC协商        6s                                │   （两端都在等用户动作，无超时）
       ├ 配对链⑥ BT无感建连     6s                                │
       ▼                                                          ▼
       BT/SCP 通道就绪                                             发送方上滑
       │                                                          ├ setSenderRole（mReceiverRole=false）
       ├ 传输链① Ready          15s                                ├ 热点/同网/P2P 连接 8988
       ├ 传输链② SenderConfirm  65s ← 发送方再次滑动                ├ META(N) + PREVIEW(第1张)
       ├ 传输链③ ReceiverConfirm 65s ← 接收方再次确认              ├ 等 ACCEPT/REJECT，≤60s
       ├ 传输链④ Transfer       24h                                │
       │   字节由 OShare 搬运，进度回调另驱动 UI                    ▼
       │                                                         接收方收到 META → 接收卡
       │                                                         ← 用户点“接收”
       │                                                         接收方回 ACCEPT
       │                                                         ├ 发送方发 FULL×N，关闭 TCP
       │                                                         ├ 接收方 count==expect → 完成
       ▼                                                          ▼
       接收方 Intent SHARED_DATA → 目标App“无缝看”                 接收方打开自家查看器
       发送方显示“已发送”                                          发送方留在相册（安静）
```

### 2.5 超时与失败分类对照

| 层 | 我们 | OPPO |
|---|---|---|
| NFC 单次交互 | IsoDep 3s（`TapService.java:527`） | APDU 本身无应用层超时；配对链每步 6-23s（§3.9） |
| 角色/等对方动作 | 无接收方超时；发送方等回执 60s（`Xfer.java:63`） | 发送确认 10s / 接收确认 23s；卡片等用户 61s；传输链 65s（§3.9 / §6.5） |
| 建连 | 热点请求 30s + 等待 31s；同网 2×1.5s；P2P 搜索最多 5 次，`3s + 2s×n`（`Xfer.java:474-489`，`:524-525`，`:600-615`） | 配对链⑥ 6s；raw 连接收5s/发3s；SCP 建链 7/9s；P2P 组主 9s/GC 20s（§6.5） |
| 传输 | **无超时**，只靠 EOF 或 `count==expect`（`Xfer.java:871-890`） | `TransferConfirm` 24h；`sendData` 看门狗 25s；进度心跳 10s×2（§6.5） |
| 失败原因 | 拒收 / 未响应 / 链路失败 / 对方超时未发货；其余为通用字符串（`TapService.java:470-513`） | 6 个配对异常 + 7 个传输结果（含权限失败、取消、同设备等）（§3.9 / §3.2） |
| 重试 | 连接底层有重试；主流程不自动重开一轮 | 主链路 0 重试（toast 让用户重碰）；连接层多次重试（§6.5） |

---

## 3. 角色模型差异（重点）

### 3.1 我们的模型：两端都先当接收方，上滑才宣告发送

碰后（`nfc/TapService.java:313-331`）：

1. `x.setExpectedPeer(...)`（`:316`）——清上一轮状态，记下对方 payload。
2. `x.startAsReceiver()`（`:318`）——两端都起 8988 监听、`mReceiverRole=true`（`nfc/Xfer.java:383-417`）。
3. `Bus.lockRole()`（`:319`；`nfc/Bus.java:275-283`）——**进程内** 30s 锁，只防同一台手机的重复事件。
4. `x.setCanSend(hasPending())`（`:322`）——有照片但未上滑，仍是“可能发送”。
5. `x.startHotspot()`（`:323`）——两端无条件准备热点（Wi-Fi 开着时提前返回，`Xfer.java:265-268`）。
6. 有照片弹发送页，无照片弹空态接收页（`:324-329`）。

底层分派标志：

- `mReceiverRole`：当前是否“接收方”。`startAsReceiver` 置 true（`Xfer.java:384`）；`startAsSender` / `setSenderRole` 置 false（`Xfer.java:421`，`:450-453`）。
- `mCanSend`：有照片、未上滑，“仍可能成为发送方”（`Xfer.java:131`，`:445-447`）；accept 循环用它决定是否保留一条静默连接（`Xfer.java:767`）。
- `mDriveP2p`：发送方在热点/同网都失败后才接管 P2P 搜索（`Xfer.java:456-458`，`:466-470`）。
- 上滑：`mUserWantsSend=true`; `setSenderRole()`; 链路已就绪就直接 `sendImages`，否则 `startAsSender()`（`TapService.java:176-191`）。

**这不是跨设备角色协商**：两端各自认为“我可能发、我也可能收”，真正决定身份的是“谁的上滑先被对方以 `META` 感知”。`ALIGN_PLAN_FINAL.md` §3.4 明确把它定义为“谁先上滑谁赢”。

### 3.2 OPPO 的模型：显式角色，在 DataSync 契约里交换并裁决

- `NfcPairRole{SEEKER,PROVIDER}`：谁先发、谁回应（§3.1①）。
- `InternalNfcEventRole{SENDER,RECEIVER}`：谁是数据发送方，由 `NfcDataSyncContract` 的 `nfcEventSender` 字段表达，并与 `nfcEvent`（`InternalNfcAction`）一起裁决本次是传图、投屏还是接力（§3.1② / §3.6）。
- `NfcShareRole{UNKNOWN,DELIVER,RECEIVER}`：传输链的角色（§3.2 结果枚举）。
- 角色不是“谁先滑动”，而是“本次事件是什么 + 哪端是数据源”；同账号/手机→平板还会改变确认行为（§3.10 / §3A.3）。
- **两端同时想发时 OPPO 如何裁决，文档没有给出完整规则** `[未确定]`；但它的模型里角色在进入确认卡之前就已经是协议字段，不存在“两边都滑了才发现”的状态。

### 3.3 两端同时上滑会发生什么（代码推演）

前提：两台都有照片，碰后都弹了发送页（`TapService.java:324`），都处于 `mReceiverRole=true, mCanSend=true`。

**正常情况（一先一后）**

1. A 上滑：`mUserWantsSend=true`，`setSenderRole()` → A 的 `mReceiverRole=false`（`TapService.java:180-181`；`Xfer.java:450-453`）。
2. A 连接 B 的 8988，写 `META(N)+PREVIEW`，等回执（`Xfer.java:957-973`）。
3. B 收到 META：`onSendStarted` 在 `mUserWantsSend` 为 false 时继续执行，B 有照片 → `switchSendPageToReceive()`（`TapService.java:401-416`，`:596-604`），B 变成接收方；用户点接收 → `ACCEPT` → A 发整图。
   - 这条路径能成立的前提是：**B 的 META 处理早于 B 的上滑**。

**同时上滑（竞态）**

1. A、B 都在极短时间内上滑：两端都 `mUserWantsSend=true`，两端都 `setSenderRole()` → 两端 `mReceiverRole=false`。
2. 之后任一端收到对方的 `META` 时，`onSendStarted` 第一行 `if (mUserWantsSend) return;`（`TapService.java:403`）直接丢弃——**接收卡不会被弹出来**，因为两端都认为自己是发送方。
3. 在 `Xfer` 的 accept 循环里，对方主动连进来的 socket 有两种落法：
   - 若 accept 时本机已经上滑（`mReceiverRole=false`），走 `stashForSending`（`Xfer.java:762-764`），原样存进 `mPendingSocket`，**没有读线程**；
   - 若 accept 时本机还是接收方（在 3s 的 `peerSpeaksFirst` 探测内），则走 `handleIncoming` 读到了对方的 META/PREVIEW（`Xfer.java:765-766`；`:730-743`），但 `TapService` 仍因 `mUserWantsSend` 在 `:403` 丢弃 `onSendStarted`，**依然没有接收卡/接受按钮**。
   - 两种分支都没有“接受回执”的入口。
4. 两端各自在**自己的出站 socket** 上等 `ACCEPT/REJECT`，上限 60s（`Xfer.java:63`，`:1030-1054`）；如果对方那条入站 socket 被 stash，则没人读。
5. 60s 后两端都 `onVerdictTimeout`，各弹一张“对方未响应”横幅（`TapService.java:507-513`），并 `Bus.unlockRole()`。**实际对方并没有“未响应”，只是两人都把自己当发送方。**

**是否已有防护**

- 有，但都只覆盖“一台手机内部”或“时序刚好”：
  - `mUserWantsSend` 防止已上滑的一侧被对方 META 改判（`TapService.java:403`）——这恰恰也是同时上滑时“无人转接收方”的原因。
  - `switchSendPageToReceive` 只在 META 先于本机上滑时生效（`TapService.java:412`，`:596-604`）。
  - `mVerdictSent` / `mRound` 防止回执重复或跨轮泄漏（`Xfer.java:121-124`，`:1085-1089`）。
  - `Bus.lockRole` 30s 只防**同一进程**的重复碰触（`Bus.java:270-283`），不跨设备；且它在 `startAsReceiver()` 之后才执行（`TapService.java:318-319`），不能阻止重复事件先重置传输轮次。
- 没有“双方都想发送”的协议帧、错误码或 UI 状态；也没有确定性的平局裁决。
- `ALIGN_PLAN_FINAL.md` §3.4 已把“双方几乎同时上滑 → 两边各等 60s”写成**可接受的降级**，并列为真机验证项；从代码看，这个降级确实会发生，而且两端的提示是“对方未响应”，对用户有误导性。

**竞态窗口**

- 从 A 的 `META` 到达 B，到 B 的上滑把 `mUserWantsSend` 置 true 之间。
- 在这段窗口内 B 上滑 → 双方都发送；在窗口外（先处理 META）B 会转接收方。窗口长度取决于 UI 输入与网络/主线程调度，实际是**毫秒到数百毫秒级**，但两端都主动上滑时并不罕见。

**后果**

- 本轮不传输；双方各等 60s；各出“发送失败 / 对方未响应”；需要重新碰一次（且 `unlockRole` 后可以重来）。
- 如果 META 处理与上滑几乎同时发生，结果取决于主线程调度：先处理 META 的一方转接收方；先处理上滑的一方进入“双方发送”。代码里没有中间态，也没有“两者同时成立时如何收场”的分支。

**最小修法（按改动面从小到大）**

1. **先加确定性平局裁决，不改 UI 语义**：NFC payload 里已经有每进程随机的 `t=<8hex>`（`Bus.java:165`）。双方上滑时把 `t` 作为 tie-break（例如字典序大者当发送方）。收到对方 META 且自己也想发时，不再在 `TapService.java:403` 静默返回，而是：
   - 若本机 `t` 输 → 调 `switchSendPageToReceive()`（或 `showReceivePage`），把 `mUserWantsSend` 复位，让用户看到接收卡；
   - 若本机 `t` 赢 → 保持发送方，等对方转接收后回 ACCEPT。
   - 同时 `Xfer` 需要允许“已 stash 的入站 socket”被重新交给 `handleIncoming`，或者 accept 循环在 `mReceiverRole=false` 时也先做一次短探测，避免 META 被永久搁在 `mPendingSocket`。
2. **只做最小止血**：保留“谁先上滑谁赢”，但在 `onSendStarted` 发现 `mUserWantsSend` 时回一帧 `TYPE_REJECT` 并显示“双方同时发送，请一方重新上滑”，让本轮快速结束而不是等 60s。改动面最小，但用户体验仍是一次失败。
3. **顺手修角色锁的退出**：发送页 Back 也应通知 NFC 层（至少 `Bus.unlockRole()`），否则下一碰 30s 内被忽略（`OverlayService.kt:593-600`；`Bus.java:275-283`）。

推荐 1 + 3；若排期只允许一处，先做 2 的“快速失败 + 明确文案”，再做 1。

### 3.4 角色模型结论

- 我们的模型在**单次、顺序操作**下是对的：碰后不预判角色、上滑才宣告、接收方点接收才发整图。
- 它的薄弱点是**没有跨设备的角色共识**：`mReceiverRole` / `mCanSend` / `mUserWantsSend` 都是本机状态，`Bus.lockRole` 是进程锁；对端只能通过“是否先发 META”间接推断。
- OPPO 把角色放进 DataSync 契约，至少保证在弹确认卡之前双方对“谁是发送方”有同一个答案。我们可以不引入完整契约链，但需要**一个确定性的角色裁决字段**（复用 `t` 或新增 `r=`）和**一条“双方都想发”的处理路径**。

---

## 4. 确认模型差异

### 4.1 我们的确认模型

```
发送方：相册选好 → 碰 → 发送页 → 上滑  ─────────────┐
接收方：碰 → （有图也先看到发送页）→ 收到 META → 接收卡 → 点“接收”
                                                      │
发送方：META(N)+PREVIEW(第1张) ──────────────────────┘
接收方：ACCEPT ──────────────────────────────────────► 发送方：FULL × N → 关闭
```

- 发送方**只确认一次**（上滑）：`TapService.java:176-192`；动效层只回执 `ACTION_UI_SEND_CONFIRMED`（`service/OverlayService.kt:674`）。
- 接收方**只确认一次**（接收/拒绝）：`TapService.java:193-225`；回执 `ACCEPT/REJECT` 写回同一 socket（`Xfer.java:1075-1112`）。
- 内容门控发生在**第一次确认之后**：`TYPE_PREVIEW` 只发第 1 张缩略图（`Xfer.java:961-969`），完整字节只在收到 `ACCEPT` 后发送（`Xfer.java:973-1010`）。
- 没有“开始传输”的第二次人工确认；`ACCEPT` 即开始。

### 4.2 OPPO 为什么需要两次确认

- **第一次（配对链③④）**：建立信任与连接。发送方上滑、接收方接受之后，才做 KSC 协商（⑤）和 BT 无感建连（⑥）（§3.1③④⑤⑥ / §5）。
- **第二次（传输链②③）**：在连接就绪、相册真正交出数据之后，**锁定这次要传的内容**。文档明确解释：相册是后交数据的，两次确认之间内容可能变化（§5；§4.6 第 5 步）。
- 两个特例：同账号 → 接收方自动同意（§3A.3）；手机→平板 → 发送方自动接受（§3.10）。
- 两次确认对应两组独立超时（10s/23s 和 65s/65s，§3.9 / §3.2），任何一次超时都能定位到“哪一步的人在等”。

### 4.3 我们的模型是否足够？

**对“相册里选好就碰”这个场景：够，而且比 OPPO 少一次冗余确认。**

- OPPO 的**第二次人工确认多余**：它存在的原因是“第一次确认时内容还没锁定”（相册后交数据）。我们的 `META(N)+PREVIEW` 已经在接收方点头前把“张数 + 第一张”摆出来，`ACCEPT` 后再发整图，语义上已经把“要不要传”和“传什么”合并成一次确认；对“选好就碰”的用户，再要求第二次滑动没有新增信息。
- OPPO 的**第一次确认也不完全适用于我们**：它承担“建立信任/建连”的职责（KSC、BT 无感连接），而我们是 NFC 直接换 IP/热点信息后建立 Wi-Fi/TCP，没有需要用户确认的配对关系。

**但有一处真正的缺失，不是“少一次确认”，而是“少一次内容锁定”：**

- 发送方上滑时，如果链路尚未就绪，`TapService` 只记住“我要发”，真正发送的是 `onP2pReady` 时的 `PENDING`（`TapService.java:180-190`，`:370-389`）。在这段窗口里：
  - 相册可以发布新的选择（`ACTION_SEND_MULTIPLE`，`TapService.java:156-165`）或清空（`ACTION_CLEAR_SELECTION`，`:169-174`）；
  - 发送方最终发出的可能是**与上滑时不同的照片**，甚至空列表（`sendImages` 里 `uris.size()` 为 0 时只发 META，没有 FULL）。
- 这正是 OPPO 第二次确认要解决的问题。我们不需要第二次人确认，但应该在上滑那一刻**快照 Uri 列表**，之后无论链路何时就绪都发这份快照。
- 另外两个“确认模型”相关的缺口：
  - 接收方**没有超时**：如果对方上滑后链路一直连不上，接收卡会一直等（`Xfer.java:804-901` 没有 socket 超时；`TapService.java:549-557` 没有计时器）。
  - 接收方点“接收”后**没有“接收中/取消”态**：`OverlayView` 的接收按钮在确认后直接淡出（`ui/OverlayView.kt:2797`，`:3324-3336`），没有可取消的传输中状态；只有一条“正在接收 → 已接收”的 toast（`OverlayView.kt:3326-3334`），且 `OverlayService` 没有 `ACTION_UI_RECEIVE_CANCEL`（`service/OverlayService.kt:1206-1208`）。这与我们自己的 `ALIGN_PLAN_FINAL.md` §2.2 / §5.3 C-4 设计不一致，属于**未落地**。OPPO 的取消是横切能力，会打断当前契约（§3.3）。

**结论**：确认次数上我们的模型足够（1+1 优于 2+2）；缺的是**上滑即锁定内容**、**接收方超时/取消**、以及**双方同时上滑的角色裁决**。把这三件事补上，就不需要照搬 OPPO 的第二次确认。

---

## 5. 相册取图流程差异

### 5.1 OPPO：相册是“分享代理”，框架不取图

- 数据源必须持有 `com.oplus.interconnect.permission.SERVICE`、绑定 `InterconnectService`、过 OCS 鉴权（uid + capability）（§4.6 第 1 步）。
- `registerShareAgent(params, callback)` 建一条**长连接回调**；框架在需要时推 `registerEvent(CONTRACT_START, role, data)`；相册收到后才 `startShare(InternalSharedDataParams{client,data,card,isReShare})`（§4.6 第 3-5 步）。
- 硬顺序：没有正在跑的 pair task 时 `startShare` 直接抛 `ShareInvalidTaskException`（§4.6 第 5 步）。
- 卡片外观（缩略图 Bitmap + 文案）由**源 App 给**，不是框架去解码相册（§4.1 / §4.2）。
- 只有 `com.coloros.gallery3d` / `com.oneplus.gallery` 在白名单里（§2；`compat_tap_share_image_pkgs` 资源值见 `_Temp/afk/jadx_out/resources/res/values/arrays.xml:16-19`）。**第三方相册不在白名单就拿不到 NFC 事件**（§4.6；`docs/OPPO_QUICKCONNECT_STUDY.md` §3.3 同结论）。
- 因此 OPPO 不需要无障碍、不需要媒体权限、不需要管屏幕外选中项或语言——相册把用户实际选中的 Uri 直接交上来（§4.4 也说明框架只对 Uri 做单条元数据查询，不枚举相册）。

### 5.2 我们：自家相册“推”，三方相册“读屏兜底”

**A. 自家相册（发布式，优先）**

- 契约写在 `TapService` 注释里：相册用**显式组件**发 `ACTION_SEND_MULTIPLE`（`EXTRA_STREAM` = Uri 列表 + `FLAG_GRANT_READ_URI_PERMISSION` + 按 uid `grantUriPermission`），离开选择时发 `com.pri.nfcdemo.CLEAR_SELECTION`（`nfc/TapService.java:58-92`）。
- 服务端：`ACTION_SEND_MULTIPLE` → 覆盖 `PENDING`、`sPublished=true`（`:156-165`）；`ACTION_CLEAR_SELECTION` → 清空、`sPublished=false`（`:169-174`）。
- `sPublished` 优先：碰触时若为 true，直接 `beginPeerRound`，**不读屏**（`:269-273`）。`sPublished` 不跨进程重启（`:87-92`），进程重启后恢复的 `PENDING` 不会冒充“相册正在上报”。
- 参考实现：`Temp/debuggallery/src/com/pri/debuggallery/MainActivity.java:267-308`（发布/清除、显式组件、按 uid 授权）。

**B. 第三方 Google 相册（读屏 + 截屏 + 像素兜底）**

1. 碰触时先问“栈顶是不是 Google 相册”（`nfc/GalleryPickup.java:67-89`，硬编码 `com.google.android.apps.photos`）。
2. 通过无障碍读当前窗口树：全屏图（面积 ≥45%）或 `isChecked()` 的格子，`content-desc` 里的**拍摄时间（分钟）** 作为目标（`nfc/AccessibilityProbeService.java:36-37`，`:79-107`）。
3. 时间窗 ±2 分钟 → ±1 小时查 MediaStore（`GalleryPickup.java:39-41`，`:239-277`）。
4. 静默截屏，对每个目标裁出中心方图，32×32 灰度归一化互相关，选最高分；多个候选且最高分 <0.30 时**放弃该张**（`GalleryPickup.java:140-219`，`:317-352`）。
5. 读 1 字节确认传输引擎之后能打开该 Uri（`GalleryPickup.java:221-233`）。
6. 拾取成功 → 覆盖 `PENDING`、`sPublished=false`（读屏不是“发布”，`TapService.java:283-288`）；拾取为空且相册可读 → **清掉上一轮选择**（`:289-296`）；读不到（无障碍关闭）→ **保留上一轮选择**（`:297-299`）。

### 5.3 我们的读屏兜底差在哪

| 问题 | 代码事实 | 影响 |
|---|---|---|
| **屏幕外选中项拿不到** | `AccessibilityProbeService.scan()` 只遍历当前窗口树（`nfc/AccessibilityProbeService.java:79-107`），**不会滚动**；滚动扫描只存在于诊断入口 `sweepAndDump`（`:132-165`），且只由 `com.pri.nfcdemo.A11Y_DUMP` 触发（`nfc/TapService.java:146-153`），拾取路径不调用。RecyclerView 未渲染的格子不在树里 | 用户勾选了很多张、其中一部分滚出屏幕时，只发可见的；与 OPPO“相册交出完整选中列表”不等价 |
| **只认中文** | 正则 `拍摄于\s*(\d{4})年...`（`AccessibilityProbeService.java:36-37`） | 相册切英文（`Taken on ...`）时解析不到 → 拾取为空；是“安全失败”（不会错发），但功能直接消失 |
| **无障碍关闭时沿用旧选择** | `GalleryPickup.canReadGallery()` 为 false（`GalleryPickup.java:105-107`）→ `TapService` 走 `:297-299`，**不清空 `PENDING`**，日志写“沿用上一轮的选择” | 用户可能以为在发当前照片，实际发的是上一轮留下的选择；这是三方兜底路径上最危险的一处（自家相册 `sPublished`/CLEAR 路径不受影响） |
| **只支持 Google 相册** | 前台判定硬编码 `com.google.android.apps.photos`（`GalleryPickup.java:80`） | 其它三方相册完全不走读屏；OPPO 白名单也只支持自家，但我们的扩展面更窄 |
| **截屏/像素匹配有成本与失败面** | 每次碰触截屏（`GalleryPickup.java:123`），失败/超时直接放弃（`:124-127`）；候选上限 400（`:50`） | 大相册、相似照片、时间戳不准时可能拾取不到；不会错发（多候选低分放弃，`:205-210`），但会“碰了没发” |

**为什么 OPPO 只支持自家相册（我们实测三方不行）**：不是能力问题，是**权限/契约问题**。OPPO 的取图入口是 `registerShareAgent`，它被 OCS（uid + capability）和 `compat_tap_share_image_pkgs` 白名单双重限制；第三方包名不在表里，框架根本不会把 NFC 事件派给它，它也无法通过鉴权（§4.6 / §2）。我们的读屏兜底正是在“没有 share-agent 契约”的前提下做的通用替代，所以它天然要面对屏幕外、语言、无障碍开关这些 OPPO 不需要面对的问题。

### 5.4 相册流程结论

- OPPO 的“相册即分享代理”在**正确性**上优于我们的读屏：用户选中什么，交上来的就是什么；屏幕外/多选/语言都不是问题。
- 我们的自家相册契约（`ACTION_SEND_MULTIPLE` + `CLEAR_SELECTION`）在形状上已经接近 OPPO 的 `registerShareAgent + startShare`，且更简单（无需 AIDL/鉴权），**这是已经做对的部分**。
- 三方兜底的价值是“没有相册配合也能用”，但它的产品化前提（无障碍常开、中文、屏幕内）比 OPPO 苛刻；尤其是**无障碍关闭时沿用旧选择**，应至少改为“清空并提示”，避免发错。

---

## 6. 流程上最关键的 5 个差异（按影响排序）

### #1 双方同时上滑没有裁决，双方各等 60s 后都报“对方未响应”

- **差异**：我们靠“谁先上滑谁赢”，没有任何跨设备角色字段；OPPO 在 `NfcDataSync` 里交换 `InternalNfcEventRole` 并裁决（§3.1②）。
- **用户能感知**：两台都有照片、几乎同时上滑 → 不传输；双方各出一张“发送失败 / 对方未响应”横幅；需要重新碰。提示还是误导性的。
- **是否需要改**：**需要**。这是流程正确性问题，不是体验优化。
- **改动面**：`nfc/TapService.java`（`onSendStarted` 的 `mUserWantsSend` 分支，`:401-415`）；`nfc/Xfer.java`（`setSenderRole` / accept 分派 / `mPendingSocket` 的重新读取，`:450-453`，`:751-780`）；`nfc/Bus.java`（复用 `t` 作为平局键或新增角色字段，`:162-177`）。可先做“快速失败 + 明确文案”止血，再做确定性裁决。

### #2 接收方没有超时/自动退出，整屏页可能永久停留；发送页 Back 留下 30s 死区

- **差异**：OPPO 每一步都有超时（10/23/65s、卡片 61s，§3.9 / §6.5），取消会打断当前契约（§3.3）；我们的接收方在“没有连接/没有报价”时没有计时器（`TapService.java:549-592`，`Xfer.java:804-901`），发送页 Back 只收窗不通知（`service/OverlayService.kt:593-600`）。
- **用户能感知**：对方一直不发/连不上时，接收方被整屏悬浮窗挡住，只能自己 Back；发送者误按 Back 后 30s 内再碰“没反应”（`nfc/Bus.java:275-283`）。
- **是否需要改**：**需要**。至少加“等待对方”的总超时与发送页 Back 的解锁。
- **改动面**：`nfc/TapService.java`（等待页计时/收尾、Back 回执处理）；`nfc/Xfer.java`（接收等待超时/报价超时）；`service/OverlayService.kt`（退出路径、可选“接收中/取消”）；`nfc/Bus.java`（`unlockRole` 调用）。

### #3 相册兜底在屏幕外/英文/无障碍关闭时失效，且关闭时会沿用旧选择

- **差异**：OPPO 由相册直接交 Uri 与预览（§4.6 / §4.2），不依赖屏幕内容；我们只能读当前无障碍树、只认中文，关闭无障碍时保留上一轮 `PENDING`（`nfc/GalleryPickup.java:79-89`，`:105-107`；`nfc/AccessibilityProbeService.java:36-37`，`:79-107`；`nfc/TapService.java:297-299`）。
- **用户能感知**：勾了很多张但只发屏幕内的；英文界面完全不发；无障碍被关/被强杀后碰一下可能把上次的选择发出去（发错照片）。
- **是否需要改**：**需要**。至少在无障碍不可读时**清空并提示**，不要沿用旧选择；屏幕外选中项要靠自家相册契约（发布完整 Uri 列表）解决，而不是读屏。
- **改动面**：`nfc/TapService.java`（`:289-299` 的兜底决策）；`nfc/GalleryPickup.java`（失败语义/前台判定）；`nfc/AccessibilityProbeService.java`（语言适配、可滚动收集，若继续走读屏）；自家相册侧（`Temp/debuggallery` 参考实现，`:267-308`）。

### #4 上滑不等于锁定内容：链路未就绪时会发“就绪时的 PENDING”

- **差异**：OPPO 用第二次确认在内容交付后锁定（§5）；我们在上滑未连上时，等到 `onP2pReady` 才读取 `PENDING`（`nfc/TapService.java:180-190`，`:370-389`），期间相册可改选/清空（`:156-174`）。
- **用户能感知**：上滑后、连接建立前如果退回相册改了选择，最终发出的是改后的照片；极端情况下 `PENDING` 为空，只发 META 没有图。
- **是否需要改**：**需要**，改动很小。上滑时把 Uri 列表快照下来，之后统一用快照。
- **改动面**：`nfc/TapService.java`（新增 `mSendSnapshot`，上滑/就绪两处共用）；`nfc/Xfer.java`（`sendImages` 接收快照即可，`:918-926`）。

### #5 失败原因与传输过程太粗：只有“拒收/未响应/链路失败”，没有分步超时/进度

- **差异**：OPPO 有 6 个配对异常 + 7 个传输结果 + 分步超时（§3.9 / §3.2 / §6.5）；我们只有 60s 回执超时、通用失败字符串，传输无超时、无进度（`nfc/Xfer.java:63`，`:1030-1054`；`nfc/TapService.java:470-513`）。
- **用户能感知**：失败时不知道该重碰还是该开 Wi-Fi/热点；接收方在传输中卡住时没有“失败”反馈，只有一张停在 i/n 的状态行。
- **是否需要改**：**建议改**。至少把“连接失败 / 回执超时 / 拒收 / 传输中断”分开，并给传输加停滞检测。
- **改动面**：`nfc/Xfer.java`（失败分类、传输超时/心跳）；`nfc/TapService.java`（文案/横幅分支）；`service/OverlayService.kt`（状态行/横幅文案，`:862-963`）。

---

## 7. 我们流程里已对齐/优于 OPPO 的部分

> 这一节不是客套：以下都是代码里已经成立、且对用户有实际价值的行为。

### 7.1 协议与安全边界

1. **ACCEPT 之前绝不发整图**。报价阶段只发 `META(N)+PREVIEW(第 1 张缩略图)`（`nfc/Xfer.java:957-972`），收到 `ACCEPT` 才写 `FULL × N`（`:973-1010`）。这与 OPPO“确认后锁定内容”的核心原则一致；我们的报价里只有第一张，而 OPPO 的 `SharedData` 模型本身可以携带整份 records + 卡片（§4.2），且在 `GAME_INVITATION` 场景会通过 115 提前同步整份数据（§4.8）。
2. **接收端校验张数**。`TYPE_META` 的张数记入 `mExpect`，`count == expect` 才判完成（`Xfer.java:123`，`:831`，`:872`）。OPPO 的接收端**从不校验 count**，数量不一致检测不到（§4.5 明确列为缺陷）。这一条我们做对了。
3. **没有外部 APK 依赖**。OPPO 依赖 OShare（`com.coloros.oshare`）、OCS 鉴权、白名单相册、平台私有 API（§1.1 / §2 / §9.3）；我们一个 APK 完成 NFC（`AndroidManifest.xml:127-159`）+ 传输（`nfc/Xfer.java:37-163`）+ 查看器（`AndroidManifest.xml:115-121`）。对 demo/自研 ROM 的落地成本远低于 OPPO。
4. **锁屏发卡侧可用**。`requireDeviceUnlock=false`（`apdu_service.xml:4`）对比 OPPO 的 `true`（§3.4 / §11.1）。读卡侧端到端锁屏行为未验证，但发卡能力本身是优势 `[未确认端到端]`。

### 7.2 用户流程

1. **成功安静、失败高调**。发送方成功后不跳转、不打断，只清 `PENDING`、收窗（`TapService.java:457-468`）；发送方成功回调只在日志里（`:503-505`）。失败只在“本机已宣告发送”后出横幅（`:475-480`），且横幅可滑走/点外关/5s 自动消失（`OverlayService.kt:882-891`，`:1377`）。这比“每次动作都弹结果”更贴近参考产品。
2. **失败原因至少区分“拒收”和“未响应”**。`onPeerRejected` 与 `onVerdictTimeout` 是两条独立路径（`TapService.java:495-513`），用户能知道是对方拒绝还是没等到；OPPO 主链路失败只有统一 toast（§6.5），虽然它有更细的内部枚举。
3. **空态是一等公民**。无照片的一方也弹页，只是把内容压住（`TapService.java:327-329`，`571-592`；`OverlayService.kt:285-303`，`1285-1303`）。OPPO 文档没有描述空态；我们的双端反馈更确定。
4. **接收后有自己的查看器，只显示本轮照片**。`ImageViewerActivity` 接收本次 `mSavedUris`（`TapService.java:646-665`；`viewer/ImageViewerActivity.kt:130-140`，`429-442`），不依赖系统相册/Google 相册，也不会翻到无关照片。OPPO 跳到目标 App 的“无缝看”（§4.7），强依赖目标 App 是否安装/是否实现。
5. **退出路径完整**：接收页 Back = 拒绝并收窗（`OverlayService.kt:594-600`）；Home/Recents（手势导航）自动收页（`:602-619`）；通知栏有“隐藏/触摸穿透”（`:169-184`，`:554-575`）；失败横幅三条关闭路径。OPPO 的取消是协议级横切（§3.3），但文档未展开 Back 的具体行为 `[未确定]`。
6. **自家相册契约简单且明确**：`ACTION_SEND_MULTIPLE` + `ACTION_CLEAR_SELECTION`，显式组件、Uri 授权、`sPublished` 优先级（`TapService.java:58-92`，`:156-174`，`:269-273`）。形状上等价于 OPPO 的 share-agent，但不需要 AIDL/OCS/白名单；这是我们已经对齐且可复用的部分。
7. **支持至少一种第三方相册**。OPPO 白名单只有两家自家相册（§2）；我们的 Google 相册读屏兜底虽然脆弱，但确实在“相册不配合”时工作（`nfc/TapService.java:278-305`；`nfc/GalleryPickup.java:111-138`），这是能力上的额外覆盖。

### 7.3 与已有设计文档的落地核对

| 设计（`ALIGN_PLAN_FINAL.md` / `ALIGNMENT_SUMMARY.md`） | 落地情况 | 证据 |
|---|---|---|
| 两阶段：`META+PREVIEW → ACCEPT/REJECT → FULL` | **已落地** | `nfc/Xfer.java:58-63`，`:957-1010`，`:1029-1072` |
| 接收卡改为收到 `TYPE_META` 才弹 | **已落地**（无照片方的空态页是碰后即弹，但内容等到 META 才出现） | `nfc/TapService.java:401-416`，`:549-557`；空态 `:571-592` |
| 失败横幅（D1） | **已落地** | `nfc/TapService.java:470-513`；`service/OverlayService.kt:862-963` |
| “谁先上滑谁赢”，同时上滑视为可接受降级 | **已落地**，但降级提示是“对方未响应” | `nfc/TapService.java:176-192`，`:401-415`；`ALIGN_PLAN_FINAL.md` §3.4 |
| “接收中/取消”子状态（C-4） | **未落地**：接收按钮确认后直接淡出，无取消 action | `ui/OverlayView.kt:2797`；`OverlayService.kt:1206-1208` 只有三个 UI 回执，没有 `ACTION_UI_RECEIVE_CANCEL` |
| D4 系统查看器 | **已被 D5 自有查看器取代**（更符合“只看本轮照片”） | `AndroidManifest.xml:115-121`；`nfc/TapService.java:646-665` |
| 锁屏触发（`NfcOemExtension`） | **未落地**：当前用公开 `TECH_DISCOVERED`，HCE 侧锁屏可用，读卡侧未接 OEM 回调 | `AndroidManifest.xml:127-141`；`docs/ALIGNMENT_SUMMARY.md` §4.3 / §7 P0 |
| 接收方超时/自动退出 | **未落地**：没有等待计时器；仅在对方断开且 0 张时 `onOfferExpired` | `nfc/TapService.java:486-493`，`:549-592`；`nfc/Xfer.java:804-901` |
| 上滑即锁定内容 | **未落地**：链路未就绪时等待 `onP2pReady` 再读 `PENDING` | `nfc/TapService.java:176-190`，`:370-389` |

---

## 8. 未确定清单（诚实清单）

| # | 未确定项 | 已知线索 |
|---|---|---|
| 1 | 锁屏端到端能否触发并显示页面 | 发卡侧 `requireDeviceUnlock=false`（`apdu_service.xml:4`）；读卡侧 `TapDispatchActivity` / 悬浮窗没有 keyguard 门控，但未真机验证；OPPO 明确 `true`（§3.4） |
| 2 | 双方同时上滑的真机实际表现 | 代码推演为双方 60s 超时；`ALIGN_PLAN_FINAL.md` §3.4 已列为真机验证项，文档未记录实测 |
| 3 | 接收方“无连接/无报价”时页面停留多久 | 代码无计时器（`TapService.java:549-592`，`Xfer.java:804-901`）；只能 Back/拒绝退出 |
| 4 | OPPO 的精确角色平局规则 | `NfcDataSync` 有 `nfcEventSender`/`nfcEvent`（§3.1②），但两端都想发时的裁决未在文档中展开 |
| 5 | OPPO 的 Back/Home 退出细节 | §3.3 只说明取消会打断当前契约；具体按键/手势行为未展开 |
| 6 | OPPO 无内容时是否也有空态页 | §5 没有描述；§4.6 的硬顺序暗示“先有 share 数据”，但未证实 |
| 7 | 我们的读屏在三方相册中的通用性 | 只实现了 Google 相册（`GalleryPickup.java:80`）；其它三方未测 |
| 8 | 我们的传输在弱网/半开连接下的行为 | 无传输超时/心跳（`Xfer.java:804-901`）；长传输卡死未验证 |

---

## 9. 附：关键代码入口速查

| 能力 | 文件:行 |
|---|---|
| NFC 读卡跳板 | `nfc/TapDispatchActivity.java:12-22` |
| NFC 读卡（IsoDep SELECT/EXCHANGE） | `nfc/TapService.java:517-542` |
| NFC 发卡（AID/EXCHANGE） | `nfc/HceService.java:33-112`；AID `app/src/main/res/xml/apdu_service.xml:8` |
| NFC payload（含热点凭据） | `nfc/Bus.java:144-178` |
| 角色锁（进程内 30s） | `nfc/Bus.java:270-287` |
| 碰后角色准备/首屏 | `nfc/TapService.java:313-331` |
| 上滑发送 | `nfc/TapService.java:176-192`；`nfc/Xfer.java:450-453` |
| 接收/拒绝 | `nfc/TapService.java:193-225`；`nfc/Xfer.java:1075-1112` |
| TCP 帧/报价/交货 | `nfc/Xfer.java:57-63`，`:918-1026`，`:1030-1112` |
| 接收完成/查看器 | `nfc/TapService.java:435-455`，`:646-665` |
| 失败横幅/状态行 | `nfc/TapService.java:470-513`；`service/OverlayService.kt:862-963` |
| 退出路径 | `service/OverlayService.kt:586-619` |
| 自家相册契约 | `nfc/TapService.java:58-92`，`:156-174`；`Temp/debuggallery/src/com/pri/debuggallery/MainActivity.java:267-308` |
| 三方相册读屏 | `nfc/GalleryPickup.java:67-219`；`nfc/AccessibilityProbeService.java:36-107` |
| 查看器 | `viewer/ImageViewerActivity.kt:68-140`，`:429-442` |
