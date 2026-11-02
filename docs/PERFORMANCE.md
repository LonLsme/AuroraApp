# 性能基线与热点清单

> 本文记录**可复现的测量方法**、**当前基线数据**与**已知热点**。
> 改完不要凭感觉说「快了」，跑一遍下面的命令再写结论。
>
> 最后一次测量：2026-11-02，模拟器 Android 17 / x86_64（`emulator-5554`）。
> 模拟器数字不代表真机绝对值，但**同一台设备上的前后对比**是可信的。

---

## 一、怎么测

### 1.1 冷启动

```bash
adb shell am force-stop com.aurora.app
sleep 2
adb shell am start -W -n com.aurora.app/.ui.main.MainActivity | grep TotalTime
```

跑 4~5 次取稳定值。**第一次通常偏高**（dex 与类加载缓存冷），不要拿它当基线。

`TotalTime` 含进程创建 + Application.onCreate + Activity 到首帧。想拆开看
`WaitTime − TotalTime`（那是前面进程启动的部分）。

### 1.2 CPU 占用（比 `dumpsys cpuinfo` 准）

`dumpsys cpuinfo` 在低负载时会四舍五入成 `0%`，看不出差异。直接读内核记账的 jiffies：

```bash
PID=$(adb shell pidof com.aurora.app)
read_cpu() { adb shell cat /proc/$PID/stat | awk '{print $14+$15}'; }
A0=$(read_cpu); sleep 30; A1=$(read_cpu); echo "Δ=$((A1-A0)) jiffies"
```

30 秒 ≈ 3000 jiffies 对应 100% 单核。**同一个 pid 前后比，不要跨进程比。**

### 1.3 帧与内存

```bash
adb shell dumpsys gfxinfo com.aurora.app | grep -E "Janky|percentile"
adb shell dumpsys meminfo com.aurora.app | grep -E "Views:|TOTAL PSS|Java Heap"
```

`Views:` 是 View 实例总数 —— 判断「是不是一次 inflate 了太多页面」最快的指标。

### 1.4 包体积

解压后按条目聚合（`classes*.dex` 占比一眼可见）：

```bash
python -c "
import zipfile
z=zipfile.ZipFile('app/build/outputs/apk/release/app-release.apk')
items=sorted(((i.file_size,i.filename) for i in z.infolist()),reverse=True)
print('解压后合计 %.1f MB'%(sum(s for s,_ in items)/1e6))
for s,n in items[:8]: print('%8.2f MB  %s'%(s/1e6,n))
"
```

---

## 二、当前基线

| 指标 | 数值 | 备注 |
| --- | --- | --- |
| 冷启动 TotalTime | **~1940 ms** | 优化前 1990 ms |
| 设备页可见 30 秒 CPU | **8~11 jiffies** | 优化前 36 jiffies |
| 刷机页可见 30 秒 CPU | ~5 jiffies | 对照组，几乎只剩轮询 |
| Views 总数 | 350 | 4 个页面全部 inflate，见 4.1 |
| TOTAL PSS | ~110 MB | 波动较大 |
| Java Heap | ~21 MB | |
| release APK | 23,151,215 B | 解压后 42.8 MB |
| release dex 合计 | **~29.8 MB** | 占解压后体积 70%，见 4.2 |
| assets/flash_tools.zip | 9.90 MB | adb/fastboot 二进制，功能依赖 |

---

## 三、已修的热点（提交 `246d4df`）

### 3.1 设备页轮询切走后不停 —— 影响最大

**现象**：停在刷机页或设置页时，设备页仍在每秒读 8 核 sysfs、每 5 秒起一次 adb。

**根因**：ViewPager2 换页只 detach / attach **页面 View**，不会让 Fragment 走
`onPause` —— 设备页 Fragment 始终是 RESUMED，它的 tick 完全感知不到"自己被切走了"。
`FlashPage` 早有 `onTabShown/onTabHidden`，设备页是 Fragment，当时漏了这一环。

**改法**：`MainActivity.onPageSelected` 里显式通知；`DeviceFragment` 加 `tabVisible`
标记，避免 root 检测回调（`onRootStateChanged`）把它重新拉起来。

**实测**：36 → 8~11 jiffies / 30 秒（**−75%**）。

