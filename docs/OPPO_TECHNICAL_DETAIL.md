# OPPO Quick Connect（`com.heytap.accessory` 16.35.0）碰一碰互传 —— 逆向与架构研究

> 分析对象：`Temp/AccessoryFramework.apk`（**只读，未修改、未入仓**）
> 反编译产物：`_Temp/afk/`（smali / jadx / 资源 / assets，均未入仓）
> 分析日期：2026-10-17
> 方法：`aapt2 dump` + `baksmali 3.0.9`（5 个 dex 全量）+ **jadx 1.5.6**（本次新装，见 §0.3）+ Kotlin `@DebugMetadata` 还原混淆名
>
> 本文是**参考方向**，不是抄写目标。团队自建 ROM，凡是"依赖 OPPO 私有件"的地方，含义都是**"我们要自己实现一个等价物"**，而不是死路。

**证据标注**（每条结论后标注来源强度）：

| 标记 | 含义 |
|---|---|
| `[smali]` | 从 baksmali 反汇编直接读到 |
| `[jadx]` | 从 jadx 反编译 Java 直接读到 |
| `[资源/清单]` | 从资源表 / AndroidManifest 读到 |
| `[assets]` | 从 assets 文件直接读到 |
| `[推断]` | 由多条证据推理，未直接读到 |
| `[未确定]` | 无法确认 |

**目录**

| 章 | 内容 | 备注 |
|---|---|---|
| §0 | 方法学突破（**混淆名还原** / jadx 装法与坑） | 决定全文可信度，建议先读 |
| §1 | **结论先行** —— 体系全貌一页 + 最值得借鉴的五件事 | |
| §2 | 组件与包全景（速览） | 完整版在 §9 |
| §3 | **NFC 交互全流程** —— 两条契约链 / 状态机 / APDU / 事件常量 / 超时总表 | ★ 核心 |
| §3.8 | 安全速览 | 完整版在 **§3A** |
| §4 | 照片 / 数据交互 —— 数据模型 / 相册侧交付 / 接收侧落地 | |
| **§3A** | **安全深挖** —— UKey2 / KSC / `ksc_info` / 降级逻辑 / 信任模型 | ★ **物理位置在 §4 之后**，是 §3.8 的完整版 |
| §5 | 收发双方确认时序（对照视频） | |
| §6 | 传输层 —— 字节怎么走 / 失败超时重试 | |
| §7 | 逐机型素材机制（`modelId_*` / `major_type`） | |
| §8 | **iOS 互通全流程** | 独立章节 |
| §9 | 组件 / 权限 / 私有依赖台账（完整版） | |
| §10 | 三方 SDK 契约 | |
| §11 | **与我们实现的对照** —— 逐项「它 / 我们 / 该不该跟 + 理由」 | |
| §12 | **未确定的部分**（诚实清单）+ 被修正的前序结论 | |
| 附录 | 证据文件 / 关键类速查（混淆名↔真名） | |

---

## 0. 本次分析的方法学突破（先说，因为它决定了本文的可信度）

### 0.1 混淆还原：Kotlin `@DebugMetadata` 泄漏了真名 ⭐

这个 APK 的类名被混淆成 `nrn` / `vwv` / `hi/b` / `r7/a` 这种形式。但 **Kotlin 编译器为每个 suspend 函数生成的 `ContinuationImpl` 子类上会挂 `@kotlin.coroutines.jvm.internal.DebugMetadata` 注解**（smali 里写作 `.annotation runtime Lrn/c;`），其字段携带**原始类名 `c=`、原始文件名 `f=`、原始方法名 `m=`、行号 `l=`**。本轮只混淆了"顶层类名"，**没有剥离这些注解**。

结果：我们用一个脚本从全部 5 个 dex 里抽出了 **2104 条注解、覆盖 2069 个被混淆的类**，还原出它们的真实身份：

```
javax/crypto 无关；示例：
com/oplus/interconnect/nfc/pair/contract/h   → NfcPairInitContract
com/oplus/interconnect/nfc/pair/contract/b   → KscNegotiationContract
com/oplus/interconnect/nfc/pair/contract/d   → NfcConnectAccessoryContract
com/oplus/interconnect/nfc/pair/contract/g   → NfcDataSyncContract
com/oplus/interconnect/nfc/pair/contract/i   → NfcReceiverConfirmContract
com/oplus/interconnect/nfc/pair/contract/k   → NfcSenderConfirmContract
com/oplus/interconnect/nfc/pair/l            → NfcDataContractProxy
com/oplus/interconnect/nfc/pair/d            → ClientNfcPairingV3
com/oplus/interconnect/nfc/pair/a0           → ServerNfcPairingV3
com/oplus/interconnect/nfc/share/g           → NfcShareContractProxy
com/oplus/interconnect/nfc/processor/e       → RealShareServer
com/heytap/accessory/beacon/pair/contract/d  → BeaconPairInitContract
...
```
`[smali]` 证据：`_Temp/afk/smali_classes4/com/oplus/interconnect/nfc/pair/contract/NfcDataSyncContract$create$1.smali:9-19`

完整映射表：`_Temp/afk/notes/obf_class_map.tsv`（2069 行）、`_Temp/afk/notes/outer_resolved.txt`（387 个"外层类"映射）。

> **对后续工作的价值**：这份映射表让"读 smali"从"猜变量"变成"读带真名的代码"。**建议团队把它当资产保留**（本工程内 `_Temp/` 不不入仓，但结论已写进本文）。
> **对我们的启发（可借鉴点）**：如果我们未来也要做混淆，**必须剥离/重写 `@DebugMetadata`**，否则等于把源码结构送给逆向者。这是很实在的一条安全经验。

### 0.2 jadx 单类模式是唯一能读出这些超大 Kotlin 类的手段

全量 jadx 反编译在 13869 个类上**报错 9910 个**（大量是 StackOverflowError / KotlinMetadata 解析失败），而且**恰好把这批最关键的契约类整个漏掉了**（因为外层类被重命名成单字母 `b/d/g/h/i/k`，jadx 的 inner-class 关联断了）。

解决办法：`jadx --single-class <混淆名>` 单独反编译，可以正常产出可读 Java。本文所有 `[jadx]` 证据都来自该模式。

### 0.3 本次新装工具（框架规则：一工具一子目录）

| 项 | 值 |
|---|---|
| 工具 | **jadx CLI 1.5.6** |
| 安装位置 | `~/.claude/platform-tools/jadx/` |
| 启动器 | `~/.claude/platform-tools/jadx/jadx`（bash 包装，用 alps 的 JDK21 跑） |
| 依赖 | `libs/` 下 39 个 jar，从 **Maven Central（`repo1.maven.org`）** 拉取 |
| 版本 | jadx-cli / jadx-core / jadx-dex-input 等均 `1.5.6` |
| 来源 | `https://repo1.maven.org/maven2/io/github/skylot/jadx-cli/1.5.6/` + 传递依赖 |
| **关键坑** | `github.com` 的 release CDN 在本服务器**被阻断**（`OpenSSL SSL_connect: Connection reset by peer`，`codeload.github.com` 也不通），所以**不能下官方 zip 包**；必须走 Maven Central 的 `jadx-cli-1.5.6-all.jar` + 传递依赖。`all.jar` 只是 CLI shim，不含 core。另需补 `logback-core`（`logback-classic` 的传递依赖在 POM 里是 `runtime` scope，自行递归时易漏），否则启动报 `No SLF4J providers were found` |
| 用法 | `~/.claude/platform-tools/jadx/jadx --single-class <类名> -d <out> <apk>` |

同步更新了 `~/.claude/platform-tools/TOOLS.md`。

---

## 1. 结论先行

