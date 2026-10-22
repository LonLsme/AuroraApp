package com.aurora.app.core.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link UpdateChecker} 里纯逻辑部分的单元测试（纯 JVM，不需要设备、不联网）。
 *
 * <p>为什么这两件事必须测：版本比较写错的表现是「明明有新版本却说已是最新」，
 * 或者反过来「永远提示有更新」—— 两种都不会崩、都不会报错，只有用户自己发现版本号没动。
 * 真机验证根本覆盖不到 {@code 1.10.0} 与 {@code 1.9.0} 这种「字符串比大小会翻车」的边角。
 */
public class UpdateCheckerTest {

    // ───────────────── 版本比较 ─────────────────

    /** 断言 {@code a} 比 {@code b} 旧。 */
    private static void assertOlder(String a, String b) {
        assertTrue(a + " 应该比 " + b + " 旧", UpdateChecker.compareVersions(a, b) < 0);
        assertTrue(b + " 应该比 " + a + " 新", UpdateChecker.compareVersions(b, a) > 0);
    }

    private static void assertSame(String a, String b) {
        assertEquals(a + " 应与 " + b + " 等价", 0, UpdateChecker.compareVersions(a, b));
    }

    @Test
    public void newerTagIsDetected() {
        assertOlder("1.1.0", "1.2.0");
        assertOlder("1.1.0", "v1.2.0");
    }

    @Test
    public void versionSegmentsCompareNumericallyNotLexically() {
        // 字符串比较会把 "10" 判成小于 "9"，这正是最常见的版本号 bug
        assertOlder("1.9.0", "1.10.0");
        assertOlder("1.9.9", "1.10.0");
        assertOlder("1.1.0", "1.1.10");
    }

    @Test
    public void missingSegmentsAreTreatedAsZero() {
        assertSame("1.2", "1.2.0");
        assertSame("1.2.0", "1.2.0.0");
        assertSame("v1.2.0", "1.2.0");
    }

    @Test
    public void identicalVersionsAreEqual() {
        assertSame("1.1.0", "1.1.0");
        assertSame("1.1.0", "V1.1.0");
    }

    @Test
    public void prereleaseIsOlderThanFinal() {
        // 语义化版本：1.2.0-beta 早于 1.2.0
        assertOlder("1.2.0-beta", "1.2.0");
        assertOlder("1.2.0-beta.1", "1.2.0");
    }

    @Test
    public void prereleaseOrdering() {
        assertOlder("1.2.0-beta.1", "1.2.0-beta.2");
        assertOlder("1.2.0-alpha", "1.2.0-beta");
    }

    @Test
    public void buildMetadataIsIgnored() {
        // 语义化版本规定 + 之后是构建元数据，不参与比较
        assertSame("1.2.0+build7", "1.2.0");
        assertSame("1.2.0+20261022", "1.2.0+build1");
    }

    @Test
    public void garbageDoesNotCrash() {
        // 版本号字段是构建期手写的，写错不能把检查更新弄崩
        assertEquals(0, UpdateChecker.compareVersions(null, null));
        assertEquals(0, UpdateChecker.compareVersions("", ""));
        assertTrue(UpdateChecker.compareVersions("abc", "1.0.0") < 0);
        assertTrue(UpdateChecker.compareVersions("1.0.0", "abc") > 0);
        assertTrue(UpdateChecker.compareVersions("1.0.0", "2.0.0") < 0);
    }

    @Test
    public void numericTagWithoutDotsWorks() {
        // 有人直接把 versionCode 当 tag，这种写法也要能比
        assertOlder("9", "10");
        assertOlder("1.1.0", "12");
    }

    // ───────────────── 规范化 ─────────────────

    @Test
    public void normalizeStripsPrefixAndMetadata() {
        assertEquals("1.2.0", UpdateChecker.normalizeVersion("v1.2.0"));
        assertEquals("1.2.0", UpdateChecker.normalizeVersion("V1.2.0"));
        assertEquals("1.2.0", UpdateChecker.normalizeVersion(" 1.2.0 "));
        assertEquals("1.2.0", UpdateChecker.normalizeVersion("1.2.0+meta"));
        assertEquals("1.2.0-beta", UpdateChecker.normalizeVersion("1.2.0-beta"));
        assertEquals("", UpdateChecker.normalizeVersion(null));
        assertEquals("", UpdateChecker.normalizeVersion("   "));
    }

    // ───────────────── 仓库配置 ─────────────────

    @Test
    public void repoFormatValidation() {
        assertTrue(UpdateChecker.isRepoValid("LonLsme/AuroraApp"));
        assertTrue(UpdateChecker.isRepoValid("a/b"));
        assertTrue(UpdateChecker.isRepoValid("  owner/repo  "));

        assertFalse(UpdateChecker.isRepoValid(null));
        assertFalse(UpdateChecker.isRepoValid(""));
        assertFalse(UpdateChecker.isRepoValid("   "));
        assertFalse(UpdateChecker.isRepoValid("no-slash"));
        assertFalse(UpdateChecker.isRepoValid("owner/"));
        assertFalse(UpdateChecker.isRepoValid("/repo"));
        // 多一个斜杠会拼出 /repos/a/b/c/releases/latest 这种必然 404 的地址
        assertFalse(UpdateChecker.isRepoValid("a/b/c"));
        assertFalse(UpdateChecker.isRepoValid("a b/c"));
    }

