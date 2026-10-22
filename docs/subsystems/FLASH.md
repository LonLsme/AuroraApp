# 子系统：刷机链路

> 代码位置：`ui/flash/`（表现层）+ `core/flash/`（能力层）。
> 相关：QDL 深刷单独成篇，见 [`QDL.md`](QDL.md)。

---

## 一、一条命令的完整路径

以「欧加真线刷」为例：

```
用户在 OplusFlashActivity 点「开始刷入」
  │
  ├─ 1. OplusFlasher / FlashCommands  拼出命令字符串（纯字符串模板）
  │     外部输入（路径、分区名）一律经 shellEscape 单引号转义
  │
  ├─ 2. FlashConsoleActivity.runConsoleCommand(cmd, callback)
  │     └─ 确认框 → 置忙（busyViews 全部 disable）
  │
  ├─ 3. FlashRuntime.run(context, cmd, listener)
  │     ├─ 单任务闸门：busy.compareAndSet(false, true)，占用失败即拒绝
  │     ├─ 前缀 envPrefix(context)：mkdir / chmod 755 / export PATH...
  │     └─ ProcessBuilder("su", "-c", envPrefix + command)
  │          redirectErrorStream(true)  ← stdout/stderr 合并
  │
  ├─ 4. 流式逐行 → listener.onLine(line) → FlashConsoleActivity.appendLine()
  │     （自动滚到底部）
  │
  └─ 5. listener.onExit(exitCode, cancelled)
        └─ 先 busy.set(false)，再回调 onExit   ← 关键顺序，见 3.2
```

---

## 二、工具链：`FlashToolchain`（252 行）

把 `assets/flash_tools.zip` 解压到应用私有目录，并做版本门控。

| 常量 | 值 |
| --- | --- |
| `ASSET_NAME` | `flash_tools.zip` |
| `DIR_NAME` | `flash_tools`（解压目标 `<filesDir>/flash_tools`） |
| `TOOLCHAIN_VERSION` | **3** |

### 包内五个可执行文件

| 文件 | 未压缩大小 | 用途 |
| --- | --- | --- |
| `bin/adb` | 6,134,248 | 目标机 ADB 通道 |
| `bin/fastboot` | 2,165,568 | 目标机 Fastboot 通道 |
| `bin/libksud` | 4,892,712 | KernelSU 补丁工具 |
| `bin/magiskboot` | 631,848 | boot 镜像解包/重打包 |
| `bin/qdl` | 5,668,288 | 高通 9008 深刷 |

总计未压缩 19,492,664 字节；压缩后 zip 为 9,902,583 字节。

> [!important] 一个 `.so` 都不带
> 五个可执行文件里，`adb` / `fastboot` 是**静态链接**；
> `qdl` 是 **Android 动态链接**（`PT_INTERP=/system/bin/linker64`），
> 但它的 `DT_NEEDED` 只有 `libm.so` / `libc.so` / `libdl.so` **三个系统库**
> —— 所以不需要随包携带任何 `.so`。
>
> 想加入新的可执行文件之前，**先做 ELF 解析确认依赖**，别把上游 tar 里的 `lib/` 整套搬过来。

### 版本门控

`isReady()` 的判断是「**五个文件都存在** 且 `prefs.getInt(KEY_VERSION)` **等于** `TOOLCHAIN_VERSION`」。

`ensureReady()` 解压后会把 `TOOLCHAIN_VERSION` 写进偏好项。

> [!warning] 改了 assets 包内容却不升 `TOOLCHAIN_VERSION`，已装机用户永远拿不到新文件。
> 这是最容易忘的一步。

解压时有 **Zip Slip 防护**（拒绝 `../` 逃逸路径）。

### ABI 约束

`isQdlSupported()` 检查 `Build.SUPPORTED_64_BIT_ABIS` 里有没有 `arm64-v8a`
—— 因为 `qdl` 只有 aarch64 构建。本应用**未配置 `abiFilters`**，
非 64 位 ARM 设备上 `QdlActivity` 会先弹框说明再 `finish()`，
而不是让用户看到 `Exec format error`。

### 公开 API

