package com.aurora.app.core.update;

import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.aurora.app.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/**
 * 调用系统安装界面。
 *
 * <p><b>为什么不能直接 {@code Intent.setDataAndType(fileUri)}</b>：
 * Android 7.0 起 {@code file://} 形式的 Uri 会触发 {@link android.os.FileUriExposedException}，
 * 必须用 {@link FileProvider} 换成 {@code content://}。清单里已经声明了
 * {@code ${applicationId}.fileprovider}，路径见 {@code res/xml/file_paths.xml}
 * （{@code external-files-path} 与 {@code cache-path} 都是 {@code path="."}，
 * 所以 {@link ApkDownloader#resolveDir} 的两种落点都能被分享）。
 *
 * <p><b>为什么还要 {@code setClipData}</b>：只加
 * {@code FLAG_GRANT_READ_URI_PERMISSION} 在部分 ROM（尤其基于 Android 5~7 的老 ROM
 * 与个别定制安装器）上不足以把读权限传给安装器，安装器打开 Uri 会拿到
 * {@code SecurityException}。ClipData 是官方推荐的兼容写法，多写一行即可。
 *
 * <p><b>「未知来源」权限</b>：Android 8.0 起按应用授权
 * （{@code REQUEST_INSTALL_PACKAGES} + 用户在设置里单独允许），
 * 未授权时 {@link #install} 会直接失败，<b>不会</b>弹任何系统提示 ——
 * 这是最容易让人误判成「安装功能坏了」的地方，所以调用方必须先
 * {@link #canInstallPackages} 判断并引导。
 *
 * <p><b>分层约束</b>：{@code core} 层，不 import 任何 {@code ui} 包。
 */
public final class UpdateInstaller {

    /** FileProvider 的 authority，与清单里的 {@code ${applicationId}.fileprovider} 一致。 */
    private static String authority(Context context) {
        return BuildConfig.APPLICATION_ID + ".fileprovider";
    }

    private UpdateInstaller() {
    }

    /**
     * 当前是否已被允许「安装未知应用」。
     *
     * <p>Android 8.0 以下没有这个开关，一律返回 true（安装时由系统弹一次性确认）。
     */
    public static boolean canInstallPackages(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            // 极少数 ROM 未实现该 API，按「可以」处理，让系统安装器自己去判断
            return true;
        }
    }

    /**
     * 打开「安装未知应用」的授权页。
     *
     * <p>优先直达本应用的开关页；国产 ROM 上这个 Action 常被裁掉，
     * 此时回落到「应用管理」列表，再不行就回落到本应用详情页 ——
     * 一条路都不通的话用户至少还能自己找到。
     *
     * @return 是否成功打开某个页面
     */
    public static boolean openInstallPermissionSettings(Context context) {
        Intent[] candidates;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            candidates = new Intent[]{
                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.getPackageName())),
                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
                    new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
                    appDetailsIntent(context)
            };
        } else {
            candidates = new Intent[]{appDetailsIntent(context)};
        }
        for (Intent intent : candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                return true;
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }
        return false;
    }

    private static Intent appDetailsIntent(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
    }

    private static final String TAG = "UpdateInstaller";

    /** 安装会话结果广播的 action，接收方见清单里的 {@code InstallResultReceiver}。 */
    public static final String ACTION_INSTALL_RESULT = "com.aurora.app.action.INSTALL_RESULT";
    /** 附加在结果广播里的安装状态码（{@link PackageInstaller#STATUS_SUCCESS} 等）。 */
    public static final String EXTRA_INSTALL_STATUS = "status";
    /** 附加在结果广播里的原始消息。 */
    public static final String EXTRA_INSTALL_MESSAGE = "message";

    /**
     * 把下载好的 APK 交给系统安装器。
     *
     * <p>优先走 {@link PackageInstaller} 会话，而不是 {@code ACTION_VIEW}：
     * 后者是「谁都能接」的隐式 Intent，在装了第三方应用市场 / 浏览器的机器上会被抢走
     * （实测某台 HyperOS 上默认处理器是 UC 浏览器的 UCApkInstaller，轮到它时既装不上也不报错），
     * 而系统安装器反而不是默认项。会话安装由系统自己弹确认框，不存在被劫持的问题。
     *
     * @return false 表示两条路都没能发起（Uri 生成失败 / 设备拒绝创建会话 / 没有安装器）
     */
    public static boolean install(Context context, File apk) {
        if (context == null || apk == null || !apk.isFile() || apk.length() <= 0) {
            return false;
        }
        if (installViaSession(context, apk)) {
            return true;
        }
        return installViaIntent(context, apk);
    }

    /**
     * 会话安装：把 APK 写进 PackageInstaller 的会话再提交。
     *
     * <p>结果通过 {@link #ACTION_INSTALL_RESULT} 广播回 {@code InstallResultReceiver}，
     * 失败信息能落到日志和提示上；提交成功不代表安装成功，用户还要在系统弹框里确认。
     */
    private static boolean installViaSession(Context context, File apk) {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId;
        try {
            sessionId = installer.createSession(params);
        } catch (Throwable t) {
            Log.w(TAG, "创建安装会话失败，回落到 ACTION_VIEW", t);
            return false;
        }
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (OutputStream out = session.openWrite("aurora-update.apk", 0, apk.length());
                 FileInputStream in = new FileInputStream(apk)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }
            session.commit(buildResultIntentSender(context));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "写入安装会话失败", t);
            try {
                installer.abandonSession(sessionId);
            } catch (Throwable ignored) {
                // 会话可能已被系统回收，忽略
            }
            return false;
        }
    }

    /** 安装结果回执的 PendingIntent；接收方是清单里注册的 {@code InstallResultReceiver}。 */
    private static android.content.IntentSender buildResultIntentSender(Context context) {
        Intent result = new Intent(context, InstallResultReceiver.class)
                .setAction(ACTION_INSTALL_RESULT)
                .setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, 0, result, flags).getIntentSender();
    }

    /**
     * 旧路径：隐式 {@code ACTION_VIEW} 拉起安装器。
     *
     * <p>只在会话安装不可用时兜底 —— 它可能被第三方安装器接管，且拿不到任何结果。
     */
    private static boolean installViaIntent(Context context, File apk) {
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(context, authority(context), apk);
        } catch (Throwable t) {
            // authority 与清单不一致、或文件落在 file_paths 未覆盖的目录时会抛
            Log.w(TAG, "生成 FileProvider Uri 失败", t);
            return false;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClipData(ClipData.newRawUri("", uri));
        try {
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "拉起系统安装器失败", t);
            return false;
        }
    }

    /** 用浏览器打开链接（安装器不可用时的兜底出口）。 */
    public static boolean openUrl(Context context, String url) {
        if (context == null || url == null || url.isEmpty()) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
