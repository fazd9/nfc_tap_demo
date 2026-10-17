# OPPO Quick Connect 可借鉴项落地状态审计

> 审计对象：`docs/OPPO_TECHNICAL_DETAIL.md`（2393 行）
> 审计代码：`app/src/main/java/com/pri/nfctouchdemo/`（nfc / service / ui / gfx / viewer）
> 分支：`nfc_tap`；审计日期：2026-10-17
> 约束：本审计只写本文件，不改任何产品代码。

## 0. 先回答：分析完 OPPO 之后，我们改动过什么代码？

**没有。** git 证据如下：

```
$ git log --oneline 4b03edf..HEAD
469de8c docs: 修正 OPPO 研究文档的传输层结论(承载/载荷/重试)
05d57cf docs: OPPO Quick Connect(AccessoryFramework.apk) 逆向与架构研究

$ git show --stat --oneline 05d57cf
 docs/OPPO_TECHNICAL_DETAIL.md | 2241 +++++++++++++++++++++++++++++++++++++++++
 1 file changed, 2241 insertions(+)

$ git show --stat --oneline 469de8c
 docs/OPPO_TECHNICAL_DETAIL.md | 272 ++++++++++++++++++++++++++++++++++++----------
 1 file changed, 212 insertions(+), 60 deletions(-)

$ git log --oneline 05d57cf^..HEAD -- app/ src/
（空）

$ git diff --stat 4b03edf..HEAD
 docs/OPPO_TECHNICAL_DETAIL.md | 2393 +++++++++++++++++++++++++++++++++++++++++
 1 file changed, 2393 insertions(+)
```

结论：OPPO 研究只产生了两个 **docs-only** 提交（`05d57cf`、`469de8c`），`app/`、`src/` 下没有任何改动。工作区当前只有未跟踪的 `Temp/video.mp4` 与 `_Temp/`（分析素材/临时文件，不是产品代码）。

### 0.1 还没改的清单（按优先级）

- **P0（安全，先改）**
  - #1 NFC payload 明文携带热点 SSID/密码/局域网 IP，且锁屏可读、日志还打印密码。
  - #7 无任何密钥协商/会话密钥（UKey2/ECDH 都没做）。
  - #13 传输 TCP 明文、无对端认证，且 API 33+ 热点口令是硬编码固定值。
- **P1（协议/可靠性）**
  - #2 200 字节 payload 静默截断，`safe()` 还会破坏含 `|`/`;` 的 SSID/密码。
  - #14 无契约链，失败只能靠自由字符串定位。
  - #15 超时未按“机器/等人/传输”分层，传输阶段无空闲/总时限。
  - #16 无分步错误码/异常类型。
  - #18 角色靠“谁先滑”决定，存在跨设备竞态。
  - #22 重试未按层拆分，且全是线性退避。
  - #23 无应用层心跳/进度停滞检测。
  - #25 失败横幅只覆盖发送方，详情被截到 12 字。
- **P2（体验/架构）**
  - #8 UK2 验证串、#9 KSC 持久化/同设备免协商、#10 同账号自动放行、#12 安全模式标志位。
  - #17 两次确认、#19 可打印报文类型、#21 承载判据、#24 传输进度与 UI 解耦。
  - #29 相册自报数据、#30 数据源提供预览图、#32 count 不一致处理、#33 设备类型分支。
  - #36 音频/触觉反馈。
- **不适用/明确不跟（➖）**
  - #6 iOS/厂商 NFC、#11 KSC CBC/遍历、#27 P2P 无兜底、#28 OShare、#34 机型素材、#35 COE。

统计：**✅ 6 / ⚠️ 9 / ❌ 15 / ➖ 6（共 36 项）**。

---

## 1. 逐项对照表

