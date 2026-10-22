# 子系统：主题引擎

> 代码位置：`ui/theme/`（4 个类）+ `ui/settings/ThemeActivity.kt` / `ThemeScreen.kt` +
> `res/values/attrs.xml` / `aurora_accent_overlays.xml` / `themes.xml`。
> 参考对象：KernelSU Manager 的主题能力（`参考仓库/KernelSU-main`）。

---

## 一、核心难题：XML View 的主题属性在资源期就定死了

KernelSU Manager 是 **100% Compose**，颜色由 materialkolor 在**运行时**从
「种子色 + PaletteStyle + ColorSpec」现算 —— 想改配色改个状态就行。

AuroraApp 的主 UI 是 **XML View**，主题属性**必须在资源期确定**，
无法在运行时注入任意色值。这就是整个主题引擎要解决的核心矛盾。

### 解法：一套算法，两条出口

```
                 ui/theme/AuroraPalette.java   ← 唯一算法实现
                 （纯 Java，零 Android 依赖，
                  自实现 sRGB ↔ CIE Lab ↔ LCh 转换）
                            │
        ┌───────────────────┴───────────────────┐
        │                                       │
   出口 ① 运行时                            出口 ② 构建前
        │                                       │
   AuroraPalette.generate(...)           AuroraPalette.main(args)
   给 Compose 层现算完整配色              生成 144 × 2 个 ThemeOverlay
   （底部导航 / 主题页 / 预览卡）          → res/values(-night)/aurora_accent_overlays.xml
        │                                       │
        └─────────── 同一份算法 ────────────────┘
              ⇒ Compose 与 XML 的配色始终一致
```

**关键点：算法是同一份。** 改配色算法必须改 `AuroraPalette`，
然后**重新跑生成器**把 `aurora_accent_overlays.xml` 重新生成 ——
否则 XML 层和 Compose 层会开始互相不一致。

---

## 二、`AuroraPalette`（483 行）

### 2.1 色彩空间

用 **CIE Lab / LCh**：

- **L** = 色调（tone，0 黑 ~ 100 白）；
- **C** = 彩度；
- **h** = 色相角。

与 Material Color Utilities 的 HCT 思路一致，但**不需要 CAM16 的复杂度**。
`colorFromLCh()` 会自动把超出 sRGB 色域的彩度**逐步收敛**，保证输出可用。

### 2.2 维度

| 维度 | 数量 | 常量 | 说明 |
| --- | --- | --- | --- |
| 种子色 | **16** | `KEY_COUNT` / `SEED_COLORS` | 索引 0 = 品牌绿 `0xFF00C853`，1..15 对齐 KernelSU 预设色板 |
| 调色板风格 | **9** | `STYLE_COUNT` / `STYLE_NAMES` / `STYLE_LABELS` | 见下表 |
| 色彩规范 | **2** | `SPEC_COUNT` / `SPEC_LABELS` | `2021 规范` / `2025 规范` |

**9 种风格**（对应 materialkolor 的 `PaletteStyle`）：

| 索引 | 常量名 |
| --- | --- |
| 0 | `STYLE_TONAL_SPOT` |
| 1 | `STYLE_NEUTRAL` |
| 2 | `STYLE_VIBRANT` |
| 3 | `STYLE_EXPRESSIVE` |
| 4 | `STYLE_FIDELITY` |
| 5 | `STYLE_CONTENT` |
| 6 | `STYLE_MONOCHROME` |
| 7 | `STYLE_RAINBOW` |
| 8 | `STYLE_FRUIT_SALAD` |

风格决定三件事：**主色相偏移 / 主彩度 / 中性彩度**。

### 2.3 代码生成出口

`AuroraPalette.main(String[] args)` 会：

1. 遍历 `KEY_COUNT × STYLE_COUNT`（16 × 9 = **144**）；
2. 每个组合生成一份 `Scheme`，写出 `ThemeOverlay.Aurora.Accent.<key>_<style>`；
3. day / night 各一套 → 两个文件各 144 个 `<style>`；
4. 同时生成 `AccentOverlays.java` 的 `TABLE[强调色索引][调色板风格]` → style 资源 id 查表。

生成的资源文件头部写着：

```
自动生成，请勿手改。重新生成：
  javac -d out AuroraPalette.java
  java -cp out com.aurora.app.ui.theme.AuroraPalette app/src/main/res app/src/main/java/com/aurora/app/ui/theme
```

一个覆盖层长这样：

```xml
<style name="ThemeOverlay.Aurora.Accent.0_0" parent="">
    <item name="auroraAccent">#FF206C33</item>
    <item name="auroraOnAccent">#FFFFFFFF</item>
</style>
```

> `parent=""` 是**故意为空**的 —— 覆盖层只提供两条属性，其余继承宿主主题。

### 2.4 `AccentOverlays`

