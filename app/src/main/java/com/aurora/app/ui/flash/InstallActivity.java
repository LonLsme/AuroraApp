package com.aurora.app.ui.flash;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.aurora.app.R;
import com.aurora.app.core.flash.AppCommands;
import com.aurora.app.core.flash.FlashCommands;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「应用安装」页（独立 Activity，竖屏，自带返回箭头与大标题）。
 *
 * <h3>它做什么</h3>
 * 在本机选一个或多个 {@code .apk}，逐个装到<b>连着的那台设备</b>上
 * （{@code adb install -r}，见 {@link AppCommands#installApks}）。
 *
 * <h3>为什么要先拷进应用专属目录</h3>
 * 与 {@link SideloadActivity} 同一个原因：SAF 给回来的是 {@code content://} URI，
 * 而 {@code adb} 是<b>按真实路径</b>读文件的普通程序，读不懂 content URI。
 *
 * <h3>为什么批量不用 adb install-multiple</h3>
 * 那条命令是给「同一个应用的分包（split APK）」用的，把几个互不相干的 APK 一起传进去
 * 只会整体失败。批量装多个应用就是老老实实循环 {@code adb install -r}，
 * 合成一条 shell（见 {@link AppCommands#installApks}）。
 *
 * <h3>为什么文件名要自己去重</h3>
 * 一次选多个包时，两条来自不同目录却同名的 {@code base.apk} 会在拷贝时互相覆盖，
 * 最后装上去的是哪一份完全说不清。所以拷贝前先按「原名 + 序号」定一个唯一文件名。
 */
public final class InstallActivity extends FlashConsoleActivity {

    /** 请求码：选择 .apk 安装包（可多选）。 */
    private static final int REQ_PICK_APK = 9402;

    /**
     * APK 的 MIME 类型。
     *
     * <p>个别第三方文件提供者对它识别不准，会让 apk 在选择器里变灰。真遇到时把它换成
     * {@code MIME_ANY} 也能用 —— {@code adb install} 只认文件内容，不看扩展名。
     */
    private static final String APK_MIME = "application/vnd.android.package-archive";

    // ── 页面控件 ──
    private View btnPickApk;
    private View btnClear;
    private View btnStart;
    private TextView tvSelection;
    private TextView tvCount;

    /** 外层滚动容器：输出变长时由它滚动，从而「上下都有弹性」。 */
    private NestedScrollView scrollRoot;

    private FlashDeviceDropdown deviceDropdown;

    /** 已选安装包（本地副本）。 */
    private final List<File> pickedApks = new ArrayList<>();

    /**
     * 已分配给待拷贝文件的文件名。
     *
     * <p>拷贝是在单线程队列里排队执行的，而文件名要在<b>入队时</b>就定下来
     * （{@link FlashFilePicker#copyAsyncAs} 的参数），这时的文件还没落到
     * {@link #pickedApks} 里 —— 只查 {@code pickedApks} 会把同一批里的重名放过去。
     */
    private final Set<String> reservedNames = new HashSet<>();

    /** 还在拷贝中的文件数；归零时才收尾。 */
    private int pendingCopies;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_install);

        btnPickApk = findViewById(R.id.btnInstallPickApk);
        btnClear = findViewById(R.id.btnInstallClear);
        btnStart = findViewById(R.id.btnInstallStart);
        tvSelection = findViewById(R.id.tvApkSelection);
        tvCount = findViewById(R.id.tvApkCount);
        scrollRoot = findViewById(R.id.scrollInstall);
        View pill = findViewById(R.id.btnInstallDevicePill);

        // 抓公共控件 + 接好返回 / 保存日志 / 终止，并触发一次设备探测。
        bindConsole();

        // 输出区随内容增高、由外层承担滚动 —— 必须取消输出框自身的滚动。
        if (tvConsole != null) {
            tvConsole.setMovementMethod(null);
        }

        deviceDropdown = new FlashDeviceDropdown(this, pill);
        if (pill != null) {
            pill.setOnClickListener(v -> deviceDropdown.toggle(lastState));
        }

        setOnClick(btnPickApk, this::pickApks);
        setOnClick(btnClear, this::clearPicked);
        setOnClick(btnStart, this::startInstall);

        renderPicked();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 弹窗挂在 Activity 的 window 上，页面不可见时留着就是 WindowLeaked。
        if (deviceDropdown != null) {
            deviceDropdown.dismiss();
        }
    }

    /** 输出追加后滚到底部：本页输出框随内容增高，滚的是外层容器。 */
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
        return new View[]{btnPickApk, btnClear, btnStart};
    }

    @Override
    protected String logFileTag() {
        return "install";
    }

    // ───────────────── 选包 ─────────────────

    private void pickApks() {
        if (!FlashFilePicker.pickMultiple(this, REQ_PICK_APK, APK_MIME)) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK) {
            return;
        }
        List<Uri> uris = FlashFilePicker.resultUris(data);
        if (uris.isEmpty()) {
            return;
        }

        setBusy(true);
        appendLine("$ 正在准备 " + uris.size() + " 个安装包…");
        pendingCopies = uris.size();
        for (Uri uri : uris) {
            String display = FlashFilePicker.displayName(this, uri);
            if (display == null || display.isEmpty()) {
                display = "app_" + System.currentTimeMillis() + ".apk";
            }
            final String fileName = uniqueApkName(display);
            FlashFilePicker.copyAsyncAs(this, uri, fileName, new FlashFilePicker.Callback() {
                @Override
                public void onProgress(long copied, long total) {
                    // 单次拷贝的进度不逐个报：多选时几路进度交错反而看不清，
                    // 「已选 N 个」的最终结果才是用户要的。
                }

                @Override
                public void onDone(File file) {
                    runOnUiThreadSafe(() -> {
                        pickedApks.add(file);
                        onOneCopyFinished();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThreadSafe(() -> {
                        appendLine("[错误] " + message);
                        onOneCopyFinished();
                    });
                }
            });
        }
    }

    private void onOneCopyFinished() {
        pendingCopies--;
        if (pendingCopies > 0) {
            return;
        }
        setBusy(false);
        renderPicked();
        appendLine("已选择 " + pickedApks.size() + " 个安装包");
    }

    /** 原名基础上取一个不与已选（含待拷贝）重名的文件名。 */
    private String uniqueApkName(String display) {
        String candidate = display;
        int index = 2;
        int dot = display.lastIndexOf('.');
        String base = dot > 0 ? display.substring(0, dot) : display;
        String ext = dot > 0 ? display.substring(dot) : "";
        while (reservedNames.contains(candidate)) {
            candidate = base + "-" + index + ext;
            index++;
        }
        reservedNames.add(candidate);
        return candidate;
    }

    /** 清空已选清单，并删掉为此拷进来的本地副本（它们只是安装用的中转文件）。 */
    private void clearPicked() {
        if (pickedApks.isEmpty()) {
            toast(AppCommands.NO_APK_SELECTED);
            return;
        }
        for (File file : pickedApks) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        pickedApks.clear();
        reservedNames.clear();
        renderPicked();
    }

    private void renderPicked() {
        if (tvCount != null) {
            tvCount.setText("已选 " + pickedApks.size() + " 个");
        }
        if (tvSelection == null) {
            return;
        }
        if (pickedApks.isEmpty()) {
            tvSelection.setText("尚未选择安装包");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (File file : pickedApks) {
            sb.append(file.getName()).append("  （").append(humanSize(file.length())).append("）\n");
        }
        tvSelection.setText(sb.toString().trim());
    }

    // ───────────────── 安装 ─────────────────

    private void startInstall() {
        if (pickedApks.isEmpty()) {
            toast(AppCommands.NO_APK_SELECTED);
            return;
        }
        if (!requireAdbDevice()) {
            return;
        }
        final List<String> paths = new ArrayList<>();
        long totalBytes = 0;
        for (File file : pickedApks) {
            if (!file.isFile()) {
                toast("安装包已不存在，请重新选择：" + file.getName());
                return;
            }
            paths.add(file.getAbsolutePath());
            totalBytes += file.length();
        }

        final String command = AppCommands.installApks(paths);
        new AlertDialog.Builder(this)
                .setTitle("确认安装")
                .setMessage("将向目标机安装 " + paths.size() + " 个应用，共 "
                        + humanSize(totalBytes) + "。\n\n"
                        + "使用覆盖安装（-r）：同名应用会被替换，其数据保留。\n"
                        + "安装期间请勿断开连接。")
                .setNegativeButton("取消", null)
                .setPositiveButton("安装", (d, w) -> runConsoleCommand(command))
                .show();
    }

    /** 设备必须已经在 ADB 模式并且已授权。 */
    private boolean requireAdbDevice() {
        if (lastState != null && lastState.adbReady) {
            return true;
        }
        toast(FlashCommands.deviceMissing("ADB"));
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
