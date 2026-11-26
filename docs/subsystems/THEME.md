# 子系统：主题引擎

> 代码位置：`ui/theme/`（3 个类）+ `ui/settings/SettingsFragment.java` +
> `res/layout/fragment_settings.xml` / `res/values/attrs.xml` / `aurora_palette.xml` / `themes.xml`。

---

## 一、核心难题：XML View 的主题属性在资源期就定死了

Compose 可以在运行时任意注入色值，而 AuroraApp 的主 UI 是 **XML View**，
主题属性**必须在资源期确定**。这就是整个主题引擎要解决的核心矛盾。

### 解法：一套算法，两条出口

```
                 ui/theme/AuroraPalette.java   ← 唯一算法实现
                 （纯 Java，零 Android 依赖，
                  自实现 sRGB ↔ CIE Lab ↔ LCh 转换）
                            │
        ┌───────────────────┴───────────────────┐
        │                                       │
   出口 ① 运行时                            出口 ② 资源期
        │                                       │
   AuroraPalette.generate(dark)          同步 2 个 hex 到
   给 Compose 层现算完整配色              res/values(-night)/aurora_palette.xml
   （底部导航 / 主题页）                  → ThemeOverlay.Aurora.Palette
        │                                       │
        └─────────── 同一份算法 ────────────────┘
              ⇒ Compose 与 XML 的配色始终一致
```

**配色由开发者固定，App 内没有任何自定义入口** —— 不是隐藏控件，而是
强调色 / 调色板风格 / 色彩规范这三个维度已从代码里整体删除
（旧版曾有 16 色 × 9 风格 × 2 规范 = 288 套组合 + 144 个自动生成的覆盖层）。

---

## 二、`AuroraPalette`

### 2.1 色彩空间

用 **CIE Lab / LCh**：**L** = 色调（tone，0 黑 ~ 100 白）、**C** = 彩度、**h** = 色相角。
与 Material Color Utilities 的 HCT 思路一致，但**不需要 CAM16 的复杂度**。
`colorFromLCh()` 会自动把超出 sRGB 色域的彩度**逐步收敛**，保证输出可用。

### 2.2 固定配方

| 项 | 值 | 说明 |
| --- | --- | --- |
| 种子色 | `SEED_COLOR = 0xFF0EA5E9` | Aurora 天空蓝（同 Tailwind sky-500），改它等于整体换肤 |
| 调色板风格 | TonalSpot（写死在 `build()`） | 色相沿用种子色，彩度 `seedChroma × 0.9` 收在 40~56（蓝色低彩度会发灰） |
| 色彩规范 | 2025（写死在 `build()`） | 页面底 tone `6/98`、卡片 `17/92`、描边 `70/40`；主色浅色取 tone **48**（Material3 默认 40 会把蓝压成深钢蓝） |

派生：`tertiary` = 主色相 +60°，`secondary` 彩度 = 主彩度 × 0.5，错误色固定色相 25。

### 2.3 两条出口如何保持一致

`generate(false/true)` 的 `primary` / `onPrimary` 就是 `aurora_palette.xml` 里的两个 hex
（浅色 `#FF0B79AC` / `#FFFFFFFF`，深色 `#FF91CDFC` / `#FF143347`）。
**改种子色或配方后，这两个文件要手动同步** —— 覆盖层只固化 primary / onPrimary
两个槽位，其余表面色来自 `Base.Theme.Aurora` 的静态属性。

> `parent=""` 是**故意为空**的 —— 覆盖层只提供两条属性，其余继承宿主主题。

---

## 三、`ThemeManager`

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

### 3.2 色彩模式（唯一保留的主题项）

| 常量 | 值 | 行为 |
| --- | --- | --- |
| `MODE_SYSTEM` | 0 | 跟随系统 |
| `MODE_LIGHT` | 1 | 浅色 |
| `MODE_DARK` | 2 | 深色 |
| `MODE_COUNT` | 3 | — |

> [!note] 旧的第 4 种模式「深色（纯黑 / AMOLED）」已随配色自定义一起移除。
> `getThemeMode()` 读到越界值会归一到 `MODE_DARK`，避免老用户升级后
> 被系统浅色模式突然刷白。`ThemeOverlay.Aurora.Amoled` 也已删除。

### 3.3 覆盖层的叠加顺序（`applyToActivity`）

```java
activity.setTheme(R.style.Theme_Aurora);
activity.getTheme().applyStyle(R.style.ThemeOverlay_Aurora_Palette, true);
```

只有一层，深浅由资源限定符区分，不再需要按偏好挑选 style。

> [!warning] Material You 动态取色已于 2026-10-12 **整体删除**
> 原因：打开开关 → Compose 立刻重组 → `dynamicLightColorScheme(context)` 去读
> `android.R.color.system_accent1_*`，**部分国产 ROM 裁剪了这批系统色** →
> 抛在重组里 → 组合树挂掉 → 闪退。连带 `values-v31/themes.xml`、`Theme.Aurora.Dynamic`、
> `ThemeManager` 的三个方法、`AuroraColorScheme` 的 `dynamic` 参数全部移除。

