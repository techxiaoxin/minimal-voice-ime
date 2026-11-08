package com.minivoice.ime.ui;

import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

import com.minivoice.ime.settings.Prefs;

/**
 * 主题只有三种：跟随手机、强制白色、强制黑色。
 * 键盘用配置覆盖的 Context 取色，设置页用 AppCompat 的夜间模式。
 */
public final class ThemeUtil {

    public static final String AUTO = "auto";
    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    private ThemeUtil() {
    }

    public static String mode(Context ctx) {
        String m = Prefs.getString(ctx, Prefs.K_THEME, AUTO);
        if (LIGHT.equals(m) || DARK.equals(m)) return m;
        return AUTO;
    }

    /** 返回 Configuration.UI_MODE_NIGHT_YES 或 NO。 */
    public static int nightMode(Context ctx) {
        String m = mode(ctx);
        if (LIGHT.equals(m)) return Configuration.UI_MODE_NIGHT_NO;
        if (DARK.equals(m)) return Configuration.UI_MODE_NIGHT_YES;
        return ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
    }

    public static boolean isLight(Context ctx) {
        return nightMode(ctx) == Configuration.UI_MODE_NIGHT_NO;
    }

    /** 设置页、主页这些 Activity 开之前调一次。 */
    public static void applyToActivity(Context ctx) {
        String m = mode(ctx);
        int target = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (LIGHT.equals(m)) target = AppCompatDelegate.MODE_NIGHT_NO;
        else if (DARK.equals(m)) target = AppCompatDelegate.MODE_NIGHT_YES;
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target);
        }
    }
}
