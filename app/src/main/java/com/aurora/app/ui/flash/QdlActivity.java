package com.aurora.app.ui.flash;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.widget.NestedScrollView;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashToolchain;
import com.aurora.app.core.flash.QdlCommands;

import java.io.File;
import java.util.Locale;

/**
 * 「高通 QDL 深刷」页（独立 Activity，竖屏，自带返回箭头与大标题）。
 *
 * <h3>它做什么</h3>
 * 用内置的 {@code qdl} 可执行文件对<b>连着的那台 9008 EDL 设备</b>做两件事，
 * 也就是参考实现那个三步流程里能独立成立的前两步：
 * <ol>
 *   <li><b>识别分区表</b>（{@code rgpt}）—— 各 LUN 的 GPT 导成文件落到
 *       {@link QdlCommands#OUTPUT_DIR}，之后的任何写入 / 切槽位都依赖这批产物；</li>
 *   <li><b>识别 / 切换 A/B 槽位</b>（{@code get_slot} / {@code set_slot}）。</li>
 * </ol>
 * 外加一条 <b>复位设备</b>（{@code reset}）—— 它不是为了「刷机」，而是让设备退出 9008：
 * 命令一律带 {@code --skip-reset}，操作完成后 {@code qdl} 不会自己复位，
 * 没有这一条用户就只能拔线重插。
 *
 * <h3>为什么写入 / 提取 / 全量包没做进来</h3>
 * 那些需要把整个镜像目录与 {@code rawprogram*.xml} 摆好，还牵到欧加全量包的 super 构建
 * 与 LUN5 保护，属于下一步。本页先把<b>命令层已经锁死的那几条</b>接上，
 * 让「设备能不能通、分区表读不读得到」这个前置问题先能被验证。
 *
 * <h3>为什么页面上没有一句机制说明</h3>
 * 热拔插警告、切槽位依赖分区表、复位意味着什么 —— 全部写在点击后的确认框里。
 * 页面只放「控件 + 实时状态」是项目约定。
 *
 * <h3>文案出处</h3>
 * 所有界面文案取自参考实现 {@code strings.xml} 的 {@code qdl_*} 条目：
 * {@code qdl_rgpt_title}「识别分区表」、{@code qdl_select_programmer}「选择引导文件」、
 * {@code qdl_storage_type}「存储类型」、{@code qdl_oplus_auth_mode}「欧加授权方式」及其三档
 * {@code qdl_oplus_auth_off/_token/_vip}、{@code qdl_ab_detect}「识别设备启动槽位」、
 * {@code qdl_ab_switch_a/_b}「切换至A」/「切换至B」、{@code qdl_reset_device}「复位设备」、
 * {@code qdl_dry_run}「模拟刷写」及其 {@code qdl_dry_run_summary}、
 * {@code qdl_hotplug_warning}、{@code qdl_slot_fail}、{@code qdl_vip_sign}、{@code qdl_vip_digest}。
 */
public final class QdlActivity extends FlashConsoleActivity {

    /** 请求码：选择引导文件（programmer）。 */
    private static final int REQ_PICK_PROG = 9401;
    /** 请求码：选择 VIP 档的签名文件（Sign）。 */
    private static final int REQ_PICK_SIGN = 9402;
    /** 请求码：选择 VIP 档的摘要文件（Digest）。 */
    private static final int REQ_PICK_DIGEST = 9403;

    /** 授权档位在下拉里的固定顺序（与 {@link QdlCommands.OplusAuth} 的声明顺序一致）。 */
    private static final QdlCommands.OplusAuth[] AUTH_TIERS = {
            QdlCommands.OplusAuth.OFF,
            QdlCommands.OplusAuth.TOKEN,
            QdlCommands.OplusAuth.VIP,
    };

    /**
     * {@code get_slot} 解析失败时的文案，逐字取自参考实现 {@code qdl_slot_fail}。
     *
     * <p>读不到槽位时不要显示「A 槽」也不要留「未读取」—— 「没读到」和「没读过」
     * 是两种状态，混在一起用户会以为设备在 A 槽。
     */
    private static final String SLOT_PARSE_FAILED = "无法获取活跃槽位（qdl get_slot 解析失败）";

    /** 未选引导文件时的拦截文案。 */
    private static final String NO_PROG_SELECTED = "请先点「选择引导文件」";