```java
public static final int[][] TABLE = { ... };   // [keyIndex][styleIndex] → style 资源 id
public static int of(int keyIndex, int styleIndex);   // 越界自动 clamp
```

---

## 三、`ThemeManager`（422 行）

### 3.1 职责

| 职责 | 方法 |
| --- | --- |
| 偏好持久化 | `SharedPreferences`，`PREFS_NAME = "aurora_settings"` |
| 设置全局夜间模式 | `applyGlobal(Context)` → `AppCompatDelegate.setDefaultNightMode(...)` |
| 给单个 Activity 叠覆盖层 | `applyToActivity(Activity)` |
| 字号缩放 | `wrapFontScale(Context)` |
| 自动套用到所有 Activity | `init(Application)` 注册生命周期回调 |

> `init(Application)` 注册了生命周期回调，**所有 Activity 自动套用主题**，
> 不需要逐个 `onCreate` 里改。

### 3.2 色彩模式

| 常量 | 值 | 行为 |
| --- | --- | --- |
| `MODE_SYSTEM` | 0 | 跟随系统 |
| `MODE_LIGHT` | 1 | 浅色 |
| `MODE_DARK` | 2 | 深色 |
| `MODE_DARK_AMOLED` | 3 | 深色 · AMOLED |
| `MODE_COUNT` | 4 | — |

### 3.3 覆盖层的叠加顺序（`applyToActivity`）

```java
activity.setTheme(R.style.Theme_Aurora);
int overlay = AccentOverlays.of(getAccentIndex(), getPaletteStyle());
if (overlay != 0) {
    activity.getTheme().applyStyle(overlay, true);  // 强调色覆盖层
}
if (isAmoled) {
    activity.getTheme().applyStyle(R.style.ThemeOverlay_Aurora_Amoled, true);
}
```

顺序很重要：**先基主题 → 再强调色 → 最后 AMOLED**，
AMOLED 覆盖层要能盖掉强调色覆盖层里的表面色。

> [!warning] Material You 动态取色已于 2026-10-12 **整体删除**
> 原因：打开开关 → Compose 立刻重组 → `dynamicLightColorScheme(context)` 去读
> `android.R.color.system_accent1_*`，**部分国产 ROM 裁剪了这批系统色** →
> 抛在重组里 → 组合树挂掉 → 闪退。连带 `values-v31/themes.xml`、`Theme.Aurora.Dynamic`、
> `ThemeManager` 的三个方法、`AuroraColorScheme` 的 `dynamic` 参数全部移除。
> 相关残留偏好 `dynamic_color` **代码已不再读取**，留着无害。

### 3.4 「重建」与「不重建」的分工

| 改什么 | 走哪条路 |
| --- | --- |
| **色彩模式** | 只调 `setDefaultNightMode` —— **不 `recreate()`** |
| **强调色 / 调色板风格 / 色彩规范 / 字号** | **需要 `recreate()`** |

> **液态玻璃**与**背景模糊**是两个**独立开关**，它们的 setter **刻意不递增
> `theme_revision`** —— 因为它们只走状态流、不影响资源期配色，
> 递增版本号会导致不必要的重建。

`theme_revision` 是主题版本号，存在偏好里。
`MainActivity` 记住 `appliedThemeRevision`，返回前台时若发现版本号变了就**重建一次**套用新配色。

### 3.5 字号缩放：为什么不用 `applyOverrideConfiguration`

字号缩放**不在 `applyToActivity` 里处理**。

正确做法是 `Activity.attachBaseContext` + `createConfigurationContext`
（封装在 `ThemeManager.wrapFontScale(Context)` 里）。

> [!warning] 不要改回 `applyOverrideConfiguration`
> 它在 `recreate()` 后，**部分国产 ROM 会触发「配置变更 → 再次 relaunch」的死循环导致卡死**。

字号范围：`FONT_SCALE_MIN = 0.85f` ~ `FONT_SCALE_MAX = 1.30f`。

### 3.6 偏好键

`theme_mode` · `accent_index` · `palette_style` · `color_spec` ·
`font_scale` · `liquid_glass` · `bottom_bar_blur` · `swipe_tabs` · `check_update` · `theme_revision`

> `dynamic_color` 已随动态取色功能删除，**代码不再读取**。
> 偏好实例本身由 `ThemeManager.getPrefs()` **静态缓存**（Compose 每次重组都要读 3~8 个键，
> 走 `getSharedPreferences()` 有系统侧缓存查找 + 锁的开销）。

---

## 四、颜色必须写成 attr，不能写 `@color/`

这是主题能不能生效的**前提条件**。

`res/values/attrs.xml` 里定义了 5 个语义属性：

