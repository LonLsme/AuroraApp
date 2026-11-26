package com.aurora.app.ui.overlay;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * 悬浮窗生命周期管理器（进程内单例）：
 * 统一维护 6 种监视窗口的创建/销毁，保证同一类型不会重复创建，支持多窗口并存。
 *
 * <p>每个开关独立驱动对应类型：
 * 开启 → {@link #start} 创建并显示窗口；关闭 → {@link #stop} 销毁窗口；
 * 全部关闭 → 由 {@link MonitorOverlayService} 自动停止前台服务。
 *
 * <p>新增监视器类型时：在 {@link MonitorType} 追加枚举值，并在
 * {@link #createWindow} 的 switch 中补充对应窗口实现即可。
 */
public final class OverlayWindowManager {

    private static volatile OverlayWindowManager instance;

    /** 当前正在运行的窗口表：type -> 窗口实例。 */
    private final Map<MonitorType, OverlayWindow> windows = new HashMap<>();

    private OverlayWindowManager() {
    }

    /** 获取全局单例（双重检查锁，保证多线程安全）。 */
    public static OverlayWindowManager getInstance() {
        if (instance == null) {
            synchronized (OverlayWindowManager.class) {
                if (instance == null) {
                    instance = new OverlayWindowManager();
                }
            }
        }
        return instance;
    }

    /** 指定类型的窗口是否正在显示。 */
    public boolean isRunning(MonitorType type) {
        return windows.containsKey(type);
    }

    /** 当前显示的窗口总数。 */
    public int runningCount() {
        return windows.size();
    }

    /**
     * 启动指定类型的监视窗口。
     * 若该类型已在运行则忽略（幂等），返回是否实际启动。
     */
    public boolean start(Context context, MonitorType type) {
        if (windows.containsKey(type)) {
            return false;
        }
        OverlayWindow window = createWindow(context, type);
        if (window == null) {
            return false;
        }
        // show() 内部已兜住 addView 异常并返回 false，这里再兜一层构造/测量阶段的异常，
        // 保证单个窗口挂不上不会把前台服务（连同其它已运行的窗口）一起带崩。
        try {
            if (!window.show()) {
                return false;
            }
        } catch (Throwable t) {
            android.util.Log.w("OverlayWindowManager", "启动悬浮窗失败: " + type, t);
            return false;
        }
        windows.put(type, window);
        return true;
    }

    /**
     * 停止并销毁指定类型的监视窗口。
     * 若未在运行则忽略（幂等），返回是否实际停止。
     */
    public boolean stop(MonitorType type) {
        OverlayWindow window = windows.remove(type);
        if (window == null) {
            return false;
        }
        window.hide();
        return true;
    }

    /** 停止全部窗口，返回停止的数量。 */
    public int stopAll() {
        int count = 0;
        for (MonitorType type : windows.keySet().toArray(new MonitorType[0])) {
            if (stop(type)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 重建所有正在显示的窗口，按当前 {@link android.util.DisplayMetrics} 重新排版。
     *
     * <p><b>为什么必须重建：</b>View 的 textSize / padding / LayoutParams 在创建那一刻就按当时的
     * density 换算成了 px。之后 {@code wm density} 或系统「显示大小」变化，并不会让已存在的视图
     * 重新换算 —— 表现就是悬浮窗在新分辨率下「大了一圈」：36dp 的窗口仍然是旧的 126px，
     * 但屏幕的逻辑宽度已经从 411dp 变成了别的值。改完分辨率 / 密度调用本方法即可恢复原尺寸。
     *
     * <p>位置会先写回 {@link OverlayConfig}，重建后窗口仍停在原处（未拖动过的窗口走默认布局）。
     *
     * <p>必须在主线程调用（WindowManager 操作）。
     *
     * @return 成功重建的窗口数
     */
    public int recreateAll(Context context) {
        if (windows.isEmpty()) {
            return 0;
        }
        MonitorType[] types = windows.keySet().toArray(new MonitorType[0]);
        for (MonitorType type : types) {
            OverlayWindow window = windows.get(type);
            if (window != null) {
                OverlayConfig.setPosition(context, type, window.getWindowX(), window.getWindowY());
            }
        }
        stopAll();
        int count = 0;
        for (MonitorType type : types) {
            if (start(context, type)) {
                count++;
            }
        }
        return count;
    }

    /** 服务销毁等场景下的整体清理，与 stopAll 等价。 */
    public void release() {
        stopAll();
    }

    /** 工厂：根据类型创建对应窗口实现。 */
    private OverlayWindow createWindow(Context context, MonitorType type) {
        switch (type) {
            case SYSTEM:
                return new SystemOverlayWindow(context);
            case PROCESS:
                return new ProcessOverlayWindow(context);
            case THREAD:
                return new ThreadOverlayWindow(context);
            case MINI:
                return new MiniOverlayWindow(context);
            case FPS:
                return new FpsOverlayWindow(context);
            case TEMPERATURE:
                return new TemperatureOverlayWindow(context);
            default:
                return null;
        }
    }
}
