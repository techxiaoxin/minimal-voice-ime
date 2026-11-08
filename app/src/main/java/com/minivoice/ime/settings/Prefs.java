package com.minivoice.ime.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全部偏好都放在本地 SharedPreferences，不上传任何公网服务器。
 */
public final class Prefs {

    private static final String FILE = "minivoice_prefs";

    public static final String K_ENDPOINT = "asr_endpoint";
    public static final String K_API_KEY = "asr_api_key";
    public static final String K_MODEL = "asr_model";
    public static final String K_PROTOCOL = "asr_protocol";
    public static final String K_LANGUAGE = "asr_language";
    public static final String K_PREFER_BT = "prefer_bluetooth_mic";
    public static final String K_DEVICE_NAME = "device_name";
    public static final String K_VOCAB = "custom_vocab";
    public static final String K_PUNCT = "punctuation_rule";
    public static final String K_FILTER = "filler_filter";
    public static final String K_SYNC_PENDING = "sync_pending";
    /** 记录内置 Key 与域名的版本，用来判断要不要刷掉本机的旧配置。 */
    public static final String K_BUILTIN_VER = "builtin_ver";
    /** 语音转完是否再过一遍 AI 整理。 */
    public static final String K_POLISH = "ai_polish";
    /** AI 整理用哪个引擎：cloud 走百炼，local 走手机里的小模型。 */
    public static final String K_POLISH_ENGINE = "polish_engine";
    /** 本地整理模型的下载地址，留空用预设。 */
    public static final String K_LLM_URL = "llm_url";

    /** 本地模型的下载地址，留空就用内置的镜像地址。 */
    public static final String K_SPACE_MODE = "space_mode";
    public static final String SPACE_INSERT = "space";
    public static final String SPACE_SEND = "send";
    public static final String K_LOCAL_URL = "local_model_url";
    /** 高精度模型的下载地址。 */
    public static final String K_LOCAL_URL_SV = "local_model_url_sv";
    /** 本地模型规格，light 轻量档，hq 高精度档。 */
    public static final String K_LOCAL_TIER = "local_model_tier";
    /** 主题：auto 跟随手机，light 白色，dark 黑色。 */
    public static final String K_THEME = "theme_mode";

    private static final Gson GSON = new Gson();

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getString(Context ctx, String key, String def) {
        return sp(ctx).getString(key, def);
    }

    public static void putString(Context ctx, String key, String value) {
        sp(ctx).edit().putString(key, value).apply();
    }

    public static boolean getBool(Context ctx, String key, boolean def) {
        return sp(ctx).getBoolean(key, def);
    }

    public static void putBool(Context ctx, String key, boolean value) {
        sp(ctx).edit().putBoolean(key, value).apply();
    }

    /** 自定义词表：一行一个词，同步时按 JSON 传。 */
    public static List<String> getWordList(Context ctx) {
        String raw = getString(ctx, K_VOCAB, "");
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static void setWordList(Context ctx, List<String> words) {
        putString(ctx, K_VOCAB, String.join("\n", words));
    }

    /** 语气词过滤表，逗号或换行分隔。 */
    public static List<String> getFillerList(Context ctx) {
        String raw = getString(ctx, K_FILTER, "嗯,呃,啊,那个,就是");
        List<String> out = new ArrayList<>();
        for (String t : raw.split("[,\n，]")) {
            String s = t.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static Map<String, String> exportSyncPayload(Context ctx) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("device", getString(ctx, K_DEVICE_NAME, android.os.Build.MODEL));
        map.put("vocab", GSON.toJson(getWordList(ctx)));
        map.put("punct", getString(ctx, K_PUNCT, ""));
        map.put("filter", getString(ctx, K_FILTER, ""));
        return map;
    }

    public static void importSyncPayload(Context ctx, Map<String, String> map) {
        if (map == null) return;
        String vocabJson = map.get("vocab");
        if (vocabJson != null) {
            List<String> words = GSON.fromJson(vocabJson, new TypeToken<List<String>>() {
            }.getType());
            if (words != null) setWordList(ctx, words);
        }
        if (map.get("punct") != null) putString(ctx, K_PUNCT, map.get("punct"));
        if (map.get("filter") != null) putString(ctx, K_FILTER, map.get("filter"));
    }

    /** 离线期间是否攒了未同步的偏好变更。 */
    public static boolean hasPendingSync(Context ctx) {
        return getBool(ctx, K_SYNC_PENDING, false);
    }

    public static void markPendingSync(Context ctx, boolean pending) {
        putBool(ctx, K_SYNC_PENDING, pending);
    }
}
