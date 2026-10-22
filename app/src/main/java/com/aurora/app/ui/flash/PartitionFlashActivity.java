package com.aurora.app.ui.flash;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashCommands;

import java.io.File;

/**
 * 「刷写分区」页（独立 Activity，竖屏，自带返回箭头与大标题）。
 *
 * <h3>它做什么</h3>
 * 把「拿一个 .img 刷进某个分区」这件事的完整前置动作摆在一页里：
 * <ol>
 *   <li><b>获取设备槽位</b> —— 只读，跑一次 {@code fastboot getvar current-slot}；</li>
 *   <li><b>选择镜像</b> —— 选完拷进应用专属目录（fastboot 只认真实路径）；</li>
 *   <li><b>切换AB分区</b> —— 读当前槽位后切到对侧，非 A/B 设备直接拒绝；</li>
 *   <li><b>刷入 boot / dtbo / recovery</b> —— 三个最常刷的分区，各占一个按钮；</li>
 *   <li><b>自定义分区刷入</b> —— 手输分区名 + 同一个镜像，刷到任意分区。</li>
 * </ol>
 *
 * <h3>为什么每个刷入按钮都先跑一次「分区存在性检查」</h3>
 * 见 {@link FlashCommands#fastbootFlashChecked} —— 这是既有工具的做法：
 * 分区在目标机上不存在就拒绝刷入，而不是把镜像盲写进去。分区名手输（自定义那行）
 * 时这一步尤其值钱：{@code initboot} 和 {@code init_boot} 差一个下划线，
 * 盲刷的后果是刷了个寂寞还看不出为什么。
 *
 * <h3>为什么不自动判断「该用哪个通道」</h3>
 * 与 {@link RebootActivity} 同样的理由：设备刚重启、adb / fastboot 都没就绪时，
 * 自动判断只会给出误导性的命令。右上角那行实时状态就是给人看这个的 ——
 * 该进 fastboot 还是该插线，由用户根据它决定。
 */
public final class PartitionFlashActivity extends FlashConsoleActivity {

    /** 请求码：选择 .img 镜像。 */
    private static final int REQ_PICK_IMAGE = 9301;

    /**
     * 三个固定分区按钮对应的分区名。
     *
     * <p>与既有工具 {@code s80.java} 里那三个 {@code o80(..., case 3/4/5)} 一一对应：
     * {@code fastboot flash boot} / {@code fastboot flash recovery} / {@code fastboot flash dtbo}。
     */
    private static final String PARTITION_BOOT = "boot";
    private static final String PARTITION_DTBO = "dtbo";
    private static final String PARTITION_RECOVERY = "recovery";

    // ── 页面控件 ──
    /** 「选择镜像」按钮。它的文字本身就是状态回显（选完显示已选文件名）。 */
    private TextView btnPickImage;
    private EditText etPartition;
    private View btnGetSlot;
    private View btnSwitchSlot;
    private View btnFlashBoot;
    private View btnFlashDtbo;
    private View btnFlashRecovery;
    private View btnFlashCustom;

    /** 外层滚动容器：输出变长时由它滚动，从而「上下都有弹性」。 */
    private NestedScrollView scrollRoot;

    private FlashDeviceDropdown deviceDropdown;

