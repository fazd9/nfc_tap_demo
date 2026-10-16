# 调试相册的「查看」态 + 相册直接上报选择（接入契约）

> 产品架构：**自家的相册把"用户正在看/选中的照片"直接交给碰一碰服务**。
> 这是 OPPO 互传的实际做法（见 `docs/OPPO_QUICKCONNECT_STUDY.md`：它自己不申请任何媒体权限、
> 没有无障碍服务，相册白名单 `compat_tap_share_image_pkgs` 硬编码自家相册）——
> 第三方相册走不通，能走通的前提是**相册是我们自己的**。
>
> `PriGallery`（正式相册，只有预编译 APK）以后按同一套契约接入；先用
> `Temp/debuggallery`（`com.pri.debuggallery`）把流程跑通。本文记的就是这套契约、
> 清除时机、以及它与"读屏回落"的共存规则。

---

## 一、做了什么

| 侧 | 改动 |
| --- | --- |
| 调试相册 | 新增**「查看一张」态**（点网格里一张照片 → 全屏看这张）；看的那张 = 当前选择；从查看态回网格、退到桌面都会**清掉** |
| 碰一碰服务 | 新增**清除入口**（`ACTION_CLEAR_SELECTION`）；新增"**相册直接上报的选择优先，读屏回落只在没人上报时使用**"规则 |

设计要点一句话：**相册只在"用户正看着它"的这段时间里持有选择**——
选择变了就上报，用户离开（回网格 / 退桌面 / 屏幕灭）就撤掉。
这样服务手里永远不会留着一份"上一刻的选择"，下一碰不会把用户没在看的照片发出去
（这正是之前踩过的坑：残留的 `PENDING` 让碰一碰发了上一张）。

---

## 二、接入契约（`PriGallery` 照抄这一段）

两个 intent，**都用显式组件**发给 `com.pri.nfctouchdemo` 的 `nfc.TapService`
（不走 `<intent-filter>`、不弹选择器）。定义见
`app/src/main/java/com/pri/nfctouchdemo/nfc/TapService.java` 的 `ACTION_CLEAR_SELECTION` 注释。

### 2.1 上报：当前屏幕上是什么，就报什么

```java
// 1..N 张:全屏看一张就是 N=1,多选勾了几张就是几张
ArrayList<Uri> uris = ...;                     // 按"用户看到的顺序"给
Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
i.setType("image/*");
i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
i.setComponent(new ComponentName("com.pri.nfctouchdemo",
        "com.pri.nfctouchdemo.nfc.TapService"));
// 服务要在相册退出后很久才去读这些 Uri;intent 上的一次性授权只跟发送方 Activity 同寿,
// 所以要按 uid 显式授予(否则动效卡片和传输引擎读不到图片)
for (Uri u : uris) {
    grantUriPermission("com.pri.nfctouchdemo", u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
}
context.startService(i);
```

### 2.2 清除：用户不再看着这份选择了

```java
Intent c = new Intent("com.pri.nfcdemo.CLEAR_SELECTION");
c.setComponent(new ComponentName("com.pri.nfctouchdemo",
        "com.pri.nfctouchdemo.nfc.TapService"));
context.startService(c);
```

### 2.3 相册侧的义务（契约的一半）

- **只在"用户正看着"时持有**：选择一变就上报；一离开就清（见 §三）。
- **只在上报过之后才发清除**：没上报过就没有东西可清，也别把服务从后台唤起
  （调试相册用 `mPublished` 这个布尔量守住这一点）。
- **只上报，不改用户的文件**：调试相册只经 MediaStore 读，不删不移。
- 清空选择（勾选全部取消）**也是一种上报**——上报"0 张"等价于清除，
  实现上就是发 §2.2 那条清除 intent。

### 2.4 服务侧的语义

| 收到 | 动作 |
| --- | --- |
| `ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` | `PENDING` = 这些 Uri，`sPublished = true`，落盘 |
| `ACTION_CLEAR_SELECTION` | `PENDING` 清空，`sPublished = false`，落盘 |
| 发送完成（`Xfer.onImageSent`） | 同上清空：这份选择已被消费，不留着 |

`sPublished` 是**进程内**的标志（不落盘）：它表示"手上这份选择是相册刚刚报的"。
进程重启后 `loadPending()` 恢复出来的是一份"服务被杀时留下的旧选择"，
**不算已上报**——这样 Google 相册那条读屏回落的行为与加这个功能之前**逐字节相同**（§四）。

---

## 三、清除时机（调试相册实际选的钩子）

| 钩子 | 为什么是它 |
| --- | --- |
| `onStop()` | **这就是"退到桌面"**（Home / 最近任务 / 切到别的应用 / 熄屏）。选中态、查看态、上报的选择**一起清**，界面上看到什么和服务手里拿着什么不会不一致 |
| `onDestroy()` | 补 `onStop` 覆盖不到的那一段：已经 stop 之后 Activity 才真正销毁（后台按返回、旋转、被回收） |
| 返回键（查看态 → 网格） | 用户明确要求的那个切换点。回网格 = `updateBars()` → 上报"0 张" → 服务清掉 |

