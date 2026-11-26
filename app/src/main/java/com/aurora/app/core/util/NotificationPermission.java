package com.aurora.app.core.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Android 13+ 通知权限的最小封装。
 *
 * <p>本应用的所有前台服务（悬浮窗监视、固件解包）都靠通知向用户说明「后台在干什么」。
 * 没申请通知权限时，前台服务照样运行，但通知根本不显示 —— 用户既看不到状态，
 * 也没有一键停止的入口。这里只在真正需要时弹一次系统授权框，不阻塞任何业务流程：
 * 拒绝也继续跑，只是没有通知。
 */
public final class NotificationPermission {

    public static final int REQUEST_CODE = 4101;

    private NotificationPermission() {
    }

    public static boolean isGranted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 未授权时异步弹系统授权框；已授权 / 低版本 / 已拒绝过都不打扰用户。 */
    public static void requestIfNeeded(Activity activity) {
        if (activity == null || isGranted(activity)) {
            return;
        }
        try {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_CODE);
        } catch (Throwable ignored) {
            // 某些 ROM 在同一会话内重复请求会抛异常，忽略即可
        }
    }
}
