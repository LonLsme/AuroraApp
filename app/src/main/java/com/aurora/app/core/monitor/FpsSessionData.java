package com.aurora.app.core.monitor;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 一次帧率记录会话的完整数据（会话头 + 每秒序列）。
 *
 * <p>与旧实现的差别：旧代码只在 SharedPreferences 里存了「平均/最高/最低」三个数，
 * 曲线画完就丢，历史记录点不进去。这里把每秒钟的 FPS、功耗、CPU 占用、CPU 温度
 * 四条序列一起存下来，详情页、CSV 导出、图片导出都从这份数据出。
 *
 * <p>序列含义：下标 = 记录开始后的第几秒，长度 = {@link #durationSec}。
 * 采样失败用 -1 表示（JSON 不支持 NaN），绘图时跳过这些点。
 */
public final class FpsSessionData {

    /** 会话开始时间（wall clock，毫秒）。同时作为文件名与唯一 id。 */
    public long startTimeMs;
    /** 时长（秒）。 */
    public int durationSec;
    /** 被记录应用包名；取不到为空串。 */
    public String packageName = "";
    /** 应用显示名；取不到为空串。 */
    public String appName = "";
    /** 记录时的屏幕分辨率，如 {@code 1080x2400}。 */
    public String viewSize = "";

    /** 每秒 FPS（-1 = 本秒无有效采样）。 */
    public float[] fps = new float[0];
    /** 每秒整机功耗 W（-1 = 不可用）。 */
    public float[] powerW = new float[0];
    /** 每秒 CPU 总占用 %（-1 = 不可用）。 */
    public float[] cpuPercent = new float[0];
    /** 每秒 CPU 温度 ℃（-1 = 不可用）。 */
    public float[] cpuTempC = new float[0];

    public FpsSessionData() {
    }

    // ─── 统计 ───

    /** 平均 FPS（只统计有效值）；无有效采样返回 0。 */
    public float avgFps() {
        return avgOf(fps);
    }

    public float maxFps() {
        return maxOf(fps);
    }

    /** 最小 FPS：忽略 0（息屏/切后台那几秒会把最小值拉到 0，没有参考意义）。 */
    public float minFps() {
        float min = Float.MAX_VALUE;
        for (float v : fps) {
            if (v > 0f && v < min) {
                min = v;
            }
        }
        return min == Float.MAX_VALUE ? 0f : min;
    }

    /** 平均功耗（W）；无有效值返回 -1。 */
    public float avgPower() {
        float avg = avgOf(powerW);
        return avg <= 0f ? -1f : avg;
    }

    /** 平均 CPU 占用；无有效值返回 -1。 */
    public float avgCpu() {
        float avg = avgOf(cpuPercent);
        return avg <= 0f ? -1f : avg;
    }

    /** 最高 CPU 温度；无有效值返回 -1。 */
    public float maxTemp() {
        float max = maxOf(cpuTempC);
        return max <= 0f ? -1f : max;
    }

    private static float avgOf(float[] series) {
        float sum = 0f;
        int n = 0;
        for (float v : series) {
            if (v >= 0f) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? 0f : sum / n;
    }

    private static float maxOf(float[] series) {
        float max = 0f;
        for (float v : series) {
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    // ─── 序列化 ───

    /** 完整会话 JSON（含四条曲线）。 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("t", startTimeMs);
            o.put("d", durationSec);
            o.put("pkg", packageName);
            o.put("app", appName);
            o.put("size", viewSize);
            o.put("fps", toArray(fps));
            o.put("pwr", toArray(powerW));
            o.put("cpu", toArray(cpuPercent));
            o.put("tmp", toArray(cpuTempC));
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 列表页用的摘要 JSON（不含曲线，解析快得多）。 */
    public JSONObject toSummaryJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("t", startTimeMs);
            o.put("d", durationSec);
            o.put("pkg", packageName);
            o.put("app", appName);
            o.put("size", viewSize);
            o.put("avg", round1(avgFps()));
            o.put("max", round1(maxFps()));
            o.put("min", round1(minFps()));
            o.put("pwr", round1(avgPower()));
            o.put("tmp", round1(maxTemp()));
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 从完整 JSON 还原。 */
    public static FpsSessionData fromJson(JSONObject o) {
        FpsSessionData d = new FpsSessionData();
        d.startTimeMs = o.optLong("t");
        d.durationSec = o.optInt("d");
        d.packageName = o.optString("pkg", "");
        d.appName = o.optString("app", "");
        d.viewSize = o.optString("size", "");
        d.fps = toFloats(o.optJSONArray("fps"));
        d.powerW = toFloats(o.optJSONArray("pwr"));
        d.cpuPercent = toFloats(o.optJSONArray("cpu"));
        d.cpuTempC = toFloats(o.optJSONArray("tmp"));
        return d;
    }

    /** 从摘要 JSON 还原（曲线为空，只用于列表展示）。 */
    public static FpsSessionData fromSummaryJson(JSONObject o) {
        FpsSessionData d = new FpsSessionData();
        d.startTimeMs = o.optLong("t");
        d.durationSec = o.optInt("d");
        d.packageName = o.optString("pkg", "");
        d.appName = o.optString("app", "");
        d.viewSize = o.optString("size", "");
        // 列表页需要显示的统计值直接写回曲线数组，避免再加一套摘要字段
        d.fps = new float[]{(float) o.optDouble("avg", 0), (float) o.optDouble("max", 0),
                (float) o.optDouble("min", 0)};
        d.powerW = new float[]{(float) o.optDouble("pwr", -1)};
        d.cpuTempC = new float[]{(float) o.optDouble("tmp", -1)};
        return d;
    }

    private static JSONArray toArray(float[] values) {
        JSONArray arr = new JSONArray();
        for (float v : values) {
            // 保留 1 位小数：文件体积减半，图表精度足够
            try {
                arr.put(round1(v));
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    private static float[] toFloats(JSONArray arr) {
        if (arr == null) {
            return new float[0];
        }
        float[] out = new float[arr.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) arr.optDouble(i, -1);
        }
        return out;
    }

    private static float round1(float v) {
        return Math.round(v * 10f) / 10f;
    }
}
