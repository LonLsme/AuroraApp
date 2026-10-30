package com.aurora.app.ui.device;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.aurora.app.R;
import com.aurora.app.core.device.DeviceReader;
import com.aurora.app.core.device.DeviceSnapshot;
import com.aurora.app.core.shell.AdbBridge;
import com.aurora.app.core.shell.ShellManager;
import com.aurora.app.core.shell.ShizukuBridge;
import com.aurora.app.ui.widget.CpuRingGaugeView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import rikka.shizuku.Shizuku;

/**
 * 设备概览页。
 *
 * <h3>权限门控</h3>
 * 页面能不能显示数据，取决于有没有一条能读系统信息的通道，优先级固定：
 * <ol>
 *   <li>{@link ShellManager.Mode#ROOT} —— 有 root 就直接进内容，<b>绝不</b>出现
 *       Shizuku 引导（用户已经拥有更高权限，再引导他去激活一个免 root 工具是荒谬的）；</li>
 *   <li>{@link ShellManager.Mode#SHIZUKU} 或 {@link ShellManager.Mode#WIRELESS_ADB} ——
 *       有一个就进内容；</li>
 *   <li>{@link ShellManager.Mode#NONE} —— 收起设备卡与 CPU 卡，只留授权引导
 *       （{@code cardAuthGuide}）：一句说明 + 两颗并列按钮。</li>
 * </ol>
 * 检测与数据读取全在后台单线程，主线程只做「把文字贴上去」。
 *
 * <h3>数据源</h3>
 * 本机与外接设备<b>共用同一套卡片</b>，靠标题右侧的胶囊下拉切换 —— 不摆两套视图。
 * 切换时整份快照重读，卡片结构完全不动，所以不存在「两张卡片各自显示一半」的中间态。
 */
public class DeviceFragment extends Fragment {

    /** Shizuku 授权请求码，回调里用它认领自己那次请求。 */
    private static final int REQ_SHIZUKU_PERMISSION = 4401;

    /** 本机刷新间隔：sysfs 直读，1 秒不心疼。 */
    private static final long TICK_LOCAL_MS = 1000L;

    /** 外接设备刷新间隔：每次都要一次 adb 往返，2 秒是「看起来实时」与「别把 adb 打通」的折中。 */
    private static final long TICK_REMOTE_MS = 2000L;

    /** 「有哪些外接设备」不用秒级刷新 —— 每次都要起一个 adb 进程。 */
    private static final long SOURCES_REFRESH_MS = 5000L;

    /** Shizuku 官方下载页 —— 没装 Shizuku 时唯一的去处。 */
    private static final String SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ExecutorService worker;
    private Context appContext;

    // ── 卡片
    private View cardOverview;
    private View cardCpu;
    private View cardGuide;

    // ── 文本
    private TextView tvDeviceName;
    private TextView tvDeviceCode;
    private TextView tvAndroidVersion;
    private TextView tvKernelVersion;
    private TextView tvSelinuxStatus;
    private TextView tvBootSlot;
    private TextView tvRadioVersion;
    private TextView tvBuildDisplay;
    private TextView tvSecurityPatch;
    private TextView tvAndroidId;
    private TextView tvBatteryCycles;
    private TextView tvBuildFingerprint;
    private TextView tvDeviceSource;

    private GridLayout llCpuCoreRings;
    private final List<CpuCoreRingHolder> cpuCoreRingHolders = new ArrayList<>();

    private DeviceSourceDropdown sourceDropdown;

    /** 下拉里当前这一份可选项，切换后要拿它回写选中态（胶囊上的字）。 */
    private List<DeviceSourceDropdown.Source> lastSources = new ArrayList<>();

    /** 当前数据源序列号；{@code null} 表示本机。 */
    private String sourceSerial;

    /** 已经贴到界面上的那份数据来自哪个数据源 / 哪条通道 —— 用来决定要不要重读。 */
    private String loadedSerial;
    private ShellManager.Mode loadedMode;

    /** 每次整份重读自增，晚到的旧结果直接丢掉（快速切数据源时会发生）。 */
    private final AtomicInteger loadToken = new AtomicInteger(0);

