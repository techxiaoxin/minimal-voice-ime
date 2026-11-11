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
    /** 整段兜底优先用这个，兼容 chat 接口，比 qwen-audio 原生多模态快。 */
    public static final String BAILIAN_BATCH_MODEL = "qwen3-asr-flash";
    /** AI 整理用的文本模型，换成 flash 版，关掉思考后单次不到一秒。 */
    public static final String POLISH_MODEL = "qwen3.8-flash";
    /** 百炼公共原生多模态识别地址，不绑定任何业务空间。 */
    public static final String BAILIAN_NATIVE_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    /** 百炼公共兼容接口地址。 */
    public static final String BAILIAN_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    /** 普通百炼 Key 用这个公共地址。 */
    public static final String BAILIAN_PUBLIC_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public String endpoint = BAILIAN_NATIVE_ENDPOINT;
    /** 仓库不内置任何 Key，装完由用户在设置页自己填。 */
    public String apiKey = "";
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
        if (!PROTOCOL_LOCAL.equals(proto) && !PROTOCOL_HTTP.equals(proto)
                && !PROTOCOL_WS.equals(proto) && !PROTOCOL_BAILIAN.equals(proto)) {
            proto = PROTOCOL_BAILIAN;
        }
        c.protocol = proto;
        c.language = Prefs.getString(ctx, Prefs.K_LANGUAGE, "zh");
        // 整理默认开着；老版本里没开过的，这次升级统一打开一次。
        if (!Prefs.getBool(ctx, "polish_on_v52", false)) {
            Prefs.putBool(ctx, Prefs.K_POLISH, true);
            Prefs.putBool(ctx, "polish_on_v52", true);
        }
        c.polish = Prefs.getBool(ctx, Prefs.K_POLISH, true);

        if (PROTOCOL_BAILIAN.equals(proto)) {
            c.endpoint = orDefault(Prefs.getString(ctx, Prefs.K_ENDPOINT, ""), BAILIAN_NATIVE_ENDPOINT);
            c.apiKey = loadKey(ctx);
            c.model = orDefault(Prefs.getString(ctx, Prefs.K_MODEL, ""), BAILIAN_MODEL);
            return c;
        }

        c.endpoint = orDefault(Prefs.getString(ctx, Prefs.K_ENDPOINT, ""), BAILIAN_NATIVE_ENDPOINT);
        c.apiKey = loadKey(ctx);
        c.model = orDefault(Prefs.getString(ctx, Prefs.K_MODEL, ""), BAILIAN_MODEL);
        return c;
    }

    /** 只读用户自己保存的 Key，仓库和安装包里都没有内置 Key。 */
    private static String loadKey(Context ctx) {
        if (Prefs.getBool(ctx, Prefs.K_KEY_CLEARED, false)) return "";
        return sanitizeKey(Prefs.getString(ctx, Prefs.K_API_KEY, ""));
    }

    /**
     * 把用户填的地址收成“这一家平台的 base URL”。
     * 用户可以直接填控制台给的 base，也可以填完整接口地址，甚至只填域名；
     * 各平台自己的路径差异只在这里处理一次，后面的模型列表、识别、整理统一复用。
     */
    public static String providerBase(String endpoint) {
        String ep = endpoint == null ? "" : endpoint.trim();
        if (ep.isEmpty()) return ep;
        String host = hostOf(ep);
        if (host.isEmpty()) return ep;
        String scheme = "https";
        String path = "";
        try {
            android.net.Uri u = android.net.Uri.parse(ep);
            if (u.getScheme() != null && !u.getScheme().isEmpty()
                    && !"ws".equals(u.getScheme()) && !"wss".equals(u.getScheme())) {
                scheme = u.getScheme();
            }
            path = u.getPath() == null ? "" : u.getPath();
        } catch (Exception ignored) {
        }
        String base = scheme + "://" + host;

        if (host.contains("aliyuncs.com") || host.contains("dashscope")) {
            // 百炼：原生 multimodal / 实时 WS / 兼容接口都在同一个业务空间域名下。
            // 无论用户填的是原生地址、兼容地址，还是只填域名，统一收成兼容 base。
            if (path.contains("/compatible-mode/v1")) return base + "/compatible-mode/v1";
            if (path.contains("/api/v1/services") || path.contains("/api-ws/")) {
                return base + "/compatible-mode/v1";
            }
            if (path.isEmpty() || "/".equals(path)) return base + "/compatible-mode/v1";
            return ep;
        }
        if (host.contains("bigmodel.cn") || host.contains("zhipuai.cn")) {
            if (path.isEmpty() || "/".equals(path)) return base + "/api/paas/v4";
            int cut = path.indexOf("/api/paas/v4");
            return cut >= 0 ? base + "/api/paas/v4" : ep;
        }
        if (host.contains("xiaomimimo.com")) {
            if (path.isEmpty() || "/".equals(path)) return base + "/v1";
            int cut = path.indexOf("/v1");
            return cut >= 0 ? base + "/v1" : ep;
        }
        // 普通 OpenAI 兼容平台：把常见的完整地址收成 base。
        if (path.endsWith("/chat/completions")) {
            return base + path.substring(0, path.length() - "/chat/completions".length());
        }
        if (path.endsWith("/models")) {
            return base + path.substring(0, path.length() - "/models".length());
        }
        return ep;
    }

    /** 从接口地址推一个查模型列表的地址，OpenAI 风格的 /models。 */
    public static String modelsUrl(String endpoint) {
        String ep = endpoint == null ? "" : endpoint.trim();
        if (ep.isEmpty()) ep = BAILIAN_NATIVE_ENDPOINT;
        String host;
        String scheme;
        try {
            android.net.Uri u = android.net.Uri.parse(ep);
            host = u.getHost();
            scheme = u.getScheme();
        } catch (Exception e) {
            return "";
        }
        if (host == null || host.isEmpty()) return "";
        if ("ws".equals(scheme) || "wss".equals(scheme)) return "";
        String base = providerBase(ep);
        if (base.isEmpty()) return "";
        if (base.endsWith("/compatible-mode/v1") || base.endsWith("/api/paas/v4")
                || base.endsWith("/v1") || base.endsWith("/api/v1")) {
            return base + "/models";
        }
        return base + "/v1/models";
    }

    /** 只看域名，不把供应商写死在识别引擎里。 */
    public static String hostOf(String endpoint) {
        try {
            android.net.Uri u = android.net.Uri.parse(endpoint == null ? "" : endpoint.trim());
            String host = u.getHost();
            return host == null ? "" : host;
        } catch (Exception ignored) {
            return "";
        }
    }

    public static boolean isBailianEndpoint(String endpoint) {
        String host = hostOf(endpoint);
        return host.contains("aliyuncs.com") || host.contains("dashscope");
    }

    /**
     * 百炼有两种 Key：普通 Key 和业务空间专属的 sk-ws- Key。
     * 用户只换 Key、没换地址时，如果不处理，就会拿普通 Key 去访问
     * 某个业务空间域名，服务端只能返回 401。这里按 Key 类型兜一下：
     * 普通 Key 遇到业务空间域名，自动落到公共 dashscope 地址。
     */
    public static String adaptEndpointForKey(String endpoint, String apiKey) {
        String ep = endpoint == null ? "" : endpoint.trim();
        String key = sanitizeKey(apiKey);
        boolean workspaceKey = key.startsWith("sk-ws-");
        String host = hostOf(ep);
        if (ep.isEmpty()) {
            return workspaceKey ? BAILIAN_NATIVE_ENDPOINT : BAILIAN_PUBLIC_ENDPOINT;
        }
        if (!workspaceKey && host.contains("maas.aliyuncs.com")) {
            return BAILIAN_PUBLIC_ENDPOINT;
        }
        return providerBase(ep);
    }

    public static boolean isXiaomiEndpoint(String endpoint) {
        return hostOf(endpoint).contains("xiaomimimo.com");
    }

    public static boolean isZhipuEndpoint(String endpoint) {
        String host = hostOf(endpoint);
        return host.contains("bigmodel.cn") || host.contains("zhipuai.cn");
    }

    /** 百炼原生多模态识别地址按用户域名推导，换业务空间也能用。 */
    public static String nativeEndpoint(String endpoint) {
        String host = hostOf(endpoint);
        if (host.isEmpty()) return BAILIAN_NATIVE_ENDPOINT;
        return "https://" + host + "/api/v1/services/aigc/multimodal-generation/generation";
    }

    /** 智谱的语音转写接口，multipart 上传 wav。 */
    public static String zhipuTranscriptionEndpoint(String endpoint) {
        String host = hostOf(endpoint);
        if (host.isEmpty()) host = "open.bigmodel.cn";
        return "https://" + host + "/api/paas/v4/audio/transcriptions";
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
        String base = providerBase(ep);
        if (base.isEmpty()) return ep;
        if (base.endsWith("/chat/completions")) return base;
        String path = "";
        try {
            android.net.Uri u = android.net.Uri.parse(base);
            path = u.getPath() == null ? "" : u.getPath();
        } catch (Exception ignored) {
        }
        if (path.isEmpty() || "/".equals(path)) return base + "/v1/chat/completions";
        return base + "/chat/completions";
    }

    /** 识别框里填的是实时模型就用它，否则回到内置的流式模型。 */
    public String streamModel() {
        String m = model == null ? "" : model.trim();
        return m.toLowerCase(java.util.Locale.ROOT).contains("realtime") ? m : STREAM_MODEL;
    }

    /**
     * 整段上传 / 兼容接口用的模型。
     * 实时模型不能拿去请求 chat/completions 或原生整段接口，
     * 否则服务端会直接返回参数错误；这里统一换成对应的整段识别模型。
     */
    public String batchModel() {
        String m = model == null ? "" : model.trim();
        String low = m.toLowerCase(java.util.Locale.ROOT);
        if (m.isEmpty() || low.contains("realtime")) return BAILIAN_MODEL;
        // 控制台会把同一个模型拆成 -streaming / -filetrans / -message 等入口，
        // 整段上传要用裸模型名，带这些后缀会报 url / 参数错误。
        for (String suffix : new String[]{"-streaming", "-filetrans", "-message"}) {
            if (low.endsWith(suffix)) {
                return m.substring(0, m.length() - suffix.length());
            }
        }
        return m;
    }

    /**
     * 流式识别地址：接口地址填 https 业务域名会自动换成同域的 wss 实时地址，
     * 直接填 wss 地址也认。这样换域名或换实时模型都能生效，不会再写死一个地址。
     */
    public String streamEndpoint() {
        String ep = endpoint == null ? "" : endpoint.trim();
        // 百炼的实时 WebSocket 入口在公共 dashscope 域名，业务空间的 maas 域名
        // 没有 /api-ws/v1/realtime 这条路由，手机会直接收到 404。
        // sk-ws- 的业务空间 Key 在公共实时地址上同样可用，所以这里固定走公共入口。
        if (isBailianEndpoint(ep)) {
            return "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=" + streamModel();
        }
        String host = null;
        String scheme = "wss";
        try {
            android.net.Uri u = android.net.Uri.parse(ep);
            host = u.getHost();
            if (u.getScheme() != null && u.getScheme().startsWith("ws")) scheme = u.getScheme();
        } catch (Exception ignored) {
        }
        if (host == null || host.isEmpty()) {
            host = "dashscope.aliyuncs.com";
        }
        // 用户直接填了 wss 地址，就沿着他填的路径走，只把 model 换成当前这个
        if ("ws".equals(scheme) || "wss".equals(scheme)) {
            String base = ep;
            int q = base.indexOf('?');
            if (q >= 0) base = base.substring(0, q);
            if (base.isEmpty()) base = scheme + "://" + host + "/api-ws/v1/realtime";
            return base + "?model=" + streamModel();
        }
        return scheme + "://" + host + "/api-ws/v1/realtime?model=" + streamModel();
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

    /** 本机存的是空串时回落到默认值，防止地址或模型为空。 */
    private static String orDefault(String v, String fallback) {
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    /** 用量记录按“识别模型 + 整理模型 + 地址”分组，换模型就换一套账。 */
    public String tokenIdentity(Context ctx) {
        String polishEngine = Prefs.getString(ctx, Prefs.K_POLISH_ENGINE, "cloud");
        String polishModel = Prefs.getString(ctx, Prefs.K_POLISH_MODEL, POLISH_MODEL);
        // 整理接口始终跟随识别；换平台域名或换模型名才另起一套账。
        return (protocol == null ? "" : protocol) + "|" + hostOf(endpoint) + "|" + model
                + "|" + polishEngine + "|" + hostOf(polishEndpoint(endpoint)) + "|" + polishModel;
    }

    public void save(Context ctx) {
        Prefs.putBool(ctx, Prefs.K_KEY_CLEARED, apiKey == null || apiKey.trim().isEmpty());
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