### 3.4 「重建」与「不重建」的分工

| 改什么 | 走哪条路 |
| --- | --- |
| **色彩模式** | 调 `setDefaultNightMode`；若前后夜间模式相同（如「跟随系统」与「深色」而系统当前就是深色）则额外 `recreate()` 一次 |
| **字号** | 必须 `recreate()`（字号作用于资源层） |
| **底栏效果 / 滑动切页** | 不 `recreate()`，只走状态流 |

`theme_revision` 是主题版本号，存在偏好里。
`MainActivity` 记住 `appliedThemeRevision`，返回前台时若发现版本号变了就**重建一次**套用新配色。

### 3.5 字号缩放：为什么不用 `applyOverrideConfiguration`

正确做法是 `Activity.attachBaseContext` + `createConfigurationContext`
（封装在 `ThemeManager.wrapFontScale(Context)` 里）。
字号范围：`FONT_SCALE_MIN = 0.85f` ~ `FONT_SCALE_MAX = 1.30f`。

> [!warning] 不要改回 `applyOverrideConfiguration`
> 它在 `recreate()` 后，**部分国产 ROM 会触发「配置变更 → 再次 relaunch」的死循环导致卡死**。

### 3.6 偏好键

`theme_mode` · `font_scale` · `bottom_bar_effect` · `swipe_tabs` ·
`check_update` · `theme_revision`

> `liquid_glass` / `bottom_bar_blur` 是旧版的两个布尔键：不再参与读取，只在
> `setBarEffect()` 里同步写回，供装回旧版本 APK 时观感不跳变。
> 首次读取时若没有 `bottom_bar_effect`，由 `migrateLegacyBarEffect()` 折算一次并落盘。

> 历史键 `accent_index` / `palette_style` / `color_spec` / `dynamic_color` 代码已不再读取，
> 留在偏好里无害（不做迁移清理，避免动用户的 SharedPreferences）。
> 偏好实例本身由 `ThemeManager.getPrefs()` **静态缓存**（Compose 每次重组都要读几个键，
> 走 `getSharedPreferences()` 有系统侧缓存查找 + 锁的开销）。

---

## 四、颜色必须写成 attr，不能写 `@color/`

这是主题能不能生效的**前提条件**。

`res/values/attrs.xml` 里定义了 9 个语义属性：

| attr | 用途 |
| --- | --- |
| `auroraAccent` | 全局强调色（品牌绿，被 `ThemeOverlay.Aurora.Palette` 改写） |
| `auroraOnAccent` | 强调色**之上**的前景色（强调色作背景时的文字/图标色），由覆盖层按明度给出，**避免白字白底** |
| `auroraCard` | 卡片表面色 |
| `auroraBackgroundElevated` | 抬升背景 |
| `auroraFill` | 填充色（图标底色、胶囊底一类） |
| `auroraTextPrimary` | 主文字色：标题、正文、数值 |
| `auroraTextSecondary` | 次要文字色：说明、副标题、单位 |
| `auroraTextTertiary` | 第三级：占位提示、极弱注释、禁用值 |
| `auroraSeparator` | 分隔线、1dp 描边 |

> [!warning] 会被覆盖层改写的颜色**必须引用 `?attr/auroraXxx`**
> 写成 `@color/xxx` 的资源**无法在运行时被覆盖层改写**。
>
> 所有的颜色都要**日夜成对**（`values/colors.xml` + `values-night/colors.xml`），
> **禁止硬编码 hex**。

### 四个文字色属性（2026-11-17）

