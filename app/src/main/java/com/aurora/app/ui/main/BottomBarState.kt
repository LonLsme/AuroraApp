package com.aurora.app.ui.main

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object BottomBarState {
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab

    /**
     * 液态玻璃开关。
     *
     * <p>底栏是 Compose 组件，开关通过状态流即时生效——这样在主题页切换玻璃效果后，
     * 返回主界面无需重建 MainActivity（重建这个 God Activity 有明显卡顿）。
     * 初值与偏好不同步的风险由 [com.aurora.app.ui.settings.ThemeActivity] 进入时写入兜底。
     */
    private val _liquidGlass = MutableStateFlow(true)
    val liquidGlass: StateFlow<Boolean> = _liquidGlass

    /** 背景模糊开关（仅模糊，不含折射）。与液态玻璃相互独立。 */
    private val _blur = MutableStateFlow(true)
    val blur: StateFlow<Boolean> = _blur

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
    }

    fun getSelectedTab(): Int = _selectedTab.value

    fun setLiquidGlass(enabled: Boolean) {
        _liquidGlass.value = enabled
    }

    fun setBlur(enabled: Boolean) {
        _blur.value = enabled
    }
}
