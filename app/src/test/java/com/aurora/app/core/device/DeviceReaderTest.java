package com.aurora.app.core.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aurora.app.core.shell.AdbBridge;

import org.junit.Test;

import java.util.Map;

/**
 * 设备页两条读取链路的纯 JVM 单元测试。
 *
 * <p>为什么优先测这些：外接设备的全部字段都来自一段 {@code adb shell} 的文本输出，
 * 解析错了不会崩、只会把「8 核显示成 1 核」「A 槽位显示成 B 槽位」这种错悄悄摆到界面上，
 * 真机点一圈很难发现。这里把最容易错的三块（KEY=VALUE 切分、核心行解析、本机地址判定）
 * 全部钉死。
 */
public class DeviceReaderTest {

    // ───────────────── KEY=VALUE 解析 ─────────────────

    @Test
    public void parseKeyValues_readsEveryLine() {
        Map<String, String> kv = DeviceReader.parseKeyValues(
                "MAN=OPPO\nMODEL=PJX110\nSDK=36\n");
        assertEquals("OPPO", kv.get("MAN"));
        assertEquals("PJX110", kv.get("MODEL"));
        assertEquals("36", kv.get("SDK"));
    }

    @Test
    public void parseKeyValues_keepsFirstValueOnDuplicateKeys() {
        // 某些 getprop 会重复输出，先到的才是主值
        Map<String, String> kv = DeviceReader.parseKeyValues("REL=16\nREL=15\n");
        assertEquals("16", kv.get("REL"));
    }

    @Test
    public void parseKeyValues_ignoresNoiseLines() {
        // adb 会混进 "* daemon not running" 之类的行，没有 = 就必须丢掉
        Map<String, String> kv = DeviceReader.parseKeyValues(
                "* daemon not running; starting now\nList of devices attached\nKERNEL=6.1.118\n");
        assertEquals(1, kv.size());
        assertEquals("6.1.118", kv.get("KERNEL"));
    }

    @Test
    public void parseKeyValues_keepsEqualsInsideValue() {
        // 指纹里带 ":"，基带里带 ","；万一将来出现 "="，也只能切第一个
        Map<String, String> kv = DeviceReader.parseKeyValues("FP=a=b:c/d\n");
        assertEquals("a=b:c/d", kv.get("FP"));
    }

    @Test
    public void parseKeyValues_nullIsEmpty() {
        assertTrue(DeviceReader.parseKeyValues(null).isEmpty());
    }

    // ───────────────── 槽位文案 ─────────────────

    @Test
    public void bootSlot_mapsBothCaseAndUnderscore() {
        assertEquals("A 槽位", DeviceReader.bootSlotLabel("_a"));
        assertEquals("A 槽位", DeviceReader.bootSlotLabel("a"));
        assertEquals("B 槽位", DeviceReader.bootSlotLabel("_b"));
        assertEquals("B 槽位", DeviceReader.bootSlotLabel("B"));
    }

    @Test
    public void bootSlot_emptyMeansNoAbPartition() {
        assertEquals("未启用 A/B 分区", DeviceReader.bootSlotLabel(""));
        assertEquals("未启用 A/B 分区", DeviceReader.bootSlotLabel(null));
        assertEquals("未启用 A/B 分区", DeviceReader.bootSlotLabel("null"));
    }

    // ───────────────── 核心行解析 ─────────────────

    @Test
    public void parseCores_readsAllCores() {
        // 真实输出形如 CPU0=0::1800000:2841600:300000（cpu0 没有 online 节点）
        Map<String, String> kv = DeviceReader.parseKeyValues(
                "CPU0=0::1800000:2841600:300000\n"
                        + "CPU1=1:1:1200000:2841600:300000\n"
                        + "CPU2=2:0::2841600:300000\n");
        DeviceSnapshot.CpuCore[] cores = DeviceReader.parseCores(kv);

        assertEquals(3, cores.length);

        assertTrue("cpu0 无 online 节点应缺省在线", cores[0].online);
        assertEquals(1800000L, cores[0].curKhz);
        assertEquals(2841600L, cores[0].maxKhz);
        assertEquals(300000L, cores[0].minKhz);

        assertEquals(1200000L, cores[1].curKhz);

        assertFalse("online=0 的核心必须判离线", cores[2].online);
        assertEquals("离线核心不算频率占比", -1L, cores[2].curKhz);
    }