    /** 已选镜像；null 表示还没选。 */
    private File pickedImage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_partition_flash);

        btnGetSlot = findViewById(R.id.btnPartitionGetSlot);
        btnPickImage = findViewById(R.id.btnPartitionPickImage);
        btnSwitchSlot = findViewById(R.id.btnPartitionSwitchSlot);
        btnFlashBoot = findViewById(R.id.btnPartitionFlashBoot);
        btnFlashDtbo = findViewById(R.id.btnPartitionFlashDtbo);
        btnFlashRecovery = findViewById(R.id.btnPartitionFlashRecovery);
        btnFlashCustom = findViewById(R.id.btnPartitionFlashCustom);
        etPartition = findViewById(R.id.etPartitionName);
        scrollRoot = findViewById(R.id.scrollPartition);
        View pill = findViewById(R.id.btnPartitionDevicePill);

        // 抓公共控件 + 接好返回 / 保存日志 / 终止，并触发一次设备探测。
        bindConsole();

        // 输出区随内容增高、由外层承担滚动 —— 必须取消输出框自身的滚动，
        // 否则竖向手势会被它吃掉，页面就不再上下回弹了。
        if (tvConsole != null) {
            tvConsole.setMovementMethod(null);
        }

        deviceDropdown = new FlashDeviceDropdown(this, pill);
        if (pill != null) {
            pill.setOnClickListener(v -> deviceDropdown.toggle(lastState));
        }

        setOnClick(btnGetSlot, this::readSlot);
        setOnClick(btnPickImage, this::pickImage);
        setOnClick(btnSwitchSlot, this::switchSlot);
        setOnClick(btnFlashBoot, () -> flashPartition(PARTITION_BOOT));
        setOnClick(btnFlashDtbo, () -> flashPartition(PARTITION_DTBO));
        setOnClick(btnFlashRecovery, () -> flashPartition(PARTITION_RECOVERY));
        setOnClick(btnFlashCustom, this::flashCustomPartition);
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
        // 只禁动作按钮：「分区名」输入框保持可编辑 —— 设备探测要几秒，
        // 那几秒里用户完全可以先把分区名打好。
        return new View[]{btnGetSlot, btnPickImage, btnSwitchSlot,
                btnFlashBoot, btnFlashDtbo, btnFlashRecovery, btnFlashCustom};
    }

    @Override
    protected String logFileTag() {
        return "partition";
    }

    // ───────────────── 槽位 ─────────────────

    /** 读当前槽位：只读操作，不需要确认，结果直接落输出区。 */
    private void readSlot() {
        runConsoleCommand(FlashCommands.fastbootReportSlot());
    }

    /**
     * 切到对侧槽位。
     *
     * <p>要确认是因为它<b>改变设备的启动行为</b>：切完之后重启进的是另一个槽位的系统，
     * 而用户很可能正打算刷某个分区 —— 槽位选错的话镜像就刷到另一份上去了。
     */
    private void switchSlot() {
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
                    setPickImageLabel(file.getName());
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

    private void setPickImageLabel(String name) {
        if (btnPickImage != null) {
            btnPickImage.setText(name);
        }
    }

    // ───────────────── 刷入 ─────────────────

    /** 三个固定分区按钮走这里。 */
    private void flashPartition(String partition) {
        if (!requireImage()) {
            return;
        }
        String command = FlashCommands.fastbootFlashChecked(
                partition, pickedImage.getAbsolutePath());
        confirmThenRun("确认刷入 " + partition,
                "镜像：" + pickedImage.getName() + "（" + humanSize(pickedImage.length()) + "）\n"
                        + "分区：" + partition + "\n\n"
                        + "刷入前会先确认该分区在设备上存在，不存在则拒绝刷入。\n\n"
                        + "命令：\n" + command,
                "刷入", command);
    }

    /** 「自定义分区刷入」那行：分区名来自输入框。 */
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
        confirmThenRun("确认刷入 " + partition,
                "镜像：" + pickedImage.getName() + "（" + humanSize(pickedImage.length()) + "）\n"
                        + "分区：" + partition + "\n\n"
                        + "分区名写错会刷到别的分区上，且不可逆。\n\n"
                        + "命令：\n" + command,
                "刷入", command);
    }

    /** 没选镜像时给一句提示并返回 {@code false}，让调用方直接收手。 */
    private boolean requireImage() {
        if (pickedImage == null || !pickedImage.isFile()) {
            pickedImage = null;
            toast("请先点「选择镜像 (.img)」");
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

    // ───────────────── 工具 ─────────────────

    private static void setOnClick(View view, Runnable action) {
        if (view != null) {
            view.setOnClickListener(v -> action.run());
        }
    }
}
