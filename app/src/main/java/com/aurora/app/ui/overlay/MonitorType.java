package com.aurora.app.ui.overlay;

/**
 * 监视器类型枚举：6 种悬浮窗的唯一标识。
 *
 * <p>每种类型同时承担两个职责：
 * <ul>
 *   <li>{@link #prefKey}：SharedPreferences 中对应开关的 key，
 *       与使用监视器弹窗中的 6 个 Switch 一一对应；</li>
 *   <li>{@link #label}：悬浮窗/通知中展示的中文名称。</li>
 * </ul>
 *
 * <p>新增监视器时：在此追加一个枚举值，并在
 * {@link OverlayWindowManager#createWindow} 中补充对应的窗口实现即可。
 */
public enum MonitorType {

    /** 负载监视器：CPU 使用率与系统负载。 */
    LOAD("monitor_load", "负载监视器"),

    /** 进程监视器：CPU 占用最高的前几个进程。 */
    PROCESS("monitor_process", "进程监视器"),

    /** 线程监视器：系统线程总数。 */
    THREAD("monitor_thread", "线程监视器"),

    /** 迷你监视器：单行胶囊综合显示 CPU / FPS / 温度。 */
    MINI("monitor_mini", "迷你监视器"),

    /** 帧率记录器：实时 FPS 与单帧耗时。 */
    FPS("monitor_fps", "帧率记录器"),

    /** 温度监视器：电池与 CPU 温度。 */
    TEMPERATURE("monitor_temperature", "温度监视器");

    /** 开关状态在 SharedPreferences 中的 key。 */
    public final String prefKey;

    /** 显示名称。 */
    public final String label;

    MonitorType(String prefKey, String label) {
        this.prefKey = prefKey;
        this.label = label;
    }
}
