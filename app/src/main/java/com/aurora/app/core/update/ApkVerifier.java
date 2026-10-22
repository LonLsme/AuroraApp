package com.aurora.app.core.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * APK 完整性校验：SHA-256 摘要 + 签名一致性。
 *
 * <p><b>两道校验各管一件事，缺一不可：</b>
 * <ul>
 *   <li><b>SHA-256</b> 保证「下到的字节就是作者发布的字节」—— 防传输损坏、
 *       防中间人替换、防镜像站被投毒。期望值来自 Release 资产自带的
 *       {@code digest} 字段，或 Release 正文 / {@code .sha256} 资产里的同一串值；</li>
 *   <li><b>签名</b> 保证「这个包能覆盖装到现在这个 App 上」—— 系统安装器只认签名，
 *       同包名不同签名的包必然装失败（{@code INSTALL_FAILED_UPDATE_INCOMPATIBLE}）。
 *       提前比对能把「下载 27 MB 到 100% 才被系统拒」变成「下完立刻说清楚原因」。</li>
 * </ul>
 *
 * <p>本类只做判断，不做选择：下载器拿到结果自己决定删文件还是弹安装器。
 *
 * <p><b>分层约束</b>：{@code core} 层，不 import 任何 {@code ui} 包。
 * <p>纯函数部分（{@link #sha256}、{@link #normalizeSha}、{@link #isSha256}）不碰
 * Android API，因此可以直接在 JVM 单测里跑，见 {@code ApkVerifierTest}。
 */
public final class ApkVerifier {

    /** 摘要标签：{@code SHA-256:}、{@code sha256=}、{@code SHA256：}（中文冒号）都认。 */
    private static final Pattern SHA_LABEL =
            Pattern.compile("(?i)^sha-?256\\s*[:：=]?\\s*");

    /**
     * 从 Release 正文里捞 64 位十六进制摘要。
     *
     * <p>要求「{@code sha256} 标签 + 摘要」成对出现，标签与摘要之间允许 16 个字符以内的
     * 分隔符 —— 这样下面这些写法都能认，而正文里偶然出现的裸十六进制串不会被误捕：
     * <pre>
     *   - SHA-256：`a1b2...`
     *   **SHA-256**: a1b2...
     *   sha256=a1b2...
     * </pre>
     */
    static final Pattern BODY_SHA = Pattern.compile(
            "(?i)sha-?256[^0-9a-f]{0,16}([0-9a-f]{64})");

    private ApkVerifier() {
    }

    // ==================== 摘要（纯逻辑，可单测） ====================

    /**
     * 计算文件的 SHA-256，返回 64 位小写十六进制。
     *
     * <p>分块读取，不把整个 APK（几十 MB）读进内存 —— 低端机上
     * {@code Files.readAllBytes} 会直接 OOM。
     */
    public static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return toHex(md.digest());
    }

    /**
     * 把各种写法的摘要规范成 64 位小写十六进制。
     *
     * @return 合法时返回规范化结果；无法识别时返回<b>空串</b>（不是 null）
     *
     * <p>能吃掉这些形式：{@code sha256:abc...}、{@code SHA-256：`abc...`}、
     * {@code abc...}（已规范）、{@code .sha256} 文件里「摘要 + 两个空格 + 文件名」。
     *
     * <p>三个细节都不能省：
     * <ul>
     *   <li><b>先剥掉 {@code sha-256} 这个标签</b> —— 标签里的 {@code a} 也是十六进制字符，
     *       不剥就会混进摘要；</li>
     *   <li><b>遇到第一个非十六进制字符就停</b> —— {@code .sha256} 文件后面跟着文件名；</li>
     *   <li><b>超过 64 位仍连续是十六进制就判无效</b> —— 截断成 64 位会得到一个
     *       看似合法、实际永远对不上的摘要，比直接报错更难查。</li>
     * </ul>
     */
    public static String normalizeSha(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        s = SHA_LABEL.matcher(s).replaceFirst("");
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("sha256:")) {
            lower = lower.substring("sha256:".length());
        }
        StringBuilder sb = new StringBuilder(64);
        boolean started = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isHexChar(c)) {
                if (started && sb.length() == 64) {
                    return "";
                }
                sb.append(c);
                started = true;
            } else if (started) {
                // 摘要已经结束，后面是空格 + 文件名之类的内容
                break;
            }
        }
        return isSha256(sb.toString()) ? sb.toString() : "";
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    /** 是否是 64 位小写十六进制。调用前需先自行小写（{@link #normalizeSha} 已处理）。 */
    public static boolean isSha256(String s) {
        if (s == null || s.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            if (!isHexChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 定长时间比较。
     *
     * <p>用 {@link String#equals} 比较摘要时，首个不同字符的位置会决定返回耗时，
     * 攻击者可以逐字节猜。这里对全部字符做按位或，耗时与内容无关。
     * 本地校验其实用不到这种对抗，但写对了不花钱。
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // ==================== 包信息（需要 Context） ====================

    /**
     * 读取 APK 文件里的包名。
     *
     * @return 解析失败返回空串 —— 文件不是合法 APK、或被截断
     */
    public static String archivePackageName(Context context, File apk) {
        PackageInfo info = readArchiveInfo(context, apk);
        return info == null || info.packageName == null ? "" : info.packageName;
    }

    /**
     * 读取 APK 文件里签名证书的 SHA-256（取第一张证书）。
     *
     * @return 读不到返回 {@code null}（未签名包、解析失败）
     */
    public static String archiveSignatureSha256(Context context, File apk) {
        return firstCertSha256(readArchiveInfo(context, apk));
    }

    /**
     * 当前已安装本 App 的签名证书 SHA-256。
     *
     * @return 读不到返回 {@code null}（正常情况下不会发生）
     */
    public static String installedSignatureSha256(Context context) {
        try {
            PackageInfo info = readInstalledInfo(context, context.getPackageName());
            return firstCertSha256(info);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 内部 ====================

    private static PackageInfo readArchiveInfo(Context context, File apk) {
        if (context == null || apk == null || !apk.isFile()) {
            return null;
        }
        try {
            PackageManager pm = context.getPackageManager();
            String path = apk.getAbsolutePath();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES);
            }
            return pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES);
        } catch (Throwable t) {
            // 部分 ROM 在解析损坏包时会抛 RuntimeException，而不是返回 null
            return null;
        }
    }

    private static PackageInfo readInstalledInfo(Context context, String pkg)
            throws PackageManager.NameNotFoundException {
        PackageManager pm = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
        }
        return pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
    }

    /**
     * 取第一张签名证书的摘要。
     *
     * <p>API 28 起 {@code signatures} 字段被 {@code signingInfo} 取代：
     * 有多个签名者（轮换密钥的 v3 签名）时取 APK 内的当前签名者，
     * 单签名者时取签名历史的最新一张。两种情况取到的都是「这个包装在设备上时用的证书」。
     */
    @SuppressWarnings("deprecation")
    private static String firstCertSha256(PackageInfo info) {
        if (info == null) {
            return null;
        }
        Signature[] signatures;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (info.signingInfo == null) {
                    return null;
                }
                signatures = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                signatures = info.signatures;
            }
        } catch (Throwable t) {
            return null;
        }
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(signatures[0].toByteArray()));
        } catch (Throwable t) {
            return null;
        }
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
