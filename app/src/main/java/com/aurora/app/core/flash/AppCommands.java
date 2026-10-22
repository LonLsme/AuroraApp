package com.aurora.app.core.flash;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「应用工具」类命令模板：列出 / 卸载 / 冻结 / 安装 / 免 root 激活。
 *
 * <h3>与 {@link FlashCommands} 的关系</h3>
 * 两者都是<b>纯字符串模板</b>，不执行（执行统一走 {@link FlashRuntime}）。
 * 分开成两个类是因为语义不同：{@link FlashCommands} 面向 fastboot 刷写，
 * 本类面向<b>目标机上的 Android 包管理</b> —— 全部命令都经 {@code adb shell} 打到
 * 那台连着的设备上，操作的是<b>目标机</b>的应用，不是本机。
 *
 * <h3>为什么这些动作用 adb 就够了，不需要 root</h3>
 * {@code adb shell} 在设备侧的身份是 {@code shell} 用户，它持有
 * {@code CHANGE_COMPONENT_ENABLED_STATE} / {@code DELETE_PACKAGES} 等签名级权限，
 * 因此 {@code pm uninstall --user 0} / {@code pm disable-user --user 0} 这类操作
 * 由 adb 发起时<b>不需要目标机 root</b>。这也正是「免 root 冻结」类应用的工作原理。
 *
 * <h3>激活命令的出处</h3>
 * 五条激活命令取自各应用<b>自己文档里给出的</b>免 root 激活步骤，不是推测：
 * <ul>
 *   <li>Shizuku —— 官方指引的 {@code adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh}
 *       （本类用等价的 {@code /sdcard} 前缀），成功时输出 {@code shizuku_starter exit with 0}；</li>
 *   <li>小黑屋设备管理员 —— 官方文档 {@code stopapp.https.gs/nonroot.html}：
 *       {@code adb shell dpm set-device-owner web1n.stopapp/.receiver.AdminReceiver}；</li>
 *   <li>小黑屋麦克斯韦妖 —— 同上文档：{@code adb shell sh /sdcard/Android/data/web1n.stopapp/files/demon.sh}；</li>
 *   <li>冰箱设备管理员 —— 官方文档 {@code iceboxdoc.catchingnow.com}：
 *       {@code adb shell dpm set-device-owner com.catchingnow.icebox/.receiver.DPMReceiver}；</li>
 *   <li>冰箱普通 ADB 模式 —— 同族命令，脚本位于 {@code .../com.catchingnow.icebox/files/start.sh}。</li>
 * </ul>
 * 「设备管理员」模式有个硬前提：目标机<b>不能有任何已登录账号、也不能有多用户 / 访客 /
 * 应用双开</b>，否则 {@code dpm} 会以 {@code Not allowed to ... already some accounts on the device}
 * 拒绝。这一点写在页面的确认框里，不写在页面上（项目约定：页面只放控件与实时状态）。
 */
public final class AppCommands {

    private AppCommands() {
    }

    // ───────────────── 应用列表 ─────────────────

    /**
     * 列出目标机的<b>第三方</b>应用：{@code adb shell pm list packages -3}。
     *
     * <p>输出形如 {@code package:com.foo.bar}，每行一个；{@link #parsePackageList(String)}
     * 负责剥前缀。这里刻意<b>不</b>加 {@code -f} —— 带上 APK 路径后每行都变长，
     * 而列表里真正有用的只有包名。
     */
    public static String listThirdParty() {
        return "adb shell pm list packages -3";
    }

    /**
     * 列出目标机的<b>系统</b>应用：{@code adb shell pm list packages -s}。
     *
     * <p>系统应用里混着大量不可动的基础组件，页面上是独立的另一个分栏，
     * 默认停在第三方那一栏。
     */
    public static String listSystem() {
        return "adb shell pm list packages -s";
    }

