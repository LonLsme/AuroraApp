package com.aurora.app.ui.process;

import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aurora.app.R;
import com.aurora.app.core.monitor.ProcessMonitor;
import com.aurora.app.core.root.RootShell;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程管理页：实时进程列表（参考 vtools ActivityProcess 的交互设计）。
 *
 * <ul>
 *   <li>搜索关键字实时过滤（进程名/包名/命令/PID）；</li>
 *   <li>排序 Chip：CPU / 内存 / PID；过滤 Chip：全部 / 应用 / 系统 / 其他；</li>
 *   <li>3 秒后台采集刷新（/proc 差值法，无需 root），首次进入先预热一次建基线；</li>
 *   <li>点击条目弹出详情：PID/CPU/内存/Swap/用户/状态/命令/cpuset/cgroup/oom 等；
 *       应用进程可「停止应用」（killall + force-stop + am kill），原生进程可「结束进程」。</li>
 * </ul>
 */
public class ProcessListActivity extends AppCompatActivity {

    private final ProcessMonitor processMonitor = new ProcessMonitor();
    private ProcessListAdapter adapter;

    private EditText etSearch;
    private ChipGroup chipGroupSort;
    private ChipGroup chipGroupFilter;
    private TextView tvCount;
    private RecyclerView rvList;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_process_list);

        Toolbar toolbar = findViewById(R.id.toolbarProcessList);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("进程管理");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etSearch = findViewById(R.id.etProcessSearch);
        chipGroupSort = findViewById(R.id.chipGroupProcessSort);
        chipGroupFilter = findViewById(R.id.chipGroupProcessFilter);
        tvCount = findViewById(R.id.tvProcessCount);
        rvList = findViewById(R.id.rvProcessList);

        rvList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProcessListAdapter(getPackageManager(), this::openDetail);
        rvList.setAdapter(adapter);

        setupSortChips();
        setupFilterChips();
        setupSearch();

        // 预热：先采一次建 CPU 差值基线，3 秒后第一帧即有真实百分比
        executor.execute(processMonitor::readAll);
        refresh();
    }

    private void setupSortChips() {
        String[][] options = {{"CPU", String.valueOf(ProcessListAdapter.SORT_CPU)},
                {"内存", String.valueOf(ProcessListAdapter.SORT_MEM)},
                {"PID", String.valueOf(ProcessListAdapter.SORT_PID)}};
        addChips(chipGroupSort, options, ProcessListAdapter.SORT_CPU, (chip, value) ->
                adapter.setSortMode(value));
    }

    private void setupFilterChips() {
        String[][] options = {{"全部", String.valueOf(ProcessListAdapter.FILTER_ALL)},
                {"应用", String.valueOf(ProcessListAdapter.FILTER_APP)},
                {"系统", String.valueOf(ProcessListAdapter.FILTER_SYSTEM)},
                {"其他", String.valueOf(ProcessListAdapter.FILTER_OTHER)}};
        addChips(chipGroupFilter, options, ProcessListAdapter.FILTER_ALL, (chip, value) ->
                adapter.setFilterMode(value));
    }

    /** 构建单选 Chip 组：点击已选 Chip 也保持选中（强制 setChecked(true)）。 */
    private void addChips(ChipGroup group, String[][] options, int defaultIndex,
                          ChipCallback callback) {
        for (int i = 0; i < options.length; i++) {
            final int value = Integer.parseInt(options[i][1]);
            Chip chip = new Chip(this);
            chip.setText(options[i][0]);
            chip.setCheckable(true);
            chip.setChecked(i == defaultIndex);
            chip.setOnClickListener(v -> {
                chip.setChecked(true);   // 保持单选，点击已选项不取消
                for (int c = 0; c < group.getChildCount(); c++) {
                    View child = group.getChildAt(c);
                    if (child instanceof Chip && child != chip) {
                        ((Chip) child).setChecked(false);
                    }
                }
                callback.onSelected(chip, value);
            });
            group.addView(chip);
        }
    }

    private interface ChipCallback {
        void onSelected(Chip chip, int value);
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.setKeyword(s.toString());
                updateCount();
            }
        });
    }

    /** 后台采集 → 解析图标/应用名 → 主线程刷新。 */
    private void refresh() {
        if (refreshing.getAndSet(true)) {
            return;
        }
        executor.execute(() -> {
            try {
                List<ProcessMonitor.ProcInfo> list = processMonitor.readAll();
                adapter.resolveMetadata();
                mainHandler.post(() -> {
                    adapter.setData(list);
                    updateCount();
                });
            } catch (Exception ignored) {
            } finally {
                refreshing.set(false);
            }
        });
    }

    private void updateCount() {
        tvCount.setText(String.format(Locale.CHINA, "共 %d 个进程", adapter.getShownCount()));
    }

    /** 点击进程 → 详情对话框。 */
    private void openDetail(ProcessMonitor.ProcInfo info) {
        executor.execute(() -> {
            ProcessMonitor.ProcInfo detail = processMonitor.readDetail(info.pid);
            mainHandler.post(() -> showDetailDialog(detail != null ? detail : info));
        });
    }

    private void showDetailDialog(ProcessMonitor.ProcInfo info) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_process_detail, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // 头部：图标 + 友好名 + 进程名
        ImageView ivIcon = view.findViewById(R.id.ivDetailIcon);
        TextView tvFriendly = view.findViewById(R.id.tvDetailFriendlyName);
        TextView tvName = view.findViewById(R.id.tvDetailProcessName);
        boolean isApp = info.pkg != null;
        Drawable icon = isApp ? loadAppIcon(info.pkg) : null;
        ivIcon.setImageDrawable(icon != null ? icon : getDrawable(isApp
                ? R.drawable.ic_process_android : R.drawable.ic_process_linux));
        tvFriendly.setText(isApp ? loadAppLabel(info.pkg) : info.name);
        tvName.setText(isApp
                ? (info.pkg + " · PID " + info.pid)
                : (info.name + " · PID " + info.pid));

        // 详情键值对
        LinearLayout rows = view.findViewById(R.id.layoutProcessDetailRows);
        addDetailRow(rows, "PID", String.valueOf(info.pid));
        addDetailRow(rows, "CPU", info.percent >= 0 ? info.percent + "%" : "--");
        addDetailRow(rows, "内存(RSS)", ProcessListAdapter.fmtMem(info.resKb));
        addDetailRow(rows, "Swap", ProcessListAdapter.fmtMem(info.swapKb));
        addDetailRow(rows, "用户", info.user == null || info.user.isEmpty() ? "--" : info.user);
        addDetailRow(rows, "状态", info.state == null ? "--" : stateDesc(info.state));
        addDetailRow(rows, "命令", info.command == null ? "--" : info.command);
        addDetailRow(rows, "完整命令行", info.cmdline == null ? "--" : info.cmdline);
        addDetailRow(rows, "cpuset", info.cpuset == null ? "--" : info.cpuset);
        addDetailRow(rows, "cgroup", info.cgroup == null ? "--" : info.cgroup);
        addDetailRow(rows, "oom_adj", info.oomAdj == null ? "--" : info.oomAdj);
        addDetailRow(rows, "oom_score_adj", info.oomScoreAdj == null ? "--" : info.oomScoreAdj);
        addDetailRow(rows, "oom_score", info.oomScore == null ? "--" : info.oomScore);

        // 操作按钮
        View btnStopApp = view.findViewById(R.id.btnDetailStopApp);
        View btnKill = view.findViewById(R.id.btnDetailKill);
        View btnCancel = view.findViewById(R.id.btnDetailCancel);

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        if (isApp) {
            btnStopApp.setVisibility(View.VISIBLE);
            btnStopApp.setOnClickListener(v -> {
                dialog.dismiss();
                killWithToast("killall -9 " + RootShell.escape(info.pkg)
                        + ";am force-stop " + RootShell.escape(info.pkg)
                        + ";am kill " + RootShell.escape(info.pkg),
                        "已停止 " + info.pkg);
            });
        }
        btnKill.setOnClickListener(v -> {
            dialog.dismiss();
            killWithToast("kill -9 " + info.pid,
                    "已结束 " + (isApp ? info.pkg : info.name));
        });

        dialog.show();
    }

    /** 以 root 异步执行命令并提示结果。 */
    private void killWithToast(String cmd, String okMsg) {
        RootShell.get().execAsync(cmd, result -> mainHandler.post(() ->
                Toast.makeText(this,
                        result.isSuccess() ? okMsg : "执行失败（需要 Root 权限）",
                        Toast.LENGTH_SHORT).show()));
    }

    /** 详情对话框的一行键值对。 */
    private void addDetailRow(LinearLayout container, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = getResources().getDisplayMetrics().density > 0
                ? (int) (6 * getResources().getDisplayMetrics().density) : 6;
        row.setLayoutParams(rlp);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(13);
        tvLabel.setTextColor(getColor(R.color.ios_text_secondary));
        row.addView(tvLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f));

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextSize(13);
        tvValue.setTextColor(getColor(R.color.ios_text_primary));
        tvValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(tvValue, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f));
        container.addView(row);
    }

    private Drawable loadAppIcon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private String loadAppLabel(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return String.valueOf(ai.loadLabel(getPackageManager()));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** 状态字符 → 中文描述。 */
    private static String stateDesc(String state) {
        switch (state) {
            case "R":
                return "运行中 (R)";
            case "S":
                return "可中断睡眠 (S)";
            case "D":
                return "不可中断睡眠 (D)";
            case "Z":
                return "僵尸 (Z)";
            case "T":
                return "已停止 (T)";
            case "t":
                return "跟踪停止 (t)";
            case "X":
                return "已死亡 (X)";
            default:
                return state;
        }
    }

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            mainHandler.postDelayed(this, 3000L);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        processMonitor.reset();
        super.onDestroy();
    }
}
