package com.aurora.app.ui.overlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 悬浮窗权限工具类：
 * 封装「系统悬浮窗权限」的检查与引导跳转。
 *
 * <p>Android 6.0（API 23）起，SYSTEM_ALERT_WINDOW 需要在系统设置中单独授权，
 * 应用内无法用运行时权限弹窗申请，只能跳转到系统悬浮窗设置页由用户手动开启。
 * 因此流程为：检查 → 未授权则提示并跳转设置页 → 用户返回后在 onResume 重试。
 */
public final class OverlayPermission {

    private OverlayPermission() {
    }

    /**
     * 当前应用是否已获得悬浮窗权限。
     */
    public static boolean canDrawOverlays(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /**
     * 跳转系统「显示在其他应用上层」设置页。
     * 用户授权后按返回键回到应用，由 Activity 的 onResume 继续流程。
     */
    public static void request(Context context) {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // 个别 ROM 可能不支持该 ACTION，回退到应用信息页
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.getPackageName()));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {
                Toast.makeText(context, "无法打开悬浮窗权限设置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 辅助：API 23+ 才能调用 Settings.canDrawOverlays，这里留一个版本判断的说明位。
     */
    public static boolean isOverlayPermissionApiAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /** 供 Activity 使用：无权限时弹提示。 */
    public static void toastNeedPermission(Activity activity) {
        Toast.makeText(activity, "请先开启「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
    }
}
