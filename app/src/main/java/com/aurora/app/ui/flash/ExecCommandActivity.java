package com.aurora.app.ui.flash;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashCommands;
import com.aurora.app.core.flash.FlashDevice;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/**
 * 「执行命令」页：用户自己写一条命令，打到连着的那台设备上。
 *
 * <h3>三条通道</h3>
 * 输入框里只写<b>参数部分</b>，前缀由通道决定（拼装规则见 {@link FlashCommands}）：
 * <pre>
 *   ADB      adb -s &lt;序列号&gt; &lt;输入&gt;
 *   Fastboot fastboot -s &lt;序列号&gt; &lt;输入&gt;
 *   Shell    adb -s &lt;序列号&gt; shell &lt;输入&gt;
 * </pre>
 * 序列号取自右上角那次探测（{@link #lastState}）。还没探测到就不带 {@code -s}，
 * 单设备时 adb 自己会选，插了两台则会明确报 {@code more than one device}。
 *
 * <h3>为什么不做「自动判断该走 ADB 还是 Fastboot」</h3>
 * 与刷机页其它子页一致：设备刚重启、两种通道都没就绪的那几秒里，自动判断给出的命令
 * 一定是错的。通道交给用户点，右上角那行实时状态就是给他看这个的。
 *
 * <h3>为什么每条都弹确认框</h3>
 * 本页输入不转义、原样交给 root shell（否则管道和引号都用不了）。既然把判断权交给了用户，
 * 就得在真正执行前把<b>拼装后的完整命令</b>摆一遍 —— 这是唯一能让他发现
 * 「我以为在设备上跑、其实在本机跑」的地方。
 */
public final class ExecCommandActivity extends FlashConsoleActivity {

    /** 历史命令与通道选择的存放位置。 */
    private static final String PREFS_NAME = "aurora_exec_command";
    private static final String KEY_CHANNEL = "channel";
    private static final String KEY_HISTORY = "history";

    /** 历史保留条数：够翻回上几次，又不至于把卡片撑成一屏。 */
    private static final int MAX_HISTORY = 12;

    private SharedPreferences prefs;

