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

import com.aurora.app.R;
import com.aurora.app.core.monitor.ProcessMonitor;
import com.aurora.app.core.root.RootShell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程监视器悬浮窗（展开列表 / 迷你可拖动条 双状态）。
 *
 * <h3>展开态</h3>
 * 顶部一条工具栏，从左到右：
 * <ol>
 *   <li><b>钉子</b>（{@link ToolIconView#KIND_PIN}）：钉住后窗口不可拖动（{@link OverlayWindow#setDragLocked}），
 *       图标填充主题绿表示已钉住，状态持久化；</li>
 *   <li><b>应用 / 全部</b>文字按钮：切换「只看应用进程」与「全部进程（含内核线程）」，
 *       切换后立即请求一轮刷新，不用等下一个周期；</li>
 *   <li>右侧贴边是<b>缩小</b>图标（{@link ToolIconView#KIND_SHRINK}）：收起成迷你条。</li>
 * </ol>
 * 工具栏下方是可滚动的进程列表（图标 + 友好名 + CPU% + 结束按钮），布局与交互沿用原实现。
 *
 * <h3>迷你态</h3>
 * 只有三样：钉子、首个进程的文字（名称 + CPU%）、扩大图标。点扩大图标回到展开态。
 *
 * <p><b>数据采集</b>仍走 {@link ProcessMonitor#readAll()}（全量遍历 /proc，按 CPU 降序），
 * 过滤在后台线程完成，只对最终上榜的进程解析图标与友好名，避免过滤切换时白做
 * PackageManager 查询。图标与友好名按包名缓存，同一应用重复上榜不再查询。
 *
 * <p><b>取舍提醒：</b>列表区域会消费触摸事件，所以在列表上拖动是「滚动列表」而非「移动窗口」，
 * 想移动窗口请拖工具栏或空白处；钉住后任何位置都不会再移动窗口。
 */
public final class ProcessOverlayWindow extends OverlayWindow {

    /** 展开态最多展示的进程条数（列表可滚动，超出部分滚出视野）。 */
    private static final int MAX_ROWS = 30;

    /** 展开态列表高度（dp）：固定高度是能滚动的前提，取值对齐星野 fw_process 的 170dip。 */
    private static final int LIST_HEIGHT_DP = 170;

    /** 展开态列表宽度（dp）：比星野的 155dip 略宽，给行尾的结束按钮留出位置。 */
    private static final int LIST_WIDTH_DP = 176;

    /** 工具栏图标点击区（dp）。 */
    private static final int TOOL_ICON_DP = 24;

    /** 迷你条进程文字的宽度上限（dp），超长应用名截断，避免窗口被撑宽。 */
    private static final int MINI_LABEL_MAX_DP = 105;

    /** 图标 / 友好名缓存上限：超过后整体清空重建，防止长期运行内存增长。 */
    private static final int META_CACHE_LIMIT = 96;

    /** 结束按钮的红色（深色玻璃底上可读）。 */
    private static final int COLOR_KILL = 0xFFD93025;

    /** 数据源：内部维护两次采样的差值基线（仅后台采集线程访问）。 */
    private final ProcessMonitor processMonitor = new ProcessMonitor();

    /** 最近一次采集 + 过滤后的进程列表（后台线程写，主线程读）。 */
    private volatile List<ProcessMonitor.ProcInfo> topProcesses = Collections.emptyList();

    /**
     * 包名 -> 应用元信息（图标 + 友好名）。
     * 后台采集线程写、主线程读，故用并发安全 Map。
     */
    private final Map<String, AppMeta> metaCache = new ConcurrentHashMap<>();

    /** 主线程 Handler：杀进程的 Toast 回调。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 钉子状态：true = 钉住，窗口不可拖动。持久化到 OverlayConfig。 */
    private boolean pinned;

    /** 过滤模式：true = 只显示应用进程，false = 全部进程。后台采集线程读取，故 volatile。 */
    private volatile boolean appsOnly;

    // ─── 迷你态视图 ───
    private TextView miniLabel;

    // ─── 展开态视图 ───
    private TextView filterButton;
    /** 列表容器（ScrollView 的唯一子视图），行按需创建。 */
    private LinearLayout rowContainer;

    /** 两态共用的钉子 / 缩小 / 扩大图标；同一时刻只有一个视图树存在，字段随之指向当前实例。 */
    private ToolIconView pinIcon;
    private ToolIconView shrinkIcon;
    private ToolIconView expandIcon;

    /** 已创建的行视图；每次重建展开视图时清空。 */
    private final List<RowViews> rows = new ArrayList<>();

    /** 展开态一行的视图引用。 */
    private static final class RowViews {
        LinearLayout root;
        ImageView icon;
        TextView name;
        TextView percent;
        ImageView kill;
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
        pinned = OverlayConfig.isPinned(context, type);
        appsOnly = OverlayConfig.isAppsOnly(context, type);
    }

    /** 显示时应用钉住状态（配置在构造时已读入）。 */
    @Override
    protected void onShown() {
        setDragLocked(pinned);
    }

    // ═══════════════════════════════════════════════════════════
    //  迷你态：钉子 + 进程文字 + 扩大
    // ═══════════════════════════════════════════════════════════

    @Override
    protected View buildCollapsedView() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        int tool = OverlayWindowStyle.dpToPx(context, TOOL_ICON_DP);
        int gap = OverlayWindowStyle.dpToPx(context, 5);

        pinIcon = buildPinIcon();
        row.addView(pinIcon, new LinearLayout.LayoutParams(tool, tool));

        miniLabel = new TextView(context);
        miniLabel.setTextColor(OverlayWindowStyle.MONITOR_TEXT);
        miniLabel.setTextSize(10);
        miniLabel.setTypeface(Typeface.MONOSPACE);
        miniLabel.setIncludeFontPadding(false);
        miniLabel.setSingleLine(true);
        miniLabel.setEllipsize(TextUtils.TruncateAt.END);
        miniLabel.setMaxWidth(OverlayWindowStyle.dpToPx(context, MINI_LABEL_MAX_DP));
        miniLabel.setText("进程 --");
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = gap;
        labelLp.rightMargin = gap;
        row.addView(miniLabel, labelLp);

        expandIcon = new ToolIconView(context, ToolIconView.KIND_EXPAND);
        expandIcon.setContentDescription("展开进程列表");
        expandIcon.setOnClickListener(v -> onSingleTap());
        row.addView(expandIcon, new LinearLayout.LayoutParams(tool, tool));
        return row;
    }

    // ═══════════════════════════════════════════════════════════
    //  展开态：工具栏（钉子 / 过滤 / 缩小）+ 进程列表
    // ═══════════════════════════════════════════════════════════

    @Override
    protected View buildExpandedView() {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);

        int tool = OverlayWindowStyle.dpToPx(context, TOOL_ICON_DP);
        int gap = OverlayWindowStyle.dpToPx(context, 6);

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        pinIcon = buildPinIcon();
        toolbar.addView(pinIcon, new LinearLayout.LayoutParams(tool, tool));

        filterButton = new TextView(context);
        filterButton.setTextSize(10);
        filterButton.setTextColor(OverlayWindowStyle.MONITOR_TEXT);
        filterButton.setTypeface(Typeface.MONOSPACE);
        filterButton.setIncludeFontPadding(false);
        filterButton.setGravity(Gravity.CENTER);
        int hPad = OverlayWindowStyle.dpToPx(context, 7);
        int vPad = OverlayWindowStyle.dpToPx(context, 2);
        filterButton.setPadding(hPad, vPad, hPad, vPad);
        filterButton.setBackground(createToolButtonBackground());
        filterButton.setText(appsOnly ? "应用" : "全部");
        filterButton.setContentDescription("切换进程过滤");
        filterButton.setOnClickListener(v -> toggleFilter());
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        filterLp.leftMargin = gap;
        toolbar.addView(filterButton, filterLp);

        // 中间留白，把缩小按钮推到右端（与标题栏的最小化按钮同位）
        View spacer = new View(context);
        toolbar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        shrinkIcon = new ToolIconView(context, ToolIconView.KIND_SHRINK);
        shrinkIcon.setContentDescription("收起为迷你窗口");
        shrinkIcon.setOnClickListener(v -> onSingleTap());
        toolbar.addView(shrinkIcon, new LinearLayout.LayoutParams(tool, tool));

        LinearLayout.LayoutParams toolbarLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        toolbarLp.bottomMargin = OverlayWindowStyle.dpToPx(context, 3);
        col.addView(toolbar, toolbarLp);

        // 列表：固定宽高的 ScrollView + 动态行容器。
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

    /** 钉子图标：点击切换钉住状态，钉住时填充绿色。 */
    private ToolIconView buildPinIcon() {
        ToolIconView icon = new ToolIconView(context, ToolIconView.KIND_PIN);
        icon.setActive(pinned);
        icon.setContentDescription(pinned ? "取消钉住窗口" : "钉住窗口");
        icon.setOnClickListener(v -> togglePinned());
        return icon;
    }

    /** 工具栏文字按钮底：白 12% 圆角底，深色玻璃上可读。 */
    private GradientDrawable createToolButtonBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(OverlayWindowStyle.dpToPx(context, 4));
        bg.setColor(0x1FFFFFFF);
        return bg;
    }

    /** 钉住 ↔ 取消钉住：更新拖动锁、图标底色与持久化状态。 */
    private void togglePinned() {
        pinned = !pinned;
        OverlayConfig.setPinned(context, type, pinned);
        setDragLocked(pinned);
        if (pinIcon != null) {
            pinIcon.setActive(pinned);
            pinIcon.setContentDescription(pinned ? "取消钉住窗口" : "钉住窗口");
        }
    }

    /** 应用 ↔ 全部：切换过滤并立即刷新一轮。 */
    private void toggleFilter() {
        appsOnly = !appsOnly;
        OverlayConfig.setAppsOnly(context, type, appsOnly);
        if (filterButton != null) {
            filterButton.setText(appsOnly ? "应用" : "全部");
        }
        requestRefreshNow();
    }

    // ═══════════════════════════════════════════════════════════
    //  行视图
    // ═══════════════════════════════════════════════════════════

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

        ImageView kill = buildKillButton();
        LinearLayout.LayoutParams killLp = new LinearLayout.LayoutParams(
                OverlayWindowStyle.dpToPx(context, 20),
                OverlayWindowStyle.dpToPx(context, 20));
        killLp.leftMargin = gap;
        row.addView(kill, killLp);
        r.kill = kill;
        return r;
    }

    /** 结束按钮：红色矢量 ✕，点击杀掉该行对应的进程。 */
    private ImageView buildKillButton() {
        ImageView kill = new ImageView(context);
        kill.setImageResource(R.drawable.ic_tool_close);
        kill.setScaleType(ImageView.ScaleType.FIT_CENTER);
        kill.setContentDescription("结束进程");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        // 与图标同色，取 12% 透明度做浅红圆底：COLOR_KILL & 0x1F...
        bg.setColor(COLOR_KILL & 0x1FFFFFFF);
        kill.setBackground(bg);
        return kill;
    }

    // ═══════════════════════════════════════════════════════════
    //  数据采集与刷新
    // ═══════════════════════════════════════════════════════════

    @Override
    protected void collectData() {
        // 全量遍历 /proc（按 CPU 降序），过滤后再截取前 MAX_ROWS 条 ——
        // 若先截前 30 再过滤，「应用」模式下可能只剩个位数甚至空列表。
        List<ProcessMonitor.ProcInfo> all = processMonitor.readAll();
        boolean appsOnlyNow = appsOnly;
        List<ProcessMonitor.ProcInfo> filtered = new ArrayList<>(MAX_ROWS);
        PackageManager pm = context.getPackageManager();
        for (ProcessMonitor.ProcInfo info : all) {
            if (appsOnlyNow && info.pkg == null) {
                continue;
            }
            filtered.add(info);
            // 只对最终上榜的进程解析图标 / 友好名，过滤掉的条目不做无用查询
            resolveMeta(pm, info.pkg);
            if (filtered.size() >= MAX_ROWS) {
                break;
            }
        }
        topProcesses = filtered;
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
            refreshExpanded();
        } else {
            refreshMini();
        }
    }

    /** 展开态：更新列表各行。 */
    private void refreshExpanded() {
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
                r.name.setText(appsOnly ? "暂无应用进程" : "读取不到进程");
                r.percent.setText("");
                r.kill.setOnClickListener(null);
                r.kill.setClickable(false);
            } else {
                // 本轮进程数比上一轮少：多余的历史行隐藏（行对象保留，下轮可能又用得上）。
                r.root.setVisibility(View.GONE);
            }
        }
    }

    /** 迷你态：钉子 + 首个进程（名称 + CPU%），过滤模式与列表一致。 */
    private void refreshMini() {
        if (miniLabel == null) {
            return;
        }
        if (topProcesses.isEmpty()) {
            miniLabel.setText("进程 --");
            return;
        }
        ProcessMonitor.ProcInfo info = topProcesses.get(0);
        AppMeta meta = metaCache.get(info.pkg != null ? info.pkg : "");
        miniLabel.setText(displayNameOf(info, meta) + " " + info.percent + "%");
    }

    /**
     * 给结束按钮绑定当前行的进程。
     * 点击立即给视觉反馈（该行名称置空），root 杀进程在后台执行：
     * 应用进程用 {@code am force-stop}（连同该应用的其他进程一起结束，更干净），
     * 原生进程用 {@code kill -9}。
     */
    private void bindKillAction(ImageView kill, TextView nameView, ProcessMonitor.ProcInfo info) {
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
                                            : killFailureMessage(result),
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception ignored) {
                        }
                    }));
        });
    }

    /** 把「超时 / 无 root / 命令失败」区分开，别一律甩一句「需要 Root 权限」。 */
    private static String killFailureMessage(RootShell.Result result) {
        if (result.timedOut) {
            return "结束超时（命令无响应），请稍后重试";
        }
        if (!result.isSuccess() && result.out != null && result.out.contains("Permission denied")) {
            return "结束失败（需要 Root 权限）";
        }
        return "结束失败（Root 授权或命令被拒绝）";
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
        miniLabel = null;
        filterButton = null;
        pinIcon = null;
        shrinkIcon = null;
        expandIcon = null;
    }
}
