# 悬浮窗三键退出机制 + 窗口置顶能力：参考实现研究（超级截屏 LongShotScreen）

> 研究对象：`/dfb/zhuyang/share/mtk_x/pri_project/android/os_app_src/LongShotScreen`（`com.pri.longshotscreen`，"Super Screenshot"）
> 对照工程：本仓库 `com.pri.nfctouchdemo`（platform 签名 + `android.uid.system`，`OverlayService` 整屏悬浮窗，现用 `TYPE_APPLICATION_OVERLAY`）
> 方法：只读研究。`mtk_x` 未做任何改动。所有结论标注三类：**代码明示** / **由代码推断（未真机验证）** / **未找到**。
> 框架侧源码有两份完全相同的副本，本文引用完整树：`/dfb/zhuyang/share/mtk_x/alps/frameworks/base/...`（同一文件在 `pri_project/android/public/frameworks/base/...` 亦有，行号一致）。

---

## 一、结论先行

1. **Home / 最近任务怎么被感知：`TaskStackListener`（"顶层任务变化"监听），不是按键拦截。**
   参考实现注册 `ActivityTaskManager.getService().registerTaskStackListener(this)`，在 `onTaskMovedToFront / onTaskFocusChanged / onTaskDescriptionChanged` 回调里判断"顶层任务换了"，然后自己退场（`stopSelf`）。Home 和最近任务由系统照常处理，悬浮窗只是**观察者**，不抢键、不消费键 —— 这正是"两者功能都照常发生"的原因。**代码明示。**
   消费点：`service/FunnyWindowService.java:211-222`，且**只在 `navigation_mode == 2`（手势导航）时**才退，三键导航时明确忽略（同文件 :219 注释 "Not gesture navigation mode, ignoring task change"）。**代码明示。**

2. **返回键：参考实现里没有任何返回键处理。** 源码与出货 APK 双向验证（第一节 2.4）：`com.pri.longshotscreen` 包内不存在 `onKeyDown/onKeyUp/dispatchKeyEvent/OnKeyListener/OnUnhandledKeyEventListener/OnBackInvoked` 任何引用；本 ROM 框架侧也没有"按返回关掉这类窗口"的机制（第二节 2.2 全树排查）。**"返回只关浮层"在本参考实现中未实现** —— 用户描述的是行为印象，不是这套代码的实现。返回键要"只关浮层"，只能由窗口自己处理（我们已经在做，见 4.1）。

3. **窗口层级：参考实现的整屏浮窗不是 `TYPE_APPLICATION_OVERLAY`，而是 `TYPE_NAVIGATION_BAR_PANEL`（2017，layer 25）；它的浮标窗口用 `TYPE_KEYGUARD_DIALOG`（2009，layer 19）并调 `setTrustedOverlay()`。** 我们现在的 `TYPE_APPLICATION_OVERLAY` 是 layer 11，**层级确实不够**：layer < 17（`TYPE_NOTIFICATION_SHADE`）的窗口会被锁屏隐藏（`WindowState.canBeHiddenByKeyguard()`），这与协调者在真机上看到的 `mPolicyVisibility=false` 完全对应。**代码明示（层级/锁屏规则）；"真机上不会挡住 XX"部分为推断，未真机验证。**

4. **权限：uid 1000 + platform 签名（`sharedUserId=android.uid.system`）+ 以 `/system/priv-app` 内置，就够用**这套窗口类型。参考实现的出货 APK 清单里**没有**声明 `STATUS_BAR_SERVICE` / `INTERNAL_SYSTEM_WINDOW`，而产品上工作正常 —— 产品实证；privapp 白名单只加了 `INTERACT_ACROSS_USERS`。我们的 app 签名身份/uid/内置方式与之一致，**差距只在窗口类型与 targetSdk（我们 37 / 它 36）**。

5. **可照搬**：`TaskStackListener` 模块（原样抄 `TaskStackModel` 的思路）+ 窗口类型升级（`TYPE_APPLICATION_OVERLAY` → `TYPE_KEYGUARD_DIALOG` 或 `TYPE_NAVIGATION_BAR_PANEL`）+ `params.setTrustedOverlay()`。返回键继续由我们窗口自己处理（现状已具备）。

---

## 二、逐项机制

### 2.1 参考实现是什么（是 overlay，不是 Activity）

