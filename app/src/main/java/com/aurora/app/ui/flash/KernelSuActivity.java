package com.aurora.app.ui.flash;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashDevice;
import com.aurora.app.core.flash.FlashRuntime;
import com.aurora.app.core.flash.FlashToolchain;
import com.aurora.app.core.flash.KernelSuPatcher;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「刷入 KernelSU」页。
 *
 * <h3>这一页在做什么</h3>
 * 把目标机的 {@code boot.img} / {@code init_boot.img} 补进 KernelSU，产出一个可以直接刷的镜像。
 * 三步：选镜像 → 选 KMI → 修补（{@code libksud boot-patch}）。
 *
 * <h3>KMI 为什么必须让用户自己选</h3>
 * 它不能从本机推断 —— 补的是<b>目标机</b>的镜像，目标是另一台设备。
 * 选错会得到一个刷上去不生效的镜像，所以这一步是人工确认而不是自动填充。
 *
 * <h3>版本那行小字</h3>
 * 「管理器版本 3.3.0 (uapi: 2)」里的版本号来自 {@code libksud --version}，
 * 也就是<b>本机携带的修补工具</b>的版本（不是目标机已装的 KernelSU 版本）。
 * 工具链还没解压时先显示「libksud 检测中…」，解压完再读。
 */
public final class KernelSuActivity extends FlashConsoleActivity {

    /** 请求码：选择 boot / init_boot 镜像。 */
    private static final int REQ_PICK_IMAGE = 9101;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-ksu-prepare");
        t.setDaemon(true);
        return t;
    });

    /** 三个整行按钮。声明成 TextView 是因为按钮文字本身就是这页的状态回显（「已选: xxx」）。 */
    private TextView btnPickImage;
    private TextView btnPickKmi;
    private View btnPatch;
    private TextView tvVersion;

    /** 已拷进应用目录的镜像。 */
    private File pickedImage;
    /** 已选的 KMI。 */
    private String selectedKmi;

    /** 版本只读一次（跑完命令刷新设备状态时会再次触发钩子）。 */
    private boolean versionLoaded;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kernel_su);

        btnPickImage = findViewById(R.id.btnKsuPickImage);
        btnPickKmi = findViewById(R.id.btnKsuPickKmi);
        btnPatch = findViewById(R.id.btnKsuPatch);
        tvVersion = findViewById(R.id.tvKsuVersion);

        if (btnPickImage != null) {
            btnPickImage.setOnClickListener(v -> pickImage());
        }
        if (btnPickKmi != null) {
            btnPickKmi.setOnClickListener(v -> chooseKmi());
        }
        if (btnPatch != null) {
            btnPatch.setOnClickListener(v -> startPatch());
        }

        bindConsole();
    }

    // ───────────────── libksud 版本 ─────────────────

    /**
     * 设备探测结束后读一次 libksud 版本。
     *
     * <p>必须排在这里而不是页面上来就跑：版本检测与设备探测共用 {@link FlashRuntime}
     * 的单任务闸门，同时发起必然有一个被拒。
     */
    @Override
    protected void onDeviceStateRefreshed(FlashDevice.State state) {
        if (versionLoaded) {
            return;
        }
        versionLoaded = true;
        loadLibksudVersion();
    }

    private void loadLibksudVersion() {
        setVersionLabel("libksud 检测中…");
        WORKER.execute(() -> {
            // 首次运行要先解压工具链（几百毫秒），否则 libksud 根本不存在。
            final boolean ready = FlashToolchain.ensureReady(getApplicationContext());
            if (ready) {
                runOnUiThreadSafe(this::readLibksudVersion);
            } else {
                runOnUiThreadSafe(() -> setVersionLabel(KernelSuPatcher.managerVersionLabel(null)));
            }
        });
    }

    private void readLibksudVersion() {
        final StringBuilder sink = new StringBuilder();
        boolean accepted = FlashRuntime.get().run(getApplicationContext(),
                KernelSuPatcher.versionCommand(), new FlashRuntime.Listener() {
                    @Override
                    public void onLine(String line) {
                        sink.append(line).append('\n');
                    }

                    @Override
                    public void onExit(int exitCode, boolean cancelled) {
                        final String version = cancelled ? null : KernelSuPatcher.parseVersion(sink.toString());
                        runOnUiThreadSafe(() -> setVersionLabel(KernelSuPatcher.managerVersionLabel(version)));
                    }
                });
        if (!accepted) {
            setVersionLabel(KernelSuPatcher.managerVersionLabel(null));
        }
    }

    private void setVersionLabel(String text) {
        if (tvVersion != null) {
            tvVersion.setText(text);
        }
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
        final Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        setBusy(true);
        appendLine("$ 正在准备镜像…");
        final long[] lastPercent = {-1};
        FlashFilePicker.copyAsync(this, uri, "boot_", ".img", new FlashFilePicker.Callback() {
            @Override
            public void onProgress(long copied, long total) {
                if (total <= 0) {
                    return;
                }
                long percent = copied * 100 / total;
                // 每 10% 打一行，别把输出区刷爆
                if (percent / 10 != lastPercent[0] / 10) {
                    lastPercent[0] = percent;
                    appendLine("  " + percent + "%");
                }
            }

            @Override
            public void onDone(File file) {
                runOnUiThreadSafe(() -> {
                    setBusy(false);
                    pickedImage = file;
                    setPickImageLabel("已选: " + file.getName());
                    appendLine("$ 已就绪: " + file.getAbsolutePath()
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

    private void setPickImageLabel(String text) {
        if (btnPickImage != null) {
            btnPickImage.setText(text);
        }
    }

    // ───────────────── 选 KMI ─────────────────

    private void chooseKmi() {
        final String[] items = KernelSuPatcher.KMI_VERSIONS;
        new AlertDialog.Builder(this)
                .setTitle("请选择内核版本")
                .setItems(items, (d, which) -> {
                    selectedKmi = items[which];
                    setPickKmiLabel(selectedKmi);
                    appendLine("$ 目标内核: " + selectedKmi);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setPickKmiLabel(String text) {
        if (btnPickKmi != null) {
            btnPickKmi.setText(text);
        }
    }

    // ───────────────── 修补 ─────────────────

    private void startPatch() {
        if (pickedImage == null || !pickedImage.isFile()) {
            toast("请先选择 boot / init_boot 镜像");
            return;
        }
        if (selectedKmi == null) {
            toast("请先选择内核版本");
            return;
        }
        if (!KernelSuPatcher.isValidKmi(selectedKmi)) {
            toast("内核版本不在支持列表内");
            return;
        }

        final File outDir = FlashToolchain.imageDir(this);
        final String outName = KernelSuPatcher.outputName(System.currentTimeMillis());
        final String command = KernelSuPatcher.patchCommand(
                selectedKmi, pickedImage.getAbsolutePath(), outDir.getAbsolutePath(), outName);

        confirmThenRun("确认修补",
                "镜像：" + pickedImage.getName() + "（" + humanSize(pickedImage.length()) + "）\n"
                        + "内核：" + selectedKmi + "\n"
                        + "输出：" + outName + "\n\n"
                        + "KMI 必须与目标机内核一致，选错会得到刷入后不生效的镜像。\n\n"
                        + "命令：\n" + command,
                "开始",
                command);
    }

    // ───────────────── 基类接口 ─────────────────

    @Override
    protected View[] busyViews() {
        return new View[]{btnPickImage, btnPickKmi, btnPatch};
    }

    @Override
    protected String logFileTag() {
        return "kernelsu";
    }
}