> [!warning] 新增页面时别忘这一条
> 凡是页面内有 `postDelayed` 轮询的，都必须能被宿主通知「你不可见了」。
> 判断依据**不能**用 `isResumed()` —— 在 ViewPager2 里它恒为 true。

### 3.2 CPU 圆环每秒无条件重绘

`CpuRingGaugeView.setProgress()` 在值相同时仍 `updateFillColor()` + `invalidate()`，
还会 `cancel()` 正在跑的高度动画。8 个核 = 每秒 8 次无效重绘 + 8 次动画中断。

**改法**：值变化 < 0.01 直接 return（比的是 `targetProgress`，不是 `displayedProgress`
—— 动画途中 target 已定、displayed 还在追，再送同一个 target 没有意义）。

### 3.3 bindCpuCoreRings 每秒 24 次无效 setText

`setText` / `setTextColor` / `setVisibility` 每一个都会 `requestLayout` + `invalidate`。
而空闲核的频率在两次采样之间完全一样。

**改法**：`setTextIfChanged` / `setTextColorIfChanged` / `setVisibilityIfChanged`；
通用入口 `setText(view, value)` 也走变化检测，静态行一起受益。

### 3.4 getSysfsCpuCount 每秒遍历目录 + 编译正则

`listFiles()` 走十几个 stat，再对每个名字 `matches("\\d+")` —— `String.matches`
**每次都编译 Pattern**。

**改法**：核数缓存（热插拔只改 `online` 的值，不增删 `cpuN` 目录）+ 手写
`isAllDigits` 取代正则；`readCpuMin/MaxFreqKhz` 去掉多余的目录 stat
（读失败与目录不存在在这条链路上等价，都是 −1）。

### 3.5 FpsChartView 每帧 new 两个 Path

帧率记录页主体，采样期间持续 `invalidate()`，60fps 下每秒 120 个 `Path`。
**在绘制阶段分配会招 GC，而 GC 停顿会直接反映到帧率图上** —— 测量工具污染被测量对象。

**改法**：提成 `reuseLine` / `reuseFill` 成员，每次 `reset()` 复用
（`reset` 保留已分配的点缓冲区，比 `new` 便宜）。

### 3.6 ApkDownloader 结束后不松静态引用

`static Job current` 持有 `Job`，而 `Job.listener` 常是界面的匿名类 —— 一次更新
泄漏一个已经关掉的界面。

**改法**：`finally` 里 `current == this` 时置空（用 `== this` 判，避免误清用户
刚发起的新任务）。

### 3.7 offscreenPageLimit 从「全部」改成 1

原来 `setOffscreenPageLimit(pages.length - 1)`，首次布局把 4 个页面全 measure + layout
一遍（累计 280+ 节点：玩机页 104、刷机页 91、设备页 59）。

**改法**：设 1，首帧只排当前页与相邻页。冷启动 1990 → 1940 ms。

> [!note] 省的是排版，不是创建
> 页面 View 仍由 `activity_main.xml` 的 `include` 一次性 inflate —— `Views: 350`
> 没有下降。要连创建一起省，得把 `include` 换成 `ViewStub`（见 4.1）。

---

## 四、待优化清单（按影响排序）

### 4.1 4 个页面在启动时就全部 inflate（结构性，收益最大但风险最高）

`activity_main.xml` 里 3 个 `include` + 1 个 `NestedScrollView` 包 `page_flash`，
都是**立即展开**。冷启动要建 280+ 个节点，其中 3 页用户可能根本不看。

| 布局 | 节点数 | 层级 |
| --- | --- | --- |
| `fragment_explore_placeholder.xml` | 104 | 8 |
| `page_flash.xml` | 91 | 5 |
| `fragment_device.xml` | **59** | **10（超过 Lint 阈值）** |
| `fragment_settings.xml` | 30 | — |

**改法（两条路，按风险递增）**：

1. **只压平设备页**：`fragment_device.xml` 深度 10，Lint 已标 `TooDeepLayout`。
   深度每多一层，measure 就多两次遍历。压掉 Lint 指出的那层 `UselessParent`
   （第 64 行附近）就能到 9 层。
2. **include → ViewStub**：非首屏的 3 页延迟到首次滑到才 inflate。`Views` 会从
   350 降到 ~150，冷启动预计再省 200~300 ms。风险在于 `setupTabPager()` 依赖
   `findViewById` 拿到页面 View，必须改成「切到该页时才 inflate 并交给 adapter」——
   会动到 ViewPager2 的装载时序，需要完整回归。

