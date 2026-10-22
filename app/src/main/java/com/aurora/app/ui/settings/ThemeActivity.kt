package com.aurora.app.ui.settings

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.aurora.app.ui.theme.ThemeManager

/**
 * 主题设置页宿主（独立轻量 Activity）。
 *
 * <p>为什么独立成页：XML 主题属性（强调色 / AMOLED 覆盖层）变更必须重建 Activity 才能生效。
 * 若把主题控件内嵌在 {@code MainActivity}（God Activity，含 ViewPager2 + 4 个 Fragment + Root 检测），
 * 每次切换都要重建整个主界面，实测会卡死。本页是纯 Compose 界面，重建代价极小。
 *
 * <p>界面本身在 [ThemeScreen] 中实现（Compose），主题项切换时靠重组即时生效，
 * 只有「色彩模式」「字号」这类影响资源层的项才重建本页。
 */
class ThemeActivity : AppCompatActivity() {

    /** 字号缩放：让本页也跟随全局字号（createConfigurationContext，不触发 relaunch 循环）。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.wrapFontScale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThemeScreen(onBack = { finish() })
        }
    }
}