### 1.1 一页体系全貌

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  数据源 App（相册 com.coloros.gallery3d / 文件管理器 / 分享面板）              │
│      │  ① 私有 RPC「Share Agent」：registerShareAgent / startShare           │
│      │     （鉴权：OCS uid+capability 白名单；须过 compat 包名白名单）        │
│      ▼                                                                        │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ com.heytap.accessory（本 APK，priv-app，Quick Connect）                │  │
│  │                                                                        │  │
│  │  ┌── 发现层 ──────────────────────────────────────────────────────┐   │  │
│  │  │ HCE 发卡侧 OplusHostApduService   AID F000004F50504F2E434F4D   │   │  │
│  │  │ 读卡侧   NfcDiscoveryService      action …DIRECT_DISCOVERED     │   │  │
│  │  │ 反射厂商 HAL com.vendor.nfc.VendorNfcAdapter.hasFeature(...)   │   │  │
│  │  └────────────────────────────────────────────────────────────────┘   │  │
│  │                              │                                        │  │
│  │  ┌── 配对状态机（两条契约链，见 §3） ──────────────────────────┐      │  │
│  │  │ 【配对链 6 步】pair/contract/*  by NfcDataContractProxy      │      │  │
│  │  │   NfcPairInit → NfcDataSync → NfcSenderConfirm               │      │  │
│  │  │   → NfcReceiverConfirm → KscNegotiation → NfcConnectAccessory│      │  │
│  │  │                                                              │      │  │
│  │  │ 【传输链 4 步】share/contract/*  by NfcShareContractProxy    │      │  │
│  │  │   ReadyConfirm → SenderConfirm → ReceiverConfirm             │      │  │
│  │  │   → TransferConfirm                                          │      │  │
│  │  │   （Watch 机型换成 Watch* 四件套）                            │      │  │
│  │  └──────────────────────────────────────────────────────────────┘      │  │
│  │                              │                                        │  │
│  │  ┌── 承载层 ────────────────────────────────────────────────────┐     │  │
│  │  │ SCP over BT-SPP（ConnectionType.SPP_INSECURE）                │     │  │
│  │  │ 碰一碰瞬间只有 APDU 走 NFC：仅传元数据，不传文件字节          │     │  │
│  │  └──────────────────────────────────────────────────────────────┘     │  │
│  │                              │                                        │  │
│  │  ┌── 安全 ────────────────────────────────────────────────────┐       │  │
│  │  │ OobKeyGenerator / UKey2KeyGenerator / KscGeneratorImpl      │       │  │
│  │  │ 派生出的 KSC 持久化进 ksc_info（per deviceId+alias）        │       │  │
│  │  └───────────────────────────────────────────────────────────┘        │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                              │                                                │
│          ② 真传文件 → com.coloros.oshare (FileTransferManager)                │
│          ③ 接收端落地 → Intent action com.oplus.interconnect.action.SHARED_DATA│
└──────────────────────────────────────────────────────────────────────────────┘
```

**一句话**：碰一碰（NFC）只负责"**确认双方身份 + 交换连接参数**"，真正传文件是 **BT-SPP 承载的 SCP 协议**，最终文件字节由 **OShare** 搬运；而**"传哪些照片"这件事，框架根本不查**——是**相册主动交给它的**。

### 1.2 最值得借鉴的五件事

| # | 借鉴点 | 为什么值得 | 证据 |
|---|---|---|---|
| **1** | **契约链（contract chain）编排**：把一段多方交互拆成有序的、每步有独立超时的"契约"对象，用一个统一的 driver 顺序跑，超时时按"当前卡在哪一步"映射成具体错误 | 我们的实现是**一个大状态机 + 一个端口 + 裸帧**，出错只能报"超时"。契约链让**每一步的失败都能被精确定位**（6 步配对链对应 6 个专属异常类），且**新增场景只要往数组里加一个契约** | `[jadx]` `NfcDataContractProxy$startContract$deferred$1.java:97-125`；`[smali]` `vh/d.smali:64-125` |
| **2** | **数据源自报模型（share agent）**：数据不是"取"的，是数据源收到回调后主动 `startShare` 交上来的 | 一次解决"屏幕外项目、多选、不需要媒体权限、不需要无障碍"四个问题。**我们自己的相册/看图器就是数据源**，可以直接照抄，零 OPPO 依赖 | `[jadx]` `processor/ShareProcessors$StartShareProcessor`；详见既有研究 `docs/OPPO_QUICKCONNECT_STUDY.md` §3.3 |
| **3** | **超时值按"人在哪一步"分级**：机器对机器的步骤 6 s，等用户确认的步骤 23 s / 65 s，真正传文件的步骤**24 小时** | 我们的实现是全局单超时。OPPO 的分级说明它明确区分了"**协议等待**"和"**人类反应等待**"——这是体验好坏的分水岭 | `[jadx]`/`[smali]` 见 §3.9 超时总表 |
| **4** | **KSC 持久化（`ksc_info`）+ 协商结果落盘**：配对成功的密钥按 `deviceId + alias` 存库，二次互传免协商 | 老用户碰一下就传完，不用再走确认。我们的实现**每传一次都是全新一次**，没有任何记忆 | `[jadx]` `l.java:867-884` `savePairDevice`；`[smali]` `pair/contract/b.smali` `saveKsc` |
| **5** | **iOS 走独立子系统**：iPhone 不能 HCE，于是**整条链路换承载**（BLE 广播唤醒 + ANCS/HFP 绑定），而不是硬塞进 NFC 协议 | 说明"同一功能、不同对端能力 → 换协议栈"是成熟做法。我们要支持 iPhone 的话必须同样处理 | 见 §8 |

> 第 6 条算半个：**`@DebugMetadata` 的教训**——如果我们自己混淆，别留这个后门（§0.1）。

---

## 2. 组件与包全景

**完整清单在 §9**（组件表 / 权限三段切分 / 私有依赖台账 / 第三方 SDK / 客户端 SDK 面）。
**README 版速览**（只列关键路径，细节与证据见 §9）：

| 层 | 参与包 / 组件 | 角色 |
|---|---|---|
| **本 APK** | `com.heytap.accessory`（Quick Connect，priv-app，平台签名） | 框架主体：发现 / 配对 / 确认卡 / 编排 |
| **数据源** | `com.coloros.gallery3d` / `com.oneplus.gallery`（**仅此二家**，硬编码白名单） | share agent，主动交数据 |
| **真传** | `com.coloros.oshare` / `com.oneplus.oshare`（`FILE_TRANSFER_SERVICE`） | 文件字节搬运（11 事务 / 4 回调） |
| **安全** | `com.oplus.ocs.*` | 调用方鉴权（uid + capability） |
| **厂商 HAL** | `com.vendor.nfc.VendorNfcAdapter` | `hasFeature("TAP_TO_SHARE_WITH_IOS")` + `enableNfcShareMode(bool)` |
| **平台隐藏 API** | `com.oplus.wrapper.*`（**249 处引用 / APK 内 0 定义**）、`android.view.OplusWindowManager`、`com.oplus.app.OplusWindowInfo`、`com.oplus.content.OplusFeatureConfigManager` | 编译期硬依赖，**第一大宗缺口** |
| **平台 AIDL 副本** | `com.heytap.addon.*`（37 文件 / 177 引用，**APK 内就地声明**） | 平台侧 Service 需自建 |
| **Native** | `libcpkit.so`（5.5 MB Rust，LAN 底座）+ 另 5 个 `.so`（共 7.4 MB） | **无源码，最大黑盒** |
| **触感** | `android.os.HapticPlayer` / `DynamicEffect`（RichTap） | **我们树中无此类，需自建或降级** |

> ⚠️ **两个易被忽略的点**：① `_Temp/afk/apk/` **未解出 `lib/`**，前序研究完全漏了 6 个 `.so`；② **`uses-library-not-required`** 含 `nfcvendorlib` 与 `vendor-oplus-hardware-cryptoeng-V1.0`。

---

## 3. NFC 交互全流程（本文核心）

### 3.0 先纠正一个前提：不是"六个契约"，是**两条链共十个契约**

立项时的描述是"六个契约：`NfcPairInitContract` / `KscNegotiationContract` / `ReadyConfirmContract` / `SenderConfirmContract` / `ReceiverConfirmContract` / `TransferConfirmContract`"。**这个列表把两条不同的链混在了一起**，而且漏了一半。真实结构是：

| 链 | 驱动者 | 契约（**按执行顺序**） | 何时跑 |
|---|---|---|---|
| **配对链**（6 步） | `NfcDataContractProxy`（`pair/l`） | ① `NfcPairInitContract` ② `NfcDataSyncContract` ③ `NfcSenderConfirmContract` ④ `NfcReceiverConfirmContract` ⑤ `KscNegotiationContract` ⑥ `NfcConnectAccessoryContract` | **碰一碰之后立刻**，负责"认出对方 / 协商 / 建连接" |
| **传输链**（4 步） | `NfcShareContractProxy`（`share/g`） | ① `ReadyConfirmContract` ② `SenderConfirmContract` ③ `ReceiverConfirmContract` ④ `TransferConfirmContract` | 连接建好之后，负责"确认传什么 / 传 / 报结果" |

**配对链的准确顺序是硬证据**，来自 `[smali] smali_classes5/vh/d.smali:64-125` `vh.d.b()`——一个静态方法，字面量顺序构造 6 个对象放进 `ArrayList`：

```smali
new-instance v0, Lcom/oplus/interconnect/nfc/pair/contract/h;   # NfcPairInitContract
new-instance v1, Lcom/oplus/interconnect/nfc/pair/contract/g;   # NfcDataSyncContract
new-instance v2, Lcom/oplus/interconnect/nfc/pair/contract/k;   # NfcSenderConfirmContract
new-instance v3, Lcom/oplus/interconnect/nfc/pair/contract/i;   # NfcReceiverConfirmContract
new-instance v4, Lcom/oplus/interconnect/nfc/pair/contract/b;   # KscNegotiationContract
new-instance v5, Lcom/oplus/interconnect/nfc/pair/contract/d;   # NfcConnectAccessoryContract
new-array v6, v6, [Lcom/oplus/interconnect/nfc/pair/contract/e; # ← 契约基类接口
aput-object v0, v6, v7   # index 0
aput-object v1, v6, v0   # index 1
aput-object v2, v6, v0   # index 2
aput-object v3, v6, v0   # index 3
aput-object v4, v6, v0   # index 4
aput-object v5, v6, v0   # index 5
```

**为什么"六个"会是六**——不是设计成六个，是这个方法正好 new 了六个。`[推断]`

**传输链的顺序也是硬证据**，来自 `[smali] smali_classes4/com/oplus/interconnect/nfc/share/g.smali:1479-1545`：一个 `packed-switch` 按判别值选**两条链之一**：

```smali
iget v0, v0, Lcom/oplus/pantaconnect/datatrans/security/a;->b:I
packed-switch v0, :pswitch_data_106      # .packed-switch 0x0 → :pswitch_7d

# default（b != 0）→ Watch 链
b=f → WatchReadyConfirmContract      g=i → WatchTransferConfirmContract
h   → WatchSenderConfirmContract     ...   （顺序：f, h, g, i）

# :pswitch_7d（b == 0）→ 常规链
b=ReadyConfirmContract   d=SenderConfirmContract
c=ReceiverConfirmContract e=TransferConfirmContract  （顺序：b, d, c, e）
```

判别值 `com.oplus.pantaconnect.datatrans.security.a.b`（int）。`datatrans/a.smali:1620` 用 `0xe` 构造它；**`0xe` = 14 = `DeviceType.APPLE_WATCH`**（见 §3.7 的 `major_type` 表）。默认构造器（无参）把它置 `0`。
→ `[推断]` **`b` 是对端/本端的设备大类码，0（或非 Watch）走常规链，Watch/Apple Watch 走 Watch 链**。`[未确定]`：`b` 的确切语义（是"本机"还是"对端"的设备类型）未证实。

#### 契约基类的形状 `[jadx]`

`pair/contract/e.java`（14 行，完整）：

```java
public interface e {                        // 配对链的契约基类
    Object a(kotlin.coroutines.c cVar);            // 取消 / 收尾（挂起等结果）
    long   b();                                    // ← 本契约的超时（ms）
    Object c(l lVar, kotlin.coroutines.c cVar);    // ← 主逻辑（l = PairOption）
    Object d(y7.b bVar, kotlin.coroutines.c cVar); // ← 收到对端报文时的处理
    String getName();
}
```
传输链的契约基类是同包的 `share/contract/a.java`（interface）。

#### 驱动循环（两条链共用同一模式）`[jadx]`

`NfcDataContractProxy$startContract$deferred$1.java:97-125` 反编译出的字节码等价于：

```kotlin
val job = scope.async {
    compatConnectionHandler.registerCompatPayloadReceiver { payload -> dispatch(payload) }  // 挂上收包回调
    for (contract in contracts) {                       // contracts = vh.d.b()，固定顺序
        val r = withTimeoutOrNull(contract.b()) {       // ← 每步独立超时
            contract.c(pairOption, this)
        }
        if (r == null) throw NfcPairTimeOutException()  // ← 超时统一抛这个，稍后按"当前契约"翻译
    }
}
```

**这是整个设计的精髓**：`for + withTimeoutOrNull` 顺序执行，超时异常只带"卡住了"这一个语义，**在外面再用"当前是哪个契约"翻译成具体错误**（见 §3.9）。收包回调 `d()` 是旁路——**契约的主逻辑在等，报文从另一个方向进来把它唤醒**（`r` 是 `kotlinx.coroutines.CompletableDeferred`，`a.smali` 里 `H(cVar)` = await，`b0()` = complete）。

### 3.1 配对链逐个契约

以下 `[jadx]` 证据均来自 `jadx --single-class` 输出（§0.2），文件路径为 `_Temp/afk/jadx_one/sources/com/oplus/interconnect/nfc/pair/contract/{b,d,g,h,i,k}.java`。

#### ① `NfcPairInitContract`（`h`）—— 版本与序号握手

| 项 | 值 |
|---|---|
| 超时 `b()` | **6000 ms** `[jadx] h.java:53` |
| 报文 | `MESSAGE_TYPE_NFC_PAIR_INIT` = **7** `[jadx] NfcPairMessageType$NfcInit.java` |
| 发起方 | **SEEKER**（`NfcPairRole.f5339b`） |

逻辑（`[jadx] h.java:73-140`）：

```
SEEKER:
  seq = new SecureRandom().nextInt()          // 随机 32 位序号
  send HELLO{ seq, version = localVersion }   // 走 SCP，标上 msgType=7
  等 PROVIDER 回复

PROVIDER:
  收到 HELLO 后：
    negotiatedVersion = min(remote.version, localVersion)   // ← 版本协商：取小
    回 { seq = remote.seq + 1, version = negotiatedVersion }
    把 negotiatedVersion 写回 PairOption.setVersion
  SEEKER 收到回复后校验 seq == sentSeq + 1

失败：序号不匹配 → 记 "init sequence wrong"，complete 一个 NfcPairException
```

**两种协议版本并存**：`d()` 里先比 `bVar.f24333a == (ProtocolMessageType$ScpVersion.V1.a() & …)`。V1 走 `fd/p2`（带 `ProtocolMessageType$ScpMessageType.HELLO`）的老 `HELLO` 报文；非 V1 走 `fd/t0`（带 `getSeq()` / `getVersion()`）的新报文。`[jadx] h.java:120-160`
→ **这是一个"新老设备互通"的兼容层**，说明协议至少迭代过一代。

**是否手机↔手机传图必需**：✅ **必需**。它是链首，负责版本协商——没有它双方无法知道该用哪套报文格式。

#### ② `NfcDataSyncContract`（`g`）—— 交换"这次要干什么"

| 项 | 值 |
|---|---|
| 超时 `b()` | **6000 ms** `[jadx] g.java:113` |
| 报文 | `MESSAGE_TYPE_NFC_DATA_SYNC_REQUEST` = **116**、`_RESPONSE` = **111**、`MESSAGE_TYPE_NFC_SHARED_DATA_SYNC` = **115** |

这是**语义协商**契约，交换的是"意图"而不是数据。`[jadx] g.java:183-230` 收到的 `RESPONSE` 里含：

```
nfcEvent           → o7.a（动作+包名，见 §3.6）
nfcEventSender     → 1=对端是 SENDER, 否则 SENDER/RECEIVER 判定
confirmResult      → 对端确认结果
accountEqualResult → 双方是否同一账号
accountHashData    → 账号哈希（字节）
deviceType         → NfcDeviceType {PHONE=0, PAD=1}
remoteDeviceName   → 对端设备名
```

**关键决策逻辑**（`[jadx] g.java:302-330` `getCompatExternalEventList` + `:429-454` `decideRemoteNfcEvent`）：

```kotlin
// 1) 兼容外部事件注入（受 Settings.Secure 开关控制）
if (Settings.Secure.getInt(...) == 1)
    list.add(o7.b("com.oplus.linker", InternalNfcAction.SHARE_FILE, ...))

// 2) 远端事件裁决
var remoteEvent = o.L(msg.getNfcEvent())
if (remoteEvent.action == InternalNfcAction.EMPTY_ACTION)   // h = EMPTY_ACTION
    remoteEvent = o7.a(InternalNfcAction.SHARE_FILE)        // ← 兜底成"传文件"

// 3) 账号不同 / 接力开关关闭 → 强制降级为传文件
if (!accountEqual || relaySwitchOff) {
    log("account different or relay switch is off")
    remoteEvent = o7.a(InternalNfcAction.SHARE_FILE)        // ← 同一个兜底
}
```

**是否手机↔手机传图必需**：✅ **必需**。它决定"这次是传图、投屏、还是接力"，并且携带 `accountEqualResult`（同账号判定）——后面的确认 UX 依赖它。

#### ③ `NfcSenderConfirmContract`（`k`）—— 发送方确认

| 项 | 值 |
|---|---|
| 超时 `b()` | **10000 ms**（`WorkRequest.MIN_BACKOFF_MILLIS`）`[jadx] k.java:129` |
| 报文 | `MESSAGE_TYPE_NFC_SENDER_CONFIRM` = **112** |
| 载荷 | `NfcTouchingResult`：`USER_REJECT`=0 / `USER_ACCEPT`=1 / `WAIT_USER`=2 / `CANCEL_ANIMATION`=3 / `WAIT_SENDER_CONFIRM`=4 |

`[jadx] k.java:132-155` 收到对端 `112` 报文时按 `confirmResult` 分支：

```
1 (USER_ACCEPT)      → 成功，complete(true)
2 (WAIT_USER)        → NfcUserCancelException      ← 注意：这里把 2 当取消
3/4/5                → 忽略（只记日志 "unknown confirm result"）
```

`[jadx] k.java:181-199` `dealCompatSenderConfirm`——**按设备组合自动确认**（重要）：

```
if (本地角色 != RECEIVER && 本端内部角色 == SENDER) {
    if (本机 deviceType == PHONE && 对端 deviceType == PHONE) {
        delay(SimpleExoPlayer.DEFAULT_DETACH_SURFACE_TIMEOUT_MS)
        send(WAIT_SENDER_CONFIRM)            // 手机→手机：要用户确认
    } else if (本机 == PHONE && 对端 == PAD) {
        delay(同)
        send(USER_ACCEPT)                    // 手机→平板：直接接受，不等用户
        complete(true)
    }
}
```

`[jadx] k.java:230-430` `dealSenderConfirm` 主逻辑：

```
if (内部角色 != SENDER) → 只需 await 对端的 sender-confirm 结果（自己不是发起方）
else {
    await shareStartDeferred                        // 等"分享已就绪"（相册交出数据）
    confirm = SenderBusinessConfirm(event, cardViewManager, deferred).confirm()
    when (confirm) {
        AGREE   → send(USER_ACCEPT); notify(SENDER_USER_SLIDE_AGREE); return true
        TIMEOUT → send(USER_REJECT); notify(SENDER_CONFIRM_SEND_TIMEOUT); throw NfcUserCancelException
        REJECT  → send(USER_REJECT); notify(SENDER_USER_SLIDE_REJECT);   throw NfcUserCancelException
    }
}
```

**是否手机↔手机传图必需**：✅ **必需**。这是发送方"上滑确认"那一下的协议化。

#### ④ `NfcReceiverConfirmContract`（`i`）—— 接收方确认

| 项 | 值 |
|---|---|
| 超时 `b()` | **23000 ms**（全链最长）`[jadx] i.java:49` |
| 报文 | `MESSAGE_TYPE_NFC_RECEIVER_CONFIRM` = **113**、`MESSAGE_TYPE_NFC_CANCEL_SEND` = **114** |
| 载荷 | `NfcReceiverConfirmResult`：`CONFIRM_RESULT_REJECT`=0 / `CONFIRM_RESULT_AGREE`=1 / `CONFIRM_RESULT_TIMEOUT`=2 |

`[jadx] i.java:382-430` 收到 `113` 时：

```
AGREE(1)   → ReceiverConfirmResult.RECEIVER_AGREE
REJECT(0)  → ReceiverConfirmResult.RECEIVER_REJECT
TIMEOUT(2) → ReceiverConfirmResult.RECEIVER_CARD_TIME_OUT
```
`[jadx] i.java:447-465` `e(UserConfirmResult)` 是本端结果→报文的编码（AGREE/REJECT/TIMEOUT）。

**23 秒超时的含义**：这是**等人在接收机上点"接受"**。对比机器步骤的 6 s，OPPO 给了近 4 倍。
**是否手机↔手机传图必需**：✅ **必需**。

#### ⑤ `KscNegotiationContract`（`b`）—— 密钥协商并落盘

| 项 | 值 |
|---|---|
| 超时 `b()` | **6000 ms** `[jadx] b.java:51` |
| 日志 tag | `"NFC-KSC"` `[jadx] b.java:40` |
| 报文 | `MESSAGE_KSC_REQUEST` = **12**、`MESSAGE_KSC_REPLY` = **13** |

`[jadx] b.java:145-206`：协商成功后调 `com.oplus.pantaconnect.connection.pair.persistence.h.e(deviceId, alias, ksc, false, cont)` 落盘，日志格式：

```
saveKsc: [deviceId=…, … , kscAlias=…, ksc=…]
失败 → NfcPairException("ksc save failed")
生成失败 → "ksc generate failed"
```

**是否手机↔手机传图必需**：⚠️ **技术上必需（顺序如此），但"是否用得上"取决于安全等级**。详见 §3.8 的安全章节：存在 `ACCESSORY_INSECURE_NFC_SHARE` 与 `key type not match, change to ukey2` 两条降级路径，说明**它可以在"不安全/不支持"的情况下退化**。`[推断]`

#### ⑥ `NfcConnectAccessoryContract`（`d`）—— 建物理连接（链尾）

| 项 | 值 |
|---|---|
| 超时 `b()` | **6000 ms** `[jadx] d.java:44` |
| 报文 | **无**（`d()` 直接 `return Unit`）`[jadx] d.java:196-199` |

**这一步不交换任何报文**，它去调 `CompatScp` 真正把 BT 连接建起来 `[jadx] d.java:85-180`：

```kotlin
// 前置：如果是 link share（InternalNfcAction.h == APP_LINK）→ 直接跳过
if (event.action == InternalNfcAction.APP_LINK) { log("link share, ignore connect accessory"); return true }

// SEEKER 侧：
statistics.n++                                     // 统计埋点
CompatScp.create().connectAccessory(
    SystemAgentAddress.NfcPair.INSTANCE,
    CompatConnectAccessoryParams(
        remoteDeviceIdHex,                         // ← 从 APDU 协商来的对端 ID
        address,                                   // ← 蓝牙地址
        kscBytes ?: ByteArray(0),                  // ← 上一步协商出的 KSC
        localDeviceId,
        ConnectionType.SPP_INSECURE,               // ★★ 明文 SPP！
        nfcEvent,                                  // 相关事件
        isPhoneConnection
    )
)
// PROVIDER 侧：CompatScp.create().acceptAccessory(同一个 SystemAgentAddress.NfcPair, …)

// 回调：
resultCode >= 0 → 成功：把 protocolDeviceId 写回 PairOption.setRemoteDeviceId，complete(true)
resultCode <  0 → NfcPairException("connect bt senseless BT accessory failed")
异常           → NfcPairException(exception.message)

日志措辞："connect bt senseless BT accessory now" / "accept bt senseless BT accessory now"
```

**这条日志措辞很关键**："**connect bt senseless BT accessory**" —— **"senseless" = 无感知**。
→ `[推断]` **碰一碰建立的 BT 连接对用户是无感的**（不弹系统配对框、不用确认），因为连接参数（地址 + KSC）已经通过 NFC 安全地传过去了。这正是 NFC 的价值所在。
**是否手机↔手机传图必需**：✅ **必需**，而且是**整条链的目的**——前面 5 步都是为了这一步能无感建连。

### 3.2 传输链逐个契约

以下 `[jadx]`/`[smali]` 证据：类名来自 `outer_resolved.txt`，超时来自各 `contract/*.smali` 的 `<init>()V` 里 `iput-wide … ->a:J` 前的常量。

| 顺序 | 类 | 超时 | 常量 | 来源 |
|---|---|---|---|---|
| ① | `ReadyConfirmContract` | **15000 ms** | `0x3a98` | `[smali]` `share/contract/b.smali:39`（⚠️ 该文件在 `smali_classes`，其余在 `smali_classes4`） |
| ② | `SenderConfirmContract` | **65000 ms** | `0xfde8` | `[smali]` `share/contract/d.smali:31` |
| ③ | `ReceiverConfirmContract` | **65000 ms** | `0xfde8` | `[smali]` `share/contract/c.smali:31` |
| ④ | `TransferConfirmContract` | **86 400 000 ms (24 h)** | `0x5265c00` | `[smali]` `share/contract/e.smali:37` |
| ①w | `WatchReadyConfirmContract` | 15000 ms | `0x3a98` | `[smali]` `share/contract/f.smali` |
| ②w | `WatchSenderConfirmContract` | 65000 ms | `0xfde8` | `[smali]` `share/contract/h.smali:31` |
| ③w | `WatchReceiverConfirmContract` | 65000 ms | `0xfde8` | `[smali]` `share/contract/g.smali:31` |
| ④w | `WatchTransferConfirmContract` | 86 400 000 ms | `0x5265c00` | `[smali]` `share/contract/i.smali:37` |

**结果枚举**（`[jadx]` `share/contract/*Result.java`），这些是**推给 UI/上层**的：

```
ReadyConfirmResult   { LOCAL_READY(0), PEER_READY(1), SENDER_USER_CANCEL(2) }
SenderConfirmResult  { SENDER_START(0), SENDER_USER_SLIDE_AGREE(1), SENDER_USER_SLIDE_REJECT(2),
                       SENDER_QUIT_AMIN_TASK(3), SENDER_CONFIRM_SEND_TIMEOUT(4),
                       SENDER_AND_RECEIVER_SAME(5), SENDER_THE_SAME_TIME(6),
                       SENDER_CONFIRM_SENDER_CANCEL_ANIMATION(7),
                       SENDER_CONFIRM_RECEIVER_CANCEL_CARD(8),
                       SENDER_USER_SLIDE_AGREE_RECEIVER_NOT_INSTALL_APP(9),
                       SENDER_USER_SLIDE_AGREE_RECEIVER_BROWSER_OPEN(10),
                       SENDER_USER_SLIDE_AGREE_RECEIVER_MARKET_OPEN(11) }
ReceiverConfirmResult{ RECEIVER_AGREE(0), RECEIVER_REJECT(1), RECEIVER_EXCHANGE(2),
                       RECEIVER_QUIT_AMIN(3), RECEIVER_CARD_TIME_OUT(4) }
TransferConfirmResult{ SENDER_TRANSFER_SUCCESS(0), SENDER_TRANSFER_FAILURE(1),
                       RECEIVER_TRANSFER_SUCCESS(2), RECEIVER_TRANSFER_FAILURE(3),
                       RECEIVER_TRANSFER_CANCEL(4),
                       RECEIVER_TRANSFER_PERMISSION_GRANT_FAIL(5),   ← 接收端授权失败
                       RECEIVER_RECEIVE_SUCCESS(6) }
NfcShareRole         { UNKNOWN(0), DELIVER(1), RECEIVER(2) }
```

> `SENDER_USER_SLIDE_AGREE_RECEIVER_{NOT_INSTALL_APP,BROWSER_OPEN,MARKET_OPEN}` 这**三个连体枚举**说明：接收端没装目标 App 时，OPPO 不是直接失败，而是**引导去浏览器/应用商店**——对应用户可见的"去下载"分支（见 §5）。
> `RECEIVER_TRANSFER_PERMISSION_GRANT_FAIL` 说明**接收端落地文件时会申请权限并可能失败**——这条对我们的实现有直接参考价值。

### 3.3 跨契约状态机（ASCII）

```
                       ┌──────────────────────────────────────────────┐
                       │  碰一碰（物理接触）                          │
                       └──────────────────┬───────────────────────────┘
                                          ▼
   ┌──────────────── 配对链（NfcDataContractProxy，串行 for + withTimeoutOrNull）───────────────┐
   │                                                                                            │
   │  ① NfcPairInitContract  (6s)       seq=随机32位, ver=min(local,remote)   msg 7             │
   │      │  失败 → NfcPairInitTimeoutException "nfc pair init time out"                        │
   │      ▼                                                                                    │
   │  ② NfcDataSyncContract  (6s)       nfcEvent / accountEqual / deviceType   msg 116/111/115  │
   │      │  失败 → NfcDataSyncTimeoutException "nfc pair data sync time out"                   │
   │      │  决策：账号不同 or 接力关 → 强制 SHARE_FILE                                          │
   │      ▼                                                                                    │
   │  ③ NfcSenderConfirmContract (10s)  手机↔手机要人确认；手机→Pad 自动接受    msg 112          │
   │      │  失败/拒绝 → NfcUserCancelException "sender timeout cancel"                         │
   │      ▼                                                                                    │
   │  ④ NfcReceiverConfirmContract (23s) ← 等接收方用户点"接受"                msg 113/114      │
   │      │  失败 → NfcUserRejectException "receiver timeout reject"                            │
   │      │  特例：本端角色==RECEIVER 时额外推 CONFIRM_RESULT_TIMEOUT 给 UI                      │
   │      ▼                                                                                    │
   │  ⑤ KscNegotiationContract (6s)     密钥协商 + saveKsc(deviceId, alias)    msg 12/13        │
   │      │  失败 → NfcKscNegotiationTimeoutException "nfc pair ksc negotiation time out"       │
   │      ▼                                                                                    │
   │  ⑥ NfcConnectAccessoryContract (6s) 无报文；CompatScp.connectAccessory(SPP_INSECURE)      │
   │      │  失败 → NfcConnectAccessoryTimeoutException "nfc pair connect accessory time out"   │
   │      ▼                                                                                    │
   │   【配对完成】finally: if (kscAlias 非空) CompatScp.savePairDevice(...)  ← 落盘                    │
   └────────────────────────────────────────────┬───────────────────────────────────────────────┘
                                                ▼
   ┌──────────────── 传输链（NfcShareContractProxy，同一驱动模式）──────────────────────────────┐
   │  ① ReadyConfirmContract    (15s)   LOCAL_READY / PEER_READY                                │
   │  ② SenderConfirmContract   (65s)   上滑确认 → SENDER_USER_SLIDE_AGREE                       │
   │  ③ ReceiverConfirmContract (65s)   接收方确认 → RECEIVER_AGREE                              │
   │  ④ TransferConfirmContract (24h)   ★ 真传：交给 com.coloros.oshare                         │
   │                                    SENDER_TRANSFER_SUCCESS / RECEIVER_RECEIVE_SUCCESS      │
   └────────────────────────────────────────────────────────────────────────────────────────────┘
```

**取消路径（横切）** `[jadx] l.java:920-935`：`NfcDataContractProxy.startContract` 里同时注册一个 `NfcPairCancellation`：

```kotlin
cancellation.onCancel = { reason: CancelReason ->
    if (isPairing.compareAndSet(true, false)) {
        contracts.current()?.onCancel(reason)     // 通知"当前卡住的那个契约"
        job.cancel()
    }
}
```
→ **取消是"打断当前契约"，而不是"另起一条状态"**。这是契约链模型的直接好处。

### 3.4 APDU 通道：HCE 发卡侧（`OplusHostApduService`）

`[jadx] com/oplus/pantaconnect/discovery/adapter/nfc/OplusHostApduService.java`（98 行，完整逻辑）。这是**收卡片**的那一侧。

```
HCE 声明（[资源/清单] res/xml/oplus_apdu_service，资源 id 0x7f14000b）：
  <host-apdu-service requireDeviceUnlock="true">
    <aid-group category="other">
      <aid-filter name="F000004F50504F2E434F4D"/>        ← AID = F0 00 00 + "OPPO.COM"
    </aid-group>
  </host-apdu-service>

processCommandApdu(apdu, extras)：
  ┌ 若 apdu == null 或 length < 4            → 回 "FF9000"（失败）
  ├ HEAD = apdu[0..4)，BODY = apdu[4..]
  │
  ├ 若 HEAD == "00A40400"  (SELECT by AID, CLA=00 INS=A4 P1=04 P2=00)
  │    ├ BODY == "0BF000004F50504F2E434F4D"  (0x0B = AID 长度, 后接 AID)
  │    │     → log "select aid success" ; 回 "9000"
  │    └ 否则                                 → log "select aid failed" ; 回 "FF9000"
  │
  ├ 若 HEAD != "00CA0101"                     → log "get invalid command" ; 回 "FF9000"
  │
  └ HEAD == "00CA0101"  (GET DATA, CLA=00 INS=CA P1=01 P2=01)   ← 数据通道
       BODY[0] = Lc ; 取 BODY[1 .. 1+Lc]
       ├ 越界                                  → "changed data invalid" ; 回 "FF9000"
       └ protocol.c.a().b(new of.a(0, NfcMode.SIMULATION_CARD, NfcType.TYPE_A, true, data))
            → resp
            resp 非空 → 回 resp + "9000"
            resp 空   → log "payload data is null" ; 回 "FF9000"

onDeactivated(reason) → protocol.c.a().g()
```

**读出来的事实** `[jadx]`：
1. **AID 是 `F000004F50504F2E434F4D`**（`F0 00 00` + ASCII `OPPO.COM`），category = `other`，`requireDeviceUnlock=true`——**锁屏不能用**。
2. **APDU 层就是"短请求 / 短响应"**：SELECT 一次拿到 `9000` 表示"我在且支持这套协议"；之后每次 `00CA0101`（GET DATA）就是一次**带 Lc 长度前缀的请求-应答**。**响应码 `9000` = 成功，`FF9000` = 失败**。
3. **报文内容由 `NfcMode.SIMULATION_CARD` 决定**——`NfcMode` 是发卡侧的模式枚举（`[jadx] disc/NfcMode.java` 存在）。这说明**同一套 APDU 命令在"卡模式"下走不同协议实现**。
4. **没有分片逻辑**（一轮一问一答，没有 `91xx` 的 GET RESPONSE 续传），所以**单次 APDU 载荷受 NFC 帧长限制**——这正是"APDU 只传元数据"的物理原因。`[推断]`
5. `onDeactivated` 里 `protocol.c.a().g()` —— **失活即重置协议状态**，说明配对会话与 NFC 场的存在强绑定。`[推断]`

### 3.5 APDU 通道：读卡侧（`NfcDiscoveryService`）

`[jadx] com/oplus/pantaconnect/discovery/adapter/nfc/NfcDiscoveryService.java`（51 行，全文）：

```java
public final int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && eq(intent.getAction(), "com.oplus.nfc.action.DIRECT_DISCOVERED")) {
        a4.b.H(a.a.y(j0.c), null, null, new NfcDiscoveryService$dispatchNfcDirectDiscovered$1(intent, this, null), 3);
    }
    stopSelf();                    // ← 立刻自杀，纯 trampoline
    return super.onStartCommand(intent, flags, startId);
}
```

**读出来的事实**：
- 它是**无状态 trampoline**：收到 N 个 `com.oplus.nfc.action.DIRECT_DISCOVERED` 广播，起一个协程处理，然后 `stopSelf()`。
- 真正的读卡逻辑在 `NfcDiscoveryService$dispatchNfcDirectDiscovered$1`（75 行）与 `adapter/nfc/protocol/*`。
- 该 Service 在清单里由 `com.oplus.permission.safe.CONNECTIVITY` 保护（见 §2）。`[资源/清单]`
- **`DIRECT_DISCOVERED` 不是 AOSP 的 action**——`[推断]` 它是 OPPO 在 NFC 服务（`com.android.nfc` / OPPO 定制 NfcService）里加的一个广播，**由系统 NFC 服务在"读到一个 tag/卡"时发出**。这意味着**我们的等价实现要么改 NFC 系统服务发这个广播，要么换一种发现机制**（见 §9 对照表）。

### 3.6 `NfcEventParcelize` 与事件模型：全部常量穷举

任务要求"枚举每一个 action/type/role 常量并指出各自在哪消费"。以下为全量 `[jadx]`/`[smali]`。

#### 角色与动作

```
NfcPairRole            { SEEKER=0,  PROVIDER=1 }        ← 谁发起（SEEKER 先发）
InternalNfcEventRole   { SENDER=0,  RECEIVER=1 }        ← 谁是数据发送方
NfcShareRole           { UNKNOWN=0, DELIVER=1, RECEIVER=2 }   ← 传输链角色
InternalNfcAction      { SCREEN_CAST=0, SHARE_FILE=1, SHARE_IMAGE=2,
                         SCREEN_RELAY=3, DATA_RELAY=4, EMPTY_ACTION=5,
                         GAME_INVITATION=6 }
```

> ⚠️ **纠正**：既有研究 `docs/OPPO_QUICKCONNECT_STUDY.md` §3.6 把 `InternalNfcAction` 的值抄成了 `SCREEN_CAST / SHARE_FILE / SHARE_IMAGE / SCREEN_RELAY / DATA_RELAY / EMPTY_ACTION / GAME_INVITATION`，**顺序正确**，但注意 `SHARE_FILE=1`、`SHARE_IMAGE=2` —— **"传文件"和"传图片"是两个不同的 action**。别把 `SHARE_FILE` 当成"传图"。

`NfcEventParcelize` 的四个字段 `(packageName, action, type, count)` `[jadx]`（SDK 面，见 §10）：
- `packageName` —— 目标/来源 App 包名
- `action` —— `InternalNfcAction`（或 SDK 侧映射）
- `type` —— MimeType 类别
- `count` —— **`records.size()`**，即"这次要传几张"

#### `NfcScenes`（SDK 面，框架内零引用 → 由客户端填）

```
NfcScenes { GALLERY_SELECT, GALLERY_SHARE, OSHARE_SHARE, SYSTEM_SHARE, UNKNOWN }
```
`[jadx]` `com/heytap/accessory/nfc/sdk/ipc/data/NfcScenes.java`。框架自身不引用 → **由相册/OShare/系统分享面板上报"我现在处于哪个场景"**。`[推断]`（与既有研究 §六#6 结论一致）

#### `NfcPairMessageType` 全表（**线上报文字节值，穷举**）

这是本文最有价值的一张表之一——**它是 SCP 通道上的报文类型码，全部 18 个**（数值已按 smali `<clinit>` 逐条核对，**不是** jadx 的折叠值）：

| 值 | 常量 | 所属枚举 | 契约 |
|---|---|---|---|
| **7** | `MESSAGE_TYPE_NFC_PAIR_INIT` | `NfcInit` | ① NfcPairInit |
| **12** | `MESSAGE_KSC_REQUEST` | `Ksc` | ⑤ KscNegotiation |
| **13** | `MESSAGE_KSC_REPLY` | `Ksc` | ⑤ KscNegotiation |
| **16** | `MESSAGE_CLIENT_INIT` | `UKey2` | ⑤（UKey2 路径）⚠️ jadx 把常量 16 折叠成了别的名字，此值来自 smali `<clinit>` `const/16 0x10` |
| **17** | `MESSAGE_SERVER_INIT` | `UKey2` | ⑤（UKey2 路径） |
| **18** | `MESSAGE_CLIENT_FINISH` | `UKey2` | ⑤（UKey2 路径） |
| **19** | `MESSAGE_SERVER_FINISH` | `UKey2` | ⑤（UKey2 路径） |
| **25** | `MESSAGE_TYPE_OOB_KEY_NEGOTIATE_READY` | `Oobkey` | ⑤（OOB key 路径） |
| **26** | `MESSAGE_TYPE_OOB_KEY_NEGOTIATE_PROGRESS` | `Oobkey` | ⑤（OOB key 路径） |
| **27** | `MESSAGE_TYPE_OOB_KEY_NEGOTIATE_COMPLETE` | `Oobkey` | ⑤（OOB key 路径） |
| **28** | `MESSAGE_TYPE_OOB_KEY_CONFIRM_REQUEST` | `Oobkey` | ⑤（OOB key 路径） |
| **29** | `MESSAGE_TYPE_OOB_KEY_CONFIRM_RESPONSE` | `Oobkey` | ⑤（OOB key 路径） |
| **111** | `MESSAGE_TYPE_NFC_DATA_SYNC_RESPONSE` | `NfcDataSync` | ② NfcDataSync |
| **112** | `MESSAGE_TYPE_NFC_SENDER_CONFIRM` | `SenderConfirm` | ③ NfcSenderConfirm |
| **113** | `MESSAGE_TYPE_NFC_RECEIVER_CONFIRM` | `ReceiverConfirm` | ④ NfcReceiverConfirm |
| **114** | `MESSAGE_TYPE_NFC_CANCEL_SEND` | `ReceiverConfirm` | ④（取消） |
| **115** | `MESSAGE_TYPE_NFC_SHARED_DATA_SYNC` | `NfcDataSync` | ②（共享数据同步） |
| **116** | `MESSAGE_TYPE_NFC_DATA_SYNC_REQUEST` | `NfcDataSync` | ② NfcDataSync |

`[jadx]` 证据：`pair/packet/NfcPairMessageType${Ksc,NfcDataSync,NfcInit,Oobkey,ReceiverConfirm,SenderConfirm,UKey2}.java`

> 观察：**111–116 恰好是 ASCII `o`(111) `p`(112) `q`(113) `r`(114) `s`(115) `t`(116)** —— "opqrst" 连续六连。`[推断]` 是**故意的"可打印区间"**，便于打日志/调试时肉眼可读。**这一条值得我们抄**：把报文类型放在可打印 ASCII 区，抓包和日志友好度提升明显。

#### 帧格式

`[smali] smali_classes/com/oplus/pantaconnect/datatrans/security/b.smali:7024` `o(Object...)[B`：
- 是一个 **varargs 字节拼接**函数（校验参数必须是 `Byte` / `byte[]` / `on.c`，否则抛 `IllegalArgumentException("concat with wrong args type.")`）
- 调用形式 `o(Byte.valueOf(TYPE), payloadBytes)` → 得到 **`[typeByte][payload]`**

→ **线格式 = `1 字节报文类型 + protobuf 载荷`**。`[smali]`
→ ⚠️ **注意这个类在 `datatrans/security` 包里，但 `o()` 本身不做任何加密**——只是拼接。**不要被包名误导**。

### 3.7 `major_type` / `DeviceType` 词汇表（素材机制的基础）

`[jadx] com/oplus/pantaconnect/discovery/model/DeviceType.java`：

| 名字 | `major` | 素材目录里出现过 |
|---|---|---|
| ALL | 0 | |
| SMART_WATCH | 1 | |
| TWS_HEADPHONES | 3 | |
| NECK_MOUNTED_HEADPHONES | 4 | |
| TV | **5** | ✅ |
| PC | **6** | ✅ |
| BRACELET | 7 | |
| PHONE | **8** | ✅ ×4 |
| PAD | **10** | ✅ ×7 |
| MACBOOK | 11 | |
| IMAC | 12 | |
| IPHONE | 13 | |
| APPLE_WATCH | **14** | ✅ |
| BADGE | 15 | |
| NAS | 51 | |
| UNSPECIFIED | -82 | |

**与 `assets/modelId_*/` 里的 `<major_type>` 完全吻合**（5/6/8/10/14）——**这就是素材按机型分类所用的字典** `[assets]`+`[jadx]`。详见 §7。
另一套：`NfcDeviceType { PHONE=0, PAD=1, UNRECOGNIZED=-1 }` `[jadx]`——只区分手机/平板，用于 data sync 与 confirm 分支。

### 3.8 安全：UKey2 / KSC / OOB Key / `ksc_info`

> ⚠️ **本节是速览；完整深挖在紧随 §4 之后的 `§3A`**（因为它是最后完成的一章，物理位置在 §4 之后）。
> `§3A` 含：UKey2 的完整算法清单、`ksc_info` 的精确 schema 与全生命周期、降级逻辑的精确触发条件、`ACCESSORY_NFC_SHARE` 的精确语义，以及**两处关键安全结论**（UK2 验证串被丢弃、账号 hash 只比 3 字节）。**以 `§3A` 为准。**

**已直接确认的事实** `[jadx]`/`[smali]`：

1. **三条并行的密钥协商路径**，全部挂在契约⑤ `KscNegotiationContract` 下：
   - `pair/contract/keygenerator/OobKeyGenerator`（`keygenerator/b`）—— `MESSAGE_TYPE_OOB_KEY_*`（25–29），5 条报文，是一套**完整的"带外密钥"握手**（READY → PROGRESS → COMPLETE → CONFIRM_REQUEST → CONFIRM_RESPONSE）
   - `pair/contract/keygenerator/UKey2KeyGenerator`（`keygenerator/d`）—— `MESSAGE_CLIENT_INIT / SERVER_INIT / CLIENT_FINISH / SERVER_FINISH`（**16/17/18/19**）
   - `pair/contract/ksc/KscGeneratorImpl`（`ksc/a`）—— `MESSAGE_KSC_REQUEST / REPLY`（12/13）
   → **三选一**，由"key type"选择，失败则降级（见下）。

2. **UKey2 是 Google 的协议**：`MESSAGE_SERVER_INIT / CLIENT_FINISH / SERVER_FINISH` 是 **Nearby Connections 的 UK2 握手**（server-init / client-finish / server-finish）标准三段。`[推断]` 这是 OPPO 参照/复用了 Google Nearby 的 UKey2 设计（ECDH + HKDF），而不是自创。**仔细的算法细节（曲线、hash、transcript）见 §3.8 附录。**

3. **`ksc_info` 的持久化时机（关键，已直接读到）** `[jadx] l.java:867-884` `NfcDataContractProxy.savePairDevice`：

```kotlin
val remoteDeviceId = pairOption.n.invoke()            // byte[]，来自契约⑥的 protocolDeviceId
val disc           = pairOption.e                     // s7.c，发现信息（含 alias = disc.i）
val connectionType = ConnectionType.SPP_INSECURE      // ★ 写死明文 SPP
val ksc            = pairOption.r.invoke() as ksc.b?  // 契约⑤协商出的 KSC
val kscAlias       = toHexString(ksc?.b)
val isXxx          = pairOption.m?.invoke() ?: false
val info = SharePairDeviceInfo(remoteDeviceId, connectionType, kscAlias, isXxx, disc.b.a())
if (info.kscAlias.isEmpty()) return                   // ★ 没有 KSC 就不落盘
CompatScp.Companion.create().savePairDevice(info)
```
   调用点在 `startContract` 的 **`finally` 块**（`[jadx] l.java:953`）→ **无论成功失败都会尝试落盘**（但 `kscAlias` 为空时跳过）。`[jadx]`

4. **`ksc_info` 表的 SQL**（既有研究已确认，本文沿用）`[smali]`：
   ```
   SELECT * FROM ksc_info WHERE deviceId = ? AND alias = ?      （含 ORDER BY date 变体）
   DELETE FROM ksc_info WHERE deviceId = ? AND alias = ?
   ```
   → `(deviceId, alias)` 是复合键，`alias` 来自发现信息 `disc.i`。
   → **`deviceId` 是"对端设备标识"，所以"配对过的手机会被记住"**——这就是二次互传能免协商的原因。

5. **降级逻辑**：字符串 `key type not match, change to ukey2, isTapShare=` `[smali]` 说明：
   - 双方先交换一个 **key type**（在哪交换：`[推断]` 契约② NfcDataSync 或契约⑤ 内）；
   - **对端 key type 与本端不匹配 → 强制切到 UKey2**；
   - `can not find ukey2 delegate.` 说明 **UKey2 有独立的 delegate 实现，可能未注入**（缺失则整个协商失败）。
   → `[推断]` **降级顺序：优先用"更强的/OOB"路径 → 不匹配则退到 UKey2 → UKey2 delegate 缺失则失败**。

6. **`ACCESSORY_INSECURE_NFC_SHARE`** `[smali]`：是一个可开关的**"不安全碰传"标志**（权限 or Settings）。
   → `[推断]` 打开后**跳过/放宽密钥校验**，用于兼容老设备或调试。这与 `ConnectionType.SPP_INSECURE`（明文 SPP）一起说明：**OPPO 的安全是分级的、且默认档位偏低**。**具体分级与判定条件见 §3.8 附录。**

7. **全链用的承载是 `ConnectionType.SPP_INSECURE`** `[jadx] d.java:110` + `l.java:871`
   → ⚠️ **最重要的一条安全结论：碰一碰的 BT 链路是明文 SPP。** 应用层密钥（KSC）用于**上层的授权/记录**，而不是把这条 SPP 链路本身加密。`[推断]`（依据：枚举名 + 无 TLS/Secure 变体的调用点）

### 3.9 超时总表（全部常量，穷举）

**配对链 6 步**：

| 步 | 契约 | 超时 | 超时后抛出的异常 / 消息 | 证据 |
|---|---|---|---|---|
| ① | NfcPairInit | **6 s** | `NfcPairInitTimeoutException("nfc pair init time out")` | `[jadx]` `h.java:53` / `l.java:966` |
| ② | NfcDataSync | **6 s** | `NfcDataSyncTimeoutException("nfc pair data sync time out")` | `[jadx]` `g.java:113` / `l.java:968` |
| ③ | NfcSenderConfirm | **10 s** | `NfcUserCancelException("sender timeout cancel")` | `[jadx]` `k.java:129` / `l.java:970` |
| ④ | NfcReceiverConfirm | **23 s** | `NfcUserRejectException("receiver timeout reject")` | `[jadx]` `i.java:49` / `l.java:972` |
| ⑤ | KscNegotiation | **6 s** | `NfcKscNegotiationTimeoutException("nfc pair ksc negotiation time out")` | `[jadx]` `b.java:51` / `l.java:974` |
| ⑥ | NfcConnectAccessory | **6 s** | `NfcConnectAccessoryTimeoutException("nfc pair connect accessory time out")` | `[jadx]` `d.java:44` / `l.java:976` |
| — | 整链兜底 | — | `NfcPairTimeOutException`（`withTimeoutOrNull` 返回 null 时） | `[jadx]` `NfcDataContractProxy$startContract$deferred$1.java:121` |
| — | 用户主动取消 | — | `CancellationException` → `NfcUserCancelException` | `[jadx]` `l.java:958` |

**传输链 4 步**：见 §3.2 表（15 s / 65 s / 65 s / 24 h）

**其它确证的超时常量** `[jadx]`：
- `ShareWaitAppShareDataTimeoutException`：等相册交数据 **10 s**（`0x2710`，`SenderIosSharePairing$start$2`）
- `NfcSenderConfirmContract.dealCompatSenderConfirm`：`delay(SimpleExoPlayer.DEFAULT_DETACH_SURFACE_TIMEOUT_MS)` —— 兼容路径的固定延时 `[未确定]` 具体值（是资源常量，未解析到数值）

→ **可直接借鉴的分级经验**：**机器步骤 6 s 档、人机交互 10–23 s 档、多用户确认 65 s 档、真实传输 24 h 档**。我们的实现目前是单一超时，这是最容易改、收益最明显的一处。

### 3.10 两条链的分工——"哪些是手机↔手机传图必需的"

> 任务要求的"最有用的输出"。结论分三层：**必需 / 条件必需 / 非必需（属于另一种机型或场景）**。

| 契约 | 手机↔手机传图 | 判定依据 |
|---|---|---|
| **配对链① NfcPairInit** | ✅ **必需** | 链首，版本协商；无它则双方报文格式无共识。`[jadx] h.java` |
| **配对链② NfcDataSync** | ✅ **必需** | 交换 `InternalNfcAction`（决定"传图"还是"投屏/接力"）+ `accountEqualResult`（决定确认 UX）。**注意：传图时 action 是 `SHARE_IMAGE`(2)，但也可能被降级为 `SHARE_FILE`(1)** `[jadx] g.java:203,454` |
| **配对链③ NfcSenderConfirm** | ✅ **必需** | 发送方上滑确认；且含"手机→Pad 自动接受"的分支（见下条） |
| **配对链④ NfcReceiverConfirm** | ✅ **必需** | 接收方接受/拒绝；**23 s 超时就是等这个** |
| **配对链⑤ KscNegotiation** | ⚠️ **条件必需** | 顺序上必跑；但存在 `key type not match, change to ukey2` 与 `ACCESSORY_INSECURE_NFC_SHARE` 两条降级路径，说明**可退化**。`[smali]` |
| **配对链⑥ NfcConnectAccessory** | ✅ **必需** | 全链目的：无感建 BT 连接（"bt senseless accessory"）。无它则没有承载 |
| **传输链① ReadyConfirm** | ✅ **必需** | 双方 ready 对齐 |
| **传输链② SenderConfirm** | ✅ **必需** | 65 s 档，上滑确认的传输侧对应 |
| **传输链③ ReceiverConfirm** | ✅ **必需** | 65 s 档 |
| **传输链④ TransferConfirm** | ✅ **必需** | 24 h 档，真正搬运文件 |
| **`Watch*` 四件套**（f/h/g/i） | ❌ **非必需** | 由 `datatrans/security/a.b` 判别值选中，`0xe`(=APPLE_WATCH) 走它。**属于手表/Apple Watch 形态** `[smali] share/g.smali:1479-1545` |
| **`Beacon*` 家族** | ❌ **非必需** | 独立的**信标（BLE）配对**子系统，不属 NFC 链；见 §3.11 |
| **`Ios*` 家族** | ❌ **非必需**（对 Android↔Android） | iPhone 专用；见 §8 |

**按机型分支的"自动接受"规则**（值得抄的体验细节）`[jadx] k.java:181-199`：

```
本机 PHONE × 对端 PHONE → 需要发送方人工确认（WAIT_SENDER_CONFIRM）
本机 PHONE × 对端 PAD   → 直接 USER_ACCEPT，不等用户
```
→ `[推断]` 逻辑是"**对方是平板（大概率是用户自己的平板）就不问**"——用设备形态当作信任代理。**这是很实用的一条**。

**⚠️ 诚实说明**：`NfcConnectAccessoryContract` 与 `KscNegotiationContract` 在配对链里的**位置（第 5、6 位）来自字面量顺序**，我没有找到"为什么 KSC 在 confirm 之后而不是之前"的设计注释；只能从行为推断：**先让用户确认要不要传，再花代价协商密钥**。`[推断]`

---

### 3.11 Beacon 家族（独立的 BLE 信标配对，非 NFC 链）

`com.heytap.accessory.beacon.*` 是**另一套配对子系统**，与 NFC 契约链并列，共用同一套契约驱动模式。

| 类 | 混淆名 | 角色 |
|---|---|---|
| `BeaconPairInitContract` | `beacon/pair/contract/d` | 链首 |
| `BeaconNegotiationContract` | `beacon/pair/contract/c` | 协商 |
| `BeaconConnectAccessoryContract` | `beacon/pair/contract/a` | 建连（`connectInsecureL2cap` / `connectBtInsecure`） |
| `BeaconContractProxy` | `beacon/pair/a` | 驱动器（与 `NfcDataContractProxy` 同构） |
| `BeaconPairManager` | `beacon/pair/g` | 管理器 |
| `OobKeyForBeaconGenerator` | `beacon/pair/contract/keygenerator/b` | **Beacon 专用的 OOB key**（与 NFC 的 `OobKeyGenerator` 并列） |
| `BlrAdvertiser` / `BlrDiscover` | `beacon/discovery/b` / `d` | BLE 广播 / 扫描（`ServiceName` 枚举） |

`[smali]`/`[jadx]` 证据：`_Temp/afk/notes/outer_resolved.txt` 中的映射行；`jadx_out/sources/com/heytap/accessory/beacon/**`

**读出来的事实**：
- 有**独立的 `ksc/` 与 `keygenerator/` 包**，说明 Beacon 也走 `KSC` 体系——**密钥基础设施是两条链共享的**。
- `BeaconConnectAccessoryContract` 的方法名是 `connectInsecureL2cap` / `connectBtInsecure` → **L2CAP / BT 明文连接**（与 NFC 链的 `SPP_INSECURE` 同一思路）。`[jadx]`
- `BlrAdvertiser` 有 `startBlrQueryServiceAdvertise` / `startBlrReplyServiceAdvertise` → **Query/Reply 两阶段广播**（先"我在找"，对方回"我在这"）。

**是否手机↔手机传图必需**：❌ **非必需**。这是**不使用 NFC 的替代发现方式**（比如设备没有 NFC，或隔空靠近）。`[推断]`

---

## 4. 照片 / 数据交互

> 本节有**三处对既有研究的纠正**，都已在 smali 层面复核。

### 4.1 纠正一：`SharedData.card` **不是** `CardOptions`

既有研究（`docs/OPPO_QUICKCONNECT_STUDY.md` §3.4）把 `card` 记成 `CardOptions`。**实际类型是 `w7/a`**：

```
[smali] smali_classes/com/oplus/interconnect/share/sdk/data/SharedData.smali
  .field private card:Lw7/a;

[smali] smali_classes5/w7/a.smali  — toString() 自报真名：
  "CompatSharedCard(appIcon=", ", thumbnail=", ", title=", ", description=",
   ", positive=", ", negative=", ", extraData="
```

→ **`card = CompatSharedCard{ Bitmap appIcon, Bitmap thumbnail, String title, String description, String positive, String negative, Map extraData }`** `[smali]`
→ **卡片上的缩略图是"源 App 直接给的 Bitmap"**，不是框架去解码相册。`CardOptions` 走**另一条路**（`InternalSharedDataParams.card`，protobuf `g7/b0`）。**两者并存，既有研究把它们混为一谈。**
→ **对我们的启发**：确认卡的外观**由数据源 App 决定**（传 Bitmap + 文案），框架只负责排版。这样相册可以给"用户实际选中的那张"而不是"第一张"。**值得抄。**

### 4.2 `SharedData` 字段表（完整）

`[smali] smali_classes/com/oplus/interconnect/share/sdk/data/SharedData.smali`

| 字段 | 类型 | 含义 | 在 APDU 上？ | 在 SCP 上？ |
|---|---|---|---|---|
| `card` | `CompatSharedCard`（`w7/a`） | 卡片外观（Bitmap + 文案），**源 App 提供** | ❌ | ✅（`MESSAGE_TYPE_NFC_SHARED_DATA_SYNC` 路径） |
| `name` | String | 数据名 | ❌ | ✅ |
| `shareName` | String | 分享名（展示用） | ❌ | ✅ |
| `records` | `List<SharedRecord>` | **实际内容列表**（每项一张照片） | 仅 **count** 上前 | ✅ |
| `packageNames` | `List<String>` | **接收端候选包名白名单**（用于 `resolveActivity` 挑接收 Activity） | ❌ | ✅ |
| `isReShare` | boolean | 是否"重分享/续传" | ❌ | ✅ |
| `extrasData` | `Map<String,String>` | 扩展键值 | ❌ | ✅ |

> `isReShare` 会被 `RealShareServer.g()` 用 RPC 参数**覆写**（`[smali] processor/e.smali:699-703`）——即**由调用方（相册）说了算**。
> 两份 protobuf 影子：`g7/f0`（startShare RPC 用，**无** `isReShare`）、`fd/q`（compat 用，`isReShare`=field 4、`extrasData`=field 5）。
> **`packageNames` 是接收端的"候选白名单"**——它解释了 §4.5 的 `resolveActivity` 为什么能精确命中接收方 App。

### 4.3 `SharedRecord` 字段表

`[smali] smali_classes/com/oplus/interconnect/share/sdk/data/SharedRecord.smali`

| 字段 | 类型 | 含义 | 备注 |
|---|---|---|---|
| `type` | `MimeType` | 内容类型 | **唯一跨 APDU 的字段**（映射成 `InternalNfcAction`） |
| `content` | String | **当 `type==APP_LINK` 时是 URL** | `[smali] nfc/share/d.smali:2247` |
| `uri` | String | 内容的 Uri | 由**源 App**给出（自带读权限授予） |
| `extraData` | `byte[]` | 不透明扩展 | 框架**从不解释**，唯一读取点 `nfc/pair/g.smali:476`（拷进 `fd/t.extraData`）。OShare 的平行模型把 `title/description/thumbnailUri` 放在**独立字段**，所以这里是个通用口袋。**内容无法从框架侧确认** `[未确定]` |

`MimeType` 全枚举（12 值，含 wire 字符串）见既有研究 §3.4。

### 4.4 纠正二：缩略图管线是 `ShareResourceManagerImpl`，不是 `MediaStore.getBitmap`

既有研究说"只用 `MediaStore.Images.Media.getBitmap` 解码缩略图"。**实际主管线是 `nfc/resource/f.smali`（`ShareResourceManagerImpl`）：**

```
getThumbnail()  [smali] nfc/resource/f.smali:182
  ├ 若 cardOptions.thumbnail.thumbnailList[0] 存在 → 直接用它（2000 ms 超时）
  └ 否则解码 records[0].uri —— ★ 只解码第一张
       目标边长 = (isXxx ? 860 : 280) * density      [smali] f.smali:423-431
       图片 → ImageDecoder.decodeBitmap + setTargetSize 监听器   [smali] nfc/resource/c.smali
       视频 → MediaMetadataRetriever.getFrameAtTime(-1, OPTION_CLOSEST_SYNC)
       其它 → loadThumbnail(Size(clamp(t, 1024, 4096)))

★ 它会去查 Uri 的 _data 列：
   ContentResolver.query(uri, ["_data"], …)            [smali] nfc/resource/d.smali:688
   → 用来推断扩展名 / MIME
```

→ **纠正**：既有研究"它**从不**查 MediaStore"的结论**不完整**——它确实会用 `_data` 查询来推断类型。但**这不是"枚举媒体库"**，是"**对已知 Uri 做一次元数据查询**"。两者性质不同：前者要 `READ_MEDIA_*` 权限并遍历，后者是对已授权 Uri 的单条查询。**结论方向不变（它不枚举相册），但表述要精确。**
→ **默认只出一张缩略图**；只有源 App 主动给了多张时才排成网格。
→ **860 vs 280 边长**取决于 `isXxx`——`[推断]` 是"高清/低清"或"大卡/小卡"两种展示形态。

### 4.5 count 协商（确认 + 精确化）

既有研究说 count = `records.size()`。**确认，并补充三点：**

1. **count 是在"App 交完数据之后"才算的**，不是在 APDU 阶段：
   `[smali] NfcPair$transferRealNfcEvent$1.smali:190-290` 先 await `o.L`（等相册交数据），再读 `o.M.b.records`；
   - 若**全部** record 都是 `IMAGE` → `action = SHARE_IMAGE`（copy mask=1）
   - 否则 action 保持白名单给的 `SHARE_FILE` / `GAME_INVITATION`
   - **两个分支都设 `count = records.size()`**（**不过滤**）
2. **承载**：count 放在 protobuf **`fd/m0.fileCount`（field 3）**里，随 **`0x74 DATA_SYNC_REQUEST`** 走 SCP 隧道 → **确实在文件字节之前到达对端**。
3. **接收端从不校验 count**（`grep getFileCount` 全树只命中 `negotiation/o.smali:638`）。它落到 `uv/card/e.f` **纯粹用于 UI 文案**。
   → **数量不一致是可能的，而且检测不到**（share 层协议根本不带 count）。**这是 OPPO 的一个真实缺陷，不要抄。**

**没有最大数量限制在框架里**——唯一的"太多"是**客户端侧的**：`HealthResponse.CODE_SELECT_PIC_COUNT_OVER = 6`（"Too many picture selected!"），**由相册产生**。`[jadx]`

### 4.6 源 App 必须做什么（编号清单，可直接当接口规格）

RPC 通道：`IInterConnIpcInterface.request(module, method, bytes, bundle)` `[smali]`

1. 持有 `com.oplus.interconnect.permission.SERVICE`；绑定 `InterconnectService`；**通过 OCS 鉴权**（uid + `OAF_CLIENT` capability）
2. `isShareAvailable(InternalShareRegisterParams)` → 返回 0 表示 OK；`0x67` 表示"非系统用户 / 不支持碰传"
3. **`registerShareAgent(params, callback)`** —— ⚠️ **这个 callback 是长连接通道**，框架包成 `ResponseWrapper` 存进 `NfcShareAgent.callback`
4. 框架**主动推**给 App：`callback("registerEvent", byte[0], {nfc_contract_event=CONTRACT_START, nfc_event_role=SENDER|RECEIVER, data_parcel=NfcEventCallbackParcelize})`
5. **收到信号后**才 `startShare(InternalSharedDataParams{client, data, card, isReShare})`
   **硬顺序约束**：`TapShareManager.l()` 在 `isPairing != true` 或"没有在跑的 pair task"时**直接抛 `ShareInvalidTaskException`**
6. `registerShareOperationCallback(callback)` —— 第二条长连接，**要本次任务的结果**（仅当有活跃 task 时才挂得上）
7. 框架回报 `"sender agree"` / `"sender cancel"` / `"sender time out"`
8. 可选 `resumeShare(InternalResumeShareParams)` —— ⚠️ **当 `isReShare==true` 时双方都会短路忽略它**（日志 `resumeShare, isReShare case, ignore`）
9. 可选 `rejectShare(InternalRejectShareParams{client, reason})`
10. `unregisterShareAgent` / `unregisterAgents`

**对我们的含义**：我们要实现的是"**我们自己的相册 → 我们自己的框架**"，这条 RPC 可以简化成**进程内接口或本地 AIDL**，但**第 3/4/5 步的"长连接通道 + 回调后才交数据 + 硬顺序约束"这套形状值得原样保留**——它天然解决了"碰一碰发生时相册可能不在前台"的问题。

### 4.7 接收端落地契约（完整）

`[smali] nfc/share/d.smali:1557` `NfcShare.onResult()` 内联的 `LaunchAppUtilsKt.startSeamlessView`（块 2442-2622）：

```java
Intent i = new Intent();
i.setPackage(targetPkg);                                    // ★ 来自发送方的 SharedData.packageNames
i.addFlags(CLEAR_TOP | NEW_TASK);
i.setAction("com.oplus.interconnect.action.SHARED_DATA");
// ★ 门控：必须 resolveActivity 成功
if (pm.resolveActivity(i, 0x10040 /* MATCH_DEFAULT_ONLY 等 */) != null) {
    i.putExtra("share_data",      /* InternalSharedData 的 protobuf 字节 */);
    i.putExtra("share_data_json", /* 同一份数据的 JSON 字节 */);
    Bundle options = new Bundle();
    options.putBoolean("view_seamless_open", true);          // ★ 放在 options 而非 extras
    startActivity(i, options);
}
```

读出来的事实：
- **接收方 Activity 必须声明 `category.DEFAULT`**（`MATCH_DEFAULT_ONLY`），否则 `resolveActivity` 不命中。
- 数据**双份**：`share_data`（protobuf）+ `share_data_json`。接收方可以只解析 JSON——**降低接入门槛**。`[推断]`
- **解析失败时既不抛异常也不跳应用商店**——**换下一个候选包名继续试**；全部失败则打开文件管理器（`oplus.intent.action.filemanager.SOURCES_DOC`）。
- 序列化的是**接收到的**记录（传输后的 Uri）+ 对端名字/包名/`extrasData`。

**关键：框架自己不落盘。** 字节由 `com.coloros.oshare` 写入；框架通过 `TransferCallback.onFileHandler` → `getParcelableArrayList("sharedRecords")` 拿到 `ArrayList<com.coloros.oshare.model.SharedRecord>`，然后**只转发 uri 字符串**。`[smali]`
→ interconnect/oshare 命名空间里 `FileProvider` / `MediaStore` / `getExternalFilesDir` / `DIRECTORY_DOWNLOADS` **全部 0 命中**。
→ 附注：`transfer/d.smali:130` —— **status 1000 但记录为空会被降级成 1001**（空结果不算成功）。`[smali]`

### 4.8 `MESSAGE_TYPE_NFC_SHARED_DATA_SYNC`（115）—— 谜底揭晓

**它不走 NFC，也不走主 SCP 链，而是走一条独立的裸 BT-SPP socket。** `[smali]`

```
[smali] pair/g.smali:92    ConnectionType.SPP
[smali] pair/g.smali:108   CompatScp.acceptRawConnection      （服务端）
[smali] pair/o.smali:1097  （发起端）

