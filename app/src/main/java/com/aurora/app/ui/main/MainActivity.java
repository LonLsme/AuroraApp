package com.aurora.app.ui.main;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.res.Configuration;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.animation.OvershootInterpolator;
import android.view.animation.DecelerateInterpolator;
import java.io.FileOutputStream;
import java.io.InputStream;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.scottyab.rootbeer.RootBeer;
import com.aurora.app.ui.theme.ThemeManager;
import com.aurora.app.data.detector.RootDetector;
import com.aurora.app.ui.appmanager.AppManagerActivity;
import com.aurora.app.ui.font.FontLibraryBackupActivity;
import com.aurora.app.ui.module.ModuleManagerActivity;
import com.aurora.app.ui.partition.PartitionManagerActivity;
import com.aurora.app.ui.payload.PayloadActivity;
import com.aurora.app.ui.plugin.GlobalDeviceSpoofActivity;
import com.aurora.app.ui.plugin.DeviceIdModifyActivity;
import com.aurora.app.ui.plugin.AuroraPluginActivity;
import com.aurora.app.ui.selinux.SelinuxManagerActivity;
import com.aurora.app.ui.overlay.MonitorOverlayService;
import com.aurora.app.ui.overlay.MonitorType;
import com.aurora.app.ui.overlay.OverlayConfig;
import com.aurora.app.ui.overlay.OverlayPermission;
import com.aurora.app.ui.device.DeviceFragment;
import com.aurora.app.ui.settings.SettingsFragment;
import com.aurora.app.ui.update.UpdateDialog;
import com.aurora.app.core.update.UpdateChecker;
import com.aurora.app.ui.widget.AuroraSwitchStyler;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.menu.ActionMenuItemView;
import androidx.appcompat.widget.ActionMenuView;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.compose.ui.platform.ComposeView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;