**为什么不用 `onPause()`**：它会在"任何东西盖到本窗口上"时触发，而**碰一碰时盖上来一层的
正是发送动效页**——在那一刻清掉，恰好把这一轮要发的照片扔了。实测也确认了：动效层的窗口
（`TYPE_NAVIGATION_BAR_PANEL`）盖上来时，下面的相册**不会** onStop，Activity 仍是
`topResumedActivity`（见 §五.4 的实测）。所以"退到桌面"用 `onStop` 表达，"被悬浮窗盖住"
不触发任何清除——两者正好分开。

**Android 16 的坑**：`targetSdk 36` 默认开启 predictive back，**`onBackPressed()` 根本不会被调用**
（实测：不注册 `OnBackInvokedCallback` 时，查看态按返回直接把整个 Activity 结束掉了，
落到了下层的 Google 相册）。所以调试相册在 `onCreate` 里注册
`getOnBackInvokedDispatcher().registerOnBackInvokedCallback(...)`，`onBackPressed()` 留作旧平台兜底。

**进程被直接杀掉**（force-stop / 低内存）时没有任何生命周期回调，清除发不出去——
这一段由服务侧的语义兜住（§四最后一条）。

---

## 四、与"读屏回落"的共存规则

服务里还有一条**读 Google 相册屏幕**的回落（`nfc/AccessibilityProbeService` +
`nfc/GalleryPickup`，在 `TapService.onPeer` 里），它是第三方相册的唯一角度，**保留**。
两条路按用户拍定的规则共存：

> **相册自己上报的选择赢；什么都没有上报时才走读屏。**

`TapService.onPeer` 里的落地：

```java
if (sPublished) {                       // 有人上报过,且还没撤
    Bus.log("碰一碰: 相册已直接上报当前选择 N 张,不再读屏幕");
    beginPeerRound(payload);            // 直接开这一轮,读屏那段根本不进
    return;
}
if (GalleryPickup.isGalleryForeground()) { ... 原样 ... }   // 只有 Google 相册在前台才会进
beginPeerRound(payload);
```

配套的三处（确保标志不会残留成"假上报"）：

1. 读屏**拿到了**照片 → `sPublished = false`（这是读来的，不是上报的）；
2. 读屏**读到相册但用户没选** → 清空 + `sPublished = false`（原有行为，不动）；
3. **进程重启**后 `sPublished` 一律是 false —— 恢复出来的旧选择**不挡**读屏：
   此时若栈顶是 Google 相册，照旧读屏、照旧以屏幕上的为准
   （实测见 §五.5，这正是"Google 相册行为不变"的关键）。

**为什么读屏的开关不用改**：`GalleryPickup.isGalleryForeground()` 本来就只认
`com.google.android.apps.photos`，"把回落限制在它真正要处理的包"本来就已经做到了，
本次不动它。

**一处顺带的行为变化（有意）**：首页 `HomeActivity` 的「把选中的照片交给一碰互联」
走的也是 `ACTION_SEND_MULTIPLE`，所以它也算"上报"。以前它会被读屏覆盖，现在按规则它赢。
若哪天觉得不对，把 §2.4 的 `sPublished = true` 改成只在"相册发的"才置位即可。

---

## 五、实机验证（Armor X17 / Android 16 / 序列号 333333333333337）

统一用 `P()` 读服务的落盘状态当判据（`PENDING` 的持久化副本），比看 UI 可靠：

```bash
A=333333333333337
P() { adb -s $A shell cat /data/data/com.pri.nfctouchdemo/shared_prefs/tapdemo.xml \
      | tr -d '\n' | sed -n 's/.*name="pending">\([^<]*\).*/\1/p'; }
```

**1. 网格多选照旧**（长按进多选 → 逐个勾）

```bash
adb -s $A shell input motionevent DOWN 122 412; sleep 1.1; adb -s $A shell input motionevent UP 122 412
adb -s $A shell input tap 360 412
adb -s $A shell input tap 598 412
```
```
-- pending: content://media/external/images/media/95|.../94|.../93
I NfcTapDemo: 服务: 收到相册当前选中 1 张照片(等碰一碰)
I NfcTapDemo: 服务: 收到相册当前选中 2 张照片(等碰一碰)
I NfcTapDemo: 服务: 收到相册当前选中 3 张照片(等碰一碰)
```
（`input swipe x y x y 600` 这种"原地长按"不可靠，实测时会退化成点击、直接把查看态打开；
`input motionevent DOWN/UP` 才稳。）

**2. 查看一张 = 上报那一张**

```bash
adb -s $A shell input tap 360 650      # 无勾选时点一张 -> 进查看态
```
```
-- pending: content://media/external/images/media/91
I NfcTapDemo: 服务: 收到相册当前选中 1 张照片(等碰一碰)
```
发送动效页卡片上出现的正是这一张（"上滑发送 1 张照片"）。

