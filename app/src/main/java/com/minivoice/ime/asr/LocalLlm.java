package com.minivoice.ime.asr;

/** 本地小语言模型的 JNI 入口，跑的是 llama.cpp。只管整理文字，不碰语音。 */
public final class LocalLlm {

    static {
        try {
            System.loadLibrary("llm");
        } catch (Throwable ignored) {
            // 没打进库的时候不影响识别，整理那边会退回规则清理
        }
    }

    private static volatile boolean loaded = false;

    private static native boolean nativeLoad(String path, int threads);

    private static native String nativeGenerate(String sys, String user, int maxTokens, int threads);

    private static native void nativeRelease();

    public static synchronized boolean load(String path) {
        if (loaded) return true;
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
        try {
            loaded = nativeLoad(path, threads);
        } catch (Throwable t) {
            loaded = false;
        }
        return loaded;
    }

    public static String generate(String sys, String user, int maxTokens) {
        if (!loaded) return "";
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
        try {
            String out = nativeGenerate(sys, user, maxTokens, threads);
            return out == null ? "" : out;
        } catch (Throwable t) {
            return "";
        }
    }

    public static synchronized void release() {
        if (!loaded) return;
        try {
            nativeRelease();
        } catch (Throwable ignored) {
        }
        loaded = false;
    }
}
