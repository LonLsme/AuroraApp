package com.aurora.app.core.flash;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 欧加真（OPPO / 一加 / realme）线刷流程编排。
 *
 * <h3>步骤划分的依据</h3>
 * 分几步不是拍脑袋定的，是设备侧的硬约束 + 踩过坑的结论：
 * <ul>
 *   <li><b>常规线刷</b>三步：第一步在 fastboot 下刷关键镜像 →
 *       第二步重构逻辑分区 → 第三步在 fastbootd 中刷剩余镜像。
 *       关键点：{@code boot} / {@code dtbo} / {@code vbmeta} 这些<b>物理分区只有 bootloader
 *       能写</b>，{@code system} / {@code vendor} 这些<b>逻辑分区只有 fastbootd 能写</b>，
 *       所以必须分两段跑、中间重启一次模式，不可能一口气刷完。</li>
 *   <li><b>纯FastbootD</b>六步：检测设备状态 → 检测 AB 分区 →
 *       重构逻辑分区 → 统计任务数 → 刷写到目标槽位 → 激活目标槽位。
 *       它面向 A/B 设备的「刷到备用槽再切过去」用法，全程不出 fastbootd。</li>
 *   <li><b>修复Super分区</b>：读 {@code super} 分区大小 →
 *       生成等大空镜像 → 刷入 → 完成。修复本身会清空超分区，所以文案里带一句
 *       「下一步进行线刷」。</li>
 * </ul>
 *
 * <h3>为什么不用一次性长脚本</h3>
 * 每一步都要<b>看上一步的输出</b>才能决定下一步：槽位来自
 * {@code getvar current-slot}，逻辑分区大小来自本地解析 sparse 头，fastbootd 是否真的起来了
 * 要读 {@code getvar is-userspace}。写成一条 {@code &&} 串起来的脚本，
 * 中间任何一步语义变化都会静默走错分支。这里的做法是「执行一步 → 解析 → 出下一步」。
 *
 * <h3>串行与并发</h3>
 * 复用 {@link FlashRuntime} 的全局单任务闸门：同一时刻全 App 只有一个刷机任务。
 * 每一步在 {@code onExit} 里入队下一步 —— {@link FlashRuntime} 是先放开 busy
 * 再回调 {@code onExit}，所以从回调里再次提交是安全的，不会自锁。
 */
public final class OplusFlasher {

    private static final String TAG = "OplusFlasher";

    /** 进入 fastboot / fastbootd 后等待设备重新枚举的秒数。 */
    private static final int MODE_SWITCH_WAIT_SEC = 10;

    /** 刷机模式。 */
    public enum Mode {
        /** 常规线刷：bootloader 刷物理分区 + fastbootd 刷逻辑分区（当前槽位）。 */
        NORMAL("常规线刷"),
        /** 纯FastbootD：全程 fastbootd，刷到备用槽位并激活过去。 */
        PURE_FASTBOOTD("纯FastbootD"),
        /** 修复 Super 分区：写一个等大的空镜像，把超分区重置干净。 */
        FIX_SUPER("修复Super分区");

        /** 界面上显示的名字，同时用于日志分隔条。 */
        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** 输出回调。<b>全部在后台线程触发</b>，更新 UI 请自行 post 回主线程。 */
    public interface Output {
        /** 一行输出（含步骤横幅）。 */
        void line(String text);

        /** 流程结束。{@code ok} 为真表示全部步骤正常跑完。 */
        void finished(boolean ok);
    }

    // ───────────────── 目录扫描 ─────────────────

    /** 镜像目录扫描结果。 */
    public static final class Inventory {
        /** 目录本身。 */
        public final File dir;
        /** bootloader 阶段刷的物理分区镜像（boot / dtbo / vbmeta / recovery …）。 */
        public final List<File> physicalImages;
        /** fastbootd 阶段刷的动态分区镜像（odm / system / vendor …）。 */
        public final List<File> logicalImages;
        /** 定制分区镜像（my_company / my_preload）。 */
        public final List<File> customImages;
        /** 校验失败原因；{@code null} 表示目录可用。 */
        public final String error;

        Inventory(File dir, List<File> physical, List<File> logical,
                  List<File> custom, String error) {
            this.dir = dir;
            this.physicalImages = Collections.unmodifiableList(physical);
            this.logicalImages = Collections.unmodifiableList(logical);
            this.customImages = Collections.unmodifiableList(custom);
            this.error = error;
        }

