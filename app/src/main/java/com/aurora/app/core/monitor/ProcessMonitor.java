package com.aurora.app.core.monitor;

import com.aurora.app.core.root.RootShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程监视器数据源：统计每个进程的 CPU 时间（utime+stime）、内存（RSS/Swap）、
 * 归属用户、命令行等信息，通过与上一次采样的差值估算各进程 CPU 占比。
 *
 * <h3>为什么要走 root（本次修复的核心）</h3>
 * 原实现纯 {@code /proc} 读取，注释里写着「内核默认 hidepid=0 时可读」。这在
 * Android 12+ 已经不成立——SELinux 的 {@code proc} 规则不允许 untrusted_app
 * 读取<b>其他 uid</b> 进程的 {@code /proc/[pid]/stat}，实测 {@code Permission denied}。
 * 后果是「能列出 300 多个 pid 目录，但每一个都解析失败」，
 * 进程监视器因此只能看到自己，最终显示 "--"。
 *
 * <p>星野Scene（HoshinoScene）的做法可作对照：它把 {@code top} 命令交给自己的
 * root 守护进程执行，由 root 身份读 /proc 后返回结构化文本。本类采用同一思路，
 * 但用一条 shell 命令一次性 dump，而不是逐 pid 起进程：
 * <pre>
 *   awk '&#123;print "S|" FILENAME "|" $0&#125;' /proc/[0-9]*&#47;stat   # 全部进程的 stat
 *   ps -A -w -o PID,USER,ARGS                              # 归属用户 + 完整命令行
 * </pre>
 * 只有<b>一次</b> root 往返，且 awk/ps 各只 fork 一次，耗时与进程数无关地保持在几十毫秒级；
 * 若改成「每个 pid 一次 root cat」，300 个进程就是 300 次往返，完全不可接受。
 *
 * <h3>取值策略</h3>
 * 先普通读；若解析到的进程数明显偏少（说明被 SELinux 拦了），改用 root dump，
 * 并把结果记为「本设备必须走 root」，后续刷新直接走 root，不再做无用的普通读尝试。
 *
 * <h3>解析规则（Linux /proc/[pid]/stat）</h3>
 * 第 1 段为 pid，第 2 段为括在 () 中的 comm（可能含空格/括号），
 * 括号后第 3 段起为 state、ppid、...，其中 utime 是第 14 字段、stime 是第 15 字段、
 * rss（单位：页）是第 24 字段。因此从括号后的片段索引 11/12/21 即为
 * utime / stime / rss，索引 0 为进程状态字符。
 */
public final class ProcessMonitor {

    /**
     * 普通读至少要解析出这么多进程，才认为没被 SELinux 拦截。
     * 任何真实 Android 设备都有 100+ 进程；只解析出个位数必然是权限问题。
     */
    private static final int MIN_DIRECT_PROCESSES = 20;

    /**
     * root dump 的超时（毫秒）。
     *
     * <p>取值刻意保守：{@code OverlayWindow.DATA_EXECUTOR} 是 <b>6 个悬浮窗共享的单线程池</b>，
     * 任何一次采集卡住都会把它后面的所有监视器一起堵住。一次 dump 正常只需几十毫秒，
     * 这里给 6 秒已是极宽裕的兜底 —— 超过即判定失败，宁可这一帧没数据，
     * 也不能让一个进程采集把整批监视器的刷新拖垮。
     */
    private static final long DUMP_TIMEOUT_MS = 6_000L;

    /** root dump 命令：S| 前缀为 stat 行，P| 单独一行作为分节标记，其后为 ps 输出。 */
    private static final String ROOT_DUMP_CMD =
            "awk '{print \"S|\" FILENAME \"|\" $0}' /proc/[0-9]*/stat 2>/dev/null; "
                    + "echo 'P|'; ps -A -w -o PID,USER,ARGS 2>/dev/null";

    /** pid -> 上一次采样到的 CPU 时间（tick），用于计算差值。 */
    private final Map<Integer, Long> prevTimes = new HashMap<>();

    /** 本设备是否必须经 root 才能读到全量进程（一旦确认就不再走普通读）。 */
    private boolean preferRoot;