- 组件：`FloatWindowService`（悬浮球菜单，"超级截屏"入口 `com.freeme.supershot.MainFloatMenu`）+ `FunnyWindowService`（整屏趣味截图/长截图编辑器，`com.freeme.supershot.FunnyShot`），均由 **Service 承载**、在 WindowManager 上 `addView`。`MainActivity` 的 `LAUNCHER` category 被注释掉（`app/src/main/AndroidManifest.xml:21-29`，注释行在 :27），是个空壳。
- 整屏浮窗的挂载：`viewmodel/funny/FunnyWindowHelper.java:173-205`（`manager.addView(mContainer, params)`），容器里塞一个布满全屏的子 View（:195-198 `MATCH_PARENT`）。
- 由 SystemUI / 框架拉起：`PriSystemUI` 的 `PriSuperScreenShotDialog.kt:58`（`FUNNY_SCREENSHOT_ACTION`）、`PriScreenShotTileLegacy.kt:66`；框架侧三指下滑：`alps/vendor/prize/framework/base/services/core/java/com/pri/server/policy/Pri3FingerScreenshotExtImpl.java:159-180`（`goToLongShotScreenService` → `startService(FunnyWindowService)`）。

### 2.2 窗口是怎么创建的（类型 / flags / 挂载）

`viewmodel/funny/FunnyWindowModel.java:74-99`（整屏编辑器窗口）：

```java
79:  int flags = 0x20520 | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
80:  int type  = FrameworkUtils.TYPE_NAVIGATION_BAR_PANEL;
84:  mParams = new WindowManager.LayoutParams(width, height, type, flags, PixelFormat.RGBA_8888);
85:  mParams.gravity = Gravity.START | Gravity.BOTTOM;
86:  mParams.width = mScreenWidth;   // 屏幕像素宽
87:  mParams.height = mScreenHeight; // 屏幕像素高
92:  ... layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
96:  ... mParams.setFitInsetsTypes(0);
```

- `FrameworkUtils.java:33`：`public static int TYPE_NAVIGATION_BAR_PANEL = WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;`（= `FIRST_SYSTEM_WINDOW+24` = 2017，见 `alps/frameworks/base/core/java/android/view/WindowManager.java:2623`）。
- `0x20520` 拆解 = `FLAG_ALT_FOCUSABLE_IM(0x20000) | FLAG_FULLSCREEN(0x400) | FLAG_LAYOUT_IN_SCREEN(0x100) | FLAG_NOT_TOUCH_MODAL(0x20)`。
  **关键：没有 `FLAG_NOT_FOCUSABLE`** → 窗口可获得焦点。而 `FLAG_ALT_FOCUSABLE_IM` 在"可获焦点"时=不抢 IME 目标、**盖在输入法之上**（`WindowManager.java:3065-3081` 原文："If FLAG_NOT_FOCUSABLE is not set, i.e. when the window is focusable, setting this flag prevents this window from becoming the target of the input method ... and will be layered above the input method"）。
- 另一个窗口（悬浮球面板）走的是另一套路，可作为"保守迁移"的模板 —— `viewmodel/floats/FloatWindowModel.java:68-86`：

```java
68:  int flags = FLAG_NOT_TOUCH_MODAL | FLAG_NOT_FOCUSABLE;
73:  int type  = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;   // 原为 TYPE_APPLICATION_OVERLAY，fixbug 69418/AS-909 改掉
78:  mParams = new WindowManager.LayoutParams(width, height, type, flags, PixelFormat.RGBA_8888);
86:  FrameworkUtils.setTrustedOverlay(mParams);
```

- `setTrustedOverlay()`：`utils/FrameworkUtils.java:111-115` → `params.setTrustedOverlay()`（hidden API，`WindowManager.java:5013-5023`，置 `PRIVATE_FLAG_TRUSTED_OVERLAY = 1<<29`，`:3687-3691`）。语义=该窗口不算"遮挡"，输入分发时不按遮挡处理（`@see MotionEvent#FLAG_WINDOW_IS_OBSCURED`）。**代码明示。**

### 2.3 返回键（Back）