| 方法 | 说明 |
| --- | --- |
| `binDir(Context)` | 工具链目录 |
| `adb(Context)` / `fastboot(Context)` / `libksud(Context)` / `magiskboot(Context)` / `qdl(Context)` | 各可执行文件的 `File` 访问器 |
| `isReady(Context)` | 五个文件齐 + 版本号匹配 |
| `ensureReady(Context)` | 解压 + 赋 x 位（**幂等**） |
| `isQdlSupported()` | ABI 是否支持 qdl |

> 解压是 zip 流，**不保留 Unix 权限位** → `ensureReady()` 里有一轮
> `setExecutable(true, false)` 循环。缺了这步，root 执行 ELF 也会 `Permission denied`。

---

## 三、执行引擎：`FlashRuntime`（207 行）

### 3.1 为什么刻意不复用 `RootShell`

`RootShell` 是「一次性执行、收完整串输出」的模型，而刷机需要四件事：

1. **流式逐行回调**（刷机要实时看进度，不能等结束）；
2. **可取消**（刷错了要能停）；
3. **固定 env 前缀**（工具链目录要进 `PATH`）；
4. **单任务闸门**（同时只允许一个刷机任务）。

### 3.2 闸门与一个**必须保留**的顺序

```java
} finally {
    ...
    busy.set(false);              // ← 先放开闸门
    if (listener != null) {
        listener.onExit(exitCode, cancelled);   // ← 再回调
    }
}
```

**先放开 `busy` 再回调 `onExit`** —— 这是「串行编排」能成立的前提：

从 `onExit` 里**再调用 `run()` 不会自锁**。多步刷机动作正是靠这一点串起来的。

> 别去「修」这个顺序。改成先回调再放闸门，所有多步流程会立刻死锁。

### 3.3 env 前缀（`envPrefix`）

每次执行都在命令前面拼一段：

```sh
mkdir -p /data/local/tmp/aurora_flash/home /data/local/tmp/aurora_flash/tmp 2>/dev/null;
chmod 755 '<binDir>'/* 2>/dev/null;
export HOME=/data/local/tmp/aurora_flash/home
       TMPDIR=/data/local/tmp/aurora_flash/tmp
       PATH='<binDir>':$PATH
       LD_LIBRARY_PATH='<binDir>':$LD_LIBRARY_PATH;
```

这样 `FlashCommands` 里写的命令**可以直接写 `adb devices`，不需要带绝对路径**。

### 3.4 其它

- `redirectErrorStream(true)`：stdout / stderr **合并**。刷机工具的错误信息大多写在
  stderr，分开读还要处理两路交错，终端体验反而更差。
- `RUNTIME_ROOT = /data/local/tmp/aurora_flash`。
- `cancel()` 不只是 `destroy()` 本地 `su` —— 子进程（adb / fastboot）会被 init 收养继续跑，
  残留进程会占住 USB 与 **5037 端口**，导致下次刷机失败。
  所以同时让 root 清理一遍（与「清理残留进程」按钮走同一套 `pkill`）。
- `killResidualProcesses()`：静态方法，供「清理残留进程」使用。
- `shellEscape(raw)`：单引号转义，把 `'` 替换为 `'\''`。

### 3.5 多步动作必须合成「一条 shell」

因为闸门是**单任务**的，而**收尾的 `refreshDeviceState()` 还要再占一次闸门**，
把 N 步写成 N 次 `run()` 中间必然出现「被拒」的窗口。

所以凡是「做完 A 接着做 B」的场景，**一律合成一条 shell 命令**，
用 `if / fi` 分支表达条件逻辑。批量卸载/冻结也是同理
（`for p in 'x' 'y'; do ... done`）。

---

## 四、设备探测：`FlashDevice`（330 行）

### 4.1 一条 `su` 命令拿全部信息

用四个标记分隔不同来源的输出：

| 标记 | 内容 |
| --- | --- |
| `##ROOT##` | `id -u`（是否 root） |
| `##ADB##` | `adb devices` 输出 |
| `##FASTBOOT##` | `fastboot devices` 输出 |
| `##USB##` | USB 设备列表 |

**绝不为每个 pid 起一次进程** —— 枚举类操作一律一条 root 命令批量 dump。

