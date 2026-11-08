package com.minivoice.ime.asr;

import android.content.Context;

import com.minivoice.ime.settings.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 本地模型的存放与下载。装两套：
 *
 * 1. 流式小模型，负责边说边出字，回显快但准确率一般；
 * 2. SenseVoice 高精度模型，负责松手后的终稿，带标点，准确率明显更高。
 *
 * 两套都放在手机内部存储，用户自己在设置页点下载。地址默认走国内镜像，可以改。
 */
public final class LocalModelManager {

    public static final String STREAM_DIR = "sherpa-zh14m";
    public static final String SV_DIR = "sherpa-sensevoice";

    public static final String STREAM_LABEL = "流式小模型";
    public static final String SV_LABEL = "高精度模型";

    /** 流式模型的四个文件。 */
    public static final String[] STREAM_FILES = {
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
    };
    public static final long[] STREAM_SIZES = {
            21621684L, 1888682L, 1795562L, 48697L,
    };

    /** 高精度模型的两个文件，SenseVoice，中文带标点。 */
    public static final String[] SV_FILES = {"model.int8.onnx", "tokens.txt"};
    public static final long[] SV_SIZES = {239233841L, 315894L};

    public static final String STREAM_BASE_DEFAULT =
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main/";
    public static final String SV_BASE_DEFAULT =
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/";

    public static final String STREAM_BASE_OFFICIAL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main/";
    public static final String SV_BASE_OFFICIAL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/";

    public static final long TOTAL = sum(STREAM_SIZES) + sum(SV_SIZES);

    public interface Listener {
        void onFile(int index, int total, String name);

        void onProgress(float progress, long downloaded, long total);

        void onDone();

        void onError(String message);
    }

    private LocalModelManager() {
    }

    private static long sum(long[] v) {
        long n = 0;
        for (long x : v) n += x;
        return n;
    }

    public static File dir(Context ctx) {
        File f = new File(ctx.getFilesDir(), STREAM_DIR);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File svDir(Context ctx) {
        File f = new File(ctx.getFilesDir(), SV_DIR);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static String baseUrl(Context ctx) {
        String v = Prefs.getString(ctx, Prefs.K_LOCAL_URL, "");
        if (v == null || v.trim().isEmpty()) return STREAM_BASE_DEFAULT;
        v = v.trim();
        if (!v.endsWith("/")) v = v + "/";
        return v;
    }

    /** 高精度模型默认也走同一个镜像域名，用户改的是 domain 前缀时跟着走。 */
    public static String svBaseUrl(Context ctx) {
        String v = Prefs.getString(ctx, Prefs.K_LOCAL_URL_SV, "");
        if (v != null && !v.trim().isEmpty()) {
            v = v.trim();
            return v.endsWith("/") ? v : v + "/";
        }
        return SV_BASE_DEFAULT;
    }

    private static boolean check(File dir, String[] files, long[] sizes) {
        for (int i = 0; i < files.length; i++) {
            File f = new File(dir, files[i]);
            if (!f.exists() || f.length() < sizes[i] * 0.9) return false;
        }
        return true;
    }

    public static boolean streamReady(Context ctx) {
        return check(dir(ctx), STREAM_FILES, STREAM_SIZES);
    }

    public static boolean svReady(Context ctx) {
        return check(svDir(ctx), SV_FILES, SV_SIZES);
    }

    /** 两套都在才算完全就位。 */
    public static boolean isReady(Context ctx) {
        return streamReady(ctx) && svReady(ctx);
    }

    private static long downloadedBytes(Context ctx) {
        long n = 0;
        for (int i = 0; i < STREAM_FILES.length; i++) {
            File f = new File(dir(ctx), STREAM_FILES[i]);
            if (f.exists()) n += f.length();
        }
        for (int i = 0; i < SV_FILES.length; i++) {
            File f = new File(svDir(ctx), SV_FILES[i]);
            if (f.exists()) n += f.length();
        }
        return n;
    }

    public static String statusText(Context ctx) {
        if (isReady(ctx)) {
            return "已下载，占用 " + mb(downloadedBytes(ctx)) + "MB";
        }
        if (streamReady(ctx) && !svReady(ctx)) {
            return "流式模型已就位，高精度模型还没下完";
        }
        if (!streamReady(ctx) && svReady(ctx)) {
            return "高精度模型已就位，流式模型还没下完";
        }
        long n = downloadedBytes(ctx);
        if (n == 0) return "还没下载，一共约 " + mb(TOTAL) + "MB";
        return "下载了 " + mb(n) + "MB / 约 " + mb(TOTAL) + "MB，没下完";
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.CHINA, "%.0f", bytes / 1024f / 1024f);
    }

    /** 同步下载，调用方自己开线程。已经完整下好的文件会跳过。 */
    public static void download(Context ctx, Listener listener) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        long done = definitelyDone(ctx);
        int index = 0;
        int total = STREAM_FILES.length + SV_FILES.length;

        // 先流式，后高精度，顺序不影响使用
        index = fetch(client, dir(ctx), baseUrl(ctx), STREAM_FILES, STREAM_SIZES,
                index, total, done, listener);
        if (index < 0) return;
        done = preDone(ctx, true);
        index = fetch(client, svDir(ctx), svBaseUrl(ctx), SV_FILES, SV_SIZES,
                total - SV_FILES.length, total, done, listener);
        if (index < 0) return;
        listener.onDone();
    }

    private static long definitelyDone(Context ctx) {
        return 0;
    }

    private static long preDone(Context ctx, boolean stream) {
        long n = 0;
        if (stream) {
            for (int i = 0; i < STREAM_FILES.length; i++) {
                File f = new File(dir(ctx), STREAM_FILES[i]);
                if (f.exists()) n += f.length();
            }
        } else {
            for (int i = 0; i < SV_FILES.length; i++) {
                File f = new File(svDir(ctx), SV_FILES[i]);
                if (f.exists()) n += f.length();
            }
        }
        return n;
    }

    /** 返回下一个要处理的下标，出错返回 -1。 */
    private static int fetch(OkHttpClient client, File dir, String base,
                             String[] files, long[] sizes, int index, int total,
                             long alreadyDone, Listener listener) {
        long done = alreadyDone;
        for (int i = 0; i < files.length; i++) {
            String name = files[i];
            File out = new File(dir, name);
            if (out.exists() && out.length() >= sizes[i] * 0.9) {
                done += out.length();
                listener.onProgress(done / (float) TOTAL, done, TOTAL);
                index++;
                continue;
            }
            listener.onFile(index, total, name);
            File tmp = new File(dir, name + ".part");
            long fileDone = 0;
            try {
                Request req = new Request.Builder().url(base + name).get().build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new RuntimeException("HTTP " + resp.code());
                    }
                    InputStream in = resp.body().byteStream();
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                            fileDone += n;
                            long shown = done + fileDone;
                            listener.onProgress(shown / (float) TOTAL, shown, TOTAL);
                            if (fileDone > sizes[i] * 1.5f) break;
                        }
                        fos.flush();
                    }
                }
                if (tmp.length() < sizes[i] * 0.9) {
                    tmp.delete();
                    throw new RuntimeException("下到的文件不完整");
                }
                if (!tmp.renameTo(out)) {
                    tmp.delete();
                    throw new RuntimeException("保存失败");
                }
                done += out.length();
            } catch (Exception e) {
                listener.onError("下载 " + name + " 失败：" + shortMsg(e)
                        + "。换个网络再点一次，已经下好的文件会跳过。");
                return -1;
            }
            index++;
        }
        return index;
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        return m.length() > 80 ? m.substring(0, 80) : m;
    }
}