- **app 侧：无任何按键处理。** 全仓 `grep -rni "onKeyDown|onKeyUp|KeyEvent|UnhandledKey|OnKeyListener|dispatchKeyEvent|OnBackInvoked"` 在 LongShotScreen 源码中 **0 命中**；出货 APK 反汇编后 `out/com/pri/longshotscreen/**` 同样 0 命中（`baksmali d` → grep `dispatchKeyEvent|onKeyDown|OnKeyUp|OnUnhandledKeyEventListener|OnBackInvoked`）。**出货 APK 字符串统计**：`registerTaskStackListener` 4 处、`TaskStackListener` 6 处、`navigation_mode` 1 处、`getRunningTasks` 1 处；`ACTION_CLOSE_SYSTEM_DIALOGS` **0 处**、`OnBackInvoked` **0 处**。即出货版本确实带 TaskStack 机制、确实不带任何按键机制。
- **框架侧：没有"BACK 关窗口"的兜底。** 本 ROM 的未处理 BACK 链路：`InputManagerService.dispatchUnhandledKey` → `interceptUnhandledKey`（`alps/frameworks/base/services/core/java/com/android/server/input/InputManagerService.java:2907-2918, 2971-2976`）→ `KeyGestureController.interceptUnhandledKey`（`:1181-1183`，只挑 SPACE/Z/SYSRQ/ESCAPE 等组合）→ `PhoneWindowManager.interceptUnhandledKey`（`services/core/java/com/android/server/policy/PhoneWindowManager.java:3961-3985`，**只处理 `KEYCODE_STEM_PRIMARY` 一个键**）→ 结束。`BackNavigationController` 只做返回动画预测，且当焦点窗口没注册 back callback 时直接 `return null`（`services/core/java/com/android/server/wm/BackNavigationController.java:283-286`：`"No callback registered, returning null."`）。全树 `KEYCODE_BACK` 扫描：`services/` 只有 `KeyGestureController` 与 `PhoneWindowManager` 两处；`vendor/prize` 只有指纹助手"发 BACK 键"和游戏空间拦截（`PriPhoneWindowMgrExtImpl.java:769-777`）；`vendor/mediatek/proprietary/frameworks` 只有输入框/时间选择器。**没有一处会在 BACK 时关闭系统窗口。**
- **推断（未真机验证）**：参考机上的编辑器浮窗（focusable、无人消费 BACK）按键表现应是"返回键被投递到浮层后丢弃，下层应用不受影响"。是否真的"浮层消失"无法从源码得到 —— 用户若确认真机上 Back 会关掉编辑器，那必是另有实现（不在本仓库这份源码里），应回问来源。
- **要"返回只关浮层"，本 ROM 可用的三条路**（都不需要无障碍服务）：
  1. 窗口可获焦点 + View 级按键：`content.setOnKeyListener{}` / 覆写容器 `dispatchKeyEvent`。**我们的 `OverlayService.kt:599`（`setOnKeyListener`）+ `:552`（判 `KEYCODE_BACK`）就是这一条，已经实现。**
  2. API 33+：`view.findOnBackInvokedDispatcher().registerOnBackInvokedCallback(...)`。框架对非 Activity 窗口同样提供（`ViewRootImpl.getOnBackInvokedDispatcher()`，`core/java/android/view/ViewRootImpl.java:14182-14194`；`android.window.WindowOnBackInvokedDispatcher` 是给"无 Activity 窗口"用的实现），注册后系统会把 back 交给它，且系统按 z 序找"有 callback 的窗口"，不会把 key 交给下层应用。**代码明示存在，未在本工程验证。**
  3. Activity 的 `onBackPressed` 不适用（服务窗口没有 Activity）。
- **不要考虑 `ACTION_CLOSE_SYSTEM_DIALOGS`**：参考实现没用（出货 APK 0 处命中），我们 targetSdk 37 在 Android 12+ 上该广播对 targetSdk≥31 的应用已受限，而且它**区分不出 Home / 最近任务**。

### 2.4 Home / 最近任务（**核心**）

**机制名：`TaskStackListener`（`android.app.TaskStackListener`）+ `ActivityTaskManager.getService().registerTaskStackListener()`。原理=监听"顶层任务/焦点任务变化"，不是监听按键。**

`viewmodel/funny/TaskStackModel.java`：

```
26: public class TaskStackModel extends TaskStackListener implements IRelease {
58:     ActivityManager.RunningTaskInfo[] tasks = getRunningTasks(false);   // 初始化顺序表
72:     ActivityTaskManager.getService().registerTaskStackListener(this);
83:     ActivityTaskManager.getService().unregisterTaskStackListener(this);
151:    public void onTaskRemoved(int taskId)
158:    public void onTaskFocusChanged(int taskId, boolean focused)      // focused==true → updateFistItem + onTopTaskChanged
182:    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo)  // → updateFistItem
208:    public void onTaskDescriptionChanged(ActivityManager.RunningTaskInfo)
246-261: private void onTopTaskChanged(final String packageName)  // RxJava 切主线程回调 mCallback.onTopTaskChanged(packageName)
```

**消费点（参考实现真正"退场"的地方）**：`service/FunnyWindowService.java`

