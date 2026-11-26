package com.aurora.app.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import com.aurora.app.R
import com.aurora.app.ui.animation.DampedDragAnimation
import com.aurora.app.ui.animation.InteractiveHighlight
import com.aurora.app.ui.theme.ThemeManager
import com.aurora.app.ui.theme.rememberAuroraColorScheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize

fun attachMainScreen(composeView: ComposeView, nativeContentView: View, onTabSelected: (Int) -> Unit) {
    composeView.setContent {
        /*
         * 底栏配色统一由 AuroraPalette（与 XML 侧 ThemeOverlay.Aurora.Palette 同源）生成，
         * 因此深色 / 浅色都能在底栏正确体现，不会出现「底栏一种强调色、tab 指示线另一种」的割裂。
         * 配色本身由开发者固定，这里只需要知道当前是不是深色。
         */
        val context = LocalContext.current
        val dark = isSystemInDarkTheme()
        val scheme = rememberAuroraColorScheme(dark)
        // 底栏效果档位：由设置项控制，通过状态流即时生效（无需重建 MainActivity）。
        // 三档 → 两个布尔：磨砂只有模糊，玻璃必然含模糊，不透明两者都不要。
        val barEffect by BottomBarState.barEffect.collectAsState()
        val glassEnabled = barEffect == ThemeManager.BAR_EFFECT_GLASS
        val blurEnabled = barEffect != ThemeManager.BAR_EFFECT_OPAQUE
        MaterialTheme(colorScheme = scheme) {
            val backdrop = rememberLayerBackdrop {
                drawRect(Color.Transparent)
                drawContent()
            }
            val selectedTab by BottomBarState.selectedTab.collectAsState()

            Box(modifier = Modifier.fillMaxSize()) {
                // Native views layer
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                ) {
                    AndroidView(
                        factory = { nativeContentView },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Floating bottom bar layer
                Box(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    FloatingBottomBar(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )
                            .padding(bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                        selectedIndex = { selectedTab },
                        onSelected = { index ->
                            if (selectedTab != index) {
                                onTabSelected(index)
                            }
                        },
                        backdrop = backdrop,
                        tabsCount = 4,
                        glassEnabled = glassEnabled,
                        blurEnabled = blurEnabled
                    ) {
                    FloatingBottomBarItem(
                        onClick = {
                            onTabSelected(0)
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            modifier = Modifier.width(22.dp).height(22.dp),
                            painter = painterResource(R.drawable.ic_device_info),
                            contentDescription = "设备",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "设备",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible
                        )
                    }
                    FloatingBottomBarItem(
                        onClick = {
                            onTabSelected(1)
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            modifier = Modifier.width(22.dp).height(22.dp),
                            painter = painterResource(R.drawable.ic_nav_flash),
                            contentDescription = "刷机",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "刷机",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible
                        )
                    }
                    FloatingBottomBarItem(
                        onClick = {
                            onTabSelected(2)
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            modifier = Modifier.width(22.dp).height(22.dp),
                            painter = painterResource(R.drawable.ic_explore),
                            contentDescription = "玩机",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "玩机",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible
                        )
                    }
                    FloatingBottomBarItem(
                        onClick = {
                            onTabSelected(3)
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            modifier = Modifier.width(22.dp).height(22.dp),
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "设置",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible
                        )
                    }
                }
            }
        }
    }
}
}

val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

@Composable
fun RowScope.FloatingBottomBarItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalFloatingBottomBarTabScale.current
    Column(
        modifier
            .clip(ContinuousCapsule)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val currentScale = scale()
                scaleX = currentScale
                scaleY = currentScale
            },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
fun FloatingBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    /**
     * 液态玻璃：折射（lens）+ 色彩增强（vibrancy）+ 满边沿高光。参考 KernelSU 的 `settings_enable_glass`。
     *
     * <p>这两个布尔由设置页的三档折算而来（{@code ThemeManager.getBarEffect}）：
     * 玻璃 → (true, true)，磨砂 → (false, true)，不透明 → (false, false)。
     */
    glassEnabled: Boolean = true,
    /**
     * 背景模糊。玻璃档必然为 true（玻璃本身就含模糊）；为 false 且 [glassEnabled] 也为 false 时
     * 底栏退化为不透明胶囊。
     */
    blurEnabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val isInLightTheme = !isSystemInDarkTheme()
    val accentColor = MaterialTheme.colorScheme.primary
    /** 任一效果开启即需要半透明底与交互高光；全关则退化为不透明底栏。 */
    val isTranslucent = glassEnabled || blurEnabled
    /**
     * 液态玻璃要比磨砂更"透"、高光更实 —— 这是**不依赖背景内容**的那部分差异。
     *
     * <p>原因：折射与提饱和都要靠背景里的明暗与色彩才看得出来。背景若是一大片纯白
     * （设置页、刷机页的卡片区就是），两者叠加也几乎没有像素变化。所以把差异落在
     * 容器透明度与边沿高光上：玻璃 24% 不透明 + 高光拉满，磨砂 46% + 无高光。
     * 「有边沿高光」与「没有边沿高光」是结构差异，任何背景下都看得见。
     */
    val glassAlpha = 0.24f
    val frostedAlpha = 0.46f
    val containerColor = when {
        glassEnabled -> MaterialTheme.colorScheme.surfaceContainer.copy(glassAlpha)
        blurEnabled -> MaterialTheme.colorScheme.surfaceContainer.copy(frostedAlpha)
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    /** 边沿高光只属于液态玻璃；磨砂与不透明都不画，避免磨砂看起来还是玻璃。 */
    val highlightAlpha = if (glassEnabled) 1f else 0f

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density) {
        derivedStateOf {
            if (totalWidthPx == 0f) 0f else {
                val fraction = (offsetAnimation.value / totalWidthPx).coerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
    }

    var currentIndex by remember(selectedIndex) { mutableIntStateOf(selectedIndex()) }

    class DampedDragAnimationHolder {
        var instance: DampedDragAnimation? = null
    }
    val holder = remember { DampedDragAnimationHolder() }

    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex().toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset ->
                val anim = holder.instance ?: return@DampedDragAnimation true
                if (tabWidthPx == 0f) return@DampedDragAnimation false

                val currentValue = anim.value
                val indicatorX = currentValue * tabWidthPx
                val padding = with(density) { 4.dp.toPx() }
                val globalTouchX = if (isLtr) {
                    val touchX = indicatorX + offset.x
                    padding + touchX
                } else {
                    val touchX = totalWidthPx - padding - tabWidthPx - indicatorX + offset.x
                    touchX
                }
                globalTouchX in 0f..totalWidthPx
            },
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = targetValue.fastRoundToInt().coerceIn(0, tabsCount - 1)
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
                animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                }
            }
        ).also { holder.instance = it }
    }

    LaunchedEffect(selectedIndex) {
        snapshotFlow { selectedIndex() }.collectLatest { currentIndex = it }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
            dampedDragAnimation.animateToValue(index.toFloat())
            onSelected(index)
        }
    }

    val interactiveHighlight = remember(animationScope, tabWidthPx) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f
                )
            }
        )
    }

    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                    tabWidthPx = contentWidthPx / tabsCount
                }
                .graphicsLayer { translationX = panelOffset }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        if (isTranslucent) {
                            // 顺序有讲究：折射要吃到**清晰**的边缘，所以排在模糊之前；
                            // 提饱和排在模糊之后，否则会被随后的模糊直接抹平。
                            // 旧顺序（vibrancy → blur → lens）两个效果都被吃掉，
                            // 实测开模糊后切换液态玻璃，底栏像素最大差只有 2/255（等于没反应）。
                            if (glassEnabled) lens(24f.dp.toPx(), 24f.dp.toPx())
                            if (blurEnabled) blur(8f.dp.toPx())
                            if (glassEnabled) vibrancy()
                        }
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = highlightAlpha)
                    },
                    shadow = {
                        Shadow.Default.copy(
                            color = Color.Black.copy(if (isInLightTheme) 0.1f else 0.2f)
                        )
                    },
                    layerBlock = {
                        if (isTranslucent) {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(if (isTranslucent) interactiveHighlight.modifier else Modifier)
                .height(64.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalFloatingBottomBarTabScale provides {
                if (isTranslucent) lerp(1f, 1.2f, dampedDragAnimation.pressProgress) else 1f
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { ContinuousCapsule },
                        effects = {
                            if (isTranslucent) {
                                val progress = dampedDragAnimation.pressProgress
                                if (glassEnabled) lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
                                if (blurEnabled) blur(8f.dp.toPx())
                                if (glassEnabled) vibrancy()
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = highlightAlpha * dampedDragAnimation.pressProgress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(if (isTranslucent) interactiveHighlight.modifier else Modifier)
                    .height(56.dp)
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        if (tabWidthPx > 0f) {
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .graphicsLayer {
                        val contentWidth = totalWidthPx - with(density) { 8.dp.toPx() }
                        val singleTabWidth = contentWidth / tabsCount
                        val progressOffset = dampedDragAnimation.value * singleTabWidth
                        translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                    }
                    .then(if (isTranslucent) interactiveHighlight.gestureModifier else Modifier)
                    .then(dampedDragAnimation.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { ContinuousCapsule },
                        effects = {
                            if (glassEnabled) {
                                val progress = dampedDragAnimation.pressProgress
                                lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, true)
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = highlightAlpha * dampedDragAnimation.pressProgress)
                        },
                        shadow = { Shadow(alpha = if (isTranslucent) dampedDragAnimation.pressProgress else 0f) },
                        innerShadow = {
                            InnerShadow(
                                radius = 8f.dp * dampedDragAnimation.pressProgress,
                                alpha = if (isTranslucent) dampedDragAnimation.pressProgress else 0f
                            )
                        },
                        layerBlock = {
                            if (isTranslucent) {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                            }
                        },
                        onDrawSurface = {
                            val progress = if (isTranslucent) dampedDragAnimation.pressProgress else 0f
                            drawRect(
                                color = if (isInLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                                alpha = 1f - progress
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
                    .height(56.dp)
                    .width(with(density) { ((totalWidthPx - 8.dp.toPx()) / tabsCount).toDp() })
            )
        }
    }
}
