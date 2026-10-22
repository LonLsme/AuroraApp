package com.aurora.app.ui.flash;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aurora.app.R;
import com.aurora.app.core.flash.AppCommands;
import com.aurora.app.core.flash.FlashCommands;

import java.util.List;

/**
 * 「应用卸载冻结」页（独立 Activity，竖屏，自带返回箭头与大标题）。
 *
 * <h3>它做什么</h3>
 * 读取<b>连着的那台设备</b>上的应用清单，勾选之后批量卸载 / 冻结 / 解冻。
 * 三条命令都经 {@code adb shell} 打到目标机（见 {@link AppCommands}），
 * 目标机<b>不需要 root</b> —— {@code adb shell} 在设备侧是 {@code shell} 用户，
 * 本身就持有 {@code DELETE_PACKAGES} / {@code CHANGE_COMPONENT_ENABLED_STATE} 权限。
 *
 * <h3>为什么列表要固定高度、自己滚</h3>
 * 目标机装几百个应用是常事。列表要是 {@code wrap_content}，整页会被撑到极长、
 * 每项都要参与测量，滚动也失去「一段一段」的手感。固定高度后列表在框内滚动，
 * 滚到边界再把手势交给外层 {@link NestedScrollView}，两层的弹性都还在。
 *
 * <h3>为什么操作完不自动刷新列表</h3>
 * 每次命令结束，基类的收尾都会顺手做一次设备探测，而探测与命令共用
 * {@link com.aurora.app.core.flash.FlashRuntime} 的单任务闸门 —— 紧接着再自动发一条
 * 列表命令有概率被拒，用户会看到一句莫名其妙的「已有任务在执行」。
 * 所以这里只在输出区留一句提示，刷新由用户点按钮决定。
 */
public final class AppUninstallActivity extends FlashConsoleActivity {

    // ── 页面控件 ──
    private View btnAppListRefresh;
    /** 应用范围切换按钮 —— 文字随当前范围变（第三方应用 / 系统应用）。 */
    private android.widget.TextView btnAppListScope;
    private View btnAppUninstall;
    private View btnAppFreeze;
    private View btnAppUnfreeze;
    private EditText etFilter;
    private TextView tvSelectionSummary;
    private RecyclerView rvApps;

    /** 外层滚动容器：输出变长时由它滚动，从而「上下都有弹性」。 */
    private NestedScrollView scrollRoot;

    private FlashDeviceDropdown deviceDropdown;

    private DeviceAppAdapter adapter;

