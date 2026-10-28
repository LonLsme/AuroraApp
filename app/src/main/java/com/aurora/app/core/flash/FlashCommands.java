package com.aurora.app.core.flash;

import java.util.Locale;

/**
 * 刷机命令模板。
 *
 * <p>命令字符串的形态逐条核对过：
 * <ul>
 *   <li>Fastboot 单分区：{@code fastboot flash boot "<img>"}；
 *       A/B 的 init_boot 用 {@code &&} 串两个槽位；</li>
 *   <li>临时启动 / 关校验：{@code fastboot boot "<img>"} 与
 *       {@code fastboot --disable-verity --disable-verification flash vbmeta "<img>"}；</li>
 *   <li>重启菜单：ADB 用 {@code adb reboot <target>}，
 *       Fastboot 用 {@code fastboot reboot-bootloader} 这类旧语法（兼容性更好）；</li>
 *   <li>残留清理：{@code pkill -9 adb; pkill -9 fastboot}。</li>
 * </ul>
 *
 * <p>本类只生成字符串，不执行 —— 执行统一走 {@link FlashRuntime}。
 * 所有拼进命令的外部输入（镜像路径、分区名）必须经 {@link #esc(String)} 包裹，
 * 否则是 root 权限下的命令注入。
 */
public final class FlashCommands {

    private FlashCommands() {
    }

    // ───────────────── 分区 ─────────────────

    /**
     * 常用分区清单。{@code abSlots} 为真表示设备可能采用 A/B 双槽，
     * 刷入时要同时写 {@code _a} 与 {@code _b}（否则切换槽位后行为不一致）。
     */
    public static final Target[] TARGETS = {
            new Target("init_boot", "init_boot", "内核（GKI 设备的 ramdisk）", true),
            new Target("boot", "boot", "内核与 ramdisk（非 GKI 设备）", false),
            new Target("recovery", "recovery", "恢复模式", false),
            new Target("dtbo", "dtbo", "设备树覆盖", false),
            new Target("vbmeta", "vbmeta", "AVB 校验元数据", false),
    };

    /** 一个可刷写分区。 */
    public static final class Target {
        /** 实际传给 fastboot 的分区名。 */
        public final String partition;
        /** 界面显示名。 */
        public final String label;
        /** 界面副标题。 */
        public final String subtitle;
        /** 是否可能需要写 {@code _a} / {@code _b} 两个槽位。 */
        public final boolean abSlots;

        Target(String partition, String label, String subtitle, boolean abSlots) {
            this.partition = partition;
            this.label = label;
            this.subtitle = subtitle;
            this.abSlots = abSlots;
        }
    }

