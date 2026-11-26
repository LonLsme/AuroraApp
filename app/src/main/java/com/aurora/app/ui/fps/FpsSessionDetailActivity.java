package com.aurora.app.ui.fps;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.aurora.app.R;
import com.aurora.app.core.monitor.FpsSessionData;
import com.aurora.app.core.monitor.FpsSessionStore;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 帧率会话详情页（对标星野 Scene 的 {@code ActivityFpsSession}）。
 *
 * <p>展示：会话归属应用 / 分辨率 / 时长 / 开始时间、五项统计，以及 FPS、整机功耗、
 * CPU 占用、CPU 温度四条每秒曲线。右上「导出」可生成 CSV（Excel 可直接打开）
 * 或把整页内容渲染成 PNG，两者都走系统分享面板。
 *
 * <p>数据来自 {@link FpsSessionStore}：每个会话一个 JSON 文件，曲线在记录时逐秒采样，
 * 不是事后估算。
 */
public final class FpsSessionDetailActivity extends AppCompatActivity {

    /** 会话开始时间（毫秒），同时是存储文件名。 */
    public static final String EXTRA_SESSION_TIME = "session_time";

    private static final int COLOR_FPS = 0xFF2563EB;
    private static final int COLOR_POWER = 0xFFF59E0B;
    private static final int COLOR_CPU = 0xFF10B981;
    private static final int COLOR_TEMP = 0xFFEF4444;

    private FpsSessionData session;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fps_session_detail);

        Toolbar toolbar = findViewById(R.id.toolbarFpsSession);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle("帧率会话");

        long time = getIntent().getLongExtra(EXTRA_SESSION_TIME, 0L);
        session = FpsSessionStore.load(this, time);
        if (session == null) {
            Toast.makeText(this, "会话数据已不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        bindSession();
        findViewById(R.id.btnSessionExport).setOnClickListener(this::showExportMenu);
    }

    // ─── 展示 ───

    private void bindSession() {
        ImageView icon = findViewById(R.id.ivSessionAppIcon);
        TextView name = findViewById(R.id.tvSessionAppName);
        TextView meta = findViewById(R.id.tvSessionMeta);

        String label = session.appName;
        if (label == null || label.isEmpty()) {
            label = session.packageName;
        }
        if (label == null || label.isEmpty()) {
            label = "未知应用";
        }
        name.setText(label);

        if (session.packageName != null && !session.packageName.isEmpty()) {
            try {
                icon.setImageDrawable(getPackageManager().getApplicationIcon(session.packageName));
            } catch (Exception ignored) {
                icon.setImageDrawable(null);
            }
        }

        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(session.startTimeMs));
        StringBuilder metaText = new StringBuilder();
        if (session.viewSize != null && !session.viewSize.isEmpty()) {
            metaText.append(session.viewSize).append(" · ");
        }
        metaText.append(formatDuration(session.durationSec)).append(" · ").append(time);
        meta.setText(metaText.toString());

        ((TextView) findViewById(R.id.tvStatAvgFps)).setText(fmt1(session.avgFps()));
        ((TextView) findViewById(R.id.tvStatMaxFps)).setText(fmt1(session.maxFps()));
        ((TextView) findViewById(R.id.tvStatMinFps)).setText(fmt1(session.minFps()));
        ((TextView) findViewById(R.id.tvStatAvgPower)).setText(fmtPower(session.avgPower()));
        ((TextView) findViewById(R.id.tvStatMaxTemp)).setText(fmtTemp(session.maxTemp()));
        ((TextView) findViewById(R.id.tvStatAvgCpu)).setText(fmtCpu(session.avgCpu()));

        FpsSeriesChartView fpsChart = findViewById(R.id.chartSessionFps);
        FpsSeriesChartView powerChart = findViewById(R.id.chartSessionPower);
        FpsSeriesChartView cpuChart = findViewById(R.id.chartSessionCpu);
        FpsSeriesChartView tempChart = findViewById(R.id.chartSessionTemp);
        fpsChart.setSeries(session.fps, COLOR_FPS, "", false);
        powerChart.setSeries(session.powerW, COLOR_POWER, "W", true);
        cpuChart.setSeries(session.cpuPercent, COLOR_CPU, "%", false);
        tempChart.setSeries(session.cpuTempC, COLOR_TEMP, "℃", false);
    }

    // ─── 导出 ───

    private void showExportMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "导出 CSV");
        menu.getMenu().add(0, 2, 1, "导出图片");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                exportCsv();
            } else {
                exportImage();
            }
            return true;
        });
        menu.show();
    }

    /** 导出逐秒数据为 CSV（UTF-8 BOM，Excel 打开中文表头不乱码）。 */
    private void exportCsv() {
        File dir = exportDir();
        if (dir == null) {
            toast("无法创建导出目录");
            return;
        }
        File target = new File(dir, "fps-" + session.startTimeMs + ".csv");
        try (FileOutputStream out = new FileOutputStream(target)) {
            StringBuilder sb = new StringBuilder();
            sb.append('\uFEFF');
            sb.append("秒,FPS,功耗(W),CPU(%),温度(℃)\n");
            int n = session.fps.length;
            for (int i = 0; i < n; i++) {
                sb.append(i + 1).append(',')
                        .append(csvValue(session.fps, i, 1)).append(',')
                        .append(csvValue(session.powerW, i, 2)).append(',')
                        .append(csvValue(session.cpuPercent, i, 0)).append(',')
                        .append(csvValue(session.cpuTempC, i, 1)).append('\n');
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            share(target, "text/csv");
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    /** 把整页内容渲染成 PNG（含滚动出屏幕的部分）。 */
    private void exportImage() {
        View content = findViewById(R.id.fpsSessionContent);
        int w = content.getWidth();
        int h = content.getHeight();
        if (w <= 0 || h <= 0) {
            toast("内容还没准备好，稍后再试");
            return;
        }
        File dir = exportDir();
        if (dir == null) {
            toast("无法创建导出目录");
            return;
        }
        File target = new File(dir, "fps-" + session.startTimeMs + ".png");
        try {
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(ContextCompat.getColor(this, R.color.fluent_background));
            content.draw(canvas);
            try (FileOutputStream out = new FileOutputStream(target)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
            share(target, "image/png");
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private File exportDir() {
        File dir = getExternalFilesDir("exports");
        if (dir == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    private void share(File file, String mime) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享会话数据"));
        } catch (Exception e) {
            toast("分享失败：" + e.getMessage());
        }
    }

    // ─── 工具 ───

    /** 取序列第 i 个值；负值（无采样）输出空单元格。 */
    private static String csvValue(float[] series, int index, int decimals) {
        if (series == null || index >= series.length) {
            return "";
        }
        float v = series[index];
        if (v < 0f) {
            return "";
        }
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    private static String fmt1(float v) {
        return v > 0f ? String.format(Locale.US, "%.1f", v) : "--";
    }

    private static String fmtPower(float v) {
        return v >= 0f ? String.format(Locale.US, "%.2fW", v) : "--";
    }

    private static String fmtTemp(float v) {
        return v > 0f ? String.format(Locale.US, "%.1f℃", v) : "--";
    }

    private static String fmtCpu(float v) {
        return v >= 0f ? String.format(Locale.US, "%.0f%%", v) : "--";
    }

    private static String formatDuration(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return m > 0 ? String.format(Locale.US, "%d:%02d", m, s) : s + "s";
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
