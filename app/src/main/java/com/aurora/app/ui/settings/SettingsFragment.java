package com.aurora.app.ui.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.aurora.app.BuildConfig;
import com.aurora.app.R;
import com.aurora.app.core.update.ApkDownloader;
import com.aurora.app.core.update.UpdateChecker;
import com.aurora.app.ui.about.AboutActivity;
import com.aurora.app.ui.theme.ThemeManager;
import com.aurora.app.ui.update.UpdateDialog;
import com.aurora.app.ui.update.UpdateDownloadDialog;

/**
 * 设置页（Miuix preference 范式：分组小标题 + 卡片行）。
 *
 * <p>本页刻意只留四件事：一个开关（自动检查更新）、一个动作（立刻检查更新 ——
 * 两者是两回事，开关只管启动时检不检）、两个入口（主题 / 关于）。
 * 内容稍长的都独立成页 ——
 * <ul>
 *   <li>{@link ThemeActivity}：切换主题必须重建 Activity，内嵌在 God Activity
 *       {@code MainActivity} 里每次切换都要重建整个主界面，会卡死；</li>
 *   <li>{@link AboutActivity}：版本信息 / 开源 / 鸣谢 / 作者，属于「一年看一次」的内容，
 *       摊在设置页只会白占高度，故收成一行入口。</li>
 * </ul>
 */
public class SettingsFragment extends Fragment {

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

    // ===== 通用 =====

    /** 防止连点：检查请求在后台跑，重复点会叠出一串对话框。 */
    private boolean checkingUpdate;

    private void bindGeneral(View root) {
        SwitchCompat swCheckUpdate = root.findViewById(R.id.swCheckUpdate);
        if (swCheckUpdate != null) {
            swCheckUpdate.setChecked(ThemeManager.isCheckUpdateEnabled(requireContext()));
            swCheckUpdate.setOnCheckedChangeListener((buttonView, isChecked) ->
                    ThemeManager.setCheckUpdateEnabled(requireContext(), isChecked));
        }

        View rowCheckUpdate = root.findViewById(R.id.rowCheckUpdate);
        TextView tvSummary = root.findViewById(R.id.tvUpdateSummary);
        refreshUpdateSummary(tvSummary);
        if (rowCheckUpdate != null) {
            rowCheckUpdate.setOnClickListener(v -> startManualUpdateCheck(rowCheckUpdate, tvSummary));
        }
    }

    /**
     * 摘要常驻显示「当前版本 + 上次检查时间」，让用户不点也知道自己是不是最新。
     *
     * <p>存在「已下载待安装」的包时改说这一句 —— 那是一个真实的待办状态，
     * 比版本号更有信息量；点进去的对话框也会直接进「已就绪」态，不必重下。
     */
    private void refreshUpdateSummary(TextView tv) {
        if (tv == null || !isAdded()) {
            return;
        }
        Context ctx = requireContext();
        if (ApkDownloader.readyApk(ctx, "") != null) {
            String tag = ApkDownloader.readyTag(ctx);
            tv.setText((tag.isEmpty() ? "新版本" : tag) + " 已下载，点此安装");
            return;
        }
        tv.setText("当前版本 " + UpdateChecker.localVersionName()
                + " · 上次检查 " + UpdateChecker.formatLastCheck(ctx));
    }

    private void startManualUpdateCheck(View row, TextView tvSummary) {
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
        if (tvSummary != null) {
            tvSummary.setText("正在检查…");
        }

        UpdateChecker.checkAsync(ctx, result -> {
            // 回调在后台线程；Fragment 可能已经 detach，回到主线程后必须重新判活
            Activity activity = getActivity();
            if (activity == null) {
                checkingUpdate = false;
                return;
            }
            activity.runOnUiThread(() -> {
                checkingUpdate = false;
                row.setEnabled(true);
                if (!isAdded()) {
                    return;
                }
                refreshUpdateSummary(tvSummary);
                UpdateDialog.show(activity, result, () -> refreshUpdateSummary(tvSummary));
            });
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从关于页或外部回来时刷新一次：上次检查时间可能已被启动检查更新
        if (getView() != null) {
            refreshUpdateSummary(getView().findViewById(R.id.tvUpdateSummary));
        }
    }

    // ===== 外观 =====

    private void bindAppearance(View root) {
        View rowTheme = root.findViewById(R.id.rowTheme);
        if (rowTheme != null) {
            rowTheme.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), ThemeActivity.class)));
        }
    }

    // ===== 关于 =====

    /**
     * 「关于」在本页只占一行，点进去才是 {@link AboutActivity}。
     *
     * <p>摘要里的版本号动态填：写死在布局里的话，改 {@code app/build.gradle.kts} 的
     * {@code versionName} 之后设置页会一直显示旧值，而这种不一致没有任何编译期提示。
     */
    private void bindAboutEntry(View root) {
        TextView summary = root.findViewById(R.id.tvAboutSummary);
        if (summary != null) {
            summary.setText("版本 " + BuildConfig.VERSION_NAME
                    + " · 开源协议 · 鸣谢");
        }
        View rowAbout = root.findViewById(R.id.rowAbout);
        if (rowAbout != null) {
            rowAbout.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), AboutActivity.class)));
        }
    }
}