帧格式：[0x73][protobuf fd/c1]
         fd/c1{ card: fd/m, data: fd/q }
```

**触发条件**：**仅当 `business.action == GAME_INVITATION` 时创建** `[smali] pair/b.smali:288-312`、`pair/z.smali:275-300`
→ 它**提前**把**整份 `SharedData`**（records + packageNames + isReShare + extras + card 缩略图）经 SPP 同步过去，为后面的主链路做准备。

> ⚠️ **一个真实的实现缺陷（别抄）**：`NfcDataSyncContract` 用 `values()` 声明了自己处理全部三种 `NfcDataSync` 消息（0x73/0x74/0x6F），**但代码只解析 0x74 和 0x6F，静默丢弃 0x73**。`[smali]`
> → **回看 §3.4 的报文表**：我原先按"契约② 处理 115"来写是不准确的。**115 的实际消费者在 `pair/g.smali` 那条裸 SPP 上，不在契约②里。**

### 4.9 `NfcScenes` 之谜 —— 已解开，而且它**什么都不做**

既有多轮研究都标"未确定"。**答案（`[smali]`）：**

```
唯一读取点：nfc/processor/c.smali  (NfcCallMethodProcessor), registerEvent 分支, :513-557
  bundle.getInt("key_scenes", -1)  →  0/1/2/3