| # | OPPO 做法（出处 §） | 我们当前实现（文件:行号） | 状态 | 差距/风险 | 建议动作（P0/P1/P2） |
|---|---|---|---|---|---|
| 1 | **NFC 载荷里凭据字段恒为空**（`networkName/networkPwd=""`、`networkFreq=-1`）；只在被读到的一瞬间用现场值覆写，并用 **AES-GCM-128（12B IV）** 加密（§1.2 #5、§6.3.2、§11.3、§11.4） | `Bus.selfPayload()` 把 `ip=`/`hs=`/`hp=` 明文拼进 payload（`Bus.java:170-177`）；`TapDispatchActivity` 只把 Tag 转交（`TapDispatchActivity.java:10-22`），不构造 payload，实际读取/发送在 `TapService.handleTag`（`517-542`）；HCE 每次 EXCHANGE 原样返回（`HceService.java:48-59`）；读卡侧解析后交给 `Xfer.setExpectedPeer`（`TapService.java:316-317`、`Xfer.java:185-211`）；热点信息按 1.2s 短缓存复用（`Bus.java:43-47`、`144-153`）；`setHotspotInfo` 还把密码打进 logcat（`Bus.java:67`）；两端的完整 payload 也被打印（`HceService.java:50`、`TapService.java:130`；`Xfer.java:206-210` 还记录对端热点 SSID） | ❌ | 任何能 `SELECT F05052494E4643` 并发 EXCHANGE 的设备（无需配对，锁屏也可用：`apdu_service.xml:4` `requireDeviceUnlock=false`）都能拿到热点 SSID/密码/局域网 IP；1.2s 缓存期内可重复读。日志是第二条泄漏面。 | **P0**：凭据不再进 NFC payload；NFC 只交换临时 ECDH 公钥/会话标识，现场派生密钥后用 AES-GCM 加密凭据（受 200B 限制时改两轮 EXCHANGE）；删除密码日志。 |
| 2 | APDU 单帧 ≤255 字节硬上限、无分块；超长必须报错（§6.3.3、§11.2“载荷”、§11.3“APDU 帧长”） | `MAX_PAYLOAD=200`（`Bus.java:41`）；`buildExchangeApdu` 超长**静默截断**只打日志（`Bus.java:96-113`）；`safe()` 把竖线（0x7C）/分号替换为 `/`/`,`（`Bus.java:197-199`）；接收侧 `parseExchangeResponse` 不校验字段完整性（`Bus.java:115-118`）；HCE 响应侧没有同样的上限检查（`HceService.java:52-56`） | ❌ | 截断发生在尾部，而 `hs`/`hp`/`ip` 正好在尾部（`Bus.java:170-177`），超长时最先丢；对端只看到缺字段，没有错误。请求侧截断、响应侧不限，行为不对称，响应超长时可能超过 ISO-DEP/应用层预期。`k=v` 拼接无转义/长度前缀，`safe()` 是**破坏性替换**而非转义，含分隔符的 SSID/密码会被改坏。 | **P1**：超长改为显式失败并给出可见错误；用长度前缀/百分号转义替换以竖线分隔的 `k=v` 串；与 P0 加密一起做（加密会增大载荷）。 |
| 3 | HCE AID 用 `F0` + 厂商号 + ASCII 品牌域名，便于人肉识别（§11.1） | `F05052494E4643` = `F0 50` + ASCII `PRINFC`（`Bus.java:33-37`；`res/xml/apdu_service.xml:8`） | ✅ | 结构同思路、可读；AID 与 OPPO 不同不影响互通。 | P2：保持。 |
| 4 | OPPO `requireDeviceUnlock=true`；文档明确建议我们保持 `false`（§11.1） | `res/xml/apdu_service.xml:4` `requireDeviceUnlock="false"` | ✅ | 锁屏可传是体验优势；但在 #1 未加密前，锁屏可读放大了凭据泄漏风险。 | P2：保持 `false`；先完成 #1 的加密。 |
| 5 | 读卡侧用私有 `DIRECT_DISCOVERED` 广播 + 纯 trampoline（§11.1） | 公开 `TECH_DISCOVERED` + `TapDispatchActivity` 透明跳板（`AndroidManifest.xml:127-141`；`TapDispatchActivity.java:10-22`；`res/xml/tech_list.xml` 仅声明 IsoDep） | ✅ | 不必改系统 NFC 服务，方向更干净；代价是所有 IsoDep 标签都会进跳板，需靠 AID SELECT 过滤（`TapService.java:526-534`）。 | P2：保持；可在跳板里先看 AID 再转交，减少无关标签唤醒。 |
| 6 | 厂商 `VendorNfcAdapter.hasFeature("TAP_TO_SHARE_WITH_IOS")` / `enableNfcShareMode`；完整 iOS 子系统（§11.1、§8） | 无 iOS 支持；无厂商 NFC 反射调用。全树 grep `VendorNfcAdapter`/`enableNfcShareMode`/`TAP_TO_SHARE`/`iOS`/`IPHONE` 均为 0 命中。 | ➖ | 文档明确建议暂不跟：需要 iPhone 上的 App + universal link 域名 + 云端兑换，成本极高。 | P2：不跟（除非产品明确要求）。 |
| 7 | **UKey2**（ECDH P-256 + SHA-512 + HKDF）或 OOB Key 做密钥协商（§3A.2、§11.4） | 全树无 `Cipher`/`KeyAgreement`/`ECDH`/`SecretKey`/`KeyStore` 使用（grep 证据）；payload 里的 `t=` 只是 8 位 UUID 子串（`Bus.java:165`），不是密钥。 | ❌ | 首配对和每次传输都没有会话密钥；后续所有加密/认证都无从谈起。 | **P0**：与 #1 合并做轻量 ECDH（JCE P-256 + HKDF-SHA256），不必引入完整 UK2 库；会话密钥同时用于 NFC 凭据和 TCP 帧。 |
| 8 | UK2 的 2 字节用户验证串被 OPPO **显式丢弃**；文档建议“反着抄”（§3A.3、§3A.10） | 无 UKey2，也无验证串。 | ❌ | 若只做匿名 ECDH，仍可能被主动中间人；2 字节比对串是低成本缓解。 | P2：一旦做 #7，在两端确认页显示 2 字节/4 位验证码并要求用户比对；不要学 OPPO 丢弃。 |
| 9 | **KSC** 16B + alias 6B 持久化到 `ksc_info`，按 `(deviceId,alias)` 唯一索引；重连 HMAC-SHA512 挑战-应答免协商（§3A.4-5、§11.4） | 无 KSC/密钥库；`SharedPreferences("tapdemo")` 只存待发 Uri（`TapService.java:229-247`）；每轮 `resetRoundState()` 清连接状态（`Xfer.java:226-256`）；每次碰触都重新交换 payload/建链。 | ❌ | 没有“老用户碰一下就传”；每次都是全新会话。没有设备身份记忆，也无法做同设备自动放行。 | P2：在 #7 的会话密钥之上做 KSC（新类 + 加密存储）；按 `(deviceId,alias)` 精确查，不要遍历。 |
| 10 | **同账号 → 自动同意、不弹 UI**；但只比 `accountHash` 前 3 字节（24 bit，碰撞 1/1677 万），文档建议比完整 hash（§3A.3、§3A.10、§11.4） | 无账号概念；payload 只有 `n/b/t/ts/p/a/ip/hs/hp`（`Bus.java:155-178`），没有 account/hash 字段；`TapService`/`Xfer` 无同账号分支。 | ❌ | 没有自动放行 UX；若未来实现，OPPO 的 3 字节比较是真实攻击面，不能照抄。 | P2：需要账号体系时再做；至少比完整 hash（32B），并定义账号绑定失败的回退。 |
| 11 | KSC at-rest 用 `AES/CBC/NoPadding` + 固定 keystore 别名；挑战-应答**遍历所有已存 KSC**（§3A.4、§3A.8） | 无 KSC，故无 at-rest 加密/挑战-应答实现。 | ➖ | 不适用；但文档明确说 CBC 无认证标签、遍历所有密钥有跨设备混用风险，不应照抄。 | P2：若做 KSC，用 AES-GCM；按 deviceId 精确查找；每设备独立密钥。 |
| 12 | `ACCESSORY_INSECURE_NFC_SHARE=2` 三档**模式标志位**（0/1/2），而不是布尔；且它不跳过加密（§3A.7、§11.4） | 安全/配对没有模式概念；角色是多个 boolean（`TapService.java:41-53`、`Xfer.java:130-139`）；`OverlayService` 的 `Mode.SEND/RECEIVE` 只是 UI 身份（`OverlayService.kt:97`、`341`；`OverlayView.kt:959-960`）。 | ❌ | 没有可扩展的安全档位；布尔已出现 `mUserWantsSend/mSendStarted/mRejected/mPeerOffered/mCanSend/mReceiverRole` 六个状态，容易组合出非法态。 | P2：引入 `ShareMode`/`SecurityTier` 枚举；先梳理角色状态机，再替换 boolean。 |
| 13 | 应用层 **AES-GCM-128**（12B IV）+ HMAC-SHA512 挑战；`SPP_INSECURE` 只是“无感通道”，不跳过加密（§3A.8、§11.4、§6.2） | TCP 8988 明文：文件字节直接 `write`（`Xfer.java:997-1010`）；控制帧也是裸 MAGIC/TYPE/LEN（`Xfer.java:57-62`、`1074-1112`）；无 TLS/Cipher；API 33+ 优先使用固定 SSID/密码 `TapShare-Transfer` / `tapshare2026`（失败回退系统默认）（`Xfer.java:55-56`、`312-320`）。 | ❌ | 同一热点/P2P 组内任何设备都能连 8988（服务端不认证对端），文件、预览、回执全部明文；固定热点口令让“不知道 NFC 也能加入”成为可能。 | **P0**：随机化热点口令（或改用系统默认随机密码）；在 #7 的会话密钥上做逐帧 AES-GCM（nonce + AAD=magic/type/len），并校验对端身份。 |
| 14 | **契约链**：有序契约 + 每步 `withTimeoutOrNull` + 按当前契约翻译专属异常（§1.2 #1、§3.0-3.2、§11.2） | 无契约类；`Xfer` 用分散的线程/Socket 流程；失败统一走 `fail(String)`（`Xfer.java:1169-1174`），`TapService` 再转成状态行/banner（`TapService.java:470-483`）。 | ❌ | 失败只能靠日志文本定位；没有“卡在哪一步”的结构化信息；新增阶段要改多处。 | **P1**：不引入完整类层次，先做最小版——`Stage` 枚举（NFC_EXCHANGE/CONNECT/OFFER/VERDICT/TRANSFER）+ 阶段进入/退出 + 阶段专属超时。 |
| 15 | **分层超时**：机器步骤 6s；等一个用户 10–23s；双人确认 65s；真传 24h（§3.9、§3.2、§11.2、§11.3） | `VERDICT_WAIT_MS=60000`（`Xfer.java:63`、`1030-1054`）；`PROBE_MS=3000`（`64`、`729-743`）；`connectTo` 1.5s×2（`474-490`）；热点请求 30s/等待 31s（`524-525`）；P2P 重试 5 次线性 `3+2n` 秒（`600-615`）；`startClientRead` 6×5s+2s（`783-800`）；`sendImages` 等连接 10s/20s（`936-951`）；NFC ISO 3s（`TapService.java:527`）；传输阶段无总超时/空闲超时。 | ⚠️ | 60s 已接近 OPPO 的 65s；真正的差距不是文档 §11.2 说的“单一约 20s”（该描述已过时），而是：没有 6s 机器步骤预算、没有 10/23s 首次确认档、发送方上滑无超时、传输无 24h 上限/停滞检测。 | **P1**：按 Stage 定义常量并统一使用；建议 NFC 3–6s、连接 15s、报价裁决 65s、传输 24h 总上限 + 10–30s 空闲检测；发送页加 65s 无操作收起（产品确认）。 |
| 16 | 6 类配对超时异常 + 7 个 `TransferConfirmResult`，能区分接收端授权失败等（§3.2、§3.9、§11.3） | 只有 `onXferFailed(String)`/`onVerdictTimeout`/`onOfferExpired`/`onPeerRejected` 等回调（`Xfer.java:39-51`）；没有错误码/阶段；字符串自由拼（如 `Xfer.java:943`、`950`、`1022`）。 | ❌ | UI 只能显示“发送失败 + 前 12 字”（`TapService.java:476-478`）；接收端失败基本只进日志；无法统计/区分权限、网络、对端拒绝、超时。 | **P1**：定义 `XferError{code, stage, cause}`；所有 `fail` 走同一出口；`TapService` 映射成 `strings.xml` 的稳定文案；与 #25 一起提交。 |
| 17 | **两次确认**：配对链“要不要传”（10s/23s）+ 传输链“开始传”（65s/65s）（§5、§3.2） | 两阶段数据协议，但每端只确认一次：发送方上滑 → META+预览报价（`TapService.java:176-191`；`Xfer.java:956-980`）；接收方点接收 → ACCEPT → 才开始发整图（`TapService.java:193-207`；`Xfer.java:973-980`、`997-1020`）。 | ⚠️ | 没有接收方“先确认要不要连”，也没有发送方“接收方同意后再确认开始”；两端的“要不要传/开始传”被压缩成各一次。数据安全上没问题（ACCEPT 前不发整图），但与 OPPO 的两次确认 UX 不同。 | P2：产品决定是否补第二次确认；文档也说我们的两阶段语义正确，不建议为凑报文而加。 |
| 18 | **显式角色协商**：`NfcPairRole` + `InternalNfcEventRole`，在 DataSync 中交换并裁决（§11.2） | “谁先滑谁是发送方”：`mCanSend=hasPending()`（`TapService.java:321-323`），`setSenderRole()` 在上滑时把 `mReceiverRole=false`（`Xfer.java:449-453`）；`Bus.lockRole()` 只锁本进程事件、30s 过期（`Bus.java:270-287`）。 | ❌ | 两端都持照片且几乎同时上滑时没有跨设备裁决，角色可能冲突/双发；`lockRole` 不能解决跨设备竞态。 | **P1**：在 NFC payload 里加 `roleIntent/hasPhotos/sessionToken`，用确定性规则裁决（如 token 小者优先），冲突时给用户可见提示；与 #1 的 payload 改版一起做。 |
| 19 | `[1B 报文类型][protobuf]`，类型值故意选可打印 ASCII（§11.2） | `MAGIC(4B)+TYPE(4B)+LEN(4B)+data`（`Xfer.java:57-62`、`817-828`、`958-968`）；TYPE 是 1–5 的小整数（`58-62`）。 | ⚠️ | `LEN` 明确是优点；类型不是可打印字符，抓包不直观；没有版本字段。 | P2：保留 `LEN`，把 TYPE 改成可打印 ASCII（如 `M/A/R/F/P`）并加 1B 版本；低风险。 |
| 20 | 18 个具名报文类型；文档建议我们短期够用、不要凑数（§3.6、§11.2） | 5 个类型：`PREVIEW/FULL/META/ACCEPT/REJECT`（`Xfer.java:58-62`）。 | ✅ | 两阶段（预告 → 裁决 → 交付）语义与 OPPO 的确认模型一致；暂不需要更多类型。 | P2：保持；等需要取消/进度/重试时再扩展。 |
| 21 | **两级承载**：缺省 BT-SPP，条件升级 Wi-Fi P2P；事前 6 条件判据（§6.2、§11.3） | 优先级：对方热点 → 同 LAN → Wi-Fi Direct（`Xfer.java:460-471`、`493-571`）；无 BT；每种都有重试/兜底。 | ⚠️ | 有“优先级”但没有 OPPO 那种显式能力判据（对端 deviceType/wlanMode/networkName 等）；Wi-Fi 选择对传图更合适，文档也建议保留。 | P2：保留 Wi-Fi 主路径；把选择条件显式化并记录决策日志；不引入 BT-SPP。 |
| 22 | 主链路**不重试**（让用户再碰）；连接层**重度重试**（GATT 3 次、指数退避封顶 1h）（§6.5、§11.3） | P2P 搜索最多 5 次、线性 `3+2n` 秒（`Xfer.java:600-615`）；`connectTo` 2 次（`474-490`）；热点内连接 3 次（`551-565`）；组主连接 6 次（`786-796`）；`sendImages` 等 10s/20s（`936-951`）。 | ⚠️ | 已有上限——文档 §11.3 说“mRetry 当前无上限”与当前代码不符（`Xfer.java:601` 已限制 5 次）。差距是各层重试预算混在 `mRetry`/局部循环里、全是线性、传输阶段没有重试策略。 | **P1**：每层独立计数器；连接层指数退避+封顶；协议主链路不自动重试；传输开始后禁止重新发现/重连。 |
| 23 | SCP 心跳 10s/90s；接收端进度心跳 10s、两轮无变化 → `BT_DISCONNECTED`（§6.5、§11.3） | 无心跳；`readVerdict` 只设 60s `SO_TIMEOUT`（`Xfer.java:1030-1034`）；接收端 `handleIncoming` 的 `readInt` 无超时（`804-828`）；接受后发送端 `write` 也无超时（`997-1010`）。 | ❌ | 对端掉线/假死时，接收端可能永久阻塞；大图传输中没有“进度停滞”判定；也没有 24h 总上限。 | **P1**：加 PING/PONG（10s）+ 两轮无进展失败；接收/发送 socket 设读超时；传输总上限 24h、空闲上限 10–30s。 |
| 24 | `onPercentChange` 只打日志 + 取消 25s 看门狗；UI 进度环由独立动画源驱动（§6.4、§11.3） | 没有 `onPercentChange`/百分比；回调 `onImageReceived/onPreview` 直接驱动状态行和卡片（`Xfer.java:39-50`；`TapService.java:391-455`）；`OverlayView` 的时间轴独立，但传输反馈没有独立驱动源。 | ❌ | 传输回调与 UI 仍耦合；没有可复用的进度模型；文档 §11.3 说“我们有进度相关逻辑”不准确（只有 x/y 计数）。 | P2：若产品要进度环，先建 `TransferProgress` 模型（字节/张数/速率），UI 从模型取；回调只更新模型。 |
| 25 | 失败可诊断：6 类配对异常 + 7 个传输结果；失败横幅/toast（§3.2、§3.9、§11.3、§6.5） | 失败横幅存在，但只在本机已声明发送方时显示（`TapService.java:474-480`）；详情截到 12 字（`476-478`）；接收端的 `onOfferExpired` 只隐藏卡片/解锁（`486-493`）；字符串只有 `fail_title/fail_rejected/fail_timeout`（`res/values/strings.xml:71-73`）。 | ⚠️ | 接收端失败无 banner；长原因被截断；无权限/网络/对端不存在等分支；无法区分“谁失败”。 | **P1**：与 #16 合并成错误码→文案映射；两端都显示；banner 支持两行/可展开，不截断。 |
| 26 | 框架不落盘，OShare/接收 App 负责落地（§4.7、§11.5） | 接收侧自己写 MediaStore（`Xfer.java:1216-1237`）并打开应用内查看器（`TapService.java:646-665`）。 | ✅ | 职责在接收侧、落地成功才打开查看器；与 OPPO 的差异是我们没有 OShare，必须自己写，文档也明确说“我们要自己做落盘”。 | P2：保持；后续可把落盘从 `Xfer` 抽到独立 `ReceiverStore`，便于测试。 |
| 27 | P2P 连不上就失败，不回落 SPP（§6.2、§11.3） | 有重试/兜底：热点 → LAN → P2P，失败继续重试（`Xfer.java:460-471`、`600-615`）。 | ➖ | 文档明确建议不要照抄 OPPO 的“无兜底”；我们的重试更适合近场传图。 | P2：不跟（保留重试）。 |
| 28 | 文件字节委托 `com.coloros.oshare`（§6.4、§11.3） | 没有 OShare，自己用 Wi-Fi/TCP 传、自己落盘。 | ➖ | 私有服务不可用；自建传输是唯一选择。 | P2：不跟（保持自建）。 |
| 29 | **数据源自报**：`registerShareAgent` → 回调 `CONTRACT_START` → `startShare`；有硬顺序约束（§1.2 #2、§4.6、§11.5） | 自有契约：`ACTION_SEND_MULTIPLE` 发布选择 + `ACTION_CLEAR_SELECTION` 清除（`TapService.java:58-92`、`156-174`、`169-174`）；`sPublished` 决定优先于读屏（`TapService.java:92`、`269-273`）；Google Photos 走无障碍+截屏+像素匹配（`GalleryPickup.java:111-138`、`AccessibilityProbeService`）；调试相册已实现发布（`Temp/debuggallery/src/com/pri/debuggallery/MainActivity.java:267-269`、`307`）。 | ⚠️ | 形状对齐（数据源自报 + 清除 + 读屏兜底），但没有 OPPO 的长连接 `registerShareAgent` 回调和硬顺序约束；系统相册（Google Photos）仍靠读屏，屏幕外多选/无障碍关闭时会退化；`PENDING` 跨进程持久化但 `sPublished` 不恢复（`TapService.java:87-92`、`239-247`），冷启后可能走读屏。 | P2：优先让自家相册/ROM 相册接发布契约；给发布加“本轮有效”令牌/过期；读屏保留为兜底。需要相册侧配合，App 单独改不动。 |
| 30 | 确认卡缩略图由**数据源提供 Bitmap**（§4.1、§4.4、§11.5） | 引擎自己解码第一张 URI 当预览（`Xfer.java:962`、`1133-1154`）；卡片图再由接收侧落缓存（`TapService.java:624-641`）。 | ⚠️ | 预览可能不是用户实际选中的那张（多选时只取第一张）；源 App 无法控制卡片外观。 | P2：在 `ACTION_SEND_MULTIPLE` 里加缩略图 extra（或允许源 App 提供 card 文案/Bitmap），调试相册先接。 |
| 31 | 多记录列表 `List<SharedRecord>`；文档建议 META count + 逐张 FULL 结合（§4.2-4.3、§11.5） | 多张图逐张 `TYPE_FULL`；`TYPE_META` 的 `len` 即张数（`Xfer.java:58-62`、`829-838`、`997-1009`）。 | ✅ | 支持多选/流式逐张；与文档建议的组合一致。 | P2：保持。 |
| 32 | count 放在 `fileCount`，但接收端不校验（缺陷）；我们已有 `mExpect`（§4.5、§11.5） | `mExpect` 记录 META 张数（`Xfer.java:829-838`）；`count>=expect` 时提前发 `onTransferDone`（`871-876`）；EOF 时 `count>0` 也发 done（`879-883`）。 | ⚠️ | `mExpect` 只用于“何时算完成”，不是严格校验：少发时 EOF 仍报完成，多发时不报错；没有“数量不一致”失败。 | P2：EOF 时若 `count != expect`，走明确失败/警告；把 expect 纳入 TransferResult。 |
| 33 | `major_type`/`DeviceType`（PHONE=8/PAD=10/TV=5/PC=6/WATCH=14…）+ 手机×平板自动接受（§3.7、§3.10、§7） | payload 只有 `n=Build.MODEL`（`Bus.java:163`）；设备匹配靠型号/蓝牙名/P2P 名（`Xfer.java:653-665`）；无 deviceType/major_type，无按设备形态的分支或自动接受。 | ❌ | 无法表达/驱动“手机→平板免确认”、TV/PC/Watch 不同 UI/流程；设备类型只用于显示名（`TapService.java:607-615`）。 | P2：若产品扩到 Pad/TV/PC，先在 payload 加 `majorType/deviceType`，再做分支；否则不做。 |
| 34 | `modelId_*` 素材目录 + `stateid` 语义名 + 深色显式声明 + 缺图回退；三级资源（内置→SAU→服务器）（§7、§7.8） | 无 modelId 素材；`res/` 只有通用 drawable/layout/values + `values-night`（主题与日间一致）；按 API 版本做窗口/圆角分支（`OverlayService.kt:809-825`、`OverlayView.kt:1695-1701`），不是按机型/系统版本下发。 | ➖ | 当前产品只有手机×手机、自家 ROM、自绘 UI，不需要 23 种设备形态素材；文档也说三级资源“先不做”，但若扩到 Pad/TV/PC 就必须做。 | P2：暂不做；扩展设备形态时按 `stateid` + `major_type` 数据驱动，先内置、不引服务器。 |
| 35 | COE 动效引擎（§2 第三方依赖、§9.3） | 没有 COE；动效是自绘 Canvas + GL 水波（`OverlayView.kt:1705 onDraw`；`RippleRenderer.kt:161 onDrawFrame`；`ParticleView`/`SenderParticles`/`ReceiverRenderer`）。 | ➖ | COE 是 OPPO 私有引擎，不是可移植的协议/机制；我们用 Canvas/GL 已达到参考视频的动效。 | P2：不跟；保持自绘。 |
| 36 | RichTap/`HapticPlayer` 触感 + OGG 音效（§2、§9.3） | 全树无 `Vibrator`/`SoundPool`/`MediaPlayer`/`ToneGenerator`/`R.raw`（grep 证据）；`res/` 无 `raw/` 目录；`OverlayService.kt:1-42` 无相关 import。 | ❌ | 发送/接收/拒绝没有任何声音或触觉反馈；参考产品有。 | P2：加短音效（SoundPool + OGG）和 `VibrationEffect`（RichTap 不可用时用系统 Vibrator 降级）；注意勿在静音/勿扰模式强行响。 |

