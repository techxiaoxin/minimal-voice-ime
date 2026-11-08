package com.minivoice.ime.asr;

/** 识别结果里偶尔冒出繁体字，这里统一转成简体，别让人看到一半繁体一半简体。 */
public final class TextNormalizer {

    private static android.icu.text.Transliterator trans;

    private TextNormalizer() {
    }

    public static String toSimplified(String text) {
        if (text == null || text.isEmpty()) return text;
        // ICU 的繁简转换是 API 29 才给的，低版本直接用字表
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                if (trans == null) {
                    trans = android.icu.text.Transliterator.getInstance(
                            "TraditionalChinese/SimplifiedChinese");
                }
                return trans.transliterate(text);
            } catch (Throwable ignored) {
            }
        }
        return fallback(text);
    }

    /** 繁体字和简体字的对应，两两一组，ICU 用不了时拿它兜底。 */
    private static final String PAIRS =
            "這这們们個个來来時时說说話话點点裡里爲为後后發发現现實实學学體体"
            + "習习聽听問问題题嗎吗嘛嘛兒儿麼么樣样誰谁國国還还沒没準准備备東东"
            + "車车電电腦脑網网絡络錢钱開开關关机机碼码聯联係系統统軟软設设頁页"
            + "記记錄录視视頻频圖图書书館馆難难謝谢錯错誤误轉转換换總总数数據据"
            + "語语音音聲声響响應应歡欢迎迎顔颜臉脸潔洁夠够簡简極极終终於于";

    private static String fallback(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char out = c;
            for (int k = 0; k + 1 < PAIRS.length(); k += 2) {
                if (PAIRS.charAt(k) == c) {
                    out = PAIRS.charAt(k + 1);
                    break;
                }
            }
            sb.append(out);
        }
        return sb.toString();
    }
}
