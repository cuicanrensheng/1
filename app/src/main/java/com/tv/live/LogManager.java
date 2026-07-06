package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 日志管理器（最终防卡顿、防崩溃版）
 * 
 * 【修改说明】
 * 1. 多线程安全：StringBuilder → StringBuffer，全局 synchronized
 * 2. 移除操作日志（代码已清理，不再使用）
 * 3. UI 解析放到子线程，只截取最近 100 行，彻底告别卡顿
 */
public class LogManager {

    // ====================== 全局日志系统 ======================
    /** 解析&播放日志（线程安全） */
    private static volatile StringBuffer PLAY_LOG = new StringBuffer();

    // ====================== 日志大小限制 ======================
    private static final int MAX_LOG_LENGTH = 20000;
    private static final int KEEP_LOG_LENGTH = 15000;
    private static final int MAX_DISPLAY_LINES = 100; // 弹窗只显示最近 100 行

    // ====================================================================
    // 1. 记录日志（加锁保证线程安全）
    // ====================================================================
    public static void log(String msg) {
        if (PLAY_LOG == null) {
            PLAY_LOG = new StringBuffer();
        }
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        synchronized (PLAY_LOG) {
            PLAY_LOG.append("[").append(time).append("] ").append(msg).append("\n");
            if (PLAY_LOG.length() > MAX_LOG_LENGTH) {
                PLAY_LOG.delete(0, PLAY_LOG.length() - KEEP_LOG_LENGTH);
            }
        }
    }

    // ====================================================================
    // 2. 显示日志对话框（子线程解析，防卡顿）
    // ====================================================================
    public static void showLogDialog(Context context) {
        new Thread(() -> {
            final String displayText;
            if (PLAY_LOG == null || PLAY_LOG.length() == 0) {
                displayText = "暂无日志内容，请先播放一个频道再查看。";
            } else {
                // 安全复制当前日志
                String originalLog;
                synchronized (PLAY_LOG) {
                    originalLog = PLAY_LOG.toString();
                }
                String[] lines = originalLog.split("\n");
                
                // 只截取最近 100 行（防止解析大文本卡主线程）
                int start = Math.max(0, lines.length - MAX_DISPLAY_LINES);
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 1; i >= start; i--) {
                    if (!lines[i].trim().isEmpty()) {
                        sb.append(lines[i]).append("\n");
                    }
                }
                displayText = sb.toString();
            }

            // 切回主线程渲染 UI
            new Handler(Looper.getMainLooper()).post(() -> {
                ScrollView scrollView = new ScrollView(context);
                TextView tv = new TextView(context);
                tv.setText(displayText);
                tv.setTextSize(12);
                tv.setPadding(40, 40, 40, 40);
                tv.setTextColor(android.graphics.Color.BLACK);
                scrollView.addView(tv);

                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("📄 解析 & 播放日志");
                builder.setView(scrollView);
                builder.setPositiveButton("关闭", null);
                builder.setNeutralButton("清空日志", (dialog, which) -> {
                    clearPlayLog();
                    Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show();
                });
                builder.show();
            });
        }).start();
    }

    // ====================================================================
    // 3. 清空与获取日志（加锁）
    // ====================================================================
    public static void clearPlayLog() {
        if (PLAY_LOG != null) {
            synchronized (PLAY_LOG) {
                PLAY_LOG.setLength(0);
            }
        }
    }

    public static String getPlayLog() {
        if (PLAY_LOG == null) return "";
        synchronized (PLAY_LOG) {
            return PLAY_LOG.toString();
        }
    }
}
