package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.aurora.app.R;

/**
 * 悬浮窗工具图标（Canvas 自绘）。
 *
 * <p>只有三种字形，够进程悬浮窗的标题栏与迷你条使用：
 * <ul>
 *   <li>{@link #KIND_PIN} 图钉：外部 SVG 转成的 {@code R.drawable.ic_tool_pin}
 *       （VectorDrawable），{@link #setActive} 为 true 时 {@code setTint} 成主题绿
 *       （表示「已钉住」），否则白 90%；</li>
 *   <li>{@link #KIND_SHRINK} 四角向内箭头：让悬浮窗变小；</li>
 *   <li>{@link #KIND_EXPAND} 四角向外箭头：把迷你条展开成完整列表。</li>
 * </ul>
 *
 * <p>缩小/扩大用 Canvas 直接画线：悬浮窗跑在别的应用之上，系统字体的符号覆盖不可靠，
 * 自绘几何图形在各 ROM 上表现一致，也不会被系统的图标主题改写。图钉是设计给定的矢量稿，
 * 转成 VectorDrawable 后由系统渲染，曲线精度比手写 Path 更高。
 */
public final class ToolIconView extends View {

    /** 图钉。 */
    public static final int KIND_PIN = 0;
    /** 缩小（四角向内）。 */
    public static final int KIND_SHRINK = 1;
    /** 扩大（四角向外）。 */
    public static final int KIND_EXPAND = 2;

    /** 常态图标色（白 90%）。 */
    private static final int COLOR_NORMAL = 0xE6FFFFFF;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 图钉的矢量资源（外部 SVG 转换）；缩小/扩大为 null。 */
    private final Drawable pinDrawable;

    private boolean active;

    public ToolIconView(Context context, int kind) {
        super(context);
        this.kind = kind;
        paint.setStrokeWidth(dp(1.4f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(COLOR_NORMAL);
        pinDrawable = kind == KIND_PIN ? context.getDrawable(R.drawable.ic_tool_pin) : null;
        applyPinTint();
    }

    /** 图钉的「已钉住」高亮；其它两种图标忽略。 */
    public void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        applyPinTint();
        invalidate();
    }

    public boolean isActive() {
        return active;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (kind == KIND_PIN) {
            drawPinDrawable(canvas);
            return;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        // 半尺寸基准：图标整体约占控件的 52%，四周留出点击余量
        float s = Math.min(getWidth(), getHeight()) * 0.26f;
        paint.setColor(COLOR_NORMAL);
        paint.setStyle(Paint.Style.STROKE);
        drawCorners(canvas, cx, cy, s, kind == KIND_SHRINK);
    }

    /** 图钉：直接绘制 VectorDrawable，按控件短边的 92% 居中铺开。 */
    private void drawPinDrawable(Canvas canvas) {
        if (pinDrawable == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        float size = Math.min(w, h) * 0.92f;
        int left = Math.round((w - size) / 2f);
        int top = Math.round((h - size) / 2f);
        int side = Math.round(size);
        pinDrawable.setBounds(left, top, left + side, top + side);
        pinDrawable.draw(canvas);
    }

    /** 图钉颜色：未钉住白 90%，已钉住主题绿（只对图钉生效）。 */
    private void applyPinTint() {
        if (pinDrawable != null) {
            pinDrawable.setTint(active ? OverlayWindowStyle.MONITOR_LEVEL_NORMAL : COLOR_NORMAL);
        }
    }

    /**
     * 四角箭头：{@code inward=true} 箭头指向中心（缩小），false 指向四角（扩大）。
     * 每个角由「一条对角线 + 尖端两条斜边」构成，共 12 条线段，无填充。
     */
    private void drawCorners(Canvas canvas, float cx, float cy, float s, boolean inward) {
        float far = s;
        float near = s * 0.30f;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                float cornerX = cx + sx * far;
                float cornerY = cy + sy * far;
                float innerX = cx + sx * near;
                float innerY = cy + sy * near;
                if (inward) {
                    canvas.drawLine(cornerX, cornerY, innerX, innerY, paint);
                    drawArrowHead(canvas, innerX, innerY,
                            (float) Math.atan2(innerY - cornerY, innerX - cornerX), s * 0.42f);
                } else {
                    canvas.drawLine(innerX, innerY, cornerX, cornerY, paint);
                    drawArrowHead(canvas, cornerX, cornerY,
                            (float) Math.atan2(cornerY - innerY, cornerX - innerX), s * 0.42f);
                }
            }
        }
    }

    /** 在 (x,y) 处按 angle 方向画两条回折斜线，形成箭头尖。 */
    private void drawArrowHead(Canvas canvas, float x, float y, float angle, float length) {
        for (int k = -1; k <= 1; k += 2) {
            double back = angle + Math.PI - k * Math.PI / 5.0;
            canvas.drawLine(x, y,
                    x + (float) (Math.cos(back) * length),
                    y + (float) (Math.sin(back) * length),
                    paint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
