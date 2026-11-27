package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

/**
 * 悬浮窗统一样式工具。
 *
 * <p>全部 6 个监视窗口统一采用深色半透明胶囊底 + 白色文字 + 阈值变色圆环。
 * 样式集中在此处定义，各窗口只引用常量/工厂方法，后期统一改样式只动本类。
 */
public final class OverlayWindowStyle {

    private OverlayWindowStyle() {
    }

    // ═══════════════════════════════════════════════════════════════
    //  监视器统一样式
    //
    //  底色不透明度取 #99000000：比常见的 #70000000 更实，
    //  悬浮窗会同时覆盖浅色应用，只有压得住背景才能保证白字可读。
    // ═══════════════════════════════════════════════════════════════

    /** 监视器底色。 */
    public static final int MONITOR_BG = 0x99000000;
    /** 迷你形态底色：比标准态更浅一档。 */
    public static final int MONITOR_BG_MINI = 0x8C000000;
    /** 圆环轨道色（白 13%）。 */
    public static final int MONITOR_RING_TRACK = 0x22FFFFFF;
    /** 环内数值/主要文字。 */
    public static final int MONITOR_TEXT = 0xFFFFFFFF;
    /** 环下小字（白 73%）。 */
    public static final int MONITOR_TEXT_DIM = 0xBBFFFFFF;
    /** 分隔线（白 14%）。 */
    public static final int MONITOR_DIVIDER = 0x24FFFFFF;

    // ─── 阈值配色 ───
    /** 正常：Aurora 主绿。 */
    public static final int MONITOR_LEVEL_NORMAL = 0xFF00E676;
    /** 偏高（>65）。 */
    public static final int MONITOR_LEVEL_HIGH = 0xFFFFB300;
    /** 很高（>85）。 */
    public static final int MONITOR_LEVEL_VERY_HIGH = 0xFFFF7043;
    /** 满载/危险（电池高温或低电量）。 */
    public static final int MONITOR_LEVEL_FULL = 0xFFFF5252;

    /** 监视器圆角（dp）。 */
    public static final int MONITOR_CORNER_DP = 5;

    // ─── 帧率记录窗专属：毛玻璃（dark glass）配色 ───
    // 只作用于帧率悬浮窗，其余五个监视窗仍用上面的深色胶囊。
    // 比原底色更通透（0x99→0x8A），加一圈白色高光描边模拟玻璃边缘反光；
    // 白字仍清晰，也不会在浅色应用上“糊成一片”。
    /** 毛玻璃底：深灰 54% 透明。 */
    public static final int FPS_GLASS_BG = 0x8A1C1C20;
    /** 毛玻璃描边：白 40% 高光。 */
    public static final int FPS_GLASS_STROKE = 0x66FFFFFF;
    /** 记录中毛玻璃底：红 60% 透明。 */
    public static final int FPS_GLASS_RECORD_BG = 0x99C62828;
    /** 记录中毛玻璃描边：浅红白 50%。 */
    public static final int FPS_GLASS_RECORD_STROKE = 0x80FFCDD2;
    /** 毛玻璃圆角（dp）：比普通胶囊略大，玻璃卡片观感。 */
    public static final int FPS_GLASS_CORNER_DP = 6;

    /** dp 转 px 换算。 */
    public static int dpToPx(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /** 监视器窗口背景（深色半透明 + 5dp 圆角，无描边）。 */
    public static GradientDrawable createMonitorBackground(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(context, MONITOR_CORNER_DP));
        bg.setColor(MONITOR_BG);
        return bg;
    }

    /** 迷你形态背景（更浅一档，仅底部圆角，复刻 monitor_game_bg）。 */
    public static GradientDrawable createMonitorMiniBackground(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(MONITOR_BG_MINI);
        int r = dpToPx(context, 3);
        bg.setCornerRadii(new float[]{0f, 0f, 0f, 0f, r, r, r, r});
        return bg;
    }

