package com.aurora.app.ui.device;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.aurora.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 「设备概览」标题右侧那颗数据源胶囊：本机 / 外接设备二选一。
 *
 * <h3>为什么是下拉而不是两套卡片</h3>
 * 本机与外接设备要显示的字段完全一样，做成上下两套视图会带来两个后果：
 * 同一屏出现两份「设备概览」，用户得先分辨哪张是哪台；两套 View 各自要刷、
 * 各自要处理「读不到」，任何一侧出问题都会连带整页重新加载。
 * 共用一套卡片、只换数据源，页面里始终只有一份设备信息。
 *
 * <h3>位置</h3>
 * 胶囊挂在「设备概览」标题那一行的右端，跟着卡片走 —— 不放页面顶部、
 * 不做悬浮角标：它是这张卡片的数据源，不是整页的开关。
 *
 * <h3>与刷机页下拉的关系</h3>
 * 交互与视觉沿用 {@code FlashDeviceDropdown}（只建一次弹窗、之后只改文字、
 * 点外部关闭、右对齐），没有直接复用是因为它的行内容固定为
 * {@code FlashDevice.SLOT_NAMES} 的 10 个槽位，而这里行数随设备数量变化。
 */
public final class DeviceSourceDropdown {

    /** 弹窗与胶囊之间的垂直间距。 */
    private static final int OFFSET_Y_DP = 6;

    /** 一个可选数据源。 */
    public static final class Source {
        /** 胶囊上显示的字：{@code 本机} 或设备序列号。 */
        public final String label;
        /** 弹窗里行下方的小字，说明这一项是什么。 */
        public final String detail;
        /** 外接设备的序列号；本机为 {@code null}。 */
        public final String serial;
        /** {@code true} 表示这不是数据源，而是一个动作（「添加设备…」），点它弹输入框。 */
        public final boolean action;

        public Source(String label, String detail, String serial) {
            this(label, detail, serial, false);
        }

        public Source(String label, String detail, String serial, boolean action) {
            this.label = label;
            this.detail = detail;
            this.serial = serial;
            this.action = action;
        }

        /** 本机数据源。 */
        public static Source local() {
            return new Source("本机", "这台设备", null);
        }

        static Source external(String serial) {
            return new Source(serial, "已连接 · ADB", serial);
        }

        /**
         * 列表末尾的「添加设备…」。
         *
         * <p>存在的理由：USB 插着的那台手机只有 root 身份的内置 adb 才枚举得到
         * （应用身份进不去 {@code /dev/bus/usb}），没有 root 时「另一台手机」
         * 只能靠 {@code adb connect ip:端口} 拉进来，否则下拉里永远是空的、
         * 用户还以为功能没做。
         */
        public static Source addDevice() {
            return new Source("添加设备…", "输入 ip:端口，连接无线调试", null, true);
        }
    }

    public interface Listener {
        void onSourceSelected(Source source);
    }

    private final Activity activity;
    private final View anchor;
    private final TextView chipLabel;
    private final Listener listener;

    private PopupWindow popup;
    private List<Source> sources = new ArrayList<>();
    private String selectedSerial;

    public DeviceSourceDropdown(Activity activity, View anchor, TextView chipLabel, Listener listener) {
        this.activity = activity;
        this.anchor = anchor;
        this.chipLabel = chipLabel;
        this.listener = listener;
    }

    /** 更新可选项与当前选中项（{@code selectedSerial == null} 表示本机）。 */
    public void setSources(List<Source> sources, String selectedSerial) {
        this.sources = sources == null ? new ArrayList<>() : new ArrayList<>(sources);
        this.selectedSerial = selectedSerial;
        renderChip();
        if (popup != null && popup.isShowing()) {
            // 打开状态下数据变了（比如刚插上外接设备），直接重建
            dismiss();
        }
    }

    /** 点一下：开着就收起，没开就弹出。 */
    public void toggle() {
        if (popup != null && popup.isShowing()) {
            dismiss();
            return;
        }
        show();
    }

    public void dismiss() {
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
        }
    }

    private void renderChip() {
        if (chipLabel == null) {
            return;
        }
        chipLabel.setText(labelOf(selectedSerial));
    }

    private String labelOf(String serial) {
        if (serial == null) {
            return "本机";
        }
        // 序列号可能很长，胶囊宽度有限：只留最后 8 位，能区分就行
        return serial.length() <= 12 ? serial : "…" + serial.substring(serial.length() - 10);
    }

    private void show() {
        if (anchor == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Source source : sources) {
            View row = activity.getLayoutInflater().inflate(R.layout.item_flash_device_status, list, false);
            TextView name = row.findViewById(R.id.tvSlotName);
            TextView detail = row.findViewById(R.id.tvSlotStatus);
            // 动作项不参与「当前选中」的比较 —— 它没有 serial，比出 null == null 会显示成「当前」
            boolean selected = !source.action
                    && (source.serial == null ? selectedSerial == null : source.serial.equals(selectedSerial));
            if (name != null) {
                name.setText(source.label);
            }
            if (detail != null) {
                detail.setText(selected ? source.detail + " · 当前" : source.detail);
            }
            row.setOnClickListener(v -> {
                dismiss();
                if (listener != null) {
                    listener.onSourceSelected(source);
                }
            });
            list.addView(row);
        }

        popup = new PopupWindow(list,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                true);
        // 非 null 背景是「点外部关闭」的前提；圆角由该 drawable 提供（列表行自身透明）
        popup.setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.bg_flash_device_popup));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.showAsDropDown(anchor, 0, dp(OFFSET_Y_DP), Gravity.END);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
