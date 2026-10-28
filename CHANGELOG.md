# 更新日志

本文件记录 Aurora 的所有重要变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> 版本号与 `app/build.gradle.kts` 的 `versionName` / `versionCode` 一一对应，
> 发版时两处必须同时改。

---

## [Unreleased]

下一个版本要做的改动放这里。发布时把本段整段改名为 `## [x.y.z] - YYYY-MM-DD`。

---

## [1.1.4] - 2026-10-28

### 变更

- **高通 QDL 深刷的输出目录改为 `/storage/emulated/0/Aurora/qdl`**（原为外部工具名的
  同名目录）。`rgpt` 每次识别分区表都会重新导出，旧目录无需迁移，也不会被清理。
- **刷机子页的按钮文案重写**：`选取 init_boot / boot 镜像`、`选择 KMI 版本`、
  `开始修补镜像`、`读取当前槽位`、`选取 .img 文件`、`切换到另一槽位`、
  `写入 boot 分区`、`按分区名写入`、`执行写入`、`开始卡刷`。
- **EDL 热插拔警告换成本项目措辞**：`EDL 模式不支持热插拔，连接后请勿断开数据线，
  并确保 USB 连接稳定`。语义与原提示一致。
- KernelSU 修补页的弹窗标题与提示（`选择 KMI 版本` / `请先选择 KMI 版本`）同步调整 ——
  这两处在运行时代码里，不在布局中。

### 许可

- 新增 `NOTICE`：随包分发的 5 个二进制（adb、fastboot、magiskboot、libksud、qdl）
  逐项列出上游来源与许可；写明 qdl 为 linux-msm/qdl 的第三方定制分支、本仓库
  不含其源码，并给出内容下架通道。
- README 致谢补一行中性说明：刷机流程的行为参考同类工具的实际表现，界面与代码
  为本项目独立实现。

### 内部

- 清理刷机子系统里遗留的外部实现引用：类注释中的点名、反编译产物类名、外部资源名，
  共约 200 处，全部改为中性叙述。功能与命令模板未变。

### 修复

- **「检查更新」对话框在更新日志较长时会超出屏幕**，底部按钮被顶到可见区域之外。
  根因：正文区挂的 `android:maxHeight` 是 `ImageView` 的属性，`ScrollView` 不识别、
  静默忽略 —— 写了等于没写，Release 正文多长对话框就有多高。现改为整张卡片装进
  限高滚动容器（最高 82% 屏高），内容短时仍按内容自适应高度。

---

## [1.1.3] - 2026-10-22

### 修复

- **设置页「通用」卡片两行文字完全重叠**（1.1.2 那次没修对）。真因不在字体排版：
  `MaterialCardView` 继承 `CardView`，而 `CardView` 就是 `FrameLayout` —— 卡里直接
  并排两个 `LinearLayout` 时，两行子 View 全从 (0,0) 开始摆放，「检查更新」整条压在
  「自动检查更新」身上。已补一层纵向容器收口，并把行内边距由四边 16dp 拆成
  「水平 16dp + 垂直 12dp」，卡片内两行间距 24dp、行高统一 72dp。

---

## [1.1.2] - 2026-10-22

### 修复

- **设置页「标题 + 摘要」两行文字挤在一起**。根因是 Android 15 起
  `includeFontPadding` 默认值改为 false（本项目 targetSdk 36），中文字形的
  上下留白被裁到边界，降部顶到下一行升部。已给 `PrefTitle` / `PrefSummary` /
  `GroupTitle` 显式恢复 `includeFontPadding=true`，并把 19 处摘要的
  `layout_marginTop` 由 2dp 抬到 4dp（dp 不随字号缩放，字号调大后 2dp 会越来越窄）。
  设置页、关于页、QDL 页同一范式一并修正。

---

## [1.1.1] - 2026-10-22

### 变更

- **关于页「作者」换成本项目作者**：酷安 → `coolapk.com/u/26622080`，GitHub → `github.com/LonLsme`；
  抖音行直接显示账号 `36398056279`（抖音号拼不进主页 URL，网页版主页路径要 sec_uid，
  因此点击走「按号搜索」）。哔哩哔哩一行移除。
- 关于页底部声明补上 `© Smart-Paocai`：上游署名不随作者换人而消失。

---

## [1.1.0] - 2026-10-22

首个**公开发布**的版本。

### 新增

- **应用内自动更新**：设置页「检查更新」现在走完整链路 —— 检查（GitHub Releases API，
  启动时静默、24 小时最多一次）→ 应用内下载 APK（带进度、速度、可取消）→
  SHA-256 与签名双校验 → 调起系统安装器。摘要三级回落（资产 `digest` / Release 正文 /
  `.apk.sha256` 侧车资产），三级全空时**拒绝安装**并引导手动下载；签名与当前版本不一致时
  提前说明「需先卸载」。关闭下载框不中断下载，下次进来自动进入「已就绪」态可直接安装。
  仓库地址由 `gradle.properties` 的 `aurora.updateRepo` 决定，Fork 后改一行即可。
  新增 `REQUEST_INSTALL_PACKAGES` 权限。设计说明见 [`docs/subsystems/UPDATE.md`](docs/subsystems/UPDATE.md)。
