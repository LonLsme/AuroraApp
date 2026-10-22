package com.aurora.app.ui.flash;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashLog;
import com.aurora.app.core.flash.OplusFlasher;

import java.io.File;

/**
 * 「欧加真线刷」页（独立 Activity，竖屏，自带返回箭头与大标题）。
 *
 * <h3>为什么是独立页面而不是对话框</h3>
 * 欧加真线刷要盯着一大段输出看进度（重构逻辑分区 + 逐个分区刷写，几十条命令），
 * 对话框放不下。而它的形态又和刷机页那 7 张卡片不一样 —— 卡片是「一个动作」，
 * 这里是一套带参数（镜像目录）的完整流程，所以单开一页。
 *
 * <h3>目录路径为什么可以直接手输</h3>
 * fastboot 要的是<b>真实文件路径</b>，SAF 给的 {@code content://} 它读不了。
 * 从系统目录选择器拿到的 tree URI 会被转成 {@code /storage/...} 形式回填；
 * 转换失败（部分 ROM 的第三方文件提供商不给 documentId）时输入框仍然可编辑，
 * 让用户自己粘贴路径 —— 参考实现里也是这么兜的。
 */
public final class OplusFlashActivity extends AppCompatActivity {

    /** 请求码：选择镜像目录。 */
    private static final int REQ_PICK_DIR = 3101;

    /** 输出缓冲上限（字符数）。 */
    private static final int MAX_LOG_CHARS = 60_000;

    private static final String PREFS = "aurora_oplus_flash";
    private static final String KEY_DIR = "image_dir";

    private TextView tvLog;
    private EditText etDir;
    private View btnSaveLog;
    private View btnStop;
    private View btnFixSuper;
    private View btnNormal;
    private View btnPure;

    private final StringBuilder logBuffer = new StringBuilder();

    /** 首次输出前，输出框里显示的是布局里那句占位引导。 */
    private boolean placeholderCleared;

    private final OplusFlasher.Output flasherOutput = new OplusFlasher.Output() {
        @Override
        public void line(String text) {
            appendLine(text);
        }

        @Override
        public void finished(boolean ok) {
            post(() -> setBusy(false));
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oplus_flash);

        tvLog = findViewById(R.id.tvOplusLog);
        etDir = findViewById(R.id.etOplusDir);
        btnSaveLog = findViewById(R.id.btnOplusSaveLog);
        btnStop = findViewById(R.id.btnOplusStop);
        btnFixSuper = findViewById(R.id.btnOplusFixSuper);
        btnNormal = findViewById(R.id.btnOplusNormalFlash);
        btnPure = findViewById(R.id.btnOplusPureFastbootd);

        if (tvLog != null) {
            // 自身滚动：外层是可伸缩 LinearLayout，套 ScrollView 只会让手势归属难判断。
            tvLog.setMovementMethod(new ScrollingMovementMethod());
        }

        findViewById(R.id.btnOplusBack).setOnClickListener(v -> handleBack());

        btnSaveLog.setOnClickListener(v -> saveLog());
        btnStop.setOnClickListener(v -> stopFlash());
        btnFixSuper.setOnClickListener(v -> startFlash(OplusFlasher.Mode.FIX_SUPER));
        btnNormal.setOnClickListener(v -> startFlash(OplusFlasher.Mode.NORMAL));
        btnPure.setOnClickListener(v -> startFlash(OplusFlasher.Mode.PURE_FASTBOOTD));
        findViewById(R.id.btnOplusPickDir).setOnClickListener(v -> pickDirectory());

        if (etDir != null) {
            etDir.setText(prefs().getString(KEY_DIR, ""));
        }

        // 返回键：刷机进行中先问一句，别让用户一按就把流程甩在后台。
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        if (OplusFlasher.isBusy()) {
            // 上一次会话（或主题重建）留下的任务还在跑，这一页接不上它的输出，如实说明。
            setBusy(true);
            appendLine("[提示] 已有一个刷机任务在执行，等待它结束或点「终止」");
        }
    }

    // ───────────────── 目录 ─────────────────

    private void pickDirectory() {
        if (!FlashFilePicker.pickDirectory(this, REQ_PICK_DIR)) {
            toast("无法打开目录选择器");
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
            // 参考实现同样在这里退回「手动输入」：SAF 的 content:// 路径 fastboot 用不了。
            toast("无法使用 SAF 内容路径，请在文本框中手动输入完整路径\n"
                    + "示例: /storage/emulated/0/Download/oplus_images");
            return;
        }
        if (etDir != null) {
            etDir.setText(path);
        }
        saveDir(path);
    }

