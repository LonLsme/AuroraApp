package com.aurora.app.core.flash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高通 QDL（9008 EDL 深刷）命令模板。
 *
 * <h3>这一族命令和 adb / fastboot 不是一回事</h3>
 * 既有工具没有自己写 Sahara / Firehose 协议，而是往 {@code assets} 里塞了一个
 * 定制的 <b>Linux 端 {@code qdl} 命令行工具</b>（{@code aarch64.tar -> bin/qdl}，作者改过的
 * 分支，banner 是 {@code QDL For Your Devices & LSCM}）。手机解压它之后以 root 身份直接跑，
 * 由它用 libusb 占住 USB 节点去和 9008 端口通信。
 *
 * <p>所以本类生成的命令<b>既不带 {@code adb} 也不带 {@code fastboot} 前缀</b> ——
 * 直接就是 {@code qdl ...}，由 {@link FlashRuntime} 的环境前缀把工具链 bin 目录前置进
 * {@code PATH} 后再执行。这与 {@link FlashCommands}（目标机侧通道）的形态差别很大，
 * 因此单独成类而不合并。
 *
 * <h3>命令原文出处</h3>
 * <ul>
 *   <li>{@code rgpt} 三档 —— {@code g30.java} 的 {@code a()} 三个分支；</li>
 *   <li>{@code set_slot} —— {@code j40.java}；{@code get_slot} / {@code reset} / {@code reboot}
 *       —— {@code e40.smali}（三者共用同一段拼装，只有尾部子命令不同）；</li>
 *   <li>{@code rgpt} 与 {@code set_slot} 串成一条 shell —— {@code t40.smali}；</li>
 *   <li>批量 {@code owrite} —— {@code f5.f0()}（欧加全量包：扫镜像目录 + 跳过固定分区集）；</li>
 *   <li>单分区 {@code owrite} / {@code oread} / {@code oerase} —— {@code e.smali} / {@code t8.smali}；</li>
 *   <li>{@code --debug} / {@code --dry-run} 注入 —— {@code f5.W()} + {@code fn.i()}；</li>
 *   <li>9008 端口探测 —— {@code x8.java} / {@code le1.java}：{@code lsusb | grep '05c6:9008'}；</li>
 *   <li>残留清理 —— {@code n40.java}。</li>
 * </ul>
 *
 * <p>选项名与合法取值都对着 {@code bin/qdl} 的字符串表核对过，不是照着用法说明猜的：
 * {@code -s/--storage} 只认 {@code emmc|nand|nvme|spinor|ufs}，{@code --oplus-token} 可带可选
 * {@code PROJID}（既有工具不传），{@code -R/--skip-reset}、{@code -n/--dry-run}、
 * {@code -d/--debug}、{@code -u/--out-chunk-size} 均为工具自带选项。
 *
 * <p>本类只生成字符串，不执行 —— 执行统一走 {@link FlashRuntime}。
 * 所有拼进命令的外部输入（引导文件路径、镜像路径、分区名）必须经 {@link #esc(String)} 包裹。
 */
public final class QdlCommands {

    private QdlCommands() {
    }

    // ───────────────── 常量 ─────────────────

    /**
     * {@code rgpt} 的分区表输出目录。
     *
     * <p>既有工具里这个路径是硬编码常量（不是 {@code filesDir}）：{@code rgpt} 会把各 LUN 的 GPT
     * 导成 {@code gpt_main*.bin} 连同 {@code partitions.txt} 落在这里，之后的 {@code owrite} /
     * {@code set_slot} 都依赖这批中间产物 —— {@code qdl} 找不到时自己的报错原文是
     * 「{@code Cannot open %s. Please run 'rgpt' first.}」。
     */
    public static final String OUTPUT_DIR = "/storage/emulated/0/Aurora/qdl";

    /** {@code rgpt} 产出的分区清单（每行形如 {@code 名字,标签}），批量写入靠它判断 A/B 配对。 */
    public static final String PARTITIONS_FILE = OUTPUT_DIR + "/partitions.txt";

    /**
     * {@code rgpt} 用的分片大小。
     *
     * <p>只有欧加授权两档（VIP / Token）才带 {@code --out-chunk-size=16384}，
     * 默认档不带 —— 这是既有工具的实际形态，不是漏写。
     */
    public static final int DEFAULT_CHUNK_SIZE = 16384;

    /** 9008 EDL 端口的 USB 标识（{@code qdl} 二进制里也是按 {@code 05c6:%04x} 拼的）。 */
    public static final String EDL_USB_ID = "05c6:9008";

    /** 设备状态显示用的通道名，与既有工具 {@code le1.E(context, "9008/EDL")} 同字面量。 */
    public static final String EDL_CHANNEL = "9008/EDL";

    /**
     * 热拔插警告文案（既有工具字符串资源 {@code qdl_hotplug_warning}）。
     *
     * <p>不是吓唬人：{@code qdl} 二进制里的报错原文是
     * 「{@code physically replug into 9008 (bus reset can't revive a wedged Qualcomm PBL)}」——
     * PBL 卡死时软复位救不回来，只能把线拔了重插。
     */
    public static final String HOTPLUG_WARNING = "⚠️9008不支持热拔插，连上设备后不得断开，并且确保USB连接稳定";

    /** 高通设备最多 6 个 LUN（0..5），{@code qdl} 的报错原文是 {@code Reading GPT tables from all LUNs (0-5)}。 */
    public static final int LUN_COUNT = 6;

    /**
     * 批量写入时<b>跳过</b>的分区。
     *
     * <p>取自既有工具 {@code f5.f0()} 的 {@code vt1.X(...)}：动态分区（走 super）、
     * {@code super} 本体与 {@code xbl} 都不在这个循环里刷 —— 它们要么由单独的
     * {@code owrite super} 处理，要么根本不该由用户态镜像覆盖。
     * 另有「{@code my_} 开头的定制分区」同样跳过。
     */
    public static final Set<String> SKIP_PARTITIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "odm", "odm_dlkm", "system", "system_ext", "system_dlkm",
                    "product", "vendor", "vendor_dlkm",
                    "super", "super_a", "super_b", "xbl")));

    /** 存储类型，取值来自 {@code qdl -h} 的 {@code T: <emmc|nand|nvme|spinor|ufs>}。 */
    public enum StorageType {
        /** UFS（绝大多数现代高通机型）。 */
        UFS("ufs"),
        /** eMMC。 */
        EMMC("emmc"),
        /** NAND。 */
        NAND("nand"),
        /** NVMe。 */
        NVME("nvme"),
        /** SPI NOR。 */
        SPINOR("spinor");

        /** 传给 {@code --storage=} 的字面量。 */
        public final String value;

        StorageType(String value) {
            this.value = value;
        }
    }

    /**
     * 欧加（OPPO / 一加 / realme）授权档位。
     *
     * <p>与既有工具 {@code f5.q(o11)} 的返回值一一对应：{@code 0 / 1 / 2}。
     * 界面上的文案见 {@code qdl_oplus_auth_off} / {@code _vip} / {@code _token}。
     */
    public enum OplusAuth {
        /** 未开启：命令不带任何授权参数。 */
        OFF,
        /**
         * VIP 授权：引导是欧加，且包内同时有 Sign（签名）与 Digest（摘要）文件。
         * 只有 {@code rgpt} 会用两个 {@code --signeddigests} 带出这两个文件。
         */
        VIP,
        /**
         * Token 鉴权：引导是欧加但包内没有 sign / digest。
         * 追加 {@code --oplus-token}，<b>所有</b>子命令都带。
         */
        TOKEN,
    }

    // ───────────────── rgpt：识别分区表 ─────────────────

    /**
     * {@code qdl <...> rgpt <输出目录>} —— 读取各 LUN 的 GPT 表，导出到 {@link #OUTPUT_DIR}。
     *
     * <p>三个阶段里的第一阶段（{@code qdl_step1_gpt}「[1/3] 读取GPT分区表...」）。
     * 之后写入 / 切槽位都依赖它的产物，所以任何批量操作之前都必须先跑通这一条。
     *
     * @param storage  存储类型
     * @param progPath 引导文件（{@code prog.mbn} / {@code DevprgProgrammer.elf}）在本机的绝对路径
     * @param auth     欧加授权档位
     * @param signPath   VIP 档的签名文件路径；非 VIP 档可为 {@code null}
     * @param digestPath VIP 档的摘要文件路径；非 VIP 档可为 {@code null}
     */
    public static String rgpt(StorageType storage, String progPath, OplusAuth auth,
                              String signPath, String digestPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("mkdir -p ").append(esc(OUTPUT_DIR)).append(" 2>/dev/null; qdl");
        if (auth == OplusAuth.VIP) {
            sb.append(" --out-chunk-size=").append(DEFAULT_CHUNK_SIZE);
            sb.append(" --storage=").append(storage.value);
            sb.append(" --signeddigests=").append(esc(signPath));
            sb.append(" --signeddigests=").append(esc(digestPath));
        } else if (auth == OplusAuth.TOKEN) {
            sb.append(" --out-chunk-size=").append(DEFAULT_CHUNK_SIZE);
            sb.append(" --storage=").append(storage.value);
            sb.append(" --oplus-token");
        } else {
            sb.append(" --storage=").append(storage.value);
        }
        sb.append(" --skip-reset ").append(esc(progPath));
        sb.append(" rgpt ").append(esc(OUTPUT_DIR));
        return sb.toString();
    }

    /**
     * 授权后缀，沿用既有工具的 {@code f5.S(o11)}：
     * {@code return q(o11Var) == 2 ? " --oplus-token" : "";}。
     *
     * <p><b>注意 VIP 档在这里返回空串</b> —— 因为 {@code --signeddigests} 只在 {@code rgpt}
     * 那一条命令里出现，{@code set_slot} / {@code get_slot} / {@code reset} 这些子命令
     * 既有工具<b>不</b>带任何授权参数。这是它的实际行为，照搬而不是「顺手补全」。
     */
    private static String authSuffix(OplusAuth auth) {
        return auth == OplusAuth.TOKEN ? " --oplus-token" : "";
    }

    // ───────────────── 槽位 ─────────────────

    /**
     * {@code qdl <...> set_slot <a|b>} —— 把设备的启动槽位切到指定值。
     *
     * <p>{@code qdl} 侧要求该设备在 GPT 里有 {@code _a} / {@code _b} 成对分区，
     * 否则报错原文是 {@code set_slot: no _a/_b pairs found on LUN %d}。
     *
     * @param slot {@code "a"} 或 {@code "b"}；非法值会被 {@link #isValidSlot(String)} 挡掉
     */
    public static String setSlot(StorageType storage, String progPath, OplusAuth auth, String slot) {
        return subcommand(storage, progPath, auth, "set_slot " + esc(slot));
    }

    /**
     * {@code qdl <...> get_slot} —— 读当前活跃槽位。
     *
     * <p>输出要靠 {@link #parseActiveSlot(String)} 解析；解析失败时既有工具给出的文案是
     * {@code qdl_slot_fail}「无法获取活跃槽位（qdl get_slot 解析失败）」。
     */
    public static String getSlot(StorageType storage, String progPath, OplusAuth auth) {
        return subcommand(storage, progPath, auth, "get_slot");
    }

    /**
     * {@code qdl <...> reset} —— 复位设备（让它从 9008 重新启动）。
     *
     * <p>这条命令本身也带 {@code --skip-reset}，看着别扭但确实是既有工具原文
     * （{@code e40.smali} 里三条子命令共用同一段拼装）。{@code -R} 的语义是「刷写完成后
     * 不要自动发 reset」，与这里主动发 {@code reset} 子命令不冲突。
     */
    public static String reset(StorageType storage, String progPath, OplusAuth auth) {
        return subcommand(storage, progPath, auth, "reset");
    }

    /** {@code qdl <...> reboot} —— 让设备重启回系统。 */
    public static String reboot(StorageType storage, String progPath, OplusAuth auth) {
        return subcommand(storage, progPath, auth, "reboot");
    }

    /**
     * 所有子命令共用的前缀：{@code qdl --storage=X[ --oplus-token] --skip-reset '<prog>'}。
     *
     * <p>形态与 {@code j40.java} / {@code e40.smali} / {@code f5.f0()} 逐字一致。
     * {@code --skip-reset} 的位置在引导文件<b>之前</b>，不要挪动；引导文件必须转义。
     *
     * <p>单独抽出来是因为后面有一串「往同一条命令尾巴上追加多个子操作」的场景
     * （批量 owrite / 批量 oread），那些地方不能再用 {@link #subcommand} 补一个空格。
     */
    private static String prefix(StorageType storage, String progPath, OplusAuth auth) {
        return "qdl --storage=" + storage.value + authSuffix(auth)
                + " --skip-reset " + esc(progPath);
    }

    /** 前缀 + 单个子命令；{@code tail} 为空时不补尾随空格。 */
    private static String subcommand(StorageType storage, String progPath, OplusAuth auth, String tail) {
        String head = prefix(storage, progPath, auth);
        return tail == null || tail.isEmpty() ? head : head + " " + tail;
    }

    /**
     * {@code rgpt} 与 {@code set_slot} 合成<b>一条</b> shell，中间用 {@code &&} 连接。
     *
     * <p>形式取自 {@code t40.smali}：{@code <rgpt 命令> && qdl --storage=X ... set_slot '<a|b>'}。
     *
     * <p>为什么不拆两条发：{@link FlashRuntime} 是单任务闸门，命令跑完的收尾里会顺手刷新
     * 设备状态（那也要占闸门），紧接着再发第二条有被拒的窗口。合成一条之后整个动作是原子的，
     * 也保证了「先 rgpt 再切槽位」的顺序不会被别的操作插进来。
     */
    public static String rgptThenSetSlot(StorageType storage, String progPath, OplusAuth auth,
                                         String signPath, String digestPath, String slot) {
        return rgpt(storage, progPath, auth, signPath, digestPath)
                + " && " + subcommand(storage, progPath, auth, "set_slot " + esc(slot));
    }

    // ───────────────── 单分区操作 ─────────────────

    /**
     * {@code owrite <分区> '<镜像>'} —— 按<b>分区名</b>写入一个镜像。
     *
     * <p>{@code qdl} 有两条寻址路线：{@code (read|write|erase) <address>}（按 LUN + 扇区地址）
     * 与 {@code (oread|owrite|oerase) <name> <Path>}（按分区名）。既有工具用后者 ——
     * 它靠 {@code rgpt} 导出的 GPT 表把分区名解析成地址，这正是「必须先跑 rgpt」的原因。
     *
     * <p>路径用单引号包死：镜像文件名里带空格、括号是常态，且这是 root 权限下的命令注入面。
     */
    public static String owrite(StorageType storage, String progPath, OplusAuth auth,
                                String partition, String imagePath) {
        return subcommand(storage, progPath, auth, "owrite " + esc(partition) + " " + esc(imagePath));
    }

    /**
     * {@code oread <分区> '<输出路径>'} —— 把设备上一个分区读出来存成镜像。
     *
     * <p>输出路径固定为 {@code <OUTPUT_DIR>/<分区名>.img}，与既有工具 {@code t8.smali}
     * 里 read 分支的拼装完全一致（它是把常量 {@code "/storage/emulated/0/Aurora/qdl/"}、
     * 分区名、{@code ".img"} 三段拼起来再过转义）。
     *
     * @param partition 目标分区名（读出来的文件名也用它）
     */
    public static String oread(StorageType storage, String progPath, OplusAuth auth, String partition) {
        String outPath = OUTPUT_DIR + "/" + partition + ".img";
        return subcommand(storage, progPath, auth, "oread " + esc(partition) + " " + esc(outPath));
    }

    /**
     * {@code oerase <分区>} —— 按分区名擦除。
     *
     * <p>既有工具给这条操作配了强警告（{@code qdl_part_erase_warn}）：
     * 「此操作不可撤销；若包含系统关键分区（如 xbl、persist、super 等），设备可能无法开机。」
     */
    public static String oerase(StorageType storage, String progPath, OplusAuth auth, String partition) {
        return subcommand(storage, progPath, auth, "oerase " + esc(partition));
    }

    /**
     * 批量提取：把勾选的多个分区一次读出来。
     *
     * <p>拼接形式取自 {@code g30.java} 的 {@code extract} 分支，有两点与别处不同：
     * <ol>
     *   <li>前面多一句 {@code mkdir -p '<输出目录>'} —— 提取是往目录里写文件，
     *       目录不存在时 {@code qdl} 自己不会建；</li>
     *   <li><b>不带 {@code --skip-reset}</b> —— 提取不改设备，没有「刷完要不要复位」的问题。</li>
     * </ol>
     */
    public static String oreadAll(StorageType storage, String progPath, OplusAuth auth,
                                  List<String> partitions) {
        StringBuilder sb = new StringBuilder();
        sb.append("mkdir -p ").append(esc(OUTPUT_DIR)).append(" && ").append(prefix(storage, progPath, auth));
        for (String partition : partitions) {
            if (partition == null || partition.trim().isEmpty()) {
                continue;
            }
            String name = partition.trim();
            sb.append(" oread ").append(esc(name));
            sb.append(" ").append(esc(OUTPUT_DIR + "/" + name + ".img"));
        }
        return sb.toString();
    }

    /** 一个「分区 -> 镜像路径」的绑定，用于按勾选逐条写入。 */
    public static final class ImageRef {
        /** 目标分区名。 */
        public final String partition;
        /** 本机上的镜像绝对路径。 */
        public final String imagePath;

        public ImageRef(String partition, String imagePath) {
            this.partition = partition;
            this.imagePath = imagePath;
        }
    }

    /**
     * 批量写入（按勾选的分区 + 各自选定的镜像）。
     *
     * <p>形式取自 {@code g30.java} 的 {@code write} 分支：{@code qdl --storage=X ... --skip-reset
     * '<prog>' } 后面跟一串 {@code owrite '<分区>' '<镜像>'}。
     *
     * <p>不在这里做「哪些该跳过」的判断 —— 调用方勾了什么就写什么，这是「单分区写入」的语义；
     * 全量包的跳过规则在 {@link #owriteAll}。
     */
    public static String owriteSelected(StorageType storage, String progPath, OplusAuth auth,
                                        List<ImageRef> refs) {
        StringBuilder sb = new StringBuilder(prefix(storage, progPath, auth));
        for (ImageRef ref : refs) {
            if (ref == null || ref.partition == null || ref.imagePath == null) {
                continue;
            }
            sb.append(" owrite ").append(esc(ref.partition.trim()));
            sb.append(" ").append(esc(ref.imagePath));
        }
        return sb.toString();
    }

    // ───────────────── 全量写入（欧加真全量包） ─────────────────

    /**
     * 全量写入：扫镜像目录，逐条 {@code owrite}，并按固定规则跳过一部分分区。
     *
     * <p>按同样顺序拼装既有工具 {@code f5.f0()}：
     * <ol>
     *   <li>先写 super：{@code owrite super '<super镜像>'}；</li>
     *   <li>再遍历镜像目录里的 {@code *.img}（按文件名排序，保证命令可复现），
     *       名字去掉 {@code .img} 后缀并转小写；</li>
     *   <li>落在 {@link #SKIP_PARTITIONS} 里的、或 {@code my_} 开头的 —— 跳过；</li>
     *   <li>在 {@code partitions.txt} 里有 {@code _a} / {@code _b} 成对记录的 ——
     *       写成 {@code owrite <名>_<槽位> '<镜像>'}（只写当前要刷的那一侧）；</li>
     *   <li>其余 —— {@code owrite <名> '<镜像>'}。</li>
     * </ol>
     *
     * <p>第 4 步是关键：全量包里的镜像名是不带槽位后缀的（{@code vendor.img}），但设备上的
     * 分区是 {@code vendor_a} / {@code vendor_b}。写成 {@code owrite vendor} 在 A/B 设备上
     * 会落到哪个槽位是不确定的 —— 所以要靠分区表把它们显式指定到目标槽位。
     *
     * @param superImagePath 已构建好的 super 镜像路径；传 {@code null} 则跳过这一步
     * @param imagePaths     镜像目录下的文件路径列表（内部会排序）
     * @param pairedBaseNames {@code partitions.txt} 里 A/B 成对分区的基名集合，
     *                        由 {@link #pairedBaseNames(String)} 从文件内容解析
     */
    public static String owriteAll(StorageType storage, String progPath, OplusAuth auth,
                                   String slot, String superImagePath,
                                   List<String> imagePaths, Set<String> pairedBaseNames) {
        StringBuilder sb = new StringBuilder(prefix(storage, progPath, auth));
        if (superImagePath != null && !superImagePath.isEmpty()) {
            sb.append(" owrite ").append(esc("super")).append(" ").append(esc(superImagePath));
        }
        List<String> sorted = new ArrayList<>(imagePaths);
        Collections.sort(sorted);

        for (String path : sorted) {
            if (path == null) {
                continue;
            }
            String base = stripImgSuffix(baseName(path));
            if (SKIP_PARTITIONS.contains(base) || base.startsWith("my_")) {
                continue;
            }
            boolean paired = pairedBaseNames != null && pairedBaseNames.contains(base);
            String partition = paired ? base + "_" + slot : base;
            sb.append(" owrite ").append(esc(partition));
            sb.append(" ").append(esc(path));
        }
        return sb.toString();
    }

    /**
     * 从 {@code partitions.txt} 的内容里解析出「A/B 成对」的分区基名集合。
     *
     * <p>算法照抄 {@code f5.f0()}：每行按 {@code ,} 取第一个字段、trim、转小写；
     * 分别收集以 {@code _a} 结尾与以 {@code _b} 结尾的，各自去掉后缀得到基名，
     * 两集合的<b>交集</b>就是「确实成对」的分区。
     *
     * <p>求交集而不是取并集是有意的：只有一个 {@code _a} 而没有 {@code _b} 的分区不算成对，
     * 那种情况在 {@code qdl} 侧会直接报
     * {@code set_slot: cross-LUN: no _b for %s}。
     */
    public static Set<String> pairedBaseNames(String partitionsTxt) {
        Set<String> a = new HashSet<>();
        Set<String> b = new HashSet<>();
        if (partitionsTxt == null) {
            return Collections.emptySet();
        }
        for (String line : partitionsTxt.split("\\R")) {
            String field = line;
            int comma = field.indexOf(',');
            if (comma >= 0) {
                field = field.substring(0, comma);
            }
            field = field.trim().toLowerCase(Locale.ROOT);
            if (field.isEmpty()) {
                continue;
            }
            if (field.endsWith("_a")) {
                a.add(field.substring(0, field.length() - 2));
            } else if (field.endsWith("_b")) {
                b.add(field.substring(0, field.length() - 2));
            }
        }
        a.retainAll(b);
        return a;
    }

    // ───────────────── 调试开关注入 ─────────────────

    /**
     * 往命令里的每个 {@code qdl} 后面插 {@code --debug} / {@code --dry-run}。
     *
     * <p>算法沿用既有工具 {@code f5.W()}：用负向先行断言避免重复插入 ——
     * 正则 {@code \bqdl\b(?!\s+--debug|\s+--dry-run)} 只匹配「后面还没跟这两个开关」的 {@code qdl}。
     * 两个开关的组合对应三个替换文本：{@code qdl --debug --dry-run} / {@code qdl --debug} /
     * {@code qdl --dry-run}（{@code fn.i()} 的 13 / 14 / 15 分支）。
     *
     * <p>合成命令里可能有<b>两个</b> {@code qdl}（{@code rgpt && set_slot}），
     * 替换是全局的，两个都会被加上 —— 与既有工具一致。
     *
     * <p>开关语义（{@code --dry-run} 是空跑：不连设备、不真实刷写）来自
     * {@code qdl_dry_run_summary}：官方选项说明是 {@code -n, --dry-run / Dry run execution,
     * no device reading or flashing}。所以这也是唯一能在<b>不接设备</b>的情况下验证命令拼装的手段。
     */
    public static String applyDebugFlags(String command, boolean debug, boolean dryRun) {
        if (command == null || (!debug && !dryRun)) {
            return command;
        }
        String replacement = debug && dryRun ? "qdl --debug --dry-run"
                : debug ? "qdl --debug" : "qdl --dry-run";
        return QDL_WORD.matcher(command).replaceAll(Matcher.quoteReplacement(replacement));
    }

    /** 匹配「后面还没跟 {@code --debug} / {@code --dry-run}」的 {@code qdl} 词。 */
    private static final Pattern QDL_WORD =
            Pattern.compile("\\bqdl\\b(?!\\s+--debug|\\s+--dry-run)");

    // ───────────────── 设备探测与清理 ─────────────────

    /**
     * 探测是否有设备挂在 9008 端口。
     *
     * <p>命令取自 {@code x8.java} / {@code le1.java}：
     * {@code lsusb 2>/dev/null | grep -i '05c6:9008'}。有匹配输出即表示在端口上，
     * 调用方按「输出非空」判断，不要去解析退出码（{@code grep} 无匹配时返回 1，属正常情况）。
     *
     * <p>{@code 05c6} 是高通 Vendor ID，{@code 9008} 是 EDL 模式的 Product ID ——
     * {@code qdl} 二进制内部也是按 {@code 05c6:%04x} 拼这个标识去认设备的。
     */
    public static String detectEdl() {
        return "lsusb 2>/dev/null | grep -i '" + EDL_USB_ID + "'";
    }

    /**
     * 清理 adb / fastboot / qdl 残留进程。
     *
     * <p>取自 {@code n40.java}：{@code pkill adb; pkill fastboot; pkill qdl}。
     * 比 {@link FlashCommands#killResidual()} 多一个 {@code qdl} —— 现在三条通道都可能留残留。
     *
     * <p>注意它<b>没带</b> {@code -9}，也没在末尾补 {@code true}：三个 {@code pkill} 只要有一个
     * 命中就返回 0，全都没命中则返回非 0。调用方不该把退出码当成「清理失败」的依据。
     */
    public static String killResidual() {
        return "pkill adb; pkill fastboot; pkill qdl";
    }

    // ───────────────── 输出解析 ─────────────────

    /** {@code qdl get_slot} 输出的槽位正则，沿用既有工具：{@code slot.*[=:].*([abAB])}。 */
    private static final Pattern SLOT_PATTERN =
            Pattern.compile("slot.*[=:].*?([abAB])");

    /**
     * 从 {@code qdl get_slot} 的输出里取槽位字母。
     *
     * <p>既有工具是逐行跑 {@code slot.*[=:].*([abAB])}，命中即取第一个捕获组。
     * 这里同样逐行扫（避免跨行误匹配），但把第二个量词改成<b>非贪婪</b> ——
     * 原标题用贪婪的 {@code .*} 时，只要该行在槽位字母后面还有别的英文单词
     * （例如 {@code slot: b, inactive}），就会一路回溯到末尾的那个 {@code a}／{@code b}
     * 上取错值。这是本项目里少数几处有意偏离原文的地方，因为读错槽位会导致写错分区。
     *
     * @return {@code "a"} 或 {@code "b"}（小写）；读不到返回 {@code null}
     */
    public static String parseActiveSlot(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            Matcher matcher = SLOT_PATTERN.matcher(line);
            if (matcher.find()) {
                return matcher.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * 从 {@code rgpt} 输出里数出「读到 GPT 的 LUN 个数」，用于判断分区表是否真的导出来了。
     *
     * <p>依据是 {@code qdl} 自己的两行输出：
     * {@code Reading GPT tables from all LUNs (0-5)} 与 {@code "  LUN %d GPT saved to %s"}。
     * 后者每导出一个 LUN 打一次，数它最直接。数不到 0 时返回 0，
     * 上层可对照 {@code qdl_no_gpt}「未找到分区表，请先执行【识别分区表】」提示重跑。
     */
    public static int countSavedLuns(String output) {
        if (output == null) {
            return 0;
        }
        int count = 0;
        for (String line : output.split("\\R")) {
            if (line.contains("GPT saved to")) {
                count++;
            }
        }
        return count;
    }

    // ───────────────── 输入校验 ─────────────────

    /** 槽位只能是 {@code a} 或 {@code b}（不区分大小写）。 */
    public static boolean isValidSlot(String slot) {
        return slot != null && ("a".equalsIgnoreCase(slot.trim()) || "b".equalsIgnoreCase(slot.trim()));
    }

    /**
     * 分区名是否「看起来像」分区名 —— 与 {@link FlashCommands#isValidPartitionName(String)} 同规则。
     *
     * <p>同样<b>不是</b>安全边界：真正的防注入是 {@link #esc(String)}。
     * 这里只把明显不像分区名的输入（粘进了一条路径、带了空格）拦在确认框之前。
     */
    public static boolean isValidPartitionName(String name) {
        return FlashCommands.isValidPartitionName(name);
    }

    // ───────────────── 小工具 ─────────────────

    /** 取路径里的文件名部分（兼容 Windows 与 Unix 两种分隔符）。 */
    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** 去掉 {@code .img} 后缀（不区分大小写，只在确实以它结尾时去）。 */
    private static String stripImgSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".img") ? name.substring(0, name.length() - 4) : name;
    }

    /** 单引号转义，与 {@link FlashRuntime#shellEscape(String)} 同规则。 */
    public static String esc(String raw) {
        return FlashRuntime.shellEscape(raw);
    }
}
