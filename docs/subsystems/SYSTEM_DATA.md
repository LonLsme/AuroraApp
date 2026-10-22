# 子系统：系统采数与 Android 权限边界

> 这不是「一个功能」，而是一张**边界地图**。
> 遇到「数据恒为 0」「显示 `--`」「频率能读但占用率读不到」这类现象，
> **先来这里查，多半是系统限制而不是 bug。**
> 相关的采集器清单见 [`MONITOR.md`](MONITOR.md) 第七节。

---

## 一、一句话结论

**「频率能读、占用率读不到」不是 bug，是 Android 12+ 的 SELinux 策略。**

从 Android 12 起，`untrusted_app` 域被移出了大量 `/proc` 与 `/sys` 路径的可读集合。
影响范围比多数人预期的大得多 —— **连列目录都会被拒**。

---

## 二、可读性地图

### 2.1 普通应用（无 root）**读不到**

| 路径 | 后果 | 代码位置 |
| --- | --- | --- |
| `/proc/stat` | CPU 占用率读不到 | `LoadMonitor` |
| `/proc/loadavg` | 系统负载读不到 | `LoadMonitor` |
| `/sys/class/power_supply/**` | 电量/电流/电压/温度读不到 | `BatteryMonitor` · `PowerMonitor` |
| `/sys/class/thermal/**` | 温度读不到 —— **连 `listFiles()` 都静默返回 `null`** | `TemperatureMonitor` |
| `/sys/class/kgsl/**`（骁龙 GPU） | GPU 使用率恒为 `--` | `GpuMonitor` |
| `/proc/[pid]/stat`（**其他 uid** 的进程） | **能列出 300 多个 pid 目录，但每一个都解析失败** | `ProcessMonitor` |
| `/sys/kernel/debug/**` | 任何情况下都需要 root | 多处 |

> [!warning] 「连列目录都拒」是最反直觉的一条
> 典型表现是 `File.listFiles()` **静默返回 `null`**（不抛异常），
> 于是代码走进「目录不存在」的分支 —— 看起来像路径写错了，
> 实际上目录存在，只是没权限看。
>
> `TemperatureMonitor` 里专门记了这条：`/sys/class/thermal` 在 Android 12+ 下
> 连目录枚举都被 SELinux 拦。

### 2.2 普通应用**可以**读

| 路径 | 内容 |
| --- | --- |
| `/proc/meminfo` | 内存 |
| `/proc/cpuinfo` | CPU 信息 |
| `/sys/devices/system/cpu/**` | cpufreq 频率、调速器、各核信息 |
| `/proc`（**目录本身**） | 可以列出 pid 目录名 |

> 这解释了那个经典错觉：**「CPU 频率能读到，为什么占用率读不到？」
> —— 因为它们在两个不同的路径集合里。**

### 2.3 不需要文件系统：`BatteryManager`

电量优先走 **`BatteryManager` 的粘性广播**（`Intent.ACTION_BATTERY_CHANGED`）：

```java
intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
```

它是**系统服务，始终可用、无需 root**。

> [!important] 这里曾经写反过
> `BatteryMonitor` 的旧注释写着「不走 BatteryManager 接口（部分机型 `getIntProperty` 失效），
> **直接读 sysfs 最可靠**」。
>
> 实测在 Android 12+ 上，SELinux 已把 `/sys/class/power_supply/**` **整体**移出
> `untrusted_app` 可读集合，而 `BatteryManager` 走系统服务、始终可用 ——
> **顺序必须倒过来：BatteryManager 首选，sysfs 兜底。**
>
> 这是「以厂商/版本的实测结果覆盖过时经验」的一个典型例子。

---

## 三、取数策略：普通读优先，root 兜底

统一封装在 **`core/util/NodeReader.java`** —— 所有节点读取都走它。

```
read(path, rootAllowed)
   │
   ├─ ① 已在 ROOT_PATHS 缓存里？ → 直接 root cat
   │
   ├─ ② 普通读（FileReader）
   │      成功 → 返回
   │
   └─ ③ 普通读失败且 rootAllowed
          ├─ 最近 15 秒内失败过？ → 放弃，返回 null（FAIL_BACKOFF_MS）
          └─ 否则 su cat → 成功则写入 ROOT_PATHS 缓存
```