wire 取值：UNKNOWN=-1, SYSTEM_SHARE=0, GALLERY_SELECT=1, GALLERY_SHARE=2, OSHARE_SHARE=3

★ 解析出来的值唯一的用途是日志：
  "register nfc event success: …, scenes:<scene>"        [smali] :852-865
★ 没有任何分支依赖它。
```

→ **它是一个"只写日志"的遥测字段。** 既不改变行为，也不参与路由。
→ **对我们的启发（反面）**：不必为"场景感知"设计专门的枚举——**OPPO 自己也没用起来**。如果我们将来需要"相册里选 vs 相册里分享"的不同行为，**应该真的用起来**，而不是记日志。

---

---

## 3A. 安全深挖（UKey2 / KSC / ksc_info / 降级逻辑）

> **这是 §3.8 的完整版**（物理位置在 §4 之后，因为它是最后完成的一章）。
> **§3.8 只是速览——以本节为准。** 本节是全文证据密度最高的部分之一，所有结论都在 smali 层复核过。

### 3A.1 先分清三个层面的 "key"

逆向时最容易混的一点：APK 里同时存在**三个** key 概念，分属不同层。`[jadx]`/`[smali]`

| # | 概念 | 载体 | 取值 | 作用 |
|---|---|---|---|---|
| **A** | `keyBasedType`（**配对层**用哪个密钥生成器） | `x.f9350w` / `q.f9317v`（裸 int 位掩码） | 1/2/4/8/16/32/64 | 选 `PresetKeyGenerator`/`UKey2Generator`/`AccountKeyGenerator`/`OobKeyGenerator`/`CryptographyGenerator` |
| **B** | `ScpKeyType`（**传输层**密钥类型） | `ScpSyncData.key_type`（protobuf field 2） | `EMPTY(0) UKEY2(1) OOBKEY(2) BOTH(3)` | 决定 SCP 会话密钥来源 |
| **C** | **KSC**（长期共享凭证） | `ksc_info` SQLite 表 | 16 字节随机 + 6 字节 alias | 后续连接的 HMAC 身份证明密钥 |

**A 是配对层的降级，B 是传输层的降级，两者独立触发，C 是 A/B 协商成功后的产物。**

### 3A.2 UKey2 = Google 原版 `Ukey2Handshake`（不是 OPPO 自研）

**结论：用的是 Google 原版库（Nearby Connections 的 UK2 实现），被 OPPO 重新编译过。** `[smali]`/`[jadx]`

- 包名 `com.google.security.cryptauth.lib.securegcm` —— **Google CryptAuth 的 securegcm**
- **外层类 `Ukey2Handshake` 被改名成 `securegcm/a`**：全 5 个 smali 树里对 `Lcom/google/security/cryptauth/lib/securegcm/Ukey2Handshake;` 的引用数 = **0**（dex 里根本不存在这个名字），而 `securegcm/a` 的字段类型全是 `Ukey2Handshake$HandshakeCipher/HandshakeRole/InternalState`。`[smali] smali_classes/com/google/security/cryptauth/lib/securegcm/a.smali:1,9,11,15`
- 状态机（`[jadx] Ukey2Handshake$InternalState.java`）：`CLIENT_START, CLIENT_WAITING_FOR_SERVER_INIT, CLIENT_AFTER_SERVER_INIT, SERVER_START, SERVER_AFTER_CLIENT_INIT, SERVER_WAITING_FOR_CLIENT_FINISHED, HANDSHAKE_VERIFICATION_NEEDED, HANDSHAKE_VERIFICATION_IN_PROGRESS, HANDSHAKE_FINISHED, HANDSHAKE_ALREADY_USED, HANDSHAKE_ERROR` —— **与上游 UK2 完全一致**

#### 算法清单（精确字符串，全部 `[smali]`）

| 字符串 | 位置 | 用途 |
|---|---|---|
| `"SHA-512"` | `securegcm/a.smali:419` / `:794` | CLIENT_FINISH 承诺的计算 / 校验（`"Commitment does not match"`） |
| `"HmacSHA256"` | `i2/a.smali:68,70,93` | HKDF-Extract / Expand |
| `"ECDH"` / `"DH"` | `h2/b.smali:77` / `:72` | EC 私钥走 ECDH |
| `"SHA-256"` | `h2/b.smali:96` | ECDH 共享密文再 SHA-256 |
| `"EC"` / `"prime256v1"` / `"secp256r1"` | `i2/b.smali:394,405,419` | `KeyPairGenerator`，`prime256v1` 失败回退 `secp256r1` |
| `"UKEY2 v1 auth"` / `"UKEY2 v1 next"` | `a.smali:531` / `:1838` | HKDF salt（验证串 / 主密钥） |
| `"client"` / `"server"` | `a.smali:1863` / `:1879` | HKDF info（方向密钥） |
| `"AES_256_CBC-HMAC_SHA256"` | `a.smali:1129`、`h2/i.smali:49` | `next_protocol` 常量（服务端强校验：`"Incorrect next protocol"`） |

**握手三条消息**：
1. **CLIENT_INIT** — `Ukey2ClientInit{ version=1, random=32B, next_protocol="AES_256_CBC-HMAC_SHA256", cipher_commitments=[{handshake_cipher=P256_SHA512, commitment=SHA-512(序列化后的 CLIENT_FINISH 整包)}] }`
2. **SERVER_INIT** — `Ukey2ServerInit{ version=1, random=32B, handshake_cipher=P256_SHA512, public_key=GenericPublicKey(EcP256PublicKey) }`
3. **CLIENT_FINISH** — `Ukey2ClientFinished{ public_key, iv_spec=16B }`；服务端校验 `SHA-512(收到整包) == commitment`

**HKDF 是手写的 HMAC 组合**（不是 JCE 的 `"HKDF"`）`[jadx] i2/a.java:29-57`：
```
a(key, salt, info):
  prk = HMAC-SHA256(key=salt, msg=key.getEncoded())    // Extract
  okm = HMAC-SHA256(key=prk,  msg=info || 0x01)        // Expand（单块，固定 32 字节）
```
**transcript = client_init 整包字节 ‖ server_init 整包字节**（外层 `Ukey2Message` 序列化字节，非内层 proto）`[smali] a.smali:518-522`

**派生链** `[smali] a.smali:m()`：
```
h(ECDH 共享) = SHA-256( KeyAgreement("ECDH").generateSecret() ) → SecretKeySpec(..., "AES")
authString(2B) = HKDF(ikm=h, salt="UKEY2 v1 auth", info=transcript)[0..2]   ← ★ 用户比对串
masterKey(32B) = HKDF(ikm=h, salt="UKEY2 v1 next", info=transcript)
clientKey      = HKDF(ikm=masterKey, salt=<32B 硬编码常量>, info="client")
```

**⚠️ 一处与上游的偏差**：`P256_SHA512 = 100`，不是上游的 `1`。`[smali]` —— `[推断]` 是 OPPO 为"防与 Google 原生互通"而做的域隔离。

### 3A.3 ⭐ 两处关键的安全结论（对我们最有价值）

#### 结论一：UK2 的用户验证串被**显式丢弃**

Google 的 UK2 设计里，`authString`（2 字节）是给用户**肉眼比对**用的（防中间人）。OPPO 的包装类 `zk/a` 的构造器**收 6 个 `byte[]` 参数，只存 5 个**——`p3`（即验证串）被丢掉。`[smali] zk/a.smali:19-32`（已逐字节复核）

→ **首配对没有任何身份验证**，纯 TOFU。全树 grep `auth_string` / `authString` / `verification` / `verifyCode` / `pinCode` 在 `com/oplus/interconnect/` 与 `com/oplus/pantaconnect/pair/` 下 **0 命中**。
→ 用户能看到的对端信息**只有 `remoteDeviceName`（对端自报，可伪造）**。
→ **对我们的启发**：**这一条应该"反着抄"**——UK2 的 2 字节比对串成本极低（屏幕上显示 2 个数字/词），却能挡掉中间人。**OPPO 省掉了它，我们不该省。**

#### 结论二：同账号 → **直接自动同意，完全不弹 UI**

`[jadx] contract/i.java:157-186`（NfcReceiverConfirmContract）：

```java
if (accountEqual == TRUE) {
    log("same account");
    if (action != APP_LINK) {
        onReceiverConfirmResult(RECEIVER_AGREE);   // ★ 直接同意
        return true;                               //   跳过用户确认
    }
} else {
    log("different account");                      // 不同账号 → 走 UI
}
```

**这是"账号即信任根"的设计**：同账号手机触碰即传，不同账号才要用户点确认。

**账号比对算法** `[jadx] contract/g.java:401-405` + `m7/a.java:19-27`：

```java
byte[] remote = msg.getAccountHashData();
byte[] local  = m7.a.b();
boolean equal = Arrays.equals(remote, local);

// m7/a.b():
byte[] h = CompatScp.create().getAccountHash();
return (h != null && h.length >= 3) ? k.p0(0, 3, h) : null;   // ★ 只取前 3 字节！
```

→ **⚠️ 账号比对只比对 hash 的前 3 字节 = 24 bit**，碰撞概率 **1/16,777,216**。
→ 这是**弱身份绑定**：作为"自动放行开关"勉强够用（它只影响 UX，不产生密钥），但**它是一条真实的攻击面**：构造一个 hash 前 3 字节相同的账号，就能让对端**静默接受**传输而不弹确认。
→ **对我们的启发**：如果我们做"同账号免确认"，**至少要比对完整 hash**（32 字节的成本和 3 字节一样）。

### 3A.4 KSC：从生成到落盘

**缩写** `[推断]`：语义上最贴近 **"Key Sharing Credential"（共享密钥凭证）**。
⚠️ **APK 内没有任何字符串/注释/类名展开这个缩写** —— 全树 grep `KeySharing`/`KeyShare`/`SharedCredential`/`key_sharing` **0 命中**。代码内只以 `ksc` 出现，日志 tag 是 `"kscTrack"` / `"KSC"`。`[未确定]`

**生成** `[jadx] pair/contract/ksc/a.java:73-108`（`KscGeneratorImpl`）：
```java
SecureRandom sr = new SecureRandom();
byte[] ksc   = new byte[16]; sr.nextBytes(ksc);      // ★ KSC 本体：16 字节纯随机
byte[] alias = new byte[6];  sr.nextBytes(alias);    // ★ alias：6 字节纯随机
x proto = x.newBuilder().a(ByteString.copyFrom(ksc)).b(ByteString.copyFrom(alias)).build();
return z7.a.c(sharedSecret, proto.toByteArray());    // 用协商出的 sharedSecret 加密后发出
```
**谁来生成**：**发起方（client）生成**，沿已加密通道下发（`MESSAGE_KSC_REQUEST`=12），服务端解密 → `checkKscDuplicate` → `saveKsc` → 回 `MESSAGE_KSC_REPLY`=13 `[resultCode, 13B nonce]`。

**`ksc_info` 精确 schema** `[smali] smali_classes5/mi/m.smali:28,32`（Room 生成的建表 SQL）：

```sql
CREATE TABLE IF NOT EXISTS `ksc_info` (
  `deviceId` TEXT NOT NULL,
  `alias`    TEXT NOT NULL,
  `autoId`   INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `ksc`      TEXT NOT NULL,      -- ★ 密文的【大写 HEX】(不是 base64)
  `iv`       TEXT NOT NULL,      -- ★ 16 字节 IV 的【大写 HEX】
  `date`     INTEGER NOT NULL
)
CREATE UNIQUE INDEX IF NOT EXISTS `index_ksc_info_deviceId_alias` ON `ksc_info` (`deviceId`, `alias`)
```
- 库 = **`ksc.db`（v1）**，identity hash `c45f89d2ba7e66035322cada204af14c`，**无 `addMigrations`、无 `fallbackToDestructiveMigration`** `[jadx] pair/db/KscDatabase_Impl.java:68`、`pair/persistence/f.java:45`
- 用 `applicationContext`（CE 保护区）
- **另有两份同名表是历史残留**：`FastPairDatabase`（库名 `a_d`，v19）里的 `ksc_info`；以及 `datatrans/temp/base/database/KscDatabase`（`deviceId`/`alias` 可 NULL，实体类已从 dex 删除 → **死代码**）

**At-rest 加密** `[jadx] pair/persistence/f.java:437-448` / `:217-233`、`p/c.java:146-172`：
```
密钥：AndroidKeyStore 内【固定别名 "ksc_key_alias"】的 AES-256 密钥
      —— ★ 全设备、全记录共用一把
算法：AES/CBC/NoPadding（明文必须恰为 16 字节，否则 "Ksc length error, now is N; 16 is required."）
IV  ：由 keystore 在 cipher.init(ENCRYPT_MODE) 时随机生成后回读 → 所以必须落库
解密结果包装为 KeyStoreHelper$MasterSecretKey（getAlgorithm()="HmacSHA512", getFormat()="RAW"）
```
→ **无 StrongBox、无 attestation challenge**。
→ **对我们的启发**：`AES/CBC/NoPadding` + 固定密钥别名，是**够用但不讲究**的选择（CBC 无认证标签，密文可被篡改而不被察觉——不过因为 KSC 是共享密钥，篡改会直接导致后续 HMAC 失败，实际影响有限）。**我们若实现等价物，建议 AES-GCM 一步到位。**

**`alias` 是什么** `[jadx]`：**"KSC 密钥槽别名"（key-slot alias）**，固定 **6 字节**，落库为 12 字符大写 hex。与 `deviceId` 共同构成逻辑主键 `(deviceId, alias)` = **"同一设备的第 N 把共享密钥"**。
三个生产者：
- (a) KSC 配对流程（`pair/f.java:305-315`）：请求包里 `kscAlias` 必须恰为 6 字节，否则 `"kscAliasLength invalid: N"`
- (b) SCP sync-secure（碰一碰 `persistKscAfterUserConfirm`）：`SyncSecureMessage{deviceId, ksc, kscAlias}`
- (c) 蓝牙非安全通道认证（SigmaAuth / InsecureRfcommAuth）：alias 取握手载荷 **`bytes[26..32]`**（`connectivity/bt/secure/a.java:757`）
  → 此时 **KSC 本体也不是随机的**，而由 ISK 派生：`PBKDF2WithHmacSHA256(ISK, salt=8×0x00, iterations=3, keyLength=128)` `[jadx] datatrans/security/b.java:2147-2150`

### 3A.5 `ksc_info` 全生命周期

| 语句 | SQL | 执行方 |
|---|---|---|
| **INSERT** | `INSERT OR REPLACE INTO ksc_info (deviceId,alias,autoId,ksc,iv,date) VALUES (?,?,nullif(?, 0),?,?,?)` | `KscRepositoryImpl.g()` = **saveKsc** |
| **UPDATE** | ``UPDATE OR ABORT `ksc_info` SET … WHERE `autoId` = ?`` | **从未被调用 → 死代码** |
| **DELETE(1)** | `DELETE FROM ksc_info WHERE deviceId = ?` | `removeKsc$2`（解绑设备时清密钥，如 `oPhoneConnectServiceImpl.unbindDevice`） |
| **DELETE(2)** | `DELETE FROM ksc_info WHERE deviceId = ? AND alias = ?` | 别名级删除 / 淘汰最老 |
| **SELECT A** | `SELECT * FROM ksc_info ORDER BY date` | `getAllKscInfos` → **`InsecureRfcommAuth.checkRequestParams` 暴力遍历所有已存密钥** |
| **SELECT B** | `SELECT * FROM ksc_info WHERE deviceId = ? ORDER BY date` | 仅 `removeOldKscIfNeed` |
| **SELECT C** | `SELECT * FROM ksc_info WHERE deviceId = ? AND alias = ?` | **`loadKsc` 主查** / `checkKscDuplicate` |

**淘汰策略 `removeOldKscIfNeed`**（`[jadx] pair/persistence/f.java:251-326`）：
```kotlin
if (deviceId 为空) return
val list = query("SELECT * FROM ksc_info WHERE deviceId = ? ORDER BY date", deviceId)  // ASC → 最老在前
if (list.size() >= 10) {                       // ★ 每设备上限 10
    dao.delete(list[0].deviceId, list[0].alias) // 删最老
    log("ksc for <dev> arrives max:10, abandon the oldest ksc:<row>")
}
```
→ **只有"按 deviceId 计数上限 10"这一种策略**；**没有 TTL、没有按最后使用的 LRU、没有全局条数上限**。

### 3A.6 降级 / fallback 逻辑（精确触发条件）

#### `"key type not match, change to ukey2"` —— 两个触发点，行为不同

**SCP Server 侧**（`[smali] pair/scp/e.smali:636-704`，类 = `RealScpServer.handshakeInternal`）：
```
if (serverKeyType == clientKeyType)      → 用该类型
else if (serverKeyType == BOTH)          → 保持 client 的
else if (clientKeyType == BOTH)          → 用 server 的
else {                                   // 真·不一致
    isTapShare = eq(channel, "tap_share")            // e.smali:651-659
    if (!isTapShare && activeTriggerMode == 0)
        throw ScpException(2558, "key type not match")   // ★ 硬失败，不降级
    log("key type not match, change to ukey2, isTapShare=" + isTapShare + ", mode=" + mode)
    → ScpKeyType.UKEY2                                       // ★ 否则强制降级
}
```

**SCP Client 侧**（`[smali] pair/scp/b.smali:470-517`）：**不一致就无条件降级到 UKEY2，不检查 `isTapShare` / `triggerMode`。**
> 附注：客户端的日志串用的是**中文全角逗号** U+FF0C（`"key type not match， change to ukey2"`），服务端是半角。`[smali]` —— 一个可以用来区分两侧日志的小细节。

**`createScps(keyType)` 的二级映射**（`[smali] pair/scp/d.smali`）：
```
EMPTY(0)  → 只打日志 "the key type is empty"（sharedSecret 保持 null）
UKEY2(1)  → ScpUKey2
OOBKEY(2) → ScpOobKey
BOTH(3)   → ScpUKey2            ★ 与 UKEY2 同分支
```
→ **无二级 fallback**（没有 OOB→UKEY2 的二次降级；EMPTY 之后就是空密钥）。

#### `"can not find ukey2 delegate."` —— 与上述降级**无关**

它是 UK2 实现层的**防御性空指针守卫** `[jadx] bl/b.java:21-25`：
```java
if (delegate == null) return Result.failure(new Ukey2Exception("can not find ukey2 delegate."));
```
delegate 由 DI 提供；**若 DI 拿不到实例，代码先打日志 `"can not get the instance: "`，然后仍然构造 `new bl.b(null)`** `[jadx] com/oplus/pantaconnect/d.java:507-541`
→ 于是在**密钥库/UK2 插件未加载的进程**里，SCP UKEY2 路径会拿到这个错误。**这是唯一触发点。**
同族错误串：`"can not found server delegate"`（`bl/b.java:46,71`）、`"Not found ukey2 handshake delegate."` / `"Not found ukey2 delegate."`（`al/b.java`）

#### 配对层（A 层）的降级

`[jadx] contract/b.java:369-372`（`KscNegotiationContract`）：
```java
f5470a = (accountEqual == null || !accountEqual)
    ? new keygenerator.d(pairRole, option)    // ★ UKey2KeyGenerator
    : new keygenerator.b(pairRole, option);   // ★ OobKeyGenerator（注意字母序：b 是 OOB）
