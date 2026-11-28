package com.aurora.app.core.util;

import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * /proc/cpuinfo、cpufreq 等 sysfs 读取。
 */
public final class CpuSysFiles {

    private CpuSysFiles() {
    }

    /** 示例： aarch64 · 8 核 · Snapdragon ... */
    public static String buildCpuSummaryLine() {
        int cores = Runtime.getRuntime().availableProcessors();
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        String hardware = readCpuInfoField("Hardware");
        if (hardware == null) {
            hardware = Build.HARDWARE;
        }
        return abi + " · " + cores + " 核 · " + hardware;
    }

    private static String readCpuInfoField(String keyPrefix) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.regionMatches(true, 0, keyPrefix, 0, keyPrefix.length())) {
                    int idx = t.indexOf(':');
                    if (idx >= 0 && idx + 1 < t.length()) {
                        return t.substring(idx + 1).trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** scaling_cur_freq，单位 kHz → GHz 字符串 */
    public static String readCpu0CurFreqGHz() {
        long khz = readCpuCurFreqKhz(0);
        if (khz <= 0) {
            return "—";
        }
        return String.format(Locale.getDefault(), "%.2f GHz", khz / 1_000_000d);
    }

    /**
     * 核数缓存。
     *
     * <p>运行期不会变：热插拔只改 {@code cpuN/online} 的<b>值</b>，不会增删 {@code cpuN}
     * 目录。而设备页每秒都要问一次 —— 一次 {@code listFiles()} 要走十几个目录项的 stat，
     * 再对每个名字跑一次 {@code matches("\\d+")}（{@link String#matches} 每次都编译 Pattern）。
     * 缓存之后这些在进程生命周期里只做一次。
     */
    private static volatile int sCpuCount = -1;

    /**
     * 从 sysfs 统计逻辑 CPU 数量（cpu0…cpuN-1），失败时用 {@link Runtime#availableProcessors()}。
     */
    public static int getSysfsCpuCount() {
        int cached = sCpuCount;
        if (cached > 0) {
            return cached;
        }
        int result = detectCpuCount();
        sCpuCount = result;
        return result;
    }

    private static int detectCpuCount() {
        File dir = new File("/sys/devices/system/cpu");
        File[] files = dir.listFiles();
        int maxIndex = -1;
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (!name.startsWith("cpu")) {
                    continue;
                }
                String tail = name.substring(3);
                if (!isAllDigits(tail)) {
                    continue;
                }
                try {
                    maxIndex = Math.max(maxIndex, Integer.parseInt(tail));
                } catch (NumberFormatException ignored) {
                    // 名字异常长之类的极端情况，跳过这一个即可
                }
            }
        }
        if (maxIndex >= 0) {
            return maxIndex + 1;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** 纯数字判断：替代 {@code matches("\\d+")}，不编译正则、不产生临时对象。 */
    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isCpuDirPresent(int cpuIndex) {
        return new File("/sys/devices/system/cpu/cpu" + cpuIndex).isDirectory();
    }

    /**
     * 若存在 {@code .../cpuN/online} 则读之；否则认为在线（只要 cpu 目录存在）。
     */
    public static boolean isCpuOnline(int cpuIndex) {
        File online = new File("/sys/devices/system/cpu/cpu" + cpuIndex + "/online");
        if (!online.isFile()) {
            return isCpuDirPresent(cpuIndex);
        }
        try (BufferedReader br = new BufferedReader(new FileReader(online))) {
            String line = br.readLine();
            if (line == null) {
                return true;
            }
            return "1".equals(line.trim());
        } catch (Exception ignored) {
            return true;
        }
    }

    /**
     * 硬件/策略最小频率 kHz（cpuinfo_min_freq，失败则试 scaling_min_freq）。
     */
    public static long readCpuMinFreqKhz(int cpuIndex) {
        // 不再先 stat 一次目录：读文件失败与目录不存在在这条链路上是同一个结果（-1），
        // 多一次 {@code isDirectory()} 只是给每秒一次的轮询白加一次系统调用。
        String base = "/sys/devices/system/cpu/cpu" + cpuIndex + "/cpufreq/";
        Long khz = readLongFile(base + "cpuinfo_min_freq");
        if (khz != null && khz > 0) {
            return khz;
        }
        khz = readLongFile(base + "scaling_min_freq");
        if (khz != null && khz > 0) {
            return khz;
        }
        return -1L;
    }

    /**
     * 硬件/策略最大频率 kHz（cpuinfo_max_freq，失败则试 scaling_max_freq）。
     */
    public static long readCpuMaxFreqKhz(int cpuIndex) {
        // 同 readCpuMinFreqKhz：省掉那次目录 stat
        String base = "/sys/devices/system/cpu/cpu" + cpuIndex + "/cpufreq/";
        Long khz = readLongFile(base + "cpuinfo_max_freq");
        if (khz != null && khz > 0) {
            return khz;
        }
        khz = readLongFile(base + "scaling_max_freq");
        if (khz != null && khz > 0) {
            return khz;
        }
        return -1L;
    }

    /**
     * 策略最小频率 kHz：<b>优先 {@code scaling_min_freq}</b>。
     *
     * <p>与 {@link #readCpuMinFreqKhz(int)} 的区别是语义：那个优先硬件极限
     * （{@code cpuinfo_min_freq}，只读、永远不变），适合「这台机器大概能在什么范围调频」；
     * 这个优先策略值（{@code scaling_min_freq}，可写），适合「用户当前把它设成了多少」。
     * CPU 控制页改完立刻回读，必须用后者 —— 否则写完 scaling_* 再读 cpuinfo_*，
     * 滑杆会跳回硬件极限，看起来像「设置成功但没生效」。
     */
    public static long readCpuScalingMinFreqKhz(int cpuIndex) {
        String base = "/sys/devices/system/cpu/cpu" + cpuIndex + "/cpufreq/";
        Long khz = readLongFile(base + "scaling_min_freq");
        if (khz != null && khz > 0) {
            return khz;
        }
        return readCpuMinFreqKhz(cpuIndex);
    }

    /** 策略最大频率 kHz：优先 {@code scaling_max_freq}，语义同 {@link #readCpuScalingMinFreqKhz(int)}。 */
    public static long readCpuScalingMaxFreqKhz(int cpuIndex) {
        String base = "/sys/devices/system/cpu/cpu" + cpuIndex + "/cpufreq/";
        Long khz = readLongFile(base + "scaling_max_freq");
        if (khz != null && khz > 0) {
            return khz;
        }
        return readCpuMaxFreqKhz(cpuIndex);
    }

    /**
     * 展示用：{@code 364~2265MHz}；不可用返回 {@code —}。
     */
    public static String formatCpuFreqRangeMhzShort(int cpuIndex) {
        if (!isCpuOnline(cpuIndex)) {
            return "—";
        }
        long minK = readCpuMinFreqKhz(cpuIndex);
        long maxK = readCpuMaxFreqKhz(cpuIndex);
        if (minK <= 0 || maxK <= 0) {
            return "—";
        }
        Locale loc = Locale.getDefault();
        double minM = minK / 1000.0;
        double maxM = maxK / 1000.0;
        return String.format(loc, "%.0f~%.0fMHz", minM, maxM);
    }

    /** 当前频率 kHz；离线或读失败返回 {@code -1} */
    public static long readCpuCurFreqKhz(int cpuIndex) {
        if (!isCpuDirPresent(cpuIndex)) {
            return -1L;
        }
        String base = "/sys/devices/system/cpu/cpu" + cpuIndex + "/cpufreq/";
        Long khz = readLongFile(base + "scaling_cur_freq");
        if (khz != null && khz > 0) {
            return khz;
        }
        khz = readLongFile(base + "cpuinfo_cur_freq");
        if (khz != null && khz > 0) {
            return khz;
        }
        return -1L;
    }

    /**
     * 多行文本：每核一行 {@code CPUn  xxxx.x MHz} 或离线/不可用；供设备页每秒刷新。
     */
    public static String buildAllCoresCurFreqMhzBlock() {
        int n = getSysfsCpuCount();
        Locale loc = Locale.getDefault();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (!isCpuDirPresent(i)) {
                continue;
            }
            if (!isCpuOnline(i)) {
                sb.append(String.format(loc, "CPU%d  离线\n", i));
                continue;
            }
            long khz = readCpuCurFreqKhz(i);
            if (khz <= 0) {
                sb.append(String.format(loc, "CPU%d  —\n", i));
            } else {
                double mhz = khz / 1000.0;
                if (mhz >= 1000d) {
                    sb.append(String.format(loc, "CPU%d  %.0f MHz\n", i, mhz));
                } else {
                    sb.append(String.format(loc, "CPU%d  %.1f MHz\n", i, mhz));
                }
            }
        }
        if (sb.length() == 0) {
            return "—";
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static Long readLongFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line != null) {
                return Long.parseLong(line.trim());
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
