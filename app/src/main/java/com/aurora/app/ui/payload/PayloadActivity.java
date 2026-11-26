package com.aurora.app.ui.payload;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aurora.app.R;
import com.aurora.app.core.util.NotificationPermission;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Payload 解包页。
 *
 * <h3>三种输入、一个出口</h3>
 * OTA 全量包（zip 里的 payload.bin）、欧加散包（zip 里直接是若干 img）、
 * 单个镜像或镜像目录 —— 都由 {@link PayloadCore#inspect(String)} 识别成同一张分区表，
 * 提取走同一条路径。用户不需要先判断自己手上的是哪种包。
 *
 * <h3>为什么进度是「读 + 写」两条时间线</h3>
 * 读取信息要解析 zip 目录与 manifest（云端包还要几次 Range 请求），
 * 提取要解压并落盘 —— 两段都可能卡住几十秒。分别给状态文案，
 * 用户才不会把「正在读包」当成「卡死了」。
 *
 * <h3>为什么输出目录要单独校验</h3>
 * 默认输出 {@code /storage/emulated/0/Aurora}。Android 11 起这个位置用 File API 直接写
 * 需要「所有文件访问」授权，未授权时 {@code mkdirs()} 只返回 false、不抛异常 ——
 * 不主动探测就会一路跑到「无法创建输出目录」这种含糊提示上。
 */
public class PayloadActivity extends AppCompatActivity {

    /** 默认输出目录：与 QDL 的产物目录同根，用户按机器找文件时只有一处。 */
    private static final String DEFAULT_OUTPUT_DIR = "/storage/emulated/0/Aurora";

    /** 进度条最大刻度：用千分比而不是百分比，慢速长任务不会长时间停在同一个整数。 */
    private static final int PROGRESS_SCALE = 1000;

    /** 进度刷新节流：太快会把主线程刷满，太慢看起来像卡住。 */
    private static final long PROGRESS_INTERVAL_MS = 200L;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-payload");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText etPath;
    private EditText etOutput;
    private EditText etFilter;
    private TextView btnRead;
    private TextView btnExtract;
    private TextView btnStop;
    private CheckBox cbSelectAll;
    private RecyclerView rvPartitions;
    private ProgressBar pbProgress;
    private TextView tvStatus;
    private TextView tvPercent;
    private TextView tvSpeed;
    private TextView tvEta;

    private PayloadPartitionAdapter adapter;

    /** 当前解析结果；换输入或退出页面时关闭。 */
    private PayloadCore.Inspection inspection;
    private final List<PayloadCore.PartitionInfoRow> allRows = new ArrayList<>();
    private final Set<String> selectedNames = new LinkedHashSet<>();

    /** 当前提取任务的取消令牌；非提取期间为 null。 */
    private PayloadCore.CancelToken cancelToken;
    /** 是否有后台任务在跑（读取或提取），用来互斥按钮。 */
    private volatile boolean busy;

    // 进度统计：速度用「两次采样之间的增量」算，避免累计平均把速度摊平
    private long lastSampleDone;
    private long lastSampleAt;
    private long lastProgressAt;
    private long totalBytes;
    private long lastPercent = -1;

    private final ActivityResultLauncher<String[]> pickFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }
                String path = resolveReadablePath(uri);
                if (path == null) {
                    toast("该位置无法按路径读取，请把包放到 /storage/emulated/0 下再选，或直接输入路径");
                    return;
                }
                etPath.setText(path);
            });

    private final ActivityResultLauncher<Uri> pickOutputLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) {
                    return;
                }
                String path = resolveReadablePath(uri);
                if (path == null) {
                    toast("无法解析该目录的真实路径，请手动输入");
                    return;
                }
                etOutput.setText(path);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payload);

        bindViews();
        setupList();
        setupActions();

        etOutput.setText(DEFAULT_OUTPUT_DIR);
        updateProgressUi(0L, 0L, "");
    }

    @Override
    protected void onDestroy() {
        PayloadCore.CancelToken token = cancelToken;
        if (token != null) {
            token.cancel();
        }
        ioExecutor.shutdownNow();
        // 任务随页面取消，前台服务也必须一起撤掉，否则通知会永远挂着
        PayloadUnpackService.stop(getApplicationContext());
        closeInspection();
        super.onDestroy();
    }

    // ───────────────────────────── 初始化 ─────────────────────────────

    private void bindViews() {
        etPath = findViewById(R.id.etPayloadPath);
        etOutput = findViewById(R.id.etPayloadOutput);
        etFilter = findViewById(R.id.etPayloadFilter);
        btnRead = findViewById(R.id.btnPayloadRead);
        btnExtract = findViewById(R.id.btnPayloadExtract);
        btnStop = findViewById(R.id.btnPayloadStop);
        cbSelectAll = findViewById(R.id.cbPayloadSelectAll);
        rvPartitions = findViewById(R.id.rvPayloadPartitions);
        pbProgress = findViewById(R.id.pbPayload);
        tvStatus = findViewById(R.id.tvPayloadStatus);
        tvPercent = findViewById(R.id.tvPayloadPercent);
        tvSpeed = findViewById(R.id.tvPayloadSpeed);
        tvEta = findViewById(R.id.tvPayloadEta);

        findViewById(R.id.btnPayloadBack).setOnClickListener(v -> finish());
    }

    private void setupList() {
        adapter = new PayloadPartitionAdapter(selectedNames, this::syncSelectAllState);
        rvPartitions.setLayoutManager(new LinearLayoutManager(this));
        rvPartitions.setAdapter(adapter);
        rvPartitions.setHasFixedSize(true);
    }

    private void setupActions() {
        findViewById(R.id.btnPickPayloadFile).setOnClickListener(v ->
                pickFileLauncher.launch(new String[]{"*/*"}));
        findViewById(R.id.btnPickPayloadOutput).setOnClickListener(v ->
                pickOutputLauncher.launch(null));

        btnRead.setOnClickListener(v -> readInfo());
        btnExtract.setOnClickListener(v -> extractSelected());
        btnStop.setOnClickListener(v -> stopCurrentTask());

        cbSelectAll.setOnClickListener(v -> {
            boolean checked = cbSelectAll.isChecked();
            selectedNames.clear();
            if (checked) {
                for (PayloadCore.PartitionInfoRow row : adapter.getRows()) {
                    selectedNames.add(row.name);
                }
            }
            adapter.notifyDataSetChanged();
            syncSelectAllState();
        });

        etFilter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    // ───────────────────────────── 读取信息 ─────────────────────────────

    private void readInfo() {
        if (busy) {
            toast("有任务在进行中，请先等它结束或点停止");
            return;
        }
        final String input = text(etPath);
        if (input.isEmpty()) {
            toast("请先填写路径或 URL");
            return;
        }
        final PayloadCore.CancelToken token = new PayloadCore.CancelToken();
        cancelToken = token;
        beginBusy("正在读取包信息…");
        ioExecutor.execute(() -> {
            try {
                PayloadCore.Inspection result = PayloadCore.inspect(input, token);
                mainHandler.post(() -> {
                    closeInspection();
                    inspection = result;
                    allRows.clear();
                    allRows.addAll(result.rows);
                    selectedNames.clear();
                    applyFilter();
                    syncSelectAllState();
                    endBusy();
                    tvStatus.setText(describe(result));
                    updateProgressUi(0L, 0L, "");
                    if (result.rows.isEmpty()) {
                        toast("没有解析到任何可提取的分区");
                    }
                });
            } catch (PayloadCore.CancelledException e) {
                // 用户自己点的停止，不是失败，别弹「读取失败」吓人
                mainHandler.post(() -> {
                    endBusy();
                    tvStatus.setText("已停止");
                });
            } catch (Throwable t) {
                final String message = readableMessage(t);
                mainHandler.post(() -> {
                    endBusy();
                    showFailure("读取失败", message);
                });
            }
        });
    }

    private String describe(PayloadCore.Inspection result) {
        String kind;
        switch (result.kind) {
            case PayloadCore.KIND_PAYLOAD:
                kind = "OTA 全量包";
                break;
            case PayloadCore.KIND_ZIP_IMAGES:
                kind = "欧加散包";
                break;
            case PayloadCore.KIND_SINGLE_IMAGE:
                kind = "单个镜像";
                break;
            default:
                kind = "镜像目录";
                break;
        }
        return kind + " · " + result.rows.size() + " 个分区 · " + formatSize(result.totalBytes);
    }

    // ───────────────────────────── 提取 ─────────────────────────────

    private void extractSelected() {
        if (busy) {
            toast("有任务在进行中，请先等它结束或点停止");
            return;
        }
        if (inspection == null) {
            toast("请先点「读取信息」");
            return;
        }
        if (selectedNames.isEmpty()) {
            toast("请先勾选要提取的分区");
            return;
        }
        final String outputPath = text(etOutput);
        if (outputPath.isEmpty()) {
            toast("请先填写输出路径");
            return;
        }
        final File outDir = new File(outputPath);
        if (!ensureWritable(outDir)) {
            return;
        }

        List<PayloadCore.PartitionInfoRow> targets = new ArrayList<>();
        for (PayloadCore.PartitionInfoRow row : inspection.rows) {
            if (selectedNames.contains(row.name)) {
                targets.add(row);
            }
        }
        long total = 0L;
        for (PayloadCore.PartitionInfoRow row : targets) {
            total += Math.max(0L, row.size);
        }
        final long totalBytesFinal = total;

        // 全量包解压后普遍比包体更大（这份真机包 9.86 GB 解压出 12.6 GB）。
        // 不预检的话会写到最后几个分区才失败，前面几 GB 白写。
        long usable = outDir.getUsableSpace();
        if (usable > 0 && usable < total + total / 20) {
            showFailure("空间不足", "所选分区解压后约 " + formatSize(total)
                    + "，输出目录可用 " + formatSize(usable) + "。\n"
                    + "请少勾几个分区，或换一个有空间的目录。");
            return;
        }

        cancelToken = new PayloadCore.CancelToken();
        beginBusy("正在提取…");
        resetProgressStats(totalBytesFinal);
        // 解包可能持续十几分钟：挂前台服务，退到后台/锁屏时进程不会被回收。
        // 顺便申请一次通知权限，让用户能看到「后台正在解包」并随时停止。
        NotificationPermission.requestIfNeeded(this);
        PayloadUnpackService.start(this);

        ioExecutor.execute(() -> {
            try {
                PayloadCore.extract(inspection, targets, outDir, cancelToken,
                        new PayloadCore.ExtractListener() {
                            @Override
                            public void onEntryStarted(String name, int index, int count) {
                                mainHandler.post(() -> tvStatus.setText(
                                        "正在提取 " + name + "（" + index + "/" + count + "）"));
                            }

                            @Override
                            public void onProgress(long entryDone, long entryTotal,
                                                   long totalDone, long totalSize) {
                                postProgress(totalDone, totalSize);
                            }
                        });
                mainHandler.post(() -> {
                    endBusy();
                    tvStatus.setText("提取完成 · " + targets.size() + " 个分区 → " + outDir.getAbsolutePath());
                    updateProgressUi(totalBytesFinal, totalBytesFinal, null);
                });
            } catch (PayloadCore.CancelledException e) {
                mainHandler.post(() -> {
                    endBusy();
                    tvStatus.setText("已停止");
                    tvSpeed.setText("速度 —");
                    tvEta.setText("剩余 —");
                });
            } catch (Throwable t) {
                final String message = readableMessage(t);
                mainHandler.post(() -> {
                    endBusy();
                    showFailure("提取失败", message);
                });
            }
        });
    }

    private void stopCurrentTask() {
        PayloadCore.CancelToken token = cancelToken;
        if (token == null || !busy) {
            toast("当前没有进行中的任务");
            return;
        }
        token.cancel();
        tvStatus.setText("正在停止…");
    }

    /**
     * 输出目录可写性探测。
     *
     * <p>Android 11+ 在未授予「所有文件访问」时，对共享存储的写入会被静默拦下：
     * {@code mkdirs()} 返回 false、{@code createNewFile()} 抛 EACCES。这里真写一个探针文件，
     * 比只看 {@code canWrite()} 可靠（后者在被 scoped storage 拦截时仍可能返回 true）。
     */
    private boolean ensureWritable(File dir) {
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                return true;
            }
        } else {
            File probe = new File(dir, ".aurora_probe");
            try {
                if (probe.createNewFile() || probe.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    probe.delete();
                    return true;
                }
            } catch (IOException ignored) {
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                && Environment.isExternalStorageManager()) {
            toast("输出目录不可写：" + dir.getAbsolutePath());
            return false;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("需要「所有文件访问」权限")
                .setMessage("输出目录 " + dir.getAbsolutePath()
                        + " 在共享存储上，Android 11 起写入需要「所有文件访问」授权。\n\n"
                        + "授权后回到本页再点一次「提取镜像」。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去授权", (d, w) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Throwable t) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Throwable ignored) {
                            toast("无法打开授权页面，请到系统设置里手动授予");
                        }
                    }
                })
                .show();
        return false;
    }

    // ───────────────────────────── 进度 ─────────────────────────────

    private void resetProgressStats(long total) {
        totalBytes = total;
        lastSampleDone = 0L;
        lastSampleAt = System.currentTimeMillis();
        lastProgressAt = 0L;
        lastPercent = -1;
        mainHandler.post(() -> updateProgressUi(0L, total, ""));
    }

    /** 后台线程调用；按 200ms 节流后才切到主线程。 */
    private void postProgress(long done, long total) {
        long now = System.currentTimeMillis();
        if (now - lastProgressAt < PROGRESS_INTERVAL_MS) {
            return;
        }
        lastProgressAt = now;
        final long doneSnapshot = done;
        final long totalSnapshot = total > 0 ? total : totalBytes;
        mainHandler.post(() -> updateProgressUi(doneSnapshot, totalSnapshot, ""));
    }

    private void updateProgressUi(long done, long total, String statusOverride) {
        long safeDone = Math.max(0L, done);
        long safeTotal = Math.max(0L, total);
        int permille = safeTotal <= 0 ? 0
                : (int) Math.min(PROGRESS_SCALE, safeDone * PROGRESS_SCALE / safeTotal);
        pbProgress.setProgress(permille);

        int percent = safeTotal <= 0 ? 0 : (int) Math.min(100L, safeDone * 100L / safeTotal);
        if (lastPercent != percent) {
            tvPercent.setText(percent + "%");
            lastPercent = percent;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastSampleAt;
        if (elapsed >= 500L) {
            long delta = safeDone - lastSampleDone;
            if (delta >= 0 && elapsed > 0) {
                double speed = delta * 1000.0 / elapsed;
                tvSpeed.setText("速度 " + formatSpeed(speed));
                long remain = safeTotal - safeDone;
                if (speed > 1024 && remain > 0) {
                    tvEta.setText("剩余 " + formatDuration((long) (remain / speed)));
                } else {
                    tvEta.setText("剩余 计算中");
                }
            }
            lastSampleDone = safeDone;
            lastSampleAt = now;
        }
        if (safeTotal <= 0) {
            tvSpeed.setText("速度 —");
            tvEta.setText("剩余 —");
        }
        if (statusOverride != null && !statusOverride.isEmpty()) {
            tvStatus.setText(statusOverride);
        }
    }

    // ───────────────────────────── 状态与列表 ─────────────────────────────

    private void beginBusy(String status) {
        busy = true;
        btnRead.setEnabled(false);
        btnRead.setAlpha(0.5f);
        btnExtract.setEnabled(false);
        btnExtract.setAlpha(0.5f);
        tvStatus.setText(status);
    }

    private void endBusy() {
        busy = false;
        cancelToken = null;
        btnRead.setEnabled(true);
        btnRead.setAlpha(1f);
        btnExtract.setEnabled(true);
        btnExtract.setAlpha(1f);
        PayloadUnpackService.stop(this);
    }

    private void applyFilter() {
        String query = text(etFilter).toLowerCase(Locale.US);
        List<PayloadCore.PartitionInfoRow> filtered = new ArrayList<>();
        for (PayloadCore.PartitionInfoRow row : allRows) {
            if (query.isEmpty() || row.name.toLowerCase(Locale.US).contains(query)) {
                filtered.add(row);
            }
        }
        adapter.submit(filtered);
        syncSelectAllState();
    }

    /** 全选框跟随当前可见行：筛选后仍按「可见的都选了」判断，避免误以为全选。 */
    private void syncSelectAllState() {
        List<PayloadCore.PartitionInfoRow> visible = adapter.getRows();
        boolean allSelected = !visible.isEmpty();
        for (PayloadCore.PartitionInfoRow row : visible) {
            if (!selectedNames.contains(row.name)) {
                allSelected = false;
                break;
            }
        }
        cbSelectAll.setChecked(allSelected);
    }

    private void closeInspection() {
        if (inspection != null) {
            inspection.close();
            inspection = null;
        }
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    /**
     * 把 SAF 返回的 URI 还原成可用的真实路径。
     *
     * <p>{@link PayloadCore} 按字节偏移读包，必须拿到真实路径；
     * content:// 无法随机读，所以这里把 DocumentsProvider 的 documentId
     * （形如 {@code primary:Download/xxx.zip}）翻译回挂载点下的路径。
     */
    private String resolveReadablePath(Uri uri) {
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            return uri.getPath();
        }
        try {
            String documentId = DocumentsContract.getDocumentId(uri);
            String volumeId;
            String relative;
            int colon = documentId.indexOf(':');
            if (colon < 0) {
                return null;
            }
            volumeId = documentId.substring(0, colon);
            relative = documentId.substring(colon + 1);
            File base = "primary".equals(volumeId)
                    ? Environment.getExternalStorageDirectory()
                    : new File("/storage/" + volumeId);
            File target = new File(base, relative);
            return target.exists() && target.canRead() ? target.getAbsolutePath() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String text(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * 失败提示。
     *
     * <p>短消息走 Toast；长消息（链接过期、不支持 Range 这类多句解释）落一个可滚动的
     * 对话框 —— Toast 两行就截断，正文还没读完就没了，用户最后只记得「读取失败」四个字，
     * 连原因都说不出来。正文可选可复制，方便把原始报错原样贴出来。
     */
    private void showFailure(String title, String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String body = message == null || message.trim().isEmpty() ? "未知错误" : message.trim();
        // 留一条可抓的现场：toast 一闪而过，用户回头说不清，adb logcat 里能找回原文
        android.util.Log.w("AuroraPayload", title + " | "
                + PayloadCore.abbreviate(text(etPath), 200) + " | " + body);
        tvStatus.setText(title + "：" + firstLine(body));
        if (body.length() <= 40) {
            toast(body);
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int horizontal = Math.round(16 * density);
        TextView text = new TextView(this);
        text.setText(body);
        text.setTextSize(13f);
        text.setTextColor(0xFF16181C);
        text.setLineSpacing(4 * density, 1f);
        text.setTextIsSelectable(true);
        text.setPadding(horizontal, Math.round(8 * density), horizontal, Math.round(8 * density));

        com.aurora.app.ui.widget.MaxHeightScrollView scroll =
                new com.aurora.app.ui.widget.MaxHeightScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(text);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 状态栏只有一行，失败原因取首行再说。 */
    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline >= 0 ? text.substring(0, newline) : text;
        return line.length() <= 60 ? line : line.substring(0, 60) + "…";
    }

    private static String readableMessage(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return message;
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return String.format(Locale.US, "%.0f B/s", bytesPerSecond);
        }
        double kb = bytesPerSecond / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB/s", kb);
        }
        return String.format(Locale.US, "%.1f MB/s", kb / 1024.0);
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        }
        long minutes = seconds / 60;
        long remain = seconds % 60;
        if (minutes < 60) {
            return minutes + " 分 " + remain + " 秒";
        }
        return (minutes / 60) + " 时 " + (minutes % 60) + " 分";
    }

    /** 分区表适配器：复选框状态由页面的 {@code selected} 集合统一持有。 */
    private static final class PayloadPartitionAdapter
            extends RecyclerView.Adapter<PayloadPartitionAdapter.Holder> {

        private final List<PayloadCore.PartitionInfoRow> rows = new ArrayList<>();
        private final Set<String> selected;
        private final Runnable onSelectionChanged;

        PayloadPartitionAdapter(Set<String> selected, Runnable onSelectionChanged) {
            this.selected = selected;
            this.onSelectionChanged = onSelectionChanged;
        }

        void submit(List<PayloadCore.PartitionInfoRow> data) {
            rows.clear();
            rows.addAll(data);
            notifyDataSetChanged();
        }

        List<PayloadCore.PartitionInfoRow> getRows() {
            return new ArrayList<>(rows);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_payload_row, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            PayloadCore.PartitionInfoRow row = rows.get(position);
            holder.name.setText(row.name);
            holder.size.setText(PayloadActivity.formatSize(row.size));
            holder.checkBox.setChecked(selected.contains(row.name));
            holder.itemView.setOnClickListener(v -> {
                if (selected.contains(row.name)) {
                    selected.remove(row.name);
                } else {
                    selected.add(row.name);
                }
                int bindingPosition = holder.getBindingAdapterPosition();
                if (bindingPosition != RecyclerView.NO_POSITION) {
                    notifyItemChanged(bindingPosition);
                }
                onSelectionChanged.run();
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final CheckBox checkBox;
            final TextView name;
            final TextView size;

            Holder(View itemView) {
                super(itemView);
                checkBox = itemView.findViewById(R.id.cbPayloadRow);
                name = itemView.findViewById(R.id.tvPayloadRowName);
                size = itemView.findViewById(R.id.tvPayloadRowSize);
            }
        }
    }
}
