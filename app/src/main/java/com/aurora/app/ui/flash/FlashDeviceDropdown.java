package com.aurora.app.ui.flash;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashDevice;

/**
 * 右上角的「设备状态下拉」。
 *
 * <h3>它是什么</h3>
 * 页头右上角那颗胶囊（文字 + 向下箭头），点开弹出一个纵向列表，列出
 * {@link FlashDevice#SLOT_NAMES} 里全部 10 个槽位及其当前状态 ——
 * 「未连接设备」只是其中一行的样子，用户点开才看得到<em>哪一类</em>设备没连上。
 *
 * <h3>与刷机页的关系</h3>
 * 刷机页（{@link FlashPage}）里有一份等价的内联实现，先于本类存在。这里把它抽出来给
 * 小米线刷页复用；两边的行为（只构建一次弹窗、只刷文字、点外部关闭、END 对齐）
 * 完全一致，后续可以把刷机页也迁过来。
 *
 * <h3>为什么弹窗只建一次</h3>
 * 10 行 View 反复 show/dismiss 时重建纯属浪费；构建一次、之后只改每行文字，
 * 打开速度就是「改 10 个 TextView」的量级。
 */
public final class FlashDeviceDropdown {

    /** 弹窗与胶囊之间的垂直间距。 */
    private static final int OFFSET_Y_DP = 6;

    private final Activity activity;
    /** 触发弹窗的胶囊 View。 */
    private final View anchor;

    private PopupWindow popup;

    /** 弹窗里 10 行的状态 TextView，下标与 {@link FlashDevice#SLOT_NAMES} 对齐。 */
    private final TextView[] slotStatusViews = new TextView[FlashDevice.SLOT_NAMES.length];

    public FlashDeviceDropdown(Activity activity, View anchor) {
        this.activity = activity;
        this.anchor = anchor;
    }

    /**
     * 点一下：开着就收起来，没开就弹出来。
     *
     * @param state 最近一次探测结果，{@code null} 表示全部未连接
     */
    public void toggle(FlashDevice.State state) {
        if (isShowing()) {
            dismiss();
            return;
        }
        show(state);
    }

    /** 弹出（已弹出时会先收起再弹，避免位置错乱）。 */
    public void show(FlashDevice.State state) {
        if (anchor == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (popup == null) {
            popup = build();
        }
        if (popup == null) {
            return;
        }
        render(state);
        // gravity END：弹窗右边缘与胶囊右边缘对齐（右上角下拉的常规观感）
        popup.showAsDropDown(anchor, 0, dp(OFFSET_Y_DP), Gravity.END);
    }

    /** 收起。页面不可见或切页时调用，避免 PopupWindow 泄漏。 */
    public void dismiss() {
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
        }
    }

    public boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    // ───────────────── 内部 ─────────────────

    private PopupWindow build() {
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < FlashDevice.SLOT_NAMES.length; i++) {
            View row = activity.getLayoutInflater()
                    .inflate(R.layout.item_flash_device_status, list, false);
            TextView name = row.findViewById(R.id.tvSlotName);
            if (name != null) {
                name.setText(FlashDevice.SLOT_NAMES[i]);
            }
            // 同一个布局被 inflate 了 10 次，id 是重复的 —— 只能存「这一行 inflate 返回值」
            // 里的引用，对容器 findViewById 永远只会拿到第一行。
            slotStatusViews[i] = row.findViewById(R.id.tvSlotStatus);
            list.addView(row);
        }

        PopupWindow window = new PopupWindow(list,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                true);
        // 非 null 背景是「点外部关闭」的前提；圆角由该 drawable 提供（列表行自身透明）
        window.setBackgroundDrawable(
                ContextCompat.getDrawable(activity, R.drawable.bg_flash_device_popup));
        window.setOutsideTouchable(true);
        window.setElevation(dp(8));
        return window;
    }

    /** 按槽位顺序把最新状态铺到 10 行上。 */
    private void render(FlashDevice.State state) {
        String[] statuses = FlashDevice.slotStatuses(state);
        for (int i = 0; i < slotStatusViews.length; i++) {
            TextView view = slotStatusViews[i];
            if (view != null) {
                view.setText(statuses[i]);
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
