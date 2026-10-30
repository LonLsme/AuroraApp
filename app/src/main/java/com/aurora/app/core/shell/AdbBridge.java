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
        File bin = FlashToolchain.adb(context);
        if (!bin.isFile()) {
            return ShellManager.Result.unavailable();
        }
        // 直接问 RootShell 而不是读 ShellManager 的缓存状态：首帧它还停在 DETECTING，
        // 那一刻按缓存判断会把 root 用户降级成应用身份，ad 设备表就少一半。
        if (RootShell.get().isRootAvailable()) {
            return runAsRoot(context, bin, timeoutMs, args);
        }
        return runAsApp(context, bin, timeoutMs, args);
    }

    /**
     * 以 root 身份跑：{@code HOME} 指向刷机页那个目录，端口用默认值。
     *
     * <p>走 {@link ShellManager#exec} 而不是自己起 {@code su}：那里已经维护着一条
     * 持久会话，反复 fork {@code su} 每次都要几百毫秒。
     */
    private static ShellManager.Result runAsRoot(Context context, File bin, long timeoutMs, String... args) {
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
            final InputStream in = process.getInputStream();
            Thread reader = new Thread(() -> {
                try (InputStream stream = in) {
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = stream.read(chunk)) > 0) {
                        buffer.write(chunk, 0, n);
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
            return new ShellManager.Result(timedOut ? -1 : exit, buffer.toString(), timedOut, false);
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
    }

    /** {@code adb devices} 里出现过的合法状态词。不在这张表里的行一律当噪声丢掉。 */
    private static final Set<String> KNOWN_STATES = new LinkedHashSet<>(java.util.Arrays.asList(
            "device", "offline", "unauthorized", "recovery", "sideload", "bootloader",
            "host", "no", "authorizing"));

    /** 列全部已连接设备（含无线与 USB）。 */
    public static List<Device> devices(Context context, long timeoutMs) {
        List<Device> result = new ArrayList<>();
        ShellManager.Result r = run(context, timeoutMs, "devices");
        if (!r.hasOutput()) {
            return result;
        }
        for (String raw : r.out.split("\n")) {
            String line = raw.trim();
            // 跳过 "List of devices attached"、空行、以及 "* daemon not running..." 这类噪声
            if (line.isEmpty() || line.startsWith("List of") || line.startsWith("*")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            // adb 有时把 "adb server version (41) doesn't match this client" 也打进同一路输出，
            // 那一行同样有两段以上，只能靠「状态词是否合法」把它剔掉
            if (!KNOWN_STATES.contains(parts[1].toLowerCase(Locale.US))) {
                continue;
            }
            result.add(new Device(parts[0], parts[1].toLowerCase(Locale.US)));
        }
        return result;
    }

    /**
     * 外接设备序列号（供设备页下拉切换数据源）。
     *
     * <p>排除「连到本机自己」的那条 —— 它属于本机数据源，出现在外接列表里会让
     * 同一台设备在页面上出现两次。
     *
     * <p>工具链没解压时会先解压：下拉里能不能列出外接设备，取决于内置 adb 在不在。
     * 这一步读 9.9MB assets，<b>只能在后台线程调用</b>（设备页把它放在读取线程上）。
     *
     * <p>无 root 时这里只会出现「用 {@link #connect} 主动连过的网络设备」。
     * USB 插着的那台看不到 —— 应用身份读不到 {@code /dev/bus/usb}，
     * 原因见类注释里的表。
     */
    public static List<String> externalSerials(Context context) {
        List<String> serials = new ArrayList<>();
        if (!isReady(context) && !FlashToolchain.ensureReady(context)) {
            return serials;
        }
        for (Device d : devices(context, DEFAULT_TIMEOUT_MS)) {
            if (!d.online() || isSelfAddress(d.serial)) {
                continue;
            }
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
