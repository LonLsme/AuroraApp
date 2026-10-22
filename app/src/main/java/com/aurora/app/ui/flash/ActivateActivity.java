package com.aurora.app.ui.flash;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.aurora.app.R;
import com.aurora.app.core.flash.AppCommands;
import com.aurora.app.core.flash.FlashCommands;

import java.util.ArrayList;
import java.util.List;

/**
 * 「免root激活」页（独立 Activity，竖屏，自带返回箭头与大标题）。
 *
 * <h3>它做什么</h3>
 * 有些应用本身要拿到系统级权限才能干活（冻结别的应用、代理安装等），但它们的作者把
 * 「拿到权限」这一步设计成<b>一次性用 adb 授权</b>——也就是所谓「免 root 激活」。
 * 这一页就是把官方文档里那几条激活命令搬到手机上直接点。
 *
 * <p>操作对象同样是<b>连着的那台设备</b>：本应用用内置 adb 对目标机执行命令，
 * 目标机不需要 root，只需要开着 USB 调试。
 *
 * <h3>命令从哪来</h3>
 * 全部来自 {@link AppCommands#ACTIVATE_TARGETS}，每一条都能在对应应用的官方文档里找到原文
 * （出处逐条记在那个常量的注释里）。这里刻意<b>不</b>提供「随便填一条命令」的输入框 ——
 * 这一页的价值是「照着官方步骤一键完成」，开放自由输入只会把风险转嫁给用户。
 *
 * <h3>为什么每条都要先弹确认框</h3>
 * 两种模式各有代价：脚本模式重启后失效、需要重复激活；设备管理员模式虽然永久生效，
 * 但要求目标机<b>先删掉所有账号、关掉多用户与应用双开</b>，否则一定失败。
 * 这些前提写在确认框里，不摆到页面上（项目约定：页面只放控件与实时状态）。
 */
public final class ActivateActivity extends FlashConsoleActivity {

    /** 动态生成的卡片，用于忙碌态整体置灰。 */
    private final List<View> targetCards = new ArrayList<>();

    /** 外层滚动容器：输出变长时由它滚动，从而「上下都有弹性」。 */
    private NestedScrollView scrollRoot;

    private FlashDeviceDropdown deviceDropdown;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activate);

        scrollRoot = findViewById(R.id.scrollActivate);
        View pill = findViewById(R.id.btnActivateDevicePill);

        // 先建卡片再 bindConsole：bindConsole 里那次设备探测会把页面置为忙碌态，
        // 卡片这时已经存在，才会跟着一起置灰。
        buildTargetCards();
        bindConsole();

        // 输出区随内容增高、由外层承担滚动 —— 必须取消输出框自身的滚动。
        if (tvConsole != null) {
            tvConsole.setMovementMethod(null);
        }

        deviceDropdown = new FlashDeviceDropdown(this, pill);
        if (pill != null) {
            pill.setOnClickListener(v -> deviceDropdown.toggle(lastState));
        }
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
        return targetCards.toArray(new View[0]);
    }

    @Override
    protected String logFileTag() {
        return "activate";
    }

    // ───────────────── 卡片列表 ─────────────────

    /**
     * 按 {@link AppCommands#ACTIVATE_TARGETS} 生成卡片。
     *
     * <p>每次进页面重建一次：清单是编译期常量，卡片也不带状态，没必要缓存复用。
     */
    private void buildTargetCards() {
        LinearLayout container = findViewById(R.id.layoutActivateTargets);
        if (container == null) {
            return;
        }
        LayoutInflater inflater = getLayoutInflater();
        for (AppCommands.ActivateTarget target : AppCommands.ACTIVATE_TARGETS) {
            View card = inflater.inflate(R.layout.item_activate_target, container, false);
            TextView title = card.findViewById(R.id.tvActivateTitle);
            TextView detail = card.findViewById(R.id.tvActivateDetail);
            if (title != null) {
                title.setText(target.title);
            }
            if (detail != null) {
                detail.setText(target.detail);
            }
            card.setOnClickListener(v -> confirmActivate(target));
            container.addView(card);
            targetCards.add(card);
        }
    }

    // ───────────────── 激活 ─────────────────

    private void confirmActivate(AppCommands.ActivateTarget target) {
        if (!requireAdbDevice()) {
            return;
        }
        final String command = AppCommands.activate(target);
        new AlertDialog.Builder(this)
                .setTitle(target.title)
                .setMessage(target.prerequisite + "\n\n命令：\n" + command)
                .setNegativeButton("取消", null)
                .setPositiveButton("激活", (d, w) -> runConsoleCommand(command))
                .show();
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
}
