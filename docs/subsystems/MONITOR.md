# 子系统：监视器与悬浮窗

> 代码位置：`ui/overlay/`（14 个类）+ `core/monitor/`（12 个采集器）+
> `core/util/`（系统节点读取工具）。
> 数据为什么读不到见 [`SYSTEM_DATA.md`](SYSTEM_DATA.md)。
> 视觉参考：HoshinoScene（星野Scene）。

---

## 一、分层

```
核心
  └─ ui/overlay/MonitorOverlayService        前台服务，悬浮窗的宿主
        └─ OverlayWindowManager              6 个窗口的注册表 + 生命周期
              └─ OverlayWindow（抽象基类）    窗口的一切通用行为
                    ├─ SystemOverlayWindow     系统监视器（四环）
                    ├─ ProcessOverlayWindow    进程监视器
                    ├─ ThreadOverlayWindow     线程监视器
                    ├─ MiniOverlayWindow       迷你监视器（单行胶囊）
                    ├─ FpsOverlayWindow        帧率记录器
                    └─ TemperatureOverlayWindow 温度监视器

数据
  └─ core/monitor/*                          12 个采集器（无 UI 依赖）
        └─ core/util/*                       节点读取工具（NodeReader 等）
```

---

## 二、`MonitorOverlayService`

| 项 | 值 |
| --- | --- |
| 类型 | `Service`，`foregroundServiceType="specialUse"` |
| `CHANNEL_ID` | `aurora_overlay` |
| `NOTIFICATION_ID` | `1001` |
| 返回 | `START_NOT_STICKY` |

| Action / 方法 | 说明 |
| --- | --- |
| `ACTION_START` / `startOverlay(ctx, type)` | 开一个监视器 |
| `ACTION_STOP` / `stopOverlay(ctx, type)` | 关一个 |
| `ACTION_STOP_ALL` / `stopAllOverlays(ctx)` | 全关 |
| `EXTRA_TYPE` | `MonitorType` 的序列化名 |

> [!warning] Android 14+ 前台服务必须声明类型
> `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 是**编译期常量**，
> 需要在 Manifest 里补 `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途
> —— 缺这个属性会**直接崩溃**（曾因此修过一轮，见 [`../archive/2026-08-29-代码评审.md`](../archive/2026-08-29-代码评审.md) 的 P0-1）。

---

## 三、`MonitorType`（6 种）

每种类型同时承担两个职责：`prefKey`（开关的偏好键）、`label`（显示名）。

| 枚举 | `prefKey` | 名称 | 内容 |
| --- | --- | --- | --- |
| `SYSTEM` | `monitor_load` | 系统监视器 | CPU / GPU / 电池 / 内存**四环** + 频率，展开含各核占用与负载 |
| `PROCESS` | `monitor_process` | 进程监视器 | **可滚动列表**（176×170dp，最多 30 行）：应用图标 + 友好名 + CPU%，行尾结束按钮 |
| `THREAD` | `monitor_thread` | 线程监视器 | 系统线程总数 |
| `MINI` | `monitor_mini` | 迷你监视器 | 单行胶囊：CPU/GPU 柱状图 + 频率 + FPS + 电池温度 |
| `FPS` | `monitor_fps` | 帧率记录器 | 实时 FPS 与单帧耗时 |
| `TEMPERATURE` | `monitor_temperature` | 温度监视器 | 电池与 CPU 温度 |

> `SYSTEM` 的 `prefKey` **仍沿用历史的 `monitor_load`** —— 为了不丢老用户已保存的开关状态。
> 这个窗口已由「负载监视器」升级为星野Scene 风格的「系统监视器」，
> **但 key 不能跟着改名**。

新增一种监视器 = **加一个枚举值 + 在 `OverlayWindowManager.createWindow` 里补一个 `case`**。

---

## 四、`OverlayWindow`（抽象基类，506 行）

### 4.1 生命周期

| 方法 | 说明 |
| --- | --- |
| `show()` / `hide()` | 加/移窗口；`hide()` 会持久化位置 |
| `isRunning()` | — |
| `onShown()` / `onHidden()` | 可覆写钩子 |
| `onStateChanged(boolean nowExpanded)` | 展开/收起状态变化 |

### 4.2 子类必须实现

| 抽象方法 | 说明 |
| --- | --- |
| `buildCollapsedView()` | 收起形态的 View |
| `buildExpandedView()` | 展开形态的 View |
| `refreshContent()` | 刷新数据 |

可覆写：`collectData()`（采集入口）、`getRefreshIntervalMs()`（刷新间隔）。

### 4.3 窗口参数三要素

```java
params.flags = FLAG_NOT_FOCUSABLE          // 不抢焦点（否则挡输入法/按键）
            | FLAG_LAYOUT_NO_LIMITS        // 允许被拖到屏幕边缘之外（部分露出）
            | FLAG_FULLSCREEN;             // 让窗口布局覆盖整个屏幕（含状态栏区域）
params.gravity = Gravity.TOP | Gravity.LEFT;
```

