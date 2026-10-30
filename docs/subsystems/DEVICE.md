# 子系统：设备页 —— 权限通道与双数据源

> 设备页（tab 0）是**唯一一个「能不能显示」取决于权限**的页面。
> 遇到「整页只剩一句授权提示」「有 Root 还提示装 Shizuku」「切了外接设备但显示本机数据」
> 「插了另一台手机却没出现在下拉里」，先看这篇。

---

## 一、一句话结论

**这一页的数据分两种：普通 API 就能读的，和必须有 shell 身份才能读的。
后者读不到时，页面不留一片「—」，而是整页收成一块授权引导。**

| 字段 | 普通应用可读 | 说明 |
| --- | --- | --- |
| 机型 / 代号 / Android 版本 / Build Display / 安全补丁 / 指纹 / 基带 | ✅ | `Build.*` |
| 内核版本 | ✅ | `System.getProperty("os.version")` |
| 当前 Slot | ✅ | 反射 `ro.boot.slot_suffix` |
| 各核频率 | ✅ | `/sys/devices/system/cpu/cpu*/cpufreq/*`（Android 12+ 仍开放） |
| 电池循环次数 | ✅ / ⚠️ | Android 14+ 走 `BatteryManager`，更早只能读 sysfs `cycle_count`，多数机型没有 |
| **SELinux** | ❌ | `getenforce` 需要 shell 身份；Android 12 起三方应用读 `/sys/fs/selinux/enforce` 也被拒 |
| **BL 状态** | ⚠️ | 属性本身可读，但 `ro.boot.*` 在 application 域常被 SELinux 过滤，**只信 shell 身份读到的值** |
| **安卓 ID（可信值）** | ⚠️ | `Settings.Secure.ANDROID_ID` 能读，但国产 ROM 可能改写；`settings get secure android_id` 才是系统真值 |

---

## 二、通道优先级

`core/shell/ShellManager.detect()` 按固定顺序探测，**命中即短路**：

| 顺序 | 通道 | 判定 | 身份 | 代价 |
| --- | --- | --- | --- | --- |
| 1 | `ROOT` | `su -c id -u` 拿到 uid 0 | root | 用户已有 |
| 2 | `SHIZUKU` | `Shizuku.pingBinder()` + `checkSelfPermission() == GRANTED` | shell | 装 Shizuku 并在里面勾选本应用 |
| 3 | `NONE` | 以上全无 | — | — |

> [!warning] 有 Root 时绝不出现 Shizuku 引导
> 不是「引导页被隐藏了」，而是路由顺序上就短路了：`detect()` 第一步命中 root 就直接
> `return`，后面那条连探测都不做（探测 Shizuku 要跨进程）。
> 这条约束写在 `ShellManager` 的类注释里，改动时别把 `su` 判定挪到后面。

### 2.1 「无线调试」为什么不作为通道了

它曾经是第三条通道：让内置 adb 连上**本机自己**的无线调试端口，借 adbd 拿到 shell 身份。
整条链路是这样：

```
mDNS 查 `_adb-tls-connect._tcp` 拿端口   ← 端口每次开关都换，绝不能写死 5555
首次还要 `_adb-tls-pairing._tcp` + 6 位配对码   ← 配对端口与连接端口不是同一个
配对成功后 adbkey 落盘，此后才能 connect
```

**三步里有两步必须用户手抄一串数字**，而它能给的身份和 Shizuku 完全一样（都是 uid 2000）。
所以整条通道被拿掉了：端口发现、配对对话框、`mdns` / `pair` / `findSelfWirelessSerial`
全部删除，页面只剩一颗「去授权 Shizuku」。

**adb 本体没删。** 它还在，只是不再服务本机 —— 它现在唯一的职责是读**外接设备**，见下一节。

### 2.2 两条通道各自怎么执行命令

| 通道 | 实现 | 关键点 |
| --- | --- | --- |
| Root | `RootShell.exec()` | 持久化 `su` 会话，单条命令毫秒级；超时销毁会话 |
| Shizuku | `ShizukuBridge.exec()` | `Shizuku.getBinder()` → `IShizukuService.newProcess()` → `IRemoteProcess`；轮询 `alive()` 收尾，超时 `destroy()` |

