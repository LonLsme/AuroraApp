package com.aurora.app.ui.about;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.aurora.app.BuildConfig;
import com.aurora.app.R;
import com.aurora.app.ui.theme.ThemeManager;

/**
 * 关于页（独立 Activity，布局 {@code activity_about.xml}，Miuix preference 范式）。
 *
 * <p>本页承载四类只读信息，设置页只保留一行入口 {@code rowAbout}：
 * <ul>
 *   <li><b>版本信息</b> —— 版本名 / 版本码 / 包名 / 构建类型，全部取自
 *       {@link BuildConfig}，不写死，避免与 {@code app/build.gradle.kts} 脱节；</li>
 *   <li><b>开源</b> —— 本项目 GitHub 仓库、上游项目 violet_Box（紫罗兰Box）与 GPL-3.0 协议；</li>
 *   <li><b>鸣谢</b> —— KernelSU / 星野Scene / Miuix 三个参考来源；</li>
 *   <li><b>作者</b> —— 作者本人的三个公开账号入口（酷安 / GitHub / 抖音）。</li>
 * </ul>
 *
 * <p>顶栏是自绘的（{@code btnAboutBack}）：全局主题 {@code Base.Theme.Aurora} 继承
 * {@code NoActionBar}，{@code getSupportActionBar()} 恒为 null，用系统 ActionBar 既拿不到
 * 标题也拿不到返回键 —— 旧版本本页因此没有任何返回入口，只能靠系统返回键退出。
 *
 * <p>颜色全部走 {@code @color/ios_*} 语义色，主题（强调色 / AMOLED 覆盖层）由
 * {@code ThemeManager.init()} 注册的 Activity 生命周期回调自动套用，本类无需再调 setTheme。
 * 字号缩放靠 {@link #attachBaseContext(Context)}，与 {@code ThemeActivity} 同一套做法。
 */
public class AboutActivity extends AppCompatActivity {

    // ── 外部链接：全部有出处，不臆造 ──
    // 本项目仓库地址**不写死**，直接由 gradle.properties 的 aurora.updateRepo 拼出来：
    // 那个值已经通过 BuildConfig.UPDATE_REPO 驱动「检查更新」，两处共用一份配置，
    // Fork / 改仓库名时只改一个地方，不会出现「更新查 A 仓、关于页却指向 B 仓」的错位。
    private static final String URL_REPO = "https://github.com/" + BuildConfig.UPDATE_REPO;
    // 上游项目地址与 README.md / docs/ARCHITECTURE.md 中登记的完全一致。
    private static final String URL_UPSTREAM = "https://github.com/Smart-Paocai/violet_Box";
    private static final String URL_LICENSE = "https://www.gnu.org/licenses/gpl-3.0.html";
    private static final String URL_KERNEL_SU = "https://github.com/tiann/KernelSU";

    // ── 作者本人的三个账号（LonLsme）──
    // 上游作者 Smart-Paocai 的署名不在这张卡里，而是留在「开源 → 上游项目」
    // 与底部声明（© Smart-Paocai）—— GPL-3.0 要求保留原始版权声明，
    // 项目换人维护不等于把原作者抹掉。
    private static final String URL_COOLAPK = "https://www.coolapk.com/u/26622080";
    private static final String URL_GITHUB = "https://github.com/LonLsme";
    /** 抖音号。网页版主页路径要 sec_uid（MS4wLjAB…），抖音号拼不进去。 */
    private static final String DOUYIN_ID = "36398056279";
    /** 抖音号唯一能保证打开就是人的地址：按号搜索，type=user 直接落到用户 tab。 */
    private static final String URL_DOUYIN = "https://www.douyin.com/search/" + DOUYIN_ID + "?type=user";

    /** 字号缩放：让本页也跟随全局字号（createConfigurationContext，不触发 relaunch 循环）。 */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeManager.wrapFontScale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        bindBack();
        bindVersionInfo();
        bindLinks();
    }

    // ===== 顶部返回 =====

    private void bindBack() {
        View back = findViewById(R.id.btnAboutBack);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }
    }

    // ===== 版本信息 =====

    private void bindVersionInfo() {
        String versionName = BuildConfig.VERSION_NAME;
        String versionCode = String.valueOf(BuildConfig.VERSION_CODE);
        String buildType = BuildConfig.BUILD_TYPE;

        // 头部那句「1.1.0 (2)」：版本名 + 版本码，和设置页摘要用的是同一份来源
        setText(R.id.tvAboutVersion, versionName + " (" + versionCode + ")");
        setText(R.id.tvAboutVersionName, versionName);
        setText(R.id.tvAboutVersionCode, versionCode);
        // 包名取运行时的 getPackageName()：debug 后缀（如 .debug）也能如实反映
        setText(R.id.tvAboutPackageName, getPackageName());
        setText(R.id.tvAboutBuildType, "debug".equalsIgnoreCase(buildType)
                ? "debug（调试版）"
                : buildType);
        // 仓库地址去掉协议头，一行放得下；值仍是运行时拼的，与检查更新同源
        setText(R.id.tvAboutRepo, URL_REPO.replace("https://", ""));
    }

    private void setText(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) {
            tv.setText(text);
        }
    }

    // ===== 可点行 =====

    private void bindLinks() {
        click(R.id.rowAboutRepo, URL_REPO, "无法打开 GitHub 仓库页面");
        click(R.id.rowAboutUpstream, URL_UPSTREAM, "无法打开上游项目页面");
        click(R.id.rowAboutLicense, URL_LICENSE, "无法打开协议页面");
        click(R.id.rowAboutKernelSu, URL_KERNEL_SU, "无法打开 KernelSU 页面");
        click(R.id.rowAboutCoolapk, URL_COOLAPK, "无法打开酷安链接");
        click(R.id.rowAboutGithub, URL_GITHUB, "无法打开 GitHub 链接");
        click(R.id.rowAboutDouyin, URL_DOUYIN, "无法打开抖音链接");
        // 抖音号在布局里是写死的兜底值，这里用常量覆盖一次，改号时只改常量不会漏
        setText(R.id.tvAboutDouyin, DOUYIN_ID);
    }

    private void click(int viewId, String url, String failMessage) {
        View v = findViewById(viewId);
        if (v != null) {
            v.setOnClickListener(view -> openUrl(url, failMessage));
        }
    }

    private void openUrl(String url, String failMessage) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, failMessage, Toast.LENGTH_SHORT).show();
        }
    }
}
