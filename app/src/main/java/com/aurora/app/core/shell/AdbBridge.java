package com.aurora.app.core.shell;

import android.content.Context;
import android.util.Log;

import com.aurora.app.core.flash.FlashRuntime;
import com.aurora.app.core.flash.FlashToolchain;
import com.aurora.app.core.root.RootShell;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 外接设备通道：用项目里内置的那份 aarch64 {@code adb} 去看「另一台手机」。
 *
 * <h3>它只是外接设备的工具，不是本机的权限通道</h3>
 * 本机信息只走 Root（见 {@link RootShell}）。本类只服务一件事：
 * <b>把外接设备的列表读出来、把命令发到外接设备上</b>。
 *
 * <h3>为什么身份不同能看到的东西不同</h3>
 * 内置 adb 靠扫 {@code /dev/bus/usb/*} 发现 USB 上插着的手机，而这些节点是
 * {@code root:usb 0660}。普通应用（uid ≥ 10000）连目录都进不去，所以
 * <b>以应用身份跑的 adb 永远列不到 USB 外接设备</b> —— 这不是代码写错，是沙箱边界。
 * <table border="1">
 *   <tr><th>身份</th><th>USB 外接</th><th>无线（{@code adb connect}）</th></tr>
 *   <tr><td>root</td><td>能枚举</td><td>能</td></tr>
 *   <tr><td>应用（无 root）</td><td><b>不能</b></td><td>能</td></tr>
 * </table>
 * 所以有 root 时一切走 root；没有 root 时仍允许 {@code adb connect}，
 * 让用户把「开着无线调试的另一台手机」接进来。
 *
 * <h3>adb server 端口</h3>
 * root 身份与刷机页<b>共用</b>一个 server（同一个 HOME、同一个默认端口），
 * 这样刷机页刚连上的设备在设备页立刻能看到，不会出现两套互相看不见的设备表。
 * 应用身份则改用 5038：它和 root 的 5037 是两个进程，同端口会互相抢 bind 而启动失败。
 */
public final class AdbBridge {

    private static final String TAG = "AdbBridge";

    /** 一次 adb 调用的默认上限：首次起 server + 扫 USB 会慢一拍。 */
    private static final long DEFAULT_TIMEOUT_MS = 15_000L;

    /** 应用身份专用的 server 端口，避免与 root/刷机页的 5037 抢 bind。 */
    private static final String APP_SERVER_PORT = "5038";

    private AdbBridge() {
    }

    // ───────────────── 环境 ─────────────────

    /** 应用身份的 HOME：非 root 下唯一能写 adbkey 的地方。 */
    private static File appHomeDir(Context context) {
        File dir = new File(context.getFilesDir(), "adb_home");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** root 身份的 HOME：与刷机页共用，adbkey 只需配对/授权一次。 */
    private static String rootHomeDir() {
        return FlashRuntime.RUNTIME_ROOT + "/home";
    }

    /** 工具链是否已解压（未解压时任何 adb 调用都无从谈起）。 */
    public static boolean isReady(Context context) {
        return FlashToolchain.isReady(context);
    }

    // ───────────────── 执行 ─────────────────

    /** 跑一条 adb 子命令（{@code args} 不含 adb 自身路径）。 */
    public static ShellManager.Result run(Context context, long timeoutMs, String... args) {
        return runBinary(context, FlashToolchain.adb(context), false, timeoutMs, args);
    }

    /**
     * 跑一条 fastboot 子命令。
     *
     * <p><b>stderr 必须合并</b>：{@code fastboot getvar} 的结果全部打在 stderr 上
     * （{@code (bootloader) product:xxx}），只读 stdout 会拿到空串，
     * 表现就是「识别到了设备但一个字段都没有」。
     */
    public static ShellManager.Result runFastboot(Context context, long timeoutMs, String... args) {
        return runBinary(context, FlashToolchain.fastboot(context), true, timeoutMs, args);
    }

    private static ShellManager.Result runBinary(Context context, File bin, boolean mergeStderr,
                                                 long timeoutMs, String... args) {
        if (!bin.isFile()) {
            Log.w(TAG, "工具链没解压，调用放弃: " + bin);
            return ShellManager.Result.unavailable();
        }
        // 直接问 RootShell 而不是读 ShellManager 的缓存状态：首帧它还停在 DETECTING，
        // 那一刻按缓存判断会把 root 用户降级成应用身份，adb 设备表就少一半。
        boolean root = RootShell.get().isRootAvailable();
        ShellManager.Result r;
        try {
            r = root ? runAsRoot(context, bin, mergeStderr, timeoutMs, args)
                    : runAsApp(context, bin, timeoutMs, args);
        } catch (Throwable t) {
            Log.w(TAG, "adb 调用抛异常 [" + (root ? "root" : "app") + "] "
                    + String.join(" ", args), t);
            return ShellManager.Result.unavailable();
        }
        if (!r.hasOutput()) {
            // 失败必须留痕：没有这行，页面只会显示「—」或空下拉，无从判断是身份、超时
            // 还是 adb 自己报错（未授权 / offline 都在这条输出里）
            String out = r.out == null ? "" : r.out.trim();
            Log.w(TAG, "adb 无有效输出 [" + (root ? "root" : "app") + "] "
                    + String.join(" ", args)
                    + " → exit=" + r.exitCode + " timedOut=" + r.timedOut
                    + " unavailable=" + r.unavailable
                    + (out.isEmpty() ? ""
                    : " out=" + (out.length() > 200 ? out.substring(0, 200) : out)));
        }
        return r;
    }

    /**
     * 以 root 身份跑：{@code HOME} 指向刷机页那个目录，端口用默认值。
     *
     * <p>走 {@link ShellManager#exec} 而不是自己起 {@code su}：那里已经维护着一条
     * 持久会话，反复 fork {@code su} 每次都要几百毫秒。
     */
    private static ShellManager.Result runAsRoot(Context context, File bin, boolean mergeStderr,
                                                 long timeoutMs, String... args) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("mkdir -p ").append(rootHomeDir()).append("; ");
        // 环境变量前缀只能作用于紧随其后的那条命令，所以 mkdir 必须放在前缀外面
        cmd.append("HOME=").append(rootHomeDir())
                .append(" TMPDIR=").append(rootHomeDir())
                .append(" LD_LIBRARY_PATH=")
                .append(RootShell.escape(FlashToolchain.binDir(context).getAbsolutePath()))
                .append(' ')
                .append(RootShell.escape(bin.getAbsolutePath()));
        for (String arg : args) {
            cmd.append(' ').append(RootShell.escape(arg));
        }
        if (mergeStderr) {
            cmd.append(" 2>&1");
        }
        return ShellManager.get().exec(cmd.toString(), timeoutMs);
    }

    /** 以应用身份跑：能 {@code connect} 网络设备，但枚举不到 USB。 */
    private static ShellManager.Result runAsApp(Context context, File bin, long timeoutMs, String... args) {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add(bin.getAbsolutePath());
        Collections.addAll(command, args);

        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            File home = appHomeDir(context);
            builder.directory(home);
            // adb 在 HOME 下建 .android 存 adbkey；非 root 只能落在应用私有目录。
            // 端口与 root 的 5037 分开，否则两个进程抢同一个 bind 会双双起不来。
            builder.environment().put("HOME", home.getAbsolutePath());
            builder.environment().put("TMPDIR", home.getAbsolutePath());
            builder.environment().put("ANDROID_ADB_SERVER_PORT", APP_SERVER_PORT);
            builder.environment().put("LD_LIBRARY_PATH",
                    FlashToolchain.binDir(context).getAbsolutePath());
            // adb 的 daemon 启动信息混在 stdout 里，合并流最省事
            builder.redirectErrorStream(true);

            process = builder.start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            // 读线程在写、主流程在 toString：ByteArrayOutputStream 不是线程安全的，
            // 不互斥就可能读到「数组已扩容但内容只写到一半」的状态，字段忽有忽无。
            final Object bufferLock = new Object();
            final InputStream in = process.getInputStream();
            Thread reader = new Thread(() -> {
                try (InputStream stream = in) {
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = stream.read(chunk)) > 0) {
                        synchronized (bufferLock) {
                            buffer.write(chunk, 0, n);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "aurora-adb-out");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
            Integer exit = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    exit = process.exitValue();
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    Thread.sleep(20L);
                }
            }
            boolean timedOut = exit == null;
            if (timedOut) {
                process.destroy();
            }
            try {
                reader.join(timedOut ? 300L : 1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String output;
            synchronized (bufferLock) {
                output = buffer.toString();
            }
            return new ShellManager.Result(timedOut ? -1 : exit, output, timedOut, false);
        } catch (Throwable t) {
            Log.w(TAG, "adb 调用失败: " + command, t);
            return ShellManager.Result.unavailable();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** 在指定设备上执行 shell 命令。 */
    public static ShellManager.Result shell(Context context, String serial, String command, long timeoutMs) {
        if (serial == null || serial.isEmpty() || command == null || command.isEmpty()) {
            return ShellManager.Result.unavailable();
        }
        return run(context, timeoutMs, "-s", serial, "shell", command);
    }

    // ───────────────── 设备 ─────────────────

    /** {@code adb devices} 的一行。 */
    public static final class Device {
        public final String serial;
        /** {@code device} / {@code unauthorized} / {@code offline} … */
        public final String state;

        Device(String serial, String state) {
            this.serial = serial;
            this.state = state;
        }

        public boolean online() {
            return "device".equals(state);
        }

        /**
         * 是不是 USB 有线连接。
         *
         * <p>判据只看序列号里有没有冒号：USB 连接报的是设备自身序列号（{@code 45850c2f}），
         * 网络连接一定带 {@code ip:端口}（{@code 192.168.1.23:37105}）。这不是猜格式 ——
         * adb 的 transport 类型在 {@code devices} 输出里不体现，序列号是唯一可用于区分的字段。
         */
        public boolean usb() {
            return serial != null && !serial.contains(":");
        }
    }

    /** {@code fastboot devices} 的一行。 */
    public static final class FastbootDevice {
        public final String serial;
        /** {@code fastboot}（bootloader）或 {@code fastbootd}（用户空间的那个 fastboot）。 */
        public final String mode;

        FastbootDevice(String serial, String mode) {
            this.serial = serial;
            this.mode = mode;
        }

        /**
         * 是不是 {@code fastbootd}。
         *
         * <p>两者必须分开：{@code fastboot devices} 会把它们都列出来，第二列不同。
         * {@code fastbootd} 是跑在 Android 用户空间的快刷服务，只能动逻辑分区，
         * 拿不到 {@code unlocked} 这类 bootloader 变量。
         */
        public boolean userspace() {
            return "fastbootd".equals(mode);
        }
    }

    /**
     * 解析 {@code fastboot devices} 的输出。
     *
     * <p>只看第二列是不是 {@code fastboot} / {@code fastbootd}：这条命令在等设备时
     * 会打印 {@code < waiting for any device >}，没权限时会打印
     * {@code no permissions (udev rules?)...}，两者都不是设备行。
     */
    static List<FastbootDevice> parseFastbootDevices(String output) {
        List<FastbootDevice> result = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return result;
        }
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String mode = parts[1].toLowerCase(Locale.US);
            if (!"fastboot".equals(mode) && !"fastbootd".equals(mode)) {
                continue;
            }
            result.add(new FastbootDevice(parts[0], mode));
        }
        return result;
    }

    /** 列全部处于 fastboot / fastbootd 的设备。 */
    public static List<FastbootDevice> fastbootDevices(Context context, long timeoutMs) {
        ShellManager.Result r = runFastboot(context, timeoutMs, "devices");
        if (!r.hasOutput()) {
            return new ArrayList<>();
        }
        return parseFastbootDevices(r.out);
    }

    /**
     * {@code fastboot -s <serial> getvar all}，原始文本交回去由调用方解析。
     *
     * <p>不用 {@link #runFastboot} 的 args 形式拼 {@code -s}：两者等价，
     * 抽成方法只是为了让「读一台 fastboot 设备」这件事有个名字，
     * 也让 {@code 2>&1} 这类细节只出现在一个地方。
     */
    public static ShellManager.Result fastbootGetvarAll(Context context, String serial, long timeoutMs) {
        if (serial == null || serial.isEmpty()) {
            return ShellManager.Result.unavailable();
        }
        return runFastboot(context, timeoutMs, "-s", serial, "getvar", "all");
    }

    /** 一次「外接设备全景」：adb 的能用/待授权两桶，外加 fastboot 那一批。 */
    public static final class Snapshot {
        /** 状态为 {@code device}，可以真正读数据。 */
        public final List<Device> online = new ArrayList<>();
        /**
         * 看得到但读不了：{@code unauthorized}（等对方点「允许」）、{@code offline}、
         * {@code authorizing}。
         *
         * <p>必须单独留下来 —— 只过滤 {@code device} 的话，USB 首次插入时列表是空的，
         * 用户看到的是「插了没反应」，而实际上那台设备已经在提示他点允许了。
         */
        public final List<Device> pending = new ArrayList<>();
        /**
         * 处于 fastboot / fastbootd 的设备。
         *
         * <p>这类设备<b>系统没启动</b>：{@code adb devices} 看不到它，
         * 只能靠 {@code fastboot devices}；读出来的也只有 bootloader 变量
         * （产品代号、序列号、BL 解锁状态、当前槽位、电池电压），
         * CPU 频率与 Android 版本一概没有。
         */
        public final List<FastbootDevice> fastboot = new ArrayList<>();

        public boolean isEmpty() {
            return online.isEmpty() && pending.isEmpty() && fastboot.isEmpty();
        }
    }

    /** {@code adb devices} 里出现过的合法状态词。不在这张表里的行一律当噪声丢掉。 */
    private static final Set<String> KNOWN_STATES = new LinkedHashSet<>(java.util.Arrays.asList(
            "device", "offline", "unauthorized", "recovery", "sideload", "bootloader",
            "host", "no", "authorizing"));

    /**
     * 解析 {@code adb devices} 的输出。
     *
     * <p>抽成静态方法只为可测：真实输出里混着 {@code "List of devices attached"}、
     * {@code "* daemon not running..."}、以及 adb server 与 client 版本不一致时的告警，
     * 那些行同样有好几段，只能靠「第二段是不是合法状态词」剔掉。
     */
    static List<Device> parseDevices(String output) {
        List<Device> result = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return result;
        }
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            // 跳过 "List of devices attached"、空行、以及 "* daemon not running..." 这类噪声
            if (line.isEmpty() || line.startsWith("List of") || line.startsWith("*")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            if (!KNOWN_STATES.contains(parts[1].toLowerCase(Locale.US))) {
                continue;
            }
            result.add(new Device(parts[0], parts[1].toLowerCase(Locale.US)));
        }
        return result;
    }

    /** 列全部已连接设备（含无线与 USB）。 */
    public static List<Device> devices(Context context, long timeoutMs) {
        ShellManager.Result r = run(context, timeoutMs, "devices");
        if (!r.hasOutput()) {
            return new ArrayList<>();
        }
        return parseDevices(r.out);
    }

    /**
     * 外接设备全景：可用的与待授权的分开返回（供设备页下拉切换数据源）。
     *
     * <p>排除「连到本机自己」的那条 —— 它属于本机数据源，出现在外接列表里会让
     * 同一台设备在页面上出现两次。
     *
     * <p>工具链没解压时会先解压：下拉里能不能列出外接设备，取决于内置 adb 在不在。
     * 这一步读 9.9MB assets，<b>只能在后台线程调用</b>（设备页把它放在读取线程上）。
     *
     * <p>无 root 时 {@code online} 里只会有「用 {@link #connect} 主动连过的网络设备」，
     * USB 插着的那台看不到 —— 应用身份读不到 {@code /dev/bus/usb}，原因见类注释里的表。
     */
    public static Snapshot externalDevices(Context context) {
        Snapshot snapshot = new Snapshot();
        if (!isReady(context) && !FlashToolchain.ensureReady(context)) {
            return snapshot;
        }
        for (Device d : devices(context, DEFAULT_TIMEOUT_MS)) {
            // USB 序列号过 isSelfAddress 会走「主机部分 = 整串」那条，与网卡地址比不可能相等
            if (isSelfAddress(d.serial)) {
                continue;
            }
            if (d.online()) {
                snapshot.online.add(d);
            } else {
                snapshot.pending.add(d);
            }
        }
        // fastboot 也只有 root 身份跑得起来（同样要读 /dev/bus/usb 节点），
        // 无 root 时不必多起一个进程白等 15 秒。
        if (RootShell.get().isRootAvailable()) {
            snapshot.fastboot.addAll(fastbootDevices(context, DEFAULT_TIMEOUT_MS));
        }
        return snapshot;
    }

    /** 只要「能用」的那批序列号。 */
    public static List<String> externalSerials(Context context) {
        List<String> serials = new ArrayList<>();
        for (Device d : externalDevices(context).online) {
            serials.add(d.serial);
        }
        return serials;
    }

    /**
     * 通过 TCP 连接一台开着「无线调试」的手机（{@code ip:port}）。
     *
     * <p>这是无 root 时唯一能把另一台手机拉进列表的办法：走网络，不碰 USB 节点。
     * 端口由对方手机的无线调试页给出（每台每次都不一样，没有可写死的值）。
     * 首次连接需要先配对，配对在对方手机上做，配对完成后这边才能 connect。
     */
    public static boolean connect(Context context, String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }
        ShellManager.Result r = run(context, 10_000L, "connect", address.trim());
        return r.hasOutput() && r.out.toLowerCase(Locale.US).contains("connected to");
    }

    // ───────────────── 地址判定 ─────────────────

    /**
     * 这个 {@code ip:port} 是不是本机自己。
     *
     * <p>回环地址一律算；其余与网卡地址逐条比对。IPv6 的 {@code [::1]:5555}
     * 要先去方括号再比。
     */
    public static boolean isSelfAddress(String serial) {
        if (serial == null || serial.isEmpty()) {
            return false;
        }
        String host = hostOf(serial);
        if (host == null || host.isEmpty()) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host) || "::1".equals(host) || "127.0.0.1".equals(host)) {
            return true;
        }
        for (String own : ownAddresses()) {
            if (own.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /** 从 {@code 1.2.3.4:5555} / {@code [::1]:5555} 里取主机部分。 */
    private static String hostOf(String serial) {
        String s = serial.trim();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end > 0 ? s.substring(1, end) : null;
        }
        int colon = s.lastIndexOf(':');
        return colon > 0 ? s.substring(0, colon) : s;
    }

    /** 本机所有网卡地址（含回环）。读不到时只有 127.0.0.1。 */
    private static List<String> ownAddresses() {
        Set<String> result = new LinkedHashSet<>();
        result.add("127.0.0.1");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address) {
                        result.add(address.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<>(result);
    }
}
