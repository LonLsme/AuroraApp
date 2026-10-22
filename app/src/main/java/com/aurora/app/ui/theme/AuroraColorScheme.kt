package com.aurora.app.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * 把 [AuroraPalette] 生成的调色板桥接成 Compose 的 [ColorScheme]。
 *
 * Compose 侧（底部悬浮导航、主题页、主题预览卡）统一走这里取色，
 * 与 XML 侧由 `ThemeOverlay.Aurora.Accent.*` 提供的强调色同源，
 * 避免出现「底栏一种强调色、tab 指示线另一种强调色」的割裂。
 *
 * <h3>为什么要做记忆化（2026-10-12 滑动掉帧修复）</h3>
 *
 * [AuroraPalette.generate] 每产出一套配色，都要跑一遍完整的
 * sRGB → Lab → LCh → 色板映射（纯计算，无 IO）。而主题页一屏内同时要画出
 * 「预览卡 1 套 + 强调色色块 16 套 + 风格色块 9 套 + 规范色块 2 套」共 **28 套**；
 * 上下滚动时 LazyColumn 会把滚出屏幕的 item 连同其 `remember` 缓存一起回收，
 * 回滚时又要全量重算 —— 这正是「上下滑动掉帧」的根因。
 *
 * 输入只有 `(dark, accentIndex, styleIndex, specIndex)` 四元组，取值空间
 * `2 × 16 × 9 × 2 = 576` 种，因此直接对四元组结果做 LRU 记忆化：
 * 滚动看过的配色一律命中缓存，不再重算。
 */

/** 缓存容量：主题页一次最多用到 28 套，留出余量以免「刚算完就被淘汰」。 */
private const val SCHEME_CACHE_CAPACITY = 64

/**
 * 配色缓存（LRU）。
 *
 * <p>这里用 [LinkedHashMap] 的 accessOrder 模式手写，而非 `android.util.LruCache`，
 * 是为了让本文件在纯 JVM 下也能编译 —— 项目的离线断言用 javac 直接编译该包。
 * 访问做了同步：理论上都在主线程重组里调用，但非 Compose 调用方也可能从别处进来。
 */
private val SCHEME_CACHE = object : LinkedHashMap<String, ColorScheme>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ColorScheme>?): Boolean =
        size > SCHEME_CACHE_CAPACITY
}

/** 读取当前偏好生成配色（底栏等「跟随全局设置」的场景用）。 */
@Composable
fun rememberAuroraColorScheme(context: Context, dark: Boolean): ColorScheme =
    rememberAuroraColorScheme(
        dark = dark,
        accentIndex = ThemeManager.getAccentIndex(context),
        styleIndex = ThemeManager.getPaletteStyle(context),
        specIndex = ThemeManager.getColorSpec(context),
    )

@Composable
fun rememberAuroraColorScheme(
    dark: Boolean,
    accentIndex: Int,
    styleIndex: Int,
    specIndex: Int,
): ColorScheme = remember(dark, accentIndex, styleIndex, specIndex) {
    auroraColorScheme(dark, accentIndex, styleIndex, specIndex)
}

/**
 * 非 Compose 上下文也可直接调用。
 * 结果按 `(dark, accentIndex, styleIndex, specIndex)` 四元组记忆化，重复取色不再重算。
 */
fun auroraColorScheme(
    dark: Boolean,
    accentIndex: Int,
    styleIndex: Int,
    specIndex: Int,
): ColorScheme {
    val key = (if (dark) '1' else '0').toString() +
            '|' + accentIndex + '|' + styleIndex + '|' + specIndex
    synchronized(SCHEME_CACHE) {
        SCHEME_CACHE[key]?.let { return it }
    }
    val built = buildAuroraColorScheme(dark, accentIndex, styleIndex, specIndex)
    synchronized(SCHEME_CACHE) {
        SCHEME_CACHE[key] = built
    }
    return built
}

/** 真正执行一次调色板生成并映射到 [ColorScheme]（结果由 [auroraColorScheme] 缓存）。 */
private fun buildAuroraColorScheme(
    dark: Boolean,
    accentIndex: Int,
    styleIndex: Int,
    specIndex: Int,
): ColorScheme {
    val s = AuroraPalette.generate(accentIndex, styleIndex, specIndex, dark)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(s.primary),
        onPrimary = Color(s.onPrimary),
        primaryContainer = Color(s.primaryContainer),
        onPrimaryContainer = Color(s.onPrimaryContainer),
        secondary = Color(s.secondary),
        secondaryContainer = Color(s.secondaryContainer),
        onSecondaryContainer = Color(s.onSecondaryContainer),
        tertiary = Color(s.tertiary),
        tertiaryContainer = Color(s.tertiaryContainer),
        onTertiaryContainer = Color(s.onTertiaryContainer),
        error = Color(s.error),
        errorContainer = Color(s.errorContainer),
        onErrorContainer = Color(s.onErrorContainer),
        background = Color(s.background),
        onBackground = Color(s.onBackground),
        surface = Color(s.surface),
        onSurface = Color(s.onSurface),
        surfaceVariant = Color(s.surfaceVariant),
        onSurfaceVariant = Color(s.onSurfaceVariant),
        surfaceContainerLowest = Color(s.surfaceContainerLowest),
        surfaceContainerLow = Color(s.surfaceContainerLow),
        surfaceContainer = Color(s.surfaceContainer),
        surfaceContainerHigh = Color(s.surfaceContainerHigh),
        surfaceContainerHighest = Color(s.surfaceContainerHighest),
        outline = Color(s.outline),
        outlineVariant = Color(s.outlineVariant),
        inverseSurface = Color(s.inverseSurface),
        inverseOnSurface = Color(s.inverseOnSurface),
        inversePrimary = Color(s.inversePrimary),
    )
}

/**
 * 页面底色：AMOLED 模式下压到纯黑，与 XML 侧的 Amoled 覆盖层保持一致。
 *
 * <p>对应 XML 的 `?attr/auroraBackground`。
 */
fun auroraBackgroundColor(scheme: ColorScheme, amoled: Boolean): Color =
    if (amoled) Color.Black else scheme.background

/**
 * 卡片色：AMOLED 下与底色融为一体（纯黑）。
 *
 * <p>对应 XML 的 `?attr/auroraCard` —— Miuix 卡片、分组容器用的都是它。
 * <b>注意不要改用 `surfaceContainerHigh`</b>：那会让预览卡里的卡片比真实界面更亮，
 * 正是本次要修掉的「预览与界面不一致」之一。
 */
fun auroraCardColor(scheme: ColorScheme, amoled: Boolean): Color =
    if (amoled) Color.Black else scheme.surfaceContainer
