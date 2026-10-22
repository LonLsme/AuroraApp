package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;

/**
 * 多柱占用率条形图（每根柱子 = 一个 CPU 核心）——复刻 HoshinoScene 的
 * {@code com.omarea.ui.CpuChartBarView}。
 *
 * <p>与原实现逐条对齐的绘制规则：
 * <ul>
 *   <li>柱宽 = 控件宽度 ÷ 柱数，柱间留 5% 宽度间隙，圆角 5px；</li>
 *   <li>柱高 = {@code (100 - 占用率) / 100 × 控件高}，即占用越高柱子越高；
 *       占用 ≤5 时按 95% 高度兜底（原实现在此处会让柱子几乎消失）；</li>
 *   <li>柱色按阈值取：{@code >85 深橙}、{@code >65 橙}、其余取强调色；</li>
 *   <li>透明度随占用率线性提高：{@code min(minAlpha + 占用率/100/2×255, maxAlpha)}。</li>
 * </ul>
 *
 * <p>迷你监视器用它显示 CPU / GPU 的历史走势（柱数 = 历史采样数），
 * 系统监视器展开态用它显示各核心的实时占用（柱数 = 核心数）。
 */
public final class CoreBarChartView extends View {

    /** 默认柱数（迷你形态的历史窗口长度）。 */
    private static final int DEFAULT_BARS = 5;
    /** 柱间圆角（px）。 */
    private static final float BAR_RADIUS = 5f;

    private final ArrayDeque<Integer> values;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 每根柱子的宽度（onDraw 首次计算后缓存，随宽度变化重算）。 */
    private float barWidth;
    /** 目标柱数：{@link #push} 用它决定历史窗口长度。 */
    private int barCount = DEFAULT_BARS;
    private int minAlpha = 35;
    private int maxAlpha = 255;

    public CoreBarChartView(Context context) {
        this(context, null);
    }

    public CoreBarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        values = new ArrayDeque<>(DEFAULT_BARS);
        for (int i = 0; i < DEFAULT_BARS; i++) {
            values.addLast(0);
        }
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setStrokeWidth(0f);
    }

    /** 设置柱子的固定数量（会重置历史）。核心数会变化的场景请直接 {@link #setData}。 */
    public void setBarCount(int count) {
        if (count <= 0) {
            return;
        }
        barCount = count;
        values.clear();
        for (int i = 0; i < count; i++) {
            values.addLast(0);
        }
        barWidth = 0f;
        invalidate();
    }

    /** 追加一个采样值（超出柱数时挤掉最旧的一根），用于历史走势。 */
    public void push(int percent) {
        values.addLast(clamp(percent));
        while (values.size() > barCount) {
            values.pollFirst();
        }
        invalidate();
    }

    private int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    /** 整体替换数据（如各核心占用）。 */
    public void setData(Integer[] data) {
        values.clear();
        if (data != null) {
            for (Integer value : data) {
                values.addLast(clamp(value == null ? 0 : value));
            }
        }
        barCount = Math.max(1, values.size());
        barWidth = 0f;
        invalidate();
    }

    /** 最低/最高透明度（0-255）。 */
    public void setAlphaRange(int minAlpha, int maxAlpha) {
        this.minAlpha = minAlpha;
        this.maxAlpha = maxAlpha;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = values.size();
        if (count <= 0) {
            return;
        }
        if (barWidth <= 0f) {
            barWidth = (float) getWidth() / count;
        }
        float height = getHeight();

        int index = 0;
        for (int percent : values) {
            float left = index * barWidth;
            float right = left + barWidth * 0.95f;

            float top;
            if (percent <= 5) {
                // 原实现：极低占用时也保留 5% 的柱高，避免视觉上完全消失
                top = height - height / 20f;
            } else if (percent >= 98) {
                top = 0f;
            } else {
                top = (100 - percent) * height / 100f;
            }

            barPaint.setColor(OverlayWindowStyle.levelColorOf(percent));
            // 透明度随占用率线性提高（复刻原实现：minAlpha + 占用率/100/2 × 255）
            barPaint.setAlpha(Math.min(minAlpha + (int) (percent / 100f / 2f * 255f), maxAlpha));
            canvas.drawRoundRect(left, top, right, height, BAR_RADIUS, BAR_RADIUS, barPaint);
            index++;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        barWidth = values.isEmpty() ? 0f : (float) w / values.size();
    }
}
