package com.aurora.app.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.aurora.app.R;
import com.aurora.app.ui.theme.ThemeColors;
import com.google.android.material.color.MaterialColors;

/**
 * 项目统一的「下拉选择菜单」：紧贴触发行下方的悬浮卡片。
 *
 * <p>不是对话框，也不是系统 {@code PopupMenu} —— 系统那套会弹在屏幕中心、带系统高亮条，
 * 与项目的原生设置范式不搭。设置页的主题色 / 字号 / 底栏效果、CPU 控制的调度器，
 * 凡是「一行 + 若干互斥选项」的地方都用这一个入口，样式才不会各写一套之后慢慢漂移。
 *
 * <p>要点：
 * <ul>
 *   <li><b>宽度自适应内容</b>：按最长一项测量（下限 120dp，上限锚点宽度 - 32dp），
 *       不做固定两档宽 —— 三项菜单和四项菜单不会出现大片空白或文字被截；</li>
 *   <li><b>高度上限屏高一半</b>：CPU 调度器在部分内核上有十来项，不限高时菜单底部会被屏幕裁掉，
 *       超限的部分在菜单内滚动；</li>
 *   <li><b>右边缘贴住锚点</b>：偏移取 {@code anchorWidth - width}，右沿与触发行的右边缘对齐，
 *       左侧自然展开；竖直方向只下移 2dp；</li>
 *   <li>卡片 20dp 圆角、纯色底、零描边、选项之间没有分隔线，
 *       阴影交给 PopupWindow 的 elevation（卡片自身不再叠 elevation，避免双层阴影）；</li>
 *   <li>选中项是主色文字 + 中等字重、右侧打勾（跟随品牌强调色）；未选中项是次级灰、正常字重；</li>
 *   <li>点任意项立即收起并回调，没有「确定」按钮。</li>
 * </ul>
 */
public final class AuroraChoiceMenu {

    private AuroraChoiceMenu() {
    }

    /** 选择结果回调。 */
    public interface OnChoiceListener {
        void onChoice(int index);
    }

    /**
     * 在锚点下方弹出选择菜单。
     *
     * @param anchor        触发菜单的整行 / 整块，菜单右沿与它右对齐
     * @param labels        选项文案，不可为空
     * @param selectedIndex 当前选中项下标；越界时视为「没有选中项」（不打勾）
     * @param listener      点选回调，可为 null（只看不改的场合）
     */
    public static void show(View anchor, String[] labels, int selectedIndex,
                            OnChoiceListener listener) {
        if (anchor == null || labels == null || labels.length == 0) {
            return;
        }
        Context ctx = anchor.getContext();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        View content = inflater.inflate(R.layout.popup_choice_menu, null, false);
        LinearLayout container = content.findViewById(R.id.menuContainer);
        // 圆角底色用代码画：形状 drawable 里的 ?attr/auroraCard 在弹出层上下文里可能取不到，
        // 取不到就整块透明（白底叠白卡，菜单像"浮"在文字上）。这里直接读属性值再设色。
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setShape(GradientDrawable.RECTANGLE);
        menuBg.setColor(MaterialColors.getColor(ctx, R.attr.auroraCard, Color.WHITE));
        menuBg.setCornerRadius(dp(ctx, 20));
        // 浮层压在同样的白卡片上，纯色底会「看不见边界」；加 1dp 极淡描边把轮廓收住，
        // 比加投影更稳（PopupWindow 的阴影会被窗口边界裁掉，实际几乎看不见）。
        menuBg.setStroke(dp(ctx, 1),
                MaterialColors.getColor(ctx, R.attr.auroraSeparator, 0x22000000));
        content.setBackground(menuBg);

        // 先用 WRAP_CONTENT 建出来：选项的点击回调要引用 popup，宽度等填充完再定
        PopupWindow popup = new PopupWindow(
                content, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(ctx, 10));
        // 与项目里其它下拉菜单共用同一套出入场动画（淡入 + 右上角轻微缩放）
        popup.setAnimationStyle(R.style.Animation_Aurora_PopupMenu);

        int primaryColor = ThemeColors.textPrimary(ctx);
        int secondaryColor = ThemeColors.textSecondary(ctx);
        Typeface selectedFace = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface normalFace = Typeface.create("sans-serif", Typeface.NORMAL);

        for (int i = 0; i < labels.length; i++) {
            boolean selected = i == selectedIndex;
            View item = inflater.inflate(R.layout.item_choice_menu, container, false);
            TextView label = item.findViewById(R.id.tvMenuLabel);
            ImageView check = item.findViewById(R.id.ivMenuCheck);
            label.setText(labels[i]);
            label.setTextColor(selected ? primaryColor : secondaryColor);
            label.setTypeface(selected ? selectedFace : normalFace);
            // 未选中保留占位，避免文字在选中态之间左右跳动
            check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            final int index = i;
            item.setOnClickListener(v -> {
                popup.dismiss();
                if (listener != null) {
                    listener.onChoice(index);
                }
            });
            container.addView(item);
        }

        // 内容驱动宽度：按下限 120dp、上限「锚点宽度 - 32dp」约束后测量实际需求
        int minWidth = dp(ctx, 120);
        int maxWidth = Math.max(minWidth, anchor.getWidth() - dp(ctx, 32));
        // 高度上限取屏高一半：CPU 调度器在部分内核上有十来项，不限高会比可用高度还高，
        // 菜单底部被屏幕裁掉，最后两项永远点不到。超出上限时由布局里的 ScrollView 滚动。
        int maxHeight = Math.round(ctx.getResources().getDisplayMetrics().heightPixels * 0.5f);
        content.measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        int width = Math.max(minWidth, content.getMeasuredWidth());
        popup.setWidth(width);
        popup.setHeight(content.getMeasuredHeight());

        // 右沿与锚点右边缘对齐，竖直方向只下移 2dp
        popup.showAsDropDown(anchor, anchor.getWidth() - width, dp(ctx, 2));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
