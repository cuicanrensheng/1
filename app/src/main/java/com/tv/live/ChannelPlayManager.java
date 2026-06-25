package com.tv.live;

import android.content.Context;
import android.widget.Toast;

import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.InfoDisplayManager;
import com.tv.live.manager.TVPlayerManager;

import java.util.List;

/**
 * 频道播放管理器
 * 作用：统一管理频道切换、播放记录、信息更新、自动跳过失效频道等逻辑
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 playChannel()、playPrev()、playNext() 等方法
 * 和相关变量抽离到这里，统一管理频道播放的逻辑。
 *
 * 【2026-06-25 合并：AutoSkipManager】
 * 【合并说明】
 * 把 AutoSkipManager 的自动跳过失效频道逻辑合并到这里，减少文件数量。
 * 原来的 AutoSkipManager 是独立的单例，现在直接作为 ChannelPlayManager 的一部分。
 *
 * 【职责分工】
 * - TVPlayerManager：控制播放器本身（播放、暂停、解码器等）
 * - ChannelPlayManager：管理频道切换（切台、记录历史、更新UI、自动跳过失效频道等）
 */
public class ChannelPlayManager {

    private static ChannelPlayManager instance;

    private final Context appContext;

    private List<Channel> channelSourceList;
    private int currentPlayIndex = 0;

    private TVPlayerManager playerManager;
    private InfoDisplayManager infoDisplayManager;
    private AppConfig appConfig;
    private PlayerStateListenerImpl playerStateListener;
    private PictureInPictureManager pipManager;

    private OnChannelPlayListener listener;

    // ====================================================================
    // ✅ 2026-06-25 合并：AutoSkipManager - 自动跳过失效频道相关
    // ====================================================================
    /** 最大连续跳过次数 */
    private static final int MAX_CONSECUTIVE_SKIP = 10;

    /** 连续失效计数 */
    private int consecutiveFailedCount = 0;

    /** 自动跳过监听器 */
    private OnAutoSkipListener autoSkipListener;

    /**
     * 自动跳过监听器接口
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     */
    public interface OnAutoSkipListener {
        void onSkipNext();
    }

    /**
     * 设置自动跳过监听器
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     */
    public void setOnAutoSkipListener(OnAutoSkipListener listener) {
        this.autoSkipListener = listener;
    }

    /**
     * 处理源失效
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
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
        if (autoSkipListener != null) {
            autoSkipListener.onSkipNext();
        }
        return true;
    }

    /**
     * 重置连续失效计数（成功播放时调用）
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     */
    public void resetAutoSkip() {
        consecutiveFailedCount = 0;
    }

    /**
     * 获取当前连续失效次数
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     */
    public int getConsecutiveFailedCount() {
        return consecutiveFailedCount;
    }

    /**
     * 获取最大连续跳过数
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     */
    public int getMaxConsecutiveSkip() {
        return MAX_CONSECUTIVE_SKIP;
    }

    // ====================================================================
    // 原有 ChannelPlayManager 代码
    // ====================================================================

    private ChannelPlayManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized ChannelPlayManager getInstance(Context context) {
        if (instance == null) {
            instance = new ChannelPlayManager(context);
        }
        return instance;
    }

    public interface OnChannelPlayListener {
        void onChannelChanged(Channel channel, int index);
    }

    public void setOnChannelPlayListener(OnChannelPlayListener listener) {
        this.listener = listener;
    }

    public void setChannelSourceList(List<Channel> list) {
        this.channelSourceList = list;
    }

    public List<Channel> getChannelSourceList() {
        return channelSourceList;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public Channel getCurrentChannel() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return null;
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) return null;
        return channelSourceList.get(currentPlayIndex);
    }

    public void setPlayerManager(TVPlayerManager manager) {
        this.playerManager = manager;
    }

    public void setInfoDisplayManager(InfoDisplayManager manager) {
        this.infoDisplayManager = manager;
    }

    public void setAppConfig(AppConfig config) {
        this.appConfig = config;
    }

    public void setPlayerStateListener(PlayerStateListenerImpl listener) {
        this.playerStateListener = listener;
    }

    /**
     * 【2026-06-25 合并：保留 setAutoSkipManager 方法用于向后兼容】
     * 现在 AutoSkipManager 已经合并到 ChannelPlayManager 里了，
     * 这个方法主要是为了不让旧代码报错，实际内部不再使用独立的 AutoSkipManager。
     */
    @Deprecated
    public void setAutoSkipManager(Object manager) {
        // 空实现，向后兼容
    }

    public void setPipManager(PictureInPictureManager manager) {
        this.pipManager = manager;
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    public void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;

        currentPlayIndex = index;

        SettingsActivity.logOperation("【播放】========================================");
        SettingsActivity.logOperation("【播放】频道名称：" + channel.getName());
        SettingsActivity.logOperation("【播放】播放地址：" + channel.getPlayUrl());
        SettingsActivity.logOperation("【播放】当前索引：" + index);
        SettingsActivity.logOperation("【播放】========================================");

        if (playerStateListener != null && channel.getName() != null) {
            playerStateListener.setCurrentChannelName(channel.getName());
        }

        if (appConfig != null) {
            appConfig.setLastPlayIndex(index);
        }

        if (playerManager != null) {
            playerManager.playUrl(channel.getPlayUrl());
        }

        TVPlayerManager.LiveInfo live = null;
        if (playerManager != null) {
            live = playerManager.getLiveInfo();
        }

        if (infoDisplayManager != null) {
            infoDisplayManager.showInfoBar(channel, live);
            infoDisplayManager.showChannelNum(index + 1);
        }

        try {
            if (appConfig != null && channel.getName() != null) {
                appConfig.addRecentChannel(channel.getName());
            }
        } catch (Exception e) {
        }

        // ✅ 2026-06-25 合并：原来调用 autoSkipManager.reset()，现在调用自己的 resetAutoSkip()
        resetAutoSkip();

        if (pipManager != null && pipManager.isInPipMode() && channel != null) {
            try {
                String bitrate = live != null ? live.bitrate : "";
                String channelName = channel.getName() != null ? channel.getName() : "";
                pipManager.updateChannelInfo(index + 1, channelName, bitrate);
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画】同步频道信息失败：" + e.getMessage());
            }
        }

        if (listener != null) {
            listener.onChannelChanged(channel, index);
        }
    }

    public void playPrev() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIndex = currentPlayIndex - 1;
        if (newIndex < 0) {
            newIndex = channelSourceList.size() - 1;
        }
        playChannel(newIndex);
    }

    public void playNext() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIndex = currentPlayIndex + 1;
        if (newIndex >= channelSourceList.size()) {
            newIndex = 0;
        }
        playChannel(newIndex);
    }

    public void updateChannelSourceList(List<Channel> list) {
        if (channelSourceList != null) {
            channelSourceList.clear();
            channelSourceList.addAll(list);
        } else {
            channelSourceList = list;
        }
    }

    public void release() {
        listener = null;
        playerManager = null;
        infoDisplayManager = null;
        appConfig = null;
        playerStateListener = null;
        pipManager = null;
        autoSkipListener = null;
        consecutiveFailedCount = 0;
        if (channelSourceList != null) {
            channelSourceList.clear();
            channelSourceList = null;
        }
    }
}