| attr | 用途 |
| --- | --- |
| `auroraAccent` | 全局强调色（默认品牌绿，被覆盖层改写） |
| `auroraOnAccent` | 强调色**之上**的前景色（强调色作背景时的文字/图标色），由覆盖层按明度给出，**避免白字白底** |
| `auroraCard` | 卡片表面色 |
| `auroraBackgroundElevated` | 抬升背景 |
| `auroraFill` | 填充色 |

> [!warning] 会被覆盖层改写的颜色**必须引用 `?attr/auroraXxx`**
> 写成 `@color/xxx` 的资源**无法在运行时被覆盖层改写** ——
> 切主题时那个控件就是不动。
>
> 所有的颜色都要**日夜成对**（`values/colors.xml` + `values-night/colors.xml`），
> **禁止硬编码 hex**。

### AMOLED 模式的连锁反应

AMOLED 覆盖层把 `auroraFill` 改成 `#0D0D0D`、`auroraCard` 改成**纯黑**
（`values-night/colors.xml` 里 `ios_background_grouped` = `#000000`）。

于是出现两个问题：

1. **页面上直接放的按钮/卡片会与背景糊成一片** ——
   已由 `Widget.Aurora.Miuix.Button` 的 **1dp `@color/ios_separator` 描边**解决。
   **卡片目前还没加描边 = 技术债**（新做的 `activity_qdl.xml` 已手动补上描边）。
2. **`bg_console` 必须用 `?attr/auroraCard` 而不是 `?attr/auroraFill`** ——
   因为浅色模式下 `auroraFill` 与 `ios_background_grouped` **同为 `#F2F2F7`**，
   用 `auroraFill` 会让输出框在浅色下隐形。

---

## 五、主题设置页

| 项 | 值 |
| --- | --- |
| 宿主 | `ui/settings/ThemeActivity.kt` |
| 界面 | `ui/settings/ThemeScreen.kt`（864 行，**整页 Compose**） |
| 入口 | 设置页 → 主题 |

> [!important] 主题设置必须是独立 Activity
> 原因：色彩模式/强调色切换需要 `recreate()` 才能生效。
> 如果主题页挂在主界面里，`recreate()` 会把用户正在操作的页面一起重建，
> 体验上表现为「点一下就跳回上一页」。

页面里包含一张**主题预览卡**（`ThemePreviewCard`，对应 KernelSU 的同名组件）——
让用户在切换强调色 / 风格 / 模式**之前**就能看到整体观感。

> [!warning] 预览卡的取色**必须与实机同源**（2026-10-12 修正了三处跑偏）
> 1. **卡片**用 `auroraCardColor()`（= `surfaceContainer`，等价 XML 的 `?attr/auroraCard`），
>    而不是 `surfaceContainerHigh` —— 否则预览里的卡片比实机更亮；
> 2. **底栏不随 AMOLED 黑化**：真实底栏 `FloatingBottomBarCompose` 恒用 `surfaceContainer`，
>    所以预览里也不能跟着页面底色一起变纯黑；
> 3. **底栏是居中胶囊**（四点 + 首点带 10% `onSurface` 指示底），不是通栏 + 一个圆点。
>
> 另：开液态玻璃 / 背景模糊时，底栏容器色降到 **40% 不透明**（实机就是 `.copy(0.4f)`），
> 预览卡通过 `translucent = glass || blur` 参数对齐。

> [!note] 配色生成做了**记忆化**（2026-10-12，滑动掉帧修复）
> `AuroraPalette.generate()` 是纯计算且不便宜，而主题页一屏要 28 套配色
> （预览 1 + 强调色 16 + 风格 9 + 规范 2）。`auroraColorScheme()` 现在按
> `(dark, accentIndex, styleIndex, specIndex)` 四元组做 **LRU 记忆化**（容量 64）。
> **不要在 Composable 里直接调 `AuroraPalette`**；`Lazy*` 回收 item 会连同 `remember`
> 一起丢掉，所以强调色行刻意用 `Row + horizontalScroll` 而不是 `LazyRow`。

外观分组共 8 个可调项：色彩模式 / 强调色 / 调色板风格 / 色彩规范 /
字号 / 液态玻璃 / 背景模糊 / 滑动切换 tab。详见
[`../FEATURES.md`](../FEATURES.md) 第六节。

---

## 六、改配色时的检查清单

1. [ ] 改的是 `AuroraPalette`（唯一算法入口），不是生成的资源文件；
2. [ ] 改完**重新跑生成器**，重新生成 `aurora_accent_overlays.xml` 与 `AccentOverlays.java`；
3. [ ] 新增的颜色**写成 attr**，并且**日夜成对**；
4. [ ] 在 **4 种色彩模式 × AMOLED** 下都确认过可读性；
5. [ ] 检查 AMOLED 下新加的卡片/按钮**有没有描边**（否则与纯黑背景糊在一起）；
6. [ ] Compose 侧的新组件**记得显式传 `colorScheme`**。
