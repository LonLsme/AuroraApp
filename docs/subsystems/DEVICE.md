# 子系统：设备页 —— Root 通道与双数据源

> 设备页（tab 0）的数据分成「普通 API 能读」和「必须 root 才能读」两种。
> 遇到「SELinux 一片 `—`」「插了另一台手机没出现在下拉里」「切了数据源但显示本机数据」
> 「CPU 只显示 1 个核」，先看这篇。

---

## 一、一句话结论

**特权字段读不到就显示「—」，页面照常展示能读到的部分 —— 不收起、不替换成引导页。**
`ShellManager` 只回答一个问题：现在有没有 root。

| 字段 | 普通应用可读 | 说明 |
| --- | --- | --- |
| 机型 / 代号 / Android 版本 / Build Display / 安全补丁 / 指纹 / 基带 | ✅ | `Build.*` |
| 内核版本 | ✅ | `System.getProperty("os.version")` |
| 当前 Slot | ✅ | 反射 `ro.boot.slot_suffix` |
| 各核频率 | ✅ | `/sys/devices/system/cpu/cpu*/cpufreq/*`（Android 12+ 仍开放） |
| 电池循环次数 | ✅ / ⚠️ | Android 14+ 走 `BatteryManager`，更早只能读 sysfs `cycle_count`，多数机型没有 |
| **SELinux** | ❌ | `getenforce` 需要 root；Android 12 起三方应用读 `/sys/fs/selinux/enforce` 也被拒 |
| **BL 状态** | ✅ | `ro.boot.*` 三个属性在 AOSP 里标 `bootloader_prop`，应用身份就有 `get` 权限（真机 Redmi 23117RK66C / Android 16 / 无 root 实测直读得到 `green` / `1` / `locked`），**不需要 root**；判不出结论时才回退 root 再读一次 |
| **安卓 ID（可信值）** | ⚠️ | `Settings.Secure.ANDROID_ID` 能读，但国产 ROM 可能改写；`settings get secure android_id` 才是系统真值 |

---

## 二、为什么只剩 Root 一条通道

`core/shell/ShellManager.detect()` 现在只判 `RootShell.isRootAvailable()`：

| 结果 | 含义 |
| --- | --- |
| `ROOT` | `su` 可用，特权字段全部可读 |
| `NONE` | 没有 root，特权字段写「—」 |

### 2.1 免 root 通道（Shizuku / 无线调试）为什么被整体移除

两条通道能给的身份都是 **uid 2000（shell）**。它确实能补上几个只读字段，但本项目真正要
动手的地方 —— CPU 调频写 `/sys/devices/system/cpu/.../scaling_setspeed`、温控阈值、
部分 sysfs 节点 —— 属主是 root，**shell 写不进去**。

结果是用户多装一个 App、多授权一次，换来一半功能「能看不能改」。与其把两把权限不同的
钥匙摆在用户面前，不如让「能看到什么」和「能改什么」共用同一把。

被删掉的东西（别再自作主张加回来）：

| 删掉的 | 原来在哪 |
| --- | --- |
| `ShizukuBridge`（binder / 授权 / `IRemoteProcess`） | `core/shell/` |
| `Mode.SHIZUKU` 与对应路由分支 | `ShellManager` |
| `dev.rikka.shizuku:api` + `:provider` 依赖 | `libs.versions.toml` / `app/build.gradle.kts` |
| `ShizukuProvider` 清单声明 | `AndroidManifest.xml` |
| 授权引导卡 `cardAuthGuide` + 权限监听回调 | `fragment_device.xml` / `DeviceFragment` |
| 无线调试通道（mDNS / 配对 / `findSelfWirelessSerial`） | 更早一轮已删 |

> [!note] 刷机页的「免 root 启动 Shizuku」不是一回事
> `AppCommands.ACTIVATE_TARGETS` 里那条命令是**给被刷的那台手机**跑的激活脚本
> （让目标机上的 Shizuku / 小黑屋 / 冰箱起来），属于刷机页的「免 root 激活」功能。
> 它跟本 App 自己要不要提权无关，所以保留。

### 2.2 命令怎么执行

| 通道 | 实现 | 关键点 |
| --- | --- | --- |
| Root | `RootShell.exec()` | 持久化 `su` 会话，单条命令毫秒级；超时销毁会话 |
| 无 root | `ShellManager.exec()` 直接返回 `unavailable` | 调用方据此显示「—」，不当失败处理 |

`Result.unavailable`（通道不可用，命令根本没执行）与「执行了但失败」必须分开 ——
前者是正常的「没权限」，后者才需要看日志。

---

## 三、本机 / 外接：同一套卡片

