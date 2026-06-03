package com.iptvlive.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * APP配置SP存储
 * 存储：订阅列表、EPG列表、自动刷新、开机自启、域名白名单、EPG仅当日等开关
 */
public class AppSpUtil {
    private static final String SP_NAME = "iptv_setting";
    private static SharedPreferences sp;

    //SP初始化
    public static void init(Context ctx) {
        sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    //换台方向反转
    public static boolean getReverseChannel() {
        return sp.getBoolean("reverse_channel", false);
    }
    public static void setReverseChannel(boolean b) {
        sp.edit().putBoolean("reverse_channel", b).apply();
    }

    //开机自启开关
    public static boolean getBootStart() {
        return sp.getBoolean("boot_start", false);
    }
    public static void setBootStart(boolean b) {
        sp.edit().putBoolean("boot_start", b).apply();
    }

    //自动刷新开关
    public static boolean getAutoRefreshSub() {
        return sp.getBoolean("auto_refresh_sub", false);
    }
    public static void setAutoRefreshSub(boolean b) {
        sp.edit().putBoolean("auto_refresh_sub", b).apply();
    }

    //自动刷新间隔小时
    public static int getAutoRefreshHour() {
        return sp.getInt("refresh_h", 24);
    }
    public static void setAutoRefreshHour(int h) {
        sp.edit().putInt("refresh_h", h).apply();
    }

    //EPG只显示当天节目
    public static boolean getEpgOnlyToday() {
        return sp.getBoolean("epg_only_day", true);
    }
    public static void setEpgOnlyToday(boolean b) {
        sp.edit().putBoolean("epg_only_day", b).apply();
    }

    //域名优选白名单
    public static List<String> getOkDomainList() {
        String str = sp.getString("ok_domain", "");
        return str.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(str.split(",")));
    }
    public static void saveOkDomainList(List<String> list) {
        sp.edit().putString("ok_domain", String.join(",", list)).apply();
    }

    //M3U订阅列表
    public static List<String> getSubSourceList() {
        String str = sp.getString("sub_list", "");
        return str.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(str.split(",")));
    }
    public static void saveSubSourceList(List<String> list) {
        sp.edit().putString("sub_list", String.join(",", list)).apply();
    }
    public static String getCurSubUrl() {
        return sp.getString("cur_sub", "");
    }
    public static void setCurSubUrl(String u) {
        sp.edit().putString("cur_sub", u).apply();
    }

    //EPG订阅列表
    public static List<String> getEpgSourceList() {
        String str = sp.getString("epg_list", "");
        return str.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(str.split(",")));
    }
    public static void saveEpgSourceList(List<String> list) {
        sp.edit().putString("epg_list", String.join(",", list)).apply();
    }
    public static String getCurEpgUrl() {
        return sp.getString("cur_epg", "");
    }
    public static void setCurEpgUrl(String u) {
        sp.edit().putString("cur_epg", u).apply();
    }
        //补充双参数getString、putString
    public static String getString(String key, String defValue) {
        return sp.getString(key, defValue);
    }

    public static void putString(String key, String value) {
        sp.edit().putString(key, value).apply();
    }

}
