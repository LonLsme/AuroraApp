package com.aurora.app.ui.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.aurora.app.BuildConfig;
import com.aurora.app.R;
import com.aurora.app.core.update.ApkDownloader;
import com.aurora.app.core.update.UpdateChecker;
import com.aurora.app.ui.about.AboutActivity;
import com.aurora.app.ui.main.BottomBarState;
import com.aurora.app.ui.main.MainActivity;
import com.aurora.app.ui.theme.ThemeManager;
import com.aurora.app.ui.update.UpdateDialog;
import com.aurora.app.ui.update.UpdateDownloadDialog;
import com.aurora.app.ui.widget.AuroraChoiceMenu;

/**
 * 设置页。
 *
 * <p>版式遵循 Android 原生设置页：整行可点、左侧线条图标、右侧当前值或轻量箭头，
 * 行间只有一条从标题处缩进的细线（布局约定见 {@code fragment_settings.xml} 顶部注释）。
 *
 * <p>三类交互各司其职：
 * <ul>
 *   <li><b>开关行</b>（自动检查更新 / 滑动切页）——
 *       只有真正二态的功能才用开关，且点整行即切换，不必精确点中开关本体；</li>
 *   <li><b>可选行</b>（主题色 / 界面字号 / 底栏效果）—— 点整行弹一个紧贴该行的悬浮菜单
 *       （{@link AuroraChoiceMenu}，不是对话框）：卡片式圆角、无分隔线、选中项右侧打勾；</li>
 *   <li><b>跳转行</b>（检查更新 / 关于）—— 行尾是轻量右箭头。</li>
 * </ul>
 *
 * <p>主题相关的项原先在独立的 {@code ThemeActivity}（Compose 页）里，现已全部收敛到本页：
 * 配色由开发者固定，用户能调的只有色彩模式、字号与底栏效果，没有单独开一页的必要。
 * 色彩模式与字号会改到资源层，必须重建一次 {@link MainActivity} 才能生效（它是宿主）。
 */
public class SettingsFragment extends Fragment {

    /** 色彩模式的展示文案，下标与 {@link ThemeManager#MODE_SYSTEM} 等常量一一对应。 */
    private static final String[] MODE_LABELS = {"跟随系统", "浅色", "深色"};

    /** 字号预设：与 ThemeManager.FONT_SCALE_MIN/MAX 范围内的常用档位对应。 */
    private static final float[] FONT_SCALES = {0.85f, 1.00f, 1.15f, 1.30f};
    private static final String[] FONT_LABELS = {"小 85%", "标准 100%", "大 115%", "特大 130%"};

    /**
     * 底栏效果档位文案，下标与 {@link ThemeManager#BAR_EFFECT_OPAQUE} 等常量一一对应。
     *
     * <p>三档互斥：磨砂与液态玻璃的观感差异落在「有没有边沿高光」与容器透明度上，
     * 这两点在纯色背景下也看得出来 —— 旧版两个独立开关里「玻璃关 + 模糊开」与
     * 「玻璃开 + 模糊开」几乎无法区分，才收敛成这一行。
     */
    private static final String[] BAR_EFFECT_LABELS = {"不透明", "磨砂", "液态玻璃"};

    public SettingsFragment() {
        super(R.layout.fragment_settings);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindGeneral(view);
        bindAppearance(view);
        bindAboutEntry(view);

    }

    @Override
    public void onResume() {
        super.onResume();
        // 从关于页或外部回来时刷新一次：上次检查时间可能已被启动检查更新
        if (getView() != null) {
            refreshUpdateValue(getView().findViewById(R.id.tvUpdateValue));
        }
    }

    // ===== 通用 =====

    /** 防止连点：检查请求在后台跑，重复点会叠出一串对话框。 */
    private boolean checkingUpdate;

