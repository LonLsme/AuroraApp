package com.aurora.app.ui.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

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
        // 还有窗口在运行 → 保持前台服务；全部关闭 → 停止服务
        if (manager.runningCount() > 0) {
            startForeground(NOTIFICATION_ID, buildNotification(manager.runningCount()));
        } else {
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

    /** 构建前台服务通知：显示当前运行的窗口数量。 */
    private Notification buildNotification(int count) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("使用监视器")
                .setContentText(count + " 个监视窗口运行中")
                .setSmallIcon(R.drawable.ic_monitor_speed)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}
