package com.minivoice.ime.asr;

/**
 * 统一的语音识别引擎接口。上层不关心底层是 HTTP 一次性上传还是 WebSocket 流式。
 */
public interface AsrEngine {

    interface Callback {
        /** 中间结果，用于输入框里的实时预览。 */
        void onPartial(String text);

        /** 最终结果，落盘上屏。 */
        void onFinal(String text);

        void onError(String message);
    }

    void start(Callback callback);

    /**
     * 引擎是否自己管录音。系统自带的语音识别会自己开麦克风，
     * 这时上层就不要再起 AudioRecorder，否则两路抢麦克风。
     */
    default boolean managesOwnAudio() {
        return false;
    }

    /** 喂入 16kHz、单声道、16bit 小端 PCM。 */
    void feed(byte[] pcm, int length);

    /** 用户松手，结束并等待最终结果。 */
    void finish();

    void cancel();
}