---

## 2. 文档与当前代码不一致的地方（审计中发现）

`docs/OPPO_TECHNICAL_DETAIL.md` 的 §11 是分析当时对代码的核对结果；本次审计发现其中几处已过时或不精确，引用时需注意：

1. **§11.2 说我们是“单一超时（约 20s）”**：当前已有 `VERDICT_WAIT_MS=60000`（`Xfer.java:63`），另有 3s 探测、20s 连接等待、30s 热点请求、5 次 P2P 重试等。正确的说法是“有多个临时超时，但未分层/未阶段化”，不是“只有一个 20s”。
2. **§11.3 说 `mRetry` 无上限**：当前 `retryDiscovery()` 在 `mRetry >= 5` 时失败（`Xfer.java:601`），有上限；问题在于各层重试混在一起、全是线性退避。
3. **§11.3 说我们“有进度相关逻辑”**：当前没有 `onPercentChange`/百分比，只有 `onImageReceived(index,total)` 的 x/y 计数；没有独立进度模型。
4. **§11.5 说 `mExpect` 做校验**：当前 `mExpect` 只用于提前判定完成；EOF 时少发仍会报完成，不是严格校验（`Xfer.java:871-889`）。
5. **§11.4 说我们传明文 WiFi 密码和 IP**：仍然成立，且本次还发现两处文档未提的泄漏面——`Bus.setHotspotInfo` 把密码打进 logcat（`Bus.java:67`），以及 API 33+ 热点口令是硬编码固定值（`Xfer.java:55-56`、`312-320`）。
6. **§11.1 说 `TECH_DISCOVERED` 需要自己过滤 AID**：我们确实靠 `SELECT` 过滤（`TapService.java:526-534`），但跳板会对所有 IsoDep 标签启动服务，属于可优化项，不是安全缺陷。