> [!warning] 这三条缺一不可
> - **`gravity = TOP|LEFT`**：`WindowManager.LayoutParams` 的 5 参构造函数默认是 `0`（即 `CENTER`），
>   不显式指定的话 `x` / `y` 会被当成**相对中心的偏移**，拖动位置全错。
> - **`FLAG_FULLSCREEN`**：缺了的话坐标系会**差一个状态栏高度** ——
>   表现为「拖到顶部拖不上去」或「点击命中区偏下」。
> - **`FLAG_LAYOUT_NO_LIMITS`**：允许窗口被拖出屏幕边缘。

### 4.4 拖动的实现

| 阶段 | 行为 |
| --- | --- |
| 按下 | 记录起始 `params.x/y` 与触点 |
| **`ACTION_MOVE`** | **每次 MOVE 直接 `updateViewLayout()` 更新 `params`** |
| 抬起 | 结束拖动，持久化位置（`OverlayConfig.setPosition`） |

> MOVE 阶段**不做节流**：悬浮窗都是小窗口，每次 `updateViewLayout` 开销可忽略；
> 反过来「松手才更新」才会导致拖动过程中窗口不跟手。

**命中区由 `params.x/y` 决定** —— 所以窗口位置更新与点击响应是同一套坐标。

> [!warning] 拖动与「窗口内可滚动列表」天然冲突
> `installDrag()` 是设在 **`rootView` 的 `OnTouchListener`** 上的，而 ViewGroup 只有在
> **没有任何子 View 消费事件**时（`mFirstTouchTarget == null`）才会走
> `super.dispatchTouchEvent()` → 触发自己的 `OnTouchListener`。
>
> 所以一旦窗口里放了 `ScrollView`（如进程监视器的展开列表），**手指落在列表上时事件被子 View 消费，
> `rootView` 完全收不到 → 表现为「拖动不了窗口」**。这不是 bug，是必然取舍（星野的进程悬浮窗同样如此）：
> **可滚动区域用来滚动，标题栏用来拖动。**
>
> 单击展开/收起、双击关闭同样受影响 —— 在列表区内会失效，但标题栏上仍可用。

### 4.5 采集线程

```java
private static final ExecutorService DATA_EXECUTOR = Executors.newSingleThreadExecutor();
```

**6 个窗口共享同一个单线程池**，采集任务串行执行。
好处：系统节点读取不会互相抢占、也不会因为多个窗口同时刷新而打满 CPU。

采集任务的提交在 `OverlayWindow` 内部（`DATA_EXECUTOR.execute(...)`）——
子类只实现 `collectData()` / `refreshContent()`，不自己起线程。

> [!warning] 共享池的代价：**单次采集的超时必须保守**
> 因为只有一个线程，**任何一次采集卡住都会把它后面的所有监视器一起堵住**。
> `ProcessMonitor` 的 root dump 是这里最重的一次调用，超时已从 12s 收紧到 **6s**
> （正常只需几十毫秒）。新增采集器时请同样给出一个「卡住就放弃」的短超时。

---

## 五、窗口管理

### `OverlayWindowManager`（单例）

| 方法 | 说明 |
| --- | --- |
| `getInstance()` | — |
| `start(ctx, type)` / `stop(type)` | 单个窗口 |
| `stopAll()` / `release()` | 全部 / 释放 |
| `isRunning(type)` / `runningCount()` | 状态查询 |
| `createWindow(ctx, type)` | **私有** —— `MonitorType` → 窗口实现的 `switch` |

### `OverlayConfig`

| 项 | 说明 |
| --- | --- |
| `PREFS_NAME` | `aurora_usage_monitor` |
| `isEnabled` / `setEnabled(ctx, type, on)` | 开关状态 |
| `isExpanded` / `setExpanded(ctx, type, on)` | 展开/收起状态 |
| `getPositionX` / `getPositionY` / `setPosition` / `clearPosition` | **窗口位置记忆** |

> [!important] 窗口位置是**跨会话记忆**的
> 返回 `Integer`（可能为 `null`）而不是 `int` —— `null` 表示「从未设置过」，
> 此时窗口应该落在默认位置，而不是被当成 `(0, 0)` 扔到左上角。

### `OverlayPermission`

| 方法 | 说明 |
| --- | --- |
| `canDrawOverlays(ctx)` | 是否已授予 `SYSTEM_ALERT_WINDOW` |
| `request(ctx)` | 跳系统设置页申请 |
| `isOverlayPermissionApiAvailable()` | API 可用性 |
| `toastNeedPermission(activity)` | 提示 |

**授权返回后自动开启**：`MainActivity` 里有个 `pendingMonitorType` 字段 ——
开关打开但权限未授予时记录下来，授权返回后自动开启对应的监视器。

---

