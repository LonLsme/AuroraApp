package com.aurora.app.ui.update;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.aurora.app.R;
import com.aurora.app.core.update.ApkDownloader;
import com.aurora.app.core.update.UpdateChecker;
import com.aurora.app.core.update.UpdateInstaller;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.Locale;

/**
 * 下载进度对话框：下载 → 校验 → 交给系统安装器。
 *
 * <p><b>四态一框</b>（不是四个对话框）：下载中 / 校验通过 / 失败 / 已就绪（重进时）。
 * 换框会让界面闪一下，且用户会以为自己点错了。
 *
 * <p><b>关掉对话框不会中断下载</b>：{@link ApkDownloader} 的任务独立于界面存活，
 * 关框只是把回调摘掉（{@code attach(null)}），数据继续写。下次点「检查更新」时
 * {@link #show} 会先查 {@link ApkDownloader#readyApk}，直接进「已就绪」态 ——
 * 用户不会为了一次误触重下 27 MB。
 *
 * <p><b>安装前必须问「未知来源」权限</b>：Android 8.0 起没有这项授权时
 * {@code startActivity} 不会报错、也不会弹任何提示，只是静默失败 ——
 * 不主动检查就会变成「点了安装没反应」的幽灵 bug。
 */
public final class UpdateDownloadDialog {

    private UpdateDownloadDialog() {
    }

