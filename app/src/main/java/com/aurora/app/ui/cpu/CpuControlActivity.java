package com.aurora.app.ui.cpu;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.aurora.app.R;
import com.aurora.app.core.cpu.CpuController;
import com.aurora.app.core.root.RootShell;
import com.aurora.app.core.util.CpuSysFiles;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CPU 频率与调度器控制页。
 *
 * <p>集群数量因机型而异（常见 2～3 个），所以集群卡片用 {@code item_cpu_cluster} 动态填充。
 *
 * <p>线程模型：
 * <ul>
 *   <li>所有 sysfs 读取和 root 写入走单线程 {@link #executor}——cpufreq 节点读起来很快，
 *       但 root 写入必须串行，避免 RootShell 会话被并发读写；</li>
 *   <li>UI 更新统一 post 回主线程；</li>
 *   <li>实时频率用一个 1 秒周期的 ticker 只刷当前频率文本（纯文件 IO，开销极小），
 *       松开滑杆时暂停 ticker 一次刷新，避免文本被覆盖。</li>
 * </ul>
 */
public class CpuControlActivity extends AppCompatActivity {

    private TextView tvRootStatus;
    private LinearLayout layoutClusters;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-cpu-control");
        t.setDaemon(true);
        return t;
    });

    private final List<ClusterViewHolder> holders = new ArrayList<>();
    private boolean viewCreated;

    private static final class ClusterViewHolder {
        CpuController.Cluster cluster;
        TextView tvCurFreq;
        TextView tvSummary;
        TextView tvMinFreq;
        TextView tvMaxFreq;
        TextView tvNoFreq;
        SeekBar seekMin;
        SeekBar seekMax;
        LinearLayout layoutGovernors;
        boolean seeking;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cpu_control);

        Toolbar toolbar = findViewById(R.id.toolbarCpuControl);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("CPU控制");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvRootStatus = findViewById(R.id.tvCpuRootStatus);
        layoutClusters = findViewById(R.id.layoutCpuClusters);
        MaterialButton btnRefresh = findViewById(R.id.btnCpuRefresh);
        btnRefresh.setOnClickListener(v -> reload());
        viewCreated = true;

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.post(ticker);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        viewCreated = false;
        super.onDestroy();
    }

    /** 后台读取集群状态并重建 UI。 */
    private void reload() {
        if (!viewCreated) {
            return;
        }
        tvRootStatus.setText("检测中...");
        layoutClusters.removeAllViews();
        holders.clear();
        executor.execute(() -> {
            boolean rootOk = RootShell.get().isRootAvailable();
            List<CpuController.Cluster> clusters = CpuController.readClusters();
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                tvRootStatus.setText(rootOk
                        ? "已获取 Root 权限，可修改频率与调度器"
                        : "未获取 Root 权限，当前为只读模式");
                tvRootStatus.setTextColor(getColor(rootOk
                        ? R.color.ios_semantic_positive
                        : R.color.ios_semantic_negative));
                if (clusters.isEmpty()) {
                    Toast.makeText(this, "未读取到任何 CPU 集群信息", Toast.LENGTH_SHORT).show();
                    return;
                }
                for (CpuController.Cluster c : clusters) {
                    addClusterCard(c);
                }
            });
        });
    }

    private void addClusterCard(CpuController.Cluster cluster) {
        ClusterViewHolder h = new ClusterViewHolder();
        h.cluster = cluster;
        View card = LayoutInflater.from(this)
                .inflate(R.layout.item_cpu_cluster, layoutClusters, false);

        TextView tvName = card.findViewById(R.id.tvClusterName);
        h.tvCurFreq = card.findViewById(R.id.tvClusterCurFreq);
        h.tvSummary = card.findViewById(R.id.tvClusterSummary);
        h.tvMinFreq = card.findViewById(R.id.tvClusterMinFreq);
        h.tvMaxFreq = card.findViewById(R.id.tvClusterMaxFreq);
        h.tvNoFreq = card.findViewById(R.id.tvClusterNoFreq);
        h.seekMin = card.findViewById(R.id.seekClusterMin);
        h.seekMax = card.findViewById(R.id.seekClusterMax);
        h.layoutGovernors = card.findViewById(R.id.layoutClusterGovernors);

        tvName.setText(cluster.displayName());
        bindFreqSection(h, cluster);
        buildGovernorChips(h, cluster);

        layoutClusters.addView(card);
        holders.add(h);
    }

    /** 频率滑杆部分：不可调频的集群整块隐藏，改显示提示。 */
    private void bindFreqSection(ClusterViewHolder h, CpuController.Cluster cluster) {
        View freqSection = h.seekMin.getParent() instanceof View
                ? (View) h.seekMin.getParent() : null;
        if (!cluster.isAdjustable()) {
            if (freqSection != null) {
                freqSection.setVisibility(View.GONE);
            }
            h.tvNoFreq.setVisibility(View.VISIBLE);
            updateCurFreqText(h);
            updateSummary(h);
            return;
        }

        long[] freqs = cluster.availableFreqsKhz;
        h.seekMin.setMax(freqs.length - 1);
        h.seekMax.setMax(freqs.length - 1);
        h.seekMin.setProgress(nearestIndex(freqs, cluster.minFreqKhz));
        h.seekMax.setProgress(nearestIndex(freqs, cluster.maxFreqKhz));
        h.tvMinFreq.setText(formatMhz(freqs[h.seekMin.getProgress()]));
        h.tvMaxFreq.setText(formatMhz(freqs[h.seekMax.getProgress()]));
        updateCurFreqText(h);
        updateSummary(h);

        h.seekMin.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // 不允许最低超过当前的最高档
                    if (progress > h.seekMax.getProgress()) {
                        h.seekMax.setProgress(progress);
                        h.tvMaxFreq.setText(formatMhz(freqs[progress]));
                    }
                    h.tvMinFreq.setText(formatMhz(freqs[progress]));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                h.seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                h.seeking = false;
                // 松手才下发写入，拖动过程中不产生任何 IO
                applyFreq(h, true, freqs[seekBar.getProgress()]);
            }
        });
        h.seekMax.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    if (progress < h.seekMin.getProgress()) {
                        h.seekMin.setProgress(progress);
                        h.tvMinFreq.setText(formatMhz(freqs[progress]));
                    }
                    h.tvMaxFreq.setText(formatMhz(freqs[progress]));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                h.seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                h.seeking = false;
                applyFreq(h, false, freqs[seekBar.getProgress()]);
            }
        });
    }

    private void buildGovernorChips(ClusterViewHolder h, CpuController.Cluster cluster) {
        h.layoutGovernors.removeAllViews();
        String[] governors = cluster.availableGovernors;
        if (governors.length == 0) {
            // 读不到列表就只展示当前值
            Chip chip = new Chip(this);
            chip.setText(cluster.governor == null ? "未知" : cluster.governor);
            chip.setCheckable(false);
            h.layoutGovernors.addView(chip);
            return;
        }
        for (String g : governors) {
            Chip chip = new Chip(this);
            chip.setText(g);
            chip.setCheckable(true);
            chip.setChecked(g.equals(cluster.governor));
            chip.setOnClickListener(v -> {
                if (h.seeking) {
                    return;
                }
                chip.setChecked(true);   // 保持单选，点击已选项也只是重新应用
                for (int i = 0; i < h.layoutGovernors.getChildCount(); i++) {
                    View child = h.layoutGovernors.getChildAt(i);
                    if (child instanceof Chip && child != chip) {
                        ((Chip) child).setChecked(false);
                    }
                }
                applyGovernor(h, g);
            });
            h.layoutGovernors.addView(chip);
        }
    }

    /** 后台写入频率，成功后刷新该卡片状态。 */
    private void applyFreq(ClusterViewHolder h, boolean isMin, long khz) {
        final String label = (isMin ? "最低" : "最高") + "频率";
        executor.execute(() -> {
            boolean ok = isMin
                    ? CpuController.setMinFreqKhz(h.cluster, khz)
                    : CpuController.setMaxFreqKhz(h.cluster, khz);
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                Toast.makeText(this, ok
                        ? h.cluster.displayName() + " " + label + "已设为 " + formatMhz(khz)
                        : label + "设置失败（需要 Root 权限）",
                        ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                if (ok) {
                    refreshHolder(h);
                }
            });
        });
    }

    /** 后台写入调度器。 */
    private void applyGovernor(ClusterViewHolder h, String governor) {
        executor.execute(() -> {
            boolean ok = CpuController.setGovernor(h.cluster, governor);
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                Toast.makeText(this, ok
                        ? h.cluster.displayName() + " 调度器已设为 " + governor
                        : "调度器设置失败（需要 Root 权限）",
                        ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                if (ok) {
                    refreshHolder(h);
                }
            });
        });
    }

    /** 后台重读单个集群并更新对应卡片。 */
    private void refreshHolder(ClusterViewHolder h) {
        executor.execute(() -> {
            List<CpuController.Cluster> fresh = CpuController.readClusters();
            CpuController.Cluster updated = null;
            for (CpuController.Cluster c : fresh) {
                if (java.util.Arrays.equals(c.cpus, h.cluster.cpus)) {
                    updated = c;
                    break;
                }
            }
            if (updated == null || !viewCreated) {
                return;
            }
            final CpuController.Cluster newCluster = updated;
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                h.cluster = newCluster;
                h.tvMinFreq.setText(formatMhz(newCluster.minFreqKhz));
                h.tvMaxFreq.setText(formatMhz(newCluster.maxFreqKhz));
                if (newCluster.isAdjustable()) {
                    long[] freqs = newCluster.availableFreqsKhz;
                    h.seekMin.setProgress(nearestIndex(freqs, newCluster.minFreqKhz));
                    h.seekMax.setProgress(nearestIndex(freqs, newCluster.maxFreqKhz));
                }
                updateCurFreqText(h);
                updateSummary(h);
            });
        });
    }

    // ───────────────── 周期刷新实时频率 ─────────────────

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!viewCreated) {
                return;   // 页面已销毁，停止自循环
            }
            if (holders.isEmpty()) {
                mainHandler.postDelayed(this, 1000L);
                return;
            }
            final ClusterViewHolder[] snapshot = holders.toArray(new ClusterViewHolder[0]);
            executor.execute(() -> {
                final long[] cur = new long[snapshot.length];
                for (int i = 0; i < snapshot.length; i++) {
                    int rep = snapshot[i].cluster.cpus[0];
                    cur[i] = CpuSysFiles.readCpuCurFreqKhz(rep);
                }
                if (!viewCreated) {
                    return;
                }
                mainHandler.post(() -> {
                    if (!viewCreated) {
                        return;
                    }
                    for (int i = 0; i < snapshot.length; i++) {
                        if (cur[i] > 0) {
                            snapshot[i].tvCurFreq.setText(formatMhz(cur[i]));
                        }
                    }
                });
            });
            mainHandler.postDelayed(this, 1000L);
        }
    };

    // ───────────────── 展示辅助 ─────────────────

    private void updateCurFreqText(ClusterViewHolder h) {
        h.tvCurFreq.setText(h.cluster.curFreqKhz > 0
                ? formatMhz(h.cluster.curFreqKhz) : "—");
    }

    private void updateSummary(ClusterViewHolder h) {
        String gov = h.cluster.governor == null || h.cluster.governor.isEmpty()
                ? "未知" : h.cluster.governor;
        h.tvSummary.setText("调度器 " + gov
                + (h.cluster.isAdjustable()
                ? " · 可用范围 " + formatMhz(h.cluster.availableFreqsKhz[0])
                + " - " + formatMhz(h.cluster.availableFreqsKhz[h.cluster.availableFreqsKhz.length - 1])
                : ""));
    }

    private static int nearestIndex(long[] sortedAsc, long target) {
        if (sortedAsc.length == 0) {
            return 0;
        }
        if (target <= sortedAsc[0]) {
            return 0;
        }
        for (int i = 1; i < sortedAsc.length; i++) {
            if (sortedAsc[i] >= target) {
                // 取更接近的一档
                long dPrev = target - sortedAsc[i - 1];
                long dCur = sortedAsc[i] - target;
                return dCur < dPrev ? i : i - 1;
            }
        }
        return sortedAsc.length - 1;
    }

    private static String formatMhz(long khz) {
        if (khz <= 0) {
            return "—";
        }
        long mhz = (khz + 500) / 1000;
        return mhz + " MHz";
    }
}