### 4.2 `State` 字段

`rootAvailable` / `adbReady` / `adbSerial` / `adbState` /
`fastbootReady` / `fastbootSerial` / `fastbootMode` / `deepFlashHint` / `usbIds`。

`summary()` 给出人读摘要（如「ADB 已连接 · 45850c2f」）；
`NOT_CONNECTED = "未连接设备"`。

### 4.3 模式判定

| 判定 | 规则 |
| --- | --- |
| Fastboot vs FastbootD | 靠 `fastboot devices` 输出**第二列**互斥判定 |
| 9008 / EDL | `KNOWN_USB_IDS` + `describeUsbId(id)` 查表给出人读名称 |
| A/B 槽位 | `slotStatuses(State)` + `SLOT_NAMES` |

### 4.4 刷新触发（四层）

1. 页面可见时每 **5 秒**轮询；
2. **USB 插拔广播 → 延后 900ms 再探测**；
3. 切页 / 回前台立刻追一次；
4. 打开设备下拉时**先渲染缓存**，后台再追一次。

> [!warning] 为什么广播要延后 900ms
> USB 广播是在「总线枚举到设备」那一刻发出的，
> 此时 `adb devices` / `fastboot devices` **还没列出设备** ——
> 立刻探测必然得到「未连接」，表现为「插上没反应，等 5 秒才出来」。

**「可见」的定义 = `pageVisible && activityResumed`**，两个条件都要满足。

**轮询必须静默**：不显示「正在检测…」、不置灰卡片、失败不 toast；
只有会话内第一次带进度提示；失败时**保持上次显示，不清空**。

---

## 五、命令模板三兄弟

**三个类刻意分开，不是一个类里分方法**，因为它们作用于完全不同的对象：

| 类 | 行数 | 作用对象 | 通道 |
| --- | --- | --- | --- |
| `FlashCommands` | 555 | **目标机的分区**（写 boot / recovery 等） | fastboot / adb |
| `AppCommands` | 368 | **目标机的应用**（卸载 / 冻结 / 激活 / 安装） | adb → `pm` / `dpm` |
| [`QdlCommands`](QDL.md) | 605 | **本机的 9008 口**（用内置 qdl 可执行文件） | 本机 `qdl` 二进制 |

共同纪律：

- **纯字符串模板，一字节都不执行**；
- **外部输入一律 `shellEscape`**；
- 每条命令的**原文都注明出处**（既有工具的哪个文件/方法）。

### 5.1 `FlashCommands`

分区刷写、槽位切换、Sideload 文案常量、残留进程清理
（`pkill -9 adb; pkill -9 fastboot; true`）。

**刷写分区前先查分区存在性**：`getvar partition-size:<p>` + `grep -qF` 固定串。
`isValidPartitionName()` **不是安全边界**，防注入始终靠 `esc()`。

### 5.2 `AppCommands`

「免 root」的原理：目标机的 `shell` 用户持有 `DELETE_PACKAGES` /
`CHANGE_COMPONENT_ENABLED_STATE` 等**签名级权限**，
所以 `pm uninstall --user 0` / `pm disable-user --user 0`
由 adb 发起时，**目标机不需要 root**。

- 批量操作**必须合成一条 shell**（理由见 3.5）。
- **两层引号**：内层（设备侧）单引号抱包名 / 双引号抱变量，外层整段交 `shellEscape()`。
- 五条激活命令**取自官方文档**（Shizuku / 小黑屋 ×2 / 冰箱 ×2），
  `activate()` 执行前先 `pm path` 查包是否安装。

### 5.3 相关的刷机工具类

| 类 | 行数 | 职责 |
| --- | --- | --- |
| `OplusFlasher` | 707 | 欧加真（OPPO / 一加 / realme）线刷编排 |
| `KernelSuPatcher` | — | KernelSU 补丁流程 |
| `MiFlashScript` | — | 小米线刷脚本的模型与解析 |
| `SparseImage` | — | sparse 镜像尺寸计算 |
| `FlashLog` | — | 日志落盘 |

