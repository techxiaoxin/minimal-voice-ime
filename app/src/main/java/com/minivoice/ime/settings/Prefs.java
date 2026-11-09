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
    public static final String K_PROMPT_CLOUD = "polish_prompt_cloud";
    /** 云端 AI 整理用的文本模型，用户可以在设置页自己换。 */
    public static final String K_POLISH_MODEL = "polish_model";
    public static final String K_PROMPT_LOCAL = "polish_prompt_local";
    /** 本地整理模型的下载地址，留空用预设。 */
    public static final String K_LLM_URL = "llm_url";
    /** 本地整理模型档位：small 0.5B / big 1.5B。 */
    public static final String K_LLM_TIER = "llm_tier";
    /** 云端整理累计用量。 */
    public static final String K_TOKEN_TOTAL = "polish_tokens";
    public static final String K_TOKEN_QUESTS = "polish_requests";

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
    public static int getInt(Context ctx, String key, int def) {
        return sp(ctx).getInt(key, def);
    }

    public static void putInt(Context ctx, String key, int value) {
        sp(ctx).edit().putInt(key, value).apply();
    }

    /** 累加一次云端整理用掉的 token，顺便记次数。 */
    public static final String K_TOKEN_LOG = "token_log";
    /** 识别和整理分别累计，两边走的是不同模型，混在一起没法跟控制台对。 */
    public static final String K_TOKEN_ASR = "token_asr_sum";
    public static final String K_TOKEN_POLISH = "token_polish_sum";

    public static void addTokens(Context ctx, int tokens) {
        addTokens(ctx, tokens, "识别");
    }

    /** 一次说话算一组：识别先落一行，紧接着的整理并到同一行里。格式 ts|识别|整理。 */
    public static void addTokens(Context ctx, int tokens, String kind) {
        int n = Math.max(0, tokens);
        putInt(ctx, K_TOKEN_TOTAL, getInt(ctx, K_TOKEN_TOTAL, 0) + n);
        putInt(ctx, K_TOKEN_QUESTS, getInt(ctx, K_TOKEN_QUESTS, 0) + 1);
        if ("整理".equals(kind)) {
            putInt(ctx, K_TOKEN_POLISH, getInt(ctx, K_TOKEN_POLISH, 0) + n);
        } else {
            putInt(ctx, K_TOKEN_ASR, getInt(ctx, K_TOKEN_ASR, 0) + n);
        }

        long now = System.currentTimeMillis();
        String[] lines = getString(ctx, K_TOKEN_LOG, "").split("\n");
        StringBuilder sb = new StringBuilder();
        boolean merged = false;
        int kept = 0;
        if ("整理".equals(kind) && lines.length > 0) {
            String first = lines[0].trim();
            String[] p = first.split("\\|");
            if (p.length >= 3) {
                try {
                    long ts = Long.parseLong(p[0]);
                    int asr = parseNum(p[1]);
                    int pol = parseNum(p[2]);
                    if (pol == 0 && now - ts < 5 * 60 * 1000L) {
                        lines[0] = ts + "|" + asr + "|" + (pol + n);
                        merged = true;
                    }
                } catch (Exception ignored) {
                    // 老格式，下面按普通行处理
                }
            }
        }
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            long ts;
            int a = 0;
            int b = 0;
            try {
                ts = Long.parseLong(p[0]);
                if ("识别".equals(p[1]) || "整理".equals(p[1]) || "其他".equals(p[1])) {
                    // 旧格式：ts|类型|tokens
                    int v = Integer.parseInt(p[2]);
                    if ("整理".equals(p[1])) b = v; else a = v;
                } else {
                    a = parseNum(p[1]);
                    b = parseNum(p[2]);
                }
            } catch (Exception e) {
                continue;
            }
            if (kept >= 60) break;
            if (sb.length() > 0) sb.append("\n");
            sb.append(ts).append("|").append(a).append("|").append(b);
            kept++;
        }
        if (!merged) {
            String head = now + "|" + ("整理".equals(kind) ? 0 : n) + "|" + ("整理".equals(kind) ? n : 0);
            String old = sb.toString();
            sb.setLength(0);
            sb.append(head);
            if (!old.isEmpty()) sb.append("\n").append(old);
        }
        putString(ctx, K_TOKEN_LOG, sb.toString());
    }

    private static int parseNum(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static void resetLog(Context ctx) {
        putString(ctx, K_TOKEN_LOG, "");
    }

    public static void resetTokens(Context ctx) {
        putInt(ctx, K_TOKEN_TOTAL, 0);
        putInt(ctx, K_TOKEN_QUESTS, 0);
        putInt(ctx, K_TOKEN_ASR, 0);
        putInt(ctx, K_TOKEN_POLISH, 0);
    }

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
