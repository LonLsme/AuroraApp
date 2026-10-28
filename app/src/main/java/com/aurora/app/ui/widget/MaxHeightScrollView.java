package com.aurora.app.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * 带高度上限的 {@link ScrollView}：内容短时按内容高（wrap_content），
 * 内容长时最多长到屏高的 {@value #MAX_HEIGHT_FRACTION} 倍，超出部分滚动。
 *
 * <p><b>为什么需要它</b>：{@code android:maxHeight} 是 {@code ImageView} 的属性，
 * {@code ScrollView} 并不识别 —— 写在 ScrollView 上会被静默忽略，不报错、
 * 不告警、编译照过。曾经就是这么写的：更新日志的 ScrollView 挂了
 * {@code maxHeight="220dp"}，看起来限了高，实际一点作用没有，
 * 于是一条长 Release 正文能把对话框顶到整屏之外，底部按钮直接跑到屏幕外。
 *
 * <p>取值用「屏高比例」而不是固定 dp：分屏 / 小窗口下父容器给的约束本身更小，
 * 本类只在<b>父约束比上限还大</b>时才收窄（见 {@link #onMeasure}），
 * 因此不会把对话框撑出可用区域。
 */
public class MaxHeightScrollView extends ScrollView {

    /** 上限占屏幕高度的比例。留出上下边距，保证按钮与系统栏都在可见区域内。 */
    private static final float MAX_HEIGHT_FRACTION = 0.82f;

    public MaxHeightScrollView(Context context) {
        super(context);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int mode = MeasureSpec.getMode(heightMeasureSpec);
        final int size = MeasureSpec.getSize(heightMeasureSpec);
        if (mode != MeasureSpec.UNSPECIFIED) {
            final int limit = Math.round(
                    getResources().getDisplayMetrics().heightPixels * MAX_HEIGHT_FRACTION);
            if (size > limit) {
                // 收窄成 AT_MOST：内容低于上限时仍然是自适应高度，不低于上限时才截断
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