```
49:  initTaskStackModel();                       // onCreate 里注册
76-79: mTaskStackModel = new TaskStackModel(); mTaskStackModel.init(this, this);
211-222:
212:  public void onTopTaskChanged(String packageName) {
214:      int mode = Settings.Secure.getInt(getContentResolver(), "navigation_mode", -1);
215:      if (mode == 2) {                       // 手势导航
217:          onExit(true);                      // → postExit() → stopSelf()，窗口随服务一并释放
218:      } else {
219:          Logger.d(TAG, "Not gesture navigation mode, ignoring task change");
```

- 触发链（推断，符合系统语义）：按 **Home** → Launcher 任务被置顶；按 **最近任务** → Recents 界面（本 ROM 中由 Launcher3/Quickstep 承载，本质也是 Launcher 任务或 SystemUI 的任务）置顶 → `onTaskMovedToFront` / `onTaskFocusChanged(focused=true)` → `onTopTaskChanged` → 退场。**Home / Recents 自身的功能完全由系统执行，浮层只是旁观后自退** —— 这就是"两键功能照常且浮层消失"的实现。**代码明示（监听与退场）；"哪个任务被置顶"未真机抓 log 验证。**
- **过滤情况（协调者问的第 2 点）**：
  - `TaskStackModel.java:247-249` 的 `if (Utils.equals(mPackageName, packageName)) return;` 早返回**被注释掉了** → 实际代码不做同包去重，`mPackageName` 只是记账（`:252`）；每次顶层任务变化都会回调（`onTaskFocusChanged`/`onTaskDescriptionChanged` 路径都会走到 `onTopTaskChanged`）。
  - **不区分"按 Home"与"切到别的 app"**：回调只给包名，两者都是"顶层任务变了"。参考实现在手势模式下对两者**一视同仁地退场**（`FunnyWindowService.java:215-220` 没有任何包名判断）。→ **只要照抄，就会连"切到别的 app"也一起退浮层。** 若我们要"仅 Home/最近任务退、切 app 不退"，需要自己加判据（例如比对包名是否为 launcher —— 参考代码里没有这个现成逻辑）。
  - 去抖/延时的例子在别家：`PriAiPetFloatBall` 的 `FloatWindowProxy.onTopTaskChanged`（`model/FloatWindowProxy.java:239-257`）用 200ms `Observable.timer` 去抖 + 同包早返回。

**公司内其它三份 `TaskStackModel`（协调者要求对比）** —— 机制一致，只是分支/细节不同，均 `extends TaskStackListener` + `registerTaskStackListener`：

| 工程 | 文件 | 差异要点 |
| --- | --- | --- |
| LongShotScreen | `.../viewmodel/funny/TaskStackModel.java`（273 行级） | `onTopTaskChanged()` 的两处主动调用被注释（:53、:204），只靠回调触发；`getRunningTasks` 先试 `getTasks(...)` 再兜底 `ActivityTaskManager.getInstance().getTasks(3,...)`（:96-134） |
| PriDynamicWindow | `PriDynamicWindow/app/src/main/java/com/pri/dynamicwindow/model/TaskStackModel.java` | 同名类，`onTopTaskChanged()` 两处**打开**（:52、:205）；其余逐行等价（diff 仅包名/import/空行） |
| PriAiPetFloatBall | `PriAiPetFloatBall/app/src/main/java/com/pri/floatball/model/TaskStackModel.java` | 回调签名多一个参数：`onTopTaskChanged(String packageName, String className)`；消费点 `model/FloatWindowProxy.java:239-257` → 只看包名，200ms 去抖后决定显隐 |
| PriAiAgentChat | `PriAiAgentChat/app/src/main/java/com/pri/chatagent/translate/model/TaskStackModel.java` | 同为双参版本；消费点 `service/TranslateService.java:1232` 等 |

结论：**这就是公司内既有的"浮窗随顶层任务变化自处"的成熟范式**，四个 app 同源；`LongShotScreen` 版本与 `PriDynamicWindow` 版本在行为上等价（差别只是"是否在初始化/任务置顶时主动触发一次回调"）。

**另一个独立范式（不推荐给我们）**：`PriAiAgentChat` 用**无障碍服务**拿按键：`service/AutoInputAccessibilityService.java:816-824`（`onKeyEvent`）+ 清单 `AndroidManifest.xml:374-384` + `res/xml/accessibility_service_config.xml`（`canRequestFilterKeyEvents="true"`、`flagRequestFilterKeyEvents`）。那是为它自家的智能键/翻译服务，需要用户手动开无障碍，且它的 `handleKeyEvent` 只处理智能键（`AppUtils.isKeyPttTranslateActive`）。**对"三键退出"不是必需，不采用。**

### 2.5 其它退出路径（顺带记录，供我们取舍）