    /**
     * 解析 {@code pm list packages} 的输出。
     *
     * <p>只认 {@code package:<名字>} 这种行 —— adb 启动阶段可能混进
     * {@code * daemon not running} / {@code adb server ...} 这类噪音行，
     * 「不以 package: 开头」的整行丢弃即可，不必逐条识别噪音特征。
     */
    public static List<String> parsePackageList(String output) {
        List<String> result = new ArrayList<>();
        if (output == null) {
            return result;
        }
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (!line.startsWith("package:")) {
                continue;
            }
            String pkg = line.substring("package:".length()).trim();
            if (!pkg.isEmpty() && !result.contains(pkg)) {
                result.add(pkg);
            }
        }
        return result;
    }

    // ───────────────── 卸载 / 冻结 / 解冻 ─────────────────

    /**
     * 批量卸载：{@code pm uninstall --user 0 <pkg>}。
     *
     * <p>用 {@code --user 0} 而不是裸 {@code pm uninstall}：前者只对「主用户」卸载，
     * 能删掉一部分预装应用（对这类应用裸 {@code uninstall} 会以
     * {@code DELETE_FAILED_INTERNAL_ERROR} 失败），而后者对预装包基本无效。
     */
    public static String uninstallPackages(List<String> packages) {
        return forEachPackage(packages, "卸载", "pm uninstall --user 0");
    }

    /**
     * 批量冻结：{@code pm disable-user --user 0 <pkg>}。
     *
     * <p>冻结在这里的含义是「停用」——应用从桌面消失、不再能被启动与自启，
     * 但数据完整保留，{@code pm enable} 可以原样恢复。
     */
    public static String disablePackages(List<String> packages) {
        return forEachPackage(packages, "冻结", "pm disable-user --user 0");
    }

    /**
     * 批量解冻：{@code pm enable <pkg>}。
     *
     * <p>刻意与 {@code disable-user --user 0} 不对称地不带 {@code --user} ——
     * 解冻要把该包在<b>所有</b>用户下重新启用，否则切到访客 / 分身用户时会发现它还是停用的。
     */
    public static String enablePackages(List<String> packages) {
        return forEachPackage(packages, "解冻", "pm enable");
    }

    /**
     * 把「逐包执行同一条设备侧命令」合成<b>一条</b> shell。
     *
     * <p>为什么不逐包各发一条 adb 命令：每一次 {@code adb shell} 都要走一遍
     * 「本机进程 → USB → 设备侧 adbd → shell」，几十个包就是几十次往返，
     * 而且 {@link FlashRuntime} 是单任务闸门，串行编排起来还得处理
     * 「上一条的收尾会顺手刷新设备状态、从而短暂占用闸门」这个窗口（见
     * {@link FlashCommands#fastbootSwitchSlot()} 里记过的同一个坑）。
     * 合成一条既快又原子，中途插不进别的操作。
     *
     * <h3>引号怎么加</h3>
     * 目标形态是设备侧 shell 看到：
     * <pre>for p in 'a.b' 'c.d'; do echo "&gt;&gt;&gt; 卸载 $p"; pm uninstall --user 0 "$p"; done</pre>
     * 本类是把它整段交给 {@link FlashRuntime#shellEscape(String)} 包一层单引号后接到
     * {@code adb shell} 后面。{@code esc()} 会把内层的每个 {@code '} 改写成
     * {@code '\''}，因此本地 shell 解析完、传给 adb 的参数<b>恰好</b>就是上面那段原文本，
     * adb 再原样交给设备侧。内层的 {@code $p} 因为处在本地单引号内，不会被本地 shell 展开。
     *
     * <p>包名另有 {@link #isValidPackageName(String)} 在入口处把关，两道一起才安全：
     * 校验负责「明显不是包名的输入不进命令」，引号负责「万一进了也跑不出多余命令」。
     */
    private static String forEachPackage(List<String> packages, String verb, String deviceCommand) {
        if (packages == null || packages.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (String pkg : packages) {
            joined.append(" '").append(pkg).append('\'');
        }
        String inner = "for p in" + joined + "; do echo \">>> " + verb + " $p\"; "
                + deviceCommand + " \"$p\"; done";
        return "adb shell " + FlashRuntime.shellEscape(inner);
    }

    // ───────────────── 安装 ─────────────────

    /**
     * 逐个安装 APK：{@code adb install -r "<本机路径>"}。
     *
     * <p>注意路径是<b>主机（本应用所在手机）</b>上的路径 —— {@code adb install} 会自己把
     * 文件推给目标机，所以选包之后必须先把 SAF 的 {@code content://} URI 落到真实路径
     * （见 {@code FlashFilePicker}）。
     *
     * <p>{@code -r}：覆盖安装并保留数据。批量装多个包时不能改用
     * {@code adb install-multiple} —— 那条命令是给「同一个应用的分包（split APK）」
     * 用的，把几个互不相干的 APK 塞进去只会全部失败。
     */
    public static String installApks(List<String> hostPaths) {
        if (hostPaths == null || hostPaths.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String path : hostPaths) {
            sb.append("echo \">>> 安装 ").append(fileName(path)).append("\"; ");
            sb.append("adb install -r ").append(FlashCommands.esc(path)).append("; ");
        }
        sb.append("true");
        return sb.toString();
    }

    // ───────────────── 免 root 激活 ─────────────────

    /** 激活方式：脚本模式（跑设备侧 shell 脚本）还是设备管理员模式（dpm）。 */
    public enum ActivateMode {
        /** {@code adb shell sh <脚本>}。重启后多数会失效，需重新激活。 */
        SCRIPT,
        /** {@code adb shell dpm set-device-owner <组件>}。一次性、永久生效。 */
        DEVICE_OWNER
    }

    /** 一个可激活的目标。 */
    public static final class ActivateTarget {
        /** 界面上的标题。 */
        public final String title;
        /** 目标机上的应用包名 —— 用来判断装没装。 */
        public final String packageName;
        /** 界面上的副标题（一句话说清这个模式的特点）。 */
        public final String detail;
        /** 激活方式。 */
        public final ActivateMode mode;
        /** {@link ActivateMode#SCRIPT} 时的设备侧脚本路径；否则为 null。 */
        public final String scriptPath;
        /** {@link ActivateMode#DEVICE_OWNER} 时的组件名（{@code 包名/.Receiver}）；否则为 null。 */
        public final String ownerComponent;
        /** 确认框里的前提说明（只在弹框里出现，不摆到页面上）。 */
        public final String prerequisite;

        ActivateTarget(String title, String packageName, String detail, ActivateMode mode,
                       String scriptPath, String ownerComponent, String prerequisite) {
            this.title = title;
            this.packageName = packageName;
            this.detail = detail;
            this.mode = mode;
            this.scriptPath = scriptPath;
            this.ownerComponent = ownerComponent;
            this.prerequisite = prerequisite;
        }
    }

    /**
     * 支持一键激活的应用清单。
     *
     * <p>只有这五条 —— 每一条都能在某份官方文档里找到原文。想加新条目，先去那个应用自己的
     * 文档/仓库里把命令抄回来，不要照着别的工具的界面臆造。
     */
    public static final ActivateTarget[] ACTIVATE_TARGETS = {
            new ActivateTarget(
                    "Shizuku",
                    "moe.shizuku.privileged.api",
                    "免 root 启动 Shizuku 服务",
                    ActivateMode.SCRIPT,
                    "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
                    null,
                    "需要：目标机已安装 Shizuku 并开启 USB 调试。\n"
                            + "激活成功后 Shizuku 里会显示「正在运行」，重启目标机后失效需重新激活。"),
            new ActivateTarget(
                    "小黑屋 · 设备管理员",
                    "web1n.stopapp",
                    "永久生效，重启后仍然有效",
                    ActivateMode.DEVICE_OWNER,
                    null,
                    "web1n.stopapp/.receiver.AdminReceiver",
                    "需要：目标机已删除全部账号（含 Google / 小米 / 华为等），\n"
                            + "且已关闭多用户、访客模式与应用双开。\n\n"
                            + "激活后可以立刻把账号加回来，一次激活长期有效（卸载应用即失效）。"),
            new ActivateTarget(
                    "小黑屋 · 麦克斯韦妖",
                    "web1n.stopapp",
                    "无需删账号，重启后失效",
                    ActivateMode.SCRIPT,
                    "/sdcard/Android/data/web1n.stopapp/files/demon.sh",
                    null,
                    "需要：目标机已安装小黑屋并开启 USB 调试。\n"
                            + "这个模式不需要删除账号，但每次开机都要重新激活；\n"
                            + "Android 8.0 及以上还需把 USB 连接方式设为「仅充电」，否则拔线后服务会停。"),
            new ActivateTarget(
                    "冰箱 · 设备管理员",
                    "com.catchingnow.icebox",
                    "永久生效，重启后仍然有效",
                    ActivateMode.DEVICE_OWNER,
                    null,
                    "com.catchingnow.icebox/.receiver.DPMReceiver",
                    "需要：目标机已删除全部账号（含 Google / 小米 / 华为等），\n"
                            + "且已关闭多用户、访客模式与应用双开。\n\n"
                            + "激活后重启一次目标机即可使用，账号可以再加回来。"),
            new ActivateTarget(
                    "冰箱 · ADB 模式",
                    "com.catchingnow.icebox",
                    "免 root 启动冰箱服务",
                    ActivateMode.SCRIPT,
                    "/sdcard/Android/data/com.catchingnow.icebox/files/start.sh",
                    null,
                    "需要：目标机已安装冰箱并开启 USB 调试。\n"
                            + "该模式不占用「设备管理员」名额，可与其它工具共存。"),
    };

    /**
     * 生成一条「先确认应用装着、再执行激活」的设备侧命令。
     *
     * <p>合成一条而不是分两步：两步之间隔着一次 {@code adb shell} 往返，
     * 而 {@link FlashRuntime} 的单任务闸门在收尾时会顺手刷新设备状态 ——
     * 那个窗口里第二条命令有概率被拒（同 {@link FlashCommands#fastbootSwitchSlot()} 的理由）。
     *
     * <p>不做检查直接跑的话，用户看到的是设备侧 {@code sh: inaccessible or not found} 这种
     * 英文报错，不如提前一句话说清「目标机上没装这个应用」。
     */
    public static String activate(ActivateTarget target) {
        if (target == null) {
            return "";
        }
        // 两种模式只差最后那一条动作：脚本模式跑设备侧脚本，设备管理员模式走 dpm。
        final String action = (target.mode == ActivateMode.DEVICE_OWNER)
                ? "dpm set-device-owner " + target.ownerComponent
                : "sh " + target.scriptPath;
        String inner = "if pm path " + target.packageName + " >/dev/null 2>&1; then "
                + "echo \"正在激活 " + target.title + " ...\"; "
                + action + "; "
                + "else echo \"" + notInstalled(target.packageName) + "\"; fi";
        return "adb shell " + FlashRuntime.shellEscape(inner);
    }

    /** 「未在目标机上检测到 %1$s，请先安装该应用」。 */
    public static String notInstalled(String packageName) {
        return "未在目标机上检测到 " + packageName + "，请先安装该应用";
    }

    /** 「请先在列表中选中应用」。 */
    public static final String NO_PACKAGE_SELECTED = "请先在列表中选中要操作的应用";

    /** 「请先选择安装包」。 */
    public static final String NO_APK_SELECTED = "请先选择 .apk 安装包";

    // ───────────────── 校验 ─────────────────

    /**
     * 包名是否「看起来像」一个包名。
     *
     * <p>放行字母 / 数字 / 点 / 下划线，长度 2..200，且必须含点 —— Android 的包名就是这套
     * 字符集。
     *
     * <p>与 {@code FlashCommands.isValidPartitionName} 一样，这里<b>不是</b>安全边界：
     * 真正的防注入是 {@link #forEachPackage} 里那两层引号。本方法的作用是把「用户手输的
     * 自定义包名里混进空格或分号」这类输入挡在生成命令之前，让失败发生在界面层而不是
     * 设备侧。
     */
    public static boolean isValidPackageName(String pkg) {
        if (pkg == null) {
            return false;
        }
        String trimmed = pkg.trim();
        if (trimmed.length() < 2 || trimmed.length() > 200 || trimmed.indexOf('.') < 0) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean accepted = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!accepted) {
                return false;
            }
        }
        return true;
    }

    /** 路径的文件名部分（用于输出里的「>>> 安装 xxx.apk」）。 */
    private static String fileName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    /** 该输出是否表示设备真的在执行（用于判断列表命令有没有被 adb 拒掉）。 */
    public static boolean isAdbError(String output) {
        if (output == null) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("no devices/emulators found")
                || lower.contains("device unauthorized")
                || lower.contains("device offline")
                || lower.contains("error: device");
    }
}
