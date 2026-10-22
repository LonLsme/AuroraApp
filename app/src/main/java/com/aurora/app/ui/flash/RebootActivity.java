package com.aurora.app.ui.flash;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;

import com.aurora.app.R;
import com.aurora.app.core.flash.FlashCommands;

/**
 * 「设备重启」页。
 *
 * <h3>两组按钮与命令的对应关系</h3>
 * 完全按参考实现（ChenAxic {@code le1.java}）的两个数组摆位，不做增删：
 * <pre>
 *   ADB 重启    系统     → adb reboot
 *               Recovery → adb reboot recovery
 *               Fastboot → adb reboot bootloader
 *               FastbootD→ adb reboot fastboot
 *               9008     → adb reboot edl
 *   Fastboot   系统     → fastboot reboot
 *               Fastboot → fastboot reboot-bootloader
 *               Recovery → fastboot reboot recovery
 *               FastbootD→ fastboot reboot fastboot
 * </pre>
 * 两个数组的项数不一样（5 比 4）—— 因为 {@code adb reboot edl} 没有 fastboot 对应物，
 * 所以「9008」只在 ADB 那一组里出现一次。
 *
 * <p>本页<b>不替用户判断</b>该走哪条通道：设备在 adb 下就点 ADB 组，
 * 在 fastboot 下就点 Fastboot 组（右上角那行实时状态就是给人看这个的）。
 * 自动判断反而会在「设备刚重启、两种都没就绪」时给出误导性的命令。
 */
public final class RebootActivity extends FlashConsoleActivity {

    private View btnAdbSystem;
    private View btnAdbRecovery;
    private View btnAdbFastboot;
    private View btnAdbFastbootd;
    private View btnAdbEdl;
    private View btnFbSystem;
    private View btnFbFastboot;
    private View btnFbRecovery;
    private View btnFbFastbootd;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reboot);

        btnAdbSystem = findViewById(R.id.btnRebootAdbSystem);
        btnAdbRecovery = findViewById(R.id.btnRebootAdbRecovery);
        btnAdbFastboot = findViewById(R.id.btnRebootAdbFastboot);
        btnAdbFastbootd = findViewById(R.id.btnRebootAdbFastbootd);
        btnAdbEdl = findViewById(R.id.btnRebootAdbEdl);
        btnFbSystem = findViewById(R.id.btnRebootFbSystem);
        btnFbFastboot = findViewById(R.id.btnRebootFbFastboot);
        btnFbRecovery = findViewById(R.id.btnRebootFbRecovery);
        btnFbFastbootd = findViewById(R.id.btnRebootFbFastbootd);

        bindTarget(btnAdbSystem, FlashCommands.RebootTarget.SYSTEM, true);
        bindTarget(btnAdbRecovery, FlashCommands.RebootTarget.RECOVERY, true);
        bindTarget(btnAdbFastboot, FlashCommands.RebootTarget.BOOTLOADER, true);
        bindTarget(btnAdbFastbootd, FlashCommands.RebootTarget.FASTBOOTD, true);
        bindTarget(btnAdbEdl, FlashCommands.RebootTarget.EDL, true);

        bindTarget(btnFbSystem, FlashCommands.RebootTarget.SYSTEM, false);
        bindTarget(btnFbFastboot, FlashCommands.RebootTarget.BOOTLOADER, false);
        bindTarget(btnFbRecovery, FlashCommands.RebootTarget.RECOVERY, false);
        bindTarget(btnFbFastbootd, FlashCommands.RebootTarget.FASTBOOTD, false);

        bindConsole();
    }

    private void bindTarget(View view, FlashCommands.RebootTarget target, boolean viaAdb) {
        if (view == null) {
            return;
        }
        view.setOnClickListener(v -> requestReboot(target, viaAdb));
    }

    private void requestReboot(FlashCommands.RebootTarget target, boolean viaAdb) {
        final String command = viaAdb
                ? FlashCommands.adbReboot(target)
                : FlashCommands.fastbootReboot(target);
        if (command == null) {
            // 只有 9008 会走到这里（它没有 fastboot 对应命令）。
            toast("该模式只能从 ADB 触发");
            return;
        }
        confirmThenRun("确认重启到" + target.label,
                "命令：\n" + command + "\n\n设备会立即重启，当前页面的输出会中断。",
                "重启",
                command);
    }

    @Override
    protected View[] busyViews() {
        return new View[]{btnAdbSystem, btnAdbRecovery, btnAdbFastboot, btnAdbFastbootd,
                btnAdbEdl, btnFbSystem, btnFbFastboot, btnFbRecovery, btnFbFastbootd};
    }

    @Override
    protected String logFileTag() {
        return "reboot";
    }
}