        /** 目录是否可用。 */
        public boolean valid() {
            return error == null;
        }

        /** 参与刷写的镜像总数（不含 super 相关内容）。 */
        public int total() {
            return physicalImages.size() + logicalImages.size() + customImages.size();
        }
    }

    /**
     * 扫描并分类镜像目录。
     *
     * <p>{@code super} 相关的文件（{@code super.img} / {@code super_a.img} /
     * {@code super_def.*.json}）<b>不参与</b>逐个分区刷写 —— 它们归超分区那套逻辑管，
     * 混进来会把超分区写坏。
     */
    public static Inventory scan(File dir) {
        List<File> physical = new ArrayList<>();
        List<File> logical = new ArrayList<>();
        List<File> custom = new ArrayList<>();

        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return new Inventory(dir, physical, logical, custom, "目录不存在或不是有效目录");
        }

        File[] files = dir.listFiles((FilenameFilter) (d, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".img"));
        if (files == null) {
            return new Inventory(dir, physical, logical, custom, "目录不可读");
        }
        if (files.length == 0) {
            return new Inventory(dir, physical, logical, custom, "目录里没有任何 .img 文件");
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        for (File file : files) {
            String name = file.getName();
            if (SparseImage.isEmptySuperArtifact(name)) {
                continue;   // super_def.*.json 之类，不在这里处理
            }
            String partition = SparseImage.partitionNameOf(name);
            if (SparseImage.isSuperRelated(partition)) {
                continue;   // super / super_a / super_b 交给修复流程
            }
            if (contains(FlashCommands.OPLUS_LOGICAL_PARTITIONS, partition)) {
                logical.add(file);
            } else if (contains(FlashCommands.OPLUS_CUSTOM_PARTITIONS, partition)) {
                custom.add(file);
            } else {
                physical.add(file);
            }
        }

        if (physical.isEmpty() && logical.isEmpty() && custom.isEmpty()) {
            return new Inventory(dir, physical, logical, custom,
                    "目录里只有 super 相关文件，没有可刷写的分区镜像");
        }
        return new Inventory(dir, physical, logical, custom, null);
    }