**为什么 Shizuku 不用 `Shizuku.newProcess`**：API 13 把它从 `Shizuku` 类移出（官方已宣布
「准备移除」，改为 UserService）。为了跑几条 `getprop` 打一个 UserService dex 不值得，
所以直接调用它内部同一套 AIDL。反编译核对过 13.1.5 的 `onBinderReceived`：
服务端给回的 binder 是裸 binder（没有再包 `ShizukuBinderWrapper`），外部直接调是安全的。

### 2.3 授权只有一步：在 Shizuku 里勾选

用户侧看到的是一次点击。代码上分两步，缺一不可：

1. `Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION)`
   —— **这一步不只是弹框**，它同时让本应用出现在 Shizuku 的「使用中的应用」列表里。
   从没请求过权限的应用，用户在 Shizuku 里根本找不到，也就谈不上勾选。
2. 延时 1.2 秒复查（`openShizukuIfStillDenied`）
   —— Shizuku 的授权框是它自己的 Activity，弹出来会让我们 `onPause`。
   所以「1.2 秒后仍在 `onResume` 且仍未授权」等价于「框没弹出来」（用户之前点过拒绝），
   这时直接拉起 Shizuku，让人去应用列表里勾选。

结果通过 `addRequestPermissionResultListener` 回来后再重测一次；
`addBinderReceivedListenerSticky` 负责「页面先开着、Shizuku 后才起来」的补测。

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
| 刷新暂停 | `onPause` 移除 tick；门控关闭时也不 tick | 同左 |

> [!warning] 外接设备绝不逐字段发命令
> 每次 adb 往返 50~150ms，十几个字段就是一两秒。所有字段合并成一条 shell：
> `echo "MAN=$(getprop ...)"; echo "MODEL=$(...)"; ... ; <CPU 循环>`，
> 解析按 `KEY=VALUE` 行取。值与 `=` 极少共存，所以不需要转义 —— 但 **key 必须带编号**
> （`CPU0=`、`CPU1=`），全写 `CPU=` 的话后者覆盖前者，8 核只会剩 1 核。

### 3.1 外接设备为什么可能「插了也不出现」

这是被问得最多的一条，原因分两层。

**第一层是平台边界，不是 bug：**

| 内置 adb 的身份 | USB 外接（OTG 线） | 网络设备（`adb connect`） |
| --- | --- | --- |
| root | **能枚举** | 能 |
| 应用（含 Shizuku 模式下的页面） | **枚举不到** | 能 |

内置 adb 靠扫 `/dev/bus/usb/*` 发现 USB 上的手机，而这些节点是 `root:usb 0660`。
普通应用（uid ≥ 10000）连目录都进不去 —— 所以**以应用身份跑的 adb 永远列不到 USB 外接设备**。
`AdbBridge.run()` 因此在有 root 时以 root 身份执行，没有 root 时退到应用身份（只够 `connect`）。

> [!note] 为什么 Shizuku 模式下也不行
> Shizuku 给的身份是 shell（uid 2000），而内置 adb 二进制位于应用私有目录
> （`/data/user/0/com.aurora.app/files/...`，owner 是应用 uid、目录 0700）。
> shell 身份既进不去这个目录、也读不到那个二进制，所以**没法用 Shizuku 身份跑内置 adb**。
> 这不是没实现，是权限模型上走不通。

**第二层是代码里的三个刷新时机问题（已修）：**

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

## 四、页面状态机

`DeviceFragment.applyGate()` 只有两个分支：

| 通道状态 | 设备卡 | CPU 卡 | 授权引导卡 |
| --- | --- | --- | --- |
| `DETECTING` | 显示 | 显示 | GONE |
| `ROOT` / `SHIZUKU` | 显示 | 显示 | GONE |
| `NONE` | **GONE** | **GONE** | 显示 |

