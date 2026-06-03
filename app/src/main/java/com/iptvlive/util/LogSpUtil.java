package com.iptvlive.util;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日志存储工具
 * 解析日志：M3U/EPG/播放报错
 * 操作日志：换台、设置、APP崩溃
 * 每条日志带时间，上限200条
 */
public class LogSpUtil {
    private static final String SP_NAME = "iptv_log_sp";
    private static SharedPreferences sp;
    private static final String KEY_PARSE = "parse_log_list";
    private static final String KEY_OPER = "oper_log_list";
    private static final int MAX_LOG = 200;
    private static final Gson gson = new Gson();

    public static void init(Context ctx) {
        sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    //获取当前时间
    private static String getNowTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    //新增解析日志
    public static void addParseLog(String msg) {
        List<String> list = getParseLogList();
        list.add(0, getNowTime() + " | " + msg);
        if (list.size() > MAX_LOG) list = list.subList(0, MAX_LOG);
        sp.edit().putString(KEY_PARSE, gson.toJson(list)).apply();
    }
    public static List<String> getParseLogList() {
        String json = sp.getString(KEY_PARSE, "[]");
        return gson.fromJson(json, ArrayList.class);
    }

    //新增操作日志
    public static void addOperCrashLog(String msg) {
        List<String> list = getOperCrashLogList();
        list.add(0, getNowTime() + " | " + msg);
        if (list.size() > MAX_LOG) list = list.subList(0, MAX_LOG);
        sp.edit().putString(KEY_OPER, gson.toJson(list)).apply();
    }
    public static List<String> getOperCrashLogList() {
        String json = sp.getString(KEY_OPER, "[]");
        return gson.fromJson(json, ArrayList.class);
    }
}
