package com.aurora.app;

import android.app.Application;

import com.aurora.app.core.display.DisplayGuard;
import com.aurora.app.ui.theme.ThemeManager;

public class AuroraApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 动态取色与强调色改由 ThemeManager 控制（仅用户开启且 API31+ 时生效）
        ThemeManager.init(this);

        // 屏幕参数的断点兜底：上次有「改了但没点保留」的残留（应用被强杀、
        // 进程被系统回收等），进程一起来就还原，避免用户一直卡在错误的分辨率上。
        // 必须放独立守护线程：root 调用是阻塞 IO，放主线程会拖慢冷启动。
        // 正常情况下 isPending 为 false，这里只是一次内存级读取，开销可忽略。
        Thread thread = new Thread(() -> {
            try {
                if (DisplayGuard.isPending(getApplicationContext())) {
                    DisplayGuard.restoreIfPending(getApplicationContext());
                }
            } catch (Exception ignored) {
                // 兜底逻辑失败不能影响应用启动；开机广播还会再兜一次
            }
        }, "aurora-display-guard-restore");
        thread.setDaemon(true);
        thread.start();
    }
}