    /** 当前列表范围：{@code false} = 第三方应用，{@code true} = 系统应用。 */
    private boolean systemScope;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_uninstall);

        btnAppListRefresh = findViewById(R.id.btnAppListRefresh);
        btnAppListScope = findViewById(R.id.btnAppListScope);
        btnAppUninstall = findViewById(R.id.btnAppUninstall);
        btnAppFreeze = findViewById(R.id.btnAppFreeze);
        btnAppUnfreeze = findViewById(R.id.btnAppUnfreeze);
        etFilter = findViewById(R.id.etAppFilter);
        tvSelectionSummary = findViewById(R.id.tvAppSelectionSummary);
        rvApps = findViewById(R.id.rvDeviceApps);
        scrollRoot = findViewById(R.id.scrollAppUninstall);
        View pill = findViewById(R.id.btnAppUninstallDevicePill);

        // 抓公共控件 + 接好返回 / 保存日志 / 终止，并触发一次设备探测。
        bindConsole();

        // 输出区随内容增高、由外层承担滚动 —— 必须取消输出框自身的滚动。
        if (tvConsole != null) {
            tvConsole.setMovementMethod(null);
        }

        adapter = new DeviceAppAdapter(count ->
                tvSelectionSummary.setText("已选 " + count + " 项"));
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        rvApps.setAdapter(adapter);

        deviceDropdown = new FlashDeviceDropdown(this, pill);
        if (pill != null) {
            pill.setOnClickListener(v -> deviceDropdown.toggle(lastState));
        }

        setOnClick(btnAppListRefresh, this::refreshList);
        setOnClick(btnAppListScope, this::toggleScope);
        setOnClick(btnAppUninstall, () -> confirmBatch(BatchAction.UNINSTALL));
        setOnClick(btnAppFreeze, () -> confirmBatch(BatchAction.FREEZE));
        setOnClick(btnAppUnfreeze, () -> confirmBatch(BatchAction.UNFREEZE));

        if (etFilter != null) {
            etFilter.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    adapter.setFilter(s == null ? "" : s.toString());
                }
            });
        }

        renderSelectionCount();
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
        // 搜索框也要禁：列表正在刷新时改筛选条件没有意义，而且会让人以为筛选没生效。
        return new View[]{btnAppListRefresh, btnAppListScope, btnAppUninstall,
                btnAppFreeze, btnAppUnfreeze, etFilter};
    }

    @Override
    protected String logFileTag() {
        return "app-manager";
    }

    // ───────────────── 列表 ─────────────────

    private void refreshList() {
        if (!requireAdbDevice()) {
            return;
        }
        String command = systemScope ? AppCommands.listSystem() : AppCommands.listThirdParty();
        runConsoleCommand(command, (output, exitCode, cancelled) -> {
            if (cancelled) {
                return;
            }
            List<String> packages = AppCommands.parsePackageList(output);
            if (packages.isEmpty()) {
                adapter.submitList(packages);
                appendLine(AppCommands.isAdbError(output)
                        ? "读取失败：未检测到 ADB 设备"
                        : "未读取到应用（列表为空）");
                return;
            }
            adapter.submitList(packages);
            appendLine("已读取 " + packages.size() + " 个应用");
        });
    }

    /** 在「第三方应用」与「系统应用」之间切换，并立刻刷新一次。 */
    private void toggleScope() {
        systemScope = !systemScope;
        if (btnAppListScope != null) {
            btnAppListScope.setText(systemScope ? "系统应用" : "第三方应用");
        }
        // 换了范围，旧列表已经没有意义 —— 先清空再读，避免用户对着上一份列表操作。
        adapter.submitList(null);
        refreshList();
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

    // ───────────────── 批量操作 ─────────────────

    /** 三个批量动作。 */
    private enum BatchAction {
        /** 卸载 —— 不可逆，数据一并清除。 */
        UNINSTALL,
        /** 冻结 —— 停用，可解冻还原。 */
        FREEZE,
        /** 解冻 —— 恢复启用。 */
        UNFREEZE
    }

    private void confirmBatch(BatchAction action) {
        List<String> packages = adapter.selectedPackages();
        if (packages.isEmpty()) {
            toast(AppCommands.NO_PACKAGE_SELECTED);
            return;
        }
        if (!requireAdbDevice()) {
            return;
        }
        // 包名逐个过一遍校验：列表来自设备、正常不会含怪字符，但这里和自定义输入走同一条路径，
        // 校验兜底一次，失败就停在这一步，不让它进到命令里。
        for (String pkg : packages) {
            if (!AppCommands.isValidPackageName(pkg)) {
                toast("包名异常，已拒绝执行：" + pkg);
                return;
            }
        }

        final String command;
        final String title;
        final String positive;
        switch (action) {
            case UNINSTALL:
                command = AppCommands.uninstallPackages(packages);
                title = "确认卸载";
                positive = "卸载";
                break;
            case FREEZE:
                command = AppCommands.disablePackages(packages);
                title = "确认冻结";
                positive = "冻结";
                break;
            default:
                command = AppCommands.enablePackages(packages);
                title = "确认解冻";
                positive = "解冻";
                break;
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(buildBatchMessage(action, packages))
                .setNegativeButton("取消", null)
                .setPositiveButton(positive, (d, w) -> runConsoleCommand(command, (output, code, cancelled) -> {
                    if (!cancelled) {
                        appendLine("操作完成，可点击「刷新列表」查看最新状态");
                    }
                }))
                .show();
    }

    private String buildBatchMessage(BatchAction action, List<String> packages) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标机上共 ").append(packages.size()).append(" 个应用：\n");
        // 超过 8 个只列前 8 个 —— 确认框的意义是让人确认「选的是这些」，不是把列表再抄一遍。
        int shown = Math.min(packages.size(), 8);
        for (int i = 0; i < shown; i++) {
            sb.append("  · ").append(packages.get(i)).append('\n');
        }
        if (packages.size() > shown) {
            sb.append("  … 其余 ").append(packages.size() - shown).append(" 个\n");
        }
        sb.append('\n');
        switch (action) {
            case UNINSTALL:
                sb.append("卸载会连同应用数据一起删除，不可恢复。");
                break;
            case FREEZE:
                sb.append("冻结（停用）后应用从桌面消失、不再能启动与自启，数据保留，可解冻还原。");
                break;
            default:
                sb.append("解冻会把该应用恢复到可用状态。");
                break;
        }
        return sb.toString();
    }

    private void renderSelectionCount() {
        if (tvSelectionSummary != null) {
            tvSelectionSummary.setText("已选 " + adapter.selectedCount() + " 项");
        }
    }

    // ───────────────── 工具 ─────────────────

    private static void setOnClick(View view, Runnable action) {
        if (view != null) {
            view.setOnClickListener(v -> action.run());
        }
    }

    /** 只关心 afterTextChanged 的 {@code TextWatcher} 空实现（省掉三个无用方法）。 */
    private abstract static class SimpleTextWatcher implements android.text.TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