    @Test
    public void parseCores_stopsWhenNoCores() {
        assertEquals(0, DeviceReader.parseCores(DeviceReader.parseKeyValues("MAN=OPPO")).length);
    }

    @Test
    public void parseCores_skipsSingleGap() {
        // 掉了一个核心号不该把后面的整段丢掉
        Map<String, String> kv = DeviceReader.parseKeyValues(
                "CPU0=0::1800000:2841600:300000\nCPU2=2:1:1500000:2841600:300000\n");
        assertEquals(2, DeviceReader.parseCores(kv).length);
    }

    // ───────────────── 频率与占比 ─────────────────

    @Test
    public void cpuCore_loadPercentIsFrequencyRatio() {
        // 1.8GHz / 2.8416GHz ≈ 63%
        DeviceSnapshot.CpuCore core = new DeviceSnapshot.CpuCore(0, true, 1800000L, 2841600L, 300000L);
        assertEquals(63, core.loadPercent());
    }

    @Test
    public void cpuCore_loadPercentClampsAndHandlesMissingData() {
        assertEquals(100, new DeviceSnapshot.CpuCore(0, true, 3000000L, 2841600L, 300000L).loadPercent());
        assertEquals(0, new DeviceSnapshot.CpuCore(0, true, 0L, 2841600L, 300000L).loadPercent());
        assertEquals(0, new DeviceSnapshot.CpuCore(0, true, 1800000L, -1L, 300000L).loadPercent());
        assertEquals(0, new DeviceSnapshot.CpuCore(0, false, 1800000L, 2841600L, 300000L).loadPercent());
    }

    @Test
    public void cpuCore_rangeLabelMatchesLegacyFormat() {
        // 2841600kHz = 2841.6MHz，与 CpuSysFiles.formatCpuFreqRangeMhzShort 一样按四舍五入到整 MHz
        assertEquals("300~2842MHz",
                new DeviceSnapshot.CpuCore(0, true, 1800000L, 2841600L, 300000L).rangeLabel());
        assertEquals("—", new DeviceSnapshot.CpuCore(0, true, 1800000L, -1L, -1L).rangeLabel());
    }

    // ───────────────── 本机地址判定 ─────────────────

    @Test
    public void selfAddress_loopbackIsSelf() {
        assertTrue(AdbBridge.isSelfAddress("127.0.0.1:37231"));
        assertTrue(AdbBridge.isSelfAddress("[::1]:37231"));
        assertTrue(AdbBridge.isSelfAddress("localhost:37231"));
    }

    @Test
    public void selfAddress_foreignHostIsNotSelf() {
        // TEST-NET-3 段，绝不会是本机网卡地址
        assertFalse(AdbBridge.isSelfAddress("203.0.113.9:37231"));
        assertFalse(AdbBridge.isSelfAddress(null));
        assertFalse(AdbBridge.isSelfAddress(""));
    }

    @Test
    public void selfAddress_usbSerialIsNotSelf() {
        // USB 序列号不是 ip:port，解析出来的 host 是它自己，不可能撞上网卡地址
        assertFalse(AdbBridge.isSelfAddress("45850c2f"));
    }

    // ───────────────── 其它 ─────────────────

    @Test
    public void snapshotDefaultsAreEmptyRatherThanNull() {
        DeviceSnapshot snapshot = new DeviceSnapshot();
        assertEquals(0, snapshot.cores.length);
        assertNull(snapshot.title);
    }
}
