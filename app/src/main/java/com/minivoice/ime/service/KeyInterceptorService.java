package com.minivoice.ime.service;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 可选的按键拦截服务。输入法自己的窗口收不到耳机按键，
 * 所以要用无障碍服务来抓 KEYCODE_HEADSETHOOK 和媒体键。
 * 只有当前输入法是极简语音时才把按键转成录音动作，其他情况原样放过。
 */
public class KeyInterceptorService extends AccessibilityService {

    private static KeyInterceptorService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean voiceKeyDown = false;

    public static boolean isEnabled() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 这里不监听界面事件，只用按键过滤能力。
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // 只在极简语音真的在当输入法时才接管，否则原样放给耳机自己处理
        if (!MinimalVoiceIME.isActive()) return false;
        MinimalVoiceIME ime = MinimalVoiceIME.peek();
        if (ime == null) return false;

        int code = event.getKeyCode();
        if (!isMappedKey(code)) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                voiceKeyDown = true;
                main.post(ime::startVoice);
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            voiceKeyDown = false;
            main.post(ime::stopVoice);
            return true;
        }
        return false;
    }

    private boolean isMappedKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return true;
            default:
                return false;
        }
    }

    public boolean isVoiceKeyDown() {
        return voiceKeyDown;
    }
}
