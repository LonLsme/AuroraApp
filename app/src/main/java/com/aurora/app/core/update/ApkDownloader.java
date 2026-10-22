package com.aurora.app.core.update;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 从 GitHub Release 下载 APK，并在下载完成后就地完成校验。
 *
 * <p><b>为什么是「单任务」而不是「每次 new 一个下载器」</b>：
 * 同一时刻只应该有一个更新包在下载 —— 启动静默检查与设置页手动检查可能几乎同时
 * 触发，两个任务并行下载同一个 APK 既是浪费流量，也会让缓存目录里出现两份半截文件。
 * 因此本类自己持有唯一的在跑任务（{@link Job}），调用方只能 {@link #start} /
 * {@link #attach} / {@link #cancel}，不存在「创建实例」这一步。
 *
 * <p><b>为什么下载和校验写在同一个类里</b>：校验不是可选步骤。一个「下载完成」的
 * APK 如果没验过摘要，就没有任何理由相信它是作者发布的那个包。把 {@code onReady}
 * 定义为<b>只有校验全过才回调</b>，调用方就不可能拿到未校验的文件 ——
 * 这比在每个调用点各写一遍校验顺序可靠。
 *
 * <p><b>线程</b>：所有回调都在下载线程（{@code aurora-apk-download}）上，
 * 调用方自己切主线程。下载线程是 daemon，进程退出时会自然结束。
 *
 * <p><b>分层约束</b>：{@code core} 层，不 import 任何 {@code ui} 包。
 */
public final class ApkDownloader {

    /** 网络不可用 / 连接被断开 / 超时。 */
    public static final int ERR_NETWORK = 1;
    /** 服务端返回非 200（404、限流、被墙劫持）。 */
    public static final int ERR_HTTP = 2;
    /** 写本地文件失败（空间不足、目录不可写）。 */
    public static final int ERR_WRITE = 3;
    /** 写之前就算出来空间不够，提前拒绝。 */
    public static final int ERR_NO_SPACE = 4;
    /** Release 里没有任何可用的摘要值 —— 无法校验，故拒绝安装。 */
    public static final int ERR_SHA_MISSING = 5;
    /** 摘要不匹配：文件被篡改或传输损坏。 */
    public static final int ERR_SHA_MISMATCH = 6;
    /** 签名与当前安装的版本不一致，系统必然拒装。 */
    public static final int ERR_SIGNATURE = 7;
    /** 包名不符或文件不是合法 APK。 */
    public static final int ERR_BAD_PACKAGE = 8;
    /** 用户主动取消。 */
    public static final int ERR_CANCELLED = 9;

    /** 进度回调的最小间隔：200ms。再密就是在拿 UI 线程刷屏，人眼也看不出差别。 */
    private static final long PROGRESS_INTERVAL_MS = 200L;

    /** 下载超时：连接 15s、两次读之间 30s（慢网下单块数据可能久等）。 */
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final String PREFS = "aurora_update";
    private static final String KEY_READY_TAG = "ready_tag";
    private static final String KEY_READY_PATH = "ready_path";
    private static final String KEY_READY_SHA = "ready_sha";

    /** 当前任务。同一时刻至多一个，故用类级字段即可。 */
    private static Job current;

    private ApkDownloader() {
    }

    /** 下载与校验的回调。全部在下载线程上。 */
    public interface Listener {
        /**
         * 进度。
         *
         * @param downloaded 已下载字节
         * @param total      总字节；服务端没给 Content-Length 时为 0（界面应显示不确定进度）
         * @param bytesPerSec 平均速度，首秒内可能为 0
         */
        void onProgress(long downloaded, long total, long bytesPerSec);

        /** 下载完成<b>且</b>摘要、包名、签名三项校验全部通过。 */
        void onReady(File apk, String sha256);

        void onError(int code, String message);

        void onCancelled();
    }

    // ==================== 对外入口 ====================

    /**
     * 开始下载。
     *
     * <p>已有任务在跑时<b>不会</b>启动第二个，只把 listener 换成新的 ——
     * 后发起的界面应当看到正在进行的那个下载，而不是自己另开一份。
     *
     * @param listener 进度回调，可为 null（纯后台下载）
     */
    public static void start(Context context, UpdateChecker.Release release, Listener listener) {
        if (context == null || release == null) {
            if (listener != null) {
                listener.onError(ERR_BAD_PACKAGE, "缺少版本信息");
            }
            return;
        }
        if (release.apkUrl == null || release.apkUrl.isEmpty()) {
            if (listener != null) {
                listener.onError(ERR_HTTP, "这个版本没有提供 APK 安装包");
            }
            return;
        }
        synchronized (ApkDownloader.class) {
            if (current != null && current.isAlive()) {
                current.listener = listener;
                return;
            }
            Job job = new Job(context.getApplicationContext(), release, listener);
            current = job;
            Thread t = new Thread(job, "aurora-apk-download");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * 把回调接到正在跑的任务上（重新打开下载对话框时用）。
     *
     * @return 没有在跑的任务时返回 false
     */
    public static boolean attach(Listener listener) {
        synchronized (ApkDownloader.class) {
            if (current == null || !current.isAlive()) {
                return false;
            }
            current.listener = listener;
            return true;
        }
    }

    /** 是否有下载在跑。 */
    public static boolean isRunning() {
        synchronized (ApkDownloader.class) {
            return current != null && current.isAlive();
        }
    }

    /**
     * 取消当前下载并删除半截文件。
     *
     * <p>取消后 {@code current} 立即置空，用户可以马上重新开始。
     */
    public static void cancel() {
        Job job;
        synchronized (ApkDownloader.class) {
            job = current;
            current = null;
        }
        if (job != null) {
            job.cancel();
        }
    }

    // ==================== 已下好的包 ====================

    /**
     * 取「已经下载并校验通过、还没安装」的那个包。
     *
     * <p>用途：用户下载完把对话框关掉了，再点「检查更新」时不该重新下 27 MB。
     * 文件已被删除（清理过缓存）时返回 null 并顺手清掉记录。
     *
     * @param tag 期望的版本 tag；为空表示不限制，任意版本都接受
     */
    public static File readyApk(Context context, String tag) {
        SharedPreferences p = prefs(context);
        String path = p.getString(KEY_READY_PATH, "");
        String readyTag = p.getString(KEY_READY_TAG, "");
        if (path == null || path.isEmpty()) {
            return null;
        }
        if (tag != null && !tag.isEmpty() && !tag.equals(readyTag)) {
            return null;
        }
        File f = new File(path);
        if (!f.isFile() || f.length() <= 0) {
            clearReady(context);
            return null;
        }
        return f;
    }

    /** 已下载包的摘要，用于在安装确认框里显示。可能为空串。 */
    public static String readySha(Context context) {
        return prefs(context).getString(KEY_READY_SHA, "");
    }

    /** 已下载包对应的版本 tag；没有待安装的包时返回空串。 */
    public static String readyTag(Context context) {
        return prefs(context).getString(KEY_READY_TAG, "");
    }

    /** 清掉「已下好」记录（安装发出后调用）。 */
    public static void clearReady(Context context) {
        prefs(context).edit()
                .remove(KEY_READY_TAG)
                .remove(KEY_READY_PATH)
                .remove(KEY_READY_SHA)
                .apply();
    }

    /** 更新包所在目录。外部私有目录优先，不可用时回落内部 cache。 */
    public static File resolveDir(Context context) {
        File ext = context.getExternalFilesDir("update");
        if (ext != null) {
            return ext;
        }
        return new File(context.getCacheDir(), "update");
    }

    // ==================== 任务 ====================

    private static final class Job implements Runnable {

        private final Context app;
        private final UpdateChecker.Release release;
        private volatile Listener listener;

        private volatile boolean cancelled;
        private volatile boolean finished;
        private volatile HttpURLConnection conn;

        Job(Context app, UpdateChecker.Release release, Listener listener) {
            this.app = app;
            this.release = release;
            this.listener = listener;
        }

        boolean isAlive() {
            return !cancelled && !finished;
        }

        void cancel() {
            cancelled = true;
            HttpURLConnection c = conn;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                    // disconnect 本身失败不影响取消语义
                }
            }
        }

        @Override
        public void run() {
            File dir = resolveDir(app);
            File part = null;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    fail(ERR_WRITE, "无法创建下载目录：" + dir.getAbsolutePath());
                    return;
                }
                cleanOld(dir);
                String name = safeFileName(release);
                File apk = new File(dir, name);
                part = new File(dir, name + ".part");
                if (part.exists() && !part.delete()) {
                    fail(ERR_WRITE, "无法清理旧的临时文件");
                    return;
                }

                long total = 0L;
                String actualSha;
                try {
                    conn = open(release.apkUrl);
                    int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        fail(ERR_HTTP, "下载失败：HTTP " + code
                                + (code == 404 ? "（资产不存在，可能已被删除）" : ""));
                        return;
                    }
                    total = conn.getContentLengthLong();
                    if (total > 0) {
                        long usable = dir.getUsableSpace();
                        // 留 4 MB 余量：写文件之外，系统安装器还要再拷一份
                        if (usable > 0 && usable < total + 4L * 1024 * 1024) {
                            fail(ERR_NO_SPACE, String.format(Locale.ROOT,
                                    "存储空间不足：需要 %.1f MB，可用 %.1f MB",
                                    total / 1048576.0, usable / 1048576.0));
                            return;
                        }
                    }
                    actualSha = download(conn, part, total);
                } finally {
                    HttpURLConnection c = conn;
                    conn = null;
                    if (c != null) {
                        c.disconnect();
                    }
                }

                if (cancelled) {
                    part.delete();
                    notifyCancelled();
                    return;
                }

                // CACHE_DIR 与 EXTERNAL 之间不能 rename，先试同目录内的原子替换
                if (apk.exists() && !apk.delete()) {
                    fail(ERR_WRITE, "无法覆盖旧的安装包文件");
                    return;
                }
                if (!part.renameTo(apk)) {
                    fail(ERR_WRITE, "无法重命名下载的临时文件");
                    return;
                }
                part = null;

                // ---- 校验：摘要 ----
                String expected = ApkVerifier.normalizeSha(release.apkSha256);
                if (expected.isEmpty() && release.apkShaUrl != null && !release.apkShaUrl.isEmpty()) {
                    expected = ApkVerifier.normalizeSha(fetchText(release.apkShaUrl));
                }
                if (expected.isEmpty()) {
                    apk.delete();
                    fail(ERR_SHA_MISSING, "该版本没有提供 SHA-256 校验值，无法确认安装包完整性");
                    return;
                }
                if (!ApkVerifier.constantTimeEquals(expected, actualSha)) {
                    apk.delete();
                    fail(ERR_SHA_MISMATCH, "校验失败：安装包与官方摘要不一致，文件可能已损坏或被替换");
                    return;
                }

                // ---- 校验：包名 ----
                String pkg = ApkVerifier.archivePackageName(app, apk);
                if (!app.getPackageName().equals(pkg)) {
                    apk.delete();
                    fail(ERR_BAD_PACKAGE, pkg.isEmpty()
                            ? "校验失败：文件不是有效的 APK"
                            : "校验失败：包名不符（" + pkg + "）");
                    return;
                }

                // ---- 校验：签名 ----
                String newSig = ApkVerifier.archiveSignatureSha256(app, apk);
                String curSig = ApkVerifier.installedSignatureSha256(app);
                if (newSig == null) {
                    apk.delete();
                    fail(ERR_SIGNATURE, "校验失败：安装包未签名或签名无法读取");
                    return;
                }
                if (curSig != null && !ApkVerifier.constantTimeEquals(
                        newSig.toLowerCase(Locale.ROOT), curSig.toLowerCase(Locale.ROOT))) {
                    apk.delete();
                    fail(ERR_SIGNATURE, "签名与当前安装的版本不一致，系统会拒绝覆盖安装。"
                            + "如果你现在装的是自行编译或第三方渠道的版本，需要先卸载再安装（会清空应用设置）。");
                    return;
                }

                markReady(app, release.tag, apk, actualSha);
                notifyReady(apk, actualSha);
            } catch (Throwable t) {
                if (part != null) {
                    part.delete();
                }
                if (cancelled) {
                    notifyCancelled();
                } else {
                    fail(ERR_NETWORK, describe(t));
                }
            } finally {
                finished = true;
            }
        }

        /**
         * 读网络、写文件、边算摘要。
         *
         * <p>摘要在这里顺流算，而不是下完再读一遍盘：27 MB 的包在低端机上
         * 二次读盘要一秒多，而且没有额外收益。
         */
        private String download(HttpURLConnection c, File part, long total) throws IOException {
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("SHA-256 不可用", e);
            }

            InputStream in;
            try {
                in = c.getInputStream();
            } catch (IOException e) {
                throw new IOException(describe(e), e);
            }

            long downloaded = 0L;
            long startAt = System.currentTimeMillis();
            long lastEmit = 0L;

            try (InputStream input = in;
                 OutputStream raw = new FileOutputStream(part);
                 OutputStream out = new BufferedOutputStream(raw, 64 * 1024)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = input.read(buf)) != -1) {
                    if (cancelled) {
                        throw new CancelledException();
                    }
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    downloaded += n;

                    long now = System.currentTimeMillis();
                    if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                        lastEmit = now;
                        long elapsed = Math.max(1L, now - startAt);
                        emitProgress(downloaded, total, downloaded * 1000L / elapsed);
                    }
                }
                out.flush();
            } catch (CancelledException e) {
                throw e;
            }
            emitProgress(downloaded, total, 0L);
            return toHex(md.digest());
        }

        private HttpURLConnection open(String url) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            // assets 的 browser_download_url 会 302 到 objects.githubusercontent.com，
            // 默认只自动跟随同协议跳转；这里显式再跟随一层，失败也只是 404 而不是报异常
            c.setRequestProperty("User-Agent", "Aurora-App/" + UpdateChecker.localVersionName());
            c.setRequestProperty("Accept", "application/octet-stream");
            return c;
        }

        /** 拉 {@code .sha256} 资产的内容（几百字节的纯文本）。 */
        private String fetchText(String url) {
            HttpURLConnection c = null;
            try {
                c = open(url);
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return "";
                }
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    int guard = 0;
                    while ((n = in.read(buf)) != -1 && guard++ < 8) {
                        out.write(buf, 0, n);
                    }
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            } catch (Throwable t) {
                return "";
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }

        /** 删掉上一次留下的 APK / 半截文件，避免几十 MB 的尸体越堆越多。 */
        private void cleanOld(File dir) {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".apk") || n.endsWith(".part") || n.endsWith(".apk.part")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            clearReady(app);
        }

        private String safeFileName(UpdateChecker.Release release) {
            String name = release.apkName == null ? "" : release.apkName.trim();
            if (name.isEmpty()) {
                name = "Aurora-" + (release.tag == null ? "update" : release.tag) + ".apk";
            }
            // 只保留安全字符：资产名来自 GitHub，但拼进本地路径前仍要做一次收敛
            StringBuilder sb = new StringBuilder(name.length());
            for (int i = 0; i < name.length(); i++) {
                char ch = name.charAt(i);
                boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                        || (ch >= '0' && ch <= '9') || ch == '.' || ch == '-' || ch == '_';
                sb.append(ok ? ch : '_');
            }
            String safe = sb.toString();
            if (!safe.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                safe = safe + ".apk";
            }
            return safe;
        }

        private void emitProgress(long downloaded, long total, long speed) {
            Listener l = listener;
            if (l != null && !cancelled) {
                try {
                    l.onProgress(downloaded, total, speed);
                } catch (Throwable ignored) {
                    // 界面自己抛异常不该把下载搞挂
                }
            }
        }

        private void notifyReady(File apk, String sha) {
            Listener l = listener;
            if (l != null) {
                l.onReady(apk, sha);
            }
        }

        private void notifyCancelled() {
            Listener l = listener;
            if (l != null) {
                l.onCancelled();
            }
        }

        private void fail(int code, String message) {
            Listener l = listener;
            if (l != null) {
                l.onError(code, message);
            }
        }

        /** 内部信号：读到一半被取消。 */
        private static final class CancelledException extends IOException {
            CancelledException() {
                super("cancelled");
            }
        }
    }

    // ==================== 静态工具 ====================

    private static void markReady(Context context, String tag, File apk, String sha) {
        prefs(context).edit()
                .putString(KEY_READY_TAG, tag == null ? "" : tag)
                .putString(KEY_READY_PATH, apk.getAbsolutePath())
                .putString(KEY_READY_SHA, sha == null ? "" : sha)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String describe(Throwable t) {
        if (t instanceof java.net.SocketTimeoutException) {
            return "下载超时，请检查网络后重试";
        }
        if (t instanceof java.net.UnknownHostException) {
            return "无法连接下载服务器，请检查网络或代理";
        }
        if (t instanceof javax.net.ssl.SSLException) {
            return "TLS 握手失败，可能是代理拦截了下载";
        }
        String msg = t.getMessage();
        return msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg;
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }
}
