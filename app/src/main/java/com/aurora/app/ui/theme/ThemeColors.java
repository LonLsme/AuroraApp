package com.aurora.app.ui.theme;

import android.content.Context;

import androidx.annotation.ColorInt;

import com.aurora.app.R;
import com.google.android.material.color.MaterialColors;

/**
 * 语义色取值的唯一入口。
 *
 * <p>布局里一律写 {@code ?attr/auroraTextPrimary} 这类属性引用，代码里一律走这里 ——
 * 两边都不出现具体色值，才能做到「改一处、全应用生效」。
 *
 * <p>在此之前代码里散着 {@code ContextCompat.getColor(ctx, R.color.ios_text_primary)}、
 * {@code R.color.explore_slate_500}、{@code R.color.fluent_on_surface_variant} 三种写法，
 * 同一个「次要文字色」在不同页面是三个不同的值（#6C6C70 / #64748B / #5A5B5F），
 * 深色模式各走各的 values-night，硬编码的那几处甚至完全不跟随深浅色。
 *
 * <p>取不到时的兜底：这些属性在 {@code Base.Theme.Aurora} 里都有定义，
 * 取不到说明主题配错（例如某个 Activity 没用 Aurora 主题），此时返回透明比
 * 硬编码一个颜色更好排查 —— 屏幕上直接看不出字，问题立刻暴露。
 */
public final class ThemeColors {

    private ThemeColors() {
    }

    /** 主文字色：标题、正文、数值。 */
    @ColorInt
    public static int textPrimary(Context context) {
        return resolve(context, R.attr.auroraTextPrimary);
    }

    /** 次要文字色：说明、副标题、单位。 */
    @ColorInt
    public static int textSecondary(Context context) {
        return resolve(context, R.attr.auroraTextSecondary);
    }

    /** 第三级文字色：占位提示、极弱注释、被禁用的值。 */
    @ColorInt
    public static int textTertiary(Context context) {
        return resolve(context, R.attr.auroraTextTertiary);
    }

    /** 分隔线与描边（AMOLED 下卡片要靠它和大背景区分）。 */
    @ColorInt
    public static int separator(Context context) {
        return resolve(context, R.attr.auroraSeparator);
    }

    @ColorInt
    private static int resolve(Context context, int attr) {
        return MaterialColors.getColor(context, attr, 0);
    }
}