**禁止摆两套视图。** 本机与外接设备的字段完全一样，做成上下两张卡片会让同一屏出现两份
「设备概览」，用户得先分辨哪张是哪台；两套 View 各自刷新、各自处理「读不到」，任何一侧
出问题都会连带整页重载。

- 共用：`cardDeviceOverview` + `cardCpu`
- 切换入口：**「设备概览」标题右侧的胶囊**（`btnDeviceSource`），不放页面顶部、不做悬浮
- 数据源列表：`本机` + `AdbBridge.externalSerials()`（排除连到自己那条，避免同一台设备出现两次）
  + 末尾常驻一行 **`添加设备…`**

切换时整份快照重读（`loadStatic(true)`），卡片结构不动，所以不存在「两张卡片各显示一半」的中间态。

| 读什么 | 本机 | 外接设备 |
| --- | --- | --- |
| 静态信息 | `Build.*` + BL 三属性（属性服务直读）+ 一次 shell（`getenforce` / `android_id`） | 一条 `adb shell`：`echo KEY=$(...)` 十几项 |
| 动态信息 | sysfs 直读，**1 秒**刷 | 每次一次 adb 往返，**2 秒**刷 |
| 刷新暂停 | `onPause` 移除 tick | 同左 |

> [!warning] 外接设备绝不逐字段发命令
> 每次 adb 往返 50~150ms，十几个字段就是一两秒。所有字段合并成一条 shell：
> `echo "MAN=$(getprop ...)"; echo "MODEL=$(...)"; ... ; <CPU 循环>`，
> 解析按 `KEY=VALUE` 行取。值与 `=` 极少共存，所以不需要转义 —— 但 **key 必须带编号**
> （`CPU0=`、`CPU1=`），全写 `CPU=` 的话后者覆盖前者，8 核只会剩 1 核。

### 3.1 外接设备为什么可能「插了也不出现」

这是被问得最多的一条。根因是平台边界，不是代码写错：

| 内置 adb 的身份 | USB 外接（OTG 线） | 网络设备（`adb connect`） |
| --- | --- | --- |
| root | **能枚举** | 能 |
| 应用（无 root） | **枚举不到** | 能 |

内置 adb 靠扫 `/dev/bus/usb/*` 发现 USB 上的手机，这些节点是 `root:usb 0660`：

```
crw-rw---- 1 root usb 189, 0 /dev/bus/usb/001/001
```

- root（uid 0）直接能开；
- 应用（uid ≥ 10000）连 `/dev/bus/usb` 目录都进不去；
- shell（uid 2000）也**不在 `usb` 组**里 —— 所以即便换了 shell 身份，这条路仍然走不通。

`AdbBridge.run()` 因此在有 root 时以 root 身份执行，没有 root 时退到应用身份（只够 `connect`）。
**判断身份时直接问 `RootShell.isRootAvailable()`，不要读 `ShellManager.state()` 的缓存** ——
首帧它还停在 `DETECTING`，按缓存判断会把 root 用户降级成应用身份，设备表少一半。

**代码层还有三个刷新时机问题（已修）：**

1. 列表原来只在门控状态变化时查一次 —— 页面开着插上线，下拉永远不更新。
   现在跟着 tick 一起轮询（root 5 秒 / 有外接 8 秒 / 都没有 30 秒）。
2. 每次查询都无条件 `setSources()`，而它会关掉正在展开的弹窗。
   现在先比序列号列表，没变化就不动下拉。
3. 连上以后没有「自动切过去」，用户还得自己再点一次下拉。
   现在 `pendingSelectSerial` 在设备出现时自动切换。

### 3.1.1 USB 直连的三条前提（不是「只能无线」）

`adb devices` 的输出里本来就同时有 USB 与网络两种 transport，代码从不区分来源，
所以 **USB 插上就能用，无线只是补充**。三条前提缺一不可：

| 前提 | 说明 |
| --- | --- |
| 本机有 root | 见上表：无 root 时应用身份进不去 `/dev/bus/usb` |
| 目标机开「USB 调试」+ OTG 线 | 本机当 host |
| **首次必须在目标机上点「允许」** | RSA key 交换绕不过。`adb devices` 先报 `unauthorized`，点了才变 `device` |

第三条是「插了没反应」的真正原因，所以下拉会**保留 `unauthorized` / `offline` / `authorizing`**
这些待就绪项（`AdbBridge.Snapshot.pending`），并在行下方写明「未授权，去对方手机上点「允许」」。
选中它只弹说明框、不切数据源 —— 只过滤 `device` 的话列表是空的，用户没有任何线索。