f5471b = new ksc.a(pairRole, option);         // KscGeneratorImpl
```
**「账号是否相同」直接决定密钥协商算法**：
- **同账号 → `OobKeyGenerator`**（账号会话密钥，无 ECDH）
- **不同账号 → `UKey2KeyGenerator`**（UK2 匿名 ECDH）
- 其余位掩码值 → `KeyBasedTypeNotFoundException(103)`，**无兜底**
- ⚠️ 位掩码 **`32`** 在两张 wire 映射表里都出现，但**没有任何生成器分支对应它** → 一旦出现必然抛异常。`[未确定]` 语义

**前置门控**（`contract/b.java:352-367`）：
- `action == APP_LINK`（link share）→ `"link share, ignore ksc"`，**整个 KSC 协商跳过**
- `checkAccessoryAvailable()` 为真 → `"BT senseless accessory has been created, no need to negotiate ksc"`，**跳过**（★ 这就是"已配对设备免协商"的实现）

#### Fallback 总顺序（一句话）

```
B 层（ScpKeyType，BOTH 是通配）
  ├ 一致 / 可被 BOTH 化解          → 用对方或自己的类型
  ├ 真不一致 + server + (!tap_share && mode==0) → 抛 2558，硬失败
  └ 真不一致 + 其他所有情况         → 强制 UKEY2
  ↓ createScps: EMPTY→空 / UKEY2→ScpUKey2 / BOTH→ScpUKey2 / OOBKEY→ScpOobKey（无二级降级）
A 层（keyBasedType）
  accountEqual==true  → OobKeyGenerator
  accountEqual==false → UKey2Generator
  其余位掩码           → 抛 103，无兜底
```

### 3A.7 `ACCESSORY_INSECURE_NFC_SHARE` 到底是什么

**它是「配对模式标志位」，值为 `2`。不是权限、不是开关、不是 Settings 项。** `[jadx]`

```java
// [jadx] com/heytap/accessory/constant/AFConstants.java:5-7
public static final int ACCESSORY_INSECURE_FLAG_NORMAL      = 0;
public static final int ACCESSORY_INSECURE_NFC_INTERCONNECT = 1;
public static final int ACCESSORY_INSECURE_NFC_SHARE        = 2;   // ★ 碰一碰传文件
```
镜像定义：`datatrans/accessory/InsecureType{DEFAULT(0), NFC_INTERCONNECT(1), NFC_SHARE(2)}`、`sdk/internal/scpCompat/InsecureType{InterConnect(1), SHARE(2)}`（值兼容）。

**⚠️ 全 APK 搜索 `ACCESSORY_INSECURE_NFC_SHARE` 只有定义处 1 处命中，没有任何代码分支直接比较它** —— 实际调用都传裸数字 `2` 或 `InsecureType.NFC_SHARE.a()`。

**它改变了什么（三件事，且都不涉及"跳过加密"）：**

1. **决定 SCP 通道的连接类型 + `UidType`**
   `ConnectionType.SPP_INSECURE(32)` + `UidType.INSECURE(2)`
   `[jadx] transportproxy/l.java:324,341,353,436,583`：`uidType = (connectorType == SPP_INSECURE) ? UidType.INSECURE : UidType.DEFAULT`
2. **`InsecureType` 是 wire 上的字段**，会写进 protobuf（`CompatConnectAccessoryParamsKt.java:23` `.setInsecureType(...)`），对端据此区分"碰一碰分享" vs "NFC 互联"
3. **触发 `tap_share` 服务的主动连接通知**（`transportproxy/l.java:1010,1111`）

**它不做什么**（重要）：**不跳过加密，不跳过认证。** 应用层 AES-GCM **照常生效**。名字里的 "INSECURE" 指的是**"无感（不需要系统配对）的连接通道"**，不是"不加密"。

> 真正"跳过认证"的是**另一个** flag：`zb.a.P`——当 Sigma 已预认证时置位，会让 `f.java`/`i.java`/`h.java` 打日志 `"Sigma authed need return"` 并**短路挑战-应答**。`[jadx]`
> ⚠️ 这也说明：**OPPO 内部存在"已认证则跳过"的快路径**，其置位条件 `[未确定]`。

### 3A.8 会话加密：AES-GCM-128

`[smali]` 三重确认（`ke/a.smali:22,46` + `ke/b.smali:440-448,704-712`）：
- **算法**：`AES/GCM/NoPadding`，密钥 128 位
- **IV/nonce**：取 `bytes[16..28]`（**12 字节**，GCM 标准长度）
- **MAC**：`HMAC-SHA512`

**挑战-应答（重连时的身份证明）** `[jadx] datatrans/security/a.java:73-83`：
```java
public static byte[] x(SecretKey ksc, byte[] b) {
    Mac mac = Mac.getInstance("HmacSHA512");
    mac.init(ksc);
    byte[] full = mac.doFinal(b);
    byte[] out = new byte[8];
    System.arraycopy(full, 0, out, 0, 8);      // ★ 截断到 8 字节
    return out;
}
```
挑战内容构造：`bArr3[0..16)` = 随机/标识，`bArr3[16..24)` = **小端 64 位时间戳**（`for (i in 0..8) bArr3[16+i] = timeServer >>> (i*8)`）`[jadx]`

**⚠️ `checkRequestParams` 是"用所有已存 KSC 逐个试算"的暴力匹配，不是按 deviceId 精确查找** `[jadx] secure/a.java:443-471`：
```java
list = getAllKscInfos();  Collections.reverse(list);   // 最新的在前
for (item : list) {
    key = getKscKey(hexToBytes(item.deviceId), hexToBytes(item.alias));
    试算 HMAC...                          // 命中即通过
}
// 全部失败 → log("checkRequestParams, rc not match, key sum:" + size)
```
→ `[推断]` **这意味着：任何持有"任一已配对设备 KSC"的对端都能通过验证（跨设备密钥混用风险）**。这是一个真实的设计缺陷。
→ 只能被 `AuthFalseCount` 缓解：**5 分钟窗口内上限 5 次失败**，持久化在 `SharedPreferences("SecurityPreferences")`，键 `"insecure_auth_fc_" + remoteAddr`，值 `"count;timestamp"`。`[未确定]` 达到 5 次后的具体惩罚动作。

### 3A.9 信任模型一句话

> **信任 = NFC 物理触碰（隐式意图）+ 账号同源（**自动放行，只比对 3 字节 hash**）+ 用户点击（不同账号时）+ KSC 挑战-应答（重连时）。
> 无证书链、无 attestation、无用户可见身份比对；UK2 的验证串被显式丢弃。首配对是纯 TOFU。**

### 3A.10 对我们的直接启示（安全部分）

| OPPO 的做法 | 该不该跟 | 理由 |
|---|---|---|
| 用 Google UK2（ECDH P-256 + SHA-512 + HKDF） | ✅ **跟** | 成熟、经过审查、有开源实现可参照；比自己设计强得多 |
| **丢弃 UK2 的 2 字节验证串** | ❌ **别跟** | 成本极低（屏幕显示 2 个数字），却能挡中间人。OPPO 省了，我们不该省 |
| 账号相同就**自动放行不弹确认** | ⚠️ **跟，但要改** | UX 上确实好（同账号自己两台手机）；但**必须比对完整 hash**，不能只比 3 字节 |
| KSC 持久化 + 重连免协商 | ✅ **跟** | 这是"老用户碰一下就传完"的根本原因 |
| KSC at-rest 用 `AES/CBC/NoPadding` | ⚠️ **跟思路，换算法** | AES-GCM 一步到位，避免 CBC 无认证标签 |
| 挑战-应答**暴力遍历所有密钥** | ❌ **别跟** | 应按 deviceId 精确查找；遍历等于跨设备密钥可混用 |
| SCP 通道用 `SPP_INSECURE`（**明文 BT**） | ⚠️ **谨慎** | 应用层有 AES-GCM 兜底，但 BT 链路本身明文。若我们的威胁模型包含"近场窃听 BT"，应在链路层也加密 |
| `ACCESSORY_INSECURE_NFC_SHARE` 这类**模式标志位** | ✅ **跟思路** | 用"模式"而非"布尔开关"表达安全档位，比 `boolean isSecure` 可扩展 |

---

---

## 5. 收发双方确认时序（对照视频的分步表）

> 交叉引用：`docs/PRODUCT_VIDEO_ANALYSIS.md`、`docs/ANIM_INVENTORY_VIDEO.md`（本次不重复视频分析，只做"协议步骤 ↔ 用户可见步骤"的对齐）

| # | 用户可见（发送方） | 用户可见（接收方） | 对应协议 | 协议超时 |
|---|---|---|---|---|
| 1 | 相册里选好照片，手机背面贴过去 | 手机背面贴过来 | 物理 NFC 触碰 → `OoplusHostApduService` 收 `00CA0101` | — |
| 2 | 屏幕边缘浮现**触碰动效**（`NfcTouchingAnimationView`） | 同 | 配对链① `NfcPairInitContract` | 6 s |
| 3 | —（无感） | —（无感） | 配对链② `NfcDataSyncContract`：交换 action / accountEqual / deviceType | 6 s |
| 4 | **确认卡弹出**，显示缩略图 + 文案，提示**上滑** | （可能同时弹卡） | 配对链③ `NfcSenderConfirmContract` | 10 s |
| 5 | 用户**上滑** | **接收卡**出现，等用户点"接受" | 配对链④ `NfcReceiverConfirmContract` | **23 s** |
| 6 | 卡片显示"正在连接" | 同 | 配对链⑤ `KscNegotiationContract` → ⑥ `NfcConnectAccessoryContract`（**BT 无感建连**） | 各 6 s |
| 7 | —（几乎无感） | 同 | 传输链① `ReadyConfirmContract` | 15 s |
| 8 | **发送方二次确认**（滑动） | — | 传输链② `SenderConfirmContract` | **65 s** |
| 9 | — | **接收方二次确认** | 传输链③ `ReceiverConfirmContract` | **65 s** |
| 10 | **进度条 / 百分比**（`onPercentChange`） | 同 | 传输链④ `TransferConfirmContract` → `com.coloros.oshare` 真传 | **24 h** |
| 11 | "已发送" | "已接收"，**跳转接收 App 的"无缝看"页面** | `onResult` → `Intent(com.oplus.interconnect.action.SHARED_DATA)` | — |

**关键观察（与视频一致）**：**确认动作发生两次**——第一次是"要不要传"（配对链③④），第二次是"开始传"（传输链②③）。这不是冗余，而是**配对链的确认用来建立信任与连接，传输链的确认用来锁定内容**（内容可能在这两次之间变化，因为相册是后来才交数据的，见 §4.6 第 5 步的硬顺序约束）。

**"同账号"会跳过第 5 步**（§3A.3 结论二）——这是**同账号两台手机"碰一下就传完"**的全部秘密。

**"手机→平板"会跳过第 4 步**（§3.10 的自动接受规则）。

---

## 6. 传输层

> 本节由子代理深挖（`_Temp/afk/notes/transport.md`）。以下为已确证的核心事实。

### 6.1 字节怎么走（一句话）

**NFC/APDU 只走"元数据 + 连接参数"；真正的字节走 BT-SPP 上的 SCP 协议；最终文件由 `com.coloros.oshare` 搬运。**

### 6.2 承载：`ConnectionType.SPP_INSECURE`

`[jadx] contract/d.java:110`（`NfcConnectAccessoryContract`）+ `[jadx] l.java:871`（`savePairDevice`）都硬编码 `ConnectionType.SPP_INSECURE`。
→ **碰一碰的承载是 BT 串口（SPP），明文档**。`[jadx]`

**为什么是 SPP 而不是 BLE GATT / Wi-Fi P2P**：`SPP_INSECURE` 的语义是"**无系统配对弹窗的 SPP**"。正常 BT SPP 需要先 `createBond` 弹配对框；而碰一碰把**连接参数（蓝牙地址 + KSC）经 NFC 传过去**，于是可以跳过系统配对直接连——这就是 §3.1 契约⑥ 里 `connect bt senseless BT accessory` 那句日志的含义。

### 6.3 跨 NFC 边界的到底是什么

这是最关键的一次"交接"。从 `[jadx] contract/d.java:85-180` 的 `CompatConnectAccessoryParams` 构造参数可以精确读出：

```java
CompatConnectAccessoryParams(
    deviceIdHex,        // ← 对端协议设备 ID（hex 字符串）
    address,            // ← ★ 蓝牙地址（来自发现层）
    kscBytes,           // ← ★ 契约⑤ 协商出的 KSC（可能为 null → new byte[0]）
    localDeviceId,      // ← 本机设备 ID
    ConnectionType.SPP_INSECURE,
    nfcEvent,
    isPhoneConnection
)
```

→ **跨 NFC 边界的是：`(对端蓝牙地址, 对端协议设备ID, KSC, 本机设备ID)`。** `[jadx]`
→ 有了这四样，BT 连接就能**无感建立**。`[推断]`

### 6.4 传输委托给 OShare

`[smali]`
```
com.coloros.oshare.FileTransferManager.getInstance()
    .sendData(Context, Bundle, ArrayList<...>, ResultCallback)
    → com.coloros.oshare.ITransferService.sendData(Intent)
