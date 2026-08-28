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
        window.show();
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

    /** 服务销毁等场景下的整体清理，与 stopAll 等价。 */
    public void release() {
        stopAll();
    }

    /** 工厂：根据类型创建对应窗口实现。 */
    private OverlayWindow createWindow(Context context, MonitorType type) {
        switch (type) {
            case LOAD:
                return new LoadOverlayWindow(context);
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
