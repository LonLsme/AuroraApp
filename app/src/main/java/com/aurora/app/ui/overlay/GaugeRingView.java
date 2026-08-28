package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * 圆环进度控件（Gauge Ring）：
 * 折叠小窗口用于展示 CPU / GPU 使用率的圆形进度环，
 * 环中心显示百分比文字。
 *
 * <p>用法：new GaugeRingView(context, accentColor)，再调
 * {@link #setProgress(int)} 更新数值（0-100，负值显示 "--"）。
 */
public final class GaugeRingView extends View {

    /** 环描边宽度（px），构造时按密度换算。 */
    private final float strokeWidthPx;
    /** 进度弧颜色（如 CPU 绿 / GPU 青）。 */
    private final int accentColor;
    /** 中心百分比文字。 */
    private String centerText = "--";

    private int progress = -1; // -1 表示未知

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    public GaugeRingView(Context context, int accentColor) {
        super(context);
        this.accentColor = accentColor;
        this.strokeWidthPx = dpToPx(5);
        initPaints();
    }

    public GaugeRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.accentColor = Color.parseColor("#00C853");
        this.strokeWidthPx = dpToPx(5);
        initPaints();
    }

    private void initPaints() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokeWidthPx);
        trackPaint.setColor(0x1F000000); // 淡灰轨道

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokeWidthPx);
        arcPaint.setColor(accentColor);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setColor(0xE6000000);
        textPaint.setTextSize(dpToPx(11));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    /**
     * 更新进度并刷新绘制。
     *
     * @param percent 0-100；小于 0 视为未知，显示 "--"。
     */
    public void setProgress(int percent) {
        this.progress = percent;
        this.centerText = percent < 0 ? "--" : String.format(Locale.US, "%d", percent);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float half = strokeWidthPx / 2f;
        // 圆环区域（避开描边溢出）
        arcRect.set(half, half, size - half, size - half);

        // 背景轨道环
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint);

        // 进度弧：从 12 点方向顺时针
        if (progress > 0) {
            canvas.drawArc(arcRect, -90f, progress * 3.6f, false, arcPaint);
        }

        // 中心文字
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(centerText, size / 2f, baseline, textPaint);
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