`FunnyWindowService` 的退场来源：UI 按钮（`MenuType.EXIT`）、`SCREEN_OFF` / 来电 / 短信 / 闹钟广播（`model/FunnyReceiverModel.java:43-47`、`receiver/FunnyWindowReceiver.java:13-15`）、旋转（`FunnyWindowService.java:203-207 onConfigurationChanged → onExit`）、以及上面的 `onTopTaskChanged`。**没有按键类退出。** 我们如果也想要"来电/锁屏/短信时收摊"，这些是现成可抄的广播清单。

---

## 三、窗口层级与"置顶"能力（协调者追加问题）

### 3.1 层级数值（权威来源）

`alps/frameworks/base/services/core/java/com/android/server/policy/WindowManagerPolicy.java:508-640`（`getWindowLayerFromTypeLw`；函数注释 `:527`："An arbitrary integer used to order windows, **with lower numbers below higher ones**"）：

| 类型 | layer | 行号 |
| --- | --- | --- |
| 普通应用窗口 | `APPLICATION_LAYER`(=2) | :535-536 |
| `TYPE_APPLICATION_OVERLAY`（我们现用） | **11** | :571-572 |
| `TYPE_SYSTEM_ALERT`（内部） | 12 | :566-570 |
| `TYPE_INPUT_METHOD`（IME） | 13 | :573-574 |
| `TYPE_STATUS_BAR` | 15 | :579-580 |
| `TYPE_NOTIFICATION_SHADE`（通知栏/锁屏宿主） | 17 | :583-584 |
| `TYPE_KEYGUARD_DIALOG`（参考实现浮标用） | **19** | :587-588 |
| `TYPE_SYSTEM_OVERLAY`（内部） | 23 | :598-601 |
| `TYPE_NAVIGATION_BAR`（导航栏） | 24 | :602-604 |
| `TYPE_NAVIGATION_BAR_PANEL`（参考实现整屏浮窗用） | **25** | :605-607 |
| `TYPE_SCREENSHOT` | 26 | :608-611 |
| `TYPE_SYSTEM_ERROR`（内部，`PriDynamicWindow` 用） | 27 | :612-614 |

**即：我们现在的 layer 11 在 IME / 状态栏 / 通知栏 / 锁屏宿主之下；参考实现的 25 在它们全部之上。**

### 3.2 锁屏（keyguard）—— 与真机证据对应

`alps/frameworks/base/services/core/java/com/android/server/wm/WindowState.java:4108-4126`：

```java
4108: boolean canBeHiddenByKeyguard() {
4110:     if (mActivityRecord != null) return false;      // Activity 的窗口另算
4112:     switch (mAttrs.type) { ... TYPE_STATUS_BAR / TYPE_NAVIGATION_BAR / TYPE_WALLPAPER ... return false; }
4123:     default:
4124:         // Hide only windows below the keyguard host window.
4125:         return mPolicy.getWindowLayerLw(this)
4126:                 < mPolicy.getWindowLayerFromTypeLw(TYPE_NOTIFICATION_SHADE);
```

执行处：`services/core/java/com/android/server/wm/DisplayPolicy.java:1874-1879`（`applyKeyguardPolicy` → `win.hide(...)`），判定 `shouldBeHiddenByKeyguard()` 在 `:1885-1920`。

- `TYPE_APPLICATION_OVERLAY`(11) < 17 → **可被锁屏隐藏** —— 与协调者真机看到的 `mPolicyVisibility=false`、焦点落到 `NotificationShade` 完全吻合。
- `TYPE_KEYGUARD_DIALOG`(19) / `TYPE_NAVIGATION_BAR_PANEL`(25) / `TYPE_SYSTEM_ERROR`(27) 均 **> 17 → 不被锁屏隐藏**。**代码明示（规则）；真机效果为推断，未验证。**

### 3.3 通知栏 / 状态栏 / IME / 可信覆盖

- 通知栏（`TYPE_NOTIFICATION_SHADE` 17）、状态栏（15）、导航栏（24）、IME（13）都在 25 之下 → 25 层窗口画在它们之上（层号比较，方向由 `:527` 注释给定）。**代码明示（层号规则）。**
- `FLAG_ALT_FOCUSABLE_IM` + 可获焦点 = 窗口**盖住** IME 而不是被 IME 盖住（`WindowManager.java:3065-3081`）。
- `InputMonitor.isTrustedOverlay(int)`（`services/core/java/com/android/server/wm/InputMonitor.java:733-752`）把 `TYPE_NAVIGATION_BAR_PANEL`（:739）、`TYPE_STATUS_BAR`、`TYPE_NOTIFICATION_SHADE`、`TYPE_NAVIGATION_BAR`、`TYPE_SECURE_SYSTEM_OVERLAY`、`TYPE_INPUT_CONSUMER` 等列为 **trusted overlay** → 输入分发时不作为"遮挡者"处理，触摸能正常送到（不会因为"被覆盖"给下层应用打 `FLAG_WINDOW_IS_OBSCURED`）。
- 任意窗口也能变 trusted：`LayoutParams.setTrustedOverlay()`（`WindowManager.java:5013-5023`，需 `INTERNAL_SYSTEM_WINDOW`，实测参考实现直接调了）。**代码明示。**

