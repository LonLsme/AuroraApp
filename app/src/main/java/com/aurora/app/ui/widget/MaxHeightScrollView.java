package com.aurora.app.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.ScrollView;

import com.aurora.app.R;

/**
 * 带高度上限的 {@link ScrollView}：内容短时按内容高（wrap_content），
 * 内容长时最多长到上限，超出部分滚动。
 *
 * <p><b>为什么需要它</b>：{@code android:maxHeight} 是 {@code ImageView} 的属性，
 * {@code ScrollView} 并不识别 —— 写在 ScrollView 上会被静默忽略，不报错、
 * 不告警、编译照过。曾经就是这么写的：更新日志的 ScrollView 挂了
 * {@code maxHeight="220dp"}，看起来限了高，实际一点作用没有，
 * 于是一条长 Release 正文能把对话框顶到整屏之外，底部按钮直接跑到屏幕外。
 *
 * <p>上限有两种给法：
 * <ul>
 *   <li>XML 里写 {@code app:auroraMaxHeight="340dp"} —— 明确的上限，适合「头部 + 按钮
 *       还要占地方」的对话框，剩余空间靠限高自己算不准；</li>
 *   <li>不写 —— 用屏高的 {@value #DEFAULT_HEIGHT_FRACTION}，适合「整张卡片就是这一个
 *       滚动容器」的场合（如更新日志弹窗）。</li>
 * </ul>
 *
 * <p>两种都给 {@code AT_MOST} 而不是 {@code EXACTLY}，并且<b>只在父约束比上限还大时
 * 才收窄</b>：分屏 / 小窗口下父容器给的可用高度本身更小，不会被这个上限反过来撑开。
 */
public class MaxHeightScrollView extends ScrollView {

    /** 未指定任何上限时，上限占屏幕高度的比例。 */
    private static final float DEFAULT_HEIGHT_FRACTION = 0.82f;

    /** XML 给的固定上限（像素）；0 表示未指定。 */
    private int explicitMaxHeight;

    /** XML 给的屏高比例；0 表示未指定。 */
    private float explicitFraction;

    public MaxHeightScrollView(Context context) {
        this(context, null);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.scrollViewStyle);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(
                    attrs, R.styleable.MaxHeightScrollView, defStyleAttr, 0);
            try {
                explicitMaxHeight = a.getDimensionPixelSize(
                        R.styleable.MaxHeightScrollView_auroraMaxHeight, 0);
                explicitFraction = a.getFloat(
                        R.styleable.MaxHeightScrollView_auroraMaxHeightFraction, 0f);
            } finally {
                a.recycle();
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int mode = MeasureSpec.getMode(heightMeasureSpec);
        final int size = MeasureSpec.getSize(heightMeasureSpec);
        if (mode != MeasureSpec.UNSPECIFIED) {
            final int screenHeight = getResources().getDisplayMetrics().heightPixels;
            final int limit;
            if (explicitMaxHeight > 0) {
                limit = explicitMaxHeight;
            } else {
                float fraction = explicitFraction > 0f ? explicitFraction : DEFAULT_HEIGHT_FRACTION;
                limit = Math.round(screenHeight * fraction);
            }
            if (size > limit) {
                // 收窄成 AT_MOST：内容低于上限时仍是自适应高度，达到上限才截断
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
