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
| **BL 状态** | ⚠️ | 属性本身可读，但 `ro.boot.*` 在 application 域常被 SELinux 过滤，**只信 root 身份读到的值** |
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
| 静态信息 | `Build.*` + 一次 shell（`getenforce` / `android_id` / BL 三属性） | 一条 `adb shell`：`echo KEY=$(...)` 十几项 |
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

### 3.2 「添加设备…」怎么用

点下拉末尾那一行，输入 `ip:端口`，走 `adb connect`。对话框里写清了三件事：

- 端口在那台手机的「无线调试」页里看得到，每台每次都不一样；
- 首次连接要**先在那台手机上完成配对**（配对在对方系统里做，这边只负责 `connect`）；
- USB 线连的那台只有 Root 才自动列出来。

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
| 下拉正展开时突然被关掉 | `setSources()` 一律 `dismiss()` | `refreshSources()` 里先比列表 |
| 外接设备读不到任何值 | 内置 adb 未解压（首次要读 9.9MB assets） | `AdbBridge.externalSerials()` 里 `ensureReady` |
| BL 状态恒为「—」 | 三个属性都被 SELinux 过滤，或没有 root | `BootloaderState.of()` 逐级回退 |
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
