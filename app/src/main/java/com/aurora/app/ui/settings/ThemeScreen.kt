package com.aurora.app.ui.settings

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.app.R
import com.aurora.app.ui.main.BottomBarState
import com.aurora.app.ui.theme.AuroraPalette
import com.aurora.app.ui.theme.ThemeManager
import com.aurora.app.ui.theme.auroraBackgroundColor
import com.aurora.app.ui.theme.auroraCardColor
import com.aurora.app.ui.theme.auroraColorScheme
import com.aurora.app.ui.theme.rememberAuroraColorScheme

/**
 * 主题设置页（参考 KernelSU Manager 的 ColorPaletteScreenMaterial）。
 *
 * <p>为什么用 Compose 而不是 XML：
 * <ul>
 *   <li>主题项切换后本页可<b>立即重绘</b>（Compose 重组），不必重建 Activity；
 *       只有「色彩模式」这种影响全局 XML 资源的项才需要重建，且重建的是本页这个极轻的界面；</li>
 *   <li>主题预览卡、色块选中动画这类连续绘制的控件，Compose 实现远比 XML 简洁。</li>
 * </ul>
 *
 * <p>配色来源与 XML 侧完全同源（见 [com.aurora.app.ui.theme.AuroraPalette]），
 * 因此预览卡里看到的颜色就是整套界面实际生效的颜色。
 */
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val systemDark = isSystemInDarkTheme()

    // 本地状态：与偏好同步。改动后本页立即重绘，XML 页面靠 theme_revision 在 onResume 时刷新。
    var mode by remember { mutableIntStateOf(ThemeManager.getThemeMode(context)) }
    var accentIndex by remember { mutableIntStateOf(ThemeManager.getAccentIndex(context)) }
    var styleIndex by remember { mutableIntStateOf(ThemeManager.getPaletteStyle(context)) }
    var specIndex by remember { mutableIntStateOf(ThemeManager.getColorSpec(context)) }
    var glass by remember { mutableStateOf(ThemeManager.isLiquidGlassEnabled(context)) }
    var blur by remember { mutableStateOf(ThemeManager.isBlurEnabled(context)) }
    var swipeTabs by remember { mutableStateOf(ThemeManager.isSwipeTabsEnabled(context)) }
    var fontScale by remember { mutableFloatStateOf(ThemeManager.getFontScale(context)) }

    val amoled = mode == ThemeManager.MODE_DARK_AMOLED
    val dark = when (mode) {
        ThemeManager.MODE_LIGHT -> false
        ThemeManager.MODE_DARK, ThemeManager.MODE_DARK_AMOLED -> true
        else -> systemDark
    }
    val scheme = rememberAuroraColorScheme(dark, accentIndex, styleIndex, specIndex)

    MaterialTheme(colorScheme = scheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(auroraBackgroundColor(scheme, amoled))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                BackTopBar("主题", onBack)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "preview_title") { SectionTitle("主题预览") }
                    item(key = "preview") {
                        ThemePreviewCard(
                            accentIndex = accentIndex,
                            styleIndex = styleIndex,
                            specIndex = specIndex,
                            dark = dark,
                            amoled = amoled,
                            // 与 FloatingBottomBarCompose 的 isTranslucent 同源：
                            // 液态玻璃 / 背景模糊任一开启时，真实底栏的容器色是 40% 不透明的。
                            translucent = glass || blur
                        )
                    }

                    item(key = "accent_title") { SectionTitle("强调色") }
                    item(key = "accent_row") {
                        AccentSwatchRow(
                            accentIndex = accentIndex,
                            styleIndex = styleIndex,
                            specIndex = specIndex,
                            dark = dark,
                            onSelect = { index ->
                                if (ThemeManager.setAccentIndex(context, index)) {
                                    accentIndex = index
                                }
                            }
                        )
                    }

                    item(key = "mode_title") { SectionTitle("色彩模式") }
                    item(key = "mode_row") {
                        ModeRow(mode = mode) { target ->
                            val darkBefore = dark
                            if (ThemeManager.setThemeMode(context, target)) {
                                mode = target
                                // setDefaultNightMode 只在「生效的夜间模式」变化时才自行重建；
                                // 若前后夜间模式相同（如 深色 ↔ 纯黑、系统↔同向模式），需手动重建一次，
                                // 否则 AMOLED 覆盖层不会生效。
                                val darkAfter = when (target) {
                                    ThemeManager.MODE_LIGHT -> false
                                    ThemeManager.MODE_DARK, ThemeManager.MODE_DARK_AMOLED -> true
                                    else -> systemDark
                                }
                                ThemeManager.applyGlobal(context)
                                if (darkBefore == darkAfter) {
                                    activity?.recreate()
                                }
                            }
                        }
                    }

                    item(key = "appearance_title") { SectionTitle("外观") }
                    item(key = "appearance_group") {
                        SettingGroup {
                            SwitchRow(
                                title = "液态玻璃",
                                summary = "底部悬浮导航使用折射 + 色彩增强的玻璃质感",
                                checked = glass,
                                onCheckedChange = { on ->
                                    if (ThemeManager.setLiquidGlassEnabled(context, on)) {
                                        glass = on
                                        // 底栏是 Compose 组件，通过状态流即时生效，无需重建 MainActivity
                                        BottomBarState.setLiquidGlass(on)
                                    }
                                }
                            )
                            SwitchRow(
                                title = "背景模糊",
                                summary = "底栏对身后的内容做模糊（不含折射）",
                                checked = blur,
                                onCheckedChange = { on ->
                                    if (ThemeManager.setBlurEnabled(context, on)) {
                                        blur = on
                                        BottomBarState.setBlur(on)
                                    }
                                }
                            )
                        }
                    }

                    item(key = "interaction_title") { SectionTitle("交互") }
                    item(key = "interaction_group") {
                        SettingGroup {
                            SwitchRow(
                                title = "滑动切换标签",
                                summary = "允许在页面左右滑动来切换设备 / 安全 / 玩机 / 设置",
                                checked = swipeTabs,
                                onCheckedChange = { on ->
                                    if (ThemeManager.setSwipeTabsEnabled(context, on)) {
                                        swipeTabs = on
                                    }
                                }
                            )
                        }
                    }

                    item(key = "palette_title") { SectionTitle("调色板") }
                    item(key = "palette_group") {
                        SettingGroup {
                            DropdownRow(
                                title = "调色板风格",
                                options = AuroraPalette.STYLE_LABELS.toList(),
                                selectedIndex = styleIndex,
                                onSelect = { index ->
                                    if (ThemeManager.setPaletteStyle(context, index)) {
                                        styleIndex = index
                                    }
                                }
                            )
                            DropdownRow(
                                title = "色彩规范",
                                options = AuroraPalette.SPEC_LABELS.toList(),
                                selectedIndex = specIndex,
                                onSelect = { index ->
                                    if (ThemeManager.setColorSpec(context, index)) {
                                        specIndex = index
                                    }
                                }
                            )
                        }
                    }

                    item(key = "font_title") { SectionTitle("字号") }
                    item(key = "font_card") {
                        FontScaleCard(
                            value = fontScale,
                            onChange = { fontScale = it },
                            onFinished = {
                                if (ThemeManager.setFontScale(context, fontScale)) {
                                    // 字号作用于资源层，本页也需要重建才能看到效果（本页极轻）
                                    activity?.recreate()
                                }
                            }
                        )
                    }

                    item(key = "footer_note") {
                        Text(
                            text = "调色板风格与色彩规范只改变颜色的生成方式，不影响功能。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ===================== 顶栏 =====================

@Composable
private fun BackTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/**
 * 分组小标题，对应 miuix 的 SmallTitle。
 *
 * <p>参数逐条取自 miuix 源码：textStyles.subtitle 为 14sp / Bold，颜色取 onBackgroundVariant，
 * insideMargin 为 PaddingValues(28.dp, 8.dp)。那个 28dp 是相对<b>卡片</b>左边缘算的，
 * 本页卡片自身还有 12dp 水平外边距，所以这里要写成 40dp 才是相对屏幕的同一位置 ——
 * 小标题比卡片左边缘更靠内，正是 Miuix 的观感特征。
 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 8.dp)
    )
}

// ===================== 主题预览卡 =====================

/**
 * 迷你「手机屏」预览：用当前生成的配色画出一个缩小版界面，
 * 让用户在切换强调色 / 风格 / 模式前就能看到整体观感（对应 KernelSU 的 ThemePreviewCard）。
 *
 * <h3>取色必须与真实界面同源（2026-10-12 修正）</h3>
 * 之前有两处「想当然」，导致预览卡里看到的颜色与实机不符：
 * <ol>
 *   <li><b>卡片用错了槽位</b>：这里画的是 {@code surfaceContainerHigh}，而 XML 侧的卡片
 *       （`Widget.Aurora.Miuix.Card` / 分组容器）取的是 {@code ?attr/auroraCard}，
 *       即 {@code surfaceContainer} —— 预览里的卡片比实机更亮。</li>
 *   <li><b>底栏跟着 AMOLED 黑掉了</b>：这里把底栏画成页面底色（AMOLED 下 = 纯黑），
 *       但真实底栏 `FloatingBottomBarCompose` 用的是 {@code surfaceContainer}，
 *       完全不随 AMOLED 变化，两者在纯黑模式下观感差得很远。</li>
 * </ol>
 * 现在统一改为走 [auroraBackgroundColor] / [auroraCardColor] 两个「与 XML 侧同源」的
 * 辅助函数；底栏另按真实形态画成<b>居中胶囊</b>（真实底栏不是通栏）。
 */
@Composable
private fun ThemePreviewCard(
    accentIndex: Int,
    styleIndex: Int,
    specIndex: Int,
    dark: Boolean,
    amoled: Boolean,
    translucent: Boolean
) {
    val configuration = LocalConfiguration.current
    val ratio = if (configuration.screenHeightDp == 0) {
        0.5f
    } else {
        configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
    }
    val preview = remember(dark, accentIndex, styleIndex, specIndex) {
        auroraColorScheme(dark, accentIndex, styleIndex, specIndex)
    }
    // 页面底 / 卡片：与 XML 侧 auroraBackground / auroraCard 同一语义（AMOLED 下均为纯黑）
    val pageBg = auroraBackgroundColor(preview, amoled)
    val cardBg = auroraCardColor(preview, amoled)
    // 底栏容器：真实底栏恒用 surfaceContainer、不参与 AMOLED 黑化；
    // 开了液态玻璃 / 背景模糊时再降到 40% 不透明（对应容器色 .copy(0.4f)）。
    val barBg = if (translucent) {
        preview.surfaceContainer.copy(alpha = 0.4f)
    } else {
        preview.surfaceContainer
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.40f)
                .aspectRatio(ratio),
            shape = RoundedCornerShape(20.dp),
            color = pageBg,
            border = BorderStroke(1.dp, preview.outlineVariant)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .fillMaxWidth()
                        .padding(start = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "Aurora",
                        style = MaterialTheme.typography.labelSmall,
                        color = preview.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(preview.secondaryContainer)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(cardBg)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(preview.primary)
                    )
                }

                // 底部悬浮导航：居中胶囊（不是通栏）+ surfaceContainer 容器。
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.60f)
                            .height(18.dp)
                            .clip(CircleShape)
                            .background(barBg)
                            .padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        repeat(4) { index ->
                            val selected = index == 0
                            Box(
                                modifier = Modifier
                                    .size(if (selected) 14.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) preview.onSurface.copy(alpha = 0.10f)
                                        else Color.Transparent
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(preview.primary)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===================== 强调色色块 =====================

@Composable
private fun AccentSwatchRow(
    accentIndex: Int,
    styleIndex: Int,
    specIndex: Int,
    dark: Boolean,
    onSelect: (Int) -> Unit
) {
    // 用「普通 Row + horizontalScroll」而不是 LazyRow（2026-10-12 滑动掉帧修复）：
    // LazyRow 只组合可见项，垂直滚动把整行滚出屏幕后它会被丢弃，16 个 ColorSwatch
    // 连同各自的 remember 缓存一起失效，回滚时又要重算 16 套调色板。
    // 强调色一共就 16 个、每个 64dp，全量组合的开销远小于反复重算调色板。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(ThemeManager.ACCENT_COUNT) { index ->
            val itemScheme = remember(dark, index, styleIndex, specIndex) {
                auroraColorScheme(dark, index, styleIndex, specIndex)
            }
            ColorSwatch(
                scheme = itemScheme,
                selected = index == accentIndex,
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun ColorSwatch(
    scheme: androidx.compose.material3.ColorScheme,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(targetValue = if (selected) 1.08f else 1f, label = "swatchScale")
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(18.dp),
        color = scheme.surfaceContainer
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            // 上/下半圆分别展示主色容器与第三色容器，比单色块更能反映整套配色的气质
            Canvas(modifier = Modifier.size(42.dp)) {
                drawArc(
                    color = scheme.primaryContainer,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true
                )
                drawArc(
                    color = scheme.tertiaryContainer,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = true
                )
            }

            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .border(2.dp, scheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(12.dp)) {
                            val w = size.width
                            val h = size.height
                            drawLine(
                                color = scheme.onPrimary,
                                start = Offset(w * 0.16f, h * 0.55f),
                                end = Offset(w * 0.42f, h * 0.80f),
                                strokeWidth = w * 0.14f,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = scheme.onPrimary,
                                start = Offset(w * 0.42f, h * 0.80f),
                                end = Offset(w * 0.86f, h * 0.22f),
                                strokeWidth = w * 0.14f,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===================== 色彩模式 =====================

@Composable
private fun ModeRow(mode: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("跟随系统", "浅色", "深色", "纯黑")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { index, label ->
            ModeButton(
                label = label,
                glyph = index,
                selected = mode == index,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    glyph: Int,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) cs.primary else cs.surfaceContainerHigh,
        contentColor = if (selected) cs.onPrimary else cs.onSurfaceVariant
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ModeGlyph(glyph, LocalContentColor.current)
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

/** 四种色彩模式的几何示意（避免引入 material-icons-extended 依赖）。 */
@Composable
private fun ModeGlyph(kind: Int, tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val strokeWidth = size.minDimension * 0.13f
        val r = size.minDimension / 2f - strokeWidth / 2f
        when (kind) {
            0 -> { // 跟随系统：半明半暗
                drawCircle(tint, radius = r, style = Stroke(width = strokeWidth))
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2)
                )
            }
            1 -> { // 浅色：空心圆 + 中心亮芯
                drawCircle(tint, radius = r, style = Stroke(width = strokeWidth))
                drawCircle(tint, radius = r * 0.42f)
            }
            2 -> drawCircle(tint, radius = r) // 深色：实心
            else -> { // 纯黑：实心 + 描边，突出「更黑的深色」
                drawCircle(tint, radius = r, style = Stroke(width = strokeWidth))
                drawCircle(tint, radius = r * 0.70f)
            }
        }
    }
}

// ===================== 分组与行项 =====================

/**
 * 一组同义行的承载卡片，对应 miuix 的 Card。
 *
 * <p>关键参数取自 miuix 源码：CardDefaults.CornerRadius = 16.dp、零阴影、
 * <b>行与行之间没有分隔线</b>（miuix 的 Card 内部不放 Divider，靠每行自身的
 * 56dp 最小高度与 16dp 内边距把行隔开）。水平外边距取示例里的 12dp。
 */
@Composable
private fun SettingGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        content = content
    )
}

/**
 * 开关行，对应 miuix 的 SwitchPreference（即 BasicComponent + 行尾 Switch）。
 *
 * <p>范式参数逐条取自 miuix 源码：最小高 56dp（BasicComponent 的 heightIn(min = 56.dp)）、
 * 内边距 16dp（BasicComponentDefaults.InsideMargin）、标题 17sp Medium（textStyles.headline1）、
 * 摘要 14sp（textStyles.body2）。<b>行首不放图标</b> —— miuix 的 preference 默认不带
 * startAction，靠「标题 + 摘要」的字号层级区分主次，这正是它比一排彩色图标更干净的原因。
 *
 * <p>整行可点即切换，与 miuix 的 SwitchPreference 一致，不必精确点中右侧开关。
 */
@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = summary,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 下拉选择行，对应 miuix 的 OverlayDropdownPreference。
 *
 * <p>版式与 [SwitchRow] 同源：56dp 最小高 + 16dp 内边距 + 17sp Medium 标题，
 * 行尾依次是「当前值（14sp，onSurfaceVariantActions 的语义色）」与右箭头。
 */
@Composable
private fun DropdownRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { expanded = true }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = options.getOrElse(selectedIndex) { "" },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(20.dp)
        )
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(index)
                                    expanded = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = index == selectedIndex,
                                onClick = {
                                    onSelect(index)
                                    expanded = false
                                }
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) { Text("关闭") }
            }
        )
    }
}

/**
 * 字号调节卡：上行是标准的 Miuix 行（56dp 最小高、17sp 标题、尾部显示当前百分比），
 * 下方挂一条滑块。卡片圆角与水平外边距同 [SettingGroup]，保持整页一致。
 */
@Composable
private fun FontScaleCard(value: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "界面字号",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "调整全局文字大小",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = "${(value * 100).toInt()}%",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = ThemeManager.FONT_SCALE_MIN..ThemeManager.FONT_SCALE_MAX,
            steps = 8,
            onValueChangeFinished = onFinished,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ===================== 说明 =====================

// 2026-10-11：原先每个设置行左侧的「图标底片」（GlyphTile，34dp 圆角方块 + 手绘几何图标，
// 共 7 种：玻璃 / 调色板 / 规范 / 字号 / 模糊 / 滑动 / 动态取色）已随 Miuix 范式改造整体移除。
// Miuix 的 preference 行默认不带 startAction，主次全靠「17sp Medium 标题 + 14sp 摘要」的
// 字号层级表达；一排彩色图标反而会与新加的分组小标题争夺注意力。
// 色彩模式那四个按钮里的几何图标（ModeGlyph）保留 —— 它是控件本体的一部分，不是行首装饰。

