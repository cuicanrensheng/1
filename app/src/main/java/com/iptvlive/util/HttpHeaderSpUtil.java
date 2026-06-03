package com.iptvlive.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局请求头管理
 * UA默认ExoPlayer全局固定
 * Refer/Cookie全局配置，单频道自定义优先级更高
 */
public class HttpHeaderSpUtil {
    private static final String SP_NAME = "http_header_sp";
    private static SharedPreferences sp;
    private static final String KEY_UA = "def_user_agent";
    private static final String KEY_REF = "def_referer";
    private static final String KEY_CK = "def_cookie";

    public static void init(Context ctx) {
        sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    //获取UA默认ExoPlayer
    public static String getUA() {
        return sp.getString(KEY_UA, "ExoPlayer");
    }
    public static void setUA(String ua) {
        sp.edit().putString(KEY_UA, ua).apply();
    }

    public static String getReferer() {
        return sp.getString(KEY_REF, "");
    }
    public static void setReferer(String ref) {
        sp.edit().putString(KEY_REF, ref).apply();
    }

    public static String getCookie() {
        return sp.getString(KEY_CK, "");
    }
    public static void setCookie(String ck) {
        sp.edit().putString(KEY_CK, ck).apply();
    }

    //组装全局Header Map
    public static Map<String, String> getGlobalHeaderMap() {
        Map<String, String> map = new HashMap<>();
        String ua = getUA();
        String ref = getReferer();
        String ck = getCookie();
        if (!ua.isEmpty()) map.put("User-Agent", ua);
        if (!ref.isEmpty()) map.put("Referer", ref);
        if (!ck.isEmpty()) map.put("Cookie", ck);
        return map;
    }
}
