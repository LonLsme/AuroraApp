package com.aurora.app.core.flash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FlashCommands} 的单元测试（纯 JVM，不需要设备）。
 *
 * <p>为什么优先测这一类：这些方法是<b>纯字符串拼装</b>，却直接决定 root shell 里跑什么命令。
 * 一个引号漏掉就是任意命令执行，而真机验证覆盖不到「用户路径里带单引号」这种边角。
 *
 * <p>约定：断言里出现 {@code '\\''} 是 shell 单引号转义的固定写法（关引号 + 转义单引号 + 重开引号），
 * 不是笔误。
 */
public class FlashCommandsTest {

    // ───────────────── 转义 ─────────────────

    @Test
    public void shellEscape_wrapsPlainText() {
        assertEquals("'boot'", FlashCommands.esc("boot"));
    }

    @Test
    public void shellEscape_escapesSingleQuote() {
        // it's -> 'it'\''s'
        assertEquals("'it'\\''s'", FlashCommands.esc("it's"));
    }

    @Test
    public void shellEscape_keepsSpacesInsideQuotes() {
        assertEquals("'/sdcard/My ROM/boot.img'",
                FlashCommands.esc("/sdcard/My ROM/boot.img"));
    }

    @Test
    public void shellEscape_nullBecomesEmptyQuotes() {
        // null 不能拼出裸空串，否则命令会少一个参数
        assertEquals("''", FlashCommands.esc(null));
    }

    // ───────────────── 分区名校验 ─────────────────

    @Test
    public void partitionName_acceptsRealNames() {
        assertTrue(FlashCommands.isValidPartitionName("boot"));
        assertTrue(FlashCommands.isValidPartitionName("init_boot"));
        assertTrue(FlashCommands.isValidPartitionName("system_ext"));
        assertTrue(FlashCommands.isValidPartitionName("vendor_dlkm"));
        assertTrue(FlashCommands.isValidPartitionName("vbmeta_system_a"));
    }

    @Test
    public void partitionName_rejectsPathAndSpaces() {
        // 最常见的误操作：把镜像路径当分区名粘进来
        assertFalse(FlashCommands.isValidPartitionName("/sdcard/boot.img"));
        assertFalse(FlashCommands.isValidPartitionName("boot img"));
        assertFalse(FlashCommands.isValidPartitionName("boot;reboot"));
        assertFalse(FlashCommands.isValidPartitionName(""));
        assertFalse(FlashCommands.isValidPartitionName("   "));
        assertFalse(FlashCommands.isValidPartitionName(null));
    }

    @Test
    public void partitionName_rejectsOverlongInput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            sb.append('a');
        }
        assertFalse(FlashCommands.isValidPartitionName(sb.toString()));
    }

    // ───────────────── Fastboot 命令 ─────────────────

    @Test
    public void fastbootFlash_quotesBothArguments() {
        assertEquals("fastboot flash 'boot' '/tmp/boot.img'",
                FlashCommands.fastbootFlash("boot", "/tmp/boot.img"));
    }

    @Test
    public void fastbootFlash_neutralisesInjectionInPath() {
        // 路径里的单引号被转义后，后面的 ; rm -rf 只是普通字符，不再是命令分隔符
        String cmd = FlashCommands.fastbootFlash("boot", "/tmp/a'; rm -rf /sdcard; '.img");
        assertEquals("fastboot flash 'boot' '/tmp/a'\\''; rm -rf /sdcard; '\\''.img'", cmd);
        // 分号仍出现在字符串里，但它被包在单引号内部
        assertTrue(cmd.contains("'/tmp/a'\\''"));
    }

    @Test
    public void fastbootFlashBothSlots_writesBothSlotSuffixes() {
        assertEquals(
                "fastboot flash 'init_boot_a' '/tmp/i.img' && fastboot flash 'init_boot_b' '/tmp/i.img'",
                FlashCommands.fastbootFlashBothSlots("init_boot", "/tmp/i.img"));
    }

    @Test
    public void fastbootVbmetaNoVerify_keepsDisableFlagsBeforeFlash() {
        assertEquals(
                "fastboot --disable-verity --disable-verification flash vbmeta '/tmp/vb.img'",
                FlashCommands.fastbootVbmetaNoVerify("/tmp/vb.img"));
    }

    @Test
    public void adbSideload_quotesZipPath() {
        assertEquals("adb sideload '/sdcard/update.zip'",
                FlashCommands.adbSideload("/sdcard/update.zip"));
    }

    // ───────────────── 自定义命令拼装 ─────────────────
    //
    // 这一组是「执行命令」页的核心：输入本身即命令行，所以刻意不整串转义。
    // 断言的重点是「不叠加第二个 -s」与「整条粘贴原样放行」。

    @Test
    public void customAdb_prependsToolAndSerial() {
        assertEquals("adb -s '45850c2f' shell pm list packages",
                FlashCommands.customAdb("45850c2f", "shell pm list packages"));
    }

    @Test
    public void customAdb_omitsSerialFlagWhenUnknown() {
        // 序列号为空时不带 -s：单设备场景下 adb 自己会选，硬编码空 -s 反而报错
        assertEquals("adb getvar all",
                FlashCommands.customAdb("", "getvar all"));
    }

    @Test
    public void customAdb_passesWholeCommandThrough() {
        // 用户整条粘贴时不能再叠一个 -s，否则 adb 只会回 unknown command。
        // 注意这里是「原样返回」，连单引号都不加 —— 整条命令交给用户自己负责。
        assertEquals("adb -s AAA shell ls",
                FlashCommands.customAdb("45850c2f", "adb -s AAA shell ls"));
    }

    @Test
    public void customAdb_keepsPipesAndRedirects() {
        String cmd = FlashCommands.customAdb("45850c2f", "shell pm list packages | grep foo");
        assertTrue(cmd.contains("| grep foo"));
    }

    @Test
    public void customAdb_similarToolNameIsNotStripped() {
        // adbsideload 不是 adb 子命令，不能被当成前缀剥掉
        assertEquals("adb -s 'S' adbsideload",
                FlashCommands.customAdb("S", "adbsideload"));
    }

    @Test
    public void customFastboot_prependsToolAndSerial() {
        assertEquals("fastboot -s 'S' getvar product",
                FlashCommands.customFastboot("S", "getvar product"));
    }

    @Test
    public void customShell_injectsShellKeyword() {
        assertEquals("adb -s 'S' shell pm list packages -3",
                FlashCommands.customShell("S", "pm list packages -3"));
    }

    @Test
    public void customShell_doesNotDoubleShellKeyword() {
        // 只写 `shell …`：补 adb + 序列号，但不再补第二个 shell
        assertEquals("adb -s 'S' shell ls -l",
                FlashCommands.customShell("S", "shell ls -l"));
        // 整条 `adb …` 粘进来：原样返回，连序列号都不动（用户自己写了他要的 -s）
        assertEquals("adb shell ls -l",
                FlashCommands.customShell("S", "adb shell ls -l"));
    }

    @Test
    public void customShell_passesFastbootThroughUntouched() {
        // Shell 通道里粘了 fastboot 命令：原样放行，由用户自己承担（确认框会显示原文）
        assertEquals("fastboot reboot", FlashCommands.customShell("S", "fastboot reboot"));
    }

    @Test
    public void customCommand_emptyInputDoesNotCrash() {
        assertEquals("adb ", FlashCommands.customAdb("", ""));
        assertEquals("fastboot ", FlashCommands.customFastboot("", null));
        assertEquals("adb shell ", FlashCommands.customShell("", ""));
    }
}
