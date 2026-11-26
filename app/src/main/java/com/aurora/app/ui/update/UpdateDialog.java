package com.aurora.app.ui.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.aurora.app.R;
import com.aurora.app.core.update.ApkDownloader;
import com.aurora.app.core.update.ReleaseNotes;
import com.aurora.app.core.update.UpdateChecker;

/**
 * 检查更新对话框。
 *
 * <p><b>一个窗口承担四种形态</b>，按 {@link UpdateChecker.Result#status} 切换显隐：
 * <table>
 *   <tr><th>形态</th><th>标题</th><th>主体</th><th>按钮</th></tr>
 *   <tr><td>检查中</td><td>正在检查更新</td><td>旋转环</td><td>（无）</td></tr>
 *   <tr><td>有新版本</td><td>发现新版本</td><td>版本对比 + 更新日志</td><td>稍后 / 立即更新</td></tr>
 *   <tr><td>已是最新</td><td>已是最新版本</td><td>当前版本 + 上次检查</td><td>知道了</td></tr>
 *   <tr><td>检查失败</td><td>检查失败</td><td>具体原因</td><td>知道了 / 手动查看</td></tr>
 * </table>
 *
 * <p><b>为什么加载态不另开一个对话框</b>：先弹加载框、结果回来再 dismiss 一个新弹一个，
 * 两个窗口之间会闪一下（位置、阴影、遮罩都要重建一次）。这里用 {@link Host} 持有同一个
 * 窗口，结果回来直接原地换成结果态。
 *
 * <p>加载态可以取消 —— 取消即放弃这次结果（用户已经表示不想等了，之后再弹会更烦）。
 * 取消与「结果弹出后再关闭」是两条不同的收尾路径，分别走 {@code onCancelled} 与 {@code onClosed}。
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
     * 已弹出的对话框句柄，用于把加载态原地切成结果态。
     *
     * <p>只在主线程使用。持有 {@link AlertDialog} 与根 View，生命周期由调用方控制：
     * 页面销毁后不要再调 {@link #bindResult}，直接 {@link #dismiss()} 即可。
     */
    public static final class Host {

        private final AlertDialog dialog;
        private final View view;
        private Runnable onCancelled;
        private Runnable onClosed;
        /** true 表示已经填过结果 —— dismiss 时走 onClosed 而不是 onCancelled。 */
        private boolean resultBound;

        private Host(AlertDialog dialog, View view) {
            this.dialog = dialog;
            this.view = view;
        }

        public boolean isShowing() {
            return dialog.isShowing();
        }

        public void dismiss() {
            dialog.dismiss();
        }

        private void bindLoading() {
            resultBound = false;
            view.findViewById(R.id.ivUpdateIcon).setVisibility(View.GONE);
            view.findViewById(R.id.pbUpdateLoading).setVisibility(View.VISIBLE);
            TextView tvTitle = view.findViewById(R.id.tvUpdateTitle);
            tvTitle.setText("正在检查更新");
            view.findViewById(R.id.tvUpdateVersions).setVisibility(View.GONE);
            view.findViewById(R.id.tvUpdateMessage).setVisibility(View.GONE);
            view.findViewById(R.id.tvUpdateBody).setVisibility(View.GONE);
            view.findViewById(R.id.svUpdateBody).setVisibility(View.GONE);
            // 检查中没有可点的动作：按钮区整体收起，避免摆一个点了没反应的禁用按钮
            view.findViewById(R.id.llUpdateActions).setVisibility(View.GONE);
        }

        private void bindResult(UpdateChecker.Result result, Runnable onLater) {
            Context context = view.getContext();
            resultBound = true;
            onClosed = onLater;

            view.findViewById(R.id.pbUpdateLoading).setVisibility(View.GONE);
            view.findViewById(R.id.ivUpdateIcon).setVisibility(View.VISIBLE);
            view.findViewById(R.id.llUpdateActions).setVisibility(View.VISIBLE);

            TextView tvTitle = view.findViewById(R.id.tvUpdateTitle);
            TextView tvVersions = view.findViewById(R.id.tvUpdateVersions);
            TextView tvMessage = view.findViewById(R.id.tvUpdateMessage);
            TextView tvBody = view.findViewById(R.id.tvUpdateBody);
            View svBody = view.findViewById(R.id.svUpdateBody);
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
                    tvMessage.setVisibility(View.VISIBLE);
                    // 版本与检查时间合成一句：参考版式是「标题 + 一句副文案」，两行碎信息反而难读
                    tvMessage.setText("当前版本 " + local
                            + " · 上次检查 " + UpdateChecker.formatLastCheck(context));
                    btnLater.setText("知道了");
                    break;

                default:
                    tvTitle.setText("检查失败");
                    tvMessage.setVisibility(View.VISIBLE);
                    tvMessage.setText(buildFailureText(result));
                    // 失败也给出口：让用户自己去 Releases 页看
                    btnNow.setVisibility(View.VISIBLE);
                    btnNow.setText("手动查看");
                    btnLater.setText("知道了");
                    openUrl = UpdateChecker.releasesPageUrl();
                    break;
            }

            // 中间滚动区：状态说明与更新日志任一有内容才显示，都空时整块收起（不留 12dp 空当）。
            // 按钮在滚动区之外，正文再长也不会把它挤走。
            boolean hasBody = tvMessage.getVisibility() == View.VISIBLE
                    || tvBody.getVisibility() == View.VISIBLE;
            svBody.setVisibility(hasBody ? View.VISIBLE : View.GONE);

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
        }
    }

    /**
     * 直接显示检查结果（自动检查、已有结果时用这条）。
     *
     * @param context 宿主 Activity（用于弹窗与跳浏览器）
     * @param result  检查结果，不可为 null
     * @param onDone  窗口关闭时回调（点按钮、点外面、返回键都会触发），可为 null
     */
    public static void show(Context context, UpdateChecker.Result result, Runnable onDone) {
        if (context == null || result == null) {
            return;
        }
        Host host = build(context);
        if (host == null) {
            return;
        }
        host.bindResult(result, onDone);
        host.dialog.show();
    }

    /**
     * 先弹加载态，结果回来后再调 {@link #showResult} 原地切换。
     *
     * @param onCancelled 用户在结果出来之前关掉了窗口（点外面 / 返回键 / 点关闭）时回调，
     *                    调用方据此复位「检查中」状态；可为 null
     * @return 句柄；宿主 Activity 已销毁时返回 null
     */
    public static Host showLoading(Context context, Runnable onCancelled) {
        if (context == null) {
            return null;
        }
        Host host = build(context);
        if (host == null) {
            return null;
        }
        host.onCancelled = onCancelled;
        host.bindLoading();
        host.dialog.show();
        return host;
    }

    /**
     * 把结果填进一个已经在显示的窗口。
     *
     * <p>{@code host} 为空时新开一个；{@code host} 存在但已被关闭（用户取消）时<b>什么都不做</b> ——
     * 用户已经表示不想等了，再把结果弹回去等于无视他的操作。
     */
    public static void showResult(Host host, Context context, UpdateChecker.Result result,
                                  Runnable onDone) {
        if (host == null) {
            show(context, result, onDone);
            return;
        }
        if (host.isShowing()) {
            host.bindResult(result, onDone);
        }
    }

    private static Host build(Context context) {
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) {
                return null;
            }
        }
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_update, null, false);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(true)
                .create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        Host host = new Host(dialog, view);
        dialog.setOnDismissListener(d -> {
            if (host.resultBound) {
                if (host.onClosed != null) {
                    host.onClosed.run();
                }
            } else if (host.onCancelled != null) {
                host.onCancelled.run();
            }
        });
        dialog.setOnShowListener(d -> applyWidth(dialog, context));
        return host;
    }

    /**
     * 卡片宽度：占屏宽 88%，上限 420dp。
     *
     * <p>小屏留出两侧呼吸空间（不是贴边），平板上不会被拉成一张长条 ——
     * 居中排版的对话框一旦过宽，标题与副文案的行长会变得难读。
     */
    private static void applyWidth(AlertDialog dialog, Context context) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int max = Math.round(420 * dm.density);
        int width = Math.min(Math.round(dm.widthPixels * 0.88f), max);
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
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