---

## 3. 建议实施顺序与改动面

### P0：先修安全广播与明文传输

| 顺序 | 项 | 改动面 | 能否独立提交 | 依赖/注意 |
|---|---|---|---|---|
| P0-A | #1 + #7：NFC 凭据延迟注入 + ECDH/AES-GCM 会话 | `nfc/Bus.java`（payload schema、`selfPayload`、APDU 编解码）、`nfc/HceService.java`（响应）、`nfc/TapService.java`（`handleTag`/字段解析）、`nfc/Xfer.java`（`setExpectedPeer` 参数）；新增 `nfc/NfcSessionCrypto.java`（建议） | 可以，作为一个安全提交（或拆成“ECDH 工具 + 接入”两笔） | **200B 硬约束**：公钥 + 密文凭据放不进单帧。建议两轮 EXCHANGE（第一轮交换临时公钥，第二轮交换加密凭据），或减少非必要字段；`PAYLOAD_TTL_MS` 只缓存非敏感 base，凭据必须现场算。 |
| P0-B | #13：TCP 逐帧 AES-GCM + 随机热点口令 + 对端认证 | `nfc/Xfer.java`（帧读写、`startHotspot`、`joinHotspot`、`readVerdict`/`writeVerdict`）；复用 P0-A 的 crypto helper | 可以，独立于超时/契约改造 | 依赖 P0-A 的会话密钥；热点改为系统默认随机密码或每次随机生成，删掉 `HS_PASS` 固定值。 |
| P0-C（前置，实际可先做） | #2：超长报错 + 结构化编码 + `safe()` 转义 | `nfc/Bus.java`、`nfc/HceService.java`、`nfc/TapService.java` | 可以，改动很小 | 这是 P0-A 的前置：加密会增大载荷，必须先让超长可见、可处理。 |