| 常量 | 值 | 作用 |
| --- | --- | --- |
| `ROOT_PATHS` | `Set` | 已知需要 root 才能读的路径缓存 |
| `ROOT_PATHS_LIMIT` | 32 | 超过后清空重建 |
| `PROBE_COOLDOWN_MS` | **0L** | （故意取 0，见下） |
| `FAIL_BACKOFF_MS` | 15,000L | root 不可用时 15 秒内不再尝试 |

> [!warning] `PROBE_COOLDOWN_MS` 为什么必须是 0
> 看起来「失败后限流」更稳妥，但会有一个严重副作用：
> 首次遍历几十个 `thermal_zone` 建索引时，如果每 2 秒才允许探测一个新路径，
> **40 个热区需要 80 秒才能建完，期间温度一直显示 `--`**。
>
> 防抖的职责交给 `FAIL_BACKOFF_MS` 就够了 —— 一次失败就让接下来 15 秒内不再发起 root 探测，
> 足以避免「无 root 设备上反复卡超时」。

> [!warning] 不要为每个路径 / 每个 pid 起一次 root 进程
> 每次 `RootShell.execOut` 都是一次进程往返。
> 300 个进程 × 1 次往返 = 完全不可接受。
> **枚举类操作一律「一条 root 命令批量 dump」。**

---

## 四、逐个采集器的处理方式

### `LoadMonitor` —— 两次采样求差

CPU 占用率的原理是**读 `/proc/stat` 做两次采样的差值**（与 vtools 完全一致）。
Android 12+ 下 `/proc/stat` 与 `/proc/loadavg` 都被 SELinux 拒绝，走 root 兜底。

### `ProcessMonitor` —— 一条 shell 一次搞定

原实现是纯 `/proc` 读取，注释里写着「内核默认 `hidepid=0` 时可读」——
**这在 Android 12+ 不成立**。实测 SELinux 不允许 `untrusted_app` 读取
**其他 uid** 进程的 `/proc/[pid]/stat`，结果是
「能列出 300 多个 pid 目录，但每一个都解析失败」，监视器只能看到自己，最终显示 `--`。

现在改成**一条 shell 命令一次性 dump**（与星野Scene 交给 root 守护进程执行 `top` 是同一思路）：

```sh
awk '{print "S|" FILENAME "|" $0}' /proc/[0-9]*/stat   # 全部进程的 stat
ps -A -w -o PID,USER,ARGS                              # 归属用户 + 完整命令行
```

**只有一次 root 往返**，且 `awk` / `ps` 各只 fork 一次，耗时与进程数无关地保持在几十毫秒级。

取值策略：**先普通读；若解析到的进程数明显偏少**（说明被 SELinux 拦了），
改用 root dump，并记下「本设备必须走 root」，后续刷新直接走 root，
不再做无用的普通读尝试。

**解析规则**（Linux `/proc/[pid]/stat`）：

| 字段 | 位置 |
| --- | --- |
| pid | 第 1 段 |
| comm | 第 2 段，**括在 `()` 中，可能含空格和括号** |
| utime | 第 14 字段（= 括号后片段索引 11） |
| stime | 第 15 字段（= 括号后片段索引 12） |
| rss（单位：**页**） | 第 24 字段（= 括号后片段索引 21） |

> [!warning] comm 可能含空格与括号
> 所以**不能简单按空格 split** 再取第 2 个字段 —— 必须先定位 `(` 与最后一个 `)`，
> 从括号之后再开始切分。

**展示名的选择**：内核把进程名截成 **`comm` 字段的 15 字符上限**，
所以长包名会被截断。因此展示时**优先用从命令行推出来的包名**：

```java
packageOf(command)      // 从命令行第一段提取包名，并剥掉 :remote 之类的后缀
isLikelyPackageName(s)  // 粗校验：不含 '/'、含 '.'、字符集限字母数字下划线点
```

不是包名（含路径分隔符 / 无点）时返回 `null`，回退显示 `comm`。

> [!warning] CPU 占比是**两次采样的差值**，所以「首帧必然没有数」
> `collectWithPercent()` 靠 `prevTimes` 里的上一次 CPU 时间算区间占用率 ——
> **首次采样没有基线**，若按差值筛选，结果必然是**空列表**，悬浮窗 / 进程页要再等一个刷新周期才有内容。
>
> 现在的做法是**首帧改按累计 CPU 时间排序**：第一帧立刻有列表、有顺序，占比也表示
> 「开机以来的累计占用」（可解释），下一帧起自动切回真实区间占用率。
> 另外：**root dump 的超时是 6 秒**（`DUMP_TIMEOUT_MS`），因为采集走的是
> **6 个悬浮窗共享的单线程池**，超时设大了会一次拖垮所有监视器。