    /**
     * 单个进程的采样结果（含归属应用信息）。
     *
     * <p>pid/name/pkg/percent 为只读主字段（悬浮窗依赖）；内存、用户、命令、
     * 状态等为可变增强字段（进程管理页/详情使用）。
     */
    public static final class ProcInfo {
        public final int pid;
        /**
         * 展示用进程名：应用进程为包名（如 com.android.systemui），
         * 其余进程为内核 comm（如 init / kworker/...）。
         */
        public final String name;
        /** 归属应用包名；非应用进程（内核线程/原生守护进程）为 null。 */
        public final String pkg;
        /** 本次采样周期内的 CPU 占用百分比（0-99）。 */
        public final int percent;

        /** RSS 物理内存（KB）。 */
        public long resKb;
        /** Swap 占用（KB，读 /proc/pid/status 的 VmSwap，部分内核为 0）。 */
        public long swapKb;
        /** 归属用户（root/system/shell/u0_aXXX/uidN）。 */
        public String user;
        /** 命令行第一段（应用进程为包名，原生进程为可执行路径）。 */
        public String command;
        /** 完整命令行（参数以空格拼接）。 */
        public String cmdline;
        /** 进程状态字符（R/S/D/Z/T 等）。 */
        public String state;

        // ─── 以下字段仅 readDetail() 填充（进程详情页） ───
        /** 所属 cpuset（如 /system/foreground）。 */
        public String cpuset;
        /** cgroup 路径。 */
        public String cgroup;
        /** oom_adj（-17..15，旧接口）。 */
        public String oomAdj;
        /** oom_score_adj（-1000..1000，新接口）。 */
        public String oomScoreAdj;
        /** oom_score（当前评分）。 */
        public String oomScore;

        ProcInfo(int pid, String name, String pkg, int percent) {
            this.pid = pid;
            this.name = name;
            this.pkg = pkg;
            this.percent = percent;
        }
    }

    /**
     * 进程原始采样：CPU 时间、内存、归属等（仅供内部统计）。
     * 包级可见（非 private）是为了让 javac 单测能直接喂真实 dump 校验解析结果。
     */
    static final class ProcEntry {
        final int pid;
        final String name;
        final String pkg;
        final long cpuTicks;
        final long resKb;
        final long swapKb;
        final String user;
        final String command;
        final String cmdline;
        final String state;

        ProcEntry(int pid, String name, String pkg, long cpuTicks, long resKb, long swapKb,
                  String user, String command, String cmdline, String state) {
            this.pid = pid;
            this.name = name;
            this.pkg = pkg;
            this.cpuTicks = cpuTicks;
            this.resKb = resKb;
            this.swapKb = swapKb;
            this.user = user;
            this.command = command;
            this.cmdline = cmdline;
            this.state = state;
        }
    }

    // ───────────────── 进程枚举 ─────────────────

    /**
     * 读取当前系统所有进程的 CPU 时间与应用归属。
     * 先普通读；被 SELinux 拦截时自动改走 root。
     */
    private List<ProcEntry> readAllProcesses() {
        if (!preferRoot) {
            List<ProcEntry> direct = readAllProcessesDirect();
            if (direct.size() >= MIN_DIRECT_PROCESSES) {
                return direct;
            }
            List<ProcEntry> viaRoot = readAllProcessesViaRoot();
            if (viaRoot.size() > direct.size()) {
                preferRoot = true;
                return viaRoot;
            }
            return direct;
        }
        return readAllProcessesViaRoot();
    }