> [!warning] fastboot 为什么不能用来做设备概览
> fastboot 也是 USB，同样受 `/dev/bus/usb` 权限约束，但更根本的问题是**它不能读系统信息**：
> fastboot 模式下 Android 根本没启动，`fastboot getvar` 只能拿到产品代号、序列号、
> BL 解锁状态、分区大小。CPU 频率、电池、指纹、系统版本全都读不到。
> 它属于刷机页（`docs/subsystems/FLASH.md`），不属于设备概览。

**USB 与无线必须在 UI 上分开**：同一个下拉里两种连接并存，用户插线后分不清多出来的那条
是哪来的，而两者的权限要求完全不同。判据只看序列号里有没有冒号 —— USB 报设备自身序列号
（`45850c2f`），网络连接一定带 `ip:端口`（`192.168.1.23:37105`）。

### 3.1.2 fastboot 模式的外接设备

对方手机停在 bootloader 里时，**Android 没启动**：`adb devices` 看不到它，
只能靠 `fastboot devices`。这条链路能读的东西和 adb 那条<b>完全不同</b>：

| 字段 | adb 外接 | fastboot 外接 |
| --- | --- | --- |
| 产品代号 / 序列号 | ✅ | ✅ `product` / `variant` |
| **BL 解锁状态** | ✅ | ✅ `unlocked` / `device-state` / `secure` |
| 当前槽位 | ✅ | ✅ `current-slot` |
| Android 版本 / 指纹 / 安全补丁 / 基带 / SELinux / 安卓 ID | ✅ | ❌ 物理上没有 |
| CPU 各核频率 / 电池循环 | ✅ | ❌ 物理上没有 |

> [!warning] stderr 必须合并
> `fastboot getvar` 的结果**全部打在 stderr**（`(bootloader) product:xxx`）。
> 只读 stdout 会拿到空串，表现是「识别到了设备但一个字段都没有」。
> `AdbBridge.runFastboot()` 因此在 root 路径的命令末尾追加 `2>&1`。

**只发一条 `getvar all`**：所有变量一次吐回来。逐条 `getvar` 要几十次 USB 往返
（每次 50~150ms），光读个解锁状态就得等好几秒。

**解析只看 `(xxx) key:value` 行**：`Finished. Total time: 0.050s` 也含冒号，
不设这道前缀门槛会解析出一条 `finished. total time` 的假键。

**BL 三级回退**（`BootloaderState.fromFastboot`）：`unlocked`（yes/no）
→ `device-state`（unlocked/locked）→ `secure`（只认 no）。三个都没有就是 `UNKNOWN`。

**UI 上必须说清「系统没启动」**：`android` 字段写死成
`Fastboot 模式 · 系统未启动` —— 否则整屏「—」会被当成读取失败。

**切换数据源要清 CPU 圆环**：`ensureCpuCoreRings()` 只在核数变化时重建，
核数「变成 0」它看不见，旧圆环（通常是本机的 8 核）会留在屏幕上假装是对方的数据。

> [!warning] 进 fastboot 不等于掉线
> 一台手机从正常系统重启进 fastboot 时，**序列号不变、`adb devices` 里消失、
> `fastboot devices` 里出现**。掉线判断如果只查 adb 那一桶，会把「正在进 fastboot」
> 误判成掉线，页面直接弹回本机 —— 而用户刚点的就是这个动作。
> `containsCurrent(snapshot, serial, sourceFastboot)` 因此按通道分开比。

**前提与 adb 一样**：`fastboot` 也要读 `/dev/bus/usb`，所以同样**只有 root 身份跑得起来**；
无 root 时连 `fastboot devices` 都起不来，这一桶直接为空。

### 3.2 「添加设备…」怎么用

点下拉末尾那一行，输入 `ip:端口`，走 `adb connect`。对话框里写清了三件事：

- 端口在那台手机的「无线调试」页里看得到，每台每次都不一样；
- 首次连接要**先在那台手机上完成配对**（配对在对方系统里做，这边只负责 `connect`）；
- USB 线连的那台只有 Root 才自动列出来 —— 有 root 时直接插线即可，不必用这个对话框。

成功后会：`pendingSelectSerial` 记下地址 → `refreshSources(true)` 跳过限流 →
列表里出现就自动切过去 → `loadStatic(true)` 读那台的快照。

---

## 四、root 状态怎么影响页面

**只影响字段，不影响布局。** 两张卡片恒定显示，`DeviceFragment` 里没有「隐藏卡片」的分支。

| 时机 | 行为 |
| --- | --- |
| `onViewCreated` | 先读一遍（`Build.*` 与 sysfs 不需要权限，首屏立刻有内容），再异步测 root |
| `detect()` 结果回来 | 若 root 状态与上次不同，`loadStatic(false)` 自己会重读一遍（缓存键含 `Mode`） |
| `onResume` | 必重测：root 刚授权完没有系统广播，只能主动查 |
| `onPause` | 移除 tick、关掉下拉 |

