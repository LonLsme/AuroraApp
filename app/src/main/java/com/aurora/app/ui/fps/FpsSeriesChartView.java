package com.aurora.app.ui.fps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 会话详情页的通用单值曲线图（FPS / 功耗 / CPU / 温度共用）。
 *
 * <p>与 {@link FpsChartView}（首页实时曲线，单色、固定 0~上限）的区别：
 * <ul>
 *   <li>支持负值区间（功耗放电为负），Y 轴范围按数据自动算；</li>
 *   <li>负值之外的 {@code -1} 视为「本秒无采样」，折线在此断开而不是压到 0；</li>
 *   <li>可指定曲线颜色、单位后缀，Y 轴标出上限 / 下限。</li>
 * </ul>
 *
 * <p>绘制完全在 onDraw 内完成，不分配列表；setSeries 先比后写，数据没变不重绘。
 */
public final class FpsSeriesChartView extends View {

    private static final int COLOR_GRID = 0x1A000000;
    private static final int COLOR_AXIS_TEXT = 0x99000000;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    private float[] values = new float[0];
    private int lineColor = 0xFF2563EB;
    private String unit = "";
    private boolean allowNegative;

    /** 上次绘制的数据指纹，避免同一条曲线反复重绘。 */
    private int lastLength = -1;
    private int lastHash = 0;

    public FpsSeriesChartView(Context context) {
        this(context, null);
    }

    public FpsSeriesChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(density * 1.6f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(density * 0.8f);
        gridPaint.setColor(COLOR_GRID);
        textPaint.setTextSize(density * 9f);
        textPaint.setColor(COLOR_AXIS_TEXT);
        textPaint.setAntiAlias(true);
    }

    /**
     * 设置曲线数据。
     *
     * @param values        每秒一个样本；{@code -1} 表示该秒无采样（断线）
     * @param color         曲线颜色
     * @param unit          Y 轴单位后缀，如 {@code "W"}、{@code "℃"}，空串表示不带
     * @param allowNegative 是否允许负值（功耗用 true，FPS/CPU/温度用 false）
     */
    public void setSeries(float[] values, int color, String unit, boolean allowNegative) {
        float[] src = values == null ? new float[0] : values;
        String unitText = unit == null ? "" : unit;
        int hash = 1;
        for (float v : src) {
            hash = hash * 31 + Float.floatToIntBits(v);
        }
        if (src.length == lastLength && hash == lastHash && color == lineColor
                && unitText.equals(this.unit) && allowNegative == this.allowNegative) {
            // 数据与样式都没变
            return;
        }
        this.values = src;
        this.lineColor = color;
        this.unit = unitText;
        this.allowNegative = allowNegative;
        linePaint.setColor(color);
        fillPaint.setColor(withAlpha(color, 0x22));
        lastLength = src.length;
        lastHash = hash;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        float left = dp * 32f;
        float right = w - dp * 8f;
        float top = dp * 8f;
        float bottom = h - dp * 6f;
        if (right <= left || bottom <= top) {
            return;
        }

        // 计算 Y 轴范围
        float min = allowNegative ? 0f : 0f;
        float max = 0f;
        boolean hasValue = false;
        for (int i = 0; i < values.length; i++) {
            float v = values[i];
            if (v < 0f) {
                continue;
            }
            hasValue = true;
            if (v > max) {
                max = v;
            }
            if (allowNegative && v < min) {
                min = v;
            }
        }
        if (!hasValue) {
            return;
        }
        if (max <= 0f) {
            max = 1f;
        }
        // 上下各留 10% 余量，曲线不贴边
        float pad = (max - min) * 0.1f;
        max += pad;
        if (allowNegative) {
            min -= pad;
        }

        // 网格 + Y 轴刻度
        gridPaint.setColor(COLOR_GRID);
        textPaint.setColor(COLOR_AXIS_TEXT);
        for (int i = 0; i <= 2; i++) {
            float ratio = i / 2f;
            float y = bottom - (bottom - top) * ratio;
            canvas.drawLine(left, y, right, y, gridPaint);
            float labelValue = min + (max - min) * ratio;
            String label = formatAxis(labelValue) + unit;
            canvas.drawText(label, dp * 2f, y + dp * 3f, textPaint);
        }

        // 折线：遇到 -1 断开
        linePath.reset();
        fillPath.reset();
        boolean started = false;
        float firstX = 0f;
        float lastX = 0f;
        for (int i = 0; i < values.length; i++) {
            float v = values[i];
            if (v < 0f) {
                started = false;
                continue;
            }
            float x = values.length <= 1
                    ? left
                    : left + (right - left) * i / (float) (values.length - 1);
            float y = bottom - (bottom - top) * (v - min) / (max - min);
            if (!started) {
                linePath.moveTo(x, y);
                firstX = x;
                started = true;
            } else {
                linePath.lineTo(x, y);
            }
            lastX = x;
        }
        canvas.drawPath(linePath, linePaint);

        // 曲线下方淡填充（只在正区间，负值曲线不填，避免遮挡）
        if (!allowNegative && lastX > firstX) {
            fillPath.set(linePath);
            fillPath.lineTo(lastX, bottom);
            fillPath.lineTo(firstX, bottom);
            fillPath.close();
            canvas.drawPath(fillPath, fillPaint);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static String formatAxis(float value) {
        if (Math.abs(value) >= 100f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(java.util.Locale.US, "%.1f", value);
    }
}
