package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 阈值变色圆环（Gauge Ring）——复刻 HoshinoScene 的
 * {@code com.omarea.ui.fw.FloatMonitorChartView}。
 *
 * <p>与原实现逐条对齐的绘制规则：
 * <ul>
 *   <li>轨道：整圈，颜色 {@code 0x22FFFFFF}（原实现的 587202559）；</li>
 *   <li>进度弧：起点 12 点方向（{@code -90°}）顺时针，扫过 {@code value × 3.6°}；
 *       数值 ≥98 时直接画满一圈，避免留下 1px 缺口；</li>
 *   <li>描边宽度 4dp，圆环直径取短边的 <b>0.9 倍</b>；</li>
 *   <li>进度弧 alpha 200；数值 ≤2 且未超过起点时不绘制（避免出现一个点）。</li>
 * </ul>
 *
 * <p>配色改用 Aurora 阈值色（{@link OverlayWindowStyle#levelColorOf}），
 * 并允许调用方覆写（电池环按温度/低电量取色）。
 *
 * <p>相对原实现的一处增强：环中心可显示文字（CPU / GPU / 电量 / MEM），
 * 原实现把中心文字交给叠在上层的 TextView 处理；这里收进同一个 View，
 * 省掉一层 FrameLayout，也让悬浮窗的视图层级更浅。
 */
public final class RingChartView extends View {

    /** 未知值（不画弧）。 */
    private static final int UNKNOWN = -1;

    private final float strokeWidthPx;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private int percent = UNKNOWN;
    private int arcColor = OverlayWindowStyle.MONITOR_LEVEL_NORMAL;
    private String centerText = "";

    public RingChartView(Context context) {
        this(context, null);
    }

    public RingChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        strokeWidthPx = dpToPx(4);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokeWidthPx);
        trackPaint.setColor(OverlayWindowStyle.MONITOR_RING_TRACK);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokeWidthPx);
        arcPaint.setStrokeCap(Paint.Cap.BUTT);   // 原实现未设 Cap，默认 BUTT
        arcPaint.setColor(arcColor);
        arcPaint.setAlpha(200);

        textPaint.setColor(OverlayWindowStyle.MONITOR_TEXT);
        textPaint.setTextSize(dpToPx(10));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    /**
     * 更新数值并自动按阈值取色。
     *
     * @param percent 0-100；小于 0 视为未知（只画轨道）
     */
    public void setPercent(int percent) {
        setPercent(percent, OverlayWindowStyle.levelColorOf(percent));
    }

    /**
     * 更新数值并指定弧色（电池环按温度/电量取色时使用）。
     *
     * @param percent 0-100；小于 0 视为未知
     * @param color   进度弧颜色
     */
    public void setPercent(int percent, int color) {
        this.percent = percent;
        if (percent >= 0) {
            this.arcColor = color;
        }
        invalidate();
    }

    /** 环中心文字（如 "CPU" / "42%"）。 */
    public void setCenterText(String text) {
        this.centerText = text == null ? "" : text;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 直径 = 短边 × 0.9（复刻原实现），居中摆放
        float size = Math.min(getWidth(), getHeight()) * 0.9f;
        float left = (getWidth() - size) / 2f;
        float top = (getHeight() - size) / 2f;
        // 描边居中在路径上，向内收半个线宽，避免超出控件边界
        float half = strokeWidthPx / 2f;
        arcRect.set(left + half, top + half, left + size - half, top + size - half);

        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint);

        int value = percent;
        if (value >= 1 || value > 2) {
            arcPaint.setColor(arcColor);
            if (value >= 98) {
                canvas.drawArc(arcRect, -90f, 360f, false, arcPaint);
            } else if (value > 0) {
                canvas.drawArc(arcRect, -90f, value * 3.6f, false, arcPaint);
            }
        }

        if (!centerText.isEmpty()) {
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(centerText, cx, cy, textPaint);
        }
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
