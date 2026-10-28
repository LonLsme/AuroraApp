package com.aurora.app.ui.update;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.aurora.app.R;
import com.aurora.app.core.update.ApkDownloader;
import com.aurora.app.core.update.ReleaseNotes;
import com.aurora.app.core.update.UpdateChecker;

/**
 * 检查更新结果对话框。
 *
 * <p><b>一个对话框承担三种结果</b>，按 {@link UpdateChecker.Result#status} 切换形态：
 * <table>
 *   <tr><th>结果</th><th>标题</th><th>主体</th><th>按钮</th></tr>
 *   <tr><td>有新版本</td><td>发现新版本</td><td>版本对比 + 更新日志</td><td>稍后 / 立即更新</td></tr>
 *   <tr><td>已是最新</td><td>已是最新版本</td><td>当前版本 + 检查时间</td><td>知道了</td></tr>
 *   <tr><td>检查失败</td><td>检查失败</td><td>具体原因</td><td>知道了 / 手动查看</td></tr>
 * </table>
 *
 * <p>失败时<b>一定给一条能走出去的路</b>（「手动查看」打开 Releases 页）：
 * GitHub API 在国内不可达或限流都很常见，只报错不给出路，用户只会以为功能坏了。
 *
 * <p><b>「立即更新」去哪了</b>：点下去交给 {@link UpdateDownloadDialog} ——
 * 下载、SHA-256 校验、签名比对、拉起系统安装器都在那边，本类只管「告不告诉用户有新版本」。
 */
public final class UpdateDialog {

    private UpdateDialog() {
    }

    /**
     * 显示结果。
     *
     * @param context 宿主 Activity（用于弹窗与跳浏览器）
     * @param result  检查结果，不可为 null
     * @param onLater 用户点了「稍后」/「知道了」时回调，用于把摘要文案复位等收尾动作；可为 null
     */
    public static void show(Context context, UpdateChecker.Result result, Runnable onLater) {
        if (context == null || result == null) {
            return;
        }
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
        }

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_update, null, false);
        TextView tvTitle = view.findViewById(R.id.tvUpdateTitle);
        TextView tvVersions = view.findViewById(R.id.tvUpdateVersions);
        TextView tvMessage = view.findViewById(R.id.tvUpdateMessage);
        TextView tvBody = view.findViewById(R.id.tvUpdateBody);
        com.google.android.material.button.MaterialButton btnLater =
                view.findViewById(R.id.btnUpdateLater);
        com.google.android.material.button.MaterialButton btnNow =
                view.findViewById(R.id.btnUpdateNow);

        UpdateChecker.Release release = result.release;
        String local = result.localVersion;
        String openUrl = null;
        // 非空表示「这个版本有可直接下载的 APK」，点主按钮走应用内下载与安装
        String autoTag = null;

        switch (result.status) {
            case UpdateChecker.STATUS_NEW_VERSION:
                tvTitle.setText("发现新版本");
                tvVersions.setVisibility(View.VISIBLE);
                tvVersions.setText(local + "  →  " + (release != null ? release.tag : ""));
                if (release != null && ReleaseNotes.hasContent(release.body)) {
                    tvBody.setText(ReleaseNotes.plain(release.body));
                    tvBody.setVisibility(View.VISIBLE);
                } else {
                    tvMessage.setVisibility(View.VISIBLE);
                    tvMessage.setText("此版本没有提供更新说明。");
                }
                btnNow.setVisibility(View.VISIBLE);
                btnLater.setText("稍后");
                // 页面地址兜底：没有 APK 资产时只能让用户自己去 Release 页
                openUrl = release != null && !release.pageUrl.isEmpty()
                        ? release.pageUrl : UpdateChecker.releasesPageUrl();
                if (release != null && release.hasApk()) {
                    autoTag = release.tag;
                    // 上次已经下好并校验过 → 按钮直说「安装」，不必让用户再点一轮下载
                    btnNow.setText(ApkDownloader.readyApk(context, release.tag) != null
                            ? "安装更新" : "立即更新");
                } else {
                    btnNow.setText("前往下载");
                }
                break;

            case UpdateChecker.STATUS_UP_TO_DATE:
                tvTitle.setText("已是最新版本");
                tvVersions.setVisibility(View.VISIBLE);
                tvVersions.setText("当前版本 " + local);
                tvMessage.setVisibility(View.VISIBLE);
                tvMessage.setText("上次检查：" + UpdateChecker.formatLastCheck(context));
                btnLater.setText("知道了");
                break;

            default:
                tvTitle.setText("检查失败");
                tvVersions.setVisibility(View.VISIBLE);
                tvVersions.setText("当前版本 " + local);
                tvMessage.setVisibility(View.VISIBLE);
                tvMessage.setText(buildFailureText(result));
                // 失败也给出口：让用户自己去 Releases 页看
                btnNow.setVisibility(View.VISIBLE);
                btnNow.setText("手动查看");
                btnLater.setText("知道了");
                openUrl = UpdateChecker.releasesPageUrl();
                break;
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(true)
                .create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        final String url = openUrl;
        final UpdateChecker.Release target = autoTag == null ? null : release;
        btnNow.setOnClickListener(v -> {
            dialog.dismiss();
            if (target != null && target.hasApk()) {
                // 应用内：下载 → SHA-256 + 签名校验 → 系统安装器
                UpdateDownloadDialog.show(context, target);
            } else if (url != null) {
                openInBrowser(context, url);
            }
        });
        btnLater.setOnClickListener(v -> {
            dialog.dismiss();
            // 记住「稍后」的版本：同一个 tag 不再在启动时自动弹，避免每次开 App 都被烦
            if (result.hasUpdate() && release != null) {
                UpdateChecker.setDismissedTag(context, release.tag);
            }
        });
        // 收尾统一走 dismiss：点按钮、点外面、按返回键都会触发，不必在每个分支里重复调用
        dialog.setOnDismissListener(d -> {
            if (onLater != null) {
                onLater.run();
            }
        });
        dialog.show();
    }

    /** 失败原因 + 一句「你还能做什么」。技术细节留在括号里，主语永远是人话。 */
    private static String buildFailureText(UpdateChecker.Result result) {
        String reason = result.message == null ? "未知错误" : result.message;
        switch (result.status) {
            case UpdateChecker.STATUS_NETWORK:
                return reason + "\n\nGitHub 在部分网络环境下不可直连。可以稍后重试，"
                        + "或点「手动查看」用浏览器打开 Releases 页。";
            case UpdateChecker.STATUS_RATE_LIMITED:
                return reason + "\n\nGitHub 对未登录请求限制为每小时 60 次，"
                        + "等一小时后再试即可；也可以直接点「手动查看」。";
            case UpdateChecker.STATUS_NOT_FOUND:
                return reason + "\n\n如果是刚建的仓库，先发布一个 Release 才能被检测到。";
            case UpdateChecker.STATUS_BAD_CONFIG:
                return reason + "\n\n请在 gradle.properties 里设置 aurora.updateRepo=owner/repo。";
            default:
                return reason;
        }
    }

    private static String formatCheckTime(long millis) {
        if (millis <= 0L) {
            return "刚刚";
        }
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(millis));
    }

    private static void openInBrowser(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
            // 设备上没有可用浏览器时静默忽略：不该因为跳转失败再弹一个错误框
        }
    }
}
