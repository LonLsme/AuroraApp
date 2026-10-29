package com.aurora.app.ui.payload;

import android.os.Build;
import android.text.TextUtils;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * payload 解包内核：识别输入 → 列分区 → 按需提取。
 *
 * <h3>支持的四类输入</h3>
 * <ol>
 *   <li><b>OTA 全量包</b>（zip，内含 {@code payload.bin}）—— 只读 zip 中央目录定位
 *       {@code payload.bin}，再读它的 manifest，不解压整包；</li>
 *   <li><b>裸 payload.bin</b>；</li>
 *   <li><b>欧加散包</b>（zip 里直接是若干 {@code .img}，没有 payload.bin）；</li>
 *   <li><b>单个镜像 / 本地目录</b> —— 直接复制，不经任何解析。</li>
 * </ol>
 *
 * <h3>为什么所有读取都走 {@link PayloadZip.Slice}</h3>
 * 本地文件、云端 URL、zip 内切片三者的差异只在「第 N 字节怎么拿」。
 * 把这一层收成一个接口后，payload 解压逻辑只有一份 ——
 * 本地全量包与云端全量包走的是同一段代码，不会出现「本地能提、云端报错」这种分裂。
 *
 * <h3>为什么提取要支持取消</h3>
 * 单个 super 分区解压后 10 GB 以上很常见。没有中断入口，用户点错一次就只能杀进程，
 * 而且半截文件会留在输出目录里。取消时抛 {@link CancelledException}，
 * 由调用方删掉未完成的文件。
 */
final class PayloadCore {

    /** 输入类型。 */
    static final int KIND_PAYLOAD = 0;
    static final int KIND_ZIP_IMAGES = 1;
    static final int KIND_SINGLE_IMAGE = 2;
    static final int KIND_DIRECTORY = 3;

    /** payload 里的操作类型（DeltaArchiveManifest.InstallOperation.Type）。 */
    private static final int OP_REPLACE = 0;
    private static final int OP_REPLACE_BZ = 1;
    private static final int OP_REPLACE_XZ = 8;
    private static final int OP_ZERO = 6;

    /** 复制/解压的缓冲大小：64 KB 与 {@link #PUMP_CHUNK} 一起决定取消的响应粒度。 */
    private static final int PUMP_CHUNK = 64 * 1024;

    private PayloadCore() {
    }

    // ───────────────────────────── 取消 ─────────────────────────────

    /** 取消令牌。提取线程每复制一块检查一次，因此取消最多 64 KB 的延迟。 */
    static final class CancelToken {
        private volatile boolean cancelled;

        void cancel() {
            cancelled = true;
        }

        boolean isCancelled() {
            return cancelled;
        }

        void throwIfCancelled() throws CancelledException {
            if (cancelled) {
                throw new CancelledException();
            }
        }
    }

    /** 取消时抛这个，调用方据此区分「用户停止」与「真失败」。 */
    static final class CancelledException extends IOException {
        CancelledException() {
            super("已停止");
        }
    }

    // ───────────────────────────── 数据模型 ─────────────────────────────

    /** 分区表的一行。zip 散包模式下 {@code entryName} 是条目全名，其余模式为 null。 */
    static final class PartitionInfoRow {
        final String name;
        final long size;
        final long rawSize;
        final String sha256;
        final String entryName;

        PartitionInfoRow(String name, long size, long rawSize, String sha256) {
            this(name, size, rawSize, sha256, null);
        }

        PartitionInfoRow(String name, long size, long rawSize, String sha256, String entryName) {
            this.name = name;
            this.size = size;
            this.rawSize = rawSize;
            this.sha256 = sha256;
            this.entryName = entryName;
        }
    }

    /** 一次「读取信息」的结果：类型 + 分区行 + 后续提取需要的句柄。 */
    static final class Inspection implements Closeable {
        final int kind;
        final String source;
        final String displayName;
        final long totalBytes;
        final List<PartitionInfoRow> rows;
        /** 仅 {@link #KIND_PAYLOAD} 非空。 */
        final PayloadSession session;
        /** 仅 {@link #KIND_ZIP_IMAGES} 非空。 */
        final PayloadZip zip;
        /** 仅 {@link #KIND_SINGLE_IMAGE} / {@link #KIND_DIRECTORY} 非空。 */
        final File localPath;

