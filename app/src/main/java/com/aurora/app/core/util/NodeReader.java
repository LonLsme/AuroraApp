package com.aurora.app.core.util;

import com.aurora.app.core.root.RootShell;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * sysfs 节点读取器（普通读优先，root 兜底）。
 *
 * <p>部分节点（典型如 {@code /sys/kernel/debug/...}）对普通应用进程是 EACCES，
 * 直接 {@code FileReader} 读不到，但设备已 root 时可通过 su cat 读取。
 * 本类把两件事封装成一个入口，并做好三件事防止拖垮采集线程：
 * <ul>
 *   <li><b>命中缓存</b>：某路径曾用 root 读到值，记入 {@link #ROOT_PATHS}，
 *       后续直接 root cat，不再重复走「普通读失败」的弯路；</li>
 *   <li><b>探测节流</b>：普通读失败后尝试 root 的间隔不小于
 *       {@link #PROBE_COOLDOWN_MS}，避免每秒对不存在/无权限节点反复探测；</li>
 *   <li><b>失败退避</b>：root 会话不可用（无 root/未授权）时 15 秒内不再尝试，
 *       否则 {@code RootShell.execOut} 会阻塞采集线程等待授权/超时。</li>
 * </ul>
 * 线程安全：采集在单线程串行执行，集合操作仍加同步兜底。
 */
public final class NodeReader {

    /**
     * 普通读失败后，尝试 root 探测的最小间隔。
     *
     * <p>取 0（即不额外限流）：探测失败的防抖由 {@link #FAIL_BACKOFF_MS} 承担——
     * 一次失败就让接下来 15 秒内不再发起 root 探测，已经足够防止「无 root 设备上
     * 反复卡超时」。若这里再设正数，会带来一个严重副作用：
     * 首次遍历几十个 thermal_zone 建索引时，每 2 秒才允许探测一个新路径，
     * 40 个热区需要 80 秒才能建完，期间温度一直显示 "--"。
     */
    private static final long PROBE_COOLDOWN_MS = 0L;
    /** root 探测/读取失败后的退避时间（无 root 时避免反复卡超时）。 */
    private static final long FAIL_BACKOFF_MS = 15_000L;
    /** root 路径缓存上限：超过后清空重建。 */
    private static final int ROOT_PATHS_LIMIT = 32;

    /** 已知需要 root 才能读的路径（普通读失败、root cat 成功过）。 */
    private static final Set<String> ROOT_PATHS = new HashSet<>();

    private static volatile long lastProbeAtMs = 0L;
    private static volatile long lastFailAtMs = 0L;

    private NodeReader() {
    }

    /**
     * 读取文本文件第一行（trim 后）；读不到返回 null。
     * 会优先普通读，失败时按节流规则尝试 root cat。
     *
     * @param path 绝对路径（仅限应用白名单内的静态系统路径，无注入面）
     * @param rootAllowed 该路径是否可能位于需要 root 的目录（如 /sys/kernel/debug）
     */
    public static String readFirstLine(String path, boolean rootAllowed) {
        // 已确认需要 root 的路径：直接 root cat（值变化实时），失败进入退避
        if (containsPath(path)) {
            String value = readRoot(path);
            if (value == null) {
                lastFailAtMs = System.currentTimeMillis();
            }
            return value;
        }
        String plain = readPlain(path);
        if (plain != null) {
            return plain;
        }
        if (!rootAllowed) {
            return null;
        }
        // 普通读失败：按节流 + 退避规则尝试一次 root 探测
        long now = System.currentTimeMillis();
        boolean cooldownPassed = now - lastProbeAtMs >= PROBE_COOLDOWN_MS;
        boolean backoffPassed = now - lastFailAtMs >= FAIL_BACKOFF_MS;
        if (!cooldownPassed || !backoffPassed) {
            return null;
        }
        lastProbeAtMs = now;
        String value = readRoot(path);
        if (value != null) {
            rememberRootPath(path);
            return value;
        }
        lastFailAtMs = System.currentTimeMillis();
        return null;
    }

    /**
     * 读取文本文件的全部行（trim 后、去空行）；读不到返回空列表。
     *
     * <p>与 {@link #readFirstLine} 共享同一套「root 路径缓存 + 探测节流 + 失败退避」，
     * 用于 {@code /proc/stat} 这类<b>多行</b>节点：
     * Android 12 起 SELinux 把 {@code /proc/stat}、{@code /proc/loadavg} 等
     * 从 untrusted_app 的可读集合里剔除，普通读必然 EACCES，
     * 只能经 su 读取（设备已 root 时）。
     *
     * @param path 绝对路径（仅限应用白名单内的静态系统路径，无注入面）
     * @param rootAllowed 普通读失败后是否允许尝试 root cat
     */
    public static List<String> readAllLines(String path, boolean rootAllowed) {
        if (containsPath(path)) {
            List<String> value = readRootLines(path);
            if (value.isEmpty()) {
                lastFailAtMs = System.currentTimeMillis();
            }
            return value;
        }
        List<String> plain = readPlainLines(path);
        if (!plain.isEmpty()) {
            return plain;
        }
        if (!rootAllowed) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        boolean cooldownPassed = now - lastProbeAtMs >= PROBE_COOLDOWN_MS;
        boolean backoffPassed = now - lastFailAtMs >= FAIL_BACKOFF_MS;
        if (!cooldownPassed || !backoffPassed) {
            return Collections.emptyList();
        }
        lastProbeAtMs = now;
        List<String> value = readRootLines(path);
        if (!value.isEmpty()) {
            rememberRootPath(path);
            return value;
        }
        lastFailAtMs = System.currentTimeMillis();
        return Collections.emptyList();
    }

    /** 已确认走 root 读取的路径集合（只读快照）。 */
    public static Set<String> knownRootPaths() {
        synchronized (ROOT_PATHS) {
            return Collections.unmodifiableSet(new HashSet<>(ROOT_PATHS));
        }
    }

    // ─── 内部 ───

    /** 普通文件读取（trim 后返回，空内容视为读不到）。 */
    private static String readPlain(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line == null) {
                return null;
            }
            String v = line.trim();
            return v.isEmpty() ? null : v;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** root cat 读取（trim 后返回；失败/空返回 null）。 */
    private static String readRoot(String path) {
        String out = RootShell.get().execOut("cat " + RootShell.escape(path));
        if (out == null || out.isEmpty()) {
            return null;
        }
        String v = out.trim();
        return v.isEmpty() ? null : v;
    }

    /** 普通文件全部行读取（trim + 去空行）；读不到返回空列表。 */
    private static List<String> readPlainLines(String path) {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                String v = line.trim();
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return out;
    }

    /**
     * root cat 读取全部行；失败返回空列表。
     * 直接复用 {@link RootShell.Result#lines()} 的「按行拆分 + trim + 去空行」。
     */
    private static List<String> readRootLines(String path) {
        RootShell.Result result = RootShell.get().exec("cat " + RootShell.escape(path));
        if (result == null || !result.isSuccess()) {
            return Collections.emptyList();
        }
        return result.lines();
    }

    private static void rememberRootPath(String path) {
        synchronized (ROOT_PATHS) {
            if (ROOT_PATHS.size() >= ROOT_PATHS_LIMIT) {
                ROOT_PATHS.clear();
            }
            ROOT_PATHS.add(path);
        }
    }

    private static boolean containsPath(String path) {
        synchronized (ROOT_PATHS) {
            return ROOT_PATHS.contains(path);
        }
    }
}