import com.aurora.app.R;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public class MainActivity extends AppCompatActivity {

    /**
     * 字号缩放：在基类 Context 上套一层携带 fontScale 的派生 Context，
     * 避免 {@code applyOverrideConfiguration} 在 recreate() 后触发 relaunch 死循环（卡死）。
     * 由 {@link com.aurora.app.ui.theme.ThemeManager#wrapFontScale} 统一处理缩放值。
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(com.aurora.app.ui.theme.ThemeManager.wrapFontScale(newBase));
    }


    public static final String EXTRA_OPEN_TAB = "extra_open_tab";

    /** 重建（主题切换 / 配置变更）时保存当前 tab，避免跳回首页。 */
    private static final String STATE_TAB = "aurora_state_tab";

    /** 页面 Fragment 的 tag，供 {@link #installPageFragments()} 判重。 */
    private static final String TAG_DEVICE_FRAGMENT = "aurora_device_fragment";
    private static final String TAG_SETTINGS_FRAGMENT = "aurora_settings_fragment";

    /**
     * FragmentManager 在 Activity 状态中的保存键（即 {@code FragmentActivity.FRAGMENTS_TAG}）。
     * 重建时移除该键，使 FragmentManager 放弃自动恢复页面 Fragment。
     *
     * <p>原因见 {@link #installPageFragments()}：页面 View 会被 {@link #setupTabPager()}
     * 摘出视图树交给 ViewPager2 托管，自动恢复的时机早于 ViewPager2 布局完成，
     * 容器定位不到会导致整页空白。
     */
    private static final String FRAGMENT_STATE_TAG = "android:support:fragments";

    private static final String PREFS_NAME = "aurora_detection_logs";

    private static final String PREFS_USAGE_MONITOR = OverlayConfig.PREFS_NAME;

    /**
     * 隐藏/恢复状态栏脚本（与用户提供的「隐藏状态栏.sh」一致）：
     * 内部按全局设置 sb_hidden 标志自动 toggle ——
     * 首次执行隐藏（禁用系统图标/时钟/通知图标），再次执行恢复。
     */
    private static final String HIDE_STATUSBAR_SCRIPT =
            "KEY=sb_hidden\n" +
            "FLAG=$(settings get global $KEY 2>/dev/null)\n" +
            "[ -z \"$FLAG\" ] && FLAG=0\n" +
            "if [ $FLAG -eq 0 ]; then\n" +
            "  cmd statusbar send-disable-flag system-icons clock notification-icons\n" +
            "  settings put global $KEY 1\n" +
            "else\n" +
            "  cmd statusbar send-disable-flag none\n" +
            "  settings put global $KEY 0\n" +
            "fi";
    private static final String KEY_LOG_PATH = "log_path";
    private static final long EXPLORE_ROOT_STATUS_CACHE_MS = 5_000L;

    private int colorSemanticSafe() {
        return ContextCompat.getColor(this, R.color.ios_semantic_positive);
    }

    private int currentTab = 0;
    /** 「设备」页在底栏里的索引（tab 0）。这是<b>唯一</b>保留顶栏的页面，见 {@link #updateToolbarForTab(int)}。 */
    private static final int TAB_DEVICE = 0;
    /** 「刷机」页在底栏里的索引（tab 1）。 */
    private static final int TAB_FLASH = 1;
    /** 上次已套用的主题版本号：返回前台时若发现主题已变更，则重建一次以套用新配色。 */
    private int appliedThemeRevision = -1;
    private LinearLayout navHome, navDevice, navExplore, navSettings;
    private LinearLayout navRow;
    private ImageView iconHome, iconDevice, iconExplore, iconSettings;
    private TextView textHome, textDevice, textExplore, textSettings;
    private View navLiquidIndicator;
    private AppBarLayout appBarLayout;
    private View overflowMenuScrim;
    private PopupMenu quickRebootPopupMenu;
    /** 顶栏「快捷重启」这一格 ActionView，用于播放按住/回弹过渡。 */
    private View quickRebootActionView;
    /** 按住态的圆形高亮底（透明度由动画驱动，见 {@link #animateQuickRebootHold}）。 */
    private Drawable quickRebootHoldBg;
    private ValueAnimator quickRebootHoldAnimator;
    /** KernelSU Miuix 的 holdDownState 观感：展开期间图标收缩到该比例。 */
    private static final float QUICK_REBOOT_HOLD_SCALE = 0.86f;
    /** 待开启的监视器类型：开关打开但悬浮窗权限未授予时记录，授权返回后自动开启。 */
    private MonitorType pendingMonitorType;
    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();
    private ViewPager2 mainPager;
    /** 「刷机」页（tab 1）的绑定与交互逻辑。 */
    private com.aurora.app.ui.flash.FlashPage flashPage;

    private View nativeContentView;

    /**
     * 四个页面 View 的固定实例（设备页 / 安全页 / 玩机页 / 设置页），由 {@link #setupTabPager()} 填充。
     *
     * <p><b>为什么需要它：</b>这四个 View 会被 {@link #setupTabPager()} 从 CoordinatorLayout 摘出、
     * 交给 ViewPager2 托管，只有在 ViewPager2 完成布局后才回到视图树上；而「设备页 / 设置页」
     * 的 {@code FragmentContainerView} 就在它们内部。FragmentManager 创建 Fragment 的 View 时
     * 需要按容器 id 定位容器（{@code FragmentStateManager.createView()}），若只能从
     * {@code nativeContentView} 子树查找，在「已摘出、尚未布局」的时间窗内必然查不到并抛
     * {@code IllegalArgumentException: No view found for id}（2026-10-08 启动即崩的根因）。
     * 本字段让 {@link #findViewById(int)} 能兜底扫描这几个未挂树的页面 View，
     * 使容器查找与视图树状态彻底解耦。
     */
    private View[] pageViews;

    private volatile boolean exploreAutoInstallTriggered = false;
    private volatile boolean exploreAutoInstallRunning = false;
    private volatile ExploreRootStatus cachedExploreRootStatus;
    private volatile long cachedExploreRootStatusAt;
    private volatile boolean exploreRootStatusRefreshRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable exploreResumeRefresh = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed() || currentTab != 2) {
                return;
            }
            updateExploreRootStatus(false);
            updateExploreUptime();
        }
    };

    private static final class ExploreRootStatus {
        final boolean rootGranted;
        final String manager;

        ExploreRootStatus(boolean rootGranted, String manager) {
            this.rootGranted = rootGranted;
            this.manager = manager;
        }
    }

    @Override
    public <T extends View> T findViewById(int id) {
        if (nativeContentView != null) {
            T view = nativeContentView.findViewById(id);
            if (view != null) {
                return view;
            }
        }
        // 兜底：页面 View 被 ViewPager2 摘出后不在 nativeContentView 子树内，
        // 但它们的实例仍在 pageViews 里（见字段注释）。FragmentManager 按容器 id
        // 定位容器时会经由本方法，缺了这段兜底就会抛「No view found for id」。
        if (pageViews != null) {
            for (View page : pageViews) {
                if (page == null) {
                    continue;
                }
                T view = page.findViewById(id);
                if (view != null) {
                    return view;
                }
            }
        }
        return super.findViewById(id);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 重建（主题切换 / 旋转屏幕 / 系统深色切换）时，先丢弃 FragmentManager 对「设备页 /
        // 设置页」两个页面 Fragment 的恢复状态，改由 installPageFragments() 一手创建。
        //
        // 为什么必须丢弃：这两个页面的容器位于会被 setupTabPager() 摘出视图树、交给 ViewPager2
        // 托管的页面 View 内部。自动恢复的时机早于 ViewPager2 首次布局，按容器 id 定位必然失败；
        // 更糟的是失败后 Fragment 对象仍留在 FragmentManager 里，会让后续按 tag 判重的安装
        // 误以为「已装好」而跳过 —— 于是整页永久空白。丢弃后重建路径与冷启动完全一致。
        //
        // 注意：此处 remove 的是 FragmentActivity 的内部键，STATE_TAB 等自定义状态不受影响。
        if (savedInstanceState != null) {
            savedInstanceState.remove(FRAGMENT_STATE_TAG);
        }
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightNavigationBars(false);
            controller.setAppearanceLightStatusBars(true);
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.navigationBars());
        }

        nativeContentView = getLayoutInflater().inflate(R.layout.activity_main, null, false);
        ComposeView composeView = new ComposeView(this);
        setContentView(composeView);

        Toolbar toolbar = findViewById(R.id.toolbar);
        appBarLayout = findViewById(R.id.appBarLayout);
        overflowMenuScrim = findViewById(R.id.overflowMenuScrim);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Aurora");
        }
        ensureToolbarVisible();
        // 顶栏「快捷重启」按钮的按压过渡：此时菜单刚 inflate，ActionView 可能还没布局，
        // bindQuickRebootActionAnimation() 内部会 post 到工具栏再去取。
        bindQuickRebootActionAnimation();
        // 恢复上次退出时仍开启的监视悬浮窗。
        // 错峰到首页首帧渲染完成后再拉起（前台服务 + addView 会抢冷启动主线程时间，
        // 与 ViewPager2 首帧争资源是「启动卡一下」的来源之一）。
        nativeContentView.postDelayed(this::restoreMonitorOverlays, 300L);
        if (overflowMenuScrim != null) {
            overflowMenuScrim.setOnClickListener(v -> {
                if (quickRebootPopupMenu != null) {
                    quickRebootPopupMenu.dismiss();
                } else {
                    setOverflowMenuScrimVisible(false);
                }
            });
        }

        View btnSelinuxManager = findViewById(R.id.btnSelinuxManager);
        if (btnSelinuxManager != null) {
            btnSelinuxManager.setOnClickListener(v ->
                    startActivity(new Intent(this, SelinuxManagerActivity.class)));
        }
        View btnPartitionManager = findViewById(R.id.btnPartitionManager);
        if (btnPartitionManager != null) {
            btnPartitionManager.setOnClickListener(v ->
                    startActivity(new Intent(this, PartitionManagerActivity.class)));
        }
        View btnFontLibraryBackup = findViewById(R.id.btnFontLibraryBackup);
        if (btnFontLibraryBackup != null) {
            btnFontLibraryBackup.setOnClickListener(v ->
                    startActivity(new Intent(this, FontLibraryBackupActivity.class)));
        }
        View btnModuleManager = findViewById(R.id.btnModuleManager);
        if (btnModuleManager != null) {
            btnModuleManager.setOnClickListener(v ->
                    startActivity(new Intent(this, ModuleManagerActivity.class)));
        }
        View btnModuleBackup = findViewById(R.id.btnModuleBackup);
        if (btnModuleBackup != null) {
            btnModuleBackup.setOnClickListener(v ->
                    startActivity(new Intent(this, com.aurora.app.ui.module.ModuleBackupActivity.class)));
        }
        View btnAppManager = findViewById(R.id.btnAppManager);
        if (btnAppManager != null) {
            btnAppManager.setOnClickListener(v -> openSystemAppManagement());
        }
        View btnPayload = findViewById(R.id.btnPayload);
        if (btnPayload != null) {
            btnPayload.setOnClickListener(v ->
                    startActivity(new Intent(this, PayloadActivity.class)));
        }
        View btnCpuControl = findViewById(R.id.btnCpuControl);
        if (btnCpuControl != null) {
            btnCpuControl.setOnClickListener(v ->
                    startActivity(new Intent(this, com.aurora.app.ui.cpu.CpuControlActivity.class)));
        }
        View btnDisplayTune = findViewById(R.id.btnDisplayTune);
        if (btnDisplayTune != null) {
            btnDisplayTune.setOnClickListener(v ->
                    startActivity(new Intent(this,
                            com.aurora.app.ui.display.DisplayTuneActivity.class)));
        }
        View btnFpsRecord = findViewById(R.id.btnFpsRecord);
        if (btnFpsRecord != null) {
            btnFpsRecord.setOnClickListener(v ->
                    startActivity(new Intent(this, com.aurora.app.ui.fps.FpsRecordActivity.class)));
        }
        View btnProcessList = findViewById(R.id.btnProcessList);
        if (btnProcessList != null) {
            btnProcessList.setOnClickListener(v ->
                    startActivity(new Intent(this, com.aurora.app.ui.process.ProcessListActivity.class)));
        }
        View btnAuroraPlugin = findViewById(R.id.btnAuroraPlugin);
        if (btnAuroraPlugin != null) {
            btnAuroraPlugin.setOnClickListener(v ->
                    startActivity(new Intent(this, AuroraPluginActivity.class)));
        }
        View btnGlobalDeviceSpoof = findViewById(R.id.btnGlobalDeviceSpoof);
        if (btnGlobalDeviceSpoof != null) {
            btnGlobalDeviceSpoof.setOnClickListener(v ->
                    startActivity(new Intent(this, GlobalDeviceSpoofActivity.class)));
        }
        View btnDeviceIdModify = findViewById(R.id.btnDeviceIdModify);
        if (btnDeviceIdModify != null) {
            btnDeviceIdModify.setOnClickListener(v ->
                    startActivity(new Intent(this, DeviceIdModifyActivity.class)));
        }
        View btnHideStatusBar = findViewById(R.id.btnHideStatusBar);
        if (btnHideStatusBar != null) {
            btnHideStatusBar.setOnClickListener(v -> toggleHideStatusBar());
        }

        // tab 恢复顺序：重建（主题变更等）优先用 savedInstanceState，其次 Intent 指定，最后首页。
        // 这样主题切换重建 MainActivity 后不会跳回首页（原实现只读 Intent，重建必回首页）。
        int defaultTab = 0;
        if (savedInstanceState != null) {
            defaultTab = savedInstanceState.getInt(STATE_TAB, 0);
        } else {
            Intent intent = getIntent();
            if (intent != null && intent.hasExtra(EXTRA_OPEN_TAB)) {
                int requested = intent.getIntExtra(EXTRA_OPEN_TAB, defaultTab);
                if (requested >= 0 && requested <= 3) {
                    defaultTab = requested;
                }
            }
        }
        if (defaultTab < 0 || defaultTab > 3) {
            defaultTab = 0;
        }
        // 记录当前已套用的主题版本，供 onResume 判断是否需要重建
        appliedThemeRevision = ThemeManager.getRevision(this);
        currentTab = defaultTab;
        BottomBarState.INSTANCE.setSelectedTab(defaultTab);
        // 同步底栏效果开关初值（Compose 侧据此决定 blur / 液态玻璃是否生效）
        BottomBarState.INSTANCE.setLiquidGlass(ThemeManager.isLiquidGlassEnabled(this));
        BottomBarState.INSTANCE.setBlur(ThemeManager.isBlurEnabled(this));

        // 页面 Fragment 的安装由 setupTabPager() 内部完成（需要先收集 pageViews，
        // 见 installPageFragments() 的说明）。此处再幂等地补一次，覆盖 setupTabPager()
        // 因 mainPager 缺失而中途返回的极端情况（按 tag 判重，正常是空操作）。
        setupTabPager();
        installPageFragments();

        // 绑定「刷机」页（tab 1）：构建分区 / 重启选项、恢复上次选择的镜像、准备工具链。
        flashPage = new com.aurora.app.ui.flash.FlashPage(this);
        flashPage.bind();

        FloatingBottomBarComposeKt.attachMainScreen(composeView, nativeContentView, new Function1<Integer, Unit>() {
            @Override
            public Unit invoke(Integer index) {
                selectTab(index != null ? index : 0, true);
                return Unit.INSTANCE;
            }
        });

        selectTab(defaultTab, false);
        // setCurrentItem 在 ViewPager2 尚未布局时不一定派发 onPageSelected，
        // 这里显式补一次，保证「启动就落在刷机页」时顶栏也是隐藏的。
        updateToolbarForTab(currentTab);
        // 同理补一次刷机页的可见性：否则「启动就在刷机页」时它的自动轮询不会启动，
        // 表现为设备状态要等切走再切回来才动。（其余 tab 默认就是不可见，无需处理。）
        if (flashPage != null && currentTab == 1) {
            flashPage.onTabShown();
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // 底部 inset 交由 Compose 悬浮底栏自己处理，避免出现“底部容器包裹感”
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        clearLog();
        // 设备信息与玩机页 uptime 依赖 4 个页面 View，而 setupTabPager() 已把它们从视图树移除、
        // 等待 ViewPager2 首次布局时重新挂载。若此处同步调用，findViewById 会返回 null，
        // 表现为「设备信息与运行时长不刷新」。因此延后到 ViewPager2 布局完成后执行。
        if (mainPager != null) {
            mainPager.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    setupDeviceInfo();
                    updateExploreUptime();
                }
            });
        } else {
            setupDeviceInfo();
            updateExploreUptime();
        }

        // 启动后静默检查一次更新。延后 3 秒是为了不和首屏的设备信息采集、页面 Fragment
        // 安装抢主线程 —— 检查本身在后台线程，但请求发出时的调度仍值得错开。
        maybeAutoCheckUpdate();
    }

    /**
     * 静默更新检查。
     *
     * <p>「静默」在这里有明确含义：<b>没更新或检查失败都不留任何痕迹</b>，
     * 只有确实发现新版本、且用户没对这个版本点过「稍后」时，才弹一次对话框。
     * 网络在部分环境不可达是常态，为此每次启动弹一个「检查失败」会非常烦人 ——
     * 检查失败要看详情，用户自己会去设置页点「检查更新」。
     */
    private void maybeAutoCheckUpdate() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            UpdateChecker.autoCheckIfDue(this, ThemeManager.isCheckUpdateEnabled(this), result -> {
                if (result == null || result.release == null) {
                    return;
                }
                // 用户对这个版本点过「稍后」→ 不再自动打扰；想装的话手动检查仍能看到
                if (result.release.tag.equals(UpdateChecker.getDismissedTag(this))) {
                    return;
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        UpdateDialog.show(this, result, null);
                    }
                });
            });
        }, 3000L);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 重建时恢复当前 tab（recreate() 会把 savedInstanceState 传回 onCreate）
        outState.putInt(STATE_TAB, currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「主题」子页返回：强调色 / 字号 / 动态取色变更后，重建一次以套用新配色。
        // 色彩模式变更由 setDefaultNightMode 自动重建（onCreate 已同步 appliedThemeRevision），
        // 因此这里不会重复重建 —— 避免"重建两次"卡死。
        int revision = ThemeManager.getRevision(this);
        if (appliedThemeRevision != revision) {
            appliedThemeRevision = revision;
            if (!isFinishing() && !isDestroyed()) {
                recreate();
            }
            return;
        }
        // 从「悬浮窗权限」设置页返回：权限已授予则自动开启之前请求的监视器
        if (pendingMonitorType != null && OverlayPermission.canDrawOverlays(this)) {
            MonitorType type = pendingMonitorType;
            pendingMonitorType = null;
            getSharedPreferences(PREFS_USAGE_MONITOR, MODE_PRIVATE)
                    .edit().putBoolean(type.prefKey, true).apply();
            MonitorOverlayService.startOverlay(this, type);
        }
        // 底栏视觉/交互开关：只改 Compose 状态与 ViewPager2 的一个开关，
        // 无需重建本 Activity（重建这个 God Activity 有明显卡顿）。
        BottomBarState.INSTANCE.setLiquidGlass(ThemeManager.isLiquidGlassEnabled(this));
        BottomBarState.INSTANCE.setBlur(ThemeManager.isBlurEnabled(this));
        if (mainPager != null) {
            mainPager.setUserInputEnabled(ThemeManager.isSwipeTabsEnabled(this));
        }
        // 兜底：正常情况下页面 Fragment 已在 onCreate 装好，这里是幂等空操作
        // （按 tag 判重）。万一因异常没装上，在此补一次，避免「必须重启 App 才有内容」。
        installPageFragments();
        mainHandler.removeCallbacks(exploreResumeRefresh);
        // 返回时先让界面完成首帧绘制，再延后刷新 Explore 状态，降低体感卡顿。
        if (currentTab == 2) {
            updateExploreUptime();
            View exploreView = findViewById(R.id.fragmentExplorePlaceholder);
            if (exploreView != null) {
                exploreView.postDelayed(exploreResumeRefresh, 120);
            } else {
                mainHandler.postDelayed(exploreResumeRefresh, 120);
            }
        }
        // 刷机页：回到前台（含从刷机子页返回 —— 那时设备多半已经换了个模式）立刻追一次探测，
        // 并恢复可见性轮询。是否真的探测由页面自己按「是否停在本页」判断。
        if (flashPage != null) {
            flashPage.onActivityResumed();
        }
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(exploreResumeRefresh);
        if (flashPage != null) {
            // 收回设备下拉（弹窗不随生命周期自动关闭，留着会 WindowLeaked），
            // 并停掉设备状态轮询 —— 切进刷机子页后主界面不该还在背后跑 su 抢闸门。
            flashPage.onActivityPaused();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // 刷机页的设备下拉是挂在 Activity 窗口上的 PopupWindow：主题切换会 recreate()，
        // 不在这里收掉就会 WindowLeaked；顺带注销 USB 插拔监听、清掉待执行的轮询回调。
        if (flashPage != null) {
            flashPage.release();
        }
        super.onDestroy();
    }

    private void selectTab(int tab) {
        selectTab(tab, true);
    }

    /**
     * 切换到指定页：ViewPager2 自带左右滑动与切换动画，
     * 底栏点击与手势滑动通过 {@link ViewPager2.OnPageChangeCallback} 双向同步。
     */
    private void selectTab(int tab, boolean animate) {
        if (mainPager == null || tab < 0 || tab > 3) {
            return;
        }
        if (mainPager.getCurrentItem() != tab) {
            mainPager.setCurrentItem(tab, animate);
        }
    }

    private void maybeAutoInstallCoreModuleSilently() {
        if (exploreAutoInstallTriggered || exploreAutoInstallRunning) {
            return;
        }
        exploreAutoInstallTriggered = true;
        exploreAutoInstallRunning = true;

        new Thread(() -> {
            try {
                if (!canExecuteSuAsRoot()) {
                    return;
                }
                if (isCoreModulePresentViaSu()) {
                    return;
                }

                File zip = extractAssetToCache("aurora_box_module.zip", "aurora_box_module_autoinstall.zip");
                if (zip == null || !zip.exists()) {
                    return;
                }

                // 静默执行：4 种安装命令依次尝试（成功即停止）
                String p = shellEscape(zip.getAbsolutePath());
                String[] candidates = new String[]{
                        "magisk --install-module " + p,
                        "/data/adb/magisk/magisk --install-module " + p,
                        "ksud module install " + p,
                        "apd module install " + p
                };
                boolean installed = false;
                for (String cmd : candidates) {
                    if (runSuCommand(cmd) == 0) {
                        installed = true;
                        break;
                    }
                }
                // 只有新模块确实装上了才清旧目录，避免「旧的删了、新的没装上」导致能力全失
                if (installed) {
                    cleanupLegacyCoreModuleViaSu();
                }
            } catch (Throwable ignored) {
            } finally {
                exploreAutoInstallRunning = false;
            }
        }).start();
    }

    /**
     * 判断核心扩展模块是否已安装。
     *
     * <p>模块 id 已由 {@code violet_box_module} 改名为 {@code aurora_box_module}。</p>
     */
    private boolean isCoreModulePresentViaSu() {
        // 兼容已安装与待更新目录
        String check = "[ -d /data/adb/modules/aurora_box_module ] || [ -d /data/adb/modules_update/aurora_box_module ]";
        if (runSuCommand(check) == 0) {
            // 新版已在位，清掉旧 id 的残留，避免两个模块在同一开机阶段互相覆盖
            cleanupLegacyCoreModuleViaSu();
            return true;
        }
        return false;
    }

    /**
     * 清掉旧 id（{@code violet_box_module}）的模块目录。
     *
     * <p>改名后若只删新不删旧，两个模块会同时执行 set_uname / resetprop，
     * 启动顺序不保证，最终生效值不可预期。配置目录的迁移由模块的 customize.sh 负责。</p>
     */
    private void cleanupLegacyCoreModuleViaSu() {
        String legacy = "[ -d /data/adb/modules/violet_box_module ] || [ -d /data/adb/modules_update/violet_box_module ]";
        if (runSuCommand(legacy) == 0) {
            runSuCommand("rm -rf /data/adb/modules/violet_box_module /data/adb/modules_update/violet_box_module");
        }
    }

    private File extractAssetToCache(String assetName, String outName) {
        try {
            File out = new File(getCacheDir(), outName);
            try (InputStream is = getAssets().open(assetName);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int runSuCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(25, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                return -1;
            }
            return process.exitValue();
        } catch (Exception ignored) {
            return -1;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String shellEscape(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * 把 4 个页面 View 从 CoordinatorLayout 中装入 ViewPager2：
     * <ul>
     *   <li>页面 View 仍是 nativeContentView 的后代（ViewPager2 在 CoordinatorLayout 内），
     *       {@link #findViewById} 覆写遍历整棵 nativeContentView，因此页面内所有 id 引用不受影响；</li>
     *   <li>{@code setOffscreenPageLimit(3)}：4 页全部预加载，FragmentContainerView（设备页）
     *       不会被 ViewPager2 回收销毁，fragment 生命周期安全；</li>
     *   <li>底栏点击（{@link #selectTab}）与手势滑动通过 OnPageChangeCallback 双向同步。</li>
     * </ul>
     */
    private void setupTabPager() {
        mainPager = findViewById(R.id.mainPager);
        if (mainPager == null) {
            return;
        }
        View[] pages = new View[]{
                findViewById(R.id.contentDevice),
                findViewById(R.id.contentHome),
                findViewById(R.id.fragmentExplorePlaceholder),
                findViewById(R.id.fragmentSettingsPlaceholder)
        };
        // 保存页面 View 的固定实例：交给 ViewPager2 之后它们就离开了 nativeContentView
        // 子树，findViewById 需要靠这份引用才能定位到页面内部的 FragmentContainerView。
        pageViews = pages;
        // 从 CoordinatorLayout 解绑，交给 ViewPager2 管理。
        // 关键：XML 中 3 个页面是 visibility="gone"（原实现靠 showOnlyTabContent 切换可见性），
        // 现在由 ViewPager2 控制显示，必须全部置为 VISIBLE，否则滑到该页会空白。
        for (View page : pages) {
            if (page == null) {
                continue;
            }
            page.setVisibility(View.VISIBLE);
            // 重置为 ViewPager2 需要的铺满参数（原 CoordinatorLayout.LayoutParams 带
            // layout_behavior 等属性，直接复用可能导致 item 尺寸异常）
            page.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (page.getParent() instanceof ViewGroup) {
                ((ViewGroup) page.getParent()).removeView(page);
            }
        }
        mainPager.setAdapter(new TabPagerAdapter(pages));
        mainPager.setOffscreenPageLimit(pages.length - 1);
        mainPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentTab = position;
                BottomBarState.INSTANCE.setSelectedTab(position);
                updateToolbarForTab(position);
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle("Aurora");
                }
                // 切到“玩机”(Explore) 时：静默检测并自动安装核心模块（只触发一次）
                if (position == 2) {
                    maybeAutoInstallCoreModuleSilently();
                }
                // 切到“刷机”时：让本页的自动轮询跑起来（切走时停掉），并立刻追一次设备探测
                if (flashPage != null) {
                    if (position == TAB_FLASH) {
                        flashPage.onTabShown();
                    } else {
                        // 离开刷机页：收回设备状态下拉（PopupWindow 挂在 Activity 窗口上，
                        // 跨页留着会一直浮在其它页面之上），并停掉设备轮询。
                        flashPage.onTabHidden();
                    }
                }
            }
        });

        // 页面 Fragment 的安装放在这里而不是 onCreate：本方法刚完成 pageViews 的收集，
        // 此时 installPageFragments() 才能通过 findViewById 的页面 View 兜底分支定位到容器。
        // 注意 Fragment 的 View 实际创建会被 FragmentManager 推迟到 onStart 之后
        // （Activity 达到 ACTIVITY_CREATED 时），那一刻 ViewPager2 还没开始布局，
        // 所以容器查找必须与视图树状态无关 —— 这正是 pageViews 兜底存在的意义。
        installPageFragments();
    }

    /**
     * 安装「设备页 / 设置页」两个页面 Fragment（按 tag 判重，幂等，可重复调用）。
     *
     * <p><b>调用时机</b>：{@link #setupTabPager()} 内部（页面 View 收集完 → {@code pageViews} 就绪），
     * 以及 {@link #onResume()} 兜底一次。
     *
     * <p><b>为什么容器查找能成功（本方法的核心）：</b>
     * FragmentManager 创建 Fragment 的 View 时（{@code FragmentStateManager.createView()}），
     * 若 {@code mContainer} 为空则按 {@code mContainerId} 定位容器，走的是
     * {@code FragmentActivity.findViewById()} —— 也就是本类 {@link #findViewById(int)} 的重写版。
     * 而这两个页面的 {@code FragmentContainerView} 位于会被 {@link #setupTabPager()} 摘出
     * CoordinatorLayout、交给 ViewPager2 托管的页面 View 内部；ViewPager2 要等首次布局
     * 才把页面挂回视图树，届时 {@code nativeContentView} 子树里<b>查不到</b>容器。
     * 因此 {@link #findViewById(int)} 增加了对 {@code pageViews} 的兜底扫描
     * （页面 View 是预创建的固定实例，容器永远在它们内部，与是否挂树无关）。
     *
     * <p><b>时序要点：</b>{@code commitNowAllowingStateLoss()} 提交时 FragmentManager 仍处于
     * {@code CREATED}（{@code onCreate} 阶段），Fragment 的 View 创建会被推迟到
     * {@code FragmentActivity.onStart()} 的 {@code dispatchActivityCreated} —— 那一刻
     * ViewPager2 尚未 measure/layout。这正是「提交时容器可达、创建 View 时不可达」的
     * 时间窗，也是 2026-10-08 启动即崩
     * （{@code IllegalArgumentException: No view found for id .../device_fragment_container}）
     * 的根因；{@code pageViews} 兜底扫平了这个窗口。
     *
     * <p><b>更早的历史坑（现象：设备页 / 设置页整页空白，必须重启 App 才恢复）：</b>
     * 最初这两页在 XML 里用 {@code android:name} <b>静态声明</b>。{@code FragmentContainerView}
     * 静态声明的添加路径是 {@code add(View, Fragment, tag)}，会把容器 <b>View 引用</b>直接
     * 赋给 Fragment（{@code mContainerId == 0}），因此首次启动永远正常；但 Activity 重建时
     * FragmentManager 只能从 {@code savedInstanceState} 恢复，<b>View 引用无法序列化</b>、
     * 只剩 {@code mContainerId == 0} → {@code createView} 时 {@code container == null}
     * → View 不挂载 → 整页空白（不抛异常，故只是白屏）。现改为代码创建 +
     * {@code onCreate} 移除 {@code android:support:fragments} 键放弃自动恢复，
     * 冷启动与重建走完全相同的路径。
     *
     * <p><b>两点加固</b>：① 两个容器各自独立判断，任一失配不再连累另一个；
     * ② 用 {@code commitNowAllowingStateLoss()} 并捕获异常，即使时序异常也不会静默放弃
     * （这两个页面 Fragment 不承载需要恢复的状态，放弃状态保存无副作用）。
     */
    private void installPageFragments() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        try {
            if (fm.findFragmentByTag(TAG_DEVICE_FRAGMENT) == null
                    && findViewById(R.id.device_fragment_container) != null) {
                fm.beginTransaction()
                        .replace(R.id.device_fragment_container, new DeviceFragment(), TAG_DEVICE_FRAGMENT)
                        .commitNowAllowingStateLoss();
            }
            if (fm.findFragmentByTag(TAG_SETTINGS_FRAGMENT) == null
                    && findViewById(R.id.settingsFragmentContainer) != null) {
                fm.beginTransaction()
                        .replace(R.id.settingsFragmentContainer, new SettingsFragment(), TAG_SETTINGS_FRAGMENT)
                        .commitNowAllowingStateLoss();
            }
        } catch (Throwable t) {
            // 单个页面安装失败不应影响另一个，也不能让主界面崩溃
            android.util.Log.w("MainActivity", "安装页面 Fragment 失败", t);
        }
    }

    /** ViewPager2 页面装载器：每个页面直接复用现有 View（懒加载不做，4 页固定）。 */
    private static final class TabPagerAdapter extends RecyclerView.Adapter<TabPagerAdapter.Holder> {

        private final View[] pages;

        TabPagerAdapter(View[] pages) {
            this.pages = pages;
        }

        @Override
        public int getItemCount() {
            return pages.length;
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            // viewType == position：直接以页面 View 作为 itemView
            View page = pages[viewType];
            // 页面 View 复用既有实例（非新建）：若仍挂在旧父容器上，入池前先解绑，
            // 否则 RecyclerView.addView 会抛「specified child already has a parent」。
            // 注意必须排除 RecyclerView 本身 —— 直接 removeView 会打乱其
            // ViewHolder ↔ child 的映射，只能由 RecyclerView 自己回收。
            if (page.getParent() instanceof ViewGroup
                    && !(page.getParent() instanceof RecyclerView)) {
                ((ViewGroup) page.getParent()).removeView(page);
            }
            return new Holder(page);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            // 页面 View 已作为 itemView 装载，无需额外绑定
        }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }

    /**
     * 按当前 tab 决定顶栏是否显示。
     *
     * <p>顶栏这一行（标题 Aurora + 监视器按钮 + 电源按钮 + 分隔线）现在<b>只在「设备」页保留</b>：
     * <ul>
     *   <li>「刷机」页自带页面头部，右上角就是设备状态下拉入口 —— 顶栏会把下拉挤下去；</li>
     *   <li>「玩机」页与「设置」页的正文自带标题与分组小标题，顶栏那一行纯属重复。</li>
     * </ul>
     * 因此除 tab 0 外一律<b>整行收起</b>。
     *
     * <p><b>代价</b>：顶栏上的「监视器」「电源」两个入口此后只在设备页可用。
     *
     * <p><b>为什么用「高度置 0」而不是 {@code setVisibility(GONE)}：</b>
     * 本页容器 ViewPager2 带 {@code appbar_scrolling_view_behavior}，测量与定位都来自
     * {@code HeaderScrollingViewBehavior}：
     * <pre>
     *   childHeight = availableHeight - header.getMeasuredHeight() + getScrollRange(header)
     *   childTop    = dependency.getBottom()
     * </pre>
     * CoordinatorLayout 的 onMeasure/onLayout 会<b>跳过 GONE 的子 View</b>，
     * 于是 AppBar 的 {@code getMeasuredHeight()} 与 {@code getBottom()} 都停在收起前的旧值
     * —— ViewPager2 既不会上移，高度也算多出一行，表现为顶部留一条空带、底部被裁掉一段。
     * 把高度改成 0 则两个量都会被真实重算（{@code getTotalScrollRange()} 本来就是 0，
     * 因为 toolbar 没配 {@code layout_scrollFlags}），结果完全确定。
     */
    private void updateToolbarForTab(int tab) {
        if (appBarLayout == null) {
            return;
        }
        boolean show = tab == TAB_DEVICE;
        ViewGroup.LayoutParams params = appBarLayout.getLayoutParams();
        int target = show ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
        if (params != null && params.height != target) {
            params.height = target;
            appBarLayout.setLayoutParams(params);
        }
        if (show) {
            appBarLayout.bringToFront();
        }
    }

    private void ensureToolbarVisible() {
        updateToolbarForTab(currentTab);
    }

    /** 打开内置应用管理（列表、提取 APK、卸载、Root 冻结与备份等）。 */
    private void openSystemAppManagement() {
        startActivity(new Intent(this, AppManagerActivity.class));
    }

    private void setViewVisibilitySafe(int viewId, int visibility) {
        View v = findViewById(viewId);
        if (v != null) {
            v.setVisibility(visibility);
        }
    }

    private void showQuickRebootConfirmDialog(String title, String command) {
        if (!canExecuteSuAsRoot()) {
            Toast.makeText(this, "请先授予应用ROOT权限", Toast.LENGTH_SHORT).show();
            return;
        }

        new androidx.appcompat.app.AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(title)
                .setMessage("即将执行重启操作，是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> executeQuickRebootCommand(command))
                .show();
    }

    private void executeQuickRebootCommand(String command) {
        Thread t = new Thread(() -> {
            String[] suBins = new String[]{"su", "/system/bin/su", "/system/xbin/su"};
            boolean success = false;
            for (String suBin : suBins) {
                Process process = null;
                try {
                    process = new ProcessBuilder(suBin, "-c", command).redirectErrorStream(true).start();
                    boolean finished = process.waitFor(1500, TimeUnit.MILLISECONDS);
                    if (finished && process.exitValue() == 0) {
                        success = true;
                        break;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (process != null) {
                        process.destroy();
                    }
                }
            }
            if (!success) {
                runOnUiThread(() -> Toast.makeText(this, "执行失败：请检查ROOT权限或设备支持情况", Toast.LENGTH_SHORT).show());
            }
        });
        t.start();
    }

    /**
     * 隐藏/恢复状态栏（玩机页「扩展功能」按钮）：
     * 以 root 权限执行 {@link #HIDE_STATUSBAR_SCRIPT}（脚本自带 toggle），
     * 执行后读回 sb_hidden 标志，Toast 反馈当前状态。
     */
    private void toggleHideStatusBar() {
        if (!canExecuteSuAsRoot()) {
            Toast.makeText(this, "请先授予应用ROOT权限", Toast.LENGTH_SHORT).show();
            return;
        }
        Thread t = new Thread(() -> {
            // 执行 toggle 脚本
            execSuCapture(HIDE_STATUSBAR_SCRIPT);
            // 读回状态用于反馈
            String flag = execSuCapture("settings get global sb_hidden");
            boolean hidden = "1".equals(flag.trim());
            runOnUiThread(() -> Toast.makeText(this,
                    hidden ? "状态栏已隐藏" : "状态栏已恢复", Toast.LENGTH_SHORT).show());
        });
        t.start();
    }

    /**
     * 以 root 权限执行一段 shell 脚本并返回 stdout（合并 stderr）。
     * 依次尝试多个 su 路径；全部失败返回空串。
     */
    private String execSuCapture(String script) {
        String[] suBins = new String[]{"su", "/system/bin/su", "/system/xbin/su"};
        for (String suBin : suBins) {
            Process process = null;
            try {
                process = new ProcessBuilder(suBin, "-c", script).redirectErrorStream(true).start();
                if (process.waitFor(3000, TimeUnit.MILLISECONDS)) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                        return sb.toString().trim();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        return "";
    }

    private void resetAllTabs() {
        int defaultColor = ContextCompat.getColor(this, R.color.nav_item_text);
        
        iconHome.setColorFilter(defaultColor);
        iconDevice.setColorFilter(defaultColor);
        iconExplore.setColorFilter(defaultColor);
        iconSettings.setColorFilter(defaultColor);
        
        textHome.setTextColor(defaultColor);
        textDevice.setTextColor(defaultColor);
        textExplore.setTextColor(defaultColor);
        textSettings.setTextColor(defaultColor);

        navHome.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        navDevice.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        navExplore.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        navSettings.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
    }

    private void animateColorTransition(ImageView icon, TextView text, int fromColor, int toColor) {
        ValueAnimator animator = ValueAnimator.ofObject(argbEvaluator, fromColor, toColor);
        animator.setDuration(220);
        animator.addUpdateListener(animation -> {
            int animatedColor = (int) animation.getAnimatedValue();
            icon.setColorFilter(animatedColor);
            text.setTextColor(animatedColor);
        });
        animator.start();
    }

    private void animateNavScale(LinearLayout targetNav) {
        if (targetNav == null) return;
        targetNav.setScaleX(0.94f);
        targetNav.setScaleY(0.94f);
        targetNav.animate()
                .scaleX(1.06f)
                .scaleY(1.06f)
                .setDuration(140)
                .setInterpolator(new OvershootInterpolator(1.15f))
                .withEndAction(() -> targetNav.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(120)
                        .start())
                .start();
    }

    private void moveLiquidIndicator(LinearLayout targetNav, boolean animate) {
        if (targetNav == null || navLiquidIndicator == null || navRow == null) return;
        targetNav.post(() -> {
            int targetWidth = targetNav.getWidth();
            if (targetWidth <= 0) return;

            float targetX = targetNav.getX();
            android.view.ViewGroup.LayoutParams params = navLiquidIndicator.getLayoutParams();
            if (params.width != targetWidth) {
                params.width = targetWidth;
                navLiquidIndicator.setLayoutParams(params);
            }

            if (!animate) {
                navLiquidIndicator.setTranslationX(targetX);
                navLiquidIndicator.setScaleX(1f);
                return;
            }

            float currentX = navLiquidIndicator.getTranslationX();
            float distance = Math.abs(targetX - currentX);
            float stretchScale = Math.min(1.28f, 1f + distance / 300f);
            boolean edgeCollision = targetNav == navHome || targetNav == navSettings;
            float direction = targetX >= currentX ? 1f : -1f;
            float overshootPx = edgeCollision ? Math.min(18f, Math.max(6f, distance * 0.08f)) : 0f;
            float collideX = edgeCollision ? (targetX + direction * overshootPx) : targetX;

            ObjectAnimator stretch = ObjectAnimator.ofFloat(navLiquidIndicator, View.SCALE_X, 1f, stretchScale, 1f);
            stretch.setDuration(360);
            stretch.setInterpolator(new DecelerateInterpolator());
            float squashScale = Math.max(0.82f, 1f - (stretchScale - 1f) * 0.65f);
            ObjectAnimator squash = ObjectAnimator.ofFloat(navLiquidIndicator, View.SCALE_Y, 1f, squashScale, 1.06f, 1f);
            squash.setDuration(420);
            squash.setInterpolator(new OvershootInterpolator(1.05f));

            ObjectAnimator slide = ObjectAnimator.ofFloat(navLiquidIndicator, View.TRANSLATION_X, currentX, collideX, targetX);
            slide.setDuration(420);
            slide.setInterpolator(new DecelerateInterpolator());
            ObjectAnimator collideSquash = ObjectAnimator.ofFloat(navLiquidIndicator, View.SCALE_X, 1f, 1f);
            if (edgeCollision) {
                collideSquash = ObjectAnimator.ofFloat(
                        navLiquidIndicator,
                        View.SCALE_X,
                        1f,
                        Math.max(0.92f, 1f - overshootPx / 120f),
                        1f
                );
                collideSquash.setDuration(220);
                collideSquash.setStartDelay(210);
                collideSquash.setInterpolator(new OvershootInterpolator(1.2f));
            }

            AnimatorSet liquidSet = new AnimatorSet();
            liquidSet.playTogether(stretch, squash, slide, collideSquash);
            liquidSet.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    navLiquidIndicator.animate()
                            .scaleX(0.97f)
                            .scaleY(1.03f)
                            .setDuration(90)
                            .withEndAction(() -> navLiquidIndicator.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(110)
                                    .setInterpolator(new OvershootInterpolator(1.1f))
                                    .start())
                            .start();
                }
            });
            liquidSet.start();
        });
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_usage_monitor) {
            showUsageMonitorDialog();
            return true;
        }
        if (itemId == R.id.action_overflow_quick_reboot) {
            View anchor = findToolbarActionView(R.id.action_overflow_quick_reboot);
            if (anchor == null) {
                Toolbar toolbar = findViewById(R.id.toolbar);
                if (toolbar != null) {
                    anchor = toolbar;
                }
            }
            showQuickRebootPopupMenu(anchor);
            return true;
        }
        if (itemId == R.id.action_quick_reboot_system) {
            showQuickRebootConfirmDialog("重启系统", "reboot");
            return true;
        }
        if (itemId == R.id.action_quick_reboot_bootloader) {
            showQuickRebootConfirmDialog("重启到 Bootloader", "reboot bootloader");
            return true;
        }
        if (itemId == R.id.action_quick_reboot_fastbootd) {
            showQuickRebootConfirmDialog("重启到 FastbootD", "reboot fastboot");
            return true;
        }
        if (itemId == R.id.action_quick_reboot_recovery) {
            showQuickRebootConfirmDialog("重启到 Recovery", "reboot recovery");
            return true;
        }
        if (itemId == R.id.action_quick_reboot_edl) {
            showQuickRebootConfirmDialog("重启到 EDL", "reboot edl");
            return true;
        }
        if (itemId == R.id.action_quick_reboot_safe_mode) {
            showQuickRebootConfirmDialog("重启到安全模式", "setprop persist.sys.safemode 1; reboot");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 使用监视器设置弹窗：6 个监视器开关，点击【确定】后保存到本地。
     */
    private void showUsageMonitorDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_usage_monitor, null);
        SwitchCompat swLoad = view.findViewById(R.id.swMonitorLoad);
        SwitchCompat swProcess = view.findViewById(R.id.swMonitorProcess);
        SwitchCompat swThread = view.findViewById(R.id.swMonitorThread);
        SwitchCompat swMini = view.findViewById(R.id.swMonitorMini);
        SwitchCompat swFps = view.findViewById(R.id.swMonitorFps);
        SwitchCompat swTemperature = view.findViewById(R.id.swMonitorTemperature);
        AuroraSwitchStyler.apply(this, swLoad);
        AuroraSwitchStyler.apply(this, swProcess);
        AuroraSwitchStyler.apply(this, swThread);
        AuroraSwitchStyler.apply(this, swMini);
        AuroraSwitchStyler.apply(this, swFps);
        AuroraSwitchStyler.apply(this, swTemperature);
        SharedPreferences prefs = getSharedPreferences(PREFS_USAGE_MONITOR, MODE_PRIVATE);
        // 先回显已保存的开关状态（此时尚未设置监听器，不会误触发悬浮窗操作）
        swLoad.setChecked(prefs.getBoolean(MonitorType.SYSTEM.prefKey, false));
        swProcess.setChecked(prefs.getBoolean(MonitorType.PROCESS.prefKey, false));
        swThread.setChecked(prefs.getBoolean(MonitorType.THREAD.prefKey, false));
        swMini.setChecked(prefs.getBoolean(MonitorType.MINI.prefKey, false));
        swFps.setChecked(prefs.getBoolean(MonitorType.FPS.prefKey, false));
        swTemperature.setChecked(prefs.getBoolean(MonitorType.TEMPERATURE.prefKey, false));
        // 开关即生效：切换时立即创建/销毁对应悬浮窗，并保存状态
        swLoad.setOnCheckedChangeListener((buttonView, isChecked) -> applyMonitorSwitch(MonitorType.SYSTEM, isChecked));
        swProcess.setOnCheckedChangeListener((buttonView, isChecked) -> applyMonitorSwitch(MonitorType.PROCESS, isChecked));
        swThread.setOnCheckedChangeListener((buttonView, isChecked) -> applyMonitorSwitch(MonitorType.THREAD, isChecked));
        swMini.setOnCheckedChangeListener((buttonView, isChecked) -> applyMonitorSwitch(MonitorType.MINI, isChecked));
        swFps.setOnCheckedChangeListener((buttonView, isChecked) -> applyMonitorSwitch(MonitorType.FPS, isChecked));
        swTemperature.setOnCheckedChangeListener((buttonView, isChecked) -> applyMonitorSwitch(MonitorType.TEMPERATURE, isChecked));
        AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setView(view)
                .setPositiveButton("确定", null)
                .create();
        // 替换 Material3 自带的窗口背景（默认 28dp 大圆角）为 iOS 风格的 20dp 圆角。
        // 注意是给整个 window 设背景而不是给 content view 设：
        // AlertDialog 的按钮区由系统创建、位于自定义 view 之外，
        // 只改 view 背景会让「确定」按钮浮在圆角卡片外面。
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog_rounded);
        }
        dialog.show();
    }

    /**
     * 开关状态应用：开启 → 启动对应悬浮窗；关闭 → 销毁对应悬浮窗。
     * 开启前检查悬浮窗权限，未授权则跳转系统设置并记录待开启类型，
     * 用户授权返回后在 onResume 中自动补开。
     */
    private void applyMonitorSwitch(MonitorType type, boolean checked) {
        SharedPreferences prefs = getSharedPreferences(PREFS_USAGE_MONITOR, MODE_PRIVATE);
        if (checked) {
            if (!OverlayPermission.canDrawOverlays(this)) {
                pendingMonitorType = type;
                OverlayPermission.toastNeedPermission(this);
                OverlayPermission.request(this);
                return; // 状态暂不保存，等授权成功后统一保存并开启
            }
            prefs.edit().putBoolean(type.prefKey, true).apply();
            MonitorOverlayService.startOverlay(this, type);
        } else {
            prefs.edit().putBoolean(type.prefKey, false).apply();
            MonitorOverlayService.stopOverlay(this, type);
        }
    }

    /**
     * 应用启动时恢复已开启的监视器：
     * 遍历 6 种类型，凡开关状态为开且已具备悬浮窗权限的，重新拉起对应悬浮窗。
     */
    private void restoreMonitorOverlays() {
        if (!OverlayPermission.canDrawOverlays(this)) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_USAGE_MONITOR, MODE_PRIVATE);
        for (MonitorType type : MonitorType.values()) {
            if (prefs.getBoolean(type.prefKey, false)) {
                MonitorOverlayService.startOverlay(this, type);
            }
        }
    }

    private void showQuickRebootPopupMenu(View anchor) {
        if (anchor == null) return;
        PopupMenu popupMenu = new PopupMenu(this, anchor, Gravity.END, 0, R.style.ThemeOverlay_Aurora_ToolbarPopup);
        popupMenu.getMenuInflater().inflate(R.menu.quick_reboot_menu, popupMenu.getMenu());
        applyPopupMenuOffsetCompat(popupMenu, -dpToPx(22));
        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.action_quick_reboot_system) {
                showQuickRebootConfirmDialog("重启系统", "reboot");
                return true;
            }
            if (itemId == R.id.action_quick_reboot_bootloader) {
                showQuickRebootConfirmDialog("重启到 Bootloader", "reboot bootloader");
                return true;
            }
            if (itemId == R.id.action_quick_reboot_fastbootd) {
                showQuickRebootConfirmDialog("重启到 FastbootD", "reboot fastboot");
                return true;
            }
            if (itemId == R.id.action_quick_reboot_recovery) {
                showQuickRebootConfirmDialog("重启到 Recovery", "reboot recovery");
                return true;
            }
            if (itemId == R.id.action_quick_reboot_edl) {
                showQuickRebootConfirmDialog("重启到 EDL", "reboot edl");
                return true;
            }
            if (itemId == R.id.action_quick_reboot_safe_mode) {
                showQuickRebootConfirmDialog("重启到安全模式", "setprop persist.sys.safemode 1; reboot");
                return true;
            }
            return false;
        });
        popupMenu.setOnDismissListener(menu -> {
            quickRebootPopupMenu = null;
            setOverflowMenuScrimVisible(false);
            // 菜单收起 → 按钮回弹（KernelSU holdDownState 的逆过程）
            animateQuickRebootHold(false);
        });
        quickRebootPopupMenu = popupMenu;
        setOverflowMenuScrimVisible(true);
        popupMenu.show();
        animateQuickRebootHold(true);
    }

    // ───────────────── 顶栏「快捷重启」按钮的过渡动画 ─────────────────

    /**
     * 找到工具栏上某个 always 显示的菜单项对应的 View。
     *
     * <p>先按 id 直接查（ActionMenuPresenter 会把菜单项 id 设到 item view 上），
     * 查不到再遍历 ActionMenuView 的子 View 比对 {@code getItemData().getItemId()} ——
     * 后者不依赖 appcompat 的内部约定，是保底手段。
     */
    private View findToolbarActionView(int itemId) {
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar == null) {
            return null;
        }
        View direct = toolbar.findViewById(itemId);
        if (direct != null) {
            return direct;
        }
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (!(child instanceof ActionMenuView)) {
                continue;
            }
            ActionMenuView actions = (ActionMenuView) child;
            for (int j = 0; j < actions.getChildCount(); j++) {
                View action = actions.getChildAt(j);
                if (action instanceof ActionMenuItemView
                        && ((ActionMenuItemView) action).getItemData().getItemId() == itemId) {
                    return action;
                }
            }
        }
        return null;
    }

    /**
     * 给顶栏「快捷重启」按钮装上按压过渡用的圆形高亮底。
     *
     * <p>用自绘图替代 Toolbar 默认的 {@code selectableItemBackground}：默认背景是
     * <b>点击波纹</b>（松手即散），而这里需要的是「菜单开着就一直保持按下」的持续状态，
     * 两者不是一回事 —— KernelSU Miuix 版就是用 IconButton 的 holdDownState 做这件事的。
     */
    private void bindQuickRebootActionAnimation() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar == null) {
            return;
        }
        toolbar.post(() -> {
            View action = findToolbarActionView(R.id.action_overflow_quick_reboot);
            if (action == null) {
                return;
            }
            Drawable highlight =
                    androidx.core.content.ContextCompat.getDrawable(
                            this, R.drawable.bg_toolbar_action_hold);
            if (highlight != null) {
                highlight = highlight.mutate();
                highlight.setAlpha(0);
                action.setBackground(highlight);
            }
            quickRebootHoldBg = highlight;
            quickRebootActionView = action;
        });
    }

    /**
     * 顶栏「快捷重启」按钮的按住 / 回弹过渡。
     *
     * <p>直接对齐 KernelSU Miuix 版 {@code RebootListPopupMiuix} 里的
     * {@code IconButton(holdDownState = showTopPopup.value)}：
     * <ul>
     *   <li>{@code held = true}（菜单展开）：图标收缩到 {@link #QUICK_REBOOT_HOLD_SCALE}，
     *       圆形高亮底淡入 —— 用 Decelerate 快进，跟手；</li>
     *   <li>{@code held = false}（菜单关闭）：带轻微回弹（Overshoot）恢复原状，
     *       高亮淡出 —— 时长比进入长，收尾更松弛。</li>
     * </ul>
     */
    private void animateQuickRebootHold(boolean held) {
        final View target = quickRebootActionView;
        if (target == null) {
            return;
        }
        target.animate().cancel();
        target.animate()
                .scaleX(held ? QUICK_REBOOT_HOLD_SCALE : 1f)
                .scaleY(held ? QUICK_REBOOT_HOLD_SCALE : 1f)
                .setDuration(held ? 150L : 260L)
                .setInterpolator(held
                        ? new DecelerateInterpolator()
                        : new OvershootInterpolator(2.2f))
                .start();

        final Drawable highlight = quickRebootHoldBg;
        if (highlight == null) {
            return;
        }
        if (quickRebootHoldAnimator != null) {
            quickRebootHoldAnimator.cancel();
        }
        quickRebootHoldAnimator = ValueAnimator.ofInt(highlight.getAlpha(), held ? 255 : 0);
        quickRebootHoldAnimator.setDuration(held ? 150L : 220L);
        quickRebootHoldAnimator.setInterpolator(new DecelerateInterpolator());
        quickRebootHoldAnimator.addUpdateListener(animation -> {
            highlight.setAlpha((int) animation.getAnimatedValue());
            target.invalidate();
        });
        quickRebootHoldAnimator.start();
    }

    private void applyPopupMenuOffsetCompat(PopupMenu popupMenu, int verticalOffsetPx) {
        if (popupMenu == null) return;
        try {
            java.lang.reflect.Field popupField = PopupMenu.class.getDeclaredField("mPopup");
            popupField.setAccessible(true);
            Object helper = popupField.get(popupMenu);
            if (helper == null) return;
            java.lang.reflect.Method setVerticalOffsetMethod = helper.getClass().getMethod("setVerticalOffset", int.class);
            setVerticalOffsetMethod.invoke(helper, verticalOffsetPx);
        } catch (Exception ignored) {
            // 兼容不同 appcompat 版本，偏移失败时保持默认位置
        }
    }

    private void setOverflowMenuScrimVisible(boolean visible) {
        if (overflowMenuScrim == null) return;
        overflowMenuScrim.animate().cancel();
        if (visible) {
            overflowMenuScrim.bringToFront();
            overflowMenuScrim.setAlpha(0f);
            overflowMenuScrim.setVisibility(View.VISIBLE);
            overflowMenuScrim.animate().alpha(1f).setDuration(160).start();
        } else {
            overflowMenuScrim.animate()
                    .alpha(0f)
                    .setDuration(140)
                    .withEndAction(() -> overflowMenuScrim.setVisibility(View.GONE))
                    .start();
        }
    }

    private File getLogFile() {
        File dir = new File(getFilesDir(), "logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "detection.log");
    }

    private void appendLog(String text) {
        try {
            File logFile = getLogFile();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                writer.write("[" + ts + "] " + text);
                writer.newLine();
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_LOG_PATH, logFile.getAbsolutePath())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private void clearLog() {
        try {
            File logFile = getLogFile();
            if (logFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private String readRawLogFile() {
        File logFile = getLogFile();
        if (!logFile.exists()) return "暂无日志";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            return "读取日志失败: " + e.getMessage();
        }
        return sb.toString().trim();
    }

    private String readSelinuxRawLogFile() {
        String raw = readRawLogFile();
        if (raw.equals("暂无日志") || raw.startsWith("读取日志失败")) return raw;
        return raw;
    }

    private String readLogFile() {
        return readSelinuxRawLogFile().replace("\n", "\n");
    }

    private void copyLogsToClipboard(String logs) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("detection_logs", logs));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void openLogFileLocation() {
        File logFile = getLogFile();
        Uri uri = Uri.fromFile(logFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "text/plain");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "打开日志文件"));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开日志文件", Toast.LENGTH_SHORT).show();
        }
    }

    private void showLogsDialog() {
        String logs = readSelinuxRawLogFile();
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setView(R.layout.dialog_logs)
                .setPositiveButton("关闭", null)
                .create();
        dialog.show();

        TextView tvLogPath = dialog.findViewById(R.id.tvLogPath);
        TextView tvLogContent = dialog.findViewById(R.id.tvLogContent);
        android.widget.Button positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setText("关闭");
        }
        if (tvLogPath != null) {
            tvLogPath.setText("日志文件: " + getLogFile().getAbsolutePath() + "  ·  点击打开");
            tvLogPath.setOnClickListener(v -> openLogFileLocation());
        }
        if (tvLogContent != null) {
            tvLogContent.setText(logs);
            tvLogContent.setTextIsSelectable(true);
        }

        View copy = dialog.findViewById(R.id.btnCopyLog);
        if (copy != null) copy.setOnClickListener(v -> copyLogsToClipboard(logs));
        View open = dialog.findViewById(R.id.btnOpenLog);
        if (open != null) open.setOnClickListener(v -> openLogFileLocation());
    }

    private void setupDeviceInfo() {
        appendLog("开始设备信息采集");
        // 设备页 UI 由 DeviceFragment + fragment_device.xml 绑定；此处只写日志与玩机页联动。
        //
        // 注意：这里**不再做任何耗时的安全/Root 检测**。原实现在此处同步跑 SELinux 诊断
        // （fork `su -c getenforce`，超时 2.5s）与 27 项 Root 检测，正好压住首个可见帧。
        // 安全页已在本次改版中由「调节」页取代，相关检测代码整块移除；
        // 玩机页所需的 Root 状态由 updateExploreRootStatus() 单独在后台线程解析。
        // getBootSlot() 仅反射读系统属性，无进程开销，保留在主线程。
        String bootSlot = getBootSlot();
        appendLog("启动槽位 = " + bootSlot);

        updateExploreRootStatus(true);
    }

    private void updateExploreRootStatus() {
        updateExploreRootStatus(false);
    }

    private void updateExploreRootStatus(boolean forceRefresh) {
        TextView tvExploreRootStatus = findViewById(R.id.tvExploreRootStatus);
        TextView tvExploreRootManager = findViewById(R.id.tvExploreRootManager);
        ImageView ivExploreRootStatusIcon = findViewById(R.id.ivExploreRootStatusIcon);
        MaterialCardView cardExploreRootStatusIconBg = findViewById(R.id.cardExploreRootStatusIconBg);
        if (tvExploreRootStatus == null || tvExploreRootManager == null || ivExploreRootStatusIcon == null || cardExploreRootStatusIconBg == null) {
            return;
        }

        ExploreRootStatus cached = cachedExploreRootStatus;
        long cacheAge = SystemClock.elapsedRealtime() - cachedExploreRootStatusAt;
        if (cached != null) {
            renderExploreRootStatus(cached);
        } else {
            renderExploreRootStatusLoading();
        }

        if (!forceRefresh && cached != null && cacheAge < EXPLORE_ROOT_STATUS_CACHE_MS) {
            return;
        }
        if (exploreRootStatusRefreshRunning) {
            return;
        }

        exploreRootStatusRefreshRunning = true;
        new Thread(() -> {
            ExploreRootStatus resolved = resolveExploreRootStatus();
            cachedExploreRootStatus = resolved;
            cachedExploreRootStatusAt = SystemClock.elapsedRealtime();
            exploreRootStatusRefreshRunning = false;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                renderExploreRootStatus(resolved);
            });
        }, "explore-root-status").start();
    }

    private ExploreRootStatus resolveExploreRootStatus() {
        RootBeer rootBeer = new RootBeer(this);
        RootDetector advancedDetector = new RootDetector(this);
        boolean hasRootArtifacts = rootBeer.isRooted() || advancedDetector.isDeviceRooted();
        boolean rootGranted = hasRootArtifacts && canExecuteSuAsRoot();
        String manager = rootGranted ? detectGrantedRootManagerByAdbFeatures() : "";
        return new ExploreRootStatus(rootGranted, manager);
    }

    private void renderExploreRootStatusLoading() {
        TextView tvExploreRootStatus = findViewById(R.id.tvExploreRootStatus);
        TextView tvExploreRootManager = findViewById(R.id.tvExploreRootManager);
        ImageView ivExploreRootStatusIcon = findViewById(R.id.ivExploreRootStatusIcon);
        MaterialCardView cardExploreRootStatusIconBg = findViewById(R.id.cardExploreRootStatusIconBg);
        if (tvExploreRootStatus == null || tvExploreRootManager == null || ivExploreRootStatusIcon == null || cardExploreRootStatusIconBg == null) {
            return;
        }
        tvExploreRootStatus.setText("正在检测ROOT状态...");
        tvExploreRootStatus.setTextColor(ContextCompat.getColor(this, R.color.explore_slate_500));
        tvExploreRootManager.setText("检测完成后将自动刷新");
        tvExploreRootManager.setTextColor(ContextCompat.getColor(this, R.color.explore_slate_500));
        ivExploreRootStatusIcon.setImageResource(R.drawable.ic_ms_security);
        ivExploreRootStatusIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.explore_slate_500)));
        cardExploreRootStatusIconBg.setCardBackgroundColor(ContextCompat.getColor(this, R.color.explore_slate_100));
    }

    private void renderExploreRootStatus(ExploreRootStatus status) {
        TextView tvExploreRootStatus = findViewById(R.id.tvExploreRootStatus);
        TextView tvExploreRootManager = findViewById(R.id.tvExploreRootManager);
        ImageView ivExploreRootStatusIcon = findViewById(R.id.ivExploreRootStatusIcon);
        MaterialCardView cardExploreRootStatusIconBg = findViewById(R.id.cardExploreRootStatusIconBg);
        if (tvExploreRootStatus == null || tvExploreRootManager == null || ivExploreRootStatusIcon == null || cardExploreRootStatusIconBg == null || status == null) {
            return;
        }

        if (status.rootGranted) {
            tvExploreRootStatus.setText("已授予ROOT");
            tvExploreRootStatus.setTextColor(colorSemanticSafe());
            tvExploreRootManager.setText("Root Manager：" + status.manager);
            ivExploreRootStatusIcon.setImageResource(R.drawable.ic_ms_verified_user);
            ivExploreRootStatusIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.explore_emerald_600)));
            cardExploreRootStatusIconBg.setCardBackgroundColor(ContextCompat.getColor(this, R.color.explore_emerald_100));
        } else {
            tvExploreRootStatus.setText("未授予ROOT");
            tvExploreRootStatus.setTextColor(ContextCompat.getColor(this, R.color.ios_semantic_negative));
            tvExploreRootManager.setText("请授予应用ROOT权限，否则部分功能无法使用");
            ivExploreRootStatusIcon.setImageResource(R.drawable.ic_ms_root_not_granted);
            ivExploreRootStatusIcon.setImageTintList(null);
            cardExploreRootStatusIconBg.setCardBackgroundColor(ContextCompat.getColor(this, R.color.explore_rose_100));
        }
        tvExploreRootManager.setTextColor(ContextCompat.getColor(this, R.color.explore_slate_500));
    }

    private void updateExploreUptime() {
        TextView tvExploreUptimeValue = findViewById(R.id.tvExploreUptimeValue);
        if (tvExploreUptimeValue == null) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        long totalMinutes = elapsed / (60 * 1000);
        long days = totalMinutes / (24 * 60);
        long hours = (totalMinutes % (24 * 60)) / 60;
        long minutes = totalMinutes % 60;

        String uptimeText;
        if (days > 0) {
            uptimeText = days + "d " + hours + "h";
        } else if (hours > 0) {
            uptimeText = hours + "h " + minutes + "m";
        } else {
            uptimeText = minutes + "m";
        }
        tvExploreUptimeValue.setText(uptimeText);
    }


    private boolean canExecuteSuAsRoot() {
        String[][] commands = new String[][]{
                {"su", "-c", "id"},
                {"/system/bin/su", "-c", "id"},
                {"/system/xbin/su", "-c", "id"}
        };
        for (String[] command : commands) {
            Process process = null;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
                boolean finished = process.waitFor(1200, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroy();
                    continue;
                }
                if (process.exitValue() != 0) {
                    continue;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && line.contains("uid=0")) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        return false;
    }

    private String detectGrantedRootManagerByAdbFeatures() {
        boolean hasKernelSu = existsPathViaSu("/data/adb/ksud") || existsPathViaSu("/data/adb/ksd");
        boolean hasAPatch = existsPathViaSu("/data/adb/apd");
        boolean hasMagisk = existsPathViaSu("/data/adb/magisk.db");

        List<String> managers = new ArrayList<>();
        if (hasKernelSu) {
            managers.add("KernelSU");
        }
        if (hasAPatch) {
            managers.add("APatch");
        }
        if (hasMagisk) {
            managers.add("Magisk");
        }

        if (managers.isEmpty()) {
            return "未知（未匹配到 /data/adb 特征）";
        }
        return String.join(" & ", managers);
    }

    private boolean existsPathViaSu(String absolutePath) {
        String[] suBins = new String[]{"su", "/system/bin/su", "/system/xbin/su"};
        for (String suBin : suBins) {
            Process process = null;
            try {
                process = new ProcessBuilder(suBin, "-c", "[ -e \"" + absolutePath + "\" ]")
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(1200, TimeUnit.MILLISECONDS);
                if (finished && process.exitValue() == 0) {
                    return true;
                }
            } catch (Exception ignored) {
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        return false;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 读取当前应用版本号（settings 页「当前版本」展示用）。 */
    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知版本";
        }
    }


    private String getBootSlot() {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class, String.class);
            
            // 尝试读取 ro.boot.slot_suffix 属性
            String slotSuffix = (String) get.invoke(null, "ro.boot.slot_suffix", "");
            if (slotSuffix != null && !slotSuffix.isEmpty()) {
                if (slotSuffix.equalsIgnoreCase("_a") || slotSuffix.equalsIgnoreCase("a")) return "A 槽位";
                if (slotSuffix.equalsIgnoreCase("_b") || slotSuffix.equalsIgnoreCase("b")) return "B 槽位";
                return slotSuffix;
            }
            
            // 如果上一个为空，尝试读取 ro.boot.slot 属性
            String slot = (String) get.invoke(null, "ro.boot.slot", "");
            if (slot != null && !slot.isEmpty()) {
                if (slot.equalsIgnoreCase("a") || slot.equalsIgnoreCase("_a")) return "A 槽位";
                if (slot.equalsIgnoreCase("b") || slot.equalsIgnoreCase("_b")) return "B 槽位";
                return slot;
            }
            
            return "未启用 A/B 分区或获取失败";
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "未知";
    }
}