    // ── 页面控件 ──
    private View btnPickProg;
    private TextView tvProgSummary;
    private View btnStorage;
    private TextView tvStorageSummary;
    private View btnAuth;
    private TextView tvAuthSummary;
    private View btnPickSign;
    private TextView tvSignSummary;
    private View btnPickDigest;
    private TextView tvDigestSummary;
    private View btnRgpt;
    private TextView tvRgptSummary;
    private View btnDetectSlot;
    private TextView tvSlotSummary;
    private View btnSwitchA;
    private View btnSwitchB;
    private View btnReset;
    private SwitchCompat swDryRun;

    /** 外层滚动容器：输出变长时由它滚动，从而「上下都有弹性」。 */
    private NestedScrollView scrollRoot;

    private FlashDeviceDropdown deviceDropdown;

    // ── 页面状态 ──
    /** 已选引导文件；null 表示还没选。 */
    private File progFile;
    /** VIP 档的签名文件；null 表示还没选。 */
    private File signFile;
    /** VIP 档的摘要文件；null 表示还没选。 */
    private File digestFile;
    /** 存储类型，默认 UFS（绝大多数现代高通机型）。 */
    private QdlCommands.StorageType storage = QdlCommands.StorageType.UFS;
    /** 欧加授权档位，默认未开启。 */
    private QdlCommands.OplusAuth auth = QdlCommands.OplusAuth.OFF;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qdl);

        bindViews();
        // 抓公共控件 + 接好返回 / 保存日志 / 终止，并触发一次设备探测。
        bindConsole();

        // 输出区随内容增高、由外层承担滚动 —— 必须取消输出框自身的滚动，
        // 否则竖向手势会被它吃掉，页面就不再上下回弹了。
        if (tvConsole != null) {
            tvConsole.setMovementMethod(null);
        }

        View pill = findViewById(R.id.btnQdlDevicePill);
        deviceDropdown = new FlashDeviceDropdown(this, pill);
        if (pill != null) {
            pill.setOnClickListener(v -> deviceDropdown.toggle(lastState));
        }

        bindActions();
        renderStorage();
        renderAuth();
        renderProgSummary();
        renderRgptSummary();

        prepareToolchain();
        warnIfUnsupported();
    }

    private void bindViews() {
        btnPickProg = findViewById(R.id.btnQdlPickProg);
        tvProgSummary = findViewById(R.id.tvQdlProgSummary);
        btnStorage = findViewById(R.id.btnQdlStorage);
        tvStorageSummary = findViewById(R.id.tvQdlStorageSummary);
        btnAuth = findViewById(R.id.btnQdlAuth);
        tvAuthSummary = findViewById(R.id.tvQdlAuthSummary);
        btnPickSign = findViewById(R.id.btnQdlPickSign);
        tvSignSummary = findViewById(R.id.tvQdlSignSummary);
        btnPickDigest = findViewById(R.id.btnQdlPickDigest);
        tvDigestSummary = findViewById(R.id.tvQdlDigestSummary);
        btnRgpt = findViewById(R.id.btnQdlRgpt);
        tvRgptSummary = findViewById(R.id.tvQdlRgptSummary);
        btnDetectSlot = findViewById(R.id.btnQdlDetectSlot);
        tvSlotSummary = findViewById(R.id.tvQdlSlotSummary);
        btnSwitchA = findViewById(R.id.btnQdlSwitchA);
        btnSwitchB = findViewById(R.id.btnQdlSwitchB);
        btnReset = findViewById(R.id.btnQdlReset);
        swDryRun = findViewById(R.id.swQdlDryRun);
        scrollRoot = findViewById(R.id.scrollQdl);
    }

    private void bindActions() {
        setOnClick(btnPickProg, this::pickProgrammer);
        setOnClick(btnStorage, this::chooseStorage);
        setOnClick(btnAuth, this::chooseAuth);
        setOnClick(btnPickSign, () -> pickExtra(REQ_PICK_SIGN));
        setOnClick(btnPickDigest, () -> pickExtra(REQ_PICK_DIGEST));
        setOnClick(btnRgpt, this::runRgpt);
        setOnClick(btnDetectSlot, this::detectSlot);
        setOnClick(btnSwitchA, () -> confirmSwitchSlot("a"));
        setOnClick(btnSwitchB, () -> confirmSwitchSlot("b"));
        setOnClick(btnReset, this::confirmReset);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 弹窗挂在 Activity 的 window 上，页面不可见时留着就是 WindowLeaked。
        if (deviceDropdown != null) {
            deviceDropdown.dismiss();
        }
    }

    /**
     * 输出追加后滚到底部。
     *
     * <p>本页的输出框是「随内容增高」的，自身没有滚动，所以要滚外层容器。
     * 输出文本测量完到能滚有一帧延迟，所以 post 到下一帧。
     */
    @Override
    protected void scrollToBottom() {
        if (scrollRoot == null) {
            return;
        }
        scrollRoot.post(() -> scrollRoot.fullScroll(View.FOCUS_DOWN));
    }

    // ───────────────── 基类接口 ─────────────────

    @Override
    protected View[] busyViews() {
        return new View[]{btnPickProg, btnStorage, btnAuth, btnPickSign, btnPickDigest,
                btnRgpt, btnDetectSlot, btnSwitchA, btnSwitchB, btnReset, swDryRun};
    }

    @Override
    protected String logFileTag() {
        return "qdl";
    }

    // ───────────────── 工具链 ─────────────────

    /**
     * 静默准备工具链。
     *
     * <p>正常路径下刷机页已经解压过了（{@code ensureReady} 是空操作）；
     * 这里兜底是因为 {@code qdl} 是本页唯一直接依赖的二进制 ——
     * 用户在刷机页刚进来就点卡片时，那边的后台解压可能还没跑完。
     */
    private void prepareToolchain() {
        if (FlashToolchain.isReady(getApplicationContext())) {
            return;
        }
        new Thread(() -> {
            if (!FlashToolchain.ensureReady(getApplicationContext())) {
                toast("刷机工具链解压失败，qdl 不可用");
            }
        }, "aurora-qdl-toolchain").start();
    }

    /**
     * 非 64 位 ARM 设备上直接说明原因。
     *
     * <p>内置的 {@code qdl} 只有 aarch64 一个构建（参考实现也只带 {@code aarch64.tar}），
     * 在 32 位 ARM / x86 设备上解出来也没法执行。与其让用户点下去收到一句
     * {@code Exec format error}，不如在这里说清楚。
     */
    private void warnIfUnsupported() {
        if (FlashToolchain.isQdlSupported()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("当前设备不能运行 qdl")
                .setMessage("内置的 qdl 只有 aarch64 一个构建，本机不是 64 位 ARM 设备，"
                        + "解压出来也无法执行。\n\n本页在其它设备上仍然可用。")
                .setPositiveButton("返回", (d, w) -> finish())
                .show();
    }

    // ───────────────── 参数：引导 / 存储 / 授权 ─────────────────

    /** 选引导文件（programmer）：{@code prog.mbn} / {@code DevprgProgrammer.elf} 这类。 */
    private void pickProgrammer() {
        if (!FlashFilePicker.pick(this, REQ_PICK_PROG, FlashFilePicker.MIME_ANY)) {
            toast("无法打开文件选择器");
        }
    }

    /**
     * 选 VIP 档需要的两个附件（签名 / 摘要）。
     *
     * <p>它们同样要拷进应用专属目录：{@code qdl} 是 root 身份按<b>真实路径</b>读文件的，
     * 读不懂 {@code content://}。
     */
    private void pickExtra(int requestCode) {
        if (!FlashFilePicker.pick(this, requestCode, FlashFilePicker.MIME_ANY)) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode != REQ_PICK_PROG && requestCode != REQ_PICK_SIGN
                && requestCode != REQ_PICK_DIGEST) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        final int code = requestCode;
        setBusy(true);
        appendLine("$ 正在准备文件…");

        final String prefix = code == REQ_PICK_PROG ? "prog_" : (code == REQ_PICK_SIGN ? "sign_" : "digest_");
        final String ext = code == REQ_PICK_PROG ? ".mbn" : ".bin";

        FlashFilePicker.copyAsync(this, uri, prefix, ext, new FlashFilePicker.Callback() {
            /** 上一次报过的百分比（按 10% 一档，避免刷屏）。 */
            private long lastPercent = -1;

            @Override
            public void onProgress(long copied, long total) {
                if (total <= 0) {
                    return;
                }
                long percent = copied * 100 / total;
                if (percent / 10 != lastPercent / 10) {
                    lastPercent = percent;
                    appendLine("  " + percent + "%");
                }
            }

            @Override
            public void onDone(File file) {
                runOnUiThreadSafe(() -> {
                    setBusy(false);
                    if (code == REQ_PICK_PROG) {
                        progFile = file;
                        renderProgSummary();
                    } else if (code == REQ_PICK_SIGN) {
                        signFile = file;
                        renderExtraSummary(tvSignSummary, file);
                    } else {
                        digestFile = file;
                        renderExtraSummary(tvDigestSummary, file);
                    }
                    appendLine("已就绪: " + file.getAbsolutePath()
                            + "（" + humanSize(file.length()) + "）");
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThreadSafe(() -> {
                    setBusy(false);
                    appendLine("[错误] " + message);
                });
            }
        });
    }

    private void renderProgSummary() {
        if (tvProgSummary == null) {
            return;
        }
        tvProgSummary.setText(progFile == null
                ? "未选择"
                : progFile.getName() + "（" + humanSize(progFile.length()) + "）");
    }

    private void renderExtraSummary(TextView view, File file) {
        if (view == null) {
            return;
        }
        view.setText(file == null
                ? "未选择"
                : file.getName() + "（" + humanSize(file.length()) + "）");
    }

    /** 存储类型：单选弹窗，选项就是 {@code --storage=} 的合法取值本身。 */
    private void chooseStorage() {
        final QdlCommands.StorageType[] all = QdlCommands.StorageType.values();
        CharSequence[] labels = new CharSequence[all.length];
        int checked = 0;
        for (int i = 0; i < all.length; i++) {
            labels[i] = all[i].value;
            if (all[i] == storage) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("存储类型")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    storage = all[which];
                    renderStorage();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renderStorage() {
        if (tvStorageSummary != null) {
            tvStorageSummary.setText(storage.value);
        }
    }

    /**
     * 欧加授权方式：三档单选。
     *
     * <p>参考实现是靠引导库里的品牌信息自动判档的；本项目暂时没有搬那套引导库，
     * 所以档位由用户自己选 —— 这与「不凭空替用户猜一个档位」是一致的。
     */
    private void chooseAuth() {
        CharSequence[] labels = new CharSequence[AUTH_TIERS.length];
        int checked = 0;
        for (int i = 0; i < AUTH_TIERS.length; i++) {
            labels[i] = authLabel(AUTH_TIERS[i]);
            if (AUTH_TIERS[i] == auth) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("欧加授权方式")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    auth = AUTH_TIERS[which];
                    renderAuth();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renderAuth() {
        if (tvAuthSummary != null) {
            tvAuthSummary.setText(authSummary(auth));
        }
        // VIP 档要用到两个额外文件，选中后才把对应两行摆出来。
        int visibility = auth == QdlCommands.OplusAuth.VIP ? View.VISIBLE : View.GONE;
        if (btnPickSign != null) {
            btnPickSign.setVisibility(visibility);
        }
        if (btnPickDigest != null) {
            btnPickDigest.setVisibility(visibility);
        }
    }

    /** 档位名，逐字取自 {@code qdl_oplus_auth_off / _token / _vip}。 */
    private static String authLabel(QdlCommands.OplusAuth tier) {
        switch (tier) {
            case VIP:
                return "VIP 授权";
            case TOKEN:
                return "Token 鉴权";
            default:
                return "未开启";
        }
    }

    /** 档位摘要，逐字取自 {@code qdl_oplus_auth_*_summary}。 */
    private static String authSummary(QdlCommands.OplusAuth tier) {
        switch (tier) {
            case VIP:
                return "VIP 授权 --signeddigests";
            case TOKEN:
                return "Token 鉴权 --oplus-token";
            default:
                return "未开启";
        }
    }

    // ───────────────── 识别分区表 ─────────────────

    /** {@code rgpt}：读各 LUN 的 GPT 表，导出到 {@link QdlCommands#OUTPUT_DIR}。 */
    private void runRgpt() {
        if (!requireProg()) {
            return;
        }
        if (auth == QdlCommands.OplusAuth.VIP && !requireVipFiles()) {
            return;
        }
        String command = withFlags(QdlCommands.rgpt(storage, progFile.getAbsolutePath(), auth,
                signFile == null ? null : signFile.getAbsolutePath(),
                digestFile == null ? null : digestFile.getAbsolutePath()));

        confirmThenRun("确认识别分区表",
                paramBlock()
                        + QdlCommands.HOTPLUG_WARNING + "\n\n"
                        + "分区表将导出到：\n" + QdlCommands.OUTPUT_DIR + "\n\n"
                        + "命令：\n" + command,
                "执行", command,
                // 数「GPT saved to」的行数：它就是真的导出了几个 LUN 的直接证据。
                // 一条都没数到时不去改摘要 —— 「没读到」与「读过但为空」要能分得开。
                (output, exitCode, cancelled) -> {
                    if (cancelled) {
                        return;
                    }
                    int luns = QdlCommands.countSavedLuns(output);
                    if (luns > 0 && tvRgptSummary != null) {
                        tvRgptSummary.setText("已保存 " + luns + " 个 LUN 的分区表 · "
                                + QdlCommands.OUTPUT_DIR);
                    }
                });
    }

    private void renderRgptSummary() {
        if (tvRgptSummary != null) {
            tvRgptSummary.setText("读取各 LUN 的 GPT 表，导出到 " + QdlCommands.OUTPUT_DIR);
        }
    }

    // ───────────────── 槽位 ─────────────────

    /** {@code get_slot}：只读。结果同时落到输出区与那一行的摘要。 */
    private void detectSlot() {
        if (!requireProg()) {
            return;
        }
        String command = withFlags(QdlCommands.getSlot(storage, progFile.getAbsolutePath(), auth));
        runConsoleCommand(command, (output, exitCode, cancelled) -> {
            if (cancelled || exitCode != 0) {
                return;
            }
            String slot = QdlCommands.parseActiveSlot(output);
            if (tvSlotSummary != null) {
                tvSlotSummary.setText(slot == null
                        ? SLOT_PARSE_FAILED
                        : "当前槽位：" + slot.toUpperCase(Locale.ROOT));
            }
        });
    }

    /**
     * {@code set_slot}：把设备启动槽位切到指定一侧。
     *
     * <p>要确认是因为它<b>改变设备的启动行为</b>；写错一侧的话刷进去的镜像就白刷了。
     * 「依赖分区表」这个前置条件写在确认框里，而不是摆成页面上的说明卡片。
     */
    private void confirmSwitchSlot(String slot) {
        if (!requireProg()) {
            return;
        }
        String command = withFlags(QdlCommands.setSlot(storage, progFile.getAbsolutePath(), auth, slot));
        String upper = slot.toUpperCase(Locale.ROOT);
        confirmThenRun("确认切换至 " + upper + " 槽",
                paramBlock()
                        + "切换槽位依赖「识别分区表」导出的分区表；没先做那一步时 qdl 会直接报错退出。\n\n"
                        + "命令：\n" + command,
                "切换至 " + upper, command);
    }

    // ───────────────── 复位 ─────────────────

    /**
     * {@code reset}：让设备退出 9008 重新启动。
     *
     * <p>这条不是刷机动作，而是上面几步做完之后的「收尾」—— 所有命令都带
     * {@code --skip-reset}，{@code qdl} 不会自己复位。参考实现里同样有一个独立的
     * 「复位设备」入口（{@code qdl_reset_device}）。它另有一个 {@code reboot} 子命令，
     * 本项目暂时只在命令模板层保留、未接线。
     */
    private void confirmReset() {
        if (!requireProg()) {
            return;
        }
        String command = withFlags(QdlCommands.reset(storage, progFile.getAbsolutePath(), auth));
        confirmThenRun("确认复位设备",
                paramBlock()
                        + QdlCommands.HOTPLUG_WARNING + "\n\n"
                        + "复位后设备会退出 9008 端口并重新启动。\n\n"
                        + "命令：\n" + command,
                "复位", command);
    }

    // ───────────────── 拼接 ─────────────────

    /**
     * 按「模拟刷写」开关给命令补 {@code --dry-run}。
     *
     * <p>只接 {@code --dry-run} 不接 {@code --debug}：前者能在<b>不接设备</b>的情况下
     * 验证整条命令拼装是否正确，后者只是日志啰嗦。开关本身是幂等的，
     * 重复应用不会插出两个 {@code --dry-run}（见 {@link QdlCommands#applyDebugFlags}）。
     */
    private String withFlags(String command) {
        boolean dryRun = swDryRun != null && swDryRun.isChecked();
        return QdlCommands.applyDebugFlags(command, false, dryRun);
    }

    /** 确认框里共用的「引导 / 存储 / 授权」三行。 */
    private String paramBlock() {
        return "引导文件：" + (progFile == null ? "—" : progFile.getName())
                + "（" + (progFile == null ? "—" : humanSize(progFile.length())) + "）\n"
                + "存储类型：" + storage.value + "\n"
                + "授权方式：" + authLabel(auth) + "\n\n";
    }

    /** 没选引导文件时给一句提示并返回 {@code false}，让调用方直接收手。 */
    private boolean requireProg() {
        if (progFile == null || !progFile.isFile()) {
            progFile = null;
            renderProgSummary();
            toast(NO_PROG_SELECTED);
            return false;
        }
        return true;
    }

    /** VIP 档必须两个附件都在。 */
    private boolean requireVipFiles() {
        if (signFile == null || !signFile.isFile()) {
            toast("VIP 授权需要先选择 Sign（签名文件）");
            return false;
        }
        if (digestFile == null || !digestFile.isFile()) {
            toast("VIP 授权需要先选择 Digest（摘要文件）");
            return false;
        }
        return true;
    }

    private static void setOnClick(View view, Runnable action) {
        if (view != null) {
            view.setOnClickListener(v -> action.run());
        }
    }
}