    private void bindGeneral(View root) {
        Context ctx = requireContext();

        SwitchCompat swCheckUpdate = root.findViewById(R.id.swCheckUpdate);
        if (swCheckUpdate != null) {
            swCheckUpdate.setChecked(ThemeManager.isCheckUpdateEnabled(ctx));
            swCheckUpdate.setOnCheckedChangeListener((buttonView, isChecked) ->
                    ThemeManager.setCheckUpdateEnabled(ctx, isChecked));
        }
        View rowCheckUpdateAuto = root.findViewById(R.id.rowCheckUpdateAuto);
        if (rowCheckUpdateAuto != null && swCheckUpdate != null) {
            rowCheckUpdateAuto.setOnClickListener(v -> swCheckUpdate.toggle());
        }

        View rowCheckUpdate = root.findViewById(R.id.rowCheckUpdate);
        TextView tvValue = root.findViewById(R.id.tvUpdateValue);
        refreshUpdateValue(tvValue);
        if (rowCheckUpdate != null) {
            rowCheckUpdate.setOnClickListener(v -> startManualUpdateCheck(rowCheckUpdate, tvValue));
        }
    }

    /**
     * 行尾常驻显示当前版本，让人不点也知道自己是不是最新。
     *
     * <p>存在「已下载待安装」的包时改说「版本号 + 待安装」—— 那是一个真实的待办状态，
     * 比单纯版本号更有信息量；点进去的对话框会直接进「已就绪」态，不必重下。
     * 行尾空间有限，这里只说结论，「上次检查时间」交给对话框展示。
     */
    private void refreshUpdateValue(TextView tv) {
        if (tv == null || !isAdded()) {
            return;
        }
        Context ctx = requireContext();
        if (ApkDownloader.readyApk(ctx, "") != null) {
            String tag = ApkDownloader.readyTag(ctx);
            tv.setText(tag.isEmpty() ? "待安装" : tag + " 待安装");
            return;
        }
        tv.setText(UpdateChecker.localVersionName());
    }

