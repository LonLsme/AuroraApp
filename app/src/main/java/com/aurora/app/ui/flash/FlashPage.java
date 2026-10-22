package com.aurora.app.ui.flash;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashDevice;
import com.aurora.app.core.flash.FlashRuntime;
import com.aurora.app.core.flash.FlashToolchain;

/**
 * 「刷机」页（底栏 tab 1）的绑定与交互逻辑。
 *
 * <h3>页面结构（与 page_flash.xml 一一对应）</h3>
 * <ul>
 *   <li>头部：左标题 + 右上角<b>设备状态下拉</b>（10 个槽位，见
 *       {@link FlashDevice#SLOT_NAMES}）；顶栏那一行在本页整行隐藏，
 *       由 {@code MainActivity.updateToolbarForTab()} 负责。</li>
 *   <li>主体：11 张等宽等高卡片，顺序固定。前 8 张「刷写设备本身」—— 小米线刷 / 欧加真线刷 /
 *       刷入KernelSU / 高通QDL / 刷写分区 / 设备重启 / ADB Sideload 卡刷 / 执行命令；
 *       之后是「应用工具」分组，3 张「操作设备上的应用」—— 应用卸载冻结 / 免root激活 / 应用安装。</li>
 * </ul>
 *
 * <h3>本页已经不跑命令了</h3>
 * 十张卡片各自进了独立竖屏页，命令执行与输出都搬到了那边。
 * 所以本类从「一个会跑命令的页面」退化成了<b>纯入口</b>：探测设备、维护右上角下拉、
 * 把点击翻成一次 {@code startActivity}。
 *
 * <p>页面职责边界：本类只负责「把用户操作翻译成一次跳转」，设备探测在 {@link FlashDevice}，
 * 工具链在 {@link FlashToolchain}。
 *
 * <h3>为什么工具链在这里准备</h3>
 * 首次解压要几百毫秒。放在入口页静默做完，用户点进任一子页时工具链已经就绪，
 * 不必在子页里再等一次（子页自己也会兜底准备，见各处 {@code FlashToolchain} 调用）。
 */
public final class FlashPage {

    private final Activity activity;
    private final Context appContext;

    // ── 头部：设备状态下拉 ──
    private TextView tvDeviceSummary;
    private View btnDevices;
    private PopupWindow devicePopup;
    /**
     * 下拉弹窗里 10 行的状态 TextView，下标与 {@link FlashDevice#SLOT_NAMES} 对齐。
     *
     * <p>弹窗内容是 inflate 出来的（同一个 item 布局重复 10 次，id 重复出现），
     * 所以只能持有「每行 inflate 返回值」里的引用 —— 对外层容器做 findViewById
     * 永远只会拿到第一行。
     */
    private final TextView[] slotStatusViews = new TextView[FlashDevice.SLOT_NAMES.length];

    // ── 8 张刷机卡 ──
    private View cardMi;
    private View cardOplus;
    private View cardKernelSu;
    private View cardQdl;
    private View cardPartition;
    private View cardReboot;
    private View cardSideload;
    private View cardExec;

    // ── 「应用工具」分组：3 张设备应用卡 ──
    private View cardAppUninstall;
    private View cardAppActivate;
    private View cardAppInstall;

    /** 最近一次探测结果；null 表示尚未探测成功。 */
    private FlashDevice.State lastState;

    // ───────────────── 自动刷新 ─────────────────

    /**
     * 页面可见时的轮询周期。
     *
     * <p>5s 是个折中：插上设备后最多 5 秒内出头（肉眼可感），又不至于频繁 fork su。
     * 一次探测是一条 su 命令（{@code id -u} + {@code adb devices} + {@code fastboot devices}
     * + sysfs 扫 USB ID），实测几百毫秒。
     */
    private static final long AUTO_REFRESH_PERIOD_MS = 5000L;

    /** 两次探测之间的最小间隔 —— 切 tab / 展开下拉 / USB 插拔可能在同一瞬间连续触发。 */
    private static final long MIN_DETECT_GAP_MS = 1500L;

    /**
     * USB 插拔后先等一拍再探测。
     *
     * <p>广播在「总线枚举到设备」时就发了，此时 {@code adb devices} / {@code fastboot devices}
     * 往往还没把它列出来，立刻探测只会拿到一份「未连接设备」。
     */
    private static final long USB_SETTLE_DELAY_MS = 900L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 轮询体（在主线程自循环）。 */
    private final Runnable autoRefreshTask = new Runnable() {
        @Override
        public void run() {
            refreshDeviceAuto();
            if (isVisible()) {
                handler.postDelayed(this, AUTO_REFRESH_PERIOD_MS);
            }
        }
    };

