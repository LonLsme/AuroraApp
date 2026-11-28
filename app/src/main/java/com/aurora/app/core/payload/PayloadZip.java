package com.aurora.app.core.payload;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * ZIP 目录索引：只读中央目录，不下载、不解压包体。
 *
 * <h3>为什么不用 {@link java.util.zip.ZipFile}</h3>
 * 它只能吃本地文件；云端全量包动辄 5～8 GB，先整包下载再解包不现实。
 * 这里把「读第 N 字节」抽象成 {@link Slice}，本地与 HTTP Range 共用同一套解析逻辑 ——
 * 云端包只花几十 KB 流量就能拿到完整分区表（payload.bin 在全量包里通常是 stored，
 * 表头与数据都能按需 Range 拉取）。
 *
 * <h3>为什么自己解析而不用现成实现</h3>
 * 之前那版按 32 位读 local header 偏移，超过 4 GB 的全量包会定位到错误位置（
 * {@code 0xFFFFFFFF} 截断）。本实现完整支持 ZIP64：中央目录大小、偏移、
 * 条目数与压缩后大小都按 8 字节读，并按 ZIP64 extra 字段的「出现顺序」取值。
 *
 * <h3>线程</h3>
 * 索引本身不可变；{@link Slice} 的读取在调用方线程串行进行（本项目提取是单线程），
 * 因此不额外加锁。
 */
final class PayloadZip implements Closeable {

    /** 中央目录条目：名称、压缩方式、大小、local header 偏移。 */
    static final class Entry {
        final String name;
        final int method;
        final long compressedSize;
        final long size;
        final long localHeaderOffset;

        /** local header 之后的数据起点；-1 表示还没算过。 */
        long dataOffset = -1L;

        Entry(String name, int method, long compressedSize, long size, long localHeaderOffset) {
            this.name = name;
            this.method = method;
            this.compressedSize = compressedSize;
            this.size = size;
            this.localHeaderOffset = localHeaderOffset;
        }
    }

    /** 随机读抽象：本地文件与 HTTP Range 各一个实现。 */
    interface Slice extends Closeable {
        long size();

        void readAt(long position, byte[] target, int offset, int length) throws IOException;

        InputStream openRange(long position, long length) throws IOException;
    }

    /**
     * 本地文件切片。
     *
     * <p>用 {@link FileChannel#read(ByteBuffer, long)} 而不是 {@link RandomAccessFile}：
     * 前者带位置参数、不会改动共享文件指针，天然是 pread 语义 ——
     * 同一时刻既算条目偏移又读条目内容时不会互相打乱。
     */
    static final class LocalSlice implements Slice {
        private final FileChannel channel;

        LocalSlice(File file) throws IOException {
            this.channel = new RandomAccessFile(file, "r").getChannel();
        }

        @Override
        public long size() {
            try {
                return channel.size();
            } catch (IOException e) {
                return 0L;
            }
        }