### 3.4 同一套"高楼层"在室内的第二个样本

`pri_project/android/pri_app/PriSystemUI/src/com/pri/qs/PriSuperScreenShotDialog.kt:72-90`（截屏模式选择弹窗）：

```kotlin
72:  win.setType(WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL)
74:  win.addFlags(FLAG_ALT_FOCUSABLE_IM or FLAG_SHOW_WHEN_LOCKED or FLAG_LAYOUT_IN_SCREEN
                    or FLAG_FULLSCREEN or FLAG_LAYOUT_NO_LIMITS)
```

→ 公司内两处独立代码都用 `TYPE_NAVIGATION_BAR_PANEL` 表达"必须盖在一切之上"（SystemUI 这份还加了 `FLAG_SHOW_WHEN_LOCKED`）。**代码明示。**

### 3.5 "置顶"的边界（不要过度承诺）

- 25 层之上还有：`TYPE_SCREENSHOT`(26)、`TYPE_SYSTEM_ERROR`(27，内部)、`TYPE_MAGNIFICATION_OVERLAY`(28)、`TYPE_DRAG`(30)、无障碍相关(31/32)、`TYPE_SECURE_SYSTEM_OVERLAY`(33)、圆角覆盖层(36)。另一进程/另一 UID 的这些窗口理论上仍在其上。**代码明示（层号），未做真机对抗验证。**
- **能确定的是**：19 / 25 已足以摆脱"被锁屏隐藏"（3.2）与"在 IME/通知栏之下"（3.3）这两类当前实际遇到的问题。**不能确定的是**：是否能盖住所有 SystemUI 系统层窗口（未验证）。

---

## 四、权限要求与我们的差距

### 4.1 参考实现的声明与身份（出货版本实测）

- 源码清单 `LongShotScreen/app/src/main/AndroidManifest.xml:2-12`：`android:sharedUserId="android.uid.system"`（:5）、`targetSdkVersion=36`/`minSdkVersion=29`（:7）；权限仅 4 条（:9-12）：`SYSTEM_ALERT_WINDOW`、`SYSTEM_OVERLAY_WINDOW`、`INTERACT_ACROSS_USERS`、`INTERACT_ACROSS_USERS_FULL`。
- **出货 APK 复核**（`aapt2 dump badging/xmltree`，`alps/pri_app/apk_release/LongShotScreen/release_160260930_26.09.30.16.57/LongShotScreen.apk`）：`sharedUserId="android.uid.system"`、`targetSdkVersion:'36'`、`minSdkVersion:'29'`，权限同上 4 条 —— **没有** `STATUS_BAR_SERVICE`、**没有** `INTERNAL_SYSTEM_WINDOW`。
- 安装方式 `alps/pri_app/apk_release/LongShotScreen/Android.mk`：`LOCAL_CERTIFICATE := platform`、`LOCAL_PRIVILEGED_MODULE := true`、`LOCAL_MODULE_PATH := $(TARGET_OUT)/priv-app` → **platform 签名 + /system/priv-app 预置**。
- privapp 白名单 `alps/pri_app/apk_release/LongShotScreen/privapp-permissions-com.pri.longshotscreen.xml`：只放行 `INTERACT_ACROSS_USERS`。产品化开关在 `alps/device/pri/module/AppsProperty.mk:504-512`。

### 4.2 框架侧的权限检查点