    private static boolean contains(String[] array, String value) {
        for (String item : array) {
            if (item.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    // ───────────────── 计划预览 ─────────────────

    /**
     * 生成给确认框看的计划摘要。
     *
     * <p>槽位与超分区大小只有运行时才读得到，这里用「运行时读取」占位 ——
     * 不编假数据，也不因此少写一项。
     */
    public static String describe(Mode mode, Inventory inv) {
        StringBuilder sb = new StringBuilder();
        sb.append("目录：").append(inv.dir == null ? "-" : inv.dir.getName()).append('\n');

        switch (mode) {
            case FIX_SUPER:
                sb.append('\n')
                        .append("1. 读取 super 分区大小\n")
                        .append("2. 本地生成等大的空镜像\n")
                        .append("3. fastboot flash super <空镜像>\n")
                        .append("4. 删除临时文件\n\n")
                        .append("这会清空超分区里的全部逻辑分区，设备将无法开机 —— ")
                        .append("修复后必须紧接着做一次线刷。");
                break;
            case NORMAL:
                sb.append("物理分区 ").append(inv.physicalImages.size()).append(" 个\n")
                        .append("逻辑分区 ").append(inv.logicalImages.size()).append(" 个\n");
                if (!inv.customImages.isEmpty()) {
                    sb.append("定制分区 ").append(inv.customImages.size()).append(" 个\n");
                }
                sb.append('\n')
                        .append("第一步在 fastboot 下刷物理分区，")
                        .append("第二步重启进 fastbootd 重构逻辑分区，")
                        .append("第三步刷入逻辑分区。\n\n")
                        .append("刷写目标：当前槽位（运行时检测）");
                break;
            case PURE_FASTBOOTD:
                sb.append("逻辑分区 ").append(inv.logicalImages.size()).append(" 个\n");
                if (!inv.customImages.isEmpty()) {
                    sb.append("定制分区 ").append(inv.customImages.size()).append(" 个\n");
                }
                sb.append('\n')
                        .append("全程在 fastbootd 下执行，刷入备用槽位后自动 set_active 切过去。\n\n");
                if (!inv.physicalImages.isEmpty()) {
                    sb.append("目录里的 ").append(inv.physicalImages.size())
                            .append(" 个物理分区镜像（boot / dtbo 等）本次不会刷 —— ")
                            .append("fastbootd 写不了物理分区。");
                }
                break;
            default:
                break;
        }
        return sb.toString();
    }

    // ───────────────── 执行 ─────────────────

    /**
     * 一个执行步骤。
     *
     * <p>{@code banner} 在<b>执行前</b>打印 —— 构建计划时就把所有步骤的描述打出来，
     * 日志里的进度会全挤在开头，完全看不出「现在跑到哪了」。
     */
    private static final class Step {
        final String command;
        final String banner;
        final Handler then;

        Step(String command, String banner, Handler then) {
            this.command = command;
            this.banner = banner;
            this.then = then;
        }
    }

    /** 步骤结束后的处理：读输出、决定下一步。 */
    private interface Handler {
        void handle(int exitCode, String output);
    }

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private static OplusFlasher active;

    private final Context appContext;
    private final Output output;
    private final ArrayDeque<Step> queue = new ArrayDeque<>();

    /** 当前步骤的输出缓冲（回调里写、handler 里读）。 */
    private final StringBuilder stepOutput = new StringBuilder();

    /** 运行期状态。 */
    private String currentSlot = "a";
    private String targetSlot = "b";
    private long superSize = -1L;
    private File tempSuperImage;

    private volatile boolean cancelled;
    private boolean failed;

    private OplusFlasher(Context context, Output output) {
        this.appContext = context.getApplicationContext();
        this.output = output;
    }

    /** 是否有欧加真线刷任务在执行。 */
    public static boolean isBusy() {
        return BUSY.get();
    }

    /** 终止当前任务（连同 adb / fastboot 残留进程一起清理）。 */
    public static void cancel() {
        OplusFlasher running = active;
        if (running != null) {
            running.cancelled = true;
        }
        FlashRuntime.get().cancel();
    }

    /**
     * 开始执行。
     *
     * @return {@code false} 表示没有启动（目录无效或已有任务在执行）
     */
    public static boolean start(Context context, Mode mode, Inventory inv, Output output) {
        if (inv == null || !inv.valid()) {
            output.line("[错误] " + (inv == null ? "目录无效" : inv.error));
            output.finished(false);
            return false;
        }
        if (!BUSY.compareAndSet(false, true)) {
            return false;
        }
        OplusFlasher flasher = new OplusFlasher(context, output);
        active = flasher;
        try {
            flasher.build(mode, inv);
        } catch (RuntimeException e) {
            Log.e(TAG, "计划构建失败", e);
            output.line("[错误] " + e.getMessage());
            BUSY.set(false);
            active = null;
            output.finished(false);
            return false;
        }
        flasher.runNext();
        return true;
    }

    // ── 队列构建 ──

    private void build(Mode mode, Inventory inv) {
        // 日志开头先交代这一轮刷的是什么：模式、目录、各类镜像数量。
        line("━━━━━━━━ " + mode.label + " ━━━━━━━━");
        line("目录: " + inv.dir.getAbsolutePath());
        line("镜像: 物理 " + inv.physicalImages.size()
                + " · 逻辑 " + inv.logicalImages.size()
                + " · 定制 " + inv.customImages.size());
        switch (mode) {
            case FIX_SUPER:
                buildFixSuper();
                break;
            case NORMAL:
                buildNormal(inv);
                break;
            case PURE_FASTBOOTD:
                buildPureFastbootd(inv);
                break;
            default:
                break;
        }
    }

    // 修复 Super 分区：读大小 → 生成空镜像 → 刷入 → 清理
    private void buildFixSuper() {
        queue.add(new Step(detectCommand(true), "正在读取 super 分区信息…", (code, out) -> {
            if (!requireFastboot(out) || !requireNotFastbootd(out) || !requireSuperSize(out)) {
                return;
            }
            line("读取成功：super 分区总大小 " + superSize + " 字节");

            tempSuperImage = new File(FlashToolchain.imageDir(appContext), "aurora_super_empty.img");
            queue.add(new Step(
                    FlashCommands.makeEmptyImage(tempSuperImage.getAbsolutePath(), superSize),
                    "正在生成空 super 镜像（" + superSize + " 字节）…",
                    (c2, o2) -> {
                        if (c2 != 0) {
                            fail("空 super 镜像生成失败");
                            return;
                        }
                        line("空 super 镜像生成成功！");
                        queue.add(new Step(
                                FlashCommands.fastbootFlash("super", tempSuperImage.getAbsolutePath()),
                                "开始刷入 super 分区…",
                                (c3, o3) -> {
                                    if (c3 == 0 && !o3.contains("FAILED")) {
                                        line("super 分区刷入完成！");
                                        line("super 分区修复完成，下一步进行线刷");
                                    } else {
                                        line("[错误] super 分区刷入失败");
                                        failed = true;
                                    }
                                    queue.add(new Step(
                                            FlashCommands.removeFile(tempSuperImage.getAbsolutePath()),
                                            null, null));
                                }));
                    }));
        }));
    }

    // 常规线刷：fastboot 刷物理分区 → 转 fastbootd → 重构 + 刷逻辑分区
    private void buildNormal(Inventory inv) {
        queue.add(new Step(detectCommand(true), "正在检测设备…", (code, out) -> {
            if (!requireFastboot(out)) {
                return;
            }
            currentSlot = FlashCommands.parseCurrentSlot(out);
            if (currentSlot == null) {
                fail("非AB分区设备，自动退出");
                return;
            }
            targetSlot = otherSlot(currentSlot);
            line("检测到当前槽位: " + currentSlot);

            // ── 第一步：在 fastboot 模式下刷写关键镜像 ──
            List<File> all = new ArrayList<>(inv.physicalImages);
            for (int i = 0; i < all.size(); i++) {
                File image = all.get(i);
                String partition = SparseImage.partitionNameOf(image.getName());
                queue.add(new Step(
                        FlashCommands.fastbootFlash(partition, image.getAbsolutePath()),
                        (i == 0 ? "[第一步] 在 fastboot 模式下刷写关键镜像…\n" : "")
                                + "刷写 " + image.getName(),
                        flashResultHandler()));
            }

            // ── 第二步：重启进 fastbootd，重构逻辑分区 ──
            queue.add(new Step(FlashCommands.fastbootRebootFastbootd(),
                    "fastboot 重启到 fastbootD...", null));
            queue.add(new Step(FlashCommands.waitForFastboot(MODE_SWITCH_WAIT_SEC),
                    "等待设备进入 fastbootD（" + MODE_SWITCH_WAIT_SEC + " 秒）…",
                    (c, o) -> {
                        if (o == null || o.trim().isEmpty()) {
                            fail("等待FastbootD设备超时");
                        } else {
                            line("已成功进入FastbootD模式（等待 " + MODE_SWITCH_WAIT_SEC + " 秒）");
                        }
                    }));
            enqueueReconstruct(inv, "[第二步] 重构逻辑分区…");

            // ── 第三步：在 fastbootd 中刷写剩余镜像 ──
            enqueueLogicalFlash(inv, currentSlot, "[第三步] 在 fastbootD 中刷写剩余镜像…");
        }));
    }

    // 纯FastbootD：全程 fastbootd，刷到备用槽位后激活
    private void buildPureFastbootd(Inventory inv) {
        queue.add(new Step(detectCommand(false), "[1/6] 检测设备状态…", (code, out) -> {
            if (!requireFastboot(out)) {
                return;
            }
            queue.add(new Step(FlashCommands.fastbootGetCurrentSlot(),
                    "[2/6] 检测设备AB分区信息…", (c2, o2) -> {
                currentSlot = FlashCommands.parseCurrentSlot(o2);
                if (currentSlot == null) {
                    fail("非AB分区设备，自动退出");
                    return;
                }
                targetSlot = otherSlot(currentSlot);
                line("检测到当前槽位: " + currentSlot + " | 目标刷写槽位: " + targetSlot);

                // 「纯FastbootD」要求全程在 fastbootd 下跑；不在就切过去。
                if (!FlashCommands.isFastbootdOutput(o2)) {
                    queue.add(new Step(FlashCommands.fastbootRebootFastbootd(),
                            "fastboot 重启到 fastbootD...", null));
                    queue.add(new Step(FlashCommands.waitForFastboot(MODE_SWITCH_WAIT_SEC),
                            "等待设备进入FastbootD（" + MODE_SWITCH_WAIT_SEC + " 秒）…",
                            (c3, o3) -> {
                                if (o3 == null || o3.trim().isEmpty()) {
                                    fail("等待FastbootD设备超时");
                                } else {
                                    line("已成功进入FastbootD模式（等待 "
                                            + MODE_SWITCH_WAIT_SEC + " 秒）");
                                }
                            }));
                } else {
                    line("设备已处于FastbootD模式");
                }

                enqueueReconstruct(inv, "[3/6] 重构逻辑分区…");

                int tasks = inv.logicalImages.size() + inv.customImages.size();
                line("[4/6] 总刷写任务数: " + tasks);
                enqueueLogicalFlash(inv, targetSlot,
                        "[5/6] 开始刷写镜像到 " + targetSlot + " 槽位…");

                queue.add(new Step(FlashCommands.fastbootSetActive(targetSlot),
                        "[6/6] 激活目标槽位 " + targetSlot + "…", (c4, o4) -> {
                    if (c4 == 0 && !o4.contains("FAILED")) {
                        line("目标槽位 " + targetSlot + " 激活成功！");
                    } else {
                        line("目标槽位激活失败，请手动执行 fastboot set_active " + targetSlot);
                        failed = true;
                    }
                }));
            }));
        }));
    }

    /**
     * 重构逻辑分区：{@code delete}（失败忽略）+ {@code create}（用 sparse 展开后的真实大小）。
     *
     * <p>delete 与 create 串在同一条命令里：分区本来不存在时 delete 必然 FAILED，
     * 这是<b>预期内</b>的，所以用 {@code ;} 连接、只看 create 的结果。
     */
    private void enqueueReconstruct(Inventory inv, String banner) {
        List<File> all = logicalWithCustom(inv);
        if (all.isEmpty()) {
            line("没有需要重构的逻辑分区");
            return;
        }
        line("开始重构逻辑分区（共" + all.size() + "个）…");
        for (int i = 0; i < all.size(); i++) {
            File image = all.get(i);
            String name = SparseImage.partitionNameOf(image.getName()) + "_" + targetSlot;
            long size = SparseImage.partitionSize(image);
            String command = FlashCommands.fastbootDeleteLogicalPartition(name)
                    + "; " + FlashCommands.fastbootCreateLogicalPartition(name, size);
            queue.add(new Step(command,
                    (i == 0 ? banner + "\n" : "") + "重构分区: " + name + " （" + size + " 字节）",
                    (code, out) -> {
                        if (out.contains("create-logical-partition") && out.contains("FAILED")) {
                            line("重构失败: " + "create-logical-partition 被拒绝");
                        }
                    }));
        }
        line("重构完成。");
    }

    /** 刷写逻辑分区（含定制分区）。 */
    private void enqueueLogicalFlash(Inventory inv, String slot, String banner) {
        List<File> all = logicalWithCustom(inv);
        for (int i = 0; i < all.size(); i++) {
            File image = all.get(i);
            String partition = SparseImage.partitionNameOf(image.getName());
            queue.add(new Step(
                    FlashCommands.fastbootFlash(partition + "_" + slot, image.getAbsolutePath()),
                    (i == 0 ? banner + "\n" : "") + "刷写 " + image.getName(),
                    flashResultHandler()));
        }
    }

    private static List<File> logicalWithCustom(Inventory inv) {
        List<File> all = new ArrayList<>(inv.logicalImages);
        all.addAll(inv.customImages);
        return all;
    }

    /** 单条 flash 的结果处理：只看退出码与 FAILED 关键字。 */
    private Handler flashResultHandler() {
        return (code, out) -> {
            if (code == 0 && !out.contains("FAILED")) {
                line("刷写完成");
            } else {
                line("刷写失败 (退出码: " + code + ")");
                failed = true;
            }
        };
    }

    // ── 设备检测 ──

    /**
     * 拼一条「一次拿到全部检测信息」的命令。
     *
     * <p>分节输出（{@code ::slot} / {@code ::userspace} / {@code ::super}）让解析不依赖
     * 行序 —— 不同 fastboot 版本对 {@code getvar} 的回显格式并不一致。
     *
     * @param needSuperSize 是否需要读 super 分区大小（修复流程用）
     */
    private static String detectCommand(boolean needSuperSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("fastboot devices 2>&1");
        sb.append("; echo '::slot'; fastboot getvar current-slot 2>&1");
        sb.append("; echo '::userspace'; fastboot getvar is-userspace 2>&1");
        if (needSuperSize) {
            sb.append("; echo '::super'; fastboot getvar partition-size:super 2>&1");
        }
        sb.append("; true");
        return sb.toString();
    }

    /** fastboot 在线判定：{@code fastboot devices} 那一段（第一个 {@code ::} 之前）非空。 */
    private boolean requireFastboot(String out) {
        String devices = beforeFirstMarker(out);
        boolean online = !devices.trim().isEmpty();
        boolean slotReadable = out != null && out.contains("current-slot");
        if (!online && !slotReadable) {
            line("未检测到任何设备");
            line("请确认目标机已进入 fastboot 模式（关机后按住音量减 + 电源）");
            failed = true;
            return false;
        }
        return true;
    }

    /** 第一个分节标记之前的内容（即 {@code fastboot devices} 的输出）。 */
    static String beforeFirstMarker(String output) {
        if (output == null) {
            return "";
        }
        int marker = output.indexOf("::");
        return marker < 0 ? output : output.substring(0, marker);
    }

    private boolean requireNotFastbootd(String out) {
        if (FlashCommands.isFastbootdOutput(out)) {
            line("[错误] 当前为FastbootD模式，请重启到 fastboot 模式");
            failed = true;
            return false;
        }
        return true;
    }

    private boolean requireSuperSize(String out) {
        long size = FlashCommands.parsePartitionSize(sectionOf(out, "::super"));
        if (size <= 0) {
            line("[错误] 读取super分区大小失败");
            failed = true;
            return false;
        }
        superSize = size;
        return true;
    }

    /** 取分节内容；标记不存在时返回 {@code null}。 */
    static String sectionOf(String output, String marker) {
        if (output == null || marker == null) {
            return null;
        }
        int start = output.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = output.indexOf("\n::", start);
        return end < 0 ? output.substring(start) : output.substring(start, end);
    }

    /** 取对侧槽位（{@code a} ↔ {@code b}）。 */
    static String otherSlot(String slot) {
        return "a".equalsIgnoreCase(slot) ? "b" : "a";
    }

    // ── 运行 ──

    private void runNext() {
        if (cancelled) {
            finish();
            return;
        }
        Step step = queue.poll();
        if (step == null) {
            finish();
            return;
        }
        if (step.banner != null) {
            line(step.banner);
        }
        // 回显实际命令 —— 刷机出问题时，日志里有命令原文才有得查。
        line("$ " + step.command);
        stepOutput.setLength(0);

        boolean accepted = FlashRuntime.get().run(appContext, step.command,
                new FlashRuntime.Listener() {
                    @Override
                    public void onLine(String text) {
                        stepOutput.append(text).append('\n');
                        line(text);
                    }

                    @Override
                    public void onExit(int exitCode, boolean wasCancelled) {
                        if (wasCancelled) {
                            line("[已中断]");
                            cancelled = true;
                            finish();
                            return;
                        }
                        try {
                            if (step.then != null) {
                                step.then.handle(exitCode, stepOutput.toString());
                            }
                        } catch (RuntimeException e) {
                            Log.e(TAG, "步骤后处理异常", e);
                            line("[错误] " + e.getMessage());
                            failed = true;
                        }
                        runNext();
                    }
                });
        if (!accepted) {
            line("[错误] 已有任务在执行");
            failed = true;
            finish();
        }
    }

    private void finish() {
        if (tempSuperImage != null && tempSuperImage.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tempSuperImage.delete();
        }
        boolean ok = !failed && !cancelled;
        if (cancelled) {
            line("[已中断]");
        } else {
            line(ok ? "━━━━━━━━ 所有刷写操作已完成 ━━━━━━━━" : "[结束] 流程未能全部完成");
        }
        BUSY.set(false);
        active = null;
        output.finished(ok);
    }

    /** 记下失败原因并清空后续步骤（当前步骤的 handler 返回后流程自然收尾）。 */
    private void fail(String reason) {
        line("[错误] " + reason);
        failed = true;
        queue.clear();
    }

    private void line(String text) {
        output.line(text == null ? "" : text);
    }
}
