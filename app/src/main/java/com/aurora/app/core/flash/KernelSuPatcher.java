package com.aurora.app.core.flash;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * KernelSU 镜像修补（{@code libksud boot-patch}）。
 *
 * <h3>命令形态来自参考实现，不是自创</h3>
 * 参考实现（ChenAxic）的 {@code w2.java:case 3} 只有一条字符串：
 * <pre>
 * mkdir -p "/storage/emulated/0/ChenAxic" &amp;&amp; libksud boot-patch --kmi "&lt;KMI&gt;" \
 *     -b "&lt;boot.img&gt;" -o "/storage/emulated/0/ChenAxic" --out-name "kernelsu_patched_&lt;yyyyMMdd_HHmmss&gt;.img"
 * </pre>
 * 本类逐字复刻参数顺序与名字（换掉输出目录），并把「输出文件名」的生成抽成
 * {@link #outputName(long)}，便于离线断言。
 *
 * <h3>KMI 是什么</h3>
 * {@code --kmi} 要的是<b>内核模块接口</b>标识（如 {@code android14-6.1}），不是内核版本号本身。
 * KernelSU 的 LKM 模式要求 KMI 严格匹配目标机内核，所以这一步必须由用户确认 ——
 * 选错会得到一个刷上去不生效（甚至起不来）的镜像。列表取自参考实现
 * {@code l92.java} 里那一行 {@code ug.K(...)}，7 项一字不差。
 *
 * <h3>为什么版本检测要经 sed</h3>
 * {@code libksud --version} 的输出形如 {@code KernelSU 3.3.0 (uapi: 2)}，
 * 参考实现把开头的非数字字符剥掉（{@code s@^[^0-9]*@@}，用 {@code @} 当分隔符，
 * 与用斜杠写的表达式等价），得到 {@code 3.3.0 (uapi: 2)}，
 * 再套进「管理器版本 %1$s」的模板 —— 界面最终就显示成「管理器版本 3.3.0 (uapi: 2)」。
 */
public final class KernelSuPatcher {

    /**
     * 可选的 KMI 版本，顺序与参考实现一致。
     *
     * <p>只在构造修补命令时使用，界面上的选择列表由它直接生成 ——
     * 单一数据源，加一项只需改这里。
     */
    public static final String[] KMI_VERSIONS = {
            "android12-5.10",
            "android13-5.10",
            "android13-5.15",
            "android14-5.15",
            "android14-6.1",
            "android15-6.6",
            "android16-6.12",
    };

    /** 修补产物的文件名前缀。 */
    public static final String OUTPUT_PREFIX = "kernelsu_patched_";

    private KernelSuPatcher() {
    }

    // ───────────────── 版本 ─────────────────

    /**
     * 读取 {@code libksud} 版本。
     *
     * <p>{@code 2>/dev/null} 吞掉「工具链还没解压」时的 no such file 噪音；
     * {@code head -n1} 只取第一行（后面几行是构建信息）。
     */
    public static String versionCommand() {
        return "libksud --version 2>/dev/null | head -n1 | sed 's/^[^0-9]*//'";
    }

    /**
     * 从版本命令的输出里取出可显示的一行。
     *
     * <p>两段式：优先认「带点号的数字行」（{@code 3.3.0 (uapi: 2)}、{@code KernelSU 3.3.0} 这种），
     * 认不出来再退回「任意含数字的行」。不同构建的 {@code libksud} 输出格式有差异，
     * 与其用正则卡死格式把新版本格式判成「读不到」，不如宽松一点 ——
     * 这行本来就是给人看的。
     *
     * @return 形如 {@code 3.3.0 (uapi: 2)}；读不到返回 {@code null}
     */
    public static String parseVersion(String output) {
        if (output == null) {
            return null;
        }
        String fallback = null;
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            // shell 的报错行不算版本
            if (line.contains("not found") || line.contains("No such file")
                    || line.contains("Permission denied") || line.startsWith("/system/bin/sh")) {
                continue;
            }
            if (line.indexOf('.') >= 0 && hasDigit(line)) {
                return line;
            }
            if (fallback == null && hasDigit(line)) {
                fallback = line;
            }
        }
        return fallback;
    }

    private static boolean hasDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** 「管理器版本 xxx」的显示文案；版本未知时给出可读的占位而不是空串。 */
    public static String managerVersionLabel(String version) {
        return version == null || version.trim().isEmpty()
                ? "管理器版本 —"
                : "管理器版本 " + version.trim();
    }

    // ───────────────── 修补 ─────────────────

    /**
     * 生成修补命令。
     *
     * <p>四个外部输入（KMI、镜像路径、输出目录、输出文件名）全部经单引号包裹 ——
     * 这条命令是以 <b>root</b> 身份跑的，不转义就是命令注入。
     */
    public static String patchCommand(String kmi, String bootImage, String outDir, String outName) {
        return "mkdir -p " + esc(outDir)
                + " && libksud boot-patch --kmi " + esc(kmi)
                + " -b " + esc(bootImage)
                + " -o " + esc(outDir)
                + " --out-name " + esc(outName);
    }

    /**
     * 修补产物文件名：{@code kernelsu_patched_<yyyyMMdd_HHmmss>.img}。
     *
     * <p>带时间戳是为了「一天补好几次」时不会互相覆盖 —— 参考实现也是这么做的。
     */
    public static String outputName(long timestamp) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(timestamp));
        return OUTPUT_PREFIX + stamp + ".img";
    }

    /** KMI 是否在支持列表内。界面只从列表里选，这里是兜底。 */
    public static boolean isValidKmi(String kmi) {
        if (kmi == null) {
            return false;
        }
        for (String item : KMI_VERSIONS) {
            if (item.equals(kmi)) {
                return true;
            }
        }
        return false;
    }

    /** 单引号转义，与 {@link FlashRuntime#shellEscape(String)} 同规则。 */
    private static String esc(String raw) {
        return FlashRuntime.shellEscape(raw);
    }
}