> 建议实际提交顺序：**P0-C（小修）→ P0-A（NFC 安全）→ P0-B（TCP 安全）**。三项都可独立编译、独立回滚。

### P1：协议/可靠性

| 顺序 | 项 | 改动面 | 能否独立提交 | 依赖/注意 |
|---|---|---|---|---|
| P1-A | #14 + #15 + #16 + #25：Stage/分层超时/错误码/banner | `nfc/Xfer.java`（Stage、超时常量、`XferError`）、`nfc/TapService.java`（错误→文案、两端 banner）、`service/OverlayService.kt`（banner 展示）、`res/values/strings.xml` | 可拆两笔：①错误码 + banner；②Stage + 超时。各自独立 | 与 P0 改同一批文件（Xfer/TapService），建议排在 P0 之后，减少冲突；不需要完整契约类层次。 |
| P1-B | #18：显式角色协商 | `nfc/Bus.java`（payload 加 `roleIntent/hasPhotos/sessionToken`）、`nfc/TapService.java`、`nfc/Xfer.java` | 可以，独立于超时改造 | 依赖 P0-A 的 payload 版本；若 P0 已改 payload schema，最好合并到同一次协议升级。 |
| P1-C | #22 + #23：分层重试 + 心跳/停滞检测 | `nfc/Xfer.java`（独立重试计数器、指数退避、PING/PONG、读超时、24h/空闲上限） | 可以，基本只动 Xfer | 与 P1-A 有冲突风险（都在 Xfer），建议 P1-A 之后。 |
| P1-D | #2（若未随 P0-C 做） | 同 P0-C | 可以 | 见上。 |

