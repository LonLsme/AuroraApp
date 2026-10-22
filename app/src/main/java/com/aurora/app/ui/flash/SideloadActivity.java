package com.aurora.app.ui.flash;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashCommands;

import java.io.File;

/**
 * 「ADB Sideload」页（独立 Activity，竖屏，自带返回箭头与大标题）。
 *
 * <h3>它做什么</h3>
 * 在 Recovery 的 {@code apply update from ADB} 模式下，把电脑侧的刷机包推给设备去做卡刷 ——
 * 也就是一条 {@code adb sideload <zip>}。页面只做四件事：选包 → 确认 → 执行 → 看输出。
 *
 * <h3>为什么选包之后要拷进应用专属目录</h3>
 * 与 {@link FlashFilePicker} 里说的一样：SAF 给回来的是 {@code content://} URI，
 * 而 {@code adb} 是<b>按真实路径</b>读文件的普通程序，读不懂 content URI。
 *
 * <h3>为什么执行前要确认设备真的在 sideload 模式</h3>
 * 既有工具（{@code w2.java} case 4 → {@code k0.smali} case 10）在这里会先做一次设备检查，
 * 不在 sideload 模式就只弹一句「未检测到 %1$s 设备」并且<b>不执行</b>。
 * 照做是有实际意义的：{@code adb sideload} 对着普通 ADB 设备跑会直接报错，
 * 用户看到的是 adb 的英文错误，不如提前一句话说清「你现在这台机器没在 sideload 模式」。
 *
 * <h3>为什么不做「自动进 sideload 模式」</h3>
 * 与 {@link RebootActivity} / {@link PartitionFlashActivity} 同样的理由：
 * 设备刚重启、通道未就绪时自动判断只会给出误导性的命令。
 * 右上角那行实时状态就是给人看「现在到底哪条通道通着」的。
 */
public final class SideloadActivity extends FlashConsoleActivity {

    /** 请求码：选择 .zip 刷机包。 */
    private static final int REQ_PICK_ZIP = 9401;

    private static final String PREFS = "aurora_sideload";
    private static final String KEY_ZIP = "picked_zip";

    /**
     * {@code adb devices} 里 sideload 模式的状态名。
     *
     * <p>普通 ADB 是 {@code device}、未授权是 {@code unauthorized}，设备停在
     * Recovery 的 apply update 模式时才是 {@code sideload}。
     */
    private static final String ADB_STATE_SIDELOAD = "sideload";

    // ── 页面控件 ──
    private View btnPickZip;
    private View btnStart;

    /** 外层滚动容器：输出变长时由它滚动，从而「上下都有弹性」。 */
    private NestedScrollView scrollRoot;

    private FlashDeviceDropdown deviceDropdown;

    /** 已选刷机包；null 表示还没选。 */
    private File pickedZip;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sideload);

        btnPickZip = findViewById(R.id.btnSideloadPickZip);
        btnStart = findViewById(R.id.btnSideloadStart);
        scrollRoot = findViewById(R.id.scrollSideload);
        View pill = findViewById(R.id.btnSideloadDevicePill);

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
        setOnClick(btnPickZip, this::pickZip);
        setOnClick(btnStart, this::startSideload);

        restorePickedZip();
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
        // 只禁两个动作按钮：设备探测要几秒，那几秒里用户也不该再选一次包。
        return new View[]{btnPickZip, btnStart};
    }

    @Override
    protected String logFileTag() {
        return "sideload";
    }

    // ───────────────── 选包 ─────────────────

    private void pickZip() {
        if (!FlashFilePicker.pick(this, REQ_PICK_ZIP, FlashFilePicker.MIME_ANY)) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ZIP || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        setBusy(true);
        appendLine("$ 正在准备刷机包…");

        // 拷到应用专属目录（adb 只认真实路径），进度按 10% 一档打进输出区 ——
        // 卡刷包动辄上百 MB，没有进度用户会以为卡死。
        FlashFilePicker.copyAsync(this, uri, "ota_", ".zip", new FlashFilePicker.Callback() {
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
                    pickedZip = file;
                    prefs().edit().putString(KEY_ZIP, file.getAbsolutePath()).apply();
                    // 按钮文字保持「选择 .zip 文件」不动（页面上一行就是这块的标题），
                    // 选了哪个包由输出区这行交代 —— 取自既有工具的 sideload_selected。
                    appendLine(FlashCommands.sideloadSelected(file.getName()));
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

    private void restorePickedZip() {
        String path = prefs().getString(KEY_ZIP, null);
        if (path == null) {
            return;
        }
        File file = new File(path);
        if (file.isFile()) {
            pickedZip = file;
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ───────────────── 开始刷入 ─────────────────

    /**
     * 三段校验 → 确认 → 执行。
     *
     * <p>校验顺序与既有工具一致（{@code w2.java} case 4）：先看包选没选（含文件还在不在），
     * 再看设备在不在 sideload 模式，最后才动手。
     */
    private void startSideload() {
        if (!requireZip()) {
            return;
        }
        if (!requireSideloadDevice()) {
            return;
        }
        final String command = FlashCommands.adbSideload(pickedZip.getAbsolutePath());
        final String fileName = pickedZip.getName();
        final long size = pickedZip.length();

        // 不走基类的 confirmThenRun：在真正执行前要多输出一行「正在推送」，
        // 那是既有工具里用户能看到的第一个「开始干活了」的反馈。
        new AlertDialog.Builder(this)
                .setTitle("确认 Sideload 卡刷")
                .setMessage("刷机包：" + fileName + "（" + humanSize(size) + "）\n\n"
                        + "设备需停在 Recovery 的「apply update from ADB」模式。\n"
                        + "推送期间请勿拔线，中断会导致刷写失败。\n\n"
                        + "命令：\n" + command)
                .setNegativeButton("取消", null)
                .setPositiveButton("刷入", (d, w) -> {
                    appendLine(FlashCommands.sideloadPushing(fileName));
                    runConsoleCommand(command);
                })
                .show();
    }

    /** 包选没选 / 还在不在。缺失时按既有工具的文案写进输出区并返回 {@code false}。 */
    private boolean requireZip() {
        if (pickedZip == null) {
            appendLine(FlashCommands.SIDELOAD_SELECT_FIRST);
            toast(FlashCommands.SIDELOAD_SELECT_FIRST);
            return false;
        }
        if (!pickedZip.isFile()) {
            // 上次选过的包被删掉 / 移走了：清掉引用，别让它一直卡在这里。
            appendLine(FlashCommands.sideloadNotFound(pickedZip.getAbsolutePath()));
            pickedZip = null;
            prefs().edit().remove(KEY_ZIP).apply();
            return false;
        }
        return true;
    }

    /**
     * 设备必须已经在 sideload 模式。
     *
     * <p>不在时顺手把右上角状态刷一遍 —— 用户看到「未连接设备」之后，
     * 下一步动作通常是去 Recovery 里把设备切到 apply update 模式。
     */
    private boolean requireSideloadDevice() {
        if (lastState != null && ADB_STATE_SIDELOAD.equals(lastState.adbState)) {
            return true;
        }
        toast(FlashCommands.deviceMissing("Sideload"));
        refreshDeviceState();
        return false;
    }

    // ───────────────── 工具 ─────────────────

    private static void setOnClick(View view, Runnable action) {
        if (view != null) {
            view.setOnClickListener(v -> action.run());
        }
    }
}