    /** 本轮页面可见期内是否已经弹过配对框，避免每次 onResume 都顶一个对话框。 */
    private boolean pairingPrompted;

    /** 上次查外接设备列表的时间戳（毫秒），用于限流。 */
    private long lastSourcesRefresh;

    /** 各核圆环、电池循环次数：定时刷新（静态行只在切源/resume 时读一次）。 */
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || !ShellManager.get().state().hasShell()) {
                return;
            }
            refreshDynamic();
            mainHandler.postDelayed(this, sourceSerial == null ? TICK_LOCAL_MS : TICK_REMOTE_MS);
        }
    };

    // ── Shizuku 状态回调：授权结果 / 服务连上，两个都要触发重测
    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQ_SHIZUKU_PERMISSION) {
                    return;
                }
                ShellManager.get().invalidate();
                detect();
            };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        if (!ShizukuBridge.hasPermission()) {
            return;
        }
        ShellManager.get().invalidate();
        detect();
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_device, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        appContext = requireContext().getApplicationContext();
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aurora-device");
            t.setDaemon(true);
            return t;
        });

        bindViews(view);
        setCopyOnClick();
        setupSourceDropdown(view);
        setupGuideButtons(view);
        registerShizukuListeners();

        // 先用上次的检测结果铺一次界面（同一个进程内第二次进页面时这里就是「已知状态」），
        // 免得已授权用户每次进来都先闪一下引导页；随后 detect() 再按真实情况校正。
        applyGate(ShellManager.get().state());
        detect();
    }

    @Override
    public void onResume() {
        super.onResume();
        // root 可能刚授权、Shizuku 可能刚激活、无线调试可能刚连上 —— 这些都没有系统广播，
        // 只能在页面回到前台时重测一次。
        ShellManager.get().invalidate();
        detect();
        mainHandler.postDelayed(this::maybePromptPairing, 600L);
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(tickRunnable);
        if (sourceDropdown != null) {
            sourceDropdown.dismiss();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
        unregisterShizukuListeners();
        if (sourceDropdown != null) {
            sourceDropdown.dismiss();
            sourceDropdown = null;
        }
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        llCpuCoreRings = null;
        cpuCoreRingHolders.clear();
        loadedSerial = null;
        loadedMode = null;
        appContext = null;
    }

    // ───────────────── 视图绑定 ─────────────────

    private void bindViews(View view) {
        cardOverview = view.findViewById(R.id.cardDeviceOverview);
        cardCpu = view.findViewById(R.id.cardCpu);
        cardGuide = view.findViewById(R.id.cardAuthGuide);

        tvDeviceName = view.findViewById(R.id.tvDeviceName);
        tvDeviceCode = view.findViewById(R.id.tvDeviceCode);
        tvAndroidVersion = view.findViewById(R.id.tvAndroidVersion);
        tvKernelVersion = view.findViewById(R.id.tvKernelVersion);
        tvSelinuxStatus = view.findViewById(R.id.tvSelinuxStatus);
        tvBootSlot = view.findViewById(R.id.tvBootSlot);
        tvRadioVersion = view.findViewById(R.id.tvRadioVersion);
        tvBuildDisplay = view.findViewById(R.id.tvBuildDisplay);
        tvSecurityPatch = view.findViewById(R.id.tvSecurityPatch);
        tvAndroidId = view.findViewById(R.id.tvAndroidId);
        tvBatteryCycles = view.findViewById(R.id.tvBatteryCycles);
        tvBuildFingerprint = view.findViewById(R.id.tvBuildFingerprint);
        tvDeviceSource = view.findViewById(R.id.tvDeviceSource);
        llCpuCoreRings = view.findViewById(R.id.llCpuCoreRings);
    }

    private void setCopyOnClick() {
        enableCopyOnClick(tvDeviceName, "设备名称");
        enableCopyOnClick(tvDeviceCode, "设备代号");
        enableCopyOnClick(tvAndroidVersion, "系统版本");
        enableCopyOnClick(tvKernelVersion, "内核版本");
        enableCopyOnClick(tvSelinuxStatus, "SELinux");
        enableCopyOnClick(tvBootSlot, "当前 Slot");
        enableCopyOnClick(tvBuildDisplay, "系统版本");
        enableCopyOnClick(tvSecurityPatch, "安全补丁日期");
        enableCopyOnClick(tvRadioVersion, "基带版本");
        enableCopyOnClick(tvAndroidId, "安卓 ID");
        enableCopyOnClick(tvBatteryCycles, "电池循环次数");
        enableCopyOnClick(tvBuildFingerprint, "构建指纹");
    }

    private void setupSourceDropdown(View root) {
        View chip = root.findViewById(R.id.btnDeviceSource);
        if (chip == null) {
            return;
        }
        chip.setOnClickListener(v -> {
            if (sourceDropdown != null) {
                sourceDropdown.toggle();
            }
        });
        sourceDropdown = new DeviceSourceDropdown(requireActivity(), chip, tvDeviceSource,
                this::onSourceSelected);
        List<DeviceSourceDropdown.Source> initial = new ArrayList<>();
        initial.add(DeviceSourceDropdown.Source.local());
        lastSources = initial;
        sourceDropdown.setSources(initial, sourceSerial);
    }

    private void setupGuideButtons(View root) {
        View activate = root.findViewById(R.id.btnActivateShizuku);
        if (activate != null) {
            activate.setOnClickListener(v -> onActivateShizuku());
        }
        View wireless = root.findViewById(R.id.btnWirelessDebug);
        if (wireless != null) {
            wireless.setOnClickListener(v -> {
                // 点这颗按钮意味着「我要去开无线调试」——回来时自动弹配对框，否则用户
                // 开完开关还得自己猜下一步该干嘛。
                pairingPrompted = false;
                openWirelessDebuggingSettings();
            });
        }
    }

    private void registerShizukuListeners() {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener);
            // Sticky：注册时 binder 已经在就立刻回调一次，省掉「页面先开着、Shizuku 后才起来」的漏判
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        } catch (Throwable ignored) {
            // 没装 Shizuku 时这些调用本身不会抛，抛了也只是少了自动刷新，不影响手动重测
        }
    }

    private void unregisterShizukuListeners() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener);
        } catch (Throwable ignored) {
        }
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
        } catch (Throwable ignored) {
        }
    }

    // ───────────────── 权限门控 ─────────────────

    private void detect() {
        if (appContext == null) {
            return;
        }
        ShellManager.get().detectAsync(appContext, state -> {
            if (!isAdded()) {
                return;
            }
            applyGate(state);
        });
    }

    /**
     * 按通道状态决定「显示卡片」还是「显示授权引导」。
     *
     * <p>{@link ShellManager.Mode#DETECTING} 按<b>放行</b>处理：首次进页面时检测要跑几十毫秒到
     * 一秒（{@code su} 可能要等授权弹窗），这期间先摆出已经能读到的机型/系统信息，
     * 比先给一个空页再跳成内容要稳。真没有通道时，检测一结束就收起卡片。
     */
    private void applyGate(ShellManager.State state) {
        boolean open = state.mode == ShellManager.Mode.DETECTING || state.hasShell();

        setVisible(cardGuide, !open);
        setVisible(cardOverview, open);
        setVisible(cardCpu, open);

        if (!open) {
            mainHandler.removeCallbacks(tickRunnable);
            maybePromptPairing();
            return;
        }

        refreshSources();
        loadStatic(false);

        mainHandler.removeCallbacks(tickRunnable);
        if (state.hasShell()) {
            mainHandler.post(tickRunnable);
        }
    }

    private static void setVisible(@Nullable View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    // ───────────────── 数据读取 ─────────────────

    /**
     * 读整份静态信息。
     *
     * @param force {@code true} 表示无条件重读（切数据源时用）
     */
    private void loadStatic(boolean force) {
        final ExecutorService executor = worker;
        final Context ctx = appContext;
        if (executor == null || ctx == null) {
            return;
        }
        final String serial = sourceSerial;
        final ShellManager.Mode mode = ShellManager.get().state().mode;
        if (!force && Objects.equals(loadedSerial, serial) && loadedMode == mode) {
            return;
        }
        loadedSerial = serial;
        loadedMode = mode;
        final int token = loadToken.incrementAndGet();
        executor.execute(() -> {
            DeviceSnapshot snapshot = DeviceReader.readStatic(ctx, serial);
            mainHandler.post(() -> {
                if (!isAdded() || token != loadToken.get()) {
                    return;
                }
                bindStatic(snapshot);
            });
        });
    }

    /** 只刷会变的字段（各核频率 + 循环次数）。 */
    private void refreshDynamic() {
        final ExecutorService executor = worker;
        final Context ctx = appContext;
        if (executor == null || ctx == null) {
            return;
        }
        final String serial = sourceSerial;
        executor.execute(() -> {
            DeviceSnapshot snapshot = DeviceReader.readDynamic(ctx, serial);
            mainHandler.post(() -> {
                if (!isAdded() || !Objects.equals(serial, sourceSerial)) {
                    return;
                }
                bindDynamic(snapshot);
            });
        });
    }

    private void bindStatic(DeviceSnapshot s) {
        if (s == null) {
            return;
        }
        setText(tvDeviceName, s.title);
        setText(tvDeviceCode, s.deviceCode == null ? null : "设备代号: " + s.deviceCode);
        setText(tvAndroidVersion, s.android);
        setText(tvKernelVersion, s.kernel);
        setText(tvBootSlot, s.bootSlot);
        setText(tvBuildDisplay, s.buildDisplay);
        setText(tvSecurityPatch, s.securityPatch);
        setText(tvRadioVersion, s.radio);
        setText(tvAndroidId, s.androidId);
        setText(tvBuildFingerprint, s.fingerprint);
        applySelinuxLabel(s.selinux);
        bindDynamic(s);
        // 循环次数在静态读里也一并带回来；读不到就写「—」，
        // 不能留着上一台设备（或上一轮）的数字假装还准
        setText(tvBatteryCycles, s.batteryCycles);
    }

    private void bindDynamic(DeviceSnapshot s) {
        if (s == null) {
            return;
        }
        if (s.cores != null && s.cores.length > 0) {
            ensureCpuCoreRings(s.cores.length);
            bindCpuCoreRings(s.cores);
        }
        if (s.batteryCycles != null) {
            setText(tvBatteryCycles, s.batteryCycles);
        }
    }

    private void setText(@Nullable TextView view, @Nullable String value) {
        if (view == null) {
            return;
        }
        view.setText(TextUtils.isEmpty(value) ? "—" : value);
    }

    // ───────────────── CPU 圆环 ─────────────────

    private void ensureCpuCoreRings(int count) {
        if (llCpuCoreRings == null || count <= 0 || count == cpuCoreRingHolders.size()) {
            return;
        }
        llCpuCoreRings.removeAllViews();
        cpuCoreRingHolders.clear();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        float density = getResources().getDisplayMetrics().density;
        int gap = (int) (3 * density);
        int rowGap = (int) (8 * density);
        for (int i = 0; i < count; i++) {
            View item = inflater.inflate(R.layout.item_cpu_core_ring, llCpuCoreRings, false);
            TextView tvLabel = item.findViewById(R.id.tvCpuCoreLabel);
            CpuRingGaugeView ring = item.findViewById(R.id.cpuRingGauge);
            TextView tvPct = item.findViewById(R.id.tvCpuCorePct);
            TextView tvFreq = item.findViewById(R.id.tvCpuCoreFreq);
            TextView tvFreqUnit = item.findViewById(R.id.tvCpuCoreFreqUnit);
            TextView tvFreqRange = item.findViewById(R.id.tvCpuCoreFreqRange);
            if (tvLabel != null) {
                tvLabel.setText("核心 " + i);
            }
            // 注意：构造函数为 (rowSpec, columnSpec)，勿与行列下标写反
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 4),
                    GridLayout.spec(i % 4, 1f));
            glp.width = 0;
            glp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            glp.setMargins(gap, i >= 4 ? rowGap : 0, gap, gap);
            item.setLayoutParams(glp);
            llCpuCoreRings.addView(item);
            cpuCoreRingHolders.add(new CpuCoreRingHolder(ring, tvPct, tvFreq, tvFreqUnit, tvFreqRange));
        }
    }

    private void bindCpuCoreRings(DeviceSnapshot.CpuCore[] cores) {
        if (cores == null || cores.length == 0) {
            return;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        int colorVariant = ContextCompat.getColor(ctx, R.color.fluent_on_surface_variant);
        int colorOnSurface = ContextCompat.getColor(ctx, R.color.fluent_on_surface);
        Locale loc = Locale.getDefault();

        int count = Math.min(cores.length, cpuCoreRingHolders.size());
        for (int i = 0; i < count; i++) {
            CpuCoreRingHolder holder = cpuCoreRingHolders.get(i);
            DeviceSnapshot.CpuCore core = cores[i];

            boolean haveRatio = core.online && core.maxKhz > 0L && core.curKhz > 0L;
            int pct = core.loadPercent();

            if (holder.ring != null) {
                holder.ring.setProgress(haveRatio ? (float) pct : 0f);
            }
            if (holder.tvPct != null) {
                if (!core.online) {
                    holder.tvPct.setText("—");
                    holder.tvPct.setTextColor(colorVariant);
                } else if (!haveRatio) {
                    holder.tvPct.setText("—");
                    holder.tvPct.setTextColor(colorOnSurface);
                } else {
                    holder.tvPct.setText(pct + "%");
                    holder.tvPct.setTextColor(ContextCompat.getColor(ctx,
                            CpuRingGaugeView.stitchTextColorResForProgress((float) pct)));
                }
            }
            if (holder.tvFreq != null) {
                if (!core.online) {
                    holder.tvFreq.setText("—");
                    holder.tvFreq.setTextColor(colorVariant);
                    if (holder.tvFreqUnit != null) {
                        holder.tvFreqUnit.setVisibility(View.GONE);
                    }
                } else {
                    if (holder.tvFreqUnit != null) {
                        holder.tvFreqUnit.setVisibility(View.VISIBLE);
                    }
                    if (core.curKhz <= 0L) {
                        holder.tvFreq.setText("—");
                        holder.tvFreq.setTextColor(colorOnSurface);
                    } else {
                        double mhz = core.curKhz / 1000.0;
                        holder.tvFreq.setText(mhz >= 1000d
                                ? String.format(loc, "%.0f", mhz)
                                : String.format(loc, "%.1f", mhz));
                        holder.tvFreq.setTextColor(colorOnSurface);
                    }
                }
            }
            if (holder.tvFreqRange != null) {
                holder.tvFreqRange.setText(core.online ? core.rangeLabel() : "—");
                holder.tvFreqRange.setTextColor(colorVariant);
            }
        }
    }

    // ───────────────── SELinux ─────────────────

    private void applySelinuxLabel(@Nullable String raw) {
        if (tvSelinuxStatus == null || getContext() == null) {
            return;
        }
        if (raw == null || raw.isEmpty()) {
            tvSelinuxStatus.setText("—");
            tvSelinuxStatus.setTextColor(ContextCompat.getColor(getContext(),
                    R.color.fluent_on_surface_variant));
            return;
        }
        String label = formatSelinuxShort(raw);
        tvSelinuxStatus.setText(label);
        int colorRes;
        if (isPermissive(label)) {
            colorRes = R.color.fluent_orange;
        } else if ("严格模式".equals(label)) {
            colorRes = R.color.fluent_green_600;
        } else {
            colorRes = R.color.fluent_on_surface_variant;
        }
        tvSelinuxStatus.setTextColor(ContextCompat.getColor(getContext(), colorRes));
    }

    private boolean isPermissive(String shortStatus) {
        if (shortStatus == null) {
            return false;
        }
        return shortStatus.toLowerCase(Locale.US).contains("permissive")
                || shortStatus.contains("宽容");
    }

    /** 与玩机「SELinux 管理」一致：严格模式 / 宽容模式 / 关闭状态 / 未知 */
    private String formatSelinuxShort(String raw) {
        String r = raw.toLowerCase(Locale.US);
        if (r.contains("严格") || r.contains("强制") || r.contains("enforcing")) {
            return "严格模式";
        }
        if (r.contains("宽容") || r.contains("permissive")) {
            return "宽容模式";
        }
        if (r.contains("关闭") || r.contains("disabled")) {
            return "关闭状态";
        }
        return raw;
    }

    // ───────────────── 数据源 ─────────────────

    /** 后台列一遍外接设备，回来更新下拉。 */
    private void refreshSources() {
        final ExecutorService executor = worker;
        final Context ctx = appContext;
        if (executor == null || ctx == null) {
            return;
        }
        // 每开一次 gate 都会走到这里，而列设备要起一次 adb 进程 —— 5 秒内不重复查，
        // 插拔设备本来也不会快到秒级
        long now = System.currentTimeMillis();
        if (now - lastSourcesRefresh < SOURCES_REFRESH_MS) {
            return;
        }
        lastSourcesRefresh = now;
        executor.execute(() -> {
            List<String> serials = AdbBridge.externalSerials(ctx);
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                List<DeviceSourceDropdown.Source> next = new ArrayList<>();
                next.add(DeviceSourceDropdown.Source.local());
                for (String serial : serials) {
                    next.add(DeviceSourceDropdown.Source.external(serial));
                }
                // 当前选中的外接设备掉线了：退回本机，别让页面停在读不到的快照上
                if (sourceSerial != null && !serials.contains(sourceSerial)) {
                    sourceSerial = null;
                    loadStatic(true);
                }
                lastSources = next;
                if (sourceDropdown != null) {
                    sourceDropdown.setSources(next, sourceSerial);
                }
            });
        });
    }

    private void onSourceSelected(DeviceSourceDropdown.Source source) {
        String serial = source == null ? null : source.serial;
        if (Objects.equals(serial, sourceSerial)) {
            return;
        }
        sourceSerial = serial;
        if (sourceDropdown != null) {
            // 胶囊上的字得跟着换：它就是「现在看的是哪台」的唯一提示
            sourceDropdown.setSources(lastSources, sourceSerial);
        }
        mainHandler.removeCallbacks(tickRunnable);
        loadStatic(true);
        if (ShellManager.get().state().hasShell()) {
            mainHandler.postDelayed(tickRunnable, 200L);
        }
    }

    // ───────────────── 授权引导 ─────────────────

    /**
     * 「去激活Shizuku」：按当前状态决定这声点击是「去下载」「去启动」还是「去授权」。
     */
    private void onActivateShizuku() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        if (!ShellManager.get().isShizukuInstalled(ctx)) {
            openUrl(SHIZUKU_DOWNLOAD_URL);
            return;
        }
        if (!ShizukuBridge.pingBinder()) {
            // 装是装了，但服务没跑：只能把 Shizuku 拉起来，由它自己去做启动引导
            launchShizukuApp(ctx);
            return;
        }
        if (ShizukuBridge.hasPermission()) {
            // 已经授权了却没进内容页：很可能刚刚授权完，重测一次即可
            ShellManager.get().invalidate();
            detect();
            return;
        }
        ShizukuBridge.requestPermission(REQ_SHIZUKU_PERMISSION);
    }

    private void launchShizukuApp(Context ctx) {
        try {
            Intent intent = ctx.getPackageManager()
                    .getLaunchIntentForPackage(ShellManager.SHIZUKU_PACKAGE);
            if (intent == null) {
                throw new IllegalStateException("no launch intent");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            toast("打不开 Shizuku，请手动启动");
        }
    }

    /**
     * 「打开无线调试设置」。
     *
     * <p>三级回落：先点名系统设置里的无线调试页（各家 ROM 的类名不统一，命中率最高的是
     * AOSP 那个），失败退到「开发者选项」，再退到设置首页 —— 不能让用户点了没反应。
     */
    private void openWirelessDebuggingSettings() {
        if (!isAdded()) {
            return;
        }
        Intent direct = new Intent();
        direct.setComponent(new ComponentName("com.android.settings",
                "com.android.settings.Settings$WirelessDebuggingActivity"));
        direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            requireContext().startActivity(direct);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent dev = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            dev.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            requireContext().startActivity(dev);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent all = new Intent(android.provider.Settings.ACTION_SETTINGS);
            all.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            requireContext().startActivity(all);
        } catch (Throwable t) {
            toast("打不开系统设置");
        }
    }

    private void openUrl(String url) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            toast("没有可用的浏览器");
        }
    }

    // ───────────────── 无线调试配对 ─────────────────

    /**
     * 无线调试开着却没连上时，弹一次配对框。
     *
     * <p>Android 11 起无线调试必须先用 6 位配对码换证书，配对的端口和连接的端口还不是同一个。
     * 那对端口只有「配对」这个页面开着的时候才在 mDNS 上广播，所以能自动填就自动填，
     * 填不上就让用户照着设置页手输 —— 各家的免 root 工具都是这个流程，没有更省的办法。
     */
    private void maybePromptPairing() {
        if (!isAdded() || pairingPrompted) {
            return;
        }
        ShellManager.State state = ShellManager.get().state();
        if (state.mode != ShellManager.Mode.NONE || !state.wirelessEnabled) {
            return;
        }
        final ExecutorService executor = worker;
        final Context ctx = appContext;
        if (executor == null || ctx == null) {
            return;
        }
        pairingPrompted = true;
        executor.execute(() -> {
            String address = AdbBridge.findPairingAddress(ctx);
            mainHandler.post(() -> {
                if (isAdded()) {
                    showPairingDialog(address);
                }
            });
        });
    }

    private void showPairingDialog(@Nullable String address) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        int pad = dp(20);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);

        EditText etAddress = new EditText(ctx);
        etAddress.setHint("配对地址，如 192.168.1.7:37231");
        etAddress.setSingleLine(true);
        etAddress.setInputType(InputType.TYPE_CLASS_TEXT);
        if (!TextUtils.isEmpty(address)) {
            etAddress.setText(address);
        }

        EditText etCode = new EditText(ctx);
        etCode.setHint("6 位配对码");
        etCode.setSingleLine(true);
        etCode.setInputType(InputType.TYPE_CLASS_NUMBER);

        box.addView(etAddress);
        box.addView(etCode);

        new AlertDialog.Builder(ctx)
                .setTitle("无线调试配对")
                .setView(box)
                .setPositiveButton("配对", (dialog, which) ->
                        doPair(etAddress.getText().toString().trim(), etCode.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doPair(String address, String code) {
        final ExecutorService executor = worker;
        final Context ctx = appContext;
        if (executor == null || ctx == null) {
            return;
        }
        if (TextUtils.isEmpty(address) || TextUtils.isEmpty(code)) {
            toast("地址和配对码都要填");
            return;
        }
        executor.execute(() -> {
            boolean paired = AdbBridge.pair(ctx, address, code);
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (paired) {
                    toast("配对成功");
                    ShellManager.get().invalidate();
                    detect();
                } else {
                    toast("配对失败，检查地址与配对码");
                }
            });
        });
    }

    // ───────────────── 杂项 ─────────────────

    private void enableCopyOnClick(@Nullable TextView textView, @NonNull String label) {
        if (textView == null) {
            return;
        }
        textView.setClickable(true);
        textView.setOnClickListener(v -> copyTextToClipboard(label, textView.getText()));
    }

    private void copyTextToClipboard(@NonNull String label, @Nullable CharSequence value) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        String text = value == null ? "" : value.toString().trim();
        if (TextUtils.isEmpty(text) || "—".equals(text)) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(ctx, "已复制到剪切板", Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Context ctx = getContext();
        if (ctx != null) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class CpuCoreRingHolder {
        final CpuRingGaugeView ring;
        final TextView tvPct;
        final TextView tvFreq;
        final TextView tvFreqUnit;
        final TextView tvFreqRange;

        CpuCoreRingHolder(CpuRingGaugeView ring, TextView tvPct, TextView tvFreq, TextView tvFreqUnit,
                          TextView tvFreqRange) {
            this.ring = ring;
            this.tvPct = tvPct;
            this.tvFreq = tvFreq;
            this.tvFreqUnit = tvFreqUnit;
            this.tvFreqRange = tvFreqRange;
        }
    }
}