### P2：其它（按收益排序）

1. **#9 + #11 KSC/同设备免协商**：`TapService`/`Xfer` + 新持久化类；依赖 #7 会话密钥；可独立提交。若做，at-rest 用 AES-GCM、按 deviceId 精确查、每设备独立密钥。
2. **#8 UK2 验证串**：确认页 UI（`OverlayView`/`OverlayService`）+ #7 的密钥派生；依赖 #7，可独立提交。
3. **#12 模式枚举、#17 两次确认**：`TapService`/`Xfer`/`OverlayService` 状态重构；建议先梳理角色状态机，再动；可独立提交，但改动面较大。
4. **#10 同账号自动放行**：需要账号体系/账户哈希；`Bus`/`TapService` + 账号 SDK；依赖产品定义，暂不排期。
5. **#29 相册自报数据**：`TapService` 契约 + 调试相册/系统相册；App 侧可先做“发布令牌/过期”，系统相册接入需 ROM 配合，不能单独提交成完整闭环。
6. **#30 预览图源提供、#32 count 严格校验、#33 deviceType**：都是数据模型/协议字段扩展；建议合并成一次“协议 v3”升级（payload 加 `majorType/preview/count`），但优先级低于安全与超时。#34 机型素材只在扩到 Pad/TV/PC 时才做。
7. **#19 可打印报文类型、#21 承载判据、#24 进度模型**：局部优化，`Xfer` 内可独立提交。
8. **#36 音频/触觉**：`res/raw/` + `OverlayService`/`TapService` 播放；独立，但需产品确认静音策略。
9. **#34/#35/#6/#27/#28**：不适用或不跟，见上表。

