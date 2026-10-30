package com.aurora.app.ui.device;

import android.content.ClipData;
import android.content.ClipboardManager;
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
import com.aurora.app.core.device.BootloaderState;
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
 *   <li>{@link ShellManager.Mode#SHIZUKU} —— 服务在跑且本应用已在 Shizuku 里被勾选，进内容；</li>
 *   <li>{@link ShellManager.Mode#NONE} —— 收起设备卡与 CPU 卡，只留授权引导
 *       （{@code cardAuthGuide}）：一句说明 + 一颗「去授权 Shizuku」。</li>
 * </ol>
 * 检测与数据读取全在后台单线程，主线程只做「把文字贴上去」。
 *
 * <h3>数据源</h3>
 * 本机与外接设备<b>共用同一套卡片</b>，靠标题右侧的胶囊下拉切换 —— 不摆两套视图。
 * 切换时整份快照重读，卡片结构完全不动，所以不存在「两张卡片各自显示一半」的中间态。
 * 下拉里常驻一行「添加设备…」：无 root 时内置 adb 枚举不到 USB 上的那台手机
 * （应用身份进不去 {@code /dev/bus/usb}，见 {@link AdbBridge}），
 * 只能靠 {@code adb connect ip:端口} 把它拉进来。
 */
public class DeviceFragment extends Fragment {

    /** Shizuku 授权请求码，回调里用它认领自己那次请求。 */
    private static final int REQ_SHIZUKU_PERMISSION = 4401;

    /** 本机刷新间隔：sysfs 直读，1 秒不心疼。 */
    private static final long TICK_LOCAL_MS = 1000L;

    /** 外接设备刷新间隔：每次都要一次 adb 往返，2 秒是「看起来实时」与「别把 adb 打通」的折中。 */
    private static final long TICK_REMOTE_MS = 2000L;

    /** 外接设备的轮询间隔（有 root）：每轮要起一次 adb，5 秒是「插上就能看见」与别太费的折中。 */
    private static final long SOURCES_REFRESH_MS = 5000L;

    /** 无 root 但列表里已有外接设备：还查，只是放慢 —— 只为反映掉线，不指望能发现新设备。 */
    private static final long SOURCES_REFRESH_MS_HAS_REMOTE = 8000L;

    /**
     * 无 root 且列表里没有外接设备：几乎不用查。
     *
     * <p>这种状态下轮询是纯浪费 —— 应用身份的 adb 本来就看<b>不</b>到 USB 上的那台，
     * 唯一的入口是用户手动 {@code connect}，而那一步会强制刷新（{@code force}）。
     * 30 秒只是留一个兜底，防止用户在外面 connect 过、回到页面看不到。
     */
    private static final long SOURCES_REFRESH_MS_IDLE = 30_000L;

    /** Shizuku 官方下载页 —— 没装 Shizuku 时唯一的去处。 */
    private static final String SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/";

    /**
     * 点「去授权 Shizuku」后等多久再判断权限框有没有出现。
     *
     * <p>Shizuku 的授权框是它自己的 Activity，弹出来会让我们 {@code onPause}。
     * 所以「1.2 秒后页面仍在前台且仍未授权」等价于「框没弹出来」——
     * 那通常意味着用户之前拒绝过，只能带他去 Shizuku 的应用列表里手动勾选。
     */
    private static final long SHIZUKU_GRANT_RECHECK_MS = 1200L;

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
    private TextView tvBootloaderState;
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

    /** 刚 connect 上的设备：等它出现在 {@code adb devices} 里就自动切过去。 */
    private String pendingSelectSerial;

    /** 已经贴到界面上的那份数据来自哪个数据源 / 哪条通道 —— 用来决定要不要重读。 */
    private String loadedSerial;
    private ShellManager.Mode loadedMode;

    /** 每次整份重读自增，晚到的旧结果直接丢掉（快速切数据源时会发生）。 */
    private final AtomicInteger loadToken = new AtomicInteger(0);

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
            // 插拔外接设备不会发广播，只能顺手轮询。放这里而不是只在 gate 刷新时查，
            // 是为了让「页面开着、现在插上线」这种最常见的动作能被看到。
            refreshSources(false);
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
        // root 可能刚授权、Shizuku 可能刚被勾选 —— 这些都没有系统广播，
        // 只能在页面回到前台时重测一次。
        ShellManager.get().invalidate();
        detect();
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
        tvBootloaderState = view.findViewById(R.id.tvBootloaderState);
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
        enableCopyOnClick(tvBootloaderState, "BL 状态");
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
        initial.add(DeviceSourceDropdown.Source.addDevice());
        lastSources = initial;
        sourceDropdown.setSources(initial, sourceSerial);
    }

    private void setupGuideButtons(View root) {
        View activate = root.findViewById(R.id.btnActivateShizuku);
        if (activate != null) {
            activate.setOnClickListener(v -> onActivateShizuku());
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
            return;
        }

        refreshSources(false);
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
        applyBootloaderLabel(s.bootloader);
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

    // ───────────────── 状态标签 ─────────────────

    /** 与玩机「SELinux 管理」一致：严格模式 / 宽容模式 / 关闭状态 / 未知 */
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

    /**
     * BL 状态的标签与配色。
     *
     * <p>配色与 SELinux 那格保持一致：需要动手关注的（已解锁 / 验证失败）用橙色，
     * 出厂状态用绿色，读不到用次要色。解锁本身不算错事 —— 玩机的人本来就要解锁，
     * 所以这里只说事实，不加「不安全」之类的判断。
     */
    private void applyBootloaderLabel(@Nullable BootloaderState state) {
        if (tvBootloaderState == null || getContext() == null) {
            return;
        }
        BootloaderState value = state == null ? BootloaderState.UNKNOWN : state;
        tvBootloaderState.setText(value.label);
        int colorRes;
        switch (value) {
            case UNLOCKED:
            case VERIFY_FAILED:
                colorRes = R.color.fluent_orange;
                break;
            case LOCKED:
            case LOCKED_SELF_SIGNED:
                colorRes = R.color.fluent_green_600;
                break;
            default:
                colorRes = R.color.fluent_on_surface_variant;
                break;
        }
        tvBootloaderState.setTextColor(ContextCompat.getColor(getContext(), colorRes));
    }

    private boolean isPermissive(String shortStatus) {
        if (shortStatus == null) {
            return false;
        }
        return shortStatus.toLowerCase(Locale.US).contains("permissive")
                || shortStatus.contains("宽容");
    }

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

    /**
     * 后台列一遍外接设备，回来更新下拉。
     *
     * @param force 跳过 5 秒限流（刚 connect 成功时用，要让新设备立刻出现）
     */
    private void refreshSources(boolean force) {
        final ExecutorService executor = worker;
        final Context ctx = appContext;
        if (executor == null || ctx == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force) {
            boolean hasRemote = false;
            for (DeviceSourceDropdown.Source source : lastSources) {
                if (!source.action && source.serial != null) {
                    hasRemote = true;
                    break;
                }
            }
            long interval = ShellManager.get().state().mode == ShellManager.Mode.ROOT
                    ? SOURCES_REFRESH_MS
                    : (hasRemote ? SOURCES_REFRESH_MS_HAS_REMOTE : SOURCES_REFRESH_MS_IDLE);
            if (now - lastSourcesRefresh < interval) {
                return;
            }
        }
        lastSourcesRefresh = now;
        executor.execute(() -> {
            List<String> serials = AdbBridge.externalSerials(ctx);
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                boolean changed = !sameSerials(serials);
                if (!changed && pendingSelectSerial == null) {
                    // 列表没变就不碰下拉：setSources 会把正在展开的弹窗关掉
                    return;
                }
                List<DeviceSourceDropdown.Source> next = new ArrayList<>();
                next.add(DeviceSourceDropdown.Source.local());
                for (String serial : serials) {
                    next.add(DeviceSourceDropdown.Source.external(serial));
                }
                next.add(DeviceSourceDropdown.Source.addDevice());

                // 刚 connect 上的设备出现了：自动切过去，省掉用户再点一次下拉
                if (pendingSelectSerial != null && serials.contains(pendingSelectSerial)) {
                    sourceSerial = pendingSelectSerial;
                    pendingSelectSerial = null;
                    loadStatic(true);
                } else if (sourceSerial != null && !serials.contains(sourceSerial)) {
                    // 当前选中的外接设备掉线了：退回本机，别让页面停在读不到的快照上
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

    /** 序列号列表与当前下拉里的外接项是否完全一致。 */
    private boolean sameSerials(List<String> serials) {
        List<String> current = new ArrayList<>();
        for (DeviceSourceDropdown.Source source : lastSources) {
            if (!source.action && source.serial != null) {
                current.add(source.serial);
            }
        }
        return current.equals(serials);
    }

    private void onSourceSelected(DeviceSourceDropdown.Source source) {
        if (source == null) {
            return;
        }
        if (source.action) {
            showAddDeviceDialog();
            return;
        }
        String serial = source.serial;
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

    /**
     * 「添加设备…」：手动 {@code adb connect ip:端口}。
     *
     * <p>说明都放这个对话框里，不放页面上 —— 页面只摆控件和状态。
     */
    private void showAddDeviceDialog() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        int pad = dp(20);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);

        EditText etAddress = new EditText(ctx);
        etAddress.setHint("192.168.1.23:37105");
        etAddress.setSingleLine(true);
        etAddress.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(etAddress);

        new AlertDialog.Builder(ctx)
                .setTitle("连接另一台手机")
                .setMessage("在那台手机的「无线调试」页里可以看到 ip 与端口。\n"
                        + "首次连接需要先在那台手机上完成配对；\n"
                        + "USB 数据线连的那台，只有 Root 才能自动列出来。")
                .setView(box)
                .setPositiveButton("连接", (dialog, which) ->
                        doConnect(etAddress.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doConnect(String address) {
        final ExecutorService executor = worker;
        final Context ctx = appContext;
        if (executor == null || ctx == null) {
            return;
        }
        if (TextUtils.isEmpty(address)) {
            toast("地址不能为空");
            return;
        }
        executor.execute(() -> {
            boolean connected = AdbBridge.connect(ctx, address);
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (connected) {
                    toast("已连接");
                    // adb 里那条设备的 serial 就是 ip:端口，直接认它；万一格式对不上，
                    // refreshSources 拿到的列表里包含了也会自动切过去
                    pendingSelectSerial = address;
                    refreshSources(true);
                } else {
                    toast("连接失败，检查地址、端口，以及是否已配对");
                }
            });
        });
    }

    // ───────────────── 授权引导 ─────────────────

    /**
     * 「去授权 Shizuku」：按当前状态决定这声点击是「去下载」「去启动」还是「去授权」。
     *
     * <p>授权这一步做了两层：
     * <ol>
     *   <li>先 {@code requestPermission} —— 这一步不只是弹框，它同时让本应用
     *       <b>出现在 Shizuku 的「使用中的应用」列表里</b>。不请求过的应用，
     *       用户在 Shizuku 里根本找不到它，也就谈不上「勾选」。</li>
     *   <li>延时复查：Shizuku 的授权框是它自己的 Activity，弹出来会让我们
     *       {@code onPause}。所以 1.2 秒后「页面仍在前台 + 仍未授权」等价于
     *       「框没弹出来」（用户之前点过拒绝），这时直接把人送到 Shizuku，
     *       让他在应用列表里勾选。</li>
     * </ol>
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
            toast("请先在 Shizuku 里启动服务");
            return;
        }
        if (ShizukuBridge.hasPermission()) {
            // 已经授权了却没进内容页：很可能刚刚授权完，重测一次即可
            ShellManager.get().invalidate();
            detect();
            return;
        }
        ShizukuBridge.requestPermission(REQ_SHIZUKU_PERMISSION);
        mainHandler.postDelayed(this::openShizukuIfStillDenied, SHIZUKU_GRANT_RECHECK_MS);
    }

    /** 授权框没出现（或被拒）时的兜底：把人送到 Shizuku 的应用列表。 */
    private void openShizukuIfStillDenied() {
        if (!isAdded() || !isResumed()) {
            // 页面不在前台 = 授权框正开着，交给回调处理
            return;
        }
        if (ShizukuBridge.hasPermission()) {
            ShellManager.get().invalidate();
            detect();
            return;
        }
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        launchShizukuApp(ctx);
        toast("在 Shizuku 的「使用中的应用」里勾选 Aurora");
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