> [!warning] `SparseImage` 有一个上游的 off-by-4 陷阱
> sparse 展开尺寸 = **`blk_sz`（偏移 12）× `total_blks`（偏移 16）**。
> 既有工具 `oz1.java` 写成 `seek(8)` 连读两个 int —— **那会恒得到 `1835020`**。
> 照抄会算错所有 sparse 镜像的大小。

> [!warning] 小米线刷本质是跑厂商脚本
> 过滤时用 `name.endsWith(".sh")`（**大小写敏感**），
> 执行 `cd <目录> && sh <脚本名>` —— **`cd` 不能省**，脚本里有相对路径。

> [!warning] 欧加真必须分两段刷
> 欧加真机型 **物理分区只有 bootloader 能写、逻辑分区只有 fastbootd 能写**，
> 所以刷写流程必须切成「bootloader 段 + fastbootd 段」两段跑。

---

## 六、控制台基类：`FlashConsoleActivity`（417 行）

所有刷机子页的基类，**固定 id 契约**（子页布局必须用这些 id）：

| id | 控件 |
| --- | --- |
| `btnFlashBack` | 返回 |
| `tvFlashDeviceState` | 设备状态文字 |
| `btnFlashSaveLog` | 保存日志 |
| `btnFlashStop` | 停止 |
| `tvFlashConsole` | 输出区 |

### 子类可覆写的钩子

| 钩子 | 默认行为 | 用在 |
| --- | --- | --- |
| `busyViews()` | **抽象** —— 忙碌时要置灰的控件数组 | 全部子页 |
| `logFileTag()` | **抽象** —— 日志文件名标签 | 全部子页 |
| `onFirstOutput()` | 空 | 首次有输出时的收尾处理 |
| `onDeviceStateRefreshed(State)` | 空 | 设备探测完成后的串行编排（见下） |
| `isTaskRunning()` | 见下 | 「停止」按钮的可用性 |
| `onStopRequested()` | — | 用户点停止 |
| `scrollToBottom()` | 默认滚 `tvConsole` | 输出区不是标准滚动容器时覆写 |

### 三个必须知道的设计点

**① `isTaskRunning()` 不能用 `FlashRuntime.isBusy()`。**

闸门把**设备探测**也算作「忙」，所以用 `isBusy()` 会让「停止」按钮
在只是探测设备时就亮起来。正确做法是自己维护一个 `commandRunning` 字段。

**② 设备探测与后续初始化共用闸门 → 串行只能靠 `onDeviceStateRefreshed` 钩子。**

因为 `run()` 在同一时刻只受理一个任务，凡是「先探测设备、探测完再做事」的流程，
都必须写成「在 `onDeviceStateRefreshed` 回调里发起下一步」，不能连着调两次 `run()`。

**③ 日志区两种形态别混用。**

| 形态 | 效果 |
| --- | --- |
| `0dp + weight=1` | 外层滚动范围恒为 0 → **只剩回弹，真的滚不动** |
| `wrap_content + minHeight` | **外层真的滚得动**，但必须配 `tvConsole.setMovementMethod(null)` + 覆写 `scrollToBottom()` |

**④ 输出区 `android:text` 是占位引导文案。**

因此 `saveLog()` **必须判断 `consoleBuffer.length() == 0`** —— 否则用户会把占位文案存进日志文件。

### `CommandCallback` 与确认执行

```java
protected interface CommandCallback {
    void onResult(String output, int exitCode, boolean cancelled);
}

protected void runConsoleCommand(String command)
protected void runConsoleCommand(String command, CommandCallback callback)

protected void confirmThenRun(String title, String message, String positive, String command)
protected void confirmThenRun(String title, String message, String positive, String command,
                              CommandCallback callback)
```

带回调的重载内部用 **`final StringBuilder sink` 单收本次输出**。

> [!warning] 列表页解析**不能**拿跨命令累积的 `consoleBuffer`
> `consoleBuffer` 是整页历史，解析时必须用本次回调给的那份输出。

---

## 七、子页清单

