package com.aurora.app.core.flash;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 刷机工具链的落盘与校验。
 *
 * <h3>为什么需要它</h3>
 * 「手机给手机刷机」的本体是 {@code adb} 与 {@code fastboot} 两个可执行文件，
 * 而 Android 系统本身<b>不带</b>它们（{@code /system/bin} 下没有，Magisk / KernelSU 也不提供）。
 * 因此必须由应用自己携带。
 *
 * <h3>带哪些二进制</h3>
 * 既有工具内置了 64MB 的 {@code aarch64.tar}，含 adb / fastboot / qdl /
 * 7z / payload-dumper-go / e2fsdroid / lpunpack / libmagic 等 20 多个文件。
 * 本项目只取链路真正需要的五个：
 * <table>
 *   <tr><th>文件</th><th>用途</th><th>链接方式（经 ELF 解析确认）</th></tr>
 *   <tr><td>{@code bin/adb}</td><td>in-system 设备与 sideload</td>
 *       <td>aarch64 <b>静态</b>（无 PT_INTERP、DT_NEEDED 为空）</td></tr>
 *   <tr><td>{@code bin/fastboot}</td><td>bootloader / fastbootd 刷写</td><td>同上</td></tr>
 *   <tr><td>{@code bin/libksud}</td><td>KernelSU 镜像修补（{@code boot-patch}）</td>
 *       <td>动态，但 DT_NEEDED 只有 {@code liblog/libdl/libm/libc} ——
 *           <b>全是系统库</b>，不需要额外带 .so</td></tr>
 *   <tr><td>{@code bin/magiskboot}</td><td>boot 镜像拆解兜底</td><td>静态</td></tr>
 *   <tr><td>{@code bin/qdl}</td><td>高通 9008 EDL 深刷（{@link QdlCommands} 那一族）</td>
 *       <td>动态，{@code PT_INTERP=/system/bin/linker64}，
 *           DT_NEEDED 只有 {@code libc/libdl/libm} —— <b>全是系统库</b>，
 *           它自己直接对 {@code /dev/bus/usb/**} 发 ioctl，不依赖 libusb</td></tr>
 * </table>
 * 压缩前 19.0MB、压缩后 9.4MB。{@code payload-dumper-go} / {@code magic.mgc} /
 * {@code lpunpack} 这些留给后续做欧加全量包与 payload 解包再带。
 *
 * <p><b>qdl 是 aarch64 专用的</b>：既有工具也只有 {@code aarch64.tar} 一份，
 * 没有 32 位版本。本应用未做 ABI 过滤（{@code abiFilters} 为空），
 * 因此在 32 位 armeabi-v7a 设备上 qdl 解出来也跑不了 ——
 * 这类设备目前只能用到 adb / fastboot 通道，见 {@link #isQdlSupported()}。
 *
 * <h3>为什么不直接解压到外部存储</h3>
 * Android 10+ 禁止从外部存储 {@code exec()} ELF；而 {@code /data/data/<pkg>/files} 属于
 * 应用私有目录，仍在允许范围内。此外本工具链的实际执行者是 {@code su}（root 身份），
 * 读私有目录没有障碍 —— 但 root 也需要文件带 x 位才能执行 ELF，
 * 所以解压后必须 chmod（见 {@link FlashRuntime#ENV_PREFIX} 里那行 chmod 兜底）。
 */
public final class FlashToolchain {

    private static final String TAG = "FlashToolchain";

    /** assets 里的工具链包。 */
    private static final String ASSET_NAME = "flash_tools.zip";

    /** 解压根目录名（位于 {@code filesDir} 下）。 */
    private static final String DIR_NAME = "flash_tools";

    /**
     * 工具链版本号。
     *
     * <p>每次替换 assets 里的 zip（升级 adb / fastboot）都要<b>把这个数字加一</b>，
     * 否则已装机用户不会重新解压，仍沿用旧二进制。
     */
    private static final int TOOLCHAIN_VERSION = 3;

    private static final String PREFS_NAME = "aurora_flash_toolchain";
    private static final String KEY_VERSION = "version";

    /** 解压缓冲区，64KB 足够。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    private FlashToolchain() {
    }

    // ───────────────── 路径 ─────────────────

    /** 工具链根目录：{@code /data/data/<pkg>/files/flash_tools}。 */
    public static File baseDir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /** 可执行文件目录。 */
    public static File binDir(Context context) {
        return new File(baseDir(context), "bin");
    }

    public static File adb(Context context) {
        return new File(binDir(context), "adb");
    }

    public static File fastboot(Context context) {
        return new File(binDir(context), "fastboot");
    }

    /**
     * KernelSU 修补工具。
     *
     * <p>注意文件名是 {@code libksud} 而不是 {@code ksud} —— 它取自既有工具的 {@code aarch64.tar}，
     * 内部就是 KernelSU 的 userspace 工具（{@code ksud}），重命名是为了绕开
     * 「{@code lib*.so} 才允许放进 APK 的 native 库目录」这条打包约定。
     * 这里按原名解压，命令里也按原名调用（{@code FlashRuntime} 已把 bin 目录放进 PATH）。
     */
    public static File libksud(Context context) {
        return new File(binDir(context), "libksud");
    }

    /** boot 镜像拆解兜底工具。 */
    public static File magiskboot(Context context) {
        return new File(binDir(context), "magiskboot");
    }

    /**
     * 高通 9008 EDL 深刷工具。
     *
     * <p>它不是「主机连目标机」那类命令 —— 由它自己直接占住 {@code /dev/bus/usb} 下的
     * USB 节点去和 9008 端口通信，所以同样必须 root（它自己的报错原文是
     * {@code usbhost: non-root path failed}）。命令模板见 {@link QdlCommands}。
     */
    public static File qdl(Context context) {
        return new File(binDir(context), "qdl");
    }

    /**
     * 当前设备能否运行内置的 {@code qdl}。
     *
     * <p>这份二进制只有 aarch64 一个构建（既有工具也只带 {@code aarch64.tar}），
     * 32 位 ARM 与 x86 设备解出来也无法执行。用于在 QDL 页上给出准确的拒绝理由，
     * 而不是等到跑命令时冒出一句 {@code Exec format error}。
     */
    public static boolean isQdlSupported() {
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 运行时临时目录。
     *
     * <p>adb 需要在 {@code HOME} 下建 {@code .android}（存 adbkey），
     * 同时会写 {@code TMPDIR}。两者都指向工具链目录，避免污染其它位置。
     */
    public static File tmpDir(Context context) {
        return new File(baseDir(context), "tmp");
    }

    /** 镜像暂存目录（外部存储的应用专属目录，root 与 fastboot 都能直接按路径读取）。 */
    public static File imageDir(Context context) {
        File dir = context.getExternalFilesDir("flash");
        if (dir == null) {
            // 极少数情况下外部存储不可用，退回内部目录，功能不中断。
            dir = new File(context.getFilesDir(), "flash");
        }
        return dir;
    }

    // ───────────────── 就绪判断 ─────────────────

    /** 工具链是否已解压且版本匹配。 */
    public static boolean isReady(Context context) {
        return adb(context).isFile() && fastboot(context).isFile()
                && libksud(context).isFile() && qdl(context).isFile()
                && prefs(context).getInt(KEY_VERSION, -1) == TOOLCHAIN_VERSION;
    }

    // ───────────────── 解压 ─────────────────

    /**
     * 确保工具链就绪。已就绪时是空操作，否则从 assets 解压。
     *
     * <p><b>耗时</b>：解压 7.9MB 数据需要几百毫秒，<b>必须在后台线程调用</b>。
     */
    public static boolean ensureReady(Context context) {
        if (isReady(context)) {
            return true;
        }
        try {
            extract(context);
            prefs(context).edit().putInt(KEY_VERSION, TOOLCHAIN_VERSION).apply();
            // 显式置可执行位（zip 不保留 Unix 权限，全靠 Java 侧补）。
            // setExecutable(true, false) → 所有者可执行；root 执行 ELF 也要求 x 位存在。
            // magiskboot 是可选的兜底工具，不存在时 setExecutable 返回 false，无需特殊处理。
            for (File exe : new File[]{adb(context), fastboot(context),
                    libksud(context), magiskboot(context), qdl(context)}) {
                //noinspection ResultOfMethodCallIgnored
                exe.setExecutable(true, false);
            }
            //noinspection ResultOfMethodCallIgnored
            tmpDir(context).mkdirs();
            Log.i(TAG, "工具链就绪: " + binDir(context).getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "工具链解压失败", e);
            return false;
        }
    }

    /** 把 assets 里的 zip 展开到 {@link #baseDir}。 */
    private static void extract(Context context) throws IOException {
        File base = baseDir(context);
        String basePath = base.getCanonicalPath() + File.separator;

        try (ZipInputStream zin = new ZipInputStream(context.getAssets().open(ASSET_NAME))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zin.getNextEntry()) != null) {
                File target = new File(base, entry.getName());

                // Zip Slip 防护：条目名可能带 ../ 逃出目标目录。
                if (!target.getCanonicalPath().startsWith(basePath)
                        && !target.getCanonicalPath().equals(base.getCanonicalPath())) {
                    Log.w(TAG, "跳过越界条目: " + entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }

                try (OutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = zin.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
