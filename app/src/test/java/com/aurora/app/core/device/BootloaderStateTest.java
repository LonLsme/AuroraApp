package com.aurora.app.core.device;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Map;

/**
 * BL（引导加载器）解锁状态的判定。
 *
 * <h3>为什么这个值得单独测</h3>
 * 三个属性在不同 ROM 上有的写有的不写，判定又是「优先级 + 回退」的链式结构，
 * 写在代码里看一遍很难确认边界。这条链一旦判反，页面上就会把解锁的机器显示成
 * 「已锁定」—— 用户会据此以为刷机失败。所以把每种组合都钉一遍。
 *
 * <p>{@link BootloaderState#of} 与 {@link DeviceReader#applyFields} 都是纯函数，
 * 不需要真机、不需要 Context。
 */
public class BootloaderStateTest {

    // ───────────────── 单属性判定 ─────────────────

    @Test
    public void verifiedBootState_orangeIsUnlocked() {
        // AVB：orange = 验证被关闭，等于 bootloader 已解锁
        assertEquals(BootloaderState.UNLOCKED,
                BootloaderState.of("orange", null, null));
    }

    @Test
    public void verifiedBootState_greenIsLocked() {
        assertEquals(BootloaderState.LOCKED,
                BootloaderState.of("green", null, null));
    }

    @Test
    public void verifiedBootState_yellowIsLockedWithCustomKey() {
        assertEquals(BootloaderState.LOCKED_SELF_SIGNED,
                BootloaderState.of("yellow", null, null));
    }

    @Test
    public void verifiedBootState_redIsVerifyFailed() {
        assertEquals(BootloaderState.VERIFY_FAILED,
                BootloaderState.of("red", null, null));
    }

    @Test
    public void flashLocked_isUsedWhenVerifiedBootStateMissing() {
        assertEquals(BootloaderState.UNLOCKED, BootloaderState.of(null, "0", null));
        assertEquals(BootloaderState.LOCKED, BootloaderState.of(null, "1", null));
    }

    @Test
    public void vbmetaDeviceState_isLastFallback() {
        assertEquals(BootloaderState.UNLOCKED, BootloaderState.of(null, null, "unlocked"));
        assertEquals(BootloaderState.LOCKED, BootloaderState.of(null, null, "locked"));
    }

    @Test
    public void allMissing_isUnknown() {
        assertEquals(BootloaderState.UNKNOWN, BootloaderState.of(null, null, null));
        assertEquals(BootloaderState.UNKNOWN, BootloaderState.of("", "", ""));
    }

    // ───────────────── 优先级 ─────────────────

    @Test
    public void verifiedBootState_winsOverFlashLocked() {
        // 真机上见过这种组合：unlock 之后 flash.locked 仍是 1，只有 verified 是准的。
        // 若判反，解锁的机器会显示「已锁定」。
        assertEquals(BootloaderState.UNLOCKED, BootloaderState.of("orange", "1", "locked"));
        assertEquals(BootloaderState.LOCKED, BootloaderState.of("green", "0", "unlocked"));
    }

    @Test
    public void unknownVerifiedBootState_fallsThroughToFlashLocked() {
        // 个别 ROM 会写一个不认识的值（比如空串或厂商自定义），此时必须继续往下看
        assertEquals(BootloaderState.UNLOCKED, BootloaderState.of("weird", "0", null));
    }

    // ───────────────── 输入清洗 ─────────────────

    @Test
    public void values_areTrimmedAndLowercased() {
        // getprop 输出带换行，shell 合并到一行时还可能留空格
        assertEquals(BootloaderState.UNLOCKED, BootloaderState.of(" Orange\n", " 0 ", null));
        assertEquals(BootloaderState.LOCKED, BootloaderState.of("GREEN", null, null));
        assertEquals(BootloaderState.UNLOCKED, BootloaderState.of(null, null, " UnLocked "));
    }

    // ───────────────── 外接设备整条解析 ─────────────────

