package com.tv.live;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志辅助类
 * 作用：统一管理 MainActivity 的本地日志列表
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 logList 和 log() 方法抽离到这里，
 * 统一管理本地日志的记录和获取。
 *
 * 【注意】
 * 这个和 SettingsActivity 里的 LogManager 不一样：
 * - LogManager：全局日志系统（操作日志 + 播放日志）
 * - LogHelper：MainActivity 本地的简单日志列表（用于调试）
 */
public class LogHelper {

    private static final String TAG = "MainActivity";

    private static LogHelper instance;

    private final List<String> logList = new ArrayList<>();

    private LogHelper() {
    }

    public static synchronized LogHelper getInstance() {
        if (instance == null) {
            instance = new LogHelper();
        }
        return instance;
    }

    public void log(String msg) {
        logList.add(msg);
        Log.d(TAG, msg);
    }

    public List<String> getLogList() {
        return logList;
    }

    public String getLogString() {
        StringBuilder sb = new StringBuilder();
        for (String line : logList) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    public void clear() {
        logList.clear();
    }

    public int size() {
        return logList.size();
    }
}
