package com.minivoice.ime.asr;

import android.content.Context;

import com.minivoice.ime.settings.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 本地整理模型（Qwen2.5-0.5B 的 GGUF）的下载和状态管理。 */
public final class LocalLlmModel {

    public static final String DIR = "llm-qwen05";
    public static final String FILE = "qwen2.5-0.5b-instruct-q4_0.gguf";
    public static final long SIZE = 428730208L;

    public static final String DEFAULT_URL =
            "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf";

    private LocalLlmModel() {
    }

    public static File dir(Context ctx) {
        File f = new File(ctx.getFilesDir(), DIR);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File file(Context ctx) {
        return new File(dir(ctx), FILE);
    }

    public static boolean ready(Context ctx) {
        File f = file(ctx);
        return f.exists() && f.length() >= (long) (SIZE * 0.98);
    }

    public static String statusText(Context ctx) {
        if (ready(ctx)) return "整理模型已就位";
        File f = file(ctx);
        if (f.exists() && f.length() > 0) {
            return String.format(java.util.Locale.CHINA, "下载了 %.0fMB，还没下完",
                    f.length() / 1024f / 1024f);
        }
        return "整理模型还没下载";
    }

    public static String url(Context ctx) {
        String v = Prefs.getString(ctx, Prefs.K_LLM_URL, "");
        if (v == null || v.trim().isEmpty()) return DEFAULT_URL;
        return v.trim();
    }

    public interface Listener {
        void onProgress(float progress, long downloaded, long total);

        void onDone(boolean ok, String message);
    }

    public static void download(Context ctx, Listener l) {
        new Thread(() -> {
            File tmp = new File(dir(ctx), FILE + ".part");
            File dst = file(ctx);
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url(ctx)).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                long total = conn.getContentLengthLong();
                if (total <= 0) total = SIZE;
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
                }
                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(tmp, have > 0);
                byte[] buf = new byte[8192];
                long done = have;
                long last = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done - last > 1024 * 512) {
                        last = done;
                        final long d = done;
                        final long t = total;
                        l.onProgress((float) d / t, d, t);
                    }
                }
                out.close();
                in.close();
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) {
                    throw new IOException2("改名失败");
                }
                l.onDone(true, "整理模型下载完成");
            } catch (Throwable e) {
                l.onDone(false, "下载失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private static class IOException2 extends Exception {
        IOException2(String m) {
            super(m);
        }
    }
}
