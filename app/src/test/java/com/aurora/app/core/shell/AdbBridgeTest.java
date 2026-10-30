package com.aurora.app.core.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * {@code adb devices} 输出的解析测试。
 *
 * <p>为什么要单独测这段：外接设备列表完全由这段文本决定，解析漏一行不会崩、只会让
 * 「插了线却没出现」这类问题看起来像功能没做。三种输入最容易出错 ——
 * 表头、adb daemon 的启动噪声、以及 server/client 版本不一致时打进来的告警
 * （告警那行同样有好几段，靠「第二段是不是合法状态词」才能剔掉）。
 */
public class AdbBridgeTest {

    @Test
    public void parseDevices_ignoresHeaderAndDaemonNoise() {
        String out = "List of devices attached\n"
                + "* daemon not running; starting now at tcp:5037\n"
                + "* daemon started successfully\n"
                + "\n"
                + "45850c2f\tdevice\n";
        List<AdbBridge.Device> devices = AdbBridge.parseDevices(out);
        assertEquals(1, devices.size());
        assertEquals("45850c2f", devices.get(0).serial);
        assertEquals("device", devices.get(0).state);
        assertTrue(devices.get(0).online());
    }

    @Test
    public void parseDevices_dropsVersionMismatchBanner() {
        // 这一行有两段以上，只有靠状态词白名单才能剔掉
        String out = "adb server version (41) doesn't match this client (39); killing...\n"
                + "45850c2f\tdevice\n";
        List<AdbBridge.Device> devices = AdbBridge.parseDevices(out);
        assertEquals(1, devices.size());
        assertEquals("45850c2f", devices.get(0).serial);
    }

    @Test
    public void parseDevices_keepsPendingStates() {
        String out = "List of devices attached\n"
                + "45850c2f\tunauthorized\n"
                + "emulator-5554\toffline\n"
                + "192.168.1.23:37105\tdevice\n";
        List<AdbBridge.Device> devices = AdbBridge.parseDevices(out);
        assertEquals(3, devices.size());
        assertFalse(devices.get(0).online());
        assertEquals("unauthorized", devices.get(0).state);
        assertEquals("offline", devices.get(1).state);
        assertTrue(devices.get(2).online());
    }

    @Test
    public void parseDevices_handlesEmptyAndNull() {
        assertTrue(AdbBridge.parseDevices("").isEmpty());
        assertTrue(AdbBridge.parseDevices(null).isEmpty());
        assertTrue(AdbBridge.parseDevices("List of devices attached\n").isEmpty());
    }

    @Test
    public void parseDevices_lowercasesState() {
        // 有些 adb 版本把状态写成大写，统一小写后再比，避免 online() 判错
        List<AdbBridge.Device> devices = AdbBridge.parseDevices("45850c2f\tDEVICE\n");
        assertEquals(1, devices.size());
        assertEquals("device", devices.get(0).state);
        assertTrue(devices.get(0).online());
    }

    @Test
    public void device_usbFlagLooksAtColonInSerial() {
        // USB 报设备自身序列号；网络连接一定带 ip:端口
        assertTrue(new AdbBridge.Device("45850c2f", "device").usb());
        assertFalse(new AdbBridge.Device("192.168.1.23:37105", "device").usb());
        // IPv6 的写法带方括号，同样含冒号
        assertFalse(new AdbBridge.Device("[::1]:5555", "device").usb());
    }

    @Test
    public void snapshot_emptyOnlyWhenBothBucketsEmpty() {
        AdbBridge.Snapshot snapshot = new AdbBridge.Snapshot();
        assertTrue(snapshot.isEmpty());
        // 只有「未授权」也算不空：用户需要在下拉里看到那一项，才知道去对方点允许
        snapshot.pending.add(new AdbBridge.Device("45850c2f", "unauthorized"));
        assertFalse(snapshot.isEmpty());
    }
}