---

## 4. 最该先改的三项

1. **#1 NFC payload 明文凭据（P0）**
   任何能选我们的 AID 的读卡器都能拿走热点 SSID/密码/IP，锁屏也可读；`Bus.setHotspotInfo` 还把密码写进 logcat。攻击门槛极低，且这是“路人可读”级别的暴露。改法明确：凭据移出常驻 payload，NFC 只交换临时 ECDH 公钥，现场派生密钥后 AES-GCM 加密注入。

2. **#13 传输明文 + 固定热点口令（P0）**
   文件字节和控制帧在 TCP 8988 上完全没有加密；API 33+ 优先使用硬编码的 `tapshare2026` 热点口令，同一热点内任何设备都能连 8988，且服务端不认证对端。它和 #1 是同一套“凭据/内容暴露”问题的另一半，应该一起修。

3. **#2 静默截断 + `safe()` 破坏性替换（P1，但建议紧随 P0）**
   这是真实 bug：payload 超过 200B 时只打日志、对端拿到残缺数据；尾部正好是 `hs/hp/ip`，含 `|`/`;` 的 SSID/密码又会被 `safe()` 改坏。修复成本极低，而且是任何加密方案（会增大载荷）的前置条件。它排第三，不是因为风险高于 #14 契约链，而是因为它是“先修五分钟、避免后续安全改造被静默破坏”的一步；#14 仍是 P1 里收益最大的结构性改造，紧随其后。