    // ───────────────── Release 解析（APK 资产与摘要） ─────────────────

    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** 拼一份 GitHub Releases API 的最小响应。 */
    private static String json(String body, String assets) {
        return "{\"tag_name\":\"v1.2.0\",\"name\":\"Aurora 1.2.0\","
                + "\"body\":" + quote(body) + ","
                + "\"html_url\":\"https://github.com/o/r/releases/tag/v1.2.0\","
                + "\"published_at\":\"2026-10-20T10:00:00Z\","
                + "\"assets\":[" + assets + "]}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    private static String apkAsset(String name, String digest) {
        return "{\"name\":\"" + name + "\",\"size\":28123392,"
                + (digest == null ? "" : "\"digest\":\"" + digest + "\",")
                + "\"browser_download_url\":\"https://example.com/" + name + "\"}";
    }

    @Test
    public void parseLatestReadsAssetDigest() {
        UpdateChecker.Release r = UpdateChecker.parseLatest(
                json("## 变更", apkAsset("Aurora-v1.2.0.apk", "sha256:" + SHA)));
        assertEquals("v1.2.0", r.tag);
        assertEquals("Aurora-v1.2.0.apk", r.apkName);
        assertEquals(28123392L, r.apkSize);
        assertEquals(SHA, r.apkSha256);
        assertEquals("https://example.com/Aurora-v1.2.0.apk", r.apkUrl);
        assertTrue(r.hasApk());
    }

    @Test
    public void parseLatestFallsBackToBodySha() {
        // 老资产的 digest 字段为空，摘要写在正文里（release.yml 一直是这么发的）
        UpdateChecker.Release r = UpdateChecker.parseLatest(
                json("## 安装包\n\n- SHA-256：`" + SHA + "`",
                        apkAsset("Aurora-v1.2.0.apk", null)));
        assertEquals(SHA, r.apkSha256);
    }

    @Test
    public void parseLatestPrefersDigestOverBody() {
        String other = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        UpdateChecker.Release r = UpdateChecker.parseLatest(
                json("- SHA-256：" + other, apkAsset("Aurora-v1.2.0.apk", "sha256:" + SHA)));
        // digest 由 GitHub 在收包时算出，比人手贴进正文的更可信
        assertEquals(SHA, r.apkSha256);
    }

    @Test
    public void parseLatestCollectsSha256SidecarAsset() {
        UpdateChecker.Release r = UpdateChecker.parseLatest(json("",
                apkAsset("Aurora-v1.2.0.apk", null) + ","
                        + apkAsset("Aurora-v1.2.0.apk.sha256", null)));
        assertEquals("", r.apkSha256);
        // 侧车文件本身不是摘要，它的地址要留给下载器按需拉取
        assertEquals("https://example.com/Aurora-v1.2.0.apk.sha256", r.apkShaUrl);
        assertEquals("https://example.com/Aurora-v1.2.0.apk", r.apkUrl);
    }

    @Test
    public void parseLatestWithoutApkAsset() {
        UpdateChecker.Release r = UpdateChecker.parseLatest(json("只有说明", ""));
        assertEquals("v1.2.0", r.tag);
        assertFalse(r.hasApk());
        assertEquals("", r.apkUrl);
        assertEquals("", r.apkSha256);
    }

    @Test
    public void parseLatestRejectsResponseWithoutTag() {
        // 没有 tag 就没法比版本，只能当解析失败；返回半个对象会让上层误判成「有更新」
        assertEquals(null, UpdateChecker.parseLatest("{\"name\":\"x\"}"));
        assertEquals(null, UpdateChecker.parseLatest(""));
        assertEquals(null, UpdateChecker.parseLatest(null));
        assertEquals(null, UpdateChecker.parseLatest("not json"));
    }

    @Test
    public void extractSha256FromBody() {
        assertEquals(SHA, UpdateChecker.extractSha256("SHA-256: " + SHA));
        assertEquals(SHA, UpdateChecker.extractSha256("sha256=" + SHA));
        assertEquals(SHA, UpdateChecker.extractSha256("SHA-256：" + SHA));
        assertEquals(SHA, UpdateChecker.extractSha256("SHA-256：`" + SHA + "`"));
        // markdown 加粗：标签与摘要之间夹 ** 与 :，也要能认
        assertEquals(SHA, UpdateChecker.extractSha256("**SHA-256**: " + SHA));
        assertEquals(SHA, UpdateChecker.extractSha256("## 安装包\n- SHA-256：`" + SHA + "`\n"));
        assertEquals("", UpdateChecker.extractSha256("没有摘要的说明"));
        // 裸十六进制串（没有 sha256 标签）不认：正文里可能是别的含义
        assertEquals("", UpdateChecker.extractSha256("`" + SHA + "`"));
        assertEquals("", UpdateChecker.extractSha256(""));
        assertEquals("", UpdateChecker.extractSha256(null));
    }
}