    /**
     * 分区名是否「看起来像」一个分区名。
     *
     * <p>放行字母 / 数字 / 下划线 / 点 / 连字符，长度 1..64 —— Android 的分区名就是这套字符集
     * （{@code init_boot} / {@code system_ext} / {@code vendor_dlkm} / {@code boot}）。
     * 出现空格或斜杠基本可以断定是输错了：把镜像路径当分区名粘进来是很常见的误操作。
     *
     * <p>这里<b>不是</b>安全边界 —— 真正的防注入是 {@link #esc(String)}（外部输入一律单引号包死）。
     * 本方法只是把明显不像分区名的输入拦在确认框之前，少一次「按了刷入才发现名字错了」。
     */
    public static boolean isValidPartitionName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean accepted = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            if (!accepted) {
                return false;
            }
        }
        return true;
    }

    // ───────────────── Fastboot 写入 ─────────────────

    /** {@code fastboot flash <partition> <img>}。 */
    public static String fastbootFlash(String partition, String imagePath) {
        return "fastboot flash " + esc(partition) + " " + esc(imagePath);
    }

    /**
     * A/B 双槽写入：{@code fastboot flash init_boot_a <img> && fastboot flash init_boot_b <img>}。
     *
     * <p>只写当前槽位的话，OTA 切槽后会退回未刷的旧镜像 —— 这是
     * 对 init_boot 特意走双槽的原因。
     */
    public static String fastbootFlashBothSlots(String partition, String imagePath) {
        String img = esc(imagePath);
        return "fastboot flash " + esc(partition + "_a") + " " + img
                + " && fastboot flash " + esc(partition + "_b") + " " + img;
    }

    /**
     * 带「分区存在性检查」的单分区刷入。
     *
     * <p>刷任何一个分区之前都会先跑一次
     * {@code fastboot getvar partition-size} 确认这个分区在目标机上真的存在；不存在就打印
     * 「检测到设备不存在 xxx分区，拒绝刷入此镜像」并中止，而不是把镜像盲刷进去。
     * 分区名写错（例如把 {@code init_boot} 写成 {@code initboot}）时，这一步能把它挡下来。
     *
     * <p>检查与刷入合成一条 shell：{@code grep -qF} 命中才执行 {@code fastboot flash}。
     * 用 {@code -F}（固定串）而不是正则 —— 分区名可能来自用户输入，带 {@code .} / {@code *}
     * 时正则匹配会得到意料之外的结果。
     */
    public static String fastbootFlashChecked(String partition, String imagePath) {
        return "if fastboot getvar partition-size:" + esc(partition) + " 2>&1 | grep -qF "
                + esc("partition-size:" + partition) + "; then "
                + "fastboot flash " + esc(partition) + " " + esc(imagePath) + "; "
                + "else echo " + esc(partitionMissing(partition)) + "; fi";
    }

    /**
     * 「检测到设备不存在 xxx分区，拒绝刷入此镜像」。
     *
     * <p>{@code %1$s} = 分区名。
     */
    public static String partitionMissing(String partition) {
        return "检测到设备不存在 " + partition + "分区，拒绝刷入此镜像";
    }

    /** {@code fastboot boot <img>}：不写入、仅临时启动（用于验证镜像是否可用）。 */
    public static String fastbootBoot(String imagePath) {
        return "fastboot boot " + esc(imagePath);
    }

    /**
     * 关闭 AVB 校验后刷 vbmeta。
     *
     * <p>{@code --disable-verity --disable-verification} 会关闭 dm-verity 与启动校验，
     * 使自定义镜像不被校验拦截。写前必须有明确确认提示（见调用方）。
     */
    public static String fastbootVbmetaNoVerify(String imagePath) {
        return "fastboot --disable-verity --disable-verification flash vbmeta " + esc(imagePath);
    }

    // ───────────────── Sideload ─────────────────

    /**
     * {@code adb sideload <zip>}：在 Recovery 的 apply update 模式下卡刷。
     *
     * <p>路径必须经 {@link #esc(String)} 包死 —— 卡刷包名里带空格或单引号是常态
     * （{@code PixelExperience-14-20240101.zip} 这类还好，用户自己改过名的就不好说），
     * 裸拼既会解析错，也是 root 权限下的命令注入。
     */
    public static String adbSideload(String zipPath) {
        return "adb sideload " + esc(zipPath);
    }

    /**
     * 「>>> 正在推送: %1$s」——推送开始前的提示。
     *
     * <p>{@code %1$s} = 文件名。
     * {@code adb sideload} 会把整个包推给设备侧校验，几百 MB 的包要等好一会儿，
     * 这一行是「已经开始干活了」的唯一即时反馈。
     */
    public static String sideloadPushing(String fileName) {
        return ">>> 正在推送: " + fileName;
    }

    /** 「请先选择 .zip 刷机包」——路径为空时输出。 */
    public static final String SIDELOAD_SELECT_FIRST = "请先选择 .zip 刷机包";

    /**
     * 「文件不存在: %1$s」，{@code %1$s} = 路径。
     *
     * <p>恢复上次选择的包之后文件被删 / 被移走，就会走到这一句。
     */
    public static String sideloadNotFound(String path) {
        return "文件不存在: " + path;
    }

    /** 「已选择: %1$s」，{@code %1$s} = 文件名。 */
    public static String sideloadSelected(String fileName) {
        return "已选择: " + fileName;
    }

    /**
     * 「未检测到 %1$s 设备，请连接设备后重试」（{@code toast_no_device}）。
     *
     * <p>{@code %1$s} 传通道名（{@code Sideload} / {@code ADB} / {@code Fastboot}），
     * 这样同一句话能给三个通道复用。
     */
    public static String deviceMissing(String channel) {
        return "未检测到 " + channel + " 设备，请连接设备后重试";
    }

    // ───────────────── 重启 ─────────────────

    /** 重启目标。 */
    public enum RebootTarget {
        /** 普通重启到系统。 */
        SYSTEM("系统", "", "reboot"),
        /** 重启到 Recovery。 */
        RECOVERY("Recovery", "recovery", "reboot recovery"),
        /** 重启到 Bootloader（fastboot 模式）。 */
        BOOTLOADER("Bootloader", "bootloader", "reboot-bootloader"),
        /** 重启到 Fastbootd（用户空间 fastboot，部分分区只能在此刷）。 */
        FASTBOOTD("Fastbootd", "fastboot", "reboot fastboot"),
        /** 重启到 EDL 9008 深刷模式（仅高通，且只有 ADB 通道支持）。 */
        EDL("EDL 9008", "edl", null);

        /** 界面显示名。 */
        public final String label;
        /** {@code adb reboot} 的参数；空串表示直接 {@code adb reboot}。 */
        public final String adbArg;
        /** fastboot 侧命令；null 表示 fastboot 下不可用。 */
        public final String fastbootCommand;

        RebootTarget(String label, String adbArg, String fastbootCommand) {
            this.label = label;
            this.adbArg = adbArg;
            this.fastbootCommand = fastbootCommand;
        }

        /** 是否支持从 fastboot 侧触发。 */
        public boolean supportsFastboot() {
            return fastbootCommand != null;
        }
    }

    /** {@code adb reboot [target]}。 */
    public static String adbReboot(RebootTarget target) {
        if (target == null || target.adbArg.isEmpty()) {
            return "adb reboot";
        }
        return "adb reboot " + target.adbArg;
    }

    /** {@code fastboot reboot[-xxx]}；目标不支持 fastboot 时返回 null。 */
    public static String fastbootReboot(RebootTarget target) {
        if (target == null || !target.supportsFastboot()) {
            return null;
        }
        return "fastboot " + target.fastbootCommand;
    }

    // ───────────────── 欧加真（OPPO / 一加 / realme）线刷 ─────────────────

    /**
     * 欧加系固件包里的<b>动态分区</b>清单。
     *
     * <p>这 8 个分区在
     * 超分区（{@code super}）里，只能先在 fastbootd 下「重构」再刷，不能在 bootloader 里直接刷。
     */
    public static final String[] OPLUS_LOGICAL_PARTITIONS = {
            "odm", "odm_dlkm", "system", "system_ext",
            "system_dlkm", "product", "vendor", "vendor_dlkm",
    };

    /** 定制分区，走单分区直刷。 */
    public static final String[] OPLUS_CUSTOM_PARTITIONS = {
            "my_company", "my_preload",
    };

    /**
     * 读取当前槽位：{@code fastboot getvar current-slot}。
     *
     * <p>输出形如 {@code current-slot: a}；非 A/B 设备返回
     * {@code FAILED (remote: 'GetVar Variable Not found')} —— 解析侧必须能区分这两者，
     * 因为「非 A/B」在欧加真线刷里是要直接终止的。
     */
    public static String fastbootGetCurrentSlot() {
        return "fastboot getvar current-slot";
    }

    /**
     * 读取分区大小：{@code fastboot getvar partition-size:<partition>}。
     *
     * <p>输出形如 {@code partition-size:super: 0x1D800000}（十六进制字节数），
     * 「修复 super」要拿这个值生成等大的空镜像。
     */
    public static String fastbootGetPartitionSize(String partition) {
        return "fastboot getvar partition-size:" + partition;
    }

    /**
     * 删除逻辑分区：{@code fastboot delete-logical-partition <name>}。
     *
     * <p>只在 fastbootd 下有效。分区不存在时 fastboot 会返回 {@code FAILED}，
     * 这是<b>预期内</b>的（首次刷机时本来就不存在），执行侧按非致命处理。
     */
    public static String fastbootDeleteLogicalPartition(String name) {
        return "fastboot delete-logical-partition " + esc(name);
    }

    /**
     * 创建逻辑分区：{@code fastboot create-logical-partition <name> <size>}。
     *
     * <p>{@code size} 必须是<b>展开后</b>的字节数（sparse 镜像要按 sparse 头算真实大小，
     * 见 {@code SparseImage}）—— 传 sparse 文件的磁盘长度会创建出过小的分区，
     * 后续 {@code flash} 直接失败。
     */
    public static String fastbootCreateLogicalPartition(String name, long size) {
        return "fastboot create-logical-partition " + esc(name) + " " + Math.max(size, 0L);
    }

    /** 切换活动槽位：{@code fastboot set_active <slot>}。 */
    public static String fastbootSetActive(String slot) {
        return "fastboot set_active " + esc(slot);
    }

    // ───────────────── 槽位读取 / 切换 ─────────────────

    /** 开始读槽位前的提示。 */
    public static final String SLOT_READING = "正在读取当前槽位...";

    /** 读不到槽位时的提示。 */
    public static final String SLOT_NOT_AB = "错误: 非AB分区设备，无法切换槽位";

    /**
     * 切换结果提示。
     * {@code %1$s} = 当前槽位，{@code %2$s} = 目标槽位（两者都大写）。
     */
    public static final String SLOT_SWITCH_TO = "当前槽位 %1$s，切换至 %2$s 槽位";

    /** 读到的槽位。{@code %1$s} = 槽位字母（大写）。 */
    public static final String SLOT_INFO = "当前槽位: %1$s";

    /**
     * 读槽位的 shell 前置片段：跑一次 {@code fastboot getvar current-slot}，把原始输出回显，
     * 再把槽位字母抽进 shell 变量 {@code c}（认不出就是空串）。
     *
     * <p>识别规则用正则 {@code current-slot:\s*([ab])}：必须出现
     * {@code current-slot} 且其后跟着 {@code a} 或 {@code b}；不区分大小写，冒号后允许空格。
     * 用 {@code tail -n1} 取最后一次命中，避免输出里混入回显行时取到错的。
     */
    private static String readSlotSnippet() {
        return "s=$(fastboot getvar current-slot 2>&1); echo \"$s\"; "
                + "c=$(printf '%s' \"$s\" | tr 'A-Z' 'a-z' "
                + "| grep -oE 'current-slot: *[ab]' | grep -oE '[ab]$' | tail -n1)";
    }

    /**
     * 只读并显示当前槽位：命令是 {@code fastboot getvar current-slot}，
     * 能认出槽位时再补一行「当前槽位: A」。
     *
     * <p>认不出<b>不</b>报错 —— 「读一下」这个动作本来就不该失败，
     * 非 A/B 设备的提示只在真正要切换时才给（见 {@link #fastbootSwitchSlot()}）。
     */
    public static String fastbootReportSlot() {
        return readSlotSnippet()
                + "; if [ -n \"$c\" ]; then echo \"" + slotInfoShell() + "\"; fi";
    }

    /**
     * 读取当前槽位并切到对侧槽位，整个过程合成<b>一条</b> shell。
     *
     * <p>为什么不做成「Java 读 → Java 解析 → 再发一条 {@code set_active}」：
     * {@link com.aurora.app.core.flash.FlashRuntime} 是单任务闸门，而命令跑完的收尾里会顺手
     * 刷新一次设备状态（那本身也要占闸门），紧接着再发第二条命令有概率被拒。
     * 合成一条就没有这个窗口，整个动作也是原子的 —— 中途插不进别的操作。
     *
     * <p>文案与判断顺序：
     * 先「正在读取当前槽位...」→ 跑 {@code getvar} →
     * 认不出槽位就「错误: 非AB分区设备，无法切换槽位」并<b>不</b>动手；
     * 认得出就「当前槽位 A，切换至 B 槽位」→ {@code fastboot set_active}。
     *
     * <p>读不到槽位时宁可什么都不做，也不猜一个槽位去 {@code set_active} ——
     * 切错槽位是「开机进到另一个系统」，用户很难当场判断出是被谁改的。
     */
    public static String fastbootSwitchSlot() {
        return "echo " + esc(SLOT_READING) + "; "
                + readSlotSnippet() + "; "
                + "if [ -z \"$c\" ]; then echo " + esc(SLOT_NOT_AB) + "; "
                + "else t=a; if [ \"$c\" = a ]; then t=b; fi; "
                + "echo \"" + slotSwitchShell() + "\"; "
                + "fastboot set_active \"$t\"; fi";
    }

    /** {@link #SLOT_INFO} 的 shell 形态：把占位符换成取大写槽位字母的命令替换。 */
    private static String slotInfoShell() {
        return SLOT_INFO.replace("%1$s", "$(echo $c | tr a-z A-Z)");
    }

    /** {@link #SLOT_SWITCH_TO} 的 shell 形态：{@code $c} 是当前槽位、{@code $t} 是目标槽位。 */
    private static String slotSwitchShell() {
        return SLOT_SWITCH_TO
                .replace("%1$s", "$(echo $c | tr a-z A-Z)")
                .replace("%2$s", "$(echo $t | tr a-z A-Z)");
    }

    /**
     * 从 bootloader 重启进 fastbootd：{@code fastboot reboot fastboot}。
     *
     * <p>fastbootd 是跑在 Android 用户空间里的 fastboot（内核已起来、super 已映射），
     * 动态分区只能在它下面改动。bootloader 下的 {@code fastboot flash system} 会失败。
     */
    public static String fastbootRebootFastbootd() {
        return "fastboot reboot fastboot";
    }

    /** 从 adb 重启进 bootloader：{@code adb reboot bootloader}。 */
    public static String adbRebootBootloader() {
        return "adb reboot bootloader";
    }

    /** 从 adb 直接重启进 fastbootd：{@code adb reboot fastboot}。 */
    public static String adbRebootFastbootd() {
        return "adb reboot fastboot";
    }

    /**
     * 生成指定字节数的空（全零）镜像。
     *
     * <p>{@code bs=1 count=0 seek=<size>} 是「只建文件、不写数据」的写法 ——
     * 文件系统会分配空洞（sparse file），瞬间完成且几乎不占空间；
     * 若改成 {@code dd if=/dev/zero} 全量写，一个 8GB 的 super 要写 8GB 磁盘。
     */
    public static String makeEmptyImage(String path, long size) {
        return "dd if=/dev/zero of=" + esc(path) + " bs=1 count=0 seek=" + Math.max(size, 0L);
    }

    /** 删除临时文件（失败不报错）。 */
    public static String removeFile(String path) {
        return "rm -f " + esc(path) + " 2>/dev/null; true";
    }

    /** 进入某模式后等待设备重新枚举：{@code sleep <n>; fastboot devices}。 */
    public static String waitForFastboot(int seconds) {
        return "sleep " + Math.max(seconds, 1) + "; fastboot devices";
    }

    /**
     * 执行小米线刷包里的 shell 脚本。
     *
     * <p>形式：{@code cd <目录> && sh <脚本名>}，两段都过 {@link #esc}。
     *
     * <p>为什么要先 {@code cd}：这些脚本内部大量用<b>相对路径</b>引用同目录的镜像文件
     * （{@code fastboot flash boot boot.img} 这种），不切到包目录会全部找不到；
     * 而包目录名里带空格、括号是常事，所以两侧都加引号。
     *
     * @param dir    刷机包根目录（绝对路径）
     * @param script 脚本文件名（{@code flash_all.sh} 之类）
     */
    public static String runShellScript(String dir, String script) {
        return "cd " + esc(dir) + " && sh " + esc(script);
    }

    // ───────────────── 输出解析 ─────────────────

    /**
     * 从 {@code fastboot getvar current-slot} 的输出里取槽位字母。
     *
     * <p>容错三种情况：{@code current-slot: a} / {@code current-slot:a} /
     * 只有孤零零的 {@code a}（部分 fastboot 版本回显格式不同）。
     *
     * @return {@code "a"} 或 {@code "b"}；读不到返回 {@code null}
     */
    public static String parseCurrentSlot(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            String lower = line.trim().toLowerCase(Locale.ROOT);
            if (lower.startsWith("current-slot")) {
                int colon = lower.indexOf(':');
                if (colon >= 0) {
                    String value = lower.substring(colon + 1).trim();
                    if (value.startsWith("a")) {
                        return "a";
                    }
                    if (value.startsWith("b")) {
                        return "b";
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从 {@code fastboot getvar partition-size:<p>} 的输出里取字节数。
     *
     * <p>三种写法都要认：{@code partition-size:super: 0x1D800000}（多数版本）、
     * {@code partition-size:super: 495452160}（十进制）、{@code 0x1d800000}（裸值）。
     *
     * @return 字节数；读不到或为 0 返回 -1
     */
    public static long parsePartitionSize(String output) {
        if (output == null) {
            return -1L;
        }
        long best = -1L;
        for (String line : output.split("\\R")) {
            String text = line.trim();
            if (text.isEmpty() || text.contains("FAILED") || text.contains("not found")) {
                continue;
            }
            int colon = text.lastIndexOf(':');
            String value = colon >= 0 ? text.substring(colon + 1).trim() : text;
            if (value.isEmpty()) {
                continue;
            }
            try {
                long parsed = value.toLowerCase(Locale.ROOT).startsWith("0x")
                        ? Long.parseLong(value.substring(2), 16)
                        : Long.parseLong(value);
                if (parsed > best) {
                    best = parsed;
                }
            } catch (NumberFormatException ignored) {
                // 这行不是数字，继续看下一行
            }
        }
        return best > 0 ? best : -1L;
    }

    /** 该输出是否表示设备真的处在 fastbootd（用户空间 fastboot）。 */
    public static boolean isFastbootdOutput(String output) {
        if (output == null) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("is-userspace: yes") || lower.contains("fastbootd");
    }

    // ───────────────── 维护 ─────────────────

    /**
     * 清理 adb / fastboot 残留进程。
     *
     * <p>刷机中断或异常退出后残留的进程会占住 USB 与
     * 5037 端口，必须清掉才能重新识别设备。
     */
    public static String killResidual() {
        return "pkill -9 adb 2>/dev/null; pkill -9 fastboot 2>/dev/null; true";
    }

    /** 打印两个工具的版本，用于确认工具链解压正确且可执行。 */
    public static String toolVersions() {
        return "echo -n 'adb: '; adb version 2>&1 | head -1; "
                + "echo -n 'fastboot: '; fastboot --version 2>&1 | head -1; true";
    }

    // ───────────────── 自定义命令 ─────────────────

    /** 「执行命令」页通道：直接走 adb。 */
    public static final int CHANNEL_ADB = 0;
    /** 「执行命令」页通道：直接走 fastboot。 */
    public static final int CHANNEL_FASTBOOT = 1;
    /** 「执行命令」页通道：{@code adb shell}，等价于在目标设备里执行。 */
    public static final int CHANNEL_SHELL = 2;

    /**
     * 拼一条自定义 adb 命令：{@code adb -s <序列号> <用户输入>}。
     *
     * <p><b>这里刻意不做整串转义</b>，与「外部输入一律 {@link #esc(String)} 包裹」相反：
     * 本页的输入本身就是命令行 —— 用户要写的就是 {@code shell pm list packages | grep foo}
     * 这种带管道、带引号、带重定向的写法。把整串包成单引号会让它变成一个参数，等于废掉这个功能。
     *
     * <p>代价必须说清楚：用户写的 {@code ;} / {@code &&} 是在<b>本机</b>（跑 Aurora 的这台）
     * 的 root shell 里生效，不是目标设备上。这也是执行前一定要把最终命令原文完整显示一遍的原因。
     *
     * <p>用户直接把整条 {@code adb ...} 粘进来时<b>原样返回</b>：他写的 {@code -s} 是他自己的意图，
     * 再叠一个 {@code -s} 只会得到一句读不懂的报错。
     */
    public static String customAdb(String serial, String args) {
        String trimmed = trimOrEmpty(args);
        if (startsWithTool(trimmed, "adb")) {
            return trimmed;
        }
        return "adb " + serialFlag(serial) + trimmed;
    }

    /** 拼一条自定义 fastboot 命令：{@code fastboot -s <序列号> <用户输入>}（整条粘贴时原样返回）。 */
    public static String customFastboot(String serial, String args) {
        String trimmed = trimOrEmpty(args);
        if (startsWithTool(trimmed, "fastboot")) {
            return trimmed;
        }
        return "fastboot " + serialFlag(serial) + trimmed;
    }

    /**
     * 拼一条目标设备内的 shell 命令：{@code adb -s <序列号> shell <用户输入>}。
     *
     * <p>三种输入都认：
     * <ul>
     *   <li>{@code pm list packages} —— 补 {@code adb -s X shell}；</li>
     *   <li>{@code shell pm list packages} —— 只补 {@code adb -s X}，不重复 {@code shell}；</li>
     *   <li>{@code adb shell pm list packages} —— 整条原样返回。</li>
     * </ul>
     * 不这么分的话，用户从别处粘一条完整命令过来就会得到
     * {@code adb shell adb shell ...} —— 报错还是成功都看不出原因。
     */
    public static String customShell(String serial, String args) {
        String trimmed = trimOrEmpty(args);
        if (startsWithTool(trimmed, "adb") || startsWithTool(trimmed, "fastboot")) {
            return trimmed;
        }
        if (startsWithTool(trimmed, "shell")) {
            return "adb " + serialFlag(serial) + trimmed;
        }
        return "adb " + serialFlag(serial) + "shell " + trimmed;
    }

    /**
     * 指定序列号前缀；序列号为空时返回空串（单设备场景下 adb / fastboot 自己会选）。
     *
     * <p>知道序列号就显式带上：同时插着两台设备时，不带 {@code -s} 的命令会直接报
     * {@code more than one device}，而用户看不出是哪台的问题。
     */
    private static String serialFlag(String serial) {
        if (serial == null || serial.trim().isEmpty()) {
            return "";
        }
        return "-s " + esc(serial.trim()) + " ";
    }

    /** 输入是否以某个工具名开头（后面必须跟空格，避免把 {@code adbsideload} 当成 adb）。 */
    private static boolean startsWithTool(String trimmed, String tool) {
        return trimmed.length() > tool.length()
                && trimmed.regionMatches(true, 0, tool, 0, tool.length())
                && trimmed.charAt(tool.length()) == ' ';
    }

    private static String trimOrEmpty(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** 单引号转义，与 {@link FlashRuntime#shellEscape(String)} 同规则。 */
    public static String esc(String raw) {
        return FlashRuntime.shellEscape(raw);
    }
}
