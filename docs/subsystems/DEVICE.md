# 子系统：设备页 —— 权限通道与双数据源

> 设备页（tab 0）是**唯一一个「能不能显示」取决于权限**的页面。
> 遇到「整页只剩一句授权提示」「有 Root 还提示装 Shizuku」「切了外接设备但显示本机数据」，
> 先看这篇。

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
| **安卓 ID（可信值）** | ⚠️ | `Settings.Secure.ANDROID_ID` 能读，但国产 ROM 可能改写；`settings get secure android_id` 才是系统真值 |

---

## 二、通道优先级

`core/shell/ShellManager.detect()` 按固定顺序探测，**命中即短路**：

| 顺序 | 通道 | 判定 | 身份 | 代价 |
| --- | --- | --- | --- | --- |
| 1 | `ROOT` | `su -c id -u` 拿到 uid 0 | root | 用户已有 |
| 2 | `SHIZUKU` | `Shizuku.pingBinder()` + `checkSelfPermission() == GRANTED` | shell / root | 装 Shizuku 并授权一次 |
| 3 | `WIRELESS_ADB` | 内置 adb 已连到**本机自己**的无线调试 | shell | 要开无线调试并配对 |
| 4 | `NONE` | 以上全无 | — | — |

> [!warning] 有 Root 时绝不出现 Shizuku 引导
> 不是「引导页被隐藏了」，而是路由顺序上就短路了：`detect()` 第一步命中 root 就直接
> `return`，后面两条连探测都不做（探测 Shizuku 要跨进程、探测无线 ADB 要起 adb）。
> 这条约束写在 `ShellManager` 的类注释里，改动时别把 `su` 判定挪到后面。

### 2.1 三条通道各自怎么执行命令

| 通道 | 实现 | 关键点 |
| --- | --- | --- |
| Root | `RootShell.exec()` | 持久化 `su` 会话，单条命令毫秒级；超时销毁会话 |
| Shizuku | `ShizukuBridge.exec()` | `Shizuku.getBinder()` → `IShizukuService.newProcess()` → `IRemoteProcess`；轮询 `alive()` 收尾，超时 `destroy()` |
| 无线 ADB | `AdbBridge.shell()` | 内置 aarch64 `adb`（`assets/flash_tools.zip`），`HOME` 指向应用私有目录存 adbkey |

**为什么 Shizuku 不用 `Shizuku.newProcess`**：API 13 把它从 `Shizuku` 类移出（官方已宣布
「准备移除」，改为 UserService）。为了跑几条 `getprop` 打一个 UserService dex 不值得，
所以直接调用它内部同一套 AIDL。反编译核对过 13.1.5 的 `onBinderReceived`：
服务端给回的 binder 是裸 binder（没有再包 `ShizukuBinderWrapper`），外部直接调是安全的。

### 2.2 无线调试为什么必须「连自己」

手机在没有 root 时想拿 shell 身份，除了 Shizuku 就只能借系统的无线调试 —— 它在系统里
就是一个跑 adbd 的 TCP 端口，本机同样能当客户端连上去（Shizuku 的免 root 模式就是这套）。

`AdbBridge.findSelfWirelessSerial()` 只接受**对端地址等于本机某个网卡地址**（含回环）的连接：

```
连上 192.168.1.7:39301（自己）→ 算通道
连上 192.168.1.9:39301（别人的手机）→ 不算通道，但会出现在「外接设备」下拉里
```

判定在 `AdbBridge.isSelfAddress()`，`DeviceReaderTest` 里有对应用例钉住。

> [!warning] 端口每次都会变
> 无线调试的端口在开关一次、重启一次后就换一个，只能靠 mDNS 现查
> （`adb mdns services` 里的 `_adb-tls-connect._tcp`），**绝不能写死 5555**。
> 首次连接还要过配对（`_adb-tls-pairing._tcp` + 6 位码），配对端口与连接端口不是同一个。

### 2.3 配对流程

Android 11 起无线调试用 TLS，首次连接必须交换证书：

1. 用户点引导页的「打开无线调试设置」→ 进系统设置
2. 打开无线调试 → 点「使用配对码配对设备」→ 屏幕上有 `ip:port` 与 6 位码
3. 回到 Aurora → 页面检测到「无线调试开着但没连上」→ 弹配对框（地址能 mDNS 查到就预填）
4. `adb pair <ip:port> <code>` → 成功后 `adb connect <连接端口>`（由 `findSelfWirelessSerial` 自动做）

配对成功后 adbkey 落在 `files/adb_home/.android`，此后只需 `adb connect`。

---

## 三、本机 / 外接：同一套卡片

**禁止摆两套视图。** 本机与外接设备的字段完全一样，做成上下两张卡片会让同一屏出现两份
「设备概览」，用户得先分辨哪张是哪台；两套 View 各自刷新、各自处理「读不到」，任何一侧
出问题都会连带整页重载。

- 共用：`cardDeviceOverview` + `cardCpu`
- 切换入口：**「设备概览」标题右侧的胶囊**（`btnDeviceSource`），不放页面顶部、不做悬浮
- 数据源列表：`本机` + `AdbBridge.externalSerials()`（排除连到自己那条，避免同一台设备出现两次）

