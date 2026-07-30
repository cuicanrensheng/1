package com.tv.live.util;

import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class LogCollector {
    private static volatile LogCollector sInstance;
    private final List<String> logs;
    private final SimpleDateFormat sdf;

    private LogCollector() {
        logs = new LinkedList<>();
        sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    }

    public static LogCollector getInstance() {
        if (sInstance == null) {
            synchronized (LogCollector.class) {
                if (sInstance == null) {
                    sInstance = new LogCollector();
                }
            }
        }
        return sInstance;
    }

    public void addLog(String tag, String msg) {
        // 🔴【白名单过滤】放行：空tag、播放日志、虎牙解析日志
        if (!TextUtils.isEmpty(tag)
                && !"播放".equals(tag)
                && !"虎牙解析".equals(tag)) {
            return; // 拦截其他无关日志
        }

        String time = sdf.format(new Date());
        String line;
        if (!TextUtils.isEmpty(tag)) {
            line = time + " 【" + tag + "】 " + msg;
        } else {
            line = time + " " + msg;
        }

        // 🟢【最新日志放头部】
        logs.add(0, line);

        // 最大缓存200行，防止内存暴涨
        if (logs.size() > 200) {
            logs.remove(logs.size() - 1);
        }
    }

    public String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        for (String log : logs) {
            sb.append(log).append("\n");
        }
        return sb.toString();
    }

    public void clear() {
        logs.clear();
    }
}
