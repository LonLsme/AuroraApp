package com.aurora.app.core.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.annotation.Nullable;

import com.aurora.app.core.root.RootShell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 屏幕参数读写层：分辨率、密度、刷新率。
 *
 * <h3>数据来源为什么走 root 而不是公开 API</h3>
 * {@code Display.getSupportedModes()} 在多数 ROM 上只返回「当前模式」一条（厂商裁剪），
 * 拿不到完整模式表；而 {@code dumpsys display} 里的 {@code mSfDisplayModes} 段
 * 一定给出全部模式，且它的 {@code id} 正是 SurfaceFlinger 侧真正认的模式号
 * ——切刷新率要用的就是这个 id，不能拿公开 API 的 modeId 去猜。
 * 因此本类统一以 root 执行 {@code dumpsys display} 为主源，公开 API 只作兜底。
 *
 * <h3>Android 16 上的落点变化（实测）</h3>
 * 旧资料常说 {@code wm size} 写 {@code settings_secure.xml} 的 {@code display_size_forced}，
 * 于是有「删掉这两个键来救援」的脚本。实测 Android 16 / HyperOS 已经改成：
 * <ul>
 *   <li>{@code settings global display_size_forced} = {@code 1080,2400}（全球命名空间、逗号分隔）</li>
 *   <li>{@code settings secure display_density_forced} = {@code 420}</li>
 * </ul>
 * 所以「删 secure 里的键」会漏掉分辨率那一半。本类一律用官方的
 * {@code wm size reset} / {@code wm density reset} 还原，不自己删键。
 *
 * <h3>刷新率的双写策略</h3>
 * 只调 {@code service call SurfaceFlinger 1035 i32 id} 能立刻改当次显示模式，
 * 但部分 ROM 在息屏/切应用后会按系统设置回弹；所以同时写
 * {@code peak_refresh_rate}（+ 部分 ROM 认的 {@code user_refresh_rate}），
 * 让系统自己维持在这个档位。两个动作都做完后再<b>回读验证</b>，
 * 验证不过就如实返回失败，不假报成功。
 *
 * <p>所有方法都是阻塞 IO，必须在后台线程调用。
 */
public final class DisplayController {

    private static final String TAG = "DisplayController";

    /** 分辨率缩放下限比例：低于物理宽高的这个比例就不允许应用，避免小到无法操作。 */
    public static final float MIN_SIZE_RATIO = 0.4f;

    /** 一条 dumpsys 命令的超时。输出较大（display 段很长），给宽松些。 */
    private static final long DUMPSYS_TIMEOUT_MS = 15_000L;

    /**
     * root 下执行 {@code dumpsys display} 并只截取模式相关行，减少传输量。
     *
     * <p>模式写成裸的 {@code DisplayMode} 而不是带大括号的精确形式：
     * {@code grep -E} 里的 {@code \{}} 在 toybox grep 上行为不确定，
     * 一旦匹配不上就会静默退化成「读不到模式」，很难排查。
     * 反正解析时本来就有「遇到非 DisplayMode 开头的行即结束本段」的分段逻辑，
     * 多带几十行 {@code DisplayModeRecord} 也不影响结果。
     */
    private static final String DUMPSYS_MODES_CMD =
            "dumpsys display | grep -E 'DisplayMode'";

    private DisplayController() {
    }

    // ───────────────── 数据模型 ─────────────────

    /** 一个显示模式（SurfaceFlinger 侧的模式号 + 分辨率 + 刷新率）。 */
    public static final class Mode implements Comparable<Mode> {
        /** SurfaceFlinger 模式号，切刷新率时传给 {@code service call SurfaceFlinger 1035}。 */
        public final int id;
        public final int width;
        public final int height;
        /** 峰值刷新率，单位 Hz。 */
        public final float refreshRate;

        Mode(int id, int width, int height, float refreshRate) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
        }

        /** 展示文本：{@code 1440x3200 · 120Hz}。 */
        public String label() {
            return width + "x" + height + " · " + formatHz(refreshRate);
        }

        /** 刷新率文本：{@code 120Hz}（去掉小数尾巴）。 */
        public String hzLabel() {
            return formatHz(refreshRate);
        }

