package com.aurora.app.ui.payload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 解包链路的端到端测试（纯 JVM）。
 *
 * <p>这里手工造出四种真实输入，然后走「识别 → 列分区 → 提取 → 校验字节」的完整路径：
 * <ul>
 *   <li>裸 {@code payload.bin}（含 REPLACE 与 REPLACE_XZ 两种 operation）；</li>
 *   <li>OTA 全量包 zip（payload.bin 以 stored 存进 zip —— 真实全量包的常态）；</li>
 *   <li>欧加散包 zip（里面直接是若干 img，deflate 压缩）；</li>
 *   <li>单个镜像与镜像目录。</li>
 * </ul>
 *
 * <p>重点不在「能跑」，而在<b>解出来的字节和灌进去的一致</b>：
 * extents 定位、data_offset 换算、xz 解码这三处任意一处错一个字节，
 * 产出的都是一张看着正常、刷进去变砖的镜像。
 */
public class PayloadCoreTest {

    private File workDir;

    @Before
    public void setUp() throws Exception {
        workDir = new File(System.getProperty("java.io.tmpdir"),
                "aurora-payload-test-" + System.nanoTime());
        assertTrue("无法创建测试目录", workDir.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursively(workDir);
    }

    // ───────────────────────────── payload.bin ─────────────────────────────

    @Test
    public void rawAndXzPayloadParsesAndExtracts() throws Exception {
        byte[] bootData = patterned(8192, 3);
        byte[] systemData = patterned(8192, 11);
        // boot 用 REPLACE（raw），system 用 REPLACE_XZ（type=8）—— 覆盖两种真实形态
        File payload = writePayload(new PartitionSpec("boot", 0, bootData, bootData.length),
                new PartitionSpec("system", 8, xz(systemData), systemData.length));

        try (PayloadCore.Inspection inspection = PayloadCore.inspect(payload.getAbsolutePath())) {
            assertEquals(PayloadCore.KIND_PAYLOAD, inspection.kind);
            assertEquals(2, inspection.rows.size());
            assertEquals("boot", inspection.rows.get(0).name);
            assertEquals(8192L, inspection.rows.get(0).size);
            assertEquals("system", inspection.rows.get(1).name);

            File outDir = new File(workDir, "out-payload");
            extractAll(inspection, outDir);
            assertArrayEquals(bootData, readFile(new File(outDir, "boot.img")));
            assertArrayEquals(systemData, readFile(new File(outDir, "system.img")));
        }
    }

    @Test
    public void fullOtaZipIsTreatedAsPayload() throws Exception {
        byte[] bootData = patterned(4096, 5);
        File payload = writePayload(new PartitionSpec("boot", 0, bootData, bootData.length));

        // 全量包里的 payload.bin 是 stored：不解压就能按偏移读，正是页面「无需解压直接提取」的前提
        File zip = new File(workDir, "ota.zip");
        writeZip(zip, "payload.bin", readFile(payload), true, null);

        try (PayloadCore.Inspection inspection = PayloadCore.inspect(zip.getAbsolutePath())) {
            assertEquals(PayloadCore.KIND_PAYLOAD, inspection.kind);
            assertEquals(1, inspection.rows.size());
            assertEquals("boot", inspection.rows.get(0).name);

            File outDir = new File(workDir, "out-ota");
            extractAll(inspection, outDir);
            assertArrayEquals(bootData, readFile(new File(outDir, "boot.img")));
        }
    }

    @Test
    public void scatterZipListsImagesAndExtractsThem() throws Exception {
        byte[] boot = patterned(3000, 7);
        byte[] dtbo = patterned(1500, 13);
        File zip = new File(workDir, "scatter.zip");
        writeZip(zip, "boot.img", boot, false, new NamedBytes("dtbo.img", dtbo));

        try (PayloadCore.Inspection inspection = PayloadCore.inspect(zip.getAbsolutePath())) {
            assertEquals(PayloadCore.KIND_ZIP_IMAGES, inspection.kind);
            assertEquals(2, inspection.rows.size());
            assertEquals("boot", inspection.rows.get(0).name);
            assertEquals(boot.length, inspection.rows.get(0).size);

            File outDir = new File(workDir, "out-scatter");
            extractAll(inspection, outDir);
            assertArrayEquals(boot, readFile(new File(outDir, "boot.img")));
            assertArrayEquals(dtbo, readFile(new File(outDir, "dtbo.img")));
        }
    }

    @Test
    public void singleImageAndDirectoryAreCopiedAsIs() throws Exception {
        byte[] content = patterned(2048, 17);
        File image = new File(workDir, "vbmeta.img");
        writeFile(image, content);

        try (PayloadCore.Inspection inspection = PayloadCore.inspect(image.getAbsolutePath())) {
            assertEquals(PayloadCore.KIND_SINGLE_IMAGE, inspection.kind);
            assertEquals(1, inspection.rows.size());
            assertEquals("vbmeta", inspection.rows.get(0).name);
            File outDir = new File(workDir, "out-single");
            extractAll(inspection, outDir);
            assertArrayEquals(content, readFile(new File(outDir, "vbmeta.img")));
        }

        File dir = new File(workDir, "images");
        assertTrue(dir.mkdirs());
        writeFile(new File(dir, "boot.img"), content);
        try (PayloadCore.Inspection inspection = PayloadCore.inspect(dir.getAbsolutePath())) {
            assertEquals(PayloadCore.KIND_DIRECTORY, inspection.kind);
            assertEquals(1, inspection.rows.size());
            File outDir = new File(workDir, "out-dir");
            extractAll(inspection, outDir);
            assertArrayEquals(content, readFile(new File(outDir, "boot.img")));
        }
    }

    @Test
    public void cancellationStopsAndLeavesNoHalfFile() throws Exception {
        byte[] bootData = patterned(4 * 1024 * 1024, 23);
        File payload = writePayload(new PartitionSpec("boot", 0, bootData, bootData.length));

        try (PayloadCore.Inspection inspection = PayloadCore.inspect(payload.getAbsolutePath())) {
            PayloadCore.CancelToken token = new PayloadCore.CancelToken();
            token.cancel();
            File outDir = new File(workDir, "out-cancel");
            try {
                PayloadCore.extract(inspection, inspection.rows, outDir, token, null);
                fail("已取消的任务应当抛 CancelledException");
            } catch (PayloadCore.CancelledException expected) {
                File half = new File(outDir, "boot.img");
                assertTrue("取消后不应留下半截镜像", !half.exists() || half.length() == 0L);
            }
        }
    }

    // ───────────────────────────── 构造工具 ─────────────────────────────

    private static final class NamedBytes {
        final String name;
        final byte[] data;

        NamedBytes(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    /**
     * 一个分区：名字、operation 类型、已准备好的数据段。
     *
     * <p>{@code rawSize} 是<b>解压后</b>的大小 —— extents 与 partition_info.size 都按它算，
     * 而 operation.data_length 按压缩后的字节数算。两者混用会让写入越过 extents 尾部。
     */
    private static final class PartitionSpec {
        final String name;
        final int operationType;
        final byte[] data;
        final long rawSize;

        PartitionSpec(String name, int operationType, byte[] data, long rawSize) {
            this.name = name;
            this.operationType = operationType;
            this.data = data;
            this.rawSize = rawSize;
        }
    }

    /**
     * 按 payload v2 的结构写一个最小可解析包：
     * {@code "CrAU" + version(8) + manifestSize(8) + sigSize(4) + manifest + data}。
     */
    private File writePayload(PartitionSpec... specs) throws Exception {
        ByteArrayOutputStream manifest = new ByteArrayOutputStream();
        protoVarint(manifest, 3, 4096);                       // block_size

        ByteArrayOutputStream dataArea = new ByteArrayOutputStream();
        for (PartitionSpec spec : specs) {
            long dataOffset = dataArea.size();
            int blocks = (int) Math.max(1L, (spec.rawSize + 4095L) / 4096L);

            ByteArrayOutputStream info = new ByteArrayOutputStream();
            protoVarint(info, 1, spec.rawSize);               // new_partition_info.size（解压后）

            ByteArrayOutputStream extent = new ByteArrayOutputStream();
            protoVarint(extent, 1, 0);                        // start_block（每个分区独立成文件）
            protoVarint(extent, 2, blocks);                   // num_blocks

            ByteArrayOutputStream operation = new ByteArrayOutputStream();
            protoVarint(operation, 1, spec.operationType);    // type
            protoVarint(operation, 2, dataOffset);            // data_offset（相对数据区）
            protoVarint(operation, 3, spec.data.length);      // data_length
            protoBytes(operation, 6, extent.toByteArray());   // dst_extents

            ByteArrayOutputStream partition = new ByteArrayOutputStream();
            protoBytes(partition, 1, spec.name.getBytes("UTF-8"));
            protoBytes(partition, 7, info.toByteArray());
            protoBytes(partition, 8, operation.toByteArray());

            protoBytes(manifest, 13, partition.toByteArray());
            dataArea.write(spec.data);
        }

        byte[] manifestBytes = manifest.toByteArray();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write("CrAU".getBytes("UTF-8"));
        writeLongBigEndian(payload, 2L);
        writeLongBigEndian(payload, manifestBytes.length);
        writeIntBigEndian(payload, 0);
        payload.write(manifestBytes);
        payload.write(dataArea.toByteArray());

        File file = new File(workDir, "payload.bin");
        writeFile(file, payload.toByteArray());
        return file;
    }

    private static void writeZip(File target, String firstName, byte[] firstData,
                                 boolean stored, NamedBytes second) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target))) {
            putEntry(out, firstName, firstData, stored);
            if (second != null) {
                putEntry(out, second.name, second.data, stored);
            }
        }
    }

    private static void putEntry(ZipOutputStream out, String name, byte[] data, boolean stored)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (stored) {
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static void extractAll(PayloadCore.Inspection inspection, File outDir) throws Exception {
        List<PayloadCore.PartitionInfoRow> rows = new ArrayList<>(inspection.rows);
        PayloadCore.extract(inspection, rows, outDir, new PayloadCore.CancelToken(), null);
    }

    private static byte[] xz(byte[] raw) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (XZOutputStream out = new XZOutputStream(buffer, new LZMA2Options())) {
            out.write(raw);
        }
        return buffer.toByteArray();
    }

    private static byte[] patterned(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) ((i * seed + 7) & 0xff);
        }
        return out;
    }

    private static void protoVarint(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, ((long) field << 3) | 0);
        writeVarint(out, value);
    }

    private static void protoBytes(ByteArrayOutputStream out, int field, byte[] value) {
        writeVarint(out, ((long) field << 3) | 2);
        writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0L) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static void writeLongBigEndian(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xff));
        }
    }

    private static void writeIntBigEndian(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    private static byte[] readFile(File file) throws IOException {
        assertNotNull(file);
        assertTrue("文件不存在: " + file, file.exists());
        byte[] data = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        return data;
    }

    /**
     * 从聊天软件复制链接时经常夹带零宽空格、不换行空格与全角空格。
     * {@code String.trim()} 只处理 {@code <= U+0020}，这些字符它一个都不管，
     * 留下来会让 {@code new URI()} 抛异常，最终误导成「路径不存在」。
     */
    @Test
    public void normalizeInputRemovesInvisibleCharacters() {
        String dirty = "\u200Bhttps://example.com/a.zip?sign=1\u00A0&t=2\u3000\uFEFF";
        assertEquals("https://example.com/a.zip?sign=1&t=2", PayloadCore.normalizeInput(dirty));
    }

    /** 整句粘贴时要把链接本身抽出来，而不是拿整句话去当路径。 */
    @Test
    public void normalizeInputExtractsUrlFromSurroundingText() {
        assertEquals("https://example.com/a.zip?a=1",
                PayloadCore.normalizeInput("这个包 https://example.com/a.zip?a=1 试试看"));
        assertEquals("https://example.com/a.zip?a=1",
                PayloadCore.normalizeInput("https://example.com/a.zip?a=1"));
    }

    /** 含 http 但解析不了，要报「链接格式不合法」，不能报成「路径不存在」。 */
    @Test
    public void malformedUrlReportsLinkInsteadOfPath() {
        try {
            PayloadCore.inspect("http://");
            fail("应当抛出异常");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage(), e.getMessage().startsWith("链接格式不合法"));
        }
    }

    /** 报错里带原始输入要截断，否则一整条签名链接会把提示撑爆。 */
    @Test
    public void abbreviateCutsLongInput() {
        String longUrl = "https://example.com/" + "a".repeat(300);
        String cut = PayloadCore.abbreviate(longUrl, 40);
        assertEquals(40, cut.length());
        assertTrue(cut.endsWith("..."));
        assertEquals("short", PayloadCore.abbreviate("short", 40));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
