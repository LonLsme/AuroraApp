package com.aurora.app.ui.flash;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashDevice;
import com.aurora.app.core.flash.FlashLog;
import com.aurora.app.core.flash.FlashRuntime;

import java.io.File;
import java.util.Locale;

/**
 * 刷机子页面的公共骨架。
 *
 * <h3>覆盖的页面</h3>
 * 「设备重启」「刷入 KernelSU」（以及同形态的页面）。它们的页头与控制按钮完全一致：
 * <pre>
 *   [← 返回]                      [设备状态]
 *   大标题
 *   [保存日志] [终止]
 *   …… 页面自己的内容 ……
 *   [输出区]
 * </pre>
 * 区别只在「中间那块内容」，所以那一块留给子类，其余全部在这里实现一次。
 *
 * <h3>控件 id 约定</h3>
 * 子类布局必须使用以下固定 id（本类据此绑定，无需子类再逐个 findViewById）：
 * {@code btnFlashBack} / {@code tvFlashDeviceState} / {@code btnFlashSaveLog} /
 * {@code btnFlashStop} / {@code tvFlashConsole}。
 *
 * <h3>设备状态为什么自动探测一次</h3>
 * 右上角那行是<b>实时状态</b>，用户进页面第一眼就该看到「未连接设备」还是「已连接 · 序列号」。
 * 但 {@code adb devices} 首次会拉起 adb server（数秒），所以只在进页面时探一次，
 * 之后由「跑完一条命令」这个时机顺带刷新（设备状态已经变了）。
 */
public abstract class FlashConsoleActivity extends AppCompatActivity {

    /** 输出缓冲上限（字符数），超长任务不至于把内存吃满。 */
    private static final int MAX_LOG_CHARS = 60_000;

    /** 页内输出区。 */
    protected TextView tvConsole;
    /** 右上角设备状态文字。 */
    protected TextView tvDeviceState;

    /** 输出缓冲。 */
    protected final StringBuilder consoleBuffer = new StringBuilder();

    /** 首次输出前，输出框里显示的是布局里的那句占位引导。 */
    private boolean placeholderCleared;

    /** 最近一次探测结果；null 表示尚未成功探测。 */
    protected FlashDevice.State lastState;

    /**
     * 本页是否有命令在跑。
     *
     * <p>不能用 {@link FlashRuntime#isBusy()} 代替：那个闸门把「设备探测」也算作忙，
     * 而探测期间按返回不该弹「任务进行中」。
     */
    private boolean commandRunning;

    // ───────────────── 子类接口 ─────────────────

    /**
     * 会被「忙碌」态整体禁用的控件（页面自己的动作按钮）。
     *
     * <p>刻意排除「保存日志」「终止」「返回」—— 任务跑起来之后这三个反而要能用。
     */
    protected abstract View[] busyViews();

    /** 保存日志时用的文件名前缀。 */
    protected abstract String logFileTag();

    /** 输出框里初始的那句占位文案清掉之后，子类可选的收尾动作。 */
    protected void onFirstOutput() {
    }

    /**
     * 一次设备探测完成后的钩子（主线程）。
     *
     * <p>给「需要等探测结束才能做下一件事」的页面用（例如刷入 KernelSU 要在设备状态出来之后
     * 再去读 libksud 版本 —— 两者共用同一个单任务闸门，同时跑必然有一个被拒）。
     */
    protected void onDeviceStateRefreshed(FlashDevice.State state) {
    }

    /** 是否有本页发起的任务在跑。 */
    protected boolean isTaskRunning() {
        return commandRunning;
    }

    /** 用户点了「终止」。默认走全局取消（连同 adb / fastboot 残留进程一起清）。 */
    protected void onStopRequested() {
        FlashRuntime.get().cancel();
    }

