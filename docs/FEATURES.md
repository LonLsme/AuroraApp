# 功能清单

> 按**用户看到的页面**组织，不是按代码包组织。
> 想知道「某个功能做没做 / 入口在哪」先看这份。
> 页面拼装方式（tab 顺序、容器 id）见 [`ARCHITECTURE.md`](ARCHITECTURE.md) 第四节。

---

## 一、主界面骨架

底栏是 **Compose 居中胶囊**（`ui/main/FloatingBottomBarCompose.kt`），四个 tab：

| tab | 名称 | 页面 | 顶栏 |
| --- | --- | --- | --- |
| 0 | **设备** | `DeviceFragment` | ✅ **唯一保留顶栏的页面** |
| 1 | **刷机** | `FlashPage` | ❌ 整行收起 |
| 2 | **玩机** | `TunePage` | ❌ 整行收起 |
| 3 | **设置** | `SettingsFragment` | ❌ 整行收起 |

### 顶栏（仅在「设备」页）

| 项 | 行为 |
| --- | --- |
| 使用监视器 | 打开悬浮窗开关面板（6 个开关，见「监视悬浮窗」节） |
| 快捷重启菜单 | 弹层：重启系统 / 重启到 Bootloader / 重启到 FastbootD / 重启到 Recovery / 重启到 EDL / 重启到安全模式 |

> 顶栏按 tab 收起**只能改高度**，原因见 [`ARCHITECTURE.md`](ARCHITECTURE.md) 5.3。

---

## 二、设备页（tab 0）

`res/layout/fragment_device.xml`，容器 id `deviceContentContainer`。

**① 设备概览**卡片 —— 12 个字段：

设备名称、设备代号、Android 版本、内核版本、SELinux 状态、当前 Slot、
系统版本（Build Display）、安全补丁日期、基带版本、安卓 ID、电池循环次数、构建指纹。

**② CPU 状态** —— `llCpuCoreRings`，按核心渲染环形仪表（`ui/widget/CpuRingGaugeView.java`）。

---

## 三、刷机页（tab 1）

`res/layout/page_flash.xml` + `ui/flash/FlashPage.java`。
顶部是**设备下拉**（`btnFlashDevices` / `tvFlashDeviceSummary`），下面是 **11 张等高卡片**
（高 76dp、间距 12dp）。

### 刷机动作（8 张）

| 卡片 | id | 说明 | 页面 |
| --- | --- | --- | --- |
| 小米线刷 | `cardFlashMi` | 小米 · 红米固件包线刷 | `MiFlashActivity` |
| 欧加真线刷 | `cardFlashOplus` | OPPO · 一加 · realme 固件线刷 | `OplusFlashActivity` |
| 刷入 KernelSU | `cardFlashKernelSu` | 刷写 `init_boot` / `boot` 获取 Root | `KernelSuActivity` |
| 高通 QDL | `cardFlashQdl` | 9008 EDL 深刷模式 | `QdlActivity` |
| 刷写分区 | `cardFlashPartition` | 单分区 Fastboot 刷写 | `PartitionFlashActivity` |
| 设备重启 | `cardFlashReboot` | 重启到系统 / Recovery / Bootloader | `RebootActivity` |
| ADB Sideload 卡刷 | `cardFlashSideload` | Recovery 下刷入 OTA 包 | `SideloadActivity` |
| 执行命令 | `cardFlashExec` | 自定义 ADB / Fastboot / Shell 命令 | `ExecCommandActivity` |

### 「应用工具」分组（3 张，`Widget.Aurora.Miuix.GroupTitle` 下）

| 卡片 | id | 说明 | 页面 |
| --- | --- | --- | --- |
| 应用卸载冻结 | `cardAppUninstall` | 批量卸载 · 冻结设备上的应用 | `AppUninstallActivity` |
| 免 root 激活 | `cardAppActivate` | Shizuku · 小黑屋 · 冰箱 | `ActivateActivity` |
| 应用安装 | `cardAppInstall` | 选择安装包 · 支持批量安装 | `InstallActivity` |

> [!important] 刷机页的语义
> **Aurora 是主机（刷机工具），操作对象是连着的那台目标设备。**
> 这里的「免 root」= **目标机**不需要 root（主机用内置 adb 打官方激活命令），
> 不是「激活本机权限」。

