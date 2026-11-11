package com.minivoice.ime.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 不依赖服务器的更新检查：从 Gitee 仓库根目录的 update.json 读版本信息，
 * APK 放在对应的 Gitee Release 附件里。
 */
public final class UpdateManager {

    public static final String MANIFEST_URL =
            "https://gitee.com/techman68/minimal-voice-ime/raw/main/update.json";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private UpdateManager() {
    }

    public static class UpdateInfo {
        public int versionCode;
        public String versionName = "";
        public String apkUrl = "";
        public String sha256 = "";
        public String notes = "";
        public boolean force;
    }

    public interface CheckCallback {
        /** info 为 null 且 error 为 null 表示已经是最新版本。 */
        void onResult(UpdateInfo info, String error);
    }

    public interface DownloadCallback {
        void onProgress(int percent, long downloaded, long total);

        void onDone(File apk);

        void onError(String message);
    }

    public static long currentVersionCode(Context context) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentVersionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    public static void check(Context context, CheckCallback callback) {
        final Context app = context.getApplicationContext();
        Request request = new Request.Builder()
                .url(MANIFEST_URL)
                .header("Cache-Control", "no-cache")
                .build();
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                MAIN.post(() -> callback.onResult(null, "网络连接失败"));
            }

            @Override
            public void onResponse(Call call, Response response) {
                String body = "";
                try (ResponseBody rb = response.body()) {
                    if (rb != null) body = rb.string();
                } catch (Exception e) {
                    MAIN.post(() -> callback.onResult(null, "读取更新信息失败"));
                    return;
                }
                if (!response.isSuccessful()) {
                    MAIN.post(() -> callback.onResult(null, "更新地址返回 " + response.code()));
                    return;
                }
                try {
                    JSONObject o = new JSONObject(body);
                    UpdateInfo info = new UpdateInfo();
                    info.versionCode = o.optInt("versionCode", 0);
                    info.versionName = o.optString("versionName", "");
                    info.apkUrl = o.optString("apkUrl", "");
                    info.sha256 = o.optString("sha256", "");
                    info.notes = o.optString("notes", "");
                    info.force = o.optBoolean("force", false);
                    if (info.versionCode <= 0 || info.apkUrl.isEmpty()) {
                        MAIN.post(() -> callback.onResult(null, "更新信息不完整"));
                        return;
                    }
                    final UpdateInfo result = info;
                    MAIN.post(() -> {
                        if (result.versionCode > currentVersionCode(app)) {
                            callback.onResult(result, null);
                        } else {
                            callback.onResult(null, null);
                        }
                    });
                } catch (Exception e) {
                    MAIN.post(() -> callback.onResult(null, "更新信息格式不对"));
                }
            }
        });
    }

    public static void download(Context context, UpdateInfo info, DownloadCallback callback) {
        final Context app = context.getApplicationContext();
        Request request = new Request.Builder().url(info.apkUrl).build();
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                MAIN.post(() -> callback.onError("下载失败，网络连接断开"));
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    MAIN.post(() -> callback.onError("下载失败，地址返回 " + response.code()));
                    return;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    MAIN.post(() -> callback.onError("下载失败，文件为空"));
                    return;
                }
                File dir = new File(app.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    MAIN.post(() -> callback.onError("创建更新目录失败"));
                    return;
                }
                File out = new File(dir, "update-" + info.versionCode + ".apk");
                long total = body.contentLength();
                long downloaded = 0L;
                int lastPct = -1;
                try (InputStream in = body.byteStream();
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        downloaded += n;
                        int pct = total > 0 ? (int) (downloaded * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int p = pct;
                            final long d = downloaded;
                            final long t = total;
                            MAIN.post(() -> callback.onProgress(p, d, t));
                        }
                    }
                    fos.flush();
                } catch (Exception e) {
                    if (out.exists()) out.delete();
                    MAIN.post(() -> callback.onError("下载中断，请重试"));
                    return;
                }
                if (info.sha256 != null && !info.sha256.isEmpty()) {
                    String actual = sha256(out);
                    if (!info.sha256.equalsIgnoreCase(actual)) {
                        out.delete();
                        MAIN.post(() -> callback.onError("安装包校验失败，请重新下载"));
                        return;
                    }
                }
                final File done = out;
                MAIN.post(() -> callback.onDone(done));
            }
        });
    }

    public static boolean canInstall(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    public static void requestInstallPermission(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(it);
        } catch (Exception ignored) {
        }
    }

    public static boolean install(Context context, File apk) {
        if (apk == null || !apk.exists()) return false;
        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", apk);
        Intent it = new Intent(Intent.ACTION_VIEW);
        it.setDataAndType(uri, "application/vnd.android.package-archive");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(it);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 给设置页获取当前版本号用。 */
    public static String versionLabel(Context context) {
        String name = currentVersionName(context);
        return name.isEmpty() ? "v?" : "v" + name;
    }
}