        Inspection(int kind, String source, String displayName, long totalBytes,
                   List<PartitionInfoRow> rows, PayloadSession session, PayloadZip zip, File localPath) {
            this.kind = kind;
            this.source = source;
            this.displayName = displayName;
            this.totalBytes = totalBytes;
            this.rows = rows;
            this.session = session;
            this.zip = zip;
            this.localPath = localPath;
        }

        @Override
        public void close() {
            if (session != null) {
                session.close();
            }
            if (zip != null) {
                zip.close();
            }
        }
    }

    /** payload 会话：manifest + 数据起点 + 底层数据源。 */
    static final class PayloadSession implements Closeable {
        final String fileName;
        final Manifest manifest;
        /** payload 数据区（各 operation 的 data_offset 相对它）的绝对起点。 */
        final long dataOffset;
        final int blockSize;
        final long archiveSize;
        final PayloadZip.Slice source;

        PayloadSession(String fileName, Manifest manifest, long dataOffset, int blockSize,
                       long archiveSize, PayloadZip.Slice source) {
            this.fileName = fileName;
            this.manifest = manifest;
            this.dataOffset = dataOffset;
            this.blockSize = blockSize;
            this.archiveSize = archiveSize;
            this.source = source;
        }

        String getSecurityPatchLevel() {
            return manifest.securityPatchLevel;
        }

