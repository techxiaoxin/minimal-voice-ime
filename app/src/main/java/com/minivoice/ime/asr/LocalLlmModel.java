package com.minivoice.ime.asr;

import android.content.Context;

import com.minivoice.ime.settings.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 本地整理模型的下载和状态管理，分小模型 0.5B 和大模型 1.5B 两档。 */
public final class LocalLlmModel {

    public static final String TIER_SMALL = "small";
    public static final String TIER_BIG = "big";

    public static final String SMALL_DIR = "llm-qwen05";
    public static final String SMALL_FILE = "qwen2.5-0.5b-instruct-q4_0.gguf";
    public static final long SMALL_SIZE = 428730208L;
    public static final String SMALL_URL =
            "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf";

    public static final String BIG_DIR = "llm-qwen15";
    public static final String BIG_FILE = "qwen2.5-1.5b-instruct-q2_k.gguf";
    public static final long BIG_SIZE = 752880160L;
    public static final String BIG_URL =
            "https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q2_k.gguf";

    private LocalLlmModel() {
    }

    /** 当前选的是哪一档。 */
    public static String tier(Context ctx) {
        String t = Prefs.getString(ctx, Prefs.K_LLM_TIER, TIER_SMALL);
        return TIER_BIG.equals(t) ? TIER_BIG : TIER_SMALL;
    }

    public static boolean isBig(Context ctx) {
        return TIER_BIG.equals(tier(ctx));
    }

    public static String dirName(Context ctx) {
        return isBig(ctx) ? BIG_DIR : SMALL_DIR;
    }

    public static String fileName(Context ctx) {
        return isBig(ctx) ? BIG_FILE : SMALL_FILE;
    }

    public static long expectedSize(Context ctx) {
        return isBig(ctx) ? BIG_SIZE : SMALL_SIZE;
    }

    public static File dir(Context ctx) {
        File f = new File(ctx.getFilesDir(), dirName(ctx));
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File file(Context ctx) {
        return new File(dir(ctx), fileName(ctx));
    }

    public static boolean ready(Context ctx) {
        File f = file(ctx);
        return f.exists() && f.length() >= (long) (expectedSize(ctx) * 0.98);
    }

    public static String statusText(Context ctx) {
        String name = isBig(ctx) ? "大模型" : "小模型";
        if (ready(ctx)) return name + "已就位，可以用";
        File f = file(ctx);
        if (f.exists() && f.length() > 0) {
            return String.format(java.util.Locale.CHINA, "%s下载了 %.0fMB，还没下完",
                    name, f.length() / 1024f / 1024f);
        }
        return name + "还没下载";
    }

    public static String url(Context ctx) {
        String v = Prefs.getString(ctx, Prefs.K_LLM_URL, "");
        if (v != null && !v.trim().isEmpty()) return v.trim();
        return isBig(ctx) ? BIG_URL : SMALL_URL;
    }

    public interface Listener {
        void onProgress(float progress, long downloaded, long total);

        void onDone(boolean ok, String message);
    }

    public static void download(Context ctx, Listener l) {
        new Thread(() -> {
            File tmp = new File(dir(ctx), fileName(ctx) + ".part");
            File dst = file(ctx);
            long expected = expectedSize(ctx);
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url(ctx)).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                long total = conn.getContentLengthLong();
                if (total <= 0) total = expected;
                long have = tmp.exists() ? tmp.length() : 0;
                if (have > 0 && have < total) {
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(url(ctx)).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("Range", "bytes=" + have + "-");
                    conn.connect();
                } else {
                    have = 0;
                    if (tmp.exists()) tmp.delete();
                }
                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(tmp, have > 0);
                byte[] buf = new byte[16384];
                long done = have;
                long last = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done - last > 1024 * 1024) {
                        last = done;
                        final long d = done;
                        final long t = total;
                        l.onProgress((float) d / t, d, t);
                    }
                }
                out.close();
                in.close();
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) throw new Exception("改名失败");
                l.onDone(true, "模型下载完成");
            } catch (Throwable e) {
                l.onDone(false, "下载失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