### 设备状态刷新

四层触发，保证「不重启 App 也能看到新插入的设备」：

1. 页面可见时每 **5 秒**轮询；
2. USB 插拔广播 —— **必须延后 900ms 再探测**
   （广播在「总线枚举到设备」那一刻发出，此时 `adb devices` / `fastboot devices` 还没列出来）；
3. 切页 / 回前台立刻追一次；
4. 打开设备下拉时**先渲染缓存**，再后台追一次。

**轮询必须静默**：不显示「正在检测…」、不置灰卡片、失败不 toast；
只有会话内第一次带进度提示；失败时**保持上次显示，不清空**。

---

## 四、玩机页（tab 2）

`res/layout/fragment_explore_placeholder.xml` + `ui/tune/TunePage.java`。

### 顶部两张状态卡

| 卡片 | 内容 |
| --- | --- |
| Root 状态 | 「已获取ROOT」/ Root Manager：KernelSU 等 |
| 开机时间 | uptime |
| 状态摘要 | `cardTuneSummary` + `tvTuneSummaryDetail`：实时显示调速器与频率，例如「调速器 schedutil / performance · 实时 1234 MHz」 |

### 基础功能（6 项）

| 入口 | id | 页面 |
| --- | --- | --- |
| Aurora 插件 | `btnAuroraPlugin` | `AuroraPluginActivity` |
| SELinux 管理 | `btnSelinuxManager` | `SelinuxManagerActivity` |
| 分区管理 | `btnPartitionManager` | `PartitionManagerActivity` |
| 模块管理 | `btnModuleManager` | `ModuleManagerActivity` |
| 字库备份 | `btnFontLibraryBackup` | `FontLibraryBackupActivity` |
| 模块备份 | `btnModuleBackup` | `ModuleBackupActivity` |

### 扩展功能（5 项）

| 入口 | id | 页面 |
| --- | --- | --- |
| 应用管理 | `btnAppManager` | `AppManagerActivity` |
| 全局机型伪装 | `btnGlobalDeviceSpoof` | `GlobalDeviceSpoofActivity` |
| 设备 ID 修改 | `btnDeviceIdModify` | `DeviceIdModifyActivity` |
| 隐藏状态栏 | `btnHideStatusBar` | （MainActivity 内联处理，无独立页） |
| 屏幕 | `btnDisplayTune` | `DisplayTuneActivity` |

### 实用功能（4 项）

| 入口 | id | 页面 | 说明 |
| --- | --- | --- | --- |
| OTA 云提取 | `btnPayload` | `PayloadActivity` | 从 OTA 包中提取 payload |
| CPU 控制 | `btnCpuControl` | `CpuControlActivity` | — |
| 进程管理 | `btnProcessList` | `ProcessListActivity` | 查看 / 搜索 / 结束系统进程，支持按 CPU 与内存排序 |
| 帧率记录 | `btnFpsRecord` | `FpsRecordActivity` | 记录系统帧率并生成曲线与统计 |

---

## 五、设置页（tab 3）

`res/layout/fragment_settings.xml` + `ui/settings/SettingsFragment.java`。

| 分组 | 行 | 说明 |
| --- | --- | --- |
| 通用 | 自动检查更新（`swCheckUpdate`） | 只管「启动时检不检」，24 小时内最多请求一次；没有新版本或失败都不提示 |
| 通用 | 检查更新（`rowCheckUpdate`） | 立刻手动检查一次，摘要常驻 `当前版本 x.y.z · 上次检查 …` |
| 外观 | 主题（`rowTheme`） | 色彩模式 · 强调色 · 调色板 · 液态玻璃 · 字号 → `ThemeActivity` |
| 关于 | 关于 Aurora（`rowAbout`） | 单行入口，摘要动态显示版本号 → `AboutActivity` |

### 检查更新（`core/update` + `ui/update`）

