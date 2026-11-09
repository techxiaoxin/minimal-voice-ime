package com.minivoice.ime.asr;

import android.content.Context;

import com.minivoice.ime.settings.Prefs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户自填的语音识别配置。请求地址和 Key 都留在本机，语音流直连用户选的接口。
 */
public class AsrConfig {

    public static final String PROTOCOL_HTTP = "http";
    public static final String PROTOCOL_WS = "ws";
    public static final String PROTOCOL_LOCAL = "local";
    public static final String PROTOCOL_BAILIAN = "bailian";

    /** 固定使用 qwen-audio-3.1-asr-flash，装完不配置也能用。 */
    public static final String BAILIAN_MODEL = "qwen-audio-3.1-asr-flash";
    public static final String BAILIAN_MODEL_AUDIO = "qwen-audio-3.1-asr-flash";
    public static final String[] BAILIAN_MODELS = {BAILIAN_MODEL};
    /** 实时流式识别模型，边说边出字，走 WebSocket。 */
    public static final String STREAM_MODEL = "qwen3-asr-flash-realtime";
    /** AI 整理用的文本模型，换成 flash 版，关掉思考后单次不到一秒。 */
    public static final String POLISH_MODEL = "qwen3.8-flash";
    /** 业务空间专属地址：sk-ws- 开头的 Key 必须配这个域名，用通用域名会被判 401。 */
    public static final String BAILIAN_NATIVE_ENDPOINT =
            "https://private-space.example.com/api/v1/services/aigc/multimodal-generation/generation";
    /** 兼容接口地址，留作备用。 */
    public static final String BAILIAN_ENDPOINT =
            "https://private-space.example.com/compatible-mode/v1/chat/completions";
    /** 内置 Key 或域名每换一次就 +1，老用户升级时靠它自动覆盖成新值。 */
    public static final int BUILTIN_VERSION = 3;
    /**
     * 内置 Key 不写死在源码里，编译时从本机 local.properties 的 bailian.key 读进来。
     * 仓库里这个值是空串，别人 clone 下来装完是没有内置 Key 的，
     * 需要自己去设置页填自己的百炼 Key。这样公开源码不会把额度共享出去。
     */
    public static final String BAILIAN_KEY = com.minivoice.ime.BuildConfig.DEFAULT_BAILIAN_KEY;

    public String endpoint = BAILIAN_NATIVE_ENDPOINT;
    public String apiKey = BAILIAN_KEY;
    public String model = BAILIAN_MODEL;
    public String protocol = PROTOCOL_BAILIAN;
    public String language = "zh";
    public boolean polish = false;
    /** WebSocket 模式下每帧的 JSON 字段名，默认约定，可在设置页改。 */
    public String wsAudioField = "audio";
    public String wsTextField = "text";
    public Map<String, String> extraHeaders = new LinkedHashMap<>();

    public static AsrConfig load(Context ctx) {
        AsrConfig c = new AsrConfig();
        String proto = orDefault(Prefs.getString(ctx, Prefs.K_PROTOCOL, ""), PROTOCOL_BAILIAN);
        // 模型下好了就默认走本地，整条链路不联网；只切这一次，之后用户自己选的算数
        if (!Prefs.getBool(ctx, "local_trial_on", false) && LocalModelManager.isReady(ctx)) {
            if (PROTOCOL_BAILIAN.equals(proto)) {
                proto = PROTOCOL_LOCAL;
                Prefs.putString(ctx, Prefs.K_PROTOCOL, proto);
            }
            Prefs.putBool(ctx, "local_trial_on", true);
        }
        if (!PROTOCOL_LOCAL.equals(proto) && !PROTOCOL_HTTP.equals(proto)
                && !PROTOCOL_WS.equals(proto) && !PROTOCOL_BAILIAN.equals(proto)) {
            proto = PROTOCOL_BAILIAN;
        }
        c.protocol = proto;
        c.language = Prefs.getString(ctx, Prefs.K_LANGUAGE, "zh");
        c.polish = Prefs.getBool(ctx, Prefs.K_POLISH, false);

        if (PROTOCOL_BAILIAN.equals(proto)) {
            int storedVer = 0;
            try {
                storedVer = Integer.parseInt(Prefs.getString(ctx, Prefs.K_BUILTIN_VER, "0"));
            } catch (Exception ignored) {
            }
            if (storedVer != BUILTIN_VERSION) {
                // 内置 Key 或域名换过版本，把本机的旧值整体刷成新的，避免继续用 401 的老配置
                c.apiKey = sanitizeKey(BAILIAN_KEY);
                c.model = BAILIAN_MODEL;
                c.endpoint = BAILIAN_NATIVE_ENDPOINT;
                Prefs.putString(ctx, Prefs.K_BUILTIN_VER, String.valueOf(BUILTIN_VERSION));
                c.save(ctx);
                return c;
            }
            // 版本没变，说明用户可能在设置页手动改过并保存，这时候以本机的值为准
            c.endpoint = orDefault(Prefs.getString(ctx, Prefs.K_ENDPOINT, ""), BAILIAN_NATIVE_ENDPOINT);
            c.apiKey = sanitizeKey(orDefault(Prefs.getString(ctx, Prefs.K_API_KEY, ""), BAILIAN_KEY));
            c.model = orDefault(Prefs.getString(ctx, Prefs.K_MODEL, ""), BAILIAN_MODEL);
            return c;
        }

        c.endpoint = orDefault(Prefs.getString(ctx, Prefs.K_ENDPOINT, ""), BAILIAN_NATIVE_ENDPOINT);
        c.apiKey = sanitizeKey(orDefault(Prefs.getString(ctx, Prefs.K_API_KEY, ""), BAILIAN_KEY));
        c.model = orDefault(Prefs.getString(ctx, Prefs.K_MODEL, ""), BAILIAN_MODEL);
        return c;
    }

