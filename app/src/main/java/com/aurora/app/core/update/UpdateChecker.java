package com.aurora.app.core.update;

import android.content.Context;
import android.content.SharedPreferences;

import com.aurora.app.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 GitHub Releases 的版本检查。
 *
 * <p>只用 JDK 自带的 {@link HttpURLConnection} 与 Android 自带的 {@code org.json}，
 * 不引入任何新依赖 —— 检查更新是一年只跑几次的动作，不值得为它多背一个库。
 *
 * <p><b>查与装是分开的两层</b>：本类只负责「查」——
 * 拿到最新版本号、更新日志与 APK 资产的地址和摘要；下载交给 {@link ApkDownloader}，
 * 交给系统安装器交给 {@link UpdateInstaller}。这样切分是为了让「查」能在
 * 没有存储权限、没有安装权限的纯网络场景下独立工作，也便于单测。
 *
 * <p><b>摘要从哪来</b>（三级回落，见 {@link #parseLatest}）：GitHub 资产的
 * {@code digest} 字段 → Release 正文里的 {@code SHA-256: ...} → 同名
 * {@code .apk.sha256} 资产的地址（由下载器按需拉取）。三级都拿不到时，
 * 下载器会拒绝安装 —— 宁可让用户走浏览器手动装，也不装一个无法验证的包。
 *
 * <p><b>不上报任何设备信息。</b>请求里只有 User-Agent（GitHub API 强制要求），
 * 不含 IMEI、Android ID、机型、已装应用等任何可识别字段。
 *
 * <p><b>分层约束</b>：本类位于 {@code core} 层，因此<b>不 import 任何 {@code ui} 包</b>。
 * 「自动检查更新」这个开关存在哪个 prefs、键名叫什么，属于 {@code ui.theme.ThemeManager}
 * 的职责，由调用方以参数形式传进来（见 {@link #autoCheckIfDue(Context, boolean)}）。
 */
public final class UpdateChecker {

    /** 远端版本与本地一致。 */
    public static final int STATUS_UP_TO_DATE = 0;
    /** 远端有更新版本。 */
    public static final int STATUS_NEW_VERSION = 1;
    /** 连不上 / 超时 / DNS 失败。 */
    public static final int STATUS_NETWORK = 2;
    /** GitHub API 限流（未认证 60 次/小时/IP）。 */
    public static final int STATUS_RATE_LIMITED = 3;
    /** 仓库不存在，或还没有发布过 Release。 */
    public static final int STATUS_NOT_FOUND = 4;
    /** {@code aurora.updateRepo} 没配或格式不对。 */
    public static final int STATUS_BAD_CONFIG = 5;
    /** 其他 HTTP 错误 / 响应体无法解析。 */
    public static final int STATUS_HTTP = 6;

    /** 自动检查的最小间隔：24 小时。 */
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    /** 响应体上限，防止异常响应把内存吃光。Release 的 body 正常最多几十 KB。 */
    private static final int MAX_BODY_BYTES = 512 * 1024;

    private static final String PREFS = "aurora_update";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String KEY_DISMISSED_TAG = "dismissed_tag";

    private static volatile ExecutorService executor;

    private UpdateChecker() {
    }

    /** 一次检查的结果。{@link #release} 仅在 {@link #status} 为「有新版本」或「已是最新」时非空。 */
    public static final class Result {
        public final int status;
        public final Release release;
        /** 给用户看的一句话，可直接显示在界面上。 */
        public final String message;
        public final String localVersion;

        Result(int status, Release release, String message, String localVersion) {
            this.status = status;
            this.release = release;
            this.message = message;
            this.localVersion = localVersion;
        }

        public boolean isSuccess() {
            return status == STATUS_UP_TO_DATE || status == STATUS_NEW_VERSION;
        }

        public boolean hasUpdate() {
            return status == STATUS_NEW_VERSION;
        }
    }

    /** Release 里本项目关心的字段。 */
    public static final class Release {
        public final String tag;
        public final String title;
        public final String body;
        public final String pageUrl;
        /** 第一个 {@code .apk} 资产的直链；没有资产时为空串。 */
        public final String apkUrl;
        public final String publishedAt;
        /** APK 资产的原始文件名（{@code Aurora-v1.2.0.apk}）；没有资产时为空串。 */
        public final String apkName;
        /** APK 资产的字节数；GitHub 未提供时为 0。 */
        public final long apkSize;
        /**
         * APK 的 SHA-256（64 位小写十六进制），来自资产 {@code digest} 字段或 Release 正文。
         * 都取不到时为空串 —— 此时下载器会去拉 {@link #apkShaUrl}。
         */
        public final String apkSha256;
        /** 随包发布的 {@code .apk.sha256} 资产地址；没有时为空串。 */
        public final String apkShaUrl;

        Release(String tag, String title, String body, String pageUrl, String apkUrl,
                String publishedAt, String apkName, long apkSize, String apkSha256,
                String apkShaUrl) {
            this.tag = tag;
            this.title = title;
            this.body = body;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
            this.publishedAt = publishedAt;
            this.apkName = apkName;
            this.apkSize = apkSize;
            this.apkSha256 = apkSha256;
            this.apkShaUrl = apkShaUrl;
        }

        /** 是否有可直接下载并自动安装的 APK。 */
        public boolean hasApk() {
            return apkUrl != null && !apkUrl.isEmpty();
        }
    }

    public interface Callback {
        /** 一定在后台线程回调，调用方自行切主线程。 */
        void onResult(Result result);
    }

    // ==================== 对外入口 ====================

    /**
     * 后台检查一次最新版本，结果通过 {@code callback} 回到后台线程。
     *
     * <p>多个界面同时触发时共用一个单线程池，串行执行、不叠加请求。
     */
    public static void checkAsync(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        final String local = BuildConfig.VERSION_NAME;
        final String repo = BuildConfig.UPDATE_REPO;

        if (!isRepoValid(repo)) {
            callback.onResult(new Result(STATUS_BAD_CONFIG, null,
                    "未配置更新仓库，无法检查更新", local));
            return;
        }

        executor().execute(() -> {
            Result result;
            try {
                result = fetchLatest(repo, local);
            } catch (Throwable t) {
                // 网络层的任何意外都收敛成「网络错误」，绝不让检查更新把 App 弄崩
                result = new Result(STATUS_NETWORK, null,
                        "检查失败：" + describeThrowable(t), local);
            }
            if (result.isSuccess()) {
                markChecked(app);
            }
            callback.onResult(result);
        });
    }

    /**
     * 启动时的静默检查。
     *
     * <p>三个前置条件全部满足才会真正发请求：用户开着「自动检查更新」、
     * 距上次成功检查超过 24 小时、账号下已有版本信息可读。
     * 有更新时回调一次；无更新、失败都<b>静默丢弃</b>，不打扰用户。
     *
     * @param enabledByUser 用户是否开启「自动检查更新」（由 ui 层读 prefs 后传入）
     * @param onUpdate      发现新版本时在后台线程回调；调用方负责切主线程
     */
    public static void autoCheckIfDue(Context context, boolean enabledByUser, Callback onUpdate) {
        if (!enabledByUser || !isAutoCheckDue(context)) {
            return;
        }
        checkAsync(context, result -> {
            if (result.hasUpdate()) {
                onUpdate.onResult(result);
            }
        });
    }

    /** 距上次成功检查是否已超过 24 小时（或从未检查过）。 */
    public static boolean isAutoCheckDue(Context context) {
        long last = prefs(context).getLong(KEY_LAST_CHECK, 0L);
        if (last <= 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        // 时钟被往回调过（last 在未来）时，直接认为该检查了，避免永远不再检查
        if (last > now) {
            return true;
        }
        return now - last >= AUTO_CHECK_INTERVAL_MS;
    }

    /** 上次成功检查的时间戳（毫秒），0 表示从未检查成功过。 */
    public static long getLastCheckTime(Context context) {
        return prefs(context).getLong(KEY_LAST_CHECK, 0L);
    }

    /** 记录「刚刚检查成功」。只在该次检查拿到了有效版本信息时调用。 */
    static void markChecked(Context context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    /** 用户点过「稍后再说」的版本 tag。同一 tag 不会再自动弹出。 */
    public static String getDismissedTag(Context context) {
        return prefs(context).getString(KEY_DISMISSED_TAG, "");
    }

    public static void setDismissedTag(Context context, String tag) {
        prefs(context).edit().putString(KEY_DISMISSED_TAG, tag == null ? "" : tag).apply();
    }

    /** 当前版本名，省得每个调用点都写 BuildConfig。 */
    public static String localVersionName() {
        return BuildConfig.VERSION_NAME;
    }

    /**
     * 上次成功检查时间的可读形式，给设置页摘要与对话框复用。
     *
     * <p>放在这里而不是各界面各写一份：两处的文案必须一致，
     * 否则设置页写「昨天 18:20」、对话框写「2026-10-21 18:20」会显得像两个功能。
     */
    public static String formatLastCheck(Context context) {
        long millis = getLastCheckTime(context);
        if (millis <= 0L) {
            return "尚未检查";
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(millis));
    }

    /** GitHub 上本项目的仓库页（不是 API 地址）。 */
    public static String repoPageUrl() {
        return "https://github.com/" + BuildConfig.UPDATE_REPO;
    }

    public static String releasesPageUrl() {
        return repoPageUrl() + "/releases";
    }

    // ==================== 网络 ====================

    private static Result fetchLatest(String repo, String local) throws Exception {
        String api = "https://api.github.com/repos/" + repo + "/releases/latest";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(api).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            // GitHub API 强制要求 User-Agent，缺失会直接 403
            conn.setRequestProperty("User-Agent", "Aurora-App/" + local);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                String json = readBody(conn.getInputStream());
                Release release = parseLatest(json);
                if (release == null) {
                    return new Result(STATUS_HTTP, null, "返回内容无法解析", local);
                }
                int cmp = compareVersions(local, release.tag);
                if (cmp < 0) {
                    return new Result(STATUS_NEW_VERSION, release,
                            "发现新版本 " + release.tag, local);
                }
                return new Result(STATUS_UP_TO_DATE, release, "已是最新版本", local);
            }
            if (code == 403 || code == 429) {
                String remaining = conn.getHeaderField("X-RateLimit-Remaining");
                boolean limited = "0".equals(remaining) || code == 429;
                return new Result(limited ? STATUS_RATE_LIMITED : STATUS_HTTP, null,
                        limited ? "GitHub 接口调用次数已用尽，请稍后再试"
                                : "GitHub 拒绝了这次请求（HTTP " + code + "）",
                        local);
            }
            if (code == 404) {
                return new Result(STATUS_NOT_FOUND, null,
                        "仓库或 Release 不存在（HTTP 404）", local);
            }
            return new Result(STATUS_HTTP, null, "HTTP " + code, local);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readBody(InputStream in) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY_BYTES) {
                    break;
                }
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** 把异常翻译成一句人话，别把 {@code java.net.SocketTimeoutException} 原样丢给用户。 */
    private static String describeThrowable(Throwable t) {
        if (t instanceof java.net.SocketTimeoutException) {
            return "连接超时，GitHub 可能不可达";
        }
        if (t instanceof java.net.UnknownHostException) {
            return "无法解析 api.github.com，请检查网络或代理";
        }
        if (t instanceof java.net.ConnectException) {
            return "无法连接到 GitHub";
        }
        if (t instanceof javax.net.ssl.SSLException) {
            return "TLS 握手失败，可能是代理拦截";
        }
        String msg = t.getMessage();
        return msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg;
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 解析 {@code GET /repos/{owner}/{repo}/releases/latest} 的响应。
     *
     * @return 缺少 {@code tag_name} 时返回 {@code null} —— 没有版本号就没法比较，只能算解析失败
     */
    static Release parseLatest(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            String tag = o.optString("tag_name", "").trim();
            if (tag.isEmpty()) {
                return null;
            }
            String title = o.optString("name", "").trim();
            if (title.isEmpty()) {
                title = tag;
            }
            String body = o.optString("body", "");
            String apkUrl = "";
            String apkName = "";
            long apkSize = 0L;
            String digestSha = "";
            String shaUrl = "";
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) {
                        continue;
                    }
                    String name = a.optString("name", "");
                    String lower = name.toLowerCase(Locale.ROOT);
                    String download = a.optString("browser_download_url", "");
                    if (lower.endsWith(".apk")) {
                        if (apkUrl.isEmpty()) {
                            apkUrl = download;
                            apkName = name;
                            apkSize = a.optLong("size", 0L);
                            // GitHub 2025 起为资产提供 digest 字段（形如 sha256:xxx），
                            // 这是最可信的一级来源：它由 GitHub 在接收上传时算出
                            digestSha = ApkVerifier.normalizeSha(a.optString("digest", ""));
                        }
                    } else if (lower.endsWith(".sha256")) {
                        // 兼容 Aurora-v1.2.0.apk.sha256 与 Aurora-v1.2.0.sha256 两种命名
                        if (shaUrl.isEmpty()) {
                            shaUrl = download;
                        }
                    }
                }
            }
            // 二级：Release 正文里的 SHA-256（release.yml 会写进去）
            String sha = !digestSha.isEmpty() ? digestSha : extractSha256(body);
            return new Release(
                    tag,
                    title,
                    body,
                    o.optString("html_url", ""),
                    apkUrl,
                    o.optString("published_at", ""),
                    apkName,
                    apkSize,
                    sha,
                    shaUrl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 Release 正文里提取 SHA-256。
     *
     * <p>认这些写法（大小写不敏感、中英文冒号都行、允许被反引号或方括号包住）：
     * <pre>
     *   - SHA-256：`a1b2c3...`（64 位）
     *   SHA256: a1b2c3...
     *   sha-256=a1b2c3...
     * </pre>
     *
     * @return 64 位小写十六进制；正文里没有时返回空串
     */
    static String extractSha256(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher m = ApkVerifier.BODY_SHA.matcher(body);
        if (!m.find()) {
            return "";
        }
        return ApkVerifier.normalizeSha(m.group(1));
    }

    /**
     * 比较两个版本号。
     *
     * @return 正数表示 {@code a} 更新、0 表示等价、负数表示 {@code a} 更旧
     *
     * <p>能处理这几种写法：{@code v1.2.0}、{@code 1.2}、{@code 1.2.0-beta.1}、{@code 1.2.0+build7}。
     * 规则与语义化版本一致：
     * <ul>
     *   <li>忽略前导 {@code v}/{@code V} 与 {@code +} 之后的构建元数据；</li>
     *   <li>主版本段按数字逐段比较，段数不足的补 0（{@code 1.2} == {@code 1.2.0}）；</li>
     *   <li>主版本相同时，带 {@code -预发布} 后缀的<b>更旧</b>（{@code 1.2.0-beta} &lt; {@code 1.2.0}）。</li>
     * </ul>
     */
    static int compareVersions(String a, String b) {
        String na = normalizeVersion(a);
        String nb = normalizeVersion(b);
        String coreA = stripPrerelease(na);
        String coreB = stripPrerelease(nb);

        int cmp = compareNumericSegments(coreA, coreB);
        if (cmp != 0) {
            return cmp;
        }
        String preA = prereleaseOf(na);
        String preB = prereleaseOf(nb);
        if (preA.isEmpty() && preB.isEmpty()) {
            return 0;
        }
        // 有预发布后缀的一方更旧
        if (preA.isEmpty()) {
            return 1;
        }
        if (preB.isEmpty()) {
            return -1;
        }
        return preA.compareTo(preB);
    }

    /** 去掉 {@code v} 前缀与 {@code +} 构建元数据，只留「主版本-预发布」。 */
    static String normalizeVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.charAt(0) == 'v' || s.charAt(0) == 'V') {
            s = s.substring(1).trim();
        }
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        return s;
    }

    private static String stripPrerelease(String normalized) {
        int dash = normalized.indexOf('-');
        return dash >= 0 ? normalized.substring(0, dash) : normalized;
    }

    private static String prereleaseOf(String normalized) {
        int dash = normalized.indexOf('-');
        return dash >= 0 ? normalized.substring(dash + 1) : "";
    }

    private static int compareNumericSegments(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseSegment(pa[i]) : 0;
            int vb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (va != vb) {
                return va > vb ? 1 : -1;
            }
        }
        return 0;
    }

    /** 只取段首的连续数字；{@code 3rc1} → 3、{@code abc} → 0。 */
    private static int parseSegment(String seg) {
        if (seg == null || seg.isEmpty()) {
            return 0;
        }
        int end = 0;
        while (end < seg.length() && Character.isDigit(seg.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            // 位数过多的异常版本号（如时间戳）截断处理，避免 NumberFormatException
            return Integer.parseInt(seg.substring(0, Math.min(end, 9)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code owner/repo} 形状校验，防止 {@code UPDATE_REPO} 为空时拼出无意义的 URL。 */
    static boolean isRepoValid(String repo) {
        if (repo == null) {
            return false;
        }
        String s = repo.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1) {
            return false;
        }
        // 只允许一个斜杠，且两边都不能含空白
        return s.indexOf('/', slash + 1) < 0 && s.indexOf(' ') < 0;
    }

    // ==================== 内部 ====================

    private static ExecutorService executor() {
        ExecutorService e = executor;
        if (e == null) {
            synchronized (UpdateChecker.class) {
                e = executor;
                if (e == null) {
                    e = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "aurora-update-check");
                        t.setDaemon(true);
                        return t;
                    });
                    executor = e;
                }
            }
        }
        return e;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