    /** ViewPager2 是否停在本页。 */
    private boolean pageVisible;
    /** 宿主 Activity 是否在前台（切到刷机子页时它会 onPause）。 */
    private boolean activityResumed;
    /** 是否有一次探测在途。只在主线程读写，无需同步。 */
    private boolean detecting;
    /** 最近一次探测的起点（{@link SystemClock#uptimeMillis()}），用于节流。 */
    private long lastDetectStartedAt;

    /** USB 插拔监听：插上/拔下的瞬间就刷新，不必等下一次轮询。 */
    private BroadcastReceiver usbWatcher;

    public FlashPage(Activity activity) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
    }

    // ───────────────── 绑定 ─────────────────

    /** 建立视图引用、接好 11 张卡片与下拉入口、开始准备工具链。幂等。 */
    public void bind() {
        tvDeviceSummary = activity.findViewById(R.id.tvFlashDeviceSummary);
        btnDevices = activity.findViewById(R.id.btnFlashDevices);

        cardMi = activity.findViewById(R.id.cardFlashMi);
        cardOplus = activity.findViewById(R.id.cardFlashOplus);
        cardKernelSu = activity.findViewById(R.id.cardFlashKernelSu);
        cardQdl = activity.findViewById(R.id.cardFlashQdl);
        cardPartition = activity.findViewById(R.id.cardFlashPartition);
        cardReboot = activity.findViewById(R.id.cardFlashReboot);
        cardSideload = activity.findViewById(R.id.cardFlashSideload);
        cardExec = activity.findViewById(R.id.cardFlashExec);

        cardAppUninstall = activity.findViewById(R.id.cardAppUninstall);
        cardAppActivate = activity.findViewById(R.id.cardAppActivate);
        cardAppInstall = activity.findViewById(R.id.cardAppInstall);

        if (btnDevices == null) {
            // 布局尚未挂载（理论上不会发生：本方法在 setContentView 之后调用）。
            return;
        }

        bindActions();
        renderDeviceSlots(null);
        setSummaryText(FlashDevice.NOT_CONNECTED);

        prepareToolchain();
        registerUsbWatcher();
    }

    private void bindActions() {
        setOnClick(btnDevices, this::showDevicePopup);

        setOnClick(cardMi, () -> startPage(MiFlashActivity.class));
        setOnClick(cardOplus, () -> startPage(OplusFlashActivity.class));
        setOnClick(cardKernelSu, () -> startPage(KernelSuActivity.class));
        setOnClick(cardQdl, () -> startPage(QdlActivity.class));
        setOnClick(cardPartition, () -> startPage(PartitionFlashActivity.class));
        setOnClick(cardReboot, () -> startPage(RebootActivity.class));
        setOnClick(cardSideload, () -> startPage(SideloadActivity.class));
        setOnClick(cardExec, () -> startPage(ExecCommandActivity.class));

        // 「应用工具」分组：操作的都是连着的那台设备上的应用，命令经内置 adb 打到目标机。
        setOnClick(cardAppUninstall, () -> startPage(AppUninstallActivity.class));
        setOnClick(cardAppActivate, () -> startPage(ActivateActivity.class));
        setOnClick(cardAppInstall, () -> startPage(InstallActivity.class));
    }

    /** 进一张卡片的独立竖屏页。 */
    private void startPage(Class<?> target) {
        activity.startActivity(new Intent(activity, target));
    }

    private static void setOnClick(View view, Runnable action) {
        if (view != null) {
            view.setOnClickListener(v -> action.run());
        }
    }

    // ───────────────── 工具链 ─────────────────

    /**
     * 静默准备工具链。
     *
     * <p>首次运行要解压 7.4MB（几百毫秒），正常路径不打扰用户；
     * 只有失败才提示 —— 那时刷机功能真的不可用，必须让用户知道。
     */
    private void prepareToolchain() {
        if (FlashToolchain.isReady(appContext)) {
            return;
        }
        new Thread(() -> {
            if (!FlashToolchain.ensureReady(appContext)) {
                toast("刷机工具链解压失败，刷机功能不可用");
            }
        }, "aurora-flash-toolchain").start();
    }

    // ───────────────── 设备状态下拉 ─────────────────

    /**
     * 弹出设备状态列表（右上角）。
     *
     * <p>弹窗只在第一次弹出时构建，之后复用同一个实例、只改文字 ——
     * 反复 show/dismiss 时重建 10 行是纯浪费。
     *
     * <p>打开这一下分两步：<b>先用最近一次结果立刻弹出</b>（下拉必须即时响应，不能等探测），
     * <b>再在后台追一次探测</b>。追完若弹窗还开着，{@code slotStatusViews} 是同一批 TextView 引用，
     * 文字会当场变成最新值 —— 用户盯着看就能看到「未连接 → 已连接」自己跳过来。
     */
    private void showDevicePopup() {
        if (btnDevices == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (devicePopup == null) {
            devicePopup = buildDevicePopup();
        }
        renderDeviceSlots(lastState);
        // gravity END：弹窗右边缘与触发器右边缘对齐（右上角下拉的常规观感）
        devicePopup.showAsDropDown(btnDevices, 0, dp(6), Gravity.END);
        refreshDeviceAuto();
    }

    private PopupWindow buildDevicePopup() {
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < FlashDevice.SLOT_NAMES.length; i++) {
            View row = activity.getLayoutInflater()
                    .inflate(R.layout.item_flash_device_status, list, false);
            TextView name = row.findViewById(R.id.tvSlotName);
            if (name != null) {
                name.setText(FlashDevice.SLOT_NAMES[i]);
            }
            slotStatusViews[i] = row.findViewById(R.id.tvSlotStatus);
            list.addView(row);
        }

        PopupWindow popup = new PopupWindow(list,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                true);
        // 非 null 背景是「点外部关闭」的前提；圆角由该 drawable 提供（列表行自身透明）
        popup.setBackgroundDrawable(
                ContextCompat.getDrawable(activity, R.drawable.bg_flash_device_popup));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        return popup;
    }

    /** 关闭设备下拉弹窗。切走 tab 或页面不可见时调用，避免 PopupWindow 泄漏。 */
    public void dismissPopup() {
        if (devicePopup != null && devicePopup.isShowing()) {
            devicePopup.dismiss();
        }
    }

    /** 按槽位顺序把最新状态铺到弹窗的 10 行上（弹窗还没建时是空操作）。 */
    private void renderDeviceSlots(FlashDevice.State state) {
        String[] statuses = FlashDevice.slotStatuses(state);
        for (int i = 0; i < slotStatusViews.length; i++) {
            TextView view = slotStatusViews[i];
            if (view != null) {
                view.setText(statuses[i]);
            }
        }
    }

    private void setSummaryText(String text) {
        if (tvDeviceSummary != null) {
            tvDeviceSummary.setText(text);
        }
    }

    // ───────────────── 生命周期 / 自动刷新 ─────────────────

    /**
     * 页面生命周期：{@code ViewPager2} 切到本页 / 切走。
     *
     * <p><b>「可见」= 停在本页 且 Activity 在前台</b>，两个条件缺一不可。
     * 不可见就停掉轮询：既省电，也避免切进刷机子页后主界面还在背后跑 su
     * —— 那会跟子页抢 {@link FlashRuntime} 的单任务闸门。
     */
    public void onTabShown() {
        pageVisible = true;
        syncAutoRefresh();
    }

    /** 切走本页：收回下拉（PopupWindow 挂在窗口上）并停掉轮询。 */
    public void onTabHidden() {
        pageVisible = false;
        dismissPopup();
        syncAutoRefresh();
    }

    /** Activity 回到前台（含从刷机子页返回 —— 那时设备模式多半已经变了）。 */
    public void onActivityResumed() {
        activityResumed = true;
        syncAutoRefresh();
    }

    public void onActivityPaused() {
        activityResumed = false;
        // 弹窗不随 Activity 生命周期自动关闭，这里显式收回，避免窗口泄漏。
        dismissPopup();
        syncAutoRefresh();
    }

    /** Activity 销毁：注销监听并清掉所有待执行回调（轮询 + USB 插拔的延迟探测）。 */
    public void release() {
        dismissPopup();
        unregisterUsbWatcher();
        handler.removeCallbacksAndMessages(null);
    }

    private boolean isVisible() {
        return pageVisible && activityResumed
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    /**
     * 按可见性同步轮询：可见则「立刻追一次 + 起周期」，不可见则停掉。
     *
     * <p>为什么还要「立刻一次」：刚切到本页、或从刷机子页返回时，用户最关心的就是
     * 「设备现在是什么状态」，让他先盯 5 秒空白说不过去。
     */
    private void syncAutoRefresh() {
        handler.removeCallbacks(autoRefreshTask);
        if (!isVisible()) {
            return;
        }
        refreshDeviceAuto();
        handler.postDelayed(autoRefreshTask, AUTO_REFRESH_PERIOD_MS);
    }

    /**
     * 自动探测（轮询 / USB 插拔 / 打开下拉）。
     *
     * <p>默认<b>静默</b>：不显示「正在检测…」、不置灰卡片、失败也不弹 toast ——
     * 轮询每 5 秒来一次，任何一处打扰都会变成干扰（按钮每 5 秒闪一下半透明尤其糟）。
     *
     * <p>唯一的例外是<b>会话内第一次</b>：那时界面上还是「未连接设备」这个初始文案，
     * 给一次「正在检测…」能让用户知道它在干活；失败也只提示这一次，之后的轮询一律静默
     * （否则没有 root 的机器会每 5 秒弹一次 toast）。
     *
     * <p>结果只在<b>成功</b>时替换显示，失败保持上一次的样子：用户看到的文字永远是
     * 「最新一次有效探测」的结果，不会在「未连接」和「ADB 已连接」之间来回跳。
     */
    private void refreshDeviceAuto() {
        if (!isVisible() || detecting) {
            return;
        }
        if (SystemClock.uptimeMillis() - lastDetectStartedAt < MIN_DETECT_GAP_MS) {
            return;
        }
        // lastDetectStartedAt == 0 表示本次会话还没探测过 —— 只有这一回带进度与失败提示。
        detect(lastDetectStartedAt == 0L);
    }

    /**
     * 执行一次设备探测。
     *
     * @param showProgress 显示「正在检测…」、置灰 10 张卡与下拉、失败给提示；{@code false} 为静默探测
     *                     （两者的取舍见 {@link #refreshDeviceAuto()}）
     */
    private void detect(final boolean showProgress) {
        if (tvDeviceSummary == null) {
            return;
        }
        if (FlashRuntime.get().isBusy()) {
            // 刷机中途重启 adb / fastboot 进程本身是危险操作，直接拒绝。
            // 静默路径什么都不做 —— 正在刷机时本来就该等任务结束再看状态。
            if (showProgress) {
                toast("有任务正在执行，请先等待结束或终止");
            }
            return;
        }

        detecting = true;
        lastDetectStartedAt = SystemClock.uptimeMillis();
        if (showProgress) {
            setSummaryText("正在检测…");
            setBusy(true);
        }

        FlashDevice.detect(appContext, state -> post(() -> {
            detecting = false;
            if (showProgress) {
                setBusy(false);
            }
            if (state == null) {
                if (showProgress) {
                    setSummaryText(FlashDevice.NOT_CONNECTED);
                    renderDeviceSlots(null);
                    toast("检测未完成");
                }
                return;
            }
            lastState = state;
            setSummaryText(state.summary());
            renderDeviceSlots(state);
        }));
    }

    // ───────────────── USB 插拔监听 ─────────────────

    /**
     * 注册 USB 插拔监听。
     *
     * <p>只靠轮询最多要等 5 秒；而插拔是「用户手上正在做的动作」，晚 5 秒就很像坏了。
     * 广播一到就延后 {@link #USB_SETTLE_DELAY_MS} 追一次探测 —— 因为广播是在
     * 「总线枚举到设备」时发的，那一刻 {@code adb devices} / {@code fastboot devices}
     * 往往还没把它列出来，立刻探测只会拿到一份「未连接设备」。
     *
     * <p>{@code RECEIVER_NOT_EXPORTED}：这几个是系统广播，不需要对外暴露；
     * 而且 targetSdk 34+ 必须显式声明方向，否则注册直接抛异常。
     */
    private void registerUsbWatcher() {
        if (usbWatcher != null) {
            return;
        }
        usbWatcher = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handler.postDelayed(FlashPage.this::refreshDeviceAuto, USB_SETTLE_DELAY_MS);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        try {
            ContextCompat.registerReceiver(activity, usbWatcher, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (RuntimeException e) {
            // 个别 ROM 对带 flag 的重载有兼容问题：退回两参重载（对系统广播仍然可用）。
            // 再失败就放弃监听 —— 轮询还在，只是慢几秒，不致命。
            try {
                activity.registerReceiver(usbWatcher, filter);
            } catch (RuntimeException ignored) {
                usbWatcher = null;
            }
        }
    }

    private void unregisterUsbWatcher() {
        if (usbWatcher == null) {
            return;
        }
        try {
            activity.unregisterReceiver(usbWatcher);
        } catch (IllegalArgumentException ignored) {
            // 上面回退也失败时（压根没注册上），这里忽略即可。
        }
        usbWatcher = null;
    }

    // ───────────────── 忙碌态 ─────────────────

    /**
     * 统一设置「忙碌」态。
     *
     * <p>忙碌时禁用会打断刷机流程的入口（下拉检测 + 10 张卡片）。
     */
    private void setBusy(boolean busy) {
        for (View view : new View[]{btnDevices, cardMi, cardOplus, cardKernelSu, cardQdl,
                cardPartition, cardReboot, cardSideload, cardExec,
                cardAppUninstall, cardAppActivate, cardAppInstall}) {
            if (view != null) {
                view.setEnabled(!busy);
                view.setAlpha(busy ? 0.5f : 1f);
            }
        }
    }

    // ───────────────── 工具 ─────────────────

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void post(Runnable action) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                action.run();
            }
        });
    }

    private void toast(String message) {
        post(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }
}
