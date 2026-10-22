package com.aurora.app.ui.flash;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import com.aurora.app.core.flash.FlashToolchain;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SAF 选文件 → 拷进应用专属目录。
 *
 * <h3>为什么必须拷贝</h3>
 * 系统文件选择器给回来的是 {@code content://} URI，而 {@code fastboot} / {@code libksud}
 * 都是<b>按真实路径</b>读文件的普通程序，读不懂 content URI。所以选中之后统一拷一份到
 * {@link FlashToolchain#imageDir(Context)}（外部存储的应用专属目录）——
 * 这个位置应用自己可写、root 身份的刷机工具也能按路径读，是唯一两头都通的地方。
 *
 * <p>刷机页（{@link FlashPage}）与刷入 KernelSU 页共用这套流程，所以从页面的私有方法里抽了出来。
 */
public final class FlashFilePicker {

    private static final String TAG = "FlashFilePicker";

    /** 拷贝缓冲区：128KB（大镜像用得上，小文件也不亏）。 */
    private static final int COPY_BUFFER = 128 * 1024;

    /** 全通配的 MIME 过滤（选任意文件）。 */
    public static final String MIME_ANY = "*/*";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-flash-pick");
        t.setDaemon(true);
        return t;
    });

    private FlashFilePicker() {
    }

    /** 拷贝进度与结果回调。<b>全部在后台线程触发</b>。 */
    public interface Callback {
        /**
         * 进度。{@code total} 为 0 表示源文件没给大小（此时不报进度）。
         */
        void onProgress(long copied, long total);

        /** 拷贝完成。 */
        void onDone(File file);

        /** 失败。 */
        void onError(String message);
    }

    /**
     * 拉起系统文件选择器。
     *
     * @param mime 过滤类型，选任意文件时传 {@link #MIME_ANY}
     */
    public static boolean pick(Activity activity, int requestCode, String mime) {
        return startPick(activity, requestCode, mime, false);
    }

    /**
     * 拉起系统文件选择器，<b>允许一次选多个文件</b>。
     *
     * <p>批量安装 APK 用。多选与单选走的是同一个 {@code ACTION_OPEN_DOCUMENT}，
     * 差别只在 {@code EXTRA_ALLOW_MULTIPLE}；返回侧多选走 {@code getClipData()}、
     * 单选走 {@code getData()}，两条都要认（部分机型在只选了一个文件时仍走 {@code getData()}）。
     */
    public static boolean pickMultiple(Activity activity, int requestCode, String mime) {
        return startPick(activity, requestCode, mime, true);
    }

    private static boolean startPick(Activity activity, int requestCode, String mime, boolean multiple) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        if (multiple) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, requestCode);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "无法打开文件选择器", e);
            return false;
        }
    }

    /**
     * 从选择结果里取出全部 URI。
     *
     * <p>多选走 {@link ClipData}、单选走 {@code getData()}；两者都空就是用户没选。
     * 返回顺序即文件选择器给出的顺序。
     */
    public static List<Uri> resultUris(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data == null) {
            return uris;
        }
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        if (uris.isEmpty() && data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    /**
     * 把选中的 URI 拷进应用专属目录。
     *
     * <p>文件名取 SAF 给的 display name；拿不到时用 {@code fallbackPrefix + 时间戳 + fallbackExt}。
     * 重名<b>直接覆盖</b>：同一个镜像反复选是常态，留一堆 {@code boot(1).img} 只会让人分不清
     * 哪个才是刚选的那份。
     */
    public static void copyAsync(final Context context, final Uri uri,
                                 final String fallbackPrefix, final String fallbackExt,
                                 final Callback callback) {
        if (uri == null) {
            callback.onError("没有选择文件");
            return;
        }
        final Context appContext = context.getApplicationContext();
        WORKER.execute(() -> {
            String displayName = displayName(appContext, uri);
            final String name = (displayName == null || displayName.isEmpty())
                    ? fallbackPrefix + System.currentTimeMillis() + fallbackExt
                    : displayName;
            copyInto(appContext, uri, name, callback);
        });
    }

    /**
     * 指定目标文件名地拷贝。
     *
     * <p>给「一次选多个文件」的页面用（应用安装页）：批量时若只听 SAF 给的显示名，
     * 两条来自不同目录但同名的 {@code base.apk} 会互相覆盖，装上去的是哪份就说不清了。
     * 由调用方决定唯一文件名是这里最省事的做法。同名文件<b>直接覆盖</b>。
     */
    public static void copyAsyncAs(final Context context, final Uri uri, final String fileName,
                                   final Callback callback) {
        if (uri == null) {
            callback.onError("没有选择文件");
            return;
        }
        if (fileName == null || fileName.isEmpty()) {
            callback.onError("文件名为空");
            return;
        }
        final Context appContext = context.getApplicationContext();
        WORKER.execute(() -> copyInto(appContext, uri, fileName, callback));
    }

    /** 拷贝主体：把 {@code uri} 落到应用专属目录下的 {@code name}，并回报进度。 */
    private static void copyInto(final Context appContext, final Uri uri, final String name,
                                 final Callback callback) {
        File dir = FlashToolchain.imageDir(appContext);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dest = new File(dir, name);

        long total = size(appContext, uri);
        long copied = 0;
        try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                callback.onError("无法读取所选文件");
                return;
            }
            try (OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[COPY_BUFFER];
                int read;
                long lastReport = 0;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    copied += read;
                    // 每 1MB 报一次进度：安装包动辄上百 MB，没有进度用户会以为卡死。
                    if (copied - lastReport >= 1024L * 1024L) {
                        lastReport = copied;
                        callback.onProgress(copied, total);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "拷贝失败", e);
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            callback.onError(e.getMessage() == null ? "拷贝失败" : e.getMessage());
            return;
        }
        callback.onProgress(copied, total);
        callback.onDone(dest);
    }

    /** SAF 给的显示名；取不到返回 {@code null}。 */
    public static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // 某些第三方文件提供者不支持 query，退回「用时间戳命名」。
        }
        return null;
    }

    /** 源文件大小；未知返回 0。 */
    public static long size(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
            // 同上
        }
        return 0L;
    }

    // ───────────────── 目录选择 ─────────────────

    /**
     * 拉起系统<b>目录</b>选择器。
     *
     * <p>与 {@link #pick} 的区别是走 {@code ACTION_OPEN_DOCUMENT_TREE} ——
     * 线刷包是一整个目录，不是一个文件。
     *
     * @return 是否成功拉起（失败时调用方应提示用户改用手输路径）
     */
    public static boolean pickDirectory(Activity activity, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, requestCode);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "无法打开目录选择器", e);
            return false;
        }
    }

    /**
     * 把 tree URI 还原成文件系统路径。
     *
     * <p>{@code content://.../tree/primary%3ADownload%2Fmi_rom} 里的 documentId 是
     * {@code primary:Download/mi_rom}；{@code primary} 对应内置存储，其它 volume 名
     * 对应 {@code /storage/<volume>}（SD 卡、U 盘）。
     *
     * <p>之所以要还原：{@code fastboot} / {@code sh} 都是按<b>真实路径</b>读文件的普通程序，
     * 读不懂 content URI。
     *
     * @return 形如 {@code /storage/emulated/0/Download/mi_rom}；无法还原返回 {@code null}
     */
    public static String resolveTreePath(Uri tree) {
        if (tree == null) {
            return null;
        }
        try {
            String documentId = DocumentsContract.getTreeDocumentId(tree);
            if (documentId == null) {
                return null;
            }
            int colon = documentId.indexOf(':');
            if (colon < 0) {
                return null;
            }
            String volume = documentId.substring(0, colon);
            String relative = documentId.substring(colon + 1);
            String base = "primary".equalsIgnoreCase(volume)
                    ? Environment.getExternalStorageDirectory().getAbsolutePath()
                    : "/storage/" + volume;
            if (relative.isEmpty()) {
                return base;
            }
            return relative.startsWith("/") ? base + relative : base + "/" + relative;
        } catch (Exception e) {
            return null;
        }
    }
}
