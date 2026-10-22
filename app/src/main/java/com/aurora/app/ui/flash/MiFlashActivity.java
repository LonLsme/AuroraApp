package com.aurora.app.ui.flash;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashCommands;
import com.aurora.app.core.flash.MiFlashScript;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「小米线刷」页（独立 Activity，竖屏，自带返回箭头与大标题）。
 *
 * <h3>小米线刷是怎么刷的</h3>
 * 小米官方线刷包解开之后，根目录里带着一组批处理脚本，刷机就是
 * <b>「切到包目录 → 跑其中一个脚本」</b>，脚本内部再逐条调 {@code fastboot flash}。
 * 所以这一页的流程很短：选目录 → 扫描出脚本 → 选一个 → 确认 → 执行。
 * 脚本名与语义、执行命令的写法都照抄参考实现（见 {@link MiFlashScript} 与
 * {@link FlashCommands#runShellScript}）。
 *
 * <h3>为什么「扫描」之后还要弹一次选择框</h3>
 * 一个线刷包里通常同时有 {@code flash_all.sh}（清数据）、
 * {@code flash_all_except_storage.sh}（保数据）、{@code flash_all_lock.sh}（回锁 BL），
 * 三者后果完全不同。扫描只解决「有哪些」，选哪个必须由用户按自己的目的来定 ——
 * 所以扫描结果同时进输出区（留痕）和选择框（下一步动作）。
 *
 * <h3>与欧加真线刷页的区别</h3>
 * 那边是我们自己拼命令、一步步控制刷写顺序；小米这边是执行厂商脚本，命令由脚本决定。
 * 两者的页头 / 输出区 / 终止逻辑一致，都跑在 {@link FlashConsoleActivity} 上。
 */
public final class MiFlashActivity extends FlashConsoleActivity {

    /** 请求码：选择刷机包目录。 */
    private static final int REQ_PICK_DIR = 9201;

    private static final String PREFS = "aurora_mi_flash";
    private static final String KEY_DIR = "mi_dir";

    private EditText etDir;
    private View btnBrowse;
    private View btnScan;

    /** 外层滚动容器：日志变长时由它滚动，从而「上下都有弹性」。 */
    private NestedScrollView scrollRoot;

    private FlashDeviceDropdown deviceDropdown;

    /** 最近一次扫描结果。 */
    private final List<MiFlashScript> scanned = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mi_flash);

        etDir = findViewById(R.id.etMiDir);
        btnBrowse = findViewById(R.id.btnMiBrowse);
        btnScan = findViewById(R.id.btnMiScan);
        scrollRoot = findViewById(R.id.scrollMiFlash);
        View pill = findViewById(R.id.btnMiDevicePill);

        // 抓公共控件 + 接好返回 / 保存日志 / 终止，并触发一次设备探测。
        bindConsole();

        // 日志区随内容增高、由外层承担滚动 —— 必须取消输出框自身的滚动，
        // 否则竖向手势会被它吃掉，页面就不再上下回弹了。
        if (tvConsole != null) {
            tvConsole.setMovementMethod(null);
        }

        deviceDropdown = new FlashDeviceDropdown(this, pill);
        if (pill != null) {
            pill.setOnClickListener(v -> deviceDropdown.toggle(lastState));
        }
        if (btnBrowse != null) {
            btnBrowse.setOnClickListener(v -> pickDirectory());
        }
        if (btnScan != null) {
            btnScan.setOnClickListener(v -> scanDirectory());
        }

        if (etDir != null) {
            etDir.setText(prefs().getString(KEY_DIR, ""));
        }
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
        // 只禁两个动作按钮：设备探测要几秒，这段时间输入框还得能改。
        return new View[]{btnBrowse, btnScan};
    }

    @Override
    protected String logFileTag() {
        return "miflash";
    }

    // ───────────────── 目录 ─────────────────

    private void pickDirectory() {
        if (!FlashFilePicker.pickDirectory(this, REQ_PICK_DIR)) {
            toast("无法打开目录选择器，请手动输入路径");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_DIR || resultCode != RESULT_OK || data == null) {
            return;
        }
        String path = FlashFilePicker.resolveTreePath(data.getData());
        if (path == null) {
            // 与欧加真页同款兜底：SAF 的 content:// 路径 shell / fastboot 都用不了。
            toast("无法使用 SAF 内容路径，请在文本框中手动输入完整路径\n"
                    + "示例: /storage/emulated/0/Download/mi_rom");
            return;
        }
        if (etDir != null) {
            etDir.setText(path);
        }
        prefs().edit().putString(KEY_DIR, path).apply();
        appendLine("目录: " + path);
    }

    private String currentDir() {
        return etDir == null || etDir.getText() == null
                ? "" : etDir.getText().toString().trim();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ───────────────── 扫描 ─────────────────

    /** 扫目录里的 {@code .sh} 脚本：结果既进输出区（留痕）也进选择框（下一步动作）。 */
    private void scanDirectory() {
        final String dir = currentDir();
        if (dir.isEmpty()) {
            toast("请先选择或输入刷机包目录");
            return;
        }
        final File root = new File(dir);
        if (!root.isDirectory()) {
            appendLine("[错误] 目录不存在: " + dir);
            toast("目录不存在，请检查路径");
            return;
        }
        appendLine("$ 扫描 " + dir);
        // 刷机包根目录里文件不少（几十个镜像 + 脚本），别压在 UI 线程上。
        new Thread(() -> {
            final List<MiFlashScript> found = MiFlashScript.scan(root);
            runOnUiThreadSafe(() -> onScanned(dir, found));
        }, "aurora-miflash-scan").start();
    }

    private void onScanned(String dir, List<MiFlashScript> found) {
        scanned.clear();
        scanned.addAll(found);

        if (scanned.isEmpty()) {
            appendLine("未找到脚本");
            toast("该目录下没有 .sh 脚本");
            return;
        }
        appendLine(String.format(Locale.CHINA, "已找到 %d 个脚本", scanned.size()));
        for (MiFlashScript script : scanned) {
            appendLine("  · " + script.name + hintSuffix(script.kind));
        }
        showScriptPicker(dir);
    }

    private void showScriptPicker(final String dir) {
        final String[] labels = new String[scanned.size()];
        for (int i = 0; i < scanned.size(); i++) {
            MiFlashScript script = scanned.get(i);
            labels[i] = script.name + hintSuffix(script.kind);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择脚本")
                .setItems(labels, (d, which) -> confirmAndRun(scanned.get(which), dir))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 确认后执行。
     *
     * <p>两个会清数据的脚本（{@code flash_all.sh} / {@code flash_all_lock.sh}）用参考实现里
     * 的原句把后果说在前面 —— 刷完开不了机再来问就晚了。
     */
    private void confirmAndRun(MiFlashScript script, String dir) {
        String command = FlashCommands.runShellScript(dir, script.name);
        String body = confirmBodyOf(script.kind);
        StringBuilder message = new StringBuilder();
        if (body != null) {
            message.append(body).append("\n\n");
        }
        message.append("命令：\n").append(command);
        confirmThenRun("确认执行", message.toString(), "确认", command);
    }

    // ───────────────── 文案（取自参考实现的字符串资源） ─────────────────

    /** 换行缩进 + 类型提示；认不出类型时返回空串。 */
    private static String hintSuffix(MiFlashScript.Kind kind) {
        String hint = hintOf(kind);
        return hint.isEmpty() ? "" : "    " + hint;
    }

    private static String hintOf(MiFlashScript.Kind kind) {
        switch (kind) {
            case FLASH_ALL:
                return "[*] 不回锁BL + 清除数据";
            case FLASH_ALL_EXCEPT_STORAGE:
                return "[*] 不回锁BL + 不清除数据";
            case FLASH_ALL_LOCK:
                return "[!] 回锁BL + 清除数据";
            default:
                return "";
        }
    }

    /** 执行前确认正文；不需要额外警告的脚本返回 {@code null}。 */
    private static String confirmBodyOf(MiFlashScript.Kind kind) {
        switch (kind) {
            case FLASH_ALL:
                return "执行 flash_all.sh 脚本会清除数据，确定继续吗？";
            case FLASH_ALL_LOCK:
                return "执行 flash_all_lock.sh 脚本会回锁BL和清除数据，确定继续吗？";
            default:
                return null;
        }
    }
}