        @Override
        public void readAt(long position, byte[] target, int offset, int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(target, offset, length);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position + (buffer.position() - offset));
                if (read < 0) {
                    throw new IOException("读取超出文件末尾");
                }
            }
        }

        @Override
        public InputStream openRange(long position, long length) {
            return new SliceInputStream(channel, position, length);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    /** 按绝对位置顺序读取定长片段，不触碰文件指针。 */
    private static final class SliceInputStream extends InputStream {
        private final FileChannel channel;
        private long position;
        private long remaining;

        SliceInputStream(FileChannel channel, long position, long length) {
            this.channel = channel;
            this.position = position;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(length, remaining);
            ByteBuffer buffer = ByteBuffer.wrap(target, offset, toRead);
            int read = channel.read(buffer, position);
            if (read <= 0) {
                return -1;
            }
            position += read;
            remaining -= read;
            return read;
        }
    }

    /** 云端切片：一次 Range 请求对应一段读取，由 {@link PayloadCore.HttpReader} 完成。 */
    static final class HttpSlice implements Slice {
        private final PayloadCore.HttpReader reader;

        HttpSlice(PayloadCore.HttpReader reader) {
            this.reader = reader;
        }

        @Override
        public long size() {
            return reader.length();
        }

        @Override
        public void readAt(long position, byte[] target, int offset, int length) throws IOException {
            reader.seek(position);
            if (offset == 0 && length == target.length) {
                reader.readFully(target);
                return;
            }
            byte[] chunk = new byte[length];
            reader.readFully(chunk);
            System.arraycopy(chunk, 0, target, offset, length);
        }

        @Override
        public InputStream openRange(long position, long length) throws IOException {
            return reader.openRangeStream(position, length);
        }

        @Override
        public void close() {
            reader.close();
        }
    }

    private static final long SIG_EOCD = 0x06054b50L;
    private static final long SIG_CEN = 0x02014b50L;
    private static final long SIG_ZIP64_EOCD = 0x06064b50L;
    private static final long SIG_ZIP64_LOCATOR = 0x07064b50L;
    private static final long SIG_LOCAL = 0x04034b50L;
    private static final long ZIP64_MAGIC = 0xFFFFFFFFL;

    /**
     * EOCD 最大长度 = 22 字节固定部分 + 65535 字节注释。
     * 尾部读这么多就一定能覆盖 EOCD 与 ZIP64 locator，不必多次请求。
     */
    private static final int TAIL_MAX = 22 + 0xFFFF;

    /** 中央目录通常几百 KB；超过这个量级说明文件异常，直接判失败而不是把内存吃满。 */
    private static final long CENTRAL_DIR_LIMIT = 64L * 1024 * 1024;

    final Slice slice;
    final List<Entry> entries;
    final long archiveSize;

    private PayloadZip(Slice slice, List<Entry> entries, long archiveSize) {
        this.slice = slice;
        this.entries = entries;
        this.archiveSize = archiveSize;
    }

    static PayloadZip open(Slice slice) throws IOException {
        long length = slice.size();
        if (length <= 0) {
            throw new IOException("包体为空或长度不可读");
        }
        int tailLength = (int) Math.min(length, TAIL_MAX);
        byte[] tail = new byte[tailLength];
        slice.readAt(length - tailLength, tail, 0, tailLength);

        ByteBuffer bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        int eocdPos = -1;
        for (int i = tailLength - 22; i >= 0; i--) {
            bb.position(i);
            if ((bb.getInt() & 0xffffffffL) == SIG_EOCD) {
                eocdPos = i;
                break;
            }
        }
        if (eocdPos < 0) {
            throw new IOException("不是有效的 ZIP 包（找不到中央目录结尾）");
        }

        // EOCD 布局：签名(4) 本盘号(2) 目录所在盘(2) 本盘条目数(2) 总条目数(2) 目录大小(4) 目录偏移(4) 注释长度(2)
        bb.position(eocdPos + 8);
        bb.getShort();                                    // 本盘条目数，与总条目数一致时无意义
        int totalEntries = bb.getShort() & 0xffff;
        long centralSize = bb.getInt() & 0xffffffffL;
        long centralOffset = bb.getInt() & 0xffffffffL;

        // ZIP64：三个字段任意一个是 0xFFFFFFFF / 0xFFFF，就去 ZIP64 EOCD 重读 8 字节版本
        if (centralSize == ZIP64_MAGIC || centralOffset == ZIP64_MAGIC || totalEntries == 0xFFFF) {
            long[] zip64 = readZip64End(tail, eocdPos, tailLength, length, slice);
            if (zip64 != null) {
                totalEntries = (int) Math.min(Integer.MAX_VALUE, zip64[0]);
                centralSize = zip64[1];
                centralOffset = zip64[2];
            }
        }
        if (centralSize <= 0 || centralOffset < 0) {
            throw new IOException("中央目录位置无效");
        }
        if (centralSize > CENTRAL_DIR_LIMIT) {
            throw new IOException("中央目录异常大（" + centralSize + " 字节），拒绝解析");
        }

        byte[] central = new byte[(int) centralSize];
        slice.readAt(centralOffset, central, 0, central.length);
        List<Entry> parsed = parseCentral(central, totalEntries);
        if (parsed.isEmpty()) {
            throw new IOException("包内没有任何文件");
        }
        return new PayloadZip(slice, parsed, length);
    }

    /**
     * 读 ZIP64 结尾记录。
     *
     * <p>先找 20 字节的 ZIP64 locator，它紧贴在 EOCD 前面；locator 里给出 ZIP64 EOCD 的
     * 64 位偏移，再按该偏移去读。ZIP64 EOCD 通常在 locator 前面，不用额外请求；
     * 万一不在尾部缓冲区内（注释特别长的包），补一次精确读取。
     *
     * @return {@code [条目数, 中央目录大小, 中央目录偏移]}，读不到返回 null 由调用方退回 32 位值
     */
    private static long[] readZip64End(byte[] tail, int eocdPos, int tailLength, long fileLength,
                                       Slice slice) throws IOException {
        int locatorPos = eocdPos - 20;
        if (locatorPos < 0) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(locatorPos);
        if ((bb.getInt() & 0xffffffffL) != SIG_ZIP64_LOCATOR) {
            return null;
        }
        bb.position(locatorPos + 8);
        long zip64EndOffset = bb.getLong();
        if (zip64EndOffset < 0 || zip64EndOffset >= fileLength) {
            return null;
        }

        byte[] record = new byte[56];
        long tailStart = fileLength - tailLength;
        if (zip64EndOffset >= tailStart && zip64EndOffset + record.length <= fileLength) {
            System.arraycopy(tail, (int) (zip64EndOffset - tailStart), record, 0, record.length);
        } else {
            slice.readAt(zip64EndOffset, record, 0, record.length);
        }

        ByteBuffer rb = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
        if ((rb.getInt() & 0xffffffffL) != SIG_ZIP64_EOCD) {
            return null;
        }
        rb.position(32);
        long totalEntries = rb.getLong();
        long centralSize = rb.getLong();
        long centralOffset = rb.getLong();
        return new long[]{totalEntries, centralSize, centralOffset};
    }

    private static List<Entry> parseCentral(byte[] central, int expected) {
        List<Entry> out = new ArrayList<>(Math.min(expected > 0 ? expected : 32, 4096));
        ByteBuffer bb = ByteBuffer.wrap(central).order(ByteOrder.LITTLE_ENDIAN);
        while (bb.remaining() >= 46) {
            int start = bb.position();
            if ((bb.getInt() & 0xffffffffL) != SIG_CEN) {
                break;
            }
            bb.position(start + 10);
            int method = bb.getShort() & 0xffff;
            bb.position(start + 20);
            long compressedSize = bb.getInt() & 0xffffffffL;
            long size = bb.getInt() & 0xffffffffL;
            int nameLength = bb.getShort() & 0xffff;
            int extraLength = bb.getShort() & 0xffff;
            int commentLength = bb.getShort() & 0xffff;
            bb.position(start + 42);
            long localOffset = bb.getInt() & 0xffffffffL;

            byte[] nameBytes = new byte[nameLength];
            bb.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);

            byte[] extra = new byte[extraLength];
            if (extraLength > 0) {
                bb.get(extra);
            }
            if (compressedSize == ZIP64_MAGIC || size == ZIP64_MAGIC || localOffset == ZIP64_MAGIC) {
                long[] zip64 = readZip64Extra(extra, size == ZIP64_MAGIC, compressedSize == ZIP64_MAGIC,
                        localOffset == ZIP64_MAGIC);
                if (size == ZIP64_MAGIC) size = zip64[0];
                if (compressedSize == ZIP64_MAGIC) compressedSize = zip64[1];
                if (localOffset == ZIP64_MAGIC) localOffset = zip64[2];
            }
            bb.position(start + 46 + nameLength + extraLength + commentLength);

            if (name.endsWith("/")) {
                continue;
            }
            out.add(new Entry(name, method, compressedSize, size, localOffset));
        }
        return out;
    }

    /**
     * 解析 ZIP64 extra（id 0x0001）。
     *
     * <p>字段顺序固定：原始大小 → 压缩后大小 → local header 偏移 → 起始磁盘号。
     * 但只有「在基本字段里写成 0xFFFFFFFF」的那些才会出现，所以要按标记顺序依次消费。
     */
    private static long[] readZip64Extra(byte[] extra, boolean hasSize, boolean hasCompressed,
                                         boolean hasOffset) {
        long[] result = {-1L, -1L, -1L};
        if (extra == null || extra.length < 4) {
            return result;
        }
        ByteBuffer bb = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
        while (bb.remaining() >= 4) {
            int id = bb.getShort() & 0xffff;
            int len = bb.getShort() & 0xffff;
            if (bb.remaining() < len) {
                break;
            }
            int blockStart = bb.position();
            if (id == 0x0001) {
                int cursor = blockStart;
                if (hasSize && cursor + 8 <= blockStart + len) {
                    bb.position(cursor);
                    result[0] = bb.getLong();
                    cursor += 8;
                }
                if (hasCompressed && cursor + 8 <= blockStart + len) {
                    bb.position(cursor);
                    result[1] = bb.getLong();
                    cursor += 8;
                }
                if (hasOffset && cursor + 8 <= blockStart + len) {
                    bb.position(cursor);
                    result[2] = bb.getLong();
                }
                return result;
            }
            bb.position(blockStart + len);
        }
        return result;
    }

    Entry find(String name) {
        for (Entry e : entries) {
            if (e.name.equals(name) || e.name.endsWith("/" + name)) {
                return e;
            }
        }
        return null;
    }

    /** 算出条目数据起点：local header 长度不固定（文件名与 extra 都可能是变长）。 */
    long dataOffset(Entry entry) throws IOException {
        if (entry.dataOffset >= 0) {
            return entry.dataOffset;
        }
        byte[] header = new byte[30];
        slice.readAt(entry.localHeaderOffset, header, 0, header.length);
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if ((bb.getInt() & 0xffffffffL) != SIG_LOCAL) {
            throw new IOException("不是有效的 ZIP 条目：" + entry.name);
        }
        bb.position(26);
        int nameLength = bb.getShort() & 0xffff;
        int extraLength = bb.getShort() & 0xffff;
        entry.dataOffset = entry.localHeaderOffset + 30L + nameLength + extraLength;
        return entry.dataOffset;
    }

    /** 打开条目内容流；stored 直接切片，deflate 解压，其余（bzip2/lzma 等）不支持。 */
    InputStream openEntry(Entry entry) throws IOException {
        long start = dataOffset(entry);
        InputStream raw = slice.openRange(start, entry.compressedSize);
        if (entry.method == 0) {
            return raw;
        }
        if (entry.method == 8) {
            // zip 里的 deflate 是裸流，不带 zlib 头；Inflater(true) 才对
            return new InflaterInputStream(raw, new Inflater(true), 64 * 1024);
        }
        raw.close();
        throw new IOException("不支持的压缩方式 " + entry.method + "：" + entry.name);
    }

    @Override
    public void close() {
        try {
            slice.close();
        } catch (IOException ignored) {
        }
    }
}
