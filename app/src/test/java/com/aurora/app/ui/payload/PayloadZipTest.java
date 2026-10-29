package com.aurora.app.ui.payload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@link PayloadZip} 的单元测试（纯 JVM）。
 *
 * <p>这里只测「中央目录 → 条目 → 数据起点」这条链路，因为它是整个解包功能的地基：
 * 偏移算错一个字节，解出来的就是一张看起来正常、刷进去变砖的镜像。
 *
 * <p>ZIP64 的边界（4 GB 以上）造不出来，靠手写文件头成本过高，这里覆盖的是
 * 常规包的 stored / deflate 两种存储方式 —— 也正是 OTA 全量包里的 payload.bin（stored）
 * 与散包 img（deflate）实际会遇到的形态。
 */
public class PayloadZipTest {

    private File zipFile;

    @Before
    public void setUp() throws Exception {
        zipFile = File.createTempFile("aurora-payload-zip", ".zip");
    }

    @After
    public void tearDown() {
        if (zipFile != null && zipFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            zipFile.delete();
        }
    }

    /** 造一个同时含 stored 与 deflate 条目的 zip，返回原始内容供比对。 */
    private byte[] writeZip(boolean stored, byte[] content) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile))) {
            ZipEntry entry = new ZipEntry("payload.bin");
            if (stored) {
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCompressedSize(content.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(content);
                entry.setCrc(crc.getValue());
            }
            out.putNextEntry(entry);
            out.write(content);
            out.closeEntry();

            ZipEntry extra = new ZipEntry("boot.img");
            out.putNextEntry(extra);
            out.write(new byte[]{1, 2, 3, 4, 5});
            out.closeEntry();
        }
        return content;
    }

    @Test
    public void listsEntriesWithSizes() throws Exception {
        byte[] content = "CrAU-payload-bytes".getBytes("UTF-8");
        writeZip(true, content);

        try (PayloadZip zip = PayloadZip.open(new PayloadZip.LocalSlice(zipFile))) {
            assertEquals(2, zip.entries.size());
            PayloadZip.Entry payload = zip.find("payload.bin");
            assertNotNull(payload);
            assertEquals(0, payload.method);
            assertEquals(content.length, payload.size);

            PayloadZip.Entry boot = zip.find("boot.img");
            assertNotNull(boot);
            assertEquals(5L, boot.size);
        }
    }

    @Test
    public void storedEntryReadsBackByteForByte() throws Exception {
        byte[] content = new byte[8192];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        writeZip(true, content);

        try (PayloadZip zip = PayloadZip.open(new PayloadZip.LocalSlice(zipFile));
             InputStream in = zip.openEntry(zip.find("payload.bin"))) {
            assertArrayEquals(content, readAll(in));
        }
    }

    @Test
    public void deflatedEntryReadsBackByteForByte() throws Exception {
        byte[] content = new byte[8192];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 7);
        }
        writeZip(false, content);

        try (PayloadZip zip = PayloadZip.open(new PayloadZip.LocalSlice(zipFile))) {
            PayloadZip.Entry entry = zip.find("payload.bin");
            assertEquals(8, entry.method);
            try (InputStream in = zip.openEntry(entry)) {
                assertArrayEquals(content, readAll(in));
            }
        }
    }

    @Test
    public void missingEntryReturnsNull() throws Exception {
        writeZip(true, "x".getBytes("UTF-8"));
        try (PayloadZip zip = PayloadZip.open(new PayloadZip.LocalSlice(zipFile))) {
            assertNull(zip.find("super.img"));
        }
    }

    @Test
    public void nonZipFileFailsClearly() throws Exception {
        try (FileOutputStream out = new FileOutputStream(zipFile)) {
            out.write("not a zip at all, just some bytes".getBytes("UTF-8"));
        }
        try {
            PayloadZip.open(new PayloadZip.LocalSlice(zipFile)).close();
            fail("非 zip 文件应当抛出异常");
        } catch (Exception e) {
            assertTrue("错误信息应说明不是 zip：" + e.getMessage(),
                    e.getMessage().contains("ZIP") || e.getMessage().contains("中央目录"));
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
