package com.aurora.app.ui.theme;

import com.aurora.app.R;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 全局主题引擎（参考 KernelSU Manager 的 ThemeController / ColorMode / ColorPaletteScreen，
 * 按 AuroraApp 的 Java + XML View 架构落地）。
 *
 * <p>职责：
 * <ul>
 *   <li>持久化主题偏好（色彩模式 / 强调色 / 调色板风格 / 色彩规范 / 字号 / 液态玻璃）；</li>
 *   <li>{@link #applyGlobal(Context)} 设置 AppCompatDelegate 夜间模式（全局生效）；</li>
 *   <li>{@link #applyToActivity(Activity)} 在 Activity 创建前叠加强调色覆盖层与 AMOLED 覆盖层；</li>
 *   <li>{@link #init(Application)} 注册生命周期回调，让所有 Activity 自动套用主题，无需逐个改动。</li>
 * </ul>
 *
 * <p><b>配色为何分两套</b>：KernelSU 是纯 Compose，颜色由 materialkolor 运行时现算。本项目的
 * 主 UI 是 XML View，主题属性只能来自静态资源，因此：
 * <ul>
 *   <li>XML 层：由 {@link AuroraPalette} 在构建前预生成 {@code ThemeOverlay.Aurora.Accent.*}
 *       （见 {@link AccentOverlays}），运行时按「强调色 × 风格」叠加；</li>
 *   <li>Compose 层（底部导航 / 主题页 / 预览卡）：直接调用 {@link AuroraPalette#generate} 现算。</li>
 * </ul>
 * 两者同源（同一个 {@link AuroraPalette}），因此 XML 与 Compose 的配色始终一致。
 */
public final class ThemeManager {

    /** 偏好存储名（遵循项目「各功能独立 PREFS_NAME」的约定）。 */
    public static final String PREFS_NAME = "aurora_settings";

    // ===== 色彩模式（对齐 KernelSU 的 ColorMode 语义） =====

    /** 跟随系统。 */
    public static final int MODE_SYSTEM = 0;
    /** 强制浅色。 */
    public static final int MODE_LIGHT = 1;
    /** 强制深色。 */
    public static final int MODE_DARK = 2;
    /** 强制深色 + 纯黑（AMOLED）：背景与卡片全部 #000000。 */
    public static final int MODE_DARK_AMOLED = 3;

    /** 色彩模式数量。 */
    public static final int MODE_COUNT = 4;

    /** 强调色数量：0 = 默认（品牌绿），1..15 = KernelSU 预设色板。 */
    public static final int ACCENT_COUNT = AuroraPalette.KEY_COUNT;

    /** 强调色种子色表（转发自 {@link AuroraPalette}，保持单一数据源）。 */
    public static final int[] ACCENT_COLORS = AuroraPalette.SEED_COLORS;

    /** 调色板风格数量。 */
    public static final int PALETTE_STYLE_COUNT = AuroraPalette.STYLE_COUNT;

    /** 色彩规范数量。 */
    public static final int COLOR_SPEC_COUNT = AuroraPalette.SPEC_COUNT;

    /** 字号可选范围（与 KernelSU 的 pageScale 0.8~1.1 思路一致，这里作用于全局字号）。 */
    public static final float FONT_SCALE_MIN = 0.85f;
    public static final float FONT_SCALE_MAX = 1.30f;

    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_ACCENT_INDEX = "accent_index";
    private static final String KEY_PALETTE_STYLE = "palette_style";
    private static final String KEY_COLOR_SPEC = "color_spec";
    private static final String KEY_FONT_SCALE = "font_scale";
    private static final String KEY_LIQUID_GLASS = "liquid_glass";
    private static final String KEY_BLUR = "bottom_bar_blur";
    private static final String KEY_SWIPE_TABS = "swipe_tabs";
    private static final String KEY_CHECK_UPDATE = "check_update";
    /** 主题版本号：任何主题项变更 +1，宿主页据此判断「返回时需要重建一次」。 */
    private static final String KEY_REVISION = "theme_revision";

    private ThemeManager() {
    }

    /**
     * 偏好实例缓存。
     *
     * <p>{@code getSharedPreferences()} 每次都要走一次系统侧的缓存查找与锁，
     * 而 Compose 侧每次重组都会读 3~8 个键（主题页滚动时尤其密集），
     * 这里缓存成静态引用后取键就是一次内存查表。
     * 用 ApplicationContext 获取，避免长期持有 Activity 引用。
     */
    private static volatile SharedPreferences sPrefs;

    public static SharedPreferences getPrefs(Context context) {
        SharedPreferences cached = sPrefs;
        if (cached != null) {
            return cached;
        }
        synchronized (ThemeManager.class) {
            if (sPrefs == null) {
                Context app = context.getApplicationContext();
                Context target = app != null ? app : context;
                sPrefs = target.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            }
            return sPrefs;
        }
    }

    /** 当前主题版本号。 */
    public static int getRevision(Context context) {
        return getPrefs(context).getInt(KEY_REVISION, 0);
    }

    /** 主题项变更后递增版本号（由各 setter 内部调用）。 */
    private static void bumpRevision(Context context) {
        getPrefs(context).edit().putInt(KEY_REVISION, getRevision(context) + 1).apply();
    }

    // ===== 色彩模式 =====

    public static int getThemeMode(Context context) {
        return getPrefs(context).getInt(KEY_THEME_MODE, MODE_SYSTEM);
    }

    /** @return 是否发生了实际变化（未变化则调用方无需重建界面）。 */
    public static boolean setThemeMode(Context context, int mode) {
        int clamped = Math.max(MODE_SYSTEM, Math.min(MODE_DARK_AMOLED, mode));
        if (clamped == getThemeMode(context)) {
            return false;
        }
        getPrefs(context).edit().putInt(KEY_THEME_MODE, clamped).apply();
        bumpRevision(context);
        return true;
    }

    /** 当前是否 AMOLED 纯黑模式。 */
    public static boolean isAmoled(Context context) {
        return getThemeMode(context) == MODE_DARK_AMOLED;
    }

    /** 当前实际是否深色（用于 Compose 侧配色判定）。 */
    public static boolean isDark(Context context, boolean systemInDark) {
        int mode = getThemeMode(context);
        if (mode == MODE_LIGHT) {
            return false;
        }
        if (mode == MODE_DARK || mode == MODE_DARK_AMOLED) {
            return true;
        }
        return systemInDark;
    }

    // ===== 强调色 =====

    /** @return 0 = 默认品牌绿；1..15 = 预设色索引。 */
    public static int getAccentIndex(Context context) {
        return getPrefs(context).getInt(KEY_ACCENT_INDEX, 0);
    }

    /** @return 是否发生了实际变化（未变化则调用方无需重建界面）。 */
    public static boolean setAccentIndex(Context context, int index) {
        int clamped = Math.max(0, Math.min(ACCENT_COUNT - 1, index));
        if (clamped == getAccentIndex(context)) {
            return false;
        }
        getPrefs(context).edit().putInt(KEY_ACCENT_INDEX, clamped).apply();
        bumpRevision(context);
        return true;
    }

    // ===== 调色板风格（对应 materialkolor PaletteStyle） =====

    public static int getPaletteStyle(Context context) {
        return getPrefs(context).getInt(KEY_PALETTE_STYLE, AuroraPalette.STYLE_TONAL_SPOT);
    }

    /** @return 是否发生了实际变化（未变化则调用方无需重建界面）。 */
    public static boolean setPaletteStyle(Context context, int styleIndex) {
        int clamped = Math.max(0, Math.min(PALETTE_STYLE_COUNT - 1, styleIndex));
        if (clamped == getPaletteStyle(context)) {
            return false;
        }
        getPrefs(context).edit().putInt(KEY_PALETTE_STYLE, clamped).apply();
        bumpRevision(context);
        return true;
    }

    // ===== 色彩规范（2021 / 2025） =====

    public static int getColorSpec(Context context) {
        return getPrefs(context).getInt(KEY_COLOR_SPEC, AuroraPalette.SPEC_2025);
    }

    /** @return 是否发生了实际变化（未变化则调用方无需重建界面）。 */
    public static boolean setColorSpec(Context context, int specIndex) {
        int clamped = Math.max(0, Math.min(COLOR_SPEC_COUNT - 1, specIndex));
        if (clamped == getColorSpec(context)) {
            return false;
        }
        getPrefs(context).edit().putInt(KEY_COLOR_SPEC, clamped).apply();
        bumpRevision(context);
        return true;
    }

    // ===== 字号 =====

    public static float getFontScale(Context context) {
        return getPrefs(context).getFloat(KEY_FONT_SCALE, 1.0f);
    }

    /** @return 是否发生了实际变化（未变化则调用方无需重建界面）。 */
    public static boolean setFontScale(Context context, float scale) {
        float clamped = Math.max(FONT_SCALE_MIN, Math.min(FONT_SCALE_MAX, scale));
        if (Math.abs(clamped - getFontScale(context)) < 0.001f) {
            return false;
        }
        getPrefs(context).edit().putFloat(KEY_FONT_SCALE, clamped).apply();
        bumpRevision(context);
        return true;
    }

    // ===== 底栏视觉效果 =====

    /** 是否启用液态玻璃（折射 + 色彩增强）。默认开启（沿用既有观感）。 */
    public static boolean isLiquidGlassEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_LIQUID_GLASS, true);
    }

    /**
     * @return 是否发生了实际变化。
     *
     * <p>注意：这里<b>刻意不递增 theme_revision</b>。液态玻璃只作用于 Compose 底栏，
     * 由 {@code BottomBarState.liquidGlass} 状态流即时生效；若递增版本号，
     * 宿主 MainActivity 会在 onResume 时无谓地重建一次（God Activity，重建有明显卡顿）。
     */
    public static boolean setLiquidGlassEnabled(Context context, boolean on) {
        if (on == isLiquidGlassEnabled(context)) {
            return false;
        }
        getPrefs(context).edit().putBoolean(KEY_LIQUID_GLASS, on).apply();
        return true;
    }

    /** 是否启用底栏背景模糊（仅模糊，不含折射）。 */
    public static boolean isBlurEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_BLUR, true);
    }

    /** 与 {@link #setLiquidGlassEnabled} 同理：只走状态流，不递增主题版本号。 */
    public static boolean setBlurEnabled(Context context, boolean on) {
        if (on == isBlurEnabled(context)) {
            return false;
        }
        getPrefs(context).edit().putBoolean(KEY_BLUR, on).apply();
        return true;
    }

    // ===== 交互 =====

    /** 是否允许左右滑动切换底部标签页。默认开启（原行为）。 */
    public static boolean isSwipeTabsEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_SWIPE_TABS, true);
    }

    /**
     * 与视觉开关同理：MainActivity 在 onResume 读取并作用到 ViewPager2，
     * 无需重建 Activity，因此不递增主题版本号。
     */
    public static boolean setSwipeTabsEnabled(Context context, boolean on) {
        if (on == isSwipeTabsEnabled(context)) {
            return false;
        }
        getPrefs(context).edit().putBoolean(KEY_SWIPE_TABS, on).apply();
        return true;
    }

    // ===== 通用 =====

    public static boolean isCheckUpdateEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_CHECK_UPDATE, true);
    }

    public static void setCheckUpdateEnabled(Context context, boolean on) {
        getPrefs(context).edit().putBoolean(KEY_CHECK_UPDATE, on).apply();
    }

    // ===== 配色查询（供 Compose 侧使用） =====

    /**
     * 取当前实际生效的一套配色。
     *
     * @param systemInDark 系统是否处于深色（Compose 侧用 {@code isSystemInDarkTheme()} 传入）
     */
    public static AuroraPalette.Scheme currentScheme(Context context, boolean systemInDark) {
        boolean dark = isDark(context, systemInDark);
        return AuroraPalette.generate(getAccentIndex(context), getPaletteStyle(context),
                getColorSpec(context), dark);
    }

    /** 取当前生效的强调色（ARGB）。 */
    public static int currentAccentColor(Context context, boolean systemInDark) {
        return currentScheme(context, systemInDark).primary;
    }

    // ===== 应用 =====

    /** 全局：设置 AppCompatDelegate 夜间模式。在 {@link Application#onCreate()} 调用一次。 */
    public static void applyGlobal(Context appContext) {
        int night;
        switch (getThemeMode(appContext)) {
            case MODE_LIGHT:
                night = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case MODE_DARK:
            case MODE_DARK_AMOLED:
                night = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                night = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(night);
    }

    /**
     * 每个 Activity 在 {@code onCreate} 之前调用：叠加强调色 / AMOLED 覆盖层。
     * 通过 {@link Application#registerActivityLifecycleCallbacks()} 的
     * {@code onActivityPreCreated} 注入。
     */
    public static void applyToActivity(Activity activity) {
        activity.setTheme(R.style.Theme_Aurora);
        int overlay = AccentOverlays.of(getAccentIndex(activity), getPaletteStyle(activity));
        if (overlay != 0) {
            activity.getTheme().applyStyle(overlay, true);
        }
        if (isAmoled(activity)) {
            activity.getTheme().applyStyle(R.style.ThemeOverlay_Aurora_Amoled, true);
        }
        // 注：字号缩放不在这里处理。applyOverrideConfiguration 在 recreate() 后于部分 ROM
        // 会触发「配置变更→再次 relaunch」死循环导致卡死，改由 Activity.attachBaseContext
        // + createConfigurationContext 实现（见 wrapFontScale / MainActivity）。
    }

    /**
     * 字号缩放的安全实现：返回携带 fontScale 的派生 Context，供
     * {@code Activity.attachBaseContext} 调用。相比 {@code applyOverrideConfiguration}，
     * 它不会触发框架层的 relaunch 循环，因此切换字号不会卡死。
     *
     * @param base 原 Context（来自 {@code super.attachBaseContext} 的参数）
     * @return 已应用字号缩放的 Context；未启用缩放时原样返回
     */
    public static Context wrapFontScale(Context base) {
        float scale = getFontScale(base);
        if (scale <= 0 || Math.abs(scale - 1.0f) < 0.001f) {
            return base;
        }
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.fontScale = scale;
        return base.createConfigurationContext(cfg);
    }

    /** 应用初始化：设置夜间模式并注册生命周期回调自动套用主题。 */
    public static void init(Application application) {
        applyGlobal(application);
        application.registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
                        applyToActivity(activity);
                    }

                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    }

                    @Override
                    public void onActivityStarted(Activity activity) {
                    }

                    @Override
                    public void onActivityResumed(Activity activity) {
                    }

                    @Override
                    public void onActivityPaused(Activity activity) {
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                    }

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                    }
                });
    }
}