- `TYPE_NAVIGATION_BAR_PANEL` → `services/core/java/com/android/server/wm/DisplayPolicy.java:1214-1220`：`case TYPE_NAVIGATION_BAR_PANEL: ... mContext.enforcePermission(systemUiPermission /* = STATUS_BAR_SERVICE */, ...)`；同时 `PhoneWindowManager.checkAddPermission` 对它是"白名单直通"（`PhoneWindowManager.java:3186-3213`，`TYPE_NAVIGATION_BAR_PANEL` 在 `:3205` 直接 `ADD_OKAY`）。
- `TYPE_KEYGUARD_DIALOG` / `TYPE_SYSTEM_ERROR` 不在该白名单 → 落到 `PhoneWindowManager.java:3216-3217`：需要 `INTERNAL_SYSTEM_WINDOW`。
- `TYPE_APPLICATION_OVERLAY`（我们现用）走 appop 分支，但 **uid 1000 自动放行**：`PhoneWindowManager.java:3225-3226`（`UserHandle.getAppId(callingUid) == Process.SYSTEM_UID → ADD_OKAY`）。
- 权限定义（`alps/frameworks/base/core/res/AndroidManifest.xml`）：`STATUS_BAR_SERVICE` = `signature|recents`（:6086-6087）；`INTERNAL_SYSTEM_WINDOW` = `signature|module|recents`（:6160-6162）；`SYSTEM_ALERT_WINDOW` = `signature|setup|appop|installer|pre23|development`（:4992-4995）。

### 4.3 uid 1000 + platform 签名够不够？

**够（对这套窗口类型而言）。** 依据：

1. 产品实证：出货 APK 只声明 4 条普通权限、privapp 白名单只加 `INTERACT_ACROSS_USERS`，却在真机上成功地 `addView` 了 `TYPE_NAVIGATION_BAR_PANEL`（layer 25）与 `TYPE_KEYGUARD_DIALOG`（layer 19）窗口（`DisplayPolicy` 的 `STATUS_BAR_SERVICE` 与 `checkAddPermission` 的 `INTERNAL_SYSTEM_WINDOW` 两道检查都放行了）。**由上述两份清单反推，结论明确。**
2. `sharedUserId=android.uid.system` 与 platform 签名是这两条检查通过的身份基础（与 framework-res/"android" 包同 uid，签名级权限随 uid 继承）。**未找到** `PermissionManagerServiceImpl` 的源码（本仓库两棵树 `alps/`、`vendor_w/` 都没有该文件，Permission 模块未镜像进来），所以**无法引用"uid==1000 一律授予"那一行源码** —— 这是本文唯一一个"结论有产品实证但缺代码引用"的点。
3. 还需要的**非权限前提**：以 `/system/priv-app` 预置（`LOCAL_PRIVILEGED_MODULE := true`）+ platform 签名。若要 appop 类权限（`SYSTEM_ALERT_WINDOW`）被静默授予，uid 1000 已是自动放行。

### 4.4 我们的差距

| 项 | 参考实现 | 我们（nfc_tap_demo） | 影响 |
| --- | --- | --- | --- |
| 签名/uid | platform + `android.uid.system` | 同（`app/src/main/AndroidManifest.xml:20`） | 一致，无差距 |
| 安装位置 | `/system/priv-app` | 待确认（产品化需按 `AppsProperty.mk` 那样内置） | 不内置则部分权限/行为不成立 |
| targetSdk | 36 | 37（`app/build.gradle.kts:17`） | 影响的是 `ACTION_CLOSE_SYSTEM_DIALOGS` 等广播限制；本方案不依赖它 |
| 窗口类型 | `TYPE_NAVIGATION_BAR_PANEL`(25) / `TYPE_KEYGUARD_DIALOG`(19)+trusted | `TYPE_APPLICATION_OVERLAY`(11)，未调 `setTrustedOverlay`（`service/OverlayService.kt:734-741`、`nfc/Overlay.java:26-37`） | **主要差距**：锁屏会被藏、层级低于 IME/系统栏 |
| Home/Recents 感知 | `TaskStackListener` | 无（`grep` 本工程无 `TaskStackListener`/`onTopTaskChanged`） | 需新增 |
| 返回键 | 未实现 | `OverlayService.kt:552/599`（`setOnKeyListener` + `KEYCODE_BACK`）已实现 | 我们反而更强，保留 |

---

## 五、可直接照搬到我们工程的做法

### 5.1 Home / 最近任务：加一个 `TaskStackListener` 模块

1. 照抄 `TaskStackModel` 骨架（`registerTaskStackListener` + `onTaskMovedToFront` / `onTaskFocusChanged` / `onTaskDescriptionChanged` / `onTaskRemoved` + `ICallback.onTopTaskChanged(String)`，主线程回调），文件放 `app/src/main/java/com/pri/nfctouchdemo/service/` 附近。
2. 回调里做我们真正想要的语义：`OverlayService` 收到"顶层任务变了" → 收起动效页并收尾（等价 `stopSelf`）。**注意 `Utils.equals` 早返回在参考实现里是被注释掉的**：我们要不要"同包不去重"、要不要"切 app 也退"必须显式写出来（参考实现是一退到底，且只在手势导航模式退：`FunnyWindowService.java:215-220`；我们需求是"Home/Recents 退、切 app 不一定退"，则需自己判据，代码库里没有现成的）。
3. 生命周期对齐：`init` 放在 `onCreate`（参考 `FunnyWindowService.java:49`），`release/unregister` 放 `onDestroy`（`:81-87, 143`），避免重复注册。

