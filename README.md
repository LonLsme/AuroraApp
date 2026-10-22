# Aurora（极光）

**一款面向高级玩家 / Root 用户的 Android 设备工具箱。**
刷机、分区、模块、SELinux、系统监视、主题定制 —— 全部收在一个应用里，不依赖任何云端账号。

在 [violet_Box（紫罗兰Box）](https://github.com/Smart-Paocai/violet_Box)（GPL-3.0）基础上二次开发，
界面按 Aurora Design System 重做（液态玻璃底栏、16 强调色 × 9 调色板、AMOLED 纯黑）。

> Performance. Beautifully Controlled. —— 掌控每一瓦性能。

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-green.svg)
![Target](https://img.shields.io/badge/targetSdk-36-lightgrey.svg)
[![Build](https://github.com/LonLsme/AuroraApp/actions/workflows/build.yml/badge.svg)](https://github.com/LonLsme/AuroraApp/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/LonLsme/AuroraApp?include_prereleases&sort=semver)](https://github.com/LonLsme/AuroraApp/releases)

> [!warning] 涉及设备写入，请先读完再装
> 本应用包含**刷写分区、关闭 AVB 校验、9008 深刷**等不可逆操作，且需要 root 才能发挥完整能力。
> 请确认你选的是**正确设备对应的镜像**。因误操作导致的设备变砖不在维护者责任范围内。

---

## 功能

主界面四个 tab：**设备 / 刷机 / 玩机 / 设置**。

### 设备

- 设备概览：型号与代号、Android 版本、内核版本、SELinux 状态、当前 Slot、安全补丁、基带、构建指纹
- CPU 状态：按核心渲染的环形仪表盘
- 顶栏：6 种**监视悬浮窗**开关、快捷重启（系统 / Bootloader / FastbootD / Recovery / EDL / 安全模式）

### 刷机

Aurora 在这里是**刷机主机**，操作对象是**用 USB 连着的那台设备**。

| 卡片 | 能力 |
| --- | --- |
| 小米线刷 | 小米 / 红米固件包（跑厂商脚本） |
| 欧加真线刷 | OPPO / 一加 / realme 固件（物理分区与逻辑分区分两段） |
| 刷入 KernelSU | 刷写 `init_boot` / `boot` 获取 Root |
| 高通 QDL | 9008 EDL 深刷：识别分区表、切 A/B 槽位、复位 |
| 刷写分区 | 单分区 Fastboot 刷写 |
| 设备重启 | 系统 / Recovery / Bootloader / FastbootD / EDL |
| ADB Sideload | Recovery 下卡刷 OTA 包 |
| **执行命令** | 自定义 ADB / Fastboot / Shell 命令下发到目标设备，附命令历史 |
| 应用工具 | 应用卸载冻结（批量）、免 root 激活、应用安装 |

### 玩机

- **基础**：Aurora 插件（SUSFS / Tricky Store / 隐藏应用列表 / 内核伪装）、SELinux 管理、
  分区管理、模块管理、字库备份、模块备份
- **扩展**：应用管理、全局机型伪装、设备 ID 修改、隐藏状态栏、屏幕（分辨率 / 密度 / 刷新率）
- **实用**：OTA 云提取、CPU 控制、进程管理、帧率记录

### 主题

4 种色彩模式（跟随系统 / 浅色 / 深色 / **AMOLED 纯黑**）× 16 种强调色 ×
9 种调色板风格 × 2 种色彩规范（2021 / 2025），配色在运行时按 sRGB ↔ Lab ↔ LCh 现算，
并同步生成 XML 覆盖层供 View 体系使用。支持字号缩放（0.85× ~ 1.30×）。

### 监视悬浮窗

6 种可同时开启、可拖动的悬浮窗：系统监视器（CPU / GPU / 电池 / 内存四环）、进程监视器、
线程监视器、迷你监视器、帧率记录器、温度监视器。

完整清单见 [`docs/FEATURES.md`](docs/FEATURES.md)。

### 设置

- **自动检查更新**：开启后每次启动静默检查一次，**24 小时内不重复请求**；没有新版本或检查失败都**不留任何痕迹**
- **检查更新**：手动触发；发现新版本后可在应用内**下载 APK → 校验 → 调起系统安装器**
- 主题入口、关于页入口

更新信息来自 **GitHub Releases**（`releases/latest`）。安装前必须拿到发布方给出的 SHA-256：
优先用 GitHub 为资产计算的 `digest` 字段，其次用 Release 正文里的 `SHA-256：…`，
最后用随包发布的 `.apk.sha256` 侧车文件；**三者都取不到时拒绝安装**，只引导去网页手动下载。
校验还包括包名与新包签名 —— 签名与当前安装版本不一致会提前提示「需先卸载」，
而不是让你下完几十 MB 才被系统拒。

> 第一次点「安装」时系统会要求授予「安装未知应用」权限（`REQUEST_INSTALL_PACKAGES`），
> 对话框里直接给出「去授权」入口。请求不携带任何设备标识，详见 [`SECURITY.md`](SECURITY.md)。

> Fork 本项目或改仓库名后，**必须**改 `gradle.properties` 里的 `aurora.updateRepo`，
> 否则检查更新会一直报「仓库或 Release 不存在」。

---

## 快速开始

### 环境要求

| 项 | 版本 | 备注 |
| --- | --- | --- |
| **JDK** | **21** | AGP 9.x 强制要求；JDK 17 会在配置阶段直接失败 |
| **Android Studio** | Narwhal 或更新 | 需要支持 AGP 9.1.1 |
| **Android SDK Platform** | **36.1** | 注意不是 36.0 —— `compileSdk` 用了 minor 版本 |
| **Gradle** | 9.5.1 | 由 Wrapper 自动下载，无需手动安装 |

### 构建

```bash
git clone https://github.com/LonLsme/AuroraApp.git
cd AuroraApp

# 1) 指明 SDK 位置（这个文件不会进版本库）
#    Linux / macOS
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
#    Windows
#    echo "sdk.dir=C\:\\Android\\Sdk" > local.properties

# 2) 编译 Debug 包（首次会下载依赖，约 150 MB）
./gradlew :app:assembleDebug
#    Windows 用 gradlew.bat :app:assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

跑单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

**Release 包**需要自带签名。在项目根目录建 `keystore.properties`（已在 `.gitignore` 里）：

```properties
storeFile=/绝对路径/aurora-release.jks
storePassword=******
keyAlias=aurora
keyPassword=******
```

没有这个文件时 `assembleRelease` 仍能跑通，只是产出未签名 APK —— 只是来编译看看的人不会被卡住。

### 改仓库地址（Fork 必看）

应用内的「检查更新」通过 GitHub Releases API 查最新版本，仓库地址来自 `gradle.properties`：

```properties
aurora.updateRepo=LonLsme/AuroraApp
```

Fork 之后把它改成 `你的用户名/仓库名` 即可，**不需要动任何 Java 代码**。
也可以在命令行临时覆盖：`./gradlew :app:assembleDebug -Paurora.updateRepo=you/AuroraApp`。

检查更新读的是 `releases/latest` —— **草稿与预发布不会被算进去**，所以正式发布之前不会误报有更新。

### 发版

```bash
# 1) 改 app/build.gradle.kts 的 versionCode / versionName，并在 CHANGELOG.md 记一条
# 2) 打 tag 并推送
git tag v1.2.0
git push origin v1.2.0
```

[`.github/workflows/release.yml`](.github/workflows/release.yml) 会自动跑测试、构建 Release APK、
算出 SHA-256 并产出 `Aurora-v1.2.0.apk.sha256` 侧车文件、从 `CHANGELOG.md` 抽本版段落生成发布说明，
再把这两个文件挂到一个**草稿** Release 上。到 GitHub 上点 **Publish release** 即完成
（草稿不算发布，App 的检查更新看不到）。

构建前会校验 tag 与 `versionName` 是否一致（`v1.2.0` ↔ `1.2.0`），不一致**直接失败** ——
包里写 1.1.0 而 Release 标 v1.2.0，用户的「检查更新」会永远认为已是最新。
tag 里带连字符（如 `v1.2.0-beta.1`）会被自动标为预发布，不会推给正式用户。

> 想在 CI 里产出**已签名**的包，需配置四个 secret：`SIGNING_KEYSTORE_BASE64`（base64 后的 jks）、
> `SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。
> 不配也能跑通，只是产物未签名、无法直接安装。
> 配了则构建后会多跑一步 `apksigner verify`，keystore 配错当次构建就红，不必等用户装上才发现。

### 常见问题

| 现象 | 原因与解法 |
| --- | --- |
| `.lck (拒绝访问)` / `Timeout waiting to lock` | Android Studio 正占着 Gradle 缓存。关掉 IDE 再构建，**不要**去杀 daemon 或删缓存 |
| `Unsupported class file major version` | 用的不是 JDK 21。`./gradlew -version` 确认 Launcher JVM |
| `Failed to find target with hash string 'android-36.1'` | 只装了 android-36。在 SDK Manager 里勾选 **Android 16 (API 36.1)** |
| 依赖下载失败 | 网络原因。配好代理后重试，不要加 `--offline`（首次构建没有缓存） |
| 装了包但功能都显示 `--` | 没给 root，或系统限制了读取（见下文「已知限制」） |

---

## 项目结构

```
AuroraApp/
├── app/src/main/
│   ├── java/com/aurora/app/      116 个源文件 · 35,482 行
│   │   ├── ui/                   表现层（22 个子包）：Activity / Fragment / 自定义 View / Compose
│   │   ├── core/                 能力层（6 个子包）：root / flash / monitor / display / cpu / util
│   │   ├── data/                 数据层：Root 检测、运行时探测、模型
│   │   ├── receiver/             系统事件入口（USB 插拔等）
│   │   └── AuroraApplication.java
│   ├── assets/
│   │   ├── flash_tools.zip       刷机工具链：adb / fastboot / libksud / magiskboot / qdl（aarch64）
│   │   └── aurora_box_module.zip 核心扩展模块（Magisk / KernelSU）
│   └── res/
│       ├── layout/               60 个布局
│       └── values*/              attrs / colors / dimens / strings / themes + 自动生成的主题覆盖层
├── aurora_box_module/            Magisk / KernelSU 模块源码（独立打包，不参与 Gradle 构建）
├── docs/                         技术文档（见下方「文档地图」）
└── .github/                      Issue 模板、PR 模板、CI
```

**分层严格单向**：`ui → core → data`。`core/` 下**不允许** import 任何 `ui/` 的类，
这条没有例外 —— 它是这个项目还能继续加功能的前提。

`aurora_box_module/` 是一个独立的 Magisk / KernelSU 模块源目录，用 shell 写，
不打进 APK 的编译流程（它的 zip 会被复制进 `assets/` 随包分发）。

---

## 技术栈

| 项 | 值 |
| --- | --- |
| 语言 | Java 108 个文件 + Kotlin 8 个文件（Compose 组件） |
| UI | XML View 为主 + Compose（底栏、主题页）、Material 3、自研 iOS / Miuix 风格组件 |
| 构建 | AGP 9.1.1 · Kotlin 2.3.10 · Compose BOM 2026.02.01 · Gradle 9.5.1 |
| Root 执行 | 自研 `RootShell`（**未使用 libsu**），统一 `su -c` + 固定环境前缀 |
| 关键依赖 | rootbeer-lib（Root 检测）· OkHttp（OTA 云提取）· commons-compress + xz（payload 解析）· kyant0 backdrop / capsule（毛玻璃） |
| 刷机工具链 | adb · fastboot · libksud · magiskboot · qdl（aarch64，随包分发） |

依赖版本集中在 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)，不要在 `build.gradle.kts` 里写死版本号。

---

## 文档地图

| 文档 | 内容 |
| --- | --- |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 分层、模块地图、启动链路、四个 tab 的拼装方式、关键机制 |
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | 开发环境、项目约定、踩坑清单、离线验证方法、交付流程 |
| [`docs/FEATURES.md`](docs/FEATURES.md) | 按页面组织的功能清单与完整 Activity 表 |
| [`docs/subsystems/FLASH.md`](docs/subsystems/FLASH.md) | 刷机链路：工具链、执行引擎、设备探测、命令模板、11 个子页 |
| [`docs/subsystems/QDL.md`](docs/subsystems/QDL.md) | 高通 QDL 9008 深刷 |
| [`docs/subsystems/THEME.md`](docs/subsystems/THEME.md) | 主题引擎：调色板算法、覆盖层生成、attr 规则、AMOLED |
| [`docs/subsystems/MONITOR.md`](docs/subsystems/MONITOR.md) | 监视悬浮窗：窗口体系、拖动实现、图表控件、采集器 |
| [`docs/subsystems/SYSTEM_DATA.md`](docs/subsystems/SYSTEM_DATA.md) | **数据读不到时先看这份**：Android 12+ 的采数权限边界 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 环境、代码约定、提交规范、PR 流程 |
| [`CHANGELOG.md`](CHANGELOG.md) | 版本变更记录 |
| [`docs/archive/`](docs/archive/) | 历史分析快照（含过时数字，仅供追溯判断依据） |

---

## 已知限制

- **Android 12+ 的数据可见性**：受 SELinux 限制，`untrusted_app` 读不到 `/proc/stat`、
  `/sys/class/thermal/**`、`/sys/class/power_supply/**`（连列目录都拒绝，`listFiles()` 静默返回 null）。
  无 root 时这些指标显示 `--` 属正常行为，见 [`docs/subsystems/SYSTEM_DATA.md`](docs/subsystems/SYSTEM_DATA.md)。
- **`targetSdk 36` + `compileSdk 36.1`**：老版本 Android Studio 打不开本工程。
- **界面文案目前硬编码在布局与 Java 里**，尚未抽取 `strings.xml`。要加多语言支持，这是一项独立的大工作。
- **无混淆（R8 未开启）**：release 包体积偏大，混淆规则没有经过完整回归，见 `app/build.gradle.kts` 注释。

---

## 参与贡献

见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。最容易上手的方向：

- `core/` 下大量纯函数（命令拼装、颜色算法、索引解析）**还没有测试**，加一个就有价值
- 不同 ROM 上读不到的系统数据（欢迎附设备型号 + 系统版本）
- 把界面文案抽进 `strings.xml`，让项目具备国际化能力

提交前请跑一遍：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

---

## 许可证与致谢

本项目以 **GPL-3.0** 发布，见 [`LICENSE`](LICENSE)。修改、分发、商用都可以，
但**必须保留署名与版权声明，并以同一协议开源**。

```
Copyright (C) 2026 Smart-Paocai   — violet_Box 原始代码
Copyright (C) 2026 LonLsme         — Aurora 二次开发部分
基于 violet_Box（紫罗兰Box，GPL-3.0）二次开发
```

> 本仓库的源文件目前**没有逐个加版权头**（历史原因），版权与协议以 `README.md` + `LICENSE` 为准。
> 提交 PR 时不需要给文件加头；如果将来要批量补，请单独开一个只做这件事的提交。

| 来源 | 作用 |
| --- | --- |
| [violet_Box（紫罗兰Box）](https://github.com/Smart-Paocai/violet_Box) | **上游项目**（GPL-3.0），本项目在其基础上二次开发 |
| [KernelSU](https://github.com/tiann/KernelSU) | 主题能力与 Miuix 组件范式的参考 |
| HoshinoScene（星野Scene） | 监视悬浮窗的视觉与系统数据采集思路参考 |
| vtools | 进程与监视器实现的对照参考 |
| Miuix | 设置页与刷机子页的尺寸、字号、卡片范式来源 |

随包分发的 `assets/flash_tools.zip` 内含 adb / fastboot / magiskboot / libksud / qdl 的 aarch64 构建，
它们各自遵循其原始许可（adb、fastboot 为 Apache-2.0，qdl 相关组件为 GPL/LGPL 系）。

安全漏洞请走私密渠道，见 [`SECURITY.md`](SECURITY.md)。