    private void saveDir(String path) {
        prefs().edit().putString(KEY_DIR, path == null ? "" : path).apply();
    }

    private String currentDir() {
        return etDir == null || etDir.getText() == null
                ? "" : etDir.getText().toString().trim();
    }

    // ───────────────── 刷写 ─────────────────

    /**
     * 确认后再开始。
     *
     * <p>刷机是不可逆操作，开始前把「这一步具体做什么、会有什么后果」摆出来 ——
     * 尤其「修复Super分区」会清空超分区，不说清楚用户刷完就开不了机。
     */
    private void startFlash(OplusFlasher.Mode mode) {
        if (OplusFlasher.isBusy()) {
            toast("已有任务在执行");
            return;
        }
        String path = currentDir();
        if (path.isEmpty()) {
            toast("请先选择镜像目录");
            return;
        }
        final File dir = new File(path);
        final OplusFlasher.Inventory inventory = OplusFlasher.scan(dir);
        if (!inventory.valid()) {
            appendLine("[错误] " + inventory.error + ": " + path);
            toast(inventory.error);
            return;
        }
        saveDir(path);

        new AlertDialog.Builder(this)
                .setTitle(mode.label)
                .setMessage(OplusFlasher.describe(mode, inventory))
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (d, w) -> runFlash(mode, inventory))
                .show();
    }

    private void runFlash(OplusFlasher.Mode mode, OplusFlasher.Inventory inventory) {
        setBusy(true);
        boolean started = OplusFlasher.start(this, mode, inventory, flasherOutput);
        if (!started) {
            setBusy(false);
            if (OplusFlasher.isBusy()) {
                appendLine("[错误] 已有任务在执行");
                toast("已有任务在执行");
            }
        }
    }

    private void stopFlash() {
        if (!OplusFlasher.isBusy()) {
            toast("当前没有正在执行的刷机任务");
            return;
        }
        OplusFlasher.cancel();
        appendLine("[正在终止…]");
    }

    /** 返回：刷机中先确认，避免误退。 */
    private void handleBack() {
        if (!OplusFlasher.isBusy()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("刷写进行中")
                .setMessage("现在退出不会中断刷写，进度仍在后台继续；"
                        + "但这一页的输出会看不到。\n\n要终止刷写吗？")
                .setNegativeButton("继续刷写", null)
                .setNeutralButton("退出查看", (d, w) -> finish())
                .setPositiveButton("终止并退出", (d, w) -> {
                    OplusFlasher.cancel();
                    finish();
                })
                .show();
    }

    // ───────────────── 日志 ─────────────────

    private void appendLine(String line) {
        post(() -> {
            if (!placeholderCleared && tvLog != null) {
                tvLog.setText("");
                placeholderCleared = true;
            }
            logBuffer.append(line == null ? "" : line).append('\n');
            if (logBuffer.length() > MAX_LOG_CHARS) {
                int cut = logBuffer.indexOf("\n", logBuffer.length() - MAX_LOG_CHARS);
                if (cut > 0) {
                    logBuffer.delete(0, cut + 1);
                }
            }
            if (tvLog != null) {
                tvLog.setText(logBuffer.toString());
                scrollToBottom();
            }
        });
    }

    private void scrollToBottom() {
        if (tvLog == null || tvLog.getLayout() == null) {
            return;
        }
        int lines = tvLog.getLineCount();
        if (lines <= 0) {
            return;
        }
        int scroll = tvLog.getLayout().getLineTop(lines) - tvLog.getHeight();
        tvLog.scrollTo(0, Math.max(scroll, 0));
    }

    private void saveLog() {
        final String text = logBuffer.length() == 0
                ? (tvLog == null || tvLog.getText() == null ? "" : tvLog.getText().toString())
                : logBuffer.toString();
        if (text.trim().isEmpty()) {
            toast("还没有输出内容");
            return;
        }
        new Thread(() -> {
            File file = FlashLog.save(getApplicationContext(), "oplus", text);
            post(() -> toast(file == null
                    ? "日志保存失败"
                    : "日志已保存: " + file.getAbsolutePath()));
        }, "aurora-oplus-save-log").start();
    }

    // ───────────────── 工具 ─────────────────

    private void setBusy(boolean busy) {
        // 刷写中把「会启动新流程」的入口全部关掉；「终止」「保存日志」「返回」保持可用。
        for (View view : new View[]{btnFixSuper, btnNormal, btnPure}) {
            if (view != null) {
                view.setEnabled(!busy);
                view.setAlpha(busy ? 0.5f : 1f);
            }
        }
        if (etDir != null) {
            etDir.setEnabled(!busy);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void post(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                action.run();
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
