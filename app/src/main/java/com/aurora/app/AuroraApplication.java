package com.aurora.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aurora.app.core.display.DisplayGuard;
import com.aurora.app.core.display.RefreshRate;
import com.aurora.app.ui.theme.ThemeManager;

public class AuroraApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 动态取色与强调色改由 ThemeManager 控制（仅用户开启且 API31+ 时生效）
        ThemeManager.init(this);

        // 每个 Activity 都向系统申请最高刷新率。
        //
        // 高刷屏默认不一定给第三方应用高刷（只对白名单/自家桌面放开，或用 LTPO 按内容
        // 动态降频），不声明偏好的话整个应用会一直按 60Hz 走 —— 用户感受到的「滑动不跟手」
        // 有一大半是刷新率造成的，和真正的掉帧是两件事。
        //
        // 放在 Application 里统一注册而不是逐个 Activity 加：项目有三十多个页面，
        // 逐个加必然漏；而且这不是业务逻辑，是窗口属性，集中一处更好维护。
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
                // 在 created 时机设置：此时窗口还没 attach，偏好会随窗口创建一起生效，
                // 不会造成「先按 60Hz 显示几帧再跳到高刷」的视觉跳变
                RefreshRate.applyMax(activity);
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                    @NonNull Bundle state) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });

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
