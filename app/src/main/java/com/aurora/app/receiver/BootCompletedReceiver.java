package com.aurora.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.util.Log;

import com.aurora.app.core.display.DisplayGuard;
import com.aurora.app.ui.selinux.SelinuxManagerActivity;

/**
 * 开机广播接收器。
 *
 * <p>除了原有的 SELinux 模式恢复，还负责屏幕参数的<b>断点兜底</b>：
 * 分辨率/密度的 override 是持久化的（实测 Android 16 上 {@code wm size} 之后
 * 重启读到的仍是改过的值），所以「改了没确认就重启」这种情况必须由开机时主动还原，
 * 否则用户会带着一个自己没点过确认、甚至可能已经看不清楚的分辨率启动系统。
 *
 * <p>注意 {@code LOCKED_BOOT_COMPLETED}（解锁前）不做屏幕还原：
 * 此时凭证加密存储还没挂载，读 SharedPreferences 会直接抛异常。
 * 屏幕还原只认解锁后的 {@code BOOT_COMPLETED}。
 */
public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            SelinuxManagerActivity.applySelinuxModeForBoot(context);
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            restoreDisplayIfPending(context);
        }
    }

    /**
     * 未确认的屏幕改动 → 开机还原。
     *
     * <p>走 {@code goAsync()} + 后台线程：root 命令可能要几百毫秒，
     * 直接在 onReceive 的主线程里做会撞上广播超时（10 秒）并占住主线程。
     */
    private void restoreDisplayIfPending(Context context) {
        if (!isUserUnlocked(context)) {
            // 理论上 BOOT_COMPLETED 一定在解锁之后，这里只兜一层保险
            Log.w(TAG, "用户尚未解锁，跳过屏幕还原");
            return;
        }
        final Context appContext = context.getApplicationContext();
        try {
            if (!DisplayGuard.isPending(appContext)) {
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "读取待确认标记失败", e);
            return;
        }
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                boolean restored = DisplayGuard.restoreIfPending(appContext);
                Log.i(TAG, "开机屏幕还原: " + (restored ? "已执行" : "无需执行"));
            } catch (Exception e) {
                Log.w(TAG, "开机屏幕还原失败", e);
            } finally {
                pending.finish();
            }
        }, "aurora-boot-display-restore").start();
    }

    private static boolean isUserUnlocked(Context context) {
        try {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return um == null || um.isUserUnlocked();
        } catch (Exception e) {
            return true;
        }
    }
}