**3. 清除：回网格 / 退桌面**

```bash
adb -s $A shell input keyevent 4       # 查看态 -> 网格
```
```
-- pending: []                          # 清掉了
I NfcTapDemo: 服务: 相册撤销了选择,清掉待发的 1 张照片
```
```bash
adb -s $A shell am startservice -n com.pri.nfctouchdemo/.nfc.TapService \
    -a com.pri.nfcdemo.DEBUG --es payload 'n=TestPeer' --es role reader
```
```
I NfcTapDemo: 本机无照片 → 本机是接收候选,等对方上滑     # 没有把上一张发出去
```
退桌面同理（`input keyevent 3`）：`pending` 变空 + 同上日志，再碰一次仍是"本机无照片"。
勾着 3 张时退桌面也一样清（`mSelected` 与上报的选择一起清）。

**4. 动效层盖上来时相册不会被 onStop**（清除时机选 `onStop` 的实测依据）

```bash
adb -s $A shell dumpsys activity activities | grep topResumedActivity
#   topResumedActivity=ActivityRecord{... com.pri.debuggallery/.MainActivity t187}   <- 悬浮窗盖着仍是它
adb -s $A logcat -d -s NfcTapDemo | grep -E "收到相册|撤销|已直接上报"
#   I NfcTapDemo: 服务: 收到相册当前选中 1 张照片(等碰一碰)
#   I NfcTapDemo: 碰一碰: 相册已直接上报当前选择 1 张,不再读屏幕
#   (没有"撤销" —— 这一轮的选择活到了碰一碰之后)
```

**5. Google 相册读屏：加功能前后行为一致**

单张全屏（`isGalleryForeground()` 为真）：

```bash
adb -s $A shell am start -n com.google.android.apps.photos/.home.HomeActivity
adb -s $A shell input tap 120 470
adb -s $A shell am startservice -n com.pri.nfctouchdemo/.nfc.TapService \
    -a com.pri.nfcdemo.DEBUG --es payload 'n=TestPeer' --es role reader
```
```
I NfcTapDemo: 碰一碰: 相册在前台,先拾取用户正在看/选中的照片
I A11yProbe: 相册扫描: 1 个目标 [2026-10-16 17:18@[0,0][720,1640]]
I GalleryPickup: 拾取结束,得到 1 张: [content://media/external/images/media/84]
I NfcTapDemo: 相册拾取: 拿到当前正在看/选中的 1 张照片,碰一碰直接发送
```

进程重启 + 残留旧选择 + Google 相册在前台（`sPublished` 不落盘的直接验证）：

```bash
# 先让调试相册上报一张(media/94),再强杀服务进程
adb -s $A shell am force-stop com.pri.nfctouchdemo
# 强杀会掉无障碍,按 GALLERY_PICKUP_RESULT.md §九 重开
adb -s $A shell settings put secure enabled_accessibility_services \
    com.pri.nfctouchdemo/com.pri.nfctouchdemo.nfc.AccessibilityProbeService
adb -s $A shell settings put secure accessibility_enabled 1
```
```
I GalleryPickup: 拾取结束,得到 1 张: [content://media/external/images/media/84]   # 屏幕上的那张
I NfcTapDemo: 相册拾取: 拿到当前正在看/选中的 1 张照片,碰一碰直接发送            # 不是残留的 94
```

**6. 相册上报优先**

调试相册里打开一张（上报），再模拟碰触：
```
I NfcTapDemo: 碰一碰: 相册已直接上报当前选择 1 张,不再读屏幕
```
——**完全没有** `A11yProbe` / `GalleryPickup` 的行（读屏那段根本没进），
发送动效页上是相册里打开的那张。

---

## 六、还没验的 / 遗留

1. **没有做真正的两机对接传输**。本次只验到"选择进 `PENDING` / 发送动效页把它画出来"
   （卡片上确实是那张照片，说明服务侧读得到相册给的 Uri，授权是有效的）。
   跨机传输引擎本身没改过。
2. **进程被强杀后"相册还开着、用户还在看那张"** 的场景：服务恢复出的旧选择会照发。
   清除钩子拿不到（进程没了），这是设计上接受的缺口（原来 `loadPending` 就是为了
   "别把发送方误判成接收方"才留的）。要收窄的话，相册可以在 `onStart` 时也上报一次
   （等于先清后报），但那会顺带清掉首页「交给一碰互联」推的照片——本次没做。
3. **旋转未测**：旋转会走到 `onStop` → 清除，行为上是"回到网格、选择清空"。
4. **多选态下点一张 = 勾/取消勾**（不是打开查看），与改动前一致；产品化时如果要
   "多选态里也能点开看"，需要另定语义。
5. **`PriGallery` 接入未做**：契约按 §二 抄即可，但那边的 `onStop`/`onDestroy`/返回键
   三个钩子要各自落实（它是预编译 APK，无源码，改动由对方团队负责）。
