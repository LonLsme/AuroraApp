package com.aurora.app.ui.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 悬浮窗抽象基类：
 * 封装所有监视窗口共有的能力——
 * <ul>
 *   <li><b>折叠/展开双状态</b>：所有窗口统一为「折叠小窗 ↔ 展开大窗」两种形态，
 *       单击切换（带平滑动画）、双击关闭销毁，状态持久化到 {@link OverlayConfig}；
 *       子类只需实现 {@link #buildCollapsedView()} / {@link #buildExpandedView()} 两套内容；</li>
 *   <li>窗口创建/销毁（WindowManager addView / removeView）；</li>
 *   <li>定时刷新循环：<b>后台线程采集（{@link #collectData()}）→ 主线程更新 UI（{@link #refreshContent()}）</b>；</li>
 *   <li>触摸拖动：拖动期间用 View translation 跟手（GPU 合成层移动，不触发窗口重布局），
 *       松手才一次性更新窗口位置 —— 避免高频 updateViewLayout 造成拖动卡顿；</li>
 *   <li>统一浅色可穿透背景 + 深色文字（与负载监视器一致的风格）。</li>
 * </ul>
 *
 * <p>子类实现约定：
 * {@link #buildCollapsedView()} 折叠小窗内容、
 * {@link #buildExpandedView()} 展开大窗内容、
 * {@link #collectData()} 后台线程做耗时 IO（可选覆写）、
 * {@link #refreshContent()} 主线程只更新 UI、
 * {@link #onStateChanged(boolean)} 折叠/展开切换钩子（如 FPS 启停）、
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

    /** 当前是否展开大窗（false = 折叠小窗）；初始值在 show 时从 OverlayConfig 读取。 */
    protected boolean expanded;

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
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT);
        // 参照 vtools FloatMonitor：
        // ① 5 参构造的 gravity 默认是 0，显式设为 TOP|LEFT 后 x/y 才是「窗口左上角相对屏幕」的坐标，
        //    否则触摸 hit-test 与布局坐标系不一致，拖动会错位/中断；
        // ② FLAG_FULLSCREEN 让窗口布局覆盖整个屏幕（含状态栏区域），坐标系与触摸区域完全对齐。
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        // API 28+ 允许窗口内容延伸到刘海/挖孔区域，避免监视窗被强制挤到安全区以下
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    /** API 26+ 使用 TYPE_APPLICATION_OVERLAY，旧版本回退 TYPE_PHONE（都需要悬浮窗权限）。 */
    protected static int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ─── 子类可访问的窗口句柄（记录态切换背景/弹出附属窗口时使用） ───

    /** 当前根视图；未显示时为 null。 */
    protected final View getRootView() {
        return rootView;
    }

    /** 当前窗口 X 坐标（px）。 */
    protected final int getWindowX() {
        return params.x;
    }

    /** 当前窗口 Y 坐标（px）。 */
    protected final int getWindowY() {
        return params.y;
    }

    /** 替换窗口背景 drawable（如 FPS 记录态切换为透明红）。 */
    protected final void setWindowBackground(android.graphics.drawable.Drawable drawable) {
        if (rootView != null) {
            rootView.setBackground(drawable);
        }
    }

    /** 内容变化后刷新窗口尺寸（WRAP_CONTENT 需要手动触发重新测量）。 */
    protected final void relayoutWindow() {
        if (rootView != null) {
            try {
                windowManager.updateViewLayout(rootView, params);
            } catch (Exception ignored) {
            }
        }
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
        applyInitialPosition();
        installDrag(rootView);
        windowManager.addView(rootView, params);
        onShown();
        refreshHandler.removeCallbacks(refreshTask);
        refreshHandler.post(refreshTask);
    }

    /**
     * 确定窗口初始位置：优先使用用户上次拖动后保存的坐标（{@link OverlayConfig#getPositionX}），
     * 没有记录或记录已失效时回退默认布局——屏幕左上角起、按类型顺序向下错开，
     * 避免多个窗口完全重叠。
     *
     * <p>保存的坐标会先做可见性校验（{@link #isPositionReachable}）：横竖屏切换、
     * 分辨率或折叠屏形态变化后，旧坐标可能完全落在屏幕之外，此时回退默认布局，
     * 避免窗口「消失」且用户找不到。
     */
    private void applyInitialPosition() {
        Integer savedX = OverlayConfig.getPositionX(context, type);
        Integer savedY = OverlayConfig.getPositionY(context, type);
        if (savedX != null && savedY != null && isPositionReachable(savedX, savedY)) {
            params.x = savedX;
            params.y = savedY;
            return;
        }
        params.x = OverlayWindowStyle.dpToPx(context, 16);
        params.y = OverlayWindowStyle.dpToPx(context, 90 + type.ordinal() * 56);
    }

    /**
     * 坐标是否仍落在用户可达范围内。
     * 窗口带 {@code FLAG_LAYOUT_NO_LIMITS}，允许被拖到屏幕边缘之外（部分露出），
     * 因此这里只排除「完全看不见」的极端情况，而不是要求整个窗口都在屏内。
     */
    private boolean isPositionReachable(int x, int y) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        return x > -width && x < width && y > -height && y < height;
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

    /**
     * 统一构建窗口根视图（子类无需覆写）：
     * 深色胶囊背景（复刻 HoshinoScene 的 shape_corner）+ 按记忆状态加载折叠/展开内容。
     */
    protected final View buildContentView() {
        expanded = OverlayConfig.isExpanded(context, type);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = OverlayWindowStyle.dpToPx(context, 5);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(OverlayWindowStyle.createMonitorBackground(context));
        root.addView(expanded ? buildExpandedView() : buildCollapsedView());
        return root;
    }

    /** 子类必须实现：折叠小窗内容视图。 */
    protected abstract View buildCollapsedView();

    /** 子类必须实现：展开大窗内容视图。 */
    protected abstract View buildExpandedView();

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

    /**
     * 子类可覆写：折叠/展开状态切换后的钩子（如展开态才开启 FPS 统计）。
     *
     * @param nowExpanded 切换后的状态
     */
    protected void onStateChanged(boolean nowExpanded) {
    }

    // ─── 定时刷新循环：后台采集 → 主线程更新 UI ───

    /** 上一轮采集是否仍在进行（防堆积标志）。 */
    private final java.util.concurrent.atomic.AtomicBoolean collecting =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            // 防堆积：上一轮采集尚未结束则跳过本轮，直接排下一次。
            // 否则慢采集（个别 sysfs/root 节点耗时）会让任务在单线程里排队累积，
            // 刷新周期被无限顺延——这就是此前「越用越卡、数据一直刷不出来」的根因。
            if (collecting.compareAndSet(false, true)) {
                DATA_EXECUTOR.execute(() -> {
                    if (running) {
                        try {
                            collectData();
                        } catch (Exception ignored) {
                            // 单条采集失败不影响后续刷新
                        }
                    }
                    refreshHandler.post(() -> {
                        collecting.set(false);
                        if (!running) {
                            return;
                        }
                        refreshContent();
                        scheduleNextRefresh();
                    });
                });
            } else {
                scheduleNextRefresh();
            }
        }
    };

    private void scheduleNextRefresh() {
        refreshHandler.postDelayed(refreshTask, getRefreshIntervalMs());
    }

    // ─── 单击/双击（所有窗口统一交互） ───

    /** 单击：折叠 ↔ 展开，带平滑动画，并记忆状态。 */
    protected void onSingleTap() {
        expanded = !expanded;
        OverlayConfig.setExpanded(context, type, expanded);
        onStateChanged(expanded);
        swapContent(expanded ? buildExpandedView() : buildCollapsedView(), true);
    }

    /** 双击：关闭并销毁本悬浮窗，同时把开关状态同步为关闭。 */
    protected void onDoubleTap() {
        OverlayConfig.setEnabled(context, type, false);
        MonitorOverlayService.stopOverlay(context, type);
    }

    /**
     * 给窗口内容视图安装触摸处理：
     * <ul>
     *   <li>手势识别：单击触发 {@link #onSingleTap()}（切换折叠/展开），
     *       双击触发 {@link #onDoubleTap()}（关闭窗口）；</li>
     *   <li>拖动：拖动期间<b>每次 MOVE 直接 updateViewLayout 更新 params</b>——
     *       WindowManager 的触摸命中区域由 params（实际布局位置）决定，
     *       不随 translation 移动。若用 translation 跟手，手指一旦拖出"按下时窗口
     *       的原始区域"，MOVE/UP 事件就不再派发给窗口，导致拖动中断/窗口停在半路
     *       （表现"只能拖一半屏幕、拖到某处就消失"）。实时提交位置后触摸区域跟随，
     *       拖动完整。悬浮窗都是小窗口，每次 updateViewLayout 开销可忽略。</li>
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
                        // 目标位置先夹紧（窗口视觉上永不飞出屏幕），再实时提交布局位置。
                        float[] clamped = clampPosition(windowStartX + dx, windowStartY + dy);
                        int newX = (int) clamped[0];
                        int newY = (int) clamped[1];
                        if (newX != params.x || newY != params.y) {
                            params.x = newX;
                            params.y = newY;
                            try {
                                windowManager.updateViewLayout(rootView, params);
                            } catch (Exception ignored) {
                                // 窗口可能已被销毁，忽略
                            }
                        }
                        // 位置已通过 params 提交，translation 保持 0（避免视觉与布局位置叠加）
                        rootView.setTranslationX(0f);
                        rootView.setTranslationY(0f);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (dragging && rootView != null) {
                        // 位置已在 MOVE 中实时提交，松手只做持久化
                        try {
                            OverlayConfig.setPosition(context, type, params.x, params.y);
                        } catch (Exception ignored) {
                        }
                    }
                    rootView.setTranslationX(0f);
                    rootView.setTranslationY(0f);
                    dragging = false;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    // 取消：放弃拖动，回到按下前位置
                    rootView.setTranslationX(0f);
                    rootView.setTranslationY(0f);
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        });
    }

    /**
     * 计算坐标夹紧后的值（不修改字段，供拖动实时使用）。
     * 采用真实屏幕尺寸（含状态栏/导航栏），与 WindowManager 的坐标系一致，
     * 避免 {@code getResources().getDisplayMetrics()} 在某些 ROM 上返回
     * 不含系统栏的可用高度，导致窗口被错误地限制在"半屏"内。
     */
    private float[] clampPosition(float x, float y) {
        int screenW;
        int screenH;
        try {
            DisplayMetrics real = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(real);
            screenW = real.widthPixels;
            screenH = real.heightPixels;
        } catch (Exception e) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            screenW = metrics.widthPixels;
            screenH = metrics.heightPixels;
        }
        int margin = OverlayWindowStyle.dpToPx(context, 12);
        int w = rootView != null ? rootView.getWidth() : 0;
        int h = rootView != null ? rootView.getHeight() : 0;
        // 窗口可能尚未布局完成（getWidth/Height 返回 0），用保守的最小尺寸兜底，
        // 否则「-w+margin」会变成正数、把窗口错误地顶出屏幕
        if (w <= 0) {
            w = OverlayWindowStyle.dpToPx(context, 48);
        }
        if (h <= 0) {
            h = OverlayWindowStyle.dpToPx(context, 32);
        }
        // 横轴：左右边缘最多露出 margin
        float cx = Math.max(-w + margin, Math.min(x, screenW - margin));
        // 纵轴：顶/底同理
        float cy = Math.max(-h + margin, Math.min(y, screenH - margin));
        return new float[]{cx, cy};
    }

    /**
     * 平滑替换窗口内容（用于折叠/展开切换）：
     * 旧内容淡出缩小 → 替换为新视图 → 重新测量窗口尺寸 → 新内容淡入放大。
     *
     * <p>防竞态保护：切换前取消上一次动画（防快速连点回调堆积），
     * 动画回调中检查窗口是否仍存活，并对 updateViewLayout 做异常保护。
     */
    protected final void swapContent(View newContent, boolean animate) {        if (!(rootView instanceof ViewGroup)) {
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
