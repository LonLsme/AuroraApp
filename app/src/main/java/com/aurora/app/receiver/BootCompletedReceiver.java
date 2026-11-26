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
 * <p>注意 {@code LOCKED_BOOT_COMPLETED}（解锁前）此时凭证加密存储还没挂载，读
 * SharedPreferences 会直接抛异常，所以 SELinux 恢复与屏幕还原都只在用户解锁后执行；
 * 解锁前那条广播直接忽略。整个处理过程放在 {@code goAsync()} 的后台线程里，
 * 避免把 {@code su} 的等待时间堆到广播的 10 秒上限上。
 */
public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        boolean bootCompleted = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean lockedBoot = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        if (!bootCompleted && !lockedBoot) {
            return;
        }

        // 广播的 onReceive 在主线程，超时上限 10 秒。这里要跑 su（最长 60 秒），
        // 绝不能同步执行：以前 SELinux 恢复就是直接调用，root 机器开机时会撞 ANR。
        final PendingResult pending = goAsync();
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                boolean unlocked = isUserUnlocked(appContext);
                if (!unlocked) {
                    // 解锁前设备加密存储还没挂载：读 SharedPreferences 会抛
                    // IllegalStateException（以前正是在这里让开机广播直接崩掉）。
                    Log.i(TAG, "用户尚未解锁，跳过 SELinux / 屏幕还原");
                    return;
                }
                try {
                    SelinuxManagerActivity.applySelinuxModeForBoot(appContext);
                } catch (Throwable t) {
                    Log.w(TAG, "开机应用 SELinux 模式失败", t);
                }
                if (bootCompleted) {
                    try {
                        boolean restored = DisplayGuard.restoreIfPending(appContext);
                        Log.i(TAG, "开机屏幕还原: " + (restored ? "已执行" : "无需执行"));
                    } catch (Throwable t) {
                        Log.w(TAG, "开机屏幕还原失败", t);
                    }
                }
            } finally {
                pending.finish();
            }
        }, "aurora-boot-restore").start();
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