| 子页 | 命令层 | 特殊之处 |
| --- | --- | --- |
| `OplusFlashActivity` | `OplusFlasher` | 物理/逻辑分区分两段 |
| `MiFlashActivity` | `MiFlashScript` | 跑厂商 `.sh` 脚本 |
| `KernelSuActivity` | `KernelSuPatcher` + `FlashCommands` | 刷 `init_boot` / `boot` |
| `PartitionFlashActivity` | `FlashCommands` | 单分区刷写，先查分区存在性 |
| `RebootActivity` | `FlashCommands` | 系统 / Recovery / Bootloader / FastbootD / EDL / 安全模式 |
| `SideloadActivity` | `FlashCommands` | Recovery 下刷 OTA |
| `ExecCommandActivity` | `FlashCommands` | 自定义命令：输入**不转义**，管道/引号可用；每条先过确认框 |
| [`QdlActivity`](QDL.md) | `QdlCommands` | 本机可执行文件通道 |
| `AppUninstallActivity` | `AppCommands` | 列表解析 + 批量 |
| `ActivateActivity` | `AppCommands` | 按 `ACTIVATE_TARGETS` 动态 inflate |
| `InstallActivity` | `AppCommands` | 多选 APK + 批量安装 |

### `InstallActivity` 的两个细节

- `uniqueApkName()` 按「原名-序号」去重，且 **`reservedNames` 在入队时就登记**
  —— 此刻文件还没落盘，不登记就会出现两个任务抢同一个文件名。
- `pendingCopies` 计数**归零才收尾**，保证「复制完才安装」。

### 公共件

| 类 | 职责 |
| --- | --- |
| `FlashFilePicker` | 文件选择：`startPick` / `pickMultiple` / `resultUris` / `copyAsyncAs` |
| `FlashDeviceDropdown` | 设备下拉 `PopupWindow` |
| `DeviceAppAdapter` | 应用列表适配器 |

> [!warning] `FlashFilePicker` 取 URI：多选用 `getClipData()`、单选用 `getData()`
> 两套 API 不一样，混用会拿到 null。

> [!warning] `FlashDeviceDropdown` 只能持每行 inflate 返回的 View 引用
> 下拉**只构建一次、只刷文字**；item 布局被 inflate 10 次，
> **`findViewById` 会命中错误副本**（id 重复）。
>
> 另外：切 tab 与 `onPause` 都要 `dismissPopup()`。

### `ui/flash/` 全部 16 个文件

| 文件 | 行数 | 类型 |
| --- | --- | --- |
| `QdlActivity.java` | 610 | 页面 · QDL |
| `FlashPage.java` | 516 | 刷机页绑定（tab 1） |
| `FlashConsoleActivity.java` | 417 | **抽象基类** |
| `OplusFlashActivity.java` | 311 | 页面 · 欧加真 |
| `ExecCommandActivity.java` | 311 | 页面 · 执行命令 |
| `FlashFilePicker.java` | 309 | 公共件 |
| `InstallActivity.java` | 308 | 页面 · 应用安装 |
| `PartitionFlashActivity.java` | 305 | 页面 · 刷写分区 |
| `AppUninstallActivity.java` | 305 | 页面 · 应用卸载冻结 |
| `SideloadActivity.java` | 284 | 页面 · ADB Sideload |
| `MiFlashActivity.java` | 273 | 页面 · 小米线刷 |
| `KernelSuActivity.java` | 267 | 页面 · 刷入 KernelSU |
| `FlashRunDialog.java` | 216 | **已无使用者（死代码，刻意保留）** |
| `ActivateActivity.java` | 158 | 页面 · 免 root 激活 |
| `DeviceAppAdapter.java` | 140 | 公共件 |
| `FlashDeviceDropdown.java` | 136 | 公共件 |
| `RebootActivity.java` | 107 | 页面 · 设备重启 |

小计 **4,659 行**。


---

## 八、约定

- **所有子页刻意不做「自动判断走 ADB 还是 Fastboot」**。
  让用户明确选通道，比猜错通道后刷失败好。
- **镜像文件先拷进 `getExternalFilesDir("flash")`** ——
  `fastboot` 读不懂 `content://` URI，必须给真实文件路径。
- **新加竖屏子页必须带完整 `configChanges`**（理由见
  [`../DEVELOPMENT.md`](../DEVELOPMENT.md) 第六节）。
