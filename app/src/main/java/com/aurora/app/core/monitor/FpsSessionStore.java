package com.aurora.app.core.monitor;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 帧率会话存储：一次记录一个文件（{@code filesDir/fps-sessions/<开始时间>.json}），
 * 另有一份 {@code index.json} 存摘要。
 *
 * <p>为什么不用 SharedPreferences：一个 300 秒的会话就有 4 条 300 长度的浮点数组，
 * 塞进 XML 会随着历史累积变成几 MB，读写都要全量解析，容易在主线程卡住。
 * 拆成「一个会话一个文件 + 一份轻量索引」后，列表页只读索引，详情页才读全文。
 *
 * <p>保留最近 {@link #LIMIT} 个会话，超出后按时间从旧到新删文件。
 */
public final class FpsSessionStore {

    private static final String DIR = "fps-sessions";
    private static final String INDEX = "index.json";
    /** 最多保留的会话数。 */
    public static final int LIMIT = 20;

    private FpsSessionStore() {
    }

    /** 保存会话（写全文 + 刷新索引 + 裁剪旧文件）。 */
    public static void save(Context context, FpsSessionData data) {
        if (context == null || data == null || data.startTimeMs <= 0) {
            return;
        }
        File dir = dir(context);
        File target = new File(dir, data.startTimeMs + ".json");
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(data.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return;
        }

        List<JSONObject> summaries = readIndex(context);
        List<JSONObject> kept = new ArrayList<>();
        for (JSONObject o : summaries) {
            if (o.optLong("t") != data.startTimeMs) {
                kept.add(o);
            }
        }
        kept.add(data.toSummaryJson());
        kept.sort((a, b) -> Long.compare(b.optLong("t"), a.optLong("t")));
        while (kept.size() > LIMIT) {
            JSONObject old = kept.remove(kept.size() - 1);
            File stale = new File(dir, old.optLong("t") + ".json");
            if (stale.exists() && !stale.delete()) {
                // 删不掉就留着索引项，避免「文件还在但列表看不到」
                kept.add(old);
                break;
            }
        }
        writeIndex(context, kept);
    }

    /** 读取会话摘要列表（新的在前）。只读索引，不解析曲线。 */
    public static List<FpsSessionData> loadSummaries(Context context) {
        List<FpsSessionData> list = new ArrayList<>();
        for (JSONObject o : readIndex(context)) {
            try {
                list.add(FpsSessionData.fromSummaryJson(o));
            } catch (Exception ignored) {
            }
        }
        list.sort((a, b) -> Long.compare(b.startTimeMs, a.startTimeMs));
        return list;
    }

    /** 读取完整会话；文件不存在或损坏返回 null。 */
    public static FpsSessionData load(Context context, long startTimeMs) {
        if (context == null) {
            return null;
        }
        File f = new File(dir(context), startTimeMs + ".json");
        if (!f.exists()) {
            return null;
        }
        try {
            String raw = new String(readAll(f), StandardCharsets.UTF_8);
            return FpsSessionData.fromJson(new JSONObject(raw));
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除一个会话（文件 + 索引项）。 */
    public static void delete(Context context, long startTimeMs) {
        if (context == null) {
            return;
        }
        new File(dir(context), startTimeMs + ".json").delete();
        List<JSONObject> kept = new ArrayList<>();
        for (JSONObject o : readIndex(context)) {
            if (o.optLong("t") != startTimeMs) {
                kept.add(o);
            }
        }
        writeIndex(context, kept);
    }

    // ─── 文件操作 ───

    private static File dir(Context context) {
        File d = new File(context.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private static List<JSONObject> readIndex(Context context) {
        List<JSONObject> list = new ArrayList<>();
        File f = new File(dir(context), INDEX);
        if (!f.exists()) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(new String(readAll(f), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    list.add(o);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private static void writeIndex(Context context, List<JSONObject> summaries) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : summaries) {
            arr.put(o);
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir(context), INDEX))) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAll(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    (int) Math.min(Math.max(file.length(), 64), 1 << 20));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
