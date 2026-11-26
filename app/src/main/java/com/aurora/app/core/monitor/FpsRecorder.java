package com.aurora.app.core.monitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.aurora.app.core.root.RootShell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 帧率记录会话管理器：单例，跨 Activity 存活（旋转屏幕不中断记录）。
 *
 * <p><b>数据结构</b>：环形缓冲 {@code short[]}，每秒一个样本（fps×10 存储，
 * 0.1 精度足够）。容量 3600（1 小时），内存仅 7KB——长时间记录也不产生 GC 压力。
 * 环形缓冲写满后覆盖最旧样本，统计值同步重算区间随之滑动。
 *
 * <p><b>线程模型</b>：后台采集线程每秒调用 {@link FpsSampler#sample()}（走 RootShell），
 * 采样失败（无 root / 节点不可用）的秒不计入样本；UI 通过 {@link #snapshot()}
 * 拿到一致性的数据副本（synchronized 保护）。
 *
 * <p><b>历史</b>：停止记录时把整段会话（每秒 FPS、功耗、CPU 占用、CPU 温度四条序列，
 * 外加归属应用与分辨率）写入 {@link FpsSessionStore}，保留最近 20 条。
 */
public final class FpsRecorder {

    /** 环形缓冲容量：1 样本/秒 × 3600 = 1 小时。 */
    public static final int CAPACITY = 3600;

    private static final String PREF_NAME = "fps_record_pref";
    private static final String KEY_HISTORY = "history";
    private static final int HISTORY_LIMIT = 10;

    private static volatile FpsRecorder instance;

    public static FpsRecorder get() {
        if (instance == null) {
            synchronized (FpsRecorder.class) {
                if (instance == null) {
                    instance = new FpsRecorder();
                }
            }
        }
        return instance;
    }

    private FpsRecorder() {
    }

    // ───────────────── 会话状态（采集线程写，snapshot 加锁读） ─────────────────

    /** 环形缓冲：fps × 10 取整存储。 */
    private final short[] samples = new short[CAPACITY];
    /** 功耗序列：W × 100（-1 = 本秒不可用）。 */
    private final short[] powerSamples = new short[CAPACITY];
    /** CPU 总占用序列（%，-1 = 不可用）。 */
    private final short[] cpuSamples = new short[CAPACITY];
    /** CPU 温度序列：℃ × 10（-1 = 不可用）。 */
    private final short[] tempSamples = new short[CAPACITY];
    /** 已写入样本数（含被覆盖的）。 */
    private int written = 0;
    /** 会话开始时间（elapsedRealtime，用于算时长）。 */
    private long startElapsedMs;
    /** 会话开始时间（wall clock，用于展示与做文件名）。 */
    private long startWallMs;
    /** 最近一次有效采样时间。 */
    private long lastSampleElapsedMs;

    private volatile boolean recording = false;
    private Thread worker;
    private final FpsSampler sampler = new FpsSampler();
    /** CPU 占用采样（内部维护两次 /proc/stat 的差值基线）。 */
    private final LoadMonitor loadMonitor = new LoadMonitor();

    /** 本次会话的归属应用与分辨率（开始时抓一次，抓不到就是空串）。 */
    private volatile String sessionPkg = "";
    private volatile String sessionApp = "";
    private volatile String sessionSize = "";

    /** 自动停止时长（秒），0 = 不限时（默认）。 */
    private volatile long maxDurationSec = 0;
    /** ApplicationContext：自动停止时保存历史用（悬浮窗场景无 Activity 可传）。 */
    private Context appContext;

    /** 最近一次采样失败时间：连续失败超过 10 秒判定为「无有效采样源」。 */
    private long lastGoodSampleMs;
    private long consecutiveFailures = 0;

    /** 会话统计快照。 */
    public static final class Snapshot {
        public final boolean recording;
        public final float[] fpsSeries;   // 按时间升序
        public final float currentFps;
        public final float avgFps;
        public final float maxFps;
        public final float minFps;
        /** 会话时长（秒）。 */
        public final long durationSec;

        Snapshot(boolean recording, float[] fpsSeries, float currentFps,
                 float avgFps, float maxFps, float minFps, long durationSec) {
            this.recording = recording;
            this.fpsSeries = fpsSeries;
            this.currentFps = currentFps;
            this.avgFps = avgFps;
            this.maxFps = maxFps;
            this.minFps = minFps;
            this.durationSec = durationSec;
        }
    }

    /** 历史会话摘要。 */
    public static final class HistoryEntry {
        public final long endTimeMs;
        public final long durationSec;
        public final float avgFps;
        public final float maxFps;
        public final float minFps;

        HistoryEntry(long endTimeMs, long durationSec, float avgFps, float maxFps, float minFps) {
            this.endTimeMs = endTimeMs;
            this.durationSec = durationSec;
            this.avgFps = avgFps;
            this.maxFps = maxFps;
            this.minFps = minFps;
        }
    }

    // ───────────────── 控制 ─────────────────

    /** 开始记录；已在记录中则忽略。 */
    public synchronized void start() {
        if (recording) {
            return;
        }
        recording = true;
        written = 0;
        maxDurationSec = 0;
        startWallMs = System.currentTimeMillis();
        startElapsedMs = SystemClock.elapsedRealtime();
        lastSampleElapsedMs = startElapsedMs;
        lastGoodSampleMs = 0L;
        consecutiveFailures = 0;
        sampler.resetBaseline();
        captureSessionMetaAsync();
        worker = new Thread(this::runLoop, "aurora-fps-recorder");
        worker.setDaemon(true);
        worker.start();
    }

    /** 开始时抓一次「哪个应用 + 什么分辨率」；失败不影响录制。 */
    private void captureSessionMetaAsync() {
        Thread t = new Thread(this::captureSessionMeta, "aurora-fps-meta");
        t.setDaemon(true);
        t.start();
    }

    private void captureSessionMeta() {
        try {
            sessionSize = readScreenSize();
        } catch (Exception ignored) {
        }
        try {
            String[] app = readForegroundApp();
            sessionPkg = app[0];
            sessionApp = app[1];
        } catch (Exception ignored) {
        }
    }

    private String readScreenSize() {
        Context ctx = appContext;
        android.util.DisplayMetrics dm = ctx != null
                ? ctx.getResources().getDisplayMetrics()
                : android.content.res.Resources.getSystem().getDisplayMetrics();
        return dm.widthPixels > 0 && dm.heightPixels > 0
                ? dm.widthPixels + "x" + dm.heightPixels
                : "";
    }

    /**
     * 读取当前前台应用（包名 + 应用名）。走 dumpsys，需要 root；
     * 取不到返回空串，会话照常保存，只是列表里显示「未知应用」。
     */
    private String[] readForegroundApp() {
        RootShell shell = RootShell.get();
        if (!shell.isRootAvailable()) {
            return new String[]{"", ""};
        }
        RootShell.Result r = shell.exec(
                "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -2",
                3000L);
        String out = r.isSuccess() ? r.out : "";
        String pkg = "";
        // 形如 mResumedActivity: ActivityRecord{... u0 com.tencent.mm/.ui.LauncherUI t123}
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\s([a-zA-Z][a-zA-Z0-9_.]+)/[.\\w$]+")
                .matcher(out);
        if (m.find()) {
            pkg = m.group(1);
        }
        String label = "";
        if (!pkg.isEmpty() && appContext != null) {
            try {
                android.content.pm.PackageManager pm = appContext.getPackageManager();
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence cs = ai.loadLabel(pm);
                label = cs == null ? "" : cs.toString();
            } catch (Exception ignored) {
            }
        }
        return new String[]{pkg, label};
    }

    /**
     * 设置本次会话的自动停止时长（秒）。
     * 0 = 不限时（默认，直到手动停止）；调用需在 {@link #start()} 之后、记录结束前。
     */
    public void setMaxDurationSec(long seconds) {
        maxDurationSec = Math.max(0, seconds);
    }

    /**
     * 记录当前会话的 ApplicationContext 引用，供自动停止时保存历史。
     * 悬浮窗等无 Activity 场景下停止记录需要它；传入 null 则自动停止时不写历史。
     */
    public synchronized void bindContext(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    /** 停止记录并保存会话摘要到历史。 */
    public synchronized void stop(Context context) {
        if (!recording) {
            return;
        }
        recording = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (context != null) {
            appContext = context.getApplicationContext();
            saveSession(context);
        } else if (appContext != null) {
            saveSession(appContext);
        }
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * 本次会话的展示标签：{@code 应用名 · 分辨率}（两者都取不到时为空串）。
     * 记录页用它告诉用户「这一条记的是谁」，避免对着一个陌生数字记录半天。
     */
    public String sessionLabel() {
        String name = sessionApp;
        if (name == null || name.isEmpty()) {
            name = sessionPkg;
        }
        if (name == null || name.isEmpty()) {
            name = "";
        }
        String size = sessionSize == null ? "" : sessionSize;
        if (name.isEmpty()) {
            return size;
        }
        return size.isEmpty() ? name : name + " · " + size;
    }

    /** 采集主循环：每秒采样一次，失败样本跳过；达到最大时长自动停止。 */
    private void runLoop() {
        while (recording) {
            long now = SystemClock.elapsedRealtime();
            // 达到最大时长：自动停止并保存历史
            if (maxDurationSec > 0 && now - startElapsedMs >= maxDurationSec * 1000L) {
                Context ctx = appContext;
                recording = false;
                if (worker != null) {
                    worker.interrupt();
                    worker = null;
                }
                if (ctx != null) {
                    saveSession(ctx);
                }
                return;
            }
            long sleepUntil = now + 1000L;
            float fps = -1f;
            try {
                fps = sampler.sample();
            } catch (Exception ignored) {
            }
            long now2 = SystemClock.elapsedRealtime();
            if (fps >= 0f) {
                // 三条辅助指标与 FPS 同槽位写入：一个槽位 = 一秒，四条曲线共用同一时间轴
                float power;
                try {
                    power = (float) PowerMonitor.readPowerW(appContext);
                } catch (Exception e) {
                    power = -1f;
                }
                float cpu;
                try {
                    cpu = loadMonitor.readCpuUsagePercent();
                } catch (Exception e) {
                    cpu = -1f;
                }
                float temp;
                try {
                    temp = TemperatureMonitor.readCpuTempC();
                } catch (Exception e) {
                    temp = -1f;
                }
                synchronized (samples) {
                    int idx = written % CAPACITY;
                    samples[idx] = (short) Math.max(0, Math.min(Short.MAX_VALUE,
                            Math.round(fps * 10f)));
                    powerSamples[idx] = encode(power, 100f);
                    cpuSamples[idx] = encode(cpu, 1f);
                    tempSamples[idx] = encode(temp, 10f);
                    written++;
                    lastSampleElapsedMs = now2;
                }
                consecutiveFailures = 0;
                lastGoodSampleMs = System.currentTimeMillis();
            } else {
                consecutiveFailures++;
            }
            long remain = sleepUntil - SystemClock.elapsedRealtime();
            if (remain > 0) {
                try {
                    Thread.sleep(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ───────────────── 读取 ─────────────────

    /** 获取当前会话的一致性快照（含曲线数据与统计）。 */
    public Snapshot snapshot() {
        synchronized (samples) {
            int n = Math.min(written, CAPACITY);
            float[] series = new float[n];
            float sum = 0f;
            float max = 0f;
            float min = Float.MAX_VALUE;
            int start = written > CAPACITY ? written - CAPACITY : 0;
            for (int i = 0; i < n; i++) {
                float v = samples[(start + i) % CAPACITY] / 10f;
                series[i] = v;
                sum += v;
                max = Math.max(max, v);
                min = Math.min(min, v);
            }
            float cur = n > 0 ? series[n - 1] : 0f;
            long end = written > 0 ? lastSampleElapsedMs : startElapsedMs;
            long duration = Math.max(0, (end - startElapsedMs) / 1000L);
            return new Snapshot(recording, series, cur,
                    n > 0 ? sum / n : 0f,
                    n > 0 ? max : 0f,
                    n > 0 ? min : 0f,
                    Math.max(0, duration));
        }
    }

    /** 连续采样失败是否已超过阈值（UI 提示「无有效采样源」用）。 */
    public boolean isSamplingDead() {
        return recording && consecutiveFailures > 10;
    }

    // ───────────────── 历史存取 ─────────────────

    /**
     * 把当前会话转成完整数据（四条曲线 + 归属应用 + 分辨率）。
     * 供保存与导出共用；录制中调用拿到的是截至当前的快照。
     */
    public FpsSessionData snapshotSession() {
        FpsSessionData data = new FpsSessionData();
        data.startTimeMs = startWallMs > 0 ? startWallMs : System.currentTimeMillis();
        data.packageName = sessionPkg == null ? "" : sessionPkg;
        data.appName = sessionApp == null ? "" : sessionApp;
        data.viewSize = sessionSize == null ? "" : sessionSize;

        int n;
        short[] fpsCopy;
        short[] pwrCopy;
        short[] cpuCopy;
        short[] tmpCopy;
        synchronized (samples) {
            n = Math.min(written, CAPACITY);
            int from = written > CAPACITY ? written - CAPACITY : 0;
            fpsCopy = new short[n];
            pwrCopy = new short[n];
            cpuCopy = new short[n];
            tmpCopy = new short[n];
            for (int i = 0; i < n; i++) {
                int idx = (from + i) % CAPACITY;
                fpsCopy[i] = samples[idx];
                pwrCopy[i] = powerSamples[idx];
                cpuCopy[i] = cpuSamples[idx];
                tmpCopy[i] = tempSamples[idx];
            }
        }
        data.durationSec = n;
        data.fps = new float[n];
        data.powerW = new float[n];
        data.cpuPercent = new float[n];
        data.cpuTempC = new float[n];
        for (int i = 0; i < n; i++) {
            data.fps[i] = fpsCopy[i] / 10f;
            data.powerW[i] = pwrCopy[i] < 0 ? -1f : pwrCopy[i] / 100f;
            data.cpuPercent[i] = cpuCopy[i] < 0 ? -1f : cpuCopy[i];
            data.cpuTempC[i] = tmpCopy[i] < 0 ? -1f : tmpCopy[i] / 10f;
        }
        return data;
    }

    /** 保存当前会话到文件存储（保留最近 20 条）。 */
    private void saveSession(Context context) {
        FpsSessionData data = snapshotSession();
        if (data.fps.length == 0) {
            return;
        }
        FpsSessionStore.save(context, data);
    }

    /** 把指标编码成 short；负值（不可用）统一存 -1。 */
    private static short encode(float value, float scale) {
        if (value < 0f || Float.isNaN(value)) {
            return -1;
        }
        long v = Math.round(value * scale);
        return (short) Math.max(-1L, Math.min(Short.MAX_VALUE, v));
    }

    /** 读取历史记录（新的在前）。 */
    public static List<HistoryEntry> loadHistory(Context context) {
        List<HistoryEntry> list = new ArrayList<>();
        JSONArray arr = readHistoryJson(context);
        for (int i = arr.length() - 1; i >= 0; i--) {   // 新的在前
            try {
                JSONObject o = arr.getJSONObject(i);
                list.add(new HistoryEntry(o.getLong("t"), o.getLong("d"),
                        (float) o.getDouble("avg"), (float) o.getDouble("max"),
                        (float) o.getDouble("min")));
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private static JSONArray readHistoryJson(Context context) {
        try {
            String raw = prefs(context).getString(KEY_HISTORY, "[]");
            return raw == null || raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static float round1(float v) {
        return Math.round(v * 10f) / 10f;
    }
}