| 项 | 实现 |
| --- | --- |
| 数据源 | GitHub Releases API：`GET /repos/{owner}/{repo}/releases/latest` |
| 仓库地址 | `gradle.properties` 的 `aurora.updateRepo` → `BuildConfig.UPDATE_REPO`（Fork 后改一行即可） |
| 网络 | `HttpURLConnection`，连接/读取超时各 8s，响应体上限 512 KB |
| 版本比较 | 数字分段比较（`1.10.0 > 1.9.0`），忽略前导 `v` 与 `+` 元数据，`-beta` 低于正式版 |
| 结果 | 三种形态共用一个对话框：有新版本 / 已是最新 / 检查失败（失败也给「手动查看」出口） |
| 自动检查 | 启动后延后 3s 静默检查；门控 = 开关开 且 距上次成功检查 ≥ 24h 且 该版本没点过「稍后」 |
| 边界 | 只查不装：不下载 APK、不调安装器、不需要 `REQUEST_INSTALL_PACKAGES` |
| 失败码 | 网络不可达 / 限流（403·429）/ 仓库不存在（404）/ 配置错误 / 其他 HTTP |

### 关于页（`AboutActivity`，独立 Activity）

`res/layout/activity_about.xml` + `ui/about/AboutActivity.java`。顶部自绘返回箭头（全局主题是
`NoActionBar`，`getSupportActionBar()` 恒为 null），下面是五个分组：

| 分组 | 内容 |
| --- | --- |
| 头部卡 | 应用图标 + 名称 + `版本名 (版本码)` + 一句话定位 |
| 版本信息 | 版本名称 / 版本码 / 包名 / 构建类型（全部取自 `BuildConfig` 与 `getPackageName()`，不写死） |
| 开源 | 上游项目 violet_Box（紫罗兰Box）· GPL-3.0 二次开发；开源协议 GPL-3.0 |
| 鸣谢 | KernelSU（主题能力与 Miuix 范式参考）/ 星野Scene（监视悬浮窗参考）/ Miuix（尺寸字号范式来源） |
| 作者 | 哔哩哔哩 / 酷安 / GitHub / 抖音 |

除「鸣谢」里的星野Scene、Miuix 两行（无公开仓库地址，故不可点）外，其余可点行都跳系统浏览器。

---

## 六、主题设置（独立页，非 tab）

入口：设置页 → 主题。宿主 `ThemeActivity.kt`，界面是整页 Compose（`ThemeScreen.kt`，864 行）。

| 项 | 可选项 |
| --- | --- |
| 色彩模式 | 跟随系统 / 浅色 / 深色 / **深色 · AMOLED**（4 种） |
| 强调色 | **16 种**种子色 |
| 调色板风格 | **9 种**：TonalSpot / Neutral / Vibrant / Expressive / Fidelity / Content / Monochrome / Rainbow / FruitSalad |
| 色彩规范 | 2021 规范 / 2025 规范（2 种） |
| 字号 | 0.85× ~ 1.30× |
| 液态玻璃 | 开 / 关（独立开关） |
| 背景模糊 | 开 / 关（独立开关，与液态玻璃**互不影响**） |
| 滑动切换 tab | 开 / 关 |

细节见 [`subsystems/THEME.md`](subsystems/THEME.md)。

---

## 七、监视悬浮窗

入口：**仅在「设备」页的顶栏** → 「使用监视器」→ 弹层 `dialog_usage_monitor`（6 个开关）。

| 类型 | `MonitorType` | 说明 | 窗口实现 |
| --- | --- | --- | --- |
| 系统监视器 | `SYSTEM` | CPU / 内存 / 负载总览 | `SystemOverlayWindow` |
| 进程监视器 | `PROCESS` | 进程维度：**可滚动列表**（最多 30 行）+ 应用图标 + 友好名 + CPU% | `ProcessOverlayWindow` |
| 线程监视器 | `THREAD` | 线程维度 | `ThreadOverlayWindow` |
| 迷你监视器 | `MINI` | 紧凑单行 | `MiniOverlayWindow` |
| 帧率记录器 | `FPS` | 帧率曲线 | `FpsOverlayWindow` |
| 温度监视器 | `TEMPERATURE` | 温度 | `TemperatureOverlayWindow` |

开关状态持久化在 `MonitorType.prefKey` 对应的偏好项里；
悬浮窗权限未授予时开关会被记住，**授权返回后自动开启**。

细节见 [`subsystems/MONITOR.md`](subsystems/MONITOR.md)。

---

## 八、完整 Activity 清单（35 个）

`☆` = 在 `AndroidManifest.xml` 里声明了完整 `configChanges`（刷机子页，
理由见 [`DEVELOPMENT.md`](DEVELOPMENT.md) 第六节）。

