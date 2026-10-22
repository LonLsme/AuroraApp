# 架构总览

> 本文描述 AuroraApp 当前（2026-10-12）的代码结构。
> 所有数字、类名、资源 id 均从源码与构建产物核对得出，未做估算。

---

## 一、项目画像

| 项 | 值 | 来源 |
| --- | --- | --- |
| Gradle 工程名 | `Aurora` | `settings.gradle.kts` |
| 包名 / 命名空间 | `com.aurora.app` | `app/build.gradle.kts` |
| 版本 | `versionCode 2` / `versionName 1.1.0` | 同上 |
| minSdk / targetSdk | **24** / **36** | 同上 |
| compileSdk | 36（`minorApiLevel = 1`） | 同上 |
| Java 语言级别 | 11（`sourceCompatibility` / `targetCompatibility`） | 同上 |
| 源码规模 | **119 个源文件 / 36,407 行**（111 `.java` + 8 `.kt`） | 实测统计 |
| 布局文件 | 61 个（`res/layout/`） | 实测统计 |
| Activity | **35 个** | 实测统计 |
| 上游 | [violet_Box](https://github.com/Smart-Paocai/violet_Box)（GPL-3.0），二次开发 | `README.md` / `LICENSE` |

**构建工具链版本**（`gradle/libs.versions.toml`）：

| 组件 | 版本 |
| --- | --- |
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.3.10 |
| Compose BOM | 2026.02.01 |
| appcompat / material | 1.6.1 / 1.10.0 |
| viewpager2 / recyclerview / constraintlayout | 1.0.0 / 1.3.2 / 2.1.4 |
| kyant0 backdrop / capsule | 1.0.6 / 2.1.3 |

**非 AndroidX 依赖**（直接写在 `dependencies` 里，未走 version catalog）：
`com.scottyab:rootbeer-lib:0.1.0`（Root 检测）、
`com.squareup.okhttp3:okhttp:4.12.0`（OTA 云提取）、
`org.apache.commons:commons-compress:1.27.1` + `org.tukaani:xz:1.10` + `commons-io:commons-io:2.18.0`（OTA payload 解析）。

> **注意**：仓库根 `README.md` 曾写着「libsu」，但 `app/build.gradle.kts` 里**没有 libsu** ——
> Root 执行走的是自研的 `core/root/RootShell.java`。

---

## 二、分层

代码是**严格单向**的三层，`ui` 依赖 `core`，`core` 依赖 `data`，反向依赖不存在。

```
┌──────────────────────────────────────────────────────────┐
│  ui/          76 个文件   表现层：Activity / Fragment /   │
│                           自定义 View / Compose 组件      │
├──────────────────────────────────────────────────────────┤
│  core/        37 个文件   能力层：Root、刷机、监视采集、   │
│                           屏幕、CPU —— 不含任何 UI 代码    │
├──────────────────────────────────────────────────────────┤
│  data/         4 个文件   数据层：Root 检测、运行时探测、  │
│                           应用行模型                      │
├──────────────────────────────────────────────────────────┤
│  receiver/     1 个文件   系统事件入口（开机广播）          │
│  AuroraApplication.java   进程级初始化（主题、RootShell…）  │
└──────────────────────────────────────────────────────────┘
```

**分层纪律**：`ui/` 下有 **32 个文件** import 了 `core/`；
而 `core/` 下**没有任何文件** import `ui/`（实测 grep `import com.aurora.app.ui` 命中 0 条）。

`core/` 里唯一的「跨界」是 `core/display/DisplayRestoreJobService.java` ——
它是 JobScheduler 服务、需要在 `Manifest` 里注册（是给系统用的，不是给 UI 用的），
逻辑本身与 UI 无关。

---

## 三、模块地图

### `ui/`（22 个子包）

| 包 | 职责 | 代表类 |
| --- | --- | --- |
| `ui/main` | 主框架：Activity、ViewPager2、底栏 | `MainActivity`(1794) · `FloatingBottomBarCompose.kt`(534) · `BottomBarState.kt` |
| `ui/device` | 「设备」页（tab 0） | `DeviceFragment`(782) |
| `ui/flash` | 「刷机」页（tab 1）与全部刷机子页 | `FlashPage`(516) · `FlashConsoleActivity`(417) · `QdlActivity`(610) · `FlashFilePicker`(309) |
| `ui/update` | 检查更新：结果对话框（三种形态共用一套控件） | `UpdateDialog` |
| `ui/settings` | 「设置」页（tab 3）与主题设置 | `SettingsFragment` · `ThemeActivity.kt` · `ThemeScreen.kt`(864) |
| `ui/theme` | 主题引擎（算法 + 偏好 + 调色板） | `AuroraPalette`(483) · `ThemeManager`(422) · `AccentOverlays` |
| `ui/overlay` | 监视悬浮窗全套（窗口 + 采集调度） | `MonitorOverlayService` · `OverlayWindow`(506) · 6 个窗口实现 |
| `ui/plugin` | Aurora 插件页（Root 生态集成） | `HiddenAppListConfigActivity`(1423) · `AuroraPluginActivity`(1222) |
| `ui/payload` | OTA 包云提取与解析 | `PayloadCore`(969) · `PayloadActivity`(482) |
| `ui/display` | 屏幕调节（分辨率/密度/刷新率） | `DisplayTuneActivity`(621) · `RefreshRateTester`(331) |
| `ui/partition` | 分区管理 | `PartitionManagerActivity`(648) |
| `ui/module` | 模块管理与备份 | `ModuleManagerActivity`(512) · `ModuleBackupActivity`(440) |
| `ui/appmanager` | 应用管理（备份/冻结/卸载） | `AppManagerActivity`(600) |
| `ui/font` | 字库备份 | `FontLibraryBackupActivity`(602) |
| `ui/cpu` | CPU 控制 | `CpuControlActivity`(436) |
| `ui/process` | 进程列表 | `ProcessListActivity`(355) |
| `ui/fps` | 帧率记录 | `FpsRecordActivity` |
| `ui/selinux` | SELinux 管理 | `SelinuxManagerActivity` |
| `ui/about` | 关于页（版本信息 / 开源 / 鸣谢 / 作者，设置页 `rowAbout` 入口） | `AboutActivity` |
| `ui/widget` | 自定义控件 | `CpuRingGaugeView` · `AuroraSwitchStyler` |
| `ui/animation` | Compose 动效基元 | `DampedDragAnimation.kt` · `InteractiveHighlight.kt` |
| `ui/modifier` | Compose 手势 | `DragGestureInspector.kt` |

### `core/`（7 个子包）

| 包 | 文件数 | 职责 |
| --- | --- | --- |
| `core/flash` | 11 | 刷机链路全套，详见 [`subsystems/FLASH.md`](subsystems/FLASH.md) |
| `core/monitor` | 12 | 12 个指标采集器，详见 [`subsystems/MONITOR.md`](subsystems/MONITOR.md) |
| `core/util` | 7 | 系统节点读取工具（`NodeReader` · `CpuStatReader` · `BatterySysFiles` · `SelinuxStatusReader` …） |
| `core/display` | 3 | `DisplayController`(710) · `DisplayGuard` · `DisplayRestoreJobService` |
| `core/update` | 2 | `UpdateChecker` —— GitHub Releases 版本检查（纯 HTTP + JSON，零新依赖）· `ReleaseNotes` —— Release 说明的 Markdown 清理 |
| `core/root` | 1 | `RootShell`(389) —— 全项目唯一的 Root 执行入口 |
| `core/cpu` | 1 | `CpuController` |

### `data/`

| 文件 | 职责 |
| --- | --- |
| `data/detector/RootDetector.java`(395) | Magisk / KernelSU / APatch 等 Root 方案检测 |
| `data/detector/AdvancedRuntimeDetector.java` | 运行时环境探测（Bootloader、USB 调试等） |
| `data/detector/HardcodedSignals.java` | 检测用的特征字符串/路径常量 |
| `data/model/InstalledAppRow.java` | 应用列表行模型 |

---

## 四、启动链路

```
1. AuroraApplication.onCreate()
   ├─ 读取 ThemeManager 偏好 → 应用色彩模式与字号
   ├─ 初始化 RootShell（懒加载，不阻塞主线程）
   └─ 注册 BootCompletedReceiver 需要的状态

2. MainActivity.onCreate()
   ├─ inflate R.layout.activity_main → nativeContentView
   │    （注意：nativeContentView 是 activity_main 的 inflate 结果，
   │      不是 contentView —— 后者被 ComposeView 占用）
   ├─ setupTabPager()   —— 收集 4 个页面 View、交给 ViewPager2
   │    └─ installPageFragments() —— 安装 DeviceFragment / SettingsFragment
   ├─ attachMainScreen(composeView, nativeContentView, callback)  —— 挂 Compose 底栏
   └─ restoreMonitorOverlays()（postDelayed 300ms）—— 恢复上次开启的悬浮窗
```

### 四个页面的容器与顺序

`setupTabPager()` 里的数组**顺序即 tab 顺序**：

| tab | 容器 id | 页面 | 承载方式 |
| --- | --- | --- | --- |
| **0** | `contentDevice` | 设备 | `DeviceFragment` → 容器 `device_fragment_container` |
| **1** | `contentHome` | 刷机 | `FlashPage`（纯代码绑定，**无 Fragment**） |
| **2** | `fragmentExplorePlaceholder` | 玩机 | `TunePage`（纯代码绑定，**无 Fragment**） |
| **3** | `fragmentSettingsPlaceholder` | 设置 | `SettingsFragment` → 容器 `settingsFragmentContainer` |

> [!warning] 两个「名字骗人」的地方
> **① tab 1 的容器 id 叫 `contentHome`，但它是刷机页。**
> 这是从上游 violet_Box 继承下来的历史命名（原本是 Home 页）。
> 想改这个名字，必须同时改 `setupTabPager()` 里的数组索引 —— 改错就是整页错位。
>
> **② tab 2 的容器 id 叫 `fragmentExplorePlaceholder`，但它是玩机页**，同理。

底栏四个 tab 的文案定义在 `ui/main/FloatingBottomBarCompose.kt`：
**设备 / 刷机 / 玩机 / 设置**（`MainActivity` 里的 `TAB_DEVICE = 0`、`TAB_FLASH = 1`）。

---

## 五、关键机制（改代码前必读）

这几条都是**踩过坑才定下来的写法**，看起来可以「简化」，实际会崩或错位。

### 5.1 `MainActivity.findViewById()` 必须三级查找

`MainActivity` 覆写了 `findViewById(int)`，查找顺序是：

```
① nativeContentView 子树        ② pageViews 兜底扫描        ③ super.findViewById
   （正在显示的页面）              （已摘出、尚未布局的页面）      （Activity 装饰视图）
```

**第 ② 级不能删。** 原因：4 个页面 View 被 `setupTabPager()` 从 `CoordinatorLayout`
**摘出**、交给 `ViewPager2` 托管，只有在 ViewPager2 完成布局后才回到视图树上。
而「设备页 / 设置页」的 `FragmentContainerView` 就在这些页面 View 内部；
`FragmentManager` 创建 Fragment 的 View 时需要按容器 id 定位容器
（`FragmentStateManager.createView()`），若只能从 `nativeContentView` 子树查找，
在「已摘出、尚未布局」的时间窗内**必然查不到**并抛
`IllegalArgumentException: No view found for id`。

`pageViews` 字段让容器查找与视图树状态彻底解耦 —— 这正是它的存在意义。

> **禁止**用 `post` / `postDelayed` 去「等布局完成」再编排 Fragment 安装。
> 这是引入 `pageViews` 之前的老写法，时序不可靠。

### 5.2 两个页面 Fragment 必须代码创建，并放弃系统自动恢复

`installPageFragments()` 用 `beginTransaction().replace(容器id, new XxxFragment(), TAG)`
创建 Fragment，**layout XML 里刻意不写 `android:name`**（见
`fragment_settings_placeholder.xml` 里的注释）。

原因：静态声明的添加路径是 `add(View, Fragment, tag)`，会把容器 **View 引用**直接赋给
Fragment（此时 `mContainerId == 0`），因此**首次启动永远正常**；但 Activity 重建时
`FragmentManager` 只能从 `savedInstanceState` 恢复，而 **View 引用无法序列化**、
只剩 `mContainerId == 0` → `createView()` 时 `container == null` → View 不挂载
→ **整页空白（不抛异常，所以只是白屏，极难排查）**。

配套两点：

1. `onCreate` 里移除 `FRAGMENT_STATE_TAG = "android:support:fragments"` 键，
   **主动放弃自动恢复**，让冷启动与重建走完全相同的路径；
2. 两个容器**各自独立判断**（任一失配不连累另一个），用
   `commitNowAllowingStateLoss()` 并 `catch (Throwable)` —— 这两个页面 Fragment
   不承载需要恢复的状态，放弃状态保存无副作用。

### 5.3 顶栏按 tab 收起 —— 只能改高度，不能 `setVisibility(GONE)`

`updateToolbarForTab(int tab)` 里 `show = (tab == TAB_DEVICE)` ——
**只有「设备」页保留顶栏**，刷机 / 玩机 / 设置三页整行收起。

收起的方式是把 `AppBarLayout` 的 `layoutParams.height` 改成 `0`，**不是 `GONE`**：

```
childHeight = availableHeight - header.getMeasuredHeight() + getScrollRange(header)
childTop    = dependency.getBottom()
```

页面 ViewPager2 带 `appbar_scrolling_view_behavior`，测量与定位都来自
`HeaderScrollingViewBehavior`。`CoordinatorLayout` 的 `onMeasure`/`onLayout`
**会跳过 GONE 的子 View** → AppBar 的 `getMeasuredHeight()` 与 `getBottom()`
都停在收起前的旧值，而且**两个量正好互相抵消** → 表现为顶部留一条空带、底部被裁掉一段。

改成高度 0 后两个量都会被真实重算（`getTotalScrollRange()` 本来就是 0，
因为 toolbar 没配 `layout_scrollFlags`），结果完全确定。

> **代价**：顶栏上的「监视器」「电源」入口只剩设备页可用。

### 5.4 页面 View 上的 `layout_marginTop` 一律无效

`setupTabPager()` 把每个页面的 `LayoutParams` **整体替换**为
`MATCH_PARENT × MATCH_PARENT`（因为原 `CoordinatorLayout.LayoutParams` 带
`layout_behavior` 等属性，直接复用会导致 ViewPager2 item 尺寸异常）。
CoordinatorLayout 时期写在 XML 里的 margin 随之丢失。

**要在页面顶部留空，只能在页面内部加 padding 或占位 View。**

### 5.5 Compose 与 XML 混排

- 底栏是 Compose（`FloatingBottomBarCompose.kt`），入口是
  `FloatingBottomBarComposeKt.attachMainScreen(composeView, nativeContentView, callback)`。
- **Compose 组件必须显式传 `colorScheme`** —— 不传时在 Material 3 下会固定走浅色，
  切到深色/AMOLED 主题时底栏不跟着变。
  配色由 `ui/theme/AuroraColorScheme.kt` 折算，运行时数据来自 `AuroraPalette`。
- 主题页 `ui/settings/ThemeScreen.kt`（864 行）是**整页 Compose**，
  宿主是 `ThemeActivity.kt`。

### 5.6 弹性滑动（iOS 风格回弹）

上下弹性 = `android:overScrollMode="always"` + `android:fillViewport="true"`
（两者缺一不可）。`NestedScrollView` 默认的 `ifContentScrolls`
**在内容不超一屏时毫无反馈**。

已覆盖：刷机 4 个子页 + 导航页 4 个 tab。

> `ViewPager2` 自身的 `overScrollMode="never"` 是**水平方向**的，**不要动**。

### 5.7 耗时检测 / IO 不进首屏路径

安全页曾因在主线程 `su getenforce`（2.5s）导致进入即卡死。
现在的规则是：**任何 Root 调用、文件遍历、进程枚举都走单线程池 + 分片渲染**。

---

## 六、子系统索引

| 子系统 | 文档 | 入口类 |
| --- | --- | --- |
| 刷机链路 | [`subsystems/FLASH.md`](subsystems/FLASH.md) | `ui/flash/FlashPage.java` |
| 高通 QDL 深刷 | [`subsystems/QDL.md`](subsystems/QDL.md) | `ui/flash/QdlActivity.java` |
| 主题引擎 | [`subsystems/THEME.md`](subsystems/THEME.md) | `ui/theme/ThemeManager.java` |
| 监视器 / 悬浮窗 | [`subsystems/MONITOR.md`](subsystems/MONITOR.md) | `ui/overlay/MonitorOverlayService.java` |
| 系统采数边界 | [`subsystems/SYSTEM_DATA.md`](subsystems/SYSTEM_DATA.md) | `core/util/NodeReader.java` |
| Root 执行 | `core/root/RootShell.java`（见下） | — |

### RootShell（`core/root/RootShell.java`，389 行）

全项目统一的 Root 执行层，公开 API：

| 方法 | 说明 |
| --- | --- |
| `RootShell.get()` | 单例 |
| `isRootAvailable()` / `invalidateRootCache()` | Root 可用性 + 缓存失效 |
| `exec(cmd)` / `exec(cmd, timeoutMs)` → `Result` | 同步执行，`Result` 含 `exitCode`/`out`/`timedOut`/`unavailable` |
| `execCode(cmd)` / `execOut(cmd)` / `execLines(cmd)` | 便捷取值 |
| `execAsync(cmd, callback)` / `execAsync(cmd, timeoutMs, callback)` | 异步执行 |
| `fileExists(path)` | 存在性判断 |
| `escape(raw)` | **所有外部输入拼接前必须过这一层** |
| `shutdown()` | 释放会话 |

默认超时 `DEFAULT_TIMEOUT_MS = 10_000L`。

> [!warning] 刷机执行层刻意不复用 `RootShell`
> `core/flash/FlashRuntime.java` 自己起 `ProcessBuilder("su", "-c", ...)`，
> 因为它需要**流式逐行回调**、**可取消**、**固定 env 前缀**、
> **单任务闸门**这四件事 —— `RootShell` 是一次性收完整串输出的模型，满足不了。
>
> 技术债：仍有零星位置的 `ProcessBuilder("su")` 未收编到 `RootShell`。

---

## 七、系统集成点

`AndroidManifest.xml` 里注册的非 Activity 组件：

| 组件 | 类型 | 关键属性 | 为什么要它 |
| --- | --- | --- | --- |
| `AuroraApplication` | Application | — | 进程级初始化 |
| `BootCompletedReceiver` | receiver | `directBootAware` + `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` | 开机恢复屏幕参数等状态 |
| `DisplayRestoreJobService` | service | `exported=true` + `BIND_JOB_SERVICE` | **屏幕断点兜底的第二通道**。小米/HyperOS 会拦开机广播（日志：`process is not permitted to auto start`），持久化 JobScheduler 任务由系统持有、跨重启保留，是另一条独立启动路径 |
| `MonitorOverlayService` | service | `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | 让悬浮窗在后台/游戏中存活 |
| `FileProvider` | provider | `${applicationId}.fileprovider` | 导出日志、分享备份文件 |

**权限清单**（8 条）：

| 权限 | 用途 |
| --- | --- |
| `QUERY_ALL_PACKAGES` | 应用管理 / 隐藏应用列表需要完整应用列表 |
| `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion=28`） | 仅旧系统写导出文件 |
| `INTERNET` | OTA 云提取、检查更新 |
| `RECEIVE_BOOT_COMPLETED` | 开机恢复 |
| `READ_PHONE_STATE` | 设备信息展示 |
| `SYSTEM_ALERT_WINDOW` | 监视悬浮窗 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 前台服务，Android 14+ 必需 |

> Android 14 (API 34) 起，前台服务必须声明类型；`specialUse` 还需在
> Manifest 里补 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途 ——
> 缺这个属性会**直接崩溃**（`MonitorOverlayService` 曾因此修过一轮，见 [`archive/2026-08-29-代码评审.md`](archive/2026-08-29-代码评审.md) 的 P0-1）。

---

## 八、技术债

按影响面排序，**不是待办清单，是已知的坑**：

| # | 问题 | 影响 | 位置 |
| --- | --- | --- | --- |
| 1 | `MainActivity` 是 **1794 行**的 God Activity，含 **261 处 `findViewById`** | 改动风险高、难测试；未启用 viewBinding | `ui/main/MainActivity.java` |
| 2 | **零有效测试**（无单元测试、无 UI 测试） | 回归全靠离线断言脚本（见 `DEVELOPMENT.md`） | 全局 |
| 3 | release 构建 **未开启混淆**（`isMinifyEnabled = false`） | 包体偏大、加密字符串可见 | `app/build.gradle.kts` |
| 4 | `RootShell` 已成但**未完全收编** | 零星 `ProcessBuilder("su")` 散落各处 | 全局 |
| 5 | 页面容器 id 与真实内容不符（`contentHome` = 刷机页） | 误导后续维护者 | `activity_main.xml` |
| 6 | AMOLED 模式下直接放在页面上的卡片**没有描边** | 卡片与背景同为纯黑、糊成一片。按钮已用 `Widget.Aurora.Miuix.Button` 的 1dp 描边解决，卡片尚未 | `res/values-night/colors.xml` |
| 7 | `ui/flash/FlashRunDialog.java`（7.3KB）**已无任何使用者** | 死代码，但刻意保留未删 | `ui/flash/` |
| 8 | `QdlCommands` 里 `owrite` 系列 / `detectEdl` / `killResidual` / `reboot` **无调用方** | 命令层已就绪，UI 未接 | `core/flash/QdlCommands.java` |
| 9 | 引导库 `Loader.7z`（16MB / 265 机型）**未搬** | QDL 页的授权档位只能由用户手选，不能自动判定 | — |
| 10 | `res/menu/bottom_nav_menu.xml` 是**遗留资源** | 已被 Compose 底栏取代，但文件仍在（里面 `nav_home` 还写着「首页」）。实测无任何引用 | `res/menu/` |

---

## 九、目录速查

```
AuroraApp/
├── app/
│   ├── build.gradle.kts            模块配置（依赖、版本、compose 开关）
│   └── src/main/
│       ├── AndroidManifest.xml     组件注册 + 权限
│       ├── assets/
│       │   ├── flash_tools.zip     刷机工具链（5 个可执行文件，见 FLASH.md）
│       │   └── aurora_box_module.zip  Aurora 核心扩展模块（Magisk / KernelSU）
│       ├── java/com/aurora/app/    119 个源文件
│       └── res/
│           ├── layout/             61 个布局
│           ├── values/             attrs / colors / dimens / strings / themes
│           │   └── aurora_accent_overlays.xml   自动生成，勿手改
│           └── values-night/       深色覆盖层
├── docs/                           本目录
├── gradle/libs.versions.toml       版本目录
├── tools/                          图标处理脚本（Python / Swift）
└── aurora_box_module/              Aurora 核心扩展模块源码（独立打包，不参与 app 构建）
```