- **发版流水线**：`.github/workflows/release.yml` —— 推 `v*` tag 自动跑测试、构建 APK、
  产出 `.apk.sha256` 侧车、从 `CHANGELOG.md` 抽本版段落生成 Release 正文，挂成草稿 Release，
  人工点 Publish 后才会被 App 的检查更新读到。构建前校验 tag 与 `versionName` 一致
  （不一致直接失败），tag 带连字符时自动标为预发布。附 `dependabot.yml` 做依赖月巡检。
- **刷机页「执行命令」**：自定义 ADB / Fastboot / Shell 命令下发到目标设备。输入**不做整串转义**
  （用户输入本身即命令行，管道、引号、重定向均可使用）；不设命令白名单，改为每条必过确认框并
  完整展示拼装后的命令原文。附 12 条命令历史（点回填 / 长按删除）。
- **关于页重构**：设置页的「关于」收成单行入口，新关于页分「版本信息 / 开源 / 鸣谢 / 作者」四段，
  版本字段全部取自 `BuildConfig`，不再手写。「开源」段首行是本项目 GitHub 仓库，地址由
  `gradle.properties` 的 `aurora.updateRepo` 拼出，与检查更新共用同一份配置。
- **核心扩展模块改名**：`violet_box_module` → `aurora_box_module`，持久化配置目录同步迁移，
  安装时自动搬运旧配置并清理旧模块目录。

### 变更

- **备份规则写实**：`allowBackup` 只保留偏好类数据，排除工具链解压版本号（恢复到新机会导致
  「刷机工具找不到 adb」）、命令历史、设备标识修改记录、字体备份等本机相关项。

- 进程监视器对齐星野Scene：展开后为可滚动列表（最多 30 行），展示应用图标、友好名与 CPU 占用；
  首帧直接按累计 CPU 时间排序（此前首次采样必然为空列表）。
- 主题预览卡与实机底栏统一取色来源（`surfaceContainer`，不参与 AMOLED 黑化），底栏按居中胶囊绘制。
- 主题页滑动流畅化：配色方案按 `(深色, 强调色, 风格, 规范)` 四元组记忆化，强调色行改为
  `Row + horizontalScroll`，`LazyColumn` 全量补 `key`，`getPrefs()` 静态缓存。

### 移除

- **动态取色（Material You）**：部分国产 ROM 裁掉了 `system_accent1_*` 系统色，打开开关即闪退，
  功能整体移除（含 `values-v31/` 与 `Theme.Aurora.Dynamic`）。
- 玩机页顶部「调速器 · 实时 MHz」栏。

### 修复

- 进程列表行数减少时，残留行显示为 `--` 占位。
- 「关于」页在 `NoActionBar` 主题下既无标题也无返回键 —— 改为自绘顶栏。
- 删除 `res/drawable/about_avatar.png`：它实际是 JPEG 却用了 `.png` 扩展名，且无任何引用。
  `assembleDebug` 能过，`assembleRelease` 的 AAPT crunch 直接报 `file failed to compile`，
  等于 release 包打不出来。

---

## [1.0.0] - 2026-08-28

第一个以 **Aurora** 名义构建的里程碑版本（内部版本，未公开发布），在
[violet_Box（紫罗兰Box）](https://github.com/Smart-Paocai/violet_Box)（GPL-3.0）基础上二次开发。

### 新增

- 四个 tab 的主界面：**设备 / 刷机 / 玩机 / 设置**。
- **设备页**：设备概览（型号、Android 版本、内核、SELinux、Slot、安全补丁、基带等）、
  按核心渲染的 CPU 环形仪表盘。
- **刷机页**：小米线刷、欧加真线刷、刷入 KernelSU、高通 QDL 9008 深刷、单分区刷写、设备重启、
  ADB Sideload，以及应用工具（卸载冻结、免 root 激活、应用安装）。
- **玩机页**：Aurora 插件（SUSFS、Tricky Store、隐藏应用列表、内核伪装）、SELinux 管理、
  分区管理、模块管理、字库备份、应用管理、全局机型伪装、设备 ID 修改、隐藏状态栏、
  屏幕参数（分辨率 / 密度 / 刷新率）、OTA 云提取、CPU 控制、进程管理、帧率记录。
- **主题引擎**：4 种色彩模式（含 **AMOLED 纯黑**）× 16 种强调色 × 9 种调色板风格 ×
  2 种色彩规范，运行时按 sRGB ↔ Lab ↔ LCh 生成配色并同步生成 XML 覆盖层。
- **6 种监视悬浮窗**：系统监视器、进程监视器、线程监视器、迷你监视器、帧率记录器、温度监视器，
  可同时开启、可拖动。
- **核心扩展模块**（Magisk / KernelSU）：内核伪装（`ksu_susfs set_uname`）与全局机型伪装
  （`resetprop`），支持选择在 `post-fs-data` 或 `service` 阶段生效。

### 变更

- 移除上游 violet_Box 的作者更新检查。
- 六个悬浮窗统一为「折叠 / 展开」双态架构。

### 已知问题

- Android 12+ 受 SELinux 限制，`untrusted_app` 无法读取 `/proc/stat`、`/sys/class/thermal/**` 等，
  部分系统数据在无 Root 时显示为 `--`，属预期行为（见 [`docs/subsystems/SYSTEM_DATA.md`](docs/subsystems/SYSTEM_DATA.md)）。

---

## 上游

本项目继承自 [violet_Box](https://github.com/Smart-Paocai/violet_Box)，其历史变更请查阅上游仓库。