    private void startManualUpdateCheck(View row, TextView tvValue) {
        if (checkingUpdate || !isAdded()) {
            return;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        Activity host = getActivity();
        if (host == null) {
            return;
        }
        // 已经有下好并校验通过的包：直接进「已就绪」，不必再查一次网络
        if (ApkDownloader.readyApk(ctx, "") != null) {
            UpdateDownloadDialog.show(host, null);
            return;
        }
        checkingUpdate = true;
        row.setEnabled(false);
        if (tvValue != null) {
            tvValue.setText("检查中…");
        }

        // 先弹加载态：结果回来后在同一个窗口里原地切成结果态，不会两个窗口闪一下。
        // 用户主动关掉加载框 = 放弃这次结果，此时要把闸门与行尾一起复位，
        // 否则行尾会一直停在「检查中…」，而且再也点不动。
        UpdateDialog.Host progress = UpdateDialog.showLoading(host, () -> {
            checkingUpdate = false;
            row.setEnabled(true);
            refreshUpdateValue(tvValue);
        });

        UpdateChecker.checkAsync(ctx, result -> {
            // 回调在后台线程；Fragment 可能已经 detach，回到主线程后必须重新判活
            Activity activity = getActivity();
            if (activity == null) {
                checkingUpdate = false;
                if (progress != null) {
                    progress.dismiss();
                }
                return;
            }
            activity.runOnUiThread(() -> {
                // 用户在结果回来之前关掉了加载框：这次结果作废，闸门与行尾已由 onCancelled 复位。
                // 这里必须早退，否则会把「已经重新发起的那次检查」的闸门再改一遍。
                if (progress != null && !progress.isShowing()) {
                    return;
                }
                checkingUpdate = false;
                row.setEnabled(true);
                if (!isAdded()) {
                    if (progress != null) {
                        progress.dismiss();
                    }
                    return;
                }
                refreshUpdateValue(tvValue);
                UpdateDialog.showResult(progress, activity, result, () -> refreshUpdateValue(tvValue));
            });
        });
    }

    // ===== 外观 =====

    private void bindAppearance(View root) {
        Context ctx = requireContext();

        // ---- 可选项 1：主题色 ----
        View rowThemeMode = root.findViewById(R.id.rowThemeMode);
        TextView tvThemeMode = root.findViewById(R.id.tvThemeModeValue);
        updateModeValue(tvThemeMode);
        if (rowThemeMode != null) {
            rowThemeMode.setOnClickListener(v -> showModeMenu(rowThemeMode, tvThemeMode));
        }

        // ---- 可选项 2：界面字号 ----
        View rowFontScale = root.findViewById(R.id.rowFontScale);
        TextView tvFontScale = root.findViewById(R.id.tvFontScaleValue);
        updateFontValue(tvFontScale);
        if (rowFontScale != null) {
            rowFontScale.setOnClickListener(v -> showFontMenu(rowFontScale, tvFontScale));
        }

        // ---- 可选项 3：底栏效果（不透明 / 磨砂 / 液态玻璃） ----
        View rowBarEffect = root.findViewById(R.id.rowBarEffect);
        TextView tvBarEffect = root.findViewById(R.id.tvBarEffectValue);
        updateBarEffectValue(tvBarEffect);
        if (rowBarEffect != null) {
            rowBarEffect.setOnClickListener(v -> showBarEffectMenu(rowBarEffect, tvBarEffect));
        }

        // ---- 开关：滑动切页 ----
        SwitchCompat swSwipe = root.findViewById(R.id.swSwipeTabs);
        if (swSwipe != null) {
            swSwipe.setChecked(ThemeManager.isSwipeTabsEnabled(ctx));
            swSwipe.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (ThemeManager.setSwipeTabsEnabled(ctx, isChecked)) {
                    // 立刻作用到 ViewPager2，否则要等下次回到本 Activity 才生效
                    Activity host = getActivity();
                    if (host instanceof MainActivity) {
                        ((MainActivity) host).applySwipeTabsEnabled(isChecked);
                    }
                }
            });
            bindRowToggle(root, R.id.rowSwipeTabs, swSwipe);
        }
    }

    /** 点整行即切换行尾开关（与原生设置页一致，不必精确点中开关本体）。 */
    private void bindRowToggle(View root, int rowId, SwitchCompat sw) {
        View row = root.findViewById(rowId);
        if (row != null) {
            row.setOnClickListener(v -> sw.toggle());
        }
    }

    // ----- 可选行的悬浮菜单 -----

    /**
     * 弹出项目统一的下拉选择菜单（定义在 {@link AuroraChoiceMenu}）。
     *
     * <p>之所以抽成公共件：CPU 控制的调度器下拉也要这个形态，
     * 两处各写一份的话，圆角、描边、宽度规则会慢慢漂成两个样子。
     */
    private void showChoiceMenu(View anchor, String[] labels, int selectedIndex,
                                AuroraChoiceMenu.OnChoiceListener listener) {
        if (!isAdded()) {
            return;
        }
        AuroraChoiceMenu.show(anchor, labels, selectedIndex, listener);
    }

    // ----- 主题色 -----

    private void updateModeValue(TextView tv) {
        if (tv == null || !isAdded()) {
            return;
        }
        int mode = ThemeManager.getThemeMode(requireContext());
        int index = (mode >= 0 && mode < MODE_LABELS.length) ? mode : 0;
        tv.setText(MODE_LABELS[index]);
    }

    private void showModeMenu(View anchor, TextView valueView) {
        showChoiceMenu(anchor, MODE_LABELS, ThemeManager.getThemeMode(requireContext()),
                index -> applyThemeMode(index, valueView));
    }

    /**
     * 应用色彩模式。
     *
     * <p>{@code setDefaultNightMode} 只在「生效的夜间模式」发生变化时才自动重建 Activity；
     * 若前后夜间模式相同（例如「跟随系统」与「深色」，而系统当前就是深色），
     * 必须手动重建一次，否则资源不会按新模式重新解析。
     */
    private void applyThemeMode(int mode, TextView valueView) {
        if (!isAdded()) {
            return;
        }
        Context ctx = requireContext();
        Activity host = getActivity();
        if (host == null) {
            return;
        }
        boolean darkBefore = ThemeManager.isDark(ctx, isSystemDark());
        if (!ThemeManager.setThemeMode(ctx, mode)) {
            updateModeValue(valueView);
            return;
        }
        boolean darkAfter;
        if (mode == ThemeManager.MODE_LIGHT) {
            darkAfter = false;
        } else if (mode == ThemeManager.MODE_DARK) {
            darkAfter = true;
        } else {
            darkAfter = isSystemDark();
        }
        ThemeManager.applyGlobal(ctx);
        updateModeValue(valueView);
        if (darkBefore == darkAfter) {
            // 前后夜间模式相同（如「跟随系统」与「深色」而系统当前就是深色）：
            // setDefaultNightMode 不会自行重建，必须手动来一次。
            recreateWithFade(host);
        } else {
            // 夜间模式真的变了：AppCompat 在 applyGlobal 内部已经把本 Activity 重建掉了，
            // 这里紧接着补上过渡动画，避免新旧主题之间硬切。
            host.overridePendingTransition(R.anim.theme_fade_in, R.anim.theme_fade_out);
        }
    }

    private boolean isSystemDark() {
        Configuration cfg = getResources().getConfiguration();
        return (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    // ----- 界面字号 -----

    private void updateFontValue(TextView tv) {
        if (tv == null || !isAdded()) {
            return;
        }
        tv.setText(Math.round(ThemeManager.getFontScale(requireContext()) * 100) + "%");
    }

    private void showFontMenu(View anchor, TextView valueView) {
        showChoiceMenu(anchor, FONT_LABELS,
                nearestFontIndex(ThemeManager.getFontScale(requireContext())),
                index -> applyFontScale(FONT_SCALES[index], valueView));
    }

    private void applyFontScale(float scale, TextView valueView) {
        if (!isAdded()) {
            return;
        }
        Context ctx = requireContext();
        Activity host = getActivity();
        if (host == null) {
            return;
        }
        boolean changed = ThemeManager.setFontScale(ctx, scale);
        updateFontValue(valueView);
        if (changed) {
            // 字号作用于资源层（attachBaseContext 里的 createConfigurationContext），
            // 必须重建宿主才能看到效果；本页是宿主里的一页，重建后会回到同一 tab。
            recreateWithFade(host);
        }
    }

    // ----- 底栏效果 -----

    private void updateBarEffectValue(TextView tv) {
        if (tv == null || !isAdded()) {
            return;
        }
        int effect = ThemeManager.getBarEffect(requireContext());
        tv.setText(BAR_EFFECT_LABELS[effect]);
    }

    private void showBarEffectMenu(View anchor, TextView valueView) {
        showChoiceMenu(anchor, BAR_EFFECT_LABELS,
                ThemeManager.getBarEffect(requireContext()),
                index -> applyBarEffect(index, valueView));
    }

    /**
     * 底栏效果只作用于 Compose 底栏，走状态流即时生效，**不重建宿主**。
     *
     * <p>这与色彩模式 / 字号不同：那两项改的是资源层，只能靠重建生效；
     * 而重建 MainActivity（1794 行的 God Activity）有明显卡顿，能免则免。
     */
    private void applyBarEffect(int effect, TextView valueView) {
        if (!isAdded()) {
            return;
        }
        if (ThemeManager.setBarEffect(requireContext(), effect)) {
            BottomBarState.INSTANCE.setBarEffect(effect);
        }
        updateBarEffectValue(valueView);
    }

    /**
     * 重建宿主并套上主题切换的过渡动画。
     *
     * <p>色彩模式 / 字号改的是资源层，只能靠重建生效；但系统默认的重建是硬切一帧。
     * 重建后立刻补一条「旧画面快速淡出 + 新画面淡入微放大」（{@code R.anim.theme_fade_*}），
     * 观感上就是新主题浮上来。动画时长（160 / 220ms）刻意做得很短，
     * 避免在设置页停留时产生"卡一下"的错觉。
     */
    private void recreateWithFade(Activity host) {
        if (host == null || host.isFinishing()) {
            return;
        }
        host.recreate();
        host.overridePendingTransition(R.anim.theme_fade_in, R.anim.theme_fade_out);
    }

    /** 已存的字号可能是滑杆时代的连续值，取最近的一档做勾选。 */
    private int nearestFontIndex(float scale) {
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < FONT_SCALES.length; i++) {
            float diff = Math.abs(FONT_SCALES[i] - scale);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    // ===== 关于 =====

    /**
     * 「关于」在本页只占一行，点进去才是 {@link AboutActivity}。
     *
     * <p>行尾只显示版本号：版本信息 / 开源协议 / 鸣谢在关于页里都有，
     * 设置页再铺一遍只会让整页变挤。
     */
    private void bindAboutEntry(View root) {
        TextView tvAboutValue = root.findViewById(R.id.tvAboutValue);
        if (tvAboutValue != null) {
            tvAboutValue.setText(BuildConfig.VERSION_NAME);
        }
        View rowAbout = root.findViewById(R.id.rowAbout);
        if (rowAbout != null) {
            rowAbout.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), AboutActivity.class)));
        }
    }
}
