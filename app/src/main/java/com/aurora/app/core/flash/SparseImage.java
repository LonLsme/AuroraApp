package com.aurora.app.core.flash;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Android sparse 镜像识别与「展开后真实大小」计算。
 *
 * <h3>为什么必须算展开尺寸</h3>
 * 欧加真线刷里，动态分区要在 fastbootd 下先 {@code create-logical-partition <名字> <大小>}
 * 再刷入。大小必须是<b>展开后</b>的字节数：固件包里的 {@code system.img} 通常是 sparse 格式，
 * 它在磁盘上可能只有几百 MB，展开后却是好几 GB。若把磁盘长度当成分区大小传进去，
 * 创建出来的分区偏小，紧接着的 {@code fastboot flash} 必然失败
 * （报错形如 {@code Not enough space to write}）。
 *
 * <h3>sparse 文件头（全部小端字段，但读取时按大端解释）</h3>
 * <pre>
 * 偏移  长度  字段
 * 0     4     magic          = 0xED26FF3A
 * 4     2     major_version
 * 6     2     minor_version
 * 8     2     file_hdr_sz
 * 10    2     chunk_hdr_sz
 * 12    4     blk_sz          ← 块大小（通常 4096）
 * 16    4     total_blks      ← 块总数
 * 20    4     total_chunks
 * 24    4     image_checksum
 * </pre>
 * 展开大小 = {@code blk_sz × total_blks}，即偏移 <b>12</b> 与 <b>16</b>。
 *
 * <h3>与参考实现的差异（刻意不照抄）</h3>
 * 参考实现 {@code oz1.java} 的 {@code a(File)} 是 {@code seek(8)} 之后连读两个 int 相乘，
 * 取到的是偏移 8（= {@code file_hdr_sz<<16 | chunk_hdr_sz}，标准镜像恒为 1835020）
 * 与偏移 12（{@code blk_sz}）—— 少跳过了 4 字节，属 off-by-4。
 * 它的后果不是「算大一点」而是<b>所有镜像算出同一个值</b>（因为前两个字段在所有标准
 * sparse 镜像里都是 28/12），分区大小会全部相同。本类按正确偏移实现。
 */
public final class SparseImage {

    private static final String TAG = "SparseImage";

    /** sparse 格式魔数（大端）。 */
    private static final int SPARSE_MAGIC = 0xED26FF3A;

    /** 文件头最短长度：少于它不可能是 sparse。 */
    private static final int MIN_HEADER = 28;

    /** 尺寸上限兜底：超过 64GB 一律认为是解析异常，回退文件长度。 */
    private static final long MAX_REASONABLE = 64L * 1024 * 1024 * 1024;

    private SparseImage() {
    }

    /**
     * 是否是 sparse 镜像。
     *
     * <p>只读前 4 字节比对魔数，不需要整文件读取。
     */
    public static boolean isSparse(File file) {
        if (file == null || !file.isFile() || file.length() < 4) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(0L);
            return raf.readInt() == SPARSE_MAGIC;
        } catch (IOException e) {
            Log.w(TAG, "读取镜像头失败: " + file.getName(), e);
            return false;
        }
    }

    /**
     * 镜像写入分区时实际占用的字节数。
     *
     * <p>sparse 镜像返回展开后大小；raw 镜像（或解析异常）返回文件长度。
     * <b>任何情况下都不抛异常</b> —— 调用方在刷机流程中间，一个 IOException
     * 不该让整条链路断掉。
     */
    public static long partitionSize(File file) {
        if (file == null || !file.isFile()) {
            return -1L;
        }
        long fallback = file.length();
        if (fallback < MIN_HEADER) {
            return fallback;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(0L);
            if (raf.readInt() != SPARSE_MAGIC) {
                return fallback;   // raw 镜像：磁盘长度就是分区大小
            }
            raf.seek(12L);
            long blockSize = Integer.toUnsignedLong(raf.readInt());
            long totalBlocks = Integer.toUnsignedLong(raf.readInt());
            long expanded = blockSize * totalBlocks;
            if (expanded <= 0 || expanded > MAX_REASONABLE) {
                Log.w(TAG, "sparse 尺寸异常(" + expanded + ")，回退文件长度: " + file.getName());
                return fallback;
            }
            return expanded;
        } catch (IOException e) {
            Log.w(TAG, "解析 sparse 尺寸失败，回退文件长度: " + file.getName(), e);
            return fallback;
        }
    }

    /**
     * 由文件名推分区名：{@code system.img} → {@code system}。
     *
     * @return 去扩展名后的名字；文件名不含 {@code .img} 时返回原文件名
     */
    public static String partitionNameOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".img")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    /** 是否是固件包里会被单独处理的 {@code super} 相关镜像（不参与普通分区刷写）。 */
    public static boolean isSuperRelated(String partitionName) {
        if (partitionName == null) {
            return false;
        }
        String lower = partitionName.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("super");
    }

    /** 是否是空超分区定义（{@code super_empty.img} / {@code super_def.*.json} 之类）。 */
    public static boolean isEmptySuperArtifact(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("empty") || lower.endsWith(".json");
    }
}
