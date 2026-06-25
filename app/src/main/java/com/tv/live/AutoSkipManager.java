package com.tv.live;

import android.content.Context;
import android.widget.Toast;

/**
 * 自动跳过失效频道管理器
 * 作用：管理连续失效频道的计数和自动跳过逻辑
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 handleSourceFailed() 方法和相关变量抽离到这里，
 * 统一管理自动跳过失效频道的逻辑。
 */
public class AutoSkipManager {

    private static final int MAX_CONSECUTIVE_SKIP = 10;

    private static AutoSkipManager instance;
    private final Context appContext;

    private int consecutiveFailedCount = 0;
    private OnAutoSkipListener listener;

    private AutoSkipManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static AutoSkipManager getInstance(Context context) {
        if (instance == null) {
            instance = new AutoSkipManager(context);
        }
        return instance;
    }

    public interface OnAutoSkipListener {
        void onSkipNext();
    }

    public void setOnAutoSkipListener(OnAutoSkipListener listener) {
        this.listener = listener;
    }

    /**
     * 处理源失效
     *
     * @param channelName 失效的频道名称
     * @return true=继续跳过下一个，false=已达上限，停止跳过
     */
    public boolean handleSourceFailed(String channelName) {
        consecutiveFailedCount++;

        SettingsActivity.logOperation("【自动切台】频道「" + channelName
                + "」源失效，连续失效第 " + consecutiveFailedCount + " 个");

        if (consecutiveFailedCount >= MAX_CONSECUTIVE_SKIP) {
            SettingsActivity.logOperation("【自动切台】已连续跳过 "
                    + MAX_CONSECUTIVE_SKIP + " 个失效频道，停止自动跳过");
            Toast.makeText(appContext, "已跳过 " + MAX_CONSECUTIVE_SKIP
                    + " 个失效频道，请检查直播源", Toast.LENGTH_SHORT).show();
            return false;
        }

        SettingsActivity.logOperation("【自动切台】自动切换到下一个频道");

        if (listener != null) {
            listener.onSkipNext();
        }

        return true;
    }

    /**
     * 重置连续失效计数（成功播放时调用）
     */
    public void reset() {
        consecutiveFailedCount = 0;
    }

    /**
     * 获取当前连续失效次数
     */
    public int getConsecutiveFailedCount() {
        return consecutiveFailedCount;
    }

    /**
     * 获取最大连续跳过数
     */
    public int getMaxConsecutiveSkip() {
        return MAX_CONSECUTIVE_SKIP;
    }

    /**
     * 释放资源
     */
    public void release() {
        listener = null;
        consecutiveFailedCount = 0;
    }
}
