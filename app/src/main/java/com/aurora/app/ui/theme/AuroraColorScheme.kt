package com.aurora.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * 把 [AuroraPalette] 生成的调色板桥接成 Compose 的 [ColorScheme]。
 *
 * Compose 侧（底部悬浮导航、主题页）统一走这里取色，与 XML 侧由
 * `ThemeOverlay.Aurora.Palette` 提供的强调色同源，避免出现
 * 「底栏一种强调色、tab 指示线另一种强调色」的割裂。
 *
 * <p>配色本身已由开发者固定（种子色 + 调色板配方写死在 [AuroraPalette] 里），
 * 唯一的输入只剩「深色 / 浅色」这一个布尔值，因此不再需要按多维偏好做 LRU 记忆化 ——
 * 全部取值空间就是 2 套，直接两个槽位缓存即可。
 */
private val SCHEME_CACHE = HashMap<Boolean, ColorScheme>(2)

/** 读取当前偏好生成配色（底栏等「跟随全局设置」的场景用）。 */
@Composable
fun rememberAuroraColorScheme(dark: Boolean): ColorScheme =
    remember(dark) { auroraColorScheme(dark) }

/** 非 Compose 上下文也可直接调用；结果按深/浅两套缓存。 */
fun auroraColorScheme(dark: Boolean): ColorScheme {
    synchronized(SCHEME_CACHE) {
        SCHEME_CACHE[dark]?.let { return it }
    }
    val built = buildAuroraColorScheme(dark)
    synchronized(SCHEME_CACHE) {
        SCHEME_CACHE[dark] = built
    }
    return built
}

/** 真正执行一次调色板生成并映射到 [ColorScheme]（结果由 [auroraColorScheme] 缓存）。 */
private fun buildAuroraColorScheme(dark: Boolean): ColorScheme {
    val s = AuroraPalette.generate(dark)
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
