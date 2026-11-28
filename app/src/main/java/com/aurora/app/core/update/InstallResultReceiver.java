package com.aurora.app.core.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/**
 * 接收 {@link PackageInstaller} 会话的安装结果。
 *
 * <p>会话安装是「提交后由系统自己弹框确认」，提交那一刻拿不到成败；真正的结果只有这条广播知道。
 * 没有它的话，用户点了安装却失败（签名不符、空间不足、包被篡改）时应用这边一片安静，
 * 只能靠用户自己回想系统弹了什么 —— 这也是「下载好了装不上」最难查的原因。
 *
 * <p>只在失败时提示，成功时静默：安装成功后进程通常会被系统杀掉重启，
 * 这时候弹一条「安装成功」既看不到，也会让新进程一启动就冒个 Toast。
 */
public class InstallResultReceiver extends BroadcastReceiver {

    private static final String TAG = "InstallResultReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !UpdateInstaller.ACTION_INSTALL_RESULT.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(UpdateInstaller.EXTRA_INSTALL_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(UpdateInstaller.EXTRA_INSTALL_MESSAGE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(TAG, "安装会话完成：STATUS_SUCCESS");
            return;
        }
        Log.w(TAG, "安装失败：status=" + status + " message=" + message);
        // 用户点了「取消」不算故障，不用弹提示吓人
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            return;
        }
        String reason = describe(status, message);
        Toast.makeText(context.getApplicationContext(), reason, Toast.LENGTH_LONG).show();
    }

    /**
     * 把状态码翻成能照着做的中文。
     *
     * <p>系统给的 {@code message} 常是英文且带包名，直接甩给用户等于没说；
     * 这里按状态码给结论，原始 message 仍写进日志备查。
     */
    private static String describe(int status, String message) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "安装被系统拦截：可能开启了「应用安装校验」或该版本不允许安装";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "安装失败：与已安装版本签名或版本号冲突，需要先卸载旧版本";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "安装失败：该安装包与当前系统版本不兼容";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "安装失败：安装包无效或已损坏";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "安装失败：存储空间不足";
            default:
                return "安装失败" + (message == null || message.isEmpty() ? "" : "：" + message);
        }
    }
}