    @Test
    public void remoteSnapshot_picksUpBootloaderFromKeyValueDump() {
        Map<String, String> kv = DeviceReader.parseKeyValues(
                "MAN=OPPO\nMODEL=PJX110\nVB=orange\nFL=1\nVBM=unlocked\nCPU0=0:1:1800000:2841600:300000\n");
        DeviceSnapshot snapshot = new DeviceSnapshot();
        DeviceReader.applyFields(snapshot, kv);
        assertEquals(BootloaderState.UNLOCKED, snapshot.bootloader);
        assertEquals("OPPO PJX110", snapshot.title);
    }

    @Test
    public void remoteSnapshot_missingBootloaderKeysStayUnknown() {
        // 老设备（Android 7 以下）根本没有 ro.boot.verifiedbootstate，整段空着也不能崩
        Map<String, String> kv = DeviceReader.parseKeyValues("MAN=OPPO\nMODEL=PJX110\n");
        DeviceSnapshot snapshot = new DeviceSnapshot();
        DeviceReader.applyFields(snapshot, kv);
        assertEquals(BootloaderState.UNKNOWN, snapshot.bootloader);
    }

    // ───────────────── fastboot 模式（系统没启动）─────────────────

    @Test
    public void fastboot_unlockedVariableWins() {
        // 最常见的一家：unlocked:yes
        assertEquals(BootloaderState.UNLOCKED, BootloaderState.fromFastboot("yes", null, null));
        assertEquals(BootloaderState.LOCKED, BootloaderState.fromFastboot("no", null, null));
    }

    @Test
    public void fastboot_deviceStateIsSecondChoice() {
        assertEquals(BootloaderState.UNLOCKED, BootloaderState.fromFastboot(null, "unlocked", null));
        assertEquals(BootloaderState.LOCKED, BootloaderState.fromFastboot(null, "locked", null));
    }

    @Test
    public void fastboot_unlockedVariableBeatsDeviceState() {
        // 两个都有时以 unlocked 为准：device-state 反映的是 vbmeta，与 fastboot 的锁不一致
        assertEquals(BootloaderState.UNLOCKED, BootloaderState.fromFastboot("yes", "locked", null));
        assertEquals(BootloaderState.LOCKED, BootloaderState.fromFastboot("no", "unlocked", null));
    }

    @Test
    public void fastboot_secureIsOnlyLastResort() {
        // secure=no 意味着验证被关（等于已解锁），但它是兜底，不能盖过前两个
        assertEquals(BootloaderState.UNLOCKED, BootloaderState.fromFastboot(null, null, "no"));
        assertEquals(BootloaderState.LOCKED, BootloaderState.fromFastboot("no", null, "yes"));
        // secure=yes 单独出现不足以判锁定 —— 有些机型 secure 与解锁状态并不同步
        assertEquals(BootloaderState.UNKNOWN, BootloaderState.fromFastboot(null, null, "yes"));
    }

    @Test
    public void fastboot_nothingReadableStaysUnknown() {
        assertEquals(BootloaderState.UNKNOWN, BootloaderState.fromFastboot(null, null, null));
        assertEquals(BootloaderState.UNKNOWN, BootloaderState.fromFastboot("", "  ", "garbage"));
    }

    @Test
    public void fastboot_applyVarsFillsOnlyFastbootFields() {
        DeviceSnapshot snapshot = new DeviceSnapshot();
        DeviceReader.applyFastbootVars(snapshot, DeviceReader.parseFastbootVars(
                "(bootloader) product:violet\n"
                        + "(bootloader) variant:sm6150\n"
                        + "(bootloader) unlocked:yes\n"
                        + "(bootloader) current-slot:b\n"
                        + "Finished. Total time: 0.050s\n"));
        // transport 由 readFastbootStatic 设，这里只管字段；ADB 是默认值
        assertEquals(DeviceSnapshot.Transport.ADB, snapshot.transport);
        assertEquals("violet · sm6150", snapshot.title);
        assertEquals("violet", snapshot.deviceCode);
        assertEquals(BootloaderState.UNLOCKED, snapshot.bootloader);
        assertEquals("B 槽位", snapshot.bootSlot);
        // 系统没启动的那几项必须留空，页面才会显示「—」
        assertEquals(null, snapshot.android);
        assertEquals(null, snapshot.kernel);
        assertEquals(null, snapshot.fingerprint);
        assertEquals(null, snapshot.selinux);
    }
}
