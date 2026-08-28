package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 悬浮窗抽象基类：
 * 封装所有监视窗口共有的能力——
 * <ul>
 *   <li>窗口创建/销毁（WindowManager addView / removeView）；</li>
 *   <li>定时刷新循环：<b>后台线程采集数据（{@link #collectData()}）→ 主线程更新 UI（{@link #refreshContent()}）</b>，
 *       避免 /proc、/sys 等文件 IO 阻塞主线程；</li>
 *   <li>触摸拖动（按住窗口任意位置即可移动，松手停在原地）；</li>
 *   <li>单击 {@link #onSingleTap()} / 双击 {@link #onDoubleTap()} 手势回调；</li>
 *   <li>内容平滑切换 {@link #swapContent(View, boolean)}（折叠/展开动画，带防竞态保护）；</li>
 *   <li>多窗口堆叠的默认初始位置（按 MonitorType 顺序错开）。</li>
 * </ul>
 *
 * <p>子类实现约定：
 * {@link #buildContentView()} 构建窗口内容视图、
 * {@link #collectData()} 在后台线程做耗时 IO（可选覆写）、
 * {@link #refreshContent()} 在主线程只更新 UI、
 * 可选覆写 {@link #getRefreshIntervalMs()} 调整刷新周期。
 *
 * <p>生命周期由 {@link OverlayWindowManager} 驱动：
 * 开关打开 → {@link #show()}；开关关闭 → {@link #hide()}。
 */
public abstract class OverlayWindow {

    /** 所有窗口共享的单线程采集池：串行执行，避免并发 IO 抢占。 */
    private static final ExecutorService DATA_EXECUTOR = Executors.newSingleThreadExecutor();

    protected final Context context;
    protected final WindowManager windowManager;
    protected final MonitorType type;

    /** 当前挂到 WindowManager 上的内容视图；未显示时为 null。 */
    private View rootView;
    private final WindowManager.LayoutParams params;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private boolean running = false;

    // ─── 拖动状态 ───
    private final int touchSlop;
    private float downRawX, downRawY;
    private float windowStartX, windowStartY;
    private boolean dragging = false;

    /** 手势识别器：区分单击（切换状态）与双击（关闭窗口）。 */
    private final GestureDetector gestureDetector;

    protected OverlayWindow(Context context, MonitorType type) {
        this.context = context;
        this.type = type;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // 显式指定外层方法，避免与 SimpleOnGestureListener 的同名方法解析混淆
                OverlayWindow.this.onSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                OverlayWindow.this.onDoubleTap();
                return true;
            }
        });
        this.params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
    }

    /** API 26+ 使用 TYPE_APPLICATION_OVERLAY，旧版本回退 TYPE_PHONE（都需要悬浮窗权限）。 */
    private static int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /**
     * 显示悬浮窗：构建内容视图 → 添加到 WindowManager → 启动定时刷新。
     * 重复调用会被忽略（幂等）。
     */
    public final void show() {
        if (running) {
            return;
        }
        running = true;
        rootView = buildContentView();
        // 初始位置：从屏幕左上角区域开始，按类型顺序向下错开，避免多窗口完全重叠
        params.x = OverlayWindowStyle.dpToPx(context, 16);
        params.y = OverlayWindowStyle.dpToPx(context, 90 + type.ordinal() * 56);
        installDrag(rootView);
        windowManager.addView(rootView, params);
        onShown();
        refreshHandler.removeCallbacks(refreshTask);
        refreshHandler.post(refreshTask);
    }

    /**
     * 销毁悬浮窗：停止刷新 → 取消进行中的动画 → 从 WindowManager 移除视图。
     * 重复调用会被忽略（幂等）。
     */
    public final void hide() {
        if (!running) {
            return;
        }
        running = false;
        refreshHandler.removeCallbacks(refreshTask);
        if (rootView != null) {
            // 取消动画，避免 withEndAction 在窗口销毁后仍操作已移除的视图
            rootView.animate().cancel();
            rootView.clearAnimation();
        }
        onHidden();
        if (rootView != null) {
            try {
                windowManager.removeView(rootView);
            } catch (Exception ignored) {
                // 窗口可能已被系统移除（如权限被收回），忽略
            }
            rootView = null;
        }
    }

    /** 当前是否显示中。 */
    public final boolean isRunning() {
        return running;
    }

    /** 子类可覆写：窗口显示后做额外初始化（如启动 FpsMonitor）。 */
    protected void onShown() {
    }

    /** 子类可覆写：窗口销毁前做清理（如停止 FpsMonitor、清空采样基线）。 */
    protected void onHidden() {
    }

    /** 子类必须实现：构建窗口内容视图。 */
    protected abstract View buildContentView();

    /**
     * 子类可覆写：在<b>后台线程</b>采集数据（读 /proc、/sys 等文件 IO 放这里），
     * 结果存入字段，供 {@link #refreshContent()} 主线程读取更新 UI。
     * 默认空实现。注意：此方法与 refreshContent 不在同一线程，字段需按简单值处理。
     */
    protected void collectData() {
    }

    /** 子类必须实现：主线程刷新 UI（只做 setText / setProgress 等轻量操作）。 */
    protected abstract void refreshContent();

    /** 子类可覆写：刷新周期（毫秒），默认 1s。 */
    protected long getRefreshIntervalMs() {
        return 1000L;
    }

    // ─── 定时刷新循环：后台采集 → 主线程更新 UI ───
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            // 后台线程执行文件 IO，不阻塞主线程
            DATA_EXECUTOR.execute(() -> {
                if (!running) {
                    return;
                }
                try {
                    collectData();
                } catch (Exception ignored) {
                    // 单条采集失败不影响后续刷新
                }
                refreshHandler.post(() -> {
                    if (!running) {
                        return;
                    }
                    refreshContent();
                    refreshHandler.postDelayed(refreshTask, getRefreshIntervalMs());
                });
            });
        }
    };

    /**
     * 给窗口内容视图安装触摸处理：
     * <ul>
     *   <li>手势识别：单击触发 {@link #onSingleTap()}（切换折叠/展开），
     *       双击触发 {@link #onDoubleTap()}（关闭窗口）；</li>
     *   <li>拖动：按住移动超过 touchSlop 即进入拖动，松手停留在当前位置。</li>
     * </ul>
     * 拖动期间手势识别器会自动判定为 scroll，不会误触发单击/双击。
     */
    private void installDrag(View view) {
        view.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    windowStartX = params.x;
                    windowStartY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        dragging = true;
                    }
                    if (dragging && rootView != null) {
                        params.x = (int) (windowStartX + dx);
                        params.y = (int) (windowStartY + dy);
                        try {
                            windowManager.updateViewLayout(rootView, params);
                        } catch (Exception ignored) {
                            // 拖动过程中窗口可能被销毁，忽略
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        });
    }

    /** 子类可覆写：单击窗口（用于切换折叠/展开）。 */
    protected void onSingleTap() {
    }

    /** 子类可覆写：双击窗口（用于关闭销毁）。 */
    protected void onDoubleTap() {
    }

    /**
     * 平滑替换窗口内容（用于折叠/展开切换）：
     * 旧内容淡出缩小 → 替换为新视图 → 重新测量窗口尺寸 → 新内容淡入放大。
     *
     * <p>防竞态保护：
     * <ul>
     *   <li>切换前取消上一次动画，防止快速连点导致回调堆积互相踩；</li>
     *   <li>动画回调中检查窗口是否仍存活（running + rootView 存在），
     *       且对 updateViewLayout 做异常保护 —— 避免窗口销毁后操作已移除视图导致崩溃。</li>
     * </ul>
     * 注意：调用方需自行维护状态（如展开/折叠标记与持久化）。
     */
    protected final void swapContent(View newContent, boolean animate) {
        if (!(rootView instanceof ViewGroup)) {
            return;
        }
        final ViewGroup container = (ViewGroup) rootView;
        Runnable doSwap = () -> {
            // 动画结束时窗口可能已被双击关闭，直接放弃本次切换
            if (!running || rootView == null) {
                return;
            }
            try {
                container.removeAllViews();
                container.addView(newContent);
                // WRAP_CONTENT 窗口：内容变化后需手动刷新窗口尺寸
                windowManager.updateViewLayout(rootView, params);
            } catch (Exception ignored) {
                // 窗口已被系统移除等情况，忽略
            }
            if (animate && running) {
                rootView.setAlpha(0f);
                rootView.setScaleX(0.95f);
                rootView.setScaleY(0.95f);
                rootView.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(100)
                        .start();
            }
        };
        if (animate && running) {
            // 取消上一次动画，避免 withEndAction 堆积
            rootView.animate().cancel();
            rootView.animate()
                    .alpha(0f).scaleX(0.95f).scaleY(0.95f)
                    .setDuration(60)
                    .withEndAction(doSwap)
                    .start();
        } else {
            doSwap.run();
        }
    }
}
