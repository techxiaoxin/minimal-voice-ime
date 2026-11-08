package com.minivoice.ime.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/**
 * 蓝牙麦检测与 SCO 通道切换。只在录音期间占用 SCO，录完立刻放开，
 * 不干扰耳机自己的播放和原生功能。
 */
public class BluetoothAudioRouter {

    private BluetoothAudioRouter() {
    }

    public static boolean hasBluetoothMic(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        AudioDeviceInfo[] inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo info : inputs) {
            int type = info.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                return true;
            }
        }
        return false;
    }

    public static void enterSco(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.startBluetoothSco();
            am.setBluetoothScoOn(true);
        } catch (Exception ignored) {
        }
    }

    public static void leaveSco(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            am.setBluetoothScoOn(false);
            am.stopBluetoothSco();
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {
        }
    }
}