    // ───────────────── 生命周期 ─────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });
    }

    /**
     * 子类在 {@code setContentView()} 之后调用：抓控件、接好通用按钮、开始首次探测。
     */
    protected void bindConsole() {
        tvConsole = findViewById(R.id.tvFlashConsole);
        tvDeviceState = findViewById(R.id.tvFlashDeviceState);

        if (tvConsole != null) {
            // 自身滚动：外层是可滚动的 NestedScrollView，再套一层只会让手势归属难判断。
            tvConsole.setMovementMethod(new ScrollingMovementMethod());
        }

        View back = findViewById(R.id.btnFlashBack);
        if (back != null) {
            back.setOnClickListener(v -> handleBack());
        }
        View save = findViewById(R.id.btnFlashSaveLog);
        if (save != null) {
            save.setOnClickListener(v -> saveLog());
        }
        View stop = findViewById(R.id.btnFlashStop);
        if (stop != null) {
            stop.setOnClickListener(v -> {
                if (!isTaskRunning()) {
                    toast("当前没有正在执行的任务");
                    return;
                }
                appendLine("[正在终止…]");
                onStopRequested();
            });
        }

        refreshDeviceState();
    }

    // ───────────────── 设备状态 ─────────────────

    /**
     * 探测一次设备状态并刷新右上角。
     *
     * <p>探测期间页面按「忙碌」处理（会拒绝新命令），探测结束自动恢复 ——
     * {@code adb devices} 与刷机命令共用同一个单任务闸门，不区分的话用户会在探测的几秒里
     * 点到按钮、然后收到一句莫名其妙的「已有任务在执行」。
     */
    protected void refreshDeviceState() {
        setDeviceState("正在检测…");
        setBusy(true);
        FlashDevice.detect(getApplicationContext(), state -> runOnUiThreadSafe(() -> {
            setBusy(false);
            lastState = state;
            setDeviceState(state == null ? FlashDevice.NOT_CONNECTED : state.summary());
            onDeviceStateRefreshed(state);
        }));
    }

    protected void setDeviceState(String text) {
        if (tvDeviceState != null) {
            tvDeviceState.setText(text);
        }
    }

    // ───────────────── 输出区 ─────────────────

    /** 追加一行输出。可从任意线程调用。 */
    protected void appendLine(String line) {
        runOnUiThreadSafe(() -> {
            if (!placeholderCleared && tvConsole != null) {
                tvConsole.setText("");
                placeholderCleared = true;
                onFirstOutput();
            }
            consoleBuffer.append(line == null ? "" : line).append('\n');
            trimBuffer();
            //noinspection ConstantConditions
            if (tvConsole != null) {
                tvConsole.setText(consoleBuffer.toString());
                scrollToBottom();
            }
        });
    }

    private void trimBuffer() {
        if (consoleBuffer.length() <= MAX_LOG_CHARS) {
            return;
        }
        int cut = consoleBuffer.indexOf("\n", consoleBuffer.length() - MAX_LOG_CHARS);
        if (cut > 0) {
            consoleBuffer.delete(0, cut + 1);
        }
    }

    /**
     * 输出追加后滚到底部。
     *
     * <p>默认滚的是输出框自身（{@code ScrollingMovementMethod} 下的内部滚动）。
     * 若子类的输出框是「随内容增高、由外层容器滚动」的形态，覆写本方法改为滚动外层，
     * 并把 {@code tvConsole} 的 movement method 置空（否则手势会被输出框吃掉，
     * 外层的上下弹性就没了）。
     */
    protected void scrollToBottom() {
        if (tvConsole == null || tvConsole.getLayout() == null) {
            return;
        }
        int lines = tvConsole.getLineCount();
        if (lines <= 0) {
            return;
        }
        int scroll = tvConsole.getLayout().getLineTop(lines) - tvConsole.getHeight();
        tvConsole.scrollTo(0, Math.max(scroll, 0));
    }

    // ───────────────── 保存日志 ─────────────────

    /** 把输出区内容落盘。 */
    protected void saveLog() {
        // 判「有没有真实输出」而不是判文本非空：输出区初始那句是占位引导
        // （「选择操作」「选择 .zip 刷机包后点击「开始刷入」」），
        // 文本非空但一个字都没产出时存下来的只会是一份没用的文件。
        if (consoleBuffer.length() == 0) {
            toast("还没有输出内容");
            return;
        }
        final String text = consoleBuffer.toString();
        final String tag = logFileTag();
        new Thread(() -> {
            File file = FlashLog.save(getApplicationContext(), tag, text);
            runOnUiThreadSafe(() -> toast(file == null
                    ? "日志保存失败"
                    : "日志已保存: " + file.getAbsolutePath()));
        }, "aurora-flash-save-log").start();
    }

    // ───────────────── 执行 ─────────────────

    /**
     * 一条命令跑完后的回调（<b>主线程</b>触发）。
     *
     * <p>给需要「拿命令输出做后续处理」的页面用 —— 例如「应用卸载冻结」页把
     * {@code adb shell pm list packages} 的输出解析成列表。输出既进了输出区，也在这里回传一份。
     */
    protected interface CommandCallback {
        /**
         * @param output    本次命令的完整输出（已合并 stdout / stderr）
         * @param exitCode  退出码；未能启动或本就没跑为 -1
         * @param cancelled 是否被用户终止
         */
        void onFinish(String output, int exitCode, boolean cancelled);
    }

    /**
     * 在本页输出区里跑一条命令。
     *
     * <p>用的是全局单任务闸门 {@link FlashRuntime}：同一时刻全 App 只有一个设备操作，
     * 这也是「探测」与「刷写」不会互相踩的原因。
     */
    protected void runConsoleCommand(String command) {
        runConsoleCommand(command, null);
    }

    /** 跑一条命令，并在结束时把完整输出回传给 {@code callback}（可为 null）。 */
    protected void runConsoleCommand(String command, final CommandCallback callback) {
        if (FlashRuntime.get().isBusy()) {
            toast("已有任务在执行");
            if (callback != null) {
                callback.onFinish("", -1, false);
            }
            return;
        }
        setBusy(true);
        commandRunning = true;
        appendLine("$ " + command);
        // 单独收一份本次输出：consoleBuffer 是跨命令累积的，解析时不能拿它。
        final StringBuilder sink = new StringBuilder();

        boolean accepted = FlashRuntime.get().run(getApplicationContext(), command,
                new FlashRuntime.Listener() {
                    @Override
                    public void onLine(String line) {
                        sink.append(line).append('\n');
                        appendLine(line);
                    }

                    @Override
                    public void onExit(int exitCode, boolean cancelled) {
                        appendLine(cancelled
                                ? "[已终止]"
                                : (exitCode == 0 ? "[完成] 退出码 0" : "[结束] 退出码 " + exitCode));
                        final String output = sink.toString();
                        runOnUiThreadSafe(() -> {
                            commandRunning = false;
                            setBusy(false);
                            // 重启 / 刷写后设备状态已经变了，顺带把右上角刷新一遍。
                            refreshDeviceState();
                            if (callback != null) {
                                callback.onFinish(output, exitCode, cancelled);
                            }
                        });
                    }
                });
        if (!accepted) {
            commandRunning = false;
            setBusy(false);
            appendLine("[错误] 已有任务在执行");
            if (callback != null) {
                callback.onFinish("", -1, false);
            }
        }
    }

    /** 执行前确认。刷机类操作不可逆，全部先过这一关。 */
    protected void confirmThenRun(String title, String message, String positive, String command) {
        confirmThenRun(title, message, positive, command, null);
    }

    /**
     * 执行前确认，并在跑完后把输出回传一份。
     *
     * <p>给「要拿命令输出更新界面状态」的页面用（QDL 页的 {@code rgpt} 要数导出了几个 LUN，
     * {@code get_slot} 要解析当前槽位）。不想要回调时走上面那个重载。
     */
    protected void confirmThenRun(String title, String message, String positive, String command,
                                  final CommandCallback callback) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton(positive, (d, w) -> runConsoleCommand(command, callback))
                .show();
    }

    // ───────────────── 忙碌态 ─────────────────

    protected void setBusy(boolean busy) {
        for (View view : busyViews()) {
            if (view != null) {
                view.setEnabled(!busy);
                view.setAlpha(busy ? 0.5f : 1f);
            }
        }
    }

    // ───────────────── 返回 ─────────────────

    /** 返回：有任务在跑就先问一句，别让用户一按就把流程甩在后台。 */
    protected void handleBack() {
        if (!isTaskRunning()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("任务进行中")
                .setMessage("现在退出不会中断任务，进度仍在后台继续；但这一页的输出会看不到。\n\n要终止任务吗？")
                .setNegativeButton("继续", null)
                .setNeutralButton("退出查看", (d, w) -> finish())
                .setPositiveButton("终止并退出", (d, w) -> {
                    onStopRequested();
                    finish();
                })
                .show();
    }

    // ───────────────── 工具 ─────────────────

    /**
     * 人类可读的文件体积。
     *
     * <p>放在基类是因为各子页的确认框都要写「镜像 xxx.img（12.3 MB）」——
     * 每个页面各写一份迟早会分叉（一个用 1024、一个用 1000）。
     */
    protected static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    protected void runOnUiThreadSafe(Runnable action) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                action.run();
            }
        });
    }

    protected void toast(String message) {
        runOnUiThreadSafe(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