- `DETECTING` 按**放行**处理：首次进页面检测要几十毫秒到一秒（`su` 可能要等授权弹窗），
  这期间先摆出已经能读到的机型/系统信息，比先给空页再跳成内容稳；真没通道时检测一结束就收起。
- 同一进程内第二次进页面时 `ShellManager.state()` 还留着上次结果，`onViewCreated` 先按它铺一次，
  不会出现「已授权用户每次进来先闪一下引导页」。
- 检测与读取全在后台单线程（`aurora-device`），主线程只贴文字。
- `onResume` 必重测：root 刚授权、Shizuku 刚被勾选都没有系统广播，只能主动查。

授权引导卡内容（`cardAuthGuide`）：

```
设备概览
需要 Shizuku 授权才能读取设备信息
[          去授权 Shizuku          ]      ← 单颗，满宽；不再有「打开无线调试设置」
```

---

## 五、踩坑清单

| 现象 | 原因 | 位置 |
| --- | --- | --- |
| 8 核只显示 1 核 | 输出 key 写成 `CPU=`，后一个核心覆盖前一个 | `DeviceReader.CPU_LOOP` |
| `BATTERY_PROPERTY_CYCLE_COUNT` 找不到符号 | 该字段 API 34 才有，本机 `android.jar` 存根里没有 | `DeviceReader.batteryPropertyId()` 反射 |
| 装完依赖编译报 `INTERACT_ACROSS_USERS_FULL` 相关 | 忘了在 Manifest 声明 `ShizukuProvider` | `AndroidManifest.xml` |
| 点了「去授权」却没有任何反应 | 从没调过 `requestPermission`，本应用不在 Shizuku 的应用列表里 | `onActivateShizuku()` |
| 授权完回到页面还是引导页 | 回调比上一次检测晚，被 `detecting` 挡掉了 | `ShellManager.detectAsync()` 的 `redetect` 标记 |
| 切到外接设备后表格还是本机数据 | 忘了 `loadStatic(true)`；`sourceSerial` 只在 dropdown 回调里改 | `onSourceSelected()` |
| 插了 USB 外接却不出现 | 无 root 时应用身份读不到 `/dev/bus/usb`，属平台边界 | `AdbBridge.runAsApp()` |
| 下拉正展开时突然被关掉 | `setSources()` 一律 `dismiss()` | `refreshSources()` 里先比列表 |
| 外接设备读不到任何值 | 内置 adb 未解压（首次要读 9.9MB assets） | `AdbBridge.externalSerials()` 里 `ensureReady` |
| BL 状态恒为「—」 | 三个属性都被 SELinux 过滤，或通道刚好不可用 | `BootloaderState.of()` 逐级回退 |

---

## 六、相关文件

| 文件 | 职责 |
| --- | --- |
| `core/shell/ShellManager.java` | 通道探测（优先级 + 短路）、命令路由、`Result` 语义 |
| `core/shell/ShizukuBridge.java` | Shizuku binder / 授权 / `IRemoteProcess` 执行 |
| `core/shell/AdbBridge.java` | 内置 adb：devices / connect / shell，root 与应用双身份，外接设备列表 |
| `core/device/DeviceReader.java` | 本机与外接两条读取链路 + 解析 |
| `core/device/DeviceSnapshot.java` | 快照值对象（静态字段 + `cores` + `batteryCycles` + `bootloader`） |
| `core/device/BootloaderState.java` | BL 三属性 → 解锁状态的优先级判定 |
| `ui/device/DeviceFragment.java` | 门控状态机、数据源切换、定时刷新、添加设备对话框 |
| `ui/device/DeviceSourceDropdown.java` | 标题右侧胶囊 + 数据源弹窗（含「添加设备…」动作项） |
| `res/layout/fragment_device.xml` | 两张内容卡 + 一张授权引导卡 |
| `app/src/test/.../core/device/DeviceReaderTest.java` | 解析与本机地址判定的纯 JVM 用例 |
| `app/src/test/.../core/device/BootloaderStateTest.java` | BL 判定与优先级回退的纯 JVM 用例 |