### `TemperatureMonitor` —— 避免每秒全量遍历

CPU 温度需要遍历 `/sys/class/thermal` 下的 `thermal_zone*` 目录，读每个的 `type` 和 `temp`。
**不能每秒对几十个 zone 做全量「目录枚举 + type + temp」三连读**，
所以缓存「可读出有效温度的 zone 目录名」（如 `thermal_zone0`）。

电池温度候选路径：

```
/sys/class/power_supply/battery/temp
/sys/class/power_supply/Battery/temp
/sys/class/power_supply/bms/temp
```

（Android 12+ 基本都读不到 → 回退 `BatteryManager`。）

### `GpuMonitor` —— 候选路径链 + 首次命中缓存

不同 SoC 暴露的 sysfs 节点路径、内容格式、单位都不一样，**无法用单一路径覆盖**。
做法是「候选路径链 + 首次探测缓存」：

1. 按命中率从高到低排列候选节点，逐个尝试，**第一个能读出有效值的即命中**；
2. 命中后把路径缓存下来（悬浮窗每 500ms ~ 2s 采一次，每次遍历 8 个文件是白白浪费 IO）；
3. **单位随路径一起记录**（`FreqUnit`），避免靠数值量级去猜。

覆盖：高通骁龙（kgsl） / 麒麟（`gpufreq/mali_ondemand/utilisation`） /
联发科天玑（ged hal） / 其他 Mali / devfreq（通用 `cur_freq`）。

> [!warning] 不要用 `exists()` 做前置检查
> 单看 `exists()` 会**留下一个永远读不到值的路径** ——
> Android 12+ 下这些路径**连 `stat` 都可能被拒**。
>
> 正确做法是**直接读，读不到自然跳过**（`NodeReader` 内部有失败退避，
> 不会反复卡超时）。

> [!note] 为什么骁龙 GPU 恒为 `--`
> 原先只对 `/sys/kernel/` 前缀放行 root 兜底，导致
> `/sys/class/kgsl/kgsl-3d0/*` 永远读不到。现已按实测结果放宽。

---

## 五、显示策略

**读不到一律显示 `--`，不要显示 `0`。**

- `0` 是一个合法的测量值，会把「读不到」伪装成「真的是 0」；
- `--` 明确表达「这项数据在本设备上不可得」。

各采集器用**哨兵返回值**表达「读不到」：

| 采集器 | 哨兵 | 含义 |
| --- | --- | --- |
| `GpuMonitor` | `-1` | 全部候选节点都读不到 |
| `BatteryMonitor` | `-1` | `readFromBatteryManager` 失败 |
| `NodeReader.readFirstLine` | `null` | 读不到 |

---

## 六、本机实测环境

| 项 | 值 |
| --- | --- |
| 设备 | Redmi K70 Ultra |
| 序列号 | `45850c2f` |
| 系统 | Android 16 |
| 屏幕 | 1440 × 3200 @ 560dpi |
| Root | KernelSU |

> [!warning] 一个反直觉的现象
> 这台机器上 **应用内 `su` 可用，但 `adb shell su` 不通**。
> 所以**不能用「adb 能不能 su」来判断应用内能不能 root**。

---

## 七、遇到「数据不对」时的排查顺序

1. **先确认是不是权限边界** —— 对照本文第二节的表。
   命中就是**预期行为**，不要当 bug 修。
2. **确认设备有没有 root** —— 有 root 时 `NodeReader` 会兜底，结果是「慢但能读到」；
   无 root 时就是 `--`。
3. **确认路径有没有被缓存** —— 首次探测会慢（要建索引），第二次应该就快了。
   如果每次都慢，说明缓存没生效。
4. **确认有没有在日志里刷 root 超时** —— 无 root 设备上反复探测会卡超时，
   靠 `FAIL_BACKOFF_MS`（15 秒）防抖。
5. **最后才怀疑代码** —— 这时再看是不是路径写错、字段索引错、
   或者 `split` 用错了（比如 `comm` 含空格那条坑）。
