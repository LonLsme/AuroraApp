package com.aurora.app.core.flash;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 设备连接状态探测。
 *
 * <p>一次探测同时取三件事：
 * <ol>
 *   <li><b>ADB 在线</b>：{@code adb devices}，拿到序列号与状态（{@code device} /
 *       {@code unauthorized} / {@code offline}）；</li>
 *   <li><b>Fastboot 在线</b>：{@code fastboot devices}；</li>
 *   <li><b>深刷口 USB ID</b>：扫 {@code /sys/bus/usb/devices/*} 的 idVendor:idProduct，
 *       命中高通 9008 / 900E、联发科 Preloader / BROM 等已知 ID。</li>
 * </ol>
 *
 * <h3>为什么用 sysfs 而不是 lsusb</h3>
 * {@code lsusb} 不是所有 Android 版本都带（toybox 版本差异大，且输出格式不稳定）。
 * USB 设备目录下的 {@code idVendor} / {@code idProduct} 节点只要内核挂载了 usbfs 就一定存在，
 * 且只需 {@code cat} —— 在 root 下一定可读。
 *
 * <h3>一次命令拿全</h3>
 * 三条探测合并成<b>一条</b> su 命令串行执行，用 {@code ##ADB##} 之类的分隔标记切段，
 * 避免为每项各起一次 root 进程（本项目在「进程枚举」上已吃过这个亏）。
 */
public final class FlashDevice {

    /** 段分隔标记，与命令里的 echo 一一对应。 */
    private static final String MARK_ROOT = "##ROOT##";
    private static final String MARK_ADB = "##ADB##";
    private static final String MARK_FASTBOOT = "##FASTBOOT##";
    private static final String MARK_USB = "##USB##";

    /** 已知的深刷 / 特殊模式 USB ID。 */
    private static final String[][] KNOWN_USB_IDS = {
            {"05c6:9008", "高通 QDLoader（9008 深刷）"},
            {"05c6:900e", "高通 900E"},
            {"0e8d:2000", "联发科 Preloader"},
            {"0e8d:0003", "联发科 BROM"},
    };

    /** 下拉弹窗里 10 个槽位的固定顺序与名称（界面文案，与 {@link #slotStatuses} 一一对应）。 */
    public static final String[] SLOT_NAMES = {
            "ADB设备",
            "Fastboot设备",
            "FastbootD设备",
            "Sideload设备",
            "Recovery设备",
            "未授权设备",
            "9008 EDL端口",
            "900E端口",
            "联发科Preloader端口",
            "联发科BROM端口",
    };

    /** 槽位未命中时的状态文案。 */
    public static final String NOT_CONNECTED = "未连接设备";

    private FlashDevice() {
    }

    /** 一次探测的结果快照。 */
    public static final class State {
        /** 是否拿到 root（{@code id -u} == 0）。 */
        public final boolean rootAvailable;
        /** ADB 是否在线且已授权。 */
        public final boolean adbReady;
        /** ADB 设备序列号；无设备为 null。 */
        public final String adbSerial;
        /** ADB 设备状态原文（{@code device} / {@code unauthorized} / {@code offline}）。 */
        public final String adbState;
        /** Fastboot 是否在线。 */
        public final boolean fastbootReady;
        /** Fastboot 设备序列号；无设备为 null。 */
        public final String fastbootSerial;
        /**
         * Fastboot 通道类型：{@code fastboot}（bootloader）/ {@code fastbootd}（用户空间）。
         *
         * <p>{@code fastboot devices} 的第二列就是它 —— 两者都能被同一条命令列出，
         * 但可刷的分区集合不同，界面上是<b>两个独立槽位</b>，必须分开判定。
         */
        public final String fastbootMode;
        /** 命中的深刷口描述；未命中为 null。 */
        public final String deepFlashHint;
        /** 扫到的全部 USB ID（调试用）。 */
        public final List<String> usbIds;

        State(boolean rootAvailable, String adbSerial, String adbState,
              String fastbootSerial, String fastbootMode,
              String deepFlashHint, List<String> usbIds) {
            this.rootAvailable = rootAvailable;
            this.adbSerial = adbSerial;
            this.adbState = adbState;
            this.adbReady = adbSerial != null && "device".equals(adbState);
            this.fastbootSerial = fastbootSerial;
            this.fastbootMode = fastbootMode;
            this.fastbootReady = fastbootSerial != null;
            this.deepFlashHint = deepFlashHint;
            this.usbIds = Collections.unmodifiableList(usbIds);
        }

        /** 顶部状态行文案 —— 页面只显示这一句实时状态，不做机制说明。 */
        public String summary() {
            if (!rootAvailable) {
                return "未获取 Root 权限";
            }
            if (fastbootReady) {
                return "Fastboot 已连接 · " + fastbootSerial;
            }
            if (adbReady) {
                return "ADB 已连接 · " + adbSerial;
            }
            if (adbSerial != null) {
                return "ADB " + ("unauthorized".equals(adbState) ? "未授权" : adbState)
                        + " · " + adbSerial;
            }
            if (deepFlashHint != null) {
                return deepFlashHint;
            }
            return "未连接设备";
        }
    }

    /**
     * 按下拉弹窗的固定顺序算出 10 个槽位的状态文案。
     *
     * <p>返回数组与 {@link #SLOT_NAMES} 严格等长同序，未命中的槽位一律是
     * {@link #NOT_CONNECTED}（界面上的小字）。{@code state == null}（还没探测过 /
     * 探测被拒）时整份返回「未连接设备」，不会抛异常。
     *
     * <p><b>为什么 Fastboot 与 FastbootD 要分开判</b>：{@code fastboot devices}
     * 会把两种模式都列出来，靠第二列（{@code fastboot} / {@code fastbootd}）区分。
     * 不区分的话用户会以为在 fastbootd 下也能刷 boot，实际会失败。
     */
    public static String[] slotStatuses(State state) {
        String[] out = new String[SLOT_NAMES.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = NOT_CONNECTED;
        }
        if (state == null) {
            return out;
        }
        // 没 root 时 adb / fastboot 都拉不起来，这里只能全是「未连接设备」；
        // 「未获取 Root 权限」由头部胶囊的 summary() 单独表达，不在槽位里重复。
        if (state.adbSerial != null && "device".equals(state.adbState)) {
            out[0] = "已连接 · " + state.adbSerial;
        }
        if (state.fastbootReady) {
            if ("fastbootd".equals(state.fastbootMode)) {
                out[2] = "已连接 · " + state.fastbootSerial;
            } else {
                out[1] = "已连接 · " + state.fastbootSerial;
            }
        }
        if ("sideload".equals(state.adbState)) {
            out[3] = "已连接 · " + state.adbSerial;
        }
        if ("recovery".equals(state.adbState)) {
            out[4] = "已连接 · " + state.adbSerial;
        }
        if ("unauthorized".equals(state.adbState)) {
            out[5] = "已连接 · " + state.adbSerial;
        }
        out[6] = usbSlot(state, "05c6:9008");
        out[7] = usbSlot(state, "05c6:900e");
        out[8] = usbSlot(state, "0e8d:2000");
        out[9] = usbSlot(state, "0e8d:0003");
        return out;
    }

    private static String usbSlot(State state, String usbId) {
        return state.usbIds.contains(usbId) ? "已连接" : NOT_CONNECTED;
    }

    /** 探测结果回调（后台线程触发）。 */
    public interface Callback {
        void onResult(State state);
    }

    /**
     * 执行一次探测。<b>必须在 root 可用时调用</b>，整个探测走一条 su 命令。
     *
     * <p>若 {@link FlashRuntime} 正忙（例如刷机进行中），探测会被拒绝并回调
     * {@code null} —— 刷机中途重启 adb / fastboot 进程本身就是危险操作。
     */
    public static void detect(final Context context, final Callback callback) {
        final StringBuilder sink = new StringBuilder();
        boolean accepted = FlashRuntime.get().run(context, buildDetectCommand(),
                new FlashRuntime.Listener() {
                    @Override
                    public void onLine(String line) {
                        sink.append(line).append('\n');
                    }

                    @Override
                    public void onExit(int exitCode, boolean cancelled) {
                        if (callback == null) {
                            return;
                        }
                        callback.onResult(cancelled ? null : parse(sink.toString()));
                    }
                });
        if (!accepted && callback != null) {
            callback.onResult(null);
        }
    }

    /**
     * 拼探测命令。
     *
     * <p>{@code adb devices} 在 adb server 未启动时会自己拉起守护进程（首次几秒），
     * 因此本方法调用方需要容忍较长耗时，UI 侧显示「检测中…」。
     */
    private static String buildDetectCommand() {
        return "echo '" + MARK_ROOT + "'; id -u; "
                + "echo '" + MARK_ADB + "'; adb devices 2>&1; "
                + "echo '" + MARK_FASTBOOT + "'; fastboot devices 2>&1; "
                + "echo '" + MARK_USB + "'; "
                + "for d in /sys/bus/usb/devices/*/; do "
                + "v=$(cat ${d}idVendor 2>/dev/null); p=$(cat ${d}idProduct 2>/dev/null); "
                + "[ -n \"$v\" ] && [ -n \"$p\" ] && echo \"$v:$p\"; "
                + "done; true";
    }

    /**
     * 解析探测输出。
     *
     * <p>包级可见（非 private）是为了让离线断言测试能直接喂字符串进来，
     * 不必依赖真机与 root。
     */
    static State parse(String output) {
        boolean root = false;
        String adbSerial = null;
        String adbState = null;
        String fastbootSerial = null;
        String fastbootMode = null;
        List<String> usbIds = new ArrayList<>();

        String section = null;
        if (output != null) {
            for (String raw : output.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                // 段标记可能与被 echo 出来的同一行混排，用 startsWith 而非 equals
                if (line.startsWith(MARK_ROOT)) {
                    section = "root";
                    continue;
                }
                if (line.startsWith(MARK_ADB)) {
                    section = "adb";
                    continue;
                }
                if (line.startsWith(MARK_FASTBOOT)) {
                    section = "fastboot";
                    continue;
                }
                if (line.startsWith(MARK_USB)) {
                    section = "usb";
                    continue;
                }

                if ("root".equals(section)) {
                    root = "0".equals(line);
                } else if ("adb".equals(section)) {
                    // 跳过 "List of devices attached"、"* daemon not running..." 之类噪音
                    if (line.startsWith("List of devices") || line.startsWith("*")
                            || line.startsWith("adb server")) {
                        continue;
                    }
                    if (adbSerial == null) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 1 && !parts[0].isEmpty()) {
                            adbSerial = parts[0];
                            adbState = parts.length >= 2 ? parts[1] : "unknown";
                        }
                    }
                } else if ("fastboot".equals(section)) {
                    // 权限不足时 fastboot 会打一整句 "no permissions ...; see [http://...]"
                    // 首 token 会被误当序列号，直接跳过。
                    if (line.startsWith("no ") || line.startsWith("*")) {
                        continue;
                    }
                    if (fastbootSerial == null) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 1 && !parts[0].isEmpty()) {
                            fastbootSerial = parts[0];
                            // 第二列是通道类型：fastboot（bootloader）/ fastbootd（用户空间）。
                            // 拿不到就按 bootloader 处理 —— 绝大多数设备是这种。
                            fastbootMode = parts.length >= 2 ? parts[1] : "fastboot";
                        }
                    }
                } else if ("usb".equals(section)) {
                    String id = line.toLowerCase(Locale.US);
                    if (id.matches("[0-9a-f]{4}:[0-9a-f]{4}")) {
                        usbIds.add(id);
                    }
                }
            }
        }

        String deepFlash = null;
        for (String[] known : KNOWN_USB_IDS) {
            if (usbIds.contains(known[0])) {
                deepFlash = known[1];
                break;
            }
        }

        return new State(root, adbSerial, adbState, fastbootSerial, fastbootMode, deepFlash, usbIds);
    }

    // ───────────────── 展示辅助 ─────────────────

    /** 单个 USB ID 的可读描述；未知 ID 原样返回。 */
    public static String describeUsbId(String id) {
        for (String[] known : KNOWN_USB_IDS) {
            if (known[0].equals(id)) {
                return known[1];
            }
        }
        return id;
    }
}
