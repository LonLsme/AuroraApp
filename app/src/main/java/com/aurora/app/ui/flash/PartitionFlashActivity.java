package com.aurora.app.ui.flash;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashCommands;
import com.aurora.app.core.flash.FlashDevice;
import com.aurora.app.ui.theme.ThemeColors;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「刷写分区」页（独立 Activity，竖屏）。
 *
 * <h3>它做什么</h3>
 * 把「拿一个 .img 刷进某个分区」这件事的完整前置动作摆在一页里：
 * <ol>
 *   <li><b>选择镜像</b> —— 选完拷进应用专属目录（fastboot 只认真实路径）；</li>
 *   <li><b>读取当前槽位</b> —— 只读，跑一次 {@code fastboot getvar current-slot}；</li>
 *   <li><b>切换到另一槽位</b> —— 读当前槽位后切到对侧，非 A/B 设备直接拒绝；</li>
 *   <li><b>刷入 boot / dtbo / recovery</b> —— 三个最常刷的分区，各占一个功能卡；</li>
 *   <li><b>按分区名写入</b> —— 手输分区名 + 同一个镜像，刷到任意分区。</li>
 * </ol>
 *
 * <h3>为什么每个刷入按钮都先跑一次「分区存在性检查」</h3>
 * 见 {@link FlashCommands#fastbootFlashChecked} —— 这是兼容性最好的做法：
 * 分区在目标机上不存在就拒绝刷入，而不是把镜像盲写进去。分区名手输（自定义那行）
 * 时这一步尤其值钱：{@code initboot} 和 {@code init_boot} 差一个下划线，
 * 盲刷的后果是刷了个寂寞还看不出为什么。
 *
 * <h3>进度条为什么按「阶段」跳，而不是按字节滚动</h3>
 * {@code fastboot flash} <b>不提供实时字节进度</b>：它在整段发送结束之后才打出
 * {@code Sending 'boot' (65536 KB) OKAY [ 1.553s]} 这一行。换句话说，发送期间
 * 一条输出都没有。所以这里只做三件事：
 * <ul>
 *   <li>认出 {@code Sending} / {@code Writing} / {@code Finished} 三个真实阶段来推进百分比；</li>
 *   <li>从每行末尾的 {@code [ 1.553s]} 里取<b>真实耗时</b>，配合镜像大小算出实际速度；</li>
 *   <li>剩余时间按「已完成阶段的实际耗时」外推，并明确写成「约」。</li>
 * </ul>
 * 不做「按时间匀速爬到 90% 再卡住」那种假进度 —— 刷机页最不该骗人的地方就是进度。
 */
public final class PartitionFlashActivity extends FlashConsoleActivity {

    /** 请求码：选择 .img 镜像。 */
    private static final int REQ_PICK_IMAGE = 9301;

    /** 三个固定分区按钮对应的分区名。 */
    private static final String PARTITION_BOOT = "boot";
    private static final String PARTITION_DTBO = "dtbo";
    private static final String PARTITION_RECOVERY = "recovery";

    /**
     * fastboot 每行末尾的耗时：{@code OKAY [  1.553s]}。
     *
     * <p>这是整个页面唯一能拿到的<b>真实</b>性能数据 —— fastboot 不给实时进度，
     * 但会给每一步花了多久。速度就是拿它算的，不是估的。
     */
    private static final Pattern PATTERN_STEP_SECONDS =
            Pattern.compile("\\[\\s*([0-9]+(?:\\.[0-9]+)?)s\\s*]");

    // ── 页面控件 ──
    private EditText etImagePath;
    private TextView tvImageSize;
    private EditText etPartition;
    private View btnGetSlot;
    private View btnSwitchSlot;
    private View btnFlashBoot;
    private View btnFlashDtbo;
    private View btnFlashRecovery;
    private View btnFlashCustom;
    private View btnLogCopy;
    private View btnLogClear;
    private View vwDeviceStateDot;

    // ── 进度区 ──
    private TextView tvProgressState;
    private TextView tvProgressPercent;
    private ProgressBar pbProgress;
    private TextView tvProgressSpeed;
    private TextView tvProgressEta;

    private FlashDeviceDropdown deviceDropdown;

    /** 已选镜像；null 表示还没选。 */
    private File pickedImage;

    /** 当前这次命令是不是「刷写」—— 只有刷写才推进度，读槽位不该动它。 */
    private boolean flashInProgress;

    /**
     * 本次刷写是否见到了 fastboot 的收尾行。
     *
     * <p>退出码 0 只说明命令跑完了，不能证明「确实写进去了」——
     * 分区不存在那条分支以前也是 0。收尾行 + 退出码 0 才算成功，否则归为「未确认」。
     */
    private boolean flashSawFinished;

    /** 本次刷写开始的时间戳，用于「剩余时间」外推。 */
    private long flashStartedAt;

    /** 发送阶段实测到的秒数（来自 fastboot 行尾的 {@code [ 1.553s]}）。 */
    private double measuredSendSeconds = -1d;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_partition_flash);

        btnGetSlot = findViewById(R.id.btnPartitionGetSlot);
        ImageButton pick = findViewById(R.id.btnPartitionPickImage);
        etImagePath = findViewById(R.id.etPartitionImagePath);
        tvImageSize = findViewById(R.id.tvImageSize);
        btnSwitchSlot = findViewById(R.id.btnPartitionSwitchSlot);
        btnFlashBoot = findViewById(R.id.btnPartitionFlashBoot);
        btnFlashDtbo = findViewById(R.id.btnPartitionFlashDtbo);
        btnFlashRecovery = findViewById(R.id.btnPartitionFlashRecovery);
        btnFlashCustom = findViewById(R.id.btnPartitionFlashCustom);
        etPartition = findViewById(R.id.etPartitionName);
        btnLogCopy = findViewById(R.id.btnLogCopy);
        btnLogClear = findViewById(R.id.btnLogClear);
        vwDeviceStateDot = findViewById(R.id.vwDeviceStateDot);

        tvProgressState = findViewById(R.id.tvProgressState);
        tvProgressPercent = findViewById(R.id.tvProgressPercent);
        pbProgress = findViewById(R.id.pbFlashProgress);
        tvProgressSpeed = findViewById(R.id.tvProgressSpeed);
        tvProgressEta = findViewById(R.id.tvProgressEta);

        View pill = findViewById(R.id.btnPartitionDevicePill);

        // 抓公共控件 + 接好返回 / 保存日志 / 终止，并触发一次设备探测。
        bindConsole();

        deviceDropdown = new FlashDeviceDropdown(this, pill);
        if (pill != null) {
            pill.setOnClickListener(v -> deviceDropdown.toggle(lastState));
        }

        setOnClick(btnGetSlot, this::readSlot);
        setOnClick(pick, this::pickImage);
        setOnClick(btnSwitchSlot, this::switchSlot);
        setOnClick(btnFlashBoot, () -> flashPartition(PARTITION_BOOT));
        setOnClick(btnFlashDtbo, () -> flashPartition(PARTITION_DTBO));
        setOnClick(btnFlashRecovery, () -> flashPartition(PARTITION_RECOVERY));
        setOnClick(btnFlashCustom, this::flashCustomPartition);
        setOnClick(btnLogCopy, this::copyLog);
        setOnClick(btnLogClear, this::clearLog);

        // 路径框只做展示：点它也能打开选择器，省得用户去够右边那颗小图标
        if (etImagePath != null) {
            etImagePath.setOnClickListener(v -> pickImage());
        }

        resetProgress();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 弹窗挂在 Activity 的 window 上，页面不可见时留着就是 WindowLeaked。
        if (deviceDropdown != null) {
            deviceDropdown.dismiss();
        }
    }

    // ───────────────── 基类接口 ─────────────────

    @Override
    protected View[] busyViews() {
        // 只禁动作按钮：「分区名」输入框保持可编辑 —— 设备探测要几秒，
        // 那几秒里用户完全可以先把分区名打好。
        return new View[]{btnGetSlot, etImagePath, btnSwitchSlot,
                btnFlashBoot, btnFlashDtbo, btnFlashRecovery, btnFlashCustom};
    }

    @Override
    protected String logFileTag() {
        return "partition";
    }

    /**
     * 设备状态变了：顶部胶囊旁边的圆点跟着变色。
     *
     * <p>只改颜色不改文字 —— 文字由基类写成「已连接 · 序列号」/「未连接设备」，
     * 圆点是给「一眼看出连没连上」用的，不该再重复一遍语义。
     *
     * <p>判绿色用 {@code adbReady || fastbootReady}，不是「state 非 null」：
     * 没有 root 时探测照样会返回一个 State（summary 写「未获取 Root 权限」），
     * 按非 null 判会让一台什么都没连的机器亮绿灯。
     */
    @Override
    protected void onDeviceStateRefreshed(FlashDevice.State state) {
        if (vwDeviceStateDot == null) {
            return;
        }
        boolean connected = state != null && (state.adbReady || state.fastbootReady);
        int color = connected
                ? ContextCompat.getColor(this, R.color.ios_semantic_positive)
                : ThemeColors.textSecondary(this);
        vwDeviceStateDot.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    /** 本页要靠逐行输出推进度，所以需要基类把每一行切回主线程回调过来。 */
    @Override
    protected boolean wantsConsoleLine() {
        return true;
    }

    /**
     * 逐行输出：认出 fastboot 的阶段，推进进度。
     *
     * <p>只在 {@link #flashInProgress} 为真时处理 —— 读槽位、切槽位同样走
     * {@code fastboot}，但那些命令不该把进度条点亮。
     */
    @Override
    protected void onConsoleLine(String line) {
        if (!flashInProgress || line == null) {
            return;
        }
        String text = line.trim();
        if (text.isEmpty()) {
            return;
        }

        // fastboot 的失败信息有 FAILED / 也有 error: 开头的，两种都算
        if (text.contains("FAILED") || text.toLowerCase(Locale.US).startsWith("error")) {
            // 只做即时反馈，不在这里落终态：flashInProgress 与最终进度 / 文案统一由 settleFlash 写。
            // fastboot 失败时不会再输出 Finished 收尾行，所以这里不置标志也不会被后续行翻成成功。
            applyProgress(0, "刷写失败", R.color.ios_semantic_negative);
            setSpeed("—");
            setEta("—");
            return;
        }

        if (text.contains("Sending '")) {
            // 这一行出现时发送已经结束 —— fastboot 只在整段发完之后才打它
            Double seconds = stepSeconds(text);
            if (seconds != null) {
                measuredSendSeconds = seconds;
                applyMeasuredSpeed(seconds);
            }
            applyProgress(55, "镜像已发送，正在写入…", 0);
            return;
        }
        if (text.contains("Writing '")) {
            applyProgress(92, "正在写入分区…", 0);
            setEta("约 1 秒");
            return;
        }
        if (text.contains("Finished")) {
            flashSawFinished = true;
            flashInProgress = false;
            applyProgress(100, "刷写完成", R.color.ios_semantic_positive);
            setEta("已完成");
        }
    }

    // ───────────────── 槽位 ─────────────────

    /** 读当前槽位：只读操作，不需要确认，结果直接落日志区。 */
    private void readSlot() {
        endFlashProgress();
        runConsoleCommand(FlashCommands.fastbootReportSlot());
    }

    /**
     * 切到对侧槽位。
     *
     * <p>要确认是因为它<b>改变设备的启动行为</b>：切完之后重启进的是另一个槽位的系统，
     * 而用户很可能正打算刷某个分区 —— 槽位选错的话镜像就刷到另一份上去了。
     */
    private void switchSlot() {
        endFlashProgress();
        String command = FlashCommands.fastbootSwitchSlot();
        confirmThenRun("确认切换槽位",
                "先读当前槽位，再切到对侧槽位（A ↔ B）。\n\n"
                        + "非 A/B 分区设备会直接拒绝，不会执行 set_active。\n\n"
                        + "命令：\n" + command,
                "切换", command);
    }

    // ───────────────── 选镜像 ─────────────────

    private void pickImage() {
        if (!FlashFilePicker.pick(this, REQ_PICK_IMAGE, FlashFilePicker.MIME_ANY)) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        setBusy(true);
        appendLine("$ 正在准备镜像…");

        FlashFilePicker.copyAsync(this, uri, "image_", ".img", new FlashFilePicker.Callback() {
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
                    pickedImage = file;
                    showPickedImage(file);
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

    /**
     * 把选中的镜像回显到路径框。
     *
     * <p>路径走 {@code ellipsize="middle"}：应用私有目录那段前缀对用户没有意义，
     * 中间省略后保住开头的目录层级和结尾的文件名，一眼能认出选的是哪个文件。
     */
    private void showPickedImage(File file) {
        if (etImagePath != null) {
            etImagePath.setText(file.getAbsolutePath());
        }
        if (tvImageSize != null) {
            tvImageSize.setText(humanSize(file.length()));
        }
    }

    // ───────────────── 刷入 ─────────────────

    /** 三个固定分区卡片走这里。 */
    private void flashPartition(String partition) {
        if (!requireImage()) {
            return;
        }
        String command = FlashCommands.fastbootFlashChecked(
                partition, pickedImage.getAbsolutePath());
        confirmFlash("确认刷入 " + partition,
                "镜像：" + pickedImage.getName() + "（" + humanSize(pickedImage.length()) + "）\n"
                        + "分区：" + partition + "\n\n"
                        + "刷入前会先确认该分区在设备上存在，不存在则拒绝刷入。\n\n"
                        + "命令：\n" + command,
                command, this::settleFlash);
    }

    /** 「按分区名写入」那行：分区名来自输入框。 */
    private void flashCustomPartition() {
        final String partition = currentPartitionName();
        if (partition.isEmpty()) {
            toast("请先输入分区名");
            return;
        }
        if (!FlashCommands.isValidPartitionName(partition)) {
            // 分区名里出现空格 / 斜杠几乎都是「把镜像路径粘进来了」。
            toast("分区名只能包含字母、数字、下划线、点和连字符");
            return;
        }
        if (!requireImage()) {
            return;
        }
        String command = FlashCommands.fastbootFlashChecked(
                partition, pickedImage.getAbsolutePath());
        confirmFlash("确认刷入 " + partition,
                "镜像：" + pickedImage.getName() + "（" + humanSize(pickedImage.length()) + "）\n"
                        + "分区：" + partition + "\n\n"
                        + "分区名写错会刷到别的分区上，且不可逆。\n\n"
                        + "命令：\n" + command,
                command, this::settleFlash);
    }

    /**
     * 刷写前的确认框。
     *
     * <p>不复用基类的 {@code confirmThenRun}：这里要在「用户点了确认」的那一刻
     * 才把进度区点亮，而不是在弹框之前 —— 否则用户点取消，进度条会白白亮起再复位。
     */
    private void confirmFlash(String title, String message, String command,
                              FlashConsoleActivity.CommandCallback callback) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("刷入", (d, w) -> {
                    beginFlashProgress();
                    runConsoleCommand(command, callback);
                })
                .show();
    }

    /**
     * 刷写结束：按「退出码 + 是否取消 + 是否见到收尾行」收敛界面状态。
     *
     * <p>这是本页唯一的终点。以前没有任何终点逻辑，进度条只靠输出文本推进：
     * 分区不存在时命令以退出码 0 结束（分支里只有 {@code echo}），界面就停在
     * 「正在检查分区… 8%」，下次刷写的输出还会被当成这一次的阶段继续解析。
     *
     * <p>判定顺序与依据见 {@link FlashCommands#classifyFlashResult}：退出码是硬契约，
     * 文本只用来判断有没有走到收尾行。不认识的结果一律归「未确认」，不冒充成功。
     */
    private void settleFlash(String output, int exitCode, boolean cancelled) {
        boolean finished = flashSawFinished;
        flashInProgress = false;
        switch (FlashCommands.classifyFlashResult(exitCode, cancelled, finished)) {
            case FlashCommands.FLASH_RESULT_OK:
                applyProgress(100, "刷写完成", R.color.ios_semantic_positive);
                setEta("已完成");
                break;
            case FlashCommands.FLASH_RESULT_NO_PARTITION:
                applyProgress(0, "未执行刷写：分区检查未通过，请确认设备已进入 Fastboot 模式且目标分区存在",
                        R.color.ios_semantic_negative);
                setSpeed("—");
                setEta("—");
                break;
            case FlashCommands.FLASH_RESULT_CANCELLED:
                applyProgress(0, "已取消刷写", R.color.ios_semantic_negative);
                setSpeed("—");
                setEta("—");
                break;
            case FlashCommands.FLASH_RESULT_FAILED:
                applyProgress(0, "刷写失败（退出码 " + exitCode + "）",
                        R.color.ios_semantic_negative);
                setSpeed("—");
                setEta("—");
                break;
            default:
                // 退出码 0 但没见到收尾行：命令确实跑完了，但无法证明写入结果，不显示为成功。
                applyProgress(pbProgress == null ? 0 : pbProgress.getProgress(),
                        "命令已结束，未能确认写入结果", 0);
                setEta("未确认");
                break;
        }
    }

    /** 没选镜像时给一句提示并返回 {@code false}，让调用方直接收手。 */
    private boolean requireImage() {
        if (pickedImage == null || !pickedImage.isFile()) {
            pickedImage = null;
            toast("请先选择镜像文件（.img）");
            return false;
        }
        return true;
    }

    private String currentPartitionName() {
        if (etPartition == null || etPartition.getText() == null) {
            return "";
        }
        return etPartition.getText().toString().trim();
    }

    // ───────────────── 日志操作 ─────────────────

    /** 复制整份日志到剪贴板。 */
    private void copyLog() {
        if (consoleBuffer.length() == 0) {
            toast("还没有输出内容");
            return;
        }
        Context ctx = getApplicationContext();
        ClipboardManager manager =
                (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            toast("剪贴板不可用");
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText("刷写日志", consoleBuffer.toString()));
        toast("日志已复制");
    }

    /** 清空日志区（含缓冲，避免下一次「保存日志」把旧内容一起写回去）。 */
    private void clearLog() {
        if (consoleBuffer.length() == 0) {
            return;
        }
        clearConsole();
        // 清空后要把占位文案放回去：完全空着的一块深色区域分不清是「刚清过」
        // 还是「输出框坏了」。
        if (tvConsole != null) {
            tvConsole.setText("选择操作后，命令与输出会显示在这里");
        }
        toast("日志已清空");
    }

    // ───────────────── 进度区 ─────────────────

    /** 刷写开始：点亮进度区，把上一次的结果清掉。 */
    private void beginFlashProgress() {
        flashInProgress = true;
        flashSawFinished = false;
        flashStartedAt = System.currentTimeMillis();
        measuredSendSeconds = -1d;
        applyProgress(8, "正在检查分区…", 0);
        setSpeed("—");
        setEta("—");
    }

    /** 非刷写命令（读槽位 / 切槽位）开始时收掉进度，避免上一次的残留挂着。 */
    private void endFlashProgress() {
        if (flashInProgress) {
            flashInProgress = false;
        }
    }

    /** 回到「空闲」。 */
    private void resetProgress() {
        flashInProgress = false;
        applyProgress(0, "空闲", 0);
        setSpeed("—");
        setEta("—");
    }

    /**
     * 落一次进度。
     *
     * @param percent 0~100
     * @param state   状态文案
     * @param colorRes 状态文字颜色；0 表示用默认主文字色
     */
    private void applyProgress(int percent, String state, int colorRes) {
        int safe = Math.max(0, Math.min(100, percent));
        if (pbProgress != null) {
            pbProgress.setProgress(safe);
        }
        if (tvProgressPercent != null) {
            tvProgressPercent.setText(safe + "%");
        }
        if (tvProgressState != null) {
            tvProgressState.setText(state);
            tvProgressState.setTextColor(colorRes == 0
                    ? ThemeColors.textPrimary(this)
                    : ContextCompat.getColor(this, colorRes));
        }
    }

    /**
     * 用实测的发送耗时算速度。
     *
     * <p>这是页面上唯一的真实性能数字：镜像字节数 ÷ fastboot 自己报的发送秒数。
     * 拿不到镜像大小时退回「—」，不猜。
     */
    private void applyMeasuredSpeed(double seconds) {
        if (pickedImage == null || seconds <= 0d) {
            return;
        }
        double mb = pickedImage.length() / 1048576.0;
        setSpeed(String.format(Locale.US, "%.1f MB/s", mb / seconds));
    }

    private void setSpeed(String text) {
        if (tvProgressSpeed != null) {
            tvProgressSpeed.setText(text);
        }
    }

    private void setEta(String text) {
        if (tvProgressEta != null) {
            tvProgressEta.setText(text);
        }
    }

    /**
     * 从 fastboot 的一行里取方括号中的耗时。
     *
     * <p>格式固定为 {@code OKAY [  1.553s]}，但不同版本空格数不一，所以用正则而非
     * 按下标切。
     */
    private static Double stepSeconds(String line) {
        Matcher matcher = PATTERN_STEP_SECONDS.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ───────────────── 工具 ─────────────────

    private static void setOnClick(View view, Runnable action) {
        if (view != null) {
            view.setOnClickListener(v -> action.run());
        }
    }
}