```
框架侧实现的回调（`com/oplus/interconnect/nfc/share/d.smali`）：
- `onFileInfoReceived(com.coloros.oshare.FileInfo)`
- `onPercentChange(int, long)` ← **进度条**
- `onResult(int, ArrayList)`

`ITransferService` 的 **11 个事务**（`[smali]` 全量）：
```
registerCallback / unregisterCallback / pauseSeedlingCard / resumeSeedlingCard /
notifyUserAckResult / sendData / cancel / notifyTaskStart / notifyBtConnected /
notifyTaskFail / getTransferStatus
```
`TransferCallback` 的 **4 个回调**；`FileInfo` 5 字段；`SharedRecord`（OShare 侧）6 个 String + `byte[]`。

**绑定方式**：`Intent("com.oplus.oshare.FILE_TRANSFER_SERVICE")` + `setPackage("com.coloros.oshare" | "com.oneplus.oshare")`；预热用 `contentResolver.call("com.oplus.oshare.nfc", ...)`。`[smali]`

→ **对我们的含义**：**wire 格式全在这个 APK 内（27 个 smali 文件），可以字节级复刻；缺的只是服务端实现。** 这是好消息——意味着"传输层"不是黑盒。

### 6.5 失败 / 超时 / 重试（总表）

**超时**：见 §3.9 与 §3.2 两张表（配对链 6/6/10/23/6/6 s；传输链 15/65/65/24h）。

**失败路径**：
- 配对层任一步超时 → 抛对应异常（§3.9 表），整链 `finally` 清理，`isPairing` 复位
- 用户取消 → `NfcPairCancellation` 通知"当前契约"的 `onCancel(reason)`，`job.cancel()` `[jadx] l.java:920-935`
- `NfcConnectAccessoryContract` 失败 → `NfcPairException("connect bt senseless BT accessory failed")`，或 `acceptAccessory` 侧的 `"accept bt senseless BT accessory failed"` `[jadx] d.java`
- 传输层 → `TransferConfirmResult` 里区分 `SENDER_TRANSFER_FAILURE` / `RECEIVER_TRANSFER_FAILURE` / `RECEIVER_TRANSFER_PERMISSION_GRANT_FAIL` / `RECEIVER_TRANSFER_CANCEL`
- 空结果降级：`transfer/d.smali:130` —— **status 1000 但记录为空 → 降级成 1001**

**重试**：`[未确定]` —— 本次**没有在 APK 里找到显式的"自动重试 N 次"逻辑**。观察到的是"**失败即整体失败 + 用户重新碰一次**"的模型。这是一个**值得注意的设计选择**：把重试交给用户（再碰一下），而不是在协议层做退避重试。
→ `[推断]` 这么做的理由是：碰一碰的"重试"成本极低（手机再碰一下），而协议层重试会带来状态机复杂度。

**`disconnectAccessoryForTapShare` / `isP2PConnectorType`**：`[未确定]` —— 这两个串在 dex 字符串表里能搜到，但本次**未能定位到具体的分支代码**（见 §12）。已知的是 `ConnectionType` 里存在 `SPP_INSECURE(32)` 与 `P2P` 两个值，且 `UidType` 有 `P2P(4)` 档 —— `[推断]` 存在"SPP 不通则走 Wi-Fi P2P"的备选路径，但**未证实**。

---

---

## 7. 逐机型素材机制（`modelId_*` / `major_type` / `config_os12.xml`）

> 本节由子代理深挖（`_Temp/afk/notes/model_assets.md`，904 行）。**核心要求"找到消费这些素材的代码"已达成。**

### 7.1 ⭐ 消费代码在哪里（这是本节最重要的答案）

**素材不是被 NFC 界面消费的，而是被 PANTACONNECT 的设备发现 / 配对弹窗流水线消费的。**

```
① 启动时把 assets 拷到本地    [jadx] appcore/config/assets/a.java:86  (AssetResCopy)
     assets/modelId_XXXXXX/*  →  <device-protected filesDir>/modelId/<XXXXXX>/
     ↑ 触发点：AppCore 启动引导  [smali] smali_classes/com/oplus/pantaconnect/appcore/a.smali:106

② 唯一的 XML 解析器            [smali] smali_classes2/bn/c.smali:1903  N(FileInputStream): ResourceData
     调用方 [jadx] appcore/config/a.java:80

③ 路径/版本持有                [jadx] appcore/config/resource/a.java
     f7332d = 目录；f7333e = <dir>/config_os12.xml

④ 派发器                      [jadx] appcore/config/resource/b.java  (ResourceObtaining)
     local / network / SAU / fallback 四级；o.d.o(v,dir) / o.d.B(v,dir)

⑤ 第二个读者（同文件）          [jadx] appcore/peripheral/common/provisioning/d.java:32
     LocalDevicePublicKeyProvider：正则 <public_key>\s*([A-Fa-f0-9]+)\s*</public_key>
     路径 <filesDir>/modelId/<id>/config_os12.xml
```

**⚠️ 重要的否定性证据**（子代理用 dex 原始字符串表 `str_classes*.txt` 验证）：
- **`device-config`、`device_name_v2`、`config.xml` 作为字符串常量在 dex 里根本不存在**
- **`modelId_` 也不存在** —— 它是 `ServiceNodeBundleKeys.MODEL_ID = "modelId"` + `str.substring(8)` 拼出来的
- `image_pairing` / `image_connected*` **从不是字符串字面量**，它们**只存在于 XML 的 `<resource_name_static>` 里**

> **对我们的实现含义**：素材目录名 `modelId_XXXXXX` 是**打包时的目录命名**，运行时用的是 `modelId` 值 + `substring(8)` 的约定。**如果我们要复刻，直接用 `modelId/<ID>/` 目录结构更清晰**（不必复刻这个 `substring(8)` 的怪癖）。

### 7.2 `modelId` 运行时来源

**类型**：`discovery/model/v` = 3 字节；合法条件 `ModelIdKt.isValidModelId`（6 个 hex 字符）`[jadx]`

| 来源 | 具体 |
|---|---|
| **对端** | BLE `ModelIdAbilityPublishPacket`（`discovery/bleprovider/packet/d.java`，携带 modelId + major + nickName）、NSD、protobuf `TerminalInfoParams.modelId_` → `ServiceNodeKt.parse/toServiceNode` |
| **对端（Apple Watch 硬编码）** | **`27D49C`** |
| **本机** | `ConfigRepository.b()`（`config/usecase/b.java:76`）→ `Build.MODEL` 查表 `f7341g`；查不到则走 `resource/b.b()`（`getModelIdLegacy`）硬编码表：<br>`2B1488`=手机 / `9F4915`=折叠 / `614AB1`=OPPO 平板 / `07CE76`=一加平板 / `751080`=其它平板 |

→ **无服务器、无本地 DB 参与 modelId 解析**。本机 modelId 完全来自 `Build.MODEL` + 硬编码表。`[jadx]`

### 7.3 `major_type` 的解析与作用

**解析** `[smali] bn/c.smali:2467`：`Integer.parseInt(text, 10)`，默认 `-1` → `ResourceData.major`
**映射** `[jadx] discovery/model/j.java:44 b(int)` → `DeviceType`（5=TV, 6=PC, 8=PHONE, 10=PAD, 14=APPLE_WATCH, 51=NAS, 0=ALL, -82=UNSPEC）—— **与 §3.7 的 `DeviceType.major` 表完全一致**

**驱动什么**：
- 设备类型推断（`appcore/devicediscovery/a.java:45`、`discovery/model/w.java:189`、`output/f.java:73`）
- 弹窗分支（`DialogViewModel.java:240` 对 `major == 6` 特判）
- RSSI 阈值（`getReconnect/Sense/SenselessThreshold`）

**⚠️ 一处对任务简报的纠正**：**4 个 NFC PairTask 不是由 `major_type` 选择的。**
`[jadx] tapshare/d.java:112` 的选择依据是 **NFC 发现结果子类型 + `NfcDeviceBrand`**：
```
s7.b (品牌)          → PadNfcPairTask
OPLUS + s7.c         → OplusNfcPairTask
APPLE + s7.a         → IosNfcPairTask
```
`:195` 另外用 `DeviceType.SMART_WATCH` 选 `WatchNfcPairTask`。
**类映射**（含一处日志 tag 错误）：`task/a`=Ios、`task/c`=Oplus、`task/d`=Pad、`task/f`=Watch。`[jadx]`
> 附注：`task/c`（OplusNfcPairTask）的 logger tag 被**错误地标成 "OplusNfcPairTask"** 之外的东西 `[未确定]` 子代理提到"logger tag mislabeled"，具体值未记录。

### 7.4 XML 解析细节

- **解析器**：`[smali] bn/c.smali:1903` `N(FileInputStream)` —— **不是** XmlPullParser，是自定义/生成式解析
- **`config.xml` 在本 APK 中是死资产** —— **只有 `config_os12.xml` 被读取**（文件名硬编码在 4 处）。`"os12"` 指**格式代次**，不是"对端 OS 版本"。`[jadx]`/`[smali]`
- **本地化**：`<values-<lang>>` 子元素；选择算法在 `bn.c.M()`，通过 OPlus 包的 `language_values_exam` 取语言，回退 `Locale.getDefault()` `[jadx]`
- **`<version>`** 只在**落地层**比较，不参与选择 `[jadx]`

### 7.5 状态图片：枚举、UI 时机、切换驱动、明暗

**stateid 全枚举**（`[assets]`，与任务简报的数字完全吻合）：

| `<stateid>` | `<resource_name_static>` | 深色变体 | 机型数 |
|---|---|---|---|
| `discovery` | `image.png` | — | **23（全部）** |
| `connecting` | `image_pairing.png` | — | **15** |
| `fast_pair_success` | `image_connected.png` | — | **15** |
| `fast_pair_failure` | `image_connected_failed.png` | `image_connected_failed_dark.png` | **17** |
| `connect_confirm` | `connect_confirm.png` | — | **13** |

`<resource_name_static_dark>` 全库**仅 2 处**（`00BE65` 与 `987F2B` 两个 TV）。

**stateid → UI 时刻**（`[jadx] e3/d.java:340` `O(SeekerDialogState, Map)`）：

| `SeekerDialogState` | 取用 stateid | UI 时刻 |
|---|---|---|
| `DEVICE_DISCOVERY`(1) | `discovery` | 发现到设备、弹窗首屏 |
| `CONNECT_CONFIRM`(2) | `connect_confirm` | 等用户点"连接" |
| `DEVICE_PAIR_PROCESS`(3) | `connecting` | 配对/连接中（若配 mp4 则用 `<resource_name_dynamic>`） |
| `DEVICE_PAIR_CONNECTED_SUCCESS`(4) | `fast_pair_success` | 连接成功（常带 `<auto_close_dialog>1`） |
| `DEVICE_PAIR_CONNECTED_FAILED`(5) | `fast_pair_failure` | 连接失败 |
| `DEVICE_AUTHENTICATION_TIME_OUT`(10) | `fast_pair_failure` | 认证超时（**复用失败态**） |
| `DEVICE_AUTHENTICATION`(6) ~ `_LIMIT`(9) | `code_auth` 等 | **assets 未机型化**，走 res 资源 |
| `DEVICE_RECONNECT_SUCCESS/FAILED`(11/12) | `reconnect_success/failure` | **assets 未提供** |

**路径拼装与渲染** `[jadx] e3/d.java:1130-1187` + `ui/view/n.java:290-306`：
```java
String imagePath = resourceInfo.getImagePath();          // 机型目录绝对路径，结尾带 File.separator
imageView = imagePath + resourceNameStaticList.get(0);   // 静态图
// 深色：imagePath + resourceDarkNameStaticList.get(0)
// 有 video 资源 → 改用 TextureView + TBLMediaPlayerUtilKt.playMp4(...)
File f = new File(resource.e.H(j2, isDark));
if (f.exists()) imageView.setImageURI(Uri.fromFile(f));
else { log("image resource is not exist"); imageView.setImageResource(R$mipmap.image_connected_failed); }
```
→ **⚠️ 真实的资源缺口**：`614AB1` 与 `07CE76` 两个机型有 5 个 stateid 但**只有 2 张 png** —— 引用 `image_pairing/connected/connect_confirm` 时会**指向不存在的文件**，UI 走 `R$mipmap.image_connected_failed` 兜底。**这是 OPPO 自己的 bug，复刻时注意。**

**深色选择规则** `[jadx] resource/e.java:300`：
```java
if (!isDark) return j2.getImageResource().getResourceName();
String dark = j2.getImageResource().getResourceNameDark();
return dark.length() == 0 ? j2.getImageResource().getResourceName() : dark;   // 空串回退
```
深色判定 `[jadx] p001if/b.java:448`：`32 == (uiMode & 48)`（`UI_MODE_NIGHT_YES`）
→ **命名约定**：后缀 `_dark`，且**必须在 XML 里显式声明** —— **代码不做 `名字 + "_dark"` 的自动推导**。
→ 全库只有 2 个机型声明了 dark 变体 → **深色模式下几乎所有机型都回退到普通图**。

### 7.6 23 个机型完整清单

`[assets]` 直读 `_Temp/afk/apk/assets/modelId_*/`

| # | modelId | major | 类型 | config_os12 | en_name | 图片状态集合 |
|---|---|---|---|---|---|---|
| 1 | `00BE65` | 5 | TV | ✅ v7 | OPPO TV | image, pairing, connected, failed, **+dark** |
| 2 | `07CE76` | 10 | PAD | ✅ v3 | 一加平板 | image, failed ⚠️ |
| 3 | `0A9880` | **51** | **NAS** | ✅ v4 | — | image |
| 4 | `0CAA28` | 10 | PAD | ✅ v2 | 一加平板 Pro | 全 5 态 |
| 5 | `27D49C` | 14 | APPLE_WATCH | ✅ v2 | Apple Watch | image |
| 6 | `2B1488` | 8 | PHONE | ✅ v7 | Phone | image |
| 7 | `2B34D8` | 10 | PAD | ✅ v6 | OPPO Pad 3 Pro | 全 5 态 |
| 8 | `3516D5` | 10 | PAD | ✅ v8 | OPPO tablet | 全 5 态 |
| 9 | `404DA9` | 10 | PAD | ✅ v2 | 一加平板 | 全 5 态 |
| 10 | `4D6B3B` | 6 | PC | ✅ v1 | PC Connect | 全 5 态 |
| 11 | `51AFAD` | 10 | PAD | ✅ v9 | OPPO tablet | 全 5 态 |
| 12 | `5EAAD4` | 8 | PHONE | ✅ v7 | Phone | image |
| 13 | `614AB1` | 10 | PAD | ✅ v4 | OPPO 平板 | image, failed ⚠️ |
| 14 | `751080` | 10 | PAD | ✅ v8 | OPPO tablet | 全 5 态 |
| 15 | `93CA67` | 6 | PC | ✅ v1 | PC Connect | 全 5 态 |
| 16 | `987F2B` | 5 | TV | ✅ v4 | OnePlus TV | image, pairing, connected, failed, **+dark** |
| 17 | `9F4915` | 8 | PHONE | ✅ v7 | Phone | image |
| 18 | `B5339A` | 6 | PC | ✅ v14 | PC Connect | 全 5 态 |
| 19 | `BFF6C4` | 10 | PAD | ✅ v8 | OPPO tablet | 全 5 态 |
| 20 | `C3734F` | 8 | PHONE | ✅ v7 | Phone | image |
| 21 | `EF36C0` | 10 | PAD | ✅ v3 | OPPO Pad 3 | 全 5 态 |
| 22 | `FCA400` | 10 | PAD | ✅ v3 | OnePlus Pad 2 | 全 5 态 |
| 23 | `FDC1CF` | 10 | PAD | ✅ v8 | OPPO tablet | 全 5 态 |

**分布**：TV×2、PC×3、PHONE×4、PAD×13、APPLE_WATCH×1、**NAS×1**
> ⚠️ **任务简报说"23 个机型"，实际是 23 个 `modelId_*` 目录**，其中包含 **1 个 NAS**（`0A9880`，major=51）——**不是设备形态而是存储设备**。简报的 "5=TV, 6=PC, 8=Phone×4, 10=Pad×7, 14=Apple Watch" 里 **Pad 实际是 13 个不是 7 个**，且**漏了 NAS**。

**注意各机型携带的 stateid 不完整**：
- 两个 TV：4 个 stateid（**无 `connect_confirm`**）
- Phone / NAS / Watch：**只有 `discovery`**（→ 手机对手机根本没有机型外观图，只有首屏一张）
- 其余 Pad/PC：全 5 态

### 7.7 内置 vs 可下载：三级资源获取 `[jadx]`

```
① APK 内置 assets                          （离线可用，本期覆盖 23 个机型）
② /data/oplus/common/sau_res/res/sys_oaf_model_<id>/    （SAU 下发，可在设备上更新）
③ OPPO IOT 服务器                          （ResourceObtaining.h()，参数含 Android/ColorOS/ota/model/imei）
```
→ **`<version>` 只在落地层比较**，用于决定"服务器上的版本是否比本地新"。

**未知 modelId 的兜底** `[jadx] resource/b.java:328` `getModelIdResUri`：
→ 回退到 `resource/b.m(major)` 的**硬编码表**，返回一个 **FileProvider URI**（`com.heytap.accessory.fileProvider`），授予 **10 个白名单包**（launcher / systemui / padconnect / …）

### 7.8 对我们的启示

| OPPO 的做法 | 该不该跟 | 理由 |
|---|---|---|
| **按 modelId 分目录 + `<major_type>` 分形态** | ✅ **跟** | 用数据驱动 UI 素材，加机型不用改代码。**我们若要支持"碰一碰对象是 TV/PC/Pad/Watch"就必须这么做** |
| 用 `<stateid>` **语义名**而非文件名索引 | ✅ **跟** | 状态名（`discovery`/`connecting`/`fast_pair_success`/`fast_pair_failure`/`connect_confirm`）比"第几张图"稳健得多 |
| **只提供 `discovery` 一张图的 Phone 机型** | ⚠️ **看情况** | OPPO 手机对手机只有首屏图——因为**手机碰手机不需要展示对方"长什么样"**。这是个合理的简化：**我们也可以只做首屏 + 卡片的缩略图**（§4.1 的 `CompatSharedCard.thumbnail`） |
| 三级资源获取（内置 → SAU → 服务器） | ❌ **先不做** | 我们的机型集合是封闭的（自家 ROM），内置即可。**不要过早引入服务器依赖** |
| `_dark` 变体需要 XML 显式声明 | ✅ **跟** | 显式声明 > 命名约定（避免"文件存在但拼错名字"这类静默失败） |
| 缺图时**静默回退到一张通用失败图** | ✅ **跟** | 容错思路正确，但**要打日志**（OPPO 打了 `"image resource is not exist"`） |

---

---

## 8. iOS 互通全流程（独立章节）

> 本节由子代理深挖（`_Temp/afk/notes/ios.md`）。

### 8.1 ⚠️ 工作假设被**部分推翻**

任务简报的假设是：
> "iPhone 不能做 HCE → iOS 配对走 BLE 广播唤醒，这可能是 `IosHostApduService` 没有 AID filter 的原因"

**核验结果**：
- ✅ **"`IosHostApduService` 无 AID filter"是真的** —— `[资源/清单]` `res/QM.xml` 只有 `<host-apdu-service android:requireDeviceUnlock="true"/>`，**没有任何 `<aid-group>`/`<aid-filter>`**
- ❌ **但"因此走 BLE 广播唤醒"是错的。** **AID 是运行时动态注册的**：

`[jadx] com/oplus/pantaconnect/discovery/adapter/nfc/a.java:15,23-83`（= `IosHostApduServiceKt`）：
```java
public static final List f13225a = List.of("D2760000850101", "D2760000850100");  // ★ NFC Forum Type 4 Tag NDEF AID
public static final void a(boolean enable) {
    if (atomicBoolean.compareAndSet(!enable, enable)) {
        NfcAdapter ad = NfcAdapter.getDefaultAdapter(ctx);
        ad.registerAidsForService(componentName, "other", f13225a);   // ★ 运行时注册
    }
}
```

**真正的机制是"角色对调"**：

| 维度 | Android ↔ Android | Android ↔ iPhone |
|---|---|---|
| NFC 角色 | 双方都能做卡 + 做读卡器 | **只有 Android 做卡**（模拟 NFC Forum Type 4 NDEF 标签），**iPhone 做读卡器**（iOS 后台读标签） |
| AID | `F000004F50504F2E434F4D`（`F00000` + ASCII `OPPO.COM`），**静态声明** | `D2760000850101` / `D2760000850100`（**NFC Forum 标准 Type 4 Tag**），**运行时注册** |
| NDEF 内容 | protobuf 风格 serviceData + header | **一条 URI 记录**：`https://connect.oppo.com/oshare/clips/<roleTag>?devId=…&code=…&devType=…&devName=…`（平板为 `…/clips/pad/<roleTag>`）`[smali] IosShareDiscovery$startAdvertise$2:216-221` |
| 文件字节 | OShare | **OShare（同一条栈）**，bundle 带 `isIosNfc=true` |

**为什么这样设计**：iOS **不能做 HCE 卡**（Apple 不开放），但**能读 NFC 标签**（后台读 NDEF 是 iOS 长期支持的能力）。所以 OPPO 让 **Android 当卡、iPhone 当读卡器** —— 这是唯一可行的方向。

### 8.2 ⭐ 最精彩的一步：把"被读"回灌为本地事件

Android 侧怎么知道"碰上了"？**不是"我读到了 iPhone"，而是"我发布的 NDEF 被读走了"。**

`[jadx] IosHostApduService.java:76-78`（READ BINARY 分支）：
```java
NdefMessage ndefMessage = new NdefMessage(bArrD);
protocol.c.a().c(ndefMessage, DeviceType.IPHONE, NfcMode.READER);   // ★ postNdefMessage
return concat(ndefMessage.toByteArray(), "9000");
```

`[jadx] OplusNfcProtocolImpl.c(...)`（`e.java:300-358`）：
```java
if (deviceType == IPHONE && nfcMode == READER) {
    var a = new di.a(IPHONE, READER, new NfcPublishData(0, hex(ndefMessage.toByteArray())));
    f13430j = launch { postNdefMessage$1$1(a) };    // 异步投递给订阅者
}
```

→ **HCE 服务自己扮演了"读卡器"的角色**，把被读走的 NDEF 当作"发现了对端"事件投递出去。
→ 这解释了为什么 iOS 路径**不需要 vendor NFC 的读卡能力**——它只需要 HCE。

**`processCommandApdu` 是一套完整的 Type 4 Tag 模拟** `[jadx] IosHostApduService.java:41-93`：

| 收到 APDU | 回包 | 语义 |
|---|---|---|
| `00A40400 <Lc> <AID>`（AID 在表内） | `9000` | SELECT NDEF Tag Application |
| `00A4000C02E103` | `9000` | SELECT CC file (E103) |
| `00B000000F` | `000F2000FA00FA0406E1043FFF00009000` | READ BINARY = **Capability Container**：CCLEN=0x000F, mapping ver=0x20, MLe=250, MLc=250, TLV=`04 06 E104 3FFF 00 00`（file id=E104, max NDEF=16383） |
| `00A4000C02E104` | `9000` | SELECT NDEF file (E104) |
| `00B0000002` | `00 <len_hi>` + `9000` | READ BINARY = NDEF 长度 |
| `00B0****…` | NDEF 字节 + `9000` | READ BINARY = NDEF 内容 |
| 其他 | `FF9000` | 非法 |

→ **对我们的含义**：如果我们要支持 iPhone，**这一段（Type 4 Tag 模拟 + 回灌）是必须自己实现的**，但它是**完全标准化的**（NFC Forum Type 4 Tag + NDEF），有公开规范可循。**这是整个 iOS 支持里最"有章可循"的部分。**

### 8.3 iOS 配对全流程（时序）

```
Android（做卡 + BLE 检测）                                  iPhone（做读卡器）

[1] 用户贴一下
     │
     ├─ IosShareDiscovery.start()
     │    └─ startAdvertise()
     │         → OplusNfcProtocolImpl.f(NfcPublishSettings[IPHONE], NfcPublishData)
     │         → setVendorNfcShareMode(true)              ← ★ 反射厂商 HAL
     │         → registerAidsForService("other", [D2760000850101, D2760000850100])
     │
[2] iPhone 后台读到 NDEF ──────────────────────────────────► 读出 URI:
     │                                                       https://connect.oppo.com/oshare/clips/<roleTag>?devId=…&code=…&devType=…&devName=…
     │                                                          │
     │                                                          ▼
     │                                                    iOS 通过 universal link
     │                                                    拉起「O+ Connect」App
     │                                                          │
[3] ◄─── Android 侧「被读」回执 ─────────────────────────────  │
     │      processCommandApdu 返 NDEF 的同时
     │      postNdefMessage(IPHONE, READER)
     │      → 触发 discovery 回调 → onAgentFound()
     │      → 解析 NDEF 里的 code → 建 s7.a → TapShareManager.f(...)
     │      → IosNfcPairTask
     │
[4] Android 侧 BLE 邻近检测（"ios_detection" service）
     │  → 决定"我是收方还是发方"（角色仲裁）
     │
[5] SenderIosSharePairing (pair/y)  ↔  ReceiverIosSharePairing (pair/w)
     │  超时/异常见 §8.5
     │
[6] 文件传输：com.coloros.oshare / com.oneplus.oshare（同一条 ITransferService AIDL）
     bundle 带 isIosNfc=true
```

**角色标签**：NDEF URI 里的 `<roleTag>` 决定谁是发送方（Android 与 iPhone 各自发布带不同 roleTag 的 URI）。

**另一条平台事件**：`[jadx] e.java:60-83` 注册进程内广播接收 `com.oplus.nfc.action.TAG_DISCONNECTED`（权限 `com.oplus.permission.safe.CONNECTIVITY`）→ `postAway()`。`OplusHostApduService.onDeactivated()` 也直接调 `protocol.c.a().g()`。
→ 平台（vendor NFC 服务）把"标签离开"通过广播通知 App。**这是我们要在 ROM 里提供的第二个 vendor NFC 接口。**

### 8.4 BLE 唤醒路径 + ANCS / HFP 绑定（`BoundIPhone`）

#### 8.4.1 BLE 唤醒的 UUID 与负载（已确证）

**`IPHONE_AWAKE_OSHARE_BLE_ADV_SERVICE_UUID` = `00007871-0000-1000-8000-00805F9B34FB`** `[smali]`
（全树 3 处编译期常量：`BleDiscover$restartDiscovery$1.smali:412`、`scan/d.smali:186`、`v3/c.smali:1024` —— **无运行时来源**）

- **iPhone 广播、Android 只扫不广播**（方向是 iPhone 主动宣告"我醒着"）
- Android 只在"**熄屏 + 未 doze + `isBackgroundScanSupport`**（= RUS `oaf_rus_enable_background_scan`，**默认 false**）"时才额外加入这个扫描过滤器 `[jadx]`
- **负载藏在 AD type `0x09`（Complete Local Name）**：`'!' | version | '!' | deviceId(6B) | '!' | accountHash(6B) | … | isAccPair` `[推断]`（索引区间依赖 `bo.g(3,8,1)` 的含端点语义）

**唤醒链路** `[jadx]`：
```
iPhone 广播 adv svc 0x7871 + AD 0x09
   ↓
Android v3.c.M() 分类 → s0(OPhoneAwakeOShare…) → 账号校验
   ↓ [isAccPair=1]
反启播 acc-pair（120 秒）
   ↓
call content://com.oplus.oshare.nfc "ios_background_share"
   + bindService(…oshare.ios.background.BackgroundBleService)
```

