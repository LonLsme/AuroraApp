package com.aurora.app.core.monitor;

import com.aurora.app.core.root.RootShell;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统级帧率采样器（参考 vtools 的 FpsUtils 实现，Java 化并接入 RootShell）。
 *
 * <p>两种采样源，按优先级自动选择：
 * <ol>
 *   <li><b>sysfs measured_fps 节点</b>（高通 DRM/Adreno）——直接读取当前实测帧率，
 *       路径探测一次后缓存，后续采样只有一次 cat；</li>
 *   <li><b>{@code service call SurfaceFlinger 1013}</b>——返回累计渲染帧数（hex），
 *       两次采样按「帧数差 / 时间差」计算 FPS。</li>
 * </ol>
 *
 * <p>所有方法<b>必须在后台线程调用</b>（走 RootShell 会话）。
 * 采样失败（无 root / 节点不存在）返回 -1。
 */
public final class FpsSampler {

    /** 常见 sysfs 帧率节点候选。 */
    private static final String[] CANDIDATE_NODES = {
            "/sys/class/drm/sde-crtc-0/measured_fps",
            "/sys/class/drm/sde-crtc-1/measured_fps",
            "/sys/class/drm/sde-crtc-2/measured_fps",
            "/sys/class/drm/sde-crtc-3/measured_fps",
            "/sys/class/graphics/fb0/measured_fps",
    };

    /** 帧率节点缓存：null=未探测；""=无可用节点（回退 service call）。 */
    private String fpsNode;

    /** service call 方式的前后两次采样状态。 */
    private long lastFrameCount = -1L;
    private long lastSampleMs = -1L;

    /** service call 方式是否已判定不可用。 */
    private boolean surfaceFlingerBroken = false;

    /**
     * 采样一次当前帧率；失败返回 -1。
     * 单次调用耗时：sysfs 路径毫秒级；service call 也在毫秒级（持久化 root 会话）。
     */
    public float sample() {
        RootShell shell = RootShell.get();
        if (!shell.isRootAvailable()) {
            return -1f;
        }
        if (fpsNode == null) {
            probeNode(shell);
        }
        if (fpsNode != null && !fpsNode.isEmpty()) {
            return readMeasuredFps(shell, fpsNode);
        }
        if (!surfaceFlingerBroken) {
            return sampleBySurfaceFlinger(shell);
        }
        return -1f;
    }

    /** 重置差值法基线（长时间未采样后重新开始时调用，避免用陈旧基线算出异常值）。 */
    public void resetBaseline() {
        lastFrameCount = -1L;
        lastSampleMs = -1L;
    }

    // ───────────────── sysfs 节点方式 ─────────────────

    /** 探测可用节点；找不到时置空串回退 service call。 */
    private void probeNode(RootShell shell) {
        for (String node : CANDIDATE_NODES) {
            if (shell.fileExists(node)) {
                // 验证内容可解析，避免读到空节点
                if (readMeasuredFps(shell, node) >= 0f) {
                    fpsNode = node;
                    return;
                }
            }
        }
        fpsNode = "";
    }

    /** 解析 measured_fps 节点内容，取最后一个可解析为浮点数的字段。 */
    private static float readMeasuredFps(RootShell shell, String node) {
        String out = shell.execOut("cat " + RootShell.escape(node));
        if (out.isEmpty()) {
            return -1f;
        }
        String[] tokens = out.trim().split("\\s+");
        for (int i = tokens.length - 1; i >= 0; i--) {
            try {
                float v = Float.parseFloat(tokens[i]);
                if (v >= 0f && v <= 500f) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return -1f;
    }

    // ───────────────── service call 差值方式 ─────────────────

    /**
     * {@code service call SurfaceFlinger 1013} 返回累计帧数，
     * 两次采样差值计算 FPS（照抄 vtools 的解析逻辑）。
     */
    private float sampleBySurfaceFlinger(RootShell shell) {
        String result = shell.execOut("service call SurfaceFlinger 1013");
        result = result == null ? "" : result.trim();
        if (result.isEmpty() || result.equals("error") || !result.contains("Parcel")) {
            surfaceFlingerBroken = true;
            return -1f;
        }
        try {
            // 格式：Result: Parcel(00000000 00000000 '........')
            int open = result.indexOf('(');
            if (open < 0 || open + 8 >= result.length()) {
                surfaceFlingerBroken = true;
                return -1f;
            }
            long frames = Long.parseLong(result.substring(open + 1, open + 9), 16);
            long now = System.currentTimeMillis();
            float fps = -1f;
            if (lastFrameCount >= 0 && lastSampleMs > 0 && now > lastSampleMs) {
                fps = (frames - lastFrameCount) * 1000f / (now - lastSampleMs);
                if (fps < 0f || fps > 500f) {
                    fps = -1f;   // 帧计数回绕或异常，丢弃本帧
                }
            }
            lastFrameCount = frames;
            lastSampleMs = now;
            return fps;
        } catch (Exception e) {
            surfaceFlingerBroken = true;
            return -1f;
        }
    }

    /** 候选节点列表（仅供测试与诊断）。 */
    static List<String> candidateNodes() {
        List<String> list = new ArrayList<>();
        for (String node : CANDIDATE_NODES) {
            list.add(node);
        }
        return list;
    }
}