切换时整份快照重读（`loadStatic(true)`），卡片结构不动，所以不存在「两张卡片各显示一半」的中间态。

| 读什么 | 本机 | 外接设备 |
| --- | --- | --- |
| 静态信息 | `Build.*` + 一次 shell（`getenforce` + `android_id`） | 一条 `adb shell`：`echo KEY=$(...)` 十几项 |
| 动态信息 | sysfs 直读，**1 秒**刷 | 每次一次 adb 往返，**2 秒**刷 |
| 刷新暂停 | `onPause` 移除 tick；门控关闭时也不 tick | 同左 |

> [!warning] 外接设备绝不逐字段发命令
> 每次 adb 往返 50~150ms，十几个字段就是一两秒。所有字段合并成一条 shell：
> `echo "MAN=$(getprop ...)"; echo "MODEL=$(...)"; ... ; <CPU 循环>`，
> 解析按 `KEY=VALUE` 行取。值与 `=` 极少共存，所以不需要转义 —— 但 **key 必须带编号**
> （`CPU0=`、`CPU1=`），全写 `CPU=` 的话后者覆盖前者，8 核只会剩 1 核。

---

## 四、页面状态机

`DeviceFragment.applyGate()` 只有两个分支：

| 通道状态 | 设备卡 | CPU 卡 | 授权引导卡 |
| --- | --- | --- | --- |
| `DETECTING` | 显示 | 显示 | GONE |
| `ROOT` / `SHIZUKU` / `WIRELESS_ADB` | 显示 | 显示 | GONE |
| `NONE` | **GONE** | **GONE** | 显示 |

- `DETECTING` 按**放行**处理：首次进页面检测要几十毫秒到一秒（`su` 可能要等授权弹窗），
  这期间先摆出已经能读到的机型/系统信息，比先给空页再跳成内容稳；真没通道时检测一结束就收起。
- 同一进程内第二次进页面时 `ShellManager.state()` 还留着上次结果，`onViewCreated` 先按它铺一次，
  不会出现「已授权用户每次进来先闪一下引导页」。
- 检测与读取全在后台单线程（`aurora-device`），主线程只贴文字。
- `onResume` 必重测：root 刚授权、Shizuku 刚激活、无线调试刚连上都没有系统广播，只能主动查。

授权引导卡内容（`cardAuthGuide`）：

```
设备概览
需要Shizuku授权或者开启无线调试，才能读取设备信息
[ 去激活Shizuku ]  [ 打开无线调试设置 ]      ← 横向并排，不上下叠
```

「去激活Shizuku」按状态分三种行为：未安装 → 开官方下载页；装了但服务没跑 → 拉起 Shizuku；
已连上但没授权 → `Shizuku.requestPermission()`，结果由
`addRequestPermissionResultListener` 回来后再重测一次。

---

## 五、踩坑清单

| 现象 | 原因 | 位置 |
| --- | --- | --- |
| 8 核只显示 1 核 | 输出 key 写成 `CPU=`，后一个核心覆盖前一个 | `DeviceReader.CPU_LOOP` |
| `BATTERY_PROPERTY_CYCLE_COUNT` 找不到符号 | 该字段 API 34 才有，本机 `android.jar` 存根里没有 | `DeviceReader.batteryPropertyId()` 反射 |
| 装完依赖编译报 `INTERACT_ACROSS_USERS_FULL` 相关 | 忘了在 Manifest 声明 `ShizukuProvider` | `AndroidManifest.xml` |
| 无线调试明明开着却判定为无通道 | 只接受「连到自己」的连接；别人的手机不算 | `AdbBridge.isSelfAddress()` |
| 切到外接设备后表格还是本机数据 | 忘了 `loadStatic(true)`；`sourceSerial` 只在 dropdown 回调里改 | `onSourceSelected()` |
| 外接设备读不到任何值 | 内置 adb 未解压（首次要读 9.9MB assets） | `AdbBridge.externalSerials()` 里 `ensureReady` |

---

## 六、相关文件

| 文件 | 职责 |
| --- | --- |
| `core/shell/ShellManager.java` | 通道探测（优先级 + 短路）、命令路由、`Result` 语义 |
| `core/shell/ShizukuBridge.java` | Shizuku binder / 授权 / `IRemoteProcess` 执行 |
| `core/shell/AdbBridge.java` | 内置 adb：devices / mdns / connect / pair / shell，本机地址判定 |
| `core/device/DeviceReader.java` | 本机与外接两条读取链路 + 解析 |
| `core/device/DeviceSnapshot.java` | 快照值对象（静态字段 + `cores` + `batteryCycles`） |
| `ui/device/DeviceFragment.java` | 门控状态机、数据源切换、定时刷新、配对对话 |
| `ui/device/DeviceSourceDropdown.java` | 标题右侧胶囊 + 数据源弹窗 |
| `res/layout/fragment_device.xml` | 两张内容卡 + 一张授权引导卡 |
| `app/src/test/.../core/device/DeviceReaderTest.java` | 解析与本机地址判定的纯 JVM 用例 |
