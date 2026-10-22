package com.aurora.app.ui.overlay;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aurora.app.core.monitor.ProcessMonitor;
import com.aurora.app.core.root.RootShell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程监视器悬浮窗（折叠 / 展开双状态）：形态对齐星野 Scene 的进程悬浮窗。
 *
 * <h3>与星野的对照（2026-10-12 改造）</h3>
 * 星野 {@code fw_process.xml} = 标题栏（进程 / 过滤 / 固定 / 最小化 / 关闭）
 * + <b>固定尺寸的 OverScrollListView</b>；其列表项 {@code list_item_process_small} 由
 * <b>应用图标（20dip）+ 友好名 + CPU%</b> 三段构成。
 *
 * <p>本类此前只有「写死的 TOP 5 五行」：既滚不动、也没有应用友好名，
 * 图标解析结果只丢进缓存、失败时不做回退 —— 观感上就是「没有效果」。现在改为：
 * <ul>
 *   <li>展开态用一张 {@link ScrollView} 承载<b>最多 {@link #MAX_ROWS} 行</b>的完整列表，可上下滚动；</li>
 *   <li>每行沿用星野的三段式：应用图标 + 友好名（取不到时回退包名 / 内核 comm）+ CPU%；</li>
 *   <li>行尾保留结束按钮（应用走 {@code am force-stop}，原生进程走 {@code kill -9}）。</li>
 * </ul>
 *
 * <p><b>取舍提醒：</b>列表区域会消费触摸事件，所以在列表上拖动是「滚动列表」而非「移动窗口」，
 * 想移动悬浮窗请拖标题栏 —— 这是可滚动悬浮窗的必然取舍，星野同样如此。
 *
 * <p>数据采集（遍历 /proc、解析图标与友好名）在后台线程进行；
 * 图标与友好名按包名缓存，同一应用重复上榜不再触发 PackageManager 查询。
 */
public final class ProcessOverlayWindow extends OverlayWindow {

    /** 展开态最多展示的进程条数（列表可滚动，超出部分滚出视野）。 */
    private static final int MAX_ROWS = 30;

    /** 展开态列表高度（dp）：固定高度是能滚动的前提，取值对齐星野 fw_process 的 170dip。 */
    private static final int LIST_HEIGHT_DP = 170;

    /** 展开态列表宽度（dp）：比星野的 155dip 略宽，给行尾的结束按钮留出位置。 */
    private static final int LIST_WIDTH_DP = 176;

    /** 图标 / 友好名缓存上限：超过后整体清空重建，防止长期运行内存增长。 */
    private static final int META_CACHE_LIMIT = 96;

    /** 结束按钮的红色（深色玻璃底上可读）。 */
    private static final int COLOR_KILL = 0xFFD93025;

    /** 数据源：内部维护两次采样的差值基线（仅后台采集线程访问）。 */
    private final ProcessMonitor processMonitor = new ProcessMonitor();

    /** 最近一次采集的 top 进程列表（后台线程写，主线程读）。 */
    private volatile List<ProcessMonitor.ProcInfo> topProcesses = Collections.emptyList();

    /**
     * 包名 -> 应用元信息（图标 + 友好名）。
     * 后台采集线程写、主线程读，故用并发安全 Map（此前是 HashMap，属于跨线程隐患）。
     */
    private final Map<String, AppMeta> metaCache = new ConcurrentHashMap<>();

    /** 主线程 Handler：杀进程的 Toast 回调。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ─── 折叠态视图引用 ───
    private ImageView foldIcon;
    private TextView foldRow;

    // ─── 展开态视图引用 ───
    /** 列表容器（ScrollView 的唯一子视图），行按需创建。 */
    private LinearLayout rowContainer;

    /** 已创建的行视图；每次重建展开视图时清空。 */
    private final List<RowViews> rows = new ArrayList<>();

    /** 展开态一行的视图引用。 */
    private static final class RowViews {
        LinearLayout root;
        ImageView icon;
        TextView name;
        TextView percent;
        TextView kill;
    }

    /** 单个应用的元信息。 */
    private static final class AppMeta {
        final Drawable icon;
        final String label;

        AppMeta(Drawable icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    public ProcessOverlayWindow(Context context) {
        super(context, MonitorType.PROCESS);
    }

    @Override
    protected View buildCollapsedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(OverlayWindowStyle.createMonitorTitleView(context, "进程"));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        foldIcon = new ImageView(context);
        int iconSize = OverlayWindowStyle.dpToPx(context, 16);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.rightMargin = OverlayWindowStyle.dpToPx(context, 6);
        row.addView(foldIcon, iconLp);

        foldRow = OverlayWindowStyle.createMonitorBodyView(context);
        foldRow.setText("--");
        foldRow.setTypeface(Typeface.MONOSPACE);
        row.addView(foldRow, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(row);
        return col;
    }

    @Override
    protected View buildExpandedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(OverlayWindowStyle.createMonitorTitleView(context, "进程 · CPU TOP"));

        // 列表：固定宽高的 ScrollView + 动态行容器。
        // 行不再是写死的 5 个 —— 每次重建展开视图时清空引用，由 refreshContent 按实际条数创建。
        rows.clear();
        rowContainer = new LinearLayout(context);
        rowContainer.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(rowContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                OverlayWindowStyle.dpToPx(context, LIST_WIDTH_DP),
                OverlayWindowStyle.dpToPx(context, LIST_HEIGHT_DP));
        col.addView(scroll, listLp);
        return col;
    }

    /** 创建一行：图标 + 名称（weight 1）+ CPU% + 结束按钮。 */
    private RowViews buildRow() {
        int iconSize = OverlayWindowStyle.dpToPx(context, 18);
        int gap = OverlayWindowStyle.dpToPx(context, 6);
        int rowPadV = OverlayWindowStyle.dpToPx(context, 2);

        RowViews r = new RowViews();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, rowPadV, 0, rowPadV);
        r.root = row;

        ImageView icon = new ImageView(context);
        row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        r.icon = icon;

        TextView name = OverlayWindowStyle.createMonitorBodyView(context);
        name.setTextSize(10);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText("--");
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = gap;
        row.addView(name, nameLp);
        r.name = name;

        TextView percent = OverlayWindowStyle.createMonitorBodyView(context);
        percent.setTextSize(10);
        percent.setGravity(Gravity.END);
        percent.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                OverlayWindowStyle.dpToPx(context, 34),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pctLp.leftMargin = gap;
        row.addView(percent, pctLp);
        r.percent = percent;

        TextView kill = buildKillButton();
        LinearLayout.LayoutParams killLp = new LinearLayout.LayoutParams(
                OverlayWindowStyle.dpToPx(context, 20),
                OverlayWindowStyle.dpToPx(context, 20));
        killLp.leftMargin = gap;
        row.addView(kill, killLp);
        r.kill = kill;
        return r;
    }

    /** 结束按钮：红色 ✕，点击杀掉该行对应的进程。 */
    private TextView buildKillButton() {
        TextView kill = new TextView(context);
        kill.setText("✕");
        kill.setTextSize(10);
        kill.setTextColor(COLOR_KILL);
        kill.setGravity(Gravity.CENTER);
        kill.setIncludeFontPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x1FD93025);
        kill.setBackground(bg);
        return kill;
    }

    @Override
    protected void collectData() {
        // 后台线程：全量遍历 /proc + 解析图标与友好名，不阻塞主线程
        List<ProcessMonitor.ProcInfo> list = processMonitor.readTop(MAX_ROWS);
        topProcesses = list;
        PackageManager pm = context.getPackageManager();
        for (ProcessMonitor.ProcInfo info : list) {
            resolveMeta(pm, info.pkg);
        }
    }

    /**
     * 解析包名对应的应用图标与友好名并写入缓存。
     * <p>{@code pkg == null} 表示内核线程 / 原生守护进程：取系统默认图标、无友好名。
     */
    private AppMeta resolveMeta(PackageManager pm, String pkg) {
        String key = pkg != null ? pkg : "";
        AppMeta cached = metaCache.get(key);
        if (cached != null) {
            return cached;
        }
        Drawable icon = null;
        String label = null;
        if (pkg == null) {
            try {
                icon = pm.getDefaultActivityIcon();
            } catch (Exception ignored) {
                // 部分 ROM 裁剪了默认图标；允许为空（该行不显示图标）
            }
        } else {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                icon = ai.loadIcon(pm);
                CharSequence cs = ai.loadLabel(pm);
                label = cs == null ? null : cs.toString();
            } catch (Exception ignored) {
                // 包不存在（已卸载 / 系统限制）：图标与友好名都留空，由展示层回退
            }
        }
        AppMeta meta = new AppMeta(icon, label);
        if (metaCache.size() >= META_CACHE_LIMIT) {
            metaCache.clear();
        }
        metaCache.put(key, meta);
        return meta;
    }

    /** 展示名：应用友好名 > {@link ProcessMonitor.ProcInfo#name}（包名或内核 comm）。 */
    private static String displayNameOf(ProcessMonitor.ProcInfo info, AppMeta meta) {
        if (meta != null && !TextUtils.isEmpty(meta.label)) {
            return meta.label;
        }
        return info.name;
    }

    /** 把行数补齐到 {@code n}（上限 {@link #MAX_ROWS}）；行只增不减，多余的由刷新时置 GONE。 */
    private void ensureRowCount(int n) {
        if (rowContainer == null) {
            return;
        }
        while (rows.size() < n && rows.size() < MAX_ROWS) {
            RowViews r = buildRow();
            rows.add(r);
            rowContainer.addView(r.root);
        }
    }

    @Override
    protected void refreshContent() {
        if (expanded) {
            // 一行都没有时也保留一行，用来显示原因，避免窗口变成一条空白条
            ensureRowCount(Math.max(1, Math.min(topProcesses.size(), MAX_ROWS)));
            for (int i = 0; i < rows.size(); i++) {
                RowViews r = rows.get(i);
                if (i < topProcesses.size()) {
                    ProcessMonitor.ProcInfo info = topProcesses.get(i);
                    AppMeta meta = metaCache.get(info.pkg != null ? info.pkg : "");
                    r.root.setVisibility(View.VISIBLE);
                    r.icon.setImageDrawable(meta != null ? meta.icon : null);
                    r.name.setAlpha(1f);
                    r.name.setText(displayNameOf(info, meta));
                    r.percent.setText(info.percent + "%");
                    bindKillAction(r.kill, r.name, info);
                } else if (i == 0) {
                    // 一条都没读到：保留这一行显示原因，避免窗口变成一条空白
                    r.root.setVisibility(View.VISIBLE);
                    r.icon.setImageDrawable(null);
                    r.name.setAlpha(0.6f);
                    r.name.setText("读取不到进程");
                    r.percent.setText("");
                    r.kill.setOnClickListener(null);
                    r.kill.setClickable(false);
                } else {
                    // 本轮进程数比上一轮少：多余的历史行隐藏（行对象保留，下轮可能又用得上）。
                    // 注意不能在这里继续走"无数据"分支 —— 否则 30 行会一起显示成 "--"。
                    r.root.setVisibility(View.GONE);
                }
            }
        } else {
            if (topProcesses.isEmpty()) {
                foldIcon.setImageDrawable(null);
                foldRow.setText("--");
            } else {
                ProcessMonitor.ProcInfo info = topProcesses.get(0);
                AppMeta meta = metaCache.get(info.pkg != null ? info.pkg : "");
                foldIcon.setImageDrawable(meta != null ? meta.icon : null);
                foldRow.setText(displayNameOf(info, meta) + " " + info.percent + "%");
            }
        }
    }

    /**
     * 给结束按钮绑定当前行的进程。
     * 点击立即给视觉反馈（该行名称置空），root 杀进程在后台执行：
     * 应用进程用 {@code am force-stop}（连同该应用的其他进程一起结束，更干净），
     * 原生进程用 {@code kill -9}。
     */
    private void bindKillAction(TextView kill, TextView nameView, ProcessMonitor.ProcInfo info) {
        kill.setClickable(true);
        kill.setOnClickListener(v -> {
            nameView.setText("--");
            String cmd = info.pkg != null
                    ? "am force-stop " + RootShell.escape(info.pkg)
                    : "kill -9 " + info.pid;
            RootShell.get().execAsync(cmd, result ->
                    mainHandler.post(() -> {
                        try {
                            Toast.makeText(context.getApplicationContext(),
                                    result.isSuccess()
                                            ? "已结束 " + (info.pkg != null ? info.pkg : info.name)
                                            : "结束失败（需要 Root 权限）",
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception ignored) {
                        }
                    }));
        });
    }

    @Override
    protected long getRefreshIntervalMs() {
        return 2000L;
    }

    @Override
    protected void onHidden() {
        // 窗口销毁时清空采样基线、图标与友好名缓存，避免 pid / Drawable 引用累积
        processMonitor.reset();
        metaCache.clear();
        rows.clear();
        rowContainer = null;
    }
}