### 主框架与设置（3 个）

| Activity | 入口 |
| --- | --- |
| `ui/main/MainActivity` | LAUNCHER |
| `ui/settings/ThemeActivity.kt` | 设置 → 主题 |
| `ui/about/AboutActivity` | 关于 |

### 刷机（`ui/flash`，12 个）

| Activity | 入口 | |
| --- | --- | --- |
| `FlashConsoleActivity` | — | **抽象基类**，不是页面 |
| `OplusFlashActivity` ☆ | 刷机 → 欧加真线刷 |
| `RebootActivity` ☆ | 刷机 → 设备重启 |
| `KernelSuActivity` ☆ | 刷机 → 刷入 KernelSU |
| `MiFlashActivity` ☆ | 刷机 → 小米线刷 |
| `PartitionFlashActivity` ☆ | 刷机 → 刷写分区 |
| `SideloadActivity` ☆ | 刷机 → ADB Sideload |
| `ExecCommandActivity` ☆ | 刷机 → 执行命令 |
| `QdlActivity` ☆ | 刷机 → 高通 QDL |
| `AppUninstallActivity` ☆ | 应用工具 → 应用卸载冻结 |
| `ActivateActivity` ☆ | 应用工具 → 免 root 激活 |
| `InstallActivity` ☆ | 应用工具 → 应用安装 |

> `ui/flash` 下另有若干**非 Activity** 文件（`FlashPage` / `FlashFilePicker` /
> `DeviceAppAdapter` / `FlashDeviceDropdown` 等），完整清单见
> [`subsystems/FLASH.md`](subsystems/FLASH.md)。

### 玩机（`ui/`，21 个）

| Activity | 入口 |
| --- | --- |
| `ui/plugin/AuroraPluginActivity` | 基础功能 → Aurora 插件 |
| `ui/plugin/SusfsConfigActivity` | Aurora 插件 → SUSFS 配置 |
| `ui/plugin/TrickyStoreConfigActivity` | Aurora 插件 → Tricky Store |
| `ui/plugin/TrickyStoreAppListActivity` | Tricky Store → 应用列表 |
| `ui/plugin/TrickyStoreHashActivity` | Tricky Store → Hash |
| `ui/plugin/TrickyStoreSecurityPatchActivity` | Tricky Store → 安全补丁 |
| `ui/plugin/HiddenAppListConfigActivity` | 隐藏应用列表配置 |
| `ui/plugin/KernelDisguiseActivity` | 内核伪装 |
| `ui/plugin/GlobalDeviceSpoofActivity` | 扩展功能 → 全局机型伪装 |
| `ui/plugin/DeviceIdModifyActivity` | 扩展功能 → 设备 ID 修改 |
| `ui/selinux/SelinuxManagerActivity` | 基础功能 → SELinux 管理 |
| `ui/partition/PartitionManagerActivity` | 基础功能 → 分区管理 |
| `ui/module/ModuleManagerActivity` | 基础功能 → 模块管理 |
| `ui/module/ModuleBackupActivity` | 基础功能 → 模块备份 |
| `ui/font/FontLibraryBackupActivity` | 基础功能 → 字库备份 |
| `ui/appmanager/AppManagerActivity` | 扩展功能 → 应用管理 |
| `ui/display/DisplayTuneActivity` ☆ | 扩展功能 → 屏幕 |
| `ui/payload/PayloadActivity` | 实用功能 → OTA 云提取 |
| `ui/cpu/CpuControlActivity` | 实用功能 → CPU 控制 |
| `ui/process/ProcessListActivity` | 实用功能 → 进程管理 |
| `ui/fps/FpsRecordActivity` | 实用功能 → 帧率记录 |

---

## 九、功能完成度的两个已知缺口

| 缺口 | 现状 |
| --- | --- |
| **高通 QDL 只做了「读」不做「写」** | 已实现：识别分区表、识别启动槽位、切 A/B 槽位、复位设备。`owrite` / `oread` / `oerase` 的命令模板已就绪但**无调用方** |
| **QDL 引导库未内置** | 既有工具用 `Loader.7z`（16MB / 265 机型）按品牌自动判定欧加授权档位；本项目没搬，改为**让用户手选**授权档 |