- 检测与读取全在后台单线程（`aurora-shell` / `aurora-device`），主线程只贴文字。
- `su` 首次调用要等授权弹窗（可达 1 秒以上），所以首帧不能等它。

---

## 五、踩坑清单

| 现象 | 原因 | 位置 |
| --- | --- | --- |
| 8 核只显示 1 核 | 输出 key 写成 `CPU=`，后一个核心覆盖前一个 | `DeviceReader.CPU_LOOP` |
| `BATTERY_PROPERTY_CYCLE_COUNT` 找不到符号 | 该字段 API 34 才有，本机 `android.jar` 存根里没有 | `DeviceReader.batteryPropertyId()` 反射 |
| 切到外接设备后表格还是本机数据 | 忘了 `loadStatic(true)`；`sourceSerial` 只在 dropdown 回调里改 | `onSourceSelected()` |
| 插了 USB 外接却不出现 | 无 root 时应用身份读不到 `/dev/bus/usb`，属平台边界 | `AdbBridge.runAsApp()` |
| 插了线、下拉里那条写着「未授权」 | 目标机屏幕上等着点「允许 USB 调试」（RSA key 交换） | `DeviceFragment.showPendingDeviceDialog()` |
| 对方点了允许，那一项还是「未授权」 | 之前只比序列号，状态变化被判成「没变化」 | `sameSources()` 比的是「序列号 + 是否就绪」 |
| fastboot 设备识别到了但字段全「—」 | 只读 stdout，而 `getvar` 的结果全在 stderr | `AdbBridge.runFastboot()` 的 `2>&1` |
| 切到 fastboot 设备后 CPU 圆环还是本机的 8 个 | 核数变 0 时 `ensureCpuCoreRings` 不重建 | `DeviceFragment.clearCpuCoreRings()` |
| 手机一进 fastboot 页面就弹回本机 | 掉线判断只查 adb 那一桶 | `containsCurrent()` 按通道分流 |
| 下拉正展开时突然被关掉 | `setSources()` 一律 `dismiss()` | `refreshSources()` 里先比列表 |
| 外接设备读不到任何值 | 内置 adb 未解压（首次要读 9.9MB assets） | `AdbBridge.externalSerials()` 里 `ensureReady` |
| BL 状态恒为「—」 | 三个属性都读不到（个别 ROM 收紧了 `bootloader_prop`，属少数）；先看 `adb logcat -s DeviceReader` 里 `BL 应用身份直读` 那行 | `BootloaderState.of()` 逐级回退，判不出时回退 root 再读 |
| **无 root 时 BL 显示「—」**（已修） | 曾把 BL 三属性与 `getenforce` 压进同一条 root shell，`execOrEmpty` 在无 root 时整条返回空串 → 一条挂全挂 | `DeviceReader` 把 BL 拆成属性服务直读（提交 `9a965f2`） |
| 本机多段输出切完后下标错位 | `split` 没传 `-1`，尾部空段被丢掉 | `DeviceReader` 里 `split("##AURORA##", -1)` |

---

## 六、相关文件

| 文件 | 职责 |
| --- | --- |
| `core/shell/ShellManager.java` | root 判定、命令路由、`Result` 语义 |
| `core/shell/AdbBridge.java` | 内置 adb：devices / connect / shell，root 与应用双身份，外接设备列表 |
| `core/device/DeviceReader.java` | 本机与外接两条读取链路 + 解析 |
| `core/device/DeviceSnapshot.java` | 快照值对象（静态字段 + `cores` + `batteryCycles` + `bootloader`） |
| `core/device/BootloaderState.java` | BL 三属性 → 解锁状态的优先级判定 |
| `ui/device/DeviceFragment.java` | root 检测、数据源切换、定时刷新、添加设备对话框 |
| `ui/device/DeviceSourceDropdown.java` | 标题右侧胶囊 + 数据源弹窗（含「添加设备…」动作项） |
| `res/layout/fragment_device.xml` | 两张内容卡（设备概览 + CPU 状态） |
| `app/src/test/.../core/device/DeviceReaderTest.java` | 解析与本机地址判定的纯 JVM 用例 |
| `app/src/test/.../core/device/BootloaderStateTest.java` | BL 判定与优先级回退的纯 JVM 用例 |
| `app/src/test/.../core/shell/AdbBridgeTest.java` | `adb devices` 输出解析（表头/daemon 噪声/版本告警/待就绪状态）与 USB 判定 |
