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
 * 全局主题引擎。
 *
 * <p><b>职责范围收窄说明</b>：早期版本曾提供「强调色 / 调色板风格 / 色彩规范」三类自定义项，
 * 现在配色由开发者固定（{@link AuroraPalette}），App 内只保留<b>色彩模式</b>这一个入口
 * （跟随系统 / 浅色 / 深色）。因此本类不再有强调色相关的键、读写方法与覆盖层索引表。
 *
 * <p>职责：
 * <ul>
 *   <li>持久化主题偏好（色彩模式 / 字号 / 液态玻璃 / 底栏模糊 / 滑动切页）；</li>
 *   <li>{@link #applyGlobal(Context)} 设置 AppCompatDelegate 夜间模式（全局生效）；</li>
 *   <li>{@link #applyToActivity(Activity)} 在 Activity 创建前叠加固定的品牌配色覆盖层；</li>
 *   <li>{@link #init(Application)} 注册生命周期回调，让所有 Activity 自动套用主题，无需逐个改动。</li>
 * </ul>
 *
 * <p><b>配色为何分两套</b>：本项目的 UI 主体是 XML View，主题属性只能来自静态资源，
 * 因此强调色由 {@code res/values/aurora_palette.xml}（深色在 {@code values-night}）固化；
 * Compose 层（底部导航 / 主题页）则直接调用 {@link AuroraPalette#generate(boolean)} 现算。
 * 两者算法同源（同一个 {@link AuroraPalette} + 同一个种子色），配色始终一致。
 */
public final class ThemeManager {

    /** 偏好存储名（遵循项目「各功能独立 PREFS_NAME」的约定）。 */
    public static final String PREFS_NAME = "aurora_settings";

    // ===== 色彩模式 =====

    /** 跟随系统。 */
    public static final int MODE_SYSTEM = 0;
    /** 强制浅色。 */
    public static final int MODE_LIGHT = 1;
    /** 强制深色。 */
    public static final int MODE_DARK = 2;

    /** 字号可选范围（与 KernelSU 的 pageScale 0.8~1.1 思路一致，这里作用于全局字号）。 */
    public static final float FONT_SCALE_MIN = 0.85f;
    public static final float FONT_SCALE_MAX = 1.30f;

    // ===== 底栏效果：互斥三档 =====

    /*
     * 旧版本是两个独立布尔（液态玻璃 / 背景模糊），4 种组合里「玻璃关 + 模糊开」与
     * 「玻璃开 + 模糊开」在观感上几乎无法区分 —— 折射与提饱和都依赖背景里的明暗与色彩，
     * 而底栏下方多为纯色留白，于是用户会觉得液态玻璃开关失效。
     * 现在收敛成三档：磨砂只有模糊、没有边沿高光；玻璃 = 折射 + 提饱和 + 满高光 + 更透的底。
     */

    /** 不透明：不模糊、不折射，底栏退化为实心胶囊。 */
    public static final int BAR_EFFECT_OPAQUE = 0;
    /** 磨砂：只做背景模糊，无边沿高光。 */
    public static final int BAR_EFFECT_FROSTED = 1;
    /** 液态玻璃：折射 + 提饱和 + 模糊，边沿高光拉满。 */
    public static final int BAR_EFFECT_GLASS = 2;
    /** 默认档位（沿用既有观感）。 */
    public static final int BAR_EFFECT_DEFAULT = BAR_EFFECT_GLASS;

    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_FONT_SCALE = "font_scale";
    private static final String KEY_BAR_EFFECT = "bottom_bar_effect";
    /** 旧版两个布尔键：不再参与读取，只在写入时同步，供装回旧版本时保持观感一致。 */
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
     * 而 Compose 侧每次重组都会读几个键，缓存成静态引用后取键就是一次内存查表。
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

    /**
     * 当前色彩模式。
     *
     * <p>旧版本存在第 4 种模式「深色（纯黑）」（存值 3），该模式已随配色自定义一起移除。
     * 读到越界值时归一到深色，避免老用户升级后落到「跟随系统」而被系统浅色模式突然刷白。
     */
    public static int getThemeMode(Context context) {
        int mode = getPrefs(context).getInt(KEY_THEME_MODE, MODE_SYSTEM);
        if (mode < MODE_SYSTEM) {
            return MODE_SYSTEM;
        }
        return Math.min(mode, MODE_DARK);
    }

    /** @return 是否发生了实际变化（未变化则调用方无需重建界面）。 */
    public static boolean setThemeMode(Context context, int mode) {
        int clamped = Math.max(MODE_SYSTEM, Math.min(MODE_DARK, mode));
        if (clamped == getThemeMode(context)) {
            return false;
        }
        getPrefs(context).edit().putInt(KEY_THEME_MODE, clamped).apply();
        bumpRevision(context);
        return true;
    }

    /** 当前实际是否深色（用于 Compose 侧配色判定）。 */
    public static boolean isDark(Context context, boolean systemInDark) {
        int mode = getThemeMode(context);
        if (mode == MODE_LIGHT) {
            return false;
        }
        if (mode == MODE_DARK) {
            return true;
        }
        return systemInDark;
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

    /**
     * 当前底栏效果档位，取值 {@link #BAR_EFFECT_OPAQUE} / {@link #BAR_EFFECT_FROSTED} /
     * {@link #BAR_EFFECT_GLASS}。
     *
     * <p>老版本升级上来时偏好里没有新键：读到旧的两个布尔后按
     * {@link #migrateLegacyBarEffect} 折算成档位并落盘一次，之后只认新键。
     * 越界值（理论上不会出现）归一到默认档，避免升级后底栏变成不可预期的样子。
     */
    public static int getBarEffect(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs.contains(KEY_BAR_EFFECT)) {
            return normalizeBarEffect(prefs.getInt(KEY_BAR_EFFECT, BAR_EFFECT_DEFAULT));
        }
        int migrated = migrateLegacyBarEffect(prefs);
        prefs.edit().putInt(KEY_BAR_EFFECT, migrated).apply();
        return migrated;
    }

    /** 越界值归一到默认档。包内可见：单测直接覆盖边界（见 {@code ThemeManagerBarEffectTest}）。 */
    static int normalizeBarEffect(int effect) {
        return (effect >= BAR_EFFECT_OPAQUE && effect <= BAR_EFFECT_GLASS)
                ? effect : BAR_EFFECT_DEFAULT;
    }

    /**
     * 旧版「液态玻璃 / 背景模糊」两个布尔 → 三档。
     *
     * <p>玻璃开启时必然带模糊（玻璃本身就含模糊），与模糊开关的取值无关；
     * 只有玻璃关闭时，模糊开关才决定是磨砂还是不透明。
     *
     * <p>包内可见供单测直调 —— 这段映射决定了老用户升级后底栏长什么样，值得锁死。
     */
    static int migrateLegacyBarEffect(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_LIQUID_GLASS, true)) {
            return BAR_EFFECT_GLASS;
        }
        return prefs.getBoolean(KEY_BLUR, true) ? BAR_EFFECT_FROSTED : BAR_EFFECT_OPAQUE;
    }

    /**
     * @return 是否发生了实际变化。
     *
     * <p>注意：这里<b>刻意不递增 theme_revision</b>。底栏效果只作用于 Compose 底栏，
     * 由 {@code BottomBarState.barEffect} 状态流即时生效；若递增版本号，
     * 宿主 MainActivity 会在 onResume 时无谓地重建一次（God Activity，重建有明显卡顿）。
     *
     * <p>旧的两个布尔键一并写回：它们不再被读取，只为「装回旧版本 APK」时观感不至于跳变。
     */
    public static boolean setBarEffect(Context context, int effect) {
        int normalized = normalizeBarEffect(effect);
        if (normalized == getBarEffect(context)) {
            return false;
        }
        getPrefs(context).edit()
                .putInt(KEY_BAR_EFFECT, normalized)
                .putBoolean(KEY_LIQUID_GLASS, normalized == BAR_EFFECT_GLASS)
                .putBoolean(KEY_BLUR, normalized != BAR_EFFECT_OPAQUE)
                .apply();
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

    // ===== 应用 =====

    /** 全局：设置 AppCompatDelegate 夜间模式。在 {@link Application#onCreate()} 调用一次。 */
    public static void applyGlobal(Context appContext) {
        int night;
        switch (getThemeMode(appContext)) {
            case MODE_LIGHT:
                night = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case MODE_DARK:
                night = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                night = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(night);
    }

    /**
     * 每个 Activity 在 {@code onCreate} 之前调用：叠加固定的品牌配色覆盖层。
     * 通过 {@link Application#registerActivityLifecycleCallbacks()} 的
     * {@code onActivityPreCreated} 注入。
     *
     * <p>覆盖层只有一套（{@code ThemeOverlay.Aurora.Palette}），深浅色由资源限定符区分，
     * 因此这里不再需要按偏好挑选 style。
     */
    public static void applyToActivity(Activity activity) {
        activity.setTheme(R.style.Theme_Aurora);
        activity.getTheme().applyStyle(R.style.ThemeOverlay_Aurora_Palette, true);
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
