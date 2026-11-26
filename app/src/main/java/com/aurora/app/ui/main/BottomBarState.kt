package com.aurora.app.ui.main

import com.aurora.app.ui.theme.ThemeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object BottomBarState {
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab

    /**
     * 底栏效果档位，取值同 [ThemeManager.BAR_EFFECT_OPAQUE] / [ThemeManager.BAR_EFFECT_FROSTED] /
     * [ThemeManager.BAR_EFFECT_GLASS]。
     *
     * <p>底栏是 Compose 组件，档位通过状态流即时生效 —— 这样在设置页切换后，
     * 返回主界面无需重建 MainActivity（重建这个 God Activity 有明显卡顿）。
     * 初值与偏好不同步的风险由 MainActivity.onResume（读偏好后写回本状态流）兜底；
     * 设置页切换时也直接写这里，所以底栏会立刻重绘。
     */
    private val _barEffect = MutableStateFlow(ThemeManager.BAR_EFFECT_GLASS)
    val barEffect: StateFlow<Int> = _barEffect

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
    }

    fun getSelectedTab(): Int = _selectedTab.value

    fun setBarEffect(effect: Int) {
        _barEffect.value = effect
    }
}