    /**
     * 打开展示（有已下好的包时直接进「已就绪」态）。
     *
     * @param context 宿主 Activity
     * @param release 目标版本；传 {@code null} 表示「只装已下好的那个包」——
     *                设置页看到「已下载待安装」时走这条路径，不必再查一次网络
     */
    public static void show(Context context, UpdateChecker.Release release) {
        if (context == null || !(context instanceof Activity)) {
            return;
        }
        Activity activity = (Activity) context;
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        // 既没有版本信息、也没有已下好的包 —— 弹出来只会是一个空框
        if (release == null && ApkDownloader.readyApk(activity, "") == null) {
            return;
        }

        View view = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_update_download, null, false);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(true)
                .create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        new Controller(activity, release, dialog, view).bind();
        dialog.show();
    }

    // ==================== 控制器 ====================

    /** 把「对话框 + 下载任务」绑在一起。一个对话框一个实例，随 dismiss 一起作废。 */
    private static final class Controller implements ApkDownloader.Listener {

        private final Activity activity;
        private final UpdateChecker.Release release;
        private final Dialog dialog;

        private final TextView tvTitle;
        private final TextView tvVersion;
        private final TextView tvStatus;
        private final TextView tvPercent;
        private final TextView tvVerify;
        private final ProgressBar pb;
        private final MaterialButton btnCancel;
        private final MaterialButton btnInstall;

        /** 已 dismiss 后不再更新界面（下载可能仍在跑）。 */
        private boolean dead;
        /** 校验通过后的本地文件，供「安装」按钮使用。 */
        private File readyFile;

        Controller(Activity activity, UpdateChecker.Release release,
                   Dialog dialog, View view) {
            this.activity = activity;
            this.release = release;
            this.dialog = dialog;
            this.tvTitle = view.findViewById(R.id.tvDlTitle);
            this.tvVersion = view.findViewById(R.id.tvDlVersion);
            this.tvStatus = view.findViewById(R.id.tvDlStatus);
            this.tvPercent = view.findViewById(R.id.tvDlPercent);
            this.tvVerify = view.findViewById(R.id.tvDlVerify);
            this.pb = view.findViewById(R.id.pbDl);
            this.btnCancel = view.findViewById(R.id.btnDlCancel);
            this.btnInstall = view.findViewById(R.id.btnDlInstall);
        }

        void bind() {
            tvVersion.setText(buildVersionLine());

            btnCancel.setOnClickListener(v -> {
                if (readyFile != null) {
                    // 已下好但用户不想现在装：留着重进时还能直接装
                    dialog.dismiss();
                } else {
                    ApkDownloader.cancel();
                    dialog.dismiss();
                }
            });
            btnInstall.setOnClickListener(v -> onInstallClick());
            dialog.setOnDismissListener(d -> {
                dead = true;
                // 只摘回调，不取消任务 —— 关框继续下，下次进来直接进「已就绪」态
                ApkDownloader.attach(null);
            });

            File cached = ApkDownloader.readyApk(activity, tag());
            if (cached != null) {
                stateReady(cached, ApkDownloader.readySha(activity));
                return;
            }
            stateDownloading();
            if (release == null) {
                // 已下好的包在两次查询之间被清理掉了（系统清缓存），只能让用户重新检查
                stateError("安装包已不存在（可能被系统清理），请返回重新检查更新");
                return;
            }
            if (!ApkDownloader.attach(this)) {
                ApkDownloader.start(activity, release, this);
            }
        }

        /** 目标 tag；没有网络版本信息时退回「任意已下好的包」。 */
        private String tag() {
            return release == null || release.tag == null ? "" : release.tag;
        }

        private String buildVersionLine() {
            String line = release != null && release.tag != null
                    ? release.tag : ApkDownloader.readyTag(activity);
            long size = release != null && release.apkSize > 0 ? release.apkSize : 0L;
            if (size > 0) {
                line = line + "  ·  " + formatBytes(size);
            }
            return line;
        }

        // ---- 状态 ----

        private void stateDownloading() {
            tvTitle.setText("正在下载更新");
            tvStatus.setText("准备中…");
            tvPercent.setText("");
            tvVerify.setVisibility(View.GONE);
            pb.setIndeterminate(true);
            pb.setProgress(0);
            btnInstall.setVisibility(View.GONE);
            btnCancel.setText("取消");
        }

        private void stateReady(File apk, String sha) {
            readyFile = apk;
            tvTitle.setText("更新已就绪");
            pb.setIndeterminate(false);
            pb.setProgress(100);
            tvPercent.setText("100%");
            tvStatus.setText(formatBytes(apk.length()) + " · 已下载完成");
            tvVerify.setVisibility(View.VISIBLE);
            tvVerify.setTextColor(activity.getColor(R.color.ios_semantic_positive));
            tvVerify.setText("SHA-256 校验通过：" + shortSha(sha)
                    + "\n包名与签名均与当前版本一致，可覆盖安装。");
            btnInstall.setVisibility(View.VISIBLE);
            btnInstall.setText(UpdateInstaller.canInstallPackages(activity)
                    ? "安装" : "去授权");
            // 失败态会把同一按钮改成「手动下载」，回到就绪态时要把语义换回来
            btnInstall.setOnClickListener(v -> onInstallClick());
            btnCancel.setText("稍后");
        }

        private void stateError(String message) {
            tvTitle.setText("更新失败");
            tvStatus.setText(message);
            tvPercent.setText("");
            tvVerify.setVisibility(View.GONE);
            pb.setIndeterminate(false);
            pb.setProgress(0);
            btnInstall.setVisibility(View.VISIBLE);
            btnInstall.setText("手动下载");
            // 失败态复用同一个按钮：此时它的语义变成「打开 Release 页」
            btnInstall.setOnClickListener(v -> {
                dialog.dismiss();
                String url = release != null && release.pageUrl != null
                        && !release.pageUrl.isEmpty()
                        ? release.pageUrl : UpdateChecker.releasesPageUrl();
                UpdateInstaller.openUrl(activity, url);
            });
            btnCancel.setText("关闭");
        }

        // ---- 安装 ----

        private void onInstallClick() {
            // 失败态的按钮已被改成「手动下载」，不走这里
            File apk = readyFile;
            if (apk == null || !apk.isFile()) {
                stateError("安装包已不存在（可能被系统清理），请重新下载");
                return;
            }
            if (!UpdateInstaller.canInstallPackages(activity)) {
                tvVerify.setVisibility(View.VISIBLE);
                tvVerify.setTextColor(activity.getColor(R.color.aurora_warning));
                tvVerify.setText("需要先允许本应用安装应用。已打开设置页，"
                        + "开启后返回这里再点一次「安装」。");
                if (!UpdateInstaller.openInstallPermissionSettings(activity)) {
                    tvVerify.setText("无法打开系统设置，请在「设置 → 应用 → 特殊权限"
                            + "→ 安装未知应用」里手动允许本应用。");
                }
                return;
            }
            if (UpdateInstaller.install(activity, apk)) {
                // 记录清掉：安装器已经拿到 Uri，本地文件装完由系统清理
                ApkDownloader.clearReady(activity);
                dialog.dismiss();
            } else {
                stateError("无法启动系统安装器。请到文件管理器打开 "
                        + apk.getName() + " 手动安装。");
            }
        }

        // ---- 下载回调（后台线程） ----

        @Override
        public void onProgress(long downloaded, long total, long bytesPerSecond) {
            runOnUi(() -> {
                if (dead) {
                    return;
                }
                if (total <= 0) {
                    pb.setIndeterminate(true);
                    tvPercent.setText("");
                    tvStatus.setText("已下载 " + formatBytes(downloaded));
                    return;
                }
                pb.setIndeterminate(false);
                int pct = (int) Math.min(100L, downloaded * 100L / total);
                pb.setProgress(pct);
                tvPercent.setText(pct + "%");
                StringBuilder sb = new StringBuilder();
                sb.append("已下载 ").append(formatBytes(downloaded))
                        .append(" / ").append(formatBytes(total));
                if (bytesPerSecond > 0) {
                    sb.append("  ·  ").append(formatBytes(bytesPerSecond)).append("/s");
                }
                tvStatus.setText(sb.toString());
            });
        }

        @Override
        public void onReady(File apk, String sha256) {
            runOnUi(() -> {
                if (dead) {
                    return;
                }
                stateReady(apk, sha256);
            });
        }

        @Override
        public void onError(int code, String message) {
            runOnUi(() -> {
                if (dead) {
                    return;
                }
                stateError(message == null || message.isEmpty()
                        ? "下载失败（错误码 " + code + "）" : message);
            });
        }

        @Override
        public void onCancelled() {
            runOnUi(() -> {
                if (dead) {
                    return;
                }
                dialog.dismiss();
            });
        }

        // ---- 小工具 ----

        private void runOnUi(Runnable r) {
            if (dead || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    r.run();
                }
            });
        }

        private static String shortSha(String sha) {
            if (sha == null || sha.isEmpty()) {
                return "—";
            }
            return sha.length() > 12 ? sha.substring(0, 12).toUpperCase(Locale.ROOT)
                    : sha.toUpperCase(Locale.ROOT);
        }

        private static String formatBytes(long bytes) {
            if (bytes <= 0) {
                return "0 B";
            }
            if (bytes < 1024) {
                return bytes + " B";
            }
            double kb = bytes / 1024.0;
            if (kb < 1024) {
                return String.format(Locale.ROOT, "%.0f KB", kb);
            }
            double mb = kb / 1024.0;
            if (mb < 1024) {
                return String.format(Locale.ROOT, "%.1f MB", mb);
            }
            return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
        }
    }
}