## 六、视觉：星野Scene 风格

样式**集中**在 `OverlayWindowStyle`，各窗口只引用常量/工厂方法。
后期统一改样式只动这一个类。

复刻来源（HoshinoScene 开源工程）：

| 来源 | 用于 |
| --- | --- |
| `shape_corner.xml` | 监视器标准底：深色半透明 + 圆角 |
| `monitor_game_bg.xml` | 迷你形态底：更浅一档、仅底部圆角 |
| `FloatMonitorChartView` | 圆环轨道色与描边宽度 |
| `FloatMonitorBatteryView` | 电池环按温度/电量上色 |
| `CpuChartBarView` | 柱状图阈值配色 |

统一样式 = **深色半透明胶囊底 + 白色文字 + 阈值变色圆环**。

### `RingChartView`（圆环）

| 参数 | 值 |
| --- | --- |
| 进度弧起点 | **12 点方向（`-90°`）** |
| 方向 | 顺时针 |
| 扫过角度 | **`value × 3.6°`**（即 `value` 是 0~100） |
| 画笔 | `Paint.Style.STROKE` |

### `CoreBarChartView`（柱状图）

柱子的绘制范围是 **`top` → `height`**，其中：

| 情况 | `top` |
| --- | --- |
| 极小值 | `height - height / 20f`（留 1/20 的可见高度，避免柱子完全消失） |
| 0 | `0f` |
| 一般 | `(100 - percent) * height / 100f` |

> 也就是**柱高 = `height − top`**。这一条已经核对过，**不要「优化」成直接算柱高**。

---

## 七、采集器：`core/monitor/`（12 个）

**全部无 UI 依赖**，输入是系统节点 / 系统服务，输出是数值。

| 采集器 | 内容 |
| --- | --- |
| `LoadMonitor` | 负载（读 `/proc/stat` 做两次采样求差） |
| `CpuCoreMonitor` | 各 CPU 核心频率/占用 |
| `MemoryMonitor` | 内存 |
| `GpuMonitor` | GPU 使用率 / 频率 |
| `BatteryMonitor` | 电量 |
| `PowerMonitor` | 功耗（电流/电压） |
| `TemperatureMonitor` | 温度 |
| `ProcessMonitor` | 进程列表与占用 |
| `ThreadMonitor` | 线程数 |
| `FpsMonitor` | 实时帧率 |
| `FpsRecorder` | 帧率记录（含历史与统计） |
| `FpsSampler` | 帧率采样原语 |

配套的读取工具在 `core/util/`：

| 类 | 用途 |
| --- | --- |
| `NodeReader` | **sysfs 节点读取器（普通读优先，root 兜底）** |
| `CpuStatReader` | `/proc/stat` 解析 |
| `CpuSysFiles` / `BatterySysFiles` | 路径常量表 |
| `GpuInfoQuery` | GPU 信息 |
| `SelinuxStatusReader` / `SelinuxShellUtil` | SELinux 状态 |

### `NodeReader` 的三个防拖垮机制

这是整个采数链路最关键的类，它把「普通读 + root 兜底」封装成一个入口：

1. **命中缓存**：某路径曾用 root 读到值，记入 `ROOT_PATHS`（上限 `ROOT_PATHS_LIMIT = 32`），
   后续直接 root `cat`，不再重复走「普通读失败」的弯路；
2. **探测节流**：`PROBE_COOLDOWN_MS = 0L`（**故意取 0**）；
3. **失败退避**：`FAIL_BACKOFF_MS = 15_000L` —— root 会话不可用时 15 秒内不再尝试。

> [!warning] 为什么 `PROBE_COOLDOWN_MS` 是 0
> 一次失败就让接下来 15 秒不再探测（`FAIL_BACKOFF_MS`）已经足够防止
> 「无 root 设备上反复卡超时」。
>
> 如果这里再设正数，会有一个严重副作用：首次遍历几十个 `thermal_zone` 建索引时，
> 每 2 秒才允许探测一个新路径 → **40 个热区需要 80 秒才能建完，期间温度一直显示 `--`**。

---

## 八、改这个子系统时的注意点

1. **新增监视器**：加 `MonitorType` 枚举 → 加窗口实现 → 在 `OverlayWindowManager.createWindow`
   补 `case` → 在 `dialog_usage_monitor` 加 Switch → 在 `MainActivity` 里接开关回调。
2. **数据总是 0 / 显示 `--`**：**先看 [`SYSTEM_DATA.md`](SYSTEM_DATA.md)**，
   多数情况是系统权限边界而不是 bug。
3. **不要给每个 pid / 每个路径起一次进程** —— 枚举类操作一律**一条 root 命令批量 dump**。
4. **不要在主线程读节点** —— 采集全部在 `DATA_EXECUTOR` 单线程池里。
5. **改样式只改 `OverlayWindowStyle`**，不要在具体窗口里散落硬编码颜色。
