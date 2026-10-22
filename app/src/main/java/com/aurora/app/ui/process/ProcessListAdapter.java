package com.aurora.app.ui.process;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.aurora.app.R;
import com.aurora.app.core.monitor.ProcessMonitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 进程管理列表适配器：负责过滤（搜索关键字 + 应用/系统/其他）、
 * 排序（CPU/内存/PID）与渲染（图标 + 友好名 + PID + CPU + 内存）。
 *
 * <p>图标与应用名解析走缓存（包名 → Drawable/CharSequence），
 * 同一应用反复上榜不重复查询 PackageManager；解析在后台线程进行
 * （由 {@link ProcessListActivity} 采集线程调用 {@link #resolveMetadata()}）。
 */
public final class ProcessListAdapter extends RecyclerView.Adapter<ProcessListAdapter.Holder> {

    public static final int SORT_CPU = 0;
    public static final int SORT_MEM = 1;
    public static final int SORT_PID = 2;

    public static final int FILTER_ALL = 0;
    public static final int FILTER_APP = 1;       // 用户应用进程（u0_a* 用户）
    public static final int FILTER_SYSTEM = 2;    // 系统应用进程（system 等用户）
    public static final int FILTER_OTHER = 3;     // 原生/非应用进程

    /** 图标缓存上限：超过后整体清空重建。 */
    private static final int ICON_CACHE_LIMIT = 200;

    private final PackageManager pm;
    /** 全部进程（最新一次采集）。 */
    private final List<ProcessMonitor.ProcInfo> all = new ArrayList<>();
    /** 过滤+排序后的可见列表。 */
    private final List<ProcessMonitor.ProcInfo> shown = new ArrayList<>();

    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final Map<String, String> labelCache = new HashMap<>();

    private String keyword = "";
    private int sortMode = SORT_CPU;
    private int filterMode = FILTER_ALL;

    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(ProcessMonitor.ProcInfo info);
    }

    public ProcessListAdapter(PackageManager pm, OnItemClickListener listener) {
        this.pm = pm;
        this.listener = listener;
    }

    /** 更新数据（应在主线程调用；采集线程需先 post 到主线程）。 */
    public void setData(List<ProcessMonitor.ProcInfo> list) {
        all.clear();
        if (list != null) {
            all.addAll(list);
        }
        applyFilter();
    }

    /** 在后台线程解析所有可见项的图标与应用名缓存（采集线程调用）。 */
    public void resolveMetadata() {
        for (ProcessMonitor.ProcInfo info : all) {
            resolveOne(info);
        }
    }

    private void resolveOne(ProcessMonitor.ProcInfo info) {
        String pkg = info.pkg;
        if (pkg == null) {
            return;   // 原生进程：无应用元数据
        }
        if (!labelCache.containsKey(pkg)) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                labelCache.put(pkg, String.valueOf(ai.loadLabel(pm)));
                if (!iconCache.containsKey(pkg)) {
                    if (iconCache.size() >= ICON_CACHE_LIMIT) {
                        iconCache.clear();
                    }
                    iconCache.put(pkg, ai.loadIcon(pm));
                }
            } catch (Exception ignored) {
                labelCache.put(pkg, pkg);   // 包不存在（已卸载/系统限制）
            }
        }
    }

    /** 关键字过滤（进程名/包名/命令/PID）。 */
    public void setKeyword(String k) {
        this.keyword = k == null ? "" : k.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    public void setSortMode(int mode) {
        this.sortMode = mode;
        applyFilter();
    }

    public void setFilterMode(int mode) {
        this.filterMode = mode;
        applyFilter();
    }

    /** 当前可见条目数（供页脚「共 N 个进程」显示）。 */
    public int getShownCount() {
        return shown.size();
    }

    private void applyFilter() {
        shown.clear();
        for (ProcessMonitor.ProcInfo info : all) {
            if (!matchesKeyword(info) || !matchesFilter(info)) {
                continue;
            }
            shown.add(info);
        }
        shown.sort(this::compare);
        notifyDataSetChanged();
    }

    private boolean matchesKeyword(ProcessMonitor.ProcInfo info) {
        if (keyword.isEmpty()) {
            return true;
        }
        String friendly = friendlyNameOf(info).toLowerCase(Locale.ROOT);
        String name = info.name == null ? "" : info.name.toLowerCase(Locale.ROOT);
        String pkg = info.pkg == null ? "" : info.pkg.toLowerCase(Locale.ROOT);
        String cmd = info.command == null ? "" : info.command.toLowerCase(Locale.ROOT);
        return friendly.contains(keyword) || name.contains(keyword)
                || pkg.contains(keyword) || cmd.contains(keyword)
                || String.valueOf(info.pid).equals(keyword);
    }

    private boolean matchesFilter(ProcessMonitor.ProcInfo info) {
        switch (filterMode) {
            case FILTER_APP:
                return info.pkg != null && isUserApp(info);
            case FILTER_SYSTEM:
                return info.pkg != null && !isUserApp(info);
            case FILTER_OTHER:
                return info.pkg == null;
            default:
                return true;
        }
    }

    /** 用户应用进程：归属用户以 u0_a 开头（Android 应用沙箱用户）。 */
    private static boolean isUserApp(ProcessMonitor.ProcInfo info) {
        return info.user != null && info.user.startsWith("u0_a");
    }

    private int compare(ProcessMonitor.ProcInfo a, ProcessMonitor.ProcInfo b) {
        switch (sortMode) {
            case SORT_MEM:
                return Long.compare(b.resKb, a.resKb);
            case SORT_PID:
                return Integer.compare(a.pid, b.pid);
            default:
                return Integer.compare(b.percent, a.percent);
        }
    }

    private String friendlyNameOf(ProcessMonitor.ProcInfo info) {
        if (info.pkg != null) {
            String label = labelCache.get(info.pkg);
            if (label != null) {
                return label;
            }
            return info.pkg;
        }
        return info.name == null ? String.valueOf(info.pid) : info.name;
    }

    private Drawable iconOf(ProcessMonitor.ProcInfo info) {
        if (info.pkg != null) {
            Drawable d = iconCache.get(info.pkg);
            return d != null ? d : null;
        }
        return null;
    }

    /** 内存格式化：< 1MB 显示 KB，否则 MB。 */
    static String fmtMem(long kb) {
        if (kb >= 1024) {
            return kb / 1024 + "MB";
        }
        return kb + "KB";
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_process_list, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ProcessMonitor.ProcInfo info = shown.get(position);
        boolean isApp = info.pkg != null;
        Drawable icon = iconOf(info);
        h.ivIcon.setImageDrawable(icon != null ? icon
                : h.itemView.getContext().getDrawable(isApp
                        ? R.drawable.ic_process_android : R.drawable.ic_process_linux));

        String friendly = friendlyNameOf(info);
        h.tvFriendly.setText(friendly);
        if (isApp && !friendly.equals(info.pkg)) {
            h.tvName.setVisibility(View.VISIBLE);
            h.tvName.setText(info.pkg + (info.pid > 0 ? " · " + info.pid : ""));
        } else {
            h.tvName.setVisibility(View.VISIBLE);
            h.tvName.setText(info.name == null ? String.valueOf(info.pid) : info.name);
        }

        h.tvPid.setText("PID " + info.pid);
        h.tvCpu.setText(info.percent >= 0 ? info.percent + "%" : "--");
        h.tvMem.setText(fmtMem(info.resKb));

        h.itemView.setOnClickListener(v -> listener.onItemClick(info));
    }

    @Override
    public int getItemCount() {
        return shown.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvFriendly;
        final TextView tvName;
        final TextView tvPid;
        final TextView tvCpu;
        final TextView tvMem;

        Holder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivProcessIcon);
            tvFriendly = itemView.findViewById(R.id.tvProcessFriendlyName);
            tvName = itemView.findViewById(R.id.tvProcessName);
            tvPid = itemView.findViewById(R.id.tvProcessPid);
            tvCpu = itemView.findViewById(R.id.tvProcessCpu);
            tvMem = itemView.findViewById(R.id.tvProcessMem);
        }
    }
}