收口前每套配色各带一份自己的文字色：`ios_text_primary`(#000000) /
`explore_slate_800`(#1E293B) / `fluent_on_surface`(#2D2F32)，设备页还有 5 处硬编码 `#16181C`。
同一个语义三个值，翻页时「这页偏灰、那页偏蓝」就是这么来的；**硬编码那几处在深色下根本不跟着变**。

收口后布局里不再出现任何具体文字色值，代码侧统一走 `ui/theme/ThemeColors`：

```java
tvValue.setTextColor(ThemeColors.textPrimary(this));    // R.attr.auroraTextPrimary
tvHint.setTextColor(ThemeColors.textSecondary(this));   // R.attr.auroraTextSecondary
```

`ThemeColors` 内部用 `MaterialColors.getColor(context, attr, 0)`，取不到返回透明 ——
属性在 `Base.Theme.Aurora` 里恒有定义，取不到说明该 Activity 没用 Aurora 主题，
让屏幕上直接看不见字比硬编码一个颜色更好排查。

替换覆盖：`android:textColor(Hint)` / `app:titleTextColor` / `app:tabTextColor` /
`app:hintTextColor` / `app:startIconTint` / `android:tint` / `app:tint` /
`android:background`（图标底色 → `auroraFill`）/ `app:strokeColor`（→ `auroraSeparator`）。
**`android:textColor` 之外的同名语义都要跟着换** —— 只换 textColor 会留下
「文字统一了、旁边的图标还是老颜色」的半吊子状态。

### 深色下为什么要给按钮加描边

深色模式里 `auroraFill` 与页面背景的明度很接近，页面背景又是 `#0D150D`，
**页面上直接放的整行按钮会与背景糊成一片** —— 所以 `Widget.Aurora.Miuix.Button`
带 1dp `@color/ios_separator` 描边（不是装饰）。新加卡片/按钮时同样要检查这一点。

> [!note] `bg_console` 必须用 `?attr/auroraCard` 而不是 `?attr/auroraFill`
> 因为浅色模式下 `auroraFill` 与 `ios_background_grouped` **同为 `#F2F2F7`**，
> 用 `auroraFill` 会让输出框在浅色下隐形。

---

## 五、主题设置页

主题项全部内联在**设置 tab** 里（`fragment_settings.xml` + `SettingsFragment`），
不再有独立的主题页 —— 配色不可自定义后，剩下的项撑不满一页，独立成页反而多一次跳转。

| 行 | 交互 | 说明 |
| --- | --- | --- |
| 主题色 | 点整行弹悬浮菜单 | 跟随系统 / 浅色 / 深色，当前项灰色变主色 + 右侧打勾；
菜单宽度按内容测量（下限 120dp）、右沿贴住分组卡片右边，底色由 `GradientDrawable` 现画 + 1dp 淡描边 |
| 界面字号 | 点整行弹悬浮菜单 | 85% / 100% / 115% / 130%，行尾显示当前百分比 |
| 底栏效果 | 点整行弹悬浮菜单 | 不透明 / 磨砂 / 液态玻璃三档互斥，行尾显示当前档；只写 `BottomBarState.barEffect`，底栏立刻重绘，不重建 Activity |
| 滑动切换导航页面 | 开关（整行可点） | 调 `MainActivity.applySwipeTabsEnabled()`，即时作用到 ViewPager2 |

三个「点整行弹菜单」的行共用 `ui/widget/AuroraChoiceMenu`：宽度按内容测量
（下限 120dp、上限锚点宽 - 32dp）、右沿贴住锚点右边缘、20dp 圆角纯色底 + 1dp 淡描边、
选中项主色文字 + 右侧打勾、出入场用 `Animation.Aurora.PopupMenu`。
CPU 控制页的调度器下拉也走同一个入口 —— 凡「一行 + 若干互斥选项」都不再各写一套。

色彩模式与字号会改到资源层，所以改完直接 `recreate()` 宿主 `MainActivity`；
其余两项只改状态流 / 一个开关，不重建（重建这个 God Activity 有明显卡顿）。

### 5.1 底栏效果为什么是互斥三档

旧版是两个独立开关（液态玻璃 / 背景模糊），4 种组合里「玻璃关 + 模糊开」与
「玻璃开 + 模糊开」在观感上几乎无法区分：折射（`lens`）与提饱和（`vibrancy`）
都要靠背景里的明暗与色彩才看得出来，而底栏下方多为纯色留白，两个效果叠上去
几乎没有像素变化 —— 用户会认为液态玻璃开关失效。

现在收敛成三档，差异落在**不依赖背景内容**的维度上（`FloatingBottomBarCompose.kt`）：

| 档位 | 效果 | 容器 alpha | 边沿高光 |
| --- | --- | --- | --- |
| 液态玻璃 | `lens` → `blur` → `vibrancy` | 0.24 | 1.00 |
| 磨砂 | `blur` | 0.46 | 0 |
| 不透明 | 无 | 1.00 | 0 |

「有没有边沿高光」是结构差异，任何背景下都看得见；透明度再拉开 0.22，
即使背景是纯白也能靠「更透」区分。效果链顺序不能改：折射要吃到清晰边缘所以要排在
模糊之前，提饱和排在模糊之后否则会被抹平（旧顺序实测切换只差 2/255）。

> 重建会套一层过渡动画：`res/anim/theme_fade_out`（160ms 淡出）+ `theme_fade_in`
> （220ms 淡入 + 1.5% 放大），由 `SettingsFragment#recreateWithFade` 在 `recreate()`
> 之后立刻 `overridePendingTransition()` 挂上。夜间模式由 AppCompat 自动重建的那条路
> 无法在 recreate 之后插入，改为 `applyGlobal()` 之后补同一条 transition。

配色只有深浅两套，`auroraColorScheme(dark)` 直接两个槽位缓存，不再需要 LRU 记忆化
（旧版一屏要算 28 套配色，那是当时强调色 16 + 风格 9 + 规范 2 的产物）。

---

## 六、改配色时的检查清单

1. [ ] 改的是 `AuroraPalette.SEED_COLOR` 或 `build()` 里的配方，不是散落的 hex；
2. [ ] 改完把 `generate(false/true)` 的 `primary` / `onPrimary` 同步到
       `res/values(-night)/aurora_palette.xml`；
3. [ ] 新增的颜色**写成 attr**，并且**日夜成对**；
4. [ ] 在**浅色 / 深色 / 跟随系统（系统深色）** 三种组合下都确认过可读性；
5. [ ] 检查深色下新加的卡片/按钮**有没有描边**（否则与背景糊在一起）；
6. [ ] Compose 侧的新组件**记得显式传 `colorScheme`**（走 `rememberAuroraColorScheme(dark)`）。
