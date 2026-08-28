package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

/**
 * 悬浮窗统一样式工具：
 * 6 个监视窗口共用的深色玻璃胶囊外观（深色半透明底 + 细描边 + Aurora 绿高亮），
 * 与 App 内浅色主题解耦——悬浮窗通常悬浮在游戏/全屏应用之上，深色底更清晰且符合
 * Aurora 设计规范「默认深色玻璃」的方向。
 *
 * <p>集中在此处定义样式，避免每个窗口各自写一遍颜色/尺寸，后期统一改样式只动本类。
 */
public final class OverlayWindowStyle {

    private OverlayWindowStyle() {
    }

    // ─── 颜色常量（Aurora 色板） ───
    /** 窗口底色：接近黑的深色半透明玻璃。 */
    public static final int COLOR_BG = 0xE60B0B0F;
    /** 描边：白色 15% 透明度。 */
    public static final int COLOR_STROKE = 0x26FFFFFF;
    /** 主文字：白。 */
    public static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;
    /** 次级文字：白 60%。 */
    public static final int COLOR_TEXT_SECONDARY = 0x99FFFFFF;
    /** 高亮数值：Aurora 主绿 #00E676。 */
    public static final int COLOR_ACCENT = 0xFF00E676;

    // ─── 浅色可穿透样式（负载监视器折叠/展开窗口使用） ───
    /** 主文字：深灰（浅色半透明底上的可读色）。 */
    public static final int COLOR_TEXT_DARK = 0xE61F2937;
    /** 次级文字：深灰 55%。 */
    public static final int COLOR_TEXT_MUTED_DARK = 0x8C1F2937;
    /** 高亮数值：深一点的 Aurora 绿（浅底可读）。 */
    public static final int COLOR_ACCENT_DARK = 0xFF00C853;

    // ─── 尺寸常量 ───
    /** 窗口圆角半径（dp）。 */
    public static final int CORNER_RADIUS_DP = 14;
    /** 窗口内边距（dp）。 */
    public static final int PADDING_DP = 12;

    /** dp 转 px 换算。 */
    public static int dpToPx(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /**
     * 构建悬浮窗圆角背景 drawable（深色底 + 细描边）。
     */
    public static GradientDrawable createWindowBackground(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(context, CORNER_RADIUS_DP));
        bg.setColor(COLOR_BG);
        bg.setStroke(dpToPx(context, 1), COLOR_STROKE);
        return bg;
    }

    /** 标题文字（小号灰字，如「负载」）。 */
    public static TextView createTitleView(Context context, String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextColor(COLOR_TEXT_SECONDARY);
        tv.setTextSize(11);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    /** 高亮数值文字（Aurora 绿，如 CPU 百分比）。 */
    public static TextView createValueView(Context context) {
        TextView tv = new TextView(context);
        tv.setTextColor(COLOR_ACCENT);
        tv.setTextSize(20);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    /** 普通正文文字（白，如进程列表行）。 */
    public static TextView createBodyView(Context context) {
        TextView tv = new TextView(context);
        tv.setTextColor(COLOR_TEXT_PRIMARY);
        tv.setTextSize(12);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    /**
     * 可穿透淡灰背景（浅色系）：
     * 白色 45% 半透明圆角底 + 深灰细描边，下层内容透出呈淡灰调。
     */
    public static GradientDrawable createWindowBackgroundLight(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(context, CORNER_RADIUS_DP));
        bg.setColor(0x73F2F2F7);
        bg.setStroke(dpToPx(context, 1), 0x33000000);
        return bg;
    }

    /** 浅色系标题文字（深灰小字）。 */
    public static TextView createDarkTitleView(Context context, String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextColor(COLOR_TEXT_MUTED_DARK);
        tv.setTextSize(11);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    /** 浅色系正文文字（深灰）。 */
    public static TextView createDarkBodyView(Context context) {
        TextView tv = new TextView(context);
        tv.setTextColor(COLOR_TEXT_DARK);
        tv.setTextSize(12);
        tv.setIncludeFontPadding(false);
        return tv;
    }
}
