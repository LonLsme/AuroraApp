package com.aurora.app.core.flash;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 小米官方线刷包里的一个 shell 脚本。
 *
 * <h3>小米线刷包是怎么刷的</h3>
 * 小米的 TGZ / 解压目录里带一组批处理脚本，刷机就是「切到包目录、跑其中一个脚本」，
 * 脚本内部再逐条调 {@code fastboot flash}。三个关键脚本与语义沿用既有工具
 * （{@code lq1.java} 里「脚本名 → 确认文案」的映射）：
 *
 * <pre>
 *   flash_all.sh                   全量刷写（清除数据）
 *   flash_all_lock.sh              全量刷写并重新锁 BL（清除数据）
 *   flash_all_except_storage.sh    保留存储的全量刷写
 * </pre>
 *
 * <p>本类只负责「识别 + 分类」，<b>不含任何界面文案</b> —— 中文怎么写交给 UI 层按
 * {@link Kind} 给出，这样 {@code core} 层不必知道既有工具里那几句提示长什么样。
 */
public final class MiFlashScript {

    /** 脚本类型。判定依据只有文件名，与脚本内容无关。 */
    public enum Kind {
        /** {@code flash_all.sh}：清数据。 */
        FLASH_ALL,
        /** {@code flash_all_except_storage.sh}：不清数据。 */
        FLASH_ALL_EXCEPT_STORAGE,
        /** {@code flash_all_lock.sh}：清数据并回锁 BL。 */
        FLASH_ALL_LOCK,
        /** 其它 {@code .sh}，语义未知，交给用户自己确认。 */
        OTHER
    }

    /** 脚本文件。 */
    public final File file;
    /** 文件名（含 {@code .sh} 后缀），展示与拼命令都用它。 */
    public final String name;
    /** 类型。 */
    public final Kind kind;

    private MiFlashScript(File file, Kind kind) {
        this.file = file;
        this.name = file.getName();
        this.kind = kind;
    }

    /**
     * 扫一个目录里的 {@code .sh} 脚本。
     *
     * <p>过滤条件是既有工具的原样搬用：{@code isFile() && getName().endsWith(".sh")}。
     * <b>特意保持大小写敏感</b>（{@code .SH} 不会被收）—— 与既有工具一致，
     * 免得同一条规则在两处表现不一样。
     *
     * <p>结果按文件名排序（忽略大小写）：目录里通常同时有三个脚本，
     * 每次扫描顺序都不同会让人以为内容变了。
     *
     * @return 永不为 {@code null}；目录不存在或读不了时返回空列表
     */
    public static List<MiFlashScript> scan(File dir) {
        List<MiFlashScript> result = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            return result;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            // Android 11+ 对未授权目录会直接返回 null（而不是空数组），别当成「目录是空的」。
            return result;
        }
        for (File file : files) {
            if (isScript(file)) {
                result.add(new MiFlashScript(file, kindOf(file.getName())));
            }
        }
        result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return result;
    }

    /** 是否是线刷脚本（普通文件 + {@code .sh} 结尾）。 */
    public static boolean isScript(File file) {
        return file != null && file.isFile() && file.getName().endsWith(".sh");
    }

    /** 按文件名判类型；认不出来返回 {@link Kind#OTHER}。 */
    public static Kind kindOf(String name) {
        if (name == null) {
            return Kind.OTHER;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "flash_all.sh":
                return Kind.FLASH_ALL;
            case "flash_all_except_storage.sh":
                return Kind.FLASH_ALL_EXCEPT_STORAGE;
            case "flash_all_lock.sh":
                return Kind.FLASH_ALL_LOCK;
            default:
                return Kind.OTHER;
        }
    }
}
