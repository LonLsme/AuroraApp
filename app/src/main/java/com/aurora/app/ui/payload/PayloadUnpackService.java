package com.aurora.app.ui.payload;

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
 * Payload 解包期间的前台服务：只负责把进程按在前台，不承担实际解包工作。
 *
 * <h3>为什么需要它</h3>
 * 解包一份全量 OTA 可能持续十几分钟并写入数 GB。以前整个过程跑在
 * {@link PayloadActivity} 自己的 executor 上，没有前台服务：用户切到后台、锁屏，
 * 或者系统按内存压力回收进程，解包就中断，已写出的文件既不完整也没有提示。
 * 挂一个前台服务后，进程有明确的「正在工作」身份，系统不会把它当作可随意回收的后台进程。
 *
 * <h3>实现约束</h3>
 * <ul>
 *   <li>必须由 {@code startForegroundService} 启动，并尽快调用 {@code startForeground}；</li>
 *   <li>targetSdk 34+ 必须使用带 {@code foregroundServiceType} 的重载，
 *       否则抛 {@code MissingForegroundServiceTypeException}（Manifest 声明的是 specialUse）；</li>
 *   <li>通知权限（Android 13+ 的 {@code POST_NOTIFICATIONS}）不授予时通知不显示，
 *       但前台服务仍然有效，不影响解包本身。</li>
 * </ul>
 */
public class PayloadUnpackService extends Service {

    private static final String TAG = "PayloadUnpackService";
    private static final String CHANNEL_ID = "aurora_payload";
    private static final int NOTIFICATION_ID = 1002;

    private static final String ACTION_START = "com.aurora.app.payload.ACTION_START";

    /** 开始解包：拉起前台服务（重复调用无副作用）。 */
    public static void start(Context context) {
        Intent intent = new Intent(context, PayloadUnpackService.class);
        intent.setAction(ACTION_START);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable t) {
            // 后台启动前台服务受限时，解包仍会继续，只是少了一层保活
            Log.w(TAG, "启动解包前台服务失败", t);
        }
    }

    /**
     * 解包结束（成功 / 失败 / 取消）后停止前台服务。
     *
     * <p>用 {@code stopService} 而不是再发一个 start 命令：服务没在运行时这是空操作，
     * 不会为了「停止」反而把它拉起来闪一下通知。
     */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, PayloadUnpackService.class));
        } catch (Throwable t) {
            Log.w(TAG, "停止解包前台服务失败", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须尽快 startForeground，否则系统会抛
        // ForegroundServiceDidNotStartInTimeException（约 5~10 秒）。
        startForegroundCompat(buildNotification());
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "固件解包",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("正在从 OTA 包提取分区镜像");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            // 个别 ROM 定制了前台服务校验：失败时降级为普通服务，不影响解包线程
            Log.e(TAG, "startForeground 失败，降级为普通服务", t);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("固件解包")
                .setContentText("正在提取分区镜像，请勿强制停止应用")
                .setSmallIcon(R.drawable.ic_monitor_speed)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
