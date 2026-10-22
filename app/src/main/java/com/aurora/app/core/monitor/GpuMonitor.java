package com.aurora.app.core.monitor;

import com.aurora.app.core.util.NodeReader;

/**
 * GPU 使用率与频率数据源。
 *
 * <p>不同 SoC 暴露的 sysfs 节点路径、内容格式、单位都不一样，无法用单一路径覆盖。
 * 这里按「候选路径链 + 首次探测缓存」的方式处理：
 * <ul>
 *   <li>按命中率从高到低排列候选节点，逐个尝试，第一个能读出有效值的即命中；</li>
 *   <li>命中后把路径缓存下来，后续采集直接读，不再重复遍历（悬浮窗每 500ms～2s 采一次，
 *       每次遍历 8 个文件是白白浪费 IO）；</li>
 *   <li>每个候选路径的单位是固定的，因此单位随路径一起记录，
 *       避免靠数值量级去猜（见 {@link FreqUnit}）。</li>
 * </ul>
 *
 * <p>覆盖情况：
 * <ul>
 *   <li><b>高通骁龙</b>：kgsl 系列节点；</li>
 *   <li><b>麒麟</b>：{@code /sys/class/devfreq/gpufreq/mali_ondemand/utilisation}；</li>
 *   <li><b>联发科天玑</b>：ged hal 节点（部分机型路径在 debug 下）；</li>
 *   <li><b>其他 Mali / devfreq</b>：通用 {@code cur_freq} 路径。</li>
 * </ul>
 *
 * <p>读取方式说明：普通读取优先（覆盖绝大多数节点），
 * {@code /sys/kernel/debug/} 下的节点需要 root 权限——普通读失败后自动经
 * {@link com.aurora.app.core.util.NodeReader} 走 su cat 兜底（设备已 root 时可读到，
 * 未 root 则回退下一候选节点，不会崩溃）。
 */
public final class GpuMonitor {

    // ─── 频率单位 ───

    /** 原始数值以 Hz 为单位（devfreq 框架惯例）。 */
    private static final int UNIT_HZ = 0;
    /** 原始数值以 kHz 为单位。 */
    private static final int UNIT_KHZ = 1;
    /** 内容为多字段，取第二个字段，单位 kHz（天玑 ged hal 的格式）。 */
    private static final int UNIT_SECOND_FIELD_KHZ = 2;

    // ─── 候选节点 ───

    /**
     * 使用率候选节点，按命中率排序。
     * 这些节点的内容大多是单个数字（可能带 {@code %} 后缀），
     * 天玑的 ged 节点是 "{@code 45 xxx}" 多字段格式，统一由 {@link #parsePercent} 处理。
     */
    private static final String[] LOAD_PATHS = {
            // 高通骁龙
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/kgsl/kgsl-3d0/gpuload",
            "/sys/kernel/gpu/gpu_busy",
            // 麒麟（Mali）
            "/sys/class/devfreq/gpufreq/mali_ondemand/utilisation",
            // 联发科天玑
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/kernel/debug/ged/hal/gpu_utilization",
    };

    /**
     * 骁龙专属的 busy/total 计数节点，内容为 "{@code busy total}" 两个数字。
     * 格式特殊，单独处理，作为上面单值节点都失败后的兜底。
     */
    private static final String LOAD_PATH_GPUBUSY = "/sys/class/kgsl/kgsl-3d0/gpubusy";

    /** 频率候选节点。 */
    private static final String[] FREQ_PATHS = {
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",      // 骁龙
            "/sys/class/kgsl/kgsl-3d0/gpuclk",                // 骁龙（旧）
            "/sys/class/devfreq/gpufreq/cur_freq",            // Mali / 麒麟
            "/sys/kernel/gpu/gpu_clock",                      // 旧骁龙
            "/sys/kernel/ged/hal/current_freqency",           // 天玑 1100 / 1200
            "/sys/kernel/debug/ged/hal/current_freqency",     // 天玑 820
    };

    /** 与 {@link #FREQ_PATHS} 一一对应的单位。 */
    private static final int[] FREQ_UNITS = {
            UNIT_HZ,
            UNIT_KHZ,
            UNIT_HZ,
            UNIT_KHZ,
            UNIT_SECOND_FIELD_KHZ,
            UNIT_SECOND_FIELD_KHZ,
    };

    // ─── 探测缓存 ───
    // null 表示尚未探测；命中为可用路径字符串；空串表示探测过但未命中。
    // 空串会定期（NEGATIVE_CACHE_TTL_MS）重试——首次探测时 root 兜底可能因
    // 授权/节流未命中，权限就绪后应能补上，避免 GPU 永久 "--"。

    private static final long NEGATIVE_CACHE_TTL_MS = 30_000L;
    private static volatile long loadPathProbedAtMs = 0L;
    private static volatile long freqPathProbedAtMs = 0L;

    private static volatile String cachedLoadPath;
    private static volatile String cachedFreqPath;
    private static volatile int cachedFreqUnit = UNIT_HZ;

    private GpuMonitor() {
    }

    /**
     * GPU 使用率（0-100）；全部节点都读不到时返回 -1，UI 显示 "--"。
     */
    public static int readGpuUsagePercent() {
        int percent = readPercentFromCachedPath();
        if (percent >= 0) {
            return percent;
        }
        // 单值节点全部失败，再试骁龙的 busy/total 计数节点
        return readGpuBusyRatio();
    }