### 4.2 release 没开 R8，dex 29.8 MB（体积最大的杠杆）

`app/build.gradle.kts:73` 明确写了 `isMinifyEnabled = false`，理由是"大量反射读
系统属性 + Compose/XML 混合，混淆规则没做过完整回归"。

这个顾虑只对**混淆**成立，对**裁剪**不成立。可以只 shrink 不 obfuscate：

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(...)
}
```

```
# proguard-rules.pro —— 只裁剪，不改名
-dontobfuscate
-keep class com.aurora.app.** { *; }      # 自家代码全留（反射读属性的入口都在这）
-keep class com.aurora.app.core.** { *; }
```

预计 dex 从 29.8 MB 降到 12~16 MB，APK 从 23 MB 降到 15~18 MB。
**注意**：`-dontobfuscate` + 全 keep 自家包，等于只裁第三方库的未用代码 ——
收益主要来自 Compose / AndroidX 里没用到的部分。真要再省，得逐条补 keep 规则再验证。

**验证方式**：装 release 包，把「设备页 / 刷机页 / 玩机页 / 主题页 / 自动更新」
各走一遍，重点看反射读的属性是否还正常（`ro.boot.*`、`getprop` 那几条）。

### 4.3 38 个未用资源 + 语言资源

`resources.arsc` 1.44 MB，Lint 报 38 个 `UnusedResources`。

```bash
grep "UnusedResources" app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt
```

**改法**：先删确定没用的；再看有没有多余语言目录（本项目只面向中文用户，
`resConfigs` 收到 `zh`、`en` 能省一截 arsc）。

### 4.4 8 处 notifyDataSetChanged（列表滚动卡顿来源）

`notifyDataSetChanged()` 会让 RecyclerView 丢掉全部动画、重新测量所有可见项。
高频刷新的列表（进程列表、已装应用、隐藏应用配置）尤其明显。

| 文件 | 行 |
| --- | --- |
| `ui/appmanager/InstalledAppAdapter.java` | 41 |
| `ui/flash/DeviceAppAdapter.java` | 66, 74 |
| `ui/module/ModuleBackupActivity.java` | 81, 146 |
| `ui/payload/PayloadActivity.java` | 203, 715 |
| `ui/plugin/HiddenAppListConfigActivity.java` | 655, 688, 701, 1046, 1080 |

**改法**：数据整体换掉时用 `DiffUtil`（或 `AsyncListDiffer`）；只是单个项状态变了
（勾选、选中）用 `notifyItemChanged(position)`。

### 4.5 38 处 Overdraw

多数来自「卡片背景 + 卡片内子 View 又设了不透明背景」。AMOLED 纯黑模式下尤其值得查
（黑底上叠黑背景是纯浪费）。

```bash
grep "Overdraw" app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt
```

**改法**：子 View 的背景改成 `@null` 或透明；只在最外层保留一次绘制。

### 4.6 activity_payload.xml 的 NestedWeights

`activity_payload.xml:305` 被 Lint 标 `NestedWeights` —— 带 weight 的 LinearLayout
嵌套会让 measure 成本随层数**指数上升**（每层都要为 weight 重新分配剩余空间）。

**改法**：把内层 weight 换成 `ConstraintLayout` 的 `layout_constraintWidth_percent`，
或者用 `LinearLayout` + 固定宽度替代。

---

## 五、通用铁律

1. **别在 tick 里无条件写 UI**。`setText` / `setTextColor` / `setVisibility` /
   `invalidate` 每一个都会触发重排重绘。先比再写，永远比"反正也不贵"便宜。
2. **别在 `onDraw` 里分配**。`new Path()` / `new RectF()` / `String.format()` 全部
   提到成员或外部。绘制阶段的 GC 停顿会直接变成掉帧。
3. **周期性的"不变值"要缓存**。核数、硬件频率上下限、`ContextCompat.getColor`
   的结果、`Pattern` —— 这些在进程生命周期内基本不变，别跟着 tick 一起跑。
4. **页面里的轮询必须能被停掉**。见 3.1，`isResumed()` 不能用来判可见性。
5. **测完再说话**。`dumpsys cpuinfo` 会四舍五入成 0%，要用 jiffies 增量；
   冷启动要跑 4~5 次；跨进程的 CPU 数字没有可比性。
