package com.aurora.app.ui.flash;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashLog;

import java.io.File;

/**
 * 刷机操作的「执行输出」对话框。
 *
 * <p>每个会跑命令的卡片各自弹一个 —— 之前刷机页底部常驻一个终端区，
 * 输出落在页面级容器里，导致「刚点的那张卡片到底在干什么」看不出来；
 * 改成随操作弹出的独立输出框后，输出与操作一一对应。
 *
 * <h3>执行中不让关</h3>
 * 刷机跑到一半把输出框关掉，等于把「正在写哪个分区」的信息丢了。
 * 所以执行期间正按钮禁用且点外部/返回键不响应，只留「终止」；
 * 结束后正按钮才变成可点的「关闭」。
 */
public final class FlashRunDialog {

    /** 输出缓冲上限（字符数）。超长任务不至于把内存吃满。 */
    private static final int MAX_LOG_CHARS = 60_000;

    private final Activity activity;
    private final AlertDialog dialog;
    private final TextView logView;

    private final StringBuilder buffer = new StringBuilder();
    private boolean finished;

    private FlashRunDialog(Activity activity, String title) {
        this.activity = activity;

        View content = activity.getLayoutInflater()
                .inflate(R.layout.dialog_flash_output, null, false);
        logView = content.findViewById(R.id.tvRunLog);
        if (logView != null) {
            // 自身滚动，避免和外层任何滚动容器抢手势
            logView.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(content)
                .setCancelable(false)   // 执行期间点外部不关；结束后由代码打开
                .setNeutralButton("保存日志", null)
                .setNegativeButton("终止", null)
                .setPositiveButton("关闭", null);

        dialog = builder.create();
        dialog.show();

        Button close = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (close != null) {
            close.setEnabled(false);
            close.setOnClickListener(v -> {
                if (finished) {
                    dialog.dismiss();
                }
            });
        }
        Button stop = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (stop != null) {
            stop.setEnabled(false);   // 没有任务在跑时「终止」无意义
            stop.setOnClickListener(v -> {
                stop.setEnabled(false);
                append("[正在终止…]");
                if (listener != null) {
                    listener.onStop();
                }
            });
        }
        Button save = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (save != null) {
            save.setOnClickListener(v -> saveLog());
        }
    }

    /** 「终止」按钮的回调。 */
    public interface StopListener {
        void onStop();
    }

    private StopListener listener;

    /**
     * 弹出输出框。
     *
     * @param stopListener 点「终止」时回调；传 {@code null} 则该按钮始终不可用
     */
    public static FlashRunDialog show(Activity activity, String title, StopListener stopListener) {
        FlashRunDialog instance = new FlashRunDialog(activity, title);
        instance.listener = stopListener;
        Button stop = instance.dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (stop != null && stopListener != null) {
            stop.setEnabled(true);
        }
        return instance;
    }

    // ───────────────── 输出 ─────────────────

    /** 追加一行。可从任意线程调用。 */
    public void append(String line) {
        run(() -> {
            buffer.append(line == null ? "" : line).append('\n');
            if (buffer.length() > MAX_LOG_CHARS) {
                int cut = buffer.indexOf("\n", buffer.length() - MAX_LOG_CHARS);
                if (cut > 0) {
                    buffer.delete(0, cut + 1);
                }
            }
            if (logView != null) {
                logView.setText(buffer.toString());
                scrollToBottom();
            }
        });
    }

    /** 追加一行并标记为「有结果」，用于流程结束的收尾提示。 */
    public void finish(int exitCode, boolean cancelled) {
        run(() -> {
            finished = true;
            AlertDialog safe = dialog;
            if (safe == null) {
                return;
            }
            Button close = safe.getButton(AlertDialog.BUTTON_POSITIVE);
            if (close != null) {
                close.setEnabled(true);
            }
            Button stop = safe.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (stop != null) {
                stop.setEnabled(false);
            }
            // 结束后允许点外部/返回键关闭
            safe.setCancelable(true);
            safe.setCanceledOnTouchOutside(true);
        });
    }

    /** 是否仍在执行（未调用 {@link #finish}）。 */
    public boolean isRunning() {
        return !finished;
    }

    public void dismiss() {
        run(() -> {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        });
    }

    /** 当前全部输出文本。 */
    public String content() {
        return buffer.toString();
    }

    // ───────────────── 内部 ─────────────────

    private void scrollToBottom() {
        if (logView == null || logView.getLayout() == null) {
            return;
        }
        int lineCount = logView.getLineCount();
        if (lineCount <= 0) {
            return;
        }
        int scroll = logView.getLayout().getLineTop(lineCount) - logView.getHeight();
        logView.scrollTo(0, Math.max(scroll, 0));
    }

    private void saveLog() {
        final String text = buffer.toString();
        if (text.isEmpty()) {
            toast("还没有输出内容");
            return;
        }
        final Context appContext = activity.getApplicationContext();
        new Thread(() -> {
            File file = FlashLog.save(appContext, "flash", text);
            run(() -> toast(file == null
                    ? "日志保存失败"
                    : "日志已保存: " + file.getAbsolutePath()));
        }, "aurora-flash-save-log").start();
    }

    private void toast(String message) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private void run(Runnable action) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                action.run();
            }
        });
    }
}
