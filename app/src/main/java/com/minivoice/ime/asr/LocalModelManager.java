package com.minivoice.ime.asr;

import android.content.Context;

import com.minivoice.ime.settings.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 本地模型的存放与下载。分两档规格，用户在设置页自己选，也允许改下载地址：
 *
 * 两档预览都用 14M 那个极简流式模型，24MB，边说边出字够快。
 * 标准档终稿 SenseVoice int8，约 228MB，中文带标点。
 * 高精度档终稿 whisper medium int8，约 903MB，中英都稳。
 *
 * 改地址只要文件名跟这一档的约定一致就能下，用户自己找的同款模型也能用。
 */
public final class LocalModelManager {

    public static final String TIER_LIGHT = "light";
    public static final String TIER_HQ = "hq";
    public static final String TIER_CUSTOM = "custom";

    /** 预览模型两档共用，24MB 的极简流式，下好一次切档就不用再下。 */
    public static final String STREAM_DIR = "sherpa-zh14m";
    /** 终稿模型两档各一个目录，能共存，切档不用重下。 */
    public static final String SV_DIR = "sherpa-sensevoice";
    public static final String HQ_FINAL_DIR = "sherpa-whisper-medium";
    public static final String CUSTOM_FINAL_DIR = "sherpa-custom-asr";

    /** 预览模型：14M 流式 zipformer，int8，约 24MB，只认中文，英文靠终稿兜。 */
    public static final String[] STREAM_FILES = {
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
    };
    public static final long[] STREAM_SIZES = {21621684L, 1888682L, 1795562L, 48697L};

    /** 标准档终稿：SenseVoice int8，中英日韩粤，自带标点和数字规整，国内阿里开源。 */
    public static final String[] LIGHT_FINAL_FILES = {"model.int8.onnx", "tokens.txt"};
    public static final long[] LIGHT_FINAL_SIZES = {239233841L, 315894L};

    /** 高精度档才有的终稿模型：whisper medium int8，encoder 加 decoder 两个大件。 */
    public static final String[] HQ_FINAL_FILES = {
            "medium-encoder.int8.onnx", "medium-decoder.int8.onnx", "medium-tokens.txt"};
    public static final long[] HQ_FINAL_SIZES = {374196283L, 571059257L, 816730L};

    public static final String STREAM_BASE_DEFAULT =
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/main/";
    public static final String SV_BASE_DEFAULT =
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/";

    public static final String HQ_FINAL_BASE_DEFAULT =
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-medium/resolve/main/";

    public interface Listener {
        void onFile(int index, int total, String name);

        void onProgress(float progress, long downloaded, long total);

        void onDone();

        void onError(String message);
    }

    private LocalModelManager() {
    }

    public static String tier(Context ctx) {
        String t = Prefs.getString(ctx, Prefs.K_LOCAL_TIER, TIER_LIGHT);
        if (TIER_HQ.equals(t) || TIER_CUSTOM.equals(t)) return t;
        return TIER_LIGHT;
    }

    public static boolean isCustom(Context ctx) {
        return TIER_CUSTOM.equals(tier(ctx));
    }

    public static boolean isHq(Context ctx) {
        return TIER_HQ.equals(tier(ctx));
    }

    public static String[] streamFiles(Context ctx) {
        return STREAM_FILES;
    }

    public static long[] streamSizes(Context ctx) {
        return STREAM_SIZES;
    }

    public static String[] finalFiles(Context ctx) {
        if (isCustom(ctx)) return LIGHT_FINAL_FILES;
        return isHq(ctx) ? HQ_FINAL_FILES : LIGHT_FINAL_FILES;
    }

    public static long[] finalSizes(Context ctx) {
        // 自定义模型的体积事先不知道，用 0 表示只校验文件存在，不在下载到一半时截断。
        if (isCustom(ctx)) return new long[]{0L, 0L};
        return isHq(ctx) ? HQ_FINAL_SIZES : LIGHT_FINAL_SIZES;
    }

    public static String defaultStreamUrl(Context ctx) {
        return STREAM_BASE_DEFAULT;
    }

    public static String defaultFinalUrl(Context ctx) {
        return isHq(ctx) ? HQ_FINAL_BASE_DEFAULT : SV_BASE_DEFAULT;
    }