    /** 监视器标题文字（白 73%，10sp，紧凑）。 */
    public static TextView createMonitorTitleView(Context context, String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextColor(MONITOR_TEXT_DIM);
        tv.setTextSize(10);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    /** 监视器正文文字（白色，11sp）。 */
    public static TextView createMonitorBodyView(Context context) {
        TextView tv = new TextView(context);
        tv.setTextColor(MONITOR_TEXT);
        tv.setTextSize(11);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    /** 监视器数值文字（等宽，白色，用于需要纵向对齐的读数行）。 */
    public static TextView createMonitorMonoView(Context context) {
        TextView tv = createMonitorBodyView(context);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        return tv;
    }

    /** 监视器分隔线（1px 白 14%，上下留白由调用方控制）。 */
    public static View createMonitorDivider(Context context) {
        View div = new View(context);
        div.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1));
        div.setBackgroundColor(MONITOR_DIVIDER);
        return div;
    }

    /**
     * 通用毛玻璃背景：与帧率记录窗同一套深灰半透明底 + 白色高光描边 + 圆角，
     * 供系统监视窗等需要 compact 玻璃质感的窗口覆写 createWindowBackground() 使用。
     * 纯色 + 描边，不叠加任何渐变。
     */
    public static GradientDrawable createGlassBackground(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(context, FPS_GLASS_CORNER_DP));
        bg.setColor(FPS_GLASS_BG);
        bg.setStroke(dpToPx(context, 1), FPS_GLASS_STROKE);
        return bg;
    }

    /**
     * 帧率记录窗毛玻璃背景（常态）：深灰半透明底 + 白色高光描边 + 6dp 圆角。
     * 仅由 {@code FpsOverlayWindow} 通过覆写 createWindowBackground() 使用。
     */
    public static GradientDrawable createFpsGlassBackground(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(context, FPS_GLASS_CORNER_DP));
        bg.setColor(FPS_GLASS_BG);
        bg.setStroke(dpToPx(context, 1), FPS_GLASS_STROKE);
        return bg;
    }

    /** 帧率记录窗毛玻璃背景（记录中）：红色半透明底 + 浅红高光描边。 */
    public static GradientDrawable createFpsGlassRecordingBackground(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(context, FPS_GLASS_CORNER_DP));
        bg.setColor(FPS_GLASS_RECORD_BG);
        bg.setStroke(dpToPx(context, 1), FPS_GLASS_RECORD_STROKE);
        return bg;
    }

    /**
     * 记录态窗口背景（透明红）：
     * 帧率记录进行中，悬浮窗底色切换为红色半透明，提示用户"正在记录"。
     */
    public static GradientDrawable createWindowBackgroundRecording(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(context, MONITOR_CORNER_DP));
        bg.setColor(0x80E53935);              // 红 50% 透明
        bg.setStroke(dpToPx(context, 1), 0x59E53935);
        return bg;
    }

    /** 时长选择窗口的选项按钮背景（白 20% 半透明圆角底，深色底上可读）。 */
    public static GradientDrawable createPickerButtonBackground(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(context, 8));
        bg.setColor(0x33FFFFFF);
        bg.setStroke(dpToPx(context, 1), 0x26FFFFFF);
        return bg;
    }

    /**
     * 按 0-100 的占用率取阈值色（复刻 {@code FloatMonitorChartView} 的判定顺序）：
     * {@code >85 → 深橙}，{@code >65 → 橙}，其余取正常色。
     *
     * @param percent 占用率，小于 0 视为未知 → 返回正常色
     */
    public static int levelColorOf(int percent) {
        if (percent > 85) {
            return MONITOR_LEVEL_VERY_HIGH;
        }
        if (percent > 65) {
            return MONITOR_LEVEL_HIGH;
        }
        return MONITOR_LEVEL_NORMAL;
    }

    /**
     * 电池环配色（复刻 {@code FloatMonitorBatteryView} 的判定顺序）：
     * {@code 温度>=48 或 电量<11 → 危险红}；{@code 温度>44 或 电量<16 → 深橙}；
     * {@code 温度>41 或 电量<31 → 橙}；其余正常色。
     *
     * @param tempC   电池温度（°C），小于 0 表示未知
     * @param percent 电量（0-100），小于 0 表示未知
     */
    public static int batteryColorOf(float tempC, int percent) {
        if (tempC >= 48f || (percent >= 0 && percent < 11)) {
            return MONITOR_LEVEL_FULL;
        }
        if (tempC > 44f || (percent >= 0 && percent < 16)) {
            return MONITOR_LEVEL_VERY_HIGH;
        }
        if (tempC > 41f || (percent >= 0 && percent < 31)) {
            return MONITOR_LEVEL_HIGH;
        }
        return MONITOR_LEVEL_NORMAL;
    }
}
