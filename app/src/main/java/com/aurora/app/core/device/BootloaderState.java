package com.aurora.app.core.device;

import java.util.Locale;

/**
 * 引导加载器（Bootloader）的解锁状态。
 *
 * <h3>为什么不用单一属性判断</h3>
 * 三个候选属性各有一批 ROM 读不到，而且优先级并不相同：
 * <table border="1">
 *   <tr><th>属性</th><th>取值</th><th>可信度</th></tr>
 *   <tr><td>{@code ro.boot.verifiedbootstate}</td>
 *       <td>{@code green} / {@code yellow} / {@code orange} / {@code red}</td>
 *       <td>最高。AVB 的原始状态，橙色即「已验证失效 + 已解锁」</td></tr>
 *   <tr><td>{@code ro.boot.flash.locked}</td>
 *       <td>{@code 1} 锁 / {@code 0} 解</td>
 *       <td>次之。部分机型根本不写这个属性（空串）</td></tr>
 *   <tr><td>{@code ro.boot.vbmeta.device_state}</td>
 *       <td>{@code locked} / {@code unlocked}</td>
 *       <td>再次。只反映 vbmeta 分区，与 fastboot 的锁不完全一致</td></tr>
 * </table>
 * 所以这里按表格顺序逐级回退：前一个给出确定结论就不再往后看。
 *
 * <h3>为什么 {@code orange} 直接算解锁</h3>
 * AVB 的四种状态是互斥的：{@code green} 官方密钥验证通过且锁定、{@code yellow}
 * 自定义密钥验证通过（仍锁定，常见于自签名 ROM）、{@code orange} 验证被关闭
 * （等于 bootloader 已解锁）、{@code red} 验证失败。判断解锁看 {@code orange}
 * 比读 {@code flash.locked} 更准 —— 后者在解锁状态被 Magisk 之类改写的机型上
 * 并不总跟着变。
 *
 * <h3>只读不判「有没有风险」</h3>
 * 解锁本身是中性事实（玩机的人本来就要解锁），所以这里不出现「不安全」字样，
 * 颜色语义交给 UI：解锁用橙色（与 SELinux 宽容一致），锁定用绿色。
 */
public enum BootloaderState {

    /** 已解锁 —— {@code verifiedbootstate=orange} 或 {@code flash.locked=0}。 */
    UNLOCKED("已解锁"),
    /** 已锁定，官方密钥验证通过。 */
    LOCKED("已锁定"),
    /** 已锁定，但用的是自定义签名密钥（自编译 / 自签名 ROM）。 */
    LOCKED_SELF_SIGNED("已锁定 · 自签名"),
    /** AVB 验证失败（{@code red}），多见于改过 boot 分区又没关验证。 */
    VERIFY_FAILED("验证失败"),
    /** 三个属性都读不到，或值不认识。 */
    UNKNOWN("—");

    /** 表格里直接显示的字。 */
    public final String label;

    BootloaderState(String label) {
        this.label = label;
    }

    /**
     * 按优先级把三个属性原始值归成一个状态。
     *
     * <p>参数都允许 {@code null}（属性不存在时 {@code getprop} 输出空行）。
     */
    public static BootloaderState of(String verifiedBootState, String flashLocked, String vbmetaDeviceState) {
        String verified = normalize(verifiedBootState);
        switch (verified) {
            case "orange":
                return UNLOCKED;
            case "green":
                return LOCKED;
            case "yellow":
                return LOCKED_SELF_SIGNED;
            case "red":
                return VERIFY_FAILED;
            default:
                break;
        }

        String locked = normalize(flashLocked);
        if ("0".equals(locked)) {
            return UNLOCKED;
        }
        if ("1".equals(locked)) {
            return LOCKED;
        }

        String vbmeta = normalize(vbmetaDeviceState);
        if ("unlocked".equals(vbmeta)) {
            return UNLOCKED;
        }
        if ("locked".equals(vbmeta)) {
            return LOCKED;
        }
        return UNKNOWN;
    }

    /**
     * 从 {@code fastboot getvar all} 的几个变量判解锁状态。
     *
     * <p>设备在 <b>fastboot 模式</b>时 Android 没启动，{@code ro.boot.*} 全读不到，
     * 只能问 bootloader 自己。三个变量的覆盖情况按厂商分布：
     * <table border="1">
     *   <tr><th>变量</th><th>取值</th><th>谁有</th></tr>
     *   <tr><td>{@code unlocked}</td><td>{@code yes} / {@code no}</td>
     *       <td>最直接。高通/联发科公版、多数国产机</td></tr>
     *   <tr><td>{@code device-state}</td><td>{@code unlocked} / {@code locked}</td>
     *       <td>Google 系与部分 A/B 机型</td></tr>
     *   <tr><td>{@code secure}</td><td>{@code yes} / {@code no}</td>
     *       <td>兜底。{@code no} 通常意味着验证已关（等于已解锁），但语义不如前两个硬</td></tr>
     * </table>
     *
     * <p>顺序同理：前一个给出确定结论就不再往后看。三点都读不到返回 {@link #UNKNOWN}，
     * 页面显示「—」—— 不猜。
     */
    public static BootloaderState fromFastboot(String unlocked, String deviceState, String secure) {
        String u = normalize(unlocked);
        if ("yes".equals(u) || "true".equals(u)) {
            return UNLOCKED;
        }
        if ("no".equals(u) || "false".equals(u)) {
            return LOCKED;
        }

        String d = normalize(deviceState);
        if ("unlocked".equals(d)) {
            return UNLOCKED;
        }
        if ("locked".equals(d)) {
            return LOCKED;
        }

        // secure 只当最后一道：它的语义是「安全启动是否启用」，不是「解锁状态」，
        // 只在 no 时才敢判解锁（secure=no 意味着验证被关掉了）
        if ("no".equals(normalize(secure))) {
            return UNLOCKED;
        }
        return UNKNOWN;
    }

    /** {@code getprop} 输出带换行与空格，统一成小写去空白后再比。 */
    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.US);
    }
}
