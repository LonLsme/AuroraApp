package com.aurora.app.ui.guide;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.aurora.app.R;
import com.aurora.app.core.root.RootShell;
import com.aurora.app.ui.main.MainActivity;

/**
 * ROOT 引导页（应用启动入口）。
 *
 * <p>启动时在后台判定一次 root 权限：
 * <ul>
 *   <li><b>已授予</b>：不留痕迹，直接进 {@link MainActivity} 并结束自己 —— 用户看不到这个页面；</li>
 *   <li><b>未授予</b>：显示「未授予 ROOT」与说明，右侧按钮可继续进入主界面，
 *       不阻断使用（大部分功能无 root 也能用，只有刷机/玩机相关会失败）。</li>
 * </ul>
 *
 * <p>为什么要单独一个 Activity，而不是在主界面弹提示：主界面首帧很重（设备信息、CPU 轮询、
 * 页面 Fragment 安装），再插一次 su 会话会拖慢冷启动；独立页面可以在主界面创建之前完成判定，
 * 已授权时整条链路无额外开销。
 *
 * <p>检测走 {@link RootShell#isRootAvailable()}（自带缓存、内部有 3s 超时），
 * 必须放后台线程 —— 未授权时它会等一次 su 交互结果，方法本身会阻塞。
 */
public final class RootGuideActivity extends AppCompatActivity {

    /** 检测中的视图（默认可见）。 */
    private View loadingView;
    /** 未授权引导内容（默认隐藏）。 */
    private View contentView;

    /** 防重入：检测回调与按钮点击可能同时触发跳转。 */
    private boolean navigated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root_guide);

        loadingView = findViewById(R.id.guideLoading);
        contentView = findViewById(R.id.guideContent);
        findViewById(R.id.btnGuideEnter).setOnClickListener(v -> enterMain());

        new Thread(() -> {
            boolean granted;
            try {
                granted = RootShell.get().isRootAvailable();
            } catch (Exception ignored) {
                // 检测异常（su 不存在、会话被拒）按未授权处理，让用户仍能进入
                granted = false;
            }
            boolean result = granted;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (result) {
                    enterMain();
                } else {
                    showGuide();
                }
            });
        }, "root-guide-check").start();
    }

    /** 未授权：切到引导内容。 */
    private void showGuide() {
        loadingView.setVisibility(View.GONE);
        contentView.setVisibility(View.VISIBLE);
    }

    /**
     * 进入主界面并结束引导页。
     * 转发启动 Intent 的 extras（{@code --es tab N} 这类调试图标直达仍可用），
     * 两个页面之间不加转场动画，避免「白屏 → 白屏」的二次闪烁。
     */
    private void enterMain() {
        if (navigated) {
            return;
        }
        navigated = true;

        Intent target = new Intent(this, MainActivity.class);
        Intent source = getIntent();
        if (source != null && source.getExtras() != null) {
            target.putExtras(source.getExtras());
        }
        startActivity(target);
        overridePendingTransition(0, 0);
        finish();
    }
}
