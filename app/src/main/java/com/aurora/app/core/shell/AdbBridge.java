package com.aurora.app.core.shell;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.aurora.app.core.flash.FlashToolchain;

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
 * 无线调试（adb over Wi-Fi）通道，用的是项目里已经内置的那份 aarch64 {@code adb}。
 *
 * <h3>为什么手机要自己连自己</h3>
 * 没有 root 时，三方应用想拿到 shell 身份只有两条路：Shizuku，或者系统的
 * 「无线调试」。后者在系统里就是一个跑 adbd 的 TCP 端口 —— 本机同样能当客户端连上去，
 * 连上以后 {@code adb shell} 拿到的就是 shell（uid 2000）身份。Shizuku 的免 root 模式
 * 走的也是这套机制，区别只是它把连接藏在了自己的服务里。
 *
 * <h3>连接要过两道门</h3>
 * <ol>
 *   <li><b>配对</b>：Android 11 起无线调试用 TLS，首次连接必须先用 6 位配对码交换证书
 *       （{@code adb pair}）。配对用的端口与连接用的端口<b>不是同一个</b>，
 *       各自通过 mDNS 广播（{@code _adb-tls-pairing._tcp} / {@code _adb-tls-connect._tcp}）。</li>
 *   <li><b>连接</b>：配对之后 {@code adb connect <ip>:<port>}。这个端口每次开关无线调试、
 *       甚至每次重启都会变，所以只能靠 mDNS 现查，不能写死。</li>
 * </ol>
 * adbkey 存在应用私有的 {@code files/adb_home/.android} 下，配对一次就长期有效。
 *
 * <h3>只认「连到自己」</h3>
 * 无线调试既可能连本机，也可能连同一局域网里的另一台手机。设备页的授权判断只接受
 * <b>对端地址等于本机某个网卡地址</b>（含回环）的连接 —— 连着别人的手机并不代表
 * 我们有权限读本机信息，把它算成「已授权」是错的。外接设备是另一回事，见
 * {@link #externalSerials}。
 */
public final class AdbBridge {

    private static final String TAG = "AdbBridge";

    /** 无线调试的连接服务名（mDNS）。 */
    private static final String SERVICE_CONNECT = "_adb-tls-connect._tcp";
    /** 无线调试的配对服务名（mDNS）。 */
    private static final String SERVICE_PAIRING = "_adb-tls-pairing._tcp";

    /** 一次 adb 调用的默认上限：起 server 那次可能慢，给足。 */
    private static final long DEFAULT_TIMEOUT_MS = 12_000L;

    /** Settings.Global 里无线调试的开关名（Android 11+）。 */
    private static final String SETTING_WIFI_ADB = "adb_wifi_enabled";

    private AdbBridge() {
    }

    // ───────────────── 环境 ─────────────────

    /** 内置 adb 的 HOME/TMPDIR：应用私有目录，非 root 下唯一能写 adbkey 的地方。 */
    private static File homeDir(Context context) {
        File dir = new File(context.getFilesDir(), "adb_home");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** 工具链是否已解压（未解压时任何 adb 调用都无从谈起）。 */
    public static boolean isReady(Context context) {
        return FlashToolchain.isReady(context);
    }

    /**
     * 系统的「无线调试」开关是否打开。
     *
     * <p>这个 key 属于 {@code Settings.Global}，个别 ROM 会把读取拦掉（抛异常）；
     * 读不到时返回 false —— 与其瞎猜，不如让调用方当成「没开」。
     */
    public static boolean wirelessSettingsEnabled(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), SETTING_WIFI_ADB, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    // ───────────────── 执行 ─────────────────

    /** 跑一条 adb 子命令（{@code args} 不含 adb 自身路径）。 */
    public static ShellManager.Result run(Context context, long timeoutMs, String... args) {
        File bin = FlashToolchain.adb(context);
        if (!bin.isFile()) {
            return ShellManager.Result.unavailable();
        }
        List<String> command = new ArrayList<>(args.length + 1);
        command.add(bin.getAbsolutePath());
        Collections.addAll(command, args);

        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            File home = homeDir(context);
            builder.directory(home);
            // adb 会在 HOME 下建 .android 存 adbkey；TMPDIR 同理。非 root 只能落在应用私有目录。
            builder.environment().put("HOME", home.getAbsolutePath());
            builder.environment().put("TMPDIR", home.getAbsolutePath());
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
     * 找到「连到本机自己」的无线调试地址；没有就返回 {@code null}。
     *
     * <p>顺序：先看 {@code adb devices} 里是不是已经连着（上次配对留下的），
     * 没有再靠 mDNS 发现端口并 {@code adb connect}。工具链没解压时会先解压 ——
     * 这一步要读 9.9MB assets，必须在后台线程调用。
     */
    public static String findSelfWirelessSerial(Context context) {
        if (!wirelessSettingsEnabled(context)) {
            return null;
        }
        if (!isReady(context)) {
            Log.i(TAG, "工具链未就绪，先解压（无线调试需要内置 adb）");
            if (!FlashToolchain.ensureReady(context)) {
                return null;
            }
        }

        for (Device d : devices(context, 5000L)) {
            if (d.online() && isSelfAddress(d.serial)) {
                return d.serial;
            }
        }

        for (String address : mdnsAddresses(context, SERVICE_CONNECT)) {
            if (!isSelfAddress(address)) {
                continue;
            }
            run(context, 8000L, "connect", address);
            for (Device d : devices(context, 5000L)) {
                if (address.equals(d.serial) && d.online()) {
                    return d.serial;
                }
            }
        }
        return null;
    }

    /** mDNS 上广播出来的配对端口（{@code ip:port}）；没有返回 {@code null}。 */
    public static String findPairingAddress(Context context) {
        List<String> addresses = mdnsAddresses(context, SERVICE_PAIRING);
        for (String address : addresses) {
            if (isSelfAddress(address)) {
                return address;
            }
        }
        return addresses.isEmpty() ? null : addresses.get(0);
    }

    /** 用 6 位配对码完成配对。 */
    public static boolean pair(Context context, String address, String code) {
        if (address == null || code == null) {
            return false;
        }
        ShellManager.Result r = run(context, 15_000L, "pair", address, code.trim());
        // 成功时 adb 输出 "Successfully paired to <addr>"
        return r.hasOutput() && r.out.toLowerCase(Locale.US).contains("successfully paired");
    }

    /** 主动 connect（配对完成后调用）。 */
    public static boolean connect(Context context, String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        ShellManager.Result r = run(context, 8000L, "connect", address);
        return r.hasOutput() && r.out.toLowerCase(Locale.US).contains("connected to");
    }

    /**
     * 解析 {@code adb mdns services}，取出指定服务对应的 {@code ip:port}。
     *
     * <p>输出形如（列之间是制表符）：
     * <pre>
     * adb-R5CT30XXXX-YYYYYY  _adb-tls-connect._tcp  192.168.1.7:39301
     * </pre>
     * 不同版本列数会变（有时前面还多一列实例名），所以按「含服务名的那一行的最后一个
     * 带冒号的字段」取地址，不做位置假设。
     */
    public static List<String> mdnsAddresses(Context context, String service) {
        Set<String> addresses = new LinkedHashSet<>();
        ShellManager.Result r = run(context, 6000L, "mdns", "services");
        if (!r.hasOutput()) {
            return new ArrayList<>(addresses);
        }
        for (String raw : r.out.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || !line.contains(service)) {
                continue;
            }
            String[] parts = line.split("\\s+");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = parts[i].trim();
                if (candidate.contains(":") && !candidate.startsWith("_")) {
                    addresses.add(candidate);
                    break;
                }
            }
        }
        return new ArrayList<>(addresses);
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
