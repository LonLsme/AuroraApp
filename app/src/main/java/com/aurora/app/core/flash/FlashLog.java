package com.aurora.app.core.flash;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 刷机日志落盘。
 *
 * <p>写到应用专属外部目录（{@code /storage/emulated/0/Android/data/<pkg>/files/flash_logs/}）：
 * 拔线后用文件管理器或电脑都能直接取走，不需要 root，也不用申请任何存储权限。
 *
 * <p>文件名带时间戳，<b>不覆盖</b>历史日志 —— 刷机出问题往往要对比「上一次成功那次」的输出。
 */
public final class FlashLog {

    private static final String TAG = "FlashLog";

    /** 保存目录名（位于 {@code getExternalFilesDir} 下）。 */
    private static final String DIR_NAME = "flash_logs";

    private FlashLog() {
    }

    /** 日志目录；外部存储不可用时退回内部目录。 */
    public static File dir(Context context) {
        File dir = context.getExternalFilesDir(DIR_NAME);
        if (dir == null) {
            dir = new File(context.getFilesDir(), DIR_NAME);
        }
        return dir;
    }

    /**
     * 把一段文本存成日志文件。
     *
     * @param prefix 文件名前缀，用于区分来源（如 {@code oplus} / {@code flash}）
     * @return 落盘后的文件；失败返回 {@code null}
     */
    public static File save(Context context, String prefix, String content) {
        File dir = dir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "日志目录创建失败: " + dir.getAbsolutePath());
            return null;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, prefix + "_" + stamp + ".log");
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content == null ? "" : content);
            writer.flush();
            return file;
        } catch (IOException e) {
            Log.e(TAG, "日志保存失败", e);
            return null;
        }
    }
}
