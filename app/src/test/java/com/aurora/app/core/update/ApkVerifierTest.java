package com.aurora.app.core.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link ApkVerifier} 纯函数部分的单元测试（纯 JVM，不需要设备、不联网）。
 *
 * <p>校验逻辑写错的表现很隐蔽：摘要算错 → 每个包都被判成「已损坏」，
 * 用户看到的是「更新永远失败」，而不是任何异常堆栈。所以这里用
 * <b>公开的测试向量</b>锁死算法输出，而不是「跑一遍看它不报错」。
 */
public class ApkVerifierTest {

    /** SHA-256("abc") 的标准向量，见 FIPS 180-4 示例。 */
    private static final String SHA_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    /** 空文件的 SHA-256，也是全项目最容易被写错的一例。 */
    private static final String SHA_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File write(String name, byte[] data) throws Exception {
        File f = tmp.newFile(name);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f;
    }

    // ───────────────── 摘要计算 ─────────────────

    @Test
    public void sha256MatchesKnownVector() throws Exception {
        File f = write("abc.bin", "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(SHA_ABC, ApkVerifier.sha256(f));
    }

    @Test
    public void sha256OfEmptyFile() throws Exception {
        File f = write("empty.bin", new byte[0]);
        assertEquals(SHA_EMPTY, ApkVerifier.sha256(f));
    }

    @Test
    public void sha256StreamsLargeFile() throws Exception {
        // 5 MB：远超单次缓冲区，确认是分块读而不是 readAllBytes
        byte[] block = new byte[1024 * 1024];
        for (int i = 0; i < block.length; i++) {
            block[i] = (byte) (i * 31 + 7);
        }
        File f = tmp.newFile("big.bin");
        try (OutputStream out = new FileOutputStream(f)) {
            for (int i = 0; i < 5; i++) {
                out.write(block);
            }
        }
        assertEquals(5L * 1024 * 1024, f.length());
        String sha = ApkVerifier.sha256(f);
        assertEquals(64, sha.length());
        assertTrue(ApkVerifier.isSha256(sha));
        // 同样的内容再算一遍必须一致（排除缓冲区复用导致的错位）
        assertEquals(sha, ApkVerifier.sha256(f));
    }

    @Test
    public void sha256IsLowerCaseHex() throws Exception {
        File f = write("case.bin", "aurora".getBytes(StandardCharsets.UTF_8));
        String sha = ApkVerifier.sha256(f);
        assertEquals(sha.toLowerCase(java.util.Locale.ROOT), sha);
    }

    // ───────────────── 规范化 ─────────────────

    @Test
    public void normalizeAcceptsGitHubDigestForm() {
        // GitHub API 的 asset.digest 就是这个形状
        assertEquals(SHA_ABC, ApkVerifier.normalizeSha("sha256:" + SHA_ABC));
    }

    @Test
    public void normalizeAcceptsBodyForms() {
        assertEquals(SHA_ABC, ApkVerifier.normalizeSha(SHA_ABC));
        assertEquals(SHA_ABC, ApkVerifier.normalizeSha("  " + SHA_ABC + "  "));
        assertEquals(SHA_ABC, ApkVerifier.normalizeSha("SHA-256：" + SHA_ABC));
        assertEquals(SHA_ABC, ApkVerifier.normalizeSha("SHA256: " + SHA_ABC));
        assertEquals(SHA_ABC, ApkVerifier.normalizeSha("sha-256=`" + SHA_ABC + "`"));
        // .sha256 文件常见的「摘要 + 两个空格 + 文件名」写法
        assertEquals(SHA_ABC, ApkVerifier.normalizeSha(SHA_ABC + "  Aurora-v1.2.0.apk"));
    }

    @Test
    public void normalizeUppercaseIsAccepted() {
        assertEquals(SHA_ABC, ApkVerifier.normalizeSha(SHA_ABC.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    public void normalizeRejectsGarbage() {
        assertEquals("", ApkVerifier.normalizeSha(null));
        assertEquals("", ApkVerifier.normalizeSha(""));
        assertEquals("", ApkVerifier.normalizeSha("   "));
        // 长度不对
        assertEquals("", ApkVerifier.normalizeSha("abc123"));
        // 长度对但不是十六进制
        assertEquals("", ApkVerifier.normalizeSha(
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"));
        // 65 位：多一位也不能放过，否则 constantTimeEquals 永远不等
        assertEquals("", ApkVerifier.normalizeSha(SHA_ABC + "0"));
    }

    @Test
    public void isSha256RequiresLowerCase() {
        assertTrue(ApkVerifier.isSha256(SHA_ABC));
        assertFalse(ApkVerifier.isSha256(SHA_ABC.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(ApkVerifier.isSha256(null));
        assertFalse(ApkVerifier.isSha256(""));
    }

    // ───────────────── 比较 ─────────────────

    @Test
    public void constantTimeEqualsBehavesLikeEquals() {
        assertTrue(ApkVerifier.constantTimeEquals(SHA_ABC, SHA_ABC));
        assertFalse(ApkVerifier.constantTimeEquals(SHA_ABC, SHA_EMPTY));
        assertFalse(ApkVerifier.constantTimeEquals(null, null));
        assertFalse(ApkVerifier.constantTimeEquals(SHA_ABC, null));
        // 长度不同必须直接 false，不能走进逐字符循环
        assertFalse(ApkVerifier.constantTimeEquals(SHA_ABC, SHA_ABC.substring(1)));
    }
}