**距离检测用平台测距** `[jadx]`：protobuf `ServiceInfo.distance`(#8) ← `OplusBluetoothRssiDetectCallback`；**阈值硬编码 1.0 m / 10.0 m**，4 秒超时 → `TIME_OUT`。服务名 `ios_detection`，**≤1.0 m = NEAR → 本机角色判定为 RECEIVER**。

#### 8.4.2 ANCS / HFP 绑定

**为什么要绑 iPhone**：BLE 的 ANCS（Apple Notification Center Service）与经典蓝牙 HFP（Hands-Free Profile）是 Android 侧**感知 iPhone 存在**的既有通道。OPPO 复用它们做"iPhone 是否在附近 / 是否已配对"的判断，避免自己维护一套 BLE 心跳。

| 项 | 值 / 行为 |
|---|---|
| `BoundIPhone` 数据结构 | `{device_list:[{mac, name, share_type, device_model, device_id, is_active, private_mac}]}` **不落盘** —— 从 `com.oplus.linker` 的 `content://com.oplus.linker.ios.client.connection.core` / `get_bound_iphone` 拉 JSON `[jadx]` |
| `ANCS_DISCONNECT_REASON_UNBIND_IPHONE` | = **errorCode 1445 ("iPhone unbind.")**；唯一来源 = OPush 推送 `"unbindIos"`；**只用于埋点**（`ancs_disconnect_event`）`[jadx]` |
| `AncsReconnectReason` 四值 | `RECEIVED_HFP_CONNECTED` / `POLLING_HFP_STATUS`（**两个是 HFP**）、"iPhone 反连 GATT"、"扫到 OPhone adv" —— ⚠️ **没有"本机主动发现 ANCS"这一项** `[jadx]` |
| **HFP 的意义** | **HFP 链路已建立 = "iPhone 在附近且已配对"的可观测代理信号**。技术原因：HFP 跑**公开 BR/EDR 地址**（可直接观测），而 **ANCS 必须连可解析私有地址**（需靠 `EXTRA_ASSOCIATE_DEVICE` 关联）`[推断]` |
| 轮询节奏 | 66 s 轮询**仅在掉线期**；HFP 触发给 30 s、其余 10 s `[jadx]` |
| 绑定带来的收益 | 拿到 iPhone 公开+私有 MAC 与 OAF deviceId；**扫描行为改变**（每个 bound iPhone 一条 MAC 级 `ScanFilter`，进熄屏/无感集，Doze 下进 CHRE；**存在 bound iPhone 本身就打开熄屏扫描**）；重连行为改变；自动解绑（bond 丢失 / 登出 / 2h 云端对账 / OPush / SDK）；云端 `scanDeviceHashKey`（`getIosKeyHash`，交给 native `libcpkit`）；iPhone 通知转发。**不影响广播包内容** |

→ **对我们的含义**：ANCS/HFP 复用是**很省力的做法**（Android 已有这套栈），但**它是 iOS 生态特有的**。**不做 iPhone 支持就整节跳过。**

### 8.5 iOS 超时 / 异常

`[jadx]` 汇总：
- `ShareWaitAppShareDataTimeoutException`：**10 秒**（`0x2710`），见 `SenderIosSharePairing$start$2` —— 等相册交数据
- `SenderIosSharePairing.b()` 里有 `resumeShare, isReShare case, ignore` —— **`isReShare` 时短路**
- 其余超时与 Android 链共用契约链的超时值

### 8.6 用户引导态（真实字符串值）

**这是本节最有产品价值的部分**——OPPO 用**一整套字符串资源**表达了 iOS 支持的所有状态机分支。

#### 主文案族 `nfc_setting_guide_des_phone_to_iphone_*`

| 资源名 | 值（zh-rCN） | 条件 |
|---|---|---|
| `..._all_no_doc_new` | iPhone 安装"%1$s"后，通过 NFC 触碰可与本设备互传图片、视频等文件。%2$s | **当前实际使用**：`%1$s`=App 名，`%2$s`="如何安装？"链接 |
| `..._not_install_no_doc_new` | iPhone 安装"%1$s"后…互传图片、视频等文件 | **设置页默认展示**（**无**链接版） |
| `..._all_1` | …互传图片、视频、**文档**等文件。%s | 该 ROM **有**文档 App |
| `..._all_no_doc` | …互传图片、视频等文件。%s | 该 ROM **无**文档 App |
| `..._two` | **"如何安装？"** | **就是链接文本本身**，被 format 进上面各条的占位符 |
| `nfc_setting_guide_title_phone_to_iphone_1` | 与 iPhone 互传 | 标题 |

**`all` vs `not_install` 的真正区别**（`[smali] NfcGuideSettingPreference:1172-1240`）：
```
base = getString(..._not_install_no_doc_new)                    // 无链接
if (deviceType ∈ 支持iOS集合 && l.f() &&
    resolveActivity(com.oplus.linker/.synergy.ui.activity.OConnectDetailActivity) != null) {
    text = format(getString(..._all_no_doc_new), l.b(ctx) /*App名*/,
                  getString(..._two) /*"如何安装？"*/)             // ← 换成带链接版
}
```
→ **能跳到 O+ 互联详情页 → 用 `_all_*` 并插入可点击链接；否则用 `_not_install_*` 裸文案。**
→ 后缀含义：`_no_doc` = 无文档 App；`_1` = 列举文件类型的变体；`_new` = 用 `%1$s` 占位**品牌名**（海外 "O+ Connect"、国内 "O+互联"）。

#### "操作说明"弹窗 `operate_des_switch_des_phone_to_iphone_*`

```
title = "..._title_1"  = "与 iPhone 互传需确保："
line1 = "..._one_new"  = "1. 对方设备为 iPhone 12 或以上，系统为 iOS 15.0 及以上，安装"%s""
line2 = "..._two"      = "2. 双方设备开启蓝牙和 NFC，本设备需开启一碰互联"
line3 = "..._three"    = "3. 发起方处于相册、文档、文件管理等应用内"
icon  = R.mipmap.iphonetoshare
```
平板版另有 `pad_operate_des_switch_des_pad_to_iphone_two/three`（"平板连接具有 NFC 功能的官方键盘"）—— **说明 iPad 的 NFC 在键盘上**。
还有 **iPhone→平板** 方向：`operate_des_switch_des_iphone_to_pad_des_1/_4`。

#### 帮助页 `ios_share_*`

```
ios_share_title          = "如何与 iPhone 互传？"
ios_share_mode_first     = "方式一：iPhone 触碰平板键盘的 NFC 区域即可互传"
ios_share_mode_second    = "方式二：iPhone 安装并打开 "O+互联" 应用。%s"
ios_share_introduce_domestic_fix = "使用此功能，手机需升级至 ColorOS 15.0 或 iOS 15.0 或以上并开启 NFC、WLAN、及蓝牙，iPhone 需安装"O+互联"。具体支持设备，可查看%s。"
```

#### 场景能力说明（含 iOS 支持/不支持分支）

```
support_scene_des_support_ios_oppo      = 支持具有"一碰互联"功能的 OPPO 设备，以及安装"O+互联"的 iPhone，详情请查看右上角操作说明
support_scene_des_no_support_ios*       = 只提 OPPO/一加/realme，不提 iPhone（未支持 iOS 的机型）
support_scene_photo_no_support_ios      = 在大图查看页、图片列表页（勾选状态）或分享页，与支持的设备一碰即可分享
support_scene_zfb_des_not_iphone        = …支付宝碰一碰（**明确不支持 iPhone**，见 support_scene_zfb_no_support_ios）
```

### 8.7 硬件/系统门槛（从文案反推）

从上面字符串可以直接读出 OPPO 的**兼容矩阵**：

| 项 | 要求 |
|---|---|
| iPhone 型号 | **iPhone 12 或以上** |
| iPhone 系统 | **iOS 15.0 或以上** |
| iPhone 必装 App | **"O+ Connect"（海外）/ "O+互联"（国内）** |
| Android 系统 | **ColorOS 15.0 或以上** |
| Android 开关 | 蓝牙 + WLAN + NFC + **"一碰互联"** |
| iPad 特殊要求 | 平板 NFC 在**官方键盘**上 |
| 发起方位置 | 必须处于**相册 / 文档 / 文件管理**等应用内 |

→ **对我们的启发**：**把兼容性要求直接写进用户可见文案**（而不是放在帮助文档里），是很实际的做法——用户碰到问题第一眼就能自我排查。

---

---

## 9. 组件与包全景（表）

> 本节由子代理产出（`_Temp/afk/notes/inventory.md`，1028 行，12 章）。

### 9.1 关键路径组件（103 个组件中只有少数真正重要）

**导出的关键路径 6 个**：

| 组件 | 作用 | 保护 |
|---|---|---|
| `OplusHostApduService` | **HCE 发卡侧**（对 Android），AID `F000004F50504F2E434F4D`，category `other`，`requireDeviceUnlock=true` | `android.permission.BIND_NFC_SERVICE` |
| `IosHostApduService` | **HCE 发卡侧**（对 iOS），`res/QM.xml` **无 aid-filter**，运行时 `registerAidsForService(comp,"other",["D2760000850101","D2760000850100"])` | 同上 |
| `NfcDiscoveryService` | **Reader 侧**，action `com.oplus.nfc.action.DIRECT_DISCOVERED` | `com.oplus.permission.safe.CONNECTIVITY` |
| `NfcContentProvider` | authority `com.heytap.accessory.nfc.NfcContentProvider`，**全部逻辑在 `call()`**：`notifyNfcNear`/`notifyNfcAway`/`notifyAndGetApduData` | `com.oplus.permission.safe.CONNECTIVITY` |
| `InterconnectService` | **唯一 RPC 入口** `IInterConnIpcInterface.request(module, method, byte[], Bundle)`，**入口先做 OCS 鉴权** | `com.oplus.interconnect.permission.SERVICE`（normal） |
| `TapShareAgentService` | ⚠️ **无 AIDL，`onBind` 返 null**，纯 `onStartCommand` 读 `getByteArrayExtra("wakeup")` | 同上 |

**非导出的关键路径 4 个**：`InterconnectInitService`（初始化总闸）、`NfcUIService`（动效/确认卡）、`NfcDialogService`、4 个 `transport.*Impl`。

**已省略**（与传图无关）：OAF 插件框架（5 svc + 8 插件 APK）、出海账号/推送/统计栈（~15）、AndroidX 自带（~11）、蓝牙媒体（2）、NAS（6）、连接保活服务（~8）。

### 9.2 权限三段切分（74 条 `uses-permission` + 6 条自研 `permission`）

**(a) 直接声明即可（27 条）** —— 含 `android.permission.NFC`（**AOSP 里是 normal**）、`VIBRATE`、`GET_TASKS`（**也是 normal**）、`QUERY_ALL_PACKAGES`、BLE 三件套 + 位置 + `NEARBY_WIFI_DEVICES` + `POST_NOTIFICATIONS`。

**(b) signature / privileged，必须 priv-app + 平台签名（11 条）**
> 子代理**逐条实测**了 `mtk_x/alps/frameworks/base/core/res/AndroidManifest.xml` 的 `protectionLevel`

| 权限 | protectionLevel | 用途 |
|---|---|---|
| `READ_FRAME_BUFFER` | `signature\|recents` | **截屏兜底** |
| `WRITE_SECURE_SETTINGS` | signature | 写安全设置 |
| `MANAGE_USB` | signature\|privileged | USB |
| `BLUETOOTH_PRIVILEGED` | signature\|privileged | **免弹窗配对** |
| `INTERACT_ACROSS_USERS` | signature | 跨用户 |
| `READ_CLIPBOARD_IN_BACKGROUND` | `signature\|role` | 后台读剪贴板 |
| `READ_WIFI_CREDENTIAL` | signature\|privileged | 读 WiFi 凭据（带外传网络参数） |
| `OVERRIDE_WIFI_CONFIG` | signature | 改 WiFi 配置 |
| `TETHER_PRIVILEGED` | signature | 热点 |
| `LOCAL_MAC_ADDRESS` | signature\|privileged | 读真实 MAC（BT/WiFi 定向连接要用） |
| `BROADCAST_PACKAGE_REMOVED` | signature | |
| `BROADCAST_CLOSE_SYSTEM_DIALOGS` | signature | |
| `REQUEST_INSTALL_PACKAGES` | `signature\|appop` | |
| `SCHEDULE_EXACT_ALARM` | normal(appop) | |

**(c) OPPO 私有权限，我们必须自己定义（20 条 `uses-permission` + 6 条 `permission` 声明）** ⭐

> 子代理**已 grep 验证**：`mtk_x/alps` 与 `sprd_x/idh` 树中**一条都没有** —— 即**这些权限是我们的 ROM 完全缺失的，必须自建**。

核心清单：
```
com.oplus.permission.safe.{CONNECTIVITY, BLUETOOTH, PHONE, WINDOW, APP_MANAGER,
                            POGO_KEYBOARD, SETTINGS, SAU, PERSISTENT, SECURITY,
                            IOT, SETTINGS_SEARCH, UPDATE, READ_COMMON, LOG,
                            AI_APP, PROTECT, ASSISTANT}          ← 18 个 safe.*
oplus.permission.OPLUS_COMPONENT_SAFE
oppo.permission.OPPO_COMPONENT_SAFE
com.oplus.oshare.permission.OSHARE_SWITCH
com.heytap.mcs.permission.RECIEVE_MCS_MESSAGE
com.eg.android.AlipayGphone.permission.NFC_PRE_LAUNCH     ← 支付宝定义，我们不需要
heytap.speechassist.* / com.oplus.metis.factdata.* / com.aiunit.aon.*
```
自研声明 6 条中 **4 条是 normal**：`com.oplus.interconnect.permission.SERVICE`、`com.heytap.accessory.permission.ACCESSORY_FRAMEWORK`、`com.oplus.pantaconnect.permission.CONNECTIVITY`、`...breeno.permission.MULTI_WAKEUP`；其余（`IGNORE_DEVICE` / `NOTIFICATION` / `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`）是 signature。

**额外发现**：代码内另有 **4 条"权限位"**（不是系统权限，走 OCS 位图）：
`com.heytap.accessory.permission.{PREROGATIVE=bit0, PUSH_MESSAGE=bit1, DISCOVERY=bit2, AWAKENABLE=bit3}` `[smali] hi/e.smali:14-17`

### 9.3 私有依赖台账 ⭐ 「我们的等价实现需要提供什么」

| 依赖项 | 它提供什么（具体能力） | 我们的等价实现需要提供什么 |
|---|---|---|
| **`com.vendor.nfc.VendorNfcAdapter`** | 静态 `getVendorNfcAdapter()`；实例 `hasFeature(String):boolean`；`enableNfcShareMode(boolean):boolean` | **NFC HAL 的「分享模式」开关**。`hasFeature` 的参数**全清单只有 `TAP_TO_SHARE_WITH_IOS` 一个**（三重验证：5 个 dex 字符串转储各 1 处 + 全树 smali 反射调用仅 `t7/a.smali:89` + **对原始 APK 裸二进制 grep 只 1 行** + 6 个 `.so` 逐个 grep 全 0）。**缺失即静默降级**（异常被 catch）→ **可以先不做** |
| **`com.coloros.oshare`** | `ITransferService` **11 个事务**、`TransferCallback` **4 个回调**、`FileInfo`(5 字段)、`SharedRecord`(6 String + byte[]) | 绑定 `Intent("com.oplus.oshare.FILE_TRANSFER_SERVICE")` + `setPackage("com.coloros.oshare"\|"com.oneplus.oshare")`；`contentResolver.call("com.oplus.oshare.nfc",…)` 预热。**wire 格式全在 APK 内（27 smali），可字节级复刻；缺的只是服务端** |
| **`com.oplus.ocs.*`** | `AUTH_CODE` 元数据双向；`AuthenticationDb`(Room)；`AuthResult(pkg, uid, pid, byte[] permitBits, int code)` | **调用方鉴权** 4 规则：`uid%10000==1000` → 系统直通(1001)；调用方签名 SHA1 与 `android` 包比对；否则比 `AUTH_CODE`；缓存过期 1003 / 未授权 1006 |
| **`libcpkit.so`（5.5 MB Rust）** ⚠️ | LAN 发现 / 组网 / **传输底座** | **无源码，只能按协议行为反推。这是最大的缺口** |
| **`com.oplus.wrapper.*`**（~30 类 / **249 处引用** / **APK 内 0 定义**） | `WifiManager`(35)、`StatsEvent$Builder`(66)、`SystemProperties`(25)、`Trace`(13)、`BluetoothGatt`(9)、`UEventObserver`(6)… | **编译期硬依赖，第一大宗** —— 我们要么提供同名类，要么改代码 |
| `android.view.OplusWindowManager` | `getVisibleWindowInfo(int)`、`getAllVisibleWindowInfo()`、`getCurrentFocus()`、`getLongshotWindowByTypeForR(int):SurfaceControl` | 窗口枚举（找 SystemUI StatusBar 裁剪）+ 长截图 |
| `com.oplus.app.OplusWindowInfo` | `packageName`/`windowName`/`mFrame`/`type` | 同上 |
| `com.oplus.content.OplusFeatureConfigManager.hasFeature(String)` | **36 处调用 / 15 个文件** | **特性开关总闸** |
| **`com.heytap.addon.*`**（37 smali / **177 处引用**） | ⚠️ **不是外部依赖** —— 是本 APK **就地声明的 OPPO 平台 AIDL 接口副本**（`$Stub`/`$StubR` 两层） | 平台侧 **Service 需自建** |
| **`android.os.HapticPlayer` + `android.os.DynamicEffect`** ⭐ | RichTap 触感播放 | **已实测我们树中无此类** —— 要自己实现（或降级为普通 Vibrator） |
| `com.oplus.metis` | 仅 `content://com.oplus.metis.smart_connect_provider/app_relay`；**APK 内无实现** | 应用接力（非碰传必需） |
| `com.oplus.hardware.cryptoeng.CryptoEngManager` | `cryptoEngCommand(byte[]):byte[]`（17 处） | `vendor-oplus-hardware-cryptoeng-V1.0` HAL |
| 截屏链 | `android.window.ScreenCapture.captureLayers/captureDisplay` + `OplusDisplayManager.getPhysicalDisplayToken(long)` + `containsSecureLayers()` | 前两者 AOSP 有；**第三个需自建** |
| **`android.nfc.action.TAG_DISCONNECTED` / `com.oplus.nfc.action.DIRECT_DISCOVERED`** | 平台 NFC 服务发出的广播 | **要在自研 NFC 系统服务里补发这两个广播**（见 §11） |

### 9.4 第三方 SDK

**有确切版本号的**：`okhttp3 4.9.3.7`（OPPO fork）、`gson 2.10.1`、`protobuf-java 4.31.1`（另有 3 套内嵌 runtime）、`com.oplus.pantaconnect.sdk 1.0.3-beta67ca2ce`、`utrace 1.4.18`、`cardwidget 2000005`、`aiunit.aon.sdk 14.0.16`、`account ucbasic 2.0.16.1`。
**无版本号（被 R8 清空）**：`okio 3.x`、`retrofit2 2.x`、`RxJava2`、`Kotlin/coroutines`、`COUI`、`Tink`、`cpkit` —— ⚠️ **这些一律标 `[未确定]`，不要猜版本。**
**关键路径上**：gson、protobuf、**Tink + Google CryptAuth `securegcm`/`securemessage`（UKey2 握手！）**、kotlinx-coroutines、COUI、Room、COE 动效引擎、RichTap、Lottie。
**无任何第三方崩溃 SDK**（bugly / sentry / umeng / 字节 全树 0 命中）。
**Native 6 个 `.so`（合计 7.4 MB，全部 arm64-v8a）** —— ⚠️ **注意 `_Temp/afk/apk/` 未解出 `lib/`，前序研究完全漏了。**

### 9.5 客户端 SDK 面

| 包 | 类数 | 说明 |
|---|---|---|
| **`com.heytap.accessory.nfc.sdk`** | **14 类** | 真正给第三方/相册用的 SDK。真 AIDL `INfcEventIpcCallback.callback(String,Bundle)`。Parcelize：`NfcEventParcelize(packageName,action,type,count)`、`NfcActionParcelize(...)`、`NfcEventCallbackParcelize(type,result,errorCode,Bundle)`、`NfcFrameworkDataParcelize(nfcMode,needPayload,payload[])`。枚举：`NfcScenes{UNKNOWN=-1,SYSTEM_SHARE=0,GALLERY_SELECT=1,GALLERY_SHARE=2,OSHARE_SHARE=3}`、`NfcDeviceBrand{OPLUS=0,APPLE=1}`、`NfcEventRoleType{SENDER=1,RECEIVER=2}`。RPC 方法表：`registerShareOperationCallback`(idx0)、`registerShareAgent`(idx1)、`isShareAvailable`(idx2)、`startShare`、`resumeShare`、`rejectShare`、`unregisterShareAgent(s)` |
| `com.oplus.interconnect.share.sdk` | 37 类 | 内部共享层（非独立 SDK）。`SharedData`/`SharedRecord`/`MimeType`(12 值) + 15 个卡片模型 |
| `com.oplus.pantaconnect.sdk` | ~201 类 | `Agent`/`Databus`/`Results`(空壳)/`Wakeup`/`PlatformInitialization`；`SdkInitProvider` 自绑定 `com.heytap.accessory` 自己的 `CoreService` |
| `com.heytap.accessory.file.FileTransfer` | — | `send(PeerAgent, String\|Uri[, String]):int` / `receive(...)`；绑定 action `"com.heytap.accessory.IAfFtManager"`（⚠️ 真实 descriptor 是 `com.heytap.accessory.core.IFileManager` / `IStreamManager`）；`ResultReceiver` code 99–103 |

> ⚠️ **纠正前序研究**：`com.heytap.accessory.nfc.sdk.*` **不是"零引用"** —— 子代理实测**被自家引用 20 处**。

### 9.6 其它要点

- `sharedUserId` **无**；`sharedUserMaxSdkVersion` **无**；RRO/overlay **无**；`allowBackup=false`
- **平台密钥存在**：`SupportAppActivity` 带 `OplusPermissionKey` + **`AppPlatformKey`** —— 后者**已 base64 解码出明文授权白名单**（**~29 个隐藏 API 方法名**：`setPairingConfirmation` / `cancelBondProcess` / `createBond` / `getConnectionInfo` / `getWifiApConfiguration` / `setWifiEnabled` / `stopTethering` / `setPCAutonomousGo` / `Settings.Secure` / `setNfcTriggered` / `screenshot` / `getPrivilegedConfiguredNetworks` / `getRunningTasks` / `getRunningAppProcesses` / `IWindowSession` … + `epona`/`tingle` 分组）
- `<queries>` 22 条，**不含相册**（靠 `QUERY_ALL_PACKAGES`）
- `res/xml/` 20 个资源，重点：`oplus_apdu_service`（**唯一 AID**）、`ios_apdu_service`（**无 AID**）、`internal_accessoryservices`（4 个 OAF endpoint，含 urn/channel/timeout）、`nfc_touch_services`、`network_config`（仅 `android.com` 允许明文）、`static_register_list`（订阅 OShare 的 `oshare_switch_state` / `oshare_send_intent`）

> **两处纠正前序研究**：
> 1. **`rule_default.db` 是 61 条规则、覆盖 12 类 App**（不只新闻；还有视频/购物/外卖/出行/社交/浏览器/海外/办公/房产/票务/阅读/日历）
> 2. **`com.heytap.addon.*` 是 37 个 APK 内定义 + 177 处引用的 AIDL 副本**（不是"外部依赖"）；而 **`com.oplus.wrapper.*` 才是真正 0 定义的外部包**

---

---

## 10. 三方 SDK 契约

### 10.1 是否真的有"对外 SDK"

**有，但形态与预期不同。** 子代理核实：

| 包 | 定位 | 依据 |
|---|---|---|
| `com.heytap.accessory.nfc.sdk`（**14 类**） | ✅ **真正的对外 SDK 面**（给相册/文件管理器/OShare 这类合作方 App 用） | 定义了完整的 Parcelize + AIDL 回调 + 场景枚举；**被自家引用 20 处**（服务端实现方）→ 是"同一份接口定义，服务端与客户端各持一侧" |
| `com.oplus.interconnect.share.sdk`（37 类） | 内部共享层，**不是**独立 SDK | `SharedData`/`SharedRecord`/`MimeType` + 15 个卡片模型 |
| `com.oplus.pantaconnect.sdk`（~201 类） | 平台连接 SDK（Agent/Databus/Wakeup） | `SdkInitProvider` 自绑定 `com.heytap.accessory` 自己的 `CoreService` |
| `com.heytap.accessory.file.FileTransfer` | 文件传输门面 | `send(PeerAgent, String\|Uri[, String]):int` / `receive(...)` |

### 10.2 客户端要实现的接口（一个合作方 App 的完整契约面）

```
// 调用方向（App → 框架）
interface IInterConnIpcInterface {
    byte[] request(String module, String method, byte[] args, Bundle extras);
}

// 回调方向（框架 → App）
interface IInterConnIpcCallback {
    boolean callback(String name, byte[] data, Bundle extras);
}

// SDK 侧更强类型的回调（nfc.sdk 包）
interface INfcEventIpcCallback {
    void callback(String /*name*/, Bundle /*data*/);       // 注意签名不同：返回 void、数据在 Bundle
}
```

**Parcelize 数据类**（`nfc.sdk.ipc.data`）：
```
NfcEventParcelize(packageName, action, type, count)                  ← 事件元数据（count = 张数）
NfcActionParcelize(packageName, action, isExecute, localDeviceId, remoteDeviceId)
NfcEventCallbackParcelize(type, result, errorCode, Bundle)
NfcFrameworkDataParcelize(nfcMode, needPayload, payload[])
```
**枚举**：`NfcScenes`、`NfcDeviceBrand{OPLUS=0, APPLE=1}`、`NfcEventRoleType{SENDER=1, RECEIVER=2}`

### 10.3 对我们的含义

- **OPPO 的"SDK"本质是"接口定义 + 长连接回调"**，不是一个封装好的易用 API。合作方要自己处理 AIDL、鉴权、生命周期。
- **如果我们要做等价物**：因为我们**同时拥有框架和数据源**（都是自家代码），可以把这个 AIDL 面**大幅简化**——甚至直接做成进程内接口。
- **但如果未来要开放给第三方 App**，`NfcScenes` / `NfcEventParcelize` / `NfcActionParcelize` 这套**类型化的 Parcelable 契约**是正确形状（**比我们的 `k=v|k=v` 字符串强得多**，见 §11）。

---

## 11. 与我们实现的对照（逐项：它 / 我们 / **该不该跟 + 理由**）

> 我们的实现事实以本仓库源码为准（`app/src/main/java/com/pri/nfctouchdemo/nfc/`），已在本次核对。
> **参考 ≠ 复制。** 下表每一项都给了明确判断。

### 11.1 触发 / 发现层

| 维度 | OPPO | 我们（已核实） | 该不该跟 + 理由 |
|---|---|---|---|
| **HCE AID** | `F000004F50504F2E434F4D` = `F00000` + ASCII `OPPO.COM`（厂商前缀 + 品牌域名） | `F05052494E4643` = `F0 50` + ASCII `PRINFC`（`app/src/main/res/xml/apdu_service.xml`） | ⚠️ **值得改**：OPPO 用了 **`F0` + 厂商号 + ASCII 品牌域名**的可读结构，便于人肉识别/抓包定位。我们的 `F0 50 "PRINFC"` 已经接近这个思路，**可读性可以，保留即可**（换不换无所谓，不构成技术差异） |
| **`requireDeviceUnlock`** | **`true`**（锁屏不可用） | **`false`**（`apdu_service.xml`） | ✅ **我们对**：锁屏可传是体验优势。OPPO 设 `true` 是安全考虑（防锁屏下被读），但**代价是"锁屏时碰一碰无效"**。**建议保持 `false`**，把安全约束放到应用层（用户确认） |
| **读卡侧** | `NfcDiscoveryService`，由系统 NFC 服务发 `com.oplus.nfc.action.DIRECT_DISCOVERED` 广播驱动；**纯 trampoline（起协程后 `stopSelf`）** | `TECH_DISCOVERED` intent-filter + `TapDispatchActivity` trampoline（`tech_list.xml` 只声明 `IsoDep`） | ✅ **思路一致（trampoline）**，但我们**没有改系统 NFC 服务**——我们用的是公开的 `TECH_DISCOVERED`。**这个选择更聪明**：`DIRECT_DISCOVERED` 是 OPPO 在自家 NFC 服务里加的私有广播，**我们要复刻就得改系统 NFC**。**保持 `TECH_DISCOVERED`，不要为了"像 OPPO"去改系统服务**。⚠️ 唯一差别：`TECH_DISCOVERED` 会被**所有** IsoDep 标签触发，需要自己过滤 AID |
| **厂商 NFC 反射能力** | `com.vendor.nfc.VendorNfcAdapter.hasFeature("TAP_TO_SHARE_WITH_IOS")` + `enableNfcShareMode(bool)` | ❌ 无 | ⚠️ **看是否做 iOS**：`hasFeature` 缺失即静默降级，**不做 iOS 的话可以先不做**；做 iOS 就必须提供 `enableNfcShareMode` |
| **iOS 支持** | 完整子系统（角色对调：Android 当卡、iPhone 读卡器 + NDEF URI universal link 拉起 App） | ❌ 无 | ❌ **暂不跟**：成本极高（要在 iPhone 上有 App、要 universal link 域名、要 BLE 邻近检测）。**除非产品明确要求** |

### 11.2 协议 / 编排层（**最值得改的部分**）

| 维度 | OPPO | 我们（已核实） | 该不该跟 + 理由 |
|---|---|---|---|
| **编排模型** | **契约链**：有序的契约对象数组，每步独立超时（6/6/10/23/6/6 s），超时后按"当前卡在哪一步"翻译成专属异常（`NfcPairInitTimeoutException` / `NfcDataSyncTimeoutException` / `NfcUserCancelException` / `NfcUserRejectException` / `NfcKscNegotiationTimeoutException` / `NfcConnectAccessoryTimeoutException`） | **隐式状态机 + 单一超时**（`Xfer.java` 内有 `20000` / `3000L + mRetry * 2000L` 等散落的超时常量） | ✅✅ **强烈建议跟**。这是**单项收益最大**的改动。同样的交互，契约链让"哪一步失败"可诊断、可统计、可差异化处理。**最低成本做法**：不引入契约类，但**把两个大阶段拆成具名步骤 + 每步独立超时 + 专属错误码** |
| **超时分级** | **机器步骤 6 s / 人机交互 10–23 s / 双人确认 65 s / 真实传输 24 h** | 单一超时（约 20 s），重试间隔 `3000 + mRetry*2000` ms | ✅ **跟**。分级的意义是"**等人和等机器的耐心不一样**"。我们的 20 s 对"等用户点接受"太短、对"等握手"太长 |
| **角色协商** | **协议协商**：`NfcPairRole{SEEKER, PROVIDER}` + `InternalNfcEventRole{SENDER, RECEIVER}`，在 `NfcDataSyncContract` 里交换并裁决 | **谁先滑动谁是发送方**（`Xfer.java:131` `mCanSend` = "持有照片且还没滑过，仍可能成为发送方"；`:449` "Swipe declares the sender role"） | ❌ **别跟我们的，改跟 OPPO 的**。我们靠"先滑"是**竞态**：两人同时滑动时行为不确定；且"持有照片"这个条件无法表达"我想传但还没选好照片" → 我们的 `mCanSend` 已经暴露了这个补丁。**OPPO 的显式角色交换消除了歧义** |
| **帧格式** | `[1 字节报文类型][protobuf 载荷]`；类型值**故意选可打印 ASCII 区**（111–116 = `o p q r s t`） | `[MAGIC(4B)][TYPE(4B)][LEN(4B)][data]`（`Xfer.java:823`） | ✅ **各有优点**：我们的 `LEN` 字段更明确（OPPO 靠 protobuf 自带长度）；**OPPO 的"类型放可打印区"值得学**（抓包 `tcpdump` 能直接肉眼认）。**建议**：保留 `LEN`，**把 `TYPE` 改成可打印 ASCII**（如 `'M'`,`'A'`,`'R'`,`'F'` 对应 meta/accept/reject/full） |
| **载荷** | **protobuf**（`fd/m0.fileCount` 等强类型字段）+ 双份序列化（`share_data` protobuf + `share_data_json`） | **200 字节 `k=v\|k=v` 字符串**（`Bus.java:41` `MAX_PAYLOAD = 200`），**超过静默截断**（`Bus.java:98-102`，只打日志） | ❌❌ **必须改**。**静默截断是真实的 bug**：`Bus.log("payload 超长已截断")` 只进日志，对端拿到的是残缺数据却**无法察觉**。Android 的 `TECH_DISCOVERED`/`IsoDep` 单帧确实有限（这是 OPPO 也只传元数据的原因），但**至少要做到：(a) 超长时报错而非截断；(b) 用带长度前缀的结构化格式而非 `k=v\|` 拼接**（`k=v` 里若含 `\|` 或 `=` 就会解析错乱） |
| **报文语义** | 18 个具名报文类型（见 §3.6 全表） | 5 个 TYPE 常量（FULL/META/ACCEPT/REJECT + MAGIC） | ✅ **短期够用**：我们的两阶段（预告 → 裁决 → 交付）**语义上是正确的**，和 OPPO 的"确认两次"模型一致。**不建议为了凑数加报文**；等需要"重试/取消/进度"时再按需扩展 |

### 11.3 传输层

| 维度 | OPPO | 我们 | 该不该跟 + 理由 |
|---|---|---|---|
| **承载** | **BT-SPP**（`ConnectionType.SPP_INSECURE`）+ SCP 协议；另有 Wi-Fi P2P 兜底（未证实） | **Wi-Fi P2P / 热点 + TCP 8988**（`Xfer.java:53`），`InetSocketAddress(gw, PORT)` | ✅ **各自合理，取决于场景**。OPPO 用 BT-SPP 是因为**碰一碰的场景是"手机贴手机"**（近场），BT 配对快、功耗低；我们用 Wi-Fi P2P 是因为**要传大图**（BT-SPP 带宽 ~100–200 KB/s，一张 5 MB 照片要 30 s+）。**结论：传图片用 Wi-Fi 是对的；但可以学 OPPO 的"BT 作为连接建立的引导"** |
| **连接参数传递** | 跨 NFC 边界传 `(对端蓝牙地址, 对端协议设备ID, KSC, 本机设备ID)` → **BT 无感直连**（`connect bt senseless BT accessory`） | 经 APDU 传 `k=v\|k=v` 字符串（含 `lanIpv4` / `p2pName` / `p2pAddr` / 热点 SSID/密码） | ⚠️ **思路一致（都把连接参数塞进 NFC）**，但**我们的安全性弱**：OPPO 传 KSC 用于后续认证；我们传明文 WiFi 密码。**见下条** |
| **进度回调** | `TransferCallback.onPercentChange(int, long)` → UI 进度条 | 有（`Xfer` 内有进度相关逻辑） | ✅ **都有**，无需改 |
| **失败/重试** | 失败即整体失败，**重试交给用户（再碰一下）**；无显式自动重试（`[未确定]`，未找到重试循环） | `long delay = 3000L + mRetry * 2000L`（**有自动重试 + 退避**） | ✅ **我们有而 OPPO 没有，这是优点**。近场场景"再碰一下"成本低，但**自动退避重试体验更好**（用户不用动）。**保留我们的重试**，只是**要把重试次数/上限显式化**（当前 `mRetry` 无上限？需确认） |
| **失败可诊断性** | 6 类配对异常 + 7 个 `TransferConfirmResult` 枚举值，**能区分"接收端授权失败"**（`RECEIVER_TRANSFER_PERMISSION_GRANT_FAIL`） | 通用失败 + banner 文案 | ⚠️ **跟**：`RECEIVER_TRANSFER_PERMISSION_GRANT_FAIL` 这类**细粒度失败原因**能让用户知道"是权限问题"而不是"网络问题"。**我们的 overlay_fail_banner 应该区分原因** |

### 11.4 安全层（**我们完全缺失，差距最大**）

| 维度 | OPPO | 我们 | 该不该跟 + 理由 |
|---|---|---|---|
| **密钥协商** | **UKey2**（Google 的 ECDH P-256 + SHA-512 + HKDF）或 OobKey（同账号） | ❌ **无** | ✅✅ **必须跟**。我们传的是**明文 WiFi 密码和 IP**，任何在 NFC 场内的人都能读走。**最低成本方案**：用 Android 自带的 `KeyAgreement("ECDH")` + `HKDF`（20 行代码）做一次性会话密钥，把 payload 加密 |
| **长期凭证** | **KSC**（16 字节）持久化在 `ksc_info`，按 `(deviceId, alias)` 建唯一索引；重连时 HMAC-SHA512 挑战-应答免协商 | ❌ **无**（每次都是全新一次） | ✅ **跟**。这是"老用户碰一下就传完"的根本原因。**实现成本低**（一张表 + 一次 HMAC） |
| **用户身份验证** | ❌ **没有** —— UK2 的 2 字节验证串被**显式丢弃**（`zk/a.smali` 6 参构造只存 5 个） | ❌ 没有 | ✅ **反着跟**：**我们该把验证串用起来**。屏幕上显示 2 个数字/词，成本极低，能挡中间人。**这是 OPPO 做错的地方，不要学** |
| **同账号自动放行** | 有，但**只比对 accountHash 的前 3 字节**（24 bit，碰撞 1/1677 万） | ❌ 无 | ⚠️ **跟功能，改实现**：同账号免确认是好的 UX；但**必须比对完整 hash**。3 字节是**真实的攻击面** |
| **传输加密** | 应用层 AES-GCM-128（IV 取 `bytes[16..28]`）+ HMAC-SHA512 挑战 | ❌ 无 | ✅ **必须跟** |
| **密钥存储** | `ksc.db`（Room v1），AES/CBC/NoPadding + **固定 keystore 别名 `ksc_key_alias`**（全设备共用一把） | ❌ 无 | ⚠️ **跟思路，换算法**：AES-GCM 一步到位；**另外建议 per-device 派生密钥**而非全设备一把 |
| **配对模式标志** | `ACCESSORY_INSECURE_NFC_SHARE = 2`（三档模式：0=普通/1=NFC互联/2=NFC分享） | ❌ 无 | ✅ **跟思路**：用"模式"而非布尔开关表达安全档位，可扩展 |

### 11.5 数据模型

| 维度 | OPPO | 我们 | 该不该跟 + 理由 |
|---|---|---|---|
| **内容载体** | `List<SharedRecord{type, content, uri, extraData}>`（**列表**，天然支持多选） | 多张图，逐张 `TYPE_FULL` | ✅ **各有各的**：我们的"逐张 TYPE_FULL"对**流式传输/渐进显示**更友好（收到一张显示一张）；OPPO 的列表适合"先给元数据再传"。**建议：两者结合**——`TYPE_META` 先给 `count`（我们已有），再逐张 `TYPE_FULL`（我们已有） |
| **数量协商** | `count = records.size()` 放在 `fd/m0.fileCount`，**接收端不校验**（OPPO 的缺陷） | `TYPE_META` 的 `len` 字段即张数（`Xfer.java:60`），接收端有 `mExpect`（`:123`）**做校验** | ✅ **我们做对了**（有 `mExpect` 校验）。**保留**。OPPO 不校验是缺陷 |
| **预览图** | `CompatSharedCard.thumbnail`（**Bitmap**，源 App 提供）+ `ShareResourceManagerImpl` 兜底解码第一张 | 有预览（两阶段里的 offer 阶段） | ✅ **跟**：**让数据源提供预览 Bitmap** 而不是框架自己解码——这样预览能和用户实际选中的内容一致 |
| **接收端落地** | Intent `com.oplus.interconnect.action.SHARED_DATA`，双份 extra（protobuf + JSON），`resolveActivity` 门控，**框架不落盘**（OShare 落盘） | 我们自己的落盘逻辑 | ✅ **跟"框架不落盘"的思路**：职责分离（传输层只管搬字节，落地是接收 App 的事）。**但我们要自己做落盘**（没有 OShare） |

### 11.6 一句话总结

**最该改的三件事**（按收益排序）：
1. **加安全层**（ECDH + 会话密钥 + 可选的 KSC 持久化）—— 我们目前**明文传 WiFi 密码**，这是最严重的问题
2. **拆超时 + 具名失败原因** —— 单项收益最大的工程改进，让"哪一步失败"可诊断
3. **修 200 字节静默截断** —— 这是真实的 bug，且修复成本极低

**最不该改的**：
- 不要为了"像 OPPO"去改系统 NFC 服务加 `DIRECT_DISCOVERED` 广播（我们用 `TECH_DISCOVERED` 更干净）
- 不要做 iOS 支持（成本极高，除非产品明确要求）
- 不要引入 protobuf（对 5 个报文类型是过度设计；**先把类型值改成可打印 ASCII 即可**）

---

## 12. 未确定的部分（诚实清单）

> **宁可承认不知道，也不要给自信的错误答案。** 以下全部未证实。

### 12.1 本次未能确定的（按重要性）

| # | 未确定项 | 已知线索 / 我搜了什么 |
|---|---|---|
| 1 | **`KSC` 缩写的展开** | 全树 grep `KeySharing`/`KeyShare`/`SharedCredential`/`key_sharing` → **0 命中**。代码内只以 `ksc` 出现。`[推断]` 最接近 "Key Sharing Credential" |
| 2 | **`qcqs` 的含义** | 3 处字符串出现，**从未展开** |
| 3 | ~~`IPHONE_AWAKE_OSHARE_BLE_ADV_SERVICE_UUID` 的 UUID 具体值~~ | **✅ 已在收尾阶段确定**：`00007871-0000-1000-8000-00805F9B34FB`（见 §8.4.1）。**仍存疑的是该 UUID 的归属**（命名里 OPhone / O+ 两套并存，Android 侧无构造点） |
| 4 | **`disconnectAccessoryForTapShare` / `isP2PConnectorType` 的分支代码** | 字符串在 dex 里能搜到，但**未定位到具体分支**。`[推断]` 存在"SPP 不通 → Wi-Fi P2P"的备选路径，未证实 |
| 5 | **传输层是否存在自动重试** | **未找到显式重试循环**。观察到的模型是"失败即整体失败 + 用户再碰一次"。但也可能是重试逻辑在 `libcpkit.so` 里（C/Rust 层看不到） |
| 6 | **`keyBasedType` 位掩码 `32` 的语义** | 两张 wire 映射表里都出现，但**没有任何生成器分支对应它** → 出现必然抛 `KeyBasedTypeNotFoundException(103)` |
| 7 | **`activeTriggerMode` 的完整枚举** | 只找到 `=1` 有具名常量 `USER_ACTIVE_TRIGGER_NOFEELING_PAIR`。`[推断]` `0=非主动触发`，`1=主动触发` |
| 8 | **`account.a.j()` 是否走网络** | `[推断]` 是本地账号服务（`AccountIdService`），但 sessionKey 来源未完全展开 |
| 9 | **`com.allawn.cryptography` 的 TEE/attestation/Sigma 由谁实现** | 本 APK 里**只有枚举**，无 `.so`、无 `loadLibrary` |
| 10 | **`zb.a.P`（"Sigma authed need return" 的置位条件）** | 这是一个**真实的"跳过认证"快路径**，置位条件未追到 |
| 11 | **`AuthFalseCount` 达到 5 次后的惩罚动作** | 只确认了计数逻辑（5 分钟窗口、上限 5），**惩罚动作未追到** |
| 12 | **`SharedRecord.extraData` 里到底装什么** | 框架**从不解释**（唯一读取点只是拷贝）。要看相册 APK 才知道 |
| 13 | **`InternalShareRegisterParams.type` 的取值** | 框架侧**只打日志**、不参与逻辑 `[未确定]` |
| 14 | **`count` 在 APDU 上的精确字节偏移** | `fd/g0` 的 field 3 行格式未展开 |
| 15 | **`packetCode` 硬编码为 14** | 在 confirm 回复里独立观察到 2 次，**意图不明** |
| 16 | **`GAME_INVITATION` 的实际生产者** | 只证明了它**不能**经 `registerEvent` 到达 |
| 17 | **`libcpkit.so`（5.5 MB Rust）的内部协议** | **无源码，只能按行为反推。这是最大的缺口** |
| 18 | **多个库的精确版本号** | `okio`/`retrofit2`/`RxJava2`/`Kotlin`/`coroutines`/`COUI`/`Tink` —— APK 内**均无字符串可证**，一律 `[未确定]`，**不要猜** |
| 19 | **`AppPlatformKey` 的 DER 签名算法与验证主体** | 形状像 ECDSA P-256，但与 `pub_2` 的密钥配对关系未证 |
| 20 | **`hi/d.a` / `hi/d.b` OCS 白名单的运行时填充源** | `<clinit>` 里**只初始化为空**，填充源未追到（ContentProvider？配置文件？） |
| 21 | **`<discovery_rssi>` 与 `ResourceData.version`** | **无任何读取者**（发现了字段但没有消费者） |
| 22 | **`f7341g`（modelId → Build.MODEL 查表）的填充点** | 未定位 |
| 23 | **`_Temp/afk/apk/` 未解出 `lib/`** | 前序研究漏了 6 个 `.so`（7.4 MB）。本次仅从 inventory 子代理得知其存在，**未逐个分析** |

### 12.2 本报告自身的边界

| 项 | 说明 |
|---|---|
| **相册侧代码不在手边** | `com.coloros.gallery3d` 是独立 APK。**"相册是 share agent" 仍是 `[推断]`**（依据：① 白名单只有它；② `NfcScenes` 为它专设两档；③ 框架无媒体读权限；④ 框架无其它取图途径）。**框架→相册的通知链已完整追通**，但"相册收到后做什么"无法从框架侧确认 |
| **视频分析未重做** | §5 的"用户可见步骤"对照了既有 `docs/PRODUCT_VIDEO_ANALYSIS.md` / `docs/ANIM_INVENTORY_VIDEO.md` 的结论，**未重新逐帧核对** |
| **jadx 反编译失败率** | 全量反编译 13869 个类中报错 9910 个。**本文所有 `[jadx]` 证据都来自 `--single-class` 模式**（逐类反编译成功），未受此影响；但**未逐一复核每一个 `[jadx]` 结论的 smali 对应**——只有 load-bearing 的结论做了双重核验 |
| **6 个 `.so` 未分析** | 见 12.1 #23 |
| **`Beacon` 家族只做了浅层梳理** | 确认了类结构、密钥基础设施共享、L2CAP/SPP_INSECURE、Query/Reply 广播模型；**未展开完整契约状态机**（判断为"非手机传图必需"后降低了优先级） |

### 12.3 本次分析中**被修正**的前序结论（汇总）

| 前序结论 | 修正 |
|---|---|
| "六个契约" | **实为两条链共 10 个契约**（配对链 6 + 传输链 4），且顺序此前未确定 |
| `SharedData.card` 是 `CardOptions` | **是 `CompatSharedCard`**（`w7/a`）；`CardOptions` 走另一条路 |
| "它从不查 MediaStore" | **不完整**：`ShareResourceManagerImpl` 会 `query(uri, ["_data"])` 推断类型（但是单条查询，不是枚举） |
| `NfcScenes` 用途未知 | **已解开：只用于打日志，无任何行为分支** |
| `com.heytap.accessory.nfc.sdk` 零引用 | **被自家引用 20 处** |
| `rule_default.db` 全是新闻 App | **61 条规则 / 12 类 App** |
| `com.heytap.addon.*` 是外部依赖 | **是本 APK 内定义的 AIDL 副本（37 文件/177 引用）**；真正的外部包是 `com.oplus.wrapper.*`（0 定义/249 引用） |
| iOS "走 BLE 广播唤醒" | **部分推翻**：机制是 **HCE 角色对调**（Android 当卡、iPhone 读卡器）+ NDEF URI universal link；BLE 用于邻近检测与 OShare 发现 |
| "23 个机型，Pad ×7" | **23 个目录，Pad ×13，另有 NAS ×1**（简报的分布不准） |
| 4 个 `*NfcPairTask` 由 `major_type` 选 | **由 NFC 发现结果子类型 + `NfcDeviceBrand` 选**，非 `major_type` |
| 配对链顺序 | **首次确定**（`vh/d.smali:64-125` 字面量顺序），并纠正"KSC 在 confirm 之前"的直觉——实际在**之后** |
| iOS 的 AID "没有 filter 所以是空壳" | **AID 是运行时动态注册的**（`registerAidsForService`，NFC Forum Type 4 Tag 标准 AID `D2760000850101/100`），且**注册前先反射 `enableNfcShareMode(true)`** |
| `UKey2` 的枚举只有 3 个值（17/18/19） | **实为 4 个**：`MESSAGE_CLIENT_INIT`=**16**（★ jadx 把常量 16 错误折叠成了别的名字，本文表已按 smali `<clinit>` 逐条核对修正，见 §3.6） |
| `ios_tap_share_pkgs` 数组会被消费 | **该数组在本 APK 内只有 R 常量、无运行时消费者**（与 `compat_tap_share_*` 不同） |

---

## 附录

### A. 本次分析产出的证据文件（均在 `_Temp/`，未入仓）

| 文件 | 内容 |
|---|---|
| `_Temp/afk/notes/obf_class_map.tsv` | **2069 个混淆类 → 真名映射**（本次最大资产） |
| `_Temp/afk/notes/obf_map.json` | 上表的原始 JSON（含行号映射） |
| `_Temp/afk/notes/outer_resolved.txt` | 387 个"外层类"的映射 |
| `_Temp/afk/notes/security.md` | 安全层深挖笔记（1449 行） |
| `_Temp/afk/notes/ios.md` | iOS 子系统笔记 |
| `_Temp/afk/notes/transport.md` | 传输层笔记 |
| `_Temp/afk/notes/model_assets.md` | 机型素材机制笔记（904 行） |
| `_Temp/afk/notes/data_model.md` | 数据模型笔记（1339 行） |
| `_Temp/afk/notes/inventory.md` | 组件/权限/依赖清单（1028 行） |
| `_Temp/afk/jadx_out/` | jadx 全量反编译产物 |
| `_Temp/afk/jadx_one/` | jadx 单类模式产物（**关键契约类的唯一可读 Java 来源**） |
| `_Temp/afk/smali_classes*/` | baksmali 全量反汇编 |

### B. 关键类速查（混淆名 ↔ 真名）

```
配对链：
  pair/contract/h  → NfcPairInitContract          pair/contract/g  → NfcDataSyncContract
  pair/contract/k  → NfcSenderConfirmContract     pair/contract/i  → NfcReceiverConfirmContract
  pair/contract/b  → KscNegotiationContract       pair/contract/d  → NfcConnectAccessoryContract
  pair/contract/e  → 契约基类接口                  pair/contract/l  → PairOption
  pair/l           → NfcDataContractProxy（驱动器）  vh/d           → 契约链工厂（b() 定义顺序）

传输链：
  share/contract/b → ReadyConfirmContract         share/contract/d → SenderConfirmContract
  share/contract/c → ReceiverConfirmContract      share/contract/e → TransferConfirmContract
  share/contract/f → WatchReadyConfirmContract    share/contract/h → WatchSenderConfirmContract
  share/contract/g → WatchReceiverConfirmContract share/contract/i → WatchTransferConfirmContract
  share/g          → NfcShareContractProxy（驱动器，packed-switch 选链）
  share/contract/a → 契约基类接口

安全：
  pair/contract/keygenerator/b → OobKeyGenerator      pair/contract/keygenerator/d → UKey2KeyGenerator
  pair/contract/ksc/a          → KscGeneratorImpl     mi/i → EncryptedKscInfo（Room 实体）
  securegcm/a                  → Ukey2Handshake（★ 被改名）

其它：
  pair/d   → ClientNfcPairingV3      pair/a0 → ServerNfcPairingV3
  pair/z   → ServerNfcPairing        pair/b  → ClientNfcPairing     pair/c0 → WatchClientNfcPairing
  pair/g   → CompatSharedDataTransmitter
  processor/e → RealShareServer      share/d → NfcShare            share/n → WatchNfcShare
  task/a → IosNfcPairTask   task/c → OplusNfcPairTask   task/d → PadNfcPairTask   task/f → WatchNfcPairTask
  discovery/adapter/nfc/k → WlanModeManager
```

### C. 参考

- 既有第一轮研究：`docs/OPPO_QUICKCONNECT_STUDY.md`（取图机制 / ShareServer RPC / 白名单，本文修正了其中 3 处）
- 产品视频分析：`docs/PRODUCT_VIDEO_ANALYSIS.md`、`docs/ANIM_INVENTORY_VIDEO.md`
- 我们的实现对齐总结：`docs/ALIGNMENT_SUMMARY.md`
- 本次新装工具：`~/.claude/platform-tools/jadx/`（见 §0.3）
