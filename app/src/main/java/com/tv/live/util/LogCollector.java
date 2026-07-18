package com.tv.live.util;

import android.util.Log;

/**
 * 日志管理工具类（基于 LogCollector 实现）
 * 用于统一输出日志到 Logcat 和 设置页面的日志收集器
 */
public class LogManager {
    private static final String TAG = "LiveTV";
    
    // 直接复用你的 LogCollector
    private static final LogCollector collector = LogCollector.getInstance();

    /**
     * 记录解析/播放日志
     * 该方法会被 LiveSourceLoader 和 RedirectLoggingHttpDataSource 调用
     */
    public static void log(String msg) {
        // 1. 输出到 Android Logcat（调试时能看到）
        Log.d(TAG, msg);
        // 2. 保存到 LogCollector（设置页面“查看解析日志”能读取到）
        collector.addLog("播放", msg);
    }

    /**
     * 记录操作日志（如设置开关、切台等）
     */
    public static void logOperation(String msg) {
        Log.d(TAG, "[操作] " + msg);
        collector.addLog("操作", msg);
    }

    /**
     * 获取所有日志（供设置页面的日志对话框显示）
     * 目前 LogCollector 会混合保存播放和操作日志，因此统一返回全部内容
     */
    public static String getPlayLog() {
        return collector.getAllLogs();
    }

    /**
     * 获取操作日志（同样返回全部日志，方便统一查看）
     */
    public static String getOperationLog() {
        return collector.getAllLogs();
    }

    /**
     * 清空日志（配合设置页面的“清空日志”按钮）
     */
    public static void clearPlayLog() {
        collector.clearLogs();
    }

    public static void clearOperationLog() {
        collector.clearLogs();
    }
}
