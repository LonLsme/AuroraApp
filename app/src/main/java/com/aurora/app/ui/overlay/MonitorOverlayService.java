package com.aurora.app.ui.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.aurora.app.R;

/**
 * 监视悬浮窗前台服务：
 * 持有 {@link OverlayWindowManager}，让悬浮窗在 App 退到后台时依然存活。
 *
 * <p>职责划分：
 * <ul>
 *   <li>收到 {@link #ACTION_START} / {@link #ACTION_STOP} / {@link #ACTION_STOP_ALL}
 *       时转发给窗口管理器执行对应启停；</li>
 *   <li>只要有窗口在运行就保持前台服务 + 低打扰通知；
 *       全部窗口关闭后自动 stopSelf 释放；</li>
 *   <li>UI 层（MainActivity）只通过本类的静态方法发起命令，不直接接触窗口细节。</li>
 * </ul>
 */
public class MonitorOverlayService extends Service {

    // ─── 命令 Action ───
    /** 启动一个监视窗口（携带 EXTRA_TYPE）。 */
    public static final String ACTION_START = "com.aurora.app.overlay.ACTION_START";
    /** 停止一个监视窗口（携带 EXTRA_TYPE）。 */
    public static final String ACTION_STOP = "com.aurora.app.overlay.ACTION_STOP";
    /** 停止全部监视窗口。 */
    public static final String ACTION_STOP_ALL = "com.aurora.app.overlay.ACTION_STOP_ALL";
    /** 命令参数：MonitorType.name()。 */
    public static final String EXTRA_TYPE = "overlay_type";

    private static final String TAG = "MonitorOverlayService";
    private static final String CHANNEL_ID = "aurora_overlay";
    private static final int NOTIFICATION_ID = 1001;

    // ─── 供 UI 层调用的静态入口 ───

    /** 启动一个监视窗口（服务未运行会自动拉起）。 */
    public static void startOverlay(Context context, MonitorType type) {
        Intent intent = command(context, ACTION_START, type);
        ContextCompat.startForegroundService(context, intent);
    }

    /** 停止一个监视窗口。 */
    public static void stopOverlay(Context context, MonitorType type) {
        Intent intent = command(context, ACTION_STOP, type);
        ContextCompat.startForegroundService(context, intent);
    }

    /** 停止全部监视窗口。 */
    public static void stopAllOverlays(Context context) {
        Intent intent = new Intent(context, MonitorOverlayService.class);
        intent.setAction(ACTION_STOP_ALL);
        ContextCompat.startForegroundService(context, intent);
    }

    private static Intent command(Context context, String action, MonitorType type) {
        Intent intent = new Intent(context, MonitorOverlayService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_TYPE, type.name());
        return intent;
    }

    // ─── Service 生命周期 ───

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        OverlayWindowManager manager = OverlayWindowManager.getInstance();
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            MonitorType type = parseType(intent.getStringExtra(EXTRA_TYPE));
            switch (action) {
                case ACTION_START:
                    if (type != null) {
                        manager.start(this, type);
                    }
                    break;
                case ACTION_STOP:
                    if (type != null) {
                        manager.stop(type);
                    }
                    break;
                case ACTION_STOP_ALL:
                    manager.stopAll();
                    break;
                default:
                    break;
            }
        }
        int runningCount = manager.runningCount();
        // 还有窗口在运行 → 保持前台服务；全部关闭 → 停止服务。
        //
        // 注意两个系统契约，缺一个都会导致进程被杀：
        // 1. 本服务由 startForegroundService() 启动，Android 8+ 要求在超时前调用
        //    startForeground()，否则抛 RemoteServiceException（API 31+ 为
        //    ForegroundServiceDidNotStartInTimeException）。因此即便本次命令的结果
        //    是「无窗口、立即停止」，也必须先挂上一次通知，再走收尾流程。
        // 2. Manifest 声明了 foregroundServiceType="specialUse"，targetSdk 34+ 必须
        //    使用带 type 的 startForeground 重载，否则抛 MissingForegroundServiceTypeException。
        //    两件事统一由 startForegroundCompat() 处理。
        startForegroundCompat(buildNotification(runningCount));
        if (runningCount <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // 服务销毁时兜底清理所有窗口（正常路径应在 stopSelf 前已清空）
        OverlayWindowManager.getInstance().release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 纯命令式服务，不提供绑定
    }

    // ─── 内部工具 ───

    /** 解析 EXTRA_TYPE，非法值返回 null。 */
    private MonitorType parseType(String name) {
        if (name == null) {
            return null;
        }
        try {
            return MonitorType.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 创建低打扰通知渠道（API 26+ 必需）。 */
    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "使用监视器",
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("悬浮监视窗口运行中");
        nm.createNotificationChannel(channel);
    }

    /**
     * 启动前台服务，兼容 Android 14（API 34）的前台服务类型校验。
     *
     * <p>API 34 起，若 Manifest 为服务声明了 {@code android:foregroundServiceType}，
     * 就必须调用带 type 的 {@code startForeground} 重载；沿用旧的二参重载会抛
     * {@code MissingForegroundServiceTypeException}。本服务声明的是 {@code specialUse}，
     * 与 Manifest 中的 {@code PROPERTY_SPECIAL_USE_FGS_SUBTYPE} 配套。
     *
     * <p>{@link ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE} 是编译期常量，
     * 会被内联到调用处，因此在低于 API 34 的设备上不会因为缺字段而崩溃。
     */
    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // 个别 ROM 定制了前台服务校验，失败时不能让主流程崩溃：
            // 降级为普通启动，窗口本身已由 OverlayWindowManager 挂出。
            Log.e(TAG, "startForeground failed, fallback to non-foreground", e);
        }
    }

    /** 构建前台服务通知：显示当前运行的窗口数量。 */
    private Notification buildNotification(int count) {
        // count 为 0 表示本次命令执行后已无窗口，服务即将停止，
        // 这则通知只用于满足 startForegroundService 的调用契约，随即会被移除。
        String contentText = count > 0
                ? count + " 个监视窗口运行中"
                : "正在停止监视窗口";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("使用监视器")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_monitor_speed)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}