    public static File dir(Context ctx) {
        File f = new File(ctx.getFilesDir(), STREAM_DIR);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File finalDir(Context ctx) {
        String name = isCustom(ctx) ? CUSTOM_FINAL_DIR
                : (isHq(ctx) ? HQ_FINAL_DIR : SV_DIR);
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    /** 预览模型固定走预设，界面那个地址框专门管终稿模型。 */
    public static String baseUrl(Context ctx) {
        return defaultStreamUrl(ctx);
    }

    /** 高精度档的终稿模型地址，留空就用预设镜像。 */
    public static String finalBaseUrl(Context ctx) {
        return customOr(ctx, defaultFinalUrl(ctx));
    }

    private static String customOr(Context ctx, String def) {
        String v = Prefs.getString(ctx, Prefs.K_LOCAL_URL_SV, "");
        if (v == null || v.trim().isEmpty()) return def;
        v = v.trim();
        return v.endsWith("/") ? v : v + "/";
    }

    private static boolean check(File dir, String[] files, long[] sizes) {
        for (int i = 0; i < files.length; i++) {
            File f = new File(dir, files[i]);
            if (!f.exists()) return false;
            if (sizes[i] > 0 && f.length() < sizes[i] * 0.9) return false;
        }
        return true;
    }

    public static boolean streamReady(Context ctx) {
        return check(dir(ctx), streamFiles(ctx), streamSizes(ctx));
    }

    public static boolean finalReady(Context ctx) {
        return check(finalDir(ctx), finalFiles(ctx), finalSizes(ctx));
    }

    /** 两套都在才算完全就位。 */
    public static boolean isReady(Context ctx) {
        return streamReady(ctx) && finalReady(ctx);
    }

    public static long totalBytes(Context ctx) {
        long n = 0;
        for (long v : streamSizes(ctx)) n += v;
        for (long v : finalSizes(ctx)) n += v;
        return n;
    }

    private static long downloadedBytes(Context ctx) {
        long n = 0;
        for (String name : streamFiles(ctx)) {
            File f = new File(dir(ctx), name);
            if (f.exists()) n += f.length();
        }
        for (String name : finalFiles(ctx)) {
            File f = new File(finalDir(ctx), name);
            if (f.exists()) n += f.length();
        }
        return n;
    }

    public static String tierLabel(Context ctx) {
        if (isCustom(ctx)) return "自定义档";
        return isHq(ctx) ? "高精度档" : "轻量档";
    }

    public static String tierSummary(Context ctx) {
        if (isCustom(ctx)) {
            return "自定义 sherpa-onnx 语音识别模型，下载后会先加载校验，不是语音模型会提示。";
        }
        if (isHq(ctx)) {
            return "极简预览加 whisper medium 终稿，中英都稳。";
        }
        return "极简预览加 SenseVoice 终稿，中文带标点，日常够用。";
    }

    public static String statusText(Context ctx) {
        if (isReady(ctx)) {
            return tierLabel(ctx) + "已下载，占用 " + mb(downloadedBytes(ctx)) + "MB";
        }
        if (isCustom(ctx)) {
            String url = Prefs.getString(ctx, Prefs.K_LOCAL_URL_SV, "");
            if (url == null || url.trim().isEmpty()) {
                return "自定义模型还没填地址，点下载模型填写模型目录";
            }
            return "自定义模型还没下完，点下载模型继续";
        }
        if (streamReady(ctx) && !finalReady(ctx)) {
            return "流式模型已就位，终稿模型还没下完";
        }
        if (!streamReady(ctx) && finalReady(ctx)) {
            return "终稿模型已就位，流式模型还没下完";
        }
        long n = downloadedBytes(ctx);
        if (n == 0) return "还没下载，一共约 " + mb(totalBytes(ctx)) + "MB";
        return "下载了 " + mb(n) + "MB / 约 " + mb(totalBytes(ctx)) + "MB，没下完";
    }

    private static String mb(long bytes) {
        return String.format(Locale.CHINA, "%.0f", bytes / 1024f / 1024f);
    }

    /** 同步下载，调用方自己开线程。已经完整下好的文件会跳过。 */
    public static void download(Context ctx, Listener listener) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        String[] sf = streamFiles(ctx);
        long[] ss = streamSizes(ctx);
        String[] ff = finalFiles(ctx);
        long[] fs = finalSizes(ctx);
        long total = totalBytes(ctx);

        // 先流式，后终稿，顺序不影响使用
        int index = fetch(client, dir(ctx), baseUrl(ctx), sf, ss,
                0, sf.length + ff.length, 0, total, listener);
        if (index < 0) return;
        long done = 0;
        for (String name : sf) {
            File f = new File(dir(ctx), name);
            if (f.exists()) done += f.length();
        }
        index = fetch(client, finalDir(ctx), finalBaseUrl(ctx), ff, fs,
                sf.length, sf.length + ff.length, done, total, listener);
        if (index < 0) return;
        listener.onDone();
    }

    /** 返回下一个要处理的下标，出错返回 -1。 */
    private static int fetch(OkHttpClient client, File dir, String base,
                             String[] files, long[] sizes, int index, int totalFiles,
                             long alreadyDone, long totalBytes, Listener listener) {
        long done = alreadyDone;
        for (int i = 0; i < files.length; i++) {
            String name = files[i];
            File out = new File(dir, name);
            if (out.exists() && (sizes[i] <= 0 || out.length() >= sizes[i] * 0.9)) {
                done += out.length();
                listener.onProgress(done / (float) totalBytes, done, totalBytes);
                index++;
                continue;
            }
            listener.onFile(index, totalFiles, name);
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
                            listener.onProgress(shown / (float) totalBytes, shown, totalBytes);
                            if (sizes[i] > 0 && fileDone > sizes[i] * 1.5f) break;
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
