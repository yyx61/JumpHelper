package com.example.jumphelper;

import android.content.Context;
import android.content.SharedPreferences;

/** 极简配置存储：系数 + 面板开关 */
public final class Prefs {

    private static final String FILE = "jump_helper";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 毫秒 / 像素。0 表示尚未初始化 */
    public static float getCoefficient(Context c) {
        return sp(c).getFloat("coefficient", 0f);
    }

    public static void setCoefficient(Context c, float v) {
        sp(c).edit().putFloat("coefficient", v).apply();
    }

    public static boolean isPanelEnabled(Context c) {
        return sp(c).getBoolean("panel", false);
    }

    public static void setPanelEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("panel", v).apply();
    }
}