    /** 普通读：仅能覆盖自身 uid 可见的进程（Android 12+ 上通常只剩本应用）。 */
    private static List<ProcEntry> readAllProcessesDirect() {
        List<ProcEntry> list = new ArrayList<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) {
            return list;
        }
        for (File dir : dirs) {
            String name = dir.getName();
            if (!dir.isDirectory() || !isNumeric(name)) {
                continue;
            }
            ProcEntry entry = readProcEntry(dir, false);
            if (entry != null) {
                list.add(entry);
            }
        }
        return list;
    }

    /** root 读：一条命令 dump 全部进程（stat + ps），返回空列表表示 root 不可用。 */
    private static List<ProcEntry> readAllProcessesViaRoot() {
        if (!RootShell.get().isRootAvailable()) {
            return Collections.emptyList();
        }
        RootShell.Result result = RootShell.get().exec(ROOT_DUMP_CMD, DUMP_TIMEOUT_MS);
        if (!result.isSuccess()) {
            return Collections.emptyList();
        }
        return parseRootDump(result.out);
    }

    /**
     * 解析 {@link #ROOT_DUMP_CMD} 的输出。
     *
     * <p>格式：
     * <pre>
     *   S|/proc/123/stat|123 (comm) R 0 ...
     *   ...
     *   P|
     *     PID USER         ARGS
     *     123 root         init second_stage
     * </pre>
     * 可见包可见性：包级可见仅为便于 javac 单测直接喂真实 dump 验证解析。
     */
    static List<ProcEntry> parseRootDump(String dump) {
        List<ProcEntry> entries = new ArrayList<>();
        if (dump == null || dump.isEmpty()) {
            return entries;
        }
        Map<Integer, String[]> psInfo = new HashMap<>();
        boolean psSection = false;
        for (String raw : dump.split("\n")) {
            if (!psSection) {
                if (raw.startsWith("P|")) {
                    psSection = true;
                    continue;
                }
                if (!raw.startsWith("S|")) {
                    continue;
                }
                ProcEntry entry = parseStatLine(raw.substring(2));
                if (entry != null) {
                    entries.add(entry);
                }
            } else {
                String t = raw.trim();
                if (t.isEmpty()) {
                    continue;
                }
                // PID USER ARGS —— ARGS 可能含空格，故限制切成 3 段
                String[] cols = t.split("\\s+", 3);
                if (cols.length < 3) {
                    continue;
                }
                int pid;
                try {
                    pid = Integer.parseInt(cols[0]);
                } catch (NumberFormatException ignored) {
                    continue;   // 表头 "PID USER ARGS"
                }
                psInfo.put(pid, new String[]{cols[1], cols[2]});
            }
        }
        // 用 ps 的 USER/ARGS 补全归属与命令行（swap 需读 status，root 下不做逐 pid 读取）
        List<ProcEntry> out = new ArrayList<>(entries.size());
        for (ProcEntry e : entries) {
            String[] extra = psInfo.get(e.pid);
            if (extra == null) {
                out.add(e);
                continue;
            }
            String cmdline = extra[1];
            int sp = cmdline.indexOf(' ');
            String command = sp > 0 ? cmdline.substring(0, sp) : cmdline;
            out.add(new ProcEntry(e.pid, e.name, packageOf(command), e.cpuTicks,
                    e.resKb, e.swapKb, extra[0], command, cmdline, e.state));
        }
        return out;
    }

    /** 解析单行 "文件名|stat内容"。 */
    private static ProcEntry parseStatLine(String text) {
        int bar = text.indexOf('|');
        if (bar <= 0) {
            return null;
        }
        int pid = pidFromPath(text.substring(0, bar));
        String s = text.substring(bar + 1);
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        if (pid <= 0 || open < 0 || close <= open) {
            return null;
        }
        try {
            String comm = s.substring(open + 1, close);
            String[] rest = s.substring(close + 1).trim().split("\\s+");
            // rest[0]=state(field3) rest[11]=utime(field14) rest[12]=stime(field15) rest[21]=rss(field24)
            if (rest.length <= 21) {
                return null;
            }
            long ticks = Long.parseLong(rest[11]) + Long.parseLong(rest[12]);
            long resKb = Long.parseLong(rest[21]) * 4L;   // rss 字段单位为页，页大小 4KB
            return new ProcEntry(pid, comm, null, ticks, resKb, 0L,
                    "", null, null, rest[0]);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 从 "/proc/123/stat" 取出 123。 */
    private static int pidFromPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return -1;
        }
        int prevSlash = path.lastIndexOf('/', lastSlash - 1);
        try {
            return Integer.parseInt(path.substring(prevSlash + 1, lastSlash));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** 读取单个进程的 stat/statm/status/cmdline 并汇总；失败返回 null。 */
    private static ProcEntry readProcEntry(File procDir, boolean root) {
        String stat = readFirstLine(new File(procDir, "stat"), root);
        if (stat == null) {
            return null;
        }
        try {
            int open = stat.indexOf('(');
            int close = stat.lastIndexOf(')');
            if (open < 0 || close <= open) {
                return null;
            }
            int pid = Integer.parseInt(stat.substring(0, open).trim());
            String comm = stat.substring(open + 1, close);
            String[] rest = stat.substring(close + 1).trim().split("\\s+");
            if (rest.length <= 13) {
                return null;
            }
            // rest[0]=state(field3) rest[11]=utime(field14) rest[12]=stime(field15)
            String state = rest[0];
            long utime = Long.parseLong(rest[11]);
            long stime = Long.parseLong(rest[12]);

            long[] mem = readMem(procDir, root);
            String[] cmd = readCommand(procDir, root);
            String user = readUser(procDir, root);

            return new ProcEntry(pid, comm, cmd != null ? cmd[0] : null,
                    utime + stime, mem[0], mem[1], user,
                    cmd != null ? cmd[1] : null, cmd != null ? cmd[2] : null, state);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 读取进程内存：返回 [rssKb, swapKb]。
     * RSS 来自 /proc/[pid]/statm 第 2 字段（页数，页大小 4KB）；
     * Swap 来自 /proc/[pid]/status 的 VmSwap 行。
     */
    private static long[] readMem(File procDir, boolean root) {
        long rssKb = 0;
        long swapKb = 0;
        // statm: size resident shared text lib data dt
        String statm = readFirstLine(new File(procDir, "statm"), root);
        if (statm != null) {
            String[] cols = statm.trim().split("\\s+");
            if (cols.length >= 2) {
                try {
                    rssKb = Long.parseLong(cols[1]) * 4L;   // 页数 × 4KB
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // status: VmSwap 行（格式 "VmSwap:\t    1234 kB"）
        String status = readFirstLineMatching(new File(procDir, "status"), "VmSwap:", root);
        if (status != null) {
            swapKb = parseKbValue(status);
        }
        return new long[]{rssKb, swapKb};
    }

    /** 解析 status 中的 "VmSwap:\t  1234 kB" 形式的 KB 数值。 */
    private static long parseKbValue(String line) {
        try {
            String s = line.substring(line.indexOf(':') + 1).trim();
            int sp = s.indexOf(' ');
            if (sp > 0) {
                s = s.substring(0, sp);
            }
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 读取进程归属用户：/proc/[pid]/status 的 Uid 行第一字段（real uid）。
     * 常见映射：0=root、1000=system、2000=shell、10000+=u0_aXX。
     */
    private static String readUser(File procDir, boolean root) {
        String line = readFirstLineMatching(new File(procDir, "status"), "Uid:", root);
        if (line == null) {
            return "";
        }
        try {
            String s = line.substring(line.indexOf(':') + 1).trim();
            int sp = s.indexOf('\t');
            if (sp < 0) {
                sp = s.indexOf(' ');
            }
            if (sp > 0) {
                s = s.substring(0, sp);
            }
            long uid = Long.parseLong(s.trim());
            if (uid == 0) {
                return "root";
            } else if (uid == 1000) {
                return "system";
            } else if (uid == 2000) {
                return "shell";
            } else if (uid >= 10000) {
                return "u0_a" + (uid - 10000);
            }
            return "uid" + uid;
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 读取命令行：返回 [包名或null, 命令第一段, 完整命令行]。
     *
     * <p>应用进程 cmdline 第一段为包名或「包名:进程后缀」（如 {@code com.foo:service}），
     * 取冒号前的部分作为包名；原生进程 cmdline 是可执行文件路径（含 '/'），
     * 包名为 null；空 cmdline（zygote/内核线程）返回 null。
     */
    private static String[] readCommand(File procDir, boolean root) {
        File cmdFile = new File(procDir, "cmdline");
        String text = root ? rootCat(cmdFile) : readTextPlain(cmdFile);
        if (text == null) {
            return null;
        }
        // cmdline 形如 "arg1\0arg2\0"（末尾还有终止 NUL），按 NUL 切分后会有空段
        List<String> args = new ArrayList<>(4);
        for (String a : text.split("\0")) {
            if (!a.isEmpty()) {
                args.add(a);
            }
        }
        if (args.isEmpty()) {
            return null;   // 空 cmdline：zygote、内核线程等
        }
        String command = args.get(0);
        return new String[]{packageOf(command), command, String.join(" ", args)};
    }

    /** 从命令行第一段提取包名；不是包名（含路径分隔符/无点）时返回 null。 */
    private static String packageOf(String command) {
        if (!isLikelyPackageName(command)) {
            return null;
        }
        int colon = command.indexOf(':');
        return colon > 0 ? command.substring(0, colon) : command;
    }

    /** 粗校验是否像包名：不含路径分隔符、含点、字符集限于字母数字下划线点。 */
    private static boolean isLikelyPackageName(String s) {
        if (s == null || s.length() < 3 || s.indexOf('/') >= 0 || s.indexOf('.') < 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ───────────────── 文件读取（普通读 → root 兜底） ─────────────────

    /** 读第一行；root=true 时普通读失败后走 root cat。 */
    private static String readFirstLine(File file, boolean root) {
        String plain = readTextPlain(file);
        if (plain != null) {
            return firstLine(plain);
        }
        if (!root) {
            return null;
        }
        String viaRoot = rootCat(file);
        return viaRoot == null ? null : firstLine(viaRoot);
    }

    /** 读某文件中以 prefix 开头的行；root=true 时普通读失败后走 root cat。 */
    private static String readFirstLineMatching(File file, String prefix, boolean root) {
        String plain = readTextPlain(file);
        if (plain != null) {
            for (String line : plain.split("\n")) {
                if (line.startsWith(prefix)) {
                    return line;
                }
            }
        }
        if (!root) {
            return null;
        }
        String viaRoot = rootCat(file);
        if (viaRoot == null) {
            return null;
        }
        for (String line : viaRoot.split("\n")) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }

    /** 普通读整个文件文本（保留 \0，cmdline 依赖它分段）；失败返回 null。 */
    private static String readTextPlain(File file) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.ISO_8859_1))) {
            StringBuilder sb = new StringBuilder(256);
            char[] buf = new char[1024];
            int n;
            while ((n = br.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * root 读取整个文件。
     *
     * <p>cmdline 以 {@code \0} 分隔参数，而 {@link RootShell.Result#out} 按行拼接，
     * {@code \0} 会被原样保留在字符串里，因此这里直接返回原始输出即可。
     * 读取失败返回 null。
     */
    private static String rootCat(File file) {
        if (!RootShell.get().isRootAvailable()) {
            return null;
        }
        RootShell.Result r = RootShell.get().exec(
                "cat " + RootShell.escape(file.getAbsolutePath()), 4000L);
        if (!r.isSuccess() || r.out.isEmpty()) {
            return null;
        }
        // Result.out 保留了每行末尾的 '\n'，去掉最后一个避免 split("\0") 多出空段
        String out = r.out;
        return out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        String line = nl >= 0 ? text.substring(0, nl) : text;
        String trimmed = line.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ───────────────── 对外 API ─────────────────

    /**
     * 返回当前 CPU 占用最高的前 {@code count} 个进程（含包名与百分比）。
     * 通过与上次采样的 CPU 时间差排序；<b>首次采样没有基线时改按累计 CPU 时间排序</b>，
     * 因此第一帧就能拿到列表，不会出现「刚打开是空的」。
     */
    public List<ProcInfo> readTop(int count) {
        List<ProcInfo> all = collectWithPercent();
        int n = Math.min(count, all.size());
        return new ArrayList<>(all.subList(0, n));
    }

    /**
     * 返回所有进程（按 CPU 占用降序，含内存/用户/命令等增强字段）。
     * 进程管理页 3 秒刷新使用；首次调用（无基线）按累计 CPU 时间排序，随后切换为区间占用率。
     */
    public List<ProcInfo> readAll() {
        return collectWithPercent();
    }

    /** 全量采集并计算百分比；内部维护差值基线。 */
    private List<ProcInfo> collectWithPercent() {
        List<ProcEntry> current = readAllProcesses();
        List<ProcInfo> result = new ArrayList<>();
        if (current.isEmpty()) {
            return result;
        }
        // 首次采样没有「上一次」可减：若仍按差值筛选，结果**必然是空列表**，
        // 悬浮窗与进程页都得再等一个完整刷新周期才有内容
        // —— 这是「进程监视器看着没有效果」的直接原因之一。
        // 改为直接拿累计 CPU 时间排序：首帧立刻有列表、有顺序、占比也可解释（开机以来的累计占用），
        // 下一帧起自动切换成真实的区间占用率。
        boolean firstSample = prevTimes.isEmpty();
        List<DeltaEntry> deltas = new ArrayList<>();
        long totalDelta = 0;
        for (ProcEntry e : current) {
            Long prev = prevTimes.get(e.pid);
            long d;
            if (firstSample) {
                d = e.cpuTicks;
            } else if (prev != null && e.cpuTicks >= prev) {
                d = e.cpuTicks - prev;
            } else {
                continue;   // 本区间没有有效采样（进程刚出现），跳过以免污染总量
            }
            totalDelta += d;
            deltas.add(new DeltaEntry(d, e));
        }
        // 更新基线（进程可能退出，需整体重建；仅保留有效采样）
        prevTimes.clear();
        for (ProcEntry e : current) {
            prevTimes.put(e.pid, e.cpuTicks);
        }
        // 按增量降序
        deltas.sort((a, b) -> Long.compare(b.delta, a.delta));
        for (DeltaEntry de : deltas) {
            int percent = totalDelta > 0 ? (int) (de.delta * 100 / totalDelta) : 0;
            ProcInfo info = toInfo(de.entry, Math.min(percent, 99));
            result.add(info);
        }
        return result;
    }

    /** ProcEntry → ProcInfo（增强字段一并拷贝）。 */
    private static ProcInfo toInfo(ProcEntry e, int percent) {
        // 显示名优先用包名：Android 的 android_set_process_name() 只把「最后 15 个字符」
        // 写进内核 comm（实测 com.android.systemui → ndroid.systemui），
        // 直接展示 comm 会得到一串被砍头的名字；包名完整且稳定。
        String displayName = e.pkg != null ? e.pkg : e.name;
        ProcInfo info = new ProcInfo(e.pid, displayName, e.pkg, percent);
        info.resKb = e.resKb;
        info.swapKb = e.swapKb;
        info.user = e.user;
        info.command = e.command;
        info.cmdline = e.cmdline;
        info.state = e.state;
        return info;
    }

    /**
     * 读取单个进程详情（列表信息 + cpuset/cgroup/oom 等内核接口字段）。
     * 进程已退出或读取失败返回 null。
     *
     * <p>详情页是「用户点开某一项」的低频路径，因此这里允许逐文件 root 读取
     * （每个 pid 最多 8 次 root cat，毫秒级），不必像全量枚举那样追求单次往返。
     */
    public ProcInfo readDetail(int pid) {
        File procDir = new File("/proc/" + pid);
        ProcEntry entry = readProcEntry(procDir, true);
        if (entry == null) {
            return null;
        }
        ProcInfo info = toInfo(entry, -1);
        info.cpuset = readText(new File(procDir, "cpuset"));
        info.cgroup = readText(new File(procDir, "cgroup"));
        info.oomAdj = readText(new File(procDir, "oom_adj"));
        info.oomScoreAdj = readText(new File(procDir, "oom_score_adj"));
        info.oomScore = readText(new File(procDir, "oom_score"));
        return info;
    }

    /** 读取单行文本文件并 trim；普通读失败时走 root。失败返回 null。 */
    private static String readText(File f) {
        String plain = readTextPlain(f);
        if (plain != null) {
            String trimmed = firstLine(plain);
            if (trimmed != null) {
                return trimmed;
            }
        }
        String viaRoot = rootCat(f);
        return viaRoot == null ? null : firstLine(viaRoot);
    }

    /** 采样增量条目：CPU 时间差 + 原始进程信息。 */
    private static final class DeltaEntry {
        final long delta;
        final ProcEntry entry;

        DeltaEntry(long delta, ProcEntry entry) {
            this.delta = delta;
            this.entry = entry;
        }
    }

    /** 清理基线，释放 pid 引用（窗口销毁时调用）。 */
    public void reset() {
        prevTimes.clear();
    }
}