    private RadioGroup rgChannel;
    private View rbAdb;
    private View rbFastboot;
    private View rbShell;
    private TextInputEditText etCommand;
    private View btnRun;
    private View btnClear;
    private View layoutHistory;
    private LinearLayout listHistory;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exec_command);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        rgChannel = findViewById(R.id.rgExecChannel);
        rbAdb = findViewById(R.id.rbExecAdb);
        rbFastboot = findViewById(R.id.rbExecFastboot);
        rbShell = findViewById(R.id.rbExecShell);
        etCommand = findViewById(R.id.etExecCommand);
        btnRun = findViewById(R.id.btnExecRun);
        btnClear = findViewById(R.id.btnExecClear);
        layoutHistory = findViewById(R.id.layoutExecHistory);
        listHistory = findViewById(R.id.listExecHistory);

        applyChannel(prefs.getInt(KEY_CHANNEL, FlashCommands.CHANNEL_ADB));
        if (rgChannel != null) {
            rgChannel.setOnCheckedChangeListener((group, checkedId) ->
                    prefs.edit().putInt(KEY_CHANNEL, currentChannel()).apply());
        }

        if (btnRun != null) {
            btnRun.setOnClickListener(v -> executeCommand());
        }
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                if (etCommand != null) {
                    etCommand.setText("");
                }
            });
        }
        // 键盘上的「前往」等价于点执行：命令行场景下多按一次按钮是纯阻力。
        if (etCommand != null) {
            etCommand.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    executeCommand();
                    return true;
                }
                return false;
            });
        }

        renderHistory();
        bindConsole();
    }

    // ───────────────── 通道 ─────────────────

    private int currentChannel() {
        int checked = rgChannel == null ? -1 : rgChannel.getCheckedRadioButtonId();
        if (checked == R.id.rbExecFastboot) {
            return FlashCommands.CHANNEL_FASTBOOT;
        }
        if (checked == R.id.rbExecShell) {
            return FlashCommands.CHANNEL_SHELL;
        }
        return FlashCommands.CHANNEL_ADB;
    }

    private void applyChannel(int channel) {
        if (rgChannel == null) {
            return;
        }
        int id = R.id.rbExecAdb;
        if (channel == FlashCommands.CHANNEL_FASTBOOT) {
            id = R.id.rbExecFastboot;
        } else if (channel == FlashCommands.CHANNEL_SHELL) {
            id = R.id.rbExecShell;
        }
        rgChannel.check(id);
    }

    // ───────────────── 执行 ─────────────────

    private void executeCommand() {
        final String raw = etCommand == null || etCommand.getText() == null
                ? "" : etCommand.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("请先输入命令");
            return;
        }

        final int channel = currentChannel();
        final String command = buildCommand(channel, raw);

        StringBuilder message = new StringBuilder();
        message.append("命令：\n").append(command);
        message.append("\n\n将以 root 身份在本机（这台 Aurora）执行，作用对象是连着的那台设备。");
        String warning = channelWarning(channel);
        if (warning != null) {
            message.append("\n\n").append(warning);
        }

        confirmThenRun("执行命令", message.toString(), "执行", command);
        rememberCommand(raw);
    }

    /** 按通道拼装最终命令。输入为空时返回空串（调用方已拦过）。 */
    private String buildCommand(int channel, String raw) {
        String serial = serialFor(channel);
        if (channel == FlashCommands.CHANNEL_FASTBOOT) {
            return FlashCommands.customFastboot(serial, raw);
        }
        if (channel == FlashCommands.CHANNEL_SHELL) {
            return FlashCommands.customShell(serial, raw);
        }
        return FlashCommands.customAdb(serial, raw);
    }

    /** 当前通道对应的序列号；尚未探测到设备时为 null。 */
    private String serialFor(int channel) {
        FlashDevice.State state = lastState;
        if (state == null) {
            return null;
        }
        return channel == FlashCommands.CHANNEL_FASTBOOT ? state.fastbootSerial : state.adbSerial;
    }

    /**
     * 通道没就绪时给一句提醒（写进确认框，不摆到页面上）。
     *
     * <p>只提醒不拦截：设备状态是轮询出来的，可能比真实情况慢几秒；
     * 而且 {@code fastboot} 在没有设备时会一直挂着等，用户看到提醒就知道该先插线还是先按终止。
     */
    private String channelWarning(int channel) {
        FlashDevice.State state = lastState;
        if (state == null) {
            return "还没有探测到设备状态，命令可能直接失败。";
        }
        if (channel == FlashCommands.CHANNEL_FASTBOOT) {
            if (state.fastbootReady) {
                return null;
            }
            return "当前未检测到 Fastboot 设备，fastboot 会一直等待设备接入（可点终止）。";
        }
        if (state.adbReady) {
            return null;
        }
        if (state.adbSerial == null) {
            return "当前未检测到 ADB 设备，命令可能直接失败。";
        }
        return "ADB 设备状态为 " + state.adbState + "，命令可能失败。";
    }

    // ───────────────── 历史命令 ─────────────────

    private List<String> loadHistory() {
        List<String> out = new ArrayList<>();
        String stored = prefs == null ? null : prefs.getString(KEY_HISTORY, "");
        if (stored == null || stored.isEmpty()) {
            return out;
        }
        for (String line : stored.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private void saveHistory(List<String> commands) {
        if (prefs == null) {
            return;
        }
        prefs.edit().putString(KEY_HISTORY, TextUtils.join("\n", commands)).apply();
    }

    /** 记一条历史：重复的提到最前，超出上限从尾部丢。 */
    private void rememberCommand(String command) {
        List<String> commands = loadHistory();
        commands.remove(command);
        commands.add(0, command);
        while (commands.size() > MAX_HISTORY) {
            commands.remove(commands.size() - 1);
        }
        saveHistory(commands);
        renderHistory();
    }

    private void forgetCommand(String command) {
        List<String> commands = loadHistory();
        if (commands.remove(command)) {
            saveHistory(commands);
            renderHistory();
            toast("已删除该条历史");
        }
    }

    /**
     * 把历史铺进卡片。
     *
     * <p>没有历史时连「最近命令」这个小标题一起隐藏 —— 空标题占着版面
     * 只会让人以为这块坏了。行是 inflate 出来的，容器里没有 Divider（Miuix 惯例）。
     */
    private void renderHistory() {
        if (listHistory == null) {
            return;
        }
        listHistory.removeAllViews();
        List<String> commands = prefs == null ? new ArrayList<>() : loadHistory();

        if (layoutHistory != null) {
            layoutHistory.setVisibility(commands.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (commands.isEmpty()) {
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (String command : commands) {
            TextView row = (TextView) inflater.inflate(R.layout.item_exec_history,
                    listHistory, false);
            row.setText(command);
            row.setOnClickListener(v -> fillCommand(command));
            row.setOnLongClickListener(v -> {
                forgetCommand(command);
                return true;
            });
            listHistory.addView(row);
        }
    }

    private void fillCommand(String command) {
        if (etCommand == null) {
            return;
        }
        etCommand.setText(command);
        etCommand.setSelection(etCommand.getText() == null ? 0 : etCommand.getText().length());
    }

    // ───────────────── 基类接口 ─────────────────

    @Override
    protected View[] busyViews() {
        return new View[]{rbAdb, rbFastboot, rbShell, etCommand, btnRun, btnClear};
    }

    @Override
    protected String logFileTag() {
        return "exec";
    }
}
