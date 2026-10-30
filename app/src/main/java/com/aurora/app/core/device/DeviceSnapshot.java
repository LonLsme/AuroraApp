package com.aurora.app.core.device;

import java.util.Locale;

/**
 * 设备信息的一次快照。
 *
 * <h3>为什么要有这个类</h3>
 * 设备页的数据有两个来源：本机（{@code Build.*} + 少量 shell）与外接设备（全部走
 * {@code adb shell}）。两个来源都得喂给<b>同一套</b>卡片，字段必须一一对齐 ——
 * 用同一个值对象承载，页面就只认快照、不认来源，切换数据源时不需要分支渲染。
 *
 * <h3>静态字段与动态字段</h3>
 * {@link #title}、{@link #fingerprint} 这类一辈子不变；{@link #cores} 与
 * {@link #batteryCycles} 每秒都在变。{@link DeviceReader#readDynamic} 只填后者，
 * 前者留 {@code null} —— 页面据此判断「这次刷新要不要动静态行」。
 */
public final class DeviceSnapshot {

    /**
     * 这份快照的来源通道。
     *
     * <p>两者能读到的字段差得很远，页面必须知道自己拿的是哪一种，
     * 否则 fastboot 模式下整屏「—」会被当成读取失败：
     * <table border="1">
     *   <tr><th>字段</th><th>{@link #ADB}</th><th>{@link #FASTBOOT}</th></tr>
     *   <tr><td>产品代号 / 序列号</td><td>✅</td><td>✅</td></tr>
     *   <tr><td>BL 解锁状态</td><td>✅</td><td>✅（问 bootloader 自己）</td></tr>
     *   <tr><td>当前槽位</td><td>✅</td><td>✅</td></tr>
     *   <tr><td>Android 版本 / 指纹 / 安全补丁 / 基带</td><td>✅</td><td>❌ 系统没启动</td></tr>
     *   <tr><td>SELinux / 安卓 ID</td><td>✅（需 root）</td><td>❌</td></tr>
     *   <tr><td>CPU 各核频率 / 电池循环</td><td>✅</td><td>❌</td></tr>
     * </table>
     */
    public enum Transport {
        /** 正常系统，走 {@code adb shell}。 */
        ADB,
        /** fastboot / fastbootd 模式，只能读 bootloader 变量。 */
        FASTBOOT
    }

    /** 这份快照从哪来。本机与正常的 USB 外接都是 {@link Transport#ADB}。 */
    public Transport transport = Transport.ADB;

    /**
     * 读取失败的原因；读到了就是 {@code null}。
     *
     * <p>没有这个字段时，一次失败的读取和「这台设备本来就没有这些字段」在页面上完全
     * 长一个样 —— 全是「—」。用户切到外接设备后看到满屏「—」，既分不清是命令没发出去、
     * 对方没授权，还是根本就没切换成功，只能反复点下拉。有它之后页面能写明
     * 「序列号 · 读取失败」。
     */
    public String error;

    /** 制造商 + 型号，如 {@code OPPO PJX110}。 */
    public String title;
    /** 设备代号，如 {@code PJX110}。 */
    public String deviceCode;
    /** 如 {@code Android 16 (API 36)}。 */
    public String android;
    /** 内核版本，如 {@code 6.1.118-android15-11}。 */
    public String kernel;
    /** {@code getenforce} 原文：{@code Enforcing} / {@code Permissive} / {@code Disabled}；读不到为 {@code null}。 */
    public String selinux;
    /** 如 {@code A 槽位} / {@code 未启用 A/B 分区}。 */
    public String bootSlot;
    /** 引导加载器解锁状态；三个属性都读不到时是 {@link BootloaderState#UNKNOWN}。 */
    public BootloaderState bootloader = BootloaderState.UNKNOWN;
    /** {@code ro.build.display.id}。 */
    public String buildDisplay;
    /** 安全补丁日期。 */
    public String securityPatch;
    /** 基带版本。 */
    public String radio;
    /** 安卓 ID。 */
    public String androidId;
    /** 构建指纹（一长串）。 */
    public String fingerprint;
    /** 电池循环次数；读不到为 {@code null}。 */
    public String batteryCycles;

    /** 各核状态，下标即核心号。 */
    public CpuCore[] cores = new CpuCore[0];

    /** 单个核心的频率与在线状态。 */
    public static final class CpuCore {
        public final int index;
        public final boolean online;
        /** 当前频率 kHz；读不到 {@code -1}。 */
        public final long curKhz;
        /** 最大频率 kHz；读不到 {@code -1}。 */
        public final long maxKhz;
        /** 最小频率 kHz；读不到 {@code -1}。 */
        public final long minKhz;

        CpuCore(int index, boolean online, long curKhz, long maxKhz, long minKhz) {
            this.index = index;
            this.online = online;
            this.curKhz = curKhz;
            this.maxKhz = maxKhz;
            this.minKhz = minKhz;
        }

        /** 展示用频段，如 {@code 364~2265MHz}；数据不全时是 {@code —}。 */
        public String rangeLabel() {
            if (minKhz <= 0 || maxKhz <= 0) {
                return "—";
            }
            return String.format(Locale.getDefault(), "%.0f~%.0fMHz", minKhz / 1000.0, maxKhz / 1000.0);
        }

        /**
         * 环心百分比 = 当前频率 / 最大频率。
         *
         * <p>不是「CPU 占用率」—— Android 12 起 {@code /proc/stat} 对三方应用关闭，
         * 占用率根本读不到。频率占比是这个替代方案里最接近「忙不忙」的指标，
         * 也解释了为什么数值会跟着调频一起跳。
         */
        public int loadPercent() {
            if (!online || maxKhz <= 0L || curKhz <= 0L) {
                return 0;
            }
            int pct = Math.round(curKhz * 100f / (float) maxKhz);
            return Math.min(100, Math.max(0, pct));
        }
    }
}
