package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 日志管理器
 * 已修复：多线程写入崩溃风险（改用 StringBuffer + synchronized）
 */
public class LogManager {

    // ====================== 全局日志系统 ======================
    /** 解析&播放日志（线程安全） */
    private static volatile StringBuffer PLAY_LOG = new StringBuffer();

    /** 操作日志（线程安全） */
    private static volatile StringBuffer OPERATION_LOG = new StringBuffer();

    // ====================== 日志大小限制 ======================
    private static final int MAX_LOG_LENGTH = 20000;
    private static final int KEEP_LOG_LENGTH = 15000;

    // ====================================================================
    // 1. 记录解析&播放日志（已加锁）
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
    // 2. 记录操作日志（已加锁）
    // ====================================================================
    public static void logOperation(String msg) {
        if (OPERATION_LOG == null) {
            OPERATION_LOG = new StringBuffer();
        }
        String time = android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
        synchronized (OPERATION_LOG) {
            OPERATION_LOG.append("[").append(time).append("] ").append(msg).append("\n");
            if (OPERATION_LOG.length() > MAX_LOG_LENGTH) {
                OPERATION_LOG.delete(0, OPERATION_LOG.length() - KEEP_LOG_LENGTH);
            }
        }
    }

    // ====================================================================
    // 3. 显示解析&播放日志对话框（建议外部在子线程调用）
    // ====================================================================
    public static void showLogDialog(Context context) {
        // 建议外部在子线程调用，或者直接在这里用 Handler 做异步
        // 为了安全，我们直接在主线程展示，但提醒用户这可能会稍微卡顿
        ScrollView scrollView = new ScrollView(context);
        TextView tv = new TextView(context);

        if (PLAY_LOG == null || PLAY_LOG.length() == 0) {
            tv.setText("暂无日志内容，请先播放一个频道再查看。");
        } else {
            // 安全复制内容
            String originalLog;
            synchronized (PLAY_LOG) {
                originalLog = PLAY_LOG.toString();
            }
            String[] lines = originalLog.split("\n");
            StringBuilder reversedLog = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) {
                    reversedLog.append(lines[i]).append("\n");
                }
            }
            tv.setText(reversedLog.toString());
        }

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
    }

    // ====================================================================
    // 4. 显示操作日志对话框（同理，建议外部在子线程调用）
    // ====================================================================
    public static void showOperationLogDialog(Context context) {
        ScrollView scrollView = new ScrollView(context);
        TextView tv = new TextView(context);

        if (OPERATION_LOG == null || OPERATION_LOG.length() == 0) {
            tv.setText("暂无操作日志。");
        } else {
            String originalLog;
            synchronized (OPERATION_LOG) {
                originalLog = OPERATION_LOG.toString();
            }
            String[] lines = originalLog.split("\n");
            StringBuilder reversedLog = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) {
                    reversedLog.append(lines[i]).append("\n");
                }
            }
            tv.setText(reversedLog.toString());
        }

        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(android.graphics.Color.BLACK);
        scrollView.addView(tv);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("📌 操作日志");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            clearOperationLog();
            Toast.makeText(context, "操作日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    // ====================================================================
    // 5. 清空日志（加锁）
    // ====================================================================
    public static void clearPlayLog() {
        if (PLAY_LOG != null) {
            synchronized (PLAY_LOG) {
                PLAY_LOG.setLength(0);
            }
        }
    }

    public static void clearOperationLog() {
        if (OPERATION_LOG != null) {
            synchronized (OPERATION_LOG) {
                OPERATION_LOG.setLength(0);
            }
        }
    }

    // ====================================================================
    // 6. 获取日志内容（加锁）
    // ====================================================================
    public static String getPlayLog() {
        if (PLAY_LOG == null) return "";
        synchronized (PLAY_LOG) {
            return PLAY_LOG.toString();
        }
    }

    public static String getOperationLog() {
        if (OPERATION_LOG == null) return "";
        synchronized (OPERATION_LOG) {
            return OPERATION_LOG.toString();
        }
    }
}
