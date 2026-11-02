package com.aurora.app.ui.fps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 帧率曲线图（自绘，无第三方依赖）：
 * <ul>
 *   <li>折线为 FPS 序列，Y 轴自动缩放（下限 0，上限取数据最大值向上取整到 30 的倍数，
 *       至少 60——保证 60fps 参考线始终可见）；</li>
 *   <li>60/120 fps 画虚线参考线并标注；</li>
 *   <li>数据不足 2 个点时只画参考线。</li>
 * </ul>
 *
 * <p>刷新策略：仅在外部调用 {@link #setData(float[])} 时重绘（invalidate），
 * 无动画、无定时器，开销可控。
 */
public class FpsChartView extends View {

    private static final int COLOR_LINE = 0xFF2563EB;
    private static final int COLOR_GRID = 0x1A1F2937;
    private static final int COLOR_REF_TEXT = 0x8C1F2937;
    private static final int COLOR_FILL = 0x142563EB;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint refPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint refTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] data = new float[0];

    /**
     * 复用的两条路径（折线与填充）。
     *
     * <p>不能每帧 {@code new Path()}：这个控件是帧率记录页的主体，采样期间持续
     * invalidate，60fps 下每秒就是 120 个 Path 对象。虽然它们很快死掉，但会在
     * 绘制阶段制造 GC 压力 —— 而绘制阶段正是最不该出现分配的地方（GC 一停就是掉帧，
     * 帧率图上会直接反映成凹陷，等于测量工具污染了被测量的东西）。
     * {@code Path.reset()} 保留已分配的点缓冲区，比重新 new 便宜得多。
     */
    private final Path reuseLine = new Path();
    private final Path reuseFill = new Path();

    public FpsChartView(Context context) {
        super(context);
        init();
    }

    public FpsChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FpsChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1.5f));
        linePaint.setColor(COLOR_LINE);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(COLOR_FILL);

        refPaint.setStyle(Paint.Style.STROKE);
        refPaint.setStrokeWidth(dp(0.8f));
        refPaint.setColor(COLOR_GRID);
        refPaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));

        refTextPaint.setTextSize(dp(9));
        refTextPaint.setColor(COLOR_REF_TEXT);
    }

    /** 更新曲线数据并重绘。 */
    public void setData(float[] series) {
        this.data = series == null ? new float[0] : series;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) {
            return;
        }
        float top = getPaddingTop() + refTextPaint.getTextSize();
        float bottom = getPaddingTop() + h;
        float chartH = bottom - top;
        if (chartH <= 0) {
            return;
        }

        // Y 上限：数据最大值向上取整到 30 的倍数，至少 60
        float maxVal = 60f;
        for (float v : data) {
            if (v > maxVal) {
                maxVal = v;
            }
        }
        float yMax = (float) (Math.ceil(maxVal / 30f) * 30f);

        // 参考线：60fps（必画）与 120fps（在范围内才画）
        drawRefLine(canvas, 60f, yMax, top, bottom, w, "60");
        if (yMax >= 120f) {
            drawRefLine(canvas, 120f, yMax, top, bottom, w, "120");
        }

        if (data.length < 2) {
            return;
        }

        // 曲线路径（复用同两个 Path，不每帧 new —— 见 reuseLine / reuseFill 的说明）
        Path line = reuseLine;
        Path fill = reuseFill;
        line.reset();
        fill.reset();
        float stepX = w / (data.length - 1);
        for (int i = 0; i < data.length; i++) {
            float x = getPaddingLeft() + i * stepX;
            float y = bottom - (data[i] / yMax) * chartH;
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, bottom);
                fill.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        fill.lineTo(getPaddingLeft() + w, bottom);
        fill.close();
        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);
    }

    /** 画一条 FPS 参考虚线并在右端标注数值。 */
    private void drawRefLine(Canvas canvas, float fps, float yMax,
                             float top, float bottom, float w, String label) {
        float y = bottom - (fps / yMax) * (bottom - top);
        if (y < top - 1f || y > bottom) {
            return;
        }
        canvas.drawLine(getPaddingLeft(), y, getPaddingLeft() + w, y, refPaint);
        canvas.drawText(label, getPaddingLeft() + w - refTextPaint.measureText(label) - dp(2),
                y - dp(2), refTextPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