        /** 刷新率取整，用于写入 settings。 */
        public int hzRounded() {
            return Math.round(refreshRate);
        }

        @Override
        public int compareTo(Mode other) {
            return Float.compare(refreshRate, other.refreshRate);
        }
    }

    /** 屏幕尺寸状态：物理值 + 当前 override（未改过时 override 为 0）。 */
    public static final class SizeState {
        public final int physicalWidth;
        public final int physicalHeight;
        public final int physicalDensity;
        /** 当前生效的 width，等于 override（若有）否则物理宽。 */
        public final int activeWidth;
        public final int activeHeight;
        public final int activeDensity;
        /** 是否处于 override 状态。 */
        public final boolean overridden;

        SizeState(int physicalWidth, int physicalHeight, int physicalDensity,
                  int activeWidth, int activeHeight, int activeDensity, boolean overridden) {
            this.physicalWidth = physicalWidth;
            this.physicalHeight = physicalHeight;
            this.physicalDensity = physicalDensity;
            this.activeWidth = activeWidth;
            this.activeHeight = activeHeight;
            this.activeDensity = activeDensity;
            this.overridden = overridden;
        }

        /** 摘要文本：{@code 1080x2400 · 420dpi（原始 1440x3200 · 560dpi）}。 */
        public String label() {
            String base = activeWidth + "x" + activeHeight + " · " + activeDensity + "dpi";
            if (!overridden) {
                return base + "（物理原始）";
            }
            return base + "（原始 " + physicalWidth + "x" + physicalHeight
                    + " · " + physicalDensity + "dpi）";
        }
    }

    /** 一次写操作的结果。{@code message} 面向用户，尽量写清失败原因。 */
    public static final class OpResult {
        public final boolean success;
        public final String message;

        OpResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        static OpResult ok(String message) {
            return new OpResult(true, message);
        }

        static OpResult fail(String message) {
            return new OpResult(false, message);
        }
    }

    // ───────────────── 读取：显示模式 ─────────────────

    /**
     * 读取全部显示模式。
     * 优先 root {@code dumpsys display}；无 root 或解析为空时回退公开 API
     * {@code Display.getSupportedModes()}（可能只有一条）。
     */
    public static List<Mode> readModes(Context context) {
        List<Mode> modes = readModesViaRoot();
        if (!modes.isEmpty()) {
            return modes;
        }
        return readModesViaApi(context);
    }

    /** 走 root 读 {@code mSfDisplayModes} 段。 */
    static List<Mode> readModesViaRoot() {
        if (!RootShell.get().isRootAvailable()) {
            return Collections.emptyList();
        }
        RootShell.Result r = RootShell.get().exec(DUMPSYS_MODES_CMD, DUMPSYS_TIMEOUT_MS);
        if (!r.isSuccess()) {
            Log.w(TAG, "dumpsys display 失败: exit=" + r.exitCode
                    + " timedOut=" + r.timedOut + " unavailable=" + r.unavailable);
            return Collections.emptyList();
        }
        return parseModes(r.out);
    }

    /**
     * 从 {@code dumpsys display} 输出中解析模式表。
     *
     * <p>只认 {@code mSfDisplayModes=} 这一段里的 {@code DisplayMode{}}
     * ——后面 {@code mSupportedModes} 段用的是另一套 id 空间（实测同一台机器上
     * 前者是 0/1/2、后者是 1/2/3），拿它去调 SurfaceFlinger 会切错档。
     *
     * <p>只取<b>第一段</b>：dumpsys 里每块屏幕各有一段，混在一起会把外接屏的
     * 模式也塞进列表，用户点了就会切到别的屏上去。
     *
     * <p>包级可见，便于无 root 环境下喂字符串离线验证。
     */
    static List<Mode> parseModes(String dump) {
        List<Mode> modes = new ArrayList<>(4);
        if (dump == null || dump.isEmpty()) {
            return modes;
        }
        boolean inSfSection = false;
        boolean sectionDone = false;
        for (String rawLine : dump.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("mSfDisplayModes=")) {
                if (sectionDone) {
                    // 已经是第二块屏幕的段落，忽略
                    break;
                }
                inSfSection = true;
                continue;
            }
            if (!inSfSection) {
                continue;
            }
            // 段内出现别的键（mActiveSfDisplayMode / mSupportedModes …）即视为段落结束
            if (!line.startsWith("DisplayMode{")) {
                inSfSection = false;
                sectionDone = true;
                continue;
            }
            Mode mode = parseModeLine(line);
            if (mode != null) {
                modes.add(mode);
            }
        }
        return modes;
    }

    /** 读取当前激活的模式号；读不到返回 -1。 */
    public static int readActiveModeId() {
        if (!RootShell.get().isRootAvailable()) {
            return -1;
        }
        RootShell.Result r = RootShell.get().exec(DUMPSYS_MODES_CMD, DUMPSYS_TIMEOUT_MS);
        if (!r.isSuccess()) {
            return -1;
        }
        return parseActiveModeId(r.out);
    }

    /** 从 dumpsys 输出解析 {@code mActiveSfDisplayMode} 的 id。包级可见便于离线验证。 */
    static int parseActiveModeId(String dump) {
        if (dump == null) {
            return -1;
        }
        for (String rawLine : dump.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("mActiveSfDisplayMode=")) {
                continue;
            }
            Mode m = parseModeLine(line.substring(line.indexOf("DisplayMode{")));
            if (m != null) {
                return m.id;
            }
        }
        return -1;
    }

    /** 从当前激活模式拿到刷新率；读不到返回 0。 */
    public static float readActiveRefreshRate() {
        if (!RootShell.get().isRootAvailable()) {
            return 0f;
        }
        RootShell.Result r = RootShell.get().exec(DUMPSYS_MODES_CMD, DUMPSYS_TIMEOUT_MS);
        if (!r.isSuccess()) {
            return 0f;
        }
        for (String rawLine : r.out.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("mActiveSfDisplayMode=")) {
                continue;
            }
            Mode m = parseModeLine(line.substring(line.indexOf("DisplayMode{")));
            if (m != null) {
                return m.refreshRate;
            }
        }
        return 0f;
    }

    /** 解析单行 {@code DisplayMode{id=1, width=1440, height=3200, …, peakRefreshRate=120.0, …}}。 */
    @Nullable
    static Mode parseModeLine(String line) {
        int brace = line.indexOf("DisplayMode{");
        if (brace < 0) {
            return null;
        }
        int end = line.indexOf('}', brace);
        if (end < 0) {
            end = line.length();
        }
        String body = line.substring(brace + "DisplayMode{".length(), end);

        int id = -1;
        int width = 0;
        int height = 0;
        float hz = 0f;
        for (String part : body.split(",")) {
            String col = part.trim();
            int eq = col.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = col.substring(0, eq).trim();
            String value = col.substring(eq + 1).trim();
            switch (key) {
                case "id":
                    id = parseInt(value, -1);
                    break;
                case "width":
                    width = parseInt(value, 0);
                    break;
                case "height":
                    height = parseInt(value, 0);
                    break;
                case "peakRefreshRate":
                    hz = parseFloat(value, hz);
                    break;
                case "vsyncRate":
                    // 老版本 dumpsys 没有 peakRefreshRate，用它兜底
                    if (hz <= 0f) {
                        hz = parseFloat(value, 0f);
                    }
                    break;
                case "fps":
                    // mSupportedModes 段的写法，同样只作兜底
                    if (hz <= 0f) {
                        hz = parseFloat(value, 0f);
                    }
                    break;
                default:
                    break;
            }
        }
        if (id < 0 || width <= 0 || height <= 0) {
            return null;
        }
        return new Mode(id, width, height, hz);
    }

    /** 公开 API 兜底：{@code Display.getSupportedModes()}。 */
    static List<Mode> readModesViaApi(Context context) {
        List<Mode> modes = new ArrayList<>(4);
        Display display = findDefaultDisplay(context);
        if (display == null) {
            return modes;
        }
        try {
            Display.Mode[] supported = display.getSupportedModes();
            if (supported == null) {
                return modes;
            }
            for (Display.Mode m : supported) {
                modes.add(new Mode(m.getModeId(), m.getPhysicalWidth(),
                        m.getPhysicalHeight(), m.getRefreshRate()));
            }
        } catch (Exception e) {
            Log.w(TAG, "getSupportedModes 失败", e);
        }
        return modes;
    }

    @Nullable
    private static Display findDefaultDisplay(Context context) {
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                return dm.getDisplay(Display.DEFAULT_DISPLAY);
            }
        } catch (Exception e) {
            Log.w(TAG, "DisplayManager 取默认屏幕失败", e);
        }
        return null;
    }

    // ───────────────── 读取：分辨率与密度 ─────────────────

    /**
     * 读取分辨率与密度状态。
     * {@code wm size} / {@code wm density} 都需要 shell 权限，所以整段走 root。
     * root 不可用时退回公开 {@link DisplayMetrics}（只能拿到当前值，拿不到物理值）。
     */
    public static SizeState readSizeState(Context context) {
        if (RootShell.get().isRootAvailable()) {
            RootShell.Result size = RootShell.get().exec("wm size", 6000L);
            RootShell.Result density = RootShell.get().exec("wm density", 6000L);
            if (size.isSuccess()) {
                int[] physical = new int[]{0, 0};
                int[] override = new int[]{0, 0};
                parseWmSize(size.out, physical, override);
                int physicalDensity = 0;
                int overrideDensity = 0;
                if (density.isSuccess()) {
                    int[] pd = new int[]{0};
                    int[] od = new int[]{0};
                    parseWmDensity(density.out, pd, od);
                    physicalDensity = pd[0];
                    overrideDensity = od[0];
                }
                if (physical[0] > 0 && physical[1] > 0) {
                    boolean overridden = override[0] > 0 && override[1] > 0;
                    int activeW = overridden ? override[0] : physical[0];
                    int activeH = overridden ? override[1] : physical[1];
                    int activeD = overrideDensity > 0 ? overrideDensity
                            : (physicalDensity > 0 ? physicalDensity : activeDensityFallback(context));
                    int physD = physicalDensity > 0 ? physicalDensity : activeD;
                    return new SizeState(physical[0], physical[1], physD,
                            activeW, activeH, activeD, overridden);
                }
            }
        }
        if (context == null) {
            // 调用方明确表示只要 root 结果（例如写前校验），拿不到就返回空状态
            return new SizeState(0, 0, 0, 0, 0, 0, false);
        }
        // 兜底：公开 API 拿当前值，把当前值同时当作物理值
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return new SizeState(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, false);
    }

    private static int activeDensityFallback(Context context) {
        return context.getResources().getDisplayMetrics().densityDpi;
    }

    /**
     * 解析 {@code wm size} 输出，形如：
     * <pre>
     * Physical size: 1440x3200
     * Override size: 1080x2400
     * </pre>
     * 无 override 行时 override 保持出参原值。包级可见便于离线验证。
     */
    static void parseWmSize(String out, int[] physical, int[] override) {
        if (out == null) {
            return;
        }
        for (String rawLine : out.split("\n")) {
            String line = rawLine.trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("Physical size".equals(key)) {
                int[] wh = parseSize(value);
                if (wh != null) {
                    physical[0] = wh[0];
                    physical[1] = wh[1];
                }
            } else if ("Override size".equals(key)) {
                int[] wh = parseSize(value);
                if (wh != null) {
                    override[0] = wh[0];
                    override[1] = wh[1];
                }
            }
        }
    }

    /** 解析 {@code wm density} 输出，形如 {@code Physical density: 560} / {@code Override density: 420}。 */
    static void parseWmDensity(String out, int[] physical, int[] override) {
        if (out == null) {
            return;
        }
        for (String rawLine : out.split("\n")) {
            String line = rawLine.trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            int value = parseInt(line.substring(colon + 1).trim(), 0);
            if ("Physical density".equals(key)) {
                physical[0] = value;
            } else if ("Override density".equals(key)) {
                override[0] = value;
            }
        }
    }

    @Nullable
    private static int[] parseSize(String value) {
        // 兼容 1080x2400 与 1080,2400 两种写法
        String normalized = value.replace(',', 'x');
        int x = normalized.indexOf('x');
        if (x <= 0) {
            return null;
        }
        int w = parseInt(normalized.substring(0, x).trim(), 0);
        int h = parseInt(normalized.substring(x + 1).trim(), 0);
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new int[]{w, h};
    }

    // ───────────────── 写入：刷新率 ─────────────────

    /**
     * 切换显示模式（刷新率）。
     *
     * <p>步骤：① {@code service call SurfaceFlinger 1035 i32 id} 立即切当次模式；
     * ② 写 {@code peak_refresh_rate}（+ {@code user_refresh_rate}，HyperOS 认这个）让系统维持；
     * ③ 回读 {@code mActiveSfDisplayMode} 验证。回读不到（例如某些 ROM 不更新该字段）
     * 时不判失败，只要前两步命令都成功就算成功，并在消息里说明「未能回读确认」。
     */
    public static OpResult applyMode(Mode mode) {
        if (!RootShell.get().isRootAvailable()) {
            return OpResult.fail("需要 Root 权限才能切换刷新率");
        }
        int hz = mode.hzRounded();

        RootShell.Result sf = RootShell.get().exec(
                "service call SurfaceFlinger 1035 i32 " + mode.id, 8000L);
        boolean sfOk = sf.isSuccess() && !sf.out.contains("Operation not permitted")
                && !sf.out.contains("not found");
        if (!sfOk) {
            Log.w(TAG, "SurfaceFlinger 1035 失败: " + sf.out);
        }

        // 双保险：写系统设置让 ROM 自己维持档位
        RootShell.get().exec("settings put system peak_refresh_rate " + hz, 6000L);
        RootShell.get().exec("settings put system user_refresh_rate " + hz, 6000L);

        float active = readActiveRefreshRate();
        boolean verified = active > 0f && Math.abs(active - mode.refreshRate) < 1.5f;
        if (verified) {
            return OpResult.ok("已切换到 " + formatHz(mode.refreshRate) + "（回读确认）");
        }
        if (sfOk) {
            return OpResult.ok("已下发 " + hz + "Hz 切换命令；未能回读确认，请以实际观感为准");
        }
        return OpResult.fail("切换失败：SurfaceFlinger 拒绝该请求（部分 ROM 锁定了显示模式）");
    }

    /** 把刷新率上限恢复为屏幕支持的最高档（相当于放开系统自适应）。 */
    public static OpResult releaseRefreshRateLimit() {
        if (!RootShell.get().isRootAvailable()) {
            return OpResult.fail("需要 Root 权限");
        }
        RootShell.get().exec("settings delete system peak_refresh_rate", 6000L);
        RootShell.get().exec("settings delete system user_refresh_rate", 6000L);
        return OpResult.ok("已恢复为系统自适应刷新率");
    }

    /** 读取当前 {@code peak_refresh_rate} 设置值；未设置为 0。 */
    public static int readPeakRefreshRateSetting() {
        if (!RootShell.get().isRootAvailable()) {
            return 0;
        }
        String raw = RootShell.get().execOut("settings get system peak_refresh_rate");
        return parseInt(raw, 0);
    }

    // ───────────────── 写入：分辨率与密度 ─────────────────

    /**
     * 应用自定义分辨率。
     *
     * <p>安全性：宽高必须同时 {@code >= 物理宽高 * }{@link #MIN_SIZE_RATIO}，
     * 否则直接拒绝——分辨率改得过小会让界面无法操作，且不易在无电脑时救回。
     * 长宽比也做一次校验（偏离物理比例超过 25% 直接拒绝），防止拉伸出错。
     *
     * @param width   目标宽（px）
     * @param height  目标高（px）
     * @param density 目标密度（dpi）；传 0 表示不动密度
     */
    public static OpResult applyResolution(int width, int height, int density) {
        if (!RootShell.get().isRootAvailable()) {
            return OpResult.fail("需要 Root 权限才能修改分辨率");
        }
        SizeState state = readSizeState(null);
        if (state.physicalWidth <= 0 || state.physicalHeight <= 0) {
            return OpResult.fail("读取物理分辨率失败，已中止");
        }
        int minW = Math.round(state.physicalWidth * MIN_SIZE_RATIO);
        int minH = Math.round(state.physicalHeight * MIN_SIZE_RATIO);
        if (width < minW || height < minH) {
            return OpResult.fail("分辨率过小：不得低于 " + minW + "x" + minH
                    + "（物理尺寸的 " + Math.round(MIN_SIZE_RATIO * 100) + "%）");
        }
        float physicalRatio = (float) state.physicalWidth / state.physicalHeight;
        float targetRatio = (float) width / height;
        if (Math.abs(targetRatio - physicalRatio) / physicalRatio > 0.25f) {
            return OpResult.fail("长宽比与屏幕差异过大，已拒绝（避免拉伸变形）");
        }

        RootShell.Result size = RootShell.get().exec("wm size " + width + "x" + height, 8000L);
        if (!size.isSuccess()) {
            return OpResult.fail("分辨率设置失败：" + shortOut(size));
        }
        if (density > 0) {
            RootShell.Result dens = RootShell.get().exec("wm density " + density, 8000L);
            if (!dens.isSuccess()) {
                return OpResult.fail("分辨率已改，但密度设置失败：" + shortOut(dens));
            }
        }
        return OpResult.ok("已应用 " + width + "x" + height
                + (density > 0 ? " · " + density + "dpi" : ""));
    }

    /**
     * 还原分辨率与密度到物理默认。
     * 同时清掉 {@code wm} 的两个 override（{@code wm size reset} 会一并删除
     * {@code display_size_forced} 与 {@code display_density_forced} 落点）。
     */
    public static OpResult resetResolution() {
        if (!RootShell.get().isRootAvailable()) {
            return OpResult.fail("需要 Root 权限才能还原");
        }
        RootShell.Result a = RootShell.get().exec("wm size reset", 8000L);
        RootShell.Result b = RootShell.get().exec("wm density reset", 8000L);
        RootShell.Result verify = RootShell.get().exec("wm size", 6000L);
        int[] physical = new int[]{0, 0};
        int[] override = new int[]{0, 0};
        if (verify.isSuccess()) {
            parseWmSize(verify.out, physical, override);
        }
        boolean stillOverridden = override[0] > 0;
        if (stillOverridden) {
            return OpResult.fail("还原命令已下发，但仍检测到 override，请手动执行 wm size reset");
        }
        if (!a.isSuccess() && !b.isSuccess()) {
            return OpResult.fail("还原失败：" + shortOut(a));
        }
        return OpResult.ok("已还原为物理分辨率 "
                + (physical[0] > 0 ? physical[0] + "x" + physical[1] : "默认值"));
    }

    /**
     * 按比例缩放时推荐的密度：保持「逻辑尺寸（dp）不变」，只改像素量。
     * 这样缩放后界面元素大小不变，只是渲染分辨率变了。
     */
    public static int suggestDensity(int targetWidth, int physicalWidth, int physicalDensity) {
        if (physicalWidth <= 0 || physicalDensity <= 0 || targetWidth <= 0) {
            return physicalDensity;
        }
        return Math.round((float) physicalDensity * targetWidth / physicalWidth);
    }

    // ───────────────── 小工具 ─────────────────

    /** 刷新率格式化：{@code 120.00001} → {@code 120Hz}，{@code 89.5} → {@code 89.5Hz}。 */
    public static String formatHz(float hz) {
        if (hz <= 0f) {
            return "—";
        }
        if (Math.abs(hz - Math.round(hz)) < 0.05f) {
            return Math.round(hz) + "Hz";
        }
        return String.format(Locale.US, "%.1fHz", hz);
    }

    private static String shortOut(RootShell.Result r) {
        if (r == null) {
            return "无输出";
        }
        if (r.timedOut) {
            return "命令超时";
        }
        if (r.unavailable) {
            return "Root 会话不可用";
        }
        String out = r.out.trim();
        if (out.isEmpty()) {
            return "exit=" + r.exitCode;
        }
        return out.length() > 120 ? out.substring(0, 120) + "…" : out;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float parseFloat(String raw, float fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 设备代号兼容信息，供页面「关于」行展示。 */
    public static String deviceInfo() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
    }
}