    /**
     * GPU 当前频率（MHz）；读取失败返回 0。
     *
     * <p>单位由命中的路径决定（见 {@link #FREQ_UNITS}），
     * 因此不再需要靠数值量级猜测，旧实现里那个「真机校准」的 TODO 随之消除。
     */
    public static int readGpuFreqMhz() {
        String path = resolveFreqPath();
        if (path.isEmpty()) {
            return 0;
        }
        long raw = parseFreqRaw(readFirstLine(path), cachedFreqUnit);
        if (raw <= 0) {
            return 0;
        }
        return cachedFreqUnit == UNIT_HZ
                ? (int) (raw / 1_000_000L)   // Hz → MHz
                : (int) (raw / 1000L);       // kHz → MHz
    }

    // ─── 使用率读取 ───

    private static int readPercentFromCachedPath() {
        String path = resolveLoadPath();
        if (path.isEmpty()) {
            return -1;
        }
        return parsePercent(readFirstLine(path));
    }

    /** 解析单个百分比数值：跳过 %{@code %} 后缀与多余字段，并做 0-100 范围校验。 */
    private static int parsePercent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        String[] parts = raw.trim().replace("%", "").split("\\s+");
        if (parts.length == 0) {
            return -1;
        }
        try {
            int value = Integer.parseInt(parts[0]);
            // 个别节点会吐出 0 或明显越界的值，视为无效好过显示错误数字
            return (value > 0 && value <= 100) ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 骁龙 gpubusy：内容为 "busy total"，占用率 = busy / total。 */
    private static int readGpuBusyRatio() {
        String line = readFirstLine(LOAD_PATH_GPUBUSY);
        if (line == null || line.isEmpty()) {
            return -1;
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) {
            return -1;
        }
        try {
            long busy = Long.parseLong(parts[0]);
            long total = Long.parseLong(parts[1]);
            if (total <= 0) {
                return -1;
            }
            int percent = (int) Math.min(100, busy * 100 / total);
            return percent > 0 ? percent : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ─── 频率读取 ───

    /** 按节点单位取出频率原始值；天玑节点取第二个字段。 */
    private static long parseFreqRaw(String line, int unit) {
        if (line == null || line.isEmpty()) {
            return 0;
        }
        String[] parts = line.trim().split("\\s+");
        String token;
        if (unit == UNIT_SECOND_FIELD_KHZ) {
            if (parts.length < 2) {
                return 0;
            }
            token = parts[1];
        } else {
            token = parts[0];
        }
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ─── 路径探测（带缓存） ───

    /**
     * 确定可用的使用率节点。
     * 探测时会实际读一次，只有能解析出有效值才算命中——
     * 部分节点虽然存在但无读权限（尤其 {@code /sys/kernel/debug/} 下那些），
     * 只判断 exists() 会留下一个永远读不到值的路径。
     */
    private static String resolveLoadPath() {
        String cached = cachedLoadPath;
        if (cached != null
                && (!cached.isEmpty()
                || System.currentTimeMillis() - loadPathProbedAtMs < NEGATIVE_CACHE_TTL_MS)) {
            return cached;
        }
        synchronized (GpuMonitor.class) {
            cached = cachedLoadPath;
            if (cached == null
                    || (!cached.isEmpty()
                    || System.currentTimeMillis() - loadPathProbedAtMs >= NEGATIVE_CACHE_TTL_MS)) {
                String found = "";
                for (String path : LOAD_PATHS) {
                    // 不做 exists() 前置检查：Android 12+ 下这些路径连 stat 都可能被拒，
                    // exists() 返回 false 会漏掉「真实存在但需 root 才能读」的节点。
                    // 直接读，读不到自然跳过（NodeReader 内部有失败退避，不会反复卡超时）。
                    if (parsePercent(readFirstLine(path)) > 0) {
                        found = path;
                        break;
                    }
                }
                cachedLoadPath = found;
                loadPathProbedAtMs = System.currentTimeMillis();
            }
            return cachedLoadPath;
        }
    }

    /** 确定可用的频率节点，同时记住它的单位。 */
    private static String resolveFreqPath() {
        String cached = cachedFreqPath;
        if (cached != null
                && (!cached.isEmpty()
                || System.currentTimeMillis() - freqPathProbedAtMs < NEGATIVE_CACHE_TTL_MS)) {
            return cached;
        }
        synchronized (GpuMonitor.class) {
            cached = cachedFreqPath;
            if (cached == null
                    || (!cached.isEmpty()
                    || System.currentTimeMillis() - freqPathProbedAtMs >= NEGATIVE_CACHE_TTL_MS)) {
                String found = "";
                for (int i = 0; i < FREQ_PATHS.length; i++) {
                    String path = FREQ_PATHS[i];
                    // 同 LOAD_PATHS：跳过 exists()，直接尝试读取
                    int unit = FREQ_UNITS[i];
                    if (parseFreqRaw(readFirstLine(path), unit) > 0) {
                        found = path;
                        cachedFreqUnit = unit;
                        break;
                    }
                }
                cachedFreqPath = found;
                freqPathProbedAtMs = System.currentTimeMillis();
            }
            return cachedFreqPath;
        }
    }

    // ─── 基础 IO ───

    /** 读取文本文件第一行；任何异常（无权限、不存在、格式异常）都返回空串。 */
    private static String readFirstLine(String path) {
        // 普通读优先；被 SELinux 拒绝时自动 root cat 兜底（NodeReader 内置退避）。
        // 候选路径全是系统节点（/sys/class/kgsl、/sys/class/devfreq、/sys/kernel/…），
        // Android 12+ 下都可能 EACCES——原先只对 /sys/kernel/ 前缀放行 root，
        // 导致骁龙的 /sys/class/kgsl/kgsl-3d0/* 永远读不到、GPU 恒为 "--"。
        String v = NodeReader.readFirstLine(path, true);
        return v == null ? "" : v;
    }
}