    /** 是否本项目支持并实测通过的百炼模型。 */
    public static boolean isSupportedModel(String model) {
        for (String m : BAILIAN_MODELS) {
            if (m.equals(model)) return true;
        }
        return false;
    }

    /**
     * 同一个域名下，识别走 multimodal 原生接口，整理走 OpenAI 兼容接口。
     * 用户改过接口地址时，把路径换一下就能复用同一个业务空间域名。
     */
    public static String polishEndpoint(String asrEndpoint) {
        String ep = asrEndpoint == null ? "" : asrEndpoint.trim();
        if (ep.isEmpty()) return BAILIAN_ENDPOINT;
        if (ep.contains("/compatible-mode/v1/chat/completions")) return ep;
        if (ep.contains("/api/v1/services/aigc/multimodal-generation/generation")) {
            return ep.replace("/api/v1/services/aigc/multimodal-generation/generation",
                    "/compatible-mode/v1/chat/completions");
        }
        return BAILIAN_ENDPOINT;
    }

    /** 原生模型走 multimodal 接口，其余走 OpenAI 兼容接口。 */
    public static String endpointFor(String model) {
        return BAILIAN_MODEL_AUDIO.equals(model) ? BAILIAN_NATIVE_ENDPOINT : BAILIAN_ENDPOINT;
    }

    /** 清掉复制带进来的空格、换行和零宽字符，这类字符会让服务端判定 Key 无效。 */
    public static String sanitizeKey(String k) {
        if (k == null) return "";
        return k.replaceAll("[\\s\\u00A0\\u2007\\u202F\\u200B\\u200C\\u200D\\u200E\\u200F\\uFEFF]", "");
    }

    /** 本机存的是空串时回落到内置值，防止空 Key 直接打出 401。 */
    private static String orDefault(String v, String fallback) {
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    public void save(Context ctx) {
        Prefs.putString(ctx, Prefs.K_ENDPOINT, endpoint);
        Prefs.putString(ctx, Prefs.K_API_KEY, apiKey);
        Prefs.putString(ctx, Prefs.K_MODEL, model);
        Prefs.putString(ctx, Prefs.K_PROTOCOL, protocol);
        Prefs.putString(ctx, Prefs.K_LANGUAGE, language);
        Prefs.putBool(ctx, Prefs.K_POLISH, polish);
    }

    public boolean isUsable() {
        if (PROTOCOL_LOCAL.equals(protocol)) return true;
        if (PROTOCOL_BAILIAN.equals(protocol)) {
            return apiKey != null && !apiKey.trim().isEmpty();
        }
        return endpoint != null && !endpoint.trim().isEmpty();
    }

    public String describe() {
        if (PROTOCOL_LOCAL.equals(protocol)) return "本地识别";
        if (PROTOCOL_BAILIAN.equals(protocol)) return "百炼 · " + model;
        if (!isUsable()) return "未配置接口";
        return protocol + " · " + endpoint;
    }
}