### 5.2 窗口层级：升级窗口类型（要"盖住一切"必须做）

- **方案 A（改动最小，够用优先级）**：`TYPE_APPLICATION_OVERLAY` → `TYPE_KEYGUARD_DIALOG`（19），并按参考实现加 `params.setTrustedOverlay()`。flags 保持我们现在的"可获焦点 + `FLAG_NOT_TOUCH_MODAL` + 整屏 + `LAYOUT_NO_LIMITS` + cutout ALWAYS"（`OverlayService.kt:712-771`），再补 `FLAG_ALT_FOCUSABLE_IM`（要盖住输入法时）。优点：`FloatWindowService` 的悬浮球就是这套类型+trusted，先例充分。
- **方案 B（与整屏编辑器对齐，最"高"）**：`TYPE_NAVIGATION_BAR_PANEL`（25），照 `FunnyWindowModel.java:79-99` 的 flags（`FLAG_ALT_FOCUSABLE_IM|FLAG_FULLSCREEN|FLAG_LAYOUT_IN_SCREEN|FLAG_NOT_TOUCH_MODAL|FLAG_LAYOUT_NO_LIMITS`）+ `setFitInsetsTypes(0)`。
- 两种都**不能再带 `FLAG_NOT_FOCUSABLE`**（否则返回键收不到；触摸穿透模式除外，我们已有该开关，`OverlayService.kt:726-731`）。
- 触摸穿透模式（`touchThrough`）下窗口重新加回 `FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE` 的现状要保留 —— 那时本来就不该抢键。

### 5.3 返回键：保持我们自己处理

继续用 `setOnKeyListener` / `dispatchKeyEvent`（现状）；如需与系统预测返回动画配合，可换 API 33+ 的 `findOnBackInvokedDispatcher().registerOnBackInvokedCallback(...)`（`ViewRootImpl.java:14182-14194`）。**不要**指望框架替我们关窗口（2.3 已证框架无此机制）。

### 5.4 不要做的事

- 不要用 `ACTION_CLOSE_SYSTEM_DIALOGS`（Android 12+ 对 targetSdk≥31 受限；且区分不出 Home/最近任务；参考实现与出货 APK 都不用）。
- 不要用无障碍服务（要用户手动开；参考实现只在本不需要它的地方用它）。
- 不要试图用 `TYPE_APPLICATION_OVERLAY` + 某个 manifest 标志"抬高"层级 —— 层号由类型决定（`WindowManagerPolicy.java:571-572`），没有任何 manifest 标志能改它。参考实现的做法是**换类型**。

---

## 六、存疑与未找到的部分（重要）

1. **"返回键只关浮层"在参考实现里未找到任何实现**（源码 + 出货 APK 双重 0 命中；框架侧也无兜底）。用户口述的这条行为与本代码不符 —— 建议回问用户"是在哪台机器、哪个应用上看到 Back 关掉浮层的"，或让用户确认是否记混（例如关掉的是 SystemUI 的截屏模式弹窗）。
2. **未做真机验证**（本次为只读研究）：窗口 19/25 层在锁屏、通知栏、IME、SystemUI 之上的实际表现；`TaskStackListener` 在按 Home / 最近任务时回调的时序与包名。全部结论以代码路径为准。
3. **`PermissionManagerServiceImpl` 不在本仓库树内**（`alps/`、`vendor_w/` 均无），无法引用"uid 1000 权限一律授予"的源码行；4.3 的结论来自"出货清单无该权限 + 产品可用"的反推。
4. `LongShotScreen` 三键导航（`navigation_mode != 2`）下 Home/最近任务是否真的不退浮层，代码上只是"忽略回调"（`FunnyWindowService.java:219`）；是否存在别的路径（如系统 UI 行为）导致仍会退，**未找到**。
5. 我们 targetSdk 37（参考实现 36）是否会引入额外的窗口/广播行为差异，**未逐一核对**（至少本文用到的机制都不依赖被限制的 API）。
6. `PriAiPetFloatBall` / `PriAiAgentChat` 的 `TaskStackModel` 是"双参（含 className）"版本，其 `className` 从何而来（是否另有 `UsageStats` / 无障碍来源）**未逐行核对**，只确认了包名路径与 LongShotScreen 一致。