        @Override
        public void close() {
            try {
                source.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ───────────────────────────── 输入识别 ─────────────────────────────

    /**
     * 识别输入并列出分区。
     *
     * <p>调用方负责在执行结束后 {@link Inspection#close()}；本方法失败时已自行释放资源。
     */
    static Inspection inspect(String input) throws IOException {
        if (TextUtils.isEmpty(input)) {
            throw new IOException("请输入本地路径或 http(s) 链接");
        }
        String value = input.trim();
        if (isValidUrl(value)) {
            return inspectRemote(value);
        }
        if (value.startsWith("file://")) {
            value = value.substring("file://".length());
        }
        File file = new File(value);
        if (!file.exists()) {
            throw new IOException("路径不存在：" + value);
        }
        if (file.isDirectory()) {
            return inspectDirectory(file);
        }
        return inspectLocalFile(file);
    }

    /** 本地目录：列出其中的镜像文件，提取就是复制。 */
    private static Inspection inspectDirectory(File dir) throws IOException {
        File[] children = dir.listFiles();
        List<PartitionInfoRow> rows = new ArrayList<>();
        long total = 0L;
        if (children != null) {
            for (File child : children) {
                if (!child.isFile() || !isImageName(child.getName())) {
                    continue;
                }
                rows.add(new PartitionInfoRow(stripImageSuffix(child.getName()),
                        child.length(), child.length(), ""));
                total += child.length();
            }
        }
        rows.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return new Inspection(KIND_DIRECTORY, dir.getAbsolutePath(), dir.getName(), total,
                rows, null, null, dir);
    }

    /** 本地文件：按魔数判 payload / zip / 单镜像，不靠后缀猜。 */
    private static Inspection inspectLocalFile(File file) throws IOException {
        byte[] head = readHead(file, 4);
        if (isPayloadMagic(head)) {
            PayloadZip.LocalSlice slice = new PayloadZip.LocalSlice(file);
            try {
                PayloadSession session = parsePayload(slice, 0L, file.getName());
                return payloadInspection(file.getAbsolutePath(), file.getName(), file.length(), session);
            } catch (IOException e) {
                slice.close();
                throw e;
            }
        }
        if (isZipMagic(head)) {
            PayloadZip zip = PayloadZip.open(new PayloadZip.LocalSlice(file));
            try {
                return inspectZip(zip, file.getAbsolutePath(), file.getName(), file.length());
            } catch (IOException e) {
                zip.close();
                throw e;
            }
        }
        if (isImageName(file.getName())) {
            List<PartitionInfoRow> rows = new ArrayList<>();
            rows.add(new PartitionInfoRow(stripImageSuffix(file.getName()),
                    file.length(), file.length(), ""));
            return new Inspection(KIND_SINGLE_IMAGE, file.getAbsolutePath(), file.getName(),
                    file.length(), rows, null, null, file);
        }
        throw new IOException("无法识别的文件类型：" + file.getName()
                + "\n支持 payload.bin、OTA 全量包 zip、散装镜像 zip、单个 .img、镜像目录");
    }

    /** 云端：先探 Content-Length，再按 zip 结构判断是 payload 还是散包。 */
    private static Inspection inspectRemote(String url) throws IOException {
        HttpReader http = new HttpReader();
        try {
            http.init(url, null);
            long length = http.length();
            byte[] head = new byte[4];
            http.seek(0L);
            http.readFully(head);
            if (isPayloadMagic(head)) {
                PayloadSession session = parsePayload(new PayloadZip.HttpSlice(http), 0L,
                        http.getFileName());
                return new Inspection(KIND_PAYLOAD, url, http.getFileName(), length,
                        payloadRows(session), session, null, null);
            }
            if (!isZipMagic(head)) {
                throw new IOException("远端文件既不是 payload.bin 也不是 zip，无法识别");
            }
            PayloadZip zip = PayloadZip.open(new PayloadZip.HttpSlice(http));
            return inspectZip(zip, url, http.getFileName(), length);
        } catch (IOException | RuntimeException e) {
            http.close();
            throw e instanceof IOException ? (IOException) e
                    : new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    /** zip 的两种形态：有 payload.bin 就当全量包，否则按散装镜像列表。 */
    private static Inspection inspectZip(PayloadZip zip, String source, String displayName, long total)
            throws IOException {
        PayloadZip.Entry payloadEntry = zip.find("payload.bin");
        if (payloadEntry != null) {
            if (payloadEntry.method != 0) {
                throw new IOException("包内 payload.bin 是压缩存储的（method="
                        + payloadEntry.method + "），无法按偏移读取；请解压出 payload.bin 后再选它");
            }
            long offset = zip.dataOffset(payloadEntry);
            PayloadSession session = parsePayload(zip.slice, offset, displayName);
            return payloadInspection(source, displayName, total, session, zip);
        }

        List<PartitionInfoRow> rows = new ArrayList<>();
        for (PayloadZip.Entry entry : zip.entries) {
            String name = entry.name;
            if (name.indexOf('/') >= 0) {
                name = name.substring(name.lastIndexOf('/') + 1);
            }
            if (!isImageName(name)) {
                continue;
            }
            rows.add(new PartitionInfoRow(stripImageSuffix(name), entry.size,
                    entry.compressedSize, "", entry.name));
        }
        if (rows.isEmpty()) {
            throw new IOException("包内既没有 payload.bin，也没有任何 .img 镜像");
        }
        rows.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return new Inspection(KIND_ZIP_IMAGES, source, displayName, total, rows, null, zip, null);
    }

    private static Inspection payloadInspection(String source, String displayName, long total,
                                                PayloadSession session) {
        return payloadInspection(source, displayName, total, session, null);
    }

    private static Inspection payloadInspection(String source, String displayName, long total,
                                                PayloadSession session, PayloadZip zip) {
        return new Inspection(KIND_PAYLOAD, source, displayName, total,
                payloadRows(session), session, zip, null);
    }

    private static List<PartitionInfoRow> payloadRows(PayloadSession session) {
        List<PartitionInfoRow> rows = new ArrayList<>();
        for (Partition partition : session.manifest.partitions) {
            if (partition.operations.isEmpty()) {
                continue;
            }
            rows.add(new PartitionInfoRow(partition.name, partition.newSize, 0L,
                    toHex(partition.newHash)));
        }
        rows.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return rows;
    }

    /** 从数据源读 payload 头与 manifest。{@code payloadOffset} 是 payload 在源内的绝对起点。 */
    private static PayloadSession parsePayload(PayloadZip.Slice source, long payloadOffset, String fileName)
            throws IOException {
        // 头固定 24 字节：魔数(4) + 版本(8) + manifest 大小(8) + 元数据签名大小(4)
        byte[] header = new byte[24];
        source.readAt(payloadOffset, header, 0, header.length);
        if (!isPayloadMagic(header)) {
            throw new IOException("不是 payload 格式（缺少 CrAU 魔数）");
        }
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        bb.position(4);
        long version = bb.getLong();
        if (version != 2L) {
            throw new IOException("payload 版本 " + version + " 不受支持（只支持 v2）");
        }
        long manifestSize = bb.getLong();
        int signatureSize = bb.getInt();
        if (manifestSize <= 0 || manifestSize > 64L * 1024 * 1024) {
            throw new IOException("manifest 大小异常：" + manifestSize);
        }

        byte[] manifest = new byte[(int) manifestSize];
        source.readAt(payloadOffset + header.length, manifest, 0, manifest.length);
        Manifest parsed = new ManifestParser(manifest).parse();
        long dataOffset = payloadOffset + header.length + manifestSize + Math.max(0, signatureSize);
        return new PayloadSession(fileName, parsed, dataOffset, parsed.blockSize, manifestSize,
                source);
    }

    // ───────────────────────────── 提取 ─────────────────────────────

    /** 提取进度回调；所有回调都在调用线程（后台线程）上执行，切 UI 由调用方负责。 */
    interface ExtractListener {
        void onEntryStarted(String name, int index, int total);

        /**
         * @param entryDone  当前条目已写出字节
         * @param entryTotal 当前条目预期字节（未知时为 0）
         * @param totalDone  所有条目累计已写出字节
         * @param totalSize  所有条目预期总字节
         */
        void onProgress(long entryDone, long entryTotal, long totalDone, long totalSize);
    }

    /**
     * 提取指定条目到输出目录。
     *
     * <p>失败与取消都会删掉未完成的文件，不会在输出目录留下半截镜像 ——
     * 半截 img 被当成完整镜像刷进设备是最坏的结果。
     */
    static void extract(Inspection inspection, List<PartitionInfoRow> targets, File outDir,
                        CancelToken token, ExtractListener listener) throws IOException {
        if (targets.isEmpty()) {
            throw new IOException("没有勾选任何分区");
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建输出目录：" + outDir.getAbsolutePath());
        }

        long totalSize = 0L;
        for (PartitionInfoRow row : targets) {
            totalSize += Math.max(0L, row.size);
        }

        long doneTotal = 0L;
        for (int i = 0; i < targets.size(); i++) {
            PartitionInfoRow row = targets.get(i);
            if (token != null) {
                token.throwIfCancelled();
            }
            if (listener != null) {
                listener.onEntryStarted(row.name, i + 1, targets.size());
            }
            File outFile = new File(outDir, outputName(row));
            try {
                long written;
                switch (inspection.kind) {
                    case KIND_PAYLOAD:
                        written = extractPayloadEntry(inspection.session, row, outFile, token,
                                i, doneTotal, totalSize, listener);
                        break;
                    case KIND_ZIP_IMAGES:
                        written = extractZipEntry(inspection.zip, row, outFile, token,
                                doneTotal, totalSize, listener);
                        break;
                    default:
                        written = copyLocalFile(inspection.kind == KIND_DIRECTORY
                                        ? new File(inspection.localPath, row.entryName != null
                                        ? row.entryName : row.name + ".img")
                                        : inspection.localPath,
                                outFile, token, doneTotal, totalSize, listener);
                        break;
                }
                doneTotal += Math.max(written, 0L);
            } catch (IOException e) {
                deleteQuietly(outFile);
                throw e;
            }
        }
    }

    private static String outputName(PartitionInfoRow row) {
        String base = row.name;
        if (row.entryName != null) {
            base = row.entryName;
            int slash = base.lastIndexOf('/');
            if (slash >= 0) {
                base = base.substring(slash + 1);
            }
        }
        return base.toLowerCase(Locale.US).endsWith(".img") ? base : base + ".img";
    }

    /** payload 分区：逐 operation 解压并写入目标 extents。 */
    private static long extractPayloadEntry(PayloadSession session, PartitionInfoRow row, File outFile,
                                            CancelToken token, int index, long doneBase,
                                            long totalSize, ExtractListener listener)
            throws IOException {
        Partition partition = null;
        for (Partition candidate : session.manifest.partitions) {
            if (candidate.name.equals(row.name)) {
                partition = candidate;
                break;
            }
        }
        if (partition == null) {
            throw new IOException("payload 内没有分区 " + row.name);
        }
        long expected = Math.max(row.size, 0L);
        long written = 0L;
        try (RandomAccessFile out = new RandomAccessFile(outFile, "rw")) {
            out.setLength(0L);
            for (Operation operation : partition.operations) {
                if (token != null) {
                    token.throwIfCancelled();
                }
                written += runOperation(session, operation, out, token,
                        written, expected, doneBase, totalSize, listener);
            }
        }
        return written;
    }

    /** 单个 operation：取数据 → 解压 → 写 extents。返回写入字节数。 */
    private static long runOperation(PayloadSession session, Operation operation, RandomAccessFile out,
                                     CancelToken token, long entryDoneBase, long entryTotal,
                                     long doneBase, long totalSize, ExtractListener listener)
            throws IOException {
        long dataStart = session.dataOffset + operation.dataOffset;
        switch (operation.type) {
            case OP_ZERO: {
                try (ExtentOutputStream extents = new ExtentOutputStream(out, operation.dstExtents,
                        session.blockSize)) {
                    long remaining = operation.dataLength;
                    byte[] zeros = new byte[PUMP_CHUNK];
                    long written = 0L;
                    while (remaining > 0) {
                        if (token != null) {
                            token.throwIfCancelled();
                        }
                        int chunk = (int) Math.min(PUMP_CHUNK, remaining);
                        extents.write(zeros, 0, chunk);
                        remaining -= chunk;
                        written += chunk;
                        notifyProgress(listener, entryDoneBase + written, entryTotal,
                                doneBase + written, totalSize);
                    }
                    return written;
                }
            }
            case OP_REPLACE:
            case OP_REPLACE_BZ:
            case OP_REPLACE_XZ: {
                try (InputStream raw = session.source.openRange(dataStart, operation.dataLength);
                     InputStream data = wrapDecompress(raw, operation.type);
                     ExtentOutputStream extents = new ExtentOutputStream(out, operation.dstExtents,
                             session.blockSize)) {
                    return pump(data, extents, token, entryDoneBase, entryTotal, doneBase, totalSize,
                            listener);
                }
            }
            default:
                throw new IOException("不支持的操作类型 " + operation.type
                        + "（该分区用了增量补丁或 Brotli 压缩，需完整镜像包）");
        }
    }

    private static InputStream wrapDecompress(InputStream raw, int type) throws IOException {
        InputStream buffered = new BufferedInputStream(raw, PUMP_CHUNK);
        switch (type) {
            case OP_REPLACE_XZ:
                return new XZCompressorInputStream(buffered, true);
            case OP_REPLACE_BZ:
                return new BZip2CompressorInputStream(buffered, true);
            default:
                return buffered;
        }
    }

    /** zip 散包条目：解压后直接复制。 */
    private static long extractZipEntry(PayloadZip zip, PartitionInfoRow row, File outFile,
                                        CancelToken token, long doneBase, long totalSize,
                                        ExtractListener listener)
            throws IOException {
        PayloadZip.Entry entry = row.entryName == null ? zip.find(row.name + ".img")
                : zip.find(row.entryName);
        if (entry == null) {
            throw new IOException("包内找不到 " + row.name);
        }
        try (InputStream in = new BufferedInputStream(zip.openEntry(entry), PUMP_CHUNK);
             OutputStream out = new FileOutputStream(outFile)) {
            return pump(in, out, token, 0L, Math.max(entry.size, 0L), doneBase, totalSize, listener);
        }
    }

    /** 单镜像 / 目录里的文件：原样复制。 */
    private static long copyLocalFile(File source, File outFile, CancelToken token,
                                      long doneBase, long totalSize, ExtractListener listener)
            throws IOException {
        if (source == null || !source.exists()) {
            throw new IOException("源文件不存在");
        }
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(source), PUMP_CHUNK);
             OutputStream out = new FileOutputStream(outFile)) {
            return pump(in, out, token, 0L, source.length(), doneBase, totalSize, listener);
        }
    }

    /** 搬运数据并在每块前后检查取消、上报进度。 */
    private static long pump(InputStream in, OutputStream out, CancelToken token,
                             long entryDoneBase, long entryTotal, long doneBase, long totalSize,
                             ExtractListener listener)
            throws IOException {
        byte[] buffer = new byte[PUMP_CHUNK];
        long written = 0L;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (token != null) {
                token.throwIfCancelled();
            }
            out.write(buffer, 0, read);
            written += read;
            notifyProgress(listener, entryDoneBase + written, entryTotal, doneBase + written,
                    totalSize);
        }
        out.flush();
        return written;
    }

    private static void notifyProgress(ExtractListener listener, long entryDone, long entryTotal,
                                       long totalDone, long totalSize) {
        if (listener != null) {
            listener.onProgress(entryDone, entryTotal, totalDone, totalSize);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    static boolean isValidUrl(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && !TextUtils.isEmpty(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isImageName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".img") || lower.endsWith(".bin");
    }

    private static String stripImageSuffix(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".img")) {
            return name.substring(0, name.length() - 4);
        }
        if (lower.endsWith(".bin")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static boolean isPayloadMagic(byte[] head) {
        return head != null && head.length >= 4
                && head[0] == 'C' && head[1] == 'r' && head[2] == 'A' && head[3] == 'U';
    }

    private static boolean isZipMagic(byte[] head) {
        return head != null && head.length >= 4
                && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    private static byte[] readHead(File file, int length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[length];
            int read = raf.read(head);
            if (read < length) {
                throw new IOException("文件太小，不是有效镜像：" + file.getName());
            }
            return head;
        }
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    // ───────────────────────────── HTTP ─────────────────────────────

    /**
     * 带 Range 的只读 HTTP 数据源。
     *
     * <p>每个读取动作都是一次独立的 Range 请求，互不影响。
     * <b>服务器不支持 Range 时直接失败</b>：此时 200 响应会把整个文件当成所请求的片段，
     * 数据会整体错位 —— 与其解出坏镜像，不如明确报错。
     */
    static final class HttpReader implements Closeable {
        private final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        private static final String DEFAULT_UA = buildDefaultUserAgent();
        private String url;
        private String fileName;
        private long fileLength;
        private long position;
        private String userAgent = DEFAULT_UA;

        private static String buildDefaultUserAgent() {
            StringBuilder sb = new StringBuilder();
            String release = Build.VERSION.RELEASE_OR_CODENAME;
            String buildId = Build.ID;
            boolean validRelease = !TextUtils.isEmpty(release);
            boolean validId = !TextUtils.isEmpty(buildId);
            boolean includeModel = "REL".equals(Build.VERSION.CODENAME)
                    && !TextUtils.isEmpty(Build.MODEL);

            sb.append("AndroidDownloadManager");
            if (validRelease) {
                sb.append("/").append(release);
            }
            sb.append(" (Linux; U; Android");
            if (includeModel || validId) {
                sb.append(";");
                if (includeModel) {
                    sb.append(" ").append(Build.MODEL);
                }
                if (validId) {
                    sb.append(" Build/").append(buildId);
                }
            }
            sb.append(")");
            return sb.toString();
        }

        void init(String url, String userAgent) throws IOException {
            this.url = url;
            this.userAgent = TextUtils.isEmpty(userAgent) ? DEFAULT_UA : userAgent;

            // 先用 1 字节 Range 探一下：既拿总长度，又能立刻看出服务器支不支持 Range
            Response response = null;
            try {
                response = client.newCall(new Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", this.userAgent)
                        .addHeader("Accept", "*/*")
                        .addHeader("Range", "bytes=0-0")
                        .build()).execute();

                if (response.code() == 206) {
                    String contentRange = response.header("Content-Range");
                    if (contentRange != null && contentRange.contains("/")) {
                        fileLength = Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1));
                    } else {
                        fileLength = response.body() == null ? 0L : response.body().contentLength();
                    }
                } else if (response.isSuccessful()) {
                    throw new IOException("该链接不支持 Range 分段读取（HTTP " + response.code()
                            + "），无法按需解析大包；请改用支持 Range 的直链或下载到本地");
                } else {
                    throw new IOException("HTTP " + response.code() + " " + response.message());
                }
                fileName = getFileNameFromHeaders(response.headers());
                position = 0L;
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }

        long length() {
            return fileLength;
        }

        String getFileName() {
            return fileName;
        }

        void seek(long bytePosition) throws IOException {
            if (bytePosition < 0 || bytePosition >= fileLength) {
                throw new IOException("读取位置越界：" + bytePosition + " / " + fileLength);
            }
            position = bytePosition;
        }

        void readFully(byte[] target) throws IOException {
            long end = position + target.length - 1;
            Response response = client.newCall(new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("Accept", "*/*")
                    .addHeader("Range", "bytes=" + position + "-" + end)
                    .build()).execute();
            try {
                if (response.code() != 206) {
                    throw new IOException("服务器未按 Range 返回（HTTP " + response.code() + "）");
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("响应体为空");
                }
                InputStream in = body.byteStream();
                int readTotal = 0;
                while (readTotal < target.length) {
                    int r = in.read(target, readTotal, target.length - readTotal);
                    if (r < 0) {
                        throw new IOException("Range 读取提前结束");
                    }
                    readTotal += r;
                }
                position += readTotal;
            } finally {
                response.close();
            }
        }

        InputStream openRangeStream(long start, long length) throws IOException {
            if (length < 0) {
                throw new IOException("长度无效：" + length);
            }
            if (length == 0) {
                return new java.io.ByteArrayInputStream(new byte[0]);
            }
            long end = start + length - 1;
            Response response = client.newCall(new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("Accept", "*/*")
                    .addHeader("Range", "bytes=" + start + "-" + end)
                    .build()).execute();
            if (response.code() != 206) {
                response.close();
                throw new IOException("服务器未按 Range 返回（HTTP " + response.code() + "）");
            }
            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                throw new IOException("响应体为空");
            }
            return new BoundedResponseInputStream(body.byteStream(), response, length);
        }

        private String getFileNameFromHeaders(Headers headers) {
            String contentDisposition = headers.get("Content-Disposition");
            if (!TextUtils.isEmpty(contentDisposition)) {
                String[] parts = contentDisposition.split(";");
                for (String part : parts) {
                    String p = part.trim();
                    if (p.startsWith("filename=")) {
                        return p.substring("filename=".length()).replace("\"", "");
                    }
                }
            }
            try {
                return new File(URI.create(url).getPath()).getName();
            } catch (Exception e) {
                return "remote_package.zip";
            }
        }

        @Override
        public void close() {
        }
    }

    // ───────────────────────────── 内部结构 ─────────────────────────────

    static final class Manifest {
        int blockSize = 4096;
        String securityPatchLevel = "";
        final List<Partition> partitions = new ArrayList<>();
    }

    static final class Partition {
        String name = "";
        long newSize = 0L;
        byte[] newHash = new byte[0];
        final List<Operation> operations = new ArrayList<>();
    }

    private static final class Operation {
        int type;
        long dataOffset;
        long dataLength;
        final List<Extent> dstExtents = new ArrayList<>();
    }

    private static final class Extent {
        long startBlock;
        long numBlocks;
    }

    private static final class BoundedResponseInputStream extends InputStream {
        private final InputStream delegate;
        private final Response response;
        private long remaining;
        private boolean closed;

        BoundedResponseInputStream(InputStream delegate, Response response, long remaining) {
            this.delegate = delegate;
            this.response = response;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = delegate.read();
            if (b < 0) {
                throw new IOException("Range 流提前结束");
            }
            remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int read = delegate.read(b, off, toRead);
            if (read < 0) {
                throw new IOException("Range 流提前结束");
            }
            remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                delegate.close();
            } finally {
                response.close();
            }
        }
    }

    private static final class ManifestParser {
        private final ProtoReader root;

        ManifestParser(byte[] bytes) {
            this.root = new ProtoReader(bytes);
        }

        Manifest parse() throws IOException {
            Manifest m = new Manifest();
            while (root.hasNext()) {
                int tag = root.readTag();
                int field = tag >>> 3;
                int wire = tag & 0x7;
                if (field == 3 && wire == 0) {
                    m.blockSize = (int) root.readVarint64();
                } else if (field == 13 && wire == 2) {
                    m.partitions.add(parsePartition(new ProtoReader(root.readLengthDelimited())));
                } else if (field == 18 && wire == 2) {
                    m.securityPatchLevel = root.readString();
                } else {
                    root.skipField(wire);
                }
            }
            if (m.blockSize <= 0) {
                m.blockSize = 4096;
            }
            return m;
        }

        private Partition parsePartition(ProtoReader r) throws IOException {
            Partition p = new Partition();
            while (r.hasNext()) {
                int tag = r.readTag();
                int field = tag >>> 3;
                int wire = tag & 0x7;
                if (field == 1 && wire == 2) {
                    p.name = r.readString();
                } else if (field == 7 && wire == 2) {
                    parsePartitionInfo(new ProtoReader(r.readLengthDelimited()), p);
                } else if (field == 8 && wire == 2) {
                    p.operations.add(parseOperation(new ProtoReader(r.readLengthDelimited())));
                } else {
                    r.skipField(wire);
                }
            }
            return p;
        }

        private void parsePartitionInfo(ProtoReader r, Partition p) throws IOException {
            while (r.hasNext()) {
                int tag = r.readTag();
                int field = tag >>> 3;
                int wire = tag & 0x7;
                if (field == 1 && wire == 0) {
                    p.newSize = r.readVarint64();
                } else if (field == 2 && wire == 2) {
                    p.newHash = r.readLengthDelimited();
                } else {
                    r.skipField(wire);
                }
            }
        }

        private Operation parseOperation(ProtoReader r) throws IOException {
            Operation op = new Operation();
            while (r.hasNext()) {
                int tag = r.readTag();
                int field = tag >>> 3;
                int wire = tag & 0x7;
                if (field == 1 && wire == 0) {
                    op.type = (int) r.readVarint64();
                } else if (field == 2 && wire == 0) {
                    op.dataOffset = r.readVarint64();
                } else if (field == 3 && wire == 0) {
                    op.dataLength = r.readVarint64();
                } else if (field == 6 && wire == 2) {
                    op.dstExtents.add(parseExtent(new ProtoReader(r.readLengthDelimited())));
                } else {
                    r.skipField(wire);
                }
            }
            return op;
        }

        private Extent parseExtent(ProtoReader r) throws IOException {
            Extent e = new Extent();
            while (r.hasNext()) {
                int tag = r.readTag();
                int field = tag >>> 3;
                int wire = tag & 0x7;
                if (field == 1 && wire == 0) {
                    e.startBlock = r.readVarint64();
                } else if (field == 2 && wire == 0) {
                    e.numBlocks = r.readVarint64();
                } else {
                    r.skipField(wire);
                }
            }
            return e;
        }
    }

    private static final class ProtoReader {
        private final byte[] data;
        private int pos = 0;

        ProtoReader(byte[] data) {
            this.data = data;
        }

        boolean hasNext() {
            return pos < data.length;
        }

        int readTag() throws IOException {
            if (!hasNext()) throw new IOException("manifest 读取越界");
            return (int) readVarint64();
        }

        long readVarint64() throws IOException {
            long result = 0;
            int shift = 0;
            while (shift < 64) {
                if (pos >= data.length) throw new IOException("manifest varint 截断");
                int b = data[pos++] & 0xff;
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IOException("manifest varint 过长");
        }

        byte[] readLengthDelimited() throws IOException {
            long len = readVarint64();
            if (len < 0 || pos + len > data.length) throw new IOException("manifest 长度无效");
            byte[] out = new byte[(int) len];
            System.arraycopy(data, pos, out, 0, (int) len);
            pos += (int) len;
            return out;
        }

        String readString() throws IOException {
            return new String(readLengthDelimited(), StandardCharsets.UTF_8);
        }

        void skipField(int wireType) throws IOException {
            switch (wireType) {
                case 0:
                    readVarint64();
                    return;
                case 1:
                    skip(8);
                    return;
                case 2:
                    skip((int) readVarint64());
                    return;
                case 5:
                    skip(4);
                    return;
                default:
                    throw new IOException("manifest wire type=" + wireType);
            }
        }

        private void skip(int n) throws IOException {
            if (n < 0 || pos + n > data.length) throw new IOException("manifest skip 越界");
            pos += n;
        }
    }

    /**
     * 按 extents 把流写进目标文件的对应块区间。
     *
     * <p>payload 的分区数据是被拆成若干 extent 的（中间可能有空洞），
     * 所以不能顺序写 —— 必须按 extent 计算真实落盘位置。
     */
    private static final class ExtentOutputStream extends OutputStream {
        private final RandomAccessFile raf;
        private final List<Extent> extents;
        private final int blockSize;
        private int idx = 0;
        private long offsetInExtent = 0L;

        ExtentOutputStream(RandomAccessFile raf, List<Extent> extents, int blockSize) throws IOException {
            this.raf = raf;
            this.extents = extents;
            this.blockSize = blockSize;
            seekCurrent();
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int remain = len;
            int cursor = off;
            while (remain > 0) {
                if (idx >= extents.size()) {
                    throw new IOException("写入超出 extents 范围（manifest 与数据不匹配）");
                }
                Extent e = extents.get(idx);
                long avail = e.numBlocks * blockSize - offsetInExtent;
                if (avail <= 0) {
                    idx++;
                    offsetInExtent = 0L;
                    seekCurrent();
                    continue;
                }
                int w = (int) Math.min(remain, avail);
                raf.write(b, cursor, w);
                cursor += w;
                remain -= w;
                offsetInExtent += w;
            }
        }

        private void seekCurrent() throws IOException {
            if (idx >= extents.size()) {
                return;
            }
            Extent e = extents.get(idx);
            raf.seek(e.startBlock * blockSize + offsetInExtent);
        }
    }
}
