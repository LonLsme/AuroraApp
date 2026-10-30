package com.aurora.app.ui.device;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
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
import com.aurora.app.ui.widget.CpuRingGaugeView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设备概览页。
 *
 * <h3>权限</h3>
 * 只有一条通道：Root。有 root 时多读出 SELinux、安卓 ID、BL 状态这几个特权字段；
 * 没有 root 时它们显示「—」，其余（机型、系统版本、内核、CPU 频率）照常 ——
 * 页面不摆「去授权」之类的引导，能显示多少显示多少。
 *
 * <h3>数据源</h3>
 * 本机与外接设备<b>共用同一套卡片</b>，靠标题右侧的胶囊下拉切换 —— 不摆两套视图。
 * 切换时整份快照重读，卡片结构完全不动，所以不存在「两张卡片各自显示一半」的中间态。
 * 下拉里常驻一行「添加设备…」：内置 adb 靠扫描 {@code /dev/bus/usb} 找 OTG 上的手机，
 * 那需要 root 身份（见 {@link AdbBridge}）；没有 root 时只能靠
 * {@code adb connect ip:端口} 手动把它拉进来。
 */
public class DeviceFragment extends Fragment {

    private static final String TAG = "DeviceFragment";

    /** 本机刷新间隔：sysfs 直读，1 秒不心疼。 */
    private static final long TICK_LOCAL_MS = 1000L;

    /** 外接设备刷新间隔：每次都要一次 adb 往返，2 秒是「看起来实时」与「别把 adb 打通」的折中。 */
    private static final long TICK_REMOTE_MS = 2000L;

    /**
     * fastboot 设备刷新间隔。
     *
     * <p>它没有 CPU 频率、没有电池循环可刷 —— 静态那几项（产品代号、BL 状态、槽位）
     * 在设备退出 fastboot 之前不会变。这里只保留一个低频心跳，让拔线能被发现；
     * 真正的插拔变化由 {@code refreshSources} 的轮询负责。
     */
    private static final long TICK_FASTBOOT_MS = 15_000L;

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

    /**
     * 整份静态快照读取失败后的重试间隔。
     *
     * <p>外接设备切过去的第一下经常失败：对方刚插上、adb 正在握手、首次 shell 要等授权，
     * 那一刻读回来是空快照。而静态行<b>只在切源时读一次</b>（tick 只刷 CPU 与循环次数），
     * 不重试的话页面会永久停在满屏「—」，用户只能来回切两次数据源才可能恢复。
     * 6 秒是「别把失败的 adb 打通」与「插好线别等太久」的折中。
     */
    private static final long STATIC_RETRY_MS = 6000L;

    /**
     * 外接设备判掉线要连续缺席几轮。
     *
     * <p>单轮缺席不能算掉线：{@code adb devices} 会因为 server 忙、USB 供电抖动、
     * 对方正在切换 usb 配置而偶尔返回空表。按单轮判的话，用户刚切到对方设备，
     * 5 秒后就被弹回本机 —— 看到的现象是「切换了但数据没变（还是本机的）」，
     * 而实际上切换成功过。
     */
    private static final int SOURCE_MISSING_TICKS_TO_DROP = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ExecutorService worker;
    private Context appContext;

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

    /**
     * 当前数据源是不是一台<b>在 fastboot 模式</b>的设备。
     *
     * <p>它不是连接方式的差别，而是设备状态的差别：对方的 Android 没启动，
     * 读取链路要整体换成 {@code fastboot getvar all}，动态刷新也完全没意义。
     */
    private boolean sourceFastboot;

    /** 刚 connect 上的设备：等它出现在 {@code adb devices} 里就自动切过去。 */
    private String pendingSelectSerial;

    /** 已经贴到界面上的那份数据来自哪个数据源 / 哪条通道 —— 用来决定要不要重读。 */
    private String loadedSerial;
    private ShellManager.Mode loadedMode;
    private boolean loadedFastboot;

    /** 每次整份重读自增，晚到的旧结果直接丢掉（快速切数据源时会发生）。 */
    private final AtomicInteger loadToken = new AtomicInteger(0);

    /** 上次查外接设备列表的时间戳（毫秒），用于限流。 */
    private long lastSourcesRefresh;

    /** 当前数据源连续几轮没在 adb / fastboot 列表里出现（用于掉线防抖）。 */
    private int sourceMissingTicks;

    /** 贴到界面上的那份快照是不是读成功了 —— 失败时 tick 会按 {@link #STATIC_RETRY_MS} 重试。 */
    private boolean loadedOk = true;

    /** 上次整份重读的时间戳，给失败重试限流。 */
    private long lastStaticAttempt;

