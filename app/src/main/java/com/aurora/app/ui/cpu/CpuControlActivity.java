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
import androidx.core.widget.NestedScrollView;

import com.aurora.app.R;
import com.aurora.app.core.cpu.CpuController;
import com.aurora.app.core.util.CpuSysFiles;
import com.aurora.app.ui.widget.AuroraChoiceMenu;

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

    private LinearLayout layoutClusters;

    // ── 顶部覆盖式标题栏（随滚动淡入） ──
    private View headerCompact;

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
        /** 核心档位文案，如「大核 · 性能核心」，按最大频率排序算出来。 */
        String tier = "";
        TextView tvCurFreq;
        TextView tvSummary;
        TextView tvMinFreq;
        TextView tvMaxFreq;
        TextView tvNoFreq;
        SeekBar seekMin;
        SeekBar seekMax;
        LinearLayout layoutGovernors;
        TextView tvGovernor;
        boolean seeking;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cpu_control);

        findViewById(R.id.btnCpuBack).setOnClickListener(v -> finish());
        bindCollapsibleHeader();

        layoutClusters = findViewById(R.id.layoutCpuClusters);
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
        mainHandler.removeCallbacksAndMessages(null);
        // 每个页面自建一个单线程 executor；不关掉就每进一次页面留一条常驻线程
        executor.shutdownNow();
        super.onDestroy();
    }

    /** 后台读取集群状态并重建 UI。 */
    private void reload() {
        if (!viewCreated) {
            return;
        }
        layoutClusters.removeAllViews();
        holders.clear();
        executor.execute(() -> {
            List<CpuController.Cluster> clusters = CpuController.readClusters();
            if (!viewCreated) {
                return;
            }
            mainHandler.post(() -> {
                if (!viewCreated) {
                    return;
                }
                if (clusters.isEmpty()) {
                    Toast.makeText(this, "未读取到任何 CPU 集群信息", Toast.LENGTH_SHORT).show();
                    return;
                }
                int[] ranks = ranksByMaxFreq(clusters);
                for (int i = 0; i < clusters.size(); i++) {
                    addClusterCard(clusters.get(i), tierOf(ranks[i], clusters.size()));
                }
            });
        });
    }

    /**
     * 绑定顶部覆盖式标题栏。
     *
     * <p>问题：188dp 的芯片图是固定头，列表只剩半屏；但图是这页的视觉主体，不能删。
     * 现在头图是滚动内容的第一块，跟着列表一起滚走，顶部另有一条 64dp 的标题栏按滚动距离淡入。
     *
     * <p><b>全程只改 alpha / visibility，不改任何尺寸</b>：上一版每帧把头部高度从 188dp 收到 64dp，
     * 滚动容器（NestedScrollView）的边界跟着变；手指按住慢拖时，容器顶端在移动，
     * 表现为「按住下滑时半屏画面抽搐」。只改透明度就没有这个反馈回路。
     */
    private void bindCollapsibleHeader() {
        headerCompact = findViewById(R.id.cpuHeaderCompact);
        findViewById(R.id.btnCpuBackCompact).setOnClickListener(v -> finish());

        NestedScrollView scroll = findViewById(R.id.scrollCpu);
        // 必须显式指定用 NestedScrollView 自己的监听器接口：它同时继承了
        // View.setOnScrollChangeListener 的重载，lambda 不写类型会「引用不明确」编译不过。
        scroll.setOnScrollChangeListener(
                (NestedScrollView.OnScrollChangeListener)
                        (v, scrollX, scrollY, oldX, oldY) -> applyHeaderAlpha(scrollY));
        applyHeaderAlpha(0);
    }

    /**
     * 按滚动距离给覆盖条做淡入。
     *
     * <p>区间取「56dp 才开始、再 56dp 完成」：头图里的大标题占位大约 46~112dp，
     * 如果从 0 就开始淡入，覆盖条会和那张大标题叠在一起，出现「两条标题重影」。
     * 等大标题基本滚出视口再让标题栏进来，衔接是干净的。
     *
     * <p>visibility 与 alpha 一起切：alpha=0 但仍是 VISIBLE 时，这条会挡掉下面 64dp 的点击。
     */
    private void applyHeaderAlpha(int scrollY) {
        int start = dp(56);
        int span = dp(56);
        float t = (scrollY - start) / (float) span;
        t = Math.max(0f, Math.min(1f, t));
        headerCompact.setAlpha(t);
        int desired = t > 0.02f ? View.VISIBLE : View.INVISIBLE;
        if (headerCompact.getVisibility() != desired) {
            headerCompact.setVisibility(desired);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 按最大频率给集群排名：0 = 最高频（大核），total-1 = 最低频（小核）。
     *
     * <p>不能按列表顺序判断 —— 集群是按 CPU 编号排列的（cpu0 往往是小核），
     * 而个别机型反而是大核在前。用频率排名不会被机型差异带偏；频率相同按原顺序稳定排名。
     */
    private static int[] ranksByMaxFreq(List<CpuController.Cluster> clusters) {
        int n = clusters.size();
        int[] ranks = new int[n];
        for (int i = 0; i < n; i++) {
            int rank = 0;
            for (int j = 0; j < n; j++) {
                if (j == i) {
                    continue;
                }
                long fj = clusters.get(j).maxFreqKhz;
                long fi = clusters.get(i).maxFreqKhz;
                if (fj > fi || (fj == fi && j < i)) {
                    rank++;
                }
            }
            ranks[i] = rank;
        }
        return ranks;
    }

    /** 档位文案：rank 0 = 大核，末位 = 小核，中间 = 中核。 */
    private static String tierOf(int rank, int total) {
        if (total <= 1) {
            return "全核心 · 性能核心";
        }
        if (rank == 0) {
            return "大核 · 性能核心";
        }
        if (rank == total - 1) {
            return "小核 · 低功耗";
        }
        return "中核 · 平衡性能";
    }

    private void addClusterCard(CpuController.Cluster cluster, String tier) {
        ClusterViewHolder h = new ClusterViewHolder();
        h.cluster = cluster;
        h.tier = tier;
        View card = LayoutInflater.from(this)
                .inflate(R.layout.item_cpu_cluster, layoutClusters, false);

        TextView tvName = card.findViewById(R.id.tvClusterName);
        h.tvCurFreq = card.findViewById(R.id.tvClusterCurFreq);
        h.tvSummary = card.findViewById(R.id.tvClusterTier);
        h.tvMinFreq = card.findViewById(R.id.tvClusterMinFreq);
        h.tvMaxFreq = card.findViewById(R.id.tvClusterMaxFreq);
        h.tvNoFreq = card.findViewById(R.id.tvClusterNoFreq);
        h.seekMin = card.findViewById(R.id.seekClusterMin);
        h.seekMax = card.findViewById(R.id.seekClusterMax);
        h.layoutGovernors = card.findViewById(R.id.layoutClusterGovernors);
        h.tvGovernor = card.findViewById(R.id.tvClusterGovernor);

        tvName.setText(cluster.displayName());
        bindFreqSection(h, cluster);
        bindGovernorPicker(h, cluster);

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

    /**
     * 调度器选择入口：整行可点，弹出下拉列表（当前项打勾）。
     *
     * <p>原实现把每个调度器铺成一个 {@code Chip}，簇多、调度器多时要横向滚动，
     * 还会跟着卡片宽度抖动；改成一行「当前调度器 ▾」后高度固定，点开才展开全部选项。
     */
    private void bindGovernorPicker(ClusterViewHolder h, CpuController.Cluster cluster) {
        TextView label = h.tvGovernor;
        label.setText(cluster.governor == null ? "未知" : cluster.governor);
        boolean selectable = cluster.availableGovernors != null
                && cluster.availableGovernors.length > 0;
        // 没有可选列表时整行置灰：仍然显示当前值，但不给点
        h.layoutGovernors.setEnabled(selectable);
        h.layoutGovernors.setAlpha(selectable ? 1f : 0.5f);
        h.layoutGovernors.setOnClickListener(selectable
                ? v -> showGovernorMenu(h)
                : null);
    }

    /**
     * 弹出调度器下拉列表；点击某项后异步写入。
     *
     * <p>用项目统一的选择菜单（{@code AuroraChoiceMenu}，与设置页的主题色 / 字号 / 底栏效果同一个）：
     * 系统 {@link android.widget.PopupMenu} 会带系统高亮条、按系统样式弹在触发行下方偏中间的位置，
     * 和这套卡片式原生设置范式不搭。这里锚点是整行「当前调度器 ▾」，菜单右沿与卡片右边缘对齐。
     *
     * <p>当前调度器不在候选列表里时传 -1：菜单不打勾，但选项照常可选（比默认勾第一项诚实）。
     */
    private void showGovernorMenu(ClusterViewHolder h) {
        CpuController.Cluster cluster = h.cluster;
        String[] governors = cluster.availableGovernors;
        if (governors == null || governors.length == 0) {
            return;
        }
        int selected = -1;
        for (int i = 0; i < governors.length; i++) {
            if (governors[i].equals(cluster.governor)) {
                selected = i;
                break;
            }
        }
        AuroraChoiceMenu.show(h.layoutGovernors, governors, selected,
                index -> {
                    if (index >= 0 && index < governors.length) {
                        applyGovernor(h, governors[index]);
                    }
                });
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
                if (h.tvGovernor != null) {
                    // 切换调度器后回读一次，标签跟着更新（失败时不会走到这里）
                    h.tvGovernor.setText(newCluster.governor == null ? "未知" : newCluster.governor);
                }
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

    /**
     * 卡片副标题 = 核心档位。
     *
     * <p>调度器已经单独占一行了，这里再写一遍「调度器 xxx」是重复信息；
     * 档位（大核 / 中核 / 小核）才是用户扫卡片时真正需要的区分。
     */
    private void updateSummary(ClusterViewHolder h) {
        if (h.tvSummary != null) {
            h.tvSummary.setText(h.tier);
        }
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
