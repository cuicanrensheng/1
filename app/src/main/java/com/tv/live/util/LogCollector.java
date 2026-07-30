package com.tv.live.util;

import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日志收集器（单例）
 * 用于在内存中缓存应用日志，供“网络调试日志”弹窗展示
 */
public class LogCollector {
    private static volatile LogCollector sInstance;
    private final StringBuilder mLogBuilder;

    private LogCollector() {
        mLogBuilder = new StringBuilder();
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

    /**
     * 添加一条日志
     * @param tag 标签
     * @param msg 日志内容
     */
    public void addLog(String tag, String msg) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        String time = sdf.format(new Date());
        mLogBuilder.append(time).append(" [").append(tag).append("] ").append(msg).append("\n");

        // 防止内存泄漏：如果日志超过 10KB，自动截断前面的内容
        if (mLogBuilder.length() > 1024 * 10) {
            mLogBuilder.delete(0, 1024 * 2);
        }
    }

    /**
     * 获取所有缓存的日志
     */
    public String getAllLogs() {
        return mLogBuilder.toString();
    }

    /**
     * 清空所有缓存日志（对应弹窗中的“清空日志”按钮）
     */
    public void clear() {
        mLogBuilder.setLength(0);
    }
}