    /** 各核圆环、电池循环次数：定时刷新（静态行只在切源/resume 时读一次）。 */
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) {
                return;
            }
            refreshDynamic();
            // 上一份静态快照没读成功（外接设备刚连上、还在等授权）：按限流重试一次。
            // 不重试的话静态行永远停在「—」，因为 tick 不碰静态字段。
            if (!loadedOk) {
                long now = System.currentTimeMillis();
                if (now - lastStaticAttempt >= STATIC_RETRY_MS) {
                    Log.i(TAG, "静态快照未成功，重试 " + sourceSerial);
                    loadStatic(true);
                }
            }
            // 插拔外接设备不会发广播，只能顺手轮询。放这里而不是只在 gate 刷新时查，
            // 是为了让「页面开着、现在插上线」这种最常见的动作能被看到。
            refreshSources(false);
            mainHandler.postDelayed(this, sourceSerial == null ? TICK_LOCAL_MS
                    : (sourceFastboot ? TICK_FASTBOOT_MS : TICK_REMOTE_MS));
        }
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

        // 先把不需要权限的部分摆出来（Build.* 与 sysfs 都读得到），别等 su 回来才显示首屏；
        // detect() 结束若发现 root 状态与上次不同，会自己触发一次整份重读。
        refreshSources(false);
        loadStatic(false);
        mainHandler.post(tickRunnable);
        detect();
    }

    @Override
    public void onResume() {
        super.onResume();
        // root 可能刚授权完 —— 这没有系统广播，只能在页面回到前台时重测一次。
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

    // ───────────────── root 检测 ─────────────────

    private void detect() {
        if (appContext == null) {
            return;
        }
        ShellManager.get().detectAsync(state -> {
            if (!isAdded()) {
                return;
            }
            onRootStateChanged();
        });
    }

    /**
     * root 状态变了就整份重读。
     *
     * <p>只有 SELinux、安卓 ID、BL 状态这几个特权字段受它影响，<b>布局不受影响</b> ——
     * 没有 root 时页面照常展示能读到的部分，不收起、也不替换成引导页。
     * 读什么由 {@link DeviceReader} 按当前通道自己决定，这里只需要让它重跑一遍。
     */
    private void onRootStateChanged() {
        loadStatic(false);
        refreshSources(false);
        mainHandler.removeCallbacks(tickRunnable);
        mainHandler.post(tickRunnable);
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
        final boolean fastboot = sourceFastboot;
        final ShellManager.Mode mode = ShellManager.get().state().mode;
        if (!force && Objects.equals(loadedSerial, serial) && loadedMode == mode
                && loadedFastboot == fastboot) {
            return;
        }
        loadedSerial = serial;
        loadedMode = mode;
        loadedFastboot = fastboot;
        lastStaticAttempt = System.currentTimeMillis();
        final int token = loadToken.incrementAndGet();
        Log.i(TAG, "读数源 serial=" + serial + " fastboot=" + fastboot
                + " mode=" + mode + " force=" + force + " token=" + token);
        executor.execute(() -> {
            DeviceSnapshot snapshot;
            try {
                snapshot = DeviceReader.readStatic(ctx, serial,
                        fastboot ? DeviceSnapshot.Transport.FASTBOOT : DeviceSnapshot.Transport.ADB);
            } catch (Throwable t) {
                // 读的时候抛异常不能把这条后台线程带走：页面会永远停在上一份数据上，
                // 看起来和「没切换」一模一样，而且没有任何报错
                Log.w(TAG, "读快照抛异常 serial=" + serial, t);
                snapshot = new DeviceSnapshot();
                snapshot.error = "读取异常: " + t.getClass().getSimpleName();
            }
            final DeviceSnapshot result = snapshot;
            mainHandler.post(() -> {
                if (!isAdded() || token != loadToken.get()) {
                    Log.i(TAG, "丢弃过期快照 token=" + token + " now=" + loadToken.get());
                    return;
                }
                bindStatic(result);
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
        if (sourceFastboot) {
            // fastboot 模式下没有 CPU 频率、没有电池循环 —— 一条命令都不用发
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
        loadedOk = s.error == null;
        if (s.error == null) {
            setText(tvDeviceName, s.title);
            setText(tvDeviceCode, s.deviceCode == null ? null : "设备代号: " + s.deviceCode);
        } else {
            // 失败时把「谁 + 为什么」直接摆在原来放设备名与代号的两格上。
            // 只写「—」的话，「没切换成功」「对方没授权」「掉线」三种情况长得一模一样，
            // 用户只能反复点下拉，而真正要做的动作（去对方手机点允许 / 换线）一个都看不出来。
            Log.w(TAG, "快照读取失败 serial=" + sourceSerial + " → " + s.error);
            String who = sourceSerial == null ? "本机" : sourceSerial;
            setText(tvDeviceName, who + " · 读取失败");
            setText(tvDeviceCode, s.error);
        }
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
        if (s.cores == null || s.cores.length == 0) {
            // 这台没有 CPU 数据（fastboot 模式）：必须把上一台的圆环清掉。
            // 不清的话会拿着本机的 8 个核继续显示，看起来像是读到了对方的数据。
            clearCpuCoreRings();
        }
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

    /**
     * 清空 CPU 圆环。
     *
     * <p>切到 fastboot 设备时必须调用：那台没有 CPU 频率可言，
     * 而 {@link #ensureCpuCoreRings(int)} 只在核数变化时重建 —— 核数「消失」
     * 这件事它看不见，旧圆环会留在屏幕上继续显示上一个设备（通常是本机）的频率。
     */
    private void clearCpuCoreRings() {
        if (llCpuCoreRings == null || cpuCoreRingHolders.isEmpty()) {
            return;
        }
        llCpuCoreRings.removeAllViews();
        cpuCoreRingHolders.clear();
    }

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
            AdbBridge.Snapshot snapshot = AdbBridge.externalDevices(ctx);
            mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                boolean changed = !sameSources(snapshot);
                // 掉线判定必须防抖：adb devices 会因为 server 忙、USB 供电抖动、
                // 对方正在切换 usb 配置而偶尔吐一张空表。按单轮判的话，用户刚切过去
                // 5 秒就被弹回本机 —— 现象正是「切换了但数据没变（还是本机的）」。
                boolean present = sourceSerial == null
                        || containsCurrent(snapshot, sourceSerial, sourceFastboot);
                if (present) {
                    sourceMissingTicks = 0;
                } else {
                    sourceMissingTicks++;
                    Log.i(TAG, "数据源本轮缺席 #" + sourceMissingTicks + " serial=" + sourceSerial);
                }
                boolean drop = sourceMissingTicks >= SOURCE_MISSING_TICKS_TO_DROP;
                if (!changed && pendingSelectSerial == null && !drop) {
                    // 列表没变就不碰下拉：setSources 会把正在展开的弹窗关掉
                    return;
                }
                List<DeviceSourceDropdown.Source> next = new ArrayList<>();
                next.add(DeviceSourceDropdown.Source.local());
                for (AdbBridge.Device device : snapshot.online) {
                    next.add(DeviceSourceDropdown.Source.external(device));
                }
                for (AdbBridge.Device device : snapshot.pending) {
                    next.add(DeviceSourceDropdown.Source.external(device));
                }
                for (AdbBridge.FastbootDevice device : snapshot.fastboot) {
                    next.add(DeviceSourceDropdown.Source.fastboot(device));
                }
                next.add(DeviceSourceDropdown.Source.addDevice());

                // 刚 connect 上的设备出现了：自动切过去，省掉用户再点一次下拉
                if (pendingSelectSerial != null && containsOnline(snapshot, pendingSelectSerial)) {
                    sourceSerial = pendingSelectSerial;
                    sourceFastboot = false;
                    pendingSelectSerial = null;
                    sourceMissingTicks = 0;
                    Log.i(TAG, "自动切到刚连上的设备 " + sourceSerial);
                    loadStatic(true);
                } else if (sourceSerial != null && drop) {
                    // 连续两轮都不见了（掉线 / 撤销授权 / 退出 fastboot）才回落本机，
                    // 单轮抖动不切 —— 见 SOURCE_MISSING_TICKS_TO_DROP 的说明
                    Log.w(TAG, "数据源连续 " + sourceMissingTicks + " 轮缺席，回落本机: "
                            + sourceSerial);
                    sourceSerial = null;
                    sourceFastboot = false;
                    sourceMissingTicks = 0;
                    loadStatic(true);
                }

                lastSources = next;
                if (sourceDropdown != null) {
                    sourceDropdown.setSources(next, sourceSerial);
                }
            });
        });
    }

    /** 某个序列号是否在「已就绪」那一批里。 */
    private boolean containsOnline(AdbBridge.Snapshot snapshot, String serial) {
        if (serial == null) {
            return false;
        }
        for (AdbBridge.Device device : snapshot.online) {
            if (serial.equals(device.serial)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前选中的那台是否还在。
     *
     * <p>必须按通道分开比：一台手机可以从正常系统重启进 fastboot（序列号不变、
     * {@code adb devices} 里消失、{@code fastboot devices} 里出现）。只查 adb 那桶
     * 会把「正在进 fastboot」误判成「掉线」，页面直接弹回本机 ——
     * 而用户刚点的就是这个动作。
     */
    private boolean containsCurrent(AdbBridge.Snapshot snapshot, String serial, boolean fastboot) {
        if (serial == null) {
            return false;
        }
        if (fastboot) {
            for (AdbBridge.FastbootDevice device : snapshot.fastboot) {
                if (serial.equals(device.serial)) {
                    return true;
                }
            }
            return false;
        }
        return containsOnline(snapshot, serial);
    }

    /**
     * 下拉里的外接项与这次查到的结果是否完全一致。
     *
     * <p>比的是「序列号 + 是否就绪」而不是只比序列号：USB 首次插上时是 {@code unauthorized}，
     * 用户去对方手机上点了「允许」之后还是同一个序列号，只比序列号会认为「没变化」，
     * 那一项就永远停在「未授权」上。
     */
    private boolean sameSources(AdbBridge.Snapshot snapshot) {
        List<String> current = new ArrayList<>();
        for (DeviceSourceDropdown.Source source : lastSources) {
            if (source.action || source.serial == null) {
                continue;
            }
            current.add(keyOf(source.serial, source.fastboot, source.ready));
        }
        List<String> next = new ArrayList<>();
        for (AdbBridge.Device device : snapshot.online) {
            next.add(keyOf(device.serial, false, true));
        }
        for (AdbBridge.Device device : snapshot.pending) {
            next.add(keyOf(device.serial, false, false));
        }
        for (AdbBridge.FastbootDevice device : snapshot.fastboot) {
            next.add(keyOf(device.serial, true, true));
        }
        return current.equals(next);
    }

    /** 下拉项的唯一键：序列号 + 通道 + 是否就绪。三者任一变化都要重建下拉。 */
    private String keyOf(String serial, boolean fastboot, boolean ready) {
        return serial + (fastboot ? "#fb" : (ready ? "#1" : "#0"));
    }

    private void onSourceSelected(DeviceSourceDropdown.Source source) {
        if (source == null) {
            return;
        }
        if (source.action) {
            showAddDeviceDialog();
            return;
        }
        if (!source.ready) {
            showPendingDeviceDialog(source);
            return;
        }
        String serial = source.serial;
        if (Objects.equals(serial, sourceSerial) && sourceFastboot == source.fastboot) {
            return;
        }
        Log.i(TAG, "切换数据源 " + sourceSerial + " → " + serial
                + " (fastboot=" + source.fastboot + ", ready=" + source.ready + ")");
        sourceSerial = serial;
        sourceFastboot = source.fastboot;
        // 刚手动切过去，从零开始数缺席轮次 —— 否则上一个数据源攒下的计数会立刻触发回落
        sourceMissingTicks = 0;
        if (sourceDropdown != null) {
            // 胶囊上的字得跟着换：它就是「现在看的是哪台」的唯一提示
            sourceDropdown.setSources(lastSources, sourceSerial);
        }
        mainHandler.removeCallbacks(tickRunnable);
        loadStatic(true);
        mainHandler.postDelayed(tickRunnable, 200L);
    }

    /**
     * 点了「还没就绪」的外接设备。
     *
     * <p>最常见的场景就是 USB 首次插上：内置 adb 已经在那条 USB 总线上看到了对方，
     * 但对方手机屏幕上正等着一个「允许 USB 调试吗？」的确认 —— 这是 RSA key 交换，
     * 绕不过去。这一屏必须存在：没有它，用户看到的就是「插了没反应」，
     * 而且没有任何线索指向「去对方手机上点允许」。
     */
    private void showPendingDeviceDialog(DeviceSourceDropdown.Source source) {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        String serial = source.serial == null ? "那台设备" : source.serial;
        new AlertDialog.Builder(ctx)
                .setTitle("这台设备还没就绪")
                .setMessage("已经通过 USB 找到 " + serial + "，但还读不了数据。\n\n"
                        + "① 对方手机屏幕上应有「允许 USB 调试吗？」弹窗 —— 点允许。\n"
                        + "② 没出现弹窗：那台手机进「开发者选项 → 撤销 USB 调试授权」，再重新插线。\n"
                        + "③ 数据线要能传数据，纯充电线只能供电。\n\n"
                        + "对方允许之后，这一项会自己变成「已连接」，不需要退出重进。")
                .setPositiveButton("知道了", null)
                .show();
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
                .setMessage("两种接法：\n\n"
                        + "① USB 数据线：本机有 Root 时直接插线 —— 对方开「USB 调试」、"
                        + "在屏幕上点「允许」，这台设备会自己出现在下拉里，不用在这里填任何东西。\n\n"
                        + "② 无线调试：在那台手机的「无线调试」页里看 ip 与端口，"
                        + "首次连接要先在那台手机上完成配对。\n\n"
                        + "没有 Root 时 USB 那台枚举不到（系统只允许 root 身份读 USB 设备节点），"
                        + "只能走 ②。")
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